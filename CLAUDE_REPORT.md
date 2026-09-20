# CLAUDE_REPORT - ProkNet v0.13.0 "the Network Brain"

Date: 2026-09-20
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built; 248/248 Android JVM tests and 11/11 server tests pass; the
brain runs end to end locally; released. Provider activation on the two
phones (TESTING section 51) is the pending hardware test. Nothing proven
before is touched.**

## 1. Version / build / commit / hash / tests

Build 51, versionName 0.13.0, SHA256 `246c746057adfc9df6ebe1abe99674fb4c6540485e24000e00a111385f5744f6`.
Commit `0920f5b` on `main`; this report on top.
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.13.0
Android JVM tests: 248 (+12). Server tests: 11.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```

## 2. Server deployment status

**BUILT + TESTED LOCALLY.** Ran on the VPS at 127.0.0.1:8089: `/health` ok;
a provider heartbeat, a buyer request uploaded by a third node, the
provider's next sync returning the activation job and the request, and a
tampered message rejected with HTTP 400, all with real P-256 keys. No
public HTTPS hostname exists for this box, so no address is hardcoded: the
app ships with the brain OFF (Développeur → BRAIN URL to set one) and works
fully in direct / local mode. `server/README.md` has the exact run command,
the test command, and the reverse-proxy requirement.

## 3. Request gossip architecture

`core/NetRequest.kt`: the signed object (id, origin, key, times, zone,
flexible MB / minutes, automatic ceiling, urgency NOW, state, generation,
hops, ECDSA P-256 signature over everything but hops), ~230 bytes, `Wire`
op 15 inside the existing encrypted BLE control envelope. `core/
RequestGossip.kt` (pure): one record per id, latest generation wins,
tombstones final, expired / hop-exhausted dropped and counted, forward
once per (id, generation, peer), never back, never to the origin,
persistence codec, sweep. `node/NetworkNode.kt`: originate, end, receive,
forward on peers-changed with unmark-on-failure, activation, sync,
diagnostics. A request travels buyer → B → C → brain in software; 3-phone
carry is not hardware-proven.

## 4. Provider activation behaviour

Opt-in on Gagner. Eligibility: opted in, validated upstream, local path
(Bluetooth on home Wi-Fi / hotspot on mobile data), Bluetooth on if needed,
not sharing, not busy, sell price within the request ceiling. One
notification, rate-limited (10 min per request, 2 min global), honest
wording (nearby over BLE / zone from the brain), PARTAGER → `setSelling
(true)`. The requester polls the decision engine in DEMANDE; the advert is
selected within the ceiling; the proven stack runs; ONLINE → FULFILLED
tombstone; ARRÊTER → CANCELLED tombstone. No second tap.

## 5. Shared coverage architecture

Opt-in "Aider ProkNet à améliorer la carte". Summaries only: hashed Wi-Fi
ids / ProkNet ids, zone, freshness, validation, price, trust. The server
keeps one source per key with a distinct-observer count (a Freebox seen by
15 phones is one source), rebuilds cells on every sync and cleanup:
GREEN only with a provider sharing now and seen < 10 min, YELLOW when
recent, RED otherwise. The phone downgrades any server GREEN to YELLOW:
history is never "available now". Carte shows "Vu par le réseau ProkNet"
rows apart from the phone's own.

## 6. Network Brain API

`GET /health` (plain text). `POST /v1/sync`, `prok-sync/1`, one signed
line message (documented in `server/README.md` and `core/SyncProtocol.kt`).
Signature verified, id = SHA-256(key)[:16] enforced, clock ±10 min,
per-node rate limit 30/min, body ≤ 256 KB, every write idempotent.

## 7. Privacy rules

Zones (~500 m cells), never coordinates; Wi-Fi as SHA-256(BSSID)[:16];
providers as ProkNet ids; no password or credential ever; both
contributions opt-in with one sentence each; a server test asserts no
table has a coordinate column.

## 8. TTL / dedup rules

NOW request 30 min; hop budget 6; dedup by id + generation; tombstones and
expired kept 2 h; forward memory 6 h; availability stale 15 min, deleted
60 min; activation job 10 min; finished jobs 24 h; sources 30 days;
freshness 1.0 at 10 min → 0.2 at 24 h. Deterministic, tested with fake
clocks on both sides.

## 9. Job model and matching

Types PROVIDER_ACTIVATION, CARRY_REQUEST (execute) and ANCHOR, RELAY,
MOVE_TO_ZONE, COURIER (modelled only); states OPEN / OFFERED / ACCEPTED /
ACTIVE / COMPLETED / FAILED / EXPIRED / CANCELLED, forward-only. Matching:
DIRECT_SOURCE → ACTIVATE_PROVIDER (cheapest eligible, locally-seen beats
same-zone, commercial delivery ≤ ceiling) → WAIT_FOR_SUPPLY → NO_PLAN, on
the phone (`core/Jobs.kt`) and the server (`brain/matching.py`) alike.

## 10. Files changed

New: `core/NetRequest.kt`, `core/RequestGossip.kt`,
`core/ProviderActivation.kt`, `core/Jobs.kt`, `core/SyncProtocol.kt`,
`node/NetworkNode.kt`, `test/.../NetworkBrainTest.kt` (12 tests),
`server/brain/{protocol,matching,db,app}.py`, `server/tests/test_brain.py`
(11 tests), `server/README.md`, `server/requirements.txt`. Patched:
`core/Wire.kt` (op 15), `ble/ProkNetNode.kt` (control hook, public
sendControl), `ProkNetApp.kt`, `service/ProkNetService.kt` (PARTAGER
notification and action, periodic sync), `node/CoverageEngine.kt` (shared
cells), `ui/MainActivity.kt` (originate / end, DEMANDE, hints, opt-ins,
shared map rows), `ui/LabActivity.kt` (COPY NETWORK, BRAIN URL),
`transport/WifiTransport.kt` (exhaustive when), layouts, strings,
`build.gradle.kts`.

## 11. Known limitations (honest)

- Provider activation is not yet hardware-proven (section 51 pending).
- 3-phone carry, relay, multi-hop, movers, citywide fulfilment: modelled, not executed, not claimed.
- The brain is local-tested, not deployed: no public HTTPS hostname on this box.
- The DEMANDE search continues while the service runs; the sphere's polling runs while the app is open, so the automatic connection needs the app in front or recently backgrounded (the foreground service keeps the request alive and the notification path works regardless).
- Sync needs the app's own Internet (it is outside its VPN): buyers on ProkNet Internet do not sync; providers do.
- Availability heartbeats only reach the brain when a URL is configured.

## 12. The exact simple hardware test

OUKITEL: Freebox ON, mobile data OFF, Bluetooth ON, sharing OFF, Gagner →
"Me prévenir quand quelqu'un cherche Internet près de moi" ON.
OnePlus: mobile data OFF, not on the Freebox, Bluetooth ON. Tap the sphere.
RECHERCHE → DEMANDE. OUKITEL: "Quelqu'un cherche Internet à proximité" →
PARTAGER. OnePlus, no more taps: CONNEXION → CONNECTÉ → Wikipedia.
TESTING section 51; COPY NETWORK on both if anything fails.
