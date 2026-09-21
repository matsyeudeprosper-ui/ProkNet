"""v0.16.2: signed receipt-parser rules, so an operator rewording is not a release.

MTN or Airtel will change a word one day, and a template parser would quietly stop
clearing debts. The parser is already scored rather than templated, but its dictionaries
still ship in the APK. This lets them be replaced by **signed data**.

Three rules make that safe enough to do at all:

1. **A dedicated signing key**, not the server's transport identity. A compromised Brain
   can then withhold or delay a config, but it cannot forge one. The phone pins the
   public key.
2. **Data only.** Word lists and nothing else. No regex from the server, no expressions,
   no scripts, no code. A rule that could execute would be a remote code path into the
   part of the system that decides whether money arrived.
3. **Hard bounds.** A compromised Brain must not be able to send fifty megabytes of
   rules, or a term long enough to matter.

If anything fails, the phone keeps the rules it already trusts. Payment detection never
waits for a config and never depends on one.
"""
import hashlib
import json

from . import protocol

DOMAIN = "ProkNet-receipt-rules-1"

CATEGORIES = ("credit", "debit", "currency", "balance", "reject", "senders")

#: Bounds. Generous for real wording, far too small to be a delivery mechanism.
MAX_TERMS_PER_CATEGORY = 64
MAX_TERM_LENGTH = 48
MAX_CONFIG_BYTES = 16 * 1024


class ConfigError(Exception):
    """The config is not acceptable. Never partially applied."""


def canonical(version: int, valid_from: int, terms: dict) -> bytes:
    """The exact bytes that are signed.

    Sorted keys and no incidental whitespace, so the phone and the server compute the same
    bytes from the same content without either of them having to re-serialise what the
    other sent.
    """
    body = {"version": int(version), "validFrom": int(valid_from),
            "terms": {k: list(terms.get(k, [])) for k in CATEGORIES}}
    raw = json.dumps(body, sort_keys=True, separators=(",", ":"), ensure_ascii=False)
    return (DOMAIN + "|" + raw).encode("utf-8")


def validate(version: int, valid_from: int, terms: dict) -> dict:
    """Shape and bounds. Raises rather than trimming: a truncated rule set is a silent
    behaviour change to the part of the system that decides whether money arrived."""
    if not isinstance(version, int) or version < 1:
        raise ConfigError("version must be a positive integer")
    if not isinstance(valid_from, int) or valid_from < 0:
        raise ConfigError("validFrom must be a timestamp")
    if not isinstance(terms, dict):
        raise ConfigError("terms must be an object")
    cleaned = {}
    for k in CATEGORIES:
        v = terms.get(k, [])
        if not isinstance(v, list):
            raise ConfigError("%s must be a list" % k)
        if len(v) > MAX_TERMS_PER_CATEGORY:
            raise ConfigError("%s has too many terms" % k)
        out = []
        for t in v:
            if not isinstance(t, str):
                raise ConfigError("%s contains a non-string term" % k)
            if not t or len(t) > MAX_TERM_LENGTH:
                raise ConfigError("%s contains a term of unacceptable length" % k)
            if any(ord(c) < 0x20 or ord(c) == 0x7F for c in t):
                raise ConfigError("%s contains a control character" % k)
            out.append(t)
        cleaned[k] = out
    if len(canonical(version, valid_from, cleaned)) > MAX_CONFIG_BYTES:
        raise ConfigError("config is too large")
    return cleaned


def sign(version: int, valid_from: int, terms: dict, private_key) -> str:
    """Used by whoever holds the config key. Never by the request-handling server."""
    from cryptography.hazmat.primitives import hashes
    from cryptography.hazmat.primitives.asymmetric import ec
    data = canonical(version, valid_from, validate(version, valid_from, terms))
    return private_key.sign(data, ec.ECDSA(hashes.SHA256())).hex()


def verify(version: int, valid_from: int, terms: dict, signature: str, config_pub: str) -> bool:
    try:
        cleaned = validate(version, valid_from, terms)
    except ConfigError:
        return False
    return protocol.verify(config_pub, canonical(version, valid_from, cleaned), signature)


class RuleStore:
    """Whatever config the operator of this Brain has published. Optional by design."""

    def __init__(self, db, config_pub: str = ""):
        self.db = db
        #: The public key phones pin. Empty means this Brain publishes no rules at all,
        #: which is a perfectly normal deployment: the built-in rules keep working.
        self.config_pub = config_pub
        self.db.executescript("""
CREATE TABLE IF NOT EXISTS receipt_rules(
    version    INTEGER PRIMARY KEY,
    valid_from INTEGER NOT NULL,
    terms      TEXT NOT NULL,
    signature  TEXT NOT NULL,
    stored_at  INTEGER NOT NULL
);
""")
        self.db.commit()

    def publish(self, version: int, valid_from: int, terms: dict, signature: str, now: int) -> dict:
        """Accept a config only if it verifies against the pinned key and is newer."""
        if not self.config_pub:
            raise ConfigError("this server publishes no parser rules")
        if not verify(version, valid_from, terms, signature, self.config_pub):
            raise ConfigError("signature does not verify against the configuration key")
        top = self.db.execute("SELECT MAX(version) v FROM receipt_rules").fetchone()["v"]
        if top is not None and version <= top:
            raise ConfigError("a newer configuration already exists")
        cleaned = validate(version, valid_from, terms)
        with self.db:
            self.db.execute(
                "INSERT INTO receipt_rules(version, valid_from, terms, signature, stored_at)"
                " VALUES(?,?,?,?,?)",
                (version, valid_from, json.dumps(cleaned, sort_keys=True, ensure_ascii=False),
                 signature, now))
        return {"ok": True, "version": version}

    def current(self):
        r = self.db.execute("SELECT * FROM receipt_rules ORDER BY version DESC LIMIT 1").fetchone()
        if r is None:
            return None
        return {
            "version": int(r["version"]),
            "validFrom": int(r["valid_from"]),
            "terms": json.loads(r["terms"]),
            "signature": r["signature"],
        }
