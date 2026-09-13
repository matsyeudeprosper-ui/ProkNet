# CLAUDE_REPORT - Milestone 2A: queued delayed delivery (ProkNet Lab v0.2.0)

Date: 2026-09-12
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, released, NOT yet tested on real phones** (Mike has the APK)

Milestone 1 (v0.1.0) passed on two real phones on 2026-09-12: discovery,
both-direction messages, 300-character message, persistent identity and
storage, Bluetooth disappearance/reappearance. Its report is in the git
history at commit `4e394d7`.

## 1. What I built

Exactly the 2A list, Kotlin only, no Python:

| Requirement | Done | How |
|---|---|---|
| Message stored as pending when peer unavailable | yes | every Send goes through `DeliveryQueue`; a row with status `pending` in SQLite |
| Auto-retry when the peer reappears, no second Send | yes | scanner diff detects a peer coming back into range, clears its backoff, pumps immediately |
| States pending / sending / delivered / failed / expired | yes | `MsgStatus`, shown in the message list as `[pending try 3]`, `[delivered]`, `[failed ...]` |
| Pending survives app restart | yes | SQLite; on start anything stuck in `sending` returns to `pending` |
| Delivery receipt, not just a BLE write ack | yes | new RECEIPT characteristic read after the write; only `ACCEPTED`/`DUPLICATE` with the matching message ID counts |
| No duplicate delivery | yes | unique (msg_id, direction) index on the receiver; retries reuse the same message ID; receipt `DUPLICATE` closes the loop |
| Clear logs for queue, retry, receipt, delivery | yes | `QUEUE:` ENQUEUED / ATTEMPT / RETRY LATER / DELIVERED / FAILED / EXPIRED; `GATT-C:` reading RECEIPT / SEND DELIVERED; `GATT-S:` receipt ACCEPTED/DUPLICATE |
| UI stays simple | yes | one new **Retry** button, peers marked `NOT IN RANGE`, `queue N` in the status line |

Not added, as instructed: multi-hop, background service, Wi-Fi Direct,
Internet sharing, encryption, wallet, payments.

## 2. Architecture / technology choices and why

**Receipt = read-back, not notification.** After the INBOX write is acked,
the sender reads a RECEIPT characteristic (`7a0c0004-...`) whose value is
`[version][status][msgId x8]`, kept per connected central on the receiver.
This needs no descriptor / indication setup and no extra round trip beyond
one read inside the same connection. The receiver fills the receipt BEFORE
sending the write response, so the read can never see a stale value.

**Queue lives in SQLite, not in memory.** `messages` gained `attempts`,
`next_attempt`, `last_error`, `delivered_at`. Restart-safety came for free.
Schema v1 -> v2 migration keeps Mike's existing messages (`sent` becomes
`delivered`).

**Retry policy.** Transport failure or missing receipt: back to `pending`
with backoff 5 s doubling to 60 s; a peer reappearing resets the backoff for
its messages. Receiver says REJECTED: `failed`, no retry. 50 attempts:
`failed`. Older than 48 h: `expired`. One attempt in flight at a time.

**Known peers table** so a peer that is off can still be selected and
queued for. The BLE address used for an attempt always comes from the live
scan, never from the table, because Android rotates addresses.

**Duplicate handling has two layers.** The receiver's unique index makes a
second copy impossible to store; the DUPLICATE receipt lets the sender mark
the message delivered even when the first attempt's receipt was lost.

Full detail: `docs/ARCHITECTURE.md`, sections "Delivery receipt" and
"Delivery queue".

## 3. Files / components added or changed

```
app/src/main/java/net/prok/proknet/
  ble/DeliveryQueue.kt     NEW  queue state machine, backoff, reappearance retry, expiry
  ble/GattServerNode.kt    RECEIPT characteristic; per-central receipts; handle-before-respond
  ble/BleSender.kt         reads RECEIPT after write; returns DeliveryResult
  ble/BleConstants.kt      RECEIPT UUID + status codes, queue tuning, DeliveryResult, Peer.inRange
  ble/ProkNetNode.kt       owns the queue; merges visible + known peers; detects reappearance
  core/MessageStore.kt     schema v2, MsgStatus, pending/retry/expire queries, peers table
  ui/MainActivity.kt       Retry button, state display, NOT IN RANGE peers, queue in diagnostics
app/src/main/res/layout/activity_main.xml   Retry button
app/build.gradle.kts       versionCode 2, versionName 0.2.0
README.md, docs/ARCHITECTURE.md, docs/TESTING.md (section 7), CLAUDE_REPORT.md
```

## 4. Exact APK path

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.2.0
Build 2: 0.86 MB, SHA256 `c499a1da93f922b90446dc4c457e23e9f0bda1f837da0d9e69cc37e2c14af860`,
versionCode 2, versionName 0.2.0, same package and debug key as v0.1 (installs
over it, data kept).

## 5. Exact build command

```
powershell -ExecutionPolicy Bypass -File C:\Projects\ProkNet\build.ps1
```
Unchanged from milestone 1. About 65 s per build.

## 6. What Mike needs to do on his phones

Install v0.2.0 over v0.1 on **both** phones (both must be on v0.2, the
receipt characteristic does not exist in v0.1). Then `docs/TESTING.md`
section 7. The five tests, in one breath:

1. Normal send with B in range: A shows `[delivered]`, A's log has
   `RECEIPT accepted`, B's log has `receipt ACCEPTED`.
2. Stop B, wait until A lists it as `NOT IN RANGE`, send two messages from A
   (`[pending]`, status `queue 2`), start B: both arrive by themselves, in order.
3. With B off, send from A, kill A completely, reopen, Start: message still
   `[pending]`; start B: delivered.
4. Burst of five with B walking out of range and back: each text exactly once on B.
5. Retry button with B off: nothing breaks.

Copy log from both phones after test 2 and test 4 is what I most want to see.

## 7. Known limitations

- Queue only carries this phone's OWN messages. Carrying other people's
  packets (real STORE-CARRY-FORWARD) is a later milestone.
- App must be open on both phones (no background service). A pending message
  is only delivered while A's app is open and sees B.
- `failed` (50 attempts) and `expired` (48 h) are implemented but not
  practical to test by hand; they are logged.
- The receipt proves the receiver STORED the message, not that a human saw it.
- Peer names: still `prok-<shortId>` on the other phone.
- Still no encryption; the receipt is as readable over the air as the message.

## 8. What failed or is uncertain

- **Untested on hardware.** Compiles, manifest verified, logic reviewed twice.
  The two things I could not exercise: (a) the reappearance trigger timing
  (scanner expiry is 25 s, so "B is back" is noticed within ~5 s of B
  advertising again, but the first attempt may hit a stale rotated address
  once and retry after 1.5 s); (b) the receipt read on Android 8-12, which
  uses the older `onCharacteristicRead` callback (handled, but a different
  code path from Android 13+).
- **Compiler warning**, harmless: the pre-API-33 read callback override is
  deprecated upstream. Left as is.
- **VPS disk** is at 1.8 GB free; builds still work. The Windows Update
  cache cleanup is still pending on Mike's side (needs an RDP session).
- Repo is still private; ChatGPT's GitHub plugin could not read it at the
  last review. Mike can make it public in Settings > Danger Zone, or tell me
  to.

## 9. Git commit hash

Code + docs: `de603a8f4684f3a8509be532f686d31b830e36b3` on `main`.
Release tag `v0.2.0` points at it. This report is committed on top.

## 10. Recommendation for the next step

Wait for Mike's 2A test. If it passes, my recommendation for 2B is the
smallest step that makes CARRY real:

1. **Foreground service** so A keeps scanning and delivering with the screen
   off and the app in the background. Without this, "carry while people
   move" cannot happen: the app dies in the pocket.
2. **Peer display names** via the IDENTITY characteristic (read once on
   first contact, cached in the peers table).
3. Only then: carry other people's packets (destination ID + TTL + hop
   count in the packet, a per-packet "seen" set, forward on contact). That
   is the first true multi-phone STORE-CARRY-FORWARD and the point where the
   Python question should be decided, because routing policy is the logic
   that would live there.

Encryption should come before any real user traffic, but after the carry
mechanics are visible and testable, so the identity/key design is informed by
what the packets actually need.
