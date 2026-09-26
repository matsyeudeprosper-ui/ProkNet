"""v0.19.0: the quote engine and the versioned rate configuration.

The rules under test: every launch amount is exactly §10.3 / §6; a rate change is a dated
row that never touches a quote already signed; no lossy quote is ever returned; the free
source cases are decided the way §6 decides them; the sponsor's money is reserved before
the quote exists; the bundle comparison says one honest word per amount and never a
universal savings claim; the cross-language fixture holds the §10.3 examples.
"""
import json
import os
import sqlite3
import unittest

from brain import quotes
from brain.quotes import (APPROVED_PAID, CHEAPER, COMPARABLE, CUSTOMER, FREE, GB, MB, MOBILE_DATA, NONE, NOT_CHEAPER,
                          Quote, QuoteError, RateConfig, SPONSOR, money)

T0 = 1_700_000_000_000
H = 3_600_000
SECRET = b"test-secret-not-for-production"
OWNER = "aa" * 16
BUYER = "bb" * 16


class ReserveStub:
    """Stands in for Fund.reserve: records calls; refuses when told to."""

    def __init__(self, ok=True):
        self.calls = []
        self.ok = ok

    def __call__(self, purpose, ref, amount, campaign_id, now):
        self.calls.append((purpose, ref, amount, campaign_id, now))
        if not self.ok:
            raise RuntimeError("budget_exhausted")
        return {"ok": True, "ref": ref}


def fresh(reserve=None):
    db = sqlite3.connect(":memory:")
    cfg = RateConfig(db)
    return cfg, Quote(db, cfg, SECRET, fund_reserve=reserve)


class DefaultsTest(unittest.TestCase):

    def test_every_default_is_exactly_the_contract(self):
        cfg, _ = fresh()
        c = cfg.current(T0)
        self.assertEqual(25, c[quotes.K_CAP], "0.25 FCFA per decimal MB = 250 FCFA/GB")
        self.assertEqual({"source": 80, "platform": 10, "reserve": 10}, c[quotes.K_SPLIT_DIRECT])
        self.assertEqual({"source": 64, "relay": 20, "platform": 10, "reserve": 6}, c[quotes.K_SPLIT_RELAY])
        self.assertEqual(1_000 * 100, c[quotes.K_WITHDRAW_MIN])
        self.assertEqual(20 * 100, c[quotes.K_BLOCK_REWARD])
        self.assertEqual(30, c[quotes.K_BLOCK_MINUTES])
        self.assertEqual(4, c[quotes.K_BLOCKS_PER_DAY])
        self.assertEqual(100 * 100, c[quotes.K_MOVE_REWARD])
        self.assertEqual(50, c[quotes.K_MOVE_CANCEL_PCT])
        self.assertEqual(40, c[quotes.K_FUND_PCT])
        self.assertEqual(10 * 100, c[quotes.K_SCOUT_FIRST])
        self.assertEqual(3 * 100, c[quotes.K_SCOUT_REFRESH])
        self.assertEqual(30 * 100, c[quotes.K_SCOUT_DAY])
        self.assertEqual(100 * 100, c[quotes.K_SCOUT_MONTH])
        self.assertEqual(5_000 * 100, c[quotes.K_SCOUT_GLOBAL])
        self.assertEqual(90, c[quotes.K_SCOUT_EXPIRY])
        self.assertEqual(10 * 60_000, c[quotes.K_QUOTE_TTL])
        self.assertEqual(MB, 1_000_000, "decimal MB, as Market.MB on the phones")
        offers = {(o["operator"], o["bytes"]): o["price_centimes"] for o in c[quotes.K_BUNDLES]["offers"]}
        self.assertEqual(350 * 100, offers[("MTN", 1 * GB)])
        self.assertEqual(400 * 100, offers[("MTN", 1_500 * MB)])
        self.assertEqual(650 * 100, offers[("MTN", 6 * GB)])
        self.assertEqual(150 * 100, offers[("AIRTEL", 110 * MB)])
        self.assertEqual(250 * 100, offers[("AIRTEL", 450 * MB)])
        self.assertEqual("2026-09-26", c[quotes.K_BUNDLES]["as_of"])

    def test_the_contract_examples_at_the_default_rate(self):
        self.assertEqual(5 * 100, money(25, 20 * MB))
        self.assertEqual(50 * 100, money(25, 200 * MB))
        self.assertEqual(250 * 100, money(25, 1 * GB))
        self.assertEqual(1_500 * 100, money(25, 6 * GB))
        self.assertEqual(375 * 100, money(25, 1_500 * MB))
        # rounding is on the total, half up, once
        self.assertEqual(0, money(25, 19_999))
        self.assertEqual(1, money(25, 20_000))

    def test_defaults_are_dated_rows_with_a_change_log(self):
        cfg, _ = fresh()
        h = cfg.history(quotes.K_CAP)
        self.assertEqual(1, len(h))
        self.assertEqual(quotes.DEFAULTS_SET_BY, h[0]["set_by"])
        self.assertEqual(0, h[0]["effective_from"])
        self.assertIn("2026-09-26", h[0]["reason"])


class VersioningTest(unittest.TestCase):

    def test_a_dated_change_does_not_alter_an_earlier_quote(self):
        cfg, Q = fresh()
        q1 = Q.quote(APPROVED_PAID, 10, None, False, None, 1 * GB, T0)
        self.assertEqual(25_000, q1["customer_total"])
        self.assertTrue(Q.verify(q1))
        cfg.set(quotes.K_CAP, 30, T0 + H, "operator", "pilot economics review", T0 + 1)
        # before it comes into force: same version, same numbers
        q_before = Q.quote(APPROVED_PAID, 10, None, False, None, 1 * GB, T0 + H // 2)
        self.assertEqual(q1["config_version"], q_before["config_version"])
        self.assertEqual(25_000, q_before["customer_total"])
        # after: a different version, different numbers
        q2 = Q.quote(APPROVED_PAID, 10, None, False, None, 1 * GB, T0 + 2 * H)
        self.assertNotEqual(q1["config_version"], q2["config_version"])
        self.assertEqual(30_000, q2["customer_total"])
        # the earlier quote still verifies as signed, carrying its own version
        self.assertTrue(Q.verify(q1))
        self.assertTrue(Q.verify(Q.get(q1["quote_id"])))
        self.assertEqual(25_000, Q.get(q1["quote_id"])["customer_total"])
        h = cfg.history(quotes.K_CAP)
        self.assertEqual(2, len(h))
        self.assertEqual("operator", h[1]["set_by"])
        self.assertEqual("pilot economics review", h[1]["reason"])
        self.assertEqual(T0 + H, h[1]["effective_from"])

    def test_a_change_cannot_be_backdated_unknown_or_unexplained(self):
        cfg, _ = fresh()
        with self.assertRaises(QuoteError) as cm:
            cfg.set(quotes.K_CAP, 30, T0 - 1, "operator", "why", T0)
        self.assertEqual(quotes.BACKDATED, cm.exception.reason)
        with self.assertRaises(QuoteError) as cm:
            cfg.set("nonsense", 30, T0, "operator", "why", T0)
        self.assertEqual(quotes.UNKNOWN_SETTING, cm.exception.reason)
        with self.assertRaises(QuoteError):
            cfg.set(quotes.K_CAP, 30, T0, "operator", "   ", T0)
        self.assertEqual(25, cfg.get(quotes.K_CAP, T0 + H), "nothing changed")


class QuoteRulesTest(unittest.TestCase):

    def test_direct_paid_split_is_80_10_10_of_the_customer_amount(self):
        _, Q = fresh()
        q = Q.quote(APPROVED_PAID, 10, None, False, None, 1 * GB, T0, actor=OWNER)
        self.assertEqual(25, q["rate_centimes_per_mb"])
        self.assertEqual(CUSTOMER, q["paying_party"])
        self.assertEqual((25_000, 20_000, 0, 2_500, 2_500), (q["customer_total"], q["source_share"], q["relay_share"], q["platform_share"], q["reserve_share"]))
        self.assertEqual(q["customer_total"], q["source_share"] + q["relay_share"] + q["platform_share"] + q["reserve_share"])
        self.assertEqual(0, q["movement_fee"])
        self.assertEqual(T0 + 10 * 60_000, q["expires_at"])
        self.assertEqual([5_000, 25_000], [e["example_total"] for e in q["examples"]])
        self.assertEqual((2_500, 2_000, 0, 250, 250), (q["per_100mb_customer"], q["per_100mb_source"], q["per_100mb_relay"], q["per_100mb_platform"], q["per_100mb_reserve"]))
        self.assertEqual("internet", q["label"])

    def test_one_relay_split_is_64_20_10_6(self):
        _, Q = fresh()
        q = Q.quote(MOBILE_DATA, 10, 3, True, None, 1 * GB, T0)
        self.assertEqual((25_000, 16_000, 5_000, 2_500, 1_500), (q["customer_total"], q["source_share"], q["relay_share"], q["platform_share"], q["reserve_share"]))
        self.assertEqual(q["customer_total"], q["source_share"] + q["relay_share"] + q["platform_share"] + q["reserve_share"])

    def test_the_minima_are_honoured_inside_the_cap_and_refused_beyond_it(self):
        _, Q = fresh()
        # a relay asking 6/MB gets it, the source gets the rest (still >= its 10/MB)
        q = Q.quote(APPROVED_PAID, 10, 6, True, None, 1 * GB, T0)
        self.assertEqual(6_000, q["relay_share"])
        self.assertEqual(15_000, q["source_share"])
        # the source keeps at least its minimum: the relay's standard 20 % is capped by what is left
        q = Q.quote(APPROVED_PAID, 17, 3, True, None, 1 * GB, T0)
        self.assertEqual(17_000, q["source_share"])
        self.assertEqual(4_000, q["relay_share"], "less than 20 %, still above the relay's own minimum")
        self.assertEqual(25_000, q["source_share"] + q["relay_share"] + q["platform_share"] + q["reserve_share"])

    def test_uneconomic_routes_are_refused_not_quoted_at_a_loss(self):
        _, Q = fresh()
        with self.assertRaises(QuoteError) as cm:
            Q.quote(APPROVED_PAID, 21, None, False, None, 1 * GB, T0)     # 21 > 80 % of 25
        self.assertEqual(quotes.UNECONOMIC, cm.exception.reason)
        self.assertEqual(409, cm.exception.code)
        with self.assertRaises(QuoteError) as cm:
            Q.quote(APPROVED_PAID, 16, 6, True, None, 1 * GB, T0)         # 16 + 6 + 2.5 + 1.5 > 25
        self.assertEqual(quotes.UNECONOMIC, cm.exception.reason)
        with self.assertRaises(QuoteError) as cm:
            Q.quote(FREE, 0, 23, True, None, 1 * GB, T0)                  # relay 23 + platform 2 + reserve 1 > 25
        self.assertEqual(quotes.UNECONOMIC, cm.exception.reason)
        self.assertEqual(0, Q.db.execute("SELECT COUNT(*) AS n FROM quotes").fetchone()["n"], "no lossy quote was stored")
        refused = Q.db.execute("SELECT COUNT(*) AS n FROM quote_audit WHERE allowed=0").fetchone()["n"]
        self.assertEqual(3, refused, "every refusal is an audit row")

    def test_a_free_direct_source_is_free_and_needs_no_quote(self):
        _, Q = fresh()
        q = Q.quote(FREE, 0, None, False, None, 1 * GB, T0)
        self.assertEqual(NONE, q["paying_party"])
        self.assertEqual("free", q["label"])
        self.assertEqual(0, q["customer_total"])
        self.assertEqual(0, q["rate_centimes_per_mb"])
        self.assertEqual(0, q["source_share"] + q["relay_share"] + q["platform_share"] + q["reserve_share"])
        self.assertTrue(Q.verify(q))

    def test_a_free_source_with_an_unsponsored_paid_relay_sells_delivery(self):
        _, Q = fresh()
        q = Q.quote(FREE, 0, 4, True, None, 1 * GB, T0)
        self.assertEqual("delivery", q["label"])
        self.assertEqual(CUSTOMER, q["paying_party"])
        self.assertEqual(8, q["rate_centimes_per_mb"], "relay 20 % (5) + platform 10 % (2) + reserve 6 % (1) of the cap")
        self.assertEqual(0, q["source_share"], "the free source gets zero")
        self.assertEqual(5_000, q["relay_share"])
        self.assertEqual(8_000, q["customer_total"])
        self.assertEqual(q["customer_total"], q["relay_share"] + q["platform_share"] + q["reserve_share"])
        # a relay asking more than the standard 20 % is paid its minimum, and the price says so
        q = Q.quote(FREE, 0, 9, True, None, 1 * GB, T0)
        self.assertEqual(12, q["rate_centimes_per_mb"])
        self.assertEqual(9_000, q["relay_share"])

    def test_a_sponsored_route_makes_the_sponsor_pay_and_reserves_first(self):
        stub = ReserveStub()
        _, Q = fresh(stub)
        q = Q.quote(FREE, 0, 4, True, "camp1", 1 * GB, T0)
        self.assertEqual(SPONSOR, q["paying_party"])
        self.assertEqual("sponsored", q["label"])
        self.assertEqual(0, q["customer_total"])
        self.assertEqual(0, q["all_in_total"])
        self.assertEqual(8_000, q["sponsor_total"])
        self.assertEqual(5_000, q["relay_share"], "the relay is paid the same, by the sponsor")
        self.assertEqual([("SESSION", q["quote_id"], 8_000, "camp1", T0)], stub.calls)
        self.assertEqual(0, q["per_100mb_customer"])
        self.assertIsNone(q["comparison"], "nothing to compare when the customer pays nothing")
        # with a move attached, the fee is reserved separately so the move job settles on its own terms
        stub.calls.clear()
        q = Q.quote(APPROVED_PAID, 10, 3, True, "camp1", 1 * GB, T0, move_to_help=True)
        self.assertEqual(10_000, q["movement_fee"])
        self.assertEqual(35_000, q["sponsor_total"])
        self.assertEqual([("SESSION", q["quote_id"], 25_000, "camp1", T0), ("MOVE", q["quote_id"] + ":move", 10_000, "camp1", T0)], stub.calls)

    def test_an_exhausted_sponsor_refuses_the_quote(self):
        stub = ReserveStub(ok=False)
        _, Q = fresh(stub)
        with self.assertRaises(QuoteError) as cm:
            Q.quote(FREE, 0, 4, True, "camp1", 1 * GB, T0)
        self.assertEqual(quotes.SPONSOR_EXHAUSTED, cm.exception.reason)
        self.assertEqual(1, len(stub.calls))
        # no fund wired at all: same refusal, never a silent customer charge
        _, Q2 = fresh(None)
        with self.assertRaises(QuoteError) as cm:
            Q2.quote(FREE, 0, 4, True, "camp1", 1 * GB, T0)
        self.assertEqual(quotes.SPONSOR_EXHAUSTED, cm.exception.reason)

    def test_the_movement_fee_is_in_the_all_in_total_for_a_paying_customer(self):
        _, Q = fresh()
        q = Q.quote(APPROVED_PAID, 10, 3, True, None, 200 * MB, T0, move_to_help=True)
        self.assertEqual(10_000, q["movement_fee"])
        self.assertEqual(5_000, q["customer_total"], "the data amount is quoted before the fee, as §10.3 says")
        self.assertEqual(15_000, q["all_in_total"])
        with self.assertRaises(QuoteError):
            Q.quote(APPROVED_PAID, 10, None, False, None, 200 * MB, T0, move_to_help=True)

    def test_expiry(self):
        _, Q = fresh()
        q = Q.quote(APPROVED_PAID, 10, None, False, None, 1 * GB, T0)
        self.assertFalse(Q.expired(q, T0 + 10 * 60_000 - 1))
        self.assertTrue(Q.expired(q, T0 + 10 * 60_000))
        self.assertTrue(Q.valid(q, T0 + 1))
        self.assertFalse(Q.valid(q, T0 + 11 * 60_000))
        with self.assertRaises(QuoteError) as cm:
            Q.accept(q["quote_id"], "OWNER", OWNER, T0 + 11 * 60_000)
        self.assertEqual(quotes.EXPIRED, cm.exception.reason)
        # declining an expired quote is still recorded (it costs nobody anything)
        Q.accept(q["quote_id"], "OWNER", OWNER, T0 + 11 * 60_000, decision="DECLINE")
        self.assertEqual("", Q.accepted_by(q["quote_id"], "OWNER"))

    def test_signature_tamper(self):
        _, Q = fresh()
        q = Q.quote(APPROVED_PAID, 10, None, False, None, 1 * GB, T0)
        self.assertTrue(Q.verify(q))
        for field, value in (("customer_total", 1), ("source_share", 24_999), ("rate_centimes_per_mb", 1), ("expires_at", T0 + 10 * H),
                             ("paying_party", SPONSOR), ("movement_fee", 1), ("config_version", "x")):
            t = dict(q)
            t[field] = value
            self.assertFalse(Q.verify(t), field)
        t = dict(q)
        t["signature"] = "0" * 64
        self.assertFalse(Q.verify(t))
        t = dict(q)
        del t["signature"]
        self.assertFalse(Q.verify(t))
        # a different secret is a different Brain
        other = Quote(sqlite3.connect(":memory:"), RateConfig(sqlite3.connect(":memory:")), b"other")
        self.assertFalse(other.verify(q))
        # display fields are not signed: a screen may recompute them freely
        t = dict(q)
        t["examples"] = []
        self.assertTrue(Q.verify(t))

    def test_acceptance_takes_only_the_quote_id_and_its_signature(self):
        _, Q = fresh()
        q = Q.quote(APPROVED_PAID, 10, None, False, None, 1 * GB, T0)
        out = Q.accept(q["quote_id"], "OWNER", OWNER, T0 + 1, signature=q["signature"])
        self.assertEqual("ACCEPT", out["decision"])
        self.assertEqual(OWNER, Q.accepted_by(q["quote_id"], "OWNER"))
        self.assertEqual("", Q.accepted_by(q["quote_id"], "CUSTOMER"))
        with self.assertRaises(QuoteError) as cm:
            Q.accept(q["quote_id"], "CUSTOMER", BUYER, T0 + 1, signature="deadbeef")
        self.assertEqual(quotes.BAD_SIGNATURE, cm.exception.reason)
        with self.assertRaises(QuoteError) as cm:
            Q.accept("nope", "CUSTOMER", BUYER, T0 + 1)
        self.assertEqual(404, cm.exception.code)


class BundleComparisonTest(unittest.TestCase):

    def test_one_honest_word_per_amount_and_no_universal_claim(self):
        _, Q = fresh()
        c = Q.compare_with_bundles(1 * GB, 25_000, T0)
        self.assertEqual(CHEAPER, c["verdict"])
        self.assertEqual("MTN 1 Go", c["bundle_label"])
        self.assertEqual(35_000, c["bundle_price_centimes"])
        c = Q.compare_with_bundles(6 * GB, 150_000, T0)
        self.assertEqual(NOT_CHEAPER, c["verdict"], "§6: do not market ProkNet as cheaper to that customer")
        self.assertIn("Préférez le forfait", c["sentence"])
        c = Q.compare_with_bundles(1_500 * MB, 37_500, T0)
        self.assertEqual(COMPARABLE, c["verdict"], "375 vs 400 FCFA is within 10 %")
        c = Q.compare_with_bundles(20 * MB, 500, T0)
        self.assertEqual(CHEAPER, c["verdict"])
        self.assertEqual("Airtel 110 Mo", c["bundle_label"], "the cheapest bundle that covers 20 MB")
        c = Q.compare_with_bundles(20 * GB, 500_000, T0)
        self.assertTrue(c["prorated"], "no bundle covers 20 GB: the biggest is prorated and says so")
        self.assertEqual(NOT_CHEAPER, c["verdict"])
        for amount, total in ((1 * GB, 25_000), (6 * GB, 150_000), (1_500 * MB, 37_500)):
            s = Q.compare_with_bundles(amount, total, T0)["sentence"]
            self.assertIn("2026-09-26", s, "dated")
            self.assertIn("pas une promesse", s)
            for banned in ("toujours", "économisez", "garanti"):
                self.assertNotIn(banned, s.lower())

    def test_the_comparison_rides_on_a_customer_quote(self):
        _, Q = fresh()
        q = Q.quote(APPROVED_PAID, 10, None, False, None, 1 * GB, T0)
        self.assertEqual(CHEAPER, q["comparison"]["verdict"])
        q = Q.quote(APPROVED_PAID, 10, 3, True, None, 1 * GB, T0, move_to_help=True)
        self.assertEqual(35_000, q["comparison"]["prok_total_centimes"], "the comparison uses the ALL-IN total, fee included")
        self.assertEqual(COMPARABLE, q["comparison"]["verdict"])


class FixtureTest(unittest.TestCase):

    def test_the_fixture_is_what_this_code_computes(self):
        path = os.path.join(os.path.dirname(__file__), "fixtures", "quote_examples.json")
        with open(path, encoding="utf-8") as f:
            on_disk = json.load(f)
        self.assertEqual(quotes.fixture_examples(), on_disk, "regenerate with python -m tests.write_quote_fixture and say so")
        totals = {e["label"]: e["customer_total"] for e in on_disk["examples"]}
        self.assertEqual({"20 Mo": 500, "200 Mo": 5_000, "1 Go": 25_000, "6 Go": 150_000}, totals)
        self.assertEqual([5, 50, 250, 1_500], [e["fcfa"] for e in on_disk["examples"]])
        for e in on_disk["examples"]:
            self.assertEqual(e["customer_total"], e["direct_source"] + e["direct_platform"] + e["direct_reserve"])
            self.assertEqual(e["customer_total"], e["relay_source"] + e["relay_relay"] + e["relay_platform"] + e["relay_reserve"])
        # the engine's own quotes agree with the fixture
        _, Q = fresh()
        for e in on_disk["examples"]:
            d = Q.quote(APPROVED_PAID, 0, None, False, None, e["bytes"], T0)
            self.assertEqual((e["direct_source"], e["direct_platform"], e["direct_reserve"]), (d["source_share"], d["platform_share"], d["reserve_share"]), e["label"])
            r = Q.quote(APPROVED_PAID, 0, 0, True, None, e["bytes"], T0)
            self.assertEqual((e["relay_source"], e["relay_relay"], e["relay_platform"], e["relay_reserve"]),
                             (r["source_share"], r["relay_share"], r["platform_share"], r["reserve_share"]), e["label"])


class DispatchTest(unittest.TestCase):

    def test_the_http_paths(self):
        _, Q = fresh()
        code, cfg = Q.handle_get("/v1/quotes/config", OWNER, {}, T0)
        self.assertEqual(200, code)
        self.assertEqual(25, cfg[quotes.K_CAP])
        code, q = Q.handle_post("/v1/quotes/quote", OWNER, {"source_kind": APPROVED_PAID, "source_min": 10, "relay_present": False, "allowance_bytes": 1 * GB}, T0)
        self.assertEqual(200, code)
        self.assertTrue(Q.verify(q))
        code, out = Q.handle_post("/v1/quotes/accept", OWNER, {"quote_id": q["quote_id"], "signature": q["signature"], "role": "OWNER"}, T0 + 1)
        self.assertEqual(200, code)
        self.assertEqual(OWNER, Q.accepted_by(q["quote_id"], "OWNER"))
        code, out = Q.handle_post("/v1/quotes/quote", OWNER, {"source_kind": APPROVED_PAID, "source_min": 21, "allowance_bytes": 1 * GB}, T0)
        self.assertEqual(409, code)
        self.assertEqual(quotes.UNECONOMIC, out["reason"])
        code, out = Q.handle_get("/v1/quotes/compare", OWNER, {"bytes": 1 * GB, "total": 25_000}, T0)
        self.assertEqual((200, CHEAPER), (code, out["verdict"]))
        code, out = Q.handle_get("/v1/quotes/history", OWNER, {"key": quotes.K_CAP}, T0)
        self.assertEqual(200, code)
        self.assertEqual(1, len(out["history"]))
        self.assertEqual(404, Q.handle_get("/v1/quotes/nope", OWNER, {}, T0)[0])


if __name__ == "__main__":
    unittest.main()
