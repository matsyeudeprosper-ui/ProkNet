"""v0.15.1: every settlement submission is signed, fresh, and used once.

Transport security and evidence are different things and both matter. HTTPS says the
bytes were not altered in flight. It says nothing about who sent them or whether this is
the third replay of a submission from an hour ago. This module covers the second half.

A submission carries:

    prok        the submitting identity's public key (64 raw bytes, hex)
    ts          milliseconds since the epoch, as the sender saw it
    nonce       random, unique per identity
    body_hash   sha256 of the exact JSON body bytes
    sig         signature over the domain-separated line above

Rejected: a bad signature, a timestamp outside the window, a body that does not hash to
what was signed, and any nonce this identity has already used.
"""
import hashlib
import time

from . import protocol

DOMAIN = "ProkNet-api-1"

#: How far out of step a phone's clock may be. Phones in the field drift.
MAX_SKEW_MS = 5 * 60 * 1000

#: How long a nonce is remembered. Must exceed the skew window, or a replay could be
#: accepted after its nonce was forgotten but while its timestamp is still valid.
NONCE_TTL_MS = 2 * MAX_SKEW_MS


class AuthError(Exception):
    """The request is not authentic. The message is safe to return."""


def signing_line(ts: int, nonce: str, body_hash: str) -> bytes:
    return ("%s|%d|%s|%s" % (DOMAIN, ts, nonce, body_hash)).encode("utf-8")


def body_hash(raw: bytes) -> str:
    return hashlib.sha256(raw).hexdigest()


class Nonces:
    """Seen nonces, per identity, with expiry. In memory: one process owns the pilot."""

    def __init__(self):
        self.seen = {}          # (prok, nonce) -> expiry ms

    def use(self, prok: str, nonce: str, now: int) -> bool:
        """True when this nonce is new. False means it is a replay."""
        self.sweep(now)
        key = (prok, nonce)
        if key in self.seen:
            return False
        self.seen[key] = now + NONCE_TTL_MS
        return True

    def sweep(self, now: int) -> int:
        dead = [k for k, exp in self.seen.items() if exp <= now]
        for k in dead:
            del self.seen[k]
        return len(dead)


def verify(headers, raw_body: bytes, nonces: Nonces, now: int = None) -> str:
    """Check a signed request and return the submitting identity's node id.

    `headers` is anything with `.get`, so the HTTP handler and the tests use the same path.
    """
    if now is None:
        now = int(time.time() * 1000)

    prok = (headers.get("X-Prok-Identity") or "").strip()
    ts_raw = (headers.get("X-Prok-Timestamp") or "").strip()
    nonce = (headers.get("X-Prok-Nonce") or "").strip()
    sig = (headers.get("X-Prok-Signature") or "").strip()

    if not prok or not ts_raw or not nonce or not sig:
        raise AuthError("unsigned request")
    if len(nonce) < 8 or len(nonce) > 64:
        raise AuthError("bad nonce")
    try:
        ts = int(ts_raw)
    except ValueError:
        raise AuthError("bad timestamp")

    if abs(now - ts) > MAX_SKEW_MS:
        raise AuthError("timestamp outside the accepted window")

    digest = body_hash(raw_body)
    if not protocol.verify(prok, signing_line(ts, nonce, digest), sig):
        raise AuthError("signature does not verify")

    # last, so a replay of a request that never verified cannot burn a nonce
    if not nonces.use(prok, nonce, now):
        raise AuthError("this request has already been used")

    return protocol.node_id(prok)
