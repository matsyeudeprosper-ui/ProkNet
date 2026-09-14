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
