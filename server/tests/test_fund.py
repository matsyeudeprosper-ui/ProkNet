"""v0.19.0: the Free Internet Fund.

The rules under test: 40 % of NET collected market revenue, as a dated rule; a reservation
only from cleared, unreserved budget - never from customer credit, never negative; every
reserve / settle / release is a double-entry posting so the books always net to zero; a
campaign's cap, daily cap and zones hold; the dashboard adds up; low() says stop.
"""
import sqlite3
import unittest

from brain import fund as fund_mod
from brain import ledger, quotes
from brain.fund import BLOCK, BUDGET, Fund, FundError, GENERAL, MARKET_REVENUE, MOVE, RELEASED, RESERVED, SCOUT, SESSION, SETTLED
from brain.quotes import RateConfig

H = 3_600_000
DAY = 24 * H
T0 = fund_mod.day_start(1_700_000_000_000) + 10 * H    # mid-morning in Brazzaville, so "today" is stable
TREASURER = "dd" * 16
SPONSOR = "ee" * 16
RELAY = "cc" * 16
SCOUT_ID = "ff" * 16
BUYER = "aa" * 16


class StubLedger:
    """The ledger callback pair for tests: records every posting and computes balances the
    way brain.ledger does (liabilities credit-minus-debit, assets the reverse)."""

    def __init__(self):
        self.postings = []

    def post(self, now, kind, debit, credit, amount, ref="", memo="", actor=""):
        if amount <= 0:
            raise ledger.LedgerError("a posting must move a positive amount")
        if debit == credit:
            raise ledger.LedgerError("a posting moves between two accounts")
        self.postings.append({"ts": now, "kind": kind, "debit": debit, "credit": credit, "amount": int(amount), "ref": ref, "memo": memo, "actor": actor})
        return "p%d" % len(self.postings)

    def balance(self, account):
        cr = sum(p["amount"] for p in self.postings if p["credit"] == account)
        dr = sum(p["amount"] for p in self.postings if p["debit"] == account)
        return cr - dr if ledger._liability(account) else dr - cr

    def net(self):
        accounts = set(p["debit"] for p in self.postings) | set(p["credit"] for p in self.postings)
        return sum(self.balance(a) if ledger._liability(a) else -self.balance(a) for a in accounts)

    def touching(self, prefix):
        return [p for p in self.postings if p["debit"].startswith(prefix) or p["credit"].startswith(prefix)]


def fresh():
    db = sqlite3.connect(":memory:")
    cfg = RateConfig(db)
    L = StubLedger()
    F = Fund(db, L.post, L.balance, cfg, treasury_ids=(TREASURER,))
    return F, L, cfg


def collected(L, amount, now=T0, rail="mtn"):
    """What the market module does when a treasurer verifies a listing invoice."""
    L.post(now, "MARKET_FEE", "float:" + rail, MARKET_REVENUE, amount, ref="inv:%d" % len(L.postings))


def campaign(F, now=T0, max_spend=10_000, fee=2_000, days=10, zones=("bacongo",), cid="camp1"):
    return F.create_campaign(TREASURER, SPONSOR, "Boutique X - Bacongo", list(zones), now, now + days * DAY, max_spend, fee, now, campaign_id=cid)


class AllocationTest(unittest.TestCase):

    def test_forty_percent_of_net_collected_revenue_as_a_dated_rule(self):
        F, L, cfg = fresh()
        collected(L, 100_000)
        out = F.allocate_market_revenue(T0, 100_000, TREASURER, memo="daily")
        self.assertEqual(40, out["pct"])
        self.assertEqual(40_000, out["allocated"])
        self.assertEqual(40_000, L.balance(BUDGET))
        self.assertEqual(60_000, L.balance(MARKET_REVENUE), "the rest stays as Prok's revenue")
        self.assertEqual(0, L.net())
        # the percentage is a dated rule with a change log, not a setting somebody flips
        cfg.set(quotes.K_FUND_PCT, 50, T0 + H, TREASURER, "pilot review: raise the fund share", T0 + 1)
        collected(L, 100_000, T0 + 2 * H)
        out = F.allocate_market_revenue(T0 + 2 * H, 100_000, TREASURER)
        self.assertEqual((50, 50_000), (out["pct"], out["allocated"]))
        h = cfg.history(quotes.K_FUND_PCT)
        self.assertEqual([40, 50], [r["value"] for r in h])
        self.assertEqual("pilot review: raise the fund share", h[1]["reason"])

    def test_only_collected_money_is_allocated_and_only_by_a_treasurer(self):
        F, L, _ = fresh()
        collected(L, 10_000)
        with self.assertRaises(FundError) as cm:
            F.allocate_market_revenue(T0, 100_000, TREASURER)      # 40 % of 100,000 > the 10,000 collected
        self.assertEqual(fund_mod.REVENUE_NOT_COLLECTED, cm.exception.reason)
        with self.assertRaises(FundError) as cm:
            F.allocate_market_revenue(T0, 10_000, SPONSOR)
        self.assertEqual(403, cm.exception.code)
        self.assertEqual(0, L.balance(BUDGET))
        self.assertEqual(2, F.db.execute("SELECT COUNT(*) AS n FROM fund_audit WHERE allowed=0").fetchone()["n"])


class ReservationTest(unittest.TestCase):

    def test_reservation_only_from_cleared_money(self):
        F, L, _ = fresh()
        campaign(F)
        with self.assertRaises(FundError) as cm:
            F.reserve(SESSION, "q1", 1_000, "camp1", T0, zone="bacongo")
        self.assertEqual(fund_mod.NOT_CLEARED, cm.exception.reason)
        self.assertEqual([], L.postings, "nothing moved")
        # the treasurer records the sponsor's transfer: budget and fee, from the wallet that received it
        c = F.clear_campaign(TREASURER, "camp1", "MTN", "MP240926.1234", T0)
        self.assertTrue(c["cleared"])
        self.assertEqual(10_000, L.balance("campaign:camp1:budget"))
        self.assertEqual(2_000, L.balance("prok:revenue"))
        self.assertEqual(12_000, L.balance("float:mtn"))
        self.assertEqual(0, L.net())
        r = F.reserve(SESSION, "q1", 1_000, "camp1", T0, zone="bacongo")
        self.assertEqual(RESERVED, F.reservation("q1")["state"])
        self.assertEqual(9_000, L.balance("campaign:camp1:budget"))
        self.assertEqual(1_000, L.balance("campaign:camp1:reserved:q1"))
        with self.assertRaises(FundError) as cm:
            F.clear_campaign(TREASURER, "camp1", "MTN", "again", T0)
        self.assertEqual(fund_mod.WRONG_STATE, cm.exception.reason)

    def test_reserve_settle_release_keep_the_books_balanced(self):
        F, L, _ = fresh()
        collected(L, 100_000)
        F.allocate_market_revenue(T0, 100_000, TREASURER)
        F.reserve(BLOCK, "b1", 2_000, None, T0)
        F.reserve(MOVE, "m1", 10_000, GENERAL, T0)
        F.reserve(SCOUT, "s1", 1_000, "", T0)
        self.assertEqual(40_000 - 13_000, L.balance(BUDGET))
        self.assertEqual(0, L.net())
        # verified block: the full reward to the relay
        out = F.settle("b1", 2_000, "earned:" + RELAY, T0 + H)
        self.assertEqual((2_000, 0), (out["settled"], out["released"]))
        self.assertEqual(2_000, L.balance("earned:" + RELAY))
        self.assertEqual(0, L.balance("fund:reserved:b1"))
        self.assertEqual(0, L.balance(fund_mod.SPENT), "the spend account is a pass-through")
        # a move cancelled after departure: half to the relay, half back to the budget
        out = F.settle("m1", 5_000, "earned:" + RELAY, T0 + H)
        self.assertEqual((5_000, 5_000), (out["settled"], out["released"]))
        self.assertEqual(7_000, L.balance("earned:" + RELAY))
        # a scout reward: promotional credit, its own book
        F.settle("s1", 1_000, "promo:" + SCOUT_ID, T0 + H)
        self.assertEqual(1_000, L.balance("promo:" + SCOUT_ID))
        self.assertEqual(0, L.balance("earned:" + SCOUT_ID), "promo credit never lands in a withdrawable account")
        self.assertEqual(40_000 - 2_000 - 5_000 - 1_000, L.balance(BUDGET))
        self.assertEqual(0, L.net())
        for p in L.postings:
            self.assertGreater(p["amount"], 0)
            self.assertNotEqual(p["debit"], p["credit"])
        # states are final; a settled reservation cannot be settled or released again
        for ref in ("b1", "m1", "s1"):
            self.assertEqual(SETTLED, F.reservation(ref)["state"])
            with self.assertRaises(FundError):
                F.release(ref, T0 + 2 * H)
        F.reserve(BLOCK, "b2", 2_000, None, T0)
        F.release("b2", T0 + H)
        self.assertEqual(RELEASED, F.reservation("b2")["state"])
        self.assertEqual(32_000, L.balance(BUDGET))
        # a zero settlement is a release
        F.reserve(BLOCK, "b3", 2_000, None, T0)
        F.settle("b3", 0, "earned:" + RELAY, T0 + H)
        self.assertEqual(RELEASED, F.reservation("b3")["state"])
        self.assertEqual(0, L.net())

    def test_settlement_bounds(self):
        F, L, _ = fresh()
        collected(L, 100_000)
        F.allocate_market_revenue(T0, 100_000, TREASURER)
        F.reserve(BLOCK, "b1", 2_000, None, T0)
        with self.assertRaises(FundError):
            F.settle("b1", 2_001, "earned:" + RELAY, T0)
        with self.assertRaises(FundError):
            F.settle("b1", -1, "earned:" + RELAY, T0)
        with self.assertRaises(FundError):
            F.settle("b1", 2_000, "credit:" + BUYER, T0)     # a fund never pays into customer credit
        with self.assertRaises(FundError):
            F.reserve(BLOCK, "b1", 2_000, None, T0)          # one reference, one reservation
        with self.assertRaises(FundError):
            F.reserve(BLOCK, "b9", 0, None, T0)
        with self.assertRaises(FundError):
            F.reserve("PARTY", "b9", 1, None, T0)
        self.assertEqual(RESERVED, F.reservation("b1")["state"])

    def test_no_reservation_from_customer_accounts(self):
        F, L, _ = fresh()
        # a customer with plenty of credit and a fund with nothing
        L.post(T0, "TOPUP", "float:mtn", "credit:" + BUYER, 1_000_000)
        with self.assertRaises(FundError) as cm:
            F.reserve(BLOCK, "b1", 2_000, None, T0)
        self.assertEqual(fund_mod.BUDGET_EXHAUSTED, cm.exception.reason)
        self.assertEqual(1, len(L.postings), "the fund posted nothing")
        collected(L, 1_000)
        F.allocate_market_revenue(T0, 1_000, TREASURER)       # 400 in the fund
        with self.assertRaises(FundError) as cm:
            F.reserve(BLOCK, "b1", 2_000, None, T0)
        self.assertEqual(fund_mod.BUDGET_EXHAUSTED, cm.exception.reason)
        F.reserve(BLOCK, "b2", 400, None, T0)
        self.assertEqual(0, L.balance(BUDGET), "never negative")
        self.assertEqual(1_000_000, L.balance("credit:" + BUYER), "customer credit untouched")
        self.assertEqual([], [p for p in L.touching("credit:") if p["kind"].startswith("FUND")])
        self.assertEqual([], L.touching("held:"))

    def test_campaign_cap_daily_cap_and_zones(self):
        F, L, _ = fresh()
        campaign(F, max_spend=10_000, days=10, zones=("bacongo", "poto-poto"))     # daily cap 1,000
        F.clear_campaign(TREASURER, "camp1", "AIRTEL", "AM1", T0)
        v = F.campaign_view("camp1", T0)
        self.assertEqual(1_000, v["daily_cap"])
        self.assertEqual(1_000, v["available_now"])
        F.reserve(SESSION, "q1", 600, "camp1", T0, zone="bacongo")
        F.reserve(SESSION, "q2", 400, "camp1", T0 + H, zone="poto-poto")
        with self.assertRaises(FundError) as cm:
            F.reserve(SESSION, "q3", 1, "camp1", T0 + 2 * H, zone="bacongo")
        self.assertEqual(fund_mod.BUDGET_EXHAUSTED, cm.exception.reason, "today's cap is spent although the pot has 9,000")
        self.assertEqual(9_000, L.balance("campaign:camp1:budget"))
        # released money returns to today's allowance
        F.release("q2", T0 + 2 * H)
        F.reserve(SESSION, "q3", 400, "camp1", T0 + 3 * H, zone="bacongo")
        # a zone the sponsor did not buy
        with self.assertRaises(FundError) as cm:
            F.reserve(SESSION, "q4", 1, "camp1", T0 + DAY, zone="moungali")
        self.assertEqual(fund_mod.OUTSIDE_ZONE, cm.exception.reason)
        # tomorrow: a fresh day, same pot
        F.reserve(SESSION, "q5", 1_000, "camp1", T0 + DAY, zone="bacongo")
        self.assertEqual(8_000, L.balance("campaign:camp1:budget"))
        # the campaign's own cap: nothing beyond max_spend, ever
        for d in range(2, 10):
            F.reserve(SESSION, "d%d" % d, 1_000, "camp1", T0 + d * DAY, zone="bacongo")
        self.assertEqual(0, L.balance("campaign:camp1:budget"))
        with self.assertRaises(FundError) as cm:
            F.reserve(SESSION, "over", 1, "camp1", T0 + 9 * DAY + H, zone="bacongo")
        self.assertEqual(fund_mod.BUDGET_EXHAUSTED, cm.exception.reason)
        # outside the dates, paused
        with self.assertRaises(FundError) as cm:
            F.reserve(SESSION, "late", 1, "camp1", T0 + 11 * DAY, zone="bacongo")
        self.assertEqual(fund_mod.OUTSIDE_DATES, cm.exception.reason)
        F.set_campaign_state(TREASURER, "camp1", fund_mod.PAUSED, T0 + 5 * DAY)
        with self.assertRaises(FundError) as cm:
            F.reserve(SESSION, "paused", 1, "camp1", T0 + 5 * DAY, zone="bacongo")
        self.assertEqual(fund_mod.WRONG_STATE, cm.exception.reason)
        self.assertEqual(0, L.net())

    def test_a_campaign_never_spends_the_general_fund_and_vice_versa(self):
        F, L, _ = fresh()
        collected(L, 100_000)
        F.allocate_market_revenue(T0, 100_000, TREASURER)     # general 40,000
        campaign(F, max_spend=1_000, days=1)
        F.clear_campaign(TREASURER, "camp1", "MTN", "MP1", T0)
        with self.assertRaises(FundError) as cm:
            F.reserve(SESSION, "big", 5_000, "camp1", T0, zone="bacongo")
        self.assertEqual(fund_mod.BUDGET_EXHAUSTED, cm.exception.reason, "the general fund does not top up a sponsor")
        self.assertEqual(40_000, L.balance(BUDGET))


class DashboardTest(unittest.TestCase):

    def test_the_numbers_add_up(self):
        F, L, _ = fresh()
        collected(L, 100_000)
        F.allocate_market_revenue(T0, 100_000, TREASURER)
        F.reserve(BLOCK, "b1", 2_000, None, T0)
        F.reserve(BLOCK, "b2", 2_000, None, T0)
        F.settle("b1", 2_000, "earned:" + RELAY, T0 + H)
        campaign(F)
        F.clear_campaign(TREASURER, "camp1", "MTN", "MP1", T0)
        F.reserve(SESSION, "q1", 800, "camp1", T0, zone="bacongo")
        F.settle("q1", 650, "earned:" + RELAY, T0 + H)
        d = F.dashboard(T0 + 2 * H)
        self.assertEqual(40, d["pct"])
        self.assertEqual(100_000, d["collected_net"])
        self.assertEqual(40_000, d["allocated"])
        self.assertEqual(60_000, d["market_revenue_unallocated"])
        self.assertEqual(2_000, d["reserved"])
        self.assertEqual(2_000, d["spent"])
        self.assertEqual(36_000, d["budget_unreserved"])
        self.assertEqual(d["allocated"], d["budget_unreserved"] + d["reserved"] + d["spent"])
        self.assertEqual(1, len(d["campaigns"]))
        c = d["campaigns"][0]
        self.assertEqual((10_000, 0, 650, 9_350, 1), (c["max_spend"], c["reserved"], c["spent"], c["budget_unreserved"], c["connections_delivered"]))
        self.assertEqual(c["max_spend"], c["budget_unreserved"] + c["reserved"] + c["spent"])
        self.assertNotIn("customer_id", json_keys(c), "the sponsor sees aggregates, never identities")
        self.assertEqual(0, L.net())

    def test_low_says_stop_offering(self):
        F, L, _ = fresh()
        self.assertTrue(F.low(T0), "an empty fund is low")
        collected(L, 100_000)
        F.allocate_market_revenue(T0, 100_000, TREASURER)     # 40,000 < the 50,000 threshold
        self.assertTrue(F.low(T0))
        collected(L, 100_000)
        F.allocate_market_revenue(T0 + 1, 100_000, TREASURER)  # 80,000
        self.assertFalse(F.low(T0))
        F.reserve(MOVE, "m1", 40_000, None, T0)
        self.assertTrue(F.low(T0), "reserved money is not offerable money")
        campaign(F, max_spend=10_000)
        self.assertTrue(F.low(T0, "camp1"), "uncleared is low")
        F.clear_campaign(TREASURER, "camp1", "MTN", "MP1", T0)
        self.assertTrue(F.low(T0, "camp1"), "a 1,000/day campaign is under the general threshold")
        code, out = F.handle_get("/v1/fund/low", RELAY, {}, T0)
        self.assertEqual((200, True), (code, out["low"]))

    def test_dispatch_and_roles(self):
        F, L, _ = fresh()
        self.assertEqual(403, F.handle_get("/v1/fund/dashboard", RELAY, {}, T0)[0])
        code, out = F.handle_post("/v1/fund/campaign", TREASURER, {"sponsor_id": SPONSOR, "label": "Ecole Y", "zones": "bacongo, moungali",
                                                                    "starts_at": T0, "ends_at": T0 + 5 * DAY, "max_spend": 5_000, "platform_fee": 1_000}, T0)
        self.assertEqual(200, code, out)
        self.assertEqual(["bacongo", "moungali"], out["zones"])
        self.assertEqual(1_000, out["daily_cap"])
        cid = out["id"]
        self.assertEqual(200, F.handle_post("/v1/fund/campaign/clear", TREASURER, {"campaign_id": cid, "rail": "MTN", "evidence": "MP2"}, T0)[0])
        self.assertEqual(403, F.handle_post("/v1/fund/campaign/clear", SPONSOR, {"campaign_id": cid, "rail": "MTN", "evidence": "MP2"}, T0)[0])
        code, view = F.handle_get("/v1/fund/campaign", SPONSOR, {"id": cid}, T0)
        self.assertEqual(200, code, "the sponsor may read its own campaign")
        self.assertEqual(403, F.handle_get("/v1/fund/campaign", RELAY, {"id": cid}, T0)[0])
        collected(L, 10_000)
        code, out = F.handle_post("/v1/fund/allocate", TREASURER, {"net": 10_000}, T0)
        self.assertEqual((200, 4_000), (code, out["allocated"]))
        code, out = F.handle_get("/v1/fund/dashboard", TREASURER, {}, T0)
        self.assertEqual(200, code)
        self.assertEqual(4_000, out["budget_unreserved"])


def json_keys(d, acc=None):
    acc = acc if acc is not None else set()
    for k, v in d.items():
        acc.add(k)
        if isinstance(v, dict):
            json_keys(v, acc)
    return acc


if __name__ == "__main__":
    unittest.main()
