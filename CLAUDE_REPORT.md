# CLAUDE_REPORT - ProkNet v0.10.0 "Bluetooth bulk Internet"

Date: 2026-09-19
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 211/211 automated tests pass, released, not yet tested on phones.
Bluetooth Internet is NOT claimed until the phones prove bidirectional bytes + VPN + DNS + HTTPS.**

## 1. The transport chosen

A **Bluetooth L2CAP connection-oriented channel** between the two phones,
carrying the existing authenticated ProkNet stream. The provider listens with
`listenUsingInsecureL2capChannel()` and gets a dynamic PSM; the customer
connects with `createInsecureL2capChannel(psm)` to that exact
`BluetoothDevice`.

## 2. Why L2CAP first, not RFCOMM or GATT

- L2CAP is a byte stream, which is what the tunnel already wants; RFCOMM
  would work too but L2CAP has lower overhead and no SDP lookup.
- The peer relationship comes from BLE discovery and the ProkNet identity, so
  the socket connects to a specific device address, never to a Classic
  Bluetooth device matched by human name. No guessing, exactly as required.
- It needs no IP network, no hotspot and no Wi-Fi Direct group, which is the
  whole point after the Wi-Fi Direct evidence.
- Both test phones (Android 14 and 15) are well above the API 29 these calls
  need. RFCOMM remains a possible fallback in the same abstraction later, but
  only with a safe way to identify the exact peer; it is not built now.

GATT stays control and store-carry-forward. It never carries an Internet
tunnel.

## 3. How authentication is reused

The upper half of the old `WifiTransport.Link` is lifted into
`transport/StreamLink.kt`: the signed HELLO/AUTH handshake (`Handshake`,
unchanged), the `LinkIo` writer thread, PACKET/RECEIPT, tunnel frames, relay
frames, byte counters. A Wi-Fi TCP socket and a Bluetooth L2CAP socket each
implement `StreamLink.Endpoint`; a `StreamLink.Host` receives the verified
frames. `WifiTransport` was refactored onto it (its `Link` inner class is
gone, its behaviour is not), and `BluetoothBulkTransport` is the new owner.

No `Tunnel.Frame` is delivered before `handshake()` verifies the peer's
Ed25519 signature. "Insecure" in the Android API names means no OS pairing
dialog; it means nothing to ProkNet. A phone that reaches the PSM and fails
the handshake gets a closed socket.

## 4. How Tunnel and Gateway connect to it

`Routing.chooseTransport(wifiUp, bulkUp, bleReachable)` puts the Bluetooth
bulk link between Wi-Fi and GATT. `ProkNetNode.sendOnBulkLink(...)` routes
each tunnel frame to whichever bulk link holds the peer, via
`Routing.bulkLinkFor(...)`, and never to GATT. The gateway, the tunnel, the
contract, the checkpoints, the billing, the VPN and the marketplace are
untouched; `sendFromGateway` / `sendFromTunnel` now pick the bulk link
instead of assuming Wi-Fi. The provider advertises `Market.FLAG_BULK_BT`
(bit 7) when it is sharing with Bluetooth on, and `P2pAdmission.buyPath`
sends a provider on home Wi-Fi to the Bluetooth path while a mobile-data
provider keeps the hotspot.

## 5. The negotiation and its safety

`core/BulkPlan.kt` is the pure lifecycle. BLE controls `OP_BULK_REQUEST`,
`OP_BULK_OFFER`, `OP_BULK_READY`, `OP_BULK_CANCEL`, each carrying the
purchase's session token. A stale or foreign offer changes nothing; every
stage (offer, accept, connect, auth) has its own bounded timeout; a late
timer for an old session is ignored; a cancel from either side ends it and
tells the other.

## 6. Measure before believing

`BULK UP` runs a probe first: 1 MB each way, `FRAME_BULK_PROBE` payload and a
`FRAME_BULK_PROBE_DONE` verdict per direction. Only `BIDIRECTIONAL` starts the
contract. Any other verdict fails the purchase. The Internet stack that then
runs is the real one: proposal, contract, session, tunnel, gateway, VPN,
whole-phone IPv4, at 5 CFA/MB.

## 7. Files changed

New: `core/BulkPlan.kt`, `transport/StreamLink.kt`,
`transport/BluetoothBulkTransport.kt`, `ui/BulkLabActivity.kt`,
`res/layout/activity_bulk_lab.xml`, `test/.../BulkPlanTest.kt`.
Changed: `core/Wire.kt` (bulk controls + probe frames),
`core/Routing.kt` (three-way transport choice), `core/Market.kt`
(FLAG_BULK_BT), `core/P2pAdmission.kt` (BLUETOOTH_BULK path),
`core/ProductState.kt` (French endings), `transport/WifiTransport.kt`
(refactored onto StreamLink), `ble/ProkNetNode.kt` (the transport, the
negotiation, the routing, the buy decision), `ui/LabActivity.kt` +
`res/layout/activity_lab.xml` + `AndroidManifest.xml` (the BT Lab entry).

## 8. Permissions

`BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE` are already in
the manifest and already requested at runtime by MainActivity and LabActivity
for Android 12+. L2CAP connect and listen need only `BLUETOOTH_CONNECT`,
which the app already holds. No pairing, no PC, no adb, no manual step: the
customer connects to the provider's PSM and the ProkNet handshake does the
rest.

## 9. Tests (211, +8)

`BulkPlanTest`: the negotiation messages round-trip and a truncated one is
nothing; a stale or foreign offer changes nothing; a socket is never a link
before the signed handshake and never carries for the wrong peer; every stage
is bounded and a late timer cannot kill a newer session; the probe passes
only on real bytes both ways; the tunnel writes to the bulk link that holds
the peer and never to GATT; the purchase chooses Bluetooth for a provider on
home Wi-Fi and keeps the hotspot for mobile data; accounting and the provider
upstream do not know which link carried the bytes. The Wi-Fi path keeps all
its existing tests through the StreamLink refactor.

## 10. Build

Build 41, versionName 0.10.0,
SHA256 `bbd42acb1ccdcc77bcb719c6bb24f93a46c38936a561a8b23bf1bcd35577eb46`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.10.0
Commit `3b43592` on `main`; this report on top.

## 11. Exact phone test steps

`docs/TESTING.md` section 46. SELLER OUKITEL on the Freebox, mobile data off,
Bluetooth on, tap SELL in the BT Lab. BUYER OnePlus, mobile data off, not on
the Freebox, Bluetooth on, tap BUY OVER BLUETOOTH. Watch for `BLUETOOTH BULK
PROBE ... VERDICT: BIDIRECTIONAL` on both phones, then accept the VPN and load
a site in Chrome. Copy the `bluetooth bulk:` diagnostic block from both phones
whatever happens.

## 12. Known limitations

- L2CAP throughput is bounded by Bluetooth, not Wi-Fi. The probe measures it;
  the marketplace and checkpoints do not care about the rate.
- If a phone's Android build refuses insecure L2CAP, the listener or connect
  fails with a specific reason in the log; RFCOMM fallback is not built yet.
- Not proven on hardware. No claim until the probe is bidirectional and the
  VPN, DNS and HTTPS all work on the phones.

## 13. The claim rule

`docs/TESTING.md` section 46. Bluetooth Internet is solved only with the
seller on the Freebox, the buyer with no mobile data and no Freebox, a
bidirectional probe, the VPN up, DNS and HTTPS working, Chrome loading pages.
