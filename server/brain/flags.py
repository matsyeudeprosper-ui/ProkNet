"""v0.19.0: feature flags, cohorts and decision records - the operator's switches.

The launch contract (2026-09-26, §10.1) says features are switched per city, per cohort
and per function, and that "go live" for a money or delivery function is never a
declaration that a permission or a payment classification exists. So every switch here
is a DATED ROW with who set it and why (never an edit), and the restricted functions
additionally require a DECISION RECORD - the document, the accountable person, the date -
before they can be turned on at all. A phone only mirrors the answer; the server is the
authority.

Functions (fixed names, from the contract):
    public_map, market_browse, market_paid_publish, scout_rewards, direct_free,
    sponsored_delivery, customer_paid_delivery, provider_payout, relay_payout

A function is ENABLED for (city, identity) when the newest effective row for that
function+city says on, AND (for a cohort-gated function) the identity is in the function's
cohort list, AND (for a restricted function) a decision record of the required kind exists.

Roles: OPERATOR identities (`PROK_OPERATOR_IDS`) set flags, cohorts and decision records
and review moderation queues; TREASURY identities (ledger) move money. One person may hold
both roles in a small pilot; the audit rows say which role acted.
"""
import json
import os
import sqlite3
import time
from typing import Callable, Dict, List, Optional

SCHEMA = """
CREATE TABLE IF NOT EXISTS flag_rows (
    id INTEGER PRIMARY KEY AUTOINCREMENT, function TEXT NOT NULL, city TEXT NOT NULL,
    enabled INTEGER NOT NULL, effective_from INTEGER NOT NULL, set_by TEXT NOT NULL,
    reason TEXT NOT NULL DEFAULT '', at INTEGER NOT NULL);
CREATE INDEX IF NOT EXISTS flag_rows_fn ON flag_rows(function, city, effective_from);
CREATE TABLE IF NOT EXISTS flag_cohorts (
    function TEXT NOT NULL, city TEXT NOT NULL, node_id TEXT NOT NULL, added_by TEXT NOT NULL,
    at INTEGER NOT NULL, removed_at INTEGER NOT NULL DEFAULT 0, PRIMARY KEY (function, city, node_id));
CREATE TABLE IF NOT EXISTS flag_decisions (
    id INTEGER PRIMARY KEY AUTOINCREMENT, kind TEXT NOT NULL, city TEXT NOT NULL, summary TEXT NOT NULL,
    document TEXT NOT NULL, accountable TEXT NOT NULL, decided_at INTEGER NOT NULL,
    recorded_by TEXT NOT NULL, at INTEGER NOT NULL, revoked_at INTEGER NOT NULL DEFAULT 0, revoke_reason TEXT NOT NULL DEFAULT '');
CREATE TABLE IF NOT EXISTS flag_audit (
    id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, actor TEXT NOT NULL, action TEXT NOT NULL,
    target TEXT NOT NULL, detail TEXT NOT NULL DEFAULT '', allowed INTEGER NOT NULL);
"""

FUNCTIONS = ("public_map", "market_browse", "market_paid_publish", "scout_rewards", "direct_free",
             "sponsored_delivery", "customer_paid_delivery", "provider_payout", "relay_payout")

#: Functions that are open to everybody once on (no cohort list).
OPEN_FUNCTIONS = ("public_map", "market_browse", "direct_free")
#: Functions that may only be on for a listed cohort.
COHORT_FUNCTIONS = ("market_paid_publish", "scout_rewards", "sponsored_delivery",
                    "customer_paid_delivery", "provider_payout", "relay_payout")
#: Functions that additionally need a decision record of the given kind before they can be on.
REQUIRED_DECISIONS = {
    "customer_paid_delivery": ("payment_classification", "upstream_permission"),
    "provider_payout": ("payment_classification",),
    "relay_payout": ("payment_classification",),
    "sponsored_delivery": ("upstream_permission",),
    "market_paid_publish": ("merchant_collection_terms",),
}
DECISION_KINDS = ("payment_classification", "upstream_permission", "merchant_collection_terms", "data_protection", "arpce")

#: French, for the app: what a switched-off function says.
OFF_SENTENCES = {
    "public_map": "La carte publique n'est pas encore ouverte dans cette ville.",
    "market_browse": "Prok Market n'est pas encore ouvert dans cette ville.",
    "market_paid_publish": "La publication d'annonces n'est pas encore ouverte pour votre compte.",
    "scout_rewards": "Pas d'offre scout payée pour le moment - les signalements restent bienvenus.",
    "direct_free": "Les connexions gratuites directes ne sont pas encore ouvertes ici.",
    "sponsored_delivery": "Pas de livraison sponsorisée disponible pour le moment.",
    "customer_paid_delivery": "La livraison payante d'Internet n'est pas encore ouverte pour votre compte.",
    "provider_payout": "Les retraits fournisseur ne sont pas encore ouverts pour votre compte.",
    "relay_payout": "Les gains relais ne sont pas encore ouverts pour votre compte.",
}


class FlagsError(Exception):
    def __init__(self, message: str, reason: str = "", code: int = 400):
        super().__init__(message)
        self.reason = reason
        self.code = code


class Flags:
    def __init__(self, db: sqlite3.Connection, operator_ids=(), clock: Callable[[], int] = None):
        self.db = db
        self.db.row_factory = sqlite3.Row
        self.db.executescript(SCHEMA)
        self.db.commit()
        self.operator_ids = set(x for x in operator_ids if x)
        self.clock = clock or (lambda: int(time.time() * 1000))

    @staticmethod
    def from_env(db: sqlite3.Connection) -> "Flags":
        ids = [x.strip() for x in os.environ.get("PROK_OPERATOR_IDS", "").split(",") if x.strip()]
        return Flags(db, operator_ids=ids)

    # ---- roles ---------------------------------------------------------------------------

    def is_operator(self, who: str) -> bool:
        return who in self.operator_ids

    def _require_operator(self, who: str, action: str, target: str, now: int):
        if who not in self.operator_ids:
            self._audit(now, who, action, target, "refused: not an operator", allowed=False)
            self.db.commit()
            raise FlagsError("only an operator may do this", "not_operator", 403)

    def _audit(self, now: int, actor: str, action: str, target: str, detail: str = "", allowed: bool = True):
        self.db.execute("INSERT INTO flag_audit(ts, actor, action, target, detail, allowed) VALUES(?,?,?,?,?,?)",
                        (now, actor, action, target, detail, 1 if allowed else 0))
        if not self.db.in_transaction:
            self.db.commit()

    # ---- the answer ------------------------------------------------------------------------

    def _newest_row(self, function: str, city: str, now: int):
        return self.db.execute("SELECT * FROM flag_rows WHERE function=? AND city=? AND effective_from<=? ORDER BY effective_from DESC, id DESC LIMIT 1",
                               (function, city, now)).fetchone()

    def in_cohort(self, function: str, city: str, node: str) -> bool:
        row = self.db.execute("SELECT 1 FROM flag_cohorts WHERE function=? AND city=? AND node_id=? AND removed_at=0",
                              (function, city, node)).fetchone()
        return row is not None

    def decision_present(self, kind: str, city: str, now: int) -> bool:
        return self.db.execute("SELECT 1 FROM flag_decisions WHERE kind=? AND city=? AND decided_at<=? AND revoked_at=0",
                               (kind, city, now)).fetchone() is not None

    def missing_decisions(self, function: str, city: str, now: int) -> List[str]:
        return [k for k in REQUIRED_DECISIONS.get(function, ()) if not self.decision_present(k, city, now)]

    def enabled(self, function: str, city: str, node: str, now: int) -> bool:
        if function not in FUNCTIONS:
            return False
        row = self._newest_row(function, city, now)
        if row is None or not int(row["enabled"]):
            return False
        if self.missing_decisions(function, city, now):
            return False
        if function in COHORT_FUNCTIONS and not self.in_cohort(function, city, node):
            return False
        return True

    def status(self, city: str, node: str, now: int) -> dict:
        """What one phone may do here, and the sentence for what it may not."""
        out = {"city": city, "functions": {}, "operator": self.is_operator(node)}
        for f in FUNCTIONS:
            on = self.enabled(f, city, node, now)
            row = self._newest_row(f, city, now)
            out["functions"][f] = {
                "enabled": on,
                "switch_on": bool(row and int(row["enabled"])),
                "cohort": (f not in COHORT_FUNCTIONS) or self.in_cohort(f, city, node),
                "missing_decisions": self.missing_decisions(f, city, now),
                "off_sentence": "" if on else OFF_SENTENCES[f],
            }
        return out

    # ---- the operator's switches ------------------------------------------------------------

    def set_flag(self, who: str, function: str, city: str, enabled: bool, reason: str, now: int, effective_from: int = 0) -> dict:
        self._require_operator(who, "flag.set", function + "@" + city, now)
        if function not in FUNCTIONS:
            raise FlagsError("unknown function")
        if not reason.strip():
            raise FlagsError("a reason is required - a switch is a dated business decision")
        missing = self.missing_decisions(function, city, now) if enabled else []
        if missing:
            self._audit(now, who, "flag.set", function + "@" + city, "refused: missing decisions " + ",".join(missing), allowed=False)
            raise FlagsError("cannot switch on %s: missing decision record(s) %s" % (function, ", ".join(missing)), "missing_decision", 409)
        with self.db:
            self.db.execute("INSERT INTO flag_rows(function, city, enabled, effective_from, set_by, reason, at) VALUES(?,?,?,?,?,?,?)",
                            (function, city, 1 if enabled else 0, effective_from or now, who, reason.strip()[:200], now))
            self._audit(now, who, "flag.set", function + "@" + city, ("on" if enabled else "off") + ": " + reason.strip()[:200])
        return {"ok": True, "function": function, "city": city, "enabled": enabled, "effective_from": effective_from or now}

    def add_to_cohort(self, who: str, function: str, city: str, node: str, now: int) -> dict:
        self._require_operator(who, "cohort.add", function + "@" + city, now)
        if function not in COHORT_FUNCTIONS:
            raise FlagsError("that function has no cohort - it is open to everybody when on")
        with self.db:
            self.db.execute("INSERT OR REPLACE INTO flag_cohorts(function, city, node_id, added_by, at, removed_at) VALUES(?,?,?,?,?,0)",
                            (function, city, node, who, now))
            self._audit(now, who, "cohort.add", function + "@" + city, node[:12])
        return {"ok": True}

    def remove_from_cohort(self, who: str, function: str, city: str, node: str, now: int) -> dict:
        self._require_operator(who, "cohort.remove", function + "@" + city, now)
        with self.db:
            self.db.execute("UPDATE flag_cohorts SET removed_at=? WHERE function=? AND city=? AND node_id=?", (now, function, city, node))
            self._audit(now, who, "cohort.remove", function + "@" + city, node[:12])
        return {"ok": True}

    def record_decision(self, who: str, kind: str, city: str, summary: str, document: str, accountable: str, decided_at: int, now: int) -> dict:
        """The external fact no engineer can manufacture, written down: what was decided, the
        document that says so, the accountable person, the date. Required before a restricted
        function can be switched on; revocable, never deleted."""
        self._require_operator(who, "decision.record", kind + "@" + city, now)
        if kind not in DECISION_KINDS:
            raise FlagsError("unknown decision kind")
        if not (summary.strip() and document.strip() and accountable.strip()) or decided_at <= 0:
            raise FlagsError("summary, document, accountable person and date are all required")
        with self.db:
            cur = self.db.execute("INSERT INTO flag_decisions(kind, city, summary, document, accountable, decided_at, recorded_by, at) VALUES(?,?,?,?,?,?,?,?)",
                                  (kind, city, summary.strip()[:500], document.strip()[:300], accountable.strip()[:100], decided_at, who, now))
            self._audit(now, who, "decision.record", kind + "@" + city, document.strip()[:100])
        return {"ok": True, "decision_id": cur.lastrowid}

    def revoke_decision(self, who: str, decision_id: int, reason: str, now: int) -> dict:
        self._require_operator(who, "decision.revoke", str(decision_id), now)
        with self.db:
            self.db.execute("UPDATE flag_decisions SET revoked_at=?, revoke_reason=? WHERE id=? AND revoked_at=0", (now, reason.strip()[:200], decision_id))
            self._audit(now, who, "decision.revoke", str(decision_id), reason.strip()[:200])
        return {"ok": True}

    def console(self, who: str, city: str, now: int) -> dict:
        self._require_operator(who, "flags.console", city, now)
        rows = self.db.execute("SELECT * FROM flag_rows WHERE city=? ORDER BY id DESC LIMIT 100", (city,)).fetchall()
        cohorts = self.db.execute("SELECT function, COUNT(*) AS n FROM flag_cohorts WHERE city=? AND removed_at=0 GROUP BY function", (city,)).fetchall()
        decisions = self.db.execute("SELECT * FROM flag_decisions WHERE city=? ORDER BY id DESC", (city,)).fetchall()
        return {
            "city": city,
            "status": self.status(city, who, now),
            "history": [dict(r) for r in rows],
            "cohorts": {r["function"]: int(r["n"]) for r in cohorts},
            "decisions": [dict(r) for r in decisions],
            "required_decisions": {k: list(v) for k, v in REQUIRED_DECISIONS.items()},
        }

    # ---- dispatch (mounted by app.py under /v1/flags/) ---------------------------------------

    def handle_get(self, who: str, path: str, query: Dict[str, str], now: int):
        city = query.get("city", "Brazzaville")
        if path == "/v1/flags/status":
            return 200, self.status(city, who, now)
        if path == "/v1/flags/console":
            return 200, self.console(who, city, now)
        return 404, {"error": "not found"}

    def handle_post(self, who: str, path: str, body: dict, now: int):
        s = lambda k, d="": body.get(k, d) if isinstance(body.get(k, d), str) else d
        n = lambda k: int(body.get(k, 0)) if isinstance(body.get(k, 0), (int, float)) and not isinstance(body.get(k, 0), bool) else 0
        city = s("city", "Brazzaville")
        if path == "/v1/flags/set":
            return 200, self.set_flag(who, s("function"), city, bool(body.get("enabled", False)), s("reason"), now, n("effective_from"))
        if path == "/v1/flags/cohort/add":
            return 200, self.add_to_cohort(who, s("function"), city, s("node_id"), now)
        if path == "/v1/flags/cohort/remove":
            return 200, self.remove_from_cohort(who, s("function"), city, s("node_id"), now)
        if path == "/v1/flags/decision":
            return 200, self.record_decision(who, s("kind"), city, s("summary"), s("document"), s("accountable"), n("decided_at"), now)
        if path == "/v1/flags/decision/revoke":
            return 200, self.revoke_decision(who, n("decision_id"), s("reason"), now)
        return 404, {"error": "not found"}
