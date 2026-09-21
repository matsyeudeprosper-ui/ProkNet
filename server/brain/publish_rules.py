"""v0.16.3: sign and publish receipt-parser rules. An ADMIN tool, not part of the server.

MTN or Airtel will reword a message one day. This is how the new wording reaches phones
without an APK release, and it is the only thing in the project that touches the
configuration signing key.

    python -m brain.publish_rules rules.json --db /srv/proknet/brain.db

The private key never appears on the command line, and never in the repository:

    PROK_CONFIG_PRIVATE_KEY_FILE=/root/proknet-keys/receipt_rules_config_key.pem
    PROK_CONFIG_PRIVATE_KEY=<PEM text>          (only if a file is impossible)

**Why the running Brain does not hold this key.** The public server is the part of the
system most likely to be compromised: it is reachable from the Internet and it parses
what strangers send it. If it also held the key, whoever took it could write new rules
for the part of every phone that decides whether money arrived. Holding only the public
key means a compromised Brain can withhold or delay a configuration - which phones
survive, because the built-in rules keep working - but can never forge one.

So the shape is deliberately boring:

    admin, offline, with the private key
        -> signs canonical bytes
        -> hands the Brain a signed configuration
        -> Brain verifies against the PUBLIC key before storing
        -> phone downloads and verifies AGAIN against the key pinned in the APK

Two verifications of the same signature, by two parties who trust each other about
nothing. The phone's is the one that matters.

The rules file is plain JSON:

    {
      "version": 2,
      "validFrom": 1758400000000,
      "terms": {
        "credit": ["vous avez recu", "fonds arrives"],
        "currency": ["FCFA", "XAF"]
      }
    }

`--db` writes straight into the Brain's SQLite file, which is the safe default: it needs
no network, no extra endpoint and no second set of credentials. `--url` posts to a running
Brain instead, for a deployment where the admin machine is not the server; that still
needs a Prok identity to sign the HTTP request, but the configuration's own signature is
what the Brain and the phones actually check.
"""
import argparse
import io
import json
import os
import sys
import time

from . import protocol
from . import ruleconfig


def load_private_key():
    """From a file path, or from the variable itself. Never from a command-line argument,
    which would put it in the shell history and in every process listing."""
    from cryptography.hazmat.primitives import serialization

    path = os.environ.get("PROK_CONFIG_PRIVATE_KEY_FILE", "").strip()
    if path:
        with io.open(path, "rb") as f:
            return serialization.load_pem_private_key(f.read(), password=None)
    pem = os.environ.get("PROK_CONFIG_PRIVATE_KEY", "").strip()
    if pem:
        return serialization.load_pem_private_key(pem.encode("utf-8"), password=None)
    raise SystemExit(
        "No configuration signing key.\n"
        "  set PROK_CONFIG_PRIVATE_KEY_FILE to the PEM file, or PROK_CONFIG_PRIVATE_KEY to its text.\n"
        "  The key is never committed and never passed as an argument.")


def public_hex(private_key) -> str:
    n = private_key.public_key().public_numbers()
    return (n.x.to_bytes(32, "big") + n.y.to_bytes(32, "big")).hex()


def expected_public_key(override: str = "") -> str:
    """The key THIS DEPLOYMENT pins, which is not the same thing as the key in my hand.

    Without this the tool trusted whatever key it was given and wrote the result straight
    into the Brain's database. Phones would have refused the result - they pin the real
    key - but the Brain would have served it and the operator would have been told it
    worked. A publishing tool that reports success for a configuration no phone can accept
    is worse than one that refuses.
    """
    if override:
        return override.strip()
    env = os.environ.get("PROK_CONFIG_PUBLIC_KEY", "").strip()
    if env:
        return env
    from . import app
    return app.CONFIG_PUBLIC_KEY


def read_config(path: str):
    with io.open(path, encoding="utf-8") as f:
        doc = json.load(f)
    version = int(doc.get("version", 0))
    valid_from = int(doc.get("validFrom", 0) or int(time.time() * 1000))
    terms = doc.get("terms", {})
    # the same bounds the phone enforces, checked here so a bad file fails at the desk
    # rather than being signed and then silently refused on every phone
    cleaned = ruleconfig.validate(version, valid_from, terms)
    return version, valid_from, cleaned


def main(argv=None):
    ap = argparse.ArgumentParser(description="Sign and publish ProkNet receipt-parser rules")
    ap.add_argument("config", help="the rules JSON file")
    ap.add_argument("--db", default="", help="write straight into the Brain's SQLite file")
    ap.add_argument("--url", default="", help="post to a running Brain instead")
    ap.add_argument("--identity", default="",
                    help="with --url: PEM file of the Prok identity that signs the HTTP request")
    ap.add_argument("--dry-run", action="store_true", help="sign and print, change nothing")
    ap.add_argument("--config-public-key", default="",
                    help="publish for a deployment that pins a different key (a test Brain)")
    a = ap.parse_args(argv)

    key = load_private_key()
    pub = public_hex(key)
    expected = expected_public_key(a.config_public_key)
    if expected and pub != expected:
        raise SystemExit(
            "This key is not the one this deployment pins.\n"
            "  holding  %s (key id %s)\n"
            "  expected %s (key id %s)\n"
            "  Phones would refuse anything signed with it. Use the right key, or pass\n"
            "  --config-public-key if you really are publishing for another deployment."
            % (pub[:16] + "...", ruleconfig.key_id(pub),
               expected[:16] + "...", ruleconfig.key_id(expected)))
    version, valid_from, terms = read_config(a.config)
    signature = ruleconfig.sign(version, valid_from, terms, key)

    doc = {"version": version, "validFrom": valid_from, "terms": terms, "signature": signature}

    print("configuration key id %s (public key %s...)" % (ruleconfig.key_id(pub), pub[:16]))
    print("version %d, validFrom %d, %d term(s)"
          % (version, valid_from, sum(len(v) for v in terms.values())))
    if not ruleconfig.verify(version, valid_from, terms, signature, pub):
        raise SystemExit("the signature does not verify against its own key; refusing to publish")
    print("signature verifies against the configuration public key")

    if a.dry_run or (not a.db and not a.url):
        print(json.dumps(doc, ensure_ascii=False, sort_keys=True))
        if not a.db and not a.url and not a.dry_run:
            print("\nnothing published: pass --db or --url", file=sys.stderr)
        return 0

    if a.db:
        import sqlite3
        db = sqlite3.connect(a.db)
        db.row_factory = sqlite3.Row
        # verified against what the DEPLOYMENT pins, never against the key in my hand
        store = ruleconfig.RuleStore(db, expected or pub)
        out = store.publish(version, valid_from, terms, signature, int(time.time() * 1000))
        print("published to %s: %s" % (a.db, out))
        return 0

    from urllib.request import Request, urlopen
    from . import signed_request
    raw = json.dumps(doc, ensure_ascii=False).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if a.identity:
        from cryptography.hazmat.primitives import serialization
        from cryptography.hazmat.primitives.asymmetric import ec
        from cryptography.hazmat.primitives import hashes
        with io.open(a.identity, "rb") as f:
            ident = serialization.load_pem_private_key(f.read(), password=None)
        n = ident.public_key().public_numbers()
        ident_pub = (n.x.to_bytes(32, "big") + n.y.to_bytes(32, "big")).hex()
        ts = int(time.time() * 1000)
        nonce = os.urandom(16).hex()
        path = "/v1/pay/rules"
        line = signed_request.signing_line(ts, nonce, signed_request.body_hash(raw), "POST", path)
        headers.update({
            "X-Prok-Identity": ident_pub,
            "X-Prok-Timestamp": str(ts),
            "X-Prok-Nonce": nonce,
            "X-Prok-Signature": ident.sign(line, ec.ECDSA(hashes.SHA256())).hex(),
        })
    req = Request(a.url.rstrip("/") + "/v1/pay/rules", data=raw, headers=headers, method="POST")
    with urlopen(req, timeout=30) as r:
        print("published to %s: %s" % (a.url, r.read().decode("utf-8")))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
