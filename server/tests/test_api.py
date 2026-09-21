"""v0.15.3: the settlement and payment endpoints, over real HTTP.

These drive the actual request handler on a real socket, so the wiring is under test and
not just the pieces: signed request -> identity check -> evidence verification -> record.

The two rules being pinned:

1. **No debt without verifiable evidence.** A phone cannot create an obligation by
   asserting one, and cannot submit under somebody else's name.
2. **A signed request proves who sent it, and that identity must match the parties.**
   Believing `buyer_id` from the JSON body would let any valid identity start a payment in
   someone else's name.
"""
import json
import threading
import unittest
from http.server import ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from brain import app, evidence, settlement, signed_request
from tests.test_evidence import checkpoint_bytes, contract_bytes, keypair, node_id, pub_hex, sign

MB = 1024 * 1024


class ApiTest(unittest.TestCase):
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
        self.session = bytes([len(app.STATE.settlements.db.execute(
            "SELECT 1 FROM settlements").fetchall()) + 1]) * 8
        self.contract = contract_bytes(self.session,
                                       bytes.fromhex(node_id(self.buyer)),
                                       bytes.fromhex(node_id(self.seller)))
        c = evidence.Contract(self.contract)
        used = 3 * MB
        self.cost = c.cost_for(used)
        self.checkpoint = checkpoint_bytes(self.session, 1, used // 2, used - used // 2, self.cost)

    # ---- helpers ---------------------------------------------------------------------

    def url(self, path):
        return "http://127.0.0.1:%d%s" % (self.port, path)

    def ev_body(self, submitter=None, **over):
        submitter = submitter or self.buyer
        body = {
            "contract": self.contract.hex(),
            "buyer_contract_sig": sign(self.buyer, evidence.CONTRACT_DOMAIN + self.contract),
            "seller_contract_sig": sign(self.seller, evidence.CONTRACT_DOMAIN + self.contract),
            "checkpoint": self.checkpoint.hex(),
            "seller_checkpoint_sig": sign(self.seller, evidence.CHECKPOINT_DOMAIN + self.checkpoint),
            "buyer_checkpoint_sig": sign(self.buyer, evidence.CHECKPOINT_DOMAIN + self.checkpoint),
            "buyer_pub": pub_hex(self.buyer),
            "seller_pub": pub_hex(self.seller),
            "submitter_pub": pub_hex(submitter),
        }
        body.update(over)
        return json.dumps(body).encode("utf-8")

    def send(self, path, raw, signer, nonce=None, ts=None, body_to_sign=None):
        """Sign exactly the bytes we send, the way the phone does."""
        import time
        ts = ts if ts is not None else int(time.time() * 1000)
        nonce = nonce or ("n" + str(id(raw)) + str(ts))
        digest = signed_request.body_hash(body_to_sign if body_to_sign is not None else raw)
        headers = {
            "Content-Type": "application/json",
            "X-Prok-Identity": pub_hex(signer),
            "X-Prok-Timestamp": str(ts),
            "X-Prok-Nonce": nonce,
            "X-Prok-Signature": sign(signer, signed_request.signing_line(ts, nonce, digest)),
        }
        req = Request(self.url(path), data=raw, headers=headers, method="POST")
        try:
            with urlopen(req, timeout=10) as r:
                return r.status, json.loads(r.read().decode("utf-8"))
        except HTTPError as e:
            return e.code, json.loads(e.read().decode("utf-8") or "{}")

    # ---- settlement submission -------------------------------------------------------

    def test_a_properly_signed_submission_of_real_evidence_is_accepted(self):
        code, out = self.send("/v1/settlements", self.ev_body(), self.buyer)
        self.assertEqual(200, code)
        self.assertTrue(out["ok"])
        self.assertEqual(self.cost, out["gross"], "the server derives the amount itself")
        self.assertFalse(out["agreed"], "only one side has reported so far")
        self.assertEqual(out["gross"], out["seller_net"] + out["prok_fee"])

    def test_both_phones_report_the_same_settlement_independently(self):
        first = self.send("/v1/settlements", self.ev_body(submitter=self.buyer), self.buyer)[1]
        second = self.send("/v1/settlements", self.ev_body(submitter=self.seller), self.seller)[1]
        self.assertEqual(first["settlement_id"], second["settlement_id"])
        self.assertTrue(second["agreed"], "both parties have now reported")
        rows = app.STATE.settlements.db.execute(
            "SELECT COUNT(*) c FROM settlements WHERE settlement_id=?", (first["settlement_id"],)).fetchone()["c"]
        self.assertEqual(1, rows, "one session, one obligation")

    def test_a_retry_with_a_new_nonce_is_idempotent(self):
        body = self.ev_body()
        a = self.send("/v1/settlements", body, self.buyer, nonce="retry-nonce-a")
        b = self.send("/v1/settlements", body, self.buyer, nonce="retry-nonce-b")
        self.assertEqual(200, a[0])
        self.assertEqual(200, b[0])
        self.assertEqual(a[1]["settlement_id"], b[1]["settlement_id"])
        self.assertEqual(a[1]["gross"], b[1]["gross"])

    def test_a_reused_nonce_is_refused(self):
        body = self.ev_body()
        self.assertEqual(200, self.send("/v1/settlements", body, self.buyer, nonce="same-nonce-xyz")[0])
        code, out = self.send("/v1/settlements", body, self.buyer, nonce="same-nonce-xyz")
        self.assertEqual(401, code)
        self.assertIn("already been used", out["error"])

    def test_a_body_modified_after_signing_is_refused(self):
        honest = self.ev_body()
        tampered = json.loads(honest.decode("utf-8"))
        tampered["gross"] = 999_999
        raw = json.dumps(tampered).encode("utf-8")
        # sign the honest body, send the tampered one
        code, out = self.send("/v1/settlements", raw, self.buyer, body_to_sign=honest)
        self.assertEqual(401, code)
        self.assertIn("does not verify", out["error"])

    def test_a_stranger_cannot_submit_somebody_elses_session(self):
        stranger = keypair()
        code, out = self.send("/v1/settlements", self.ev_body(submitter=stranger), stranger)
        self.assertEqual(400, code)
        self.assertIn("neither the buyer nor the seller", out["error"])

    def test_the_signing_identity_must_be_the_submitter_in_the_evidence(self):
        # correctly signed by the buyer, but the evidence names the seller as submitter
        code, out = self.send("/v1/settlements", self.ev_body(submitter=self.seller), self.buyer)
        self.assertEqual(401, code)
        self.assertIn("not the submitter", out["error"])

    def test_forged_evidence_is_refused_over_http_too(self):
        other = keypair()
        bad = self.ev_body(buyer_contract_sig=sign(other, evidence.CONTRACT_DOMAIN + self.contract))
        code, out = self.send("/v1/settlements", bad, self.buyer)
        self.assertEqual(400, code)
        self.assertIn("buyer contract signature is invalid", out["error"])

    def test_an_unsigned_request_is_refused(self):
        req = Request(self.url("/v1/settlements"), data=self.ev_body(),
                      headers={"Content-Type": "application/json"}, method="POST")
        try:
            with urlopen(req, timeout=10) as r:
                self.fail("an unsigned settlement must not be accepted: %d" % r.status)
        except HTTPError as e:
            self.assertEqual(401, e.code)

    # ---- payment authorisation -------------------------------------------------------

    def report(self):
        return self.send("/v1/settlements", self.ev_body(), self.buyer)[1]["settlement_id"]

    def pay_body(self, sid, buyer_id=None, seller_id=None, amount=None, ref="REF-1"):
        return json.dumps({
            "rail": "MANUAL_PILOT",
            "operator_ref": ref,
            "buyer_id": buyer_id if buyer_id is not None else node_id(self.buyer),
            "seller_id": seller_id if seller_id is not None else node_id(self.seller),
            "amount": amount if amount is not None else self.cost,
            "allocations": [{"settlement_id": sid, "allocated": amount if amount is not None else self.cost}],
        }).encode("utf-8")

    def test_a_buyer_may_pay_its_own_obligation(self):
        sid = self.report()
        code, out = self.send("/v1/payments/initiate", self.pay_body(sid), self.buyer, ref_seed=None) \
            if False else self.send("/v1/payments/initiate", self.pay_body(sid), self.buyer)
        self.assertEqual(200, code)
        self.assertTrue(out["ok"], out.get("error"))
        self.assertEqual(settlement.PAYMENT_INITIATED, out["status"])

    def test_one_identity_cannot_start_a_payment_in_another_buyers_name(self):
        sid = self.report()
        stranger = keypair()
        # a perfectly valid Prok identity, claiming to be our buyer
        code, out = self.send("/v1/payments/initiate",
                              self.pay_body(sid, buyer_id=node_id(self.buyer), ref="REF-IMPOSTOR"),
                              stranger)
        self.assertEqual(403, code)
        self.assertIn("own buyer", out["error"])

    def test_a_buyer_cannot_pay_an_obligation_that_is_not_theirs(self):
        sid = self.report()
        stranger = keypair()
        # honest about who it is, but the obligation belongs to somebody else
        code, out = self.send("/v1/payments/initiate",
                              self.pay_body(sid, buyer_id=node_id(stranger), ref="REF-OTHER"),
                              stranger)
        self.assertEqual(200, code)
        self.assertFalse(out["ok"])
        self.assertIn("other parties", out["error"])

    def test_a_payment_may_not_span_two_sellers(self):
        sid = self.report()
        other_seller = keypair()
        code, out = self.send("/v1/payments/initiate",
                              self.pay_body(sid, seller_id=node_id(other_seller), ref="REF-MIX"),
                              self.buyer)
        self.assertEqual(200, code)
        self.assertFalse(out["ok"])
        self.assertIn("other parties", out["error"])

    def test_a_destination_that_contradicts_the_seller_on_record_is_refused(self):
        sid = self.report()
        seller_id = node_id(self.seller)
        app.STATE.settlements.set_destination(seller_id, "MANUAL_PILOT", "242060000111", 1)
        raw = json.loads(self.pay_body(sid, ref="REF-DEST").decode("utf-8"))
        raw["destination"] = "242069999999"
        code, out = self.send("/v1/payments/initiate", json.dumps(raw).encode("utf-8"), self.buyer)
        self.assertEqual(200, code)
        self.assertFalse(out["ok"])
        self.assertEqual(settlement.SECURITY_REVIEW, out["status"])

    def test_payment_idempotency_is_separate_from_request_replay(self):
        # a new nonce each time, so the request layer is happy; the payment layer must
        # still recognise the same real operator transfer
        sid = self.report()
        a = self.send("/v1/payments/initiate", self.pay_body(sid, ref="REF-IDEM"), self.buyer, nonce="pay-nonce-1")
        b = self.send("/v1/payments/initiate", self.pay_body(sid, ref="REF-IDEM"), self.buyer, nonce="pay-nonce-2")
        self.assertEqual(200, a[0])
        self.assertEqual(200, b[0])
        self.assertTrue(b[1].get("duplicate"), "the same reference is the same transfer")
        self.assertEqual(a[1]["payment_id"], b[1]["payment_id"])


if __name__ == "__main__":
    unittest.main()
