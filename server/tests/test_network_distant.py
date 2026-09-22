"""v0.17.2: the loop that actually matters - two phones that have never met.

This is the scenario the whole Network Brain milestone exists for, and the one v0.17.1
could not do. The provider's job used to be an activation id and nothing else; it was
expected to already hold the buyer's `NetRequest` from the legacy gossip path, which two
phones on opposite sides of a neighbourhood have no reason to have exchanged. So the
activation arrived and the provider had nothing to verify, nothing to show, and no
notification.

A job now carries the buyer's own signed request. The Brain carries it; it does not
vouch for it. The provider checks the buyer's signature itself.
"""
import json
import threading
import time
import unittest
from http.server import ThreadingHTTPServer
from urllib.error import HTTPError
from urllib.request import Request, urlopen

from brain import app, network, protocol, signed_request
from tests.test_evidence import keypair, node_id, pub_hex, sign

ZONE = "z1234:5678"


def request_line(signer, demand_id, zone=ZONE, now=None, expires=None, state="CREATED",
                 desired_mb=0, urgency=0):
    """A NetRequest exactly as the phone signs it: same fields, same bytes."""
    now = now if now is not None else int(time.time() * 1000)
    expires = expires if expires is not None else now + 30 * 60_000
    r = protocol.Request(
        id=demand_id, origin_short=protocol.short_id(pub_hex(signer)),
        origin_pub=pub_hex(signer), created_at=now, updated_at=now, expires_at=expires,
        zone=zone, desired_mb=desired_mb, desired_minutes=0, ceiling=-1, urgency=urgency,
        state=state, generation=0, hops=0, signature="")
    sig = sign(signer, protocol.request_signed_bytes(r))
    r.signature = sig
    return r.line()


class DistantLoopTest(unittest.TestCase):
    """Item 13. The buyer and the provider exchange nothing except through the Brain."""

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
        DistantLoopTest.SEQ += 1
        self.buyer = keypair()
        self.provider = keypair()
        self.stranger = keypair()
        self.demand_id = "%032x" % DistantLoopTest.SEQ       # a NetRequest id is 16 bytes
        self.zone = "z-dist-%d" % DistantLoopTest.SEQ
        app.STATE.netrate.clear()

    # ---- plumbing ---------------------------------------------------------------------

    def url(self, path):
        return "http://127.0.0.1:%d%s" % (self.port, path)

    def call(self, method, path, signer, body=None):
        raw = b"" if body is None else json.dumps(body).encode("utf-8")
        ts = int(time.time() * 1000)
        DistantLoopTest.SEQ += 1
        nonce = "n-%d-%d" % (ts, DistantLoopTest.SEQ)
        line = signed_request.signing_line(ts, nonce, signed_request.body_hash(raw),
                                           method, path)
        h = {"Content-Type": "application/json",
             "X-Prok-Identity": pub_hex(signer), "X-Prok-Timestamp": str(ts),
             "X-Prok-Nonce": nonce, "X-Prok-Signature": sign(signer, line)}
        req = Request(self.url(path), data=(raw if method == "POST" else None),
                      headers=h, method=method)
        try:
            with urlopen(req, timeout=10) as r:
                return r.status, json.loads(r.read().decode("utf-8") or "{}")
        except HTTPError as e:
            return e.code, json.loads(e.read().decode("utf-8") or "{}")

    def present(self, who=None):
        return self.call("POST", "/v1/network/presence", who or self.provider,
                         {"zone": self.zone, "upstreamAvailable": 1, "sharingEnabled": 1,
                          "commercialReady": 1, "maxBuyers": 1, "priceHintInternal": 300})

    def ask(self, line=None, **kw):
        body = {"demandId": self.demand_id, "zone": self.zone, "budgetCentimes": 5_000,
                "requestedClass": network.COMMERCIAL,
                "requestLine": request_line(self.buyer, self.demand_id, self.zone)
                if line is None else line}
        body.update(kw)
        return self.call("POST", "/v1/network/demand", self.buyer, body)

    # ---- THE test ------------------------------------------------------------------------

    def test_a_provider_that_never_met_the_buyer_gets_a_usable_job(self):
        self.assertEqual(200, self.present()[0])
        code, out = self.ask()
        self.assertEqual(200, code)
        self.assertTrue(out["carriesRequest"], "the demand must carry the signed request")
        self.assertEqual(network.ACTIVATION_SENT, out["status"])

        jobs = self.call("GET", "/v1/network/jobs", self.provider)[1]["jobs"]
        self.assertEqual(1, len(jobs))
        job = jobs[0]

        # everything the provider needs is in the job. Nothing came over Bluetooth, and
        # nothing came through /v1/sync - this test never calls it.
        line = job["requestLine"]
        self.assertTrue(line, "the job must carry the buyer's signed request")
        r = protocol.parse_request(line.split("\t"))
        self.assertIsNotNone(r)
        self.assertTrue(protocol.verify_request(r),
                        "the provider verifies the BUYER's signature, not the Brain's word")
        self.assertEqual(self.demand_id, r.id)
        self.assertEqual(node_id(self.buyer), protocol.node_id(r.origin_pub))

        # the provider accepts, and the buyer sees it
        self.assertEqual(200, self.call("POST", "/v1/network/jobs/accept", self.provider,
                                        {"activationId": job["activationId"]})[0])
        got = self.call("GET", "/v1/network/demand?id=" + self.demand_id, self.buyer)[1]
        self.assertEqual(network.PROVIDER_ACCEPTED, got["demand"]["status"])

    def test_the_buyer_learns_somebody_is_preparing_without_learning_who(self):
        self.present()
        aid = self.ask()[1]["activationId"]
        self.call("POST", "/v1/network/jobs/accept", self.provider, {"activationId": aid})
        got = self.call("GET", "/v1/network/demand?id=" + self.demand_id, self.buyer)[1]
        self.assertNotIn(node_id(self.provider), json.dumps(got))

    # ---- item 3: the signed request must belong to the caller ------------------------------

    def test_a_request_signed_by_somebody_else_is_refused(self):
        self.present()
        # perfectly valid, correctly signed - by the wrong person
        line = request_line(self.stranger, self.demand_id, self.zone)
        code, out = self.ask(line=line)
        self.assertEqual(400, code)
        self.assertIn("belongs to somebody else", out["error"])

    def test_a_request_for_another_demand_is_refused(self):
        self.present()
        line = request_line(self.buyer, "%032x" % 999999, self.zone)
        code, out = self.ask(line=line)
        self.assertEqual(400, code)
        self.assertIn("not for this demand", out["error"])

    def test_a_request_for_another_area_is_refused(self):
        self.present()
        line = request_line(self.buyer, self.demand_id, "z-somewhere-else")
        self.assertEqual(400, self.ask(line=line)[0])

    def test_an_expired_request_is_refused(self):
        self.present()
        now = int(time.time() * 1000)
        line = request_line(self.buyer, self.demand_id, self.zone, now=now - 60_000,
                            expires=now - 1_000)
        code, out = self.ask(line=line)
        self.assertEqual(400, code)
        self.assertIn("expired", out["error"])

    def test_a_closed_request_is_refused(self):
        self.present()
        line = request_line(self.buyer, self.demand_id, self.zone, state="CANCELLED")
        self.assertEqual(400, self.ask(line=line)[0])

    # ---- item 14: tampering ----------------------------------------------------------------

    def test_editing_any_signed_field_breaks_the_request(self):
        self.present()
        good = request_line(self.buyer, self.demand_id, self.zone)
        f = good.split("\t")
        # id, zone, expiry, public key - each is inside the signature
        for index, value in ((0, "%032x" % 424242), (6, "z-elsewhere"),
                             (5, str(int(f[5]) + 600_000)), (2, pub_hex(self.stranger))):
            bad = list(f)
            bad[index] = value
            code, _ = self.ask(line="\t".join(bad))
            self.assertEqual(400, code, "field %d was accepted after editing" % index)

    def test_editing_the_signature_breaks_the_request(self):
        self.present()
        f = request_line(self.buyer, self.demand_id, self.zone).split("\t")
        f[14] = "00" * 70
        self.assertEqual(400, self.ask(line="\t".join(f))[0])

    def test_rubbish_where_a_request_should_be_is_refused(self):
        self.present()
        for line in ("not a request", "a\tb\tc", "\t".join(["x"] * 15)):
            self.assertEqual(400, self.ask(line=line)[0])

    def test_nothing_is_stored_when_the_request_is_refused(self):
        self.present()
        self.ask(line=request_line(self.stranger, self.demand_id, self.zone))
        self.assertIsNone(app.STATE.net.demand(self.demand_id),
                          "a refused demand must leave nothing behind")

    # ---- item 37: who may see a job ----------------------------------------------------------

    def test_only_the_assigned_provider_sees_the_signed_request(self):
        self.present()
        self.ask()
        for who in (self.stranger, self.buyer):
            self.assertEqual([], self.call("GET", "/v1/network/jobs", who)[1]["jobs"])

    def test_a_second_provider_cannot_accept_the_first_ones_job(self):
        self.present()
        aid = self.ask()[1]["activationId"]
        other = keypair()
        self.call("POST", "/v1/network/presence", other,
                  {"zone": self.zone, "upstreamAvailable": 1, "sharingEnabled": 1,
                   "commercialReady": 1, "priceHintInternal": 300})
        code, out = self.call("POST", "/v1/network/jobs/accept", other, {"activationId": aid})
        self.assertEqual(400, code)
        self.assertIn("offered", out["error"])

    def test_the_buyer_cannot_accept_its_own_activation(self):
        self.present()
        aid = self.ask()[1]["activationId"]
        self.assertEqual(400, self.call("POST", "/v1/network/jobs/accept", self.buyer,
                                        {"activationId": aid})[0])

    # ---- item 10: accepting twice is safe ------------------------------------------------------

    def test_a_retried_acceptance_is_idempotent(self):
        self.present()
        aid = self.ask()[1]["activationId"]
        first = self.call("POST", "/v1/network/jobs/accept", self.provider, {"activationId": aid})
        again = self.call("POST", "/v1/network/jobs/accept", self.provider, {"activationId": aid})
        self.assertEqual(200, first[0])
        self.assertEqual(200, again[0])
        self.assertTrue(again[1].get("duplicate"))
        self.assertEqual(network.PROVIDER_ACCEPTED,
                         app.STATE.net.demand(self.demand_id)["status"])

    # ---- item 25: the request follows the buyer --------------------------------------------------

    def test_a_buyer_that_moves_keeps_the_same_request(self):
        self.present()
        aid = self.ask()[1]["activationId"]
        elsewhere = self.zone + "-north"
        code, out = self.call("POST", "/v1/network/demand/move", self.buyer,
                              {"demandId": self.demand_id, "zone": elsewhere})
        self.assertEqual(200, code)
        self.assertEqual(elsewhere, out["zone"])
        d = app.STATE.net.demand(self.demand_id)
        self.assertEqual(elsewhere, d["zone"])
        self.assertEqual(self.demand_id, d["demand_id"], "same request, not a new one")
        self.assertEqual(network.ACT_EXPIRED, app.STATE.net.activation(aid)["state"],
                         "the offer left behind closes")

    def test_the_demand_read_back_says_which_zone_it_is_in(self):
        # the phone compares this with its own zone to decide whether it has moved
        self.present()
        self.ask()
        got = self.call("GET", "/v1/network/demand?id=" + self.demand_id, self.buyer)[1]
        self.assertEqual(self.zone, got["demand"]["zone"])


class SchemaFourTest(unittest.TestCase):
    """Item 38. A Brain already carrying a pilot's money and a live demand upgrades."""

    def setUp(self):
        import os
        import tempfile
        self.path = os.path.join(tempfile.mkdtemp(), "brain.db")

    def build_schema_three(self):
        """Everything a running v0.17.1 Brain would hold."""
        import sqlite3
        from brain import db as braindb
        from brain import settlement

        con = sqlite3.connect(self.path)
        con.row_factory = sqlite3.Row
        for sql in braindb.MIGRATIONS[:3]:
            con.executescript(sql)
        for v in (1, 2, 3):
            con.execute("INSERT INTO schema_version(version) VALUES (?)", (v,))
        con.commit()
        con.close()

        s = settlement.Settlements(self.path)
        s.report({"settlement_id": settlement.settlement_id("sess", "ch", "cp"),
                  "session_id": "sess", "buyer_id": "aa" * 16, "seller_id": "bb" * 16,
                  "contract_hash": "ch", "checkpoint_hash": "cp", "gross": 7_300,
                  "fee_pct": 5, "expires_at": 9_999_999_999_999}, "buyer", 1_700_000_000_000)

        con = sqlite3.connect(self.path)
        con.row_factory = sqlite3.Row
        con.execute("INSERT INTO network_presence(provider_id, zone, updated_at, expires_at,"
                    " upstream_available, sharing_enabled, commercial_ready, max_buyers)"
                    " VALUES(?,?,?,?,1,1,1,1)", ("bb" * 16, "z1:1", 1_700_000_000_000,
                                                 1_700_000_120_000))
        con.execute("INSERT INTO network_demand(demand_id, buyer_id, zone, created_at,"
                    " expires_at, updated_at, status, attempts)"
                    " VALUES(?,?,?,?,?,?,?,1)",
                    ("dem-old-0001", "aa" * 16, "z1:1", 1_700_000_000_000,
                     1_700_000_900_000, 1_700_000_000_000, network.PROVIDER_ACCEPTED))
        con.execute("INSERT INTO network_activation(activation_id, demand_id, buyer_id,"
                    " provider_id, zone, created_at, expires_at, updated_at, state,"
                    " accepted_at, link_deadline) VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    ("act-old-1", "dem-old-0001", "aa" * 16, "bb" * 16, "z1:1",
                     1_700_000_000_000, 1_700_000_075_000, 1_700_000_001_000,
                     network.ACCEPTED, 1_700_000_001_000, 1_700_000_601_000))
        con.execute("INSERT INTO network_events(at, kind, subject) VALUES(?,?,?)",
                    (1_700_000_000_000, "demand.created", "dem-old-0001"))
        con.commit()
        con.close()

    def rows(self, table):
        import sqlite3
        con = sqlite3.connect(self.path)
        try:
            return [tuple(r) for r in con.execute("SELECT * FROM %s" % table)]
        finally:
            con.close()

    def test_everything_survives_the_upgrade(self):
        self.build_schema_three()
        before = {t: self.rows(t) for t in
                  ("settlements", "settlement_audit", "network_presence",
                   "network_activation", "network_events")}
        demands_before = self.rows("network_demand")

        from brain.db import Brain
        b = Brain(self.path)
        self.assertEqual(4, b.schema_version())
        b.db.close()

        for t, rows in before.items():
            self.assertEqual(rows, self.rows(t), "%s changed during the upgrade" % t)
        # the demand gained one column and kept everything else
        after = self.rows("network_demand")
        self.assertEqual(len(demands_before), len(after))
        self.assertEqual(demands_before[0], after[0][:len(demands_before[0])])
        self.assertEqual("", after[0][-1], "an old demand simply has no signed request")

    def test_an_old_accepted_activation_keeps_its_rendezvous_window(self):
        self.build_schema_three()
        from brain.db import Brain
        b = Brain(self.path)
        plane = network.NetworkPlane(b.db)
        a = plane.activation("act-old-1")
        self.assertEqual(network.ACCEPTED, a["state"])
        self.assertEqual(1_700_000_601_000, int(a["link_deadline"]))
        self.assertEqual(1, len(plane.jobs_for_provider("bb" * 16, 1_700_000_300_000)))
        b.db.close()

    def test_a_job_from_an_old_demand_carries_no_request_and_does_not_crash(self):
        # compatibility: a demand created by build 69 has no signed line
        self.build_schema_three()
        from brain.db import Brain
        b = Brain(self.path)
        plane = network.NetworkPlane(b.db)
        job = plane.jobs_for_provider("bb" * 16, 1_700_000_300_000)[0]
        self.assertEqual("", job["request_line"] or "")
        b.db.close()

    def test_upgrading_twice_records_it_once(self):
        self.build_schema_three()
        from brain.db import Brain
        Brain(self.path).db.close()
        mid = {t: self.rows(t) for t in ("settlements", "network_demand")}
        b = Brain(self.path)
        self.assertEqual(4, b.schema_version())
        self.assertEqual(1, int(b.db.execute(
            "SELECT COUNT(*) FROM schema_version WHERE version=4").fetchone()[0]))
        b.db.close()
        for t, rows in mid.items():
            self.assertEqual(rows, self.rows(t))

    def test_an_upgraded_demand_table_matches_a_fresh_one(self):
        import os
        import tempfile
        self.build_schema_three()
        from brain.db import Brain
        up = Brain(self.path)
        fresh = Brain(os.path.join(tempfile.mkdtemp(), "fresh.db"))
        shape = lambda b: [(r[1], r[2], r[3], r[4], r[5]) for r in
                           b.db.execute("PRAGMA table_info(network_demand)")]
        self.assertEqual(shape(fresh), shape(up))
        up.db.close(); fresh.db.close()


if __name__ == "__main__":
    unittest.main()
