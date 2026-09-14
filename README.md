# ProkNet

ProkNet is a decentralized communication network made from ordinary Android
phones. Core principle: **STORE -> CARRY -> FORWARD**. Phones discover each
other nearby, exchange data without Internet, carry packets for others while
people move, and forward them when another ProkNet phone is met.

```
PROK          commerce / services
  |
PROKNET       communication / connectivity network
  |-- BLE            (v0.1: this)
  |-- Wi-Fi P2P      (later)
  `-- Internet       (optional gateway, later)
```

This repository contains the **Prok** app (engine + consumer UI, with the
lab screen kept as a developer screen), built milestone by milestone.

## Milestones

| Version | Milestone | Status |
|---|---|---|
| v0.1.0 | Lab: BLE discovery + direct text message, Internet off | passed on two real phones (2026-09-12) |
| v0.2.0 | 2A: queued delayed delivery with delivery receipts | passed on two real phones (2026-09-13) |
| v0.3.0 | 2B: background operation via foreground service | passed on two real phones (2026-09-13) |
| v0.4.0 | 2C1: first one-relay STORE -> CARRY -> FORWARD | superseded by v0.4.1 before testing |
| v0.4.1 | 2C1 hardening: explicit last-hop ID, JVM routing tests gate the build | built, awaiting three-phone test |
| v0.5.0 | Secure Fast Link: key-bound identity, end-to-end encryption, transport abstraction, Wi-Fi link, large transfers | passed on two phones except the Wi-Fi link (2026-09-13) |
| v0.5.1 | Wi-Fi join fix: security-aware join, host address from DHCP, approval banner, phase line | passed: WIFI UP, signed handshake, 1 MB over Wi-Fi (2026-09-13) |
| v0.6.0 | Internet through another phone: provider/buyer roles, tunnel over the Wi-Fi link, VpnService client, gateway | phone test: session dropped at the first tunnel frame (main-thread socket write) |
| v0.6.1 | Fix: link writes on a dedicated writer thread (core/LinkIo), real exception text, loopback link tests, copy buttons at the top | passed on two phones: A browses HTTPS through B with its own data OFF (2026-09-13) |
| v0.7.0 | Connectivity Marketplace: BUY / SELL / RELAY, offers with price in the BLE scan, signed contract, signed usage checkpoints, exact CFA pricing + Prok fee, ledger, history | phone test: first CONTRACT_PROPOSE misrouted on the seller, buyer timed out |
| v0.7.1 | Fix: tunnel frames routed by direction and role (pure, tested), regression test for the first-frame sequence | passed on two phones: mutually signed settlement, 11.47 MB -> 57.35 CFA (2026-09-13) |
| v0.8.0 | Consumer product UI: Home / Internet / Earn / Activity / Profile, plain words, one state-translation layer (ProductState); the whole lab screen kept under Profile > Developer | approved by the architect (2026-09-14) |
| v0.9.0 | Coverage engine foundation (pure planner, zones, economic ceiling) + 3-phone live relay feasibility (second Wi-Fi link, end-to-end sealed relay frames, Relay Lab probe, Wi-Fi source discovery) | 3-phone test: radio topology WORKS (STA+AP concurrency, both links up), but the buyer was never introduced |
| v0.9.1 | Relay introduction handshake: the buyer asks (idempotent, repeated), the relay always answers, both sides acknowledge; stale-link recovery; honest failure wording | built, 107 JVM tests pass, awaiting the 3-phone retest |
| v0.9.2 | Consumer app in FRENCH (first market), sharing refusal says what is really wrong, a failed buy attempt no longer blocks the next SELL | built, 108 JVM tests pass |
| v0.9.3 | Link setup fails fast with a real reason: per-step timeouts, the host says at once when it cannot create the hotspot, the buyer screen says what to check, the seller screen warns about Wi-Fi / Location | phone diag: the seller received the request and answered nothing |
| v0.9.4 | A phone asked to host ALWAYS answers: a stale or idle link is dropped for the new customer, a real refusal is sent back with its reason (busy / no hotspot), and a host that gives up tells the waiting phone | phone diag: works, the refusal arrives in 2 s; the provider cannot create its hotspot |
| v0.9.5 | The refusal carries the provider's own Android error, so the buyer names the single thing to change; Location pre-check and one hotspot retry on the provider; an explicit refusal no longer starts a retry backoff | phone test: seller on Wi-Fi refused, same seller on mobile data works |
| v0.9.6 | Sharing capability probe: a seller on Wi-Fi tests its own hotspot once per network before any customer, remembers the answer with the band, and the coverage engine stops planning Wi-Fi resale through that phone while keeping the network as an observed source | built, 114 JVM tests pass |
| v0.9.7 | Wi-Fi Direct experiment (method B) behind the developer screens: form a P2P group while the seller stays on its home router, adopt the P2P socket into the existing authenticated link, COPY P2P DIAG. Method A untouched | built, 119 JVM tests pass; **not proven on phones** |

### v0.1 - what it does

Two Android phones with mobile data and Wi-Fi OFF can:

- generate a persistent unique ProkNet identity (16 random bytes, stored locally)
- discover each other over Bluetooth Low Energy (BLE)
- show nearby ProkNet devices in a list
- select one and send a short text message phone-to-phone
- store sent and received messages locally (SQLite)
- show discovery / connection / debug status on screen
- show a full internal log, with **Copy log** and **Share** buttons, so testing
  needs no ADB, no PC and no Android Studio

### v0.2 (milestone 2A) - queued delayed delivery

- A message to a phone that is not in range is stored as **pending** and
  delivered automatically when that phone is seen again. No second Send.
- Message states: `pending`, `sending`, `delivered`, `failed`, `expired`.
- Pending messages survive app restart (SQLite queue).
- **Delivery receipt**: the receiver confirms it stored the message; a bare
  BLE write acknowledgement no longer counts as delivered.
- Duplicate delivery is impossible: message IDs are unique on both sides.
- Known peers stay in the list marked "NOT IN RANGE" so you can queue for them.
- **Retry** button clears backoff and tries everything pending now.

### v0.3 (milestone 2B) - background operation

- A **foreground service** with a persistent notification owns the node.
  Start starts the service; Stop (button or notification action) stops it.
- The screen (Activity) is only a window: close it, reopen it, swipe the app
  away, the node keeps advertising, scanning, receiving and delivering.
- Screen off does not stop anything. A short wake lock guards each delivery.
- UI shows `Service: RUNNING / STOPPED` and a **Battery** button to ask
  Android to exempt ProkNet from battery optimisation (OEM background killers).
- Log lines for service start/stop, activity foreground/background, screen
  on/off, task swiped away, battery-exemption state.

### v0.4 (milestone 2C1) - one relay: STORE -> CARRY -> FORWARD

- Packet v3 carries origin ID, **destination ID**, **last-hop ID** (who
  transmitted this hop), message ID, TTL and hop count. Relay identity comes
  from the packet, never from a Bluetooth address.
- A wants to reach C but only B is nearby: A **hands off** the packet to B.
  B answers with a custody receipt; A shows `[handed_off via prok-B, not final]`.
- B stores it as `~ carrying prok-A -> prok-C [carrying]` and offers it to
  nobody except C. When B meets C, B forwards it automatically.
- C stores it as a message **from A** (`via prok-B, 1 hop`), exactly once.
- Messages are identified by (origin, message ID) on every phone, so a retry
  or a second route can never create a second copy.
- One relay only, no flooding: a packet that already passed through a relay is
  refused by other relays. TTL and 48 h expiry bound everything.

### v0.5 - Secure Fast Link

- **Cryptographic identity.** Each install has a P-256 key pair; the ProkNet ID
  is derived from the public key. The private key never leaves the app.
- **End-to-end encryption.** Every message is signed by the origin and encrypted
  for the destination (ECDH + HKDF + AES-256-GCM). A relay sees only routing
  metadata and cannot read or alter the payload without detection.
- **Transport abstraction.** Routing talks to a `Transport` interface. BLE is
  one implementation; Wi-Fi is the second.
- **Wi-Fi link.** Local-only hotspot on one phone, joined by the other, TCP with a
  signed mutual handshake. Credentials travel inside an encrypted BLE message.
  No router, no Internet. One system "connect" dialog on the joining phone.
- **Large payloads.** Text of any length and files up to ~2 MB: encrypted once,
  cut into 400-byte chunks, sent over Wi-Fi when the link is up, else BLE.
  Integrity by GCM tag plus SHA-256; progress and retry states in the UI.
- Diagnostics: identity fingerprint, peer key status, BLE and Wi-Fi link
  state, active transport, bytes sent/received, transfer progress, real
  version number from the package.

### v0.6 - Internet through another phone

- **Provide Internet** on the phone that has mobile data or home Wi-Fi; it
  advertises the capability over BLE (`[NET]` in peer lists) and shows
  PROVIDER READY with its upstream type and validation state.
- **Use Internet** on the other phone: the Wi-Fi link comes up (or is reused),
  a session is opened with the peer authenticated on that link, Android asks
  once to allow the VPN, and from then on every app on the phone reaches the
  Internet through the provider. TCP and DNS are supported; UDP other than
  DNS is dropped (QUIC falls back to TCP).
- Tunnel: multiplexed frames with stream IDs on the existing authenticated
  TCP link; no per-frame receipts; long-lived streams; keepalives.
- Client: Android `VpnService` with a small user-space TCP/IP stack in Kotlin
  (no root, no NDK). Provider: real sockets bound to its Internet network,
  never to the hotspot.
- **Net test** button: DNS + real TLS + HTTPS GET through the provider with
  status code, latency and bytes, independent of the VPN.
- Usage accounting per session: bytes up/down, streams, DNS queries,
  duration, provider, disconnect reason (local only, no payments).

### v0.7 - Connectivity Marketplace

- **SELL**: price per MB (CFA), optional minimum session price and max MB.
  The offer (price, upstream type, validated, relay flag) rides in the BLE
  scan response, so buyers see it without connecting.
- **BUY**: pick a nearby offer (ranked: validated, cheaper, stronger), tap
  BUY, approve the unavoidable Android Wi-Fi and VPN prompts, browse.
- **Contract**: buyer proposes, seller accepts, both signatures stored on both
  phones. The tunnel session only starts under that contract's hash. Terms
  cannot change during a session.
- **Signed usage checkpoints** every 30 s / 1 MB: seller signs cumulative
  usage and cost, buyer verifies (sequence, non-decreasing, exact cost under
  the terms, within tolerance of its own counter) and countersigns. Final cost
  comes from the last mutually signed checkpoint on both phones.
- **Money math** is integer only (centimes), rounding half up, MB = 1,000,000
  bytes; Prok fee is a configurable percentage (default 5%).
- **Ledger**: buyer owes seller (gross), seller owes Prok (fee); mark paid /
  mark received / dispute / cancel; balances; session **History**.
- **RELAY**: advertised capability; forwarded-packet counts shown. Live
  Internet is still buyer -> seller -> Internet only.

Not here: Mobile Money, custody, automatic cashout, multi-hop Internet.

## Decided

`docs/DECISION_WIFI_SHARING.md`: a seller phone connected to a Wi-Fi router
often cannot create the hotspot a customer joins (Android
`ERROR_NO_CHANNEL`, no app-side band control). **Option B approved and
built in v0.9.6**: the phone tests itself once per network, says so in one
sentence, and the coverage engine stops planning Wi-Fi resale through it
while keeping the network as an observed candidate source. Wi-Fi Direct is
the next transport experiment, not started.

## Build (on the VPS)

Everything builds on the Windows VPS. No PC, no Android Studio.

```
powershell -ExecutionPolicy Bypass -File C:\Projects\ProkNet\build.ps1
```

The script runs the JVM unit tests first (`app/src/test`, pure Kotlin, no
Bluetooth, no emulator). If any test fails, **no APK is produced**. The test
summary is written to `dist\test-results.txt`.

Output APK (always the same path):

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```

Options: `-Clean` (full rebuild), `-Offline` (no dependency downloads).
The first build downloads Gradle and the Android Gradle plugin (a few hundred MB,
several minutes). Later builds take about one to three minutes.

Toolchain on the VPS (installed once): JDK 17 at `C:\Android\jdk17`, Android
SDK at `C:\Android\sdk` (platform 34, build-tools 34.0.0), Gradle 8.7 via the
wrapper. See `docs/ARCHITECTURE.md` for how they were installed.

## Getting the APK onto a phone

Each build is also published as a GitHub release asset of this repository
(`ProkNetLab-debug.apk`). Open the release page on the phone, download, and
install (allow "install unknown apps" for the browser when asked).

## Repository layout

```
app/                      Android app (Kotlin, no AndroidX, plain Activity)
  src/main/java/net/prok/proknet/
    core/                 Identity, Packet, MessageStore, Crypto, Routing, Tunnel, Market, ProductState (engine -> user words), DiagLog
                          Coverage (v0.9 pure planner), Relay (v0.9 sealed relay frames)
    node/                 TunnelClient (buyer), Gateway (seller), RelayNode + RelayProbe (v0.9), TransferEngine
    ble/                  Advertiser, GATT server, Scanner, Sender, DeliveryQueue, ProkNetNode
    service/              ProkNetService (foreground service owning the node)
    ui/                   MainActivity (v0.8 consumer screen), LabActivity (developer / diagnostics), RelayLabActivity (v0.9 relay experiment)
    ProkNetApp.kt         Application: creates the node once per process
build.ps1                 The build command
dist/                     Predictable APK output location (APK itself not committed)
docs/ARCHITECTURE.md      Design and technology choices
docs/TESTING.md           Step-by-step phone test procedure
CLAUDE_REPORT.md          Milestone report for the architect (ChatGPT)
```

## Roles

- ChatGPT: architect / product lead. Sets milestones, reviews reports.
- Claude Code: implementation engineer. Builds on the VPS, reports in `CLAUDE_REPORT.md`.
- Mike: tests on real phones, from the phone only.
