# CLAUDE_REPORT - ProkNet v0.13.3 "the control plane has to be trustworthy"

Date: 2026-09-20
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 273/273 Android tests and 11/11 server tests pass,
released. The proven transport stack, the v0.12.5 design, the gossip
protocol and the brain are untouched. Acceptance needs 3 consecutive
cycles + the Bluetooth recovery test (TESTING 55, 56, 57).**

## 1. Root causes

1. **A different buyer was suppressed by a clock.** A global two-minute
   cooldown refused request B 53 s after request A ("activation …
   rate-limited"). A rate limit should stop one unresolved opportunity from
   nagging, never hide new demand.
2. **Push was the only place demand existed.** A dismissed, missed or
   forbidden notification lost the request completely; nothing in the app
   could show it.
3. **`isReady` was a Boolean that outlived reality.** After a Bluetooth
   toggle Android had destroyed the ProkNet GATT service, but
   `GattServerNode.isReady` stayed true, so `recoverRadio`'s
   `if (server?.isReady != true)` skipped the server rebuild and ProkNet
   kept advertising a control plane it no longer had: `peer has no ProkNet
   service (services=2)` on every customer.
4. **Retry storms.** `onPeers()` fires constantly and each fire re-queued
   the same request to the same peer, giving the broken phone no quiet
   moment to repair itself.

## 2. New ProviderOpportunity architecture

`core/ProviderInbox.kt` (pure, persisted to
`filesDir/opportunities.v1.txt`): one `Opportunity` per request id with
origin, source (LOCAL over BLE / BRAIN from the zone), zone, receivedAt,
expiresAt, generation, notifiedAt and accepted. `offer` adds or updates by
generation and never duplicates; `remove` is called by the tombstone, the
expiry, the sweep and by a request this phone can no longer serve;
`accept` marks PARTAGER. Notification and Gagner card both render from it.
On startup `rebuildInbox` restores active opportunities from the carried
requests, marking already-known ones as alerted so a restart does not
re-notify.

## 3. Notification aggregation and rate-limit rules

The wall-clock limiter is **deleted** (`ProviderActivation.Limiter`,
`GLOBAL_MS`, `PER_REQUEST_MS` are gone from the codebase, not just
unused). The rule is now: **one alert per opportunity, by id.**

- same request id, duplicate or gossip copy → never alerts again;
- request A ends, B arrives a second later → B alerts immediately;
- A still open, B arrives → one alert covering both, and only B is newly
  marked;
- 3 requests at once → "3 personnes cherchent Internet à proximité.", one
  notification;
- brain-only requests say "Une demande Internet existe dans votre zone" —
  never "à proximité" without local BLE evidence.

## 4. Gagner inbox behaviour

A card above the sharing setup while sharing is off: the aggregated title,
"À proximité · maintenant" (or "Dans votre zone · il y a 3 min"), and
PARTAGER. It survives a dismissed notification, denied permission, an
accidental tap, reopening the app, screen off/on, activity recreation and a
process restart. While sharing is already active it becomes a quiet line —
"2 autres personnes cherchent Internet". Home shows a small "1 demande"
badge on the Partager card. No ids, TTLs, generations or protocol words
anywhere in the consumer UI.

## 5. One PARTAGER path

`NetworkNode.acceptOpportunity(requestId?)`, called identically by the
notification action and the Gagner button. It re-checks: the request is
still active, the phone's **current** Internet (v0.13.2 capability, not the
gateway), the local path and Bluetooth, the price ceiling, and that it is
not already busy — then calls the normal `setSelling(true)`. Otherwise it
returns one sentence for the user and, for a dead request, removes the
card.

## 6. Bluetooth generation / recovery design

`core/BleLifecycle.kt` (pure): `generation`, `phase` (DOWN →
SERVICE_PENDING → READY / FAILED), and `serviceGeneration`,
`advertisingGeneration`, `scanGeneration`. `serviceReady` requires the
confirmed generation to equal the current one; `mayAdvertise` requires
`serviceReady`; `controlPlaneHealthy` requires both; `isStale(gen)` guards
every callback. `bluetoothOff` invalidates everything; `bluetoothOn` bumps
the generation.

`BleTransport` has ONE rebuild path (`rebuild(why)`, which `recoverRadio`
now is): close scanner, advertiser and GATT server; open a new
`GattServerNode` stamped with the new generation; add the service; start
the scanner (it needs nothing from us); **and start advertising only from
`onServiceAdded`**, after Android confirms. A late `onServiceAdded` from an
older generation is logged and ignored. `setCapabilities` refuses to
advertise an offer when the service is not ready.

The invariant is enforced every watchdog tick by `checkInvariant()`: if
advertising without a ready service → immediate rebuild; if the service is
missing and the bounded backoff allows → rebuild. Never while `linkBusy`.

## 7. Retry / backoff design

`core/ControlRetry.kt` (pure): one attempt per
`(requestId, generation, peerShort)`, phases IDLE → SENDING → BACKOFF /
PARKED / DELIVERED, delays 1 / 3 / 10 / 30 s, max 6 attempts. Keyed by the
signed ProkNet short id, so BLE address rotation is irrelevant. A
"no ProkNet service" failure parks the **peer** for 45 s (doubled after 3),
so no request hammers it; a delivery clears the peer's record. Our own BLE
generation changing, or a parked peer reappearing, resets the backoff
immediately. `keepOnly` prunes finished requests.

## 8. Diagnostics (COPY NETWORK)

Adds, on top of the v0.13.2 lines: `Provider opportunities: N (local L,
brain B), oldest Ns` in the summary; a `provider opportunities:` block with
one line per opportunity (id, origin, source, age, alerted / NOT alerted,
ACCEPTED) and `notification: last shown …, total …, suppressed reason: …`;
a `control plane:` block printing generation, GATT server, ProkNet service,
advertising generation, scan generation and rebuild count; and
`forwarding: sends … | in flight … | parked: prok-…` with the per-peer
"advertises ProkNet but its GATT service is missing" note.

## 9. Files changed

New: `core/BleLifecycle.kt`, `core/ProviderInbox.kt`,
`core/ControlRetry.kt`, `test/.../ActivationReliabilityTest.kt` (18 tests).
Patched: `ble/GattServerNode.kt` (generation + service-added callback),
`transport/BleTransport.kt` (one ordered rebuild, generation-stamped
state, invariant check, advertise-after-service), `ble/ProkNetNode.kt`
(adapter transitions to the transport, invariant in the watchdog, control
error reporting, `onSharingStopped`, `bleGeneration`),
`node/NetworkNode.kt` (inbox, retry, acceptOpportunity, rebuildInbox,
persistence, diagnostics), `service/ProkNetService.kt` (aggregated alert,
permission check, ACTION_SHARE_NOW → acceptOpportunity),
`ui/MainActivity.kt` (Gagner card, home badge, inbox repaint),
`core/ProviderActivation.kt` (limiter deleted), layouts, strings,
`test/.../NetworkBrainTest.kt`, `build.gradle.kts`.

## 10. Tests

**273 Android** (+18) and **11 server**. The new ones cover: one request →
one opportunity, one alert, one card; duplicates never re-alert; a new
buyer alerts immediately after the previous request ended; 3 requests
aggregate into one alert; brain requests never claim "à proximité";
cancelled / fulfilled / expired / accepted each remove the card and the
alert; the inbox survives a process restart without re-alerting; a denied
notification permission leaves the demand visible; a stale notification
after the request ended cannot start sharing (and every refusal has a
sentence); **three full cycles in a row with nothing left behind**; the
generation machine (healthy only when service+advert+scan share the current
generation; OFF→ON invalidates everything; stale callbacks ignored;
advertising never before service-added; bounded service retry; advertising
without a service reported as UNHEALTHY); and the retry rules (no resend on
every onPeers tick, growing backoff that gives up, a service-missing peer
parked not hammered, identity-keyed state, delivery clears the record).

## 11. Version / build / commit / hash

Build 54, versionName 0.13.3, SHA256 `ad6dddcd7ab6a4857a0e744e7b3e76e7e7988a1e2652d0dd368df845445f12ec`.
Commit `cd15fd4` on `main`; this report on top.
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.13.3

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```

## 12. Known limitations

- Not yet run on the phones: 3 cycles, the Bluetooth recovery and the
  background cases are TESTING 55 / 56 / 57.
- The OUKITEL's own `p2p` interface and Wi-Fi Direct remain archived and
  untouched.
- A rebuild of the BLE stack drops any in-flight GATT client operation; the
  retry layer re-sends, but a control message can be delayed by one
  backoff step after a Bluetooth toggle.
- Provider activation still serves one customer at a time: the inbox tracks
  several requests and the matching layer will choose later; PARTAGER makes
  the phone available rather than binding it to one buyer.
- The brain is still BUILT + TESTED LOCALLY (no public HTTPS host).
- 3-phone carry and relay remain modelled, not executed, not claimed.

## 13. The exact hardware test

TESTING 55: three consecutive cycles, the third with the notification
**swiped away** and PARTAGER pressed from Gagner instead.
TESTING 56: Bluetooth OFF 10 s then ON on the OUKITEL, **without
restarting the app**, then a new request — no "peer has no ProkNet
service", and COPY NETWORK shows the generation moved with
`ProkNet service: ADDED`.
TESTING 57: screen off / app closed, then notifications denied — the
Gagner card must appear in both cases.
