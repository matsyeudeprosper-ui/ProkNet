# CLAUDE_REPORT - ProkNet v0.10.1 "the runtime obeys the architecture"

Date: 2026-09-19
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 213/213 automated tests pass, released, not yet tested on phones.
This is a lifecycle correction, not a rewrite. Bluetooth Internet is still NOT claimed.**

## 1. The violation you found

v0.10.0 claimed the home-Wi-Fi provider stays on its Wi-Fi and serves over
Bluetooth. `setSelling(true)` still called `checkSharing`, which ran
`HotspotProbe` and, on a refusal, `startP2pFallback` created a Wi-Fi Direct
group. So a normal SELL on home Wi-Fi could still start the hotspot machinery
and the archived transport, contaminating the very test v0.10 exists for.

## 2. One pure rule (requirements 1, 3)

`BulkPlan.sellerAccessPath(upstreamIsWifi, bulkSupported, bluetoothOn)`:

| upstream | Bluetooth | path |
|---|---|---|
| mobile data | any | HOTSPOT (the proven path, probes as before) |
| home Wi-Fi | on | BLUETOOTH_BULK (no probe, Wi-Fi radio untouched) |
| home Wi-Fi | off / unsupported | NONE (reported honestly, no silent group) |

`onSharingReady` runs when SELL comes up and applies it. `needsHotspotProbe`
is true only for HOTSPOT, so a Bluetooth seller never calls `HotspotProbe`
and never touches the Wi-Fi local-link state; it advertises `FLAG_BULK_BT`
and waits for a `BULK_REQUEST`. Wi-Fi Direct is not one of the answers.

## 3. Automatic Wi-Fi Direct is gone (requirement 2)

Both automatic `startP2pFallback` calls in `checkSharing` are removed. The
buyer-asking rebuild only fires if a developer group was already up. And
`startP2pFallback` refuses structurally unless `p2pDeveloperEnabled` is set,
which only the P2P lab entry points (`p2pSell`, `p2pBuy`) set. No normal
SELL / BUY / share-check / network-change / provider-request path can create
a group. The rule is code, not a comment.

## 4. Mobile-data hotspot preserved (requirement 4)

A mobile-data upstream still returns HOTSPOT and runs exactly as before. The
change is specific to the home-Wi-Fi seller.

## 5. The BLE watchdog knows the bulk link (requirement 5)

`bleState().linkBusy` now includes `bulk.state.active || bulk.linkedPeer !=
null`, so the watchdog does not recover the scanner or advertiser during
BULK_REQUEST, LISTENING, CONNECTING, AUTH, PROBE or UP.

## 6. Reachability and the transports collection (requirements 6, 7)

`reachablePeers` adds the authenticated bulk peer next to the Wi-Fi one, so a
live L2CAP peer is not treated as gone during a BLE scan gap. The stale
`transports` collection (`listOf(wifi, ble)`, previously unused) now lists
`wifi, bulk, ble`.

## 7. Tests (213, +2)

`sellerAccessPath` on every combination: home Wi-Fi + Bluetooth -> Bluetooth
bulk with no probe; mobile data -> hotspot with a probe; home Wi-Fi without
Bluetooth -> NONE, never Wi-Fi Direct. A remembered hotspot refusal does not
matter to a Bluetooth seller, and a Wi-Fi network change under it keeps the
Bluetooth answer with no probe. No SellerAccessPath value names Wi-Fi Direct.
Everything from v0.10.0 still passes.

## 8. Build

Build 42, versionName 0.10.1,
SHA256 `2593115155e42b1458c34174a92c58dcfdb61179522c555d64261d5dc23f5b67`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.10.1
Commit `d4cd31e` on `main`; this report on top.

## 9. The clean hardware test (requirement 9)

`docs/TESTING.md` sections 47 then 46. The SELLER OUKITEL, on the Freebox
with mobile data off and Bluetooth on, must show `SELLER ACCESS PATH =
BLUETOOTH_BULK`, `PROVIDER READY, upstream Wi-Fi, validated`, and NO hotspot
probe, NO Wi-Fi Direct line, NO `p2p` interface. Then the BUYER over
Bluetooth: BULK UP, `VERDICT: BIDIRECTIONAL`, contract, VPN, DNS, HTTPS,
Chrome.

## 10. Not changed

The L2CAP design, the StreamLink refactor, the signed handshake, the 1 MB
probe, the tunnel, the gateway, the VPN, the contracts, the accounting, the
Wire bulk negotiation, the session tokens, the mobile-data hotspot path and
the developer P2P lab are all untouched.

## 11. The claim rule

Sections 47 and 46. No claim until the seller is provably clean and the buyer
reaches bidirectional bytes, VPN, DNS and HTTPS.
