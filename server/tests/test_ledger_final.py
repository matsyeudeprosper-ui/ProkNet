"""v0.18.0 final: refunds, reversals, identity recovery, top-up caps, reconciliation,
hold-zero, and the migration from a real schema-5 Brain."""
import os
import sqlite3
import tempfile
import unittest

from brain import ledger
from brain.ledger import (APPROVED, CANCELLED, DENIED, IN_SESSION, Ledger, LedgerError, MATCHED, NEEDS_REVIEW,
                          PAID, REFUND, RELEASED, REQUESTED, SENT, UNASSIGNED, WITHDRAWAL)
from tests.test_ledger import BUYER, RELAY, SELLER, STRANGER, T0, TREASURER, derived, fresh

NUM = "066123456"
NUM_H = ledger.msisdn_hash(NUM)


def live(test_ids=(BUYER, STRANGER)):
    L = fresh(payments_live=True, test_ids=test_ids)
    return L


def topup(L, customer, amount, sender=NUM, sms="sms-1", t=T0, rail="MTN"):
    i = L.create_intent(customer, rail, amount, t)
    return L.observe_credit(TREASURER, rail, ledger.msisdn_hash(sender), i["send_exactly"], sms, t + 1), i


class RefundTest(unittest.TestCase):

    def test_a_refund_goes_only_to_a_number_the_customer_paid_from(self):
        L = live()
        out, i = topup(L, BUYER, 100_000)
        self.assertEqual(MATCHED, out["state"])
        credit = L.balance("credit:" + BUYER)
        # a number never seen: refused
        with self.assertRaises(LedgerError) as cm:
            L.request_refund(BUYER, "MTN", "055987654", 50_000, T0 + 2)
        self.assertEqual(ledger.NOT_ORIGIN_NUMBER, cm.exception.reason)
        # the bound number on the other rail: refused too (the binding is per rail)
        with self.assertRaises(LedgerError):
            L.request_refund(BUYER, "AIRTEL", NUM, 50_000, T0 + 2)
        w = L.request_refund(BUYER, "MTN", NUM, 50_000, T0 + 2)["withdrawal"]
        self.assertEqual(REFUND, w["kind"])
        self.assertEqual(REQUESTED, w["state"])
        self.assertEqual(credit - 50_000, L.balance("credit:" + BUYER), "reserved at once")
        self.assertEqual(50_000, L.balance("inflight:withdrawals"))
        # cannot spend what is reserved
        with self.assertRaises(LedgerError):
            L.hold(BUYER, SELLER, credit, T0 + 3)
        # the same queue, the same treasurer, the same states
        q = L.queue(TREASURER, T0 + 3)
        self.assertEqual(1, q["summary"]["manual_sends_pending"])
        self.assertEqual(NUM, q["rows"][0]["msisdn"])
        L.treasury_withdrawal(TREASURER, w["id"], "approve", T0 + 4)
        L.treasury_withdrawal(TREASURER, w["id"], "sent", T0 + 5)
        self.assertEqual("one", L.observe_debit(TREASURER, "MTN", NUM_H, 50_000, "debit-1", T0 + 6)["matched"])
        self.assertEqual(PAID, L._w(w["id"])["state"])
        self.assertEqual(0, L.balance("inflight:withdrawals"))
        self.assertEqual(0, L.net_of_all_accounts())

    def test_a_denied_or_cancelled_refund_returns_to_credit_not_earnings(self):
        L = live()
        topup(L, BUYER, 100_000)
        credit = L.balance("credit:" + BUYER)
        w = L.request_refund(BUYER, "MTN", NUM, 50_000, T0 + 2)["withdrawal"]
        L.cancel_withdrawal(BUYER, w["id"], T0 + 3)
        self.assertEqual(credit, L.balance("credit:" + BUYER))
        self.assertEqual(0, L.balance("earned:" + BUYER))
        w2 = L.request_refund(BUYER, "MTN", NUM, 50_000, T0 + 4)["withdrawal"]
        L.treasury_withdrawal(TREASURER, w2["id"], "deny", T0 + 5, memo="number closed")
        self.assertEqual(credit, L.balance("credit:" + BUYER))
        self.assertEqual(DENIED, L._w(w2["id"])["state"])

    def test_no_refund_while_a_session_holds_the_credit(self):
        L = live()
        topup(L, BUYER, 100_000)
        L.hold(BUYER, SELLER, 10_000, T0 + 2)
        with self.assertRaises(LedgerError) as cm:
            L.request_refund(BUYER, "MTN", NUM, 50_000, T0 + 3)
        self.assertEqual(ledger.HOLD_EXISTS, cm.exception.reason)


class ReversalTest(unittest.TestCase):

    def test_a_posting_is_reversed_once_with_a_memo_and_nothing_is_deleted(self):
        L = fresh()
        L.test_credit(TREASURER, BUYER, 5_000, T0)
        pid = L.db.execute("SELECT id FROM ledger_postings WHERE kind='ADJUSTMENT'").fetchone()["id"]
        with self.assertRaises(LedgerError):
            L.reverse_posting(TREASURER, pid, "   ", T0 + 1)
        with self.assertRaises(LedgerError) as cm:
            L.reverse_posting(SELLER, pid, "oops", T0 + 1)
        self.assertEqual(403, cm.exception.code)
        out = L.reverse_posting(TREASURER, pid, "posted to the wrong phone", T0 + 1)
        self.assertEqual(5_000, out["amount"])
        self.assertEqual(0, L.balance("credit:" + BUYER))
        self.assertEqual(2, L.db.execute("SELECT COUNT(*) AS n FROM ledger_postings").fetchone()["n"], "the original stays")
        with self.assertRaises(LedgerError) as cm:
            L.reverse_posting(TREASURER, pid, "again", T0 + 2)
        self.assertEqual(409, cm.exception.code)
        with self.assertRaises(LedgerError):
            L.reverse_posting(TREASURER, out["reversal_id"], "undo the undo", T0 + 3)
        self.assertEqual(1, L.db.execute("SELECT COUNT(*) AS n FROM ledger_audit WHERE action='treasury.reverse' AND allowed=1").fetchone()["n"])
        self.assertEqual(0, L.net_of_all_accounts())


class IdentityRecoveryTest(unittest.TestCase):

    def test_a_reinstalled_phone_proves_its_number_and_the_balances_move_not_duplicate(self):
        L = live(test_ids=(BUYER, STRANGER))
        topup(L, BUYER, 100_000, sms="sms-old")                 # old identity bound to NUM
        L.post_settlement(derived(gross=20_000, buyer=BUYER, seller=SELLER), T0 + 2)
        old_credit = L.balance("credit:" + BUYER)
        # the new identity (STRANGER) sends a tagged top-up FROM THE SAME NUMBER
        out, i = topup(L, STRANGER, 10_000, sms="sms-new", t=T0 + 10)
        self.assertEqual(NEEDS_REVIEW, out["state"])
        self.assertEqual(ledger.REBIND_CANDIDATE, out["reason"])
        self.assertEqual(0, L.balance("credit:" + STRANGER), "nothing credited on the message alone")
        self.assertEqual(i["send_exactly"], L.balance(ledger.UNASSIGNED_ACCOUNT))
        items = L.review_list(TREASURER, T0 + 11)
        self.assertEqual("rebind:" + BUYER, items[0]["claim_ref"])
        L.review(TREASURER, out["topup_id"], True, T0 + 12)
        self.assertEqual(0, L.balance("credit:" + BUYER), "the old identity is empty")
        self.assertEqual(old_credit + i["send_exactly"], L.balance("credit:" + STRANGER), "moved plus the new top-up, never doubled")
        self.assertEqual(STRANGER, L.db.execute("SELECT customer_id FROM ledger_bindings WHERE sender_hash=?", (NUM_H,)).fetchone()["customer_id"])
        self.assertEqual(0, L.net_of_all_accounts())

    def test_move_identity_by_hand_is_refused_while_the_old_one_is_busy(self):
        L = fresh()
        L.test_credit(TREASURER, BUYER, 50_000, T0)
        L.hold(BUYER, SELLER, 10_000, T0 + 1)
        with self.assertRaises(LedgerError) as cm:
            L.move_identity(TREASURER, BUYER, STRANGER, "same person, new phone", T0 + 2)
        self.assertEqual(ledger.IDENTITY_BUSY, cm.exception.reason)
        with self.assertRaises(LedgerError):
            L.move_identity(TREASURER, BUYER, STRANGER, "", T0 + 2)
        with self.assertRaises(LedgerError):
            L.move_identity(SELLER, BUYER, STRANGER, "x", T0 + 2)

    def test_a_provider_s_earnings_and_a_debt_follow_the_person(self):
        L = fresh()
        L.test_credit(TREASURER, BUYER, 100, T0)
        L.post_settlement(derived(gross=100_000), T0)      # seller earns; buyer goes negative
        self.assertLess(L.balance("credit:" + BUYER), 0)
        earned = L.balance("earned:" + SELLER)
        out = L.move_identity(TREASURER, SELLER, RELAY, "proved by number", T0 + 1)
        self.assertEqual({"earned": earned}, out["moved"])
        self.assertEqual(earned, L.balance("earned:" + RELAY))
        out = L.move_identity(TREASURER, BUYER, STRANGER, "proved by number", T0 + 2)
        self.assertLess(out["moved"]["credit"], 0)
        self.assertEqual(0, L.balance("credit:" + BUYER))
        self.assertLess(L.balance("credit:" + STRANGER), 0)
        self.assertEqual(0, L.net_of_all_accounts())


class TopupGuardsTest(unittest.TestCase):

    def test_a_large_matched_topup_waits_for_a_person(self):
        L = live()
        out, i = topup(L, BUYER, ledger.TOPUP_MAX_AUTO_CENTIMES + 100)
        self.assertEqual(NEEDS_REVIEW, out["state"])
        self.assertEqual(ledger.ABOVE_AUTO_LIMIT, out["reason"])
        self.assertEqual(0, L.balance("credit:" + BUYER))
        L.review(TREASURER, out["topup_id"], True, T0 + 5)
        self.assertEqual(i["send_exactly"], L.balance("credit:" + BUYER))

    def test_the_treasury_number_is_shown_only_while_payments_are_live(self):
        db = sqlite3.connect(":memory:", check_same_thread=False)
        off = Ledger(db, treasury_ids=(TREASURER,), test_ids=(BUYER,), payments_live=False, treasury_msisdn={"MTN": "+242 06 000 00 00"})
        self.assertEqual("", off.create_intent(BUYER, "MTN", 1_000, T0)["pay_to"])
        on = Ledger(sqlite3.connect(":memory:", check_same_thread=False), treasury_ids=(TREASURER,), payments_live=True, treasury_msisdn={"MTN": "+242 06 000 00 00"})
        self.assertEqual("060000000", on.create_intent(BUYER, "MTN", 1_000, T0)["pay_to"])
        self.assertEqual("", on.create_intent(BUYER, "AIRTEL", 1_000, T0 + 1)["pay_to"], "no Airtel number configured")


class ReconcileTest(unittest.TestCase):

    def test_a_typed_balance_below_the_ledger_blocks_approvals_until_it_is_explained(self):
        L = live()
        topup(L, BUYER, 100_000)                                 # float MTN = tagged amount
        expected = L.balance("float:mtn")
        L.post_settlement(derived(gross=60_000), T0 + 2)
        w = L.request_withdrawal(SELLER, "MTN", "055987654", 50_000, T0 + 3)["withdrawal"]
        r = L.reconcile(TREASURER, T0 + 3)
        self.assertFalse(r["alert"])
        self.assertTrue(r["rails"]["MTN"]["check_stale"], "nobody typed a balance yet")
        # the treasurer types LESS than the ledger expects: the parsed message is now in doubt
        L.balance_check(TREASURER, "MTN", expected - 5_000, T0 + 4)
        r = L.reconcile(TREASURER, T0 + 4)
        self.assertTrue(r["rails"]["MTN"]["doubt"])
        self.assertTrue(r["alert"]); self.assertTrue(r["approvals_blocked"])
        with self.assertRaises(LedgerError) as cm:
            L.treasury_withdrawal(TREASURER, w["id"], "approve", T0 + 5)
        self.assertEqual(ledger.RECONCILIATION_ALERT, cm.exception.reason)
        # a later check that matches clears it
        L.balance_check(TREASURER, "MTN", expected, T0 + 6)
        self.assertFalse(L.reconcile(TREASURER, T0 + 6)["alert"])
        self.assertEqual(APPROVED, L.treasury_withdrawal(TREASURER, w["id"], "approve", T0 + 7)["withdrawal"]["state"])

    def test_test_credit_is_cover_not_a_hole(self):
        L = fresh()
        L.test_credit(TREASURER, BUYER, 100_000, T0)
        L.post_settlement(derived(gross=60_000), T0 + 1)
        r = L.reconcile(TREASURER, T0 + 2)
        self.assertEqual(0, r["shortfall"])
        self.assertFalse(r["approvals_blocked"])
        w = L.request_withdrawal(SELLER, "MTN", NUM, 50_000, T0 + 3)["withdrawal"]
        self.assertEqual(APPROVED, L.treasury_withdrawal(TREASURER, w["id"], "approve", T0 + 4)["withdrawal"]["state"])

    def test_an_unmatched_debit_shows_per_rail(self):
        L = live()
        L.observe_debit(TREASURER, "AIRTEL", ledger.msisdn_hash("055000000"), 700, "d1", T0)
        r = L.reconcile(TREASURER, T0 + 1)
        self.assertEqual(1, r["rails"]["AIRTEL"]["unmatched_debits"])
        self.assertEqual(700, r["rails"]["AIRTEL"]["unmatched_debits_centimes"])
        self.assertEqual(0, r["rails"]["MTN"]["unmatched_debits"])


class HoldZeroTest(unittest.TestCase):

    def test_the_seller_may_release_a_session_that_settled_at_zero(self):
        L = fresh()
        L.test_credit(TREASURER, BUYER, 5_000, T0)
        h = L.hold(BUYER, SELLER, 2_000, T0)["hold_id"]
        L.hold_started(h, SELLER, "0102030405060708", T0 + 1)
        with self.assertRaises(LedgerError) as cm:
            L.hold_settled_zero(h, STRANGER, T0 + 2)
        self.assertEqual(403, cm.exception.code)
        out = L.hold_settled_zero(h, SELLER, T0 + 2)
        self.assertEqual(RELEASED, out["state"])
        self.assertEqual(5_000, L.balance("credit:" + BUYER))
        with self.assertRaises(LedgerError):
            L.hold_settled_zero(h, SELLER, T0 + 3)


class SchemaSixUpgradeTest(unittest.TestCase):
    """A pilot Brain that ran v0.18.0 build 80 (schema 5, with rows) upgrades to 6."""

    def test_schema_five_becomes_six_and_every_row_survives(self):
        from brain import db as braindb
        path = os.path.join(tempfile.mkdtemp(), "brain.db")
        con = sqlite3.connect(path)
        for sql in braindb.MIGRATIONS[:5]:
            con.executescript(sql)
        for v in range(1, 6):
            con.execute("INSERT INTO schema_version(version) VALUES (?)", (v,))
        con.commit()
        # rows a build-80 Brain would hold, written with the build-80 column set (no `kind`)
        con.execute("INSERT INTO ledger_postings(id, ts, kind, debit_account, credit_account, amount, ref, memo, actor) VALUES('p1', 1, 'ADJUSTMENT', 'prok:testcredit', 'credit:aa', 5000, '', 'test', 't')")
        con.execute("INSERT INTO ledger_withdrawals(id, payee_id, rail, msisdn_hash, msisdn, amount, state, requested_at, updated_at) VALUES('w1', 'bb', 'MTN', 'h', '066123456', 50000, 'SENT', 1, 2)")
        con.execute("INSERT INTO ledger_holds(id, customer_id, seller_id, amount, state, created_at, last_keepalive, updated_at) VALUES('h1', 'aa', 'bb', 100, 'IN_SESSION', 1, 1, 1)")
        con.commit()
        before = {t: [tuple(r) for r in con.execute("SELECT * FROM %s" % t)] for t in ("ledger_postings", "ledger_holds")}
        con.close()
        b = braindb.Brain(path)
        self.assertEqual(6, b.schema_version())
        cols = [r[1] for r in b.db.execute("PRAGMA table_info(ledger_withdrawals)")]
        self.assertIn("kind", cols)
        self.assertEqual("kind", cols[-1], "ALTER appends; a fresh CREATE must not put it elsewhere")
        row = b.db.execute("SELECT kind, state, msisdn FROM ledger_withdrawals WHERE id='w1'").fetchone()
        self.assertEqual(("WITHDRAWAL", "SENT", "066123456"), tuple(row))
        for t, rows in before.items():
            self.assertEqual(rows, [tuple(r) for r in b.db.execute("SELECT * FROM %s" % t)], t)
        # and the ledger works on the upgraded file
        L = Ledger(b.db, treasury_ids=(TREASURER,))
        self.assertEqual(1, L.queue(TREASURER, 3)["summary"]["sent_unconfirmed"])
        b.db.close()

    def test_a_fresh_brain_has_the_same_column_order_as_an_upgraded_one(self):
        from brain import db as braindb
        fresh_path = os.path.join(tempfile.mkdtemp(), "fresh.db")
        b = braindb.Brain(fresh_path)
        self.assertEqual(6, b.schema_version())
        cols = [r[1] for r in b.db.execute("PRAGMA table_info(ledger_withdrawals)")]
        self.assertEqual("kind", cols[-1])
        b.db.close()


if __name__ == "__main__":
    unittest.main()
