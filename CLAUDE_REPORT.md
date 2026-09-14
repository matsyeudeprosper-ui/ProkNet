# CLAUDE_REPORT - ProkNet v0.9.0 "Coverage engine foundation + 3-phone relay feasibility"

Date: 2026-09-14
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 100/100 automated tests pass, released. Live relay on real
phones: NOT PROVEN (needs Mike's 3-phone test).**

## 1. Honest summary

- Part A (pure coverage engine): implemented and tested on synthetic zones.
- Part B (3-phone live relay): the full path is implemented (second Wi-Fi
  link on the relay, end-to-end sealed frames, introductions, forwarding,
  counters, probe, COPY RELAY DIAG). Whether a phone can hold the upstream
  Wi-Fi client connection and its own local-only hotspot at the same time
  is unknown until the test. Nothing in this build fakes it: if the
  hotspot cannot start while joined, the log says so and the diag shows it.
- Part C (source discovery): Wi-Fi scan + local trust classification in
  the Relay Lab. No passwords, no auto-connect, no upload.
- v0.8 consumer UI unchanged apart from two lines: a relayed offer card
  says "through another phone", and Home uses the coverage words.

## 2. Part A - core/Coverage.kt (pure, 9 tests)

Concepts as specified: InternetSource (type, Trust class, cost/MB,
reliability), RadioObservation -> CandidateLink, CoverageNode (all the
fields listed in the spec), DemandRequest (COMMERCIAL / SPONSORED /
GROWTH_SUBSIDY with budget), CoverageJob (PROVIDE / RELAY / MOVE /
ACTIVATE), CandidateRoute, RouteScore, CoverageZone GREEN / YELLOW / RED.

Planner: enumerate paths buyer -> relays -> usable provider (max 3
relays), fundable providers with an ACTIVATE job, and mover routes where a
link is missing. Score = delivered cost + failure penalty + delay penalty +
movement cost + resource penalty, every weight in one `Coverage.Policy`
object. Preferences from the spec are all in the score: stable and
charging phones (bonus), reliable links (pFail), phones already in
position (in-position bonus vs movement cost), couriers heading to the
zone (movement discount), free/cheap sources (delivered cost).

Economic ceiling: COMMERCIAL rejects jobs > revenue with the reason
"exceeds commercial ceiling: jobs 60.00 CFA > 50.00 CFA"; SPONSORED
against the sponsor budget; GROWTH_SUBSIDY computes the explicit
`subsidyNeeded` and rejects above budget.

Tests (CoverageTest) prove exactly the eight decisions you listed plus
zone colouring and observation -> link quality:
free nearby Wi-Fi beats a paid 2-relay path; one reliable direct mobile
provider beats a cheap 3-relay chain with 0.5-quality links (chain pFail
> 90 %); a passive relay in position beats a mover; a mover is chosen when
it is the only way and jobs (11 CFA) fit revenue (50 CFA), rejected at 61
CFA, a courier pays half; COMMERCIAL rejects the 60 CFA-of-jobs route that
GROWTH_SUBSIDY with 15 CFA budget approves (subsidy 10 CFA) and rejects
with 5 CFA; UNKNOWN / CAPTIVE / NOT_ALLOWED are never redistributable and
such a provider is not even a candidate; ranking identical for six
shuffled input orders with reversed link directions.

Simulated only: no real city, no GPS, no dispatch. Consumer abstraction
added: `ProductState.coverageWord` = "Internet available / Internet can be
arranged / No connection available yet" (tested to contain no colour or
"relay" word).

## 3. Part B - live relay

### 3.1 Design (what the phones will run)

```
A (buyer)                 B (relay)                          C (seller)
data OFF                  data OFF                           data ON
tunnel client  --sealed-->  wifi (host, DOWN link)
               link A-B     |  RelayNode: forward, count
                            wifiUp (client, UP link) --sealed--> gateway -> Internet
```

- `WifiTransport` gained a name and a `mayHost` flag. The node runs two
  instances: `wifi` (unchanged behaviour, hosts A) and `wifiUp`
  (client-only, joins C's hotspot via WifiNetworkSpecifier). Both use the
  same BLE control channel; `dispatchControl` gives a WIFI_OFFER to the
  instance that requested it. Upstream socket = granted Network's socket
  factory; downstream = server socket on the hotspot. Nothing relies on
  the default network.
- `core/Relay.kt` (pure, 3 tests): FRAME_RELAY = [ver][origin 4][nonce
  12][AES-GCM(tunnel frame)], key = HKDF(static ECDH(A, C), both ids),
  aad = ver + origin. FRAME_RELAY_INFO = introductions with self-certifying
  identity records (a relay cannot invent a seller: a forged record is
  refused, tested). B never holds the key (tested: B's key does not open
  the frame; tampering with ciphertext or the clear origin fails).
- `node/RelayNode.kt`: B forwards between links and counts (Relay.Session:
  id, up peer, down peer, bytes and frames each direction, duration,
  reason; in memory, log and diag). A: after the introduction the existing
  TunnelClient proposes the contract to C with every frame sealed. C: the
  existing Gateway handles A's frames as if A were on the link
  (`Gateway.Hooks.peerFullId(short)` instead of the link peer), so C's
  contract, checkpoints, ledger and history name A. Keepalives, checkpoints,
  settlement: unchanged protocol, just sealed.
- B advertises `SELL | VIA_RELAY` at C's price when its upstream is up and
  C is selling; A's consumer card shows "through another phone". A buying
  a relayed offer waits (20 s max) for B's introduction before proposing.
- Direction/role routing from v0.7.1 still gates every frame
  (TunnelRoutingTest unchanged, still 17 types).

### 3.2 Capability probe (Developer -> Relay Lab)

Reports device, Android/API, isStaApConcurrencySupported,
isStaConcurrencyForLocalOnlyConnectionsSupported, multi-Internet and
bridged-AP concurrency, Wi-Fi Direct / Aware features, bands, WPA3; every
Network (transports, INTERNET/validated, iface, addresses, DNS, default);
IPv4 interfaces; both links with socket binding ("client socket a:b ->
c:d (bound to network N)", "host socket ... (accepted on hotspot SSID)");
last hotspot error; relay counters and history. COPY RELAY DIAG = all of
it + last 80 log lines.

### 3.3 What can go wrong, and how it will show

- B's hotspot refuses while joined: `hotspot failed, reason 2
  (incompatible mode ...)` in the log, `last hotspot error` in the diag.
- Android drops the upstream when the hotspot starts:
  `requestNetwork.onLost` on B, `UP: DOWN ...`.
- Either way the Relay Lab keeps working and the diag records it. Section
  18 of TESTING.md asks Mike to try both orders (upstream first, hotspot
  first).

If the topology fails, the Android-native alternative to investigate next
is Wi-Fi Direct group owner + STA (widely supported concurrently); the
probe already reports the P2P feature. Not built, as instructed.

## 4. Part C - source discovery

Relay Lab -> SCAN WI-FI: SSID, BSSID, dBm, security (from capabilities:
open / WEP / WPA / WPA2 / WPA3), time; tap to classify locally
(authorized / public-open / captive / unknown / do not use; stored per
BSSID in app-private prefs). "shareable" appears only for the two
redistributable classes. No passwords, no connection attempts, no upload.

## 5. Tests and build

100 tests: 87 from v0.8 + CoverageTest 9 + RelayTest 3 + ProductStateTest
+1 (coverage words). All green, APK gated. Build 13, versionName 0.9.0,
1.16 MB, SHA256
`299e2620358548bdf1c5565ed4f150c910305269b4393947a2385b540e274923`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.9.0
Commit `b7e653bd4b3910d03afb4ed1f2664076616ac000` on `main`; this report on top.

## 6. Preserved

Direct buy/sell (v0.7.1 path) is untouched: when no relay is involved,
`relay.providerShort` / `relayedBuyer` are null and every send goes through
`wifi.sendTunnel` exactly as before. Same database, identity, packages.
v0.8 consumer screens unchanged except the two lines above.

## 7. Status table

| Piece | Status |
|---|---|
| Coverage planner, zones, economic ceiling | Implemented, Simulated (JVM) |
| Sealed relay frames, introductions | Implemented, tested on JVM |
| Two Wi-Fi links on one phone | Implemented, hardware UNPROVEN |
| A -> B -> C live browsing | Implemented, hardware UNPROVEN |
| Relay counters, probe, COPY RELAY DIAG | Implemented |
| Wi-Fi scan + trust classification | Implemented (local only) |
| Citywide dispatch, relay rewards, source database | Future |

## 8. Retest

`docs/TESTING.md` section 18 with its checklist. The result Mike sends
back decides whether v1.0 relay work continues on this topology or moves
to Wi-Fi Direct.
