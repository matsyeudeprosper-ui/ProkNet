"""v0.19.0: the free Internet finder - consented venues, coarse sightings, checks, rewards.

The product rule this module keeps: **a sighting is never a place.** A phone that saw a
Wi-Fi reports a coarse, hashed sighting in a 500 m cell; a place people are sent to walk
to is a venue an operator recorded consent for, with an entrance the owner approved,
access terms, hours, one real DEVICE check with validated Internet and a second
independent check. Nothing here publishes a venue on its own, and an open SSID alone
never becomes a destination (contract §10.2).

Two layers, kept in two tables and never mixed:
    place_venues     exact entrance (lat/lon) - ONLY for a consented public venue, and the
                     coordinates leave this server only while the venue is visible
    place_sightings  coarse: a cell string, a privacy-preserving radio hash, a kind, a
                     signal and whether Android reported validated Internet. Never an SSID,
                     never a coordinate. The reporter's own id is kept for the scout rule.

STATUS RULE (`status`, a pure function tested on every boundary). A venue answer is one of
    WORKING_NOW        a successful DEVICE/OPERATOR check less than 5 min ago, venue open
                       now, and no failure after it
    RECENTLY_VERIFIED  a successful check less than 24 h ago (the age is in status_age_ms)
    OLDER_CHECK        24 h .. 7 d
    UNVERIFIED_STALE   7 d or more, or never checked; the phone leaves it out of the
                       default recommended list
    REPORTED           the venue is PENDING_REVIEW, or a community report is open
    UNAVAILABLE        two independent failures within 24 h with no success after them,
                       or one OPERATOR-confirmed failure (closure), or the venue is
                       HIDDEN / REMOVED
Priority when several apply: UNAVAILABLE, then REPORTED, then the freshness ladder.
Visitor "worked / didn't work" reports are VISITOR checks: their failures count towards
the two-failure demotion and their successes raise confidence, but only a DEVICE or an
OPERATOR check makes a venue WORKING_NOW or RECENTLY_VERIFIED - a claim is not a probe.
The French label for each state is STATUS_TEXT and is written to
server/tests/fixtures/place_status.txt; the phone asserts equality with that file.

PUBLICATION (`venue_publish`): refused unless ALL of: consent recorded by an operator
(who, when, which document), access_rule set, hours set, at least one successful DEVICE
check, and a second successful check by a DIFFERENT checker (DEVICE or OPERATOR). Each
missing precondition is its own reason slug. Owner removal hides at once; a SAFETY report
hides at once pending review; every other report leaves the venue visible as REPORTED.

DEDUP: same radio_hash AND same cell = the same spot (a second venue is refused, a
sighting is matched to it). Same normalised name within 100 m = a candidate duplicate,
recorded on the new venue for the operator to look at, never merged automatically.
Identical SSIDs in different cells are different spots - and since only hashes arrive,
the cell is what separates them.

SCOUT REWARDS (exact numbers from §10.2, promotional credit, never cash):
    FIRST    10 FCFA = 1000 centimes, to the reporter of the EARLIEST sighting with
             validated_internet=1 whose radio_hash+cell dedupes to the venue, decided when
             the venue is PUBLISHED (which needs the second independent check). Once per
             venue, ever. Never to the venue's own owner.
    REFRESH  3 FCFA = 300 centimes, to the DEVICE checker whose successful check follows
             7 days without a success or a confirmed failure (UNAVAILABLE). At most once
             per venue in any 7-day window. Operator checks earn nothing.
    caps     30 FCFA/day and 100 FCFA/month per scout, 5,000 FCFA/month for everybody
             (UTC calendar day and month). A reward that would cross a cap is recorded
             REJECTED with the cap as its reason; `offer_available(now)` is then false and
             the app says "pas d'offre scout payée" before a contribution, which is still
             accepted and stored. EARNED rewards expire 90 days after they were earned.
             A scout may appeal a REJECTED reward within 30 days: the appeal text is kept
             and the state stays REJECTED until an operator decides.
On EARNED, and only then, the ledger callback posts debit "fund:promo" -> credit
"promo:<scout>"; on expiry the same amount goes back. The callback is whatever the lead
wires: a function `ledger_post(now, kind, debit, credit, amount, ref, memo)` or a Ledger
object with `_post`. With no ledger, the decision is still recorded here.

HTTP surface (`handle_get(who, path, query, now)` and `handle_post(who, path, body, now)`,
returning (code, dict); PlacesError becomes (code, {"error", "reason"}); an unknown path
is (404, {"error": "not found"})). Public GETs work unsigned (who == "").
    GET  /v1/places/index?city=&if_newer=      the city index for offline caching;
                                                {"unchanged": true} when nothing is newer
    GET  /v1/places/venue?id=                   one venue card (visible venues only)
    GET  /v1/places/search?city=&q=             name / neighbourhood / kind search
    GET  /v1/places/offer                       the scout offer for `who` (may be "")
    GET  /v1/places/rewards                     signed: the caller's rewards
    GET  /v1/places/ops/queue                   operator: drafts, claims, reports, appeals
    GET  /v1/places/ops/venue?id=               operator: the full row with its checks
    POST /v1/places/sighting                    coarse sighting (never creates a venue)
    POST /v1/places/check                       {venue_id, ok, kind DEVICE|VISITOR, note}
    POST /v1/places/report                      {venue_id, kind, text}
    POST /v1/places/claim                       {venue_id, kind CLAIM|CORRECTION|REMOVAL, text}
    POST /v1/places/appeal                      {reward_id, text}
    POST /v1/places/ops/venue                   operator: submit a venue (DRAFT)
    POST /v1/places/ops/update                  operator: edit fields of a venue
    POST /v1/places/ops/consent                 operator: record the owner's consent
    POST /v1/places/ops/publish                 operator: publish (preconditions checked)
    POST /v1/places/ops/hide                    operator: hide, or send to PENDING_REVIEW
    POST /v1/places/ops/remove                  operator: remove for good
    POST /v1/places/ops/check                   operator: an OPERATOR check
    POST /v1/places/ops/claim                   operator: review a claim
    POST /v1/places/ops/report                  operator: review a report
    POST /v1/places/ops/reward                  operator: decide an appealed reward
    POST /v1/places/ops/sweep                   operator: run the sweep now
"""
import hashlib
import json
import math
import re
import sqlite3
import time
import unicodedata
from typing import Callable, Dict, List, Optional

# ---- schema -------------------------------------------------------------------------------------

SCHEMA = """
CREATE TABLE IF NOT EXISTS place_venues (
    id TEXT PRIMARY KEY, city TEXT NOT NULL, neighbourhood TEXT NOT NULL DEFAULT '', name TEXT NOT NULL,
    kind TEXT NOT NULL DEFAULT '', lat REAL NOT NULL DEFAULT 0, lon REAL NOT NULL DEFAULT 0,
    cell TEXT NOT NULL DEFAULT '', radio_hash TEXT NOT NULL DEFAULT '',
    access_rule TEXT NOT NULL DEFAULT '', signin_path TEXT NOT NULL DEFAULT '', hours_json TEXT NOT NULL DEFAULT '',
    owner_id TEXT NOT NULL DEFAULT '', consent_recorded_by TEXT NOT NULL DEFAULT '', consent_at INTEGER NOT NULL DEFAULT 0,
    consent_doc TEXT NOT NULL DEFAULT '', state TEXT NOT NULL, prok_deliverable INTEGER NOT NULL DEFAULT 0,
    dup_candidate_of TEXT NOT NULL DEFAULT '', submitted_by TEXT NOT NULL DEFAULT '',
    published_at INTEGER NOT NULL DEFAULT 0, created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS place_venues_city ON place_venues(city, state);
CREATE INDEX IF NOT EXISTS place_venues_spot ON place_venues(radio_hash, cell);
CREATE TABLE IF NOT EXISTS place_sightings (
    id TEXT PRIMARY KEY, city TEXT NOT NULL, cell TEXT NOT NULL, radio_hash TEXT NOT NULL, kind TEXT NOT NULL DEFAULT '',
    signal INTEGER NOT NULL DEFAULT 0, validated_internet INTEGER NOT NULL DEFAULT 0, reporter_id TEXT NOT NULL, at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS place_sightings_spot ON place_sightings(radio_hash, cell, at);
CREATE TABLE IF NOT EXISTS place_checks (
    id TEXT PRIMARY KEY, venue_id TEXT NOT NULL, checker_id TEXT NOT NULL, at INTEGER NOT NULL, ok INTEGER NOT NULL,
    kind TEXT NOT NULL, note TEXT NOT NULL DEFAULT '');
CREATE INDEX IF NOT EXISTS place_checks_venue ON place_checks(venue_id, at);
CREATE TABLE IF NOT EXISTS place_claims (
    id TEXT PRIMARY KEY, venue_id TEXT NOT NULL, claimant_id TEXT NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL DEFAULT '',
    state TEXT NOT NULL, at INTEGER NOT NULL, reviewed_by TEXT NOT NULL DEFAULT '', reviewed_at INTEGER NOT NULL DEFAULT 0,
    note TEXT NOT NULL DEFAULT '');
CREATE INDEX IF NOT EXISTS place_claims_state ON place_claims(state);
CREATE TABLE IF NOT EXISTS place_reports (
    id TEXT PRIMARY KEY, venue_id TEXT NOT NULL, reporter_id TEXT NOT NULL, kind TEXT NOT NULL, text TEXT NOT NULL DEFAULT '',
    state TEXT NOT NULL, at INTEGER NOT NULL, prior_state TEXT NOT NULL DEFAULT '',
    reviewed_by TEXT NOT NULL DEFAULT '', reviewed_at INTEGER NOT NULL DEFAULT 0, note TEXT NOT NULL DEFAULT '');
CREATE INDEX IF NOT EXISTS place_reports_venue ON place_reports(venue_id, state);
CREATE TABLE IF NOT EXISTS place_rewards (
    id TEXT PRIMARY KEY, scout_id TEXT NOT NULL, venue_id TEXT NOT NULL, kind TEXT NOT NULL, amount_centimes INTEGER NOT NULL,
    state TEXT NOT NULL, reason TEXT NOT NULL DEFAULT '', at INTEGER NOT NULL, decided_at INTEGER NOT NULL DEFAULT 0,
    expires_at INTEGER NOT NULL DEFAULT 0, appeal TEXT NOT NULL DEFAULT '', appealed_at INTEGER NOT NULL DEFAULT 0,
    decided_by TEXT NOT NULL DEFAULT '', earned_day TEXT NOT NULL DEFAULT '', earned_month TEXT NOT NULL DEFAULT '');
CREATE UNIQUE INDEX IF NOT EXISTS place_rewards_first_once ON place_rewards(venue_id) WHERE kind = 'FIRST';
CREATE INDEX IF NOT EXISTS place_rewards_scout ON place_rewards(scout_id, state);
CREATE INDEX IF NOT EXISTS place_rewards_month ON place_rewards(earned_month, state);
CREATE TABLE IF NOT EXISTS place_audit (
    id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, actor TEXT NOT NULL,
    action TEXT NOT NULL, target TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '', allowed INTEGER NOT NULL);
"""

# ---- constants (§10.2; every one a name, so a pilot can change it without reading code) --------

#: "Working now" needs a success in the previous 5 minutes; it expires by itself.
WORKING_NOW_MS = 5 * 60_000
#: "Recently verified" = a success in the previous 24 hours; after that "older check".
RECENT_MS = 24 * 3_600_000
#: At 7 days a venue leaves the default recommended list until checked again.
STALE_MS = 7 * 24 * 3_600_000
#: Two independent failures inside this window, with no success after them, demote.
FAILURE_WINDOW_MS = 24 * 3_600_000

#: Brazzaville is UTC+1 all year (WAT). Hours are local; the clock is UTC milliseconds.
LOCAL_UTC_OFFSET_MS = 3_600_000

FIRST_REWARD_CENTIMES = 10 * 100
REFRESH_REWARD_CENTIMES = 3 * 100
SCOUT_DAY_CAP_CENTIMES = 30 * 100
SCOUT_MONTH_CAP_CENTIMES = 100 * 100
GLOBAL_MONTH_CAP_CENTIMES = 5_000 * 100
REWARD_EXPIRY_MS = 90 * 24 * 3_600_000
APPEAL_WINDOW_MS = 30 * 24 * 3_600_000
REFRESH_WINDOW_MS = 7 * 24 * 3_600_000
#: Same name this close to an existing venue is a candidate duplicate for the operator.
DUPLICATE_NAME_RADIUS_M = 100.0
#: The coarse cell, identical to CoverageModel.zoneId on the phone: 0.005 degrees.
CELL_DEG = 0.005

# venue states
DRAFT = "DRAFT"
PENDING_REVIEW = "PENDING_REVIEW"
PUBLISHED = "PUBLISHED"
HIDDEN = "HIDDEN"
REMOVED = "REMOVED"
VENUE_STATES = (DRAFT, PENDING_REVIEW, PUBLISHED, HIDDEN, REMOVED)
#: What the public index lists. PENDING_REVIEW stays visible - as REPORTED.
VISIBLE_STATES = (PUBLISHED, PENDING_REVIEW)

# access rules
FREE_OPEN = "FREE_OPEN"
FREE_AFTER_SIGNIN = "FREE_AFTER_SIGNIN"
FREE_LIMITED = "FREE_LIMITED"
CUSTOMERS_ONLY = "CUSTOMERS_ONLY"
ACCESS_RULES = (FREE_OPEN, FREE_AFTER_SIGNIN, FREE_LIMITED, CUSTOMERS_ONLY)
#: The phone shows these words for the access rule; kept here so both sides agree.
ACCESS_TEXT = {
    FREE_OPEN: "Gratuit ici",
    FREE_AFTER_SIGNIN: "Gratuit après connexion",
    FREE_LIMITED: "Gratuit avec limite",
    CUSTOMERS_ONLY: "Clients uniquement",
}

# status
WORKING_NOW = "WORKING_NOW"
RECENTLY_VERIFIED = "RECENTLY_VERIFIED"
OLDER_CHECK = "OLDER_CHECK"
UNVERIFIED_STALE = "UNVERIFIED_STALE"
REPORTED = "REPORTED"
UNAVAILABLE = "UNAVAILABLE"
STATUSES = (WORKING_NOW, RECENTLY_VERIFIED, OLDER_CHECK, UNVERIFIED_STALE, REPORTED, UNAVAILABLE)
#: The one table of words. server/tests/fixtures/place_status.txt holds the same lines and
#: the phone's PlacesView asserts equality with that file.
STATUS_TEXT = {
    WORKING_NOW: "Fonctionne maintenant",
    RECENTLY_VERIFIED: "Vérifié récemment",
    OLDER_CHECK: "Ancien contrôle — confirmez avant de vous déplacer",
    UNVERIFIED_STALE: "Non vérifié depuis plus de 7 jours",
    REPORTED: "Signalé, à vérifier",
    UNAVAILABLE: "Indisponible",
}
#: Recommended-list order: the phone sorts by this and drops UNVERIFIED_STALE.
STATUS_RANK = {WORKING_NOW: 0, RECENTLY_VERIFIED: 1, OLDER_CHECK: 2, REPORTED: 3, UNAVAILABLE: 4, UNVERIFIED_STALE: 5}

# check kinds
DEVICE = "DEVICE"
OPERATOR = "OPERATOR"
VISITOR = "VISITOR"
CHECK_KINDS = (DEVICE, OPERATOR, VISITOR)
#: Only a real probe vouches for freshness.
PROBE_KINDS = (DEVICE, OPERATOR)

# claim kinds and states
CLAIM = "CLAIM"
CORRECTION = "CORRECTION"
REMOVAL = "REMOVAL"
CLAIM_KINDS = (CLAIM, CORRECTION, REMOVAL)
PENDING = "PENDING"
ACCEPTED = "ACCEPTED"
DECLINED = "DECLINED"
APPLIED = "APPLIED"

# report kinds and states
FAILURE = "FAILURE"
SAFETY = "SAFETY"
CLOSURE = "CLOSURE"
OTHER = "OTHER"
REPORT_KINDS = (FAILURE, SAFETY, CLOSURE, OTHER)
OPEN = "OPEN"
CONFIRMED = "CONFIRMED"
DISMISSED = "DISMISSED"

# reward kinds and states
FIRST = "FIRST"
REFRESH = "REFRESH"
EARNED = "EARNED"
EXPIRED = "EXPIRED"
REJECTED = "REJECTED"

# reason slugs (machine-readable, like ledger.py's)
NOT_OPERATOR = "not_operator"
NOT_SIGNED = "not_signed"
UNKNOWN_VENUE = "unknown_venue"
WRONG_STATE = "wrong_state"
NO_CONSENT = "no_consent"
NO_ACCESS_RULE = "no_access_rule"
NO_HOURS = "no_hours"
NO_DEVICE_CHECK = "no_device_check"
NO_SECOND_CHECK = "no_second_check"
DUPLICATE_SPOT = "duplicate_spot"
BAD_RADIO_HASH = "bad_radio_hash"
BAD_CELL = "bad_cell"
BAD_KIND = "bad_kind"
NOT_YOURS = "not_yours"
APPEAL_WINDOW_CLOSED = "appeal_window_closed"
CAP_DAY = "cap_day"
CAP_MONTH = "cap_month"
CAP_GLOBAL = "cap_global"
OWNER_SELF = "owner_self"
NOT_STALE = "not_stale"
REFRESH_WINDOW = "refresh_window"
NOT_A_PROBE = "not_a_probe"

POST_PATHS = ("/v1/places/sighting", "/v1/places/check", "/v1/places/report", "/v1/places/claim", "/v1/places/appeal",
              "/v1/places/ops/venue", "/v1/places/ops/update", "/v1/places/ops/consent", "/v1/places/ops/publish",
              "/v1/places/ops/hide", "/v1/places/ops/remove", "/v1/places/ops/check", "/v1/places/ops/claim",
              "/v1/places/ops/report", "/v1/places/ops/reward", "/v1/places/ops/sweep")

RADIO_HASH_RE = re.compile(r"[0-9a-f]{16,64}")
CELL_RE = re.compile(r"z-?[0-9]{1,7}:-?[0-9]{1,7}")
DAYS = ("mon", "tue", "wed", "thu", "fri", "sat", "sun")


class PlacesError(Exception):
    def __init__(self, message: str, reason: str = "", code: int = 400):
        super().__init__(message)
        self.reason = reason
        self.code = code


# ---- pure helpers ---------------------------------------------------------------------------

def _id(*parts) -> str:
    return hashlib.sha256("|".join(str(p) for p in parts).encode("utf-8")).hexdigest()[:24]


def cell_of(lat: float, lon: float) -> str:
    """The coarse cell string, byte-identical to CoverageModel.zoneId on the phone."""
    return "z%d:%d" % (math.floor(lat / CELL_DEG), math.floor(lon / CELL_DEG))


def distance_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """Haversine, metres. Straight line - never called a walking distance anywhere."""
    r = 6_371_000.0
    p1, p2 = math.radians(lat1), math.radians(lat2)
    dp, dl = math.radians(lat2 - lat1), math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(min(1.0, math.sqrt(a)))


def normalise_name(name: str) -> str:
    """Lower-case, accents stripped, one space between words: "Café  du PORT" == "cafe du port"."""
    s = unicodedata.normalize("NFKD", name or "")
    s = "".join(ch for ch in s if not unicodedata.combining(ch))
    return " ".join(s.lower().split())


def _clean_text(s, limit: int = 120) -> str:
    """Names and free text: no braces or brackets, so the phone's flat reader stays honest."""
    return "".join(ch for ch in str(s or "") if ch not in "{}[]\"\\").strip()[:limit]


def parse_hours(raw) -> Dict[str, list]:
    """{"mon": ["08:00", "20:00"], ...}: a day missing or [] is closed. A JSON string or a
    dict; anything unreadable is {} (which fails the publication rule, on purpose)."""
    if isinstance(raw, str):
        if not raw.strip():
            return {}
        try:
            raw = json.loads(raw)
        except ValueError:
            return {}
    if not isinstance(raw, dict):
        return {}
    out = {}
    for day in DAYS:
        v = raw.get(day)
        if isinstance(v, (list, tuple)) and len(v) == 2 and all(isinstance(x, str) and re.fullmatch(r"[0-2][0-9]:[0-5][0-9]", x) for x in v):
            out[day] = [v[0], v[1]]
        else:
            out[day] = []
    return out if any(out.values()) else {}


def hours_line(hours: Dict[str, list]) -> str:
    """The flat form the phone parses: "mon=08:00-20:00,tue=,..." (a day with no range is closed)."""
    return ",".join(d + "=" + ("-".join(hours.get(d) or []) if hours.get(d) else "") for d in DAYS)


def open_at(hours: Dict[str, list], now: int) -> Optional[bool]:
    """Open at [now] in local time, or None when hours are unknown. A range that closes
    before it opens ("20:00"-"02:00") runs past midnight."""
    if not hours:
        return None
    local = (now + LOCAL_UTC_OFFSET_MS) // 60_000          # whole local minutes since epoch
    day_idx = int(((local // 1440) + 3) % 7)                 # 1970-01-01 was a Thursday
    minute = int(local % 1440)
    def _m(s):
        return int(s[:2]) * 60 + int(s[3:])
    rng = hours.get(DAYS[day_idx]) or []
    if rng:
        o, c = _m(rng[0]), _m(rng[1])
        if (o <= minute < c) if o < c else (minute >= o or minute < c):
            return True
    prev = hours.get(DAYS[(day_idx - 1) % 7]) or []           # yesterday's range past midnight
    if prev:
        o, c = _m(prev[0]), _m(prev[1])
        if c < o and minute < c:
            return True
    return False


def status(venue_row, checks, now: int, open_now, open_reports: int = 0) -> dict:
    """The status rule. Pure: a venue row (dict or sqlite3.Row with `state`), its checks
    (each with checker_id, at, ok, kind), the clock, whether it is open now (True / False /
    None = unknown) and how many community reports are open.

    Returns {"status", "status_age_ms", "last_ok_at", "confidence"}. `status_age_ms` is
    the age of the last successful probe (-1 when there was none); `confidence` is 0..100
    from how many different checkers succeeded in the last 7 days and how fresh the
    newest success is - a number for the card, not a promise.
    """
    state = venue_row["state"]
    rows = [dict(c) for c in checks]
    rows.sort(key=lambda c: int(c["at"]))
    rows = [c for c in rows if int(c["at"]) <= now]
    probe_ok = [c for c in rows if int(c["ok"]) and c["kind"] in PROBE_KINDS]
    last_ok_at = int(probe_ok[-1]["at"]) if probe_ok else 0
    age = (now - last_ok_at) if last_ok_at else -1
    # confidence first: it is reported whatever the status says
    week = [c for c in rows if int(c["ok"]) and now - int(c["at"]) < STALE_MS]
    checkers = len(set(c["checker_id"] for c in week))
    conf = min(3, checkers) * 25
    if last_ok_at:
        conf += 25 if age < WORKING_NOW_MS else 15 if age < RECENT_MS else 5 if age < STALE_MS else 0
    conf = max(0, min(100, conf))
    out = {"status_age_ms": age, "last_ok_at": last_ok_at, "confidence": conf}

    if state in (HIDDEN, REMOVED):
        return dict(out, status=UNAVAILABLE)
    # failures after the last success, inside the window
    after = [c for c in rows if not int(c["ok"]) and int(c["at"]) >= last_ok_at and now - int(c["at"]) < FAILURE_WINDOW_MS]
    if any(c["kind"] == OPERATOR for c in after):
        return dict(out, status=UNAVAILABLE)                 # a trusted operator confirmed the closure
    if len(set(c["checker_id"] for c in after)) >= 2:
        return dict(out, status=UNAVAILABLE)                 # two independent recent failures
    if state == PENDING_REVIEW or open_reports > 0:
        return dict(out, status=REPORTED)
    if not last_ok_at or age >= STALE_MS:
        return dict(out, status=UNVERIFIED_STALE)
    if age < WORKING_NOW_MS and open_now is True and not after:
        return dict(out, status=WORKING_NOW)
    if age < RECENT_MS:
        return dict(out, status=RECENTLY_VERIFIED)
    return dict(out, status=OLDER_CHECK)


def _day(ms: int) -> str:
    return time.strftime("%Y-%m-%d", time.gmtime(ms // 1000))


def _month(ms: int) -> str:
    return time.strftime("%Y-%m", time.gmtime(ms // 1000))


def _q(query, key: str, default: str = "") -> str:
    v = query.get(key, default) if isinstance(query, dict) else default
    if isinstance(v, (list, tuple)):
        v = v[0] if v else default
    return str(v) if v is not None else default


class Places:
    def __init__(self, db: sqlite3.Connection, is_operator: Callable[[str], bool], ledger=None):
        self.db = db
        self.db.row_factory = sqlite3.Row
        self.db.executescript(SCHEMA)
        self.db.commit()
        self.is_operator = is_operator or (lambda who: False)
        #: a callable (now, kind, debit, credit, amount, ref, memo) or a Ledger with _post
        if ledger is None:
            self.ledger_post = None
        elif callable(ledger) and not hasattr(ledger, "_post"):
            self.ledger_post = ledger
        else:
            self.ledger_post = lambda now, kind, debit, credit, amount, ref, memo: ledger._post(
                now, kind, debit, credit, amount, ref=ref, memo=memo, actor="places")

    # ---- roles and audit -----------------------------------------------------------------------

    def _audit(self, now: int, actor: str, action: str, target: str, detail: str = "", allowed: bool = True):
        self.db.execute("INSERT INTO place_audit(ts, actor, action, target, detail, allowed) VALUES(?,?,?,?,?,?)",
                        (now, actor, action, target, detail, 1 if allowed else 0))
        if not self.db.in_transaction:
            self.db.commit()

    def _refuse(self, now: int, actor: str, action: str, target: str, detail: str, error: PlacesError):
        self._audit(now, actor, action, target, "refused: " + detail, allowed=False)
        self.db.commit()
        raise error

    def _require_operator(self, who: str, action: str, target: str, now: int):
        if not who or not self.is_operator(who):
            self._refuse(now, who, action, target, "not an operator",
                         PlacesError("only an operator may do this", NOT_OPERATOR, 403))

    def _require_signed(self, who: str, action: str, now: int):
        if not who:
            self._refuse(now, who, action, "-", "unsigned", PlacesError("sign in first", NOT_SIGNED, 401))

    # ---- venues ---------------------------------------------------------------------------------

    def _venue(self, venue_id: str) -> sqlite3.Row:
        row = self.db.execute("SELECT * FROM place_venues WHERE id=?", (venue_id,)).fetchone()
        if row is None:
            raise PlacesError("unknown venue", UNKNOWN_VENUE, 404)
        return row

    def _checks(self, venue_id: str) -> List[sqlite3.Row]:
        return self.db.execute("SELECT * FROM place_checks WHERE venue_id=? ORDER BY at", (venue_id,)).fetchall()

    def _open_reports(self, venue_id: str) -> int:
        return int(self.db.execute("SELECT COUNT(*) AS n FROM place_reports WHERE venue_id=? AND state=?", (venue_id, OPEN)).fetchone()["n"])

    def status_of(self, venue_row, now: int) -> dict:
        hours = parse_hours(venue_row["hours_json"])
        return status(venue_row, self._checks(venue_row["id"]), now, open_at(hours, now), self._open_reports(venue_row["id"]))

    def venue_submit(self, operator: str, city: str, name: str, kind: str, lat: float, lon: float, now: int,
                     neighbourhood: str = "", radio_hash: str = "", cell: str = "", access_rule: str = "",
                     signin_path: str = "", hours=None, prok_deliverable: bool = False) -> dict:
        """An operator records a venue as DRAFT. Dedup: the same radio_hash in the same cell is
        the same spot and is refused (409, with the existing id); the same name within 100 m
        is a candidate duplicate, flagged on the row and left for the operator."""
        self._require_operator(operator, "places.venue_submit", name[:40], now)
        name = _clean_text(name)
        if not name or not city:
            raise PlacesError("a venue needs a city and a name")
        try:
            lat, lon = float(lat), float(lon)
        except (TypeError, ValueError):
            raise PlacesError("the entrance needs coordinates")
        if not (-90 <= lat <= 90 and -180 <= lon <= 180):
            raise PlacesError("the entrance needs coordinates")
        cell = cell or cell_of(lat, lon)
        if radio_hash and not RADIO_HASH_RE.fullmatch(radio_hash):
            raise PlacesError("radio_hash must be a hex hash, never a network name", BAD_RADIO_HASH)
        if access_rule and access_rule not in ACCESS_RULES:
            raise PlacesError("unknown access rule", BAD_KIND)
        if radio_hash:
            dup = self.db.execute("SELECT id FROM place_venues WHERE radio_hash=? AND cell=? AND state!=?", (radio_hash, cell, REMOVED)).fetchone()
            if dup is not None:
                raise PlacesError("that spot is already recorded as venue " + dup["id"], DUPLICATE_SPOT, 409)
        near = ""
        wanted = normalise_name(name)
        for r in self.db.execute("SELECT id, name, lat, lon FROM place_venues WHERE city=? AND state!=?", (city, REMOVED)).fetchall():
            if normalise_name(r["name"]) == wanted and distance_m(lat, lon, float(r["lat"]), float(r["lon"])) <= DUPLICATE_NAME_RADIUS_M:
                near = r["id"]
                break
        vid = _id("venue", city, name, lat, lon, now)
        hrs = parse_hours(hours) if hours is not None else {}
        with self.db:
            self.db.execute("INSERT INTO place_venues(id, city, neighbourhood, name, kind, lat, lon, cell, radio_hash, access_rule, signin_path,"
                            " hours_json, state, prok_deliverable, dup_candidate_of, submitted_by, created_at, updated_at)"
                            " VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                            (vid, city, _clean_text(neighbourhood, 60), name, _clean_text(kind, 40), lat, lon, cell, radio_hash, access_rule,
                             _clean_text(signin_path, 200), json.dumps(hrs) if hrs else "", DRAFT, 1 if prok_deliverable else 0, near, operator, now, now))
            self._audit(now, operator, "places.venue_submit", vid, "dup_candidate_of=" + near if near else "")
        # the earliest validated finder is remembered now, so the scout sees "en attente"
        self._pending_first(vid, now)
        return {"ok": True, "venue_id": vid, "state": DRAFT, "dup_candidate_of": near}

    def venue_update(self, operator: str, venue_id: str, now: int, **fields) -> dict:
        """Edit the fields an operator may edit. Coordinates, hours and the access rule are
        exactly what consent covers, so a correction after publication goes back through
        PENDING_REVIEW (visible as REPORTED) until the operator re-publishes."""
        self._require_operator(operator, "places.venue_update", venue_id, now)
        row = self._venue(venue_id)
        if row["state"] == REMOVED:
            raise PlacesError("a removed venue is not edited", WRONG_STATE, 409)
        sets, vals = [], []
        for k, v in fields.items():
            if v is None:
                continue
            if k in ("name", "neighbourhood", "kind", "signin_path"):
                sets.append(k + "=?"); vals.append(_clean_text(v, 200 if k == "signin_path" else 120))
            elif k in ("lat", "lon"):
                sets.append(k + "=?"); vals.append(float(v))
            elif k == "access_rule":
                if v not in ACCESS_RULES:
                    raise PlacesError("unknown access rule", BAD_KIND)
                sets.append("access_rule=?"); vals.append(v)
            elif k == "hours":
                h = parse_hours(v)
                sets.append("hours_json=?"); vals.append(json.dumps(h) if h else "")
            elif k == "prok_deliverable":
                sets.append("prok_deliverable=?"); vals.append(1 if v else 0)
            elif k == "radio_hash":
                if v and not RADIO_HASH_RE.fullmatch(str(v)):
                    raise PlacesError("radio_hash must be a hex hash", BAD_RADIO_HASH)
                sets.append("radio_hash=?"); vals.append(str(v))
            elif k == "owner_id":
                sets.append("owner_id=?"); vals.append(str(v))
        if not sets:
            return {"ok": True, "venue_id": venue_id, "state": row["state"]}
        material = any(s.split("=")[0] in ("lat", "lon", "access_rule", "hours_json") for s in sets)
        with self.db:
            if "lat" in fields or "lon" in fields:
                lat = float(fields.get("lat", row["lat"])); lon = float(fields.get("lon", row["lon"]))
                sets.append("cell=?"); vals.append(cell_of(lat, lon))
            if material and row["state"] == PUBLISHED:
                sets.append("state=?"); vals.append(PENDING_REVIEW)
            sets.append("updated_at=?"); vals.append(now)
            self.db.execute("UPDATE place_venues SET " + ", ".join(sets) + " WHERE id=?", vals + [venue_id])
            self._audit(now, operator, "places.venue_update", venue_id, ",".join(sorted(fields.keys())))
        return {"ok": True, "venue_id": venue_id, "state": self._venue(venue_id)["state"]}

    def record_consent(self, operator: str, venue_id: str, consent_doc: str, now: int, owner_id: str = "") -> dict:
        """The owner's consent, recorded by an operator: who recorded it, when, and which
        document. Without this a venue never publishes and its entrance never leaves here."""
        self._require_operator(operator, "places.record_consent", venue_id, now)
        row = self._venue(venue_id)
        if not (consent_doc or "").strip():
            raise PlacesError("the consent document reference is required", NO_CONSENT)
        with self.db:
            self.db.execute("UPDATE place_venues SET consent_recorded_by=?, consent_at=?, consent_doc=?, owner_id=COALESCE(NULLIF(?, ''), owner_id), updated_at=? WHERE id=?",
                            (operator, now, _clean_text(consent_doc, 200), owner_id or "", now, venue_id))
            self._audit(now, operator, "places.record_consent", venue_id, _clean_text(consent_doc, 60))
        return {"ok": True, "venue_id": venue_id, "state": row["state"]}

    def publication_blockers(self, venue_id: str) -> List[str]:
        """Every reason this venue may not be published, in a fixed order; [] means it may."""
        row = self._venue(venue_id)
        out = []
        if not (row["consent_recorded_by"] and self.is_operator(row["consent_recorded_by"]) and int(row["consent_at"]) > 0 and row["consent_doc"]):
            out.append(NO_CONSENT)
        if row["access_rule"] not in ACCESS_RULES:
            out.append(NO_ACCESS_RULE)
        if not parse_hours(row["hours_json"]):
            out.append(NO_HOURS)
        ok = [c for c in self._checks(venue_id) if int(c["ok"])]
        device = [c for c in ok if c["kind"] == DEVICE]
        if not device:
            out.append(NO_DEVICE_CHECK)
        else:
            first = device[0]["checker_id"]
            if not any(c["kind"] in PROBE_KINDS and c["checker_id"] != first for c in ok):
                out.append(NO_SECOND_CHECK)
        return out

    def venue_publish(self, operator: str, venue_id: str, now: int) -> dict:
        """PUBLISHED only when every precondition holds (see publication_blockers). The first
        finder's reward is decided here, because this is the moment the second independent
        check is known to exist."""
        self._require_operator(operator, "places.venue_publish", venue_id, now)
        row = self._venue(venue_id)
        if row["state"] == REMOVED:
            raise PlacesError("a removed venue is not published again", WRONG_STATE, 409)
        blockers = self.publication_blockers(venue_id)
        if blockers:
            self._refuse(now, operator, "places.venue_publish", venue_id, ",".join(blockers),
                         PlacesError("not publishable: " + ", ".join(blockers), blockers[0], 409))
        with self.db:
            self.db.execute("UPDATE place_venues SET state=?, updated_at=?, published_at=CASE WHEN published_at=0 THEN ? ELSE published_at END WHERE id=?",
                            (PUBLISHED, now, now, venue_id))
            self._audit(now, operator, "places.venue_publish", venue_id)
        reward = self._decide_first(venue_id, now)
        return {"ok": True, "venue_id": venue_id, "state": PUBLISHED, "first_reward": reward}

    def venue_hide(self, operator: str, venue_id: str, now: int, reason: str = "", review: bool = False) -> dict:
        """HIDDEN (out of the index, UNAVAILABLE) or, with review=True, PENDING_REVIEW (still
        listed, shown as REPORTED) - the operator's "I want this looked at again"."""
        self._require_operator(operator, "places.venue_hide", venue_id, now)
        row = self._venue(venue_id)
        if row["state"] == REMOVED:
            raise PlacesError("already removed", WRONG_STATE, 409)
        to = PENDING_REVIEW if review else HIDDEN
        with self.db:
            self.db.execute("UPDATE place_venues SET state=?, updated_at=? WHERE id=?", (to, now, venue_id))
            self._audit(now, operator, "places.venue_hide", venue_id, reason)
        return {"ok": True, "venue_id": venue_id, "state": to}

    def venue_remove(self, operator: str, venue_id: str, now: int, reason: str = "") -> dict:
        self._require_operator(operator, "places.venue_remove", venue_id, now)
        self._venue(venue_id)
        with self.db:
            self.db.execute("UPDATE place_venues SET state=?, updated_at=? WHERE id=?", (REMOVED, now, venue_id))
            self._audit(now, operator, "places.venue_remove", venue_id, reason)
        return {"ok": True, "venue_id": venue_id, "state": REMOVED}

    # ---- claims (owner) -------------------------------------------------------------------------

    def claim(self, owner: str, venue_id: str, kind: str, text: str, now: int) -> dict:
        """CLAIM ("this is mine"), CORRECTION or REMOVAL. A REMOVAL by the recorded owner hides
        the venue immediately; everything else waits for an operator."""
        self._require_signed(owner, "places.claim", now)
        if kind not in CLAIM_KINDS:
            raise PlacesError("unknown claim kind", BAD_KIND)
        row = self._venue(venue_id)
        if row["state"] == REMOVED:
            raise PlacesError("that venue is gone", WRONG_STATE, 409)
        cid = _id("claim", venue_id, owner, kind, now)
        immediate = kind == REMOVAL and row["owner_id"] and row["owner_id"] == owner
        with self.db:
            self.db.execute("INSERT INTO place_claims(id, venue_id, claimant_id, kind, text, state, at) VALUES(?,?,?,?,?,?,?)",
                            (cid, venue_id, owner, kind, _clean_text(text, 500), APPLIED if immediate else PENDING, now))
            if immediate:
                self.db.execute("UPDATE place_venues SET state=?, updated_at=? WHERE id=?", (HIDDEN, now, venue_id))
            self._audit(now, owner, "places.claim." + kind.lower(), venue_id, "hidden now" if immediate else "pending")
        return {"ok": True, "claim_id": cid, "state": APPLIED if immediate else PENDING,
                "venue_state": HIDDEN if immediate else row["state"]}

    def review_claim(self, operator: str, claim_id: str, accept: bool, now: int, note: str = "") -> dict:
        """Accepting a CLAIM names the claimant as owner; a REMOVAL hides; a CORRECTION sends
        the venue to PENDING_REVIEW so the operator edits it with the text in hand."""
        self._require_operator(operator, "places.review_claim", claim_id, now)
        c = self.db.execute("SELECT * FROM place_claims WHERE id=?", (claim_id,)).fetchone()
        if c is None:
            raise PlacesError("unknown claim", code=404)
        if c["state"] != PENDING:
            raise PlacesError("claim is " + c["state"], WRONG_STATE, 409)
        with self.db:
            self.db.execute("UPDATE place_claims SET state=?, reviewed_by=?, reviewed_at=?, note=? WHERE id=?",
                            (ACCEPTED if accept else DECLINED, operator, now, _clean_text(note, 300), claim_id))
            if accept:
                if c["kind"] == CLAIM:
                    self.db.execute("UPDATE place_venues SET owner_id=?, updated_at=? WHERE id=?", (c["claimant_id"], now, c["venue_id"]))
                elif c["kind"] == REMOVAL:
                    self.db.execute("UPDATE place_venues SET state=?, updated_at=? WHERE id=?", (HIDDEN, now, c["venue_id"]))
                elif c["kind"] == CORRECTION:
                    self.db.execute("UPDATE place_venues SET state=?, updated_at=? WHERE id=? AND state=?", (PENDING_REVIEW, now, c["venue_id"], PUBLISHED))
            self._audit(now, operator, "places.review_claim", claim_id, ("accepted " if accept else "declined ") + c["kind"])
        return {"ok": True, "claim_id": claim_id, "state": ACCEPTED if accept else DECLINED, "venue_state": self._venue(c["venue_id"])["state"]}

    # ---- reports (anyone) -----------------------------------------------------------------------

    def report(self, reporter: str, venue_id: str, kind: str, text: str, now: int) -> dict:
        """SAFETY hides the venue at once pending review. FAILURE / CLOSURE / OTHER keep it
        visible as REPORTED until an operator confirms or dismisses."""
        self._require_signed(reporter, "places.report", now)
        if kind not in REPORT_KINDS:
            raise PlacesError("unknown report kind", BAD_KIND)
        row = self._venue(venue_id)
        if row["state"] == REMOVED:
            raise PlacesError("that venue is gone", WRONG_STATE, 409)
        rid = _id("report", venue_id, reporter, kind, now)
        with self.db:
            self.db.execute("INSERT INTO place_reports(id, venue_id, reporter_id, kind, text, state, at, prior_state) VALUES(?,?,?,?,?,?,?,?)",
                            (rid, venue_id, reporter, kind, _clean_text(text, 500), OPEN, now, row["state"]))
            if kind == SAFETY and row["state"] != HIDDEN:
                self.db.execute("UPDATE place_venues SET state=?, updated_at=? WHERE id=?", (HIDDEN, now, venue_id))
            self._audit(now, reporter, "places.report." + kind.lower(), venue_id)
        return {"ok": True, "report_id": rid, "state": OPEN, "venue_state": self._venue(venue_id)["state"],
                "offer": self.offer_for(reporter, now)}

    def review_report(self, operator: str, report_id: str, confirm: bool, now: int, note: str = "") -> dict:
        """Confirming SAFETY removes the venue; confirming FAILURE or CLOSURE records an
        OPERATOR failure check (which the status rule reads as a confirmed closure), and a
        confirmed CLOSURE hides. Dismissing a SAFETY report restores the state it had."""
        self._require_operator(operator, "places.review_report", report_id, now)
        r = self.db.execute("SELECT * FROM place_reports WHERE id=?", (report_id,)).fetchone()
        if r is None:
            raise PlacesError("unknown report", code=404)
        if r["state"] != OPEN:
            raise PlacesError("report is " + r["state"], WRONG_STATE, 409)
        vid = r["venue_id"]
        with self.db:
            self.db.execute("UPDATE place_reports SET state=?, reviewed_by=?, reviewed_at=?, note=? WHERE id=?",
                            (CONFIRMED if confirm else DISMISSED, operator, now, _clean_text(note, 300), report_id))
            if confirm:
                if r["kind"] == SAFETY:
                    self.db.execute("UPDATE place_venues SET state=?, updated_at=? WHERE id=?", (REMOVED, now, vid))
                elif r["kind"] in (FAILURE, CLOSURE):
                    self.db.execute("INSERT INTO place_checks(id, venue_id, checker_id, at, ok, kind, note) VALUES(?,?,?,?,?,?,?)",
                                    (_id("check", vid, operator, now, "report"), vid, operator, now, 0, OPERATOR, "confirmed report " + report_id))
                    if r["kind"] == CLOSURE:
                        self.db.execute("UPDATE place_venues SET state=?, updated_at=? WHERE id=?", (HIDDEN, now, vid))
            elif r["kind"] == SAFETY and r["prior_state"] in VISIBLE_STATES:
                self.db.execute("UPDATE place_venues SET state=?, updated_at=? WHERE id=? AND state=?", (r["prior_state"], now, vid, HIDDEN))
            self._audit(now, operator, "places.review_report", report_id, ("confirmed " if confirm else "dismissed ") + r["kind"])
        return {"ok": True, "report_id": report_id, "state": CONFIRMED if confirm else DISMISSED, "venue_state": self._venue(vid)["state"]}

    # ---- checks ---------------------------------------------------------------------------------

    def check(self, checker: str, venue_id: str, ok: bool, kind: str, now: int, note: str = "") -> dict:
        """One check. DEVICE = this phone actually connected and Android validated Internet;
        VISITOR = a person's "worked / didn't work"; OPERATOR = staff, and only staff. The
        refresh reward is decided here, from the status the venue had BEFORE this check."""
        self._require_signed(checker, "places.check", now)
        if kind not in CHECK_KINDS:
            raise PlacesError("unknown check kind", BAD_KIND)
        if kind == OPERATOR:
            self._require_operator(checker, "places.check", venue_id, now)
        row = self._venue(venue_id)
        if row["state"] == REMOVED:
            raise PlacesError("that venue is gone", WRONG_STATE, 409)
        before = self.status_of(row, now)["status"] if row["state"] == PUBLISHED else ""
        cid = _id("check", venue_id, checker, now, kind, 1 if ok else 0)
        with self.db:
            self.db.execute("INSERT INTO place_checks(id, venue_id, checker_id, at, ok, kind, note) VALUES(?,?,?,?,?,?,?)",
                            (cid, venue_id, checker, now, 1 if ok else 0, kind, _clean_text(note, 200)))
            self.db.execute("UPDATE place_venues SET updated_at=? WHERE id=?", (now, venue_id))
            self._audit(now, checker, "places.check", venue_id, kind + (" ok" if ok else " FAIL"))
        reward = None
        if ok and kind == DEVICE and row["state"] == PUBLISHED and before in (UNVERIFIED_STALE, UNAVAILABLE):
            reward = self._decide_refresh(checker, venue_id, now, before)
        after = self.status_of(self._venue(venue_id), now)
        return {"ok": True, "check_id": cid, "status": after["status"], "status_text": STATUS_TEXT[after["status"]],
                "refresh_reward": reward, "offer": self.offer_for(checker, now)}

    # ---- sightings ------------------------------------------------------------------------------

    def sighting(self, reporter: str, city: str, cell: str, radio_hash: str, kind: str, signal: int,
                 validated_internet: bool, now: int) -> dict:
        """A coarse observation. Stored, matched to a venue by radio_hash+cell when one exists,
        and NEVER turned into a venue. The answer carries the scout offer so the app can say
        "pas d'offre scout payée" when the budget is out."""
        self._require_signed(reporter, "places.sighting", now)
        if not radio_hash or not RADIO_HASH_RE.fullmatch(str(radio_hash)):
            raise PlacesError("a sighting carries a hex radio hash, never a network name", BAD_RADIO_HASH)
        if not cell or not CELL_RE.fullmatch(str(cell)):
            raise PlacesError("a sighting needs a coarse cell", BAD_CELL)
        sid = _id("sighting", reporter, radio_hash, cell, now)
        with self.db:
            self.db.execute("INSERT INTO place_sightings(id, city, cell, radio_hash, kind, signal, validated_internet, reporter_id, at) VALUES(?,?,?,?,?,?,?,?,?)",
                            (sid, city or "", cell, radio_hash, _clean_text(kind, 20), int(signal or 0), 1 if validated_internet else 0, reporter, now))
        venue = self.db.execute("SELECT id, state FROM place_venues WHERE radio_hash=? AND cell=? AND state!=?", (radio_hash, cell, REMOVED)).fetchone()
        if venue is not None and venue["state"] not in (PUBLISHED,):
            self._pending_first(venue["id"], now)
        return {"ok": True, "sighting_id": sid, "matched_venue_id": venue["id"] if venue else "",
                "offer": self.offer_for(reporter, now)}

    # ---- rewards --------------------------------------------------------------------------------

    def _earliest_finder(self, venue_row, before: int) -> Optional[sqlite3.Row]:
        if not venue_row["radio_hash"]:
            return None
        return self.db.execute("SELECT * FROM place_sightings WHERE radio_hash=? AND cell=? AND validated_internet=1 AND at<=?"
                               " ORDER BY at ASC, id ASC LIMIT 1", (venue_row["radio_hash"], venue_row["cell"], before)).fetchone()

    def _pending_first(self, venue_id: str, now: int):
        """A PENDING row for the earliest validated finder, so the scout's screen can say
        "en attente" before publication. Nothing is credited here."""
        row = self._venue(venue_id)
        if self.db.execute("SELECT 1 FROM place_rewards WHERE venue_id=? AND kind=?", (venue_id, FIRST)).fetchone():
            return
        # only a sighting BEFORE the first publication can have found the place
        s = self._earliest_finder(row, int(row["published_at"]) or now)
        if s is None or (row["owner_id"] and s["reporter_id"] == row["owner_id"]):
            return
        with self.db:
            self.db.execute("INSERT OR IGNORE INTO place_rewards(id, scout_id, venue_id, kind, amount_centimes, state, at) VALUES(?,?,?,?,?,?,?)",
                            (_id("reward", FIRST, venue_id), s["reporter_id"], venue_id, FIRST, FIRST_REWARD_CENTIMES, PENDING, int(s["at"])))

    def _issued(self, scout: str, now: int):
        """(scout today, scout this month, everybody this month) in centimes, EARNED or since expired."""
        d, m = _day(now), _month(now)
        q = lambda sql, *a: int(self.db.execute(sql, a).fetchone()["s"])
        return (q("SELECT COALESCE(SUM(amount_centimes),0) AS s FROM place_rewards WHERE scout_id=? AND earned_day=? AND state IN (?,?)", scout, d, EARNED, EXPIRED),
                q("SELECT COALESCE(SUM(amount_centimes),0) AS s FROM place_rewards WHERE scout_id=? AND earned_month=? AND state IN (?,?)", scout, m, EARNED, EXPIRED),
                q("SELECT COALESCE(SUM(amount_centimes),0) AS s FROM place_rewards WHERE earned_month=? AND state IN (?,?)", m, EARNED, EXPIRED))

    def _cap_hit(self, scout: str, amount: int, now: int) -> str:
        day, month, everybody = self._issued(scout, now)
        if everybody + amount > GLOBAL_MONTH_CAP_CENTIMES:
            return CAP_GLOBAL
        if day + amount > SCOUT_DAY_CAP_CENTIMES:
            return CAP_DAY
        if month + amount > SCOUT_MONTH_CAP_CENTIMES:
            return CAP_MONTH
        return ""

    def offer_available(self, now: int) -> bool:
        """Is there any paid scout offer left this month, for anybody? False when the global
        budget is spent: the app must say "pas d'offre scout payée" before a contribution."""
        return self._issued("", now)[2] + REFRESH_REWARD_CENTIMES <= GLOBAL_MONTH_CAP_CENTIMES

    def offer_for(self, scout: str, now: int) -> dict:
        """The offer as this scout sees it before contributing. `available` is false when any
        cap already stops the smaller reward; `text` is the sentence to show."""
        day, month, everybody = self._issued(scout, now) if scout else (0, 0, self._issued("", now)[2])
        global_ok = everybody + REFRESH_REWARD_CENTIMES <= GLOBAL_MONTH_CAP_CENTIMES
        day_left = max(0, SCOUT_DAY_CAP_CENTIMES - day)
        month_left = max(0, SCOUT_MONTH_CAP_CENTIMES - month)
        available = global_ok and day_left >= REFRESH_REWARD_CENTIMES and month_left >= REFRESH_REWARD_CENTIMES
        reason = "" if available else (CAP_GLOBAL if not global_ok else CAP_DAY if day_left < REFRESH_REWARD_CENTIMES else CAP_MONTH)
        return {"available": available, "reason": reason,
                "text": "Offre scout : 10 CFA de crédit promotionnel par lieu confirmé, 3 CFA par remise à jour" if available else "pas d'offre scout payée",
                "first_centimes": FIRST_REWARD_CENTIMES, "refresh_centimes": REFRESH_REWARD_CENTIMES,
                "day_left_centimes": day_left, "month_left_centimes": month_left,
                "global_left_centimes": max(0, GLOBAL_MONTH_CAP_CENTIMES - everybody)}

    def _earn(self, reward_id: str, now: int, decided_by: str = ""):
        r = self.db.execute("SELECT * FROM place_rewards WHERE id=?", (reward_id,)).fetchone()
        self.db.execute("UPDATE place_rewards SET state=?, decided_at=?, expires_at=?, reason='', decided_by=?, earned_day=?, earned_month=? WHERE id=?",
                        (EARNED, now, now + REWARD_EXPIRY_MS, decided_by, _day(now), _month(now), reward_id))
        if self.ledger_post is not None:
            self.ledger_post(now, "PROMO_" + r["kind"], "fund:promo", "promo:" + r["scout_id"], int(r["amount_centimes"]), reward_id,
                             "scout " + r["kind"].lower() + " " + r["venue_id"])

    def _decide_first(self, venue_id: str, now: int) -> Optional[dict]:
        """At publication: the earliest validated finder earns, once per venue ever, unless a
        cap says no (then REJECTED with that reason, appealable)."""
        row = self._venue(venue_id)
        self._pending_first(venue_id, now)
        r = self.db.execute("SELECT * FROM place_rewards WHERE venue_id=? AND kind=?", (venue_id, FIRST)).fetchone()
        if r is None:
            return None
        if r["state"] != PENDING:
            return self._reward_view(r)
        if row["owner_id"] and r["scout_id"] == row["owner_id"]:
            with self.db:
                self.db.execute("UPDATE place_rewards SET state=?, reason=?, decided_at=? WHERE id=?", (REJECTED, OWNER_SELF, now, r["id"]))
            return self._reward_view(self.db.execute("SELECT * FROM place_rewards WHERE id=?", (r["id"],)).fetchone())
        cap = self._cap_hit(r["scout_id"], int(r["amount_centimes"]), now)
        with self.db:
            if cap:
                self.db.execute("UPDATE place_rewards SET state=?, reason=?, decided_at=? WHERE id=?", (REJECTED, cap, now, r["id"]))
            else:
                self._earn(r["id"], now)
            self._audit(now, "places", "places.reward.first", r["id"], cap or "earned")
        return self._reward_view(self.db.execute("SELECT * FROM place_rewards WHERE id=?", (r["id"],)).fetchone())

    def _decide_refresh(self, checker: str, venue_id: str, now: int, before: str) -> Optional[dict]:
        """The first successful DEVICE check after 7 days without one, or after a confirmed
        failure, earns the refresh - once per venue per 7 days, caps permitting."""
        row = self._venue(venue_id)
        if row["owner_id"] and checker == row["owner_id"]:
            return None
        last = self.db.execute("SELECT at FROM place_rewards WHERE venue_id=? AND kind=? AND state IN (?,?,?) ORDER BY at DESC LIMIT 1",
                               (venue_id, REFRESH, EARNED, EXPIRED, PENDING)).fetchone()
        if last is not None and now - int(last["at"]) < REFRESH_WINDOW_MS:
            return None
        rid = _id("reward", REFRESH, venue_id, checker, now)
        cap = self._cap_hit(checker, REFRESH_REWARD_CENTIMES, now)
        with self.db:
            self.db.execute("INSERT INTO place_rewards(id, scout_id, venue_id, kind, amount_centimes, state, reason, at, decided_at) VALUES(?,?,?,?,?,?,?,?,?)",
                            (rid, checker, venue_id, REFRESH, REFRESH_REWARD_CENTIMES, REJECTED if cap else PENDING, cap, now, now if cap else 0))
            if not cap:
                self._earn(rid, now)
            self._audit(now, checker, "places.reward.refresh", rid, (cap or "earned") + " after " + before)
        return self._reward_view(self.db.execute("SELECT * FROM place_rewards WHERE id=?", (rid,)).fetchone())

    @staticmethod
    def _reward_view(r) -> dict:
        return {"id": r["id"], "scout_id": r["scout_id"], "venue_id": r["venue_id"], "kind": r["kind"], "amount_centimes": int(r["amount_centimes"]),
                "state": r["state"], "reason": r["reason"], "at": int(r["at"]), "decided_at": int(r["decided_at"]), "expires_at": int(r["expires_at"]),
                "appeal": r["appeal"], "appealed_at": int(r["appealed_at"])}

    def rewards_for(self, scout: str, now: int = 0) -> dict:
        """What the scout's screen shows: every reward with its state and reason, the total
        still valid, and the offer. Promotional credit, never cash - the text says so."""
        rows = self.db.execute("SELECT * FROM place_rewards WHERE scout_id=? ORDER BY at DESC LIMIT 100", (scout,)).fetchall()
        earned = sum(int(r["amount_centimes"]) for r in rows if r["state"] == EARNED)
        return {"rewards": [self._reward_view(r) for r in rows], "earned_centimes": earned,
                "pending_centimes": sum(int(r["amount_centimes"]) for r in rows if r["state"] == PENDING),
                "note": "Crédit promotionnel Prok, non retirable en espèces, valable 90 jours.",
                "offer": self.offer_for(scout, now)}

    def appeal(self, scout: str, reward_id: str, text: str, now: int) -> dict:
        """Within 30 days of the decision the scout may contest a REJECTED reward. The text is
        kept, the state stays REJECTED until an operator decides."""
        self._require_signed(scout, "places.appeal", now)
        r = self.db.execute("SELECT * FROM place_rewards WHERE id=?", (reward_id,)).fetchone()
        if r is None:
            raise PlacesError("unknown reward", code=404)
        if r["scout_id"] != scout:
            raise PlacesError("not your reward", NOT_YOURS, 403)
        if r["state"] != REJECTED:
            raise PlacesError("only a rejected reward can be contested", WRONG_STATE, 409)
        if now - int(r["decided_at"]) > APPEAL_WINDOW_MS:
            raise PlacesError("the 30-day appeal window has closed", APPEAL_WINDOW_CLOSED, 409)
        if not (text or "").strip():
            raise PlacesError("say why")
        with self.db:
            self.db.execute("UPDATE place_rewards SET appeal=?, appealed_at=? WHERE id=?", (_clean_text(text, 500), now, reward_id))
            self._audit(now, scout, "places.appeal", reward_id)
        return {"ok": True, "reward": self._reward_view(self.db.execute("SELECT * FROM place_rewards WHERE id=?", (reward_id,)).fetchone())}

    def decide_reward(self, operator: str, reward_id: str, earn: bool, now: int, reason: str = "") -> dict:
        """An operator settles an appeal: EARNED (posted now, caps still apply) or REJECTED
        with the operator's reason."""
        self._require_operator(operator, "places.decide_reward", reward_id, now)
        r = self.db.execute("SELECT * FROM place_rewards WHERE id=?", (reward_id,)).fetchone()
        if r is None:
            raise PlacesError("unknown reward", code=404)
        if r["state"] not in (REJECTED, PENDING):
            raise PlacesError("reward is " + r["state"], WRONG_STATE, 409)
        with self.db:
            if earn:
                cap = self._cap_hit(r["scout_id"], int(r["amount_centimes"]), now)
                if cap:
                    raise PlacesError("the cap still stops this: " + cap, cap, 409)
                self._earn(reward_id, now, decided_by=operator)
            else:
                self.db.execute("UPDATE place_rewards SET state=?, reason=?, decided_at=?, decided_by=? WHERE id=?",
                                (REJECTED, _clean_text(reason, 200) or r["reason"], now, operator, reward_id))
            self._audit(now, operator, "places.decide_reward", reward_id, "earned" if earn else "rejected: " + reason)
        return {"ok": True, "reward": self._reward_view(self.db.execute("SELECT * FROM place_rewards WHERE id=?", (reward_id,)).fetchone())}

    # ---- the public answers ---------------------------------------------------------------------

    def _card(self, row, now: int, full: bool = False) -> dict:
        st = self.status_of(row, now)
        hours = parse_hours(row["hours_json"])
        visible = row["state"] in VISIBLE_STATES
        card = {
            "id": row["id"], "city": row["city"], "neighbourhood": row["neighbourhood"], "name": row["name"], "kind": row["kind"],
            # the exact entrance leaves the server only for a visible, consented venue
            "lat": float(row["lat"]) if visible else 0.0, "lon": float(row["lon"]) if visible else 0.0,
            "access_rule": row["access_rule"], "access_text": ACCESS_TEXT.get(row["access_rule"], ""),
            "signin_path": row["signin_path"] if row["access_rule"] == FREE_AFTER_SIGNIN else "",
            "hours": hours_line(hours) if hours else "",
            "open_now": open_at(hours, now) is True,
            "status": st["status"], "status_text": STATUS_TEXT[st["status"]], "status_age_ms": st["status_age_ms"],
            "last_ok_at": st["last_ok_at"], "confidence": st["confidence"],
            "direct_or_relay": "direct", "prok_deliverable": bool(int(row["prok_deliverable"])),
            "updated_at": int(row["updated_at"]),
        }
        if full:
            card.update({"state": row["state"], "cell": row["cell"], "radio_hash": row["radio_hash"], "owner_id": row["owner_id"],
                         "consent_recorded_by": row["consent_recorded_by"], "consent_at": int(row["consent_at"]), "consent_doc": row["consent_doc"],
                         "dup_candidate_of": row["dup_candidate_of"], "submitted_by": row["submitted_by"], "created_at": int(row["created_at"]),
                         "blockers": self.publication_blockers(row["id"]),
                         "open_reports": self._open_reports(row["id"]),
                         "checks": [{"checker_id": c["checker_id"], "at": int(c["at"]), "ok": bool(int(c["ok"])), "kind": c["kind"], "note": c["note"]}
                                    for c in self._checks(row["id"])]})
        return card

    def venue(self, venue_id: str, now: int, who: str = "") -> dict:
        row = self._venue(venue_id)
        if row["state"] not in VISIBLE_STATES and not (who and self.is_operator(who)):
            raise PlacesError("unknown venue", UNKNOWN_VENUE, 404)
        return self._card(row, now, full=bool(who and self.is_operator(who)))

    def _visible(self, city: str) -> List[sqlite3.Row]:
        return self.db.execute("SELECT * FROM place_venues WHERE city=? AND state IN (?,?) ORDER BY name", (city,) + VISIBLE_STATES).fetchall()

    def last_update(self, city: str) -> int:
        """The newest change a phone could notice in this city: a venue row or a check."""
        v = self.db.execute("SELECT COALESCE(MAX(updated_at),0) AS m FROM place_venues WHERE city=?", (city,)).fetchone()["m"]
        c = self.db.execute("SELECT COALESCE(MAX(c.at),0) AS m FROM place_checks c JOIN place_venues v ON v.id=c.venue_id WHERE v.city=?", (city,)).fetchone()["m"]
        return int(max(v, c))

    def index(self, city: str, now: int) -> dict:
        """The whole city for offline caching: count, last update, neighbourhoods and the
        minimal cards with status. The list says it is not exhaustive, because it is not."""
        rows = self._visible(city)
        cards = [self._card(r, now) for r in rows]
        cards.sort(key=lambda c: (STATUS_RANK[c["status"]], c["name"]))
        return {"city": city, "generated_at": now, "count": len(cards), "last_update": self.last_update(city), "exhaustive": False,
                "note": "Liste non exhaustive : seuls les lieux vérifiés et consentants figurent ici.",
                "neighbourhoods": sorted(set(c["neighbourhood"] for c in cards if c["neighbourhood"])),
                "venues": cards}

    def search(self, city: str, text: str, now: int = 0) -> dict:
        q = normalise_name(text)
        cards = []
        for r in self._visible(city):
            hay = normalise_name(r["name"] + " " + r["neighbourhood"] + " " + r["kind"])
            if not q or q in hay:
                cards.append(self._card(r, now))
        cards.sort(key=lambda c: (STATUS_RANK[c["status"]], c["name"]))
        return {"city": city, "query": text, "count": len(cards), "venues": cards}

    def ops_queue(self, operator: str, now: int) -> dict:
        self._require_operator(operator, "places.ops_queue", "-", now)
        drafts = [self._card(r, now, full=True) for r in self.db.execute("SELECT * FROM place_venues WHERE state IN (?,?,?) ORDER BY updated_at DESC", (DRAFT, PENDING_REVIEW, HIDDEN)).fetchall()]
        claims = [dict(r) for r in self.db.execute("SELECT * FROM place_claims WHERE state=? ORDER BY at", (PENDING,)).fetchall()]
        reports = [dict(r) for r in self.db.execute("SELECT * FROM place_reports WHERE state=? ORDER BY at", (OPEN,)).fetchall()]
        appeals = [self._reward_view(r) for r in self.db.execute("SELECT * FROM place_rewards WHERE state=? AND appeal!='' ORDER BY appealed_at", (REJECTED,)).fetchall()]
        return {"venues": drafts, "claims": claims, "reports": reports, "appeals": appeals,
                "offer_available": self.offer_available(now), "issued_this_month_centimes": self._issued("", now)[2]}

    # ---- housekeeping ---------------------------------------------------------------------------

    def sweep(self, now: int) -> Dict[str, int]:
        """Expire EARNED rewards past 90 days (and give the credit back to the fund). Status is
        derived at read time, so WORKING_NOW expiry and demotion need no timer; the counts
        of stale and unavailable published venues are reported for the operator's digest."""
        n = {"rewards_expired": 0, "venues_stale": 0, "venues_unavailable": 0}
        with self.db:
            for r in self.db.execute("SELECT * FROM place_rewards WHERE state=? AND expires_at<=?", (EARNED, now)).fetchall():
                self.db.execute("UPDATE place_rewards SET state=?, reason='expired after 90 days' WHERE id=?", (EXPIRED, r["id"]))
                if self.ledger_post is not None:
                    self.ledger_post(now, "PROMO_EXPIRE", "promo:" + r["scout_id"], "fund:promo", int(r["amount_centimes"]), r["id"] + ":expire", "promo credit expired")
                n["rewards_expired"] += 1
        for r in self.db.execute("SELECT * FROM place_venues WHERE state=?", (PUBLISHED,)).fetchall():
            s = self.status_of(r, now)["status"]
            if s == UNVERIFIED_STALE:
                n["venues_stale"] += 1
            elif s == UNAVAILABLE:
                n["venues_unavailable"] += 1
        return n

    # ---- HTTP dispatch --------------------------------------------------------------------------

    def handle_get(self, who: str, path: str, query, now: int):
        try:
            city = _q(query, "city", "Brazzaville")
            if path == "/v1/places/index":
                newer = _q(query, "if_newer", "0")
                try:
                    newer = int(newer)
                except ValueError:
                    newer = 0
                last = self.last_update(city)
                if newer and last <= newer:
                    return 200, {"city": city, "unchanged": True, "last_update": last, "generated_at": now}
                return 200, self.index(city, now)
            if path == "/v1/places/venue":
                return 200, self.venue(_q(query, "id"), now, who)
            if path == "/v1/places/search":
                return 200, self.search(city, _q(query, "q"), now)
            if path == "/v1/places/offer":
                return 200, self.offer_for(who, now)
            if path == "/v1/places/rewards":
                self._require_signed(who, "places.rewards", now)
                return 200, self.rewards_for(who, now)
            if path == "/v1/places/ops/queue":
                return 200, self.ops_queue(who, now)
            if path == "/v1/places/ops/venue":
                self._require_operator(who, "places.ops_venue", _q(query, "id"), now)
                return 200, self._card(self._venue(_q(query, "id")), now, full=True)
            return 404, {"error": "not found"}
        except PlacesError as e:
            return e.code, {"error": str(e), "reason": e.reason}

    def handle_post(self, who: str, path: str, body, now: int):
        # tolerate the two argument orders that have been written down for this dispatcher
        if isinstance(body, str) and isinstance(path, dict):
            path, body = body, path
        body = body if isinstance(body, dict) else {}
        s = lambda k, d="": body.get(k, d) if isinstance(body.get(k, d), str) else d
        n = lambda k, d=0: int(body.get(k, d)) if isinstance(body.get(k, d), (int, float)) and not isinstance(body.get(k, d), bool) else d
        b = lambda k: body.get(k) is True or body.get(k) == 1 or body.get(k) == "true"
        if path not in POST_PATHS:
            return 404, {"error": "not found"}
        try:
            self._require_signed(who, "places.post", now)
            if path == "/v1/places/sighting":
                return 200, self.sighting(who, s("city", "Brazzaville"), s("cell"), s("radio_hash"), s("kind"), n("signal"), b("validated_internet"), now)
            if path == "/v1/places/check":
                kind = s("kind", VISITOR) or VISITOR
                if kind == OPERATOR:
                    raise PlacesError("operator checks go through /v1/places/ops/check", BAD_KIND)
                return 200, self.check(who, s("venue_id"), b("ok"), kind, now, s("note"))
            if path == "/v1/places/report":
                return 200, self.report(who, s("venue_id"), s("kind", OTHER) or OTHER, s("text"), now)
            if path == "/v1/places/claim":
                return 200, self.claim(who, s("venue_id"), s("kind"), s("text"), now)
            if path == "/v1/places/appeal":
                return 200, self.appeal(who, s("reward_id"), s("text"), now)
            if path == "/v1/places/ops/venue":
                return 200, self.venue_submit(who, s("city", "Brazzaville"), s("name"), s("kind"), body.get("lat"), body.get("lon"), now,
                                              neighbourhood=s("neighbourhood"), radio_hash=s("radio_hash"), cell=s("cell"), access_rule=s("access_rule"),
                                              signin_path=s("signin_path"), hours=body.get("hours"), prok_deliverable=b("prok_deliverable"))
            if path == "/v1/places/ops/update":
                fields = {k: body.get(k) for k in ("name", "neighbourhood", "kind", "signin_path", "lat", "lon", "access_rule", "hours", "prok_deliverable", "radio_hash", "owner_id") if k in body}
                return 200, self.venue_update(who, s("venue_id"), now, **fields)
            if path == "/v1/places/ops/consent":
                return 200, self.record_consent(who, s("venue_id"), s("consent_doc"), now, s("owner_id"))
            if path == "/v1/places/ops/publish":
                return 200, self.venue_publish(who, s("venue_id"), now)
            if path == "/v1/places/ops/hide":
                return 200, self.venue_hide(who, s("venue_id"), now, s("reason"), b("review"))
            if path == "/v1/places/ops/remove":
                return 200, self.venue_remove(who, s("venue_id"), now, s("reason"))
            if path == "/v1/places/ops/check":
                return 200, self.check(who, s("venue_id"), b("ok"), OPERATOR, now, s("note"))
            if path == "/v1/places/ops/claim":
                return 200, self.review_claim(who, s("claim_id"), b("accept"), now, s("note"))
            if path == "/v1/places/ops/report":
                return 200, self.review_report(who, s("report_id"), b("confirm"), now, s("note"))
            if path == "/v1/places/ops/reward":
                return 200, self.decide_reward(who, s("reward_id"), b("earn"), now, s("reason"))
            if path == "/v1/places/ops/sweep":
                self._require_operator(who, "places.sweep", "-", now)
                return 200, self.sweep(now)
            return 404, {"error": "not found"}
        except PlacesError as e:
            return e.code, {"error": str(e), "reason": e.reason}
