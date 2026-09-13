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

**Contract** (62 bytes): version, session id (8, random by the buyer), buyer
id, seller id, price/MB, minimum price, max MB, fee %, start time. Flow over
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

## Automated tests (87)

`app/src/test`: PacketTest 11, RoutingTest 16, CryptoTest 7, TransferTest 6,
WireTest 4, LinkStateTest 4, TcpipTest 5, TunnelTest 5, TcpFlowTest 6, LinkIoTest 7, MarketTest 9, TunnelRoutingTest 3, ProductStateTest 4. `build.ps1` runs them first and refuses the APK
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
