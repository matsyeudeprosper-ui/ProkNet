"""The Network Brain's memory: SQLite now, plain SQL and a migrations list so PostgreSQL later is a driver swap.

Every write is idempotent: the same upload twice leaves the same rows. Nothing
here is a position: zones are the ~500 m cells the phones already use, Wi-Fi
sources are hashed ids, nodes are ProkNet identities.
"""
import sqlite3
import time
from typing import Callable, Dict, List, Optional, Tuple

from . import ledger
from . import matching
from . import network
from .protocol import Availability, Coverage, Request, verify_request

MIGRATIONS = [
    # 1: the pilot schema
    """
    CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS nodes (
        node_id TEXT PRIMARY KEY, pub TEXT NOT NULL, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL, zone TEXT NOT NULL, syncs INTEGER NOT NULL DEFAULT 0);
    CREATE TABLE IF NOT EXISTS coverage_sources (
        source_key TEXT PRIMARY KEY, kind TEXT NOT NULL, zone TEXT NOT NULL, first_seen INTEGER NOT NULL, last_seen INTEGER NOT NULL,
        validation_count INTEGER NOT NULL DEFAULT 0, observation_count INTEGER NOT NULL DEFAULT 0, best_price INTEGER NOT NULL DEFAULT -1,
        trust TEXT NOT NULL DEFAULT 'UNKNOWN', last_validated INTEGER NOT NULL DEFAULT 0);
    CREATE TABLE IF NOT EXISTS coverage_observers (source_key TEXT NOT NULL, node_id TEXT NOT NULL, last_seen INTEGER NOT NULL, PRIMARY KEY (source_key, node_id));
    CREATE TABLE IF NOT EXISTS coverage_cells (
        zone TEXT PRIMARY KEY, last_seen INTEGER NOT NULL, direct INTEGER NOT NULL, potential INTEGER NOT NULL, best_price INTEGER NOT NULL,
        status TEXT NOT NULL, observers INTEGER NOT NULL, updated_at INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS internet_requests (
        id TEXT PRIMARY KEY, origin_short TEXT NOT NULL, origin_pub TEXT NOT NULL, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL, zone TEXT NOT NULL, desired_mb INTEGER NOT NULL, desired_minutes INTEGER NOT NULL, ceiling INTEGER NOT NULL,
        urgency INTEGER NOT NULL, state TEXT NOT NULL, generation INTEGER NOT NULL, hops INTEGER NOT NULL, signature TEXT NOT NULL,
        uploaded_by TEXT NOT NULL, received_at INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS provider_availability (
        node_id TEXT PRIMARY KEY, zone TEXT NOT NULL, potential INTEGER NOT NULL, sharing INTEGER NOT NULL, upstream INTEGER NOT NULL,
        price INTEGER NOT NULL, busy INTEGER NOT NULL, capable INTEGER NOT NULL, last_heartbeat INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS jobs (
        id TEXT PRIMARY KEY, type TEXT NOT NULL, zone TEXT NOT NULL, request_id TEXT NOT NULL, provider TEXT, created_at INTEGER NOT NULL,
        expires_at INTEGER NOT NULL, reward INTEGER NOT NULL DEFAULT 0, max_cost INTEGER NOT NULL DEFAULT 0, requirements TEXT NOT NULL DEFAULT '',
        state TEXT NOT NULL, updated_at INTEGER NOT NULL);
    CREATE TABLE IF NOT EXISTS sync_receipts (node_id TEXT NOT NULL, ts INTEGER NOT NULL, received_at INTEGER NOT NULL, PRIMARY KEY (node_id, ts));
    """,
    # 2: v0.17.0, the live control plane. Presence, demand, activation, reliability and an
    # audit trail, all durable - a phone that asks for Internet and then loses signal must
    # still be findable, and a Brain restart must not lose who is waiting for whom. Added
    # as a numbered migration rather than another CREATE IF NOT EXISTS, so the server can
    # always say which schema it is on.
    network.SCHEMA,
    # 3: v0.17.1. An accepted provider needs a real window to reach the buyer, separate
    # from the short window it had to answer in. Additive only: two columns with defaults,
    # so no existing activation, demand or financial row is touched.
    network.MIGRATION_3,
    # 4: v0.17.2. A demand carries the buyer's own signed NetRequest, so a provider that
    # has never met the buyer can verify the request itself. Additive: one column with a
    # default, so every existing demand, activation and financial row is untouched.
    network.MIGRATION_4,
    # 5: v0.18.0. The Prok ledger: postings, holds, withdrawals, observed top-ups, audit.
    # New tables only, so every existing row is untouched. A ledger cannot send money;
    # these tables record what was proved and what a person still has to do by hand.
    ledger.SCHEMA,
]

TOMBSTONE_KEEP_MS = 2 * 3_600_000
AVAILABILITY_TTL_MS = 15 * 60_000
AVAILABILITY_DELETE_MS = 60 * 60_000
SOURCE_KEEP_MS = 30 * 24 * 3_600_000
JOB_TTL_MS = 10 * 60_000
JOB_KEEP_MS = 24 * 3_600_000


class Brain:
    def __init__(self, path: str = ":memory:", clock: Callable[[], int] = None):
        self.clock = clock or (lambda: int(time.time() * 1000))
        self.db = sqlite3.connect(path, check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self._migrate()

    def _migrate(self):
        cur = self.db.cursor()
        cur.execute("CREATE TABLE IF NOT EXISTS schema_version (version INTEGER NOT NULL)")
        row = cur.execute("SELECT MAX(version) AS v FROM schema_version").fetchone()
        have = row["v"] or 0
        for i, sql in enumerate(MIGRATIONS, start=1):
            if i > have:
                cur.executescript(sql)
                cur.execute("INSERT INTO schema_version(version) VALUES (?)", (i,))
        self.db.commit()

    # ---- the one operation ------------------------------------------------------------------------------------

    def sync(self, up, now: Optional[int] = None) -> dict:
        now = self.clock() if now is None else now
        cur = self.db.cursor()
        cur.execute("INSERT INTO nodes(node_id, pub, first_seen, last_seen, zone, syncs) VALUES (?,?,?,?,?,1) "
                    "ON CONFLICT(node_id) DO UPDATE SET last_seen=excluded.last_seen, zone=excluded.zone, syncs=syncs+1", (up.node_id, up.pub, now, now, up.zone))
        cur.execute("INSERT OR IGNORE INTO sync_receipts(node_id, ts, received_at) VALUES (?,?,?)", (up.node_id, up.ts, now))
        rejected = []
        for c in up.coverage:
            self._observe(cur, up.node_id, c, now)
        if up.availability is not None:
            a = up.availability
            cur.execute("INSERT INTO provider_availability(node_id, zone, potential, sharing, upstream, price, busy, capable, last_heartbeat) VALUES (?,?,?,?,?,?,?,?,?) "
                        "ON CONFLICT(node_id) DO UPDATE SET zone=excluded.zone, potential=excluded.potential, sharing=excluded.sharing, upstream=excluded.upstream, "
                        "price=excluded.price, busy=excluded.busy, capable=excluded.capable, last_heartbeat=excluded.last_heartbeat",
                        (up.node_id, a.zone, int(a.potential), int(a.sharing), a.upstream, a.price, int(a.busy), int(a.capable), now))
        for r in up.requests:
            why = self._store_request(cur, r, up.node_id, now)
            if why:
                rejected.append((r.id, why))
        for (jid, state, ts) in up.jobs:
            self._job_change(cur, jid, state, now)
        self._rebuild_cells(cur, now)
        self._match_open_requests(cur, now)
        self.db.commit()
        return self._download(cur, up, now, rejected)

    # ---- coverage: one record per real source, observers counted once each -----------------------------------------

    def _observe(self, cur, node_id: str, c: Coverage, now: int):
        last_seen = min(c.last_seen, now)
        cur.execute("INSERT INTO coverage_sources(source_key, kind, zone, first_seen, last_seen, validation_count, observation_count, best_price, trust, last_validated) "
                    "VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(source_key) DO UPDATE SET "
                    "zone=CASE WHEN excluded.last_seen >= last_seen THEN excluded.zone ELSE zone END, "
                    "first_seen=MIN(first_seen, excluded.first_seen), last_seen=MAX(last_seen, excluded.last_seen), "
                    "validation_count=validation_count+excluded.validation_count, observation_count=observation_count+excluded.observation_count, "
                    "best_price=CASE WHEN excluded.best_price >= 0 AND (best_price < 0 OR excluded.best_price < best_price) THEN excluded.best_price ELSE best_price END, "
                    "trust=CASE WHEN excluded.trust <> 'UNKNOWN' THEN excluded.trust ELSE trust END, "
                    "last_validated=MAX(last_validated, excluded.last_validated)",
                    (c.key, c.kind, c.zone, last_seen, last_seen, 1 if c.validated else 0, max(1, c.observations), c.price, c.trust, last_seen if c.validated else 0))
        # the same observer reporting again is not a second observer
        cur.execute("INSERT INTO coverage_observers(source_key, node_id, last_seen) VALUES (?,?,?) ON CONFLICT(source_key, node_id) DO UPDATE SET last_seen=MAX(last_seen, excluded.last_seen)",
                    (c.key, node_id, last_seen))

    def _rebuild_cells(self, cur, now: int):
        rows = cur.execute("SELECT s.*, (SELECT COUNT(*) FROM coverage_observers o WHERE o.source_key = s.source_key) AS observers FROM coverage_sources s WHERE ? - last_seen <= ?",
                           (now, matching.RECENT_MS)).fetchall()
        by_zone: Dict[str, dict] = {}
        for s in rows:
            z = by_zone.setdefault(s["zone"], {"zone": s["zone"], "last_seen": 0, "potential": 0, "best_price": -1, "observers": 0})
            z["last_seen"] = max(z["last_seen"], s["last_seen"])
            z["observers"] = max(z["observers"], s["observers"])
            if matching.usable_source(s["kind"], s["trust"], s["last_validated"] > 0):
                z["potential"] += 1
                if s["best_price"] >= 0 and (z["best_price"] < 0 or s["best_price"] < z["best_price"]):
                    z["best_price"] = s["best_price"]
        sharing = {}
        for p in cur.execute("SELECT zone, COUNT(*) AS n, MAX(last_heartbeat) AS hb FROM provider_availability WHERE sharing = 1 AND ? - last_heartbeat <= ? GROUP BY zone",
                             (now, AVAILABILITY_TTL_MS)).fetchall():
            sharing[p["zone"]] = (p["n"], p["hb"])
            z = by_zone.setdefault(p["zone"], {"zone": p["zone"], "last_seen": 0, "potential": 0, "best_price": -1, "observers": 0})
            z["last_seen"] = max(z["last_seen"], p["hb"])
        cur.execute("DELETE FROM coverage_cells")
        for z in by_zone.values():
            direct = sharing.get(z["zone"], (0, 0))[0]
            status = matching.cell_status(direct, z["potential"], z["last_seen"], now)
            cur.execute("INSERT INTO coverage_cells(zone, last_seen, direct, potential, best_price, status, observers, updated_at) VALUES (?,?,?,?,?,?,?,?)",
                        (z["zone"], z["last_seen"], direct, z["potential"], z["best_price"], status, z["observers"], now))

    # ---- requests: signed, generation-ordered, tombstones final -------------------------------------------------------------

    def _store_request(self, cur, r: Request, uploaded_by: str, now: int) -> Optional[str]:
        if not verify_request(r):
            return "invalid signature"
        cur_row = cur.execute("SELECT generation, state FROM internet_requests WHERE id = ?", (r.id,)).fetchone()
        if cur_row is not None:
            if r.generation < cur_row["generation"]:
                return "older generation"
            if r.generation == cur_row["generation"]:
                return None  # idempotent
            if cur_row["state"] in ("CANCELLED", "FULFILLED") and not r.tombstone:
                return "resurrection blocked"
        if not r.tombstone and r.expired(now):
            return "expired"
        if not r.tombstone and r.hops > matching_max_hops():
            return "hops exhausted"
        state = r.state if r.tombstone else "UPLOADED"
        cur.execute("INSERT INTO internet_requests(id, origin_short, origin_pub, created_at, updated_at, expires_at, zone, desired_mb, desired_minutes, ceiling, urgency, state, generation, hops, signature, uploaded_by, received_at) "
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON CONFLICT(id) DO UPDATE SET updated_at=excluded.updated_at, state=excluded.state, generation=excluded.generation, hops=excluded.hops, "
                    "signature=excluded.signature, uploaded_by=excluded.uploaded_by, received_at=excluded.received_at",
                    (r.id, r.origin_short.lower(), r.origin_pub, r.created_at, r.updated_at, r.expires_at, r.zone, r.desired_mb, r.desired_minutes, r.ceiling, r.urgency,
                     state, r.generation, r.hops, r.signature, uploaded_by, now))
        if r.tombstone:
            cur.execute("UPDATE jobs SET state='CANCELLED', updated_at=? WHERE request_id=? AND state IN ('OPEN','OFFERED','ACCEPTED','ACTIVE')", (now, r.id))
        return None

    def _job_change(self, cur, jid: str, state: str, now: int):
        allowed = {"OPEN": ("OFFERED", "ACCEPTED", "EXPIRED", "CANCELLED"), "OFFERED": ("ACCEPTED", "OPEN", "EXPIRED", "CANCELLED"),
                   "ACCEPTED": ("ACTIVE", "FAILED", "EXPIRED", "CANCELLED"), "ACTIVE": ("COMPLETED", "FAILED", "CANCELLED")}
        row = cur.execute("SELECT state FROM jobs WHERE id = ?", (jid,)).fetchone()
        if row is None or state not in allowed.get(row["state"], ()):
            return
        cur.execute("UPDATE jobs SET state=?, updated_at=? WHERE id=?", (state, now, jid))

    # ---- matching: an activation job per (request, provider), never twice -----------------------------------------------------

    def providers(self, cur, now: int) -> List[matching.Provider]:
        return [matching.Provider(p["node_id"], p["zone"], bool(p["potential"]), bool(p["sharing"]), p["price"], p["last_heartbeat"], bool(p["busy"]))
                for p in cur.execute("SELECT * FROM provider_availability WHERE ? - last_heartbeat <= ?", (now, AVAILABILITY_TTL_MS)).fetchall()]

    def _match_open_requests(self, cur, now: int):
        provs = self.providers(cur, now)
        for r in cur.execute("SELECT * FROM internet_requests WHERE state NOT IN ('CANCELLED','FULFILLED','EXPIRED') AND expires_at > ?", (now,)).fetchall():
            ceiling = None if r["ceiling"] < 0 else r["ceiling"]
            # a provider sharing in the zone now is a direct source the buyer will see by itself
            direct = any(p.sharing and p.zone == r["zone"] and r["zone"] != matching.NO_ZONE for p in provs)
            d = matching.match(True, False, r["zone"], ceiling, direct, provs, now)
            if d.plan != matching.ACTIVATE_PROVIDER:
                continue
            existing = cur.execute("SELECT id FROM jobs WHERE request_id=? AND provider=? AND type='PROVIDER_ACTIVATION'", (r["id"], d.provider)).fetchone()
            if existing is not None:
                continue
            jid = "act-%s-%s" % (r["id"][:8], d.provider[:8])
            cur.execute("INSERT OR IGNORE INTO jobs(id, type, zone, request_id, provider, created_at, expires_at, reward, max_cost, requirements, state, updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                        (jid, "PROVIDER_ACTIVATION", r["zone"], r["id"], d.provider, now, min(r["expires_at"], now + JOB_TTL_MS), 0, 0, d.reason, "OFFERED", now))
            cur.execute("UPDATE internet_requests SET state='SUPPLY_POSSIBLE', updated_at=? WHERE id=? AND state='UPLOADED'", (now, r["id"]))

    # ---- the download ---------------------------------------------------------------------------------------------------------------

    def _download(self, cur, up, now: int, rejected) -> dict:
        cells = [dict(c) for c in cur.execute("SELECT * FROM coverage_cells ORDER BY last_seen DESC LIMIT 100").fetchall()]
        short = up.node_id[:8]
        jobs = [dict(j) for j in cur.execute("SELECT * FROM jobs WHERE provider=? AND state IN ('OFFERED','ACCEPTED','ACTIVE') AND expires_at > ?", (up.node_id, now)).fetchall()]
        # requests this node should know: the ones its jobs point at, and open ones in its zone if it is a potential provider
        ids = {j["request_id"] for j in jobs}
        av = cur.execute("SELECT potential, price FROM provider_availability WHERE node_id=?", (up.node_id,)).fetchone()
        if av is not None and av["potential"] and up.zone != matching.NO_ZONE:
            # only requests this provider could actually serve: same zone, and a ceiling that admits its price
            for r in cur.execute("SELECT id FROM internet_requests WHERE zone=? AND state NOT IN ('CANCELLED','FULFILLED','EXPIRED') AND expires_at > ? AND origin_short <> ? "
                                 "AND (ceiling < 0 OR ceiling >= ?)", (up.zone, now, short, av["price"])).fetchall():
                ids.add(r["id"])
        reqs = []
        for rid in sorted(ids):
            row = cur.execute("SELECT * FROM internet_requests WHERE id=?", (rid,)).fetchone()
            if row is not None:
                reqs.append(_row_request(row))
        statuses = [(r["id"], r["state"], r["generation"]) for r in cur.execute("SELECT id, state, generation FROM internet_requests WHERE origin_short=? ORDER BY updated_at DESC LIMIT 10", (short,)).fetchall()]
        advice = ["rejected %s: %s" % (rid, why) for (rid, why) in rejected]
        return {"server_time": now, "cells": cells, "requests": reqs, "jobs": jobs, "statuses": statuses, "advice": advice}

    # ---- cleanup: deterministic TTLs ----------------------------------------------------------------------------------------------------

    def cleanup(self, now: Optional[int] = None) -> dict:
        now = self.clock() if now is None else now
        cur = self.db.cursor()
        n = {}
        n["requests_expired"] = cur.execute("UPDATE internet_requests SET state='EXPIRED', updated_at=? WHERE state NOT IN ('CANCELLED','FULFILLED','EXPIRED') AND expires_at <= ?", (now, now)).rowcount
        n["requests_deleted"] = cur.execute("DELETE FROM internet_requests WHERE state IN ('CANCELLED','FULFILLED','EXPIRED') AND ? - updated_at > ?", (now, TOMBSTONE_KEEP_MS)).rowcount
        n["availability_deleted"] = cur.execute("DELETE FROM provider_availability WHERE ? - last_heartbeat > ?", (now, AVAILABILITY_DELETE_MS)).rowcount
        n["jobs_expired"] = cur.execute("UPDATE jobs SET state='EXPIRED', updated_at=? WHERE state IN ('OPEN','OFFERED','ACCEPTED','ACTIVE') AND expires_at <= ?", (now, now)).rowcount
        n["jobs_deleted"] = cur.execute("DELETE FROM jobs WHERE state IN ('COMPLETED','FAILED','EXPIRED','CANCELLED') AND ? - updated_at > ?", (now, JOB_KEEP_MS)).rowcount
        n["sources_deleted"] = cur.execute("DELETE FROM coverage_sources WHERE ? - last_seen > ?", (now, SOURCE_KEEP_MS)).rowcount
        cur.execute("DELETE FROM coverage_observers WHERE source_key NOT IN (SELECT source_key FROM coverage_sources)")
        cur.execute("DELETE FROM sync_receipts WHERE ? - received_at > ?", (now, 24 * 3_600_000))
        self._rebuild_cells(cur, now)
        self.db.commit()
        return n

    # ---- views for /health and tests -------------------------------------------------------------------------------------------------------

    def schema_version(self) -> int:
        """Which numbered migration this database is on. For /health and for operators."""
        row = self.db.execute("SELECT MAX(version) AS v FROM schema_version").fetchone()
        return int(row["v"] or 0)

    def counts(self) -> dict:
        cur = self.db.cursor()
        return {t: cur.execute("SELECT COUNT(*) AS n FROM " + t).fetchone()["n"]
                for t in ("nodes", "coverage_sources", "coverage_cells", "internet_requests", "provider_availability", "jobs")}

    def request(self, rid: str) -> Optional[Request]:
        row = self.db.execute("SELECT * FROM internet_requests WHERE id=?", (rid,)).fetchone()
        return None if row is None else _row_request(row)

    def cells(self) -> List[dict]:
        return [dict(c) for c in self.db.execute("SELECT * FROM coverage_cells").fetchall()]

    def source(self, key: str) -> Optional[dict]:
        row = self.db.execute("SELECT s.*, (SELECT COUNT(*) FROM coverage_observers o WHERE o.source_key = s.source_key) AS observers FROM coverage_sources s WHERE source_key=?", (key,)).fetchone()
        return None if row is None else dict(row)

    def jobs_for(self, node_id: str) -> List[dict]:
        return [dict(j) for j in self.db.execute("SELECT * FROM jobs WHERE provider=? ORDER BY created_at", (node_id,)).fetchall()]


def matching_max_hops() -> int:
    return 6


def _row_request(row) -> Request:
    return Request(row["id"], row["origin_short"], row["origin_pub"], row["created_at"], row["updated_at"], row["expires_at"], row["zone"],
                   row["desired_mb"], row["desired_minutes"], row["ceiling"], row["urgency"], row["state"], row["generation"], row["hops"], row["signature"])
