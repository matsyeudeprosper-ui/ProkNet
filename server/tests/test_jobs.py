"""v0.19.0: relay offers and availability blocks.

The rules under test, from contract §4 "Relay job and exact earning moment" and §10.3:
a STAY block exists only with a cleared reservation; it is verified only by the probe rule
(>= 3 successful probes spread over 30 minutes, <= 1 failed); four blocks per relay per day;
lost reachability ends the block with nothing paid; a MOVE needs the reservation and the
paying party's acceptance of the all-in quote and is earned only on arrival + confirmed
readiness; cancellation pays 0 before departure and 50 % after; the volunteer may decline;
offers expire; the state words are the shared fixture.
"""
import os
import sqlite3
import unittest

from brain import fund as fund_mod
from brain import jobs as jobs_mod
from brain import quotes
from brain.fund import BUDGET, Fund, RELEASED, RESERVED, SETTLED
from brain.jobs import (ACCEPTED, CANCELLED, CARRY, COMPLETED, DECLINED, EXPIRED, FAILED, IN_PROGRESS, Jobs, JobsError, MOVE,
                        OFFERED, OFFER_TEXT, PIPELINE, RUNNING, STAY)
from brain.quotes import APPROVED_PAID, FREE, GB, MB, Quote, RateConfig
from tests.test_fund import StubLedger, collected

H = 3_600_000
MIN = 60_000
DAY = 24 * H
T0 = fund_mod.day_start(1_700_000_000_000) + 10 * H
TREASURER = "dd" * 16
OPERATOR = "ee" * 16
RELAY = "cc" * 16
CUSTOMER = "aa" * 16
SPONSOR = "ff" * 16
SECRET = b"test-secret"


def fresh(budget=100_000, hold_ok=("hold-ok",)):
    db = sqlite3.connect(":memory:")
    cfg = RateConfig(db)
    L = StubLedger()
    F = Fund(db, L.post, L.balance, cfg, treasury_ids=(TREASURER,))
    Q = Quote(db, cfg, SECRET, fund_reserve=F.reserve)
    if budget:
        collected(L, budget * 100 // 40)
        F.allocate_market_revenue(T0 - H, budget * 100 // 40, TREASURER)
    J = Jobs(db, F, Q, cfg, post=L.post, hold_covers=lambda hid, cust, amt: hid in hold_ok, operator_ids=(OPERATOR,))
    return J, F, Q, L, cfg


def stay(J, now=T0, relay=RELAY, zone="bacongo"):
    return J.offer_stay(OPERATOR, relay, zone, None, now)


def running_block(J, now=T0):
    o = stay(J, now)
    J.accept(o["id"], RELAY, now)
    J.start_block(o["id"], RELAY, now + MIN)
    return o["id"], now + MIN


def move_quote(Q, now=T0, sponsor=None):
    return Q.quote(APPROVED_PAID, 10, 3, True, sponsor, 1 * GB, now, move_to_help=True)


class StayTest(unittest.TestCase):

    def test_a_block_needs_a_cleared_reservation_first(self):
        J, F, Q, L, _ = fresh(budget=0)
        with self.assertRaises(JobsError) as cm:
            stay(J)
        self.assertEqual(jobs_mod.NO_RESERVATION, cm.exception.reason)
        self.assertEqual(0, J.db.execute("SELECT COUNT(*) AS n FROM relay_offers").fetchone()["n"], "no offer without money")
        J2, F2, _, L2, _ = fresh(budget=2_000)
        o = stay(J2)
        self.assertEqual(OFFERED, o["state"])
        self.assertEqual(2_000, o["amount"])
        self.assertEqual("Proposé", o["text"])
        self.assertEqual(RESERVED, F2.reservation(o["id"])["state"])
        self.assertEqual(0, L2.balance(BUDGET), "the reward left the budget before the offer existed")
        with self.assertRaises(JobsError):
            stay(J2, T0 + MIN)      # the budget is spent: no second offer
        # what the volunteer sees BEFORE accepting: amount, time, requirements, cap
        self.assertEqual((30, 4, 3, 1), (o["block_minutes"], o["blocks_per_day"], o["probes_required"], o["probes_max_failed"]))
        self.assertEqual(T0 + jobs_mod.OFFER_TTL_MS, o["expires_at"])

    def test_only_an_operator_commissions_a_block(self):
        J, _, _, _, _ = fresh()
        with self.assertRaises(JobsError) as cm:
            J.offer_stay(RELAY, RELAY, "bacongo", None, T0)
        self.assertEqual(403, cm.exception.code)

    def test_a_block_is_verified_only_by_the_probe_rule(self):
        J, F, _, L, _ = fresh()
        oid, t1 = running_block(J)
        self.assertEqual(IN_PROGRESS, J.offer_view(J._offer(oid), t1)["state"])
        for m in (3, 13, 23):
            J.probe(oid, True, t1 + m * MIN)
        self.assertEqual(IN_PROGRESS, J.offer_view(J._offer(oid), t1 + 25 * MIN)["state"], "not before 30 minutes")
        self.assertEqual(0, L.balance("earned:" + RELAY))
        J.sweep(t1 + 30 * MIN)
        v = J.offer_view(J._offer(oid), t1 + 30 * MIN)
        self.assertEqual(COMPLETED, v["state"])
        self.assertEqual("Vérifié — gagné", v["text"])
        self.assertEqual(2_000, v["earned"])
        self.assertEqual(2_000, L.balance("earned:" + RELAY))
        self.assertEqual(SETTLED, F.reservation(oid)["state"])
        self.assertEqual(0, L.net())
        self.assertEqual(2, v["pipeline_stage"], "earned balance")

    def test_probes_bunched_at_the_start_do_not_verify(self):
        J, F, _, L, _ = fresh()
        oid, t1 = running_block(J)
        for m in (1, 2, 3, 4, 5):
            J.probe(oid, True, t1 + m * MIN)
        J.sweep(t1 + 30 * MIN)
        v = J.offer_view(J._offer(oid), t1 + 30 * MIN)
        self.assertEqual(FAILED, v["state"])
        self.assertEqual("Non vérifié — rien gagné", v["text"])
        self.assertEqual(0, L.balance("earned:" + RELAY))
        self.assertEqual(RELEASED, F.reservation(oid)["state"])
        self.assertEqual(100_000, L.balance(BUDGET), "the reservation came back")

    def test_the_probe_rule_on_its_boundaries(self):
        ok = lambda t: {"at": t, "ok": True}
        ko = lambda t: {"at": t, "ok": False}
        B = 30 * MIN
        v = Jobs.verified_by_probes
        self.assertTrue(v([ok(1), ok(11 * MIN), ok(21 * MIN)], 0, B))
        self.assertTrue(v([ok(1), ok(11 * MIN), ok(21 * MIN), ko(15 * MIN)], 0, B), "one failed probe is allowed")
        self.assertFalse(v([ok(1), ok(11 * MIN), ok(21 * MIN), ko(15 * MIN), ko(25 * MIN)], 0, B), "two are not")
        self.assertFalse(v([ok(1), ok(2 * MIN), ok(21 * MIN)], 0, B), "nothing in the middle third")
        self.assertFalse(v([ok(1), ok(11 * MIN)], 0, B), "two probes are not three")
        self.assertFalse(v([ok(1), ok(11 * MIN), ok(31 * MIN)], 0, B), "a probe after the block is not in it")
        self.assertTrue(v([ok(0), ok(10 * MIN), ok(20 * MIN)], 0, B), "the thirds' lower edges are inside")
        self.assertFalse(v([], 0, B))

    def test_a_probe_answered_at_the_thirty_minute_mark_completes_without_a_sweep(self):
        J, _, _, L, _ = fresh()
        oid, t1 = running_block(J)
        J.probe(oid, True, t1 + 5 * MIN)
        J.probe(oid, True, t1 + 15 * MIN)
        v = J.probe(oid, True, t1 + 30 * MIN)
        self.assertEqual(COMPLETED, v["state"])
        self.assertEqual(2_000, L.balance("earned:" + RELAY))

    def test_two_consecutive_failed_probes_end_the_block_with_no_pay(self):
        J, F, _, L, _ = fresh()
        oid, t1 = running_block(J)
        J.probe(oid, True, t1 + 3 * MIN)
        J.probe(oid, False, t1 + 8 * MIN)
        v = J.probe(oid, False, t1 + 9 * MIN)
        self.assertEqual(FAILED, v["state"])
        self.assertEqual("2 consecutive failed probes", v["fail_reason"])
        self.assertEqual(0, L.balance("earned:" + RELAY))
        self.assertEqual(RELEASED, F.reservation(oid)["state"])
        with self.assertRaises(JobsError):
            J.probe(oid, True, t1 + 10 * MIN)    # nothing runs any more

    def test_ten_silent_minutes_end_the_block_with_no_pay(self):
        J, F, _, L, _ = fresh()
        oid, t1 = running_block(J)
        J.probe(oid, True, t1 + 3 * MIN)
        n = J.sweep(t1 + 13 * MIN)
        self.assertEqual(0, n["blocks_failed_silent"], "exactly ten minutes is not more than ten")
        n = J.sweep(t1 + 13 * MIN + 1)
        self.assertEqual(1, n["blocks_failed_silent"])
        self.assertEqual(FAILED, J.offer_view(J._offer(oid), t1 + 14 * MIN)["state"])
        self.assertEqual(0, L.balance("earned:" + RELAY))
        self.assertEqual(100_000, L.balance(BUDGET))

    def test_the_challenge_answer_is_the_probe(self):
        J, _, _, L, _ = fresh()
        oid, t1 = running_block(J)
        for m in (2, 12, 22):
            c = J.challenge(oid, RELAY, t1 + m * MIN)
            v = J.answer(oid, RELAY, c["nonce"], t1 + m * MIN + 5_000)
            self.assertEqual(m // 10 + 1, v["probes_ok"])
        # a wrong answer is a failed probe; a late one too
        c = J.challenge(oid, RELAY, t1 + 25 * MIN)
        v = J.answer(oid, RELAY, "wrong", t1 + 25 * MIN + 1_000)
        self.assertEqual(1, v["probes_failed"])
        c = J.challenge(oid, RELAY, t1 + 26 * MIN)
        v = J.answer(oid, RELAY, c["nonce"], t1 + 26 * MIN + jobs_mod.CHALLENGE_TTL_MS + 1)
        self.assertEqual(FAILED, v["state"], "second consecutive failure ends it")
        self.assertEqual(0, L.balance("earned:" + RELAY))

    def test_four_blocks_per_relay_per_day(self):
        J, F, _, L, _ = fresh()
        ids = [stay(J, T0 + i * MIN)["id"] for i in range(4)]
        with self.assertRaises(JobsError) as cm:
            stay(J, T0 + 5 * MIN)
        self.assertEqual(jobs_mod.CAP_REACHED, cm.exception.reason)
        # a declined offer frees its slot; a completed one does not
        J.decline(ids[0], RELAY, T0 + 6 * MIN)
        self.assertEqual(RELEASED, F.reservation(ids[0])["state"])
        stay(J, T0 + 7 * MIN)
        with self.assertRaises(JobsError):
            stay(J, T0 + 8 * MIN)
        # another relay is not capped by this one; tomorrow is a new day
        stay(J, T0 + 9 * MIN, relay="bb" * 16)
        stay(J, T0 + DAY)
        self.assertEqual(4, J.my_offers(RELAY, T0 + 10 * MIN)["blocks_today"])
        self.assertEqual(0, L.net())

    def test_ordinary_online_time_earns_nothing(self):
        J, _, _, L, _ = fresh()
        # no offer, no block: a probe on nothing is refused, nothing is posted
        with self.assertRaises(JobsError):
            J.probe("nothing", True, T0)
        self.assertEqual(0, L.balance("earned:" + RELAY))
        self.assertEqual([], [p for p in L.postings if p["credit"].startswith("earned:")])

    def test_the_relay_terms_gate_the_offer(self):
        J, _, _, _, _ = fresh()
        J.set_settings(RELAY, T0, min_payout_centimes=3_000)
        with self.assertRaises(JobsError) as cm:
            stay(J)
        self.assertEqual(jobs_mod.BELOW_MINIMUM, cm.exception.reason)
        J.set_settings(RELAY, T0, min_payout_centimes=0, window_start_min=18 * 60, window_end_min=22 * 60)
        with self.assertRaises(JobsError) as cm:
            stay(J)     # T0 is 10:00 Brazzaville
        self.assertEqual(jobs_mod.OUTSIDE_WINDOW, cm.exception.reason)
        o = stay(J, T0 + 9 * H)   # 19:00
        self.assertEqual((18 * 60, 22 * 60), (o["window_start_min"], o["window_end_min"]), "the terms travel with the offer")
        J.set_settings(RELAY, T0, available=False)
        with self.assertRaises(JobsError) as cm:
            stay(J, T0 + 9 * H + MIN)
        self.assertEqual(jobs_mod.UNAVAILABLE, cm.exception.reason)


class MoveTest(unittest.TestCase):

    def test_a_move_needs_the_reservation_and_the_customers_acceptance(self):
        J, F, Q, L, _ = fresh()
        q = move_quote(Q)
        self.assertEqual(10_000, q["movement_fee"])
        with self.assertRaises(JobsError) as cm:
            J.request_move(CUSTOMER, RELAY, "cell-7", q["quote_id"], jobs_mod.FUND, "r1", T0)
        self.assertEqual(jobs_mod.NOT_ACCEPTED, cm.exception.reason)
        Q.accept(q["quote_id"], "CUSTOMER", CUSTOMER, T0, signature=q["signature"])
        with self.assertRaises(JobsError) as cm:
            J.request_move(CUSTOMER, RELAY, "cell-7", q["quote_id"], jobs_mod.FUND, "r1", T0)
        self.assertEqual(jobs_mod.NO_RESERVATION, cm.exception.reason)
        F.reserve(fund_mod.MOVE, "r1", 10_000, None, T0)
        o = J.request_move(CUSTOMER, RELAY, "cell-7", q["quote_id"], jobs_mod.FUND, "r1", T0)
        self.assertEqual((OFFERED, MOVE, 10_000, 5_000, "cell-7"), (o["state"], o["kind"], o["amount"], o["extra"], o["rendezvous_cell"]))
        # a quote without a movement fee is not an all-in quote
        plain = Q.quote(APPROVED_PAID, 10, 3, True, None, 1 * GB, T0)
        Q.accept(plain["quote_id"], "CUSTOMER", CUSTOMER, T0)
        with self.assertRaises(JobsError) as cm:
            J.request_move(CUSTOMER, RELAY, "cell-7", plain["quote_id"], jobs_mod.FUND, "r1", T0)
        self.assertEqual(jobs_mod.BAD_QUOTE, cm.exception.reason)
        # a reservation of the wrong purpose or amount does not count
        F.reserve(fund_mod.BLOCK, "r2", 10_000, None, T0)
        F.reserve(fund_mod.MOVE, "r3", 9_999, None, T0)
        with self.assertRaises(JobsError):
            J.request_move(CUSTOMER, RELAY, "cell-7", q["quote_id"], jobs_mod.FUND, "r2", T0)
        with self.assertRaises(JobsError):
            J.request_move(CUSTOMER, RELAY, "cell-7", q["quote_id"], jobs_mod.FUND, "r3", T0)

    def test_the_earning_moment_is_arrival_plus_confirmed_readiness(self):
        J, F, Q, L, _ = fresh()
        q = move_quote(Q)
        Q.accept(q["quote_id"], "CUSTOMER", CUSTOMER, T0)
        F.reserve(fund_mod.MOVE, "r1", 10_000, None, T0)
        o = J.request_move(CUSTOMER, RELAY, "cell-7", q["quote_id"], jobs_mod.FUND, "r1", T0)
        oid = o["id"]
        J.accept(oid, RELAY, T0 + MIN)
        with self.assertRaises(JobsError):
            J.arrived(oid, RELAY, T0 + 2 * MIN)          # not departed
        J.departed(oid, RELAY, T0 + 2 * MIN)
        with self.assertRaises(JobsError) as cm:
            J.customer_confirms_ready(CUSTOMER, oid, T0 + 3 * MIN)
        self.assertEqual(jobs_mod.WRONG_STATE if False else jobs_mod.NOT_READY, cm.exception.reason)
        with self.assertRaises(JobsError):
            J.ready(oid, RELAY, T0 + 3 * MIN)            # arrive first
        J.arrived(oid, RELAY, T0 + 10 * MIN)
        with self.assertRaises(JobsError) as cm:
            J.customer_confirms_ready(CUSTOMER, oid, T0 + 10 * MIN)
        self.assertEqual(jobs_mod.NOT_READY, cm.exception.reason, "arrived is not ready")
        J.ready(oid, RELAY, T0 + 11 * MIN)
        self.assertEqual(0, L.balance("earned:" + RELAY), "the relay's own word earns nothing yet")
        with self.assertRaises(JobsError) as cm:
            J.customer_confirms_ready("zz" * 16, oid, T0 + 12 * MIN)
        self.assertEqual(403, cm.exception.code)
        v = J.customer_confirms_ready(CUSTOMER, oid, T0 + 12 * MIN)
        self.assertEqual((COMPLETED, 10_000), (v["state"], v["earned"]))
        self.assertEqual(10_000, L.balance("earned:" + RELAY))
        self.assertEqual(SETTLED, F.reservation("r1")["state"])
        self.assertEqual(0, L.net())
        with self.assertRaises(JobsError):
            J.customer_confirms_ready(CUSTOMER, oid, T0 + 13 * MIN)   # once

    def test_cancellation_cases(self):
        J, F, Q, L, cfg = fresh()
        def new_move(tag, now):
            q = move_quote(Q, now)
            Q.accept(q["quote_id"], "CUSTOMER", CUSTOMER, now)
            F.reserve(fund_mod.MOVE, tag, 10_000, None, now)
            return J.request_move(CUSTOMER, RELAY, "cell-7", q["quote_id"], jobs_mod.FUND, tag, now)["id"]
        # by the customer before the relay departed: 0
        a = new_move("a", T0)
        J.accept(a, RELAY, T0 + MIN)
        v = J.cancel(a, CUSTOMER, T0 + 2 * MIN)
        self.assertEqual((CANCELLED, 0, "customer"), (v["state"], v["compensation"], v["cancel_by"]))
        self.assertEqual(RELEASED, F.reservation("a")["state"])
        # by the customer after departure: 50 % (a dated setting) from the same reservation
        b = new_move("b", T0 + H)
        J.accept(b, RELAY, T0 + H + MIN)
        J.departed(b, RELAY, T0 + H + 2 * MIN)
        v = J.cancel(b, CUSTOMER, T0 + H + 5 * MIN)
        self.assertEqual((CANCELLED, 5_000), (v["state"], v["compensation"]))
        self.assertEqual(5_000, L.balance("earned:" + RELAY))
        r = F.reservation("b")
        self.assertEqual((SETTLED, 5_000), (r["state"], r["settled_amount"]))
        # by the relay after departing: nothing
        c = new_move("c", T0 + 2 * H)
        J.accept(c, RELAY, T0 + 2 * H + MIN)
        J.departed(c, RELAY, T0 + 2 * H + 2 * MIN)
        v = J.cancel(c, RELAY, T0 + 2 * H + 5 * MIN)
        self.assertEqual((0, "relay"), (v["compensation"], v["cancel_by"]))
        self.assertEqual(RELEASED, F.reservation("c")["state"])
        # a stranger cannot cancel; a finished job cannot be cancelled
        d = new_move("d", T0 + 3 * H)
        with self.assertRaises(JobsError) as cm:
            J.cancel(d, "zz" * 16, T0 + 3 * H + MIN)
        self.assertEqual(403, cm.exception.code)
        self.assertEqual(5_000, L.balance("earned:" + RELAY))
        self.assertEqual(0, L.net())
        # the compensation percentage is a dated rule
        cfg.set(quotes.K_MOVE_CANCEL_PCT, 30, T0 + 4 * H, OPERATOR, "pilot review", T0 + 3 * H)
        e = new_move("e", T0 + 4 * H)
        J.accept(e, RELAY, T0 + 4 * H + MIN)
        J.departed(e, RELAY, T0 + 4 * H + 2 * MIN)
        self.assertEqual(3_000, J.cancel(e, CUSTOMER, T0 + 4 * H + 3 * MIN)["compensation"])

    def test_a_customer_funded_move_pays_from_the_hold(self):
        J, F, Q, L, _ = fresh()
        q = move_quote(Q)
        Q.accept(q["quote_id"], "CUSTOMER", CUSTOMER, T0)
        with self.assertRaises(JobsError) as cm:
            J.request_move(CUSTOMER, RELAY, "cell-7", q["quote_id"], jobs_mod.CUSTOMER, "hold-short", T0)
        self.assertEqual(jobs_mod.NO_RESERVATION, cm.exception.reason)
        oid = J.request_move(CUSTOMER, RELAY, "cell-7", q["quote_id"], jobs_mod.CUSTOMER, "hold-ok", T0)["id"]
        J.accept(oid, RELAY, T0 + MIN)
        J.departed(oid, RELAY, T0 + 2 * MIN)
        J.arrived(oid, RELAY, T0 + 8 * MIN)
        J.ready(oid, RELAY, T0 + 9 * MIN)
        J.customer_confirms_ready(CUSTOMER, oid, T0 + 10 * MIN)
        fee = [p for p in L.postings if p["kind"] == "MOVE_FEE"]
        self.assertEqual(1, len(fee))
        self.assertEqual(("held:" + CUSTOMER, "earned:" + RELAY, 10_000), (fee[0]["debit"], fee[0]["credit"], fee[0]["amount"]))
        self.assertEqual(100_000, L.balance(BUDGET), "the fund was not touched")

    def test_a_sponsored_move_is_funded_by_the_quotes_own_reservation(self):
        J, F, Q, L, _ = fresh()
        F.create_campaign(TREASURER, SPONSOR, "Ecole", ["bacongo"], T0 - H, T0 + DAY, 50_000, 5_000, T0 - H, campaign_id="camp1", daily_cap_centimes=50_000)
        F.clear_campaign(TREASURER, "camp1", "MTN", "MP1", T0 - H)
        q = move_quote(Q, sponsor="camp1")
        self.assertEqual(quotes.SPONSOR, q["paying_party"])
        self.assertEqual(RESERVED, F.reservation(q["quote_id"] + ":move")["state"])
        # no customer acceptance needed: the sponsor pays and reserved
        oid = J.request_move(CUSTOMER, RELAY, "cell-7", q["quote_id"], jobs_mod.FUND, q["quote_id"] + ":move", T0)["id"]
        J.accept(oid, RELAY, T0 + MIN)
        J.departed(oid, RELAY, T0 + 2 * MIN)
        J.arrived(oid, RELAY, T0 + 8 * MIN)
        J.ready(oid, RELAY, T0 + 9 * MIN)
        J.customer_confirms_ready(CUSTOMER, oid, T0 + 10 * MIN)
        self.assertEqual(10_000, L.balance("earned:" + RELAY))
        self.assertEqual(50_000 - 25_000 - 10_000, L.balance("campaign:camp1:budget"))
        self.assertEqual(0, L.net())


class CarryTest(unittest.TestCase):

    def test_carry_earns_only_through_the_settlement_reading_the_quote(self):
        J, F, Q, L, _ = fresh()
        q = Q.quote(APPROVED_PAID, 10, 3, True, None, 1 * GB, T0)
        o = J.offer_carry(RELAY, "bacongo", q, T0)
        self.assertEqual((CARRY, 5_000, OFFERED), (o["kind"], o["amount"], o["state"]))
        J.accept(o["id"], RELAY, T0 + MIN)
        self.assertEqual(0, L.balance("earned:" + RELAY), "accepting earns nothing")
        self.assertEqual([], [p for p in L.postings if "earned:" in p["credit"]])
        # the ledger asks what the relay's share of a settled session is: from the SIGNED quote
        self.assertEqual(5_000, J.relay_share_for({"quote_id": q["quote_id"], "gross": 25_000}))
        self.assertEqual(2_500, J.relay_share_for({"quote_id": q["quote_id"], "gross": 12_500}), "scaled to verified bytes")
        self.assertEqual(0, J.relay_share_for({"quote_id": q["quote_id"], "gross": 0}), "no bytes, no earning")
        self.assertEqual(5_000, J.relay_share_for({"quote_id": q["quote_id"], "gross": 30_000}), "never above the quoted share")
        self.assertEqual(0, J.relay_share_for({"quote_id": "nope", "gross": 25_000}))
        tampered = dict(q)
        tampered["relay_share"] = 20_000
        self.assertEqual(0, J.relay_share_for({"gross": 25_000}, quote=tampered))
        # a sponsored session: the customer paid zero, so scale by delivered bytes
        F.create_campaign(TREASURER, SPONSOR, "Ecole", [], T0 - H, T0 + DAY, 50_000, 0, T0 - H, campaign_id="camp1")
        F.clear_campaign(TREASURER, "camp1", "MTN", "MP1", T0 - H)
        qs = Q.quote(FREE, 0, 4, True, "camp1", 1 * GB, T0)
        self.assertEqual(2_500, J.relay_share_for({"quote_id": qs["quote_id"], "gross": 0, "bytes_down": 500 * MB}))
        self.assertEqual(0, J.relay_share_for({"quote_id": qs["quote_id"], "gross": 0, "bytes_down": 0}))
        # a direct quote names no relay: nothing to offer
        with self.assertRaises(JobsError):
            J.offer_carry(RELAY, "bacongo", Q.quote(APPROVED_PAID, 10, None, False, None, 1 * GB, T0), T0)


class LifecycleTest(unittest.TestCase):

    def test_decline(self):
        J, F, _, L, _ = fresh()
        o = stay(J)
        v = J.decline(o["id"], RELAY, T0 + MIN)
        self.assertEqual((DECLINED, "Refusé"), (v["state"], v["text"]))
        self.assertEqual(RELEASED, F.reservation(o["id"])["state"])
        self.assertEqual(100_000, L.balance(BUDGET))
        with self.assertRaises(JobsError):
            J.accept(o["id"], RELAY, T0 + 2 * MIN)
        # somebody else's offer
        o2 = stay(J, T0 + 2 * MIN)
        with self.assertRaises(JobsError) as cm:
            J.decline(o2["id"], "bb" * 16, T0 + 3 * MIN)
        self.assertEqual(403, cm.exception.code)
        # accepted, not started: still declinable
        J.accept(o2["id"], RELAY, T0 + 3 * MIN)
        self.assertEqual(DECLINED, J.decline(o2["id"], RELAY, T0 + 4 * MIN)["state"])
        self.assertEqual(0, L.net())

    def test_expiry(self):
        J, F, _, L, _ = fresh()
        o = stay(J)
        n = J.sweep(o["expires_at"] - 1)
        self.assertEqual(0, n["expired"])
        n = J.sweep(o["expires_at"])
        self.assertEqual(1, n["expired"])
        v = J.offer_view(J._offer(o["id"]), o["expires_at"])
        self.assertEqual((EXPIRED, "Expiré"), (v["state"], v["text"]))
        self.assertEqual(RELEASED, F.reservation(o["id"])["state"])
        self.assertEqual(100_000, L.balance(BUDGET))
        # accepting late expires it on the spot
        o2 = stay(J, T0 + H)
        with self.assertRaises(JobsError) as cm:
            J.accept(o2["id"], RELAY, o2["expires_at"] + 1)
        self.assertEqual(jobs_mod.EXPIRED_REASON, cm.exception.reason)
        self.assertEqual(EXPIRED, J._offer(o2["id"])["state"])
        # accepted but never started by expiry: expired too, money back
        o3 = stay(J, T0 + 2 * H)
        J.accept(o3["id"], RELAY, T0 + 2 * H + MIN)
        J.sweep(o3["expires_at"])
        self.assertEqual(EXPIRED, J._offer(o3["id"])["state"])
        self.assertEqual(RELEASED, F.reservation(o3["id"])["state"])
        self.assertEqual(0, L.net())

    def test_state_texts_match_the_shared_fixture_exactly(self):
        path = os.path.join(os.path.dirname(__file__), "fixtures", "relay_offer_states.txt")
        with open(path, encoding="utf-8") as f:
            lines = [l.strip() for l in f if l.strip() and not l.startswith("#")]
        fixture = dict(l.split("|", 1) for l in lines)
        self.assertEqual(fixture, OFFER_TEXT)
        self.assertEqual("Vérifié — gagné", OFFER_TEXT[COMPLETED])
        self.assertEqual("Non vérifié — rien gagné", OFFER_TEXT[FAILED])
        for state, text in OFFER_TEXT.items():
            if state != COMPLETED:
                self.assertNotIn("gagné", text.replace("rien gagné", ""), state)
        self.assertEqual(("Contrat en attente", "Vérifié", "Gagné", "Retrait demandé", "Payé"), PIPELINE)

    def test_my_offers_and_dispatch(self):
        J, F, Q, L, _ = fresh()
        o = stay(J)
        code, out = J.handle_get("/v1/jobs/mine", RELAY, {}, T0)
        self.assertEqual(200, code)
        self.assertEqual(1, len(out["offers"]))
        self.assertEqual("Proposé", out["offers"][0]["text"])
        self.assertEqual(2_000, out["pipeline"]["pending_centimes"])
        self.assertEqual(list(PIPELINE), out["pipeline"]["stages"])
        self.assertEqual(OFFER_TEXT, out["states"])
        code, out = J.handle_post("/v1/jobs/accept", RELAY, {"offer_id": o["id"]}, T0 + MIN)
        self.assertEqual((200, ACCEPTED), (code, out["state"]))
        code, out = J.handle_post("/v1/jobs/stay/start", RELAY, {"offer_id": o["id"]}, T0 + 2 * MIN)
        self.assertEqual((200, IN_PROGRESS), (code, out["state"]))
        code, out = J.handle_get("/v1/jobs/stay/challenge", RELAY, {"offer_id": o["id"]}, T0 + 3 * MIN)
        self.assertEqual(200, code)
        code, out = J.handle_post("/v1/jobs/stay/answer", RELAY, {"offer_id": o["id"], "nonce": out["nonce"]}, T0 + 3 * MIN + 1_000)
        self.assertEqual((200, 1), (code, out["probes_ok"]))
        self.assertEqual(403, J.handle_post("/v1/jobs/stay/probe", RELAY, {"offer_id": o["id"], "ok": True}, T0 + 4 * MIN)[0])
        self.assertEqual(200, J.handle_post("/v1/jobs/stay/probe", OPERATOR, {"offer_id": o["id"], "ok": True}, T0 + 13 * MIN)[0])
        code, out = J.handle_post("/v1/jobs/settings", RELAY, {"min_payout": 1_500, "battery_floor": 30, "window_start": 480, "window_end": 1320}, T0)
        self.assertEqual((200, 1_500, 30), (code, out["min_payout_centimes"], out["battery_floor_pct"]))
        code, out = J.handle_get("/v1/jobs/settings", RELAY, {}, T0)
        self.assertEqual((480, 1320, True), (out["window_start_min"], out["window_end_min"], out["available"]))
        code, out = J.handle_post("/v1/jobs/accept", RELAY, {"offer_id": "nope"}, T0)
        self.assertEqual(404, code)
        self.assertEqual(404, J.handle_get("/v1/jobs/nope", RELAY, {}, T0)[0])
        code, out = J.handle_get("/v1/jobs/states", RELAY, {}, T0)
        self.assertEqual(OFFER_TEXT, out["states"])
        # the move path end to end through the dispatcher
        q = move_quote(Q, T0)
        Q.handle_post("/v1/quotes/accept", CUSTOMER, {"quote_id": q["quote_id"], "signature": q["signature"], "role": "CUSTOMER"}, T0)
        F.reserve(fund_mod.MOVE, "r1", 10_000, None, T0)
        code, mv = J.handle_post("/v1/jobs/move/request", CUSTOMER, {"relay_id": RELAY, "rendezvous_cell": "cell-7", "quote_id": q["quote_id"], "funding_ref": "r1"}, T0)
        self.assertEqual(200, code, mv)
        self.assertEqual(200, J.handle_post("/v1/jobs/accept", RELAY, {"offer_id": mv["id"]}, T0 + MIN)[0])
        for p in ("departed", "arrived", "ready"):
            self.assertEqual(200, J.handle_post("/v1/jobs/move/" + p, RELAY, {"offer_id": mv["id"]}, T0 + 2 * MIN)[0])
        self.assertEqual(403, J.handle_post("/v1/jobs/move/confirm", RELAY, {"offer_id": mv["id"]}, T0 + 3 * MIN)[0], "the relay cannot confirm itself")
        code, out = J.handle_post("/v1/jobs/move/confirm", CUSTOMER, {"offer_id": mv["id"]}, T0 + 3 * MIN)
        self.assertEqual((200, COMPLETED), (code, out["state"]))
        self.assertEqual(10_000, L.balance("earned:" + RELAY))
        code, out = J.handle_get("/v1/jobs/offer", CUSTOMER, {"id": mv["id"]}, T0 + 4 * MIN)
        self.assertEqual(200, code, "the move's customer may read it")
        self.assertEqual(403, J.handle_get("/v1/jobs/offer", "bb" * 16, {"id": mv["id"]}, T0 + 4 * MIN)[0])
        self.assertEqual(0, L.net())


if __name__ == "__main__":
    unittest.main()
