"""v0.17.0: the control plane over real HTTP, and upgrading a v0.16.5 Brain.

Two things this file exists for.

**Authorization.** The control plane is the first part of ProkNet where one phone asks
the server to make another phone's screen light up. Every route therefore answers about
the caller and nobody else, and the verified request identity is the only actor: a body
field named `buyerId` or `providerId` is not checked against the signature, it is not read
at all, so there is nothing for it to disagree with.

**Not losing a pilot's money on an upgrade.** Item 98. A v0.16.5 Brain in the field holds
settlements, payment transactions, destinations, expectations, receipts, device identities,
nonces and signed parser rules. v0.17 adds tables beside them and must touch none of it.
"""
import json
import threading
import time
import unittest
from http.server import ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from brain import app, network, signed_request
from tests.test_evidence import keypair, node_id, pub_hex, sign

ZONE = "z1234:5678"


class NetworkApiTest(unittest.TestCase):
    SEQ = 0

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
        self.provider = keypair()
        self.stranger = keypair()
        # a fresh id per test, so one test's demand is never another's. From a counter,
        # not the clock: tests in the same millisecond would otherwise share a demand and
        # fail each other for reasons that have nothing to do with the code.
        NetworkApiTest.SEQ += 1
        self.demand_id = "dem-test-%d" % NetworkApiTest.SEQ
        # ...and its own zone. One Brain serves the whole class, and a provider from an
        # earlier test is still inside its 120s presence window - so a shared zone means
        # the matcher legitimately picks somebody this test has never heard of.
        self.zone = "z-test-%d" % NetworkApiTest.SEQ
        app.STATE.netrate.clear()

    # ---- plumbing ---------------------------------------------------------------------

    def url(self, path):
        return "http://127.0.0.1:%d%s" % (self.port, path)

    def call(self, method, path, signer, body=None, sign_path=None, headers=None):
        raw = b"" if body is None else json.dumps(body).encode("utf-8")
        ts = int(time.time() * 1000)
        # a counter, not the clock: several calls land in the same millisecond and a
        # repeated nonce is a replay, which would fail the test for the wrong reason
        NetworkApiTest.SEQ += 1
        nonce = "n-%d-%d" % (ts, NetworkApiTest.SEQ)
        line = signed_request.signing_line(
            ts, nonce, signed_request.body_hash(raw), method,
            sign_path if sign_path is not None else path)
        h = headers if headers is not None else {
            "Content-Type": "application/json",
            "X-Prok-Identity": pub_hex(signer),
            "X-Prok-Timestamp": str(ts),
            "X-Prok-Nonce": nonce,
            "X-Prok-Signature": sign(signer, line),
        }
        req = Request(self.url(path), data=(raw if method == "POST" else None),
                      headers=h, method=method)
        try:
            with urlopen(req, timeout=10) as r:
                return r.status, json.loads(r.read().decode("utf-8") or "{}")
        except HTTPError as e:
            return e.code, json.loads(e.read().decode("utf-8") or "{}")

    def heartbeat(self, who=None, **kw):
        body = {"zone": self.zone, "upstreamAvailable": 1, "sharingEnabled": 1,
                "commercialReady": 1, "maxBuyers": 2, "priceHintInternal": 300}
        body.update(kw)
        return self.call("POST", "/v1/network/presence", who or self.provider, body)

    def ask(self, who=None, **kw):
        body = {"demandId": self.demand_id, "zone": self.zone, "budgetCentimes": 5_000,
                "requestedClass": network.COMMERCIAL}
        body.update(kw)
        return self.call("POST", "/v1/network/demand", who or self.buyer, body)

    # ---- the whole loop, over HTTP ------------------------------------------------------

    def test_a_buyer_with_no_local_source_reaches_a_provider(self):
        self.assertEqual(200, self.heartbeat()[0])
        code, out = self.ask()
        self.assertEqual(200, code)
        self.assertEqual(network.ACTIVATION_SENT, out["status"])
        aid = out["activationId"]

        # the provider sees it in its own inbox
        jobs = self.call("GET", "/v1/network/jobs", self.provider)[1]["jobs"]
        self.assertEqual(1, len(jobs))
        self.assertEqual(aid, jobs[0]["activationId"])

        # taps PARTAGER
        self.assertEqual(200, self.call("POST", "/v1/network/jobs/accept", self.provider,
                                        {"activationId": aid})[0])

        # and the buyer learns somebody is preparing - without learning who
        got = self.call("GET", "/v1/network/demand?id=" + self.demand_id, self.buyer)[1]
        self.assertEqual(network.PROVIDER_ACCEPTED, got["demand"]["status"])
        self.assertEqual(network.ACCEPTED, got["activation"]["state"])
        self.assertNotIn(node_id(self.provider), json.dumps(got),
                         "the buyer's screen says a provider is preparing, not which one")

        # the transport then reports what actually happened
        self.assertEqual(200, self.call("POST", "/v1/network/jobs/state", self.buyer,
                                        {"activationId": aid, "result": network.ACT_CONNECTED})[0])
        final = self.call("GET", "/v1/network/demand?id=" + self.demand_id, self.buyer)[1]
        self.assertEqual(network.CONNECTED, final["demand"]["status"])

    # ---- authorization ------------------------------------------------------------------

    def test_an_unsigned_request_reaches_nothing(self):
        for method, path in (("GET", "/v1/network/jobs"), ("GET", "/v1/network/demand"),
                             ("GET", "/v1/network/coverage?zone=" + self.zone)):
            req = Request(self.url(path), headers={}, method=method)
            try:
                with urlopen(req, timeout=10) as r:
                    self.fail("%s %s answered %d unsigned" % (method, path, r.status))
            except HTTPError as e:
                self.assertEqual(401, e.code)

    def test_a_stranger_cannot_read_somebody_elses_request(self):
        self.assertEqual(200, self.heartbeat()[0])
        self.assertEqual(200, self.ask()[0])
        code, out = self.call("GET", "/v1/network/demand?id=" + self.demand_id, self.stranger)
        self.assertEqual(403, code)
        self.assertNotIn("demand", out)

    def test_a_provider_inbox_holds_only_its_own_jobs(self):
        self.assertEqual(200, self.heartbeat()[0])
        self.assertEqual(200, self.ask()[0])
        self.assertEqual([], self.call("GET", "/v1/network/jobs", self.stranger)[1]["jobs"])
        self.assertEqual([], self.call("GET", "/v1/network/jobs", self.buyer)[1]["jobs"])

    def test_only_the_assigned_provider_may_accept(self):
        self.assertEqual(200, self.heartbeat()[0])
        aid = self.ask()[1]["activationId"]
        code, out = self.call("POST", "/v1/network/jobs/accept", self.stranger,
                              {"activationId": aid})
        self.assertEqual(400, code)
        self.assertIn("offered", out["error"])

    def test_only_a_party_may_report_a_result(self):
        self.assertEqual(200, self.heartbeat()[0])
        aid = self.ask()[1]["activationId"]
        self.call("POST", "/v1/network/jobs/accept", self.provider, {"activationId": aid})
        code, _ = self.call("POST", "/v1/network/jobs/state", self.stranger,
                            {"activationId": aid, "result": network.ACT_CONNECTED})
        self.assertEqual(400, code)

    def test_only_the_buyer_may_cancel_its_own_request(self):
        self.assertEqual(200, self.heartbeat()[0])
        self.assertEqual(200, self.ask()[0])
        self.assertEqual(400, self.call("POST", "/v1/network/demand/cancel", self.stranger,
                                        {"demandId": self.demand_id})[0])
        self.assertEqual(200, self.call("POST", "/v1/network/demand/cancel", self.buyer,
                                        {"demandId": self.demand_id})[0])

    def test_a_presence_writes_only_the_callers_own_row(self):
        self.assertEqual(200, self.heartbeat()[0])
        mine = self.call("GET", "/v1/network/presence/me", self.provider)[1]["presence"]
        self.assertIsNotNone(mine)
        self.assertIsNone(self.call("GET", "/v1/network/presence/me", self.stranger)[1]["presence"])

    # ---- item 67: the body never names the actor -----------------------------------------

    def test_a_demand_cannot_be_created_in_somebody_elses_name(self):
        self.assertEqual(200, self.heartbeat()[0])
        # A signs, and the body claims to be B. The field is ignored, so the demand
        # belongs to A - and B can therefore not read it.
        self.assertEqual(200, self.ask(buyerId=node_id(self.stranger))[0])
        self.assertEqual(200, self.call("GET", "/v1/network/demand?id=" + self.demand_id,
                                        self.buyer)[0])
        self.assertEqual(403, self.call("GET", "/v1/network/demand?id=" + self.demand_id,
                                        self.stranger)[0])

    def test_a_presence_cannot_be_published_for_another_provider(self):
        self.assertEqual(200, self.heartbeat(providerId=node_id(self.stranger))[0])
        self.assertIsNone(self.call("GET", "/v1/network/presence/me", self.stranger)[1]["presence"])

    # ---- item 68: retries are normal -----------------------------------------------------

    def test_a_repeated_heartbeat_is_one_provider(self):
        for _ in range(3):
            self.assertEqual(200, self.heartbeat()[0])
        z = self.call("GET", "/v1/network/coverage?zone=" + self.zone, self.buyer)[1]
        self.assertIn(z["state"], (network.YELLOW, network.GREEN))

    def test_a_repeated_demand_is_one_demand(self):
        self.assertEqual(200, self.heartbeat()[0])
        first = self.ask()[1]
        again = self.ask()[1]
        self.assertTrue(again.get("duplicate"))
        self.assertEqual(first["demandId"], again["demandId"])

    def test_a_repeated_accept_is_not_an_error(self):
        self.assertEqual(200, self.heartbeat()[0])
        aid = self.ask()[1]["activationId"]
        first = self.call("POST", "/v1/network/jobs/accept", self.provider, {"activationId": aid})
        again = self.call("POST", "/v1/network/jobs/accept", self.provider, {"activationId": aid})
        self.assertEqual(200, first[0])
        self.assertEqual(200, again[0])
        self.assertTrue(again[1].get("duplicate"))

    # ---- signatures are bound, like every other money-or-privacy route -------------------

    def test_a_signature_for_one_route_is_refused_at_another(self):
        code, out = self.call("POST", "/v1/network/presence", self.provider,
                              {"zone": ZONE}, sign_path="/v1/network/demand")
        self.assertEqual(401, code)
        self.assertIn("must cover", out["error"])

    def test_a_legacy_body_only_signature_is_refused(self):
        raw = json.dumps({"zone": ZONE}).encode("utf-8")
        ts = int(time.time() * 1000)
        nonce = "legacy-net-%d" % ts
        headers = {
            "Content-Type": "application/json",
            "X-Prok-Identity": pub_hex(self.provider),
            "X-Prok-Timestamp": str(ts),
            "X-Prok-Nonce": nonce,
            "X-Prok-Signature": sign(self.provider, signed_request.signing_line(
                ts, nonce, signed_request.body_hash(raw))),
        }
        self.assertEqual(401, self.call("POST", "/v1/network/presence", self.provider,
                                        {"zone": ZONE}, headers=headers)[0])

    def test_a_get_signature_is_tied_to_its_query(self):
        a = "/v1/network/coverage?zone=" + self.zone
        b = "/v1/network/coverage?zone=z-other"
        self.assertEqual(200, self.call("GET", a, self.buyer)[0])
        self.assertEqual(401, self.call("GET", b, self.buyer, sign_path=a)[0])

    # ---- item 82/83: a consumer map gets a colour, not a neighbour list ------------------

    def test_coverage_answers_a_colour_and_nothing_else(self):
        self.assertEqual(200, self.heartbeat()[0])
        code, out = self.call("GET", "/v1/network/coverage?zone=" + self.zone, self.buyer)
        self.assertEqual(200, code)
        self.assertEqual({"zone", "state", "updatedAt"}, set(out.keys()),
                         "in a thin zone, a provider count is one identifiable household")
        self.assertNotIn(node_id(self.provider), json.dumps(out))

    def test_there_is_no_way_to_list_the_providers_in_a_zone(self):
        self.assertEqual(200, self.heartbeat()[0])
        for path in ("/v1/network/providers?zone=" + self.zone, "/v1/network/presence"):
            self.assertEqual(404, self.call("GET", path, self.buyer)[0])

    # ---- item 87: a liveness probe says almost nothing -----------------------------------

    def test_health_is_open_but_tells_nobody_anything(self):
        with urlopen(self.url("/health"), timeout=10) as r:
            self.assertEqual(200, r.status)
            out = json.loads(r.read().decode("utf-8"))
        self.assertTrue(out["ok"])
        self.assertEqual("0.17.1", out["version"])
        text = json.dumps(out)
        for leak in ("nodes", "requests", "sources", "settlements", "syncs", "db"):
            self.assertNotIn(leak, text, "a probe must not report how many people use ProkNet")

    def test_the_counts_moved_behind_a_signature(self):
        out = self.call("GET", "/v1/network/diagnostics", self.buyer)[1]
        self.assertIn("network", out)
        self.assertEqual(3, out["schema"])

    # ---- item 70: spam control that does not break recovery -------------------------------

    def test_one_buyer_cannot_wake_providers_over_and_over(self):
        """Three separate controls, all of which have to hold."""
        self.assertEqual(200, self.heartbeat()[0])
        self.assertEqual(200, self.ask()[0])

        # 1. a second, different demand while one is live is the same person asking twice
        self.demand_id = "dem-second-one"
        again = self.ask()[1]
        self.assertTrue(again.get("duplicate"))
        self.assertEqual(1, int(app.STATE.net.db.execute(
            "SELECT COUNT(*) FROM network_demand WHERE buyer_id=?",
            (node_id(self.buyer),)).fetchone()[0]))

        # 2. cancelling and asking again immediately is refused
        self.assertEqual(200, self.call("POST", "/v1/network/demand/cancel", self.buyer,
                                        {"demandId": again["demandId"]})[0])
        self.demand_id = "dem-right-away"
        code, out = self.ask()
        self.assertEqual(400, code)
        self.assertIn("wait", out["error"])

    def test_the_rate_limiter_stops_a_flood(self):
        # 3. the limiter itself, which is the backstop behind the two policies above
        now = int(time.time() * 1000)
        with app.STATE.lock:
            for _ in range(app.NETWORK_RATE["demand"]):
                self.assertTrue(app.STATE.allow_network("flood-node", "demand", now))
            self.assertFalse(app.STATE.allow_network("flood-node", "demand", now))
            # and a different kind of request is a different budget
            self.assertTrue(app.STATE.allow_network("flood-node", "presence", now))
            # and it lifts once the window has passed
            self.assertTrue(app.STATE.allow_network(
                "flood-node", "demand", now + app.NETWORK_RATE_WINDOW_MS + 1))

    def test_heartbeats_are_never_throttled_into_uselessness(self):
        # a provider beats every 30-60s; the budget has to be comfortable
        for _ in range(15):
            self.assertEqual(200, self.heartbeat()[0])


class UpgradeFromV165Test(unittest.TestCase):
    """Item 98: a v0.16.5 Brain in the field upgrades without losing a thing.

    The v0.16.4 lesson was that a schema bug only exists on an upgraded install and is
    always absent on a fresh one. The same reasoning applies to the server, so this builds
    a database that looks like a running v0.16.5 Brain - with real financial rows in it -
    and opens it as v0.17.
    """

    FINANCIAL = ("settlements", "settlement_audit", "payment_transactions",
                 "payment_allocations", "payment_events", "payment_destinations",
                 "pay_destinations", "pay_expectations", "pay_receipts",
                 "device_identities", "request_nonces", "receipt_rules")

    def setUp(self):
        import os
        import tempfile
        self.path = os.path.join(tempfile.mkdtemp(), "brain.db")

    def build_v165(self):
        """A Brain as v0.16.5 left it: schema 1, plus every payment table, with rows."""
        import sqlite3
        from brain import db as braindb
        from brain import paybox, ruleconfig, settlement, signed_request as sr

        # schema 1 only: exactly what v0.16.5's migration list contained
        con = sqlite3.connect(self.path)
        con.row_factory = sqlite3.Row
        con.executescript(braindb.MIGRATIONS[0])
        con.execute("INSERT INTO schema_version(version) VALUES (1)")
        con.commit()
        con.close()

        s = settlement.Settlements(self.path)
        s.report({"settlement_id": settlement.settlement_id("sess", "ch", "cp"),
                  "session_id": "sess", "buyer_id": "aa" * 16, "seller_id": "bb" * 16,
                  "contract_hash": "ch", "checkpoint_hash": "cp", "gross": 7_300,
                  "fee_pct": 5, "expires_at": 9_999_999_999_999}, "buyer", 1_700_000_000_000)
        s.set_destination("bb" * 16, "MTN_MOMO", "desthash", 1_700_000_000_000)
        box = paybox.PayBox(self.path)
        box.note_device("d" * 64, "aa" * 16, 1_700_000_000_000)
        store = ruleconfig.RuleStore(box.db, "")
        box.db.execute("INSERT INTO receipt_rules(version, valid_from, terms, signature,"
                       " stored_at) VALUES(2, 1758400000000, '{}', 'sig', 1758400000001)")
        nonces = sr.Nonces(box.db)
        nonces.use("aa" * 64, "an-old-nonce", 1_700_000_000_000)
        box.db.commit()
        return self.snapshot()

    def snapshot(self):
        import sqlite3
        con = sqlite3.connect(self.path)
        con.row_factory = sqlite3.Row
        out = {}
        for t in self.FINANCIAL:
            try:
                out[t] = ([tuple(r) for r in con.execute("SELECT * FROM %s" % t)],
                          [r["sql"] for r in con.execute(
                              "SELECT sql FROM sqlite_master WHERE tbl_name=? ORDER BY name", (t,))])
            except sqlite3.OperationalError:
                out[t] = None
        con.close()
        return out

    def test_every_financial_row_and_index_survives(self):
        before = self.build_v165()
        self.assertTrue(any(v and v[0] for v in before.values()), "the fixture must hold rows")

        from brain.db import Brain
        b = Brain(self.path)
        self.assertEqual(3, b.schema_version(), "the upgrade must be recorded, not implied")
        b.db.close()

        after = self.snapshot()
        for t in self.FINANCIAL:
            self.assertEqual(before[t], after[t], "%s changed during the upgrade" % t)

    def test_the_new_tables_appear(self):
        self.build_v165()
        from brain.db import Brain
        b = Brain(self.path)
        names = {r[0] for r in b.db.execute(
            "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'network%'")}
        self.assertIn("network_presence", names)
        self.assertIn("network_demand", names)
        self.assertIn("network_activation", names)
        self.assertIn("network_events", names)
        b.db.close()

    def test_upgrading_twice_does_nothing_the_second_time(self):
        self.build_v165()
        from brain.db import Brain
        Brain(self.path).db.close()
        mid = self.snapshot()
        b = Brain(self.path)
        self.assertEqual(3, b.schema_version())
        for v in (2, 3):
            self.assertEqual(1, int(b.db.execute(
                "SELECT COUNT(*) FROM schema_version WHERE version=?", (v,)).fetchone()[0]),
                "migration %d must be recorded once" % v)
        b.db.close()
        self.assertEqual(mid, self.snapshot())

    def test_the_payment_service_still_works_on_the_upgraded_database(self):
        # the point of the whole exercise: a pilot's Brain keeps taking payments
        self.build_v165()
        from brain.db import Brain
        Brain(self.path).db.close()

        from brain import paybox, settlement
        s = settlement.Settlements(self.path)
        rows = list(s.db.execute("SELECT settlement_id, gross, status FROM settlements"))
        self.assertEqual(1, len(rows))
        self.assertEqual(7_300, int(rows[0]["gross"]))
        self.assertEqual(settlement.PENDING, rows[0]["status"])
        box = paybox.PayBox(self.path)
        self.assertEqual(1, int(box.db.execute(
            "SELECT COUNT(*) FROM device_identities").fetchone()[0]))


if __name__ == "__main__":
    unittest.main()
