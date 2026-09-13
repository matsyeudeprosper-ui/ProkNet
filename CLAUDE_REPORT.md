# CLAUDE_REPORT - ProkNet v0.5.1 "Wi-Fi join fix"

Date: 2026-09-13
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 48/48 automated tests pass, released, NOT yet tested on phones**

v0.5.0 phone results (2026-09-13): identity, key learning, E2E text both
ways, signatures, background/screen-off, transfer engine (20 KB in, 98,889 B
out, 1 MB falling back to BLE) all passed. Only the Wi-Fi link failed: host
side complete (request received, hotspot up, `AndroidShare_xxxx` created,
offer sent), initiator side stuck in JOINING and ended with
`requestNetwork.onUnavailable`. A suspicious host IP (192.168.1.159, the
phone's normal Wi-Fi address) was also seen.

## 1. Investigation: why the join failed on Android 14 / OnePlus

`ConnectivityManager.requestNetwork` with a `WifiNetworkSpecifier` ends in
`onUnavailable` in exactly four situations, and v0.5.0 was exposed to three:

| Cause | v0.5.0 | v0.5.1 |
|---|---|---|
| **Security mismatch.** A specifier built with `setWpa2Passphrase` matches WPA2 and transition-mode APs, never a WPA3-SAE-only AP. Android 13/14 local-only hotspots are WPA2, WPA3 or transition depending on vendor; OnePlus/OxygenOS 14 is not documented. | always WPA2 | the host reads `SoftApConfiguration.securityType` and sends it; the client builds the matching specifier; for transition/unknown it tries WPA2, then WPA3 |
| **No visible Activity.** The system shows the "connect to device?" dialog only while the requesting app has an Activity in front; from the background the request just times out. | request fired from the service regardless | the transport waits for `appVisible()`; the UI shows a yellow banner; a high-priority notification opens the app if it is hidden |
| **Not found in scan within the request's timeout.** Hotspot on a band the client is not scanning, hidden SSID, or the request timed out before the user acted. | 45 s, no hidden flag | 60 s, hidden flag passed through, and a scan diagnostic logs whether the SSID is visible and on which frequency |
| **Dialog declined / wrong passphrase.** | | logged per attempt; Android 8-9 legacy path had a quoting bug (passphrase sent with quotes), fixed |

The wrong host IP was a separate bug in the host-side heuristic (it could
score the STA interface). It never mattered in the test because the join
failed first, but it would have failed the TCP step next.

## 2. What changed

1. **Security-aware join** (`Wire.joinAttempts`, `WifiTransport.joinWithSpecifier`): offer carries security type and hidden flag; attempts WPA2 -> WPA3 when needed; each attempt and its `onUnavailable` reason is logged.
2. **Host address from the network itself**: after `onAvailable` the client reads `LinkProperties` of the granted network: DHCP server address (Android 11+) and default-route gateway first (that IS the hotspot phone), then a `.1` guess on its own subnet, then the offered IPs. On the host, every address of the phone's own Wi-Fi network is excluded before choosing candidates, and all interfaces are logged.
3. **Approval made obvious**: banner `WI-FI: JOINING (tap CONNECT in the Android dialog)`; if the app is not visible the join waits and a high-importance notification "open the app to join Wi-Fi" is posted; Wi-Fi off opens the system Wi-Fi panel (no Settings navigation).
4. **Logs**: every `NetworkCallback` (`onAvailable`, `onCapabilitiesChanged` with SSID/RSSI/frequency, `onLinkPropertiesChanged` with addresses/routes/DHCP server, `onLosing`, `onLost`, `onUnavailable` with the attempt and reason), the scan diagnostic, host interface list, TCP candidate order and per-candidate result, handshake steps (`HELLO sent`, `HELLO from`, `signature VERIFIED`).
5. **Phase line** REQUESTING -> OFFERED -> JOINING -> TCP -> AUTH -> WIFI UP in the banner, diagnostics panel, notification and status line.
6. Timeouts: join 60 s, step 120 s. BLE fallback for transfers unchanged (45 s wait).

Crypto, routing, queue, transfer engine: untouched. Wire format: two optional
trailing bytes on the offer; v0.5.0 offers still parse (tested).

## 3. Automated tests

`total=48 failed=0 errors=0 skipped=0`. `WireTest` extended: security and
hidden fields round-trip, a v0.5.0 offer without them still parses, an offer
without IPs is valid (DHCP path), `joinAttempts` for every security type.
LinkState, Crypto, Transfer, Packet, Routing unchanged and green.

## 4. What still needs phones (the only goal)

`docs/TESTING.md` section 12: WIFI UP once, then 1 MB over Wi-Fi. Keep the
app screen open on both phones. The log table in section 12 maps each
failure line to its meaning; if it still fails, both logs will now say
exactly which of the four causes it is.

## 5. Exact APK / release

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.5.1
Build 7: 0.96 MB, SHA256 `04b89697f7fdad7a71b5cd579cab0a154f35f0ba2d36cd15dfcaf41cce35ffb1`,
versionCode 7, versionName 0.5.1. Commit `9349f06f7722d62876be89c980a3f3f911b7a12a`
on `main`; this report on top.

## 6. Remaining uncertainty

- If the OnePlus hotspot is on 5 GHz and the other phone is 2.4 GHz-only, no
  specifier can join; the scan diagnostic will show `NOT visible`. The
  fallback then is the reverse role (the other phone hosts): press Wi-Fi
  link from the other phone.
- `LinkProperties.dhcpServerAddress` needs Android 11; below it the gateway
  route is used.
- Per-chunk receipts over TCP make 1 MB ~2,500 round trips; expected a few
  seconds on Wi-Fi.

Ready for v0.6 Internet-through-phone as soon as WIFI UP is seen once.
