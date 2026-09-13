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

**Packet v2** (`core/Packet.kt`, since v0.4), big-endian, max 512 bytes:

```
0   2  magic "PK"
2   1  version = 2
3   1  type    = 1 (TEXT)
4  16  origin ID       (who wrote it; never changes on relay)
20 16  destination ID  (final recipient; never changes on relay)
36  8  message ID      (random, chosen by the origin; never changes)
44  8  timestamp ms    (origin's clock)
52  1  TTL             (max custody transfers, default 3)
53  1  hops            (custody transfers so far; a relay adds 1 when forwarding)
54  2  text length N
56  N  text UTF-8      (N <= 456)
```

(origin, message ID) is the global identity of a message. Every phone keeps
a unique index on it per direction, so a retry, a duplicate handoff or a
second route can never store a second copy. A destination whose last 12
bytes are zero is matched on the short ID only (used when a peer's full ID
is unknown). v1 packets still decode and are treated as addressed to the
receiver.
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

## Identity

`core/Identity.kt`: 16 random bytes from `SecureRandom`, generated once,
stored in SharedPreferences. Display name is local only (default
`prok-<first 8 hex>`). This is a placeholder for a real key pair in a later
milestone; the API surface is intentionally tiny so the swap is cheap.

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

Encryption, multi-hop routing (more than one relay, route choice, flooding),
Wi-Fi Direct, Internet sharing, wallet or payments.
