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

## 9. Milestone 2C1 (v0.4.1): one relay, STORE -> CARRY -> FORWARD

Three phones on v0.4.1 (v0.4.0 was never tested; v0.4.1 changes the wire
format, so all three must be on the same build): A (origin), B (relay), C (destination). Before the
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

## 10. Automated tests (v0.4.1) - run on the VPS, no phone needed

```
powershell -ExecutionPolicy Bypass -File C:\Projects\ProkNet\build.ps1
```

runs 23 JUnit tests in `app/src/test` before building. They cover: packet
encode/decode round trip, size limits, origin/destination/message ID
unchanged through a relay, last hop A -> B -> C, hop count, TTL rejection,
one-relay limit, malformed and truncated packets (every truncation length
plus 500 random blobs), v1 and v2 decoding, legacy destination padding,
direct-wins planning, handoff target choice, forward-only-to-destination,
carried-before-own ordering, backoff, handed_off-never-delivered,
result-to-state mapping, give-up after 50 attempts, exactly-once delivery
through two routes, and the whole A -> B -> C story with three simulated
phones. Results: `dist\test-results.txt`. A failing test produces no APK.

What they do NOT cover (needs phones): BLE timing, MTU and long writes,
Android address rotation, the SQLite migration itself, vendor background
killers.

## 11. v0.5 Secure Fast Link (two phones)

Install v0.5.0 on both. On first Start each phone generates its key: its ID
CHANGES (new `prok-xxxxxxxx`). Old peer entries can be ignored. Press Start
on both; grant the new "Nearby devices" (Android 13+) or Location permission.

**11.1 Keys are learned automatically.** Within ~10 s of seeing each other,
each phone lists the other as `[key] prok-... "name"`, and the log shows
`KEY FETCH` then `KEY LEARNED for prok-... via ble: fingerprint ...`.
Compare the fingerprint shown on A for B with B's own `fp` line: they must match.

**11.2 Encrypted direct message.** Send a text A -> B. A: `[e2e delivered]`.
B: `<- prok-A [e2e received, signed]`. B's log: `FINAL RECEIVED ... E2E decrypted, signature VERIFIED`.
Both directions.

**11.3 Queue and relay behaviour unchanged.** Repeat 7.2 (queue while B
off) and, with three phones, 9.1. Relay B's list shows `~ carrying prok-A -> prok-C [e2e carrying]: (opaque)`.

**11.4 Long text over BLE.** Big test -> "2 KB text". A: `=> prok-B [xfer e2e sending 33%..]`
then `delivered`. B: `<= prok-A [xfer e2e received] text 2016 B: ProkNet big test...`.
Log on B: `RECEIVED transfer ... sha256 OK, signature VERIFIED`.

**11.5 Wi-Fi link by hand.** Both phones: Wi-Fi ON, Location ON. Select B on
A, press **Wi-Fi link**. Expected within ~30 s: A log `NEGOTIATE: sending WIFI_REQUEST`,
B log `WIFI_REQUEST from prok-A`, `hotspot UP ssid=...`, A log `WIFI_OFFER from prok-B`,
then on A a system dialog "ProkNet Lab wants to connect to <ssid>": tap Connect.
Then both: `LINK UP with prok-... (authenticated by signature)`; the peers list
shows `[WIFI UP]`; diagnostics show `wifi: UP (...)`.

**11.6 Fast transfer.** With the link up, Big test -> "200 KB binary" or
"1 MB binary". Should complete in seconds; the row shows `via wifi`. Then
"Send file" with a photo (< 2 MB). B's log shows the SHA-256 and `sha256 OK`.
Compare A's `sha256` in the diagnostic text with B's.

**11.7 Automatic Wi-Fi.** Stop the link (Stop/Start on one phone). Send a
200 KB test WITHOUT pressing Wi-Fi link: A must request the link by itself
(`asking prok-B for a Wi-Fi link first`), the dialog appears on A, and the
transfer goes `via wifi`. If the dialog is declined, after 45 s it falls back
to BLE (slow but must complete).

**11.8 Loss and reconnect.** With a transfer running over Wi-Fi, turn B's
Wi-Fi off. A: `link failed`, transfer `RETRY LATER ... at chunk N`. Turn Wi-Fi
back on: the next attempt resumes from chunk N (log `chunks N..`).

**11.9 Background.** Repeat 8.4 with an encrypted message. Unchanged.

What to send back: Copy log from both phones after 11.5 and 11.7.

### Checklist for the v0.5 report

- [ ] 11.1 keys learned, fingerprints match
- [ ] 11.2 encrypted message both ways, `signature VERIFIED`
- [ ] 11.3 queue (and relay if three phones) still work
- [ ] 11.4 2 KB text over BLE arrives intact
- [ ] 11.5 Wi-Fi link comes up, `[WIFI UP]`
- [ ] 11.6 200 KB / 1 MB / photo over Wi-Fi, SHA-256 matches
- [ ] 11.7 Wi-Fi negotiated automatically for a big payload
- [ ] 11.8 resume after link loss
- [ ] 11.9 background operation unchanged

## 12. v0.5.1 Wi-Fi link (two phones) - the only goal: WIFI UP once, then 1 MB over Wi-Fi

Both phones on v0.5.1. Wi-Fi ON, Location ON, Bluetooth ON on both. Keep the
ProkNet screen OPEN on both phones during the whole test.

1. Start both. Wait for `[key]` on both peer lists.
2. On A select B and press **Wi-Fi link**. Watch the banner on A:
   `REQUESTING` -> `OFFERED` -> `JOINING (tap CONNECT in the Android dialog)`.
3. A system dialog appears on A (it may take up to 30 s: Android scans for the
   hotspot). Tap **CONNECT** (French: "Se connecter"). If the dialog shows a
   list, choose the AndroidShare_xxxx entry.
4. Banner: `TCP` -> `AUTH` -> green `WI-FI UP with prok-B`.
5. On A press **Big test** -> "1 MB binary". It should finish in seconds with
   `via wifi`. B shows `<= prok-A [xfer e2e received] file test-1m.bin`.
6. Copy log from BOTH phones, whatever happened.

If it fails, the log now says why. Look for:

| Log line on A (client) | Meaning |
|---|---|
| `scan: SSID ... NOT visible` | the hotspot is on a band A cannot see, or not up; retry with B's Wi-Fi on but not connected to any network |
| `scan: SSID ... visible, 5xxx MHz, caps [...]` then `onUnavailable` | security mismatch or dialog not confirmed; note the caps text |
| `requestNetwork.onUnavailable ... attempt 1, WPA2` then `retrying ... WPA3` | expected on WPA3 hotspots; the second attempt should succeed |
| `PHASE JOINING (open the app to approve)` | the app was not in front; open it |
| `TCP: host candidates in order [...]` then `TCP connect x failed` | joined but the host address is wrong; send both logs |
| `AUTH: ...` lines | handshake progress; `INVALID` means key mismatch |

Log line on B (host): `hotspot started: ssid=... security=...` and
`hotspot addresses (own Wi-Fi network excluded): [...]` tell what B offered.

## 13. v0.6 Internet through another phone (two phones)

Roles: **B = provider** (mobile data ON, Internet works), **A = buyer**
(mobile data OFF, not connected to any normal Wi-Fi; Wi-Fi itself ON,
Location ON). Both on v0.6.0, both apps started, keys learned (`[key]`).

**13.1 Provider ready.** On B press **Provide Internet**. The Internet line
shows `PROVIDER: PROVIDER READY | upstream mobile data, validated`. A's peer
list shows B with `[NET]` within ~10 s. B's log: `UPSTREAM mobile data ... (validated Internet)`.
If it says `NO UPSTREAM`: mobile data is off, or Android has not validated it yet.

**13.2 Buyer connects.** On A select B and press **Use Internet**.
Expected on A, in order: Wi-Fi banner REQUESTING -> OFFERED -> JOINING (tap
CONNECT) -> TCP -> AUTH -> WIFI UP (skipped if the link is already up), then
`BUYER: CONNECTING`, then `TUNNEL UP`, then an Android dialog
"Connection request / ProkNet Lab wants to set up a VPN connection": tap **OK**.
Log on A: `SESSION OK: provider prok-B upstream mobile data`, `VPN UP: 10.8.0.2/24 ...`.
Log on B: `SESSION OK for prok-A via mobile data`.
A key icon appears in A's status bar (VPN active).

**13.3 In-app test.** On A press **Net test**. A dialog shows something like
`stream open in 900 ms via provider / TLS TLSv1.3 ... / HTTP: HTTP/1.1 200 OK / 1256 bytes in 2400 ms`.
Internet line: `BUYER: INTERNET OK`. B's log shows `stream N open to example.com:443`.

**13.4 Real browser.** On A open Chrome and load https://example.com, then
https://www.wikipedia.org. Pages must render. A's Internet line counts
`flows` and `dns`; B's counts `streams` and bytes. A's log may show
`UDP to port 443 dropped` (QUIC): expected, the browser falls back to TCP.

**13.5 Accounting.** On A press Stop Internet. Both logs print
`SESSION END: ... up N B, down M B, streams S, dns D, T s, ended: ...`.
Copy log on both phones: the diagnostic text lists the session and the last
Net test.

**13.6 Reliability.**
- B turns mobile data OFF while A browses: A shows `INTERNET LOST` within a
  few seconds; back ON: `TUNNEL UP` again (B log: `UPSTREAM ... [capabilities changed]`).
- B presses Stop providing: A `DISCONNECTED` with reason, VPN stays up but
  nothing loads until Use Internet again (or press Stop Internet).
- Walk A out of Wi-Fi range: link fails, A `DISCONNECTED: Wi-Fi link closed`.
- Swipe A away, reopen: VPN and session survive (the service holds them).

What to send back: Copy log from both phones after 13.4, and the Net test
dialog text.

### Checklist for the v0.6 report

- [ ] 13.1 PROVIDER READY with upstream type, `[NET]` visible on the buyer
- [ ] 13.2 SESSION OK on both, VPN dialog accepted, VPN UP
- [ ] 13.3 Net test: HTTP status through the provider
- [ ] 13.4 a real HTTPS page renders in the browser on A with data OFF
- [ ] 13.5 session accounting on both sides
- [ ] 13.6 at least the mobile-data-off/on case

## 14. v0.6.1 - copy buttons and the retest

- **COPY LOG** is now at the very top of the screen (full diagnostic + full log).
- **COPY DIAG** is next to Net test (everything above the log + the last 120
  log lines: short enough for a chat message).

Retest = section 13 unchanged. The v0.6.0 failure ("tunnel write failed:
null", link DOWN right after Use Internet) is fixed at the root: link writes
no longer happen on the main thread. If anything still fails, the last error
now names the exception class and where it happened.

## 15. v0.7 Marketplace (two phones)

B = seller (mobile data ON), A = buyer (mobile data OFF). Both on v0.7.0,
started, keys learned.

**15.1 SELL.** On B press **SELL**, set 5 CFA/MB, min 0, max 0, Start selling.
B's Internet line: `SELL: PROVIDER READY | 5 CFA/MB | upstream mobile data, validated`.
A's peer list shows B as `[SELL 5 CFA/MB mobile data]` within ~10 s.
B's log: `SELL enabled: 5 CFA/MB ...`.

**15.2 BUY.** On A press **BUY** (with B selected, or pick B from the list).
Approve the Wi-Fi CONNECT dialog and the VPN OK dialog as before.
A's log, in order: `BUY from prok-B at 5 CFA/MB`, `STATE AGREEING - proposing 5 CFA/MB ...`,
`CONTRACT AGREED with prok-B: session xxxx, 5 CFA/MB ... (both signatures stored)`,
`SESSION OK`, `VPN UP`. B's log: `CONTRACT AGREED with prok-A ...`, `SESSION OK for prok-A ... under contract xxxx`.

**15.3 Usage and price rise.** On A press Net test, then browse a few pages.
Every ~30 s both logs show `CHECKPOINT #n issued` (B) and
`CHECKPOINT #n verified and countersigned` (A). A's Internet line:
`BUY: INTERNET OK ... 1.20 MB used, running 6.00 CFA, agreed 5.50 CFA (checkpoint #3)`.
B's line: `buyer prok-A: 1.20 MB ... running 6.00 CFA, agreed 5.50 CFA (checkpoint #3)`.
The "agreed" amounts must be identical on both phones at the same checkpoint number.

**15.4 End and settle.** On A press **STOP BUY**. Both logs print
`SETTLEMENT (... view) session xxxx: signed usage N MB -> X CFA (checkpoint #k), fee ..., seller net ...`.
X must be the same on both phones. B's line shows `sold N MB, earned <net>`.
Press **Ledger** on both: A sees `I owe 5.50 CFA prok-A -> prok-B internet session [pending]`;
B sees the same entry as `owed to me` plus `prok-B -> prok-network network fee 5% [pending]`.
**History** lists the session with MB, duration, price and final cost.

**15.5 Settlement marks.** On A: Ledger -> the entry -> MARK AS PAID. On B:
Ledger -> the entry -> MARK AS RECEIVED -> B shows `[settled]`. (Each phone
keeps its own ledger in v0.7; the marks are local.)

**15.6 Price lock.** While A is connected, on B press STOP SELL then SELL at
10 CFA/MB. A's session ends (`seller disabled`), A's final cost is still at
5 CFA/MB. Pressing BUY again on A proposes 10 CFA/MB (the new advertised price).

**15.7 RELAY.** Press RELAY on either phone: the other lists it with `[RELAY]`.
Diagnostics show `relay activity: N packets forwarded` (from real carry-forward).

What to send back: COPY DIAG from both phones after 15.4.

### Checklist for the v0.7 report

- [ ] 15.1 offer visible on the buyer with price and upstream
- [ ] 15.2 contract agreed on both, session under contract, VPN up
- [ ] 15.3 checkpoints countersigned; agreed cost identical on both
- [ ] 15.4 same final cost on both; ledger entries (gross + fee)
- [ ] 15.5 paid / received marks
- [ ] 15.6 price locked for the running session

## 16. v0.7.1 retest

Section 15 unchanged. The v0.7.0 failure (`no contract answer within 15s`)
is fixed at the root. Expected on both phones right after the Wi-Fi link is
up: `CONTRACT AGREED ... (both signatures stored)`, then `SESSION OK`, VPN
UP on A, and `CHECKPOINT #1` lines within 30 s of the first traffic.

## 17. v0.8 consumer UI (two phones, same engine as v0.7.1)

The engine is untouched, so section 15 still describes what happens
underneath. This section is about what a normal user sees.

Phone B (seller): open Prok. If permissions were granted before, the top
right chip says **On** within a second. Tap **Share Internet**: set price 5,
minimum 0, max 0, tap **Start sharing**. Expected: "You're sharing Internet /
Available to people nearby", the terms line, Data shared 0 B, Earned 0 CFA.
If mobile data is off: "Waiting for your Internet / Turn on mobile data or
connect to Wi-Fi".

Phone A (buyer): open Prok, tap **Get Internet**. Expected: one card
"Internet available · <B's name>", "5 CFA / MB", "Good signal · Mobile data ·
Checked". Tap it: confirmation with price, minimum, limit, fee. Tap
**Connect**. Expected states in order: "Finding provider…" -> "Connecting…"
(with "Android will ask to join a network: tap CONNECT") -> "Securing
connection…" -> "Starting Internet…" (Android VPN dialog: tap OK) ->
"Connected". Then Data used / Cost so far count up while browsing. B shows
"Someone is using your Internet" with the customer line.

Tap **Stop** on A. Expected: A back to the offer list; Activity tab on both
phones shows the session card with the same final cost; tap it for the
detail (date, data, duration, price, final cost, payment "To pay" on A,
"To receive" on B; B also shows the Prok fee). Mark as paid / received work
as in v0.7.

Home: connection state, nearby count, offers count, session cost while
buying, earnings while sharing. Earn: total earned, "Help ProkNet" switch.
Profile: name, "Keep Prok running" switch, background permission, and
**Open developer screen** = the complete v0.7.1 lab screen (Start/Stop,
Wi-Fi link, Big test, Send file, BUY/SELL/RELAY, Ledger, History, dev
Internet controls, COPY LOG, COPY DIAG). Dark mode: switch the phone to dark
theme and reopen.

### Checklist for the v0.8 report

- Both phones: does anything on the five consumer tabs show an engine word
  (GATT, WIFI, SESSION, checkpoint, hex ID)? Which screen?
- A: the exact sequence of state titles seen, and whether the two Android
  dialogs were announced by the hint line.
- Final cost on A's and B's Activity cards (must match).
- Anything you could not do from the consumer screens and had to do in the
  developer screen.
- COPY DIAG from the developer screen if something failed.

## 18. v0.9 three-phone live relay (A, B, C)

This is a physics experiment. Both outcomes are useful results. Do not
help it along: A must not be able to reach C directly.

Setup:
- C = seller: mobile data ON. Prok running, **Share Internet** on (5 CFA/MB).
- B = relay: mobile data OFF, Wi-Fi ON, Location ON. Prok running (developer
  screen: Start). Do NOT share or buy on B.
- A = buyer: mobile data OFF, Wi-Fi ON. Prok running.
- Place A and C far enough apart that A does not see C's offer; B in the
  middle sees both. (If the room is small, first prove the flow with all
  three close together, then separate.)

On B, developer screen -> **Relay Lab**:
1. Tap **RELAY MODE**. Expected: "RELAY MODE on", state line "waiting for
   both links".
2. Tap C in the peer list (it must show `[key]` and `[SELL 5]`), then
   **LINK UPSTREAM**. Approve the Android "connect to device" dialog on B.
   Expected within a minute: `UP: WIFI UP prok-<C>` and, in the log, `LINK
   UP with prok-<C> (client...)`. On C: WIFI UP with B.
3. Tap **REFRESH** and read the capabilities block: note `STA+AP
   concurrency` and `STA concurrency for local-only connections`.

On A: **Get Internet**. Expected: one card "Internet available · <B> ·
through another phone / 5 CFA / MB". Tap it, **Connect**, approve the
Wi-Fi dialog. This is the moment of truth: B must start its hotspot while
it is joined to C.
- If it works: A shows Finding -> Connecting -> Securing -> Starting ->
  Connected; VPN dialog on A; browsing in Chrome works. B's Relay Lab
  shows a session with bytes "to seller" and "to buyer" growing. C's
  developer screen shows `CONTRACT AGREED with prok-<A>` (A, not B) and
  the session with A. A's Activity card shows the session with C.
- If B's hotspot fails: B's log shows `hotspot failed, reason ...
  (incompatible mode ...)` or the upstream link drops when the hotspot
  starts (`requestNetwork.onLost`). That is the result: this phone cannot
  do the concurrent topology. Try the other order once: on B drop the
  upstream (DROP UP), let A connect to B first (A: Get Internet, Connect;
  B hosts), then LINK UPSTREAM to C.

Then on B: **COPY RELAY DIAG** (after the attempt, whichever way it went)
and paste it to ChatGPT. Also COPY DIAG from A and C's developer screens.

### Checklist for the v0.9 report

- B: phone model, Android version, the four concurrency lines.
- B: did the upstream link come up? Did the hotspot start while it was up?
  Exact hotspot error if not. Did the upstream link survive the hotspot?
- A: state titles seen; did Chrome browse? Data used / cost on the card.
- B: session bytes to seller / to buyer at the end.
- C: does its session and Activity name A? Final cost on A and C.
- Which order was tried (upstream first / downstream first) and the result
  of each.

### Wi-Fi source discovery (any phone)

Relay Lab -> **SCAN WI-FI** (Location ON). Expected: a list with name,
BSSID, dBm, security. Tap one, classify it; the line shows the class and
"shareable" only for public/open and authorized. Nothing connects.

## 19. v0.9.1 three-phone retest

Same setup as section 18 (C sells with mobile data ON, B relays with data
OFF, A buys with data OFF). What changed: the buyer now ASKS the relay to
introduce its seller, so the order no longer matters.

Do it in the order that failed last time, on purpose:
1. B: Relay Lab -> RELAY MODE, select C, LINK UPSTREAM, approve the dialog.
   Expected: `UP: WIFI UP prok-<C>`, and a relay session that says
   `intro 1/1 ack -/seller` once C has acknowledged.
2. Wait a minute or two doing nothing. This is what broke v0.9.0.
3. A: Get Internet -> the card "through another phone" -> Connect.
   Expected in A's log: `INTRO REQUEST 1/5 -> relay prok-<B>`, then
   `INTRODUCED by relay prok-<B>: seller prok-<C>`, then the normal
   `CONTRACT AGREED` / `SESSION OK` / VPN / browsing.
   Expected in B's Relay Lab: `INTRO_REQUEST from prok-<A> -> INTRODUCE`,
   `intro 2/2 ack buyer/seller`, then the byte counters moving.
4. Then try the other order too (A connects to B first, B links upstream
   afterwards) and a retry after a failure: press Stop on A, then Connect
   again. Both must work without restarting anything.

Negative case worth one run: on B turn RELAY MODE on but do NOT link
upstream, then press Connect on A. Expected: A stops within a few seconds
saying it could not build the connection, NOT a 20 s silent wait, and the
message must not say "move closer".

### Checklist for the v0.9.1 report

- Did the late buyer get introduced? Paste the three log lines above.
- Relay session line at the end: intro counts, both acks, bytes each way.
- Did browsing work on A? Data used / cost on the card, and does C's
  Activity name A?
- The other order, the retry, and the no-upstream case.

## 20. v0.9.2 French app and the sharing fix

Any phone, consumer screens only (the developer screens stay English on
purpose).

1. Open the app. Everything must be French: Accueil / Internet / Gagner /
   Activite / Profil, the buttons OBTENIR INTERNET and PARTAGER INTERNET,
   and the notification "Prok fonctionne (votre nom)".
2. Partager Internet: leave the minimum and the limit EMPTY, put 5 as the
   price, tap COMMENCER LE PARTAGE. Expected: it starts (empty means zero
   now). Before v0.9.2 this said "check the price".
3. Clear the price and tap again. Expected: "Entrez un prix par Mo".
4. On a phone where the developer Relay Lab has RELAY MODE on, try to
   share. Expected: "Le mode relais est active. Desactivez-le dans
   Developpeur, puis reessayez." (not the price message).
5. Start a purchase that fails (for example connect to a provider that has
   no Internet), let it show "Connexion perdue", then tap Fermer and try
   PARTAGER INTERNET. Expected: sharing starts. Before v0.9.2 the failed
   attempt was still counted as "buying" and sharing was refused.
6. Numbers: a session must read like "11,5 Mo" and "57 CFA" even on a
   phone set to English.

### Checklist for the v0.9.2 report

- Any English left on the five consumer tabs, in a dialog, or in the
  notification? Where?
- The four sharing cases above, with the exact message each time.
- Anything that reads badly in French (wording, too long for the button).

## 21. v0.9.3 why a connection fails

Reproduce the report: the buyer stays on "Recherche d un fournisseur..."
then shows "Connexion perdue / Reessayez" with nothing to act on.

1. Seller phone: turn Wi-Fi OFF (keep mobile data on), share Internet.
   Expected on the SELLER: the sharing card warns "Activez le Wi-Fi sur ce
   telephone...". Expected on the BUYER after tapping Connect: it stops
   within seconds (not two minutes) with "Le fournisseur n a pas pu creer
   le point d acces...".
2. Seller phone: Wi-Fi ON, Location OFF, share. Expected: the seller card
   warns about Location; the buyer gets the same fast, explicit failure.
3. Seller phone: leave Prok running but close the app screen, then buy from
   another phone. Expected: the buyer fails after about 60 s with "Le
   fournisseur n a pas repondu. Sur son telephone : Wi-Fi et localisation
   actives, application ouverte."
4. Everything on, both phones ready: the normal flow must still work
   (Connexion..., the Android dialog, Securisation..., Connecte).
5. After any failure, press PARTAGER INTERNET on the buyer phone: sharing
   must start (the dead attempt no longer counts as buying).

If it still fails with everything on: developer screen -> COPY DIAG on BOTH
phones and paste them. The seller log now prints "starting the local-only
hotspot for prok-... (wifi on/off)" and the exact hotspot error.

### Checklist for the v0.9.3 report

- The exact message and how long it took, for each of the three cases.
- Did the seller card show the Wi-Fi / Location warning?
- Does the normal flow still work end to end?

## 22. v0.9.4 the seller always answers

**Install this build on BOTH phones.** The fix is mostly on the phone that
SHARES; a seller running an older build still answers nothing.

1. Normal case: seller shares, buyer connects. Must work end to end.
2. Repeat the purchase three times in a row without restarting anything.
   Every attempt must reach the Android dialog; none may hang on
   "Recherche d un fournisseur...".
3. Seller side, on purpose: link the two phones once (buy and stop), then
   buy again a few minutes later. The seller now drops the idle link and
   hosts again instead of ignoring the request.
4. Seller with Wi-Fi OFF: the buyer must stop within seconds with "Le
   fournisseur n a pas pu creer le point d acces...".
5. Two buyers, one seller: while the first is connected and browsing, the
   second taps Connect. Expected on the second: "Le fournisseur est deja
   occupe avec un autre telephone. Reessayez dans un moment." (not a
   60 s wait, and the first session must not break).

### Checklist for the v0.9.4 report

- Build number shown in Profil on BOTH phones (it must be 0.9.4).
- Did any attempt still hang until the 60 s timeout?
- The exact message for cases 4 and 5.

## 23. v0.9.5 the provider says what is wrong with IT

Both phones on 0.9.5.

1. Provider connected to a Wi-Fi network, share, then buy from the other
   phone. If the provider phone cannot share its Wi-Fi channel, the buyer
   must now say: "Le fournisseur doit se deconnecter du Wi-Fi et utiliser
   ses donnees mobiles...". Do exactly that on the provider (Wi-Fi OFF,
   mobile data ON) and buy again: it must work.
2. Provider with Location OFF: the buyer says the provider must turn
   Location on. The provider log must show "cannot host: Location services
   are off" WITHOUT calling Android.
3. Provider with the Android hotspot (partage de connexion) ON: the buyer
   says to turn it off.
4. Press CONNECT twice in a row after a refusal: the second attempt must go
   out immediately (no "wait 40s").
5. Developer screen -> COPY DIAG on the BUYER now contains the provider's
   own error line ("provider refused, ITS error: ...").

### Checklist for the v0.9.5 report

- The exact sentence the buyer showed, and the provider error line from the
  buyer's COPY DIAG.
- Which change on the provider made it work.

## 24. v0.9.6 the phone tests itself before a customer does

1. Seller phone connected to your Wi-Fi router, mobile data off. Turn
   sharing on. Within a couple of seconds the sharing card must say:
   "Ce telephone ne peut pas partager ce reseau Wi-Fi. Vous pouvez partager
   vos donnees mobiles a la place." Nothing else changes: sharing stays on.
2. Same phone, Wi-Fi off, mobile data on, sharing on: no warning at all,
   and a buyer connects as usual.
3. If the router has a 2.4 GHz network, join the seller to it and turn
   sharing on: if that phone can host there, the warning must NOT appear
   and a buyer must connect while the seller stays on Wi-Fi. This is the
   case the rule exists for.
4. Developer screen -> COPY DIAG on the seller: the line "share check:
   CAN_SHARE / CANNOT_SHARE on 5 GHz DFS ch 60 (5300 MHz) [bssid:...]" with
   the Android error. Relay Lab -> TEST SHARING forces a fresh test.
5. Turn sharing off and on again: the answer must be instant (remembered),
   with no second hotspot flash.

### Checklist for the v0.9.6 report

- The exact share-check line from COPY DIAG on each phone, with band and
  channel. This is the data that shows whether failures are all 5 GHz/DFS.
- Did a 2.4 GHz network let the seller share while staying on Wi-Fi?
- Any phone where the probe itself misbehaved (long flash, Wi-Fi dropped).

## 25. v0.9.7 Wi-Fi Direct experiment (two phones)

Both phones on 0.9.7. Nothing in the normal app changes; everything here is
Profil -> Ouvrir l ecran developpeur -> Relay Lab -> WI-FI DIRECT LAB.

Setup:
- SELLER: connected to the home router (Freebox), **mobile data OFF**,
  Prok running. Note the Wi-Fi network name shown at the top of the lab.
- BUYER: **mobile data OFF**, Wi-Fi ON, Prok running.

Run:
1. SELLER: tap **P2P SELL TEST**. Expected: phase CREATING GROUP then
   GROUP OWNER, a group line with an ssid and an interface (p2p-wlan0-0),
   and - the whole point - "my Wi-Fi network now" still showing the
   Freebox. If it changes to none, stop and report: that is the
   GROUP_BUT_STA_LOST verdict.
2. BUYER: tap **P2P BUY TEST**, wait for the seller in the peer list, tap
   it. Approve any Android invitation. Expected: phase CLIENT, then a
   socket line, then "ProkNet link: WIFI UP with prok-...".
3. BUYER: tap **USE INTERNET**, approve the VPN prompt, then **NET TEST**.
   Then open Chrome and load a site.
4. SELLER: check that its own Wi-Fi network is STILL the Freebox and that
   the seller line shows the buyer and bytes.
5. Both phones: **COPY P2P DIAG** and send both.

The verdict line at the bottom of the lab is what matters:
- LINK_UP_STA_KEPT = the experiment succeeded.
- GROUP_BUT_STA_LOST = Wi-Fi Direct killed the home connection: useless for
  reselling home Wi-Fi on this phone.
- NO_GROUP / LINK_FAILED = it did not get that far; the errors are in the
  diag.

### Checklist for the v0.9.7 report

- The verdict on each phone, and who ended up group owner.
- The seller's Wi-Fi network before and after the group formed.
- The p2p interface name and IPs, and the socket line.
- Did Chrome load pages on the buyer? Data and cost seen on the seller.
- Any Android error text from either diag.

Nothing about home Wi-Fi resale is claimed until this passes.

## 26. v0.9.8 the P2P role switch must leave nothing behind

Both phones on 0.9.8, in the WI-FI DIRECT LAB. This section is about the
cleanup that v0.9.7 got wrong; section 25 is still the real experiment.

1. On one phone: P2P SELL TEST, wait for GROUP OWNER and a group line.
   Tap STOP P2P. Expected in the log: CLEANUP started, then one line per
   step (CANCEL_CONNECT, STOP_DISCOVERY, CLOSE_SOCKETS, REMOVE_GROUP) with
   Android answer, then CLEANUP complete. The screen must then show
   IDLE, role NONE, group none, socket none, and no p2p0 address in the
   interfaces line.
2. Same phone: P2P BUY TEST. Expected: another CLEANUP first, then
   DISCOVERING, and the peer list filling with the other phone. The group
   line must stay "none": this is exactly what failed before.
3. Reverse it: P2P BUY TEST, STOP P2P, then P2P SELL TEST. The group must
   be created fresh, and no old socket may remain.
4. Tap STOP P2P three times in a row: nothing may break, and the state must
   stay IDLE and clean.
5. COPY P2P DIAG and check the line `clean (nothing left from a previous
   role): true`.

### Checklist for the v0.9.8 report

- The four cleanup lines with Android answers, for one STOP.
- Does the interfaces line still show p2p0 after STOP?
- Does BUY after SELL now discover peers?
- Then run section 25 again for the real Wi-Fi Direct verdict.

## 27. v0.9.9 the real test, from the normal app

Both phones on 0.9.9. This is the flow that decides whether home Wi-Fi
resale works. Nothing here needs the developer screens.

Setup:
- SELLER: connected to the Freebox, **mobile data OFF**, Prok running.
- BUYER: **mobile data OFF**, Wi-Fi ON, Prok running.

Run:
1. SELLER: PARTAGER INTERNET, price 5, start. Expected: sharing starts, and
   after a second or two the card says "Partage active par liaison directe
   entre telephones (essai)". The phone must STILL be on the Freebox.
2. BUYER: OBTENIR INTERNET. The seller's offer appears as usual. Tap it,
   then SE CONNECTER.
3. Expected on the buyer, in French, with no jargon: Recherche d un
   fournisseur..., then Connexion..., Securisation..., Demarrage
   d Internet... (accept the Android VPN prompt), then Connecte.
4. Open Chrome on the buyer and load two or three sites.
5. SELLER: the card must show a customer and the data going up.

Then, for the record, developer screen -> Relay Lab -> WI-FI DIRECT LAB ->
COPY P2P DIAG on BOTH phones. The lines that matter:
- seller: role GROUP_OWNER, **clients joined: 1**, its Wi-Fi network still
  the Freebox before and now, last invitation accepted;
- buyer: role CLIENT, group formed, socket connected to 192.168.49.1:47742,
  ProkNet link WIFI UP.

If the buyer stops with "Impossible d etablir la connexion", the reason is
in its log: no group, not invited, or not seen in the peer list.

### Checklist for the v0.9.9 report

- Did the seller stay on the Freebox the whole time?
- Seller clients count, and the invitation line.
- Did Chrome load pages? Data and cost on both phones.
- Both COPY P2P DIAG outputs.

Home Wi-Fi resale is claimed only when this exact flow passes.

## 28. v0.9.10 Bluetooth must repair itself after a session

Both phones on 0.9.10. This is the recovery the last run needed; section 27
is still the flow that decides Wi-Fi Direct.

1. Run section 27 to the end: a real Internet session over Wi-Fi Direct,
   Chrome loading pages.
2. End it: STOP on the buyer, or let it drop on its own.
3. Put the two phones side by side and DO NOTHING for two minutes. No
   toggling Bluetooth, no killing the app, no reboot.
   Expected: the seller reappears in Accueil ("1 personne") and its offer
   comes back under OBTENIR INTERNET, by itself.
   In the log you should see, if a repair was needed: "BLE health check
   ... scan stale", "BLE radio recovery started", "advertising restarted",
   "scan restarted", "BLE recovery complete".
4. Buy again from the normal screens. It must work without any developer
   action.
5. Seller side: sharing must still be ON the whole time, and its offer must
   be advertised again after any recovery (do not tap PARTAGER INTERNET
   again).
6. Negative check: with both phones idle and nothing shared, leave the app
   running for five minutes. The log must NOT show repeated recoveries;
   the watchdog must stay quiet when nobody is expected.

Developer screen -> COPY DIAG now has a "ble health:" line with advertising
state, scan results and their age, GATT timeouts and how many recoveries
happened.

### Checklist for the v0.9.10 report

- Did the phones find each other again on their own, and how long did it
  take?
- The recovery lines from the log, if any.
- Did sharing stay on, and did the offer come back without touching it?
- Any sign of a restart loop while idle.

## 29. v0.9.11 no more silent refusals

Both phones on 0.9.11. Same setup as section 27.

1. SELLER on the Freebox, mobile data OFF: PARTAGER INTERNET.
   Watch the log: the offer must only claim the direct way in once the log
   says the group is formed. If createGroup is refused, it must retry
   ("createGroup refused (BUSY), retrying in 3s").
2. BUYER: OBTENIR INTERNET, pick the offer, SE CONNECTER.
   Expected: ONE invitation request per ten seconds, not one per four
   seconds.
3. If the seller cannot admit the buyer, the buyer must STOP within a
   couple of seconds and show a real sentence, never return to the offer
   list in silence.
4. If the seller's group had died, the log on the seller must say
   "rebuilding it" and the next buyer request must succeed.
5. Then the full flow of section 27: Connecte, Chrome loads, seller shows
   the customer.

### Checklist for the v0.9.11 report

- Did the buyer stop on the first refusal, with which sentence?
- How many invitation requests per minute in the log?
- Did the seller rebuild its group when asked?
- Did the normal flow finally connect?

## 30. v0.9.12 the home Wi-Fi resale proof

This is the exact condition that decides it. Nothing less counts.

Setup, checked before starting:
- SELLER: **mobile data OFF**, connected to the Freebox Wi-Fi.
- BUYER: **mobile data OFF**, **not connected to the Freebox** (Wi-Fi on,
  no network joined, or joined to nothing usable).
- Both phones on 0.9.12, Prok running, Location on.

Run, from the normal screens only:
1. SELLER: PARTAGER INTERNET at 5 CFA. The card says the direct link trial
   is active. The log must show WI-FI DIRECT GROUP FORMED with role
   GROUP_OWNER before the offer claims that way in.
2. BUYER: OBTENIR INTERNET, the seller's offer, SE CONNECTER.
   The log should read, in order: "asking ... whether its Wi-Fi Direct
   group is ready", "answered GROUP_READY, its Wi-Fi Direct name is ...",
   "joining the provider group: attempt 1/4", "connect accepted",
   "WI-FI DIRECT GROUP FORMED: role CLIENT", a socket line to
   192.168.49.1:47742, then the signed handshake and the contract.
3. Accept the VPN prompt. Wait for Connecte.
4. Open Chrome on the buyer and load two or three sites.
5. Check on the SELLER: still connected to the Freebox, clients 1, a
   customer on its card with the data going up.

Then COPY P2P DIAG on both phones.

**Claim rule:** home Wi-Fi resale is solved only if, in that run, the seller
never left the Freebox, the buyer had no mobile data and no Freebox
connection of its own, and Chrome loaded pages. Anything else is a partial
result and must be reported as one.

### Checklist for the v0.9.12 report

- The five log lines from step 2, as they appeared.
- Seller clients count and its Wi-Fi network before and during.
- Buyer role, group owner address, socket line, VPN, INTERNET OK.
- Did Chrome load? Data and cost on both phones.

## 31. v0.9.13 the TCP listener lifecycle

Same setup as section 30, and section 30 is still THE claim rule. This
section is only about reading the new lines while you run it.

On the SELLER, after PARTAGER INTERNET, the log must show, in order:

```
WI-FI DIRECT GROUP FORMED: role GROUP_OWNER ... | endpoint generation 1, role GROUP_OWNER,
        interface p2p-wlan0-XX, local 192.168.49.1, network NNN
P2P ENDPOINT generation 1, ... (first endpoint, seen on connection info)
LISTENER creating: 192.168.49.1:47742 | generation 1, ...
LISTENER actual: 192.168.49.1:47742 | ... | bound to the P2P endpoint=true | network bound=true
LISTENER accept loop started for generation 1 (token 1) on 192.168.49.1:47742
```

`bound to the P2P endpoint=true` is the line that was missing in v0.9.12. If
it says false, copy the whole block: the listener did not land on the Wi-Fi
Direct address.

When the buyer joins, the seller must log:

```
CLIENT COUNT 0 -> 1: listener check says valid | endpoint ... | listener 192.168.49.1:47742 ...
TCP accepted 192.168.49.124:NNNNN on 192.168.49.1 (generation 1)
```

A check that says anything else is not a failure by itself: the listener is
then rebuilt for the current endpoint and the reason is printed. What must
never appear is a client joining with no check line at all.

On the BUYER, each dial attempt prints its own network:

```
DIAL 1/6: 192.168.49.124 -> 192.168.49.1:47742 | p2p interface p2p0 |
          android network NNN | socket bound to P2P network=true
TCP connected 192.168.49.124 -> 192.168.49.1:47742 (bound to the P2P network=true)
```

Then the signed handshake, the contract, the VPN and INTERNET OK, as before.

**The bounded failure.** If the group forms and no transport comes up, the
buyer must STOP after about 45 seconds with
"Connexion locale créée, mais le fournisseur ne répond pas." and go back to
the offer list. It must never spin forever. Check on the seller right after:
its sharing card is still on, its Wi-Fi is still the Freebox, and it can
serve the next attempt without being restarted.

**Upstream check, every run:** while the customer is online, the seller must
still show the Freebox as its Internet, and COPY DIAG on the seller must show
`wlan0` as the upstream with `p2p...` listed as a local link only.

### Checklist for the v0.9.13 report

- The five seller lines above, verbatim.
- The `CLIENT COUNT 0 -> 1` line and its verdict.
- One `DIAL` line from the buyer, with the bound state.
- Whether TCP was accepted, and how long after the join.
- If it failed: the French sentence, the time it took, and both P2P DIAG dumps.

## 32. v0.9.14 the data plane, read on both phones

Setup is section 30, unchanged, and section 30 is still the claim rule.

**SELLER, when the buyer joins.** The three lines that matter now:

```
CLIENT COUNT 0 -> 1
DATA PLANE generation 1.1 created (client membership established, seen on the client count changed)
   group generation 1, membership generation 1, role GROUP_OWNER,
   interface p2p-wlan0-XX, local 192.168.49.1, network NNN, clients 1
   old listener: group generation 1, membership generation 0 -> stale for live client membership
LISTENER rebuilding for live membership: the listener was built before this client membership existed
LISTENER actual: 192.168.49.1:47742 | ... | on the P2P local address=true
LISTENER accept loop started for generation 1.1 (token 2) on 192.168.49.1:47742
```

Then the handshake and the outgoing dial:

```
P2P MEMBER: prok-XXXX is in my group at 192.168.49.124:47742
answering TRANSPORT_READY: 192.168.49.1:47742 for membership generation 1
TRANSPORT dial to 192.168.49.124:47742 for generation 1.1
DIAL 1/6: 192.168.49.1 -> 192.168.49.124:47742 | p2p interface p2p-wlan0-XX |
          android network NNN | binding ANDROID_NETWORK
```

**BUYER.**

```
WI-FI DIRECT GROUP FORMED: role CLIENT ... group generation 1, membership generation 1
telling prok-XXXX that I am in its group at 192.168.49.124:47742
TRANSPORT_READY from prok-XXXX: 192.168.49.1:47742 for membership generation 1
DIAL 1/6: 192.168.49.124 -> 192.168.49.1:47742 | p2p interface p2p0 |
          android network none | binding LOCAL_ADDRESS 192.168.49.124
```

`binding NONE` must never appear. If it does, the phone knew neither a P2P
network nor a P2P address, and the socket was refused on purpose.

**Either side may win.** The session is good whether the log says
`TCP accepted ... membership generation 1` on the seller or
`TCP connected ... binding ANDROID_NETWORK` on the seller. Both are the same
authenticated ProkNet link. Write down WHICH ONE won, because that is the
answer to the question this version was built to ask.

Then, unchanged: signed handshake, contract, VPN, INTERNET OK, Chrome.

**The bounded failure** still applies: with a formed group and no transport
after 45 s, the buyer stops with
"Connexion locale créée, mais le fournisseur ne répond pas." and the seller
keeps sharing.

### Checklist for the v0.9.14 report

- The seller block above, verbatim, especially the `old listener: ... -> stale` line.
- Which side produced the socket, and how long after `CLIENT COUNT 0 -> 1`.
- The `binding ...` word from both phones.
- Seller Wi-Fi before, during and after. Provider upstream must stay the Freebox.
- If it still fails: both COPY P2P DIAG dumps, which now print the data plane,
  the listener and the verdict.

## 33. v0.9.15 the radio and the link probe

Setup is section 30, unchanged. Section 30 is still the claim rule.

**What must appear now, on BOTH phones, the moment the buyer joins:**

```
DISCOVERY off: somebody has joined: the radio must stay on the group channel
discovery stopped, the radio can stay on the group channel
```

After that line there must be **no** `starting peer discovery` and no
`discoverPeers accepted` until the group is gone. If either appears while a
customer is in the group, that is the bug coming back and the run should be
reported with those timestamps.

**The measurement.** Each side opens a UDP echo when its listener is armed:

```
LINK PROBE listening on 192.168.49.1:47743
```

and the dialling side prints one of three verdicts:

```
LINK PROBE verdict: the link carries IP packets both ways
LINK PROBE verdict: packets arrive here but our answers do not get back
LINK PROBE verdict: NO IP packet crossed the Wi-Fi Direct link in either direction
```

**This verdict is the result of the run**, whatever happens to the TCP
connection. Copy it from both phones. It is worth more than the six DIAL
lines, because it separates a radio problem from a socket problem for good.

If the probe says the link carries packets and TCP still times out, copy both
COPY P2P DIAG dumps: that would be a new and different fault.

### Checklist for the v0.9.15 report

- The `DISCOVERY off` line on both phones, with its timestamp.
- Any `discoverPeers accepted` AFTER it, if there is one.
- The `LINK PROBE verdict` line from both phones.
- Whether TCP connected, and which side won.
- Seller Wi-Fi before, during and after. The Freebox must survive.

## 34. v0.9.16 the one way link

Setup is section 30. Section 30 is still the claim rule.

Read these four lines on BOTH phones, in this order.

**1. The radio lock, when the group forms:**

```
RADIO LOCK held: HIGH_PERF+LOW_LATENCY (a Wi-Fi Direct group exists on this phone)
```

If it says `none`, the phone refused both locks and that is worth reporting
on its own.

**2. The group channel, next to the home Wi-Fi channel:**

```
GROUP CHANNEL: 5 GHz ch 48 (5240 MHz) | this phone's Wi-Fi: ? 5 GHz ch 48 (5240 MHz)
```

Write down whether the two are the same channel. On the buyer the second half
will say `none`, which is expected.

**3. The probe verdict, on each phone.** There are now five:

```
the link carries IP packets both ways
only BROADCAST crosses: the two phones cannot address each other directly
packets arrive here but our answers do not get back
NO IP packet crossed the Wi-Fi Direct link in either direction
the link was never probed
```

**Copy the verdict from BOTH phones.** They can differ, and when they differ
that is the answer: in the v0.9.15 run the owner said packets arrive but
answers do not get back, and the client said nothing crossed. That pair means
uplink only.

**4. Whether TCP connected**, and which side won.

### What each result means for the next step

- Both phones say the link carries packets both ways, and TCP connects: the
  transport is done and the rest of section 30 applies.
- Both phones say it carries packets and TCP still fails: a new fault, above
  IP. Send both COPY P2P DIAG dumps.
- Owner says "answers do not get back" again: the Wi-Fi lock did not change
  the downlink, and the next lever is the association direction, not the
  sockets.
- `only BROADCAST crosses`: the two phones cannot address each other
  directly, which is an addressing fault and has its own fix.

### Checklist for the v0.9.16 report

- `RADIO LOCK held:` from both phones.
- `GROUP CHANNEL:` from the seller, and the home Wi-Fi channel beside it.
- `LINK PROBE verdict:` from BOTH phones, both lines.
- Whether TCP connected. Seller Wi-Fi before, during and after.

## 35. v0.9.17 the screen must not end a purchase before it starts

Quick check, one minute, before section 34.

1. On the buyer, make an attempt fail (press SE CONNECTER with the seller not
   sharing, and let it end).
2. Press SE CONNECTER again on a seller that IS sharing.

The card must say "Recherche d'un fournisseur..." and stay there. It must NOT
say "Connexion perdue" in the first seconds. If it does, copy the `wifi:` line
from the diagnostic: it means a transport that is not in use is still being
read.

Then run section 34 **without pressing STOP**. The attempt ends by itself
after about 45 seconds with
"Connexion locale créée, mais le fournisseur ne répond pas." if no transport
comes up, and the log lines from section 34 are only written while it runs.

## 36. v0.9.18 the buyer must dial the provider and nothing else

One line to check while running section 34.

Every join attempt now names its target:

```
joining the provider group: attempt 1/4 to "C1 Pro" (72:cb:dd:b9:a1:da)
join accepted: waiting up to 15s for the group to form
```

The name in quotes must be the provider. If any attempt names a printer, a
television or anything else, that is a bug and the run should be reported
with those lines.

While the provider is missing from the list, the log says what it CAN see:

```
the provider group is ready: looking for it in this phone's Wi-Fi Direct list
(2 seen, 2 with a real address, looking for "C1 Pro"): DIRECT-FB-HP DeskJet 2700 series; Hisense VIDAA TV
```

That is correct behaviour now: it waits instead of dialling one of them.

There must also be **no second attempt within 15 seconds of an accepted
join**. `connect accepted` followed by another attempt three seconds later is
the v0.9.18 bug coming back.

## 37. v0.9.19 the band, and being findable again

**First, the thing that blocked the last run.** After a customer leaves, the
provider must log:

```
CLIENT COUNT 1 -> 0
keeping Wi-Fi Direct discovery alive: the customer left the group
starting peer discovery from a clean state
```

and the buyer must then see it again. A provider that holds a group and is
not discoverable is the v0.9.19 bug coming back. Test it directly: connect,
let it fail, then try a second time from the same two phones without
restarting anything.

**Second, the band.** On the seller, when sharing starts:

```
creating a fresh Wi-Fi Direct group (attempt 1/3): asking for a 2.4 GHz group:
this phone's own Wi-Fi is on 5 GHz ch 48 (5240 MHz), so a 5 GHz group would
share one channel with it
createGroup accepted (2.4 GHz requested), waiting for the group to form
GROUP CHANNEL: 2.4 GHz ch 6 (2437 MHz) | this phone's Wi-Fi: ? 5 GHz ch 48 (5240 MHz)
```

The `GROUP CHANNEL` line is the one that matters. If it still says 5 GHz
ch 48, Android did not honour the request and the experiment did not run.

If the log says "the 2.4 GHz group was refused ... creating a default group
instead", copy that line: the phone will not take a band request at all.

**Then the probe verdict, from BOTH phones**, exactly as in section 34. That
is still the result of the run.

### Checklist for the v0.9.19 report

- The `CLIENT COUNT 1 -> 0` and discovery lines after a failed attempt, and
  whether a SECOND attempt then finds the provider.
- `GROUP CHANNEL:` from the seller. Same band as the home Wi-Fi or not?
- `LINK PROBE verdict:` from both phones.
- Whether the seller kept the Freebox throughout.

## 38. v0.9.20 symmetric admission

Setup is section 30. This section is about the four lines that decide WHICH
phone acts.

**On the buyer, after the provider answers GROUP_READY:**

```
telling the provider what this phone can see: I cannot address the other phone
(2 addressable: DIRECT-FB-HP DeskJet 2700 series; Hisense VIDAA TV)
JOIN PLAN = SELLER_INVITE: only the provider can address the customer, so the provider invites
waiting for the provider to invite this phone
```

or, in the other case:

```
telling the provider what this phone can see: I can address "C1 Pro" at 72:cb:dd:b9:a1:da
JOIN PLAN = BUYER_CONNECT: the customer can address the provider, so the customer joins
joining the provider group: attempt 1/4 to "C1 Pro" (72:cb:dd:b9:a1:da)
```

**On the seller, at the same moment:**

```
admission: the customer "OnePlus Nord CE 2 Lite 5G" cannot address me, and I can address
"OnePlus Nord CE 2 Lite 5G" at 1e:4f:f2:19:36:ce -> SELLER_INVITE
JOIN PLAN = SELLER_INVITE: only the provider can address the customer, so the provider invites
INVITING the customer into my group: "OnePlus Nord CE 2 Lite 5G" (1e:4f:f2:19:36:ce)
```

**Both phones must print the SAME plan.** If they disagree, copy both lines:
that is the bug.

**Nothing may be dialled or invited that is not the other phone.** A printer
or a television in the addressable list is normal and must simply be ignored.

**Only one side acts.** A `connect accepted` on the buyer and an
`invitation ... accepted` on the seller in the same attempt is the ownership
rule failing.

Then the run continues into section 37 and section 34:

```
CLIENT COUNT 0 -> 1
GROUP CHANNEL: ... | this phone's Wi-Fi: ...
LINK PROBE verdict: ...     (from BOTH phones)
```

### Checklist for the v0.9.20 report

- The JOIN PLAN line from BOTH phones, and whether they match.
- Which side acted, and whether the group formed.
- If it formed: `GROUP CHANNEL` and the probe verdict from both phones.
- If it did not: the visibility lines from both phones, so we can see who
  could address whom.

## 39. v0.9.21 the invitation must actually be sent

The one line that was missing from the whole of v0.9.20. On the SELLER, when
the customer reports that it cannot address the provider:

```
admission: the customer "OnePlus Nord CE 2 Lite 5G" cannot address me, and I can address
"OnePlus Nord CE 2 Lite 5G" at 1e:4f:f2:19:36:ce | group formed=true role=GROUP_OWNER
hasMember=false owner=SELLER -> SELLER_INVITE
JOIN PLAN = SELLER_INVITE: only the provider can address the customer, so the provider invites
INVITING the customer into my group: "OnePlus Nord CE 2 Lite 5G" (1e:4f:f2:19:36:ce)
INVITE: inviting OnePlus Nord CE 2 Lite 5G (1e:4f:f2:19:36:ce)
invitation to OnePlus Nord CE 2 Lite 5G accepted by Android, waiting for it to join
```

`hasMember=false` with `group formed=true` is the normal state for a provider
waiting for its first customer, and it MUST invite. If the log shows
`not inviting right now: ...` instead, copy that line: it says which of the
four conditions was not met.

Then, still on the seller:

```
CLIENT COUNT 0 -> 1
DATA PLANE generation N.1 created (client membership established)
GROUP CHANNEL: ... | this phone's Wi-Fi: ...
LINK PROBE verdict: ...
```

On the BUYER:

```
JOIN PLAN = SELLER_INVITE: only the provider can address the customer, so the provider invites
waiting for the provider to invite this phone
WI-FI DIRECT GROUP FORMED: role CLIENT ...
LINK PROBE verdict: ...
```

**If it still fails**, the sentence now tells you where. "the provider could
see this phone, but the Wi-Fi Direct invitation did not complete" means the
invitation went out and Android never formed the group, which is a different
fault from "neither phone could address the other".

### Checklist for the v0.9.21 report

- The seller's `admission:` line with `hasMember=` and `owner=`.
- `INVITING` and `invitation ... accepted by Android`, or the
  `not inviting right now:` reason.
- `CLIENT COUNT 0 -> 1` on the seller.
- `GROUP CHANNEL:` and the `LINK PROBE verdict:` from BOTH phones.

## 40. v0.9.22 one clean run

This is the run that finally judges the 2.4 GHz band. Read it in this order.

**1. The plan, on both phones.**

```
JOIN PLAN = BUYER_CONNECT     or     JOIN PLAN = SELLER_INVITE
```

**2. The association, and its clock starting.**

```
ASSOCIATION started, owner BUYER, clock starts NOW: this phone is joining the provider group
(up to 40s, a person may have to tap Connect)
```

or on the provider:

```
INVITING the customer into my group: "OnePlus Nord CE 2 Lite 5G" (1e:4f:f2:19:36:ce)
invitation ... accepted by Android, waiting for it to join
ASSOCIATION started, owner SELLER, clock starts NOW
```

**If Android shows a confirmation dialog on either phone, tap CONNECT.** The
attempt now waits for you. It must NOT fail while that dialog is open.

**3. No discovery while it is pending.** Between the association starting and
the group forming there must be NO:

```
starting peer discovery from a clean state
```

A line saying `not starting discovery: an association is in flight` is the
correct behaviour. If `discoverPeers accepted` appears in that window, the
run is contaminated again and the probe verdict does not count.

**4. Membership, then the radio locked to the data phase.**

```
CLIENT COUNT 0 -> 1
DISCOVERY off: somebody is on this link: the radio stays on the group channel
ASSOCIATION ended (membership formed)
```

`DISCOVERY off` must appear immediately after the client count, not fifteen
seconds later.

**5. Only then, the experiment.**

```
GROUP CHANNEL: 2.4 GHz ch N (24NN MHz) | this phone's Wi-Fi: ? 5 GHz ch 48 (5240 MHz)
LINK PROBE verdict: ...        (from BOTH phones)
```

### Checklist for the v0.9.22 report

- The JOIN PLAN from both phones.
- The `ASSOCIATION started` line, and whether Android asked you to confirm.
- Any `starting peer discovery` between that line and the group forming.
- `CLIENT COUNT 0 -> 1` and the `DISCOVERY off` line right after it.
- `GROUP CHANNEL:` and the `LINK PROBE verdict:` from both phones.

**Only a run with no discovery in the data window decides the 2.4 GHz
question.**

## 41. v0.9.23 the reversed topology experiment

The question: **can the OUKITEL be a Wi-Fi Direct CLIENT of the buyer's group
while staying on the Freebox, when it cannot be a usable group owner?**

Setup is section 30, with mobile data off on both phones and the seller on
the Freebox.

**Turn the experiment on, on the BUYER only.** Wi-Fi Direct Lab, the button
at the bottom:

```
TOPOLOGY: SELLER OWNS THE GROUP        <- tap it
TOPOLOGY: BUYER OWNS THE GROUP (experiment)
```

The buyer tells the seller over BLE when the purchase starts, so nothing has
to be set on the provider.

Then buy from the normal screen. Expected, on the BUYER:

```
TOPOLOGY = BUYER_GROUP_OWNER: the customer owns the Wi-Fi Direct group and the
provider joins it, keeping its home Wi-Fi
WI-FI DIRECT GROUP FORMED: role GROUP_OWNER
GROUP CHANNEL: ...
admission: ... -> GUEST_CONNECT or OWNER_INVITE
CLIENT COUNT 0 -> 1
LINK PROBE verdict: ...
```

On the SELLER:

```
TOPOLOGY = BUYER_GROUP_OWNER (asked by prok-XXXX)
REVERSED TOPOLOGY: dropping my own group and joining the customer group as a CLIENT,
while staying on Freebox 5 GHz ch 48
telling the customer what this phone can see: I can address "OnePlus..." at 1e:...
JOIN PLAN = GUEST_CONNECT
joining the customer group: "OnePlus Nord CE 2 Lite 5G" (1e:4f:f2:19:36:ce)
WI-FI DIRECT GROUP FORMED: role CLIENT ... p2p0=192.168.49.x
```

**The three things to check on the seller at that moment**, all at once:

- `wlan0 = 192.168.1.x` is still there, on the Freebox,
- `p2p0 = 192.168.49.x` exists,
- `provider: ... upstream Wi-Fi, validated` still says Wi-Fi.

Then the probe verdict from BOTH phones, and only if IP works, the signed
handshake, the contract, the VPN, INTERNET OK and Chrome.

**Whatever happens, copy the LAST P2P TEST RESULT block from both phones.**
It is at the end of the Wi-Fi Direct diagnostic and it survives cleanup:

```
--- LAST P2P TEST RESULT (kept after cleanup) ---
time / topology / role / group channel / home Wi-Fi channel / peer
association / membership / discovery stopped / local IP / peer IP
UDP sent / received / replies sent / replies received
TCP accepted / TCP connected / verdict / failure stage
```

This is an experiment, not a fix. It may fail, and a failure is still an
answer: it would mean the OUKITEL cannot carry a Wi-Fi Direct data path in
either role while it stays on its home Wi-Fi.

## 42. v0.9.24 the reversed topology, second attempt

Section 41 still applies. Two things it could not show last time must now
appear.

**On the BUYER, with the lab toggle on BUYER OWNS THE GROUP:**

```
TOPOLOGY = BUYER_GROUP_OWNER: the customer owns the Wi-Fi Direct group and the
provider joins it, keeping its home Wi-Fi
BUY decision: forcing Wi-Fi Direct because buyer owns the group
starting buyer-owned P2P group
WI-FI DIRECT GROUP FORMED: role GROUP_OWNER ... local 192.168.49.1
```

There must be **no `WIFI_REQUEST`** in that purchase. If there is one, the
buyer took the hotspot path and the experiment did not run.

**On the SELLER:**

```
TOPOLOGY = BUYER_GROUP_OWNER (asked by prok-XXXX)
REVERSED TOPOLOGY: joining customer group as CLIENT (dropping my own group), while staying on Freebox ...
telling the customer what this phone can see: I can address "OnePlus..." at 1e:...
JOIN PLAN = ...
WI-FI DIRECT GROUP FORMED: role CLIENT ... p2p0=192.168.49.x
```

and at that moment: `wlan0 = 192.168.1.x` still present, `p2p0` present, and
`provider: ... upstream Wi-Fi, validated`.

**Then cancel on the buyer, and check the seller:**

```
REVERSED TOPOLOGY session cleared for prok-XXXX (the customer cancelled: ...)
| still sharing: true, upstream Wi-Fi
```

After that line there must be NO further `telling the customer what this
phone can see`. If the line keeps coming, the v0.9.23 stale loop is back.

**Then buy again** from the same two phones without restarting anything: the
seller must go through `TOPOLOGY` and `REVERSED TOPOLOGY: joining` again from
scratch.

Only once both roles are really present does the probe verdict count:

```
LINK PROBE verdict: ...     (from BOTH phones)
```

Copy the LAST P2P TEST RESULT block from both phones whatever happens.

## 43. v0.9.25 the owner's group has to exist first

Section 42 still applies. The first proof is now only this, on the BUYER:

```
BUY decision: forcing Wi-Fi Direct because buyer owns the group
starting buyer-owned P2P group
createGroup attempt 1 accepted (default band), waiting for the group to form
group creation pending: formed=false is normal while CREATING_GROUP (attempt 1/3)
GROUP FORMED after createGroup attempt 1: role GROUP_OWNER
WI-FI DIRECT GROUP FORMED: role GROUP_OWNER ... local 192.168.49.1
GROUP CHANNEL: ...
local P2P address=192.168.49.1
keeping Wi-Fi Direct discovery alive: starting peer discovery for reversed admission
```

Between `createGroup ... accepted` and `GROUP FORMED` there must be **no**
`keeping Wi-Fi Direct discovery alive: the group is gone` and **no**
`asking prok-... whether its Wi-Fi Direct group is ready`.

If the group does not form, the buyer must show the retries and the specific
ending, not a peer-visibility one:

```
no group formed within 15s of createGroup attempt 1 being accepted: retrying group creation attempt 2/3
...
GROUP CREATE FAILED: Android accepted createGroup three times but no Wi-Fi Direct group formed
PURCHASE FAILED at stage GROUP_CREATE_FAIL
```

Only once `role GROUP_OWNER` and `192.168.49.1` are present on the buyer does
the seller side count:

```
TOPOLOGY = BUYER_GROUP_OWNER
REVERSED TOPOLOGY: joining customer group as CLIENT ... while staying on Freebox
WI-FI DIRECT GROUP FORMED: role CLIENT ... p2p0=192.168.49.x
```

with `wlan0 = 192.168.1.13` still present and the upstream still validated.
Then, and only then, the probe verdict from both phones.

Copy the LAST P2P TEST RESULT block from both phones whatever happens.

## 44. v0.9.26 nothing moves while the owner creates its group

Section 43 still applies, with one stricter rule for the window between
`createGroup ... accepted` and `GROUP FORMED` on the BUYER.

In that window the ONLY lines allowed are:

```
group creation pending: formed=false is normal while CREATING_GROUP
admission deferred: buyer-owned group is still being created (... remembered that "C1 Pro" can/cannot address me)
not starting discovery: group creation owns the radio (...)
no group formed within 15s ... retrying group creation attempt N/3
```

These must NOT appear in that window:

```
JOIN PLAN = ...
admission: ... -> ...
starting peer discovery from a clean state
INVITING ...
ASSOCIATION started ...
```

If any of them does, copy the lines with their timestamps: it means a caller
got past the gate.

After `GROUP FORMED`, the deferred report is used at once:

```
the group exists now: evaluating the visibility prok-... sent while it was being created
admission: the customer "C1 Pro" ... | group formed=true role=GROUP_OWNER ... -> GUEST_CONNECT | OWNER_INVITE | WAIT
```

The seller keeps its loop the whole time: `telling the customer what this
phone can see` every eight seconds, on the Freebox, upstream validated. That
is correct and must not stop.

## 45. v0.9.27 three REAL creation attempts, or a specific reason why not

Sections 43 and 44 still apply. This section is about counting honestly.

A **real attempt** is one of these, and nothing else:

```
createGroup attempt N accepted (...), waiting for the group to form
   ... fifteen seconds of only pending / deferred / owns-the-radio lines ...
no group formed within 15s of createGroup attempt N being accepted
```

or

```
createGroup attempt N refused: <a reason that is not BUSY>
```

Between real attempts the buyer must show the reset, and the framework must
be verified clean before the next one:

```
RESETTING_FRAMEWORK before attempt 2/3
reset: the half-made group was removed         (or: nothing to remove)
framework clean: starting GROUP_CREATE_ATTEMPT 2/3
```

BUSY lines are allowed and do NOT count:

```
Wi-Fi Direct framework still busy after previous creation; waiting before retry (...)
```

**The decision rule.** If the log shows three lines of the form
`createGroup attempt N accepted` (N = 1, 2, 3), each followed by a silent
fifteen seconds and `no group formed`, with a verified reset between them,
then this OnePlus does not create an autonomous Wi-Fi Direct group in this
configuration, and we stop patching this topology. Anything less than that
is not yet a verdict.

If instead it ends with `GROUP CREATE FAILED: GROUP_CREATE: the Wi-Fi Direct
framework stayed BUSY ...`, the framework never settled and the screen says
to wait a few seconds. Try once more after a minute before drawing anything.

Whatever happens, the buyer must NOT show "Le fournisseur est déjà occupé".
Copy the LAST P2P TEST RESULT block from both phones.

## 46. v0.10.0 Bluetooth bulk Internet

The exact proof condition, from the normal or the lab path.

Setup:
- SELLER OUKITEL: mobile data OFF, connected to the Freebox Wi-Fi, Bluetooth ON.
- BUYER OnePlus: mobile data OFF, NOT connected to the Freebox, Bluetooth ON.
- Both phones on 0.10.0, node running, Location on.

The lab path (Developer -> BT Lab):
1. SELLER: tap SELL. The card says it will serve over Bluetooth.
2. BUYER: tap BUY OVER BLUETOOTH. It picks the visible provider offer that
   advertises Bluetooth bulk.

The buyer log should read, in order:

```
BULK REQUEST -> prok-XXXX session ...
BULK OFFER from prok-XXXX: L2CAP PSM NNN at AA:BB:...
BULK CONNECTING ...
BULK SOCKET CONNECTED (client) ...
AUTH START / AUTH OK / BULK UP with prok-XXXX
BLUETOOTH BULK PROBE
  buyer -> seller: 1,048,576 B OK, ... KB/s
  seller -> buyer: 1,048,576 B OK, ... KB/s
  VERDICT: BIDIRECTIONAL
```

Only then: the contract, TUNNEL UP, the VPN prompt (accept it), CONNECTE,
and Chrome loading a normal HTTPS site.

**The first proof is the probe verdict, from both phones.** A socket that
connected is not a link that carries. If the verdict is anything but
BIDIRECTIONAL, copy the LAST lines and both `bluetooth bulk:` diagnostic
blocks; the screen will also say which direction failed.

**Claim rule.** Bluetooth Internet is solved only if, in that run, the seller
stayed on the Freebox with mobile data off, the buyer had no mobile data and
no Freebox connection, the probe was BIDIRECTIONAL, the VPN came up, DNS and
HTTPS worked, and Chrome loaded pages. Anything less is a partial result.

### Checklist for the v0.10.0 report

- The `BLUETOOTH BULK PROBE` block from both phones.
- Seller Wi-Fi before and during: `wlan0` on the Freebox, upstream validated.
- Buyer role CLIENT, VPN up, INTERNET OK, Chrome.
- The `bluetooth bulk:` diagnostic block from both phones whatever happens.

## 47. v0.10.1 the home-Wi-Fi seller stays on Wi-Fi and touches nothing else

Before the buyer connects, the SELLER OUKITEL (Freebox, mobile data off,
Bluetooth on, SELL on) must show:

```
SELLER ACCESS PATH = BLUETOOTH_BULK (...): serving over Bluetooth; staying on the home Wi-Fi, the Wi-Fi radio is not touched
provider: PROVIDER READY, upstream Wi-Fi, validated
```

and must NOT show any of these:

```
SHARE CHECK ...: testing the hotspot ...
SHARING BY WI-FI DIRECT ...
WI-FI DIRECT GROUP FORMED ...
```

The diagnostic must show `wlan0` on the Freebox and no `p2p` interface. If a
`SHARE CHECK ... testing` or any Wi-Fi Direct line appears under a normal
SELL, that is the bug returning.

Then run section 46 as before. The developer P2P lab still works when opened
explicitly, and only then.

### Checklist for the v0.10.1 report

- The `SELLER ACCESS PATH = BLUETOOTH_BULK` line on the seller.
- No hotspot probe, no Wi-Fi Direct line, no `p2p` interface before or during.
- Then the section 46 result: `VERDICT: BIDIRECTIONAL`, VPN, DNS, HTTPS, Chrome.

## 48. v0.10.2 the two-button Bluetooth test

Both phones: install the APK, open Developer -> BT Lab. Nothing else to
set up; the screen starts the node and asks for permissions by itself.

OUKITEL (seller): stay on the Freebox, mobile data off, Bluetooth on. The
screen must show `Internet source: Wi-Fi ✅` and `Bluetooth: Ready ✅`.
Tap START SHARING. It says `Waiting for another phone...`.

OnePlus (buyer): mobile data off, not on the Freebox, Bluetooth on. Wait
for `Seller found ✅`, tap CONNECT, then wait. The screen walks by itself:

```
Connecting...
Checking both directions...
Starting Internet...          (accept the VPN if the phone asks)
Testing Internet...
INTERNET WORKING ✅
```

The seller meanwhile shows `Phone connected ✅ / Testing connection...`,
then `Bluetooth connection works both ways ✅ / Internet sharing
starting...`, then `Sharing Internet ✅`.

On both phones tap COPY TEST RESULT and paste it here. The summary comes
first:

```
ProkNet Bluetooth Test

Phone role: BUYER
Bluetooth connected: YES
Authentication: YES
Buyer -> seller: PASS 262144/262144 B
Seller -> buyer: PASS 262144/262144 B
Contract: YES
VPN: YES
DNS: YES
HTTPS: YES
Internet: YES
```

then open Chrome on the buyer and load a page. A failure is one sentence
on the screen (`Connection test failed. Buyer -> seller was too slow.`)
and the summary shows the direction and the bytes, e.g. `Buyer -> seller:
PARTIAL 243712/262144 B`, `Failure stage: PROBE`.

What the log must show, for us: `PROBE BUYER_TO_SELLER`, then `received
262,144 / 262,144 B PASS ... confirming` on the seller, then `PROBE
SELLER_TO_BUYER`, then `PROBE COMPLETE` with `VERDICT: BIDIRECTIONAL`.
Direction 2 must never start before direction 1 is confirmed. If `bad
frame length 0` or `EOFException` still appear AFTER a clean sequential
probe, they get investigated next; if they only follow a timeout, they are
its consequence.

### Claim rule

Bluetooth Internet is claimed only when a copied BUYER summary shows
`Internet: YES` with both directions `PASS`, and Chrome loaded a page.

## 49. v0.11.0 the normal app, no Developer screen

This is the hardware regression of the consumer UI over the path proven
in section 48. Do not open Developer on either phone.

OUKITEL (seller): on the Freebox, mobile data off, Bluetooth on. Open
ProkNet -> Partager mon Internet -> keep the price -> Commencer le
partage. The screen must say "Vous partagez votre Internet", "Disponible
pour les personnes à proximité", and under it "Source : Wi-Fi (Freebox) ✅"
with the price. It must NOT show any warning about Wi-Fi, Location or the
hotspot.

OnePlus (buyer): mobile data off, not on the Freebox, Bluetooth on. Open
ProkNet -> Internet -> tap the offer (price, signal, "Wi-Fi") -> Connecter.
The screen walks by itself:

```
Connexion…
Vérification de la connexion…
Démarrage d'Internet…          (a sentence, CONTINUER, then Android's VPN prompt, first time only)
Internet connecté ✅
```

Then open Wikipedia in Chrome. The seller meanwhile shows "Quelqu'un
utilise votre Internet" with the data and the earnings.

Then tap Arrêter on the buyer, and on the seller Arrêter le partage. Run
it a second time: the VPN must start without asking.

If something fails, the buyer screen shows ONE sentence (for example "La
connexion à proximité est trop faible. Rapprochez les téléphones et
réessayez."). Report the sentence. The technical detail is under
Développeur -> Copy diagnostic, for us, not for you.

For us, the seller log at start must show `consumer start: removing any
stale Wi-Fi Direct group` and the diagnostic must list no `p2p-wlan0-0`
interface while the session is running.

### Claim rule

The normal UI is regression-proven when both phones reach "Internet
connecté ✅" / "Quelqu'un utilise votre Internet" with Wikipedia loaded,
twice, with no Developer screen opened.

## 50. v0.12.0 one tap

OUKITEL (seller): on the Freebox, mobile data off, Bluetooth on. Open
ProkNet -> Gagner -> COMMENCER À PARTAGER. It says "Disponible autour de
vous ✅" with "Source : Wi-Fi (Freebox) ✅".

OnePlus (buyer): mobile data OFF, not on the Freebox, Bluetooth on. Open
ProkNet. Tap the big button OBTENIR INTERNET. Wait. The card walks by
itself: "Recherche d'Internet…", "Recherche du meilleur Internet…",
"Internet trouvé ✅", "Connexion…", "Vérification de la connexion…",
"Démarrage d'Internet…" (the VPN sentence and Android's prompt the first
time), "Internet connecté ✅" with price, used, cost. Open Wikipedia.

Then open Carte on both phones and send a screenshot if anything looks
wrong. That is all.

If something fails, the card shows one sentence; tap ARRÊTER and try
again. For us: Développeur -> COPY COVERAGE shows the sources, the cells
and "last GET INTERNET decision ... why".

### Claim rule

The one-tap flow is regression-proven when the OnePlus reaches "Internet
connecté ✅" from OBTENIR INTERNET, with the OUKITEL chosen automatically
(the diagnostic says "cheapest validated reachable source" or "the last
one that worked"), and Wikipedia loads. The map is a foundation: it is
checked for honesty (no source shown that was never seen), not for
coverage.

## 51. v0.13.0 the request that wakes a provider (THE test)

**Put the two phones next to each other for about 30 seconds first.** Then,
on the OnePlus, Développeur -> COPY NETWORK must already say:

```
Nearby ProkNet phones: 1 (prok-24e480e6)
Last peer seen: 3 s ago
```

If it says 0 and "NOT IN RANGE now", the phones cannot hear each other yet
and the test cannot work: wait, or toggle Bluetooth off and on once and
wait 30 s more (v0.13.1 restarts the radio by itself after that).

OUKITEL: Freebox Wi-Fi ON, mobile data OFF, Bluetooth ON, sharing OFF.
Open ProkNet → Gagner → turn ON "Me prévenir quand quelqu'un cherche
Internet près de moi". Put the phone down.

OnePlus: mobile data OFF, not on the Freebox, Bluetooth ON. Open ProkNet,
tap the sphere. There is no seller, so the sphere goes RECHERCHE, then
after about 15 s DEMANDE, with "Demande envoyée aux téléphones ProkNet
autour de vous."

OUKITEL: a notification "Quelqu'un cherche Internet à proximité." with
PARTAGER. Tap PARTAGER. Nothing else.

OnePlus, without another tap: CONNEXION, then CONNECTÉ. Open Wikipedia.

That proves: offline request → local demand propagation → provider
activation → automatic seller discovery → automatic connection → real
Internet.

If the OUKITEL never gets the notification: Développeur → COPY NETWORK on
both phones. The OnePlus must show `REQUEST <id> created`, and `FORWARD
<id> gen 1 -> prok-<oukitel>`; the OUKITEL must show `REQUEST <id> gen 1
NETWORK_REQUESTED from prok-<oneplus>: NEW` and either `PROVIDER ACTIVATION
opportunity` or `not activating for <id>: <reason>` (the reason is the
fix).

Then tap ARRÊTER on the OnePlus and STOP sharing on the OUKITEL, and run it
once more.

## 52. v0.13.0 the brain, if a server is configured

Not required for section 51. If a Network Brain URL is set on both phones
(Développeur → BRAIN URL), COPY NETWORK must show, on the OUKITEL: `SYNC
(...)`, `SYNC ok`, `last success`, the availability sent while the
preference is ON, and after section 51 the request as `FULFILLED`. Mike
does not run any command; the brain is BUILT + TESTED LOCALLY until a
public HTTPS hostname exists.

### Claim rule

Provider activation is hardware-proven only when section 51 ends with
CONNECTÉ and Wikipedia without a second tap on the OnePlus. 3-phone carry
and relay stay unproven until phone #3 exists.

## 53. v0.13.1 the two fixed bugs

A fresh request never wears an old error. On the OnePlus: connect, then
tap ARRÊTER, then tap the sphere again. It must go RECHERCHE, then DEMANDE.
It must NOT show RÉESSAYER, "Connexion perdue" or "Rapprochez-vous du
fournisseur" before anything has been tried. A real failure still shows its
own sentence.

The radio recovers after Bluetooth comes back. With ProkNet running, turn
Bluetooth off on the OnePlus, wait ten seconds, turn it back on. Within
about 25 seconds the log (COPY NETWORK) must show `Bluetooth is back on:
the radio will be restarted once` and a recovery, and the two phones must
see each other again without restarting the app.

## 54. v0.13.2 the provider knows it could share

On the OUKITEL, on the Freebox with sharing OFF, Développeur -> COPY
NETWORK must show:

```
current phone Internet: WI-FI validated
potential seller path: BLUETOOTH_BULK
seller gateway running: NO
```

If it says `current phone Internet: NONE` while the phone is really on
Wi-Fi, that is the bug returning. `seller gateway running: NO` with a
validated Internet above it is correct and expected: the gateway starts
only when PARTAGER is tapped.

Then run section 51. The log must now show `PROVIDER ACTIVATION
opportunity for <id>` instead of `not activating for <id>: NO_INTERNET`.

## 55. v0.13.3 three cycles in a row (THE acceptance test)

One successful run is not acceptance. Setup as in section 51: OUKITEL on
the Freebox, mobile data OFF, Bluetooth ON, sharing OFF, "Me prévenir…" ON.
OnePlus: mobile data OFF, NOT on any Wi-Fi, Bluetooth ON. Side by side.

**Cycle 1.** OnePlus: tap the sphere → RECHERCHE → DEMANDE. OUKITEL: the
notification AND a card in Gagner. Tap PARTAGER. OnePlus, no more taps:
CONNEXION → CONNECTÉ. Open Wikipedia. Stop the buyer, stop the seller.

**Cycle 2, immediately.** Same thing. A new notification and card must
appear **at once** — there is no two-minute wait any more. It must connect
again.

**Cycle 3, the important one.** Start the request, then on the OUKITEL
**swipe the notification away without tapping it**. Open ProkNet → Gagner.
The demand card must still be there with PARTAGER. Tap it. The OnePlus must
connect. That proves the notification is not the source of truth.

## 56. v0.13.3 Bluetooth recovery, without restarting the app

After those cycles, on the OUKITEL: turn Bluetooth OFF, wait 10 seconds,
turn it ON. **Do not restart the app.** Wait about 30 seconds, then make a
new request from the OnePlus.

Expected: the OUKITEL is found again, the request is delivered, the
notification and the card appear, and there is NO "peer has no ProkNet
service". Then PARTAGER and Internet must work.

For us, COPY NETWORK on the OUKITEL must show the generation having moved:

```
BLE control plane:
  generation: 3
  GATT server: OPEN
  ProkNet service: ADDED
  advertising generation: 3
  scan generation: 3
  rebuilds: 2 (last: Bluetooth returned)
```

`ProkNet service: MISSING` together with an advertising generation equal to
the current one is the bug returning.

## 57. v0.13.3 background and notifications off

With the OUKITEL screen off and ProkNet not visible, send a request from
the OnePlus. Unlock the OUKITEL, open ProkNet → Gagner: the card must be
there. Then turn ProkNet's notifications off in Android settings and repeat:
no push, but the Gagner card must still appear, and COPY NETWORK must say
`suppressed reason: notification not shown (permission denied…)` with the
opportunity still active.

## 58. v0.14.0 a budget session

Same two phones, same setup as section 51 (OUKITEL on the Freebox, sharing
OFF, "Me prévenir…" ON; OnePlus with mobile data OFF and no Wi-Fi).

On the OUKITEL, open Gagner. There must be **no price to type**: it says
"ProkNet fixe automatiquement un prix rentable pour vous" with three
choices, Équilibré selected. Leave it.

On the OnePlus, open Internet. The budget card shows "50 CFA" selected.
Leave it. Go back and tap the sphere. The first time, ProkNet asks once:
"Budget maximum : 50 CFA — ProkNet ne dépensera jamais plus." Tap
CONTINUER.

Then the v0.13.3 flow runs unchanged: RECHERCHE → DEMANDE → the OUKITEL's
notification or Gagner card → PARTAGER → CONNEXION → CONNECTÉ. Open
Wikipedia and load a few pages — a few CFA is plenty, there is no need to
spend the budget.

Expected on the OnePlus while connected:

```
Vous avez dépensé 3 CFA sur votre budget de 50 CFA
```

and **never** a CFA/MB figure. Stop. The charge must be what was used, not
50 CFA.

Expected on the OUKITEL: "Vous avez gagné …" with a figure greater than
zero.

For us, COPY NETWORK on either phone must show the `pricing:` block:

```
pricing:
  buyer budget: 50.00 CFA | seller policy: BALANCED | my source: AUTHORIZED_HOME_WIFI
  my automatic rate: 2.11 CFA/MB internal, advertised 3 CFA/MB | my floor: 2.00 CFA/MB
  live contract: v2 BUDGET, budget 50.00 CFA, rate 3.00 CFA/MB, ceiling 16.66 MB, spent 3.00 CFA
```

`live contract: v2 BUDGET` with a ceiling that costs at most the budget is
the thing to check. A v1 legacy contract here would mean the budget path did
not run.

### Optional: the mobile-data seller

If the OUKITEL is switched to mobile data with sharing off, Gagner → "Mon
forfait" → for example 1 000 CFA for 2 000 Mo → COMMENCER. The advertised
price must be **higher** than on Wi-Fi, because the data now costs the
seller real money. That is the protection working.

## 59. v0.14.1 the budget session is boring (THE acceptance test)

v0.14.0 build 55 failed here: the probe passed both ways and the seller then
answered `contract rejected: malformed proposal`. This section is what makes
v0.14.1 stable, and it is not passed until all three parts pass **in one
sitting, without restarting either app**.

Setup as in section 58: OUKITEL on the Freebox Wi-Fi as the seller, OnePlus
as the buyer, budget 50 CFA.

### 59a. Three budget sessions in a row

Do the whole buy-and-browse flow **three times**, stopping properly between
each one:

1. OnePlus: GET INTERNET -> connect to the OUKITEL -> open Wikipedia, read
   two or three pages -> stop.
2. Wait for both phones to show they are idle again.
3. Repeat, twice more.

Every one of the three must reach Internet. The second and third must not be
slower or less reliable than the first, and neither phone may need a restart.

After each session the buyer must read

```
Vous avez dépensé N CFA sur votre budget de 50 CFA
```

with N small and **growing** across the three sessions only because you used
more, never jumping to 50. The seller must read "Vous avez gagné …" with a
figure above zero each time.

COPY NETWORK on the OUKITEL must show, after each session, a `contract:`
block ending in

```
  decode: PASS | signature: PASS | economic admission: PASS
  reject reason: accepted
```

If `reject reason:` ever says anything else, copy the whole block: it now
names exactly which of the six steps failed and at which contract version.

### 59b. Bluetooth failure injection

During the **second** session, while a page is loading, switch Bluetooth off
on the OnePlus, wait ten seconds, and switch it back on. Do not touch the
app. Then tap GET INTERNET again.

Expected: the session ends with an honest error, not a frozen screen, and
the next tap works. The buyer must not carry the old session's spend into
the new one, and the seller must not still believe it has a live contract.
COPY NETWORK on the buyer must show `buyer saw rejection:` empty or a real
reason, never a stale one from a previous attempt.

### 59c. Background

During the **third** session, put the buyer app in the background (home
button), leave the screen off for two minutes, then come back. Internet must
still work, or fail with a clear message. The amount spent must be the
amount actually used during those two minutes, not the budget.

### What would make this a FAIL

- Any "malformed proposal", "malformed contract" or "malformed signed
  envelope" message at all.
- A `live contract:` line showing `v1` instead of `v2 BUDGET`.
- A spend equal to the budget after a short session.
- A second or third session that needs an app restart.
- Any consumer screen showing a CFA-per-megabyte figure. It is allowed only
  in Developer, in COPY NETWORK, and in the detail of an old v1 session.

## 60. v0.14.2 stopping is boring (THE acceptance test)

v0.14.1 connects and bills correctly on the phones. This section is only about
what happens when you press Stop. Nothing in the connection path changed, so if
anything in section 59 behaves differently, that is a regression and a FAIL.

Same setup as section 59. OUKITEL on the Freebox with mobile data off and
Équilibré; OnePlus with Wi-Fi and mobile off, budget 50 CFA.

### 60a. A short paid session must not be free

Connect normally. Open Wikipedia and read for about **5 to 10 seconds only**,
which is deliberately less than the 30 second checkpoint interval. Press Stop.

Expected on the OnePlus:

```
Vous avez dépensé N CFA sur votre budget de 50 CFA
```

with **N greater than zero** and far below 50. The OUKITEL must show "Vous avez
gagné" with a figure above zero.

This is the whole point of the release. Before v0.14.2 this session settled at
zero because the closing checkpoint never arrived.

COPY NETWORK on the OnePlus must show:

```
session shutdown:
  state: DISCONNECTED
  reason: user stopped
  final checkpoint: PASS
  final settlement: N CFA
  bulk close: NORMAL
  stale callbacks ignored: 0
```

`final checkpoint: PASS` is the line to check. `timeout` or `unavailable` means
the closing figure did not come back, and the amount fell back to the last
periodic one.

### 60b. No invented failure

Read the diagnostic after that Stop. These must **not** appear:

- `BULK FAILED at IDLE`
- `no receipt over bt-bulk within 15s`
- `session failed:` anything, when you simply pressed Stop

A normal stop is not an error and must leave no error behind.

### 60c. Immediate reconnect, three times

Without touching Bluetooth and without restarting either app, tap GET INTERNET
again straight away. Expected each time: CONTRACT AGREED, SESSION OK, INTERNET
OK. Do it three times, browsing a few seconds each time and stopping.

Each session must bill something. No session may need a restart.

### 60d. Stop pressed twice

During one session, press Stop and then press it again immediately. Expected:
one settlement, one amount on screen, and the phone still able to reconnect. The
amount must not double.

### 60e. Bluetooth pulled during the stop

During a later session, press Stop and switch Bluetooth **off within a second**,
then back on after ten seconds. Expected: the phone settles on the last figure
both sides signed, returns to idle without hanging, and reconnects normally once
Bluetooth is back. COPY NETWORK may show `final checkpoint: unavailable` here,
which is honest and correct.

### 60f. The seller stops first

During a session, press the seller's stop-sharing on the OUKITEL instead. Both
phones must converge to a clean state, the buyer must not be left with a VPN up
and no tunnel behind it, and the next session must work.

### What would make this a FAIL

- A short session settling at 0 CFA.
- `BULK FAILED` or a receipt error after an ordinary Stop.
- A doubled charge from pressing Stop twice.
- Any session needing an app restart or a Bluetooth toggle to start again.
- Any regression in section 59.

## 61. v0.15.0 the seller stops (THE stability gate)

This must pass before the wallet is worth testing at all. It is the seller-side
twin of section 60.

Same setup: OUKITEL seller on the Freebox, OnePlus buyer, budget 50 CFA.

### 61a. A short session the seller ends

Connect normally. Browse Wikipedia for **5 to 10 seconds only**. Then press
stop-sharing on the **OUKITEL**, not on the OnePlus.

Expected:

- The OUKITEL shows "Vous avez gagné" with a figure **above zero**.
- The OnePlus shows a spend **above zero** and below 50 CFA.
- The two figures agree: the buyer's spend is the seller's earning plus the
  Prok fee.
- The OnePlus says **"Le fournisseur a arrêté le partage."** and not a
  connection error. Its VPN must be off.

COPY NETWORK on the OUKITEL must show `final checkpoint: PASS`.

Before v0.15.0 this session earned the seller nothing.

### 61b. Immediate reconnect

Turn sharing back on and reconnect from the OnePlus at once, without
restarting either app. Then do 61a again. Both must work.

## 62. v0.15.0 the wallet

Only after section 61 passes.

### 62a. The obligation appears

Run one short paid session and stop it. On both phones open **Activité**.

Expected on the OnePlus: **À payer** shows what the session cost.
Expected on the OUKITEL: **À recevoir** shows the seller's share.

The note under the figures must say Prok does not hold your money. There must
be no "Solde" anywhere.

### 62b. Where the seller is paid

On the OUKITEL, Activité → **Recevoir avec** → enter the Mobile Money number
and choose MTN or Airtel. It must then show the operator and a **masked**
number, never the whole one.

### 62c. Mock payment (developer only)

On the OnePlus, open Developer and **long-press COPY NETWORK**. It must say
mock payments are on. This is the only way to enable them.

Back in Activité, tap **PAYER**. Choose the simulated rail.

Expected: the buyer shows Payé, the seller shows Reçu, and the amounts match.
Restart **both** apps. The state must still be settled, with no duplicate.

### 62d. Manual pilot payment

With mock payments off, tap PAYER and choose **Paiement direct**. It must show
the seller's masked number and ask for a reference.

Enter anything reference-shaped. Expected: **"Paiement en attente de
vérification."** It must NOT say paid. This is the point: a reference somebody
typed is a claim, not proof.

### 62e. Three small sessions, one payment

Run three short sessions with the same seller without paying in between. À
payer must be the **sum** of the three, and one PAYER should clear all of them.

### 62f. The credit limit

Keep running paid sessions without paying. Once the unpaid total reaches the
limit, GET INTERNET must refuse **before** any Bluetooth setup, with
"Réglez N CFA pour continuer". It must refuse quickly, with no Bluetooth
activity at all.

A free source must still work at that moment.

### What would make this a FAIL

- A seller-stopped short session earning zero.
- The buyer's spend and the seller's earning not agreeing.
- "Le fournisseur a arrêté le partage." replaced by a connection error.
- The word "Solde" anywhere, or any claim that a payment is guaranteed.
- A typed reference shown as paid.
- A duplicate obligation or a doubled amount after restarting the apps.
- The credit limit refusing only after Bluetooth setup rather than before.

## 63. v0.15.1 the Wallet looks like a product

Sections 61 and 62 covered whether the money is *correct*. This one is about
whether it is *usable*. Judge it as a person, not as an engineer.

### 63a. Three small sessions, one payment

With the same seller, run **three** short paid sessions, stopping each one,
without paying in between.

Open **Activité** and tap **Wallet**.

Expected:

- One summary card. **À payer** is the strongest figure, because you owe money.
- One action card: "Paiement à effectuer", naming the seller as **Prok XXXX**
  and saying **3 sessions sont regroupées dans ce paiement.**
- Exactly one button. Not three, not zero.
- Under the figures: "Prok ne détient pas votre argent."
- History grouped under **Aujourd'hui**, each row showing what it was, who
  with, the amount, and **En attente**.

On the OUKITEL the same screen must lead with **À recevoir** instead.

### 63b. Nothing technical on screen

Read the whole Wallet screen. None of these may appear anywhere on it:

- `PENDING`, `PAYMENT_SEEN`, `CONFIRMED` or any other status word in capitals
- a settlement id, a session id or a checkpoint hash
- a full 32-character `prok-` identity
- any price per megabyte
- the word **Solde**

Tap one history row. The detail must be in plain French: statut, avec, budget
maximum, utilisé, frais Prok, le vendeur reçoit. The technical identifiers are
allowed **only** behind "Détails techniques".

### 63c. Mock payment of the batch

Developer → long-press COPY NETWORK to enable simulated payments. Back in
Wallet, tap the pay button and choose the simulated rail.

Expected: all three sessions become **Payé ✓** together, the OUKITEL shows
**Reçu ✓**, and À payer drops to zero with "Tout est à jour".

Restart both apps. The state must be unchanged. Repeat the same mock payment:
nothing may duplicate.

### 63d. Manual reference

Turn simulated payments off. Tap pay and choose **Paiement direct**.

Expected: the seller's **masked** number, an instruction, and a reference box.
After entering a reference, the confirmation must say **"En attente de
vérification. Nous ne marquerons pas ce paiement comme reçu avant
vérification."** and the row must read **À vérifier**, never Payé.

Try the same reference again for a different seller: **"Cette référence est
déjà utilisée."**

### 63e. Receiving, and privacy

On the OUKITEL, Wallet → the receiving card. Before setup it must invite you to
configure. After setup it must show the operator and a masked number, plus one
sentence: the number is never broadcast to nearby phones.

### 63f. Empty and clean states

On a phone with no history, Wallet must show a calm empty state, not a blank
panel. Once everything is paid it must say **Tout est à jour** with no button.

### 63g. Home and Gagner

Home may show at most **one** money line when something is owed, and tapping it
opens the Wallet. Gagner shows the earnings figure and a **Voir le Wallet**
link, and must NOT repeat the transaction history.

### What would make this a FAIL

- More than one primary button on the Wallet screen.
- Any technical identifier or status word visible without opening the advanced
  sheet.
- A typed reference shown as paid.
- The same reference silently accepted twice.
- Home or Gagner turning into a second accounting screen.
- Any regression in sections 59 to 62.

## 64. v0.15.2 the two redesigned screens

No new capability here, so this is a reading test. Judge it as a person who has
never used the app, not as an engineer. Anything you have to puzzle over is a
failure, and so is anything that stopped working.

### 64a. Gagner, before sharing

Open **Gagner** with Bluetooth on and the phone on Wi-Fi.

Expected: **three** cards, in this order.

1. A card saying **Partagez votre Internet**, one sentence about earning money,
   one button **Commencer**, and one quiet line underneath naming your Wi-Fi and
   roughly what you would earn.
2. **Gagné aujourd'hui** with one large figure.
3. One row: **Réglages du partage**, with a summary such as "Équilibré" and a
   chevron.

There must be **no** price chips, no number fields and no switches visible
before you open Réglages. Count the buttons on the screen: there should be one.

### 64b. Réglages still holds everything

Tap **Réglages du partage**. It must expand to show the three price choices
with a sentence explaining the selected one, "Mon forfait" with its two fields
and save button, the notification switch, the coverage switch and the relay
switch.

Change the price choice. The summary line on the Réglages row must follow.
Enter a bundle and save it. Toggle each switch. **Every one of these must still
work exactly as before** — nothing was removed, only moved.

Tap the row again to fold it away.

### 64c. Gagner, while sharing

Tap **Commencer**.

Expected: the same card now reads **Vous partagez**, the dot turns amber, three
figures appear inside the card (people connected, shared, earned), and the
button becomes **Arrêter le partage** in a calm grey, not red.

Connect the OnePlus. The title must become **Quelqu'un utilise votre Internet**,
the dot green, and the people count 1.

Tap **Arrêter le partage**. It must stop, exactly as the old button did.

### 64d. Activité is only a history

Open **Activité**.

Expected: a title, then your sessions grouped under **Aujourd'hui** / **Hier**,
each row showing what it was, who with, the amount and its state. Then a clearly
separate **Compte** section.

There must be **no** money boxes, no "à payer" figures, no payment button and no
receiving-method card on this screen. All of that is under **Wallet**. If you
see the same information on both, that is the bug this release fixed coming
back.

Tap a row: the session detail must still open, as before.

### 64e. The Wallet is untouched

Tap **Wallet**. Everything from section 63 must still be there and still work.

### 64f. Nothing lost

Walk the whole app once: get Internet, share Internet, the map, a payment, the
account settings, Developer. Everything that worked in v0.15.1 must still work.

### What would make this a FAIL

- More than one button visible on Gagner before opening Réglages.
- Any control that used to exist and now cannot be reached.
- Activité and Wallet showing the same money figures.
- A red "stop" block, or a subtitle running to two sentences.
- The word "clients" anywhere a person can see.

## 65. v0.15.3 settlement reaches the server, and survives not reaching it

The point of this release is that a session settles correctly whether or not
anything else in the world is available. Test the offline path first: it is
the one that matters.

### 65a. A normal session, unchanged

Run a short paid session and stop it. Everything from sections 61 to 63 must
behave exactly as before: both phones agree the figure, the Wallet shows the
obligation, the Internet path is untouched.

### 65b. Settlement with no server at all

Leave the Brain URL empty, or point it at something unreachable.

Run another paid session. Stop it.

Expected:

- the session works normally;
- the Wallet shows the obligation;
- **no error reaches the user** — not a toast, not a red line, nothing.

COPY NETWORK may show `server verification: not configured (local mode)` or a
pending count. That is the only place any of this may appear.

This is the critical case. ProkNet is offline-first: two phones settling
between themselves must never depend on a third party.

### 65c. The server catches up

Set a reachable Brain URL. Wait for the next sync, or trigger one.

Expected: the stored evidence uploads and the server verifies it. Open the
transaction in the Wallet, then **Détails techniques**: "Vérification serveur"
must read **Vérifié**.

Nothing on the consumer cards may change. They said the truth before and they
say the truth now.

### 65d. Restart before sync

Finish a session with the server unreachable. **Force-stop both apps.** Make
the server reachable again and reopen them.

Expected: the saved evidence uploads without the session being re-run. The
evidence was read back from the database, not from memory.

### 65e. Both phones report

After a session both phones have reported, the server must hold **one**
settlement with both parties recorded. Check the server, not the phones:

```
GET /v1/settlements/<id>
```

The two reports must agree, and there must not be two rows.

### 65f. Wallet organisation

Open **Activité**. Expected: history, then **Compte**.

Switch to **Wallet**. Expected: the summary, one action, the receiving method
and the history — and **no Compte, no network card, no Developer button
underneath**. Scroll to the bottom to be sure.

Switch back. Compte must reappear under Activité.

### What would make this a FAIL

- Any consumer-visible error when the server is unreachable.
- A session that will not settle locally without a server.
- Evidence lost across a restart.
- Two settlement rows for one session.
- Account, network or Developer cards visible under the Wallet.
- Any regression in sections 59 to 64.

## 66. v0.16.0 paying at a kiosk, verified automatically

This is the milestone that touches real money habits, so test it as a person in
Brazzaville would, not as an engineer.

You need both phones plus **one real Mobile Money transfer**, or a message that
looks like one arriving on the seller's phone.

### 66a. The seller gets ready

On the OUKITEL: Gagner -> Réglages du partage.

Set a number under **Recevoir les paiements** (MTN or Airtel). It must then show
the operator and a **masked** number.

Under **Vérification automatique**, tap ACTIVER. Android's own notification
access screen must open. Grant it to ProkNet. Come back: it must read
**Activée ✓**.

If you do not grant it, the line must say the verification needs enabling, and
free sharing must still work.

### 66b. A debt is created

Run a short paid session from the OnePlus and stop it. The OnePlus Wallet must
show **À payer** with a small amount.

### 66c. The buyer is told what to do, and nothing more

On the OnePlus: Wallet -> PAYER.

Expected: the amount, the operator, the seller's masked number, and an
instruction saying you may use your usual Mobile Money method **or a kiosk**,
and that ProkNet will detect the reception automatically.

The only button is **J'AI COMPRIS**. There must be **no** button anywhere that
says you have paid. Tap it: the screen must say the payment is awaited.

### 66d. The real payment

Send exactly that amount to the seller's number, the ordinary way: a kiosk, or
your own Mobile Money app. **Do not tell ProkNet anything.** Do not type a
reference. There is nowhere to type one.

Expected on the OUKITEL, within seconds of the operator's message arriving:

- the amount appears as received;
- the obligation becomes **Reçu ✓**;
- no button was pressed by anybody.

Expected on the OnePlus once the receipt reaches it: **Payé ✓**, and À payer
back to zero.

COPY NETWORK on the OUKITEL should show the payment line with the source and
the outcome.

### 66e. The wrong amount does not clear anything

Create another debt. Start a payment. Send a **different** amount.

Expected: nothing is cleared. The debt stands, and the buyer may try again.

### 66f. A message from the wrong place is ignored

With a payment expected, have any other app post a notification saying money was
received, or send yourself an ordinary SMS containing the amount from a normal
phone number.

Expected: nothing is cleared. Only the default SMS application may speak for the
operator.

### 66g. The credit limit

With a debt outstanding and unpaid, tap GET INTERNET on the OnePlus.

Expected: refused **before any Bluetooth activity at all**, with
"Réglez N CFA pour continuer". It must refuse instantly.

A free source must still work at that moment.

Then pay properly and confirm a paid session works again.

### 66h. Nothing else changed

Walk the app once: get Internet, share Internet, map, Wallet, Activité, Compte,
Gagner. Everything from v0.15.x must behave as before.

### What would make this a FAIL

- Being asked for a transaction reference anywhere.
- Any button that lets the buyer or the seller declare a payment.
- A debt cleared by a message from an app that is not the default SMS app.
- A debt cleared by the wrong amount.
- The credit limit refusing only after Bluetooth setup.
- The seller's full phone number visible anywhere.
- Any regression in sections 59 to 65.

## 67. v0.16.1 the whole payment loop, on two phones

This is the milestone. It is not complete because the seller recognised an SMS;
it is complete when the **buyer's debt clears by itself**.

Setup. OUKITEL is the seller: Internet upstream working, Mobile Money number
set under Gagner -> Réglages du partage, notification access granted, the line
reading **Activée ✓**. OnePlus is the buyer.

### 67a. A debt, and the buyer learns where to pay

Run a short paid session from the OnePlus and stop it.

Open the OnePlus Wallet and tap **PAYER**. It must show the seller's operator
and a **masked** number. If it says the provider has not indicated where to be
paid, the destination did not cross: that is a failure, not a fallback, and
there must be no option to type a reference instead.

### 67b. The seller is told, and says so

Tap **J'AI COMPRIS**.

Expected: within a second or two the screen says **La vérification automatique
est prête.** That sentence means the seller accepted the expectation. If it
says **Préparation du paiement…**, keep the phones near each other a moment;
it must resolve.

COPY NETWORK on the OUKITEL should show an accepted window with the amount.

### 67c. Pay normally, touch nothing

Send exactly that amount to the seller's number the ordinary way: a kiosk, or
your own Mobile Money app.

**Press nothing on either phone.** There is no "I have paid" button, and there
is nothing to press on the seller.

Expected on the OUKITEL, seconds after the operator's message arrives: the
payment is recognised and the obligation reads **Reçu ✓**.

Expected on the OnePlus: **Payé ✓**, and À payer back to zero, with nobody
having touched it.

That last step is the milestone. A seller-side detection alone is not a pass.

### 67d. Paid Internet works again immediately

On the OnePlus, tap GET INTERNET. A paid session must start normally, because
the debt that was blocking it is gone.

### 67e. The buyer walks away first

Repeat 67a and 67b, then **carry the OnePlus out of Bluetooth range** before
paying. Pay from wherever you are.

Expected: the OUKITEL detects and signs the receipt while alone. Bring the
phones back together. The OnePlus must clear the debt when they meet, without
anybody re-running anything.

### 67f. Two buyers, one amount

If a third phone is available, have both buyers owe the same amount and both
tap PAYER. The second must read **Un paiement du même montant est déjà en
cours.** Different amounts must both be accepted.

### 67g. Privacy

With **no** payment outstanding, send an ordinary SMS to the seller's phone.
COPY NETWORK must show no candidate at all: the message was never read.

### What would make this a FAIL

- The buyer's debt not clearing by itself.
- Being offered a box to type a transaction reference.
- "La vérification automatique est prête" appearing when the seller never
  accepted.
- A notification being examined with no payment outstanding.
- Any regression in sections 59 to 66.

## 68. v0.16.2 the same payment, through the Brain

Section 67 proved the loop when the two phones can see each other. This proves
it when they cannot, and proves that a reinstall does not erase a debt.

**Prerequisite:** a reachable Brain. Start `server/brain/app.py` on the VPS and
put its URL into both phones: COPY NETWORK -> the brain URL box (empty = off).
Both phones must show `server: <url>` in COPY NETWORK. Without a Brain this
section cannot run; the phones are then in local-only mode and section 67 is
the test that applies.

### 68a. The buyer never meets the seller again

Run a short paid session, stop it, and take the OnePlus **out of Bluetooth
range and keep it there** for the whole of 68a.

On the OnePlus, open the Wallet and tap **PAYER**.

Expected: it shows the seller's operator and a masked number, even though the
seller is nowhere near. The destination came from the Brain as the seller's own
signed claim.

Tap **J'AI COMPRIS**. Within a sync cycle or two the OnePlus must reach **La
vérification automatique est prête.** The OUKITEL collected the expectation
from the Brain and answered it, with the phones still apart.

Pay the amount the ordinary way. Press nothing.

Expected on the OUKITEL: the payment is recognised, the obligation reads
**Reçu ✓**. Expected on the OnePlus, still out of range: **Payé ✓** and À payer
back to zero.

That is the section. If the OnePlus needs to meet the seller again, it failed.

### 68b. Nothing happens twice

Bring the phones back together and leave them a minute.

Expected: nothing changes. À payer stays at zero, the payment count does not go
up, and no second obligation appears. The same signed object arriving twice —
once from the Brain, once over Bluetooth — is one payment.

COPY NETWORK on either phone shows a `payment sync:` line with its last run.

### 68c. A debt survives a reinstall

Run a paid session from the OnePlus and stop it, so it owes money. Do **not**
pay.

Uninstall ProkNet from the OnePlus. Install it again. It now has a brand new
identity and an empty database.

Tap GET INTERNET and try to buy.

Expected: refused, with **Réglez <the real amount> pour continuer** — the true
amount, not 0 F. The Brain recognised the phone and told it what it still owes.
The old identity's debt followed the phone, not the install.

Now pay that debt from the new install and buy again: it must work.

### 68d. Without the Brain, nothing got worse

Clear the brain URL on both phones (empty = off). Re-run section 67 end to end.

Expected: identical behaviour to v0.16.1. The local path never waits for a
server and never needed one.

### 68e. Privacy did not change

With no payment outstanding, send an ordinary SMS to the seller. COPY NETWORK
must still show no candidate. Nothing about the Brain changed what is read.

### What would make this a FAIL

- Needing the phones to meet to finish a payment in 68a.
- A debt paid once being counted twice in 68b.
- "Réglez 0 F" after a reinstall in 68c.
- Any regression in section 67 when the Brain is off.
- The Brain being able to show one phone another phone's payments or wallet.

## 69. v0.16.3 the four holds

Sections 67 and 68 still apply unchanged. This one covers only what v0.16.3 added.

### 69a. An Airtel seller can be paid from far away

On the OUKITEL, set the receiving number to an **Airtel** number under Gagner ->
Réglages du partage. Run a short paid session from the OnePlus, stop it, and take
the OnePlus out of Bluetooth range.

On the OnePlus, open the Wallet and tap PAYER.

Expected: it shows **Airtel** and the masked Airtel number. Before v0.16.3 this said
the provider had not indicated where to be paid, however long you waited.

Repeat with an MTN number to confirm nothing regressed.

### 69b. Changing operator does not strand a payment

With a payment outstanding, change the receiving number on the OUKITEL from MTN to
Airtel (or back).

Expected, within the next ten minutes: the buyer is still shown the OLD number. A
transfer already on its way must still land somewhere valid. After ten minutes the
new one appears, and the old one never comes back.

### 69c. New wording without a new APK

This needs the Brain and the admin key. On the VPS:

```powershell
$env:PROK_CONFIG_PRIVATE_KEY_FILE = "C:\ProkNetKeys\receipt_rules_config_key.pem"
python -m brain.publish_rules rules.json --db C:\ProkNetBrain\brain.db
```

with a `rules.json` adding whatever wording you want to test to `credit`.

Then, on the OUKITEL, COPY NETWORK and wait for a sync.

Expected: nothing visible changes and nothing breaks. Send yourself a message using
the new wording while a payment is outstanding; it must now be recognised. The old
wording must keep working.

**FAIL** if a payment stops being recognised after a configuration is published.
Adding words must never take any away.

### 69d. Nothing readable by a stranger

This one is for the VPS, not the phones. With the Brain running:

```
curl https://<brain>/v1/settlements/<any id from COPY NETWORK>
```

Expected: `401`. Not the amount, not the two parties, not the audit trail.

### 69e. No regression with the Brain off

Clear the brain URL on both phones and re-run section 67 end to end.

Expected: identical behaviour. The local path never waits for a server.

### What would make this a FAIL

- An Airtel seller still unreachable in 69a.
- The new number being used immediately in 69b.
- Published wording breaking wording that already worked in 69c.
- Anything but 401 in 69d.
- Any regression in sections 59 to 68.
