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
import time
import unittest
from http.server import ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from brain import app, evidence, settlement, signed_request
from tests.test_evidence import checkpoint_bytes, contract_bytes, keypair, node_id, pub_hex, sign
from tests.test_paybox import dest_line, exp_line

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

    def send(self, path, raw, signer, nonce=None, ts=None, body_to_sign=None, legacy=False):
        """Sign exactly the bytes we send, the way the phone does.

        v0.16.3 also signs the METHOD and the canonical request target. `legacy=True`
        signs the old body-only line, which every money route must now refuse.
        """
        import time
        ts = ts if ts is not None else int(time.time() * 1000)
        nonce = nonce or ("n" + str(id(raw)) + str(ts))
        digest = signed_request.body_hash(body_to_sign if body_to_sign is not None else raw)
        line = (signed_request.signing_line(ts, nonce, digest) if legacy
                else signed_request.signing_line(ts, nonce, digest, "POST", path))
        headers = {
            "Content-Type": "application/json",
            "X-Prok-Identity": pub_hex(signer),
            "X-Prok-Timestamp": str(ts),
            "X-Prok-Nonce": nonce,
            "X-Prok-Signature": sign(signer, line),
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
        self.assertIn("signature", out["error"])

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


class PayApiTest(unittest.TestCase):
    """v0.16.2: the payment endpoints over real HTTP.

    The Brain now holds signed payment objects on behalf of phones that are not near each
    other. That makes it a place where somebody could try to read other people's money, or
    to act in their name, so these drive the real handler and check who is allowed to do
    what -- not just that the happy path returns 200.
    """

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
        self.stranger = keypair()
        self.now = int(time.time() * 1000)
        # a real obligation, so the buyer genuinely owes this seller
        self.session = bytes([len(app.STATE.settlements.db.execute(
            "SELECT 1 FROM settlements").fetchall()) + 1]) * 8
        contract = contract_bytes(self.session, bytes.fromhex(node_id(self.buyer)),
                                  bytes.fromhex(node_id(self.seller)))
        c = evidence.Contract(contract)
        used = 3 * MB
        cp = checkpoint_bytes(self.session, 1, used // 2, used - used // 2, c.cost_for(used))
        body = json.dumps({
            "contract": contract.hex(),
            "buyer_contract_sig": sign(self.buyer, evidence.CONTRACT_DOMAIN + contract),
            "seller_contract_sig": sign(self.seller, evidence.CONTRACT_DOMAIN + contract),
            "checkpoint": cp.hex(),
            "seller_checkpoint_sig": sign(self.seller, evidence.CHECKPOINT_DOMAIN + cp),
            "buyer_checkpoint_sig": sign(self.buyer, evidence.CHECKPOINT_DOMAIN + cp),
            "buyer_pub": pub_hex(self.buyer),
            "seller_pub": pub_hex(self.seller),
            "submitter_pub": pub_hex(self.buyer),
        }).encode("utf-8")
        code, out = self.call("POST", "/v1/settlements", self.buyer, body)
        self.assertEqual(200, code)
        self.settlement_id = out["settlement_id"]
        self.amount = out["gross"]

    # ---- helpers ---------------------------------------------------------------------

    def url(self, path):
        return "http://127.0.0.1:%d%s" % (self.port, path)

    def call(self, method, path, signer, raw=b"", sign_path=None, sign_method=None,
             headers=None, nonce=None):
        """Sign the method and the path as well, exactly as PaymentSync does."""
        ts = int(time.time() * 1000)
        nonce = nonce or ("n%d%d" % (id(path), ts))
        digest = signed_request.body_hash(raw)
        line = signed_request.signing_line(
            ts, nonce, digest,
            method=sign_method if sign_method is not None else method,
            path=sign_path if sign_path is not None else path)
        h = {
            "Content-Type": "application/json",
            "X-Prok-Identity": pub_hex(signer),
            "X-Prok-Timestamp": str(ts),
            "X-Prok-Nonce": nonce,
            "X-Prok-Signature": sign(signer, line),
        }
        if headers is not None:
            h = headers
        req = Request(self.url(path), data=(raw if method == "POST" else None),
                      headers=h, method=method)
        try:
            with urlopen(req, timeout=10) as r:
                return r.status, json.loads(r.read().decode("utf-8") or "{}")
        except HTTPError as e:
            return e.code, json.loads(e.read().decode("utf-8") or "{}")

    def publish_destination(self):
        raw = json.dumps({"line": dest_line(self.seller), "seller_pub": pub_hex(self.seller)}).encode("utf-8")
        return self.call("POST", "/v1/pay/destination", self.seller, raw)

    # ---- who may read what -------------------------------------------------------------

    def test_an_unsigned_request_reads_nothing(self):
        req = Request(self.url("/v1/pay/expectations"), headers={}, method="GET")
        try:
            with urlopen(req, timeout=10) as r:
                self.fail("an unsigned read returned %d" % r.status)
        except HTTPError as e:
            self.assertEqual(401, e.code)

    def test_only_somebody_who_owes_a_seller_may_read_where_it_is_paid(self):
        self.assertEqual(200, self.publish_destination()[0])
        path = "/v1/pay/destinations?seller=%s" % node_id(self.seller)
        code, out = self.call("GET", path, self.buyer)
        self.assertEqual(200, code)
        self.assertEqual(1, len(out["destinations"]))
        self.assertEqual(pub_hex(self.seller), out["destinations"][0]["seller_pub"],
                         "the key must travel or the buyer cannot verify the claim")
        # somebody with no obligation has no business knowing the seller's number
        self.assertEqual(403, self.call("GET", path, self.stranger)[0])
        # the seller may always read its own
        self.assertEqual(200, self.call("GET", path, self.seller)[0])

    def test_an_inbox_only_ever_shows_its_owner_its_own_items(self):
        self.assertEqual(200, self.publish_destination()[0])
        raw = json.dumps({
            "line": exp_line(self.buyer, self.seller, amount=self.amount,
                             ids=(self.settlement_id,), payment_id="pa-" + self.settlement_id[:6],
                             created=self.now),
            "buyer_pub": pub_hex(self.buyer),
            "settlement_ids": [self.settlement_id],
        }).encode("utf-8")
        self.assertEqual(200, self.call("POST", "/v1/pay/expectation", self.buyer, raw)[0])

        mine = self.call("GET", "/v1/pay/expectations", self.seller)[1]["expectations"]
        self.assertEqual(1, len(mine))
        self.assertEqual(pub_hex(self.buyer), mine[0]["buyer_pub"])
        self.assertEqual([], self.call("GET", "/v1/pay/expectations", self.stranger)[1]["expectations"])
        self.assertEqual([], self.call("GET", "/v1/pay/expectations", self.buyer)[1]["expectations"])

    def test_a_wallet_is_readable_only_by_its_owner(self):
        code, out = self.call("GET", "/v1/wallet?node=" + node_id(self.buyer), self.buyer)
        self.assertEqual(200, code)
        self.assertEqual(403, self.call("GET", "/v1/wallet?node=" + node_id(self.buyer), self.stranger)[0],
                         "a node id is not a secret, so it cannot be the key to somebody's money")
        req = Request(self.url("/v1/wallet?node=" + node_id(self.buyer)), headers={}, method="GET")
        try:
            with urlopen(req, timeout=10) as r:
                self.fail("an unsigned wallet read returned %d" % r.status)
        except HTTPError as e:
            self.assertEqual(401, e.code)

    # ---- who may act in whose name ------------------------------------------------------

    def test_only_the_seller_may_publish_its_own_destination(self):
        raw = json.dumps({"line": dest_line(self.seller), "seller_pub": pub_hex(self.seller)}).encode("utf-8")
        code, out = self.call("POST", "/v1/pay/destination", self.stranger, raw)
        self.assertEqual(400, code)
        self.assertIn("seller", out["error"])

    def test_only_the_buyer_may_leave_its_own_expectation(self):
        self.assertEqual(200, self.publish_destination()[0])
        raw = json.dumps({
            "line": exp_line(self.buyer, self.seller, amount=self.amount,
                             ids=(self.settlement_id,), payment_id="pb-" + self.settlement_id[:6],
                             created=self.now),
            "buyer_pub": pub_hex(self.buyer),
            "settlement_ids": [self.settlement_id],
        }).encode("utf-8")
        self.assertEqual(400, self.call("POST", "/v1/pay/expectation", self.stranger, raw)[0])

    # ---- a signature is for one endpoint -------------------------------------------------

    def test_a_signature_made_for_one_endpoint_is_refused_at_another(self):
        raw = json.dumps({"line": dest_line(self.seller), "seller_pub": pub_hex(self.seller)}).encode("utf-8")
        code, out = self.call("POST", "/v1/pay/destination", self.seller, raw,
                              sign_path="/v1/pay/receipt")
        self.assertEqual(401, code)

    def test_a_signature_made_for_a_get_is_refused_on_a_post(self):
        raw = json.dumps({"line": dest_line(self.seller), "seller_pub": pub_hex(self.seller)}).encode("utf-8")
        self.assertEqual(401, self.call("POST", "/v1/pay/destination", self.seller, raw,
                                        sign_method="GET")[0])

    def test_a_nonce_is_good_once(self):
        raw = json.dumps({"pseudonym": "a" * 64}).encode("utf-8")
        nonce = "reused-nonce-1234"
        self.assertEqual(200, self.call("POST", "/v1/device/risk", self.buyer, raw, nonce=nonce)[0])
        self.assertEqual(401, self.call("POST", "/v1/device/risk", self.buyer, raw, nonce=nonce)[0],
                         "a captured request must not be replayable")

    # ---- device risk ---------------------------------------------------------------------

    def test_the_server_decides_what_a_device_owes_not_the_phone(self):
        raw = json.dumps({"pseudonym": "b" * 64, "unresolvedCentimes": 0}).encode("utf-8")
        code, out = self.call("POST", "/v1/device/risk", self.buyer, raw)
        self.assertEqual(200, code)
        self.assertEqual(self.amount, out["unresolvedCentimes"],
                         "the phone said zero; the server knows better")
        # a NEW identity on the SAME phone inherits the debt the old identity left
        reborn = keypair()
        raw2 = json.dumps({"pseudonym": "b" * 64}).encode("utf-8")
        out2 = self.call("POST", "/v1/device/risk", reborn, raw2)[1]
        self.assertEqual(self.amount, out2["unresolvedCentimes"],
                         "reinstalling must not clear a debt")
        self.assertGreaterEqual(out2["priorIdentityCount"], 1)

    def test_a_different_phone_inherits_nothing(self):
        clean = keypair()
        raw = json.dumps({"pseudonym": "c" * 64}).encode("utf-8")
        out = self.call("POST", "/v1/device/risk", clean, raw)[1]
        self.assertEqual(0, out["unresolvedCentimes"])


    # ---- v0.16.3: private settlement data --------------------------------------------

    def test_a_settlement_is_readable_only_by_its_two_parties(self):
        path = "/v1/settlements/" + self.settlement_id
        for who in (self.buyer, self.seller):
            code, out = self.call("GET", path, who)
            self.assertEqual(200, code)
            self.assertEqual(self.amount, out["settlement"]["gross"])
            self.assertTrue(out["audit"])
        # a settlement id is not a secret: both phones hold it and it comes from signed
        # session bytes, so knowing one must reveal nothing
        code, out = self.call("GET", path, self.stranger)
        self.assertEqual(403, code)
        self.assertNotIn("settlement", out)
        for leak in (str(self.amount), node_id(self.buyer), node_id(self.seller)):
            self.assertNotIn(leak, json.dumps(out))

    def test_an_unsigned_settlement_read_is_refused(self):
        req = Request(self.url("/v1/settlements/" + self.settlement_id), headers={}, method="GET")
        try:
            with urlopen(req, timeout=10) as r:
                self.fail("an unsigned settlement read returned %d" % r.status)
        except HTTPError as e:
            self.assertEqual(401, e.code)

    def test_an_unknown_settlement_is_404_for_everybody(self):
        # said the same way to a party and to a stranger, so this cannot be used to find
        # out which settlement ids exist
        for who in (self.buyer, self.stranger):
            code, _ = self.call("GET", "/v1/settlements/" + "0" * 32, who)
            self.assertEqual(404, code)

    # ---- v0.16.3: no legacy signatures on money ----------------------------------------

    def test_a_legacy_body_only_signature_is_refused_on_every_money_route(self):
        raw = json.dumps({"pseudonym": "d" * 64}).encode("utf-8")
        for path in ("/v1/device/risk", "/v1/pay/destination", "/v1/payments/initiate"):
            ts = int(time.time() * 1000)
            nonce = "legacy%d%d" % (id(path), ts)
            headers = {
                "Content-Type": "application/json",
                "X-Prok-Identity": pub_hex(self.buyer),
                "X-Prok-Timestamp": str(ts),
                "X-Prok-Nonce": nonce,
                "X-Prok-Signature": sign(self.buyer, signed_request.signing_line(
                    ts, nonce, signed_request.body_hash(raw))),
            }
            code, out = self.call("POST", path, self.buyer, raw, headers=headers)
            self.assertEqual(401, code, "%s accepted a body-only signature" % path)
            self.assertIn("must cover", out["error"])

    def test_the_bound_version_of_the_same_request_is_accepted(self):
        # the positive control for the test above
        raw = json.dumps({"pseudonym": "e" * 64}).encode("utf-8")
        self.assertEqual(200, self.call("POST", "/v1/device/risk", self.buyer, raw)[0])

    def test_a_get_signature_is_tied_to_its_query(self):
        # ?payment=A and ?payment=B ask about two different people's money
        a = "/v1/pay/reply?payment=aaaa"
        b = "/v1/pay/reply?payment=bbbb"
        self.assertEqual(404, self.call("GET", a, self.buyer)[0], "signed for itself: reaches the route")
        self.assertEqual(401, self.call("GET", b, self.buyer, sign_path=a)[0],
                         "a signature for one payment must not read another")

    def test_the_query_may_arrive_in_any_order(self):
        self.assertEqual(200, self.publish_destination()[0])
        seller = node_id(self.seller)
        one = "/v1/pay/destination?seller=%s&rail=MTN_MOMO" % seller
        other = "/v1/pay/destination?rail=MTN_MOMO&seller=%s" % seller
        self.assertEqual(200, self.call("GET", one, self.buyer)[0])
        self.assertEqual(200, self.call("GET", other, self.buyer)[0])
        # and the canonical form of the two is the same string
        self.assertEqual(signed_request.canonical_target(one),
                         signed_request.canonical_target(other))

    # ---- v0.16.3: both rails through the Brain -------------------------------------------

    def test_a_remote_buyer_reaches_an_airtel_seller(self):
        raw = json.dumps({"line": dest_line(self.seller, msisdn="055987654", rail="AIRTEL_MONEY"),
                          "seller_pub": pub_hex(self.seller)}).encode("utf-8")
        self.assertEqual(200, self.call("POST", "/v1/pay/destination", self.seller, raw)[0])
        out = self.call("GET", "/v1/pay/destinations?seller=" + node_id(self.seller), self.buyer)[1]
        rails = sorted(d["rail"] for d in out["destinations"])
        self.assertIn("AIRTEL_MONEY", rails)

    def test_a_seller_that_moves_to_another_operator_is_still_reachable(self):
        # a seller has ONE place it is paid; the operator is part of what can change
        for version, rail, number in ((1, "MTN_MOMO", "066123456"),
                                      (2, "AIRTEL_MONEY", "055987654")):
            raw = json.dumps({"line": dest_line(self.seller, msisdn=number, rail=rail,
                                                version=version),
                              "seller_pub": pub_hex(self.seller)}).encode("utf-8")
            self.assertEqual(200, self.call("POST", "/v1/pay/destination", self.seller, raw)[0])
        out = self.call("GET", "/v1/pay/destinations?seller=" + node_id(self.seller), self.buyer)[1]
        self.assertEqual(1, len(out["destinations"]), "one seller, one place it is paid")
        # inside the cooling window the OLD one is still the one to use, and it is a real
        # signed claim either way
        self.assertIn(out["destinations"][0]["rail"], ("MTN_MOMO", "AIRTEL_MONEY"))

    def test_the_named_rail_route_refuses_to_guess(self):
        code, out = self.call("GET", "/v1/pay/destination?seller=" + node_id(self.seller), self.buyer)
        self.assertEqual(400, code, "no rail named must be an error, never a default")
        self.assertIn("rail", out["error"])

    def test_a_stranger_cannot_list_a_sellers_destinations(self):
        self.assertEqual(200, self.publish_destination()[0])
        self.assertEqual(403, self.call(
            "GET", "/v1/pay/destinations?seller=" + node_id(self.seller), self.stranger)[0])

    # ---- the webhook cannot be used as a weapon --------------------------------------------

    def test_an_unverified_webhook_changes_nothing(self):
        before = app.STATE.settlements.get(self.settlement_id)["status"]
        req = Request(self.url("/v1/payments/webhook"),
                      data=json.dumps({"payment_id": "whatever", "amount": 999999}).encode("utf-8"),
                      headers={"Content-Type": "application/json"}, method="POST")
        with urlopen(req, timeout=10) as r:
            self.assertEqual(202, r.status)
            self.assertFalse(json.loads(r.read().decode("utf-8"))["ok"])
        self.assertEqual(before, app.STATE.settlements.get(self.settlement_id)["status"],
                         "anybody who can reach this endpoint must not be able to freeze real money")
