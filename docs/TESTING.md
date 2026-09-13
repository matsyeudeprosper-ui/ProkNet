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

## 7. Milestone 2A (v0.2): queued delayed delivery

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
