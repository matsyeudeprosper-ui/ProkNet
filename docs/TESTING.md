# ProkNet Lab - Testing on phones

No PC, no ADB, no Android Studio. Everything needed is on screen.

## 1. Install

1. On each phone open the GitHub release page of this repo and download
   `ProkNetLab-debug.apk` (or use the APK file sent to you directly).
2. Tap the downloaded file. Allow "Install unknown apps" for the browser /
   file manager if Android asks. Play Protect may warn because the app is
   unsigned by a store; choose "Install anyway".
3. Both phones must run the **same APK build**.

Minimum Android 8.0. Both phones need BLE (any phone from the last 8 years).

## 2. Prepare the phones

On **both** phones:

- Turn **mobile data OFF** and **Wi-Fi OFF** (airplane mode, then Bluetooth back on, is the easiest way).
- Turn **Bluetooth ON**.
- On Android 8 to 11 also turn **Location ON** (Android requires it for BLE scanning; the app does not use your position).
- Keep the phones within a few metres of each other for the first test.

## 3. Run

1. Open **ProkNet Lab** on both phones.
2. Press **Start**. Grant the Bluetooth permissions when asked ("Nearby devices" on Android 12+, "Location" on older phones). Press Start again if the prompt interrupted it.
3. The status line should read something like
   `BT on | server ready | adv on | scan on | peers 0`.
4. Within about 5 seconds each phone should list the other under
   *Nearby ProkNet devices* as `prok-xxxxxxxx  rssi -60  ...`.
   The `xxxxxxxx` matches the `id` shown at the top of the other phone.
5. On phone A tap the entry for phone B. The *Selected:* line updates.
6. Type a message and press **Send**.
7. Phone A shows the message in *Messages* as `-> prok-... [pending]`, `[sending]`, then `[delivered]` (v0.1 said `[sent]`).
8. Phone B shows `<- prok-... [received]: your text` and the log line
   `GATT-S: PACKET from ...`.
9. Repeat from B to A.

## 4. What the log should show for one successful send

Sender:
```
UI: send "hello" -> prok-1a2b3c4d
GATT-C: queued send to prok-1a2b3c4d (43 bytes), queue=1
GATT-C: attempt 1: connecting to prok-1a2b3c4d @ 5C:...
GATT-C: connected to prok-1a2b3c4d, requesting MTU 517
GATT-C: mtu=517 status=0
GATT-C: ProkNet service found, writing 43 bytes
GATT-C: write ACKED by prok-1a2b3c4d
GATT-C: SEND OK to prok-1a2b3c4d: delivered (mtu 517, attempt 1)
```
Receiver:
```
GATT-S: central 6A:... CONNECTED (status 0)
GATT-S: mtu with 6A:... = 517
GATT-S: write from 6A:... len=43
GATT-S: PACKET from 9f8e7d6c msg=... text="hello"
```

## 5. If something fails

Press **Copy log** (clipboard) or **Share** (send to WhatsApp / email /
Telegram) on the phone that misbehaved and paste the text into the chat with
Claude. The diagnostic includes the phone model, Android version, identity,
status line, peer list and the full log.

Common cases:

| Symptom | Meaning | What to do |
|---|---|---|
| `advertising FAILED: FEATURE_UNSUPPORTED` or `does not support BLE advertising` | this phone cannot be discovered, but can still find and send | use it only as the sender, or test with another phone |
| `scan FAILED: APP_REGISTRATION_FAILED` | Bluetooth stack in a bad state | toggle Bluetooth off/on, press Stop then Start |
| peers stay 0 on Android 8-11 | Location is off or location permission denied | turn Location on, re-grant permission |
| `disconnected in phase 'connecting' (status 133)` | generic Android BLE error | the app retries once automatically; if it keeps happening, re-discover (Stop, Start) and try again |
| `timeout in phase 'mtu'` | peer stack did not answer the MTU request | app falls back after 3 s automatically; report if it happens every time |
| `peer has no ProkNet inbox characteristic` | the other phone's GATT server is not running | press Stop then Start on the other phone |
| messages arrive twice | duplicate delivery | the store ignores duplicates by message ID; you should not see it in the list, only in the log |

## 6. Test checklist for milestone 1 (v0.1, passed 2026-09-12)

- [ ] A sees B, B sees A (both lists non-empty)
- [ ] A -> B text arrives and shows `[received]` on B, `[sent]` on A
- [ ] B -> A works too
- [ ] a 300-character message arrives intact (tests the long-write path)
- [ ] Stop then Start on both phones, discovery recovers
- [ ] close and reopen the app: identity unchanged, old messages still listed
- [ ] walk 10-20 m apart: peer expires from the list after ~25 s, comes back when close

## 7. Milestone 2A (v0.2): queued delayed delivery - passed 2026-09-13

Both phones on v0.2.0. Names below: A = sender, B = receiver.

**7.1 Receipt on a normal send (B in range).**
Send A -> B. A's message goes `[pending]` -> `[sending]` -> `[delivered]`.
A's log must contain `write ACKED ... reading RECEIPT` then
`SEND DELIVERED ... RECEIPT accepted`. B's log: `PACKET from ... -> receipt ACCEPTED`.

**7.2 Queue while B is off.**
1. On B: press **Stop** (or turn Bluetooth off). Wait until A's list shows
   B as `NOT IN RANGE` (about 25 s).
2. On A: tap B, send "queued 1". It shows `[pending]`. Status line shows
   `queue 1`. Log: `QUEUE: ENQUEUED msg=...`.
3. Send "queued 2" the same way. `queue 2`.
4. On B: press **Start** (or Bluetooth on, then Start).
5. Within ~10 s A's log shows `peer prok-... reappeared, 2 pending message(s)`
   then `ATTEMPT 1 msg=...`, and both messages become `[delivered]` without
   touching Send. B shows both `[received]`, in order.

**7.3 Queue survives restart.**
1. B off. On A send "after restart" -> `[pending]`.
2. Close A completely (swipe away), reopen, press Start.
   Log: `QUEUE: queue started, pending=1`. The message is still `[pending]`.
3. Turn B on, Start. The message is delivered automatically.

**7.4 No duplicates.**
Send five messages quickly A -> B while B is in range, then walk B out of
range mid-way and back. Every message must appear exactly once on B.
A's log may show `RETRY LATER` and later `RECEIPT duplicate` lines: that is
the receipt catching a retry of something B already had. B's list must not
show any text twice.

**7.5 Retry button.**
With B off, send one message, then press **Retry** on A: log says
`manual retry: backoff cleared`; nothing is sent (B not in range), status
stays `[pending]`. Turn B on: delivered.

**7.6 Failed / expired (optional, long).**
`expired` needs 48 h of waiting. `failed` needs 50 transport failures against
a visible peer, which is hard to provoke by hand. Skip unless ChatGPT asks;
the code paths are logged as `FAILED msg=...` and `EXPIRED msg=...`.

### Checklist for the 2A report

- [ ] 7.1 normal send shows `RECEIPT accepted` on A and `receipt ACCEPTED` on B
- [ ] 7.2 two queued messages delivered automatically when B returns, in order
- [ ] 7.3 pending survives closing and reopening A
- [ ] 7.4 zero duplicates on B after a flaky-range burst
- [ ] 7.5 Retry with B off does nothing harmful
- [ ] old v0.1 messages still listed after upgrading (schema migration)

## 8. Milestone 2B (v0.3): background operation - passed 2026-09-13

Both phones on v0.3.0. On first Start, Android 13+ asks for notification
permission: allow it, otherwise the persistent notification stays hidden
(the service still runs). Press **Battery** once on each phone and accept
the dialog; it matters on Xiaomi / Huawei / Oppo / some Samsung phones.

**8.1 Service starts and shows.**
Press Start. The line under the identity reads `Service: RUNNING (background OK)`.
A notification "ProkNet running (prok-...)" appears with the status text and
a "Stop ProkNet" action. Log: `SERVICE: foreground started`, `NODE: node started`.

**8.2 Activity closes, node lives.**
Press the phone's Back or Home. The notification stays. Reopen the app from
the notification: peers list and status are still live, no Start needed.
Log shows `UI: activity hidden (background) - node continues in the service`
then `UI: activity visible (foreground); service RUNNING, node running`.

**8.3 Swipe away from recents.**
Swipe the app out of Recents. Notification stays. Log (visible after
reopening): `SERVICE: app swiped away from recents - service keeps running`.

**8.4 THE test: pending delivery with A asleep.**
1. Stop B (or Bluetooth off on B). Wait for `NOT IN RANGE` on A.
2. On A, send "asleep test" to B: `[pending]`, `queue 1`.
3. On A, press Home, then turn the screen OFF. Leave it off.
4. Wait 1 minute. Then Start B (Bluetooth on, open app, Start).
5. Within ~10-30 s B shows `<- prok-... [received]: asleep test`.
   Do NOT touch A until B has it.
6. Now wake A and open the app. Log on A must show, in order:
   `SERVICE: SCREEN OFF - node keeps running: ... scan on ...`,
   `SCAN: NEW peer prok-...` (B reappearing),
   `QUEUE: peer prok-... reappeared, 1 pending message(s) -> retry now`,
   `QUEUE: DELIVERED msg=...`, then `SERVICE: SCREEN ON`.

**8.5 Receive while asleep.**
A running, screen off, app not open. From B send "to sleeping A". Wake A,
open the app: the message is in the list with `[received]`, and the log
shows the `GATT-S: PACKET from ...` line timestamped while the screen was off.

**8.6 Stop from the notification.**
Tap "Stop ProkNet" on the notification. Notification disappears; the app shows
`Service: STOPPED`; log `SERVICE: STOP requested`, `NODE: node stopped`.

**8.7 Long background (optional).**
Leave both phones running, screens off, for 30 minutes, then send both ways.
If a phone's vendor killed ProkNet, its log will show a gap and a
`service created` line without a preceding `STOP`; press Battery and retest.

### Checklist for the 2B report

- [ ] 8.1 service RUNNING line + notification
- [ ] 8.2 close/reopen the screen without losing the node
- [ ] 8.3 swipe away, service survives
- [ ] 8.4 pending message delivered while A's screen is off and app not open
- [ ] 8.5 message received while asleep
- [ ] 8.6 Stop from the notification works
- [ ] all 2A behaviour unchanged (queue, receipts, no duplicates, old messages still listed)

## 9. Milestone 2C1 (v0.4): one relay, STORE -> CARRY -> FORWARD

Three phones on v0.4.0: A (origin), B (relay), C (destination). Before the
test, let all three see each other once with the app running, so every phone
has learned the others' full IDs (they appear in the peers list). Then:

**9.1 THE test: A -> B carries -> C.**
1. Turn C off (Stop, or Bluetooth off). Wait until A and B show C as
   `NOT IN RANGE`.
2. On A, tap **C** in the list (the NOT IN RANGE entry) and send "relay test".
   Within ~10 s A shows `-> prok-C [handed_off via prok-B, not final]`.
   A's log: `HANDING OFF msg=... to relay prok-B`, then
   `HANDED OFF ... custody accepted, NOT final delivery`.
   B's log: `ACCEPTED FOR RELAY msg=... from prok-A for prok-C ... CARRYING`.
   B's list: `~ carrying prok-A -> prok-C [carrying]`. B's status: `carry 1`.
3. Turn A off completely (Stop, or Bluetooth off). A must play no further part.
4. Turn C on (Bluetooth on, app, Start).
5. Within ~30 s: B's log `DESTINATION SEEN: prok-C ... forward now`,
   `FORWARDING msg=... to its destination prok-C (hop 1/3)`, `FORWARDED ... custody complete`.
   B's list: `[forwarded to prok-C]`.
   C's list: `<- prok-A [received via prok-B, 1 hop]: relay test`.
   C's log: `FINAL RECEIVED msg=... from prok-A via relay prok-B after 1 hop(s)`.
6. C must show the message **once**, and **from prok-A**, not from prok-B.

**9.2 Direct still wins.**
All three on and in range. A sends to C. A's log shows `ATTEMPT 1 ... direct`,
`DELIVERED ... final`. Nothing on B.

**9.3 No duplicate through two routes.**
1. C off. A sends "dup test" to C -> handed off to B.
2. Bring C on **while A is still on** and near both. A is done after the
   handoff (state `handed_off`), so only B forwards. C gets it once via B.
3. Now press **Retry pending** on A: nothing happens for that message
   (it is `handed_off`, not pending). C still has exactly one copy.

**9.4 Relay survives restart.**
With B carrying a packet for C (C off), close B completely and reopen it,
Start. Log: `CARRYING msg=... for prok-C`. Turn C on: forwarded.

**9.5 No flooding.**
With B carrying for C, bring a fourth phone D (or A again) near B, C still
off. B must NOT hand the packet to anyone: no `FORWARDING` line until C
itself appears.

**9.6 Old data.**
Messages from v0.1-v0.3 still listed after upgrading; any still-pending
v0.3 message is delivered normally (it is addressed by short ID now).

### Checklist for the 2C1 report

- [ ] 9.1 A -> B -> C with A off during the forward; C shows sender A, once
- [ ] 9.1 A shows `handed_off ... not final`, never `delivered`
- [ ] 9.1 B shows `carrying` then `forwarded`
- [ ] 9.2 direct delivery unchanged when C is in range
- [ ] 9.3 exactly one copy on C
- [ ] 9.4 carried packet survives B restart
- [ ] 9.5 B forwards only to C
- [ ] background operation (v0.3) still works: do 9.1 with B's screen off
