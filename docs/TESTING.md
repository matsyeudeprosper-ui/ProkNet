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
