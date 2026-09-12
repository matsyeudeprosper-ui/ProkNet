# ProkNet Lab v0.1 - Testing on phones

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
7. Phone A shows the message in *Messages* as `-> prok-... [sending]`, then `[sent]`.
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

## 6. Test checklist for the milestone report

- [ ] A sees B, B sees A (both lists non-empty)
- [ ] A -> B text arrives and shows `[received]` on B, `[sent]` on A
- [ ] B -> A works too
- [ ] a 300-character message arrives intact (tests the long-write path)
- [ ] Stop then Start on both phones, discovery recovers
- [ ] close and reopen the app: identity unchanged, old messages still listed
- [ ] walk 10-20 m apart: peer expires from the list after ~25 s, comes back when close
