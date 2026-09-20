"""The Network Brain, tested with real P-256 keys and an injected clock. Run: python -m unittest discover -s server/tests -t server"""
import hashlib
import os
import struct
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from cryptography.hazmat.primitives import hashes
from cryptography.hazmat.primitives.asymmetric import ec

from brain import matching, protocol
from brain.db import Brain, AVAILABILITY_TTL_MS, TOMBSTONE_KEEP_MS

NOW = 1_700_000_000_000
TTL = 30 * 60_000


class Node:
    """A phone: a key, an id, and the two signatures it produces."""

    def __init__(self):
        self.priv = ec.generate_private_key(ec.SECP256R1())
        nums = self.priv.public_key().public_numbers()
        self.pub = nums.x.to_bytes(32, "big").hex() + nums.y.to_bytes(32, "big").hex()
        self.id = protocol.node_id(self.pub)
        self.short = self.id[:8]

    def sign(self, data: bytes) -> str:
        return self.priv.sign(data, ec.ECDSA(hashes.SHA256())).hex()

    def request(self, rid="0123456789abcdef", zone="z1:2", now=NOW, ceiling=-1, state="NETWORK_REQUESTED", generation=1, hops=0):
        r = protocol.Request(rid, self.short, self.pub, now, now, now + TTL, zone, 0, 0, ceiling, 0, state, generation, hops, "")
        r.signature = self.sign(protocol.request_signed_bytes(r))
        return r

    def tombstone(self, r, state="FULFILLED", now=NOW):
        t = protocol.Request(**{**r.__dict__, "state": state, "generation": r.generation + 1, "updated_at": now, "hops": 0, "signature": ""})
        t.signature = self.sign(protocol.request_signed_bytes(t))
        return t

    def upload(self, now=NOW, zone="z1:2", coverage=(), availability=None, requests=(), jobs=(), signer=None, claim_id=None):
        lines = ["V\t1", "N\t%s\t%s\t%d" % (claim_id or self.id, self.pub, now), "Z\t" + zone]
        for c in coverage:
            lines.append("C\t" + "\t".join(str(v) for v in c))
        if availability:
            lines.append("P\t" + "\t".join(str(v) for v in availability))
        for r in requests:
            lines.append("R\t" + r.line())
        for j in jobs:
            lines.append("J\t%s\t%s\t%d" % j)
        body = "\n".join(lines) + "\n"
        return body + "S\t" + (signer or self).sign(body.encode("utf-8")) + "\n"


def avail(zone="z1:2", potential=True, sharing=False, price=500, busy=False):
    return (zone, str(potential).lower(), str(sharing).lower(), 2, price, str(busy).lower(), "true")


def cov(key="wifi:0123456789abcdef", kind="WIFI", zone="z1:2", last_seen=NOW, validated=True, price=0, trust="AUTHORIZED_PRIVATE", obs=3):
    return (key, kind, zone, last_seen, str(validated).lower(), price, trust, obs)


class ProtocolTest(unittest.TestCase):
    def test_signed_sync_accepted_and_bad_ones_rejected(self):
        n = Node()
        up = protocol.parse_upload(n.upload(), NOW)
        self.assertEqual(n.id, up.node_id)
        self.assertEqual("z1:2", up.zone)
        with self.assertRaises(protocol.ProtocolError):
            protocol.parse_upload(n.upload().replace("z1:2", "z1:3"), NOW)           # tampered
        with self.assertRaises(protocol.ProtocolError):
            protocol.parse_upload(n.upload(now=NOW - protocol.CLOCK_SKEW_MS - 1), NOW)   # stale clock
        other = Node()
        with self.assertRaises(protocol.ProtocolError):
            protocol.parse_upload(n.upload(signer=other), NOW)                      # someone else's key
        with self.assertRaises(protocol.ProtocolError):
            protocol.parse_upload(n.upload(claim_id=other.id), NOW)                  # claims another id
        with self.assertRaises(protocol.ProtocolError):
            protocol.parse_upload("V\t1\nN\t%s\t%s\t%d\n" % (n.id, n.pub, NOW), NOW)  # unsigned

    def test_request_signature_matches_the_phone_layout(self):
        n = Node()
        r = n.request()
        self.assertTrue(protocol.verify_request(r))
        self.assertEqual(len(protocol.request_signed_bytes(r)), 1 + 8 + 4 + 8 + 8 + 8 + 4 + 4 + 4 + 1 + 4 + 1 + 2 + 4)
        r2 = protocol.Request(**{**r.__dict__, "ceiling": 100})
        self.assertFalse(protocol.verify_request(r2))
        hopped = protocol.Request(**{**r.__dict__, "hops": 3})
        self.assertTrue("hops are not signed", protocol.verify_request(hopped))
        self.assertEqual(r, protocol.parse_request(r.line().split("\t")))


class BrainTest(unittest.TestCase):
    def setUp(self):
        self.clock_now = NOW
        self.brain = Brain(":memory:", clock=lambda: self.clock_now)
        self.buyer = Node()
        self.provider = Node()
        self.uploader = Node()

    def sync(self, node, **kw):
        now = kw.pop("now", self.clock_now)
        return self.brain.sync(protocol.parse_upload(node.upload(now=now, **kw), now), now)

    def test_duplicate_sync_is_idempotent(self):
        r = self.buyer.request()
        self.sync(self.uploader, requests=[r], coverage=[cov()], availability=avail())
        before = self.brain.counts()
        self.sync(self.uploader, requests=[r], coverage=[cov()], availability=avail())
        self.assertEqual(before, self.brain.counts())
        self.assertEqual(1, self.brain.counts()["internet_requests"])
        self.assertEqual(1, self.brain.source("wifi:0123456789abcdef")["observers"])

    def test_generations_and_tombstones(self):
        r = self.buyer.request()
        self.sync(self.uploader, requests=[r])
        self.assertEqual("UPLOADED", self.brain.request(r.id).state)
        t = self.buyer.tombstone(r)
        self.sync(self.uploader, requests=[t])
        self.assertEqual("FULFILLED", self.brain.request(r.id).state)
        self.assertEqual(2, self.brain.request(r.id).generation)
        # the old open generation arrives later from another carrier: it cannot return OPEN
        d = self.sync(self.provider, requests=[r])
        self.assertEqual("FULFILLED", self.brain.request(r.id).state)
        self.assertTrue(any("older generation" in a for a in d["advice"]))
        # a signed open generation 3 cannot reopen a tombstone either
        reopened = protocol.Request(**{**t.__dict__, "state": "NETWORK_REQUESTED", "generation": 3, "signature": ""})
        reopened.signature = self.buyer.sign(protocol.request_signed_bytes(reopened))
        d = self.sync(self.provider, requests=[reopened])
        self.assertEqual("FULFILLED", self.brain.request(r.id).state)
        self.assertTrue(any("resurrection" in a for a in d["advice"]))
        # a forged request is rejected
        forged = protocol.Request(**{**r.__dict__, "id": "ffffffffffffffff", "ceiling": 1})
        d = self.sync(self.provider, requests=[forged])
        self.assertIsNone(self.brain.request("ffffffffffffffff"))
        self.assertTrue(any("invalid signature" in a for a in d["advice"]))

    def test_expired_request_not_matched_and_cleanup_expires_it(self):
        r = self.buyer.request(now=NOW - TTL - 1)
        d = self.sync(self.uploader, requests=[r])
        self.assertIsNone(self.brain.request(r.id))
        self.assertTrue(any("expired" in a for a in d["advice"]))
        live = self.buyer.request(rid="1111111111111111")
        self.sync(self.uploader, requests=[live])
        self.clock_now = NOW + TTL + 1
        n = self.brain.cleanup()
        self.assertEqual(1, n["requests_expired"])
        self.assertEqual("EXPIRED", self.brain.request(live.id).state)
        self.clock_now = NOW + TTL + TOMBSTONE_KEEP_MS + 2
        self.brain.cleanup()
        self.assertIsNone(self.brain.request(live.id))

    def test_provider_activation_only_when_eligible_and_within_the_ceiling(self):
        # the provider heartbeats: opted in, not sharing, 5 CFA, same zone
        self.sync(self.provider, availability=avail(price=500))
        # a 3 CFA request in the zone: no activation
        capped = self.buyer.request(rid="2222222222222222", ceiling=300)
        self.sync(self.uploader, requests=[capped])
        self.assertEqual([], self.brain.jobs_for(self.provider.id))
        # an automatic-price request: the provider gets one job, once
        r = self.buyer.request()
        d = self.sync(self.uploader, requests=[r])
        self.sync(self.uploader, requests=[r])
        jobs = self.brain.jobs_for(self.provider.id)
        self.assertEqual(1, len(jobs))
        self.assertEqual("PROVIDER_ACTIVATION", jobs[0]["type"])
        self.assertEqual("OFFERED", jobs[0]["state"])
        self.assertEqual("SUPPLY_POSSIBLE", self.brain.request(r.id).state)
        # the provider's own sync brings the job and the request
        d = self.sync(self.provider, availability=avail(price=500))
        self.assertEqual([r.id], [j["request_id"] for j in d["jobs"]])
        self.assertEqual([r.id], [x.id for x in d["requests"]])
        # the buyer's own sync brings a status line, not its own request as work
        d = self.sync(self.buyer)
        self.assertIn((r.id, "SUPPLY_POSSIBLE", 1), d["statuses"])
        # the job state can move forward, never sideways
        self.sync(self.provider, availability=avail(), jobs=[(jobs[0]["id"], "ACCEPTED", self.clock_now)])
        self.assertEqual("ACCEPTED", self.brain.jobs_for(self.provider.id)[0]["state"])
        self.sync(self.provider, availability=avail(), jobs=[(jobs[0]["id"], "COMPLETED", self.clock_now)])
        self.assertEqual("ACCEPTED", self.brain.jobs_for(self.provider.id)[0]["state"])
        # the tombstone cancels the job
        self.sync(self.uploader, requests=[self.buyer.tombstone(r)])
        self.assertEqual("CANCELLED", self.brain.jobs_for(self.provider.id)[0]["state"])

    def test_stale_heartbeat_busy_sharing_or_other_zone_provider_not_matched(self):
        r = self.buyer.request()
        self.sync(self.provider, availability=avail(busy=True))
        self.sync(self.uploader, requests=[r])
        self.assertEqual([], self.brain.jobs_for(self.provider.id))
        self.sync(self.provider, availability=avail(zone="z9:9"))
        self.sync(self.uploader, requests=[r])
        self.assertEqual([], self.brain.jobs_for(self.provider.id))
        self.sync(self.provider, availability=avail(sharing=True))
        self.sync(self.uploader, requests=[r])
        self.assertEqual([], self.brain.jobs_for(self.provider.id))
        # a fresh eligible heartbeat, then time passes: stale, not matched
        self.sync(self.provider, availability=avail())
        self.clock_now = NOW + AVAILABILITY_TTL_MS + 1
        r2 = self.buyer.request(rid="3333333333333333", now=self.clock_now)
        self.sync(self.uploader, requests=[r2])
        self.assertEqual([j for j in self.brain.jobs_for(self.provider.id) if j["request_id"] == r2.id], [])
        # and the stale availability is deleted after an hour
        self.clock_now = NOW + 61 * 60_000
        self.assertEqual(1, self.brain.cleanup()["availability_deleted"])

    def test_commercial_plan_cannot_exceed_the_ceiling(self):
        ok, _ = matching.admissible(300 * 100, 50 * 100)
        self.assertFalse(ok)
        ok, _ = matching.admissible(40 * 100, 50 * 100)
        self.assertTrue(ok)
        ok, _ = matching.admissible(300 * 100, 50 * 100, matching.SPONSORED, budget=300 * 100)
        self.assertTrue(ok)
        ok, _ = matching.admissible(300 * 100, 50 * 100, matching.GROWTH_SUBSIDY, budget=200 * 100)
        self.assertFalse(ok)
        d = matching.match(True, False, "z1:2", 300, False, [matching.Provider("p", "z1:2", True, False, 500, NOW, False)], NOW)
        self.assertEqual(matching.WAIT_FOR_SUPPLY, d.plan)
        d = matching.match(True, False, "z1:2", None, False, [matching.Provider("p", "z1:2", True, False, 500, NOW, False), matching.Provider("q", "z1:2", True, False, 300, NOW, False)], NOW)
        self.assertEqual(("ACTIVATE_PROVIDER", "q"), (d.plan, d.provider))
        self.assertEqual(matching.DIRECT_SOURCE, matching.match(True, False, "z1:2", None, True, [], NOW).plan)
        self.assertEqual(matching.NO_PLAN, matching.match(False, False, "z1:2", None, False, [], NOW).plan)

    def test_shared_source_dedups_across_observers_and_coordinates_are_never_stored(self):
        a, b, c = Node(), Node(), Node()
        for n in (a, b, c):
            self.sync(n, coverage=[cov(last_seen=NOW - 1000 * (1 + hash(n.id) % 5))])
        s = self.brain.source("wifi:0123456789abcdef")
        self.assertEqual(3, s["observers"])
        self.assertEqual(1, self.brain.counts()["coverage_sources"])
        # a ProkNet provider seen by many phones is one source
        for n in (a, b, c):
            self.sync(n, coverage=[cov(key="prok:24e480e6", kind="PROKNET", price=500, obs=1)])
        self.assertEqual(2, self.brain.counts()["coverage_sources"])
        self.assertEqual(3, self.brain.source("prok:24e480e6")["observers"])
        # a mere detection is not supply
        self.sync(a, coverage=[cov(key="wifi:deadbeefdeadbeef", validated=False, trust="UNKNOWN", price=-1)])
        cell = [x for x in self.brain.cells() if x["zone"] == "z1:2"][0]
        self.assertEqual(2, cell["potential"])
        self.assertEqual(0, cell["best_price"])
        # no column anywhere holds a coordinate
        cols = [r[1] for t in ("nodes", "coverage_sources", "coverage_cells", "internet_requests", "provider_availability", "jobs")
                for r in self.brain.db.execute("PRAGMA table_info(%s)" % t).fetchall()]
        for bad in ("lat", "lon", "lng", "latitude", "longitude", "gps"):
            self.assertFalse(any(bad in cname.lower() for cname in cols), bad)

    def test_shared_coverage_status_is_honest_and_decays(self):
        # sources only: YELLOW, never GREEN
        self.sync(self.uploader, coverage=[cov()])
        self.assertEqual("YELLOW", self.brain.cells()[0]["status"])
        # a provider sharing right now in the zone: GREEN on the server (the client still downgrades it)
        self.sync(self.provider, availability=avail(sharing=True))
        self.assertEqual("GREEN", [x for x in self.brain.cells() if x["zone"] == "z1:2"][0]["status"])
        # 20 minutes later that heartbeat is stale: back to YELLOW
        self.clock_now = NOW + 20 * 60_000
        self.brain.cleanup()
        self.assertEqual("YELLOW", [x for x in self.brain.cells() if x["zone"] == "z1:2"][0]["status"])
        # two days later: no cell at all
        self.clock_now = NOW + 2 * 24 * 3_600_000
        self.brain.cleanup()
        self.assertEqual([], [x for x in self.brain.cells() if x["zone"] == "z1:2"])
        self.assertAlmostEqual(1.0, matching.freshness(0))
        self.assertAlmostEqual(0.0, matching.freshness(matching.RECENT_MS))
        self.assertTrue(0.2 < matching.freshness(12 * 3_600_000) < 1.0)

    def test_download_renders_and_the_phone_parses_the_same_lines(self):
        r = self.buyer.request()
        self.sync(self.provider, availability=avail())
        d = self.sync(self.uploader, requests=[r], coverage=[cov()])
        text = protocol.render_download(d["server_time"], d["cells"], d["requests"], d["jobs"], d["statuses"], d["advice"])
        self.assertTrue(text.startswith("V\t1\t%d\n" % NOW))
        self.assertIn("X\tz1:2\t", text)
        d2 = self.sync(self.provider, availability=avail())
        text2 = protocol.render_download(d2["server_time"], d2["cells"], d2["requests"], d2["jobs"], d2["statuses"], d2["advice"])
        self.assertIn("J\tact-", text2)
        self.assertIn("R\t" + r.id, text2)
        # the request line the server sends still verifies with the origin's key
        line = [l for l in text2.split("\n") if l.startswith("R\t")][0]
        back = protocol.parse_request(line.split("\t")[1:])
        self.assertTrue(protocol.verify_request(back))


if __name__ == "__main__":
    unittest.main()
