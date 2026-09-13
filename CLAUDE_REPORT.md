# CLAUDE_REPORT - ProkNet v0.5.0 "Secure Fast Link"

Date: 2026-09-13
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 48/48 automated tests pass, released, NOT yet tested on phones**

History: M1 v0.1.0, M2A v0.2.0, M2B v0.3.0 passed on two real phones.
M2C1 v0.4.0/v0.4.1 (one-relay carry-forward, hardened) built, untested
(needs three phones). Earlier reports in git history
(`4e394d7`, `db703ca`, `c3619cf`, `2867eda`, `acf9ca0`).

## 1. What I built (one branch, one release)

| Block | Done | Summary |
|---|---|---|
| A. Cryptographic identity | yes | P-256 key pair per install; ID derived from the public key; private key in app-private storage; survives restart/update; public record exchanged over BLE and Wi-Fi; v0.4 random ID kept as legacy |
| B. End-to-end encryption | yes | sign-then-encrypt: ECDSA inside; ephemeral ECDH + HKDF + AES-256-GCM outside; routing header as authenticated data; relay sees metadata only; direct A->B works with two phones; relay path keeps ciphertext byte-identical |
| C. Transport abstraction | yes | `transport/Transport.kt` interface; BLE and Wi-Fi implement it; routing, queue and transfer engine never touch Bluetooth or sockets |
| D. Wi-Fi transport | yes | local-only hotspot on the responder, `WifiNetworkSpecifier` join on the initiator (legacy join on Android 8-9), TCP link with signed mutual handshake, negotiated over encrypted BLE control messages; link state machine with timeouts and retry backoff |
| E. Large payloads | yes | any text length, files up to ~2 MB, whole-blob encryption, 400-byte chunks on either transport, file-backed reassembly with persisted mask, SHA-256 + signature verification, progress %, pending/sending/delivered/failed and receiving/received/failed states, automatic Wi-Fi negotiation above 4 KB with BLE fallback |
| F. Preserve behaviour | yes | discovery, queue, receipts, dedup, foreground service, screen-off, swipe-away and one-relay logic are unchanged in code path; all v0.4.1 routing tests still pass (adapted to packet v4) |
| G. Tests | yes | 48 JVM tests gate the build (were 23); see section 4 |
| H. UI / diagnostics | yes | fingerprint, `[key]`/`[no key]` per peer, BLE and Wi-Fi link state, active transport, bytes tx/rx per transport, transfer %, `e2e`/`PLAIN` per message, `signed`/`unverified`, real version from `PackageInfo` |
| I. Scope | kept | no wallet, payments, marketplace, multi-hop, economics; the Wi-Fi socket is ready to carry a tunnel frame type |

## 2. Cryptography architecture

- **Curve/primitives:** P-256 (secp256r1) for both ECDH and ECDSA-SHA256,
  AES-256-GCM, HKDF-SHA256 (HMAC-based, own 40-line implementation). All
  standard JCA on Android API 26+ and on the VPS JVM. Curve25519/Ed25519 were
  not chosen because they are not guaranteed in JCA below API 33.
- **Identity:** `ID = SHA-256(X||Y)[0..16]`. The 16-byte ID format, short IDs,
  scan responses, SQLite keys and routing are untouched. Fingerprint = 32 hex
  chars of the same hash. The identity record `[2][id][pub 64][name]` is
  self-authenticating: a record whose ID does not derive from its key, or
  whose key is not on the curve, is rejected (invalid-curve defence added
  after a test caught that the JDK does not check it).
- **Key storage:** PKCS#8 bytes, Base64, in `SharedPreferences` MODE_PRIVATE.
  AndroidKeyStore was not used: it cannot do ECDH below API 31 and would have
  split the code path. The key never leaves `Identity` (only `sign`, `open`,
  `buildSigned` are exposed) and is never logged, exported or displayed.
- **Envelope:** `[ephPub 64][nonce 12][GCM(ct)+tag]`, key =
  HKDF(ECDH(eph, destStatic), salt "ProkNet-v5", info = ephPub||destPub||aad).
  `aad = origin||dest||msgId||ts||type`, so a relay changing any of those breaks
  the tag; `lastHop` and `hops` are outside the aad and are the only fields a
  relay may change. Plaintext = `[sigLen][ECDSA sig][kind][body]`, signature
  over `aad||kind||body`, so the destination knows the origin wrote exactly
  this and a relay cannot even see the signature. Fresh ephemeral key and
  nonce per message (per-message forward secrecy). Overhead: 92 bytes
  envelope + ~72 bytes signature -> 275 bytes of text still fit one BLE packet;
  longer text becomes a transfer automatically.
- **Key learning:** on first sight of a peer the node reads its identity
  record over one short BLE connection; every later send reads it on the same
  connection if still unknown; the Wi-Fi handshake also delivers it. To
  encrypt for C, A must have met C once. No plaintext fallback: an unknown
  key refuses the send with a clear log and toast.
- **Wi-Fi handshake:** HELLO (record + 16-byte nonce) both ways, then AUTH =
  signature over `"ProkNet-wifi-1"||myId||peerId||myNonce||peerNonce`. Both
  verified before the link is UP; the link is bound to identities, not
  addresses. The Wi-Fi payload itself is NOT additionally encrypted at the
  link layer: every frame on it is already an end-to-end envelope (the WPA2
  hotspot adds its own layer as well).

## 3. Exact Wi-Fi mechanism

`WifiManager.startLocalOnlyHotspot` on the host (responder) + on the
initiator `ConnectivityManager.requestNetwork` with a `WifiNetworkSpecifier`
(SSID + WPA2 passphrase) on Android 10+, or the legacy
`WifiConfiguration/enableNetwork` on Android 8-9. Credentials, host IPv4
candidates and port (47741) travel inside an encrypted BLE control message
(`WIFI_REQUEST` / `WIFI_OFFER` / `WIFI_CANCEL`). Sockets are created through
the granted `Network` so they bind to the hotspot even when mobile data is
on. Frames: `[u32 len][type][payload]`, every PACKET answered by a RECEIPT
frame; same receipt codes as BLE. Roles: whoever wants the link initiates
and joins; if both request at once the lower ID hosts. Not Wi-Fi Direct:
see `docs/ARCHITECTURE.md` "Wi-Fi link" for the reasoning.

The next milestone's Internet tunnel is another frame type on this same
socket (client side `VpnService` -> frames -> host side sockets); nothing in
the link needs to change for it.

## 4. Automated tests: what worked

`dist\test-results.txt`: `total=48 failed=0 errors=0 skipped=0`, 108 s
including the APK.

| Required | Test |
|---|---|
| crypto identity persistence model | `CryptoTest.identity_is_derived_from_the_public_key_and_is_stable` (PKCS#8 round trip, same ID, fingerprint) |
| encrypt/decrypt success | `seal_and_open_succeed_for_the_destination_only` |
| wrong destination cannot decrypt | same test: relay B and sender A both fail |
| tampered ciphertext fails | `tampered_ciphertext_or_header_fails_to_open` (byte flips in eph key, nonce, ct, tag; truncation; wrong aad) |
| sender authentication | `signed_plaintext_authenticates_the_sender_inside_the_envelope` (wrong signer, wrong header, altered body all fail) |
| relay cannot decrypt payload | `relay_sees_only_routing_metadata_and_cannot_read_or_alter_the_payload` (B cannot open; forwarded ciphertext byte-identical; header tamper and origin relabel fail at C) |
| packet routing metadata remains valid | `RoutingTest.encrypted_envelope_keeps_routing_metadata_valid_through_a_relay`, `PacketTest.envelope_and_chunk_types_carry_opaque_payloads` (aad excludes lastHop/hops/ttl) |
| transport selection | `TransferTest.transport_selection_prefers_wifi_when_up_and_negotiates_only_for_big_payloads` |
| fragmentation/reassembly | `chunking_round_trip_in_order`, `out_of_order_duplicate_and_missing_chunks` (shuffled order, duplicate ignored, wrong geometry refused, mask survives restart), `malformed_chunks_are_rejected_without_throwing` |
| large payload integrity | `corrupted_payload_fails_integrity_and_relay_cannot_open_blob`, `text_larger_than_one_packet_goes_through_the_blob_path_intact` (2400 chars) |
| reconnect/retry behaviour | `LinkStateTest` x4: initiator and host paths, tie-break, loss -> DOWN -> retry backoff 5 s..60 s capped, success resets, step timeout, UP never times out |
| identity record / handshake / control messages | `WireTest` x4 (self-authenticating record, control TLVs + fuzz, handshake binding to nonces and keys, receipt frames) |
| everything from v0.4.1 | `PacketTest` 11 (v4 + v1/v2/v3 decoding), `RoutingTest` 16 incl. the three-phone A->B->C simulation and `chunk_packets_are_delivered_direct_only_and_never_relayed` |

Two test-side findings during the pass: (1) the JDK accepted an off-curve
public key, so an explicit on-curve check was added to `Crypto.publicKeyFrom`;
(2) a missing import in a test. The build gate refused the APK both times.

## 5. What still requires physical-phone testing

Everything in `docs/TESTING.md` section 11, in this order of risk:

1. **Key learning over BLE** (11.1): the extra IDENTITY read inside a batch
   op is new code on the GATT client state machine.
2. **Encrypted direct message** (11.2): first real use of `open`/`verify` on
   Android's Conscrypt provider (same JCA names; expected to match the JVM).
3. **Hotspot bring-up** (11.5): `startLocalOnlyHotspot` behaviour, SSID
   readability per Android version (API 30+ `SoftApConfiguration` vs older
   `WifiConfiguration`), the AP interface address heuristic (`ap0`/`swlan0`/
   `wlan1`, address ending `.1`), and whether the host can hotspot while its
   Wi-Fi STA is connected (vendor-dependent).
4. **Joining** (11.5): the `WifiNetworkSpecifier` dialog, and whether the
   socket bound to that `Network` reaches the host (should, via DHCP subnet).
5. **Throughput and the 1 MB test** (11.6): per-chunk receipt over TCP is
   ~2500 round trips for 1 MB; expected seconds, not minutes.
6. **Loss/resume** (11.8) and **automatic negotiation** (11.7), including the
   dialog appearing while the app is in the background.
7. **Migration**: an upgraded install gets a new ID; old peers show as stale
   entries; v0.4 pending plaintext rows are marked failed with an explanation
   (they cannot be encrypted after the fact).

## 6. Android permissions and system dialogs

| When | What the user sees |
|---|---|
| Start, Android 13+ | "Nearby devices" (Bluetooth + Wi-Fi), then notifications |
| Start, Android 12 | Bluetooth "Nearby devices" + Location (fine + coarse, must be requested together) |
| Start, Android 8-11 | Location |
| Host side of a Wi-Fi link | none, but Wi-Fi ON and Location ON are required by the system for a hotspot on most versions; failure reasons are logged (`hotspot failed, reason N (hint)`) |
| Initiator side, Android 10+ | one system dialog "ProkNet Lab wants to use a temporary Wi-Fi network / connect to <ssid>": tap Connect. Once per link set-up. |
| Initiator side, Android 8-9 | none (legacy join) |
| Send file | the system document picker |

Manifest additions: `NEARBY_WIFI_DEVICES` (neverForLocation), `ACCESS_FINE/
COARSE_LOCATION` up to API 32, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_STATE`,
`ACCESS_NETWORK_STATE`, `CHANGE_NETWORK_STATE`, `INTERNET` (sockets; no
Internet is used).

## 7. Known OEM limitations

- **Hotspot while connected to Wi-Fi** (STA+AP concurrency) is not
  supported on some chipsets; the host may need Wi-Fi on but not connected.
  The failure is logged with the Android reason code.
- **Hotspot band/channel:** some phones start the local-only hotspot on 5 GHz;
  a joining phone without 5 GHz support will not see it (`onUnavailable`).
- **Vendor battery managers** (Xiaomi, Huawei, Oppo, some Samsung) can kill
  the hotspot or the service; the Battery button and the existing v0.3
  mitigations apply.
- **Android 10+ specifier networks** are app-scoped: only ProkNet's sockets
  use the hotspot; the phone's normal connectivity is untouched (good for
  us, but it means the link exists only while the service holds the request).
- **Legacy join (Android 8-9)** may not route to the hotspot subnet if the
  phone prefers mobile data; best effort only.
- **Scan-response size**: unchanged from v0.4.1 (short-ID fallback exists).

## 8. Files / components

```
core/Crypto.kt        NEW  keys, id derivation, fingerprint, sign/verify, seal/open, Signed plaintext
core/Identity.kt      key-backed identity, migration, sign/open/buildSigned
core/Packet.kt        v4: opaque payload, types, aad(); v1-v3 decoding
core/Transfer.kt      NEW  chunking, assembler, blob body (pure)
core/LinkState.kt     NEW  Wi-Fi link state machine (pure)
core/Wire.kt          NEW  identity record, control TLVs, TCP frames, handshake data (pure)
core/Routing.kt       CHUNK decisions, transport selection, payload-based outgoingPacket
core/MessageStore.kt  schema v4: payload/enc/verified/transport, peer_keys, transfers
transport/Transport.kt, BleTransport.kt, WifiTransport.kt   NEW
node/TransferEngine.kt   NEW
ble/BleSender.kt      batch sends on one connection, identity reads, resume after reconnect
ble/GattServerNode.kt identity record v2, bytes counter
ble/DeliveryQueue.kt  transport-agnostic, opaque payloads
ble/ProkNetNode.kt    transports, key learning, encryption/decryption, control routing, engine
ui/MainActivity.kt    diagnostics, Wi-Fi link / Big test / Send file, real version
app/src/test/...      CryptoTest, TransferTest, WireTest, LinkStateTest (new), PacketTest, RoutingTest (updated)
build.ps1             discards stale test reports before each run
AndroidManifest.xml   Wi-Fi/network permissions; build.gradle.kts versionCode 6 / 0.5.0
README.md, docs/ARCHITECTURE.md (identity, E2E, transports, Wi-Fi, transfers), docs/TESTING.md section 11
```

## 9. Exact APK / release / build

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.5.0
Build 6: 0.99 MB, SHA256 `3860d25b07612890b48beb35f50e8cfa452f6ce50514caf2e4810a59ad0f28b3`,
versionCode 6, versionName 0.5.0. Build command unchanged:
`powershell -ExecutionPolicy Bypass -File C:\Projects\ProkNet\build.ps1`
(runs the 48 tests first; no APK on failure).
Code + tests + docs: commit `bfb3719fb3bbdfa1a854887bbbf5c6170c9b89ae` on `main`.
This report is committed on top.

## 10. Known limitations and honest uncertainties

- Chunked transfers are direct only; a relay refuses CHUNK packets.
  Carrying encrypted blobs for others is the next relay milestone.
- After a handoff the origin still stops (2C1 rule), unchanged.
- Received files stay in the app's private `files/received/`; the UI shows
  name, size and SHA-256 but does not export them yet (needs a content
  provider or MediaStore write; deferred to keep the scope).
- The 1 MB transfer keeps the whole blob in memory on both sides (fine at
  the 2 MB cap; a streaming path is needed before larger files).
- Per-chunk DB and file writes on the receiver may make a 1 MB Wi-Fi
  transfer take ~10-20 s instead of ~2 s; measured only on the phone.
- One hotspot per phone: while hosting a link to A, B cannot host one for C.
- The VPS disk is at 1.66 GB free; the build script stops below 1 GB.

## 11. Recommendation

Test section 11 on two phones. The three things that decide whether the
Wi-Fi mechanism is right for the target devices are 11.5 (link comes up),
11.6 (1 MB in seconds) and 11.7 (automatic). If hotspot bring-up fails on
Mike's phones for a vendor reason, the fallback design is Wi-Fi Direct
behind the same `Transport` interface: nothing above the transport changes.
After that, the Internet tunnel (client `VpnService` over the Wi-Fi socket)
is the natural next block.
