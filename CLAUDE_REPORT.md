# CLAUDE_REPORT - ProkNet v0.9.5 "the provider's own error, on the buyer's screen"

Date: 2026-09-14
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 108/108 automated tests pass, released, not yet retested on phones**

## 1. v0.9.4 verified on the phones

Mike's diagnostic (build 17) shows the v0.9.4 mechanism working exactly as
designed, five attempts in a row:

```
17:35:01.748 GATT-C: frame 1/1 -> DELIVERED (RECEIPT accepted)
17:35:03.298 WIFI: prok-24e480e6 cancelled the link: cannot create the hotspot
17:35:03.298 WIFI: PHASE DOWN - the provider could not start its Wi-Fi hotspot
```

Two seconds instead of sixty, with a real sentence on screen. The silent
refusal is gone. What remains is the provider's actual problem: it cannot
create a local-only hotspot. Its advert also changed from "mobile data" to
**Wi-Fi**, so that phone is now connected to a Wi-Fi router and selling
that, which is the classic case where Android answers ERROR_NO_CHANNEL
(the AP cannot use the channel the station is on).

## 2. What v0.9.5 adds

- **The cancel carries the host's error.** WIFI_CANCEL is now
  `[reason][detail utf8 <=120]`. The host puts its own text in it
  ("reason 1 (no channel: ...)", "Location services are off on the
  provider", "missing the Nearby devices / Location permission"). Older
  builds send no detail, which still parses.
- **The buyer names one action.** `ProductState.lostHint` maps that detail
  to a single French instruction: turn Location on / turn the Android
  hotspot off / leave Wi-Fi and use mobile data / grant Nearby devices.
- **The buyer's diagnostic shows it**: "provider refused, ITS error: ..."
  in COPY DIAG and in the Relay Lab, so the next report needs one phone,
  not two.
- **Host-side prevention**: Location services are checked before calling
  Android; the attempt logs which Wi-Fi network the phone is on; a failure
  is retried once after closing any reservation this app still holds.
- **No backoff after an explicit refusal** (`LinkState.forgetFailures`):
  the exponential wait is for silent failures, not for a peer that said no
  in one second. Mike hit "wait 40s" twice in the log; that is fixed.

## 3. Most likely resolution for the phones

If the detail comes back as ERROR_NO_CHANNEL, the provider must leave its
Wi-Fi network and share mobile data instead. That is exactly the
combination that worked in the 3-phone relay test, where the same phone
hosted `AndroidShare_5999` while its mobile data was the upstream.

## 4. Tests (108)

`WireTest`: the detail round-trips, is capped, and a reason-less cancel
from an older build still parses. `ProductStateTest`: the four host errors
each produce their own French instruction and none of them says "move
closer". `LinkStateTest`: `forgetFailures` clears the backoff.

## 5. Build

Build 18, versionName 0.9.5, 1.18 MB,
SHA256 `46b8097f39cdc248523200a414f891f0b8e9a6891c734bac949b0b8cedf6b32c`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.9.5
Commit `CODE_COMMIT` on `main`; this report on top.

## 6. Preserved

Only the cancel payload changed, and it is backward compatible in both
directions. Relay handshake, coverage planner, marketplace, database and
the consumer screens are untouched.
