# ProkNet Network Brain (pilot, v0.13)

The shared brain the phones synchronise with when they have Internet: coarse
coverage, provider availability, Internet requests, jobs. Small on purpose:
Python 3.11 standard library HTTP server + SQLite, plus `cryptography` to
verify the phones' ECDSA P-256 signatures.

**Status: BUILT + TESTED LOCALLY.** Not deployed on a public HTTPS hostname.
The Android app runs fully in direct / local mode with no server configured;
the brain only augments it. Never hardcode a plain-http address into the app.

## Run

```
cd server
pip install -r requirements.txt
python -m brain.app --host 127.0.0.1 --port 8080 --db brain.db
```

Routes:

- `GET /health` — plain text: `ok`, protocol, uptime, counts. For deployment checks.
- `POST /v1/sync` — body: one signed `prok-sync/1` upload; response: one `prok-sync/1` download.

## Tests

```
cd server
python -m unittest discover -s tests -t .
```

Real P-256 keys, an injected clock, an in-memory database. 11 tests: signed
sync accepted, tampered / stale / wrong-key / wrong-id / unsigned rejected;
request signature layout byte-identical to the phone; duplicate sync
idempotent; duplicate request not duplicated; newer generation supersedes;
a tombstone can never return OPEN, even signed; expired request not stored
and cleanup expires live ones on time; provider activation only when opted
in, alive, not busy, not sharing, same zone, within the ceiling, once per
(request, provider); job states move forward only; the tombstone cancels
the job; stale heartbeats not matched and deleted; commercial plans cannot
exceed the customer ceiling, sponsored / growth may; one Wi-Fi source across
three observers, one provider across three phones; a mere detection is not
supply; no table has a coordinate column; shared status GREEN only with a
provider sharing now, YELLOW then nothing as time passes; the download
renders and the request line still verifies.

## Deploy (when a hostname exists)

1. A VPS with Python 3.11 and `pip install cryptography`.
2. Run the server on `127.0.0.1:8080` under a supervisor (systemd, NSSM on Windows, or a scheduled task):
   `python -m brain.app --host 127.0.0.1 --port 8080 --db /var/lib/proknet/brain.db`
3. Put an HTTPS reverse proxy in front. Caddy, two lines:
   ```
   brain.example.org {
       reverse_proxy 127.0.0.1:8080
   }
   ```
   or nginx with `proxy_pass http://127.0.0.1:8080;` under a TLS server block.
4. On the phones: Développeur → BRAIN URL → `https://brain.example.org`. Empty = off.
5. Check `https://brain.example.org/health`.

## Protocol (prok-sync/1)

Tab-separated lines. Upload: `V 1` · `N nodeId pub ts` · `Z zone` · `C key
kind zone lastSeen validated price trust observations`* · `P zone potential
sharing upstream price busy capable`? · `R <request line>`* · `J jobId state
ts`* · `S signatureHex` (ECDSA P-256 / SHA-256, DER, over every byte before
it). The node id must equal SHA-256(pub)[:16]; the timestamp must be within
10 minutes of the server clock. Download: `V 1 serverTs` · `X zone status
direct potential bestPrice lastSeen observers`* · `R <request>`* · `J jobId
type zone requestId state expiresAt`* · `Q requestId state generation`* ·
`A advice`*. Every write is idempotent.

## Privacy

Zones are ~500 m cells (`z<latIdx>:<lonIdx>`), never a point; Wi-Fi sources
are `wifi:<sha256(BSSID)[:16]>`, never a BSSID; providers are ProkNet ids.
No password, no credential, no coordinate is accepted or stored (a test
checks the schema for coordinate columns). Coverage summaries and
availability heartbeats are sent only when the person opted in on the
phone.

## TTLs (deterministic, tested with a fake clock)

NOW request 30 min → `EXPIRED`; tombstones and expired requests kept 2 h;
availability stale after 15 min, deleted after 60 min; activation jobs
10 min; finished jobs kept 24 h; sources kept 30 days; cells rebuilt on
every sync and every cleanup; confidence decays from 1.0 at 10 min to 0.2
at 24 h.

## Moving to PostgreSQL later

`brain/db.py` is plain SQL with a migrations list and `ON CONFLICT ... DO
UPDATE` upserts (SQLite ≥ 3.24 / PostgreSQL ≥ 9.5). Swap the connection and
the `?` placeholders; the logic does not change.
