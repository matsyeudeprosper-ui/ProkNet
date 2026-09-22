"""v0.17.0: the live network control plane. Demand, presence, activation.

What this is for. Until now two phones had to find each other by themselves. ProkNet
worked when they happened to be near each other with both apps open. This is the part
that makes it behave like a network: a buyer who can see nobody says so, and a provider
who could help is asked.

**The Brain never carries anybody's Internet.** It coordinates four things - who is
willing to share, who needs a connection, who was asked, and what happened - and then
gets out of the way. The bytes go over the Bluetooth L2CAP path that has been
hardware-proven since v0.10.2, unchanged.

Three rules this module is built around.

**The local path stays first.** Two phones that can already see each other must never
wait for a server. If this whole module is unreachable, ProkNet keeps working exactly as
it did in v0.16.5. Nothing here is on the critical path of a session.

**A zone is a hint, not a route.** `CoverageModel.zoneId` gives roughly a
neighbourhood - deliberately coarse, deliberately not an address. Two phones in one zone
are *worth asking*; whether Bluetooth actually reaches is a question only the phones can
answer. So the Brain may say PROVIDER_FOUND and it may never say CONNECTED, and the
buyer's screen must not promise Internet until the transport itself reports it.

**Only the phones know what worked.** Activation states move on what the two parties
report, and reporting is best-effort: a session that works while the status upload fails
is still a working session. None of this touches signed money or accounting - a Brain
status is a hint for matching, never evidence.

Privacy: a presence row holds a coarse zone, capability flags and an internal price
hint. Never a Mobile Money number, never coordinates, never anything that identifies a
house. `/v1/pay/destinations` remains the only way a number is revealed, and only to
somebody who owes that seller right now.
"""
import sqlite3
import time

# ---- how long things live ------------------------------------------------------------
# Every one of these is a constant here rather than a literal at a call site, because a
# pilot will want to change them without reading the matching code.

#: A provider that stops sending heartbeats is gone. Phones move, lose signal and get
#: closed; matching a buyer to a stale provider wastes the buyer's time, which is the
#: one thing this milestone exists to stop.
PRESENCE_TTL_MS = 120 * 1000

#: Kept a while after expiry, so reliability history and diagnostics survive a gap.
PRESENCE_KEEP_MS = 24 * 3600 * 1000

#: An offered activation the provider never answers. Short: the buyer is standing there.
OFFER_TTL_MS = 75 * 1000

#: Kept as the old name, because v0.17.0 called it this and tests refer to it.
ACTIVATION_TTL_MS = OFFER_TTL_MS

#: Once a provider has said yes, it gets a real window.
#:
#: v0.17.0 used the 75-second offer window for this too, which meant an accepted provider
#: vanished a minute later while the two people were still walking towards each other -
#: the buyer's screen said "un fournisseur se prépare" and then gave up for no reason the
#: buyer could see. Deciding to help and being within Bluetooth range are minutes apart.
LINK_WAIT_TTL_MS = 10 * 60 * 1000

#: A demand outlives several activation attempts.
#:
#: v0.17.1 raised this from ten minutes to fifteen, for a concrete reason. An accepted
#: provider now gets a ten-minute window to reach the buyer, and with both numbers equal
#: that window swallowed the whole request - so "try the next provider when the first one
#: never turns up" could never actually happen. Fifteen leaves room for one failed
#: rendezvous and another attempt.
DEMAND_TTL_MS = 15 * 60 * 1000

#: How many providers one demand may wake, one at a time. Twenty phones buzzing for one
#: small request is how a network makes itself unwelcome.
MAX_ACTIVATION_ATTEMPTS = 3

#: After a buyer cancels or lets a demand expire, a pause before it may ask again, so a
#: provider cannot be woken every few seconds by one person.
DEMAND_COOLDOWN_MS = 60 * 1000

#: Audit rows are for diagnostics, not for ever.
EVENT_KEEP_MS = 7 * 24 * 3600 * 1000

FREE = "FREE"
SPONSORED = "SPONSORED"
COMMERCIAL = "COMMERCIAL"
CLASSES = (FREE, SPONSORED, COMMERCIAL)

# ---- demand lifecycle ------------------------------------------------------------------
# Deliberately explicit. PROVIDER_FOUND may never become CONNECTED in one step: the Brain
# cannot know a Bluetooth path works merely because a provider exists in the same zone.
CREATED = "CREATED"
SEARCHING = "SEARCHING"
PROVIDER_FOUND = "PROVIDER_FOUND"
ACTIVATION_SENT = "ACTIVATION_SENT"
PROVIDER_ACCEPTED = "PROVIDER_ACCEPTED"
WAITING_FOR_LOCAL_LINK = "WAITING_FOR_LOCAL_LINK"
CONNECTED = "CONNECTED"
EXPIRED = "EXPIRED"
CANCELLED = "CANCELLED"
FAILED = "FAILED"

DEMAND_LIVE = (CREATED, SEARCHING, PROVIDER_FOUND, ACTIVATION_SENT, PROVIDER_ACCEPTED,
               WAITING_FOR_LOCAL_LINK)
DEMAND_TERMINAL = (CONNECTED, EXPIRED, CANCELLED, FAILED)

#: The one path a demand may walk. Anything not listed is refused rather than applied,
#: so a phone cannot report itself CONNECTED out of nowhere.
DEMAND_NEXT = {
    CREATED: (SEARCHING, PROVIDER_FOUND, CANCELLED, EXPIRED, FAILED),
    SEARCHING: (PROVIDER_FOUND, CANCELLED, EXPIRED, FAILED),
    PROVIDER_FOUND: (ACTIVATION_SENT, SEARCHING, CANCELLED, EXPIRED, FAILED),
    ACTIVATION_SENT: (PROVIDER_ACCEPTED, SEARCHING, CANCELLED, EXPIRED, FAILED),
    PROVIDER_ACCEPTED: (WAITING_FOR_LOCAL_LINK, SEARCHING, CANCELLED, EXPIRED, FAILED),
    WAITING_FOR_LOCAL_LINK: (CONNECTED, SEARCHING, CANCELLED, EXPIRED, FAILED),
}

# ---- activation lifecycle ---------------------------------------------------------------
OFFERED = "OFFERED"
ACCEPTED = "ACCEPTED"
DECLINED = "DECLINED"
LOCAL_LINK_SEEN = "LOCAL_LINK_SEEN"
ACT_CONNECTED = "CONNECTED"
ACT_EXPIRED = "EXPIRED"
ACT_FAILED = "FAILED"

ACTIVATION_NEXT = {
    OFFERED: (ACCEPTED, DECLINED, ACT_EXPIRED, ACT_FAILED),
    ACCEPTED: (LOCAL_LINK_SEEN, ACT_CONNECTED, ACT_EXPIRED, ACT_FAILED),
    LOCAL_LINK_SEEN: (ACT_CONNECTED, ACT_FAILED, ACT_EXPIRED),
}
ACTIVATION_OVER = (DECLINED, ACT_EXPIRED, ACT_FAILED, ACT_CONNECTED)

# ---- zone availability -------------------------------------------------------------------
GREEN = "GREEN"
YELLOW = "YELLOW"
RED = "RED"

SCHEMA = """
-- v0.17.0. Durable on purpose: a phone that asks for Internet and then loses signal must
-- still be findable, and a Brain restart must not lose who is waiting for whom.
CREATE TABLE IF NOT EXISTS network_presence(
    provider_id     TEXT PRIMARY KEY,
    zone            TEXT NOT NULL,
    updated_at      INTEGER NOT NULL,
    expires_at      INTEGER NOT NULL,
    upstream_available INTEGER NOT NULL DEFAULT 0,
    upstream_class  TEXT NOT NULL DEFAULT '',
    sharing_enabled INTEGER NOT NULL DEFAULT 0,
    commercial_ready INTEGER NOT NULL DEFAULT 0,
    free_ready      INTEGER NOT NULL DEFAULT 0,
    sponsored_ready INTEGER NOT NULL DEFAULT 0,
    current_load    INTEGER NOT NULL DEFAULT 0,
    max_buyers      INTEGER NOT NULL DEFAULT 1,
    offer_class     TEXT NOT NULL DEFAULT 'COMMERCIAL',
    price_hint_internal INTEGER NOT NULL DEFAULT -1
);
CREATE INDEX IF NOT EXISTS idx_presence_zone ON network_presence(zone, expires_at);

CREATE TABLE IF NOT EXISTS network_demand(
    demand_id       TEXT PRIMARY KEY,
    buyer_id        TEXT NOT NULL,
    zone            TEXT NOT NULL,
    created_at      INTEGER NOT NULL,
    expires_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,
    budget_centimes INTEGER NOT NULL DEFAULT 0,
    requested_class TEXT NOT NULL DEFAULT 'COMMERCIAL',
    connectivity_need TEXT NOT NULL DEFAULT '',
    status          TEXT NOT NULL,
    attempts        INTEGER NOT NULL DEFAULT 0,
    note            TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_demand_buyer ON network_demand(buyer_id, status);
CREATE INDEX IF NOT EXISTS idx_demand_zone ON network_demand(zone, status);

CREATE TABLE IF NOT EXISTS network_activation(
    activation_id   TEXT PRIMARY KEY,
    demand_id       TEXT NOT NULL,
    buyer_id        TEXT NOT NULL,
    provider_id     TEXT NOT NULL,
    zone            TEXT NOT NULL,
    created_at      INTEGER NOT NULL,
    expires_at      INTEGER NOT NULL,
    updated_at      INTEGER NOT NULL,
    state           TEXT NOT NULL,
    buyer_said      TEXT NOT NULL DEFAULT '',
    provider_said   TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_activation_provider ON network_activation(provider_id, state);
CREATE INDEX IF NOT EXISTS idx_activation_demand ON network_activation(demand_id, state);

-- Whether a provider the Brain suggested actually ended up carrying anything. A weak
-- matching signal and nothing else: this is NOT the v0.16 payment trust score, and the
-- two must never be mixed. Paying your debts and being reachable over Bluetooth are
-- different facts about a person.
CREATE TABLE IF NOT EXISTS network_reliability(
    provider_id     TEXT PRIMARY KEY,
    offers          INTEGER NOT NULL DEFAULT 0,
    accepted        INTEGER NOT NULL DEFAULT 0,
    connected       INTEGER NOT NULL DEFAULT 0,
    failed          INTEGER NOT NULL DEFAULT 0,
    last_success    INTEGER NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS network_events(
    id      INTEGER PRIMARY KEY AUTOINCREMENT,
    at      INTEGER NOT NULL,
    kind    TEXT NOT NULL,
    subject TEXT NOT NULL,
    detail  TEXT NOT NULL DEFAULT ''
);
CREATE INDEX IF NOT EXISTS idx_events_at ON network_events(at);

-- What a buyer last did, so a cancelled demand cannot be recreated instantly and used to
-- buzz the same provider over and over.
CREATE TABLE IF NOT EXISTS network_cooldown(
    buyer_id  TEXT PRIMARY KEY,
    until_ms  INTEGER NOT NULL
);
"""


#: v0.17.1 adds two columns to `network_activation`.
#:
#: Declared here once and applied two ways, which is the only way an upgraded server and a
#: fresh one end up identical: `db.MIGRATIONS[2]` runs them as **numbered migration 3** on
#: a database that already exists, and `NetworkPlane.__init__` adds whatever is missing on
#: a connection nobody migrated. Putting them into SCHEMA instead would have been the
#: v0.16.1 mistake on the server - `CREATE TABLE IF NOT EXISTS` does nothing to a table
#: that is already there, so no existing Brain would ever have got them.
ADDED_V3 = (
    ("accepted_at", "INTEGER NOT NULL DEFAULT 0"),
    ("link_deadline", "INTEGER NOT NULL DEFAULT 0"),
)

#: The same thing as SQL, for the numbered migration. `ALTER TABLE ADD COLUMN` with a
#: default does not rewrite the rows, so no activation can be lost on the way.
MIGRATION_3 = "\n".join(
    "ALTER TABLE network_activation ADD COLUMN %s %s;" % (name, decl)
    for name, decl in ADDED_V3)


class NetworkError(Exception):
    """The request cannot be honoured. The message is safe to return to the caller."""


def _b(v) -> int:
    return 1 if v else 0


class Presence:
    """One provider's willingness and capability, as it described itself.

    Capability is the provider's own claim - it is the only party that can see its
    upstream, its Bluetooth and its own settings. What the Brain enforces is that a
    *claim* never becomes an *obligation*: a provider that says it is commercially ready
    still goes through every v0.16 check on its own phone before it shares for money.
    """

    def __init__(self, row):
        for k in row.keys():
            setattr(self, k, row[k])
        self.row = row

    def fresh(self, now: int) -> bool:
        return now < int(self.expires_at)

    def has_capacity(self) -> bool:
        return int(self.current_load) < max(1, int(self.max_buyers))

    def ready_for(self, want: str) -> bool:
        """Is this provider usable for the class of help the buyer asked for?

        A buyer asking for COMMERCIAL will take free help gladly - it is cheaper. A buyer
        asking for FREE must not be silently charged, which is the whole point of the
        class being in the demand.
        """
        if not int(self.sharing_enabled) or not int(self.upstream_available):
            return False
        if want == FREE:
            return bool(int(self.free_ready))
        if want == SPONSORED:
            return bool(int(self.sponsored_ready)) or bool(int(self.free_ready))
        return (bool(int(self.commercial_ready)) or bool(int(self.free_ready))
                or bool(int(self.sponsored_ready)))

    def effective_class(self, want: str) -> str:
        """What this provider would actually give. Free beats everything for the buyer."""
        if int(self.free_ready):
            return FREE
        if int(self.sponsored_ready) and want != COMMERCIAL:
            return SPONSORED
        if int(self.sponsored_ready) and not int(self.commercial_ready):
            return SPONSORED
        return COMMERCIAL


class NetworkPlane:
    """Demand, presence and activation, all durable. One connection; the caller locks."""

    def __init__(self, db):
        # Takes an open connection so it shares the Brain's database and its transaction
        # boundaries rather than opening a second one against the same file.
        self.db = db if isinstance(db, sqlite3.Connection) else sqlite3.connect(db)
        self.db.row_factory = sqlite3.Row
        self.db.executescript(SCHEMA)
        # and anything a numbered migration would have added, for a connection that was
        # never migrated - a test's bare in-memory database, for instance
        have = {r[1] for r in self.db.execute("PRAGMA table_info(network_activation)")}
        for name, decl in ADDED_V3:
            if name not in have:
                self.db.execute("ALTER TABLE network_activation ADD COLUMN %s %s"
                                % (name, decl))
        self.db.commit()

    # ---- events -------------------------------------------------------------------

    def note(self, kind: str, subject: str, detail: str = "", now: int = None):
        self.db.execute("INSERT INTO network_events(at, kind, subject, detail) VALUES(?,?,?,?)",
                        (now if now is not None else int(time.time() * 1000), kind, subject, detail))

    def events(self, limit: int = 50):
        return [dict(r) for r in self.db.execute(
            "SELECT * FROM network_events ORDER BY id DESC LIMIT ?", (limit,))]

    # ---- presence -----------------------------------------------------------------

    def oldest_waiting_demand(self, zone: str, now: int):
        """The demand in this zone that has been waiting longest and can still be served.

        Oldest first, deliberately. When a provider appears, favouring the newest request
        would mean somebody who has been standing there for eight minutes watches newer
        arrivals get served ahead of them.
        """
        marks = ",".join("?" * len(DEMAND_LIVE))
        rows = self.db.execute(
            "SELECT * FROM network_demand WHERE zone=? AND status IN (%s) AND expires_at>?"
            " AND attempts<? ORDER BY created_at ASC" % marks,
            (zone,) + tuple(DEMAND_LIVE) + (now, MAX_ACTIVATION_ATTEMPTS)).fetchall()
        for r in rows:
            if self.live_activation_for(r["demand_id"]) is None:
                return dict(r)
        return None

    def serve_zone(self, zone: str, now: int):
        """A provider has just become useful here. Give the work to whoever waited longest.

        v0.17.1. v0.17.0 only matched at the moment a demand was created, so a buyer who
        asked before any provider was awake stayed SEARCHING for ever even once somebody
        turned sharing on beside them. This is the other half of the loop.
        """
        d = self.oldest_waiting_demand(zone, now)
        if d is None:
            return None
        return self.serve(d["demand_id"], now)

    def _reconsider_provider_offers(self, provider_id: str, old_zone: str, new_zone: str,
                                    now: int):
        """A provider that has moved cannot serve an offer it was given somewhere else.

        An unanswered offer in the old zone is closed at once, so the buyer's search
        resumes instead of waiting on somebody who has walked away. An offer already
        ACCEPTED is left alone: the two of them may well be moving towards each other,
        which is exactly what the local-link window is for.
        """
        if old_zone == new_zone:
            return
        for r in self.db.execute(
                "SELECT * FROM network_activation WHERE provider_id=? AND state=? AND zone=?",
                (provider_id, OFFERED, old_zone)).fetchall():
            self._expire_activation(dict(r), now, "the provider left the area")

    def put_presence(self, provider_id: str, body: dict, now: int) -> dict:
        """A heartbeat. Idempotent by construction: one row per provider, replaced.

        Sent every minute or two by every willing provider, so it has to be the cheapest
        thing here and it must never fail in a way that needs a retry to be different
        from the first attempt.
        """
        if not provider_id:
            raise NetworkError("presence needs an identity")
        zone = str(body.get("zone", ""))[:32]
        if not zone:
            raise NetworkError("presence needs a zone")
        offer = str(body.get("offerClass", COMMERCIAL))
        if offer not in CLASSES:
            raise NetworkError("unknown offer class")
        ttl = int(body.get("ttlMs", PRESENCE_TTL_MS))
        ttl = max(30_000, min(ttl, PRESENCE_TTL_MS))
        was = self.presence(provider_id)
        with self.db:
            self.db.execute(
                "INSERT INTO network_presence(provider_id, zone, updated_at, expires_at,"
                " upstream_available, upstream_class, sharing_enabled, commercial_ready,"
                " free_ready, sponsored_ready, current_load, max_buyers, offer_class,"
                " price_hint_internal) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
                " ON CONFLICT(provider_id) DO UPDATE SET"
                " zone=excluded.zone, updated_at=excluded.updated_at,"
                " expires_at=excluded.expires_at,"
                " upstream_available=excluded.upstream_available,"
                " upstream_class=excluded.upstream_class,"
                " sharing_enabled=excluded.sharing_enabled,"
                " commercial_ready=excluded.commercial_ready,"
                " free_ready=excluded.free_ready,"
                " sponsored_ready=excluded.sponsored_ready,"
                " current_load=excluded.current_load, max_buyers=excluded.max_buyers,"
                " offer_class=excluded.offer_class,"
                " price_hint_internal=excluded.price_hint_internal",
                (provider_id, zone, now, now + ttl,
                 _b(body.get("upstreamAvailable")), str(body.get("upstreamClass", ""))[:24],
                 _b(body.get("sharingEnabled")), _b(body.get("commercialReady")),
                 _b(body.get("freeReady")), _b(body.get("sponsoredReady")),
                 max(0, int(body.get("currentLoad", 0))),
                 max(1, int(body.get("maxBuyers", 1))), offer,
                 int(body.get("priceHintInternal", -1))))
            if was is not None:
                self._reconsider_provider_offers(provider_id, was.zone, zone, now)
        out = {"ok": True, "expiresAt": now + ttl, "ttlMs": ttl}
        # v0.17.1: a provider that has just become useful - newly awake, back under
        # capacity, or arrived in this zone - is offered the work somebody is waiting for.
        # Without this, matching only ever happened when a demand was created.
        p = self.presence(provider_id)
        if p is not None and p.ready_for(COMMERCIAL) and p.has_capacity():
            served = self.serve_zone(zone, now)
            if served and "activationId" in served:
                out["served"] = served["activationId"]
        return out

    def drop_presence(self, provider_id: str, now: int) -> dict:
        """A provider that stops sharing says so at once rather than waiting to go stale."""
        with self.db:
            self.db.execute("UPDATE network_presence SET expires_at=?, sharing_enabled=0"
                            " WHERE provider_id=?", (now, provider_id))
        return {"ok": True}

    def presence(self, provider_id: str):
        r = self.db.execute("SELECT * FROM network_presence WHERE provider_id=?",
                            (provider_id,)).fetchone()
        return Presence(r) if r is not None else None

    def fresh_in_zone(self, zone: str, now: int):
        return [Presence(r) for r in self.db.execute(
            "SELECT * FROM network_presence WHERE zone=? AND expires_at>? AND sharing_enabled=1"
            " ORDER BY provider_id", (zone, now))]

    # ---- demand -------------------------------------------------------------------

    def active_demand_for(self, buyer_id: str):
        marks = ",".join("?" * len(DEMAND_LIVE))
        r = self.db.execute(
            "SELECT * FROM network_demand WHERE buyer_id=? AND status IN (%s)"
            " ORDER BY created_at DESC LIMIT 1" % marks,
            (buyer_id,) + tuple(DEMAND_LIVE)).fetchone()
        return dict(r) if r is not None else None

    def demand(self, demand_id: str):
        r = self.db.execute("SELECT * FROM network_demand WHERE demand_id=?",
                            (demand_id,)).fetchone()
        return dict(r) if r is not None else None

    def put_demand(self, buyer_id: str, body: dict, now: int) -> dict:
        """A buyer says it needs Internet and could not find any itself.

        Idempotent on `demandId`, because this is exactly the request a phone will send
        again the moment it gets signal back. Re-sending the same id returns the same
        demand rather than waking a second provider.
        """
        demand_id = str(body.get("demandId", ""))[:32]
        if len(demand_id) < 8:
            raise NetworkError("a demand needs an id")
        zone = str(body.get("zone", ""))[:32]
        if not zone:
            raise NetworkError("a demand needs a zone")
        want = str(body.get("requestedClass", COMMERCIAL))
        if want not in CLASSES:
            raise NetworkError("unknown requested class")

        existing = self.demand(demand_id)
        if existing is not None:
            if existing["buyer_id"] != buyer_id:
                raise NetworkError("that demand belongs to somebody else")
            return {"ok": True, "duplicate": True, "demandId": demand_id,
                    "status": existing["status"]}

        # one live demand per buyer: a second is the same person asking twice, and the
        # honest answer is the one they already have
        live = self.active_demand_for(buyer_id)
        if live is not None:
            return {"ok": True, "duplicate": True, "demandId": live["demand_id"],
                    "status": live["status"], "note": "you already have a live request"}

        cool = self.db.execute("SELECT until_ms FROM network_cooldown WHERE buyer_id=?",
                               (buyer_id,)).fetchone()
        if cool is not None and now < int(cool["until_ms"]):
            raise NetworkError("please wait a moment before asking again")

        ttl = max(60_000, min(int(body.get("ttlMs", DEMAND_TTL_MS)), DEMAND_TTL_MS))
        with self.db:
            self.db.execute(
                "INSERT INTO network_demand(demand_id, buyer_id, zone, created_at,"
                " expires_at, updated_at, budget_centimes, requested_class,"
                " connectivity_need, status, attempts) VALUES(?,?,?,?,?,?,?,?,?,?,0)",
                (demand_id, buyer_id, zone, now, now + ttl, now,
                 max(0, int(body.get("budgetCentimes", 0))), want,
                 str(body.get("connectivityNeed", ""))[:32], CREATED))
            self.note("demand.created", demand_id, zone, now)
        return {"ok": True, "demandId": demand_id, "status": CREATED, "expiresAt": now + ttl}

    def set_demand_status(self, demand_id: str, to: str, now: int, why: str = "") -> bool:
        """Move a demand, only along a path that exists.

        Refusing an impossible move rather than applying it is what stops a phone
        reporting itself CONNECTED without ever having been offered a provider.
        """
        d = self.demand(demand_id)
        if d is None:
            return False
        if d["status"] == to:
            return True
        if to not in DEMAND_NEXT.get(d["status"], ()):
            return False
        self.db.execute("UPDATE network_demand SET status=?, updated_at=?, note=?"
                        " WHERE demand_id=?", (to, now, why[:120], demand_id))
        self.note("demand." + to.lower(), demand_id, why, now)
        return True

    def move_demand(self, demand_id: str, buyer_id: str, zone: str, now: int) -> dict:
        """The buyer has walked into a different neighbourhood while still searching.

        v0.17.1 policy, stated so it is not guesswork: **the same demand follows the
        buyer.** Its id, its age and its attempt count are kept - it is one need, and
        recreating it would reset the buyer's place in the queue and burn the cooldown -
        and any offer still outstanding in the old zone is closed at once, because a
        provider there can no longer help. The matcher then runs in the new zone.

        Only while the demand is live, and only by its own buyer.
        """
        d = self.demand(demand_id)
        if d is None:
            raise NetworkError("unknown demand")
        if d["buyer_id"] != buyer_id:
            raise NetworkError("only the buyer may move its own demand")
        if d["status"] not in DEMAND_LIVE:
            raise NetworkError("that demand is no longer live")
        if not zone:
            raise NetworkError("a demand needs a zone")
        if zone == d["zone"]:
            return {"ok": True, "duplicate": True, "zone": zone, "status": d["status"]}

        with self.db:
            # an offer in the old zone is dead: the two of them are no longer near
            for r in self.db.execute(
                    "SELECT * FROM network_activation WHERE demand_id=? AND state=?",
                    (demand_id, OFFERED)).fetchall():
                self._expire_activation(dict(r), now, "the buyer left the area")
            self.db.execute("UPDATE network_demand SET zone=?, updated_at=? WHERE demand_id=?",
                            (zone, now, demand_id))
            self.note("demand.moved", demand_id, zone, now)
        out = self.serve(demand_id, now)
        out["zone"] = zone
        return out

    def cancel_demand(self, demand_id: str, buyer_id: str, now: int) -> dict:
        """The buyer stopped needing it, or found Internet another way.

        Everything offered for it closes with it: a provider must not go on seeing a
        request nobody is waiting on.
        """
        d = self.demand(demand_id)
        if d is None:
            raise NetworkError("unknown demand")
        if d["buyer_id"] != buyer_id:
            raise NetworkError("only the buyer may cancel its own demand")
        if d["status"] in DEMAND_TERMINAL:
            return {"ok": True, "duplicate": True, "status": d["status"]}
        with self.db:
            self.db.execute("UPDATE network_demand SET status=?, updated_at=? WHERE demand_id=?",
                            (CANCELLED, now, demand_id))
            self._close_activations(demand_id, ACT_EXPIRED, now, "demand cancelled")
            self.db.execute("INSERT INTO network_cooldown(buyer_id, until_ms) VALUES(?,?)"
                            " ON CONFLICT(buyer_id) DO UPDATE SET until_ms=excluded.until_ms",
                            (buyer_id, now + DEMAND_COOLDOWN_MS))
            self.note("demand.cancelled", demand_id, "", now)
        return {"ok": True, "status": CANCELLED}

    def _close_activations(self, demand_id: str, state: str, now: int, why: str):
        for r in self.db.execute(
                "SELECT activation_id, state FROM network_activation WHERE demand_id=?"
                " AND state IN (?,?,?)", (demand_id, OFFERED, ACCEPTED, LOCAL_LINK_SEEN)):
            self.db.execute("UPDATE network_activation SET state=?, updated_at=?"
                            " WHERE activation_id=?", (state, now, r["activation_id"]))
            self.note("activation." + state.lower(), r["activation_id"], why, now)

    # ---- activation ---------------------------------------------------------------

    def activation(self, activation_id: str):
        r = self.db.execute("SELECT * FROM network_activation WHERE activation_id=?",
                            (activation_id,)).fetchone()
        return dict(r) if r is not None else None

    def live_activation_for(self, demand_id: str):
        r = self.db.execute(
            "SELECT * FROM network_activation WHERE demand_id=? AND state IN (?,?,?)"
            " ORDER BY created_at DESC LIMIT 1",
            (demand_id, OFFERED, ACCEPTED, LOCAL_LINK_SEEN)).fetchone()
        return dict(r) if r is not None else None

    def jobs_for_provider(self, provider_id: str, now: int, limit: int = 10):
        """A provider's own inbox, and only its own."""
        rows = [dict(r) for r in self.db.execute(
            "SELECT * FROM network_activation WHERE provider_id=? AND state IN (?,?,?)"
            " ORDER BY created_at LIMIT ?",
            (provider_id, OFFERED, ACCEPTED, LOCAL_LINK_SEEN, limit))]
        return [a for a in rows if now < self.deadline_of(a)]

    def offer(self, demand_id: str, provider_id: str, now: int) -> dict:
        """Ask one provider. One at a time, and never more than MAX_ACTIVATION_ATTEMPTS.

        Waking twenty phones for one small request is how a network makes itself
        unwelcome, so the fallback in `serve` is sequential: A declines, then B is asked.
        """
        d = self.demand(demand_id)
        if d is None:
            raise NetworkError("unknown demand")
        if d["status"] not in DEMAND_LIVE:
            raise NetworkError("that demand is no longer live")
        if self.live_activation_for(demand_id) is not None:
            raise NetworkError("an activation is already outstanding for that demand")
        if int(d["attempts"]) >= MAX_ACTIVATION_ATTEMPTS:
            raise NetworkError("no more activation attempts for that demand")

        activation_id = ("act-" + demand_id + "-" + str(int(d["attempts"]) + 1))[:48]
        expires = min(int(d["expires_at"]), now + ACTIVATION_TTL_MS)
        with self.db:
            self.db.execute(
                "INSERT INTO network_activation(activation_id, demand_id, buyer_id,"
                " provider_id, zone, created_at, expires_at, updated_at, state)"
                " VALUES(?,?,?,?,?,?,?,?,?)",
                (activation_id, demand_id, d["buyer_id"], provider_id, d["zone"],
                 now, expires, now, OFFERED))
            self.db.execute("UPDATE network_demand SET attempts=attempts+1 WHERE demand_id=?",
                            (demand_id,))
            self.set_demand_status(demand_id, PROVIDER_FOUND, now, provider_id[:8])
            self.set_demand_status(demand_id, ACTIVATION_SENT, now, provider_id[:8])
            self._bump(provider_id, "offers", now)
            self.note("activation.offered", activation_id, provider_id[:8], now)
        return {"ok": True, "activationId": activation_id, "expiresAt": expires}

    def answer(self, activation_id: str, provider_id: str, accept: bool, now: int) -> dict:
        """The provider tapped PARTAGER, or did not.

        Only the provider the job names may answer it. Idempotent: a retried accept
        returns the same answer rather than counting twice.
        """
        a = self.activation(activation_id)
        if a is None:
            raise NetworkError("unknown activation")
        if a["provider_id"] != provider_id:
            raise NetworkError("only the provider this was offered to may answer it")
        want = ACCEPTED if accept else DECLINED
        if a["state"] == want:
            return {"ok": True, "duplicate": True, "state": want}
        if want not in ACTIVATION_NEXT.get(a["state"], ()):
            raise NetworkError("that activation has already been settled")
        if now >= self.deadline_of(a):
            with self.db:
                self._expire_activation(a, now, "answered too late")
            raise NetworkError("that activation has expired")

        with self.db:
            self.db.execute("UPDATE network_activation SET state=?, updated_at=?,"
                            " provider_said=? WHERE activation_id=?",
                            (want, now, want, activation_id))
            if accept:
                # v0.17.1: a real window to walk towards each other, bounded by the
                # demand itself so an accepted activation can never outlive the request
                # it serves.
                d = self.demand(a["demand_id"])
                deadline = min(int(d["expires_at"]), now + LINK_WAIT_TTL_MS) if d else now + LINK_WAIT_TTL_MS
                self.db.execute("UPDATE network_activation SET accepted_at=?, link_deadline=?"
                                " WHERE activation_id=?", (now, deadline, activation_id))
                self.set_demand_status(a["demand_id"], PROVIDER_ACCEPTED, now, provider_id[:8])
                self._bump(provider_id, "accepted", now)
            self.note("activation." + want.lower(), activation_id, provider_id[:8], now)
        return {"ok": True, "state": want}

    @staticmethod
    def deadline_of(a) -> int:
        """When this activation runs out.

        The offer window while it is only OFFERED; the local-link window once a provider
        has agreed. One place, so nothing has to remember which applies.
        """
        if a["state"] in (ACCEPTED, LOCAL_LINK_SEEN) and int(a["link_deadline"] or 0) > 0:
            return int(a["link_deadline"])
        return int(a["expires_at"])

    def _expire_activation(self, a: dict, now: int, why: str):
        self.db.execute("UPDATE network_activation SET state=?, updated_at=? WHERE activation_id=?",
                        (ACT_EXPIRED, now, a["activation_id"]))
        self.note("activation.expired", a["activation_id"], why, now)
        # the demand goes back to searching so another candidate can be tried
        self.set_demand_status(a["demand_id"], SEARCHING, now, why)

    def report(self, activation_id: str, who: str, result: str, now: int) -> dict:
        """What actually happened, from one of the two parties.

        Best-effort and advisory. A session that works while this upload fails is still a
        working session, and nothing here touches signed money or accounting - it only
        teaches the matcher whether its suggestion was any good.

        A provider cannot declare itself CONNECTED for somebody else's demand: only the
        two parties the activation names may report on it, which is why `who` is the
        verified identity and not a field in the body.
        """
        a = self.activation(activation_id)
        if a is None:
            raise NetworkError("unknown activation")
        if who != a["provider_id"] and who != a["buyer_id"]:
            raise NetworkError("only the two parties may report on this activation")
        if result not in (LOCAL_LINK_SEEN, ACT_CONNECTED, ACT_FAILED):
            raise NetworkError("unknown result")

        side = "provider_said" if who == a["provider_id"] else "buyer_said"
        with self.db:
            self.db.execute("UPDATE network_activation SET %s=?, updated_at=?"
                            " WHERE activation_id=?" % side, (result, now, activation_id))
            moved = result in ACTIVATION_NEXT.get(a["state"], ())
            if moved:
                self.db.execute("UPDATE network_activation SET state=?, updated_at=?"
                                " WHERE activation_id=?", (result, now, activation_id))
            if result == ACT_CONNECTED:
                self.set_demand_status(a["demand_id"], WAITING_FOR_LOCAL_LINK, now, "link")
                self.set_demand_status(a["demand_id"], CONNECTED, now, side)
                self._bump(a["provider_id"], "connected", now, success=True)
            elif result == LOCAL_LINK_SEEN:
                self.set_demand_status(a["demand_id"], WAITING_FOR_LOCAL_LINK, now, side)
            elif result == ACT_FAILED:
                self._bump(a["provider_id"], "failed", now)
                self.set_demand_status(a["demand_id"], SEARCHING, now, "link failed")
            self.note("activation.report", activation_id, side + "=" + result, now)
        out = self.activation(activation_id)
        return {"ok": True, "state": out["state"],
                # both sides agreeing is worth more than either alone
                "corroborated": out["buyer_said"] == ACT_CONNECTED and out["provider_said"] == ACT_CONNECTED}

    # ---- reliability ------------------------------------------------------------------

    def _bump(self, provider_id: str, column: str, now: int, success: bool = False):
        self.db.execute("INSERT OR IGNORE INTO network_reliability(provider_id) VALUES(?)",
                        (provider_id,))
        self.db.execute("UPDATE network_reliability SET %s=%s+1 WHERE provider_id=?" % (column, column),
                        (provider_id,))
        if success:
            self.db.execute("UPDATE network_reliability SET last_success=? WHERE provider_id=?",
                            (now, provider_id))

    def reliability(self, provider_id: str) -> dict:
        r = self.db.execute("SELECT * FROM network_reliability WHERE provider_id=?",
                            (provider_id,)).fetchone()
        if r is None:
            return {"offers": 0, "accepted": 0, "connected": 0, "failed": 0, "last_success": 0}
        return dict(r)

    def score(self, provider_id: str) -> float:
        """A weak signal in [0, 1]. Unknown providers are not punished for being new.

        0.5 with no history on purpose: a network that only ever suggests providers it has
        already used can never grow, and a new seller has to get a first chance.
        """
        r = self.reliability(provider_id)
        seen = int(r["connected"]) + int(r["failed"])
        if seen == 0:
            return 0.5
        return int(r["connected"]) / float(seen)

    # ---- matching ---------------------------------------------------------------------

    def candidates(self, demand_id: str, now: int):
        """Fresh, eligible, capable providers for this demand, best first.

        Deterministic, and deliberately readable rather than clever:

        1. free help, then sponsored, then the cheapest commercial offer;
        2. the more reliable provider;
        3. the fresher heartbeat;
        4. the more spare capacity;
        5. the provider id, so two equal candidates always order the same way.

        No randomness. A pilot has to be able to explain why a particular phone rang.
        """
        d = self.demand(demand_id)
        if d is None:
            return []
        want = d["requested_class"]
        tried = {r["provider_id"] for r in self.db.execute(
            "SELECT provider_id FROM network_activation WHERE demand_id=?", (demand_id,))}

        out = []
        for p in self.fresh_in_zone(d["zone"], now):
            if p.provider_id == d["buyer_id"]:
                continue                      # a phone does not provide to itself
            if p.provider_id in tried:
                continue                      # already asked, and it said no or went quiet
            if not p.ready_for(want):
                continue
            if not p.has_capacity():
                continue
            cls = p.effective_class(want)
            if want == FREE and cls != FREE:
                continue                      # never silently charge somebody who asked for free
            price = int(p.price_hint_internal)
            if cls == COMMERCIAL and price < 0:
                continue                      # an unpriced commercial offer cannot be compared
            out.append((p, cls, price))

        rank = {FREE: 0, SPONSORED: 1, COMMERCIAL: 2}
        out.sort(key=lambda t: (
            rank[t[1]],
            t[2] if t[1] == COMMERCIAL else 0,
            -self.score(t[0].provider_id),
            -int(t[0].updated_at),
            -(max(1, int(t[0].max_buyers)) - int(t[0].current_load)),
            t[0].provider_id))
        return out

    def serve(self, demand_id: str, now: int) -> dict:
        """Find the next candidate for a live demand and offer it the job.

        Called when a demand appears and again whenever one falls through, so a buyer is
        never left waiting on a provider that said no.
        """
        d = self.demand(demand_id)
        if d is None:
            raise NetworkError("unknown demand")
        if d["status"] not in DEMAND_LIVE:
            return {"ok": True, "status": d["status"], "note": "not live"}
        if now >= int(d["expires_at"]):
            with self.db:
                self.set_demand_status(demand_id, EXPIRED, now, "window closed")
            return {"ok": True, "status": EXPIRED}
        live = self.live_activation_for(demand_id)
        if live is not None:
            return {"ok": True, "status": d["status"], "activationId": live["activation_id"]}
        if int(d["attempts"]) >= MAX_ACTIVATION_ATTEMPTS:
            with self.db:
                self.set_demand_status(demand_id, FAILED, now, "no provider accepted")
            return {"ok": True, "status": FAILED}

        cands = self.candidates(demand_id, now)
        if not cands:
            with self.db:
                self.set_demand_status(demand_id, SEARCHING, now, "no candidate")
            return {"ok": True, "status": SEARCHING, "candidates": 0}
        chosen = cands[0][0]
        out = self.offer(demand_id, chosen.provider_id, now)
        out["status"] = ACTIVATION_SENT
        out["candidates"] = len(cands)
        return out

    # ---- zone availability ----------------------------------------------------------

    def zone_status(self, zone: str, now: int) -> dict:
        """GREEN / YELLOW / RED for one zone. A hint, never a promise.

        GREEN means a fresh usable provider is here AND something recently worked.
        YELLOW means somebody could probably be woken. RED means nobody useful is known.
        None of the three says a Bluetooth path exists - only the phones can say that.
        """
        fresh = self.fresh_in_zone(zone, now)
        usable = [p for p in fresh if int(p.upstream_available) and p.has_capacity()]
        recent = self.db.execute(
            "SELECT COUNT(*) c FROM network_activation WHERE zone=? AND state=? AND updated_at>?",
            (zone, ACT_CONNECTED, now - 24 * 3600 * 1000)).fetchone()["c"]
        if usable and recent:
            status = GREEN
        elif fresh:
            status = YELLOW
        else:
            status = RED
        return {"zone": zone, "status": status, "providers": len(fresh),
                "usable": len(usable), "recentConnections": int(recent)}

    # ---- housekeeping -----------------------------------------------------------------

    def sweep(self, now: int) -> dict:
        """Expire what has run out and forget what is no longer interesting.

        Runs on the Brain's existing cleanup timer. Everything it decides is derived from
        a stored timestamp, so it is deterministic and testable with an injected clock.
        """
        n = {"activations": 0, "demands": 0, "presence": 0, "events": 0}
        with self.db:
            # OFFERED runs out on its own short window; ACCEPTED and LOCAL_LINK_SEEN run
            # out on the longer one, so nobody who agreed to help disappears in a minute
            for r in self.db.execute(
                    "SELECT * FROM network_activation WHERE state IN (?,?,?)",
                    (OFFERED, ACCEPTED, LOCAL_LINK_SEEN)).fetchall():
                a = dict(r)
                if now < self.deadline_of(a):
                    continue
                why = "no answer" if a["state"] == OFFERED else "the phones never met"
                self._expire_activation(a, now, why)
                n["activations"] += 1
            marks = ",".join("?" * len(DEMAND_LIVE))
            for r in self.db.execute(
                    "SELECT demand_id FROM network_demand WHERE status IN (%s) AND expires_at<=?"
                    % marks, tuple(DEMAND_LIVE) + (now,)).fetchall():
                self.db.execute("UPDATE network_demand SET status=?, updated_at=? WHERE demand_id=?",
                                (EXPIRED, now, r["demand_id"]))
                self.note("demand.expired", r["demand_id"], "", now)
                n["demands"] += 1
            n["presence"] = self.db.execute(
                "DELETE FROM network_presence WHERE expires_at<=?",
                (now - PRESENCE_KEEP_MS,)).rowcount
            n["events"] = self.db.execute(
                "DELETE FROM network_events WHERE at<=?", (now - EVENT_KEEP_MS,)).rowcount
            self.db.execute("DELETE FROM network_cooldown WHERE until_ms<=?", (now,))
        return {k: v for k, v in n.items() if v}

    def counts(self) -> dict:
        q = lambda sql, *a: int(self.db.execute(sql, a).fetchone()[0])
        return {
            "presence": q("SELECT COUNT(*) FROM network_presence"),
            "demands_live": q("SELECT COUNT(*) FROM network_demand WHERE status IN (%s)"
                              % ",".join("?" * len(DEMAND_LIVE)), *DEMAND_LIVE),
            "activations_live": q("SELECT COUNT(*) FROM network_activation WHERE state IN (?,?,?)",
                                  OFFERED, ACCEPTED, LOCAL_LINK_SEEN),
            "connected_total": q("SELECT COUNT(*) FROM network_activation WHERE state=?",
                                 ACT_CONNECTED),
        }
