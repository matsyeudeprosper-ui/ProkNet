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
  both request (lower ID hosts), step timeouts (120 s), retry backoff after
  failures (5 s doubling to 60 s), loss handling.
- v0.5.1 join details, learned from the first phone test:
  - The host reads the hotspot's real security type (`SoftApConfiguration`:
    WPA2, WPA3-SAE, transition or open) and sends it in the offer. The client
    builds the matching `WifiNetworkSpecifier` (`setWpa2Passphrase` vs
    `setWpa3Passphrase`); for transition/unknown it tries WPA2 then WPA3.
    A WPA2 specifier never matches a WPA3-only hotspot: that is one way the
    request ends in `onUnavailable`.
  - The client no longer trusts offered IPs first. After `onAvailable` it
    reads the granted network's `LinkProperties`: the DHCP server address
    (Android 11+) and the default-route gateway ARE the hotspot phone. Offered
    IPs are the last resort. On the host, addresses of the phone's own Wi-Fi
    network are excluded before choosing candidates.
  - Android shows the "connect to device?" dialog only while the requesting
    app has a visible Activity. The transport waits for `appVisible()`
    before calling `requestNetwork`, the UI shows a banner, and the service
    posts a high-priority notification that opens the app.
  - Every `NetworkCallback` event is logged with capabilities, SSID, link
    addresses, routes and DHCP server; a scan diagnostic logs whether the
    hotspot SSID is visible from the client and on which frequency.
  - Phases for the UI: REQUESTING -> OFFERED -> JOINING -> TCP -> AUTH -> WIFI UP.
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

## Link I/O (v0.6.1)

`core/LinkIo.kt` owns the framing of one authenticated link: `[u32 len][type][payload]`,
a reader thread that dispatches frames, and a **writer thread fed by a bounded
queue (1024 frames)**. No caller ever writes to the socket on its own thread:
data threads enqueue with backpressure, the Android main thread enqueues
without blocking (a full queue drops the frame and logs it). `Handshake`
(HELLO/AUTH both ways, signatures verified) lives next to it. Both are pure
Kotlin and tested over real loopback sockets (`LinkIoTest`), including the
exact sequence that failed on the phones in v0.6.0: SESSION_START sent the
instant the link is up. Every failure reason now carries the exception
class, message and top stack frames (`LinkIo.describe`).

Why v0.6.0 failed: Android throws `NetworkOnMainThreadException` (message
null) for a socket write on the main thread. v0.5 only ever wrote from worker
threads; the tunnel's first frame was sent from the link-up callback on the
main thread, the write threw, and the link was torn down with the reason
"tunnel write failed: null".

## Internet through another phone (v0.6)

```
Phone A (buyer, no Internet)                         Phone B (provider, mobile data)
apps -> Android VpnService (TUN 10.8.0.2/24, DNS 10.8.0.1)
   -> TunnelClient: user-space TCP/DNS endpoints (core/TcpFlow.kt, core/Tcpip.kt)
   -> tunnel frames on the authenticated Wi-Fi TCP link  ==>  Gateway: real sockets on B's upstream Network
                                                                   (Network.socketFactory / bindSocket) -> Internet
```

**Tunnel protocol** (`core/Tunnel.kt`): link frame type 5 carries
`[type 1][stream id u32][data <= 16 KB]`. Types: SESSION_START/OK/END,
OPEN_TCP (host, port), TCP_OPEN_OK, TCP_DATA, TCP_CLOSE (half-close),
DNS_REQUEST/RESPONSE (raw DNS messages), ERROR (stream or session),
KEEPALIVE (15 s, 45 s timeout), UPSTREAM_STATE. Streams are multiplexed and
long-lived; the link's own TCP gives ordering, reliability and backpressure.
There are no per-frame receipts (the v0.5 chunk/receipt path is untouched
and still used for message transfers).

**Session binding**: SESSION_START carries the buyer's ID and is accepted
only if it equals the identity authenticated by the Wi-Fi handshake;
SESSION_OK carries the provider's ID and is accepted only if it equals the
link peer. Tunnel frames from any other peer are ignored.

**Client (A)**: `VpnService.Builder` with address 10.8.0.2/24, route
0.0.0.0/0, DNS 10.8.0.1, MTU 1500, blocking reads, ProkNet's own package
excluded (`addDisallowedApplication`) and the Wi-Fi link socket `protect()`ed
so the tunnel can never loop into itself. No IPv6 address or route, so apps
stay on IPv4. `TunnelClient` parses each packet: TCP -> one `TcpFlow` per
(src port, dst ip, dst port); UDP/53 -> DNS_REQUEST with the raw query, the
answer is wrapped back into a UDP/IP packet; other UDP is dropped (logged
once per port). `TcpFlow` plays the remote end towards the app: SYN ->
SYN-ACK (MSS 1360) + OPEN_TCP; app data -> ACK + TCP_DATA (buffered until
TCP_OPEN_OK); provider data -> PSH/ACK segments within the app's window,
retransmitted after 1 s without ACK progress; FIN/RST handled both ways;
idle flows reset after 5 min. Literal window (no scaling), no SACK, no
congestion control: the TUN is lossless. Everything is unit-tested on the JVM.

**Gateway (B)**: `Tunnel.chooseUpstream` picks a network with INTERNET
capability that is not the ProkNet link (Wi-Fi without Internet, or the
ap/swlan interface), preferring validated Wi-Fi, then validated cellular.
Every outbound socket is created from that `Network.socketFactory`; DNS
datagrams are `bindSocket`ed to it and sent to its DNS servers (fallback
8.8.8.8 / 1.1.1.1). One writer thread and one reader thread per stream;
`shutdownOutput` on TCP_CLOSE; all sockets closed on session end, link loss
or provider off. A `NetworkCallback` follows upstream changes and pushes
UPSTREAM_STATE to the buyer (INTERNET LOST / back).

**Capability flag**: the BLE scan response gained one byte after the full
ID (`[2][id 16][flags 1]`, 22 bytes with headers): bit 0 = providing Internet.

**States**: provider PROVIDER OFF / NO UPSTREAM / PROVIDER READY / TUNNEL UP /
INTERNET LOST; buyer DISCONNECTED / CONNECTING / TUNNEL UP / INTERNET OK /
INTERNET LOST. Accounting (`Tunnel.Accounting`): bytes up/down, streams,
DNS queries, start/end, duration, peer, disconnect reason; kept in memory
(last 20 sessions) on both sides.

**In-app test**: a loopback bridge turns one tunnel stream into a local
socket so the platform TLS stack can run a real handshake and HTTP GET to
example.com:443 through the provider; reports protocol, cipher, status
line, bytes and latency. It does not depend on the VPN.

**Not in v0.6**: UDP other than DNS, IPv6, ICMP (ping), relayed/multi-hop
Internet, any economics.

## Connectivity marketplace (v0.7)

All economics live in `core/Market.kt` (pure, tested); networking code only
carries bytes and calls it.

**Offer in the scan response**: `[2][id 16][flags 1][price u16]` (24 bytes
with headers). Flags: bit0 SELL (seller on and upstream present), bit1
RELAY, bit2 validated, bits 4-5 upstream type. Ranking
`score = validated*100 + (60 - 3*price, floor 0) + signal(0..40)`,
unavailable last, ties by ID: deterministic and tested.

**Contract** (v1 62 bytes, v2 84 bytes): version, session id (8, random by
the buyer), buyer id, seller id, price/MB, minimum price, max MB, fee %,
start time, and for v2 the economics below. Flow over
the tunnel: `CONTRACT_PROPOSE` (contract + buyer signature) ->
`CONTRACT_ACCEPT` (hash + seller signature) or `CONTRACT_REJECT`. The seller
accepts only its exact current terms, the buyer id of the authenticated
link peer, a fresh session id and a recent start time; if only min/max/fee
differ it answers with its real terms and the buyer re-proposes once, but
only at the advertised price. `SESSION_START` then carries the contract
hash and is refused for anything else. Both phones store contract and both
signatures (`sessions` table).

**Usage checkpoints** (45 bytes): session id, sequence, bytes up, bytes
down, cost in centimes, time, final flag. The seller issues one every 30 s
or 1 MB from its own counters, signed (`USAGE_CHECKPOINT`); the buyer
verifies the signature and `Market.validateCheckpoint` (sequence strictly
increasing, usage never decreasing, cost exactly the terms applied to the
bytes, claimed bytes within 64 KB + 10% of its own count, under max MB,
nothing after a final one), then countersigns (`USAGE_ACK`). Duplicates and
out-of-order checkpoints are rejected; a rejection is logged as a dispute.
Both phones store every checkpoint with both signatures. **Final cost** on
both phones = terms applied to the last mutually signed checkpoint (or the
minimum price if none): neither phone's private counter decides alone, and
a seller can lose at most the last unsigned interval.

**Ledger** (`ledger` table): entries with a content-hash id (idempotent),
session, payer, recipient, amount in centimes, reason, time, status
(pending / settled / disputed / cancelled), paid-at, received-at. A finished
session books `buyer -> seller: gross` and `seller -> prok-network: fee`.
Settlement is accounting only: MARK AS PAID (payer), MARK AS RECEIVED
(recipient), both -> settled; either may dispute; untouched entries may be
cancelled. Balances are computed over pending entries. No money is held.

**Fraud groundwork**: session ids single-use (checked against the store),
sequence must increase, duplicate receipts ignored, identities bound to the
Wi-Fi-authenticated link on both sides, price locked by the signed contract,
malformed / negative / overflow values rejected at decode (contracts,
checkpoints, offers, prices, fees).

**Frame routing (v0.7.1)**: `Tunnel.route(type, providing)` decides whether an
incoming tunnel frame goes to the seller Gateway or the buyer TunnelClient,
from the frame's direction (buyer->seller: CONTRACT_PROPOSE, SESSION_START,
OPEN_TCP, DNS_REQUEST, USAGE_ACK; seller->buyer: CONTRACT_ACCEPT/REJECT,
SESSION_OK, TCP_OPEN_OK, DNS_RESPONSE, UPSTREAM_STATE, USAGE_CHECKPOINT; the
rest both ways) and this phone's role only, never from whether a buyer is
already registered. v0.7.0 routed on "SESSION_START or known buyer", so the
first CONTRACT_PROPOSE on a fresh link went to the seller's own buyer client
and was never answered. A phone sells or buys on a link, not both.

**RELAY**: a flag in the offer plus counts of packets this phone actually
forwarded for others (existing carry-forward). No live multi-hop Internet.

## Consumer product UI (v0.8)

The engine under v0.7.1 is unchanged. v0.8 adds a second window onto the
same node and demotes the lab screen to a developer screen.

```
ProkNetNode (unchanged)  <--observes--  ui/MainActivity   consumer: Home / Internet / Earn / Activity / Profile
                         <--observes--  ui/LabActivity    developer: the v0.1-v0.7 lab screen, whole, under Profile > Developer
                                              ^
                       core/ProductState -----+  the ONE place engine states become user words (pure, tested)
```

- `core/ProductState` is the translation layer. Input: `buyerWanted`,
  Wi-Fi phase, link up, `TunnelClient.state`, VPN up, last error. Output:
  `Buyer.IDLE / FINDING / CONNECTING / SECURING / STARTING / ONLINE / LOST`
  with titles "Finding provider…", "Connecting…", "Securing connection…",
  "Starting Internet…", "You're online", "Connection lost", plus a hint
  ("Android will ask to join a network: tap CONNECT"). Seller side:
  `Seller.OFF / NO_INTERNET / AVAILABLE / SERVING / LOST` ("Waiting for your
  Internet", "You're sharing Internet", "Someone is using your Internet").
  Also words for numbers: `cfaShort` ("57 CFA"), `data` ("11.5 MB"),
  `duration`, `signalWord`, `upstreamWord` ("Mobile data"), price / minimum /
  limit lines, payment words, and `wallet(entries, me)` = to pay / to receive /
  Prok fees over pending ledger entries. Screens never read `Gateway.state`
  or `TunnelClient.state` directly for display.
- `MainActivity` (consumer): one layout, five sections in a `FrameLayout`,
  a hand-made bottom bar (no AndroidX, so no BottomNavigationView). The
  Internet tab picks its sub-screen from state on every refresh: sharing
  active > buyer active or lost > confirmation (an offer was tapped) > share
  setup > offer cards. A 2 s ticker refreshes counters while visible; node
  callbacks refresh immediately. Get Internet: offer cards from
  `node.offers()` (ranked by the engine) -> confirmation (price, minimum,
  limit, signal, upstream, fee) -> CONNECT = `node.buy(peer)` and the engine
  does Wi-Fi link -> contract -> tunnel -> VPN by itself; the VPN consent
  callback (`node.vpnRequested`) is set by whichever screen is in front.
  Share Internet: price / minimum charge / max data per customer ->
  `node.setSelling(true, ...)`. STOP = `node.stopInternet`, STOP SHARING =
  `node.setSelling(false)`. Earn: net earnings from the ledger (received
  minus fees owed to Prok), relay switch = `node.setRelay`. Activity:
  sessions from the store as cards; tap = detail dialog (date, data,
  duration, price, final cost, Prok fee, payment) with MARK AS PAID /
  RECEIVED / DISPUTE from the v0.7 settlement foundation. Wallet = accounting
  view only ("Prok does not hold your money").
- Start-up: the consumer screen starts the foreground service by itself when
  every permission is already granted; otherwise the first Get / Share tap
  walks the same permission -> notification -> Bluetooth flow as the lab
  screen, then runs the tapped action.
- Theme: `ProkTheme` on the platform Material theme, light and dark via
  `values-night`, rounded cards and 56 dp primary buttons as drawables.
  Nothing new in the manifest except the theme and `LabActivity`
  (`exported=false`).
- What normal users no longer see: GATT / BLE / WIFI_REQUEST / SESSION_START
  / checkpoint internals, raw IDs (only their own short Prok ID on Profile),
  logs, node start/stop buttons, routing controls. All of it is one tap away
  under Profile > Developer / Diagnostics, with COPY LOG and COPY DIAG.

## Long-term coverage model (LOCKED, v0.9)

ProkNet is not only a nearby data-selling marketplace. Its job: find free
Internet whenever possible, otherwise the cheapest usable Internet, and
organize phones and people to extend it to where demand is.

```
INTERNET SOURCES      mobile data / home Wi-Fi / shop Wi-Fi / public Wi-Fi / Prok-funded / sponsored
        |
COVERAGE ENGINE       sources + nodes + radio links + demand  ->  ranked routes, zone status
        |
direct provider  /  passive relay  /  funded provider  /  mover (or courier already travelling)
        |
      BUYER           priority: FREE -> CHEAPEST -> RELIABLE BACKUP
```

Resources: PROVIDER (supplies Internet), ANCHOR (stable phone/location),
RELAY (forwards), MOVER (moves to close a gap), COURIER (already travelling
a useful route). The engine prefers the cheapest reliable delivered route,
never the most complicated mesh. A detected Wi-Fi network is NOT a source
until it has a permission class: OPEN_REUSABLE, AUTHORIZED_PRIVATE,
CAPTIVE_PORTAL, UNKNOWN, NOT_ALLOWED; only the first two may be shared.

Status legend used below: **Implemented** (code + JVM tests), **Simulated**
(synthetic data only), **Hardware proven** (passed on Mike's phones),
**Future** (design only).

## Coverage engine (v0.9) - Implemented, Simulated

`core/Coverage.kt`, pure. Concepts: `InternetSource` (type, trust class,
cost per MB, reliability), `RadioObservation` -> `CandidateLink` (quality =
success rate x signal x freshness), `CoverageNode` (zone, hasInternet,
source, canProvide/canRelay/canMove, stationary, charging, battery,
reliability, expected availability, minimum reward, move cost, heading
zone, fundable + activation cost), `DemandRequest` (buyer, zone, expected
MB, max price, kind COMMERCIAL / SPONSORED / GROWTH_SUBSIDY, budget),
`CoverageJob` (PROVIDE / RELAY / MOVE / ACTIVATE), `CandidateRoute`,
`RouteScore`, `CoverageZone` (GREEN / YELLOW / RED).

`Coverage.plan(demand, nodes, links, policy)` enumerates simple paths from
the buyer through relays to usable providers (and to fundable providers,
with an ACTIVATE job), plus buyer -> mover -> provider routes where a link
is missing (MOVE job), then scores each:

```
total = deliveredCost (source cost + relay/provider rewards + activation)
      + failurePenalty (pFail x (revenue + failureBase))
      + delayPenalty   (per relay hop, per move, per activation)
      + movementCost   (mover; a courier heading to the zone pays a share)
      + resourcePenalty (low battery +, charging -, stationary -, relay already in position -)
```

Every weight is a field of `Coverage.Policy`; nothing else hard-codes a
formula. Economic ceiling: COMMERCIAL routes must keep jobs <= revenue (a
50 CFA session cannot silently create 60 CFA of jobs); SPONSORED needs
jobs <= sponsor budget; GROWTH_SUBSIDY may lose money but the loss is an
explicit `subsidyNeeded` <= budget. Ranking: feasible first, lowest total,
fewer relays, then route id, so it is deterministic for any input order.
Zones: GREEN = feasible route now without movement/activation and low
failure risk; YELLOW = feasible with a mover or an activation, or blocked
only by money; RED otherwise. `ProductState.coverageWord` turns that into
"Internet available / Internet can be arranged / No connection available
yet" for the consumer screen; colours and jargon never reach it.

Not built (Future): a real city/GPS backend, dispatching jobs to phones,
paying movers, a shared Wi-Fi source database.

## Live relay (v0.9) - Implemented; hardware UNPROVEN

Goal: `A (buyer, data OFF) -> B (relay, data OFF) -> C (seller, data ON) ->
Internet`, with B forwarding but not reading the buyer's traffic.

- **Two Wi-Fi links on B.** `WifiTransport` now takes a name and a
  `mayHost` flag; the node runs two instances: `wifi` (the normal one, hosts
  A on its local-only hotspot) and `wifiUp` (client-only, joins C's hotspot
  through a `WifiNetworkSpecifier`). Both share the BLE control channel;
  `ProkNetNode.dispatchControl` hands a WIFI_OFFER to the instance that
  asked for it. Sockets stay explicitly separated: the upstream client
  socket is created from the granted `Network`'s socket factory; the
  downstream server socket accepts on the hotspot. Whether Android lets one
  phone hold a specifier STA connection and a local-only hotspot at the
  same time is exactly what the Relay Lab measures.
- **Sealed frames.** `core/Relay.kt`: `FRAME_RELAY = [ver][origin short
  4][nonce 12][AES-GCM(tunnel frame)]`, key = HKDF(ECDH(A static key, C
  static key), ids), aad = ver + origin. `FRAME_RELAY_INFO` carries B's
  introductions: to A "the seller behind me is C at this price" (C's
  self-certifying identity record), to C "the buyer behind me is A", and
  PEER_GONE. B never has the key; A and C verify each other's contract
  signatures as before. No forward secrecy yet (static keys), noted.
- **Introduction handshake (v0.9.1).** v0.9.0 introduced both sides once,
  unsolicited, at the instant the relay's second link came up. The 3-phone
  test showed why that fails: the buyer taps CONNECT minutes later and waits
  for an introduction that already happened. The handshake is now
  request/answer and every step is a pure function in `core/Relay`:
  `ROLE_INTRO_REQUEST` (buyer -> relay, carries the buyer's own record),
  `Relay.onIntroRequest(relayMode, downPeer, upPeer, sellerSelling, from)` ->
  INTRODUCE / NO_UPSTREAM / NOT_MY_BUYER / NOT_A_RELAY (a function of the
  relay's CURRENT state only, so asking late or twice is the same as asking
  first), `ROLE_NO_UPSTREAM` as an explicit negative answer, and
  `ROLE_INTRO_ACK` from each introduced side so the relay knows the far
  APPLICATION processed it (a successful socket write proves nothing). The
  buyer's ladder is `Relay.buyerStep(introduced, refused, attempts)` ->
  ASK / START_CONTRACT / NO_SELLER / GIVE_UP, with 5 attempts 2.5 s apart.
  Session lifecycle is `Relay.onLinks(...)` -> START / RESTART / END / KEEP /
  IDLE, so a link that drops clears the session and the same buyer coming
  back simply starts a new one. `LinkState.staleLinkRequest(peer)` closes the
  other half of that: a peer we still believe we are linked to, asking for a
  new link, means its side is gone, so the stale link is dropped and hosted
  again instead of letting the peer time out in REQUESTING.
- **Roles in `node/RelayNode.kt`.** B: relay mode on, session starts when
  both links are up, introductions sent, FRAME_RELAY forwarded to the other
  link, `Relay.Session` counts bytes and frames per direction, duration
  and disconnect reason (in memory + log + diag). A: the introduction
  sets `providerShort`; the tunnel client then proposes the contract to C,
  every frame sealed. C: the introduction sets `relayedBuyer`; sealed
  frames from A are opened and routed to the Gateway exactly as if A were
  on the link (`Gateway.Hooks.peerFullId(peerShort)` replaces the old
  link-peer lookup), and the Gateway's replies are sealed back. So C's
  session, checkpoints, ledger and history name A, not B.
- **Advertising.** B with a selling seller upstream advertises `SELL |
  VIA_RELAY` at C's price (new flag bit `Market.FLAG_VIA_RELAY`); the
  ranking puts a relayed offer just below a direct one at equal price. The
  consumer offer card says "through another phone".
- **What is not done on purpose:** no citywide routing protocol, no relay
  reward yet, no multi-buyer relay, no automatic relay selection.

## Relay Lab and Wi-Fi source discovery (v0.9) - Implemented

Developer screen -> Relay Lab (`ui/RelayLabActivity`, `node/RelayProbe`):
device model, Android/API, `isStaApConcurrencySupported`,
`isStaConcurrencyForLocalOnlyConnectionsSupported`, multi-Internet and
bridged-AP concurrency, Wi-Fi Direct / Aware features, bands, WPA3; every
`Network` with transports, INTERNET/validated, interface, addresses, DNS,
default flag; IPv4 interfaces; both ProkNet links with their socket
binding and hotspot state; relay session counters and history; COPY RELAY
DIAG (all of it + last 80 log lines). Wi-Fi scan through the normal
`WifiManager` scan API (Android-throttled; Location must be on): SSID,
BSSID, level, security from the capabilities string, timestamp. A tap
classifies the BSSID locally (SharedPreferences) with a `Coverage.Trust`
class. No passwords, no automatic connection, nothing uploaded.

## Money people understand (v0.14.0)

The product rule: the buyer says *"I have 50 CFA, get me Internet"*, the
seller says *"share my Internet and make me money"*, and ProkNet does the
arithmetic. Megabytes stay in the accounting, out of the product.

**The engine** (`core/Pricing.kt`, pure, integer centimes, no floating point
anywhere near money). Source kinds carry a cost policy: FREE_PUBLIC,
SPONSORED and PROK_FUNDED cost nothing; AUTHORIZED_HOME_WIFI /
AUTHORIZED_SHOP_WIFI cost what their owner declares (a fixed line's marginal
cost really is about zero); MOBILE_DATA costs what the bundle cost, derived
from "1 000 CFA for 2 Go" and never from a CFA/MB the seller had to compute;
UNKNOWN is not sellable at all. An undeclared mobile bundle is assumed to
cost something, never nothing, so silence cannot make a seller sell at a
loss.

From that: `sellerFloorPerMb = sourceCost + safety + earning`, where the
earning is the larger of a flat per-MB figure and a share of the source
cost, both per policy (CHEAPER / BALANCED / EARN_MORE). Then
`rateForFloor` grosses the floor up so the Prok fee comes out of the buyer's
payment and never out of the seller's floor. The seller advertises
`ceil(rate / 100)` whole CFA, so the on-air price can never sit below the
floor and no wire format changed.

**The identity** every quote satisfies exactly, and a test pins for every
source and policy and budget:

```
buyer charge  =  Prok fee  +  seller's source cost  +  seller's profit
```

**The budget is a signed ceiling.** `Market.Contract` gains version 2:
`rateCentimesPerMb`, `buyerBudgetCentimes`, `maxBillableBytes`,
`sourceCostBasisCentimesPerMb`, `sellerPolicy`, `pricingMode`. Both phones
sign it. `Contract.costFor(bytes)` bills a v2 contract at the exact centime
rate, capped at the byte ceiling AND at the signed budget, so a 50 CFA
session cannot become 75 CFA; a v1 contract bills exactly as it always did,
and old stored contracts still decode (`LEN` or `LEN_V2`). Every cost call
site — checkpoints, validation, both running totals — goes through
`costFor`, so there is one billing rule, not five.

**Both sides are protected.** The buyer's side (`quoteForOffer`) checks only
what is its own business: does my budget buy something useful at the price I
was offered? The seller's side re-checks in `acceptableProposal` that the
proposed rate still leaves it its floor after the fee — it signs economics,
not a price list. And the check happens *before* the expensive part:
`ProkNetNode.buy()` refuses an impossible deal before any Bluetooth channel,
handshake or probe is paid for.

**Free stays free.** A budget of 50 CFA means "you may spend up to 50",
never "take 50". A free source quotes a rate of 0, charges 0, and is not
rationed by money.

## The signed envelope is self-describing (v0.14.1)

v0.14.0 shipped the budget contract and failed on the phones. The Bluetooth
link came up, the signed handshake passed, a 512 KB probe passed both ways,
and then the seller answered `contract rejected: malformed proposal`. The
cause was one line: `Gateway.onProposal` parsed every proposal with
`Tunnel.parseSigned(data, Market.Contract.LEN)` — the **v1** length — so an
84-byte v2 body never reached `Contract.decode`. The economics were correct.
The envelope was read with the wrong ruler.

The rule now: **the wire says its own length.** The first byte of a contract
is its version; `Contract.bodyLenFor(version)` is the only place a body
length comes from, and an unknown version returns -1 rather than a guess.
`Market.framingOf` walks the envelope in one order and names where it
stopped — `EMPTY`, `UNSUPPORTED_VERSION`, `TRUNCATED`, `NO_SIGNATURE`,
`LENGTH_MISMATCH`, `BAD_CONTRACT`, `OK` — and `Contract.decode` refuses any
body whose size does not match its declared version, so a 62-byte body
claiming v2 is not silently read as a budget contract full of zeros, and an
84-byte body claiming v1 is not read as a legacy one with its economics
dropped. No other fixed-size parser is version-dependent: the checkpoint is
45 bytes in every version, and `CONTRACT_ACCEPT` carries the contract
**hash**, which is 32 bytes whatever the contract was.

**The admission decision is a pure function.** `Gateway` needs an Android
Context and a main Looper, so it can never be built in a JVM test, and that
is precisely why the boundary inside it was never tested. The decision moved
out to `Market.admitProposal`, which takes the envelope, the link peer, the
buyer key, the seller's terms and the seller's floor, and returns an
`Admission`: the reason (null when agreed) plus what it saw on the way —
declared version, envelope length, whether it decoded, whether the signature
verified. `Gateway.onProposal` now only carries that answer out to the wire
and the diagnostic. The tests call the same function the seller phone calls.

**The ceiling is checked with the un-clamped figure.** `costFor` deliberately
clamps to the signed budget, which makes it useless for judging the contract
itself: a ceiling inflated to three times the budget still "costs" exactly
the budget. `uncappedCostFor` gives the seller the real figure, and
`acceptableProposal` admits on that, so a byte ceiling that does not fit its
signed budget is refused instead of quietly agreed to.

**Money cannot overflow.** Every product is clamped before it is multiplied,
not after: `costFor`, `uncappedCostFor`, `Pricing.chargeFor` and
`Pricing.bytesForBudget` bound their inputs to `MAX_BILLABLE_BYTES` (1 TB),
`MAX_PRICE_PER_MB` and the new `MAX_BUDGET_CENTIMES` (1 000 000 CFA) first.
The largest legal product is about 1.1e17, well inside Long. A hostile or
corrupt byte count produces a charge inside the budget, never a negative one.

**The seller judges the economics it has now.** A phone may advertise on home
Wi-Fi and be on mobile data by the time the proposal arrives. `Gateway` holds
a `sellerFloorProvider` that recomputes the floor from the **current** source
at the moment the contract is admitted, so a rate agreed for free Wi-Fi is
refused once the data costs real money.

**What the contract still does not say.** Version 2 signs the rate, the
budget, the byte ceiling, the source-cost basis, the seller policy and the
pricing mode. It does **not** carry a cost class or a payer. A sponsored or
Prok-funded session is therefore not cryptographically distinguishable from
an ordinary one, and neither phone can prove to the other who was meant to
pay: those are local policy, not an agreement. Signing them needs a version 3.
A test pins this so no screen or document can claim otherwise.

**The rate the buyer signs is the advertised one.** The field holds centimes
and could carry 2.11 CFA/MB, but the buyer only ever learns the seller's
advertised price, which is whole CFA rounded **up**. So in practice the
signed rate is a multiple of 100. The seller still admits on its own current
floor, so the rounding can only ever favour the seller, never underpay it.
Carrying the true sub-CFA rate needs a signed offer in the advertisement,
which this release does not add. It is not claimed anywhere that the buyer
receives the exact internal rate.

## Stopping is symmetric (v0.15.0)

v0.14.2 gave the BUYER a two-step stop and left the seller path as it was.
`Gateway.stop()` called `endSession` straight away, which issued a closing
checkpoint and settled in the same breath on `lastSigned` — the **previous**
one, because the buyer had not countersigned the new one yet — and then
cleared the contract, so the buyer's countersignature arrived at a seller
that had forgotten what it signed. A short session was therefore still free
whenever the SELLER was the one to stop. `ProkNetNode.setSelling(false)` had
the same shape one level up: it cancelled the bulk link in the statement
after `gateway.stop()`.

Both directions now run one machine, `core/Teardown.kt`, which gained a
`Cause` (LOCAL_STOP, PEER_STOP, LINK_LOST, TIMEOUT). **The role decides which
frame goes out first. It never decides what the session costs.** A test pins
that: the same signed usage settles at the same figure, to the centime,
including the split, whichever phone pressed Stop.

Seller stop is now: stop accepting buyer traffic, keep the link up, issue the
closing checkpoint, wait for the countersignature under the existing bound,
settle, tell the buyer, release the providing state. Nothing — contract,
session, lastIssued, lastSigned, buyerShort — is cleared until settlement may
complete. The buyer treats SESSION_END as an ending rather than a fault: it
closes the VPN and says "Le fournisseur a arrêté le partage."

## The payment loop, wired (v0.16.1)

v0.16.0 built the models and left each one on whichever phone created it. The
seller's destination never reached the buyer, the buyer's expectation never
reached the seller, and the seller's receipt never came back, so the flow could
not complete. `core/PayWire.kt` is the wire.

Four messages over the existing authenticated control channel, plus a terminal
one:

```
seller  --DESTINATION_CLAIM-->  buyer     where to send the cash
buyer   --EXPECTATION-------->  seller    what to watch for, and for how long
seller  --EXPECTATION_REPLY-->  buyer     accepted, or busy with that amount
seller  --RECEIPT----------->   buyer     the money arrived; debt cleared
buyer   --EXPECTATION_END--->   seller    that window is over; free the amount
```

Each is versioned, carries its own id, and is **signed by the party whose claim
it is**. The receiver verifies before it persists or acts, never after, because
the carrier proving who sent a message is not the same as the content being
signed by the right party. Domain separators differ per message type, and a
test proves a signature from one cannot be reused on another.

**The seller derives what is owed from its own records.** When an expectation
arrives, the seller does not take the buyer's word about the amount: every named
session must be one it knows, still outstanding, and the sum must equal the
amount exactly. A padded list is refused, because padding is precisely how a
receipt would later reach a debt the payment never covered.

**The same-amount lock moved to the seller**, which is the only phone that can
see every buyer's window at once. A second buyer owing the same amount is told
to wait a few minutes rather than being charged a different amount.

**The buyer's screen tells the truth.** It says "Préparation du paiement" until
the seller has actually accepted. Telling somebody to walk to a kiosk while the
seller knows nothing about the payment would waste their trip.

**Receipts are persisted before delivery is attempted**, and retried whenever a
peer reappears, so a buyer who has already walked away still gets cleared when
the phones next meet.

**No silent manual fallback.** If a seller has no verified destination,
automatic payment is unavailable and the app says so. We decided not to trust
self-reported payment, so falling back to a buyer-typed reference would have
quietly undone that decision.

## The capture privacy gate (v0.16.1)

v0.16.0 promised that message content was inspected only while a payment was
expected. In practice a running node was enough: every default-SMS notification
was read and then discarded. The promise is now structural. Both sources ask
`ReceiptCapture.expecting()` and return **before** touching a title or a body,
and that callback exposes a single boolean, so the listener never learns what is
owed, to whom, or how much.

## Buyer credit counts buyer payments (v0.16.1)

v0.16.0 counted receipts where the identity was the buyer **or** the seller, so
selling Internet twenty times silently raised your own borrowing allowance.
Earning money is not evidence that you pay your debts. The counters are now
separate and only the buyer one may raise a credit limit.

## Cash at a kiosk, verified automatically (v0.16.0)

The habit in Congo-Brazzaville already works, and the design constraint is
that **we do not change it**:

```
buyer owes 10 CFA
  -> walks to any ordinary Mobile Money kiosk
  -> hands over cash and the seller's normal MTN or Airtel number
  -> kiosk sends it the way it always does
  -> done
```

The kiosk installs nothing, scans nothing, signs nothing and has never heard
of ProkNet. The buyer has no transaction reference to give us, and asking for
one would break the habit the whole design exists to preserve.

So what ProkNet adds is on the **seller's** phone: it notices the operator's
ordinary "you have received" message and matches it to the debt.

### Two sources, one abstraction

`DIRECT_SMS` reads the telephony API, where the sender address comes from the
network. `DEFAULT_SMS_NOTIFICATION` reads a notification posted by the phone's
**default SMS application**, which is the practical source for most builds
because the SMS permissions are restricted on Play. The rest of ProkNet never
asks which one produced a candidate; it only asks how much it is worth.

The obvious attack is that any app can post "you have received 50 CFA", so a
notification counts only when the default SMS app posted it — something a
hostile app cannot become silently. Screenshots, clipboard text, buyer-supplied
text and typed sentences are not sources, and there is no method anywhere that
accepts them.

### The parser is scored, never templated

Writing `if (text.startsWith("Vous avez reçu"))` would be a payment system that
quietly stops clearing debts the day MTN adds a promotional line. We have no
real operator samples and are not waiting for any, so the parser is built to
survive wording it has never seen.

The text is normalised (accents, case, unicode spaces, separators) and then
**scored**. Credit words pull one way, sending and withdrawal words pull the
other and outrank them. Every number in the message competes to be the amount,
scored on proximity to receipt verbs and currency tokens, and pushed down by a
preceding "solde", by being nine digits or more, or by sitting inside a date or
a clock time. The expected amount is the strongest single signal, and it can
only ever promote a number that is genuinely present.

The dictionaries are data, so an operator rewording can later arrive as a
**signed** configuration rather than a new APK. An unsigned configuration must
never be able to alter financial matching.

When two amounts score alike, the answer is AMBIGUOUS and nothing is cleared.
The bias is stated once and applied everywhere: **a false negative costs a
retry, a false positive gives away Internet for free.**

### Matching without a reference

A `PaymentExpectation` is created when the buyer taps PAYER: seller, rail,
destination hash, exact amount, a twenty-minute window, and the obligations it
covers. A receipt matches on all of those plus the operator's message, and on
nothing else.

Two things were deliberately **not** done:

- **We do not add centimes to fingerprint a transfer.** Charging somebody 51
  CFA instead of 50 to tell their payment apart is taking their money to solve
  our engineering problem.
- **We do not ask for a reference.** It would work, and it would break the
  habit.

Instead ambiguity is *prevented*: one seller may not hold two live expectations
for the same amount on the same rail. Two buyers each owing 50 CFA simply take
turns, seconds apart, and neither pays anything extra. Different amounts run
concurrently without trouble.

### Three kinds of truth, never collapsed

| | |
|---|---|
| `SESSION_VERIFIED` | both phones signed the usage |
| `PAYMENT_DEVICE_VERIFIED` | the seller's phone observed the incoming money itself |
| `PAYMENT_OPERATOR_VERIFIED` | MTN or Airtel confirmed through their API — **future only** |

A signed session does not mean it was paid. A message seen on a phone is not an
operator attestation, and nothing in this build may ever claim to be one; a
test enforces that no source can produce the operator level. For the pilot,
device-verified is enough to clear a debt, and the property that matters is
that the evidence is **observed by the seller, not asserted by the buyer**.

### Who decides

Neither party. The buyer cannot, because they benefit from the wrong answer.
The seller cannot, because a button they press is worth nothing to a buyer who
has already handed over cash. There is no "I have paid" button and no
"I received it" button, and there never will be. The state machine decides, and
when it cannot decide it clears nothing.

### Making non-payment boring

Designed on the assumption that some people will simply not pay. The defence is
arithmetic rather than goodwill: a brand-new identity may owe about one short
session (10 CFA in the pilot) and gets nothing more until a payment is
**observed**. Trust then grows through observed payments only — 25, 50, 100 CFA
— and never because somebody pressed a button. Free and sponsored Internet are
never gated, because neither costs the seller anything.

The gate is checked before any Bluetooth channel, handshake or probe, so a
blocked buyer is told on the home screen rather than after a connection has
been built.

**Reinstalling does not reset the debt.** A device pseudonym is derived from an
app-scoped identifier and hashed with a domain separator before it is stored or
sent. No hardware serial, no IMEI, no advertising id, nothing that follows
anybody to another app. A new identity on a device that still owes money starts
with no credit at all.

This is **anti-abuse, not identity**. A factory reset defeats it and somebody
determined will get through. That is precisely why the exposure behind it is one
short session: we would rather be bypassable than collect invasive identifiers
to pretend otherwise.

### Privacy

Nothing reads the inbox. Nothing scans history. A message is inspected only
while a payment is actually expected, and what is kept is its sha256, the parsed
amount, the time, the source metadata, the parser version and the confidence.
The message body never travels and is never stored; a test asserts a sender name
and phone number in a message do not appear in the signed receipt.

## The phones now prove it (v0.15.3)

v0.15.1 made the server stop believing amounts: it re-derives the money from
the signed contract and the signed closing checkpoint. But nothing on the
phone built or sent that package, so the verifier had nothing to verify. The
two halves were ready for each other with no wire between them. This is the
wire.

**Almost nothing had to be stored that was not already stored.** The
`sessions` table keeps the contract bytes with both signatures; the
`checkpoints` table keeps each checkpoint body with the seller signature and
the buyer countersignature. Every byte the server needs was already durable.
The only new table is the queue state.

**Evidence, not results.** `core/Evidence.kt` assembles the package **from the
database**, never from a live object, so a settlement survives a restart, a
flat battery and a week offline. The claimed settlement id and amount travel
too, but only as a cross-check the server may refuse us on.

It returns a reason rather than throwing, because most reasons are ordinary: a
free session owes nothing, a session whose closing checkpoint was never
countersigned has nothing anybody may be billed for, and a v1 session is out
of scope for real money. **There is no fallback that asks the server to trust a
local number.** No verifiable evidence, no server settlement.

**The signature covers the bytes that are sent.** `core/SignedApi.kt` is the
phone half of `signed_request.py`: identity, timestamp, nonce, and a signature
over `ProkNet-api-1|ts|nonce|sha256(body)`. The body is built once and the
same array is signed and written. Re-serialising JSON after signing — a
different key order, one different space — is the classic way to break this,
so the code makes it impossible rather than merely avoiding it.

**Patience, not a loop.** `node/SettlementSync.kt` queues on settlement and
drains when the server is reachable, with doubling backoff from 30 seconds to
6 hours and a maximum attempt count. A failed submission never touches the
local obligation. A retry is a new timestamp, a new nonce and a new signature
over the **same deterministic settlement**, so it is a fresh request and not a
replay. A duplicate answer is success: the id is derived from signed facts, so
"already reported" is exactly what we wanted. HTTP 400 and 403 stop the
retries because the evidence itself is wrong; 401, 5xx and timeouts do not.

**Offline-first is preserved.** The server is a witness, not a participant.
Two phones still find each other, agree a price, share Internet and settle
with nobody else involved. Both phones queue independently, and the server
reconciles the two reports; neither waits for the other.

## A signed request proves who sent it (v0.15.3)

`/v1/payments/initiate` verified the request signature and then trusted
`buyer_id` from the JSON body. Any valid Prok identity could therefore start a
payment in somebody else's name. The signed request already proves who sent
it; that identity must be **compared** with the parties, not ignored.

- the authenticated submitter must be the buyer named in the payment, or 403,
  and the stored buyer is taken from the verified identity rather than the body;
- a payment must name its seller, and every allocation must belong to that same
  buyer and seller, so one transfer cannot pay one seller for another's work;
- `payment_destinations` records where a seller is paid per rail, and a payment
  naming a different destination for a seller already on record is refused as a
  security review rather than sent somewhere new on a phone's say-so.

Request replay and payment idempotency stay separate concerns, as they must: a
fresh nonce satisfies the request layer, while the same rail and operator
reference is still recognised as the same real transfer.

## Compte belongs to Activité (v0.15.3)

A layout mistake in v0.15.2: the Compte section closed **outside** both
`activityPane` and `walletPane`, so switching to Wallet left the account,
network and developer cards showing underneath the money screen — exactly the
separation the Wallet redesign existed to create. The whole section moved
inside `activityPane`.

The summary card also led with nothing when both figures were zero, leaving
three equal zeros. It now leads with whatever needs action, and falls back to
the informational figure when nothing does: to pay, then to receive, then
earned today, then nothing at all for a new wallet.

Server verification state appears in the transaction detail under "Détails
techniques" and nowhere else.

## Gagner asks one question (v0.15.2)

The Gagner screen had grown seven cards of equal weight: a source line, a price
policy with three chips, a bundle form with two number fields, a fee note, an
earnings total, two switches and a relay panel — all stacked above and below the
button that actually does something. Someone who has never used an app before
reads that as a form to fill in, not an offer to accept.

The screen asks one question: **do you want to share your Internet right now?**

So it is now three cards:

1. **The hero.** One state, one title, one sentence, one button. The button's
   meaning comes from the state rather than from two panes hiding two buttons:
   `Commencer` when idle, `Arrêter le partage` when sharing. While sharing, the
   three live figures sit *inside* the hero instead of competing with it as
   three separate cards.
2. **Money.** Gagné aujourd'hui as the one big number, what is owed underneath,
   and a quiet text link to the Wallet. No second copy of the history.
3. **Réglages du partage.** One row with a summary line and a chevron. Behind
   it: the price policy with a plain-language hint for each choice, the mobile
   bundle form, notifications, coverage sharing, relay, and the diagnostic
   source detail. A first-time user never opens it; the summary line still
   shows the state without opening it.

`core/EarnUi.kt` decides all of that, so the four states are tested rather than
judged from a screenshot. Two wording rules the tests enforce: no subtitle is
ever two sentences, and nothing says "clients" — a person counts people.

Stopping is styled as a calm secondary button, not a red block. It is
completely reversible, and the filled button is reserved for the action we
actually want.

## Activité is a history, not a second wallet (v0.15.2)

After the Wallet arrived in v0.15.1, Activité was still carrying three money
boxes, a custody note, a payment card and a receiving-method card — every one
of them now one tap away in the Wallet — and the account settings were buried
underneath all of it. Two screens were doing the same job and neither did it
well.

The split is now clean, and the duplicated widgets were deleted rather than
hidden:

- **Activité** answers *what happened*: one row per session, grouped by day,
  with the counterparty and the amount as a quiet detail.
- **Wallet** answers *what money needs attention*.

`core/ActivityUi.kt` holds the row model, and it deliberately reuses
`WalletUi.dayLabel`, `WalletUi.shortName` and the Wallet's status words, so the
two histories can never disagree about the same fact or drift apart visually. A
free session reads "Gratuit" rather than "0 CFA", because a zero reads like
something went wrong.

The account moved out from under the history into its own **Compte** section
with its own heading.

## The server derives the money itself (v0.15.1)

v0.15.0 shipped with a gap I flagged as the largest one: `/v1/settlements`
believed the amounts a phone sent. A phone could invent a session, recompute a
matching settlement id from its own invented fields, and create debt. Fine for
a diagnostic, unacceptable for real money.

A phone no longer reports amounts at all. It submits **evidence**: the exact
signed bytes of the contract and the closing checkpoint, and the four
signatures over them. `brain/evidence.py` re-derives everything the money
depends on and refuses anything it cannot verify:

1. the contract is a valid, paid, v2 budget session;
2. the two public keys are the parties the contract names;
3. both parties signed **those exact** contract bytes;
4. the checkpoint belongs to that session and is the closing one;
5. both parties signed **those exact** checkpoint bytes;
6. the signed cost is what the terms give, and fits the signed budget and
   byte ceiling;
7. the submitter is the buyer or the seller;
8. gross, Prok fee, seller net and the settlement id are computed **here**.

A claimed amount or id is accepted only as a cross-check. A disagreement is
refused rather than corrected, because a sender that is broken or lying does
not deserve a row in the settlements table.

**Submissions are signed and used once.** Transport security and evidence are
different things. HTTPS says the bytes were not altered; it says nothing about
who sent them or whether this is the third replay of an hour-old submission.
`brain/signed_request.py` requires identity, timestamp, nonce and a signature
over the body hash, and refuses a bad signature, a stale timestamp, an altered
body, or a reused nonce. A failed signature does not burn the nonce, so an
attacker cannot lock out a legitimate request by guessing it.

## One transfer, many obligations (v0.15.1)

The v0.15.0 payment event key was `rail | reference | settlementId`, which let
the same operator reference be presented independently against several
unrelated obligations. The naive fix is a unique reference per obligation, and
it is wrong: netting tiny sessions into one real transfer is deliberate, since
nobody should authorise a Mobile Money payment for 3 CFA.

So the unit of payment is the transfer, not the obligation.

```
payment_transactions   one real operator transfer, UNIQUE(rail, operator_ref)
payment_allocations    how much of it settles which obligation
```

Three 5 CFA sessions become one 15 CFA MTN payment with three allocations.
The invariants: allocations may not exceed the amount transferred; no
obligation may be allocated more than it still owes; a confirmed obligation
cannot be paid again; every obligation in one payment belongs to the same two
parties; a duplicate confirmation is a no-op. A confirmation for a different
amount, or the same reference reused for a different payment, moves the
transaction and everything it touches to `SECURITY_REVIEW`. A partial payment
leaves the rest owing, and a second transfer can clear it.

## The Wallet is a product, not a report (v0.15.1)

The engineering works. The Wallet had to stop looking like engineering. It now
answers four questions without anybody reading twice: what do I owe, what am I
owed, what has been paid, and what should I do now.

`core/WalletUi.kt` decides all of that, so the screen states are testable
rather than a matter of opinion about a screenshot. It makes three promises
the tests enforce:

- **One obvious action.** `primaryAction` returns exactly one thing to do,
  ordered by urgency: money I owe, then nowhere to be paid, then money owed to
  me, then nothing. It is never an empty PAY button.
- **No technical clutter.** No status enum, no settlement id, no checkpoint
  hash, no raw 32-character identity, no CFA per megabyte. A test sweeps every
  visible string. The engineering lives in one "Détails techniques" sheet.
- **Honest money words.** Never "Solde". Never a guarantee. "Payé" only when a
  payment was actually verified; a typed reference reads "À vérifier".

Structure: Activité gained a segmented switch, so the bottom navigation is
unchanged and Wallet lives inside it. Activité answers *what happened*; Wallet
contains money events only. One summary card leads with the figure that needs
action, one action card, one receiving card, then history grouped by day with
an amount, a counterparty and a status chip. `prok-24e480e6a1b2…` is shown as
**Prok 24E4** — an abstraction over the identity, not an invented name, so it
swaps cleanly when real profiles arrive. Gagner shows one figure and a link
rather than a second copy of the history; Home shows at most one money line,
and only when nothing more urgent needs that space.

## The wallet is an obligation layer, not a bank (v0.15.0)

ProkNet coordinates and verifies payment. **It does not hold anybody's money.**
Every design decision below follows from that one sentence, and the wording
follows from it too: the wallet shows "À payer", "À recevoir", "Payé", "Reçu"
and "Gagné", and never a balance, because a balance would be a promise Prok
cannot keep.

**An obligation comes only from signed truth.** `Settlement.fromSession` takes
a v2 contract and the checkpoint BOTH phones signed and returns an
`Obligation`, or null. The UI can never name an amount. Null is a normal
outcome: a free session (rate 0) owes nothing, a session with nothing mutually
signed owes nothing because nobody may be billed for usage they never signed
for, and a legacy v1 session is out of scope for real money.

**One session, one obligation.** The settlement id is
`sha256(domain | sessionId | contractHash | checkpointHash)`. A restart, a
re-derivation, a repeated sync and the other phone's independent derivation
all produce the same string, so a second payment request is not something the
code has to remember to avoid — it is unrepresentable. Change the usage and
the id changes: a tampered amount is a different obligation, not a louder
version of this one.

**Both phones derive it, neither trusts the other.** The buyer builds one from
its copy of the session, the seller from its own. `agree()` compares them. A
mismatch is `DISPUTED` and **the larger figure is never charged** — on the
phone, and in the server's `report()`.

**The accounting identity**, asserted on every obligation:

```
buyer obligation  =  seller receivable  +  Prok fee
```

**v0.15 covers commercial v2 sessions only.** Contract v2 signs the rate, the
budget, the byte ceiling, the source-cost basis, the seller policy and the
pricing mode. It does **not** sign a cost class or a payer. A sponsored
session therefore cannot be proven to be one, and real money must not rest on
an unsigned claim. Sponsored and Prok-funded sessions keep working; they
simply create no payable obligation. This is the honest option and it avoids
expanding the contract protocol again.

**Small sessions net.** Authorising a Mobile Money transfer for 3 CFA is
absurd, so obligations to the same seller accumulate and settle together.
`SettlementPolicy` decides whether a paid session may start at all, **before**
any Bluetooth channel, handshake or probe is paid for: free is never gated,
sponsored runs under its own payer, a small debt does not interrupt one-tap
Internet, and past the credit limit the buyer is asked to settle first. A
confirmed payment history earns a little more room. A disputed session blocks
until a human has looked at it.

**Payment rails are adapters**, so the wallet core never knows which operator
it is talking to. Stated plainly, because the difference matters:

| Rail | Status |
|---|---|
| `MOCK` | Real, developer mode only, off unless a long press in the Lab screen enables it |
| `MANUAL_PILOT` | Real. The buyer pays outside the app and gives the reference; the obligation reaches PAYMENT_SEEN and no further |
| `MTN_MOMO`, `AIRTEL_MONEY` | Interface and documented integration points only. No merchant credentials exist here, so they report unavailable and refuse to initiate |

**A typed reference is a claim, never a payment.** Only a rail, or a webhook
whose signature the settlement service verified, may produce CONFIRMED. The
server ships with no operator signing secret, so today nothing can be
confirmed by webhook at all, and the code says so rather than defaulting to
trusting it.

**ProkNet never asks for a PIN**, secret code, password or operator OTP. The
user authorises payment inside the operator's own flow. A design that needs
the PIN is the wrong design.

**Privacy.** A Mobile Money number is stored locally, never put in a BLE
advert or in gossip, and shown masked. Discovery keeps using the Prok
identity; payment identity is exchanged only for a real settlement.

**The server** (`server/brain/settlement.py`) reconciles the two phones'
reports, records every payment event under an idempotency key so a retried
webhook is a no-op, refuses a payment whose amount does not match, never moves
a confirmed obligation, expires unpaid ones, and writes an append-only audit
trail.

## Stopping is two steps (v0.14.2)

v0.14.1 connected, browsed and billed correctly on the phones. Stopping was
dirty. The log, on one Stop:

```
23:20:25.168 NODE:   CONTROL -> seller over bt-bulk
23:20:25.169 BTBULK: BULK cancelled: customer stopped
23:20:25.169 NODE:   CONTROL ... failed: no receipt over bt-bulk within 15s
23:20:25.206 BTBULK: BULK FAILED at IDLE: no receipt over bt-bulk within 15s
```

Three milliseconds, not fifteen seconds. `stopInternet` sent the final control
message, closed the Bluetooth link in the very next statement, and only then
stopped the tunnel. So the message could never be acknowledged, the transport
reported a failure caused entirely by our own shutdown, and the tunnel's
SESSION_END went out over a link that no longer existed.

The quiet half of the same bug was worse than the noise. The seller issues a
usage checkpoint every 30 seconds, and its closing checkpoint had nowhere to
arrive, so **a session stopped before the first periodic checkpoint settled at
zero**. Browsing for twenty seconds and pressing Stop was free, repeatably.

**The rule: never destroy the transport underneath a message the protocol
still expects an answer to.** Stopping is now:

```
RUNNING -> SETTLING -> IDLE
   stop new app traffic
   say SESSION_END over the LIVE link
   seller issues the closing checkpoint for what was really used
   buyer verifies and countersigns it
   (bounded: 4 s, then settle on the last figure both sides did sign)
   close the VPN
   close the tunnel session
   flush what is queued, then close the bulk link
   clear the purchase state
```

`core/Teardown.kt` is that machine, with no Android in it. `TunnelClient` and
`Gateway` both drive it; neither can be built in a JVM test, so the decisions
live where the tests can run the real ones, the same move that fixed the
v0.14.1 contract bug.

**An intentional close is not a failure.** `BulkPlan.sendFailureIsReal` decides:
a send failure counts only when we were not closing on purpose, a session is
still live, and the send belonged to that session. A late report from a
finished session is counted and dropped instead of marking an idle transport
FAILED. `StreamLink` also stopped claiming "no receipt within 15s" for a link
that we closed a millisecond earlier; it now says the link closed.

**The last signature has to actually leave the phone.** `LinkIo.close` shuts the
output stream at once, so a frame handed to the writer a moment before could
die in the queue. On a graceful stop that frame is the buyer's countersignature
on the closing figure, and losing it costs the seller the whole session.
`closeAfterFlush` waits for the queue to drain, bounded, on a worker thread. It
is a wait on a condition, not a sleep: it returns the moment the queue is empty.

**Stale callbacks.** Every transition out of SETTLING bumps a token. A timer
armed by one stop cannot end the session that came after it, and the bulk
transport judges a late send failure against the session it belonged to.

**Stopping twice is stopping once.** `Teardown.begin` on a stop already under
way returns the same state, `ProkNetNode.stoppingInternet` guards the node path
and `TunnelClient.finishStop` guards itself, so the screen and the system
lifecycle both calling cleanup produce one settlement, one close and one set of
ledger entries.

**If the peer is already gone**, there is nobody to sign a closing figure with:
settle on the last figure both sides did sign, close locally, and do not wait.
Nothing is ever invented for traffic nobody signed for.

**The quote has a shelf life.** The buyer snapshots its quote when `buy()`
admits the deal and does not re-read the budget preference mid-setup, so
changing the budget during a connection cannot alter a purchase already under
way. But setting up a Bluetooth link can take a while, so a snapshot older
than two minutes is taken again before anything is signed rather than trusted.

**Nobody is subsidised by accident.** COMMERCIAL must fit the budget and
leave the seller in profit. SPONSORED lets the buyer pay nothing while the
sponsor's budget is the ceiling and the seller still earns. GROWTH_SUBSIDY
is allowed only inside an explicit subsidy budget. The payer — BUYER,
SPONSOR or PROK — is part of every quote.

**The screens.** Buyer: a budget card (25 / 50 / 100 CFA, remembered,
confirmed once before the first ever paid session) and, during a session,
"Vous avez dépensé 34 CFA sur votre budget de 50 CFA" with a warning only
near the end. Offers read "Jusqu'à 50 CFA" or "Gratuit". Seller: no price
box at all — "ProkNet fixe automatiquement un prix rentable pour vous",
three choices, an optional "Mon forfait" (paid / for how many Mo), a bounded
earning estimate shown only when it is genuinely calculable, and "Vous avez
gagné 27 CFA". CFA/MB survives only in Developer diagnostics and in the
display of old v1 sessions.

## The control plane has to be trustworthy (v0.13.3)

Three failures on the phones, all of the same family: our code said ready,
Android disagreed, and a real Internet request was lost.

**A second buyer was swallowed by a clock.** Request A alerted at 17:57:35;
request B, a different buyer, was refused 53 seconds later as
"rate-limited" by a global two-minute cooldown. A rate limit exists to stop
one unresolved opportunity from nagging, never to hide new demand. The
wall-clock limiter is deleted. `ProviderInbox` alerts each opportunity
**once, by id**: a duplicate or a gossip copy never alerts again, and a
different buyer always gets through. Several requests arriving together
produce one aggregated alert ("3 personnes cherchent Internet à proximité")
rather than three.

**Push was the only place demand existed.** Now it is an alert, not the
truth. `ProviderInbox` is one persistent source of truth
(`filesDir/opportunities.v1.txt`) that both the notification and the new
Gagner card render from. A request survives a dismissed notification, a
denied notification permission, a tap on the wrong thing, the screen going
off, the activity being recreated and the process being restarted: on
startup the inbox is rebuilt from the carried requests, without re-alerting
what was already alerted. It leaves only when the request really ends —
tombstone, expiry, acceptance, or the phone becoming unable to serve it —
and then it leaves the card and the notification at the same moment.
PARTAGER on the notification and PARTAGER on the card call the same
`NetworkNode.acceptOpportunity(id)`, which re-checks the request, the
current Internet, Bluetooth, the price ceiling and the session before
starting the normal seller flow, and otherwise answers in one sentence
("Votre Internet n'est plus disponible.", "Cette demande n'est plus
active.").

**"peer has no ProkNet service (services=2)".** After a Bluetooth toggle
the OUKITEL reported `server ready, adv on, scan on` while every customer
failed service discovery. `GattServerNode.isReady` was a Boolean set once
by `onServiceAdded` and never invalidated, so the old recovery
(`if (server?.isReady != true)`) skipped the server rebuild and kept
advertising a control plane that no longer existed. Booleans cannot
describe an asynchronous stack. `BleLifecycle` is a generation machine:
every piece — GATT server, ProkNet service, advertiser, scanner — is
stamped with the generation it was built under and counts only while that
stamp is current. An adapter that returns bumps the generation, which
invalidates everything at once, including callbacks still in flight
(`isStale`). `BleTransport` now has ONE rebuild path that reconstructs in
order: close everything, open the server, add the service, **wait for
Android to confirm it**, and only then advertise. The invariant — *if
ProkNet advertises, its GATT service must really be there* — is checked by
the watchdog on every tick and repaired immediately, and a service that
cannot be added is retried with a bounded backoff while the phone
advertises nothing.

**Retry storms.** `onPeers()` fires constantly, and every fire re-queued the
same request to the same peer. `ControlRetry` allows one attempt per
(request generation, peer identity) with a 1 / 3 / 10 / 30 s backoff, keyed
by the signed ProkNet short id so BLE address rotation changes nothing. A
peer whose GATT service is missing is parked for 45 s rather than hammered,
and the diagnostic says "peer advertises ProkNet but its GATT service is
missing". Our own stack being rebuilt, or a parked peer reappearing, clears
the backoff at once.

**After a session.** Stopping sharing calls `onSharingStopped`, which drops
opportunities whose request is gone and un-accepts the rest, so the next
request is never blocked by anything left behind.

## Could this phone become a seller? (v0.13.2)

The OUKITEL received the request, verified the signature, stored it — and
refused: `not activating for ...: NO_INTERNET`, while the same diagnostic
showed `wlan0=192.168.1.13` and a validated Freebox.

`NetworkNode.eligibility()` asked the seller gateway
(`node.gateway.upstream?.validated`, `node.sellerAccessPath()`). The gateway
only starts inside `setSelling(true)`, which only runs when the user taps
PARTAGER. So with sharing off `gateway.upstream` is null: NO_INTERNET, no
notification, no PARTAGER, no gateway — a closed loop the user could never
break.

Provider activation asks **"could this phone become a seller right now?"**
It must never ask "is the seller gateway already running?".

`node/Upstream.kt` answers the first question from the phone's real
connectivity: `networks(context)` (the ConnectivityManager scan, moved out
of `Gateway` so both use one truth, not two) and `now(context)` =
`Tunnel.chooseUpstream(...)`. `Gateway.networks()` is now one line calling
it, so the seller path and the eligibility path can never disagree.

`ProviderActivation.potentialPath(upstreamType, upstreamValidated,
bulkSupported, bluetoothOn)` is pure: no validated Internet → NONE;
validated Wi-Fi → `BulkPlan.sellerAccessPath` (BLUETOOTH_BULK with
Bluetooth and L2CAP, NONE without); anything else validated → HOTSPOT.
`ProviderActivation.eligibility(...)` composes it, and `alreadySharing` is
the only field the gateway still decides.

`NetworkNode.currentUpstream()` picks the source of truth by state: the
gateway when SELL is on (it is authoritative then), the phone's current
capability when SELL is off. The gateway is never pre-started to answer a
question.

The diagnostic separates the three facts that were conflated:

```
current phone Internet: WI-FI validated
potential seller path: BLUETOOTH_BULK
seller gateway running: NO
```

so "provider upstream none" can no longer read as "this phone has no
Internet".

## Two bugs from the first v0.13 run (v0.13.1)

**A new request wore an old session's error.** `getInternet()` set
`lostDismissed = false` but nothing cleared the node's last error:
`clearLastFailure()` only runs inside `node.buy()`, which never happens
when no source is found. So "Wi-Fi link closed: customer stopped: stopped
by user", left by a session the user had stopped, made `ProductState.buyer`
return LOST (an error with `wanted = false` is LOST), and `buyerVisible`
outranked the live request: the home screen showed RÉESSAYER / Connexion
perdue while the request was searching. Three changes, each testable:
`node.clearLastFailure()` is public and called when a request starts (a new
request starts from a clean screen, exactly as a direct purchase does);
`ProductState.isUserStop` means a session the user stopped is never an
error for anybody; and `ProductState.homeOwner(sellerOn, purchaseActive,
requestActive, showLost)` decides the screen, with an active request
outranking a lost card from a purchase that is over. The lost card still
shows when nothing is running, and a failing request sets `lostDismissed`
back so its own reason appears.

**Bluetooth OFF → ON left the radio wedged and "healthy".** `scanStale` is
gated on `expectPeers`, which is false when the last peer is older than
15 minutes. On the phone the last peer was 55 minutes old, so after the
adapter went off and came back — with `scanning` and `advertising` still
claiming true, because an adapter restart does not call the failure
callbacks — every verdict was HEALTHY and nothing ever restarted. Two
bounded rules now: `BLUETOOTH_RETURNED` recovers exactly once per OFF → ON
transition (the node notices the transition in the watchdog itself, no
receiver), and `SCAN_SILENT` recovers when the scanner **has heard peers
before** and has then heard nothing at all for ten minutes. A phone that
never heard anybody is alone in a field, not wedged, and is still left
alone — the v0.9.10 rule stands. Both obey the existing guards: never
during a link or a bulk session, never inside the backoff.

**COPY NETWORK answers the test first.** Five lines at the top: nearby
ProkNet phones (with their ids), last peer seen, this phone's request
state, who the last request went to, and whether an activation
notification was sent with the reason if not.

## The Network Brain (v0.13.0)

The product target: a person with no Internet taps once; if no seller is
active, the request enters the ProkNet network, nearby potential providers
can be activated, and when one comes online ProkNet connects the requester
by itself. The direct path is untouched and still first: GET INTERNET with a
usable provider in reach connects immediately.

**The offline rule.** A request never needs Internet to exist. After the
15 s local search finds nothing usable, the phone signs a `NetRequest`
(`core/NetRequest.kt`): 16-hex id, origin short id and public key, created /
updated / expires, coarse zone, flexible MB and minutes, automatic price
ceiling, urgency NOW, state, generation, hops, ECDSA P-256 signature over
everything but the hops. ~230 bytes, inside an encrypted BLE control
envelope (`Wire` op 15). States: CREATED, SEARCHING_LOCAL,
DIRECT_SOURCE_FOUND, NETWORK_REQUESTED, CARRIED, UPLOADED, SUPPLY_POSSIBLE,
PROVIDER_ACTIVATING, CONNECTING, ONLINE, FAILED, EXPIRED, CANCELLED,
FULFILLED; the user only ever reads RECHERCHE / DEMANDE / CONNEXION /
CONNECTÉ.

**Store-carry-forward** (`core/RequestGossip.kt`, pure; `node/NetworkNode.kt`
on the phone). One record per id, the latest generation wins, a tombstone
(CANCELLED / FULFILLED) is final: an older carried copy is dropped, and even
a validly signed newer open generation cannot replace a tombstone. Expired
and hop-exhausted requests are dropped and counted. Forwarding is once per
(id, generation, peer), never back to the sender, never to the origin; a
failed send is unmarked and retried when the peer reappears. The store
persists (`filesDir/requests.v1.txt`) and is swept: expired → EXPIRED kept
2 h, tombstones kept 2 h, forward memory 6 h. Carrying means storing,
moving, forwarding, uploading; it never means carrying Internet.

**Provider activation** (`core/ProviderActivation.kt`). Opt-in on Gagner:
"Me prévenir quand quelqu'un cherche Internet près de moi". A carried
request meets the phone's eligibility: opted in, validated upstream, a local
path (Bluetooth for home Wi-Fi, hotspot for mobile data), Bluetooth on if
needed, not already sharing, not busy, and its sell price within the
request's ceiling. Then one notification (rate-limited: 10 min per request,
2 min globally) with honest wording: "Quelqu'un cherche Internet à
proximité." when the request came over BLE, "Une demande Internet existe
dans votre zone." when it came from the brain. PARTAGER is a service action:
`setSelling(true)`, the normal seller. The requester, still polling the
decision engine every second in DEMANDE, sees the advert, `GetInternet`
selects it within the ceiling, and the proven stack runs; ONLINE marks the
request FULFILLED and the tombstone travels and syncs. ARRÊTER cancels the
same way. No second tap.

**The brain** (`server/`, Python 3.11 stdlib + `cryptography`, SQLite with
a migrations list). One operation, `POST /v1/sync`, one signed line-protocol
message (`core/SyncProtocol.kt` ↔ `server/brain/protocol.py`,
`prok-sync/1`): node id + key + timestamp, zone, coverage summaries (opt-in),
availability heartbeat (opt-in), request generations, job changes, then the
signature over every byte before it. The server verifies the signature,
that the id is SHA-256(key)[:16], and the clock (±10 min); every write is an
upsert; the same message twice leaves the same rows; per-node rate limit.
Back: shared cells, the requests this node should act on, its jobs, the
statuses of its own requests, one line of advice. `GET /health` for
deployment. Matching (`server/brain/matching.py`, mirrors `core/Jobs.kt`):
DIRECT_SOURCE → ACTIVATE_PROVIDER (opted in, heartbeat < 15 min, validated,
not busy, not sharing, same zone or seen locally, within ceiling, cheapest;
commercial delivery cost ≤ ceiling) → WAIT_FOR_SUPPLY → NO_PLAN; one
PROVIDER_ACTIVATION job per (request, provider); a tombstone cancels its
jobs. Cleanup is deterministic and tested with a fake clock.

**Shared coverage honesty.** The brain's GREEN (a provider sharing now,
seen < 10 min) becomes YELLOW on the phone: "seen 5 minutes ago" is never
"you can connect". GREEN on a phone is only a source that phone reaches
now. Carte shows "Vu par le réseau ProkNet" rows apart from the phone's own.

**Sync behaviour.** Only when a server is configured (Développeur → BRAIN
URL; empty by default). Triggers: request created / carried / ended,
provider or coverage preference changed, PARTAGER, 60 s after service
start, then every 15 min; exponential backoff 1 → 30 min on failure. The
app itself is outside its own VPN, so a phone with only ProkNet Internet
does not sync; the phone with real Internet does.

**Jobs** (`core/Jobs.kt`). PROVIDER_ACTIVATION and CARRY_REQUEST execute;
ANCHOR, RELAY, MOVE_TO_ZONE, COURIER are modelled (types, states OPEN →
OFFERED → ACCEPTED → ACTIVE → COMPLETED / FAILED / EXPIRED / CANCELLED,
fields, forward-only transitions) and nothing pretends they run. No reward
today: the provider earns through the session.

**Privacy.** Zones are cells, sources are hashed ids or ProkNet ids, no
coordinate is uploaded or stored (a server test checks the schema), no
password ever, both contributions are opt-in with one sentence each.

## Under the sphere (v0.12.5)

Mike: the sphere and its animation are the design; the rest of the home
needed a professional treatment. The three boxed tiles became one slim
pill strip (`bg_strip`) with three values and thin dividers, lowercase
labels, no captions in caps. The two text rows became two square action
cards side by side: a brand-tinted icon in a soft circle
(`bg_icon_circle`), a one-word title, a tiny caption; the Partager card
turns its caption green ("● Vous partagez") while sharing.

## The sphere is the interface (v0.12.4)

Mike liked the sphere and asked for less writing, more intuition, and a
sphere that breathes. So the state moved into the sphere:

- `PulseButtonView.Mode` IDLE / SEARCHING / CONNECTING / ONLINE. At rest
  the sphere breathes on a 3.4 s sine (radius 2 %, halo alpha); searching
  breathes on 1.5 s with a turning radar wedge; connecting emits two
  expanding pulse rings; online turns the body, halo and arcs green. A
  press sends a ripple ring outward. The two scan arcs rotate slowly in
  opposite directions; the source dots orbit the first arc (24 s per turn,
  8 s while searching). The view redraws with `postInvalidateOnAnimation`
  while attached and shown, never otherwise.
- One word per state, set by the home refresh: OBTENIR INTERNET /
  RECHERCHE / CONNEXION / CONNECTÉ / RÉESSAYER.
- The ask block no longer hides while a request runs; it hides only for a
  seller. The status card sits under the sphere with the details and
  ARRÊTER. A tap while a request is alive does nothing.
- Two defects fixed: the software layer used for blur showed as a hard
  rectangle because the ambient gradients reached the view edge (they now
  stay inside 92 % of the radius), and the outer arc was clipped (arcs are
  bounded by the view radius, height 336 dp).
- Less writing: no "Besoin d'Internet ?", no "Prêt · …" line, tiles show
  only the value ("2", "gratuit", "3 min"), door rows are a title and a
  chevron, the share row says "Vous partagez" only while sharing.

## The sphere, second pass (v0.12.3)

Mike: "Better but not quite. Was that the best you have?" It was not. The
v0.12.2 sphere was a pale flat disc inside three even circles, and the
tiles truncated.

`PulseButtonView` now runs in a software layer so it can blur: a drop
shadow (blurred dark ellipse under the sphere), a blurred halo ring, and
blurred glows under the source dots. The body is a four-stop radial
gradient from a tight near-white specular (top-left) through the brand
blue to a deep navy edge, with a thin white rim. Ambient light replaces
the bullseye: a blue radial glow high-left and a violet one low-right, and
two scan arcs whose stroke fades around the circle (a sweep gradient) so
they read as light, not as a target. The usable sources the engine sees
(`sources`, set from the home refresh) sit on the first arc as small green
glowing dots, evenly spread; while searching they drift and a radar wedge
turns once every 3 s. The label carries a soft shadow.

Tiles: short captions (Sources / Prix / Dernière) on one line, values
auto-sized between 12 and 18 sp, so "il y a 14 min" is complete.

## Accueil and Carte, redesigned (v0.12.2)

Mike rejected the v0.12.1 home (a giant filled ball in an empty frame) and
map (one green square). Both were redrawn with a metaphor the product can
honour: a sonar.

- `ui/PulseButtonView` draws the one button: a soft radial glow, three
  thin still rings, a 150 dp sphere lit from the top-left with a faint rim,
  the label inside. While a request is alive (`searching = true`) two rings
  expand from the sphere to the edge and fade, on a 2 s loop driven by
  `postInvalidateOnAnimation`. Pressed state darkens the sphere. It is a
  plain `View`, so the Activity uses `setOnClickListener` as before.
- Under it one quiet line ("Prêt · 1 source · 5 CFA par Mo"), then three
  tiles (Autour de vous / Meilleur prix / Dernière fois, the last from the
  persisted requests), then two door rows with chevrons (Partager mon
  Internet, Carte). The status card is unchanged.
- `ui/CoverageMapView` without a position draws a sonar: rings at 30 / 55 /
  85 / 100 %, a faint cross, "récent" and "ancien" on the axis, the phone
  as a glowing dot, and one dot per known source placed by age (fresh
  within 10 min at 30 %, then linearly to the edge at 24 h), spread evenly
  with a little of the id so the picture is stable, coloured by status
  (available / seen recently / stale), labelled, tappable (nearest dot
  within 28 dp). With a position it draws the cell grid as before.
- The Carte tab: the sonar in a card, one hint line, two tiles (Disponible
  maintenant / Vu récemment with their dots), the "around you" card with
  the location button inside it and an honest sentence, then source rows
  with a status dot, name, status · age · price, and a chevron.
- Stat tiles auto-size their text so "629 CFA" never wraps.

## The design system (v0.12.1)

The first v0.12 screens were flat: plain circle, plain cards, a legend
with light text on light pills, and a map card that said "Internet
disponible" under "Position inconnue". v0.12.1 is a visual pass with no
new library and no code change under the screens.

- `values/colors.xml` and `values-night/colors.xml` carry the same names:
  `bg`, `card`, `card_alt`, `stroke`, `text`, `text_muted`, `brand`,
  `brand_deep`, `glow`, `glow_soft`, `ok`, `warn`, `danger`, `chip_bg`,
  `nav_*`, `divider`, and the fixed map pastels with `legend_text` dark so
  the legend reads in both themes. Night is a deep navy with one blue.
- `styles.xml`: H1 30 / H2 19 / Body 15 / Muted 13 / Caption 11 caps
  spaced / Big 36 / Stat 22; buttons 58 dp, primary is a gradient,
  secondary is a bordered chip surface, danger is flat.
- Drawables: `bg_card` (bordered, 22 dp), `bg_card_alt` (soft gradient),
  `bg_card_accent` (brand border for the live status), `bg_hero` (a glow
  fading into the background behind the button), `bg_big_button` (two
  translucent glow rings around a radially lit sphere, pressed state),
  `bg_nav` (top-rounded bar), `bg_pill_*` (legend), `dot_ok` / `dot_muted`
  (the status chip).
- `CoverageMapView` draws the phone as a glowing dot, cells with a small
  gap and soft radius, and chooses dark or theme text depending on the
  fill.
- Words: the chip says "Actif" / "En pause"; the map card says "Vu par ce
  téléphone, sans position" when there is no position; an unnamed
  connected network is "Wi-Fi connecté".

## One tap, and the phone as a sensor (v0.12.0)

**The promise.** No Internet? Open ProkNet, tap once. The home screen is
one big button, OBTENIR INTERNET. Everything under it is the proven stack
of v0.10/v0.11; this milestone adds the decision, the request, and the
coverage memory, all pure and tested, and the screens that show them.

**GetInternet** (`core/GetInternet.kt`). Candidates carry an id, a way
(ProkNet direct, existing link, connected Wi-Fi, mobile-data provider), a
price in centimes per MB, reachable-now, signal, last seen, validated,
reliability, setup cost, capacity, authorization. `decide()` blocks what
the phone cannot genuinely use (not reachable, stale > 90 s, not
authorized, not validated, above the ceiling), then ranks: usable free
first, then by `price + (1 - reliability) x 3 CFA/MB + setup/20 MB`, with
a 0.4 CFA tie-break for the last source that worked. So free validated
Internet beats 5 CFA, a 6 CFA source at 0.9 reliability beats a 5 CFA one
at 0.3, and a cheaper source that is unreachable or stale never wins. No
usable candidate but something known -> REQUEST_NETWORK; nothing known ->
NONE. The reason is a sentence for the diagnostic. `fromOffer` turns a live
BLE offer into a candidate; the transport underneath stays the v0.10.1 /
v0.11 rules, never the engine's choice.

**InternetRequest** (`core/InternetRequest.kt`). One tap creates a request
with flexible MB and duration, automatic price, urgency NOW, a coarse zone,
and walks SEARCHING -> DIRECT_SOURCE_FOUND -> CONNECTING -> ONLINE, or
NETWORK_NEEDED ("Aucun Internet disponible tout de suite. ProkNet continue
de chercher autour de vous."), FAILED, CANCELLED. NETWORK_NEEDED never
pretends a relay is coming. The planner foundation is there: hops with
roles PROVIDER / ANCHOR / RELAY / MOVER / COURIER, cost classes COMMERCIAL
/ SPONSORED / GROWTH_SUBSIDY, and the rule: a commercial plan whose
delivery cost exceeds the customer ceiling is refused; sponsored and
growth plans may carry an explicit subsidy; movement ranks last. v0.12
only ever builds the direct plan.

**CoverageModel** (`core/CoverageModel.kt`). A sighting (ProkNet peer or
Wi-Fi network) becomes ONE source record: `prok:<id>` or `wifi:<sha256 of
the BSSID, 16 hex>`; the BSSID itself is never stored. First / last seen,
observation count, best / last signal, price if any, validation history,
trust class (OPEN_REUSABLE, AUTHORIZED_PRIVATE, CAPTIVE_PORTAL, UNKNOWN,
NOT_ALLOWED), security, zones seen, selling, successes. Detection is not
authorization: a network merely seen stays UNKNOWN and is never usable.
Zones are ~500 m cells (`z<latIdx>:<lonIdx>`, 0.005 degrees); a phone that
does not know its position records `z?`. A cell is GREEN only when a
source in it is usable now and was seen within 10 minutes; YELLOW when a
plausible source was seen within 24 h; RED otherwise. Everything persists
through a versioned, tolerant text codec (sources, the last 500
observations, the last 50 requests, the last successful source).

**CoverageEngine** (`node/CoverageEngine.kt`). Feeds sightings from the
node's peer list (a node listener), the connected Wi-Fi with Android's own
validated / captive-portal verdict, and cached scan results when the app
is in front and fine location was granted, never starting a scan.
Throttles to one observation per source per minute, saves 20 s after a
change and on background. Location: last known, plus network-provider
updates every 5 minutes / 300 m while the app is in front, coarse
permission asked once on the Carte tab with one plain sentence. Builds the
GET INTERNET candidates from live offers (reachable = in range and key
known; existing link = the bulk or Wi-Fi link already up), remembers the
last successful source, and prints the whole picture for COPY COVERAGE.

**The screens.** Accueil: the question, the button, the promise, a status
card while searching / connecting / connected / failed, and a coverage
hint ("Internet autour de vous, 1 option trouvée, à partir de 5 CFA par
Mo" / "Internet vu récemment ici" / "Pas encore d'Internet disponible
ici"). Internet: the manual list, simple cards, CONNECTER. Carte: the
cells around the phone, a legend, "around you", the known sources, a
dialog per source or cell. Gagner: source line, price, options folded,
COMMENCER À PARTAGER, then clients / shared today / earned today. Activité:
session cards, the wallet row, and the account block (name, keep running,
battery, developer). No protocol word anywhere; a test checks the buyer
and seller words for L2CAP / PSM / BULK / GATT / probe / hotspot / BSSID.

**Not built on purpose.** No tiles, no backend, no relay execution, no
mover payments, no change to the probe size.

## The consumer path (v0.11.0)

The proven two-phone path is the normal product now. Nothing under it
changed; this milestone is the rules the normal screens obey, made pure
and tested, so a person never chooses a transport or reads a protocol
word.

**Seller.** Partager -> price -> Commencer. `BulkPlan.sellerAccessPath`
(v0.10.1) picks the local link: home Wi-Fi with Bluetooth -> Bluetooth,
mobile data -> the hotspot, home Wi-Fi without Bluetooth -> NONE with the
sentence "Activez le Bluetooth". `ProductState.sellerNeedsHotspotWarnings`
keeps the hotspot warnings (Wi-Fi on, Location on, "this network refuses
a hotspot") off the Bluetooth path, where they were wrong.
`ProductState.sellerSourceLine` prints "Source : Wi-Fi (Freebox) ✅" and
no protocol. The screen then reads "Vous partagez votre Internet /
Disponible pour les personnes à proximité", and "Quelqu'un utilise votre
Internet" with data and earnings once a customer is on.

**Buyer.** Internet -> the offer card (price, signal, source) ->
Connecter. `P2pAdmission.buyPath` (v0.10.0) chooses: an authenticated link
is reused; a Bluetooth-capable provider on Wi-Fi -> Bluetooth; a
mobile-data provider -> the hotspot; Wi-Fi Direct never, since no normal
provider advertises a group any more. `ProductState.buyerNeedsWifi` stops
CONNECT from demanding Wi-Fi on the customer for the Bluetooth path.

**Four words.** `ProductState.buyer` gained `CHECKING`, fed by
`ProkNetNode.linkChecking()` (link up, quick check running). The user
reads exactly: Connexion… -> Vérification de la connexion… -> Démarrage
d'Internet… (contract and tunnel share the word) -> Internet connecté ✅.
A test walks the real `BulkPlan.buyPhase` strings through it and checks
no title or hint contains L2CAP, PSM, BULK, GATT, probe or 256.

**Automatic continuation.** `BulkPlan.afterProbe(verdict)` is the rule the
node's hook applies: BIDIRECTIONAL -> start the contract, anything else
-> end the attempt. The VPN is requested by the existing
`TunnelClient.onSessionUp -> vpnRequested` path; MainActivity now shows
one sentence ("Prok a besoin de votre autorisation pour faire passer
Internet par le fournisseur à proximité" / CONTINUER) before Android's
own prompt, and once granted Android returns no intent and later sessions
start the VPN without asking.

**Failures.** `ProductState.lostHint` maps the probe failure to "La
connexion à proximité est trop faible. Rapprochez les téléphones et
réessayez.", Bluetooth off to "Le Bluetooth est éteint. Activez-le pour
vous connecter.", a provider that lost its upstream to "Le fournisseur a
perdu son Internet.", and everything unmapped to "Impossible de se
connecter à ce fournisseur. Réessayez.". The technical text stays in
Developer -> diagnostics.

**Stale Wi-Fi Direct group.** The proven OUKITEL diagnostic still listed
`p2p-wlan0-0 192.168.49.1` from earlier developer tests. `ProkNetNode.start`
now calls `P2pLink.clearStaleGroup()` unless the developer P2P lab is on:
the same Android-confirmed walk as STOP (cancel connect, stop discovery,
close sockets, remove group), which `P2pPlan.Life.start` runs "always,
even when this phone believes it is idle". Only the p2p interface is
touched; wlan0 and the Freebox are not. Cleanup only; no Wi-Fi Direct
debugging.

**Developer BT diagnostics** (the v0.10.2 test screen) stays under
Developer, renamed "BT diagnostics".

## The sequential probe (v0.10.2)

The v0.10.1 phones settled the question the probe existed for: Bluetooth
L2CAP between the OUKITEL and the OnePlus carries real data BOTH ways.
Seller -> buyer moved the full 1 MB; buyer -> seller moved 696, 802 and
966 KB across three runs and was then killed. The probe was the problem:
both phones pushed 1 MB at once through one ~30-34 KB/s channel under one
40 s clock, so the directions competed, one finished, the other got close
and timed out, and the verdict said "only seller -> buyer carried bytes",
which was false.

The probe is sequential now, and smaller:

```
PROBE BUYER_TO_SELLER   buyer sends 256 KB, seller confirms the full receipt
PROBE SELLER_TO_BUYER   seller sends 256 KB, buyer confirms the full receipt
PROBE COMPLETE          VERDICT: BIDIRECTIONAL -> contract -> tunnel -> VPN
```

`BulkPlan.probeSender(step, isHost)` says who sends in a step and nobody
sends outside one, so there is no full-duplex contention to measure.
Direction 2 does not start until direction 1 is confirmed by a receipt
report. Each direction has its own clock: the receiver waits 30 s, the
sender 36 s, so a receiver that times out reports what DID arrive and
both phones show the same numbers.

Every probe state change, including the byte count, runs on the main
thread: a receipt report and the next direction's first frames come from
the same read thread in order, and posting both keeps that order. The
counter of the old design lived on the read thread and could be reset by
a direction change in flight.

Verdicts stopped lying. Per direction: 0 B is `NO_DATA`, part of the
payload is `PARTIAL`, the whole payload is `PASS`. The link verdict is the
result of the first direction that did not pass, named:
`PARTIAL, buyer -> seller timed out`, with the bytes next to it.

`bad frame length 0` and `EOFException` were seen after the old timeout
cancelled the link mid-transfer. They are not chased in this version; if
they survive a clean sequential probe they get their own investigation.

## The test screen (v0.10.2)

The person testing is not a developer. `BtLabText` is a pure module: a
`Snapshot` of the node in, one plain sentence out, tested on the JVM. The
seller sees `Internet source: Wi-Fi`, `Bluetooth: Ready`, one button
START SHARING, then `Waiting for another phone...`, `Phone connected /
Testing connection...`, `Bluetooth connection works both ways / Internet
sharing starting...`. The buyer sees `Seller found`, one button CONNECT,
then `Connecting...`, `Checking both directions...`, `Starting
Internet...`, `INTERNET WORKING`. The screen starts the node and asks for
the permissions by itself, runs the HTTPS test through the seller by
itself once the tunnel is up, and asks for the VPN by itself. A failure is
one sentence. COPY TEST RESULT copies a short summary a reader can judge
without the log, then the full diagnostic.

## The runtime obeys the architecture (v0.10.1)

v0.10.0 claimed the home-Wi-Fi provider stays on its Wi-Fi and serves over
Bluetooth, and the runtime did not obey it. `setSelling` still ran the
hotspot capability probe, and on a refusal `startP2pFallback` created a
Wi-Fi Direct group. The archived transport could still appear under a normal
SELL.

One pure rule decides the seller's local link now, and Wi-Fi Direct is not
one of its answers:

```
BulkPlan.sellerAccessPath(upstreamIsWifi, bulkSupported, bluetoothOn)
  mobile-data upstream            -> HOTSPOT        (probes, as before)
  home Wi-Fi + Bluetooth on       -> BLUETOOTH_BULK  (no probe, radio untouched)
  home Wi-Fi + Bluetooth off/none -> NONE            (reported, never a silent group)
```

`onSharingReady` applies it when SELL comes up: only the HOTSPOT path runs
`HotspotProbe`, the BLUETOOTH_BULK path advertises `FLAG_BULK_BT` and waits
for a `BULK_REQUEST` without touching the Wi-Fi radio, and NONE is stated
plainly. The two automatic `startP2pFallback` calls in the share check are
gone.

Wi-Fi Direct is now structurally developer-only: `startP2pFallback` refuses
unless `p2pDeveloperEnabled` is set, and only the P2P lab entry points set
it. No SELL, BUY, share-check, network-change or provider-request path can
create a group.

Two related fixes. The BLE watchdog's `linkBusy` now includes the bulk
lifecycle (`bulk.state.active || bulk.linkedPeer != null`), so it never
restarts the radios during a `BULK_REQUEST`, `LISTENING`, `CONNECTING`,
`AUTH`, `PROBE` or `UP`. And `reachablePeers` keeps an authenticated bulk
peer reachable through a BLE scan gap, next to the Wi-Fi one, so routing and
the queue do not treat a live L2CAP peer as gone.

## Bluetooth bulk Internet (v0.10.0)

Wi-Fi Direct is archived. Both topologies were measured on the real pair and
neither carries a usable IP path: the OUKITEL as group owner is one way, the
OnePlus never forms a group. So the local link between the two phones is now
a **Bluetooth L2CAP connection-oriented channel**, and it asks Android for no
hotspot and no group, so the provider simply stays on its Freebox.

### The layering

```
BLE GATT (unchanged)              Bluetooth L2CAP (new)
  discover, identity, SELL          the authenticated ProkNet stream
  negotiate the bulk channel        signed HELLO/AUTH
  control, store-carry-forward      PACKET / RECEIPT / tunnel / relay
                                    the 1 MB probe, then the Internet tunnel
```

GATT never carries an Internet tunnel; it carries control and small packets,
as it always has. The tunnel only ever chooses between the two bulk links,
Wi-Fi and Bluetooth.

### One authenticated stream, two carriers

The upper half of the old Wi-Fi link is now `transport/StreamLink.kt`: the
signed handshake, the writer thread, PACKET/RECEIPT, tunnel and relay frames,
byte accounting. A Wi-Fi TCP socket and a Bluetooth L2CAP socket each present
themselves as a `StreamLink.Endpoint`, so both feed the same code and the
same security. `WifiTransport` was refactored onto it with its behaviour
unchanged; `BluetoothBulkTransport` is the new owner.

Nothing on either stream is trusted before `handshake()` verifies the peer's
Ed25519 signature. The Android L2CAP APIs are the "insecure" ones, which only
means no OS pairing dialog; a nearby phone that reaches the PSM and fails the
ProkNet handshake gets a closed socket.

### The negotiation

```
BUY pressed
buyer  -> seller (BLE):  BULK_REQUEST(session)
seller opens listenUsingInsecureL2capChannel(), gets a dynamic PSM
seller -> buyer (BLE):   BULK_OFFER(session, L2CAP, psm)
buyer  createInsecureL2capChannel(psm) to that EXACT BluetoothDevice
        (the peer's current BLE address; never matched by human name)
socket connected -> signed handshake -> BULK UP
```

Every message carries the purchase's session token, so a stale offer from an
earlier attempt cannot steer a later one, and every stage has its own bounded
timeout in `core/BulkPlan.kt` (the pure lifecycle: who may do what, in which
phase, with which session, for how long).

### Measure the link before believing it

`BULK UP` does not start the tunnel. First the transport sends 1 MB and
receives 1 MB, and reports each direction separately:

```
BLUETOOTH BULK PROBE
  buyer -> seller: 1,048,576 B OK, xxx KB/s
  seller -> buyer: 1,048,576 B OK, xxx KB/s
  VERDICT: BIDIRECTIONAL
```

Only `BIDIRECTIONAL` proceeds to the contract, the tunnel and the VPN. Any
other verdict fails the purchase with a Bluetooth-specific French sentence.
That is the lesson of the whole Wi-Fi Direct saga made into a gate: a socket
that connected is not a link that carries.

### Transport selection

`Routing.chooseTransport(wifiUp, bulkUp, bleReachable)`: an authenticated
Wi-Fi link, then an authenticated Bluetooth bulk link, then GATT for control.
The purchase decision (`P2pAdmission.buyPath`) sends a provider on its home
Wi-Fi, which advertises `FLAG_BULK_BT`, to the Bluetooth path; a provider on
mobile data keeps the proven hotspot; the lab can force Bluetooth for either.
The gateway, the tunnel, the contract, the billing, the VPN, the marketplace
and method A are unchanged: the bytes just travel on a different link.

## BUSY is not an attempt (v0.9.27)


v0.9.26 produced the first genuinely clean creation attempt on the OnePlus:
accepted, fifteen silent seconds with nothing but `pending` and `deferred`
lines, no group. Then:

```
12:20:13.617  nothing to remove (BUSY)
12:20:13.618  creating attempt 2
12:20:13.620  createGroup refused BUSY
12:20:16.631  creating attempt 3
12:20:16.636  createGroup failed after 3 attempts: BUSY
```

Attempts 2 and 3 never happened. Android was still settling the first
creation, said BUSY twice, and the counter treated that as evidence. One
real attempt, not three, and a screen that told the customer the provider
was busy with another phone while the provider sat idle on the Freebox.

### Two counters

`core/P2pCreation.kt` is a pure state machine: IDLE, CREATING, FORMING,
RESETTING, DONE, FAILED. A **logical attempt** is one Android accepted or
explicitly refused; there are three. A **reset try** is the framework asking
for time; there are eight, a second and a half apart. BUSY never consumes an
attempt, it consumes a reset try.

```
GROUP_CREATE_ATTEMPT 1/3    accepted, never formed
RESETTING_FRAMEWORK         cancelConnect, stopPeerDiscovery, removeGroup,
                            then requestConnectionInfo must say formed=false;
                            BUSY anywhere -> back off, try the reset again
framework clean             only now: GROUP_CREATE_ATTEMPT 2/3
```

Nothing is committed when `createGroup()` is called; the attempt number is
committed when Android answers. If the framework never leaves BUSY, the
machine ends in a bounded, specific failure after its eight tries.

### Typed failures

`P2pCreation.Fail` is NEVER_FORMED, FRAMEWORK_BUSY, REFUSED or PERMISSION.
Every reason text carries the same prefix, the link records the stage as a
typed value, and `stageOf` recognises the prefix and the word `createGroup`,
so a creation failure cannot fall through to stage NONE again.

### The screen tells the truth

A bare `BUSY` used to match the provider-busy sentence. That branch now
requires the provider to have said so. A framework that stayed busy on this
phone reads "Le Wi-Fi Direct de ce téléphone est encore occupé. Attendez
quelques secondes puis réessayez." Any other creation failure reads "Ce
téléphone n'a pas réussi à créer la connexion Wi-Fi Direct. Réessayez."
Neither mentions the provider.

### Late callbacks

Each purchase carries a session token. A visibility report from a peer with
no purchase running is ignored before any gate, a deferred report is
replayed only if its token still matches, and stopping a purchase bumps the
token so nothing from it can touch the next one.

## The admission plane is dormant while the owner creates its group (v0.9.26)

v0.9.25 held `formed=false` correctly inside the link, and the admission
layer above it carried on regardless:

```
12:03:48  createGroup attempt 3 accepted
12:03:48  group creation pending: formed=false is normal while CREATING_GROUP
12:03:49  admission: ... group formed=false role=NONE -> GUEST_CONNECT
12:03:55  starting peer discovery from a clean state
12:04:03  GROUP CREATE FAILED
```

A rule that lives in one entry point is not a rule. `onConnectionInfo` knew
that creation owns the radio; `onP2pVisibility` did not, decided a plan,
sent it, and its follow-up resumed discovery against `createGroup()`.

### One gate

```
admissionAllowed(topology, providing, stage, groupFormed, role)
  owner of the group:  stage == GROUP_OWNER && groupFormed && role == GROUP_OWNER
  guest:               stage != CREATING_GROUP
```

Every admission action asks it: a visibility report, a join plan, the owner's
wait step, the search clock, and through those the invitation, the connect
and discovery. While it says no, a visibility report is remembered and
answered with `admission deferred: buyer-owned group is still being created`,
and nothing else moves. The moment the group forms, the deferred report is
evaluated as if it had just arrived, so the guest's useful loop is never
interrupted and nothing it said is lost.

### A second net under discovery

`P2pLink.keepDiscovering` refuses outright while the stage is
CREATING_GROUP, whoever calls it and whyever, logging
`not starting discovery: group creation owns the radio`. Even a caller that
forgets the rule cannot scan against a pending creation.

The v0.9.24 seller cleanup and the v0.9.25 formation retry ladder are
untouched.

## Creating a group is its own stage (v0.9.25)

v0.9.24 took the right path and then Android was fought at its own game:

```
createGroup accepted (default band), waiting for the group to form
connection: formed=false role=NONE groupOwner=null
keeping Wi-Fi Direct discovery alive: the group is gone, this phone can look for peers again
starting peer discovery from a clean state
discoverPeers failed: BUSY (framework busy)
```

After an ACCEPTED `createGroup()`, Android is allowed to report
`groupFormed = false` while the group is still being created. That is
"pending", not "gone". Starting peer discovery in that window collides with
the creation, gets BUSY in a loop, and the group never forms.

### CREATING_GROUP owns the radio

`P2pPlan.onFormedFalse(stage, createAccepted)` answers HOLD while a creation
is in flight and GONE otherwise. On HOLD nothing happens except one log line:
no radio release, no discovery, no search-failure logic. Only three events
end a creation: `formed = true`, an explicit `createGroup` refusal, or its
own clock.

### An accepted group that never forms has its own clock

The old code retried only refusals. Each acceptance now arms
`GROUP_FORMATION_TIMEOUT_MS` (15 s). `P2pPlan.onFormationTimeout(stage,
formed, attempt, max)` answers FORMED, IGNORE, RETRY or FAIL: a retry removes
the half-made group and creates again, and the third silence is
`GROUP_CREATE_FAIL`, a stage of its own with its own French sentence. It is
never reported as peer visibility, because admission never began.

### The owner's stages are separate

```
GROUP_CREATION   until the owner's group exists
SEARCH           from formation, until an association is accepted
ASSOCIATION      from acceptance
TRANSPORT        from membership
```

`P2pPlan.searchedMs(...)` counts the search only from the moment the owner's
group formed, so creation time never consumes the search deadline, and the
owner's transport clock starts at membership rather than at formation. And an
owner never asks the provider whether the provider's group is ready: that
ladder belongs to SELLER_GROUP_OWNER only.

The seller side of v0.9.24 (joining as a client while keeping the Freebox,
and the one-unit cleanup) is untouched.

## The reversed experiment has to actually start, and actually end (v0.9.24)

The v0.9.23 run reached the provider with the topology and then tested
nothing, for two reasons that are both ours.

### A customer that owns the group must not wait for the provider to own one

```
buyer   topology BUYER_GROUP_OWNER
seller  TOPOLOGY = BUYER_GROUP_OWNER, telling the customer what this phone can see
buyer   WIFI_REQUEST -> prok-24e480e6 over BLE          <- the hotspot path
```

The purchase decision was `if (offer.p2p) startP2pBuy() else requestWifi()`.
A provider in BUYER_GROUP_OWNER mode drops its own group on purpose, so it
stops advertising `p2p`, so the customer fell back to the hotspot request.
A chicken-and-egg. `P2pAdmission.buyPath(topology, offerP2p, linkUp,
viaRelay)` now decides:

```
authenticated link already up   -> use it (through the relay if that is how it was sold)
BUYER_GROUP_OWNER selected      -> Wi-Fi Direct, whatever the offer says
offer advertises a group        -> Wi-Fi Direct
otherwise                       -> the hotspot request
```

Production is unchanged: the second line fires only when the customer chose
the experiment in the lab. The log says `BUY decision: forcing Wi-Fi Direct
because buyer owns the group` and `starting buyer-owned P2P group`.

### The provider's reversed session ends as one unit

After the customer had cancelled, the provider kept saying:

```
telling the customer what this phone can see: I cannot address the other phone
```

every few seconds, for minutes. The guest state was five loose fields that
nothing cleared. It is now `P2pAdmission.GuestSession` (the customer, the
plan, the two clocks) and `P2pAdmission.OwnerDecision` (the plan in force,
who holds it, the invitation clock), each reset as one unit by
`clearReversedSession`, which runs when:

- the customer sends a cancel (the customer now sends one whenever it stops a
  Wi-Fi Direct purchase),
- a different customer takes over,
- the customer goes back to the production topology,
- the provider stops sharing.

The guest ladder only ticks while `GuestSession.ticks(peer, providing,
linked, hasMember)` holds, so a stale loop cannot survive into the next
purchase. The Freebox connection is never touched by the cleanup: only the
temporary Wi-Fi Direct client attempt belongs to the session.

## The clean 2.4 GHz answer, and the reversed topology (v0.9.23)

v0.9.22 produced the first uncontaminated run of the whole experiment:

```
19:38:15.047  formed=true role=CLIENT groupOwner=192.168.49.1
19:38:15.059  DISCOVERY off
19:38:15.072  GROUP CHANNEL: 2.4 GHz ch 6 (2437 MHz)
19:38:31.109  LINK PROBE verdict: NO IP packet crossed the Wi-Fi Direct link in either direction
              (sent 10, unicast replies 0, broadcast replies 0, probes answered by us 0)
```

Admission, the membership handshake over BLE and TRANSPORT_READY all worked.
Discovery stopped the instant the group formed, so nothing was scanning
during the data window this time. **Forcing the group to 2.4 GHz does not fix
the provider-as-group-owner topology on these two phones.**

### Membership ends admission, for good

The same run then killed itself with a false reason:

```
19:38:31  association in flight for 16s
19:38:55  the provider could see this phone, but the invitation did not complete
```

The invitation had completed sixteen seconds earlier. `ladder(...)` did not
know that membership existed, so the association clock it had started before
the join eventually expired. It now takes `hasMember` and returns
MEMBER_JOINED forever once a client is on the link; the node clears the
admission clock at that moment and only the transport deadline can end the
session.

### A failure belongs to a stage

```
SEARCH_FAIL       neither phone could address the other
ASSOCIATION_FAIL  an invitation or a join did not complete
TRANSPORT_FAIL    the group exists and no IP packet crosses it
TUNNEL_FAIL / INTERNET_FAIL
```

`P2pAdmission.stageOf(reason)` files every ending, the log prints
`PURCHASE FAILED at stage ...`, and the customer's French sentence matches:
the v0.9.22 run was a TRANSPORT_FAIL, so the screen now says the direct link
was created and the network link between the two phones does not answer.

### The evidence survives cleanup

`core/P2pReport.kt` keeps one record per phone of the last attempt: time,
topology, role, group channel, home channel, peer, association, membership,
when discovery stopped, both IP addresses, the four UDP counters, TCP
accepted and connected, the verdict and the failure stage. Cleanup no longer
erases the decisive numbers, which is how the seller's counters for the
19:38 run were lost.

### The reversed topology

Every clean measurement so far has tested one arrangement: the provider owns
the group while staying on its home Wi-Fi. `P2pPlan.Topology` adds the other
one as a controlled experiment.

```
SELLER_GROUP_OWNER   production: the provider owns the group, the customer joins
BUYER_GROUP_OWNER    experiment: the customer owns the group, the provider joins
                     it as a client and keeps its Freebox connection
```

Who owns the group does not change who sells the Internet.
`P2pLink.startGroupOwner(providing)` and `startGuest(providing)` separate the
Wi-Fi Direct role from the ProkNet role, so the provider is still the
authenticated host whichever side created the group.

The admission plan is named by Wi-Fi Direct role now, so the same rule works
either way round:

```
GUEST_CONNECT   the phone that does not own the group joins it
OWNER_INVITE    the phone that owns the group invites the other in
WAIT            neither can address the other yet
```

The customer announces the topology over BLE when the purchase starts, the
provider obeys it, and the group owner is always the phone that decides the
plan. The toggle lives in the Wi-Fi Direct Lab; production is unchanged.

## An accepted association owns the radio and its own clock (v0.9.22)

Two lifecycle bugs, both visible in the v0.9.21 runs, and the first one
contaminated the 2.4 GHz experiment.

### Discovery restarted inside an accepted join

```
18:54:58.447  connect accepted
18:54:58.447  DISCOVERY off
18:54:58.470  stopPeerDiscovery refused: BUSY
18:54:58.472  connection formed=false
18:54:58.474  starting peer discovery again        <- wrong
18:55:04      group formed, LINK PROBE starts
18:55:15      LINK PROBE verdict: no packets
18:55:20      DISCOVERY off
```

Android emits `groupFormed = false` in the middle of its own join
choreography. v0.9.21 read that as "the attempt is dead, look again", so
scanning ran for sixteen seconds INTO the data-path window, which is exactly
the condition the v0.9.15 rule exists to prevent.

There is now one truth, and `keepDiscovering` refuses while it holds:

```
associationPending = owner != NOBODY && accepted && !hasMember && !failed
                     && (now - acceptedAt) < ASSOCIATION_TIMEOUT_MS
```

An attempt ends when membership forms, when Android refuses it, or when its
own clock runs out. A transient callback is none of those.

**And stopping discovery is now a post-condition of membership, not a
branch.** On a first join the group generation and the membership generation
change in the same observation; the group branch won, and the line that
stopped discovery lived in the membership branch. It is now unconditional:

```
advance the plane
if (now.hasMember) { stop discovery; the association is over }
then, independently: group change, membership change, endpoint, listener
```

### Choosing a plan is not starting an attempt

```
19:10:36.476  JOIN PLAN = SELLER_INVITE
19:10:36.478  the provider could see this phone, but the invitation did not complete
```

Two milliseconds. The purchase had been searching for thirty seconds, and the
deadline it was judged against had expired before the attempt existed.
Meanwhile Android had put a confirmation dialog on both phones and the user
was reading it.

So the clocks are separated:

```
search        from the start of the purchase, SEARCH_GIVE_UP_MS (60 s)
association   from the moment Android ACCEPTED a connect() or an invite(),
              ASSOCIATION_TIMEOUT_MS (40 s), long enough for a person to
              read a popup and tap Connect
```

`P2pAdmission.ladder(associationStartedAt, now, searchedMs)` returns SEARCH,
ASSOCIATING or GIVE_UP, and once an association has been accepted the search
time decides nothing at all. Both plans use the same semantics: the buyer
starts its clock when its `connect()` is accepted, and when the provider
announces SELLER_INVITE, because the provider invites in the same breath.

A refusal from Android ends the attempt immediately and a replan may follow.

## A group is a room, membership is admission (v0.9.21)

v0.9.20 made the right decision and then did nothing with it:

```
17:56:05.441  admission: the customer "OnePlus Nord CE 2 Lite 5G" cannot address me,
              and I can address "OnePlus Nord CE 2 Lite 5G" at 1e:4f:f2:19:36:ce
              -> SELLER_INVITE
              role = GROUP_OWNER, group formed = true, clients = 0
17:56:05      INVITING the customer into my group     never printed
```

`mayInvite` required `!groupFormed`, reading "a group exists" as "admission
is complete". For this architecture that is simply wrong: **the provider
creates and owns its Wi-Fi Direct group before any customer arrives**, so
`groupFormed` is true from the moment sharing starts, and the invitation it
had just decided on could never be sent.

The truth is membership, which the data plane already models:
`P2pDataPlane.Plane.hasMember` is a client count above zero for an owner, and
"I joined" for a client.

```
groupFormed      the room exists
hasMember        somebody is in it        <- admission is over
```

So `mayInvite`, `keepOwner` and `heldPlan` all take `hasMember` now, and
`mayInvite` takes `ownsGroup` separately, because a provider must own a group
before it can invite anybody into it. The state the invitation exists FOR is
exactly the one the phone was in:

```
role GROUP_OWNER, group formed, clients 0,
this phone can address the exact customer, plan SELLER_INVITE
-> INVITE
```

Two smaller corrections came with it. A customer waiting for an invitation
keeps reporting what it can see, because that report is what makes the
provider decide again if an invitation did not arrive; without it a failed
invitation had nothing to retry it. And every ending now names the stage that
failed:

```
the provider could see this phone, but the Wi-Fi Direct invitation did not complete
the customer could see the provider, but the Wi-Fi Direct join did not complete
neither phone could address the other over Wi-Fi Direct
```

The last run ended with the third sentence, which was false: the provider
could see the customer perfectly. Each has its own French wording on the
customer's screen.

## Admission is symmetric (v0.9.20)

Two real runs, two opposite failures:

```
CASE A (v0.9.11)  the buyer saw the seller; the OWNER's peer list showed the
                  buyer as 00:00:00:00:00:00 and could not identify it
CASE B (v0.9.19)  the seller saw "OnePlus Nord CE 2 Lite 5G" at
                  1e:4f:f2:19:36:ce, status available;
                  the buyer saw 0 peers, 0 real addresses, 0 join attempts
```

Every design so far has assumed one particular side can address the other.
Android does not guarantee that in either direction, so admission has to stop
assuming and start asking.

### The exchange

BLE already carries identity and intent. It now carries visibility too:

```
buyer  -> seller   P2P_VISIBILITY   canSee=false, my name is "OnePlus Nord CE 2 Lite 5G"
seller decides     it looks for exactly that name in its OWN peer list
seller -> buyer    P2P_JOIN_PLAN    SELLER_INVITE
seller             invites that exact peer
```

The decision is one pure function of two facts:

```
buyer sees seller                 -> BUYER_CONNECT   (preferred: it has formed groups)
buyer blind, seller sees buyer    -> SELLER_INVITE
neither sees the other            -> WAIT, both keep looking, bounded
```

It is taken on the provider, which is the only phone that holds both facts,
and both sides then obey the same plan. The seller's `invite()` machinery,
which has existed since v0.9.9 as a manual lab button, is now a real
coordinated admission path.

### Two rules that keep it safe

- **Never guess.** A phone "sees" the other only when a peer's NAME matches
  the identity exchanged over BLE and its address is real. No group owner
  fallback, no anonymous address, no lone available peer. `P2pAdmission.look`
  is the only way either side answers the question.
- **One attempt owns admission.** Once a side is told to act, the plan is
  held for `ATTEMPT_OWN_MS` (20 s), so a customer that suddenly sees the
  provider cannot start connecting while an invitation is in flight, and an
  invitation cannot be sent while a join is pending. An accepted association
  also stops discovery on the initiating side, and discovery returns by
  itself whenever an attempt did not produce a group.

The bounded end is unchanged, with a truer sentence: a purchase where neither
phone could ever address the other now fails saying exactly that.

## The radio lock is ruled out, and the band is the last lever (v0.9.19)

The v0.9.18 run finally produced the measurement with everything else in
place. The seller, with the Wi-Fi radio lock held:

```
15:32:18.114  LINK PROBE: a packet DID cross, U1 from 192.168.49.124, answering it twice
15:32:19.702  ... U2      15:32:21.307  ... U3      15:32:22.923  ... U4
15:32:24.313  LINK PROBE verdict: packets arrive here but our answers do not get back
              (sent 10, unicast replies 0, broadcast replies 0, probes answered by us 4)
```

Every one of the customer's probes arrived. The provider answered each one
twice, once unicast and once to the group broadcast address, and **neither
form came back**. The provider's own ten probes, five unicast and five
broadcast, got nothing.

So:

- the Wi-Fi radio lock does not fix the downlink,
- broadcast does not cross either, which rules out the two phones being
  unable to address each other,
- it is not the sockets, the binding, the listener, the membership or
  discovery. All of those are now correct and measured.

What is left is the radio. The provider's group follows its home Wi-Fi onto
5 GHz channel 48, so one radio is serving a home network and a group on a
single channel, and only one direction survives it.

### Ask for the other band

`P2pPlan.groupBand(staFreqMhz)` returns 2.4 GHz when this phone's own Wi-Fi
is on 5 GHz, and lets Android choose otherwise. The group is then created
with `setGroupOperatingBand(GROUP_OWNER_BAND_2GHZ)`, which needs a named
group, so the group carries a fixed name and a per-run passphrase.

It is a request, not an assumption. If Android refuses, the plain group is
created instead and the log says which happened; `GROUP CHANNEL:` then
reports what was actually granted.

### A provider whose customer left has to be findable again

The same run showed why nothing worked afterwards:

```
15:33:00.079  not starting discovery: this link already has a peer on it
16:14:17.991  (buyer) looking for it ... (0 seen, 0 with a real address): none addressable
```

v0.9.15 stops discovery when somebody joins, which is right, and never
started it again when they left. The client count reached zero a few
milliseconds after that decision was taken, so the provider sat there,
holding a group, invisible, for forty minutes. Discovery now comes back the
moment the link has no peer on it, from both the client count and the data
plane.

## Never guess who you are talking to (v0.9.18)

The screen behaved, the purchase ran, and the join ladder spent itself on a
printer:

```
13:55:22.482  joining the provider group: attempt 1/4 to 72:cb:dd:b9:a1:da   (C1 Pro)
13:55:22.505  connect accepted, waiting for the group
13:55:25.852  Android was busy: trying to join again: attempt 2/4 to 72:cb:dd:b9:a1:da
13:55:25.860  connect refused: BUSY (framework busy)
13:55:33.860  Android was busy: trying to join again: attempt 3/4 to 14:cb:19:f5:f9:fc
13:55:33.883  connect accepted for 14:cb:19:f5:f9:fc                         (HP DeskJet 2700)
13:55:34.493  attempt 4/4 to 14:cb:19:f5:f9:fc
13:55:37.867  could not join the provider Wi-Fi Direct group after 4 attempts
```

Two faults, both ours.

**The provider was chosen by guessing.** `pickSellerPeer` matched the name
the provider sent over BLE, and when that name was not in the peer list it
fell back to "any peer that owns a group". At 13:55:32 the provider had
dropped out of the list for a few seconds, and the only group owner left was
the printer. The fallback existed for a provider too old to send its name.
It is gone. A provider that is not in the list means WAIT, which is the same
rule as refusing to infer a buyer from `00:00:00:00:00:00`: **this network
does not guess who it is talking to.**

**An accepted join was overtaken by its own successor.** The next attempt was
held off only when Android REFUSED, so an accepted `connect()` was followed
three seconds later by another one, and the framework answered BUSY to us.
Now the next attempt is held off BEFORE asking, for
`P2pPlan.JOIN_ACCEPTED_WAIT_MS` (15 s), and only a refusal shortens that to
the busy backoff. An accepted join is waited for.

The log also names what it dials, `attempt 1/4 to "C1 Pro"
(72:cb:dd:b9:a1:da)`, and lists the addressable peers by name, so a wrong
target is visible at once instead of being a MAC address nobody recognises.

## A purchase starts from a clean screen (v0.9.17)

The v0.9.16 build was never exercised, because the screen ended every
purchase before it began:

```
13:28:44.291  UI: CONNECT pressed: prok-24e480e6 5 CFA/MB
13:28:46.815  asking prok-24e480e6 whether its Wi-Fi Direct group is ready
13:28:48.386  UI: Stop Internet pressed          <- the user, after "Connexion perdue"
```

Three attempts, three stops within four seconds, and the group never formed.
The diagnostic says why:

```
wifi: DOWN / DOWN (initiator with prok-24e480e6) - could not reach the host
      (10.168.138.1: ... EHOSTUNREACH ...) (retry allowed in 5s)
```

That is the HOTSPOT transport, holding a failure from an attempt minutes
earlier. `ProductState.buyer` turns any phase beginning with `DOWN` into
`LOST`, and the consumer screen was reading that transport even when the
purchase was going over Wi-Fi Direct. So the first refresh after CONNECT said
"Connexion perdue", every time.

Two rules now:

- **A purchase is judged by the transport it uses.** `ProkNetNode.buyPhase()`
  is the single place that decides: the Wi-Fi Direct link during a Wi-Fi
  Direct purchase, the hotspot transport otherwise.
  `P2pPlan.buyPhase(stage, groupFormed, planeUsable, linked)` is the pure
  mapping, so FINDING, JOINING, TCP and AUTH mean the same thing on both
  paths.
- **A purchase starts from a clean screen.** `buy()` clears the buyer error,
  the tunnel error and any leftover hotspot failure before anything starts.
  A failure from the last attempt can never end the next one.

## The link is one way (v0.9.16)

The v0.9.15 probe did its job on the first run, and the two phones returned
different verdicts, which IS the finding:

```
buyer  (client)  LINK PROBE verdict: NO IP packet crossed the Wi-Fi Direct link in either direction
                 (sent 5, replies 0, probes answered by us 0)
seller (owner)   LINK PROBE: a packet DID cross, 10 bytes from 192.168.49.124   x6
seller (owner)   LINK PROBE verdict: packets arrive here but our answers do not get back
                 (sent 5, replies 0, probes answered by us 3)
```

Read together: **every packet the client sent reached the owner, and nothing
the owner sent reached the client.** Not the UDP answers, not its own probes,
not a TCP handshake in either dial direction. The uplink is perfect and the
downlink is dead.

That also explains every failure since v0.9.12 in one line. The buyer's SYN
arrives, the owner's SYN-ACK never comes back, so the buyer times out and the
owner's `accept()` never completes, which is why `TCP accepted` was never
printed even though the listener was correct all along.

It is not a socket problem, it is not a binding problem, and it is not the
listener lifecycle. Those are all correct now and the measurement proves it.

### What an application can do about it

A group owner has to buffer frames for a client whose radio is asleep and
deliver them at the beacon. On a phone whose single radio is also serving a
home Wi-Fi connection, that delivery is a known place for frames to die. The
driver is not ours, but the sleep is refusable:

- **`RadioLock`** holds `WIFI_MODE_FULL_HIGH_PERF` and, on API 29+,
  `WIFI_MODE_FULL_LOW_LATENCY` for as long as a group exists on this phone,
  on BOTH sides, and releases them the moment it is gone. Holding a Wi-Fi
  lock during a data transfer is what Wi-Fi Direct expects of an application
  in the first place.
- **The group channel is logged** next to this phone's own Wi-Fi channel, so
  a group forced onto the home Wi-Fi channel is visible rather than assumed.
- **The probe separates unicast from broadcast**, in both directions. Each
  side now answers a probe twice, once to the sender and once to the group
  broadcast address, and the verdict has a fourth value: `only BROADCAST
  crosses: the two phones cannot address each other directly`. That
  distinguishes a radio that drops everything from two phones that cannot
  resolve each other, which need different answers.

## The radio has to be on the group channel (v0.9.15)

v0.9.14 removed the last socket-level doubt, and the phone run came back
with the cleanest possible result:

```
seller  p2p-wlan0-27 = 192.168.49.1   GROUP_OWNER   network 159   clients 1
seller  listener 192.168.49.1:47742   generation 1.1   valid for this live membership
seller  DIAL 1..6  192.168.49.1 -> 192.168.49.124:47742   binding ANDROID_NETWORK
buyer   p2p0 = 192.168.49.124         CLIENT        generation 1.1
buyer   listener 192.168.49.124:47742 valid for this live membership
buyer   DIAL 1..6  192.168.49.124 -> 192.168.49.1:47742   binding LOCAL_ADDRESS
BOTH    every attempt: SocketTimeoutException after 4000 ms
```

Two listeners, both armed for the live membership, both bound to their own
P2P address, dialling each other, and twelve timeouts. Not one refusal, not
one unreachable: nothing answered in either direction. **That is not a
socket problem. No IP packet crosses the link at all.**

What both logs also show, every thirty seconds, right through the dial
window:

```
starting peer discovery from a clean state
discoverPeers accepted
```

Wi-Fi Direct discovery makes a single-radio phone LEAVE the group channel to
scan the social channels, and Android keeps a find running for about two
minutes once it is accepted. The association survives that, because beacons
do. A four second TCP handshake does not.

### The rule

Discovery belongs to **admission**, and admission ends when somebody has
joined. It is tied to membership, not to the group, so nothing about the
proven admission path changes:

```
seller sharing, group empty     -> discovery ON   (a buyer must still find it)
buyer looking for the seller    -> discovery ON
somebody joined (clients 0 -> 1)-> discovery OFF, on both phones, in the
                                   framework, not only in our own loop
the group is gone               -> discovery ON again
```

### Measure the link, do not guess at it

A timed out SYN says nothing about why. So each side now opens a UDP echo on
its own P2P address, port 47743, for as long as its listener lives, and the
dialling side sends a few probes:

```
LINK PROBE listening on 192.168.49.1:47743
LINK PROBE 1/5 to 192.168.49.124: REPLY in 14 ms
LINK PROBE verdict: the link carries IP packets both ways
```

or, if the link really is dead:

```
LINK PROBE verdict: NO IP packet crossed the Wi-Fi Direct link in either direction
```

`ONE_WAY` is the third possible answer: their packets reach us and ours do
not get back. Whatever the next run shows, the log now names which of the
three it is, and that decides whether the remaining problem is the radio,
the routing, or something in between.

## Membership is part of the transport (v0.9.14)

v0.9.13 gave the socket an endpoint identity. The phone run then produced
the most useful failure of the whole experiment:

```
seller  p2p-wlan0-26 = 192.168.49.1   GROUP_OWNER   network 158
seller  listener 192.168.49.1:47742   generation 1  accepting true
seller  CLIENT COUNT 0 -> 1           listener check says VALID
buyer   p2p0 = 192.168.49.124         android network none
buyer   socket bound to P2P network=false
buyer   DIAL 1..6 -> 192.168.49.1:47742   all six timed out
seller  TCP accepted                  never
```

Every field matched, our own validator said valid, and nothing was
reachable. So "valid" was measuring the wrong thing.

### What the v0.9.9 comparison says

The 70 minute session that worked ran this same socket code. At commit
`757a41d` the listener was created at group formation, on `0.0.0.0`, and the
buyer dialled with a plain unbound socket. Both runs create the listener
BEFORE any client exists, both keep discovery alive, both dial from the
client on `onConnectionInfo`. The only differences are:

- **who associates.** In v0.9.9 the OWNER called `connect()` to invite the
  guest. Since v0.9.12 the CLIENT joins by itself, because the owner's peer
  list anonymises the buyer. That is the one behavioural change between a
  data path that carried 34 MB and one that carries nothing.
- v0.9.13 narrowed the listener from every interface to the P2P address,
  which is strictly more correct and cannot explain a lost SYN.

So the lesson from the old success is not "bind harder". It is that a
listener which existed before the client joined is not evidence of anything,
and that the data path must not depend on which side happened to start the
association.

### The lifecycle

```
group formed -> endpoint exists -> client membership established
             -> a data plane generation becomes usable
             -> listener and dial belong to THAT generation
```

`core/P2pDataPlane.kt` (pure) carries two counters. `groupGeneration`
changes when the group or its endpoint changes. `membershipGeneration`
changes when this phone gains a live peer: an owner whose client count
reached one, or a client that joined. A listener from group 4 / membership 0
is not the listener of group 4 / membership 1, and `validate` says
`STALE_MEMBERSHIP` by name. Nothing is usable, and no socket is opened,
before `usable` is true.

### Both phones dial

Android can bind an OUTGOING socket to a network (`Network.bindSocket`) and
offers no public way to bind a LISTENING one. The provider is the phone that
also holds a home Wi-Fi network, so it is exactly the phone whose listening
socket cannot be tied to Wi-Fi Direct. It therefore does not depend on being
dialled:

```
buyer joins -> buyer is a member at once, arms its own listener
buyer -> seller (BLE):  P2P_MEMBER  192.168.49.124:47742
seller observes clients 0 -> 1, membership generation 1
seller arms the listener FOR THAT membership
seller -> buyer (BLE):  TRANSPORT_READY  192.168.49.1:47742  membership 1
buyer dials the seller          seller dials the buyer
first authenticated socket wins, the link state machine closes the loser
```

`GROUP_READY` now means only "you may join my group". `TRANSPORT_READY`
means "you are joined and my listener is armed for your membership". A buyer
that hears nothing still dials by itself after a bounded 8 s, which also
covers the developer lab where there is no BLE channel.

### Every socket says how it is bound

```
android network exists -> Network.bindSocket(socket)      binding ANDROID_NETWORK
else a P2P address     -> socket.bind(192.168.49.124, 0)  binding LOCAL_ADDRESS
else                   -> refuse to open the socket       binding NONE
```

The OnePlus buyer reported `android network none` while holding
`p2p0 = 192.168.49.124`, and then dialled unbound, which is a guess about
routing. A guess is what timed out six times, so an unbindable socket is now
a transport error with a message, never a dial.

The provider upstream is untouched by all of this: only the local transport
socket is tied to Wi-Fi Direct, and a `p2p...` interface stays a LOCAL link
that can never be an upstream.

## A socket belongs to an endpoint (v0.9.13)

v0.9.12 proved the topology on real phones, and nothing moved:

```
seller  wlan0 = 192.168.1.13           still on the Freebox
seller  p2p-wlan0-25 = 192.168.49.1    GROUP_OWNER, clients 1
buyer   p2p0 = 192.168.49.124          CLIENT, groupOwner 192.168.49.1
seller 09:08:43  group owner listening on :47742
buyer  09:10:1x  192.168.49.124 -> 192.168.49.1:47742   x6, all timed out
seller           TCP accepted:         never
```

The listener was opened once, when the group formed, on every interface
(`0.0.0.0`), and was then believed forever because the object was not null.
Ninety seconds later the buyer joined a group whose endpoint that socket did
not necessarily belong to any more. **A server socket object is not proof
that a usable server exists.**

So the socket is given an identity, in `core/P2pEndpoint.kt` (pure) and
`transport/P2pSocketBinding.kt` (the only place that touches Android
networks):

```
Endpoint  = generation + role + interface + local address + android network
Listener  = the endpoint generation it was built for + what it really bound to
validate(endpoint, listener) -> VALID | STALE_GENERATION | WRONG_ADDRESS |
                                WRONG_INTERFACE | WRONG_NETWORK |
                                NOT_ACCEPTING | NO_LISTENER | NO_ENDPOINT
```

- **Bound to the endpoint, not to the world.** The owner listens on
  `192.168.49.1:47742`, taken from the live P2P interface, and the buyer
  binds its dial socket to the P2P `Network` before connecting. Both log
  `socket bound to P2P network=true/false`, so a dial that left through the
  wrong network is visible instead of silent.
- **A new generation only for a real change.** Role, interface, local
  address, Android network, or the group being destroyed and recreated give
  a new generation. Anything else leaves the endpoint, and its listener,
  exactly as they are.
- **Validation when the client arrives.** On `clients 0 -> 1` the endpoint is
  read again and the listener is checked against it. It is replaced only if
  the check says it is not this endpoint's listener. This is endpoint
  validation, not "restart because maybe".
- **A generation and a token on the accept loop.** A loop from an older
  lifecycle that comes back with a connection is refused and the connection
  closed, so a stale listener can never feed a newer group.
- **The upstream is untouched.** Only the local buyer/seller transport socket
  belongs to Wi-Fi Direct. The provider gateway keeps choosing the real
  upstream network exactly as before, and a `p2p...` interface stays a LOCAL
  link that can never be an upstream. LOCAL LINK = P2P, UPSTREAM = the home
  Wi-Fi.
- **The spinner ends.** Once the group is formed the whole transport has
  `P2pPlan.TRANSPORT_GIVE_UP_MS` (45 s, more than the six dial attempts) to
  come up. After that the purchase fails with
  "Connexion locale créée, mais le fournisseur ne répond pas." and is torn
  down cleanly; the seller keeps sharing.

## The buyer joins by itself (v0.9.12)

The next run had everything healthy on both sides and a real group on the
seller, and still nobody joined:

```
seller: sharing by Wi-Fi Direct: GROUP OWNER clients 0
seller Wi-Fi Direct peer list: 00:00:00:00:00:00  available
seller BLE at the same moment:  prok-0f7d57b3  rssi -38
```

Android anonymises the buyer in the OWNER's peer list on these phones, so
owner-side identification cannot be the main path, and inferring the buyer
from `00:00:00:00:00:00` would be guessing. The buyer, on the other hand,
sees the seller correctly with its real P2P address.

So the roles are swapped again, and this time in the direction the phones
actually support:

```
seller creates the group and advertises the way in ONLY while groupFormed
buyer picks the offer in the normal app
buyer -> seller (BLE): is your group ready? (+ the buyer's own P2P name)
seller -> buyer (BLE): GROUP_READY | REBUILDING_GROUP | NOT_AVAILABLE,
                       with the SELLER's own Wi-Fi Direct name
buyer finds that name in ITS OWN peer list, with a real address
buyer calls connect() -> joins the existing group -> seller clients = 1
socket, signed authentication, tunnel, VPN, accounting: unchanged
```

- `P2pPlan.groupStatus(sharing, groupFormed, isOwner)` produces the answer;
  `Wire.OP_P2P_STATUS` carries it with the seller's name.
- `P2pPlan.anonymous(address)` refuses `00:00:00:00:00:00`,
  `02:00:00:00:00:00` and empty addresses everywhere, and
  `pickSellerPeer(peers, name, groupOwners)` matches the seller by name,
  falling back to "the peer that owns a group" for an older seller build.
- `P2pPlan.joinStep(...)` -> ASK_STATUS, WAIT_PEER, CONNECT, RETRY_BUSY,
  WAIT_REBUILD, FAIL_NOT_AVAILABLE, GIVE_UP, DONE. A busy framework is
  retried at 3, 6, 12 and 24 s, four attempts, and a minute ends it with a
  readable French sentence.
- Owner-side inviting is kept in the Wi-Fi Direct Lab as a manual fallback;
  nothing in the normal path depends on it.

## The refusal has to be heard (v0.9.11)

The next run had a perfectly healthy radio (`adv on since 608s`, 1587 scan
results, no GATT timeouts, no recoveries) and still failed. The log shows
the shape exactly: every four seconds the buyer asked to be invited, the
request was DELIVERED, **the seller answered with a 50 byte control message**,
and the buyer did nothing with it, six times, until the 45 s ladder gave up
and the screen fell back to the offer list with no explanation.

Three faults, all of them ours:

1. **The answer was thrown away.** A WIFI_CANCEL is handed to the hotspot
   transport, whose state machine is idle on the Wi-Fi Direct path, so it
   dropped it in silence. The node now routes a cancel to the Wi-Fi Direct
   buyer when that is what is waiting, and the attempt ends at once with the
   provider's own words.
2. **The seller advertised a way in it did not have.** `p2pFallbackActive`
   was set as soon as sharing started, before the group existed, so buyers
   were invited to a door that was not there.
   `P2pPlan.advertiseP2p(sharing, groupFormed)` now gates the advert on the
   group really being formed, and the advert is refreshed whenever the group
   appears or disappears. `createGroup` also retries three times, because
   the framework answers BUSY right after a cleanup.
3. **A seller asked for something it advertised now rebuilds it.**
   `P2pPlan.admission(sharingByP2p, groupFormed, isOwner)` -> INVITE,
   REBUILD_GROUP or REFUSE. Only a phone that is not sharing that way says
   no; a phone whose group died recreates it and the buyer's next ladder
   step succeeds.

Two smaller things from the same log: the buyer asked once per 4 s tick
instead of once per ladder step (now one request per 10 s), and
`stopInternet()` cleared `lastBuyError`, so every failure message was wiped
before the screen could show it. That is why it "searched forever then went
back to the offer page". Failures now go through one `failBuy(logReason,
userError)` that stops first and keeps the reason.

## Self-healing BLE (v0.9.10)

A 70 minute Internet session over Wi-Fi Direct ended with "connection
closed: end of stream". Both phones were then side by side and saw nobody:
the known seller expired after 25 s, every control message answered "no
transport", two GATT reconnects timed out after 20 s each, and the
diagnostic still said `server ready, adv on, scan on`. Restarting the
service changed nothing because the node considered itself running.

The requested state was not the truth, so the truth is now recorded and
judged separately.

- **What is measured** (from the callbacks, not from intent): when
  advertising was confirmed and its last failure; whether `startScan` was
  accepted, its last failure, how many results have ever arrived and when
  the last one did; consecutive GATT timeouts and the last success; the
  adapter state; whether a Wi-Fi or Wi-Fi Direct session just ended.
- **`core/BleHealth` (pure)** turns that into a verdict: HEALTHY,
  NOT_RUNNING, BLUETOOTH_OFF, BUSY, COOLING_DOWN, ADVERTISING_STALE,
  SCAN_STALE, BOTH_STALE, GATT_WEDGED. Silence only counts as a fault when
  company is expected (`expectPeers`: buying, selling, or a phone seen in
  the last 15 minutes), so a phone alone in a field is never restarted.
  After a session the stale window drops from 40 s to 15 s because the
  stack is the prime suspect.
- **`BleTransport.recoverRadio(why)`** stops and recreates ONLY the scanner
  and the advertiser, re-applying the advertised flags and price so a
  seller's offer goes straight back on the air. The GATT server is
  restarted only if it is itself not ready; the identity, the queue, the
  node and any live link are untouched.
- **The watchdog** runs every 10 s inside the foreground service, never
  while a link is up or a group is being formed, and backs off after each
  recovery: one minute, then double, capped at five.
- **The admission ladder pauses.** `P2pPlan.guestTick(controlAvailable,
  reachableMs, unreachableMs, ...)` returns PAUSED when the provider is not
  reachable over BLE, so nothing is sent to a dead transport; the 45 s
  admission timeout only counts time when the control path was really
  there, and 90 s out of range is its own clear failure.

The consumer app shows none of this. It goes back from "0 personnes" to
seeing phones and offers on its own; the developer diagnostic carries the
health line and what recovery happened.

## The group owner invites (v0.9.9)

The v0.9.8 run narrowed the failure to the join itself. The seller's group
forms, it stays on the Freebox, it sees the buyer in its peer list, but
`clients` stays 0; the buyer taps, `connect()` is accepted, the seller turns
"invited", and no group ever forms on the buyer.

That direction cannot work: a phone that already OWNS a group cannot join
another one, so the buyer's invitation has nowhere to land. The invitation
must travel the other way.

```
seller creates the group and stays on the router
buyer becomes discoverable and WAITS
buyer -> seller over BLE: P2P_REQUEST("<its Wi-Fi Direct name>")
seller matches the name in its peer list and calls connect() = an invitation
buyer joins, seller clients = 1
group owner accepts the TCP socket, the existing transport adopts it
tunnel, VPN, contract, checkpoints: unchanged
```

- `P2pPlan.joinRole(iOwnAGroup)` -> OWNER_INVITES / GUEST_WAITS.
- The buyer cannot send its own P2P MAC: Android hides a phone's own
  address since Android 10. It sends its device NAME over the BLE control
  channel (`Wire.OP_P2P_REQUEST`) and `P2pPlan.matchPeer` finds it in the
  owner's peer list (exact, then case-insensitive, then contains).
- No silent waiting: `P2pPlan.guestStep(elapsed, groupFormed, ownerVisible)`
  -> WAIT, ASK_AGAIN at 12 s, TRY_MYSELF at 24 s (only if the owner is
  visible, since Android sometimes wants the guest to move), GIVE_UP at
  45 s with a real message.
- A refusal has its own code, `Wire.CANCEL_P2P`, carrying the reason ("the
  provider has no Wi-Fi Direct group right now", "the provider cannot see
  this phone in its Wi-Fi Direct peer list").
- Discovery is kept alive on both sides while waiting, because Android
  stops it after a couple of minutes.

## From the normal screens (v0.9.9)

The experiment is no longer developer-only:

- Seller taps **PARTAGER INTERNET**. Mobile data upstream keeps the
  hotspot. A Wi-Fi upstream is probed as in v0.9.6, and when the hotspot is
  refused on that network the phone now creates a Wi-Fi Direct group
  instead of giving up, and advertises it with a new BLE flag
  (`Market.FLAG_P2P`, bit 6). The sharing card says "Partage activé par
  liaison directe entre téléphones (essai)" and nothing else changes.
- Buyer taps **OBTENIR INTERNET** and picks the offer. If the offer carries
  the flag, `buy()` runs the Wi-Fi Direct path instead of a hotspot
  request. The buyer sees the usual French states and never the words P2P,
  group owner or invitation.
- Everything above the link is untouched: the socket is adopted by
  `WifiTransport`, so the signed handshake, the tunnel, the VPN, the
  contract and the checkpoints are the same code as method A.

The Wi-Fi Direct Lab stays for diagnostics, with the peer list now
inviting when this phone owns the group.

## The P2P lifecycle is deterministic (v0.9.8)

The first phone run found a cleanup bug, not a physics answer. After STOP
and a later BUY, a buyer still showed `phase DISCOVERING`, `group
ssid=DIRECT-...`, `p2p0=192.168.49.1` and `socket listening :47742` while
finding no peers, because v0.9.7 fired `removeGroup` and forgot it while
wiping its own state at once.

Every role change (SELL, BUY, STOP) now walks the same cleanup, in this
order, waiting for Android to answer each step:

```
cancelConnect -> stopPeerDiscovery -> close sockets -> removeGroup -> start the new role
```

- `P2pPlan.Life` (pure) owns every visible field. `peers` is cleared when
  discovery stops, the listening socket when it is really closed, and the
  group, the role and `groupFormed` only when Android confirms
  `removeGroup`. Nothing is cleared early, nothing is left behind.
- A broadcast that arrives DURING a cleanup (a connection or group change
  from the group being torn down) is ignored, so it cannot resurrect the
  old group.
- Each step has a 4 s watchdog: if the framework never answers, the step is
  logged as a timeout and the walk continues rather than wedging.
- A newer cleanup cancels an older one (`cleanupSeq`), so double taps are
  safe.
- The log prints `CLEANUP started`, one line per step with Android's own
  answer (`ok`, `nothing to undo / refused: BUSY`, `no answer within 4s`),
  and `CLEANUP complete` with the resulting state and the live interfaces;
  the diagnostic also carries a `clean` flag that says whether anything
  from a previous role survived.

## Wi-Fi Direct experiment, method B (v0.9.7) - NOT PROVEN

Phone evidence: a seller joined to a home router (Freebox) cannot create a
LocalOnlyHotspot, so no buyer reaches it; the same seller with Wi-Fi off and
mobile data on works. Selling home, shop or public Wi-Fi therefore needs a
local link that can exist WHILE the seller stays joined to that router.

```
home router
     |   the seller stays connected: this is what must survive
seller phone
     |   Wi-Fi Direct group
buyer phone
```

- **Method A stays the only path the consumer app uses.** Method B lives in
  a developer screen (Relay Lab -> WI-FI DIRECT LAB) and changes nothing
  else.
- `core/P2pPlan` (pure): `role(groupFormed, isGroupOwner)`,
  `socketTarget` (the group owner listens, the client dials it, whichever
  way Android decided), `ready`, `verdict` (NO_GROUP /
  GROUP_BUT_STA_LOST / LINK_FAILED / LINK_UP_STA_KEPT) and
  `isLocalLinkIface` so a `p2p*` interface can never be chosen as an
  upstream. Its own port, 47742, so both methods can listen at once.
- `transport/P2pLink` (Android): `WifiP2pManager` discovery, `createGroup`
  on the seller, `connect` on the buyer, the four P2P broadcasts, group and
  connection info, a server socket on the group owner and a dialling client
  with retries. It records the phone's own Wi-Fi network **before and
  after** the group forms, because a group that kills the STA connection is
  a failure even if bytes flow.
- **The data path is not new.** `P2pLink` only produces a connected socket;
  `WifiTransport.adoptSocket(socket, isHost, medium)` then runs the same
  signed handshake and the same framing, so the tunnel, the VPN, the
  contract and the checkpoints above it are the existing code. In
  `LinkState`, `adopt(asHost)` goes straight to HANDSHAKE from idle and is
  refused while method A is busy.
- Seller upstream safety: `Gateway` now marks any `p2p*` interface as a
  local ProkNet link, so a customer's traffic can never be routed back into
  the P2P group instead of out to the router.

Success is only what the phones show: the seller still on the router, the
buyer browsing through it. Until then this is an experiment, and
`docs/TESTING.md` section 25 is how it is run.

## Can this phone resell its own Wi-Fi? (v0.9.6)

A provider serves a customer through a local-only hotspot. On most chipsets
that hotspot must use the channel the phone's own Wi-Fi connection already
sits on, so a phone joined to a 5 GHz or DFS network is often refused with
`ERROR_NO_CHANNEL`. Android gives an app no way to choose the band, so the
only honest answer is to test it.

- `core/ShareCheck` (pure): `needed(upstreamType)` (only a Wi-Fi upstream
  can clash), `shouldProbe`, `verdict`, `canShareWhileOnWifi` (null =
  never tested, and **unknown is not a refusal**), `key(ssid, bssid)` (one
  answer per network, BSSID first), and `band` / `channel` / `isDfs` /
  `describe` so every failure is recorded with its frequency.
- `node/HotspotProbe` (Android): starts a local-only hotspot, closes it
  immediately, reports started or the exact Android error. 15 s cap.
- `ProkNetNode.checkSharing(why, force)` runs it when SELL is switched on
  and whenever the upstream network changes, never while a link is in use.
  The verdict, the detail and the frequency are stored per network key in
  `proknet_share_cap`, so the answer is instant next time. The Relay Lab
  has a TEST SHARING button to force a fresh run.
- The consumer screen shows one sentence, only for the phone and network
  concerned: "Ce téléphone ne peut pas partager ce réseau Wi-Fi. Vous
  pouvez partager vos données mobiles à la place." There is no global
  advice to switch Wi-Fi off.

In the coverage engine:

- `CoverageNode.canShareWhileOnWifi` (default true; false only after a
  tested refusal) and `InternetSource.wifiBased` / `frequencyMhz` / `band`.
- `Coverage.canDeliver(node)` gates every place the planner picks a
  provider (direct, relayed, fundable, mover), and `score` refuses such a
  route with a reason as a second line of defence.
- The source is **not** removed from the map: `observedSources` keeps every
  source seen, `deliverableSources` is the subset someone present can hand
  over today, and `blockedSources` explains the difference. A Wi-Fi network
  that this phone cannot resell stays a candidate for a capable phone
  later.

## Next transport experiment (not started)

Wi-Fi Direct (P2P group owner) coexists with a station connection more
freely on many phones, and the Relay Lab already reports whether P2P is
supported. It is the next candidate if the probe shows that many phones
cannot resell Wi-Fi, and it comes after the 3-phone relay retest.

## Carrying the host's error to the user (v0.9.5)

WIFI_CANCEL now carries `[reason byte][detail utf8]`, the detail being the
host's own error text, capped at 120 bytes (an older build sends neither,
which still parses). So the phone that is waiting can log and show what
Android told the OTHER phone, without anybody opening it:

| host error | what the buyer's screen says (French) |
|---|---|
| Location services off | the provider must turn Location on |
| ERROR_INCOMPATIBLE_MODE / tethering | the provider must turn its Android hotspot off |
| ERROR_NO_CHANNEL | the provider must leave Wi-Fi and use mobile data: its phone cannot share the Wi-Fi channel |
| SecurityException | the provider must grant Nearby devices and Location to Prok |
| anything else | turn Wi-Fi and Location on, then try again |

On the host side: Location services are checked BEFORE calling Android (it
refuses a local-only hotspot without them, whatever the permissions say),
the current Wi-Fi network is logged with the attempt, and a failure is
retried once after closing any reservation this app may still hold.

An explicit refusal also clears the retry budget
(`LinkState.forgetFailures`): the exponential backoff exists for attempts
that failed silently, not for a peer that answered "no" in one second. The
user may press CONNECT again immediately.

## Answering a link request (v0.9.4)

The v0.5 rule ignored a WIFI_REQUEST, with no answer at all, whenever this
phone was already UP or busy negotiating. A seller still holding a link
from an earlier test therefore refused every customer for ever, and the
customer saw only "searching..." until its own timeout. Every case now has
an answer, decided by the pure `LinkState.hostAnswer(peer, myShort,
linkInUse)`:

| situation | answer |
|---|---|
| UP with the same peer (its side is gone, it would not ask otherwise) | drop the stale link, host |
| UP with someone else, a session really running | refuse, send WIFI_CANCEL(BUSY) |
| UP with someone else, link idle | drop the idle link, host the newcomer |
| both asked at once, the other ID is lower | ignore, it hosts |
| mid-negotiation for this same peer | tear it down, start over |
| mid-negotiation for someone else | refuse, send WIFI_CANCEL(BUSY) |
| idle | host |

`linkInUse` comes from the node: a gateway session, a tunnel session or a
relay session. An idle link is never a reason to refuse a customer.

WIFI_CANCEL now carries a reason byte (`CANCEL_NO_HOTSPOT`, `CANCEL_BUSY`,
generic). A build older than v0.9.4 sends no byte, which reads as generic,
so the change is backward compatible. A host that gives up before the link
came up (hotspot failure, step timeout) sends one too, so the waiting phone
stops immediately instead of running its own timeout.

## Why a link setup fails (v0.9.3)

One 120 s timeout for every step told the user nothing and took two
minutes. Now:

- `LinkState.stepTimeoutMs(state)` (pure, tested): REQUESTING 60 s,
  HOSTING 45 s, HANDSHAKE 30 s, and 120 s only for the two steps that wait
  for a human to tap Android's "connect to this device?" dialog (OFFERING,
  JOINING). `WifiTransport.timeoutReason(step)` turns the step that ran out
  into a sentence.
- The host says so immediately. When `startLocalOnlyHotspot` fails (Wi-Fi
  off, Location off, tethering active, no channel) the host sends a
  WIFI_CANCEL over BLE, so the initiator stops in seconds instead of
  waiting for its own timeout.
- `ProkNetNode.lastBuyError` keeps the reason after the attempt is cleared,
  so the screen can still explain it while the purchase state is already
  free for the next action.
- `ProductState.lostHint` classifies it into three actionable French
  sentences (nobody answered / could not create the hotspot / the network
  was not joined) plus the radio case and a generic one.
- The seller's own screen warns when Wi-Fi or Location is off, because the
  hotspot is created on the SELLER side: that is where the fix is.

## Language (v0.9.2)

The consumer app speaks **French**: the five tabs, every dialog, every
toast and the notification. Two places hold those words and nothing else
does:

- `res/values/strings.xml` for the layout and the Activity
  (`getString(...)`, with `%1$s` arguments for names and amounts);
- `core/ProductState` for everything derived from engine state, still pure
  and unit-tested, now asserting French. Numbers are formatted with
  `Locale.FRANCE` explicitly (comma decimal, `o / Ko / Mo / Go`), never the
  phone's locale, so a phone set to English still shows the same figures.

The developer screen, the Relay Lab and the whole log stay in **English**
on purpose: they are engineering tools, read by the team and pasted into
reviews. Engine strings (`WifiTransport.phase`, `TunnelClient.state`,
`lastError`) also stay English; they are protocol values, and
`ProductState` is what turns them into words. Adding another language later
means `res/values-xx/strings.xml` plus one language switch in
`ProductState`; no screen would change.

## Automated tests (108)

`app/src/test`: PacketTest 11, RoutingTest 16, CryptoTest 7, TransferTest 6,
WireTest 4, LinkStateTest 4, TcpipTest 5, TunnelTest 5, TcpFlowTest 6, LinkIoTest 7, MarketTest 9, TunnelRoutingTest 3, ProductStateTest 7, CoverageTest 10, RelayTest 3, RelayHandshakeTest 5. `build.ps1` runs them first and refuses the APK
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

## v0.16.2 the Brain as a carrier for payments

The payment objects of v0.16.1 (destination claim, expectation, reply, receipt)
are signed by phones and verified by phones. v0.16.2 lets the Brain hold them
in between, for the case the product is built around: a buyer uses somebody's
Internet, walks away, and pays an hour later from the other side of town.

**The server is a carrier and a validator, never an author.** It stores what
phones signed, refuses what does not verify, and can never move money, redirect
a payment or invent a debt. Every object a phone downloads is the other phone's
own signed bytes, checked locally by the same code the Bluetooth path uses.

| Route | Who may | Carries |
|---|---|---|
| `POST /v1/pay/destination` | the seller named in the claim | signed claim + seller key |
| `GET /v1/pay/destination?seller=&rail=` | somebody who owes that seller, or the seller | active claim + seller key |
| `POST /v1/pay/expectation` | the buyer named in it | signed expectation + buyer key |
| `GET /v1/pay/expectations` | the seller, its own inbox only | pending expectations + buyer keys |
| `POST /v1/pay/reply` | the seller the expectation names | ACCEPTED / BUSY / ... |
| `GET /v1/pay/reply?payment=` | the buyer or the seller of that payment | the seller's answer |
| `POST /v1/pay/receipt` | the seller that observed the money | signed receipt + seller key |
| `GET /v1/pay/receipts` | the buyer, its own only | unacknowledged receipts |
| `POST /v1/pay/receipt/ack` | the buyer the receipt names | - |
| `POST /v1/pay/end` | the buyer | closes a window early |
| `GET /v1/pay/rules` | any signed identity | the signed parser configuration |
| `POST /v1/device/risk` | any signed identity | what THIS PHONE still owes |

### Why the public key travels with the object

A buyer that has never met a seller cannot check that seller's signature, so
without the key the Brain path is fail-closed and useless. Sending the key is
safe because **a node id is the hash of the key**: the receiving phone
re-derives the id and refuses a key that does not produce the id in the
message. A Brain that substituted its own key would be offering a key for a
different identity. That check is `BrainPayload.pubFor`, and a key learned in
person always wins over one the server offered.

### One object, one payment

Every object has a deterministic id. The phone keeps a small `pay_sync` ledger
of what it has already pushed, and the engine's own dedup decides what it has
already applied. An object that arrives twice, once directly and once through
the Brain, is one logical object, one settlement and one trust increment.

### Device risk is the server's answer, not the phone's

The phone sends a domain-separated pseudonym. It does **not** send whether it
believes it owes anything, because a freshly reinstalled phone would say no.
The server derives the amount from its own verified settlement records across
every identity seen on that pseudonym. No IMEI, no serial, no advertising id:
only `ProkNet-device-v1` hashed over the Android id ever leaves the phone.

### Signed parser rules

MTN and Airtel will reword a message one day. `ReceiptRules` lets the word
lists be replaced without a release, under three rules the phone enforces
itself: a **pinned, dedicated key** (not the Brain's transport identity), **data
only** (word lists, no regex or expressions from the server), and **hard
bounds** on count, length, characters and total size.

`PINNED_CONFIG_KEY` is **empty in this build**, which means no remote
configuration is accepted at all and the built-in rules are the only rules.
That is deliberate: publishing a key before a key ceremony would look like a
security control while being none. Detection never waits for a configuration
and never depends on one.

### Signed requests are bound to their endpoint

`signing_line` now covers the method and the path as well as the body:

```
ProkNet-api-1|<ts>|<nonce>|<sha256 body>|<METHOD>|<path>
```

Without that, a captured request to one payment endpoint could be replayed at
another that accepts the same JSON. Requests signed the old way are still
accepted, so v0.16.1 phones keep working.

Nonces are now stored in SQLite with a unique constraint. In memory they
vanished on restart, which meant restarting the server was a way to undo replay
protection.

## v0.16.3 the last four holds in the payment milestone

### Signed parser rules are switched on

A dedicated P-256 keypair now exists, key id `9410c707`. The public half is pinned
in `ReceiptRules.PINNED_CONFIG_KEY` and held by `brain/app.py`; the private half
lives only on the admin machine and is used only by `brain/publish_rules.py`.
`docs/OPERATIONS.md` says where it is kept and how to publish.

The running Brain never holds the private key. It is the part of the system most
likely to be compromised - reachable from the Internet, parsing what strangers send
it - and if it could sign rules, whoever took it could rewrite how every phone
decides whether money arrived. Holding only the public half means a compromised
Brain can withhold or delay a configuration, which phones survive, but never forge
one.

**A bug this uncovered.** `ReceiptRules.canonical` emitted its categories in
declaration order; `ruleconfig.canonical` sorts them. Both sides were
self-consistent, both suites passed, and every configuration the server signed would
have been refused by every phone. The feature would have looked finished and could
not have worked once. `server/tests/fixtures/crosslang.json` now pins the two
spellings against each other, in both directions, with signatures captured from real
runs of each language.

### Both operators work through the Brain

The phone used to fetch `/v1/pay/destination?seller=…&rail=MTN_MOMO`. An Airtel
seller was therefore unreachable through the Brain: the buyer owed money and was told
the provider had not said where to be paid, with nothing on screen to suggest the
question had been asked wrongly.

`GET /v1/pay/destinations?seller=` returns what the seller signed, on whatever rail.
Nothing in the retrieval path names an operator.

A seller has ONE place it is paid, and which operator that is can change. Versions
therefore count **per seller**, not per (seller, rail) - which is how
`DestinationClaim.nextVersion` has always numbered them on the phone. The server had
a separate counter per rail, so a seller moving to Airtel would have left a stale MTN
claim looking current for ever. `DestinationClaim.mayReplace` also used to refuse any
claim on a different rail, which meant a seller's own phone rejected the claim it had
just made.

Cooling still holds across a rail change: a seller who moves from MTN to Airtel keeps
receiving on MTN until the window closes, so a transfer already on its way still
lands somewhere valid.

### Private settlement data is authorised

`GET /v1/settlements/{id}` required nothing. A settlement id is not a secret - it is
derived from signed session bytes and both phones hold it - so knowing one revealed
the amount, both parties, the payment state and the whole audit trail.

It now needs a signed request, and answers only the buyer or the seller. 401
unsigned, 403 for anybody else, 404 for an unknown id said the same way to everybody
so the route cannot be used to discover which ids exist.

### Money endpoints refuse legacy signatures

`signed_request.verify` accepted either a method/path-bound signature or v0.16.1's
body-only one. That kept the cross-endpoint replay surface alive on exactly the
routes that move money. `require_bound=True` is the default now, and every route
under `/v1/settlements`, `/v1/wallet`, `/v1/pay/`, `/v1/payments/` and `/v1/device/`
uses it. Asking for a bound check without supplying a target raises rather than
silently accepting everything.

The signed line is:

```
ProkNet-api-1|<ts>|<nonce>|<sha256 body>|<METHOD>|<canonical request target>
```

**The canonical request target includes the query.** `?payment=A` and `?payment=B`
ask about two different people's money. The rule is deliberately dull, because a
clever one is what two languages eventually disagree about: keep the path exactly as
sent, drop an empty query, otherwise sort the raw `k=v` pieces and rejoin with `&`.
Nothing is decoded - `%2F` must not quietly become `/` on one side only.
`signed_request.canonical_target` and `SignedApi.canonicalTarget` are pinned together
by the same fixture.

### Financial database changes are atomic

`_set` used to commit. A payment touching three obligations committed three times on
the way through, so a failure in the middle left a real transaction row beside
obligations that had not moved - money half-settled, with nothing in the audit trail
to say which half was true.

`_set` and `_audit` now never commit; the caller owns the transaction. `record`,
`report`, `initiate`, `webhook`, `expire`, `open_payment`, `confirm_payment` and
`_flag_payment` each wrap their whole change in one `with self.db:`.

`server/tests/test_atomicity.py` injects a failure in the middle of each one and
compares a full snapshot of every money table before and against after. Six of those
tests fail if `_set` starts committing again.

## v0.16.4 upgrading a phone, and rotating a destination

### The database migration that was never written

The database version tells the story:

| build | version | app DB version |
|---|---|---|
| 62 | v0.16.0 | 8 |
| 63 | v0.16.1 | 8 - **schema changed, version did not** |
| 64 | v0.16.2 | 9 |
| 65 | v0.16.3 | 9 |
| 66 | v0.16.4 | **10** |

v0.16.1 changed `payment_receipts` (adding `delivered`) and `destination_claims`
(`PRIMARY KEY(seller_id, rail)` to `(seller_id, rail, version)`) without bumping the
version, so no upgrade step ever ran. v0.16.2 and v0.16.3 bumped to 9 but only called
`CREATE TABLE IF NOT EXISTS`, which does nothing at all to a table that already
exists.

A phone running since build 62 therefore still had the build-62 shapes. On it,
`receiptDelivered` queries a column that is not there, and saving a new destination
REPLACES the old one, so there is no previous claim and the cooling period silently
stops working. Every test passed, because every test built its database from nothing.

`Migrations.toV10` is a pure function from the schema SQLite reports to the statements
needed, so it can be tested against real databases built with the historical
`CREATE TABLE` text. `MessageStore.migrateV10` reads `PRAGMA table_info`, runs the
plan in one transaction, and does nothing when there is nothing to do - so running it
twice is safe.

Both tables are **rebuilt**, not altered, even for the single missing `delivered`
column. `ALTER TABLE ADD COLUMN` appends, so an upgraded phone would have
`..., sig, delivered` while a fresh install has `..., delivered, sig`. Nothing breaks
today, since every query is by name - but a phone whose table is a different shape
from every test database is precisely the situation this work exists to end. A test
compares names, types, NOT NULL, defaults, key ordinals, column order and indexes
between a fresh install and an upgraded one.

Nothing is fabricated. If `payment_receipts` were missing `sig` the migration refuses
rather than writing an empty signature, because a receipt with a fabricated signature
looks like evidence that money arrived and verifies against nobody.

### Destination rotation

A seller has one place they are paid. Changing it starts a ten-minute cooling window
during which buyers are still sent to the OLD number, so a transfer already on its way
still lands somewhere valid. Three things had to be true at once and were not:

1. **The claim handed to a buyer must be the active one, not the newest configured
   one.** `sendDestinationTo` sent the newest, so during cooling the buyer built an
   expectation against a number the seller was not watching and the seller refused its
   own buyer's payment. There are two different truths - `configuredDestination()` and
   `activeDestination(now)` - and they must not be mixed. The settings screen may say
   "Airtel, en attente" while every payment in flight still goes to MTN.
2. **A buyer must keep asking for newer claims.** `creditorsWithoutDestination()` only
   asked when the buyer had none, so a seller could change their number and the buyer
   would pay the old one until the debt was settled.
   `creditorsNeedingDestinationRefresh()` asks for every outstanding creditor on every
   sync; repeating the question is a no-op because a claim that is not newer changes
   nothing.
3. **An expectation already created stays pinned.** The seller compared against one
   hash, so the moment cooling ended it began refusing expectations it had itself asked
   for minutes earlier. `DestinationClaim.acceptableHashes` returns what is active now
   AND what was active when that buyer asked - normally the same single hash.

The window is measured from the claim's own signed `createdAt`. The server used to
measure it from `stored_at`, the moment it happened to receive the claim - a number the
phone cannot see. A phone offline for an hour would have moved to its new number while
the Brain still sent buyers to the old one, and neither side could have noticed.
`server/tests/fixtures/crosslang.json` now carries ten timeline cases that
`DestinationClaim.active` and `PayBox.active_destination` must both answer identically.

The phone also pushes **every** claim the Brain has not seen, not only the newest,
because the Brain works out which one is active from the history.

### A settled debt is not a standing right to a phone number

`_owes` authorised a destination read if there had ever been a settlement between the
two parties, so a buyer who paid in full six months ago could still ask the Brain for
that seller's current Mobile Money number, for ever.
`Settlements.has_outstanding_between` uses the project's one list of states that mean
money is still owed - PENDING, PAYMENT_INITIATED, PAYMENT_SEEN - rather than a copy
that can drift. CONFIRMED is paid, EXPIRED is closed, and DISPUTED and SECURITY_REVIEW
are for a human, not a reason to hand out a number.

## v0.16.5 the cooling timestamp becomes a signed fact

### Destination claim v2

v0.16.4 made a destination claim's `createdAt` decide when a new number goes live, on
the phone and on the Brain. It was still outside the signature. Anything carrying a
claim could therefore move the cooling window: ten minutes earlier and a buyer is sent
to a number the seller is not watching yet; ten minutes later and the seller keeps
being paid on a number it has abandoned.

A field that decides where money goes has to be a signed fact, so there is a new
domain rather than a quiet change to the bytes under the old one:

```
ProkNet-destination-claim-2|<sellerId>|<RAIL>|<normalised msisdn>|<version>|<createdAt>
```

The wire prefix says which bytes were signed - `dest2` for the above, `dest1` for
v0.16.0's, which omitted `createdAt`. The prefix is never guessed and never inferred:
a legacy claim relabelled `dest2` does not verify, and neither does the reverse.
Otherwise a carrier could have an unsigned timestamp treated as authenticated.

`Destination.time_is_signed` and `DestinationClaim.timeIsSigned` say which kind a
claim is, so nothing describes a legacy timestamp as authenticated.

### Legacy claims, read-only

Claims signed the old way are already on phones and in Brain databases. They keep
verifying, keep working, and a seller who has not changed their number is never asked
to re-enter it. No build after 66 creates one. The moment that seller does change
anything, the replacement is v2 and cooling runs on a signed timestamp from then on.

The signature format and the claim version are separate things: a legacy claim at
version 3 is followed by a v2 claim at version **4**, not 1. Versions stay
seller-global and monotonic, so an old claim can never come back.

**No second database migration.** The table keeps fields and a signature, not the wire
line, so the format is derived when a stored claim is re-encoded: try the format we
produce now, fall back to the one we used to (`DestinationClaim.formatOf`). The Brain
stores the whole line, so its prefix is already there, and no historical signed bytes
are rewritten.

### An expectation is judged by the destination it was given

`put_expectation` measured the active destination from the moment the expectation
reached the server:

```
09:59  the buyer creates a signed expectation; MTN is still active
10:00  the cooling window closes and Airtel becomes active
10:01  the buyer finally gets online and uploads it
```

Checked against 10:01 the expectation named MTN while Airtel was active, and the Brain
refused a payment the seller itself had asked for a minute earlier. Arrival time
depends on when a phone found signal - it is the one thing in this decision that is
not a signed fact.

`PayBox.acceptable_destinations(seller, now, asked_at, cooling)` returns what was
active when the buyer asked **and** what is active now, as (rail, hash) pairs.
`DestinationClaim.acceptableHashes` on the phone returns the same set, so the seller's
own decision and the Brain's cannot differ. Arrival time now decides only whether the
expectation has expired.

The buyer cannot reach backwards for an older destination: `createdAt` is inside the
buyer's own signed expectation, so backdating it breaks the expectation instead.

### The fixture grew

`server/tests/fixtures/crosslang.json` gains a `destination_v2` section - canonical
bytes, a Python signature, a signature captured from a real Kotlin run, and a wire line
with only the timestamp moved, which both sides must refuse. The timeline cases now
carry each claim's format and include a mixed legacy/v2 history at, before and after
the cooling boundary.

## v0.17.0 the live network Brain

Until now two phones had to find each other by themselves. ProkNet worked when they
happened to be near each other with both apps open. v0.17 adds the part that lets a
buyer who can see nobody say so, and a provider who could help be asked.

```
                        PROKNET BRAIN
                              |
              +---------------+---------------+
              |               |               |
           DEMAND         PRESENCE       ACTIVATION
              |               |               |
              +---------------+---------------+
                              |
                        NETWORK STATE
                              |
            +-----------------+-----------------+
            |                                   |
       BUYER PHONE                        PROVIDER PHONE
       GET INTERNET                       GAGNER / PARTAGER
            |                                   |
            +--------- existing BLE/L2CAP ------+
```

**The Brain never carries anybody's Internet.** It coordinates who is willing, who
needs, who was asked and what happened, then gets out of the way. The bytes go over
the Bluetooth L2CAP path hardware-proven since v0.10.2, untouched.

### Three rules it is built around

**The local path stays first.** Order: a usable local source, then a locally visible
ProkNet provider, then the Brain. Two phones that can already see each other never
wait for a server, and with the Brain unreachable ProkNet behaves exactly as it did in
v0.16.5. Nothing in the control plane is on the critical path of a session.

**A zone is a hint, not a route.** `CoverageModel.zoneId` already gave roughly a
neighbourhood - coarse on purpose, not an address - and it is reused rather than
replaced. The phone computes the zone and the Brain receives it, so no raw GPS is sent.
Two phones in one zone are *worth asking*; whether Bluetooth reaches is a question only
the phones can answer.

**Only the phones know what worked.** A status report is best-effort and advisory. A
session that works while the report fails is still a working session, and no Brain
status ever changes signed money, a settlement or a payment trust state.

### What was reused rather than rebuilt

The spec said not to create parallel versions of systems that exist, and most of the
model layer already did:

| v0.17 needs | already existed |
|---|---|
| a coarse zone id | `CoverageModel.zoneId`, cell 0.005 degrees |
| a signed demand object | `NetRequest.Request` + `RequestGossip` |
| a provider activation inbox | `ProviderInbox`, with `Source.BRAIN` since v0.13.3 |
| provider eligibility | `ProviderActivation.eligibility` |
| the activation notification | `NetworkNode.alertHook` |
| zone colours | `Coverage.ZoneStatus` |
| a signed request | `SignedApi`, hardened in v0.16.3 |

So a Brain activation becomes a `ProviderInbox` opportunity exactly like a locally
gossiped one, and the notification, the Gagner card and PARTAGER keep working as they
do. `NetworkNode.originate` was already the one place meaning "ask the network", so the
demand is created there - the buyer presses GET INTERNET once and ProkNet decides.

### Honesty in the wording

`core/NetworkAccess.kt` is the single state model every screen reads. The reason it
exists rather than a few booleans: the answer now comes from three places at once, and
`brainSearching`, `providerFound` and `localConnecting` spread through the UI would
disagree with each other inside a week.

It enforces the rule structurally rather than by discipline. `onDemandStatus` cannot
produce CONNECTED from **any** server word - including the server's own "CONNECTED",
which means somebody told the Brain a connection happened, not that this phone has one.
Only `LinkEvent.INTERNET_UP`, from the transport, produces it. So a provider accepting
reads "Un fournisseur se prépare", never "Internet disponible".

The model has no field for a provider identity, which is the point: a buyer is told
somebody is preparing, never which phone.

### Matching

Deterministic and deliberately readable: free, then sponsored, then the cheapest
commercial offer; then reliability, freshness, spare capacity, and the provider id as a
stable last tie-break. No randomness - a pilot has to be able to explain why a
particular phone rang. A buyer who asked for free is never silently given a commercial
provider. An unpriced commercial offer is not a candidate, because it cannot honestly be
compared. A phone is never matched to itself.

One activation at a time, at most three attempts per demand, and a cooldown after a
cancellation. Twenty phones buzzing for one small request is how a network makes itself
unwelcome.

Network reliability is kept apart from v0.16 payment trust on purpose. Paying your debts
and being reachable over Bluetooth are different facts about a person. A provider with no
history scores 0.5 rather than 0, because a network that only suggests providers it has
already used can never grow.

### Independent failure domains

`NetworkBrainSync` is its own class and its own failure domain, sharing only `SignedApi`
and the HTTP shape with `PaymentSync` and `SettlementSync`.

And a real bug this milestone fixed: settlements and payments ran only **after** a
successful `/v1/sync`, so one hiccup in coverage gossip also stopped money reaching the
server. v0.15.3 taught that lesson and v0.16.2 quietly re-introduced it.
`runBrainSubsystems` now runs in a `finally`, outside the sync's own `try`, with each
subsystem in its own `try/catch`.

### Privacy

A presence row holds a coarse zone, capability flags and an internal price hint. No
Mobile Money number, no coordinates, nothing that identifies a house - asserted by a
test that reads the table definition. There is deliberately **no way to list the
providers in a zone**: the coverage answer is a colour and an `updatedAt`, because in a
thin zone "1 provider" is one identifiable household.

`/health` now says almost nothing. It used to report how many nodes, requests and
sources the Brain knew about, which told anybody who asked how many people were using
ProkNet and roughly where. The counts moved behind a signature.

A client-supplied zone is a matching hint and **not security evidence**. A fake zone can
waste an activation; it cannot move money, gain trust or bypass a payment check, because
none of those read it.

### Schema

Brain migration **2**, numbered and recorded in `schema_version`, not another
`CREATE TABLE IF NOT EXISTS`. Six tables: presence, demand, activation, reliability,
events and a per-buyer cooldown. Presence is judged by its own TTL even after a restart,
so old timestamps are never treated as fresh.

The Android database is **unchanged at version 10**. Network control state is not
financial and lives in the existing file-based pattern that `RequestGossip` and
`ProviderInbox` already use, so there was no honest reason to bump it.

## v0.17.1 making the Brain visible, and giving an accepted provider time

v0.17.0 built the control plane and drew none of it. Mike would have installed build
68 and seen nothing new: the Home status model existed and no view read it, the map
never received a Brain colour, and the Activité event model wrote nothing. That is
what this release fixes, plus four behaviours the loop needed to be honest.

### Home, the map and Activité

`NetworkAccess.homeState` is the whole Home decision as one pure function.
`MainActivity` needs a `Context` and cannot run off a phone, so the screen gathers
facts and draws the answer while the decision lives where a test can reach it. That
is the only way "PROVIDER_ACCEPTED shows *Un fournisseur se prépare*" gets verified
rather than asserted about a string constant.

The branch order **is** the honesty rule, and it is tested as such:

1. the transport says Internet works -> CONNECTED, and nothing else can produce it;
2. the transport is bringing a link up -> CONNECTING;
3. a Brain demand is live -> whatever the Brain says it is;
4. searching locally -> SEARCHING;
5. the last attempt failed -> FAILED;
6. otherwise -> IDLE, described by the zone.

The Brain reporting CONNECTED means *somebody* told it a connection happened, not
that this phone has one, so it produces "Connexion en cours…" and never "Connecté".

`NetworkAccess.mergeZone` merges rather than replaces. Local observation outranks the
Brain, because a source this phone can see is stronger evidence than a server's
opinion about the neighbourhood. A Brain answer older than five minutes is
**discarded** rather than downgraded one step: there is no honest middle ground
between "somebody was there five minutes ago" and "somebody is there", and a zone
staying green because somebody was there yesterday is the specific thing it prevents.
Nothing known is RED; there are no demo cells.

`NetworkHistory` is a small durable list for Activité, one line per real transition.
Dedup is the design rather than an afterthought: a waiting buyer polls every few
seconds, so the naive version prints "Recherche Internet" four times. Rows are keyed
on the demand and the transition, and a repeat returns the same object so the screen
does not even repaint. A technical kind writes nothing at all.

It is deliberately **not** in SQLite, so the Android database stays at **10**. This is
coordination history, not signed evidence: it holds nothing verifiable, losing it
costs a reader some scrollback, and `RequestGossip` and `ProviderInbox` already keep
their state in files this way. A migration with nothing to migrate would be worse.

### An accepted provider now has time to arrive

v0.17.0 reused the 75-second window a provider had to *answer* in as the window to
actually reach the buyer. So somebody who tapped PARTAGER vanished a minute later
while the two of them were still walking towards each other, and the buyer's screen
gave up for no reason they could see.

`OFFER_TTL_MS` (75 s) and `LINK_WAIT_TTL_MS` (10 min) are now separate, and
`deadline_of` is the single place that knows which applies. The accepted window is
`min(demand expiry, acceptedAt + wait)`, so it can never outlive the request it
serves.

`DEMAND_TTL_MS` went from 10 minutes to 15, for a concrete reason rather than taste:
with both numbers equal, an accepted provider swallowed the whole request and "try the
next provider when the first never turns up" could never fire. A test caught that - it
asserted a fallback that was unreachable.

### The other half of matching

v0.17.0 matched only when a demand was created, so a buyer who asked before anybody
was sharing stayed SEARCHING for ever even once a provider woke up beside them. A
presence heartbeat now picks up waiting work, which also covers a provider coming back
under capacity after a session and one arriving in the zone.

Oldest compatible demand first. Somebody who has waited eight minutes must not watch
newer arrivals get served ahead of them.

### Movement

Written down rather than left to guesswork. A buyer who walks keeps the **same**
demand - same id, same age, same attempt count, because recreating it would reset
their place in the queue and burn the cooldown - and any offer left behind in the old
zone closes at once. A provider that walks away loses an **unanswered** offer but
keeps one it has already **accepted**, because it may well be walking towards the
buyer and that is what the window is for.

### Local networking wins

The Brain is not a reservation system. If it activated provider A and local discovery
found B first, B is used and the demand closes - `localConnectionWon` reports the
result first so the matcher still learns what worked, then cancels. Every step is
best-effort: it runs after a session is already up and nothing in it may touch one.

### Restart

The demand id lives in memory, so a process death loses it. `reconcile()` asks the
Brain on the next run - `GET /v1/network/demand` with no id answers with this buyer's
live request. Server authority rather than a local table: one round trip cannot
disagree with the server, and when the Brain is unreachable the honest answer is that
we do not know, which is also what a stale local copy would have been hiding.

### Schema 3

Two columns on `network_activation`: `accepted_at` and `link_deadline`. Added as
**numbered migration 3**, not a quiet edit of schema 2 - `CREATE TABLE IF NOT EXISTS`
does nothing to a table that already exists, which would have been the v0.16.1 mistake
on the server. Declared once and applied both by the migration and defensively by
`NetworkPlane`, so an upgraded Brain and a fresh one end up the same shape.

## v0.17.2 a Brain job that stands on its own

Five wiring faults, found by reading v0.17.1 rather than by a failing test. Each one
breaks the exact scenario the milestone exists for: two phones too far apart to have
met.

### 1. A job needed a request the provider could not have

The provider path did `state.requests[j.demandId]` and gave up when it was null - so an
activation only produced an opportunity if the buyer's `NetRequest` had already arrived
through the legacy gossip path. Two phones on opposite sides of a neighbourhood have no
reason to have exchanged anything. The activation arrived, the store was empty, and
there was no opportunity, no notification and no card.

A demand now **carries the buyer's own signed request**, and a job hands it to the
provider. Not an unsigned replacement: `NetRequest.encodeLine` of the exact object the
buyer signed, stored byte-for-byte and never reconstructed.

The Brain carries it; it does not vouch for it. Both ends verify:

**Server**, before storing: the line parses, the signature verifies, `r.id ==
demandId`, the request is open, not expired, and in the demand's zone - and
`protocol.node_id(r.origin_pub)` equals the **authenticated caller**. That last check is
the one that matters: `verify_request` only proves *somebody* signed it, so without it a
signed request belonging to A could be uploaded by B. The full node id, not the eight
hex characters of `origin_short`, because a short id is a prefix and prefixes collide.

**Phone**, before acting: decode, verify the buyer's signature, check the id matches the
job, check it has not expired. Fail closed - an unverifiable job produces nothing at
all.

The verified request is then folded into the ordinary request store, so eligibility, the
card and the tombstone-on-cancel all behave exactly as they do for a gossiped one. No
parallel Brain-only admission model.

### 2. PARTAGER never told the Brain

`acceptOpportunity` started the seller and marked the inbox, and never called
`networkSync.answer`. So the activation stayed OFFERED for ever and the buyer waited on
"un fournisseur se prépare" from somebody who had in fact already agreed. The central
failure of the product flow, and nothing detected it because every piece worked alone.

`ProviderInbox.Opportunity` now carries `brainActivationId` (empty for a local one), the
file format gained a tenth field that older files simply lack, and PARTAGER:

1. re-checks eligibility;
2. starts the seller;
3. **persists the acceptance** - before anything is sent;
4. sends it on the IO executor, never the main thread.

Persisted first, deliberately. A dying process or a dead network must not be able to
lose the fact that somebody pressed the button.

If the send fails, the job comes back OFFERED on the next poll while the inbox says
accepted, and it is sent again - the server route is idempotent, and the provider is
never asked to tap twice. That survives a restart, because the acceptance and the
activation id are both on disk. If the Brain says the activation is genuinely over, the
card is dropped; a running **session** is never touched, because a control-plane job
expiring is not a reason to stop somebody's Internet.

### 3. A zone colour claimed a working connection

`IDLE + GREEN` said **"Internet disponible maintenant"**, and GREEN can come entirely
from the Brain - which knows only that somebody was sharing somewhere in a coarse cell.
Not that this phone can reach them, not that Bluetooth carries that far, not that they
have capacity left.

Now:

| | |
|---|---|
| Brain GREEN alone | "Un fournisseur est actif dans votre zone" |
| map GREEN | "Fournisseur actif dans cette zone" |
| a source this phone can use | "Internet disponible maintenant" |
| transport up | "Connecté" |

`homeState` takes `localUsableNow` explicitly rather than inferring availability from a
colour, because inferring it from a colour is exactly how a buyer gets told Internet is
ready and then cannot load a page.

### 4. Coverage freshness was refreshed by unrelated work

Home and the map aged the Brain's zone answer against `lastOk`, which means "some
control-plane run succeeded". A heartbeat, a demand poll or a job poll says nothing
about whether the coverage endpoint answered - so a stale GREEN stayed looking fresh for
as long as anything else was working.

`zoneStatusAt` is set only when `GET /v1/network/coverage` actually returns, and the
freshness window is measured against that.

### 5. The buyer's zone move was server-only

v0.17.1 had the route, the policy and the tests, and nothing on the phone ever noticed
its own zone changing. `followTheBuyer()` compares the local coarse zone with
`demandZone` - the zone the **server** last confirmed, tracked rather than guessed - and
moves the demand when they differ. Only on a real change, so a heartbeat does not become
a move; never to an unknown zone, because losing a location fix is not the same as
having moved.

### Schema 4

One column, `network_demand.request_line`, as numbered additive migration 4. A demand
created by build 69 simply has none, and a job from it carries an empty line - which the
phone treats as unusable rather than crashing on. Android DB stays at **10**.

## v0.17.3 an idle provider is a real provider

Two runtime blockers, both found by reading build 70 rather than running it, and neither
visible to any test that already passed. They have the same shape as the five faults of
v0.17.2: every piece was correct on its own, and the join between them made a state the
product depends on unreachable.

### 1. Presence required the seller gateway to be running already

Build 70 built the presence as `sharingEnabled = e.optIn && node.sellOn`, and the
heartbeat published nothing unless that was true. `sellOn` means "the seller gateway is
running right now", so the Brain could only ever see a phone that was **already
sharing**.

That is a contradiction with the product, not a detail. The flow ProkNet exists for is:

    an idle phone with Internet, opted in, not sharing
      -> the Brain knows it exists
      -> a buyer's demand is offered to it
      -> the notification arrives
      -> the user taps PARTAGER
      -> sharing starts

With `sellOn = false` there was no presence, so no activation could ever arrive. With
`sellOn = true` the presence existed but PARTAGER then refused the job with
`ALREADY_SHARING`. Neither state completes the loop. TESTING 74 passed only because it
instructed Mike to turn PARTAGER on before the buyer asked, which is not how anybody
will use this.

**Willingness and current sharing are now separate facts, and neither is derived from
the other.**

| | meaning | derived from |
|---|---|---|
| `willing` | you may ask this phone | opted in, validated upstream, a local path it could offer |
| `availableForActivation` | and it has room | `willing && currentLoad < maxBuyers` |
| `currentlySharing` | the gateway is up | `sellOn` - reported for truth, never a prerequisite |
| `currentLoad` | live seller sessions | 0 or 1 in the pilot |

On the wire `sharingEnabled` carries `availableForActivation`, which is what the matcher
has always needed: *this provider can be asked*, not *this provider is already serving
somebody*. **The Brain schema does not change.** The server has always gated separately
on `sharing_enabled` and `has_capacity()`, in both `candidates()` and `zone_status()`, so
honest capacity numbers do the rest.

A **busy** provider keeps heartbeating, with `currentLoad = 1`. It is a real provider and
its zone is genuinely covered; withdrawing would make a covered area look empty. It
simply gets no second buyer. Only an **unwilling** phone - opted out, no Internet, no
path - withdraws its presence, because a buyer matched to somebody who has opted out
wastes a real walk to a kiosk.

`freeReady` and `commercialReady` describe **ability** now, not the gateway: "if the user
accepts this request, can this phone serve it?". `commercialReady` is
`willing && mayOfferPaidSharing()`, which is every v0.16 paid-seller condition unchanged.
Explicit opt-in is still required, so no phone becomes a provider silently.

The decision lives in one pure object, `core/ProviderPresence.kt`, which the presence
hook calls - so the test and the phone run the same function rather than two hopefully
identical ones. `willing` is a single definition in `ProviderActivation`, and the v0.13
`availability()` is expressed in terms of it, so they cannot drift.

### 2. The acknowledgement retry existed and could not run

v0.17.2 added a retry for an acceptance that never reached the Brain. `pollJobs` then
did:

```kotlin
if (found.map { it.activationId } == jobs.map { it.activationId }) return
```

and the reconciliation was downstream of that. But the stuck case is **by definition**
the same activation coming back in the same state, because the acceptance never arrived.
So the one situation the retry existed for was the one situation in which it never ran.
A provider could stay stuck for ever while every poll succeeded.

`pollJobs` now: decode and verify every job, then call `onJobsSeen` **on every successful
poll**, and only then - if the list actually moved - replace it and call `onJobs` for the
UI. The change key is `activationId + ":" + state`, so OFFERED becoming ACCEPTED counts
as movement.

The poll gate was wrong for the same reason. It required `sharingEnabled`, which after
PARTAGER is false because the phone is full - so the phone that has just accepted was
exactly the phone that stopped polling. It gates on `willing` now.

Server side this already worked: `jobs_for_provider` never looked at `sharing_enabled`,
so a full provider can still reach its own jobs. That is now pinned by a test, because
it is load-bearing for recovery.

### 3. A dropped packet is not a decision

`answer()` returned a Boolean, and `false` meant "refused", "timed out", "no signal" and
"the server had a fault" all at once. The retry treated `false` as a refusal and removed
the provider's accepted opportunity - so a provider who tapped PARTAGER somewhere with
bad signal could lose the card, and with it the buyer's answer.

Every `NetworkError` in the Brain is an HTTP 400, so the status code cannot carry the
distinction, and matching on the English message would have been the third cross-language
string divergence in this project. So a definitive refusal now carries a
machine-readable `reason`; a transient one carries none.

    ACTIVATION_UNKNOWN      the activation does not exist
    ACTIVATION_NOT_YOURS    it was offered to somebody else
    ACTIVATION_SETTLED      already declined or otherwise closed
    ACTIVATION_EXPIRED      answered after its deadline

`BrainAnswer.classify` maps 2xx to `ACCEPTED`; exception, no reply, 5xx, 408, 429 **and
any 4xx whose reason is not on that list** to `RETRYABLE_FAILURE`; and only a listed
reason to `TERMINAL_REJECT`. Conservative on purpose: retrying a settled activation costs
one idempotent request, and discarding a live one costs a buyer waiting for ever on
somebody who already agreed.

`server/tests/fixtures/brain_answer_reasons.txt` is one committed file that both the
Python and the Kotlin test read. Two independent implementations of one rule, each with
its own green suite, is exactly the shape in which a mismatch survives - v0.16.3 shipped
one. If either side drifts, one of the two tests fails.

What each outcome does:

- **retryable** - keep the opportunity, keep `accepted`, keep `brainActivationId`, keep
  selling, log quietly, try again next poll. Nothing is removed.
- **terminal** - drop the stale card, and only if no seller session is live.
  `BrainAck.outcome` refuses to drop one while a session exists: somebody is using the
  Internet right now, and control-plane bookkeeping never cuts that off.
- **accepted** - record it and stop retrying. A repeat the server answers with
  `duplicate: 1` is a success, not a refusal.

### 4. Durable acknowledgement

`ProviderInbox.Opportunity` gained `brainAcked`, appended as field 11. The decoder
accepts 9 fields (build 69), 10 (build 70) and 11 (build 71); a missing field reads as
"not acknowledged", which costs one idempotent request and never a lost tap. An upgrade
that dropped the inbox would break a promise to a buyer who is already waiting.

**The server's job state is the authority**; `brainAcked` is only the phone's memory of
what that authority last said. So:

- server `OFFERED` + local `accepted` -> resend, *even if* `brainAcked` was true. A stale
  local "acknowledged" must not silence the one signal that says the acceptance was lost.
- server `ACCEPTED` or `LOCAL_LINK_SEEN` + local `accepted` -> mark acknowledged, send
  nothing.
- job absent from `/jobs` -> do nothing. Absence is not a verdict, and one failed or
  empty GET must not throw away a tap. An opportunity leaves when the buyer's own request
  expires, when it is tombstoned, or when the Brain states a terminal reason.

`offerFromBrain` preserves `accepted` as before, and preserves `brainAcked` only for the
same activation id; a genuinely new activation has certainly not been acknowledged.

This decision is pure too, in `core/BrainAck.kt`, with `NetworkNode` a thin loop over it,
so the retry and restart tests exercise the code the phone runs.

### No schema change

Brain schema stays **4**, Android DB stays **10**. This is bookkeeping, and an appended
inbox field is the right size of change for it. Nothing in payment, settlement, trust,
BLE, L2CAP, the VPN or the rendezvous timings was touched.

## v0.17.4 being unable to charge is not an offer to give something away

One product-rule bug, found by reading build 71. The activation loop was correct by then;
this is about what the Brain was told the loop was *for*.

### The bug

`ProviderPresence.of` did:

```kotlin
val commercial = willing && mayOfferPaidSharing
val free       = willing && !mayOfferPaidSharing
offerClass     = if (mayOfferPaidSharing) COMMERCIAL else FREE
```

`mayOfferPaidSharing()` is the v0.16 paid-seller safety check: a valid payment
destination, payment verification readiness, the seller not blocked. When it is false the
true sentence is **"this seller cannot take money right now"**. Build 71 wrote down
**"this seller is giving their Internet away"**. Those are different facts about different
things, and one does not imply the other in either direction.

So a seller who had opted in to earn money, had working Internet, and had simply not
finished setting up their Mobile Money number was published to the whole zone as a FREE
provider.

Two things were wrong at once.

It breaks the locked product rule that **FREE must be explicit**. Nobody offered
anything; a missing configuration did.

And it creates an economic mismatch the rest of the system cannot absorb. The Brain
matches a buyer who asked for free help; the provider taps PARTAGER; and `Pricing` — which
has never looked at `mayOfferPaidSharing` and never will — quotes a paid rate for that
session from the seller's real source. The buyer was promised one contract and offered
another at the moment of connection.

### Three independent facts

| | meaning | derived from |
|---|---|---|
| `willing` | you may ask this phone | opted in, validated upstream, a path it could offer — **unchanged from v0.17.3** |
| `commercialReady` | a paid session could be completed | `willing && intent == COMMERCIAL && mayOfferPaidSharing()` |
| `freeReady` | this Internet was genuinely offered as a gift | `willing && intent == FREE` |

Capacity stays where v0.17.3 put it, in `currentLoad` / `maxBuyers`, which the Brain
checks separately in both the matcher and `zone_status`.

### What controls FREE

`ProviderPresence.intentOf(source)`, which is `Pricing.isFree(source)` — `source.free` or
`SourceKind.FREE_PUBLIC`. That is not a new opinion: it is the exact predicate the pricing
engine has always used to decide the buyer pays nothing, now extracted into one function
so the Brain's advertised class and the session's real price cannot disagree. `quote()`
and `autoRate()` call the same function.

No fake preference was invented to make this testable. `ProkNetNode.mySource()` produces
`MOBILE_DATA`, `AUTHORIZED_HOME_WIFI` or `UNKNOWN` and never sets `free`, so **in
production today every real provider is COMMERCIAL** and `freeReady` is false. That is the
conservative answer and the honest one. When a real free source or a user-facing "give it
away" setting exists, it arrives at `intentOf` and nowhere else.

### The truth table

| provider | commercialReady | freeReady | offerClass |
|---|---|---|---|
| commercial, payment ready | true | false | COMMERCIAL |
| commercial, payment NOT ready | false | false | **COMMERCIAL** |
| explicitly free source | false | true | FREE |
| explicitly free and paid-capable | false | true | FREE |

`offerClass` is the class being **offered**, always. A commercial seller that cannot
charge stays COMMERCIAL and is excluded by its readiness being false — the server's
`ready_for` returns false for every class when nothing is ready. Flipping the label to
FREE instead would be a lie the matcher would then act on. `priceHintInternal` follows the
same rule: 0 for a free source, because that is what `Pricing.autoRate` would produce, and
the seller's real rate otherwise.

The last row is a policy choice worth stating: an explicit offer of free Internet is not
withdrawn by the seller also being *able* to charge. FREE stays explicit in both
directions — never inferred from payment failure, never revoked by payment success.

### The starvation this fix would otherwise have introduced

Before v0.17.4 nearly every provider carried `free_ready = 1` at some point, because
nearly every provider is at some moment unable to charge. So a FREE demand almost always
found somebody. Now that FREE is explicit, a FREE demand in a zone with only commercial
providers is genuinely unservable.

`serve_zone` hands the oldest waiting demand to a provider that has just become useful.
An unservable FREE demand sitting at the head of that queue would absorb every heartbeat
for its full fifteen minutes, and the COMMERCIAL demands behind it would never be
reconsidered — a buyer starved by a request nobody could have served.

So `oldest_waiting_demand` takes an optional predicate and `serve_zone` takes the provider
that triggered it, skipping demands that provider's `ready_for` refuses. Fairness is
unchanged: still oldest first, among the demands this provider can actually take. The full
matcher still runs afterwards, so this only chooses **which waiting buyer to try**, never
who gets offered the job.

### Readiness becoming true

`put_presence` has called `serve_zone` since v0.17.1 whenever the heartbeat leaves the
provider `ready_for(COMMERCIAL)` with capacity. That now does real work here: a seller who
finishes setting up Mobile Money sends one heartbeat with `commercialReady = 1`, and a
commercial demand that has been waiting in that zone is offered to them without the buyer
asking again.

### No schema change

Brain schema stays **4**, Android DB stays **10**. Every field used here already existed;
only the values are honest now.

## v0.17.5 a permission nobody was ever asked for

The first defect found by running v0.17 on real phones, and it was not in the Brain.

### What happened

The pilot OUKITEL was healthy in every way that mattered: Brain reachable over HTTPS,
signed requests succeeding, `syncs: 4, error: -`, notifications on, Internet validated,
Bluetooth on, and a working local BLE session with the other phone. The Brain saw nothing
from it. No presence, no demand, no activation, for a whole day.

Its diagnostic said:

```
zone: z? (no location permission)
```

Every Brain call is gated on the zone, and each one returns early **and silently**:

| call | guard |
|---|---|
| presence heartbeat | `ProviderPresence.of` returns null for `NO_ZONE` |
| job poll | same - no presence, no poll |
| coverage refresh | `if (z.isEmpty()) return` |
| create demand | `if (z.isEmpty()) return ""` |
| `reconcile` | **no zone check** |

`reconcile` was the only one of the six arriving at the server, which is exactly what the
Brain's access log showed and is how the cause was found.

### Two faults, not one

**Nobody was told.** The provider saw nothing at all. The buyer saw *"Aucun Internet
disponible tout de suite"*, which is true and describes the wrong problem: it sounds like
an empty neighbourhood, when in fact the whole network layer was switched off. No user
could diagnose that, and neither could the operator without reading the server log.

**Nobody was asked.** `askLocation()` existed, and fired only when the user opened the
**map** tab. A person who uses Accueil and Gagner - which is everybody - is never asked
once. The permission the entire product depends on was behind a screen with no reason to
visit it.

### The rule

A capability gated on a permission must ask for it **where it is used**, and must say so
on the screens where its absence is felt. Not on one tab, and not only in a diagnostic.

`core/LocationGate.kt` decides, purely, which of four states the phone is in and what to
say about each:

| state | when | the one tap |
|---|---|---|
| `NONE` | granted and switched on | nothing to say |
| `ASK_PERMISSION` | not granted, Android will still show its dialog | the system dialog |
| `OPEN_SETTINGS` | dialog exhausted (denied twice) | the app's own settings page, opened directly |
| `TURN_ON_LOCATION` | granted, but the phone's location switch is off | the location screen, opened directly |

**Nobody is sent hunting.** The normal answer is the system yes/no dialog. The settings
screens are only used when Android will no longer show that dialog, and then they are
opened by intent straight onto the right page - never "go into Settings and find it",
which most people cannot do and should not have to.

Android cannot tell "never asked" from "permanently denied": `shouldShowRequestPermission-
Rationale` is false in both cases. So the app remembers whether it has ever asked, and
`canAskInApp` is `!askedBefore || shouldShowRationale`. That stays in the Activity;
`LocationGate` takes it as an argument and remains pure.

### Where it now asks

- **GET INTERNET** - without a zone the Brain cannot create a demand at all, so asking
  here is the difference between a working button and a buyer watching *Recherche* for
  ever.
- **Turning on « Me prévenir quand quelqu'un cherche Internet »** - opting in to be woken
  is precisely the moment the zone starts to matter, because a provider with no zone
  publishes no presence and is offered no buyer however willing it is.
- **The map**, as before.

After the permission is granted the app continues what the user actually pressed. A
permission granted with nothing happening afterwards reads as a broken app.

### Where it now says something

`homeNote` on Accueil and `earnFootnote` on Gagner - both existing lines, no new banner.
Bluetooth-off still wins on Accueil, because that stops even the local path.

The note names the **consequence**, not the setting: *"Zone inconnue : autorisez la
position pour trouver Internet près de vous."* Telling somebody a zone is unknown explains
nothing; telling them ProkNet cannot see anyone around them is the thing they are
actually experiencing.

### Privacy, repeated at the point of asking

The permission dialog is the only place most users will ever read what ProkNet does with
a position, so the promise is restated there every time: a zone to 500 m, never an exact
position, never a history of movements. A permission granted without understanding is not
consent. A test asserts those three sentences are present.

### Not changed

No Brain, payment, BLE, L2CAP or VPN behaviour. Brain schema 4, Android DB 10. This
milestone changes only what the phone asks for and what it admits to.

## v0.17.6 the permission that did not exist

### The root cause, found in the built APK

`aapt2 dump permissions` on build 74 said:

```
uses-permission: name='android.permission.ACCESS_COARSE_LOCATION' maxSdkVersion='32'
```

**On Android 13, 14 and 15 the location permission was not declared at all.**
`checkSelfPermission` could only ever return DENIED; `requestPermissions` could only ever
do nothing. The pilot OUKITEL is Android 15 - so it could not have held a position under
any circumstances, no amount of granting in settings would have helped, and the v0.17.5
dialog would have asked for something that did not exist.

The cap was correct when it was written. Location used to be needed only by the old
radios: BLE scan results on Android 8-11, and the local-only Wi-Fi hotspot on 8-12.
Android 13+ replaced both with `NEARBY_WIFI_DEVICES` and `BLUETOOTH_SCAN/neverForLocation`,
so capping location at API 32 avoided asking for something no longer needed. Then **v0.13
made the coarse zone the thing the entire Network Brain runs on** - no zone, no presence,
no demand, no coverage - and nobody lifted the cap. A permission stopped being optional
and its declaration never caught up.

`ACCESS_COARSE_LOCATION` is now uncapped, because the zone needs it on every version.
`ACCESS_FINE_LOCATION` **keeps** its cap: a 500 m cell is all ProkNet ever needs, so on a
modern phone it asks for coarse and nothing more - which is also exactly what the dialog
promises. The permission request was narrowed to coarse only to match.

This is the defect that cost the day. The two below are real and were fixed in the same
milestone, but they were sitting behind a door that could not open.

### Two more faults, stacked behind it

**The position stopped with the screen.** Location updates were requested in the
**Activity's** `onForeground` and cancelled in `onBackground`. A fix is usable for thirty
minutes, so a provider who put the phone in a pocket had no zone half an hour later. No
zone means `ProviderPresence.of` returns null, which means no presence heartbeat, which
means the Brain cannot see that phone at all. The implementation was "only while you are
looking at it"; the product said the opposite.

**A stationary phone never refreshed.** Updates asked for `LOCATION_MIN_DISTANCE_M =
300f`. The network provider delivers nothing until the phone moves that far, so a phone
sitting still in a house received **no updates whatever** and went stale even with the app
open. The idle provider is by definition the phone that is not moving - so the one case
that mattered was the one case that could not work. Fixing only the first fault would have
left this one hiding underneath it.

### The fix, without a background-location permission

ProkNet already runs a foreground service with a permanent notification; that is how it
carries anything at all. A foreground service that declares the **`location`** type may
receive updates while the app is closed **on the ordinary coarse permission** - no
`ACCESS_BACKGROUND_LOCATION`, and therefore no trip into system settings, which is
consistent with the v0.17.5 rule that nobody is ever sent hunting.

So:

- `FOREGROUND_SERVICE_LOCATION` declared, and the service type becomes
  `connectedDevice|location`.
- The type is added at `startForeground` **only when the permission is actually held**.
  Android 14 refuses a service that declares a type it has no permission for, and being
  refused would take the whole node down rather than just the zone.
- `CoverageEngine.updateZoneWatch(...)` starts and stops the updates; the Activity's
  `onForeground`/`onBackground` no longer touch location at all. They still drive the
  Wi-Fi sweep, which genuinely is a foreground concern.
- `LOCATION_MIN_DISTANCE_M` is now **0**, so time is the only interval and a stationary
  phone keeps its timestamp alive. One coarse network fix every five minutes.

### Only while it is needed

`core/ZoneWatch` decides, purely, whether a position should be held at all:

| reason | held? |
|---|---|
| sharing right now | yes |
| looking for Internet | yes |
| opted in to be woken | yes - **this is the case that was broken** |
| none of those | **no** |

Somebody who is neither offering nor looking has no position tracked. The service releases
it when the reason goes away and on `onDestroy`, so a position is never held behind a
stopped node.

Nothing in that decision mentions the screen. That was the bug, and a test asserts the
property directly: for every combination of the three reasons, `needed` equals
`opted || sharing || looking`.

### Self-correcting

Every caller that can change the answer - the notify switch, sharing starting or stopping,
a request beginning or ending, the permission being granted - calls
`ProkNetService.refreshZoneWatch(...)`, which is static because those callers have a
Context and not the service object. It is idempotent, and the service's existing
once-a-minute sweep calls it too, so a missed call site costs at most sixty seconds rather
than a silent regression.

### Visible

The diagnostic gained a line, because "is this phone holding a position, and how long is
the current fix good for?" was previously unanswerable:

```
zone watch: tracking: opted in to be woken | last fix il y a 3 min, good for 27 min
```

A stale zone is now visibly a stale zone.

### Not changed

No Brain, payment, BLE, L2CAP or VPN behaviour. Brain schema 4, Android DB 10.

## v0.17.7 evidence, not a memory of having asked

Build 74 shipped the manifest fix and the pilot phone was **still stuck** on the same
screen. That one was mine, and it is a clean example of a guess standing in for a fact.

### The bug

```kotlin
canAskLocationInApp() =
    !prefs.getBoolean("location_asked") || shouldShowRequestPermissionRationale(COARSE)
```

Build 73 set `location_asked` the first time the user tapped **Autoriser**. But on build
73 the permission was not in the manifest, so that request **went nowhere**. Build 74 then
inherited a flag saying "already asked" for a question that had never actually been put.

`shouldShowRequestPermissionRationale` returns false for **both** "never asked" and
"denied for ever". So the two together concluded the dialog was exhausted, and the app
offered a settings page instead of ever trying — on the build that had just fixed the
underlying problem.

### The rule

**A memory of having asked is a guess. Only the answer is evidence.**

The flag is now written solely from what a real request returned:

| outcome | meaning | flag |
|---|---|---|
| granted | done | cleared |
| denied, rationale owed | Android will ask again | cleared |
| denied, no rationale owed | "don't ask again" | **set** |

Nothing is assumed in advance. When in doubt, try the dialog: an unnecessary dialog costs
one tap, and a wrongly-offered settings page costs the user the entire feature.

It is also **scoped to the build that observed it**. A new build may declare different
permissions — build 74 did exactly that — so a verdict reached under an older manifest
means nothing. That alone would have unstuck the pilot phone.

### A fifth state: `NOT_IN_BUILD`

The app now asks the package manager whether `ACCESS_COARSE_LOCATION` is in **its own**
manifest:

```kotlin
packageManager.getPackageInfo(packageName, GET_PERMISSIONS)
    .requestedPermissions?.contains(ACCESS_COARSE_LOCATION)
```

When it is not, the dialog says so plainly and shows **no button**, because there is
nothing for the user to open. A settings page with no Position entry is a dead end, and
that dead end is what left the pilot tapping *Ouvrir* and finding nothing:

> Cette version de ProkNet ne contient pas l'autorisation de position… Ce n'est pas votre
> téléphone : installez la dernière version de ProkNet.

A missing declaration **outranks every other diagnosis**, because if the permission is not
in the build then no other answer can be true. A test asserts that ordering across every
combination.

This turns the whole class of build-time permission mistakes — the class that cost a full
day — from silence into one sentence on the screen.

### The test that caught the design

`every_blocked_state_says_something_the_user_can_act_on` failed on the first build, with
*"NOT_IN_BUILD needs a button"*. The rule it protects is still right — every blocked state
must say something — so it was split rather than weakened:

- every blocked state has a title, a message, a note and a diagnostic line;
- a **button** appears exactly when there is something to tap, which is every state except
  `NOT_IN_BUILD`.

The test was encoding a real decision, so the decision got written down instead of the
assertion being relaxed.

### Not changed

No Brain, payment, BLE, L2CAP or VPN behaviour. Brain schema 4, Android DB 10.

## v0.17.8 a map that grows with what it knows

A design pass on Carte, asked for after seeing it on a real phone. No behaviour changed —
this is about what the screen says and how much room it takes to say it.

### What was wrong

**The grid was a fixed 9×9.** `range = 4`, always. On the pilot phone ProkNet knew about
exactly **one** cell, so the map was eighty identical empty squares around one real one.
The grid was not lying — there genuinely was nothing else known — but a 9×9 field is a
promise of density the data could not keep, and it reads as a loading skeleton rather than
a map.

**The legend was dressed as statistics.** Two large cards, each a third of the screen,
reading **1** and **1**. They are a colour key. They were taking the best space on the
page to carry it.

**The same fact appeared twice.** "Fournisseur actif dans cette zone · 2 sources connues"
as the hero card, then again as a list row: "Vu par le réseau ProkNet · Autour de vous ·
2 source(s) connue(s)". One screen, one fact, two places.

**No orientation.** "à 500 m près" was a caption under the map. Nothing on the map itself
said which way north was or how big a square really is.

### What it does now

**The grid grows with the network.** `rangeFor()` measures the furthest cell ProkNet
actually knows something about and clamps it to 1..4. One known cell gives a confident
3×3 with large, well-rounded cells; as more cells are learned the grid widens and the
cells tighten. Watching it grow is the point — it makes the map a record of the network
spreading rather than a mostly-empty field.

**Unknown cells recede.** They fade towards the edge (`unknownAlpha`), so the empty area
becomes context instead of competing with the one square that means something. Known
cells get a soft radial glow in their status colour, so the eye lands on information.

**Your cell is unmistakably yours** — a brand-coloured rounded stroke around the square
itself, not only a dot in the middle of it.

**The map carries its own compass and scale.** A small `N` with a tick above the grid, and
a bar exactly one cell wide labelled `500 m` below it. Scale you can see beats scale you
have to read.

**The legend is one quiet strip** — `● 1 disponible · ● 1 vu récemment` — and the map
above it got the room back. The map also lost its card border: a bordered box inside a
padded page was a frame around a frame.

**The duplicate row is gone.** Shared cells are listed for *other* zones only; the hero
card already states what the network sees here, with the same source count.

### The caption now carries the rule

`map_grid_hint` used to repeat the scale. It says this instead:

> Une couleur montre ce que ProkNet a vu ici. Ce n'est pas une promesse de connexion.

That is the v0.17.2 honesty rule — a zone colour is not a working connection — stated
where the colours actually are, rather than only on Home.

### Constraints respected

Still one custom `View` drawing on `Canvas`: no AndroidX, no map SDK, no tiles, no network
fetch, nothing that stutters on a low-end phone. The only new per-frame work is one radial
gradient per *known* cell, and known cells are few by definition.

## v0.17.9 a service type chosen before the permission existed

Found on hardware the same evening v0.17.8 shipped, from the Brain's access log rather
than from the phone — the payment calls were arriving on schedule and **no presence came
with them**.

### The defect

v0.17.6 chose the foreground-service type set **once**, inside `startForeground`:

```kotlin
var types = FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
if (hasLocationPermission()) types = types or FOREGROUND_SERVICE_TYPE_LOCATION
startForeground(NOTIF_ID, notif, types)
```

The service starts when the app first runs — which is **before** the user has granted
location. So it came up as `connectedDevice` only. The permission was granted a minute
later, the zone watch started, and it worked **while the app was on screen**. The moment
the app went to background Android cut the location updates, because a foreground service
without the `location` type may not have them. Thirty minutes later the fix expired, the
zone went to `z?`, and the phone stopped publishing presence.

Eight presence posts while the app was being used, then silence. **TESTING 78 could never
have passed**, and the overnight run would have been a wasted night.

### The rule

**A capability decided before its permission exists has to be re-asserted after the
permission arrives.** Same shape as v0.17.5 (asking on one screen only) and v0.17.7 (a
memory of having asked): a decision taken once, at the wrong moment, and never revisited.

`ensureForegroundTypes()` recomputes the set and calls `startForeground` again only when
the answer actually moved. It runs on every start intent and from the service's existing
once-a-minute sweep, so granting the permission takes effect within a minute without the
user restarting anything. It also runs *before* the zone watch starts, because beginning
location updates that Android will cut at the next screen-off is worse than not starting
them — it looks like it worked.

It logs the transition, so the diagnostic can show it happened:

```
foreground service types updated - it may now hold a coarse position with the app closed
```

### Not changed

No Brain, payment, BLE, L2CAP or VPN behaviour. Brain schema 4, Android DB 10.
