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
| v0.9.7 | Wi-Fi Direct experiment (method B) behind the developer screens: form a P2P group while the seller stays on its home router, adopt the P2P socket into the existing authenticated link, COPY P2P DIAG. Method A untouched | phone test: stale P2P state survived STOP, so a later BUY never discovered anything |
| v0.9.8 | Deterministic P2P lifecycle: every role change walks cancelConnect, stopPeerDiscovery, close sockets, removeGroup, each confirmed by Android, and only then starts the new role | phone test: seller hosts a group and stays on the router, but the buyer never joins |
| v0.9.9 | The group OWNER invites the guest (a group owner cannot join another group), the guest waits and is never silent, and the whole path runs from the normal Internet screens with the hotspot as the fallback | phone test: a 70 min Internet session over Wi-Fi Direct WORKED; afterwards BLE discovery was wedged |
| v0.9.10 | Self-healing BLE: real health tracked from the callbacks, a controlled scanner/advertiser restart after a session or when discovery is wedged, with cooldown, and the buyer's admission ladder pauses instead of hammering a transport that is gone | phone test: BLE stayed healthy, but the buyer ignored the seller's refusal |
| v0.9.11 | The seller's refusal is delivered to the buyer that asked, a seller never advertises the direct way in without a live group and rebuilds it when a customer asks, and every failure keeps its message | phone test: the seller has a real group but Android anonymises the buyer in its peer list |
| v0.9.12 | The BUYER joins: the seller only answers GROUP_READY / REBUILDING / NOT_AVAILABLE with its own Wi-Fi Direct name, and the buyer finds it in its own peer list and connects, with a bounded retry when Android is busy | phone test: the whole topology forms, seller on the Freebox AND group owner, buyer joined as client |
| v0.9.13 | The transport socket belongs to an endpoint: the seller listens ON the Wi-Fi Direct address with a generation, revalidates it when a client joins, a stale accept loop can never feed a newer group, and the buyer dials with its socket bound to the P2P network | phone test: every field matched, the listener was called valid, and six dials still timed out |
| v0.9.14 | Client membership is part of the transport: a data plane generation only becomes usable when somebody has joined, the listener is armed for THAT membership, the provider announces TRANSPORT_READY and dials the customer as well, and every socket says how it is bound | phone test: both listeners armed, both sides dialled correctly bound, and every SYN in BOTH directions timed out |
| v0.9.15 | The radio: peer discovery stops the moment somebody joins, because scanning takes a single-radio phone off the group channel, and a UDP link probe measures whether any IP packet crosses the link at all | phone test: the probe MEASURED the fault. The link is ONE WAY, client to owner only |
| v0.9.16 | One way link: the radio is held awake with a Wi-Fi lock while a group exists, the group channel is logged next to the home Wi-Fi channel, and the probe now separates unicast from broadcast in both directions | phone test blocked: the screen said "Connexion perdue" instantly, so the build was never exercised |
| v0.9.17 | A purchase is judged by the transport it actually uses, and starts from a clean screen: no failure from an earlier attempt can end the next one before it begins | phone test: the screen behaved, and the join ladder then dialled a printer |
| v0.9.18 | Only the provider that named itself is ever dialled, and an accepted join is waited for instead of being overtaken by its own successor | phone test: the probe confirmed the one way link with the radio lock held, and the seller then stopped being findable |
| v0.9.19 | A provider whose customer left is findable again, and a provider on a 5 GHz home Wi-Fi asks for a 2.4 GHz group instead of sharing one channel with it | phone test: admission failed the other way round, the seller saw the buyer and the buyer saw nobody, so the band experiment never ran |
| v0.9.20 | Admission is symmetric: the two phones exchange what each can see over BLE, the provider turns that into one plan (BUYER_CONNECT, SELLER_INVITE or WAIT), and one attempt owns admission until it forms a group or dies | phone test: the plan was decided correctly and the invitation was never sent, because the provider's own empty group counted as admission being over |
| v0.9.21 | A group is a room, membership is admission: a provider holding an EMPTY group invites the customer it can see, and every ending names the stage that actually failed | phone test: a group formed on 2.4 GHz at last, but discovery ran through the data window; and a SELLER_INVITE was killed 2 ms after it was chosen |
| v0.9.22 | An accepted association owns the radio and its own clock: discovery cannot restart while it is in flight, membership turns discovery off whatever else changed, and the attempt is timed from the moment Android accepted it | phone test: the first CLEAN 2.4 GHz run. The group formed, discovery stopped at once, and NO IP packet crossed |
| v0.9.23 | Membership ends admission for good, failures are filed by stage, each phone keeps its last test result after cleanup, and the customer can own the group instead of the provider (controlled experiment) | phone test: the topology reached the seller, but the buyer still sent a hotspot request and the seller kept a dead session alive |
| v0.9.24 | A customer that owns the group always starts Wi-Fi Direct, and the provider's reversed session ends as one unit when the customer cancels | phone test: the right path at last, and then createGroup was accepted and the group never formed, because a transient formed=false restarted discovery against Android's own creation |
| v0.9.25 | Creating a group is its own stage with its own clock: a formed=false after an accepted createGroup is held, an accepted group that never forms is retried and then fails specifically, and an owner never asks the provider about its group | phone test: the retry ladder worked, but the admission layer still decided plans and restarted discovery while createGroup was pending |
| v0.9.26 | The admission plane is dormant while this phone creates its group: one pure gate every admission action asks, a deferred visibility evaluated once the group exists, and a hard net under discovery | phone test: the first genuinely clean creation attempt on the OnePlus, accepted, silent, no group; then two framework BUSY answers were counted as attempts 2 and 3 |
| v0.9.27 | BUSY is not an attempt: a creation state machine with two counters, a verified framework reset between real attempts, every creation failure typed as GROUP_CREATE_FAIL, and a screen that never blames the provider for this phone's own Wi-Fi Direct | built, 203 JVM tests pass; Wi-Fi Direct then archived (see below) |
| v0.10.0 | Bluetooth bulk Internet: the provider keeps its home Wi-Fi and serves over a Bluetooth L2CAP channel, the same signed handshake / tunnel / VPN on top, a 1 MB probe each way before the tunnel, no hotspot and no Wi-Fi Direct | built, 211 JVM tests pass; runtime still ran the old sharing machinery (fixed in v0.10.1) |
| v0.10.1 | The runtime obeys the architecture: a home-Wi-Fi Bluetooth seller chooses the Bluetooth path directly, runs no hotspot probe, and never creates a Wi-Fi Direct group automatically; the watchdog and reachability know the bulk link | **phones proved it**: seller stayed on the Freebox, no probe, no Wi-Fi Direct, L2CAP + signed auth + BULK UP; the 1 MB full-duplex probe was too aggressive (one direction killed at 700-960 KB) |
| v0.10.2 | Sequential probe: 256 KB buyer -> seller, confirmed, then 256 KB seller -> buyer, confirmed; PARTIAL / NO_DATA / PASS per direction instead of "carried nothing"; a two-button test screen with COPY TEST RESULT | **HARDWARE PROVEN 2026-09-19 on the OUKITEL (Android 15) / OnePlus (Android 14) pair**: Freebox Wi-Fi -> seller -> Bluetooth L2CAP -> buyer -> ProkNet VPN -> real Internet. Signed auth, 256 KB PASS both ways, contract, session, VPN, DNS, HTTPS (TLS 1.3, HTTP 200), accounting/checkpoint, Wikipedia in Chrome |
| v0.11.0 | Consumer integration: the proven two-phone path from the normal app. Seller: Partager -> Commencer. Buyer: Internet -> offer -> Connecter -> "Internet connecté ✅". Transport chosen by rule, never by the user; VPN explained in one sentence; failures in one sentence; stale developer Wi-Fi Direct group removed at start | built, 222 JVM tests pass |
| v0.12.0 | One big button: OBTENIR INTERNET. A pure decision engine (free first, cheapest reliable next, never a stale or unauthorized source), an Internet request model, a coverage observation layer (one record per real source, coarse ~500 m cells, hashed Wi-Fi ids, no passwords), the first Carte tab, a polished consumer UI (Accueil / Internet / Carte / Gagner / Activité), COPY COVERAGE for developers | built, 236 JVM tests pass |
| v0.12.1 | The design pass: one design system (colors light + night, type scale, bg_* drawables), a glowing one-tap button, bordered rounded cards, readable legend pills, status chip with a dot, a real nav bar; the map card no longer contradicts itself | built, 236 JVM tests pass |
| v0.12.2 | Accueil and Carte redesigned from scratch: a drawn sonar button (`PulseButtonView`, rings sweep while searching), three quiet tiles, two door cards; the map is a sonar of real sightings (you at the centre, fresh near, old far, tappable) or the cell grid once a position exists | built, 236 JVM tests pass |
| v0.12.3 | The sphere drawn to look lit and heavy (specular highlight, dark edge, drop shadow, luminous rim), ambient blue + violet light and two faint scan arcs instead of a bullseye, the usable sources as green dots on the arc, a radar wedge while searching; tiles that never truncate | built, 236 JVM tests pass |
| v0.12.4 | The sphere is the interface: it breathes at rest, arcs and source dots orbit, it pulses while searching, ripples when pressed, turns green when connected, one word per state; the home loses its question and status lines, tiles show values only, door rows are title + chevron | built, 236 JVM tests pass |
| v0.12.5 | Under the sphere: one slim stat strip (sources · prix · dernière fois) and two icon action cards (Partager, Carte) | built, 236 JVM tests pass |
| v0.13.0 | The Network Brain: an offline signed Internet request that the phones around store, carry and forward (once per peer, dedup, TTL, hop budget, tombstones); provider activation ("Quelqu'un cherche Internet à proximité" → PARTAGER); a Python Network Brain (one signed idempotent sync, SQLite, matching, TTL cleanup); shared coverage that never manufactures GREEN; the job model | built, 248 JVM + 11 server tests pass; brain BUILT + TESTED LOCALLY |
| v0.13.1 | Two bugs from the first v0.13 phone run: a fresh request showed the error of a session the user had stopped ("Connexion perdue / RÉESSAYER"); and after Bluetooth OFF → ON the watchdog said "healthy" forever with adv on, scan on, peers 0. Plus the five answers at the top of COPY NETWORK | built, 252 JVM + 11 server tests pass |
| v0.13.2 | The activation blocker: a phone on a validated Freebox with sharing OFF refused every request with NO_INTERNET, because eligibility read the seller gateway, which only starts after PARTAGER. Provider eligibility now reads the phone's real current Internet (`node/Upstream.kt`, the scan shared with the gateway) | built, 255 JVM + 11 server tests pass |
| v0.13.3 | Provider activation reliability: a demand inbox that a dismissed notification cannot lose, one alert per request instead of a wall-clock cooldown that swallowed real buyers, a BLE control plane rebuilt by generation (GATT server → ProkNet service → only then advertising), and bounded forwarding instead of retry storms | built, 273 JVM + 11 server tests pass |
| v0.14.0 | Human pricing: the buyer picks a CFA budget and can never be charged more; the seller picks what to earn and never types a price; ProkNet computes a rate that covers the seller's real data cost, safety and margin, keeps free Internet free, and refuses deals that would lose money | FAILED on the phones: every budget contract was rejected as "malformed proposal" |
| v0.14.1 | Stable budget contracts: signed contracts are parsed at the length their own version byte declares, the admission decision is one pure function the tests run, the budget ceiling holds against hostile byte counts, and the whole session is tested from proposal to ledger | **HARDWARE PROVEN (2026-09-20): v2 budget contract agreed, session, VPN, Internet, real TCP flows, signed checkpoints, repeat session** |
| v0.17.10 | The last structural blocker: the phone talked to the Brain every 15 minutes against a 120-second presence window, so a provider was visible ~13% of the time and a waiting buyer could sit on "Recherche" for a quarter of an hour after somebody had accepted. The right cadences were already declared in the code and nothing used them; they now follow the state. refreshDetection() also finally gets called | built, 731 JVM + 360 server tests pass; hardware acceptance pending |
| v0.17.9 | The foreground service chose its Android service type once, at start, before the user had granted location - so it was never allowed to hold a position with the app closed, and the overnight idle-provider test could not have passed. The type is now re-asserted whenever the answer could have changed | built, 721 JVM + 360 server tests pass; hardware acceptance pending |
| v0.17.8 | Carte redesigned: the grid grows with what ProkNet actually knows instead of always drawing a 9x9 field of empty squares, unknown cells recede so the known ones carry the eye, the map draws its own compass and 500 m scale, the two large "1" cards become one quiet legend, and the duplicated zone row is gone | built, 721 JVM + 360 server tests pass; no behaviour change |
| v0.17.7 | Build 74 fixed the manifest and the phone was still stuck: the app had inherited a note saying it had already asked, for a question that never reached Android. The flag is now written only as evidence from a real answer and scoped to the build that saw it, and a build missing the permission says so with no button instead of pointing at an empty settings page | built, 721 JVM + 360 server tests pass; hardware acceptance pending (TESTING 77, 79, 78, then 75 and 76) |
| v0.17.6 | The root cause of the lost day, found in the built APK: `ACCESS_COARSE_LOCATION` was capped at API 32, so on Android 13+ the permission the whole Network Brain depends on was never declared and could never be granted. Also: the coarse zone is now held by the foreground service instead of the screen, and a stationary phone no longer goes stale | built, 716 JVM + 360 server tests pass; hardware acceptance pending (TESTING 77, 78, then 75 and 76) |
| v0.17.5 | The first defect real phones found: the whole Brain layer is gated on a coarse zone, and the app only ever asked for that permission on the map tab - so a healthy phone was invisible to the network all day and neither screen said why. ProkNet now asks where the zone is needed, with one tap, and says plainly when it is missing | built, 706 JVM + 360 server tests pass; hardware acceptance pending (TESTING 77, then 75 and 76) |
| v0.17.4 | The offer class is the truth: a seller who cannot currently be paid is simply unavailable instead of being advertised as free, FREE comes only from an explicitly free source through the same predicate the pricing engine uses, and a demand nobody can serve no longer starves the demands somebody can | built, 696 JVM + 360 server tests pass; hardware acceptance pending (TESTING 75 then 76) |
| v0.17.3 | An idle provider is a real provider: being willing to share and currently sharing are separate facts, so a phone that is opted in with Internet is visible to the Brain before it starts the seller; the PARTAGER acknowledgement retries on every poll and across a restart without a second tap; and a temporary network failure is no longer read as a definitive refusal | built, 687 JVM + 352 server tests pass; hardware acceptance pending (TESTING 75) |
| v0.17.2 | The distant loop actually closed: a Brain job carries the buyer's own signed request so a provider that has never met them can verify and show it, PARTAGER now acknowledges the activation (with retry across restarts), a zone colour no longer claims a working connection, coverage ages on its own clock, and a live request follows the buyer between zones | built, 663 JVM + 336 server tests pass; hardware acceptance pending (TESTING 74) |
| v0.17.1 | The Network Brain made visible: Home shows what the network is doing, the map merges Brain zone colour with local observation, Activité records the request and the match once each, and an accepted provider gets ten minutes to reach the buyer instead of seventy-five seconds. Plus late matching when a provider appears, oldest-first fairness, and local networking superseding Brain coordination at any time | built, 643 JVM + 314 server tests pass; hardware acceptance pending (TESTING 73) |
| v0.17.0 | The live Network Brain: a buyer with no local source creates a demand, the Brain finds a nearby provider worth asking, the provider gets the existing PARTAGER notification, and the proven Bluetooth path then carries the Internet. Presence with a TTL, deterministic matching, zone colours, and a control plane that never carries traffic or touches money | built, 611 JVM + 287 server tests pass; hardware acceptance pending (TESTING 72) |
| v0.16.5 | The destination claim's timestamp is signed (claim v2), so the cooling window cannot be moved by whoever carries the claim; legacy claims stay readable; and the Brain judges a payment expectation by the destination that was active when the buyer created it, not when it happened to arrive | built, 583 JVM + 210 server tests pass; hardware acceptance pending (TESTING 71) |
| v0.16.4 | Upgrade safety and destination rotation: a real database migration for phones carried forward from any earlier v0.16 build, the active destination handed out consistently during a change, buyers refreshing seller destinations while a debt stands, and losing that access once it is paid | built, 568 JVM + 185 server tests pass; hardware acceptance pending (TESTING 70) |
| v0.16.3 | Final payment hardening: signed parser rules switched on with a real dedicated key and an admin publishing tool, both operators reachable through the Brain, settlement detail reads authorised, financial DB changes atomic, and every money endpoint requiring a method/path-bound signature | built, 536 JVM + 174 server tests pass; hardware acceptance pending (TESTING 69) |
| v0.16.2 | The Brain carries the signed payment objects so two phones that never meet again can still finish a payment; reinstall debt is server-backed; parser rules can arrive as signed data | built, 514 JVM + 112 server tests pass; hardware acceptance pending (TESTING 68) |
| v0.16.1 | The payment loop wired end to end: the seller's signed destination reaches the buyer, the buyer's signed expectation reaches the seller, and the seller's signed receipt comes back and clears the debt, verified at every hop; plus the capture privacy gate and buyer-only trust accounting | built, 476 JVM + 70 server tests pass; hardware acceptance pending (TESTING 67) |
| v0.16.0 | Automatic Mobile Money receipt verification: the buyer pays cash at any ordinary kiosk, the seller's phone detects the operator's own message and clears the debt with no transaction reference; plus a template-independent parser, signed seller destinations, a buyer credit limit and anti-reinstall device binding | built, 455 JVM + 70 server tests pass; hardware acceptance pending (TESTING 66) |
| v0.15.3 | End-to-end settlement trust: the phones now build, persist and submit the signed session evidence the server verifies, with an offline queue and bounded retry; the server enforces that a payment may only be started by its own buyer; Compte moved inside Activité | built, 408 JVM + 70 server tests pass; hardware acceptance pending (TESTING 65) |
| v0.15.2 | Gagner and Activité redesigned: Gagner asks one question with one button and folds every expert control away, Activité stopped duplicating the Wallet and is now a clean history with its own account section | built, 396 JVM + 55 server tests pass; hardware acceptance pending (TESTING 64) |
| v0.15.1 | Wallet polish and settlement trust: the server re-derives every figure from signed evidence instead of believing a phone, submissions are signed and replay-protected, one real operator transfer became a first-class PaymentTransaction with allocations, and the Wallet was redesigned as a consumer money screen | built, 379 JVM + 55 server tests pass; hardware acceptance pending (TESTING 63) |
| v0.15.0 | Prok Wallet: a finished session becomes a signed CFA obligation both phones derive independently, netted per seller, gated by a credit limit, payable through a rail abstraction (mock and manual pilot real; MTN and Airtel interface only). Plus the seller-stop stability gate that had to pass first | **seller-stop HARDWARE PROVEN (2026-09-20): short seller-ended sessions settle non-zero, both phones agree, reconnect works.** Wallet hardware test pending |
| v0.14.2 | Clean shutdown, and the seller-stop half of it proven in v0.15.0: stopping settles the usage both phones signed for before anything closes, a session shorter than the checkpoint interval is no longer free, an intentional close is never reported as a transport failure, and a callback from a finished session cannot poison the next one | built, 327 JVM + 11 server tests pass; hardware acceptance pending (TESTING 60) |

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

## Proven

**Two-phone home-Wi-Fi sharing over Bluetooth: HARDWARE PROVEN on this
device pair** (2026-09-19, v0.10.2 build 43). OUKITEL C1 Pro, Android 15,
seller on the Freebox; OnePlus Nord CE 2 Lite, Android 14, buyer with
mobile data off. Bluetooth L2CAP authenticated with both signatures,
256 KB PASS buyer -> seller and seller -> buyer, contract agreed, session
started, VPN up, DNS working, HTTPS working (TLS 1.3, HTTP 200),
accounting and checkpoint working, Wikipedia loaded in Chrome with the
ProkNet VPN as the phone's Internet path. Not generalised to every Android
device: proven on this pair.

## Hardware test pending (v0.13)

- Offline request → local demand propagation → provider activation →
  automatic seller discovery → automatic connection → real Internet, on
  the OUKITEL + OnePlus pair. TESTING section 51. Until Mike runs it,
  provider activation is BUILT / SOFTWARE TESTED, not proven.

## Built and software-tested (not yet hardware-proven)

- Offline request gossip (`core/NetRequest.kt`, `core/RequestGossip.kt`,
  `node/NetworkNode.kt`): signed requests, store-carry-forward over the
  encrypted BLE control channel, dedup by id + generation, TTL, hop budget,
  tombstones, persistence.
- The Network Brain (`server/`): 11 tests, run locally end to end.
- Shared coverage (`core/SyncProtocol.kt`): summaries in, cells out, never GREEN from history.
- The job model and the matching (`core/Jobs.kt`, `server/brain/matching.py`).
- Provider activation matching and the rate-limited notification.
- Request carry: a signed request travels buyer → phone B → phone C with
  Internet → brain in software; the 3-phone case has no hardware yet.

- The one-tap decision engine (`core/GetInternet.kt`): 14 JVM tests on the
  ranking rules and the request state machine.
- The coverage observation layer (`core/CoverageModel.kt`, `node/CoverageEngine.kt`):
  deduplication, cells, conservative status, persistence codec.
- The first Carte tab: schematic ~500 m cells around the phone, no tiles.
- The Internet request model and the planner foundation with the economic
  rule (`core/InternetRequest.kt`).
- The one-tap flow on the phones (OBTENIR INTERNET -> the proven Bluetooth
  provider) is the pending hardware regression, TESTING section 50.

## Not yet hardware-proven, not claimed

- 3-phone request carry, 3-phone live Internet relay, multi-hop Internet,
  mover jobs, citywide autonomous fulfilment. The models exist (hops,
  roles, cost classes, job types); nothing executes them and no button
  pretends to.

## Decided

- **Wi-Fi Direct is archived (2026-09-19).** Two topologies, both measured on
  the real OUKITEL + OnePlus pair. SELLER_GROUP_OWNER: the OUKITEL forms a
  group and keeps its Freebox, but the IP path is one way (everything the
  client sends arrives, nothing the owner sends comes back, on the default
  band and forced to 2.4 GHz, with the Wi-Fi radio lock held).
  BUYER_GROUP_OWNER: the OnePlus never forms a group; Android accepts
  `createGroup` and the framework stays BUSY. Not viable for this hardware.
  The code and its diagnostics stay in the repo; nothing selects it in
  consumer operation. The local link moved to Bluetooth in v0.10.0.

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
                          PaymentEngine (v0.16.0), SettlementSync, PaymentSync (v0.16.2 Brain carrier)
    (core, cont.)         PayWire, ReceiptParser, ReceiptRules (v0.16.2 signed rules), BrainPayload, Trust, Wallet
    ble/                  Advertiser, GATT server, Scanner, Sender, DeliveryQueue, ProkNetNode
    service/              ProkNetService (foreground service owning the node)
    ui/                   MainActivity (v0.8 consumer screen), LabActivity (developer / diagnostics), RelayLabActivity (v0.9 relay experiment)
    ProkNetApp.kt         Application: creates the node once per process
build.ps1                 The build command
dist/                     Predictable APK output location (APK itself not committed)
docs/ARCHITECTURE.md      Design and technology choices
docs/TESTING.md           Step-by-step phone test procedure
docs/OPERATIONS.md        Running the Brain, and where the one signing key lives
deploy/brain/             install / start / stop / status / backup for the VPS
server/brain/             Network Brain (Python): sync, settlements, paybox (v0.16.2), ruleconfig (v0.16.2)
server/tests/             Server tests (python -m unittest discover -s tests)
CLAUDE_REPORT.md          Milestone report for the architect (ChatGPT)
```

## Roles

- ChatGPT: architect / product lead. Sets milestones, reviews reports.
- Claude Code: implementation engineer. Builds on the VPS, reports in `CLAUDE_REPORT.md`.
- Mike: tests on real phones, from the phone only.
