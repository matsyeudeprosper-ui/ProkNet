# CLAUDE_REPORT - Milestone 2B: background operation (ProkNet Lab v0.3.0)

Date: 2026-09-13
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, released, NOT yet tested on real phones** (Mike has the APK)

History: Milestone 1 (v0.1.0) passed on two real phones 2026-09-12.
Milestone 2A (v0.2.0, queued delivery + receipts) passed on two real phones
2026-09-13: normal delivery, pending while peer unavailable, pending survives
restart, five queued messages delivered in order, Retry with no duplicates,
every message exactly once. Earlier reports are in git history
(`4e394d7`, `db703ca`).

## 1. What I built

| Requirement | Done | How |
|---|---|---|
| Foreground service with persistent notification | yes | `service/ProkNetService.kt`, channel "ProkNet node", notification shows the live status line and a "Stop ProkNet" action |
| Starting ProkNet starts the service | yes | Start button -> `startForegroundService`; the service calls `node.start()` |
| Service owns the node lifecycle, not the Activity | yes | node is created by `ProkNetApp` (Application), started/stopped only by the service; Activity only adds/removes itself as a listener |
| Activity can close and reopen without killing the node | yes | `onStop` detaches, `onStart` re-attaches and re-reads peers/messages/status; `stopWithTask=false` so swiping the app away does not stop the service |
| Screen off must not stop discovery or queued delivery | yes | foreground service + filtered BLE scan (Android only drops unfiltered screen-off scans) + partial wake lock during each delivery attempt |
| Queue, receipts, duplicate protection, SQLite unchanged | yes | no change to `MessageStore`, `GattServerNode`, `BleSender`, packet format; `DeliveryQueue` only gained the wake lock |
| Clear UI status for service running/stopped | yes | `Service: RUNNING (background OK) / STOPPED` line plus battery-exemption state |
| Diagnostic logs for service start, background state, screen-off continuity, service stop | yes | `SERVICE:` foreground started / SCREEN OFF - node keeps running: <status> / SCREEN ON / app swiped away / STOP requested / service destroyed; `UI:` activity visible / activity hidden - node continues |
| Kotlin only | yes | |

Also added, because background operation on real phones needs it: a
**Battery** button that opens Android's "ignore battery optimisations"
dialog, and a POST_NOTIFICATIONS request on Android 13+ (without it the
notification is invisible; the service still runs).

Not added: multi-hop, third-party carrying, encryption, Wi-Fi Direct,
Internet sharing, wallet, payments.

## 2. Architecture / technology choices and why

**Application-owned node, service-driven.** The node object must outlive
any screen, so it is created once per process in `ProkNetApp`. The service
is the only thing that calls `start()`/`stop()`. The Activity is a listener.
This keeps the BLE code untouched: nothing in `ble/` knows about services.

**Foreground service type `connectedDevice`.** Android 14 refuses a
foreground service without a declared type, and this is the type meant for
Bluetooth work. It requires a granted Bluetooth runtime permission at start
time, which the UI guarantees before it starts the service.

**Node keeps its own listeners list.** The service (for the notification)
and the Activity (when visible) both observe the node; the old single
`listener` field became `addListener`/`removeListener`.

**Wake lock only during a delivery attempt.** BLE callbacks wake the CPU by
themselves, but the connect -> MTU -> discover -> write -> read chain has
gaps where a dozing CPU could stretch the 20 s timeout. A partial wake lock
for one attempt (auto-released after ~45 s) costs nothing measurable and
removes that doubt. No wake lock is held while idle.

**No alarm, no job scheduler.** Discovery is event-driven (scan callbacks);
the queue's 10 s tick is a handler on the main looper, which keeps running
inside a foreground service. If a vendor kills the process anyway,
`START_STICKY` asks Android to restart the service; the queue then recovers
from SQLite (`recoverInterrupted`).

Full detail: `docs/ARCHITECTURE.md`, section "Background operation".

## 3. Files / components added or changed

```
app/src/main/java/net/prok/proknet/
  ProkNetApp.kt              NEW  Application: DiagLog init, one node per process
  service/ProkNetService.kt  NEW  foreground service, notification, screen receiver, Stop action
  ble/ProkNetNode.kt         listeners list; passes PowerManager to the queue
  ble/DeliveryQueue.kt       partial wake lock around each attempt
  ui/MainActivity.kt         observes only; Start/Stop go through the service;
                             Service line, Battery button, notification permission
app/src/main/AndroidManifest.xml   application class, service (connectedDevice,
                             stopWithTask=false), FOREGROUND_SERVICE(+_CONNECTED_DEVICE),
                             POST_NOTIFICATIONS, WAKE_LOCK, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS
app/src/main/res/drawable/ic_notify.xml   status-bar icon
app/src/main/res/layout/activity_main.xml Service line, Retry/Battery row
app/build.gradle.kts         versionCode 3, versionName 0.3.0
README.md, docs/ARCHITECTURE.md, docs/TESTING.md (section 8), CLAUDE_REPORT.md
```

## 4. Exact APK path

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.3.0
Build 3: 0.84 MB, SHA256 `c69fcb0f318aa9f69c7bb084fbf0068c8d337402ee5a77787f00fe674d5346d9`,
versionCode 3, versionName 0.3.0. Installs over v0.2, data kept. Manifest
verified with aapt2: service present, type connectedDevice, stopWithTask=false.

## 5. Exact build command

```
powershell -ExecutionPolicy Bypass -File C:\Projects\ProkNet\build.ps1
```
Unchanged. About 77 s.

## 6. What Mike needs to do on his phones

Install v0.3.0 on both phones. On first Start, allow the notification
permission (Android 13+). Press **Battery** once on each phone and accept.
Then `docs/TESTING.md` section 8. The one that matters most (8.4):

1. Stop B. Wait until A shows B as NOT IN RANGE.
2. On A send "asleep test" to B: `[pending]`.
3. On A press Home, turn the screen off, leave it off for a minute.
4. Start B. B must receive "asleep test" within about 30 s, with A untouched.
5. Then wake A and Copy log. It should show `SCREEN OFF - node keeps running`,
   B reappearing, `peer ... reappeared ... retry now`, `DELIVERED`, then `SCREEN ON`.

Also 8.5 (B sends to a sleeping A) and 8.6 (Stop from the notification).
Copy log from A after 8.4 is the one I most want to see.

## 7. Known limitations

- Vendor background killers (Xiaomi, Huawei, Oppo, some Samsung modes) can
  still kill a foreground service. The Battery exemption reduces this; some
  phones also need an "autostart" / "no restrictions" toggle in their own
  settings. The log will show it: a `service created` line with no preceding
  `STOP requested`.
- If Android kills the process, pending messages are safe (SQLite) but
  delivery pauses until the service is restarted by the system
  (`START_STICKY`) or by the user.
- Bluetooth turned off by the user while running is not handled specially:
  the node keeps its "running" state and logs errors. Stop/Start recovers.
- The notification shows the status line only; no message content.
- Still: own messages only, no encryption, names are `prok-<id>`.

## 8. What failed or is uncertain

- **Untested on hardware.** Compiles; manifest verified. The uncertain
  parts are all phone-specific: whether Doze on a given phone delays the
  first scan result after B reappears (expect up to ~1 min in deep Doze
  rather than 5-10 s), and whether the vendor kills the service.
- **Screen-off scan latency.** With the screen off Android may lengthen
  the scan interval even with a filter. Delivery should still happen, just
  later. TESTING 8.4 allows 30 s; if it takes longer but arrives, that is
  a note, not a failure.
- **Two compiler warnings** unchanged from 2A (deprecated read callback
  override, unused `address` parameter). Harmless.
- **VPS disk** 1.76 GB free; builds fine. Windows Update cache cleanup still
  pending on Mike's side.
- Repo still private.

## 9. Git commit hash

Code + docs: `5946b8040f974fcdd78bf0d7e7a04de27b173867` on `main`.
Release tag `v0.3.0` points at it. This report is committed on top.

## 10. Recommendation for the next step

Wait for the 2B test, especially 8.4. If it passes, the phone now
STORES and can CARRY its own messages while in a pocket. My recommendation
for the next milestone (2C) is the smallest step that makes FORWARD real:

1. Packet v2 with **destination ID, TTL, hop count**; the receiver stores
   any packet whose destination is not itself as "carried", and offers it to
   every peer it meets (a per-packet seen-set per peer, so it is offered once).
   Delivery receipt semantics stay the same per hop.
2. **Peer display names** via the IDENTITY characteristic (cheap, improves
   the UI for three-phone tests).
3. Three-phone test: A and C never meet; B walks between them.

That is the first true STORE-CARRY-FORWARD across phones, and the moment to
decide the Python question: the carry/forward policy (what to accept, how
long to keep, whom to offer) is the logic that would live in Python if we go
that way. Encryption should follow immediately after, before any real use,
because carried packets pass through strangers' phones.
