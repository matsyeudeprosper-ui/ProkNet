"""v0.18.0: the ledger.

The rule under test everywhere here: a ledger cannot send money. It records what was
proved (a verified session, an operator message on the treasury phone) and what a person
still has to do by hand, and it must never let the same money be counted, reserved or
sent twice.
"""
import json
import os
import sqlite3
import tempfile
import threading
import time
import unittest
from http.server import ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from brain import app, evidence, ledger, settlement, signed_request
from brain.ledger import (APPROVED, CANCELLED, CONSUMED, DENIED, EXPIRED, IN_SESSION, Ledger, LedgerError,
                          MATCHED, NEEDS_ATTENTION, NEEDS_REVIEW, PAID, PRE_SESSION, REJECTED, RELEASED,
                          REQUESTED, SENT, STALE, UNASSIGNED, WITHDRAWAL_TEXT)
from tests.test_evidence import checkpoint_bytes, contract_bytes, keypair, node_id, pub_hex, sign

MB = 1_000_000
T0 = 1_700_000_000_000
BUYER = "aa" * 16
SELLER = "bb" * 16
RELAY = "cc" * 16
TREASURER = "dd" * 16
STRANGER = "ee" * 16
H = 3_600_000


def fresh(payments_live=False, test_ids=(BUYER,)):
    db = sqlite3.connect(":memory:", check_same_thread=False)
    return Ledger(db, treasury_ids=(TREASURER,), test_ids=test_ids, payments_live=payments_live)


def derived(sid="s1", gross=1_000, fee_pct=5, session="0102030405060708", buyer=BUYER, seller=SELLER):
    fee = (gross * fee_pct + 50) // 100
    return {"settlement_id": sid, "session_id": session, "buyer_id": buyer, "seller_id": seller,
            "checkpoint_hash": "cp" + sid, "contract_hash": "ch" + sid, "gross": gross,
            "prok_fee": fee, "seller_net": gross - fee, "fee_pct": fee_pct, "actor": "seller",
            "expires_at": T0 + 10 * H}


class DoubleEntryTest(unittest.TestCase):

    def test_every_posting_nets_to_zero_and_balances_are_sums(self):
        L = fresh()
        L.test_credit(TREASURER, BUYER, 5_000, T0)
        L.hold(BUYER, SELLER, 1_500, T0)
        L.post_settlement(derived(gross=1_000), T0 + 60_000)
        self.assertEqual(0, L.net_of_all_accounts())
        self.assertEqual(5_000 - 1_000, L.balance("credit:" + BUYER))
        self.assertEqual(0, L.balance("held:" + BUYER), "the hold's remainder went back to credit")
        self.assertEqual(950, L.balance("earned:" + SELLER))
        self.assertEqual(50, L.balance("prok:revenue"))
        self.assertEqual(5_000, L.balance("prok:testcredit"), "test credit is visible as such")

    def test_a_posting_cannot_move_nothing_or_less(self):
        L = fresh()
        with self.assertRaises(LedgerError):
            L._post(T0, "ADJUSTMENT", "a", "b", 0)
        with self.assertRaises(LedgerError):
            L._post(T0, "ADJUSTMENT", "a", "b", -5)

    def test_the_invariant_names_the_shortfall(self):
        L = fresh()
        L.test_credit(TREASURER, BUYER, 5_000, T0)
        inv = L.invariant()
        # test credit is a liability with no float behind it - that is exactly what the
        # invariant must show, not hide
        self.assertEqual(5_000, inv["liabilities"])
        self.assertEqual(0, inv["float"])
        self.assertEqual(5_000, inv["shortfall"])
        self.assertEqual(5_000, inv["test_credit_issued"])


class SessionPostingTest(unittest.TestCase):

    def test_a_settlement_posts_once(self):
        L = fresh()
        L.test_credit(TREASURER, BUYER, 5_000, T0)
        first = L.post_settlement(derived(), T0)
        second = L.post_settlement(derived(), T0 + 1)
        self.assertTrue(first["posted"])
        self.assertFalse(second["posted"])
        self.assertEqual("already", second["reason"])
        self.assertEqual(950, L.balance("earned:" + SELLER))

    def test_split_sums_to_gross_exactly(self):
        L = fresh()
        L.test_credit(TREASURER, BUYER, 100_000, T0)
        for gross in (1, 3, 99, 1_001, 12_345):
            d = derived(sid="s%d" % gross, gross=gross)
            L.post_settlement(d, T0)
        earned = L.balance("earned:" + SELLER)
        prok = L.balance("prok:revenue")
        self.assertEqual(1 + 3 + 99 + 1_001 + 12_345, earned + prok)
        self.assertEqual(0, L.net_of_all_accounts())

    def test_relay_share_only_when_a_relay_is_named(self):
        L = fresh()
        L.test_credit(TREASURER, BUYER, 10_000, T0)
        L.post_settlement(derived(sid="norelay"), T0)
        self.assertEqual(0, L.balance("earned:" + RELAY))
        L.post_settlement(derived(sid="relay"), T0, relay_id=RELAY, relay_centimes=100)
        self.assertEqual(100, L.balance("earned:" + RELAY))
        self.assertEqual(950 + 850, L.balance("earned:" + SELLER))
        with self.assertRaises(LedgerError):
            L.post_settlement(derived(sid="toomuch"), T0, relay_id=RELAY, relay_centimes=10_000)

    def test_a_session_without_a_hold_still_posts_and_may_go_negative(self):
        # a session that really happened is never hidden. The report shows the negative.
        L = fresh()
        L.test_credit(TREASURER, BUYER, 300, T0)
        out = L.post_settlement(derived(gross=1_000), T0)
        self.assertTrue(out["posted"])
        self.assertEqual(0, out["from_hold"])
        self.assertEqual(-700, L.balance("credit:" + BUYER))
        self.assertEqual(950, L.balance("earned:" + SELLER))

    def test_zero_gross_posts_nothing(self):
        L = fresh()
        self.assertFalse(L.post_settlement(derived(gross=0), T0)["posted"])


class HoldTest(unittest.TestCase):

    def setUp(self):
        self.L = fresh()
        self.L.test_credit(TREASURER, BUYER, 5_000, T0)

    def test_a_hold_reserves_credit_and_one_customer_holds_once(self):
        h = self.L.hold(BUYER, SELLER, 2_000, T0)
        self.assertEqual(PRE_SESSION, h["state"])
        self.assertEqual(3_000, self.L.balance("credit:" + BUYER))
        self.assertEqual(2_000, self.L.balance("held:" + BUYER))
        with self.assertRaises(LedgerError) as cm:
            self.L.hold(BUYER, "ff" * 16, 100, T0 + 1)
        self.assertEqual(ledger.HOLD_EXISTS, cm.exception.reason)
        self.assertEqual(409, cm.exception.code)

    def test_a_hold_cannot_exceed_credit(self):
        with self.assertRaises(LedgerError) as cm:
            self.L.hold(BUYER, SELLER, 5_001, T0)
        self.assertEqual(ledger.INSUFFICIENT_CREDIT, cm.exception.reason)

    def test_two_concurrent_holds_for_one_customer_one_wins(self):
        # the same connection, two threads, one active-hold rule. SQLite serialises the
        # writes; the loser must see HOLD_EXISTS, not a second reservation.
        path = os.path.join(tempfile.mkdtemp(), "l.db")
        results = []
        def go():
            db = sqlite3.connect(path, check_same_thread=False, timeout=5)
            L = Ledger(db, treasury_ids=(TREASURER,), test_ids=(BUYER,))
            try:
                results.append(L.hold(BUYER, SELLER, 1_000, T0)["state"])
            except LedgerError as e:
                results.append(e.reason)
        seed = Ledger(sqlite3.connect(path, check_same_thread=False), treasury_ids=(TREASURER,), test_ids=(BUYER,))
        seed.test_credit(TREASURER, BUYER, 5_000, T0)
        ts = [threading.Thread(target=go) for _ in range(2)]
        for t in ts: t.start()
        for t in ts: t.join()
        self.assertEqual(sorted([PRE_SESSION, ledger.HOLD_EXISTS]), sorted(results))

    def test_a_long_session_with_keepalives_never_loses_its_hold(self):
        h = self.L.hold(BUYER, SELLER, 2_000, T0)["hold_id"]
        self.L.hold_started(h, SELLER, "0102030405060708", T0 + 5_000)
        t = T0
        while t < T0 + 2 * H:                      # two hours, a keepalive every 30 s
            t += 30_000
            self.L.keepalive(h, SELLER, t)
            self.L.sweep(t)
        self.assertEqual(IN_SESSION, self.L.open_hold(BUYER)["state"])
        self.assertEqual(2_000, self.L.balance("held:" + BUYER))

    def test_keepalives_stop_then_stale_but_still_reserved_then_expired_after_a_day(self):
        h = self.L.hold(BUYER, SELLER, 2_000, T0)["hold_id"]
        self.L.hold_started(h, SELLER, "0102030405060708", T0)
        n = self.L.sweep(T0 + ledger.HOLD_KEEPALIVE_TTL_MS)
        self.assertEqual(1, n["holds_stale"])
        self.assertEqual(STALE, self.L.open_hold(BUYER)["state"])
        self.assertEqual(2_000, self.L.balance("held:" + BUYER), "stale is NOT released")
        # the seller may not release it either: a session may still settle
        with self.assertRaises(LedgerError):
            self.L.release_unused(h, SELLER, T0 + 20 * 60_000)
        self.L.sweep(T0 + ledger.HOLD_STALE_TTL_MS - 1)
        self.assertEqual(STALE, self.L.open_hold(BUYER)["state"])
        n = self.L.sweep(T0 + ledger.HOLD_STALE_TTL_MS)
        self.assertEqual(1, n["holds_expired_stale"])
        self.assertIsNone(self.L.open_hold(BUYER))
        self.assertEqual(5_000, self.L.balance("credit:" + BUYER))

    def test_evidence_at_twenty_hours_consumes_a_stale_hold(self):
        h = self.L.hold(BUYER, SELLER, 2_000, T0)["hold_id"]
        self.L.hold_started(h, SELLER, "0102030405060708", T0)
        self.L.sweep(T0 + 20 * H)
        out = self.L.post_settlement(derived(gross=1_200), T0 + 20 * H)
        self.assertEqual(1_200, out["from_hold"])
        row = self.L.db.execute("SELECT * FROM ledger_holds WHERE id=?", (h,)).fetchone()
        self.assertEqual(CONSUMED, row["state"])
        self.assertEqual(3_800, self.L.balance("credit:" + BUYER))
        self.assertEqual(0, self.L.balance("held:" + BUYER))

    def test_a_late_settlement_after_expiry_still_posts(self):
        h = self.L.hold(BUYER, SELLER, 2_000, T0)["hold_id"]
        self.L.hold_started(h, SELLER, "0102030405060708", T0)
        self.L.sweep(T0 + ledger.HOLD_STALE_TTL_MS + H)
        out = self.L.post_settlement(derived(gross=1_200), T0 + 30 * H)
        self.assertTrue(out["posted"])
        self.assertEqual(0, out["from_hold"])
        self.assertEqual(3_800, self.L.balance("credit:" + BUYER))

    def test_a_hold_that_never_started_expires_after_thirty_minutes_or_is_released_by_the_seller(self):
        h1 = self.L.hold(BUYER, SELLER, 1_000, T0)["hold_id"]
        self.assertEqual(RELEASED, self.L.release_unused(h1, SELLER, T0 + 1)["state"])
        h2 = self.L.hold(BUYER, SELLER, 1_000, T0 + 2)["hold_id"]
        self.L.sweep(T0 + 2 + ledger.HOLD_PRE_TTL_MS - 1)
        self.assertEqual(PRE_SESSION, self.L.open_hold(BUYER)["state"])
        self.L.sweep(T0 + 2 + ledger.HOLD_PRE_TTL_MS)
        self.assertIsNone(self.L.open_hold(BUYER))
        self.assertEqual(5_000, self.L.balance("credit:" + BUYER))
        self.assertEqual(EXPIRED, self.L.db.execute("SELECT state FROM ledger_holds WHERE id=?", (h2,)).fetchone()["state"])

    def test_only_the_seller_that_holds_may_touch_it(self):
        h = self.L.hold(BUYER, SELLER, 1_000, T0)["hold_id"]
        with self.assertRaises(LedgerError) as cm:
            self.L.hold_started(h, STRANGER, "01", T0)
        self.assertEqual(403, cm.exception.code)

    def test_the_seventh_hold_in_an_hour_is_refused(self):
        t = T0
        for i in range(ledger.MAX_HOLDS_PER_HOUR):
            seller = ("%02x" % (0x10 + i)) * 16       # different sellers, so the pair limit is not what trips
            h = self.L.hold(BUYER, seller, 100, t)["hold_id"]
            self.L.release_unused(h, seller, t + 1)
            t += 60_000
        with self.assertRaises(LedgerError) as cm:
            self.L.hold(BUYER, "77" * 16, 100, t)
        self.assertEqual(ledger.TOO_MANY_HOLDS, cm.exception.reason)
        self.assertEqual(429, cm.exception.code)
        # an hour later it is allowed again
        self.assertEqual(PRE_SESSION, self.L.hold(BUYER, "77" * 16, 100, T0 + ledger.HOLD_WINDOW_MS + 1)["state"])

    def test_the_fourth_hold_with_the_same_seller_in_an_hour_is_refused(self):
        t = T0
        for i in range(ledger.MAX_HOLDS_PER_PAIR_PER_HOUR):
            h = self.L.hold(BUYER, SELLER, 100, t)["hold_id"]
            self.L.release_unused(h, SELLER, t + 1)
            t += 60_000
        with self.assertRaises(LedgerError) as cm:
            self.L.hold(BUYER, SELLER, 100, t)
        self.assertEqual(ledger.TOO_MANY_HOLDS, cm.exception.reason)


class RolesAndSwitchTest(unittest.TestCase):

    def test_a_seller_cannot_post_a_topup_or_an_adjustment(self):
        L = fresh(payments_live=True)
        with self.assertRaises(LedgerError) as cm:
            L.observe_credit(SELLER, "MTN", "h1", 500, "sms1", T0)
        self.assertEqual(403, cm.exception.code)
        self.assertEqual(ledger.NOT_TREASURY, cm.exception.reason)
        with self.assertRaises(LedgerError):
            L.test_credit(SELLER, BUYER, 500, T0)
        rows = L.db.execute("SELECT * FROM ledger_audit WHERE allowed=0").fetchall()
        self.assertEqual(2, len(rows), "every refusal is an audit row")
        self.assertEqual(0, L.balance("credit:" + BUYER))

    def test_test_credit_only_to_a_listed_test_identity(self):
        L = fresh(test_ids=(BUYER,))
        with self.assertRaises(LedgerError) as cm:
            L.test_credit(TREASURER, STRANGER, 500, T0)
        self.assertEqual(ledger.NOT_TEST_IDENTITY, cm.exception.reason)
        self.assertEqual(500, L.test_credit(TREASURER, BUYER, 500, T0, "T81")["credit"])

    def test_payments_disabled_records_the_topup_and_credits_nobody(self):
        L = fresh(payments_live=False)
        out = L.observe_credit(TREASURER, "MTN", "sender1", 50_300, "sms-a", T0)
        self.assertEqual(REJECTED, out["state"])
        self.assertEqual(ledger.PAYMENTS_DISABLED, out["reason"])
        self.assertEqual(0, L._sum_prefix("credit:"))
        self.assertEqual(0, L.balance("float:mtn"))
        # and the review path cannot sneak a credit through either
        with self.assertRaises(LedgerError):
            L.review(TREASURER, out["topup_id"], True, T0)


class WithdrawalTest(unittest.TestCase):

    def setUp(self):
        self.L = fresh()
        self.L.test_credit(TREASURER, BUYER, 100_000, T0)
        self.L.post_settlement(derived(gross=1_000_000), T0)       # seller earned 950_000
        self.earned = self.L.balance("earned:" + SELLER)
        self.assertEqual(950_000, self.earned)

    def request(self, amount=500_000, t=T0 + 1):
        return self.L.request_withdrawal(SELLER, "MTN", "066123456", amount, t)["withdrawal"]

    def test_below_the_minimum_is_refused(self):
        with self.assertRaises(LedgerError) as cm:
            self.request(ledger.WITHDRAW_MIN_CENTIMES - 1)
        self.assertEqual(ledger.BELOW_MINIMUM, cm.exception.reason)

    def test_a_request_reserves_and_a_second_open_request_is_refused(self):
        w = self.request(500_000)
        self.assertEqual(REQUESTED, w["state"])
        self.assertEqual(450_000, self.L.balance("earned:" + SELLER), "reserved money left the earned account")
        self.assertEqual(500_000, self.L.balance("inflight:withdrawals"))
        with self.assertRaises(LedgerError) as cm:
            self.request(100_000, T0 + 2)
        self.assertEqual(ledger.WITHDRAWAL_OPEN_REASON, cm.exception.reason)
        with self.assertRaises(LedgerError):
            self.request(900_000, T0 + 3)  # would exceed what is left, and there is one open anyway

    def test_cancel_only_while_requested(self):
        w = self.request()
        out = self.L.cancel_withdrawal(SELLER, w["id"], T0 + 2)
        self.assertEqual(CANCELLED, out["withdrawal"]["state"])
        self.assertEqual(950_000, self.L.balance("earned:" + SELLER))
        w2 = self.request(500_000, T0 + 3)
        self.L.treasury_withdrawal(TREASURER, w2["id"], "approve", T0 + 4)
        with self.assertRaises(LedgerError):
            self.L.cancel_withdrawal(SELLER, w2["id"], T0 + 5)
        with self.assertRaises(LedgerError) as cm:
            self.L.cancel_withdrawal(STRANGER, w2["id"], T0 + 5)
        self.assertEqual(403, cm.exception.code)

    def test_state_texts_match_the_shared_fixture_exactly(self):
        path = os.path.join(os.path.dirname(__file__), "fixtures", "withdrawal_states.txt")
        with open(path, encoding="utf-8") as f:
            lines = [l.strip() for l in f if l.strip() and not l.startswith("#")]
        fixture = dict(l.split("|", 1) for l in lines)
        self.assertEqual(fixture, WITHDRAWAL_TEXT)
        self.assertEqual("Retrait demandé", WITHDRAWAL_TEXT[REQUESTED])
        self.assertEqual("Retrait demandé", WITHDRAWAL_TEXT[APPROVED])
        self.assertEqual("Envoi en cours", WITHDRAWAL_TEXT[SENT])
        self.assertEqual("Payé", WITHDRAWAL_TEXT[PAID])

    def test_the_treasury_flow_and_the_duplicate_send_guard(self):
        w = self.request()
        wid = w["id"]
        # a seller cannot approve its own
        with self.assertRaises(LedgerError) as cm:
            self.L.treasury_withdrawal(SELLER, wid, "approve", T0 + 2)
        self.assertEqual(403, cm.exception.code)
        # sent before approve: refused, audited
        with self.assertRaises(LedgerError) as cm:
            self.L.treasury_withdrawal(TREASURER, wid, "sent", T0 + 2)
        self.assertEqual(409, cm.exception.code)
        self.L.treasury_withdrawal(TREASURER, wid, "approve", T0 + 3)
        self.assertEqual("Retrait demandé", self.L.wallet(SELLER, T0 + 3)["withdrawal"]["text"])
        out = self.L.treasury_withdrawal(TREASURER, wid, "sent", T0 + 4)
        self.assertEqual(SENT, out["withdrawal"]["state"])
        self.assertEqual("Envoi en cours", out["withdrawal"]["text"])
        # the second tap
        with self.assertRaises(LedgerError) as cm:
            self.L.treasury_withdrawal(TREASURER, wid, "sent", T0 + 5)
        self.assertEqual(409, cm.exception.code)
        refused = self.L.db.execute("SELECT * FROM ledger_audit WHERE allowed=0 AND action='treasury.sent'").fetchall()
        self.assertEqual(2, len(refused))
        # the queue never lists a SENT row among the sends still to do
        q = self.L.queue(TREASURER, T0 + 5)
        self.assertEqual(0, q["summary"]["manual_sends_pending"])
        self.assertEqual(1, q["summary"]["sent_unconfirmed"])
        # paid by hand needs a reference
        with self.assertRaises(LedgerError):
            self.L.treasury_withdrawal(TREASURER, wid, "paid", T0 + 6)
        out = self.L.treasury_withdrawal(TREASURER, wid, "paid", T0 + 6, evidence="MP2409.1234")
        self.assertEqual(PAID, out["withdrawal"]["state"])
        self.assertEqual("treasurer:MP2409.1234", out["withdrawal"]["paid_evidence"])
        self.assertEqual(0, self.L.balance("inflight:withdrawals"))
        self.assertEqual(-500_000, self.L.balance("float:mtn"), "the float went down by what was sent")
        self.assertEqual(0, self.L.net_of_all_accounts())

    def test_the_honest_count_equals_the_sends_a_person_still_has_to_do(self):
        sellers = [("%02x" % (0x20 + i)) * 16 for i in range(3)]
        for i, s in enumerate(sellers):
            self.L.post_settlement(derived(sid="w%d" % i, gross=1_000_000, seller=s), T0)
            self.L.request_withdrawal(s, "MTN", "06612345%d" % i, 500_000, T0 + i)
        w0 = self.L.queue(TREASURER, T0 + 10)["rows"][0]["id"]
        self.L.treasury_withdrawal(TREASURER, w0, "approve", T0 + 11)
        self.L.treasury_withdrawal(TREASURER, w0, "sent", T0 + 12)
        s = self.L.summary(T0 + 13)
        self.assertEqual(2, s["manual_sends_pending"])
        self.assertEqual("2 retraits en attente = 2 envois manuels", s["sentence"])
        self.assertEqual(1, s["sent_unconfirmed"])
        one = fresh(); one.test_credit(TREASURER, BUYER, 100_000, T0); one.post_settlement(derived(gross=1_000_000), T0)
        one.request_withdrawal(SELLER, "MTN", "066123456", 500_000, T0)
        self.assertEqual("1 retrait en attente = 1 envoi manuel", one.summary(T0)["sentence"])

    def test_deny_returns_the_reserve_and_unsent_goes_back_to_approved(self):
        w = self.request()
        self.L.treasury_withdrawal(TREASURER, w["id"], "approve", T0 + 2)
        self.L.treasury_withdrawal(TREASURER, w["id"], "sent", T0 + 3)
        out = self.L.treasury_withdrawal(TREASURER, w["id"], "unsent", T0 + 4, memo="typo in number")
        self.assertEqual(APPROVED, out["withdrawal"]["state"])
        out = self.L.treasury_withdrawal(TREASURER, w["id"], "deny", T0 + 5, memo="number invalid")
        self.assertEqual(DENIED, out["withdrawal"]["state"])
        self.assertEqual(950_000, self.L.balance("earned:" + SELLER))
        self.assertEqual(0, self.L.balance("inflight:withdrawals"))

    def test_debit_sms_matches_exactly_one_sent_row(self):
        w = self.request()
        self.L.treasury_withdrawal(TREASURER, w["id"], "approve", T0 + 2)
        self.L.treasury_withdrawal(TREASURER, w["id"], "sent", T0 + 3)
        # wrong amount: not matched, recorded as an unmatched debit
        out = self.L.observe_debit(TREASURER, "MTN", ledger.msisdn_hash("066123456"), 4_000, "sms-x", T0 + 4)
        self.assertEqual("none", out["matched"])
        self.assertEqual(SENT, self.L._w(w["id"])["state"])
        out = self.L.observe_debit(TREASURER, "MTN", ledger.msisdn_hash("066123456"), 500_000, "sms-y", T0 + 5)
        self.assertEqual("one", out["matched"])
        self.assertEqual(PAID, self.L._w(w["id"])["state"])
        self.assertEqual("sms-y", self.L._w(w["id"])["paid_evidence"])
        # the same SMS again does nothing
        self.assertEqual("already", self.L.observe_debit(TREASURER, "MTN", ledger.msisdn_hash("066123456"), 500_000, "sms-y", T0 + 6)["matched"])

    def test_two_sent_rows_with_the_same_number_and_amount_are_not_auto_paid(self):
        other = "99" * 16
        self.L.post_settlement(derived(sid="o", gross=1_000_000, seller=other), T0)
        a = self.request()
        b = self.L.request_withdrawal(other, "MTN", "+242066123456", 500_000, T0 + 1)["withdrawal"]  # same number (a household)
        for w in (a, b):
            self.L.treasury_withdrawal(TREASURER, w["id"], "approve", T0 + 2)
            self.L.treasury_withdrawal(TREASURER, w["id"], "sent", T0 + 3)
        out = self.L.observe_debit(TREASURER, "MTN", ledger.msisdn_hash("066123456"), 500_000, "sms-z", T0 + 4)
        self.assertEqual("ambiguous", out["matched"])
        self.assertEqual(SENT, self.L._w(a["id"])["state"])
        self.assertEqual(SENT, self.L._w(b["id"])["state"])

    def test_sent_turns_amber_after_a_day_and_needs_attention_after_a_week(self):
        w = self.request()
        self.L.treasury_withdrawal(TREASURER, w["id"], "approve", T0 + 2)
        self.L.treasury_withdrawal(TREASURER, w["id"], "sent", T0 + 3)
        self.assertFalse(self.L.wallet(SELLER, T0 + 3 + ledger.SENT_AMBER_MS - 1)["withdrawal"]["amber"])
        self.assertTrue(self.L.wallet(SELLER, T0 + 3 + ledger.SENT_AMBER_MS)["withdrawal"]["amber"])
        self.L.sweep(T0 + 3 + ledger.SENT_ATTENTION_MS)
        v = self.L.wallet(SELLER, T0 + 3 + ledger.SENT_ATTENTION_MS)["withdrawal"]
        self.assertEqual(NEEDS_ATTENTION, v["state"])
        self.assertEqual("En vérification", v["text"])

    def test_approve_checks_the_rail_float_only_when_payments_are_live(self):
        live = fresh(payments_live=True)
        live.test_credit(TREASURER, BUYER, 100_000, T0)
        live.post_settlement(derived(gross=1_000_000), T0)
        w = live.request_withdrawal(SELLER, "MTN", "066123456", 500_000, T0)["withdrawal"]
        with self.assertRaises(LedgerError) as cm:
            live.treasury_withdrawal(TREASURER, w["id"], "approve", T0 + 1)
        self.assertEqual(ledger.INSUFFICIENT_CREDIT, cm.exception.reason)
        # in the pilot the treasurer sends from a wallet the ledger does not know: allowed
        w2 = self.request()
        self.assertEqual(APPROVED, self.L.treasury_withdrawal(TREASURER, w2["id"], "approve", T0 + 2)["withdrawal"]["state"])


class TopupTest(unittest.TestCase):

    def setUp(self):
        self.L = fresh(payments_live=True)

    def test_a_duplicate_sms_posts_nothing(self):
        i = self.L.create_intent(BUYER, "MTN", 50_000, T0)
        a = self.L.observe_credit(TREASURER, "MTN", "s1", i["send_exactly"], "sms1", T0 + 1)
        b = self.L.observe_credit(TREASURER, "MTN", "s1", i["send_exactly"], "sms1", T0 + 2)
        self.assertEqual(MATCHED, a["state"])
        self.assertTrue(b["duplicate"])
        self.assertEqual(i["send_exactly"], self.L.balance("credit:" + BUYER))
        self.assertEqual(i["send_exactly"], self.L.balance("float:mtn"))

    def test_an_unknown_sender_is_matched_by_the_unique_amount_tag_and_then_bound(self):
        i = self.L.create_intent(BUYER, "MTN", 50_000, T0)
        self.assertFalse(i["bound"])
        self.assertNotEqual(50_000, i["send_exactly"])
        self.assertTrue(50_000 < i["send_exactly"] <= 50_000 + 99 * 100)
        out = self.L.observe_credit(TREASURER, "MTN", "s1", i["send_exactly"], "sms1", T0 + 1)
        self.assertEqual("tag", out["matched_by"])
        self.assertEqual(BUYER, out["customer_id"])
        # bound now: the next intent needs no tag and a plain transfer matches
        i2 = self.L.create_intent(BUYER, "MTN", 20_000, T0 + 2)
        self.assertTrue(i2["bound"])
        self.assertEqual(20_000, i2["send_exactly"])
        out = self.L.observe_credit(TREASURER, "MTN", "s1", 20_000, "sms2", T0 + 3)
        self.assertEqual("binding", out["matched_by"])
        self.assertEqual(i["send_exactly"] + 20_000, self.L.balance("credit:" + BUYER))

    def test_two_open_intents_never_share_a_tag(self):
        a = self.L.create_intent(BUYER, "MTN", 50_000, T0)
        b = self.L.create_intent(STRANGER, "MTN", 50_000, T0 + 1)
        self.assertNotEqual(a["send_exactly"], b["send_exactly"])

    def test_anything_else_is_unassigned_and_a_number_alone_claims_nothing(self):
        out = self.L.observe_credit(TREASURER, "MTN", "s9", 30_000, "sms9", T0)
        self.assertEqual(UNASSIGNED, out["state"])
        self.assertEqual(0, self.L._sum_prefix("credit:"))
        self.assertEqual(30_000, self.L.balance("float:mtn"), "the money did arrive")
        self.assertEqual(30_000, self.L.balance(ledger.UNASSIGNED_ACCOUNT), "and sits in suspense, owed to somebody")
        self.assertEqual(30_000, self.L.invariant()["liabilities"])
        with self.assertRaises(LedgerError) as cm:
            self.L.claim(BUYER, "MTN", "s9", 0, "", T0 + 1)
        self.assertEqual(ledger.CLAIM_INCOMPLETE, cm.exception.reason)
        with self.assertRaises(LedgerError):
            self.L.claim(BUYER, "MTN", "s9", 30_000, "   ", T0 + 1)
        with self.assertRaises(LedgerError):
            self.L.claim(BUYER, "MTN", "s9", 29_000, "MP1", T0 + 1)   # wrong amount
        out = self.L.claim(BUYER, "MTN", "s9", 30_000, "MP240925.777", T0 + 1)
        self.assertEqual(NEEDS_REVIEW, out["state"])
        self.assertEqual(0, self.L.balance("credit:" + BUYER), "a claim credits nothing by itself")
        # the treasurer decides
        with self.assertRaises(LedgerError):
            self.L.review(SELLER, out["topup_id"], True, T0 + 2)
        self.L.review(TREASURER, out["topup_id"], True, T0 + 2)
        self.assertEqual(30_000, self.L.balance("credit:" + BUYER))
        self.assertEqual(0, self.L.balance(ledger.UNASSIGNED_ACCOUNT))
        self.assertEqual(30_000, self.L.balance("float:mtn"), "the float did not rise twice")
        self.assertEqual(0, self.L.net_of_all_accounts())
        self.assertIsNotNone(self.L.db.execute("SELECT 1 FROM ledger_bindings WHERE sender_hash='s9'").fetchone())

    def test_a_rejected_review_goes_back_to_unassigned(self):
        out = self.L.observe_credit(TREASURER, "MTN", "s9", 30_000, "sms9", T0)
        self.L.claim(BUYER, "MTN", "s9", 30_000, "ref", T0 + 1)
        self.L.review(TREASURER, out["topup_id"], False, T0 + 2, memo="reference does not match")
        row = self.L.db.execute("SELECT * FROM ledger_topups WHERE id=?", (out["topup_id"],)).fetchone()
        self.assertEqual(UNASSIGNED, row["state"])
        self.assertEqual("", row["customer_id"])
        items = self.L.review_list(TREASURER, T0 + ledger.UNCLAIMED_REVIEW_MS + 3)
        self.assertTrue(items[0]["stale"])


class MsisdnTest(unittest.TestCase):

    def test_the_fixture_is_what_this_code_computes(self):
        path = os.path.join(os.path.dirname(__file__), "fixtures", "msisdn_hash.txt")
        with open(path, encoding="utf-8") as f:
            rows = [l.strip().split("|") for l in f if l.strip() and not l.startswith("#")]
        self.assertGreaterEqual(len(rows), 8)
        for raw, digits, h in rows:
            self.assertEqual(digits, ledger.msisdn_digits(raw), raw)
            self.assertEqual(h, ledger.msisdn_hash(raw), raw)
        # every spelling of one number is one hash; a different number is a different hash
        self.assertEqual(ledger.msisdn_hash("066123456"), ledger.msisdn_hash("+242 06 612 34 56"))
        self.assertNotEqual(ledger.msisdn_hash("066123456"), ledger.msisdn_hash("055987654"))
        self.assertEqual("", ledger.msisdn_hash("abc"))

    def test_a_withdrawal_needs_a_real_number_and_only_the_treasurer_sees_it(self):
        L = fresh()
        L.test_credit(TREASURER, BUYER, 100_000, T0)
        L.post_settlement(derived(gross=100_000), T0)
        with self.assertRaises(LedgerError):
            L.request_withdrawal(SELLER, "MTN", "12", 50_000, T0)
        w = L.request_withdrawal(SELLER, "MTN", "+242 06 612 34 56", 50_000, T0)["withdrawal"]
        self.assertNotIn("msisdn", w)
        self.assertEqual("3456", w["msisdn_tail"])
        row = L.queue(TREASURER, T0)["rows"][0]
        self.assertEqual("066123456", row["msisdn"])
        self.assertEqual(ledger.msisdn_hash("066123456"), row["msisdn_hash"])
        # the number never reaches the audit table
        for r in L.db.execute("SELECT * FROM ledger_audit").fetchall():
            self.assertNotIn("066123456", r["detail"] + r["target"])


class LedgerUpgradeTest(unittest.TestCase):
    """A Brain on schema 4 with real money rows upgrades to 5 and keeps every row."""

    def test_schema_four_becomes_five_and_nothing_moves(self):
        from brain import db as braindb
        path = os.path.join(tempfile.mkdtemp(), "brain.db")
        con = sqlite3.connect(path)
        for sql in braindb.MIGRATIONS[:4]:
            con.executescript(sql)
        for v in (1, 2, 3, 4):
            con.execute("INSERT INTO schema_version(version) VALUES (?)", (v,))
        con.commit()
        con.close()
        s = settlement.Settlements(path)
        s.report({"settlement_id": settlement.settlement_id("sess", "ch", "cp"), "session_id": "sess",
                  "buyer_id": BUYER, "seller_id": SELLER, "contract_hash": "ch", "checkpoint_hash": "cp",
                  "gross": 7_300, "fee_pct": 5, "expires_at": 9_999_999_999_999}, "buyer", T0)
        before = [tuple(r) for r in sqlite3.connect(path).execute("SELECT * FROM settlements")]
        b = braindb.Brain(path)
        self.assertEqual(5, b.schema_version())
        names = set(r[0] for r in b.db.execute("SELECT name FROM sqlite_master WHERE type='table'"))
        for t in ("ledger_postings", "ledger_holds", "ledger_withdrawals", "ledger_topups", "ledger_intents",
                  "ledger_bindings", "ledger_audit", "ledger_balance_checks"):
            self.assertIn(t, names)
        idx = set(r[0] for r in b.db.execute("SELECT name FROM sqlite_master WHERE type='index'"))
        self.assertIn("ledger_withdrawals_one_open", idx)
        self.assertIn("ledger_postings_once", idx)
        b.db.close()
        self.assertEqual(before, [tuple(r) for r in sqlite3.connect(path).execute("SELECT * FROM settlements")])


class LedgerApiTest(unittest.TestCase):
    """The routes, over a real socket, with signed requests: who may do what."""

    seq = 0

    @classmethod
    def setUpClass(cls):
        app.STATE = app.State(":memory:")
        cls.server = ThreadingHTTPServer(("127.0.0.1", 0), app.Handler)
        cls.port = cls.server.server_address[1]
        cls.thread = threading.Thread(target=cls.server.serve_forever, daemon=True)
        cls.thread.start()

    @classmethod
    def tearDownClass(cls):
        cls.server.shutdown()
        cls.server.server_close()

    def setUp(self):
        self.buyer = keypair()
        self.seller = keypair()
        self.treasurer = keypair()
        L = app.STATE.ledger
        L.treasury_ids = {node_id(self.treasurer)}
        L.test_ids = {node_id(self.buyer)}
        L.payments_live = False

    def call(self, method, path, signer, body=None):
        raw = json.dumps(body).encode("utf-8") if body is not None else b""
        ts = int(time.time() * 1000)
        LedgerApiTest.seq += 1
        nonce = "n%d-%d" % (ts, LedgerApiTest.seq)   # never the clock alone: two calls a millisecond apart are a replay
        line = signed_request.signing_line(ts, nonce, signed_request.body_hash(raw), method=method, path=path)
        h = {"Content-Type": "application/json", "X-Prok-Identity": pub_hex(signer), "X-Prok-Timestamp": str(ts),
             "X-Prok-Nonce": nonce, "X-Prok-Signature": sign(signer, line)}
        req = Request("http://127.0.0.1:%d%s" % (self.port, path), data=(raw if method == "POST" else None), headers=h, method=method)
        try:
            with urlopen(req, timeout=10) as r:
                return r.status, json.loads(r.read().decode("utf-8") or "{}")
        except HTTPError as e:
            return e.code, json.loads(e.read().decode("utf-8") or "{}")

    def submit_session(self, used_bytes=3 * MB, session=None, submitter=None, budget=5_000):
        submitter = submitter or self.seller
        session = session or bytes([len(app.STATE.settlements.db.execute("SELECT 1 FROM settlements").fetchall()) + 1]) * 8
        contract = contract_bytes(session, bytes.fromhex(node_id(self.buyer)), bytes.fromhex(node_id(self.seller)), budget=budget)
        c = evidence.Contract(contract)
        cp = checkpoint_bytes(session, 1, used_bytes // 2, used_bytes - used_bytes // 2, c.cost_for(used_bytes))
        body = {"contract": contract.hex(),
                "buyer_contract_sig": sign(self.buyer, evidence.CONTRACT_DOMAIN + contract),
                "seller_contract_sig": sign(self.seller, evidence.CONTRACT_DOMAIN + contract),
                "checkpoint": cp.hex(),
                "seller_checkpoint_sig": sign(self.seller, evidence.CHECKPOINT_DOMAIN + cp),
                "buyer_checkpoint_sig": sign(self.buyer, evidence.CHECKPOINT_DOMAIN + cp),
                "buyer_pub": pub_hex(self.buyer), "seller_pub": pub_hex(self.seller), "submitter_pub": pub_hex(submitter)}
        return self.call("POST", "/v1/settlements", submitter, body), session.hex()

    def test_verified_evidence_becomes_earnings_once(self):
        code, out = self.call("POST", "/v1/ledger/treasury/test_credit", self.treasurer,
                              {"target": node_id(self.buyer), "amount": 100_000, "memo": "T80"})
        self.assertEqual(200, code, out)
        (code, out), session_hex = self.submit_session()
        self.assertEqual(200, code, out)
        self.assertTrue(out["ledger"]["posted"])
        gross = out["gross"]
        code, w = self.call("GET", "/v1/ledger/wallet", self.seller)
        self.assertEqual(200, code)
        self.assertEqual(out["seller_net"], w["earned"])
        code, wb = self.call("GET", "/v1/ledger/wallet", self.buyer)
        self.assertEqual(100_000 - gross, wb["credit"])
        # the buyer corroborates the same evidence: nothing posts twice
        (code2, out2), _ = self.submit_session(session=bytes.fromhex(session_hex), submitter=self.buyer)
        self.assertEqual(200, code2, out2)
        self.assertTrue(out2["agreed"])
        self.assertFalse(out2["ledger"]["posted"])
        self.assertEqual(out["seller_net"], self.call("GET", "/v1/ledger/wallet", self.seller)[1]["earned"])
        self.assertEqual(100_000 - gross, self.call("GET", "/v1/ledger/wallet", self.buyer)[1]["credit"])

    def test_every_ledger_read_is_signed_and_treasury_routes_refuse_a_seller(self):
        req = Request("http://127.0.0.1:%d/v1/ledger/wallet" % self.port)
        with self.assertRaises(HTTPError) as cm:
            urlopen(req, timeout=5)
        self.assertEqual(401, cm.exception.code)
        for path in ("/v1/ledger/treasury/queue", "/v1/ledger/treasury/summary", "/v1/ledger/treasury/review", "/v1/ledger/treasury/audit"):
            code, out = self.call("GET", path, self.seller)
            self.assertEqual(403, code, path)
            self.assertEqual(ledger.NOT_TREASURY, out["reason"])
        code, out = self.call("POST", "/v1/ledger/treasury/topup", self.seller,
                              {"rail": "MTN", "sender_hash": "x", "amount": 500, "sms_hash": "h"})
        self.assertEqual(403, code)
        code, out = self.call("GET", "/v1/ledger/treasury/queue", self.treasurer)
        self.assertEqual(200, code)
        self.assertIn("manual_sends_pending", out["summary"])

    def test_hold_then_session_then_withdrawal_then_treasury_over_http(self):
        self.call("POST", "/v1/ledger/treasury/test_credit", self.treasurer, {"target": node_id(self.buyer), "amount": 1_000_000})
        code, h = self.call("POST", "/v1/ledger/hold", self.seller, {"customer_id": node_id(self.buyer), "amount": 200_000})
        self.assertEqual(200, code, h)
        code, out = self.call("POST", "/v1/ledger/hold", self.seller, {"customer_id": node_id(self.buyer), "amount": 100})
        self.assertEqual(409, code)
        self.assertEqual(ledger.HOLD_EXISTS, out["reason"])
        # 600 MB at 3 CFA/MB = 1,800 CFA gross, above the withdrawal minimum
        (code, s), session_hex = self.submit_session(used_bytes=600 * MB, budget=200_000)
        self.assertEqual(200, code, s)
        self.assertGreaterEqual(s["gross"], ledger.WITHDRAW_MIN_CENTIMES)
        self.assertLess(s["gross"], 200_000)
        self.assertEqual(s["gross"], s["ledger"]["from_hold"], "the session was paid from the hold")
        code, out = self.call("POST", "/v1/ledger/hold/started", self.seller, {"hold_id": h["hold_id"], "session_hex": session_hex})
        # the session already settled and consumed the hold (the seller reported late): 409 is the honest answer
        self.assertEqual(409, code)
        code, w = self.call("GET", "/v1/ledger/wallet", self.buyer)
        self.assertIsNone(w["hold"], "consumed")
        self.assertEqual(1_000_000 - s["gross"], w["credit"], "the hold's remainder came back")
        # withdraw
        code, out = self.call("POST", "/v1/ledger/withdraw", self.seller, {"rail": "AIRTEL", "msisdn": "055987654", "amount": 100})
        self.assertEqual(400, code)
        self.assertEqual(ledger.BELOW_MINIMUM, out["reason"])
        amount = s["seller_net"]
        self.assertGreaterEqual(amount, ledger.WITHDRAW_MIN_CENTIMES)
        code, out = self.call("POST", "/v1/ledger/withdraw", self.seller, {"rail": "AIRTEL", "msisdn": "055987654", "amount": amount})
        self.assertEqual(200, code, out)
        wid = out["withdrawal"]["id"]
        self.assertEqual("Retrait demandé", out["withdrawal"]["text"])
        code, q = self.call("GET", "/v1/ledger/treasury/queue", self.treasurer)
        self.assertEqual(1, q["summary"]["manual_sends_pending"])
        for action in ("approve", "sent"):
            code, out = self.call("POST", "/v1/ledger/treasury/withdrawal", self.treasurer, {"withdrawal_id": wid, "action": action})
            self.assertEqual(200, code, out)
        code, out = self.call("POST", "/v1/ledger/treasury/withdrawal", self.treasurer, {"withdrawal_id": wid, "action": "sent"})
        self.assertEqual(409, code)
        code, out = self.call("POST", "/v1/ledger/treasury/debit", self.treasurer,
                              {"rail": "AIRTEL", "counterparty_hash": ledger.msisdn_hash("055987654"), "amount": amount, "sms_hash": "debit-1"})
        self.assertEqual("one", out["matched"])
        code, w = self.call("GET", "/v1/ledger/wallet", self.seller)
        self.assertEqual("Payé", w["withdrawal"]["text"])
        self.assertEqual(0, w["withdrawable"])

    def test_payments_disabled_over_http(self):
        code, out = self.call("POST", "/v1/ledger/treasury/topup", self.treasurer,
                              {"rail": "MTN", "sender_hash": "x", "amount": 500, "sms_hash": "h-disabled"})
        self.assertEqual(200, code)
        self.assertEqual(REJECTED, out["state"])
        self.assertEqual(ledger.PAYMENTS_DISABLED, out["reason"])
        code, i = self.call("POST", "/v1/ledger/intent", self.buyer, {"rail": "MTN", "amount": 500})
        self.assertEqual(200, code)
        self.assertFalse(i["payments_live"])


if __name__ == "__main__":
    unittest.main()
