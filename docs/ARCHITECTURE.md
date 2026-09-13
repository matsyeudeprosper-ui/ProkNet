# ProkNet Lab - Architecture

## Goal of v0.1 (kept as the base of every later milestone)

Phone A <---- offline local connection ----> Phone B

Both phones exchange a text message with mobile data and Wi-Fi disabled.
Everything in this milestone exists only to prove that link and to make it
debuggable from the phone itself.

## Transport choice: BLE GATT

BLE was chosen over Wi-Fi Direct for the first proof because:

- it needs no user pairing dialog, no group owner negotiation, no IP layer
- it works on every Android phone since 5.0, with the app in the foreground
- it gives us both halves we need later for store-carry-forward:
  a cheap always-on beacon (advertising) and a short connection for data
- failure modes are well known (status 133, MTU quirks) and loggable

Cost: BLE is slow (a few KB/s) and one packet is capped at 512 bytes. That is
fine for v0.1 and will be revisited when bulk transfer matters (Wi-Fi P2P
milestone).

Every phone plays **both roles at once**:

| Role | Android API | ProkNet class |
|---|---|---|
| Be discoverable | `BluetoothLeAdvertiser` | `ble/BleAdvertiser.kt` |
| Accept messages | `BluetoothGattServer` | `ble/GattServerNode.kt` |
| Find peers | `BluetoothLeScanner` | `ble/BleScanner.kt` |
| Deliver a message | `BluetoothGatt` client | `ble/BleSender.kt` |

`ble/ProkNetNode.kt` owns all four and is the only object the UI talks to.
Since v0.3 the node is created once per process by `ProkNetApp` and started
and stopped by `service/ProkNetService.kt`, never by the Activity.

## Over-the-air format

**Advertisement** (31 bytes): flags + 128-bit ProkNet service UUID
`7a0c0001-9b1e-4c5a-8d7f-0b6e2f3a4c5d`. Scanners filter on this UUID, so
non-ProkNet devices are never listed.

**Scan response**: manufacturer data, company ID `0xFFFF` (reserved by the
Bluetooth SIG for testing). Since v0.4 the payload is `[advVersion=2][fullId x16]`
(21 bytes with headers, fits the 31-byte limit), so every phone learns the
full 16-byte ID of every phone it sees and can address packets to it without
a GATT read. v1 peers (`[1][shortId x4]`) are still understood. Peers are
keyed by short ID (first 4 bytes), not by MAC address, because Android
rotates BLE addresses.

**GATT service** `7a0c0001-...`:

| Characteristic | UUID | Props | Purpose |
|---|---|---|---|
| IDENTITY | `7a0c0002-...` | read | `idHex|displayName` UTF-8 |
| INBOX | `7a0c0003-...` | write (with response) | one encoded Packet per write |
| RECEIPT | `7a0c0004-...` | read | v0.2: `[ver=1][status][msgId x8]` for the last packet this central wrote |

**Packet v3** (`core/Packet.kt`, since v0.4.1), big-endian, max 512 bytes:

```
0   2  magic "PK"
2   1  version = 3
3   1  type    = 1 (TEXT)
4  16  origin ID       (who wrote it; never changes on relay)
20 16  destination ID  (final recipient; never changes on relay)
36 16  last-hop ID     (the phone transmitting THIS hop; every transmitter stamps itself)
52  8  message ID      (random, chosen by the origin; never changes)
60  8  timestamp ms    (origin's clock)
68  1  TTL             (max custody transfers, default 3)
69  1  hops            (custody transfers so far; a relay adds 1 when forwarding)
70  2  text length N
72  N  text UTF-8      (N <= 440)
```

A -> B: last hop = A. B -> C: last hop = B. C displays "from A via B" from
these two fields alone. **No Bluetooth MAC address is used anywhere in
routing or accounting** (Android rotates them, and the address a GATT server
sees is not reliably the one the scanner saw). The transport address appears
in the log only as a debugging aid.

(origin, message ID) is the global identity of a message. Every phone keeps
a unique index on it per direction, so a retry, a duplicate handoff or a
second route can never store a second copy. A destination whose last 12
bytes are zero is matched on the short ID only (used when a peer's full ID
is unknown). Decoding is strict (exact length, known version and type, any
exception -> null) and never throws. v2 packets (no last hop) and v1 packets
(no destination, treated as addressed to the receiver) still decode.
The sender requests MTU 517 so a full packet fits in one write; when the peer
grants less, Android automatically uses the GATT long-write procedure and the
server reassembles the prepared-write chunks.

## Send sequence (one delivery attempt)

```
connectGatt(TRANSPORT_LE)
  -> CONNECTED -> requestMtu(517)   (3 s fallback if no callback)
  -> onMtuChanged -> discoverServices()
  -> onServicesDiscovered -> find INBOX -> writeCharacteristic(packet)
  -> onCharacteristicWrite(GATT_SUCCESS)      = Android says the write landed
  -> readCharacteristic(RECEIPT)              (v0.2)
  -> onCharacteristicRead: status + msgId     = the PEER says what it did with it
  -> disconnect + close
```

One attempt at a time, 20 s timeout, one automatic transport retry inside the
attempt. Every step is written to the in-app log with the raw status code and
a hint.

## Delivery receipt (milestone 2A)

A BLE write acknowledgement only proves the bytes reached the other phone's
Bluetooth stack. It does not prove the app parsed and stored them. So the
receiver keeps, per connected central, a receipt for the last packet it
handled, and the sender reads it right after the write:

| Receipt status | Receiver did | Sender marks |
|---|---|---|
| 1 ACCEPTED | it is the destination and stored the message | delivered (final) |
| 3 ACCEPTED_RELAY | it is NOT the destination and took custody (v0.4) | handed_off (NOT final) |
| 2 DUPLICATE | already had this (origin, message ID) as received or carried | delivered / handed_off, matching the attempt |
| 0 REJECTED | did not parse, TTL exhausted, or already relayed once | failed (direct) / keep looking for another relay (handoff) |
| missing / wrong msgId | old app version or a race | not delivered, retry later |

A hop receipt means "the relay accepted custody", never "the destination has
it". The UI keeps the two apart: `handed_off` on the origin, `carrying` /
`forwarded` on the relay, `received ... via` on the destination.

The receipt is filled BEFORE the write response is sent, so the read that
follows can never see a stale value. It is cleared on disconnect.

## Delivery queue (milestone 2A)

`ble/DeliveryQueue.kt` is the smallest possible STORE -> FORWARD, for this
phone's own outgoing messages only. State machine per outgoing message,
persisted in SQLite (`messages.status`, `attempts`, `next_attempt`,
`last_error`, `delivered_at`):

```
        enqueue                attempt starts
  ----------------> pending --------------------> sending
                     ^  ^                           |
     backoff elapsed |  | transport failed /        | receipt ACCEPTED / DUPLICATE
     or peer         |  | no receipt                v
     reappeared      |  +------------------------ delivered
                     |
                     +-- 50 attempts or receipt REJECTED --> failed
                     +-- older than 48 h -------------------> expired
```

- `pump()` runs on: enqueue, every scanner update, a 10 s tick, and after each
  attempt. It picks the oldest pending message whose peer is in range and whose
  backoff has elapsed. One attempt in flight at a time.
- Backoff after a failed attempt: 5 s doubling to a 60 s cap.
- **Peer reappears** (was out of the scanner's list, now back): backoff for its
  messages is cleared and a pump runs immediately. This is the "B comes back,
  A delivers by itself" path.
- On start, any message stuck in `sending` from a crash goes back to `pending`.
- The **Retry** button clears every backoff and pumps.
- Retries re-encode the SAME message ID and timestamp, so a retry can never
  create a second copy on the receiver.

## One-relay STORE -> CARRY -> FORWARD (milestone 2C1)

```
A (origin)                 B (relay)                     C (destination)
pending for C
  C not in range,
  B in range  --packet(dest=C,hops=0)-->  not for me, hops 0 < TTL,
                                          unknown (origin,msgId):
  <-- receipt ACCEPTED_RELAY --           store as CARRYING     [ACCEPTED FOR RELAY]
handed_off via B                          ...B walks...
(A is done; NOT final)                    C appears           [DESTINATION SEEN]
                                          --packet(hops=1)-->  for me: store as
                                                               received from A via B
                                          <-- receipt ACCEPTED  [FINAL RECEIVED]
                                          forwarded (custody complete)
```

Rules, all in `DeliveryQueue.pump()` and `ProkNetNode.onPacketReceived()`:

- The origin sends **direct** whenever the destination is in range. Only when
  it is not, and some other peer is, does it hand off, to the strongest one.
  One handoff: after `handed_off` the origin stops trying.
- A relay forwards a carried packet **only to its exact destination**. It never
  offers it to another phone, so there is no flooding.
- A relay refuses custody when TTL is exhausted or when the packet already
  has hops >= 1 (one relay per message in 2C1). It answers REJECTED; the
  origin keeps the message pending and may try another relay later.
- Duplicate detection on every phone is (origin, message ID) across received,
  carried and forwarded rows; the receipt says DUPLICATE and the sender treats
  it as success for that step.
- Carried packets share the 48 h expiry and the backoff/attempt rules of own
  messages. Carrying survives restarts (SQLite, `forwarding` -> `carrying`
  on start).
- Log vocabulary: `ACCEPTED FOR RELAY`, `CARRYING`, `DESTINATION SEEN`,
  `FORWARDING`, `FORWARDED`, `HANDING OFF`, `HANDED OFF`, `FINAL RECEIVED`.
- Robustness (v0.4.1): a packet handler exception answers REJECTED instead of
  killing the Bluetooth thread; a malformed scan record is ignored; a phone
  whose chipset refuses the 16-byte scan response falls back to the 4-byte
  short ID and is then addressed in short-ID form; devices seen without any
  scan response are listed as "(no ID yet)" and cannot be selected as a
  destination; a corrupt stored row is marked failed instead of crashing the
  queue.

Known peers (`peers` table: short ID, label, last seen, last address, full ID) are shown
in the list as "NOT IN RANGE" so a message can be queued for a phone that is
currently off. Address at send time always comes from the live scan result,
never from the table, because Android rotates BLE addresses.

## Background operation (milestone 2B)

```
ProkNetApp (Application)  ----creates----> ProkNetNode (one per process)
       |                                        ^         ^
ProkNetService (foreground) --start/stop--------+         | addListener / removeListener
       |  persistent notification, screen on/off log,     |
       |  wake lock during delivery attempts              |
MainActivity (a window) ---------observes only------------+
```

- **Start** in the UI calls `startForegroundService`. The service calls
  `startForeground` with type `connectedDevice` (required on Android 14 for
  Bluetooth work), then `node.start()`. `START_STICKY`, `stopWithTask=false`:
  Android restarts it if it kills the process, and swiping the app away does
  not stop it.
- **Stop** (button or the notification's "Stop ProkNet" action) sends the
  STOP action: `node.stop()`, notification removed, service stops.
- The Activity attaches as a listener in `onStart` and detaches in `onStop`.
  It never starts or stops the node. Closing it logs
  `activity hidden (background) - node continues in the service`.
- A `BroadcastReceiver` in the service logs `SCREEN OFF - node keeps running`
  with the live status line, so screen-off continuity is visible in the log.
- `DeliveryQueue` holds a partial wake lock for the duration of one delivery
  attempt (max ~45 s). BLE callbacks wake the CPU on their own, but the
  connect/MTU/discover/write/read chain has gaps where a dozing CPU could
  stretch the 20 s timeout.
- Why BLE keeps working with the screen off: Android only drops screen-off
  scans that have no `ScanFilter`; ProkNet always filters on its service UUID.
- **Battery** button opens the system dialog to exempt the app from battery
  optimisation. Not required on stock Android; needed on phones whose vendor
  kills background services (Xiaomi, Huawei, Oppo, some Samsung profiles).
  The service logs the exemption state at every start.

Still no third-party carrying: the service keeps THIS phone's node alive; the
queue still only holds this phone's own messages.

## Routing decisions are pure functions (v0.4.1)

`core/Routing.kt` contains every decision and nothing else: no Android, no
BLE, no database.

| Function | Answers |
|---|---|
| `decideReceive(packet, myId, alreadyKnown)` | FINAL / RELAY / DUPLICATE / REJECT_TTL / REJECT_ALREADY_RELAYED / REJECT_MALFORMED |
| `receiptFor(decision)`, `resultFor(receiptCode)` | receipt code <-> DeliveryResult mapping used by both ends |
| `plan(carrying, pending, inRange, now)` | the next attempt: FORWARD (carried, destination present), DIRECT, or HANDOFF to the strongest other peer; null if nothing can be done |
| `applyResult(kind, result, attemptNo, peer)` | the message's next state, backoff and log line |
| `outgoingPacket(...)` | the bytes to transmit: identity untouched, last hop = me, hops+1 when forwarding |

`ble/DeliveryQueue.kt` and `ble/ProkNetNode.kt` only feed facts in and
execute what comes out. `app/src/test/java/net/prok/proknet/core/` holds 23
JUnit tests over these functions and the packet format, including a full
A -> B -> C simulation with three in-memory phones. `build.ps1` runs them
before assembling; a failure means no APK.

## Identity (v0.5: cryptographic)

`core/Identity.kt` + `core/Crypto.kt`. One P-256 (secp256r1) key pair per
installation, generated with the platform `KeyPairGenerator` and stored as
PKCS#8 in the app's private SharedPreferences (MODE_PRIVATE: no other app can
read it; it is never exported, logged or shown). The ProkNet ID is
`SHA-256(X||Y)[0..16]`, so the 16-byte ID format, short IDs, scan responses
and routing are unchanged, and an ID is now a claim only its key holder can
back. Fingerprint = 32 hex chars of the same hash, shown in the UI.

Public keys travel in the **identity record** `[2][id 16][pub 64][name]`,
readable over the BLE IDENTITY characteristic and exchanged in the Wi-Fi
handshake. A record is accepted only if its ID really derives from its key
and the key is a valid curve point (invalid-curve attacks are rejected).
Keys are learned automatically: on first sight of a peer the node reads its
record (one short BLE connection), and every later send reads it on the same
connection if still unknown. Learned keys live in the `peer_keys` table.

Migration: an install upgraded from v0.4 gets a new key-derived ID; the old
random ID is kept as `legacy_id_hex`. Other phones' peer lists will show the
old ID as "not in range" until it ages out.

Why P-256 and not Curve25519: on Android API 26-32 `X25519`/`Ed25519` are
not guaranteed in JCA; P-256 ECDH and ECDSA are, on every version and on the
VPS JVM where the tests run.

## End-to-end encryption (v0.5)

Packet v4 gained an opaque payload and a type. Type 2 = ENVELOPE:

```
plaintext = [sigLen 1][ECDSA-SHA256 sig by ORIGIN][kind 1][body]
              sig covers aad || kind || body
envelope  = [ephemeral P-256 pub 64][nonce 12][AES-256-GCM(plaintext) + tag 16]
              key = HKDF-SHA256(ECDH(ephemeral, destination static), salt "ProkNet-v5", info = ephPub || destPub || aad)
aad       = origin(16) || dest(16) || msgId(8) || timestamp(8) || type(1)   (the immutable header)
```

Properties, each with a JVM test in `CryptoTest`:

| Property | Mechanism |
|---|---|
| only the destination can read | ECDH with the destination's static key |
| sender authenticated | ECDSA signature inside the envelope, checked against the origin's learned key |
| tamper-evident | GCM tag; the routing header is associated data, so changing origin/dest/msgId/ts/type breaks decryption |
| relay learns nothing | payload is opaque bytes; lastHop and hops are outside the aad and are the only fields a relay changes |
| replay / duplicate | (origin, message ID) index as before |
| forward secrecy per message | fresh ephemeral key and nonce per envelope |

Kinds inside the plaintext: TEXT (a message), CONTROL (Wi-Fi negotiation),
BLOB (a large payload body). A phone sends nothing in plaintext any more;
v1-v3 plaintext packets are still accepted and shown as `PLAIN`.

To encrypt for C, A must hold C's key, learned during any earlier BLE contact.
If the key is unknown the send is refused with a clear log line (no
plaintext fallback). A message that does not decrypt, or whose signature
fails, is REJECTED at the receiver.

## Transport abstraction (v0.5)

```
transport/Transport.kt     interface: start/stop, canReach(peer), bulkCapable, sendBatch(frames), stats
transport/BleTransport.kt  advertise + scan + GATT server + GATT client; identity record reads
transport/WifiTransport.kt local-only hotspot + TCP link with signed handshake
```

`DeliveryQueue`, `TransferEngine` and `ProkNetNode` never touch Bluetooth or
sockets. Routing picks the transport per frame with
`Routing.chooseTransport`: Wi-Fi whenever its link to that peer is up, else
BLE. Both transports answer every frame with the same receipt codes.

## Wi-Fi link (v0.5)

Mechanism: **LocalOnlyHotspot + WifiNetworkSpecifier**, not Wi-Fi Direct.

```
initiator (wants bulk)                          host
   --BLE ENVELOPE(CONTROL WIFI_REQUEST)-->
                                                startLocalOnlyHotspot() -> SSID, passphrase, AP address
                                                ServerSocket :47741
   <--BLE ENVELOPE(CONTROL WIFI_OFFER ssid/pass/ips/port)--
   requestNetwork(WifiNetworkSpecifier)          (Android 10+: one system dialog "connect to device?")
   TCP connect via that Network
   HELLO(identity record + nonce)  <->  HELLO
   AUTH(sig over both IDs + both nonces) <-> AUTH     both keys verified -> LINK UP
   frames [u32 len][type][payload], PACKET answered by RECEIPT
```

- Decisions live in `core/LinkState.kt` (pure, tested): roles, tie-break when
  both request (lower ID hosts), step timeouts (45 s), retry backoff after
  failures (5 s doubling to 60 s), loss handling.
- Credentials are only ever inside an end-to-end encrypted control message.
- The link is bound to the peer's identity by the handshake; addresses are
  transport details.
- Why not Wi-Fi Direct: group formation is slower and flakier across vendors,
  needs an invitation dialog too, and yields the same thing in the end (an IP
  link). A hotspot gives a plain TCP socket to a known host address, which is
  exactly what an Internet tunnel needs next (the future tunnel is just
  another frame type on this socket).
- Cost: on Android 10+ the joining phone sees one system dialog per link
  set-up; the host needs Wi-Fi on and Location on (system requirement for
  hotspots on most versions). Android 8-9 use the legacy `WifiConfiguration`
  join without a dialog.

## Large payloads (v0.5)

`core/Transfer.kt` (pure) + `node/TransferEngine.kt`. The whole payload
(`[type][sha256][name][data]`) is signed and encrypted ONCE as a blob, then
cut into 400-byte chunks `[tid 8][index][count][total][data]` that fit a v4
CHUNK packet on any transport. The receiver writes chunks by index into a
file, persists the received mask per chunk (restart-safe), and when complete
opens the envelope, verifies the origin's signature and the SHA-256, and
saves the data under `files/received/`. Chunks are direct only (never
relayed). Transfers above 4 KB ask for a Wi-Fi link first and fall back to
BLE after 45 s. States: pending, sending (with %), delivered, failed /
receiving, received, failed. Text longer than one packet automatically
becomes a transfer.

## Automated tests (48)

`app/src/test`: PacketTest 11, RoutingTest 16, CryptoTest 7, TransferTest 6,
WireTest 4, LinkStateTest 4. `build.ps1` runs them first and refuses the APK
on any failure.

## Storage

`core/MessageStore.kt`: SQLite via `SQLiteOpenHelper`, tables `messages`
(with the v0.2 queue columns) and `peers`. Schema v1 -> v2 migration keeps
existing messages and maps the old `sent` status to `delivered`. No Room, no
annotation processing, so the build stays small and fast.

`core/DiagLog.kt`: ring buffer of the last 600 log lines, mirrored to logcat
and to `files/proknet-log.txt`. The UI shows it live; Copy puts the full
diagnostic (device, identity, status, peers, log) on the clipboard; Share
sends it to any app (WhatsApp, email, Telegram).

## Why Kotlin only, no Python yet

The project direction is Python for protocol / business logic and a thin
Kotlin layer for Android APIs. In v0.1 the "protocol" is 80 lines (packet
encode/decode) and everything else is Android BLE plumbing that must stay
native. Embedding Python (Chaquopy or similar) now would add a large
dependency, a slower build on the shared VPS and a second runtime to debug,
for no benefit yet. The boundary is prepared: `core/Packet.kt` and the
routing logic that comes with store-carry-forward are the pieces that can
move to Python once there is real protocol logic to host. This is flagged as a
decision for the architect in `CLAUDE_REPORT.md`.

## Why no AndroidX

Plain `android.app.Activity` and stock widgets. No AppCompat, Material or
ConstraintLayout. This keeps the dependency graph to the Kotlin stdlib,
which matters on a 12 GB VPS shared with live services and keeps the APK
small. UI polish is explicitly out of scope.

## Build toolchain on the VPS

Installed once under `C:\Android` so nothing else on the box is touched:

| Component | Location | Source |
|---|---|---|
| Temurin JDK 17.0.20 | `C:\Android\jdk17` | api.adoptium.net zip |
| Android cmdline-tools | `C:\Android\sdk\cmdline-tools\latest` | dl.google.com |
| platforms;android-34, build-tools;34.0.0 | `C:\Android\sdk` | `sdkmanager` |
| Gradle 8.7 | `%USERPROFILE%\.gradle\wrapper\dists` | Gradle wrapper |
| AGP 8.5.2, Kotlin 1.9.24 | Gradle cache | Maven (google, central) |

`gradle.properties` caps the build JVM at 1.5 GB and runs the Kotlin compiler
in-process; `build.ps1` runs Gradle with `--no-daemon` so no JVM lingers on
the box after a build, and refuses to build with under 1 GB free disk.

## What is deliberately NOT here

Multi-hop routing (more than one relay, route choice, flooding), Internet
tunnelling over the Wi-Fi link (next), wallet or payments. Relayed messages
are encrypted; relayed CHUNKS are not supported yet (direct only).
