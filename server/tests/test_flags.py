"""v0.19.0: feature flags are dated rows per city and function; restricted functions need a
decision record before they can be on; cohort functions need the identity listed."""
import sqlite3
import unittest

from brain import flags as F

T0 = 1_700_000_000_000
OP = "op" * 16
USER = "aa" * 16
CITY = "Brazzaville"


def fresh():
    return F.Flags(sqlite3.connect(":memory:", check_same_thread=False), operator_ids=(OP,))


class FlagsTest(unittest.TestCase):

    def test_everything_is_off_until_an_operator_switches_it_on_with_a_reason(self):
        f = fresh()
        for fn in F.FUNCTIONS:
            self.assertFalse(f.enabled(fn, CITY, USER, T0))
        with self.assertRaises(F.FlagsError) as cm:
            f.set_flag(USER, "public_map", CITY, True, "x", T0)
        self.assertEqual(403, cm.exception.code)
        with self.assertRaises(F.FlagsError):
            f.set_flag(OP, "public_map", CITY, True, "  ", T0)
        f.set_flag(OP, "public_map", CITY, True, "launch day", T0)
        self.assertTrue(f.enabled("public_map", CITY, USER, T0))
        self.assertFalse(f.enabled("public_map", "Pointe-Noire", USER, T0), "per city")
        st = f.status(CITY, USER, T0)
        self.assertTrue(st["functions"]["public_map"]["enabled"])
        self.assertEqual("", st["functions"]["public_map"]["off_sentence"])
        self.assertTrue(st["functions"]["market_browse"]["off_sentence"].startswith("Prok Market"))

    def test_a_switch_is_a_dated_row_and_the_future_one_does_not_apply_yet(self):
        f = fresh()
        f.set_flag(OP, "public_map", CITY, True, "on", T0)
        f.set_flag(OP, "public_map", CITY, False, "pause tomorrow", T0 + 1, effective_from=T0 + 86_400_000)
        self.assertTrue(f.enabled("public_map", CITY, USER, T0 + 3_600_000))
        self.assertFalse(f.enabled("public_map", CITY, USER, T0 + 86_400_000))
        self.assertEqual(2, len(f.console(OP, CITY, T0 + 2)["history"]))

    def test_a_cohort_function_needs_the_identity_listed(self):
        f = fresh()
        f.record_decision(OP, "merchant_collection_terms", CITY, "terms reviewed", "doc-1.pdf", "Mike", T0 - 1, T0)
        f.set_flag(OP, "market_paid_publish", CITY, True, "first sellers", T0)
        self.assertFalse(f.enabled("market_paid_publish", CITY, USER, T0), "on, but not for this identity")
        f.add_to_cohort(OP, "market_paid_publish", CITY, USER, T0)
        self.assertTrue(f.enabled("market_paid_publish", CITY, USER, T0))
        f.remove_from_cohort(OP, "market_paid_publish", CITY, USER, T0 + 1)
        self.assertFalse(f.enabled("market_paid_publish", CITY, USER, T0 + 1))
        with self.assertRaises(F.FlagsError):
            f.add_to_cohort(OP, "public_map", CITY, USER, T0)

    def test_a_restricted_function_cannot_be_switched_on_without_its_decision_records(self):
        f = fresh()
        with self.assertRaises(F.FlagsError) as cm:
            f.set_flag(OP, "customer_paid_delivery", CITY, True, "try", T0)
        self.assertEqual("missing_decision", cm.exception.reason)
        self.assertEqual(["payment_classification", "upstream_permission"], f.missing_decisions("customer_paid_delivery", CITY, T0))
        f.record_decision(OP, "payment_classification", CITY, "counsel letter: prepaid credit is a service prepayment", "counsel-2026-10-01.pdf", "Mike", T0 - 5, T0)
        with self.assertRaises(F.FlagsError):
            f.set_flag(OP, "customer_paid_delivery", CITY, True, "try", T0)
        d = f.record_decision(OP, "upstream_permission", CITY, "venue X written permission", "venue-x-terms.pdf", "Mike", T0 - 5, T0)
        f.set_flag(OP, "customer_paid_delivery", CITY, True, "pilot cohort", T0)
        f.add_to_cohort(OP, "customer_paid_delivery", CITY, USER, T0)
        self.assertTrue(f.enabled("customer_paid_delivery", CITY, USER, T0))
        # revoking a decision switches the function off again without touching the flag row
        f.revoke_decision(OP, d["decision_id"], "permission withdrawn", T0 + 1)
        self.assertFalse(f.enabled("customer_paid_delivery", CITY, USER, T0 + 1))
        self.assertEqual(["upstream_permission"], f.status(CITY, USER, T0 + 1)["functions"]["customer_paid_delivery"]["missing_decisions"])

    def test_a_decision_needs_document_person_and_date(self):
        f = fresh()
        with self.assertRaises(F.FlagsError):
            f.record_decision(OP, "arpce", CITY, "ok", "", "Mike", T0, T0)
        with self.assertRaises(F.FlagsError):
            f.record_decision(OP, "arpce", CITY, "ok", "doc", "", T0, T0)
        with self.assertRaises(F.FlagsError):
            f.record_decision(OP, "nonsense", CITY, "ok", "doc", "Mike", T0, T0)
        with self.assertRaises(F.FlagsError) as cm:
            f.record_decision(USER, "arpce", CITY, "ok", "doc", "Mike", T0, T0)
        self.assertEqual(403, cm.exception.code)
        self.assertEqual(1, f.db.execute("SELECT COUNT(*) FROM flag_audit WHERE allowed=0").fetchone()[0])

    def test_dispatch(self):
        f = fresh()
        code, out = f.handle_get(USER, "/v1/flags/status", {"city": CITY}, T0)
        self.assertEqual(200, code)
        self.assertIn("public_map", out["functions"])
        code, out = f.handle_post(OP, "/v1/flags/set", {"function": "public_map", "city": CITY, "enabled": True, "reason": "go"}, T0)
        self.assertEqual(200, code)
        self.assertTrue(f.enabled("public_map", CITY, USER, T0))
        self.assertEqual(404, f.handle_get(USER, "/v1/flags/nope", {}, T0)[0])


class SchemaSevenUpgradeTest(unittest.TestCase):
    """A pilot Brain on schema 6 with ledger rows upgrades to 7 (all new tables) and keeps every row."""

    def test_schema_six_becomes_seven_and_nothing_moves(self):
        import os, tempfile
        from brain import db as braindb
        path = os.path.join(tempfile.mkdtemp(), "brain.db")
        con = sqlite3.connect(path)
        for sql in braindb.MIGRATIONS[:6]:
            con.executescript(sql)
        for v in range(1, 7):
            con.execute("INSERT INTO schema_version(version) VALUES (?)", (v,))
        con.execute("INSERT INTO ledger_postings(id, ts, kind, debit_account, credit_account, amount, ref, memo, actor) VALUES('p1', 1, 'ADJUSTMENT', 'prok:testcredit', 'credit:aa', 5000, '', 'test', 't')")
        con.execute("INSERT INTO ledger_withdrawals(id, payee_id, rail, msisdn_hash, msisdn, amount, state, requested_at, updated_at, kind) VALUES('w1', 'bb', 'MTN', 'h', '066123456', 100000, 'SENT', 1, 2, 'WITHDRAWAL')")
        con.commit()
        before = {t: [tuple(r) for r in con.execute("SELECT * FROM %s" % t)] for t in ("ledger_postings", "ledger_withdrawals")}
        con.close()
        b = braindb.Brain(path)
        self.assertEqual(7, b.schema_version())
        names = set(r[0] for r in b.db.execute("SELECT name FROM sqlite_master WHERE type='table'"))
        for t in ("flag_rows", "flag_decisions", "place_venues", "market_listings", "market_invoices", "quote_config", "fund_campaigns", "relay_offers"):
            self.assertIn(t, names, t)
        for t, rows in before.items():
            self.assertEqual(rows, [tuple(r) for r in b.db.execute("SELECT * FROM %s" % t)], t)
        # twice does nothing the second time
        b.db.close()
        b2 = braindb.Brain(path)
        self.assertEqual(7, b2.schema_version())
        self.assertEqual(7, b2.db.execute("SELECT COUNT(*) FROM schema_version").fetchone()[0])
        b2.db.close()


if __name__ == "__main__":
    unittest.main()
