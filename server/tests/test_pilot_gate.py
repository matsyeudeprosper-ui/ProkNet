"""v0.18.2: the two gaps ChatGPT's review found.

1. PROK_PAYMENTS_LIVE=1 must open real money only to the pilot allowlist (PROK_PILOT_IDS):
   an unlisted identity is never shown a treasury number and is never credited from an
   observed message, however valid the message is. Tested over the real HTTP handler.
2. A withdrawal may be approved only against a balance the treasurer typed for that rail
   within the last day that is not below the ledger's expectation. Missing, stale, low
   and matching balances are each tested.
"""
import json
import sqlite3
import threading
import time
import unittest
from http.server import ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from brain import app, ledger, signed_request
from brain.ledger import APPROVED, MATCHED, NEEDS_REVIEW, Ledger, LedgerError
from tests.test_evidence import keypair, node_id, pub_hex, sign
from tests.test_ledger import BUYER, SELLER, STRANGER, T0, TREASURER, derived, fresh

NUM = "066123456"
H = 3_600_000


class PilotAllowlistApiTest(unittest.TestCase):
    """Over a real socket, with signed requests, payments LIVE and a treasury number set."""

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
        self.listed = keypair()
        self.unlisted = keypair()
        self.treasurer = keypair()
        L = app.STATE.ledger
        L.treasury_ids = {node_id(self.treasurer)}
        L.pilot_ids = {node_id(self.listed)}
        L.payments_live = True
        L.treasury_msisdn = {"MTN": "060000000"}

    def call(self, method, path, signer, body=None):
        raw = json.dumps(body).encode("utf-8") if body is not None else b""
        ts = int(time.time() * 1000)
        PilotAllowlistApiTest.seq += 1
        nonce = "p%d-%d" % (ts, PilotAllowlistApiTest.seq)
        line = signed_request.signing_line(ts, nonce, signed_request.body_hash(raw), method=method, path=path)
        h = {"Content-Type": "application/json", "X-Prok-Identity": pub_hex(signer), "X-Prok-Timestamp": str(ts),
             "X-Prok-Nonce": nonce, "X-Prok-Signature": sign(signer, line)}
        req = Request("http://127.0.0.1:%d%s" % (self.port, path), data=(raw if method == "POST" else None), headers=h, method=method)
        try:
            with urlopen(req, timeout=10) as r:
                return r.status, json.loads(r.read().decode("utf-8") or "{}")
        except HTTPError as e:
            return e.code, json.loads(e.read().decode("utf-8") or "{}")

    def test_only_a_listed_identity_is_shown_the_treasury_number(self):
        code, on = self.call("POST", "/v1/ledger/intent", self.listed, {"rail": "MTN", "amount": 50_000})
        self.assertEqual(200, code, on)
        self.assertTrue(on["payments_live"]); self.assertTrue(on["pilot"])
        self.assertEqual("060000000", on["pay_to"])
        code, off = self.call("POST", "/v1/ledger/intent", self.unlisted, {"rail": "MTN", "amount": 50_000})
        self.assertEqual(200, code, off)
        self.assertTrue(off["payments_live"], "payments are live for the server")
        self.assertFalse(off["pilot"])
        self.assertEqual("", off["pay_to"], "but this identity is shown nowhere to send money")
        code, w = self.call("GET", "/v1/ledger/wallet", self.unlisted)
        self.assertFalse(w["pilot"])
        code, w = self.call("GET", "/v1/ledger/wallet", self.listed)
        self.assertTrue(w["pilot"])

    def test_a_valid_message_for_an_unlisted_identity_credits_nobody(self):
        # the unlisted identity registers an intent (it can), and a perfectly valid operator
        # message for exactly that tagged amount arrives on the treasury phone
        suspense_before = app.STATE.ledger.balance(ledger.UNASSIGNED_ACCOUNT)
        code, i = self.call("POST", "/v1/ledger/intent", self.unlisted, {"rail": "MTN", "amount": 50_000})
        code, out = self.call("POST", "/v1/ledger/treasury/topup", self.treasurer,
                              {"rail": "MTN", "sender_hash": ledger.msisdn_hash(NUM), "amount": i["send_exactly"], "sms_hash": "sms-unlisted-1"})
        self.assertEqual(200, code, out)
        self.assertEqual(NEEDS_REVIEW, out["state"])
        self.assertEqual(ledger.NOT_IN_PILOT, out["reason"])
        code, w = self.call("GET", "/v1/ledger/wallet", self.unlisted)
        self.assertEqual(0, w["credit"], "held for a person, never spendable by itself")
        # the money is on the books, in suspense, not lost
        self.assertEqual(suspense_before + i["send_exactly"], app.STATE.ledger.balance(ledger.UNASSIGNED_ACCOUNT))
        # and even a treasurer cannot credit it until the identity is listed
        code, r = self.call("POST", "/v1/ledger/treasury/review", self.treasurer, {"topup_id": out["topup_id"], "confirm": True})
        self.assertEqual(403, code)
        self.assertEqual(ledger.NOT_IN_PILOT, r["reason"])
        # the same message for a LISTED identity is credited
        code, i2 = self.call("POST", "/v1/ledger/intent", self.listed, {"rail": "MTN", "amount": 50_000})
        code, out2 = self.call("POST", "/v1/ledger/treasury/topup", self.treasurer,
                               {"rail": "MTN", "sender_hash": ledger.msisdn_hash("055987654"), "amount": i2["send_exactly"], "sms_hash": "sms-listed-1"})
        self.assertEqual(MATCHED, out2["state"], out2)
        code, w = self.call("GET", "/v1/ledger/wallet", self.listed)
        self.assertEqual(i2["send_exactly"], w["credit"])

    def test_a_bound_unlisted_sender_is_not_credited_either(self):
        # list, top up once (binds the number), then unlist: the next message from the
        # bound number must NOT credit automatically
        L = app.STATE.ledger
        L.pilot_ids.add(node_id(self.unlisted))
        code, i = self.call("POST", "/v1/ledger/intent", self.unlisted, {"rail": "MTN", "amount": 20_000})
        code, out = self.call("POST", "/v1/ledger/treasury/topup", self.treasurer,
                              {"rail": "MTN", "sender_hash": ledger.msisdn_hash("066111111"), "amount": i["send_exactly"], "sms_hash": "sms-bind-1"})
        self.assertEqual(MATCHED, out["state"])
        L.pilot_ids.discard(node_id(self.unlisted))
        code, out = self.call("POST", "/v1/ledger/treasury/topup", self.treasurer,
                              {"rail": "MTN", "sender_hash": ledger.msisdn_hash("066111111"), "amount": 30_000, "sms_hash": "sms-bind-2"})
        self.assertEqual(NEEDS_REVIEW, out["state"])
        self.assertEqual(ledger.NOT_IN_PILOT, out["reason"])
        code, w = self.call("GET", "/v1/ledger/wallet", self.unlisted)
        self.assertEqual(i["send_exactly"], w["credit"], "only the first, listed top-up")


class ApprovalNeedsTodaysBalanceTest(unittest.TestCase):

    def setUp(self):
        self.L = fresh(balances=False)          # this class tests the balance rule itself
        self.L.test_credit(TREASURER, BUYER, 1_000_000, T0)
        self.L.post_settlement(derived(gross=200_000), T0)          # seller earned 190_000
        self.w = self.L.request_withdrawal(SELLER, "MTN", NUM, 100_000, T0 + 1)["withdrawal"]

    def approve(self, t):
        return self.L.treasury_withdrawal(TREASURER, self.w["id"], "approve", t)

    def test_missing_balance_blocks(self):
        with self.assertRaises(LedgerError) as cm:
            self.approve(T0 + 2)
        self.assertEqual(ledger.RECONCILIATION_ALERT, cm.exception.reason)
        self.assertIn("type today", str(cm.exception))
        self.assertEqual(ledger.BLOCK_NO_CHECK, self.L.reconcile(TREASURER, T0 + 2)["rails"]["MTN"]["approval_block"])
        self.assertTrue(self.L.reconcile(TREASURER, T0 + 2)["approvals_blocked"])

    def test_a_balance_on_the_other_rail_does_not_count(self):
        self.L.balance_check(TREASURER, "AIRTEL", 0, T0 + 2)
        with self.assertRaises(LedgerError):
            self.approve(T0 + 3)
        self.assertEqual("", self.L.reconcile(TREASURER, T0 + 3)["rails"]["AIRTEL"]["approval_block"])

    def test_stale_balance_blocks(self):
        self.L.balance_check(TREASURER, "MTN", 0, T0 + 2)
        with self.assertRaises(LedgerError) as cm:
            self.approve(T0 + 2 + ledger.BALANCE_CHECK_MAX_AGE_MS + 1)
        self.assertIn("older than a day", str(cm.exception))
        self.assertEqual(ledger.BLOCK_STALE_CHECK, self.L.reconcile(TREASURER, T0 + 2 + ledger.BALANCE_CHECK_MAX_AGE_MS + 1)["rails"]["MTN"]["approval_block"])

    def test_low_balance_blocks(self):
        # give the MTN float something to expect
        self.L.payments_live = True; self.L.pilot_ids.add(STRANGER)
        i = self.L.create_intent(STRANGER, "MTN", 50_000, T0 + 1)
        self.L.observe_credit(TREASURER, "MTN", ledger.msisdn_hash("055000000"), i["send_exactly"], "s-low", T0 + 2)
        expected = self.L.balance("float:mtn")
        self.L.balance_check(TREASURER, "MTN", expected - 100, T0 + 3)
        with self.assertRaises(LedgerError) as cm:
            self.approve(T0 + 4)
        self.assertIn("below", str(cm.exception))
        self.assertEqual(ledger.BLOCK_BELOW, self.L.reconcile(TREASURER, T0 + 4)["rails"]["MTN"]["approval_block"])

    def test_a_matching_recent_balance_allows_and_the_block_reads_empty(self):
        self.L.balance_check(TREASURER, "MTN", 0, T0 + 2)          # nothing on the float yet: 0 matches 0
        r = self.L.reconcile(TREASURER, T0 + 3)
        self.assertEqual("", r["rails"]["MTN"]["approval_block"])
        self.assertEqual(APPROVED, self.approve(T0 + 3)["withdrawal"]["state"])
        # a balance ABOVE what the ledger expects is fine (fees, cash the ledger does not track)
        self.L.balance_check(TREASURER, "MTN", 999_999, T0 + 4)
        self.assertEqual("", self.L.reconcile(TREASURER, T0 + 5)["rails"]["MTN"]["approval_block"])

    def test_the_boundary_is_exactly_one_day(self):
        self.L.balance_check(TREASURER, "MTN", 0, T0 + 2)
        self.assertEqual("", self.L._approvals_blocked("MTN", T0 + 2 + ledger.BALANCE_CHECK_MAX_AGE_MS))
        self.assertEqual(ledger.BLOCK_STALE_CHECK, self.L._approvals_blocked("MTN", T0 + 2 + ledger.BALANCE_CHECK_MAX_AGE_MS + 1))


if __name__ == "__main__":
    unittest.main()
