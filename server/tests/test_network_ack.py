"""v0.17.3: an idle provider is a real provider, and a lost packet is not a refusal.

Two runtime blockers, both found by reading build 70 rather than by running it, and
neither visible to any test that already passed.

 1. THE PHONE ONLY PUBLISHED A PRESENCE WHILE IT WAS ALREADY SHARING. The server side of
    that is fine and always was - the matcher asks for a provider that may be asked and
    has room - so the tests here pin the two properties the phone now depends on: a
    provider with room is offered a buyer, and a provider that is full is not, while
    staying visible so its zone does not look empty.

 2. `answer()` RETURNED A BOOLEAN. Every refusal from this module is an HTTP 400, so the
    status code cannot tell "this activation is over" from "the Brain had a bad moment",
    and the phone was deleting a provider's acceptance whenever the network hiccuped. A
    definitive refusal now carries a machine-readable `reason`; a transient one carries
    none. `fixtures/brain_answer_reasons.txt` is the single list this test and the Kotlin
    `BrainAckTest` both read, because v0.16.3 shipped a cross-language mismatch that two
    separately-green suites could not see.
"""
import io
import json
import os
import sqlite3
import threading
import time
import unittest
from http.server import ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from brain import app, network, protocol, signed_request
from tests.test_evidence import keypair, node_id, pub_hex, sign
from tests.test_network_distant import request_line

FIXTURE = os.path.join(os.path.dirname(__file__), "fixtures", "brain_answer_reasons.txt")


def plane():
    db = sqlite3.connect(":memory:")
    db.row_factory = sqlite3.Row
    return network.NetworkPlane(db)


class TerminalReasonTest(unittest.TestCase):
    """Only a stated reason may make a phone throw away a provider's tap."""

    NOW = 1_700_000_000_000

    def setUp(self):
        self.n = plane()
        self.buyer = "b" * 32
        self.seller = "a" * 32
        self.other = "c" * 32
        self.zone = "z-ack:1"
        self.n.put_presence(self.seller, {
            "zone": self.zone, "upstreamAvailable": 1, "sharingEnabled": 1,
            "commercialReady": 1, "currentLoad": 0, "maxBuyers": 1,
            "offerClass": network.COMMERCIAL, "priceHintInternal": 300}, self.NOW)

    def a_live_offer(self):
        self.n.put_demand(self.buyer, {
            "demandId": "dem-ack-0001", "zone": self.zone, "budgetCentimes": 5_000,
            "requestedClass": network.COMMERCIAL}, self.NOW)
        out = self.n.serve("dem-ack-0001", self.NOW)
        self.assertEqual(network.ACTIVATION_SENT, out["status"])
        return out["activationId"]

    def reason_of(self, fn):
        with self.assertRaises(network.NetworkError) as cm:
            fn()
        return getattr(cm.exception, "reason", "")

    def test_an_activation_that_does_not_exist_is_final(self):
        self.assertEqual(network.ACTIVATION_UNKNOWN,
                         self.reason_of(lambda: self.n.answer("act-nope", self.seller, True, self.NOW)))

    def test_somebody_elses_activation_is_final(self):
        aid = self.a_live_offer()
        self.assertEqual(network.ACTIVATION_NOT_YOURS,
                         self.reason_of(lambda: self.n.answer(aid, self.other, True, self.NOW)))

    def test_an_activation_already_settled_the_other_way_is_final(self):
        aid = self.a_live_offer()
        self.n.answer(aid, self.seller, False, self.NOW)          # declined
        self.assertEqual(network.ACTIVATION_SETTLED,
                         self.reason_of(lambda: self.n.answer(aid, self.seller, True, self.NOW)))

    def test_an_activation_answered_too_late_is_final(self):
        aid = self.a_live_offer()
        late = self.NOW + network.OFFER_TTL_MS + 1
        self.assertEqual(network.ACTIVATION_EXPIRED,
                         self.reason_of(lambda: self.n.answer(aid, self.seller, True, late)))

    def test_repeating_an_acceptance_is_a_success_and_not_a_refusal(self):
        # item 16. The phone whose first accept was lost must be able to send it again.
        aid = self.a_live_offer()
        first = self.n.answer(aid, self.seller, True, self.NOW)
        self.assertEqual(network.ACCEPTED, first["state"])
        again = self.n.answer(aid, self.seller, True, self.NOW + 1_000)
        self.assertTrue(again["ok"])
        self.assertTrue(again["duplicate"])
        self.assertEqual(network.ACCEPTED, again["state"])
        # and the buyer's demand did not move backwards or count twice
        d = self.n.demand("dem-ack-0001")
        self.assertEqual(network.PROVIDER_ACCEPTED, d["status"])

    def test_an_ordinary_failure_carries_no_reason_at_all(self):
        # anything without a slug is retryable on the phone, which is the safe default
        self.assertEqual("", self.reason_of(
            lambda: self.n.cancel_demand("dem-nobody-has-this", self.buyer, self.NOW)))
        self.assertEqual("", self.reason_of(
            lambda: self.n.put_demand(self.buyer, {"demandId": "dem-no-zone", "zone": ""},
                                      self.NOW)))


class SharedReasonListTest(unittest.TestCase):
    """One file, two languages. v0.16.3 is why this exists."""

    def test_the_fixture_is_exactly_what_this_module_sends(self):
        with io.open(FIXTURE, encoding="utf-8") as f:
            listed = [l.strip() for l in f if l.strip() and not l.strip().startswith("#")]
        self.assertEqual(sorted(listed), sorted(network.TERMINAL_REASONS))
        self.assertTrue(listed, "an empty list would make nothing final")

    def test_nothing_transient_crept_into_the_terminal_list(self):
        for slug in network.TERMINAL_REASONS:
            self.assertTrue(slug.startswith("ACTIVATION_"), slug)


class CapacityTest(unittest.TestCase):
    """Item 6 and item 22: full means full, and full is not the same as gone."""

    NOW = 1_700_000_000_000

    def setUp(self):
        self.n = plane()
        self.zone = "z-cap:1"
        self.buyer = "b" * 32
        self.seller = "a" * 32

    def present(self, load, enabled=1):
        self.n.put_presence(self.seller, {
            "zone": self.zone, "upstreamAvailable": 1, "sharingEnabled": enabled,
            "commercialReady": 1, "currentLoad": load, "maxBuyers": 1,
            "offerClass": network.COMMERCIAL, "priceHintInternal": 300}, self.NOW)

    def ask(self, demand_id="dem-cap-0001"):
        self.n.put_demand(self.buyer, {
            "demandId": demand_id, "zone": self.zone, "budgetCentimes": 5_000,
            "requestedClass": network.COMMERCIAL}, self.NOW)
        return self.n.serve(demand_id, self.NOW)

    def test_an_idle_provider_with_room_is_offered_the_buyer(self):
        self.present(load=0)
        out = self.ask()
        self.assertEqual(network.ACTIVATION_SENT, out["status"])

    def test_a_provider_with_no_room_is_not_given_a_second_buyer(self):
        self.present(load=1)
        out = self.ask()
        self.assertNotEqual(network.ACTIVATION_SENT, out["status"])
        self.assertEqual([], self.n.jobs_for_provider(self.seller, self.NOW))

    def test_a_full_provider_still_counts_as_somebody_in_the_zone(self):
        # a phone that is serving somebody is a real provider, and a zone with one is not
        # empty - it simply has nobody free this minute
        self.present(load=1)
        z = self.n.zone_status(self.zone, self.NOW)
        self.assertEqual(1, z["providers"])
        self.assertEqual(0, z["usable"])
        self.assertNotEqual(network.RED, z["status"])


class AckRecoveryOverHttpTest(unittest.TestCase):
    """Item 23 and item 24, end to end, with an idle provider and a lost acceptance."""

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

    SEQ = 0

    def setUp(self):
        AckRecoveryOverHttpTest.SEQ += 1
        self.buyer = keypair()
        self.provider = keypair()
        self.demand_id = "%032x" % (0xACC00000 + AckRecoveryOverHttpTest.SEQ)
        self.zone = "z-ackhttp-%d" % AckRecoveryOverHttpTest.SEQ
        app.STATE.netrate.clear()

    def call(self, method, path, signer, body=None):
        raw = b"" if body is None else json.dumps(body).encode("utf-8")
        ts = int(time.time() * 1000)
        AckRecoveryOverHttpTest.SEQ += 1
        nonce = "n-%d-%d" % (ts, AckRecoveryOverHttpTest.SEQ)
        line = signed_request.signing_line(ts, nonce, signed_request.body_hash(raw),
                                           method, path)
        h = {"Content-Type": "application/json",
             "X-Prok-Identity": pub_hex(signer), "X-Prok-Timestamp": str(ts),
             "X-Prok-Nonce": nonce, "X-Prok-Signature": sign(signer, line)}
        req = Request("http://127.0.0.1:%d%s" % (self.port, path),
                      data=(raw if method == "POST" else None), headers=h, method=method)
        try:
            with urlopen(req, timeout=10) as r:
                return r.status, json.loads(r.read().decode("utf-8") or "{}")
        except HTTPError as e:
            return e.code, json.loads(e.read().decode("utf-8") or "{}")

    def present(self, load=0, enabled=1):
        return self.call("POST", "/v1/network/presence", self.provider,
                         {"zone": self.zone, "upstreamAvailable": 1, "sharingEnabled": enabled,
                          "commercialReady": 1, "currentLoad": load, "maxBuyers": 1,
                          "priceHintInternal": 300})

    def ask(self):
        return self.call("POST", "/v1/network/demand", self.buyer,
                         {"demandId": self.demand_id, "zone": self.zone,
                          "budgetCentimes": 5_000, "requestedClass": network.COMMERCIAL,
                          "requestLine": request_line(self.buyer, self.demand_id, self.zone)})

    def jobs(self):
        return self.call("GET", "/v1/network/jobs", self.provider)[1]["jobs"]

    # ---- THE test -------------------------------------------------------------------

    def test_an_idle_provider_that_never_shared_before_completes_the_whole_loop(self):
        # item 23. The provider is NOT sharing when the demand arrives - which is the
        # entire point, and the state build 70 could never reach, because the phone only
        # published a presence once the seller gateway was already running.
        self.assertEqual(200, self.present(load=0)[0])
        code, out = self.ask()
        self.assertEqual(200, code)
        self.assertEqual(network.ACTIVATION_SENT, out["status"])

        jobs = self.jobs()
        self.assertEqual(1, len(jobs))
        job = jobs[0]
        self.assertEqual(network.OFFERED, job["state"])
        # the provider can verify the buyer for itself; nothing was ever gossiped
        r = protocol.parse_request(job["requestLine"].split("\t"))
        self.assertTrue(protocol.verify_request(r))
        self.assertEqual(node_id(self.buyer), protocol.node_id(r.origin_pub))

        # PARTAGER: the seller starts, and only then is the phone busy
        self.assertEqual(200, self.call("POST", "/v1/network/jobs/accept", self.provider,
                                        {"activationId": job["activationId"]})[0])
        self.assertEqual(200, self.present(load=1, enabled=0)[0])
        got = self.call("GET", "/v1/network/demand?id=" + self.demand_id, self.buyer)[1]
        self.assertEqual(network.PROVIDER_ACCEPTED, got["demand"]["status"])

    def test_a_busy_provider_can_still_reach_its_own_jobs_to_reconcile_them(self):
        # the retry has to be possible from exactly the state a provider is in right
        # after it taps PARTAGER: sharing, full, and no longer matchable.
        self.present(load=0)
        aid = self.ask()[1]["activationId"]
        self.present(load=1, enabled=0)
        jobs = self.jobs()
        self.assertEqual(1, len(jobs), "a full provider must still see its own job")
        self.assertEqual(aid, jobs[0]["activationId"])
        self.assertEqual(network.OFFERED, jobs[0]["state"],
                         "still OFFERED is what tells the phone its acceptance was lost")

    def test_the_acceptance_that_was_lost_is_simply_sent_again(self):
        # item 24, server half. The first accept never arrived - here that is simply an
        # accept that was never sent. The job comes back with the SAME id in the SAME
        # state, and the retry lands.
        self.present(load=0)
        aid = self.ask()[1]["activationId"]
        first = self.jobs()
        self.assertEqual([aid], [j["activationId"] for j in first])
        second = self.jobs()
        self.assertEqual([j["activationId"] for j in first],
                         [j["activationId"] for j in second])
        self.assertEqual([network.OFFERED], [j["state"] for j in second])

        self.assertEqual(200, self.call("POST", "/v1/network/jobs/accept", self.provider,
                                        {"activationId": aid})[0])
        code, out = self.call("POST", "/v1/network/jobs/accept", self.provider,
                              {"activationId": aid})
        self.assertEqual(200, code, "a repeat must be idempotent, never a refusal")
        self.assertTrue(out.get("duplicate"))
        self.assertEqual(network.PROVIDER_ACCEPTED,
                         self.call("GET", "/v1/network/demand?id=" + self.demand_id,
                                   self.buyer)[1]["demand"]["status"])
        self.assertEqual([network.ACCEPTED], [j["state"] for j in self.jobs()],
                         "and now the state itself tells the phone to stop retrying")

    def test_a_definitive_refusal_says_why_in_a_form_a_phone_can_act_on(self):
        self.present()
        self.ask()
        code, out = self.call("POST", "/v1/network/jobs/accept", self.provider,
                              {"activationId": "act-does-not-exist"})
        self.assertEqual(400, code)
        self.assertEqual(network.ACTIVATION_UNKNOWN, out.get("reason"))
        self.assertIn("error", out, "the sentence a person reads is unchanged")

    def test_a_transient_refusal_carries_no_reason_so_the_phone_keeps_trying(self):
        self.present()
        code, out = self.call("POST", "/v1/network/demand/poll", self.buyer,
                              {"demandId": "dem-nobody-has-this"})
        self.assertEqual(400, code)
        self.assertNotIn("reason", out,
                         "no slug means retryable, which is the safe default")


if __name__ == "__main__":
    unittest.main()
