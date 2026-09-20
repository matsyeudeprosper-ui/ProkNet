# CLAUDE_REPORT - ProkNet v0.14.2 "clean shutdown"

Date: 2026-09-20
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 327/327 Android tests and 11/11 server tests pass,
released as build 57. No new feature. Nothing in the connection path changed.
The hardware acceptance test is TESTING 60 and has NOT been run yet.**

v0.14.1 is hardware-proven for the whole budget connection and I have not
touched it. This release only makes stopping boring.

## 1. Root cause of the shutdown race

Your reading was right, and it is deterministic. `ProkNetNode.stopInternet`
ran in this order:

```kotlin
sendControl(peer, Wire.bulkCancel(...)) {}   // async, waits up to 15 s for a receipt
bulk.cancel("customer stopped: " + reason)   // destroys the link under it
tunnel.stop(reason)                          // sends SESSION_END over a link that is gone
```

`sendControl` hands the frame to a worker thread which blocks in
`StreamLink.sendAndWait`. The main thread then closes the link. The worker
wakes, sees no receipt and a closed stream, returns `TRANSPORT_FAILED`, and
`sendBatch` posts `fail(detail)` to the main thread. By then `cancel` has
already reset the phase to IDLE, which is why the log says `BULK FAILED at
IDLE`. The three-millisecond gap in your timestamps is the whole story: nothing
waited fifteen seconds, the message text was simply wrong.

**The quiet half was worse than the noise.** `tunnel.stop` ran *after* the link
was gone, so `SESSION_END` never reached the seller, so the seller never issued
its closing checkpoint, so the buyer never countersigned one. The seller issues
a checkpoint every 30 seconds. A session stopped before the first one therefore
settled from `finalCost(contract, null)` — **zero**. Browsing for twenty seconds
and pressing Stop was free, repeatably. That is the bug worth this release; the
noisy log line was only its symptom.

## 2. The teardown state machine

`core/Teardown.kt`, pure, no Android:

```
RUNNING -> SETTLING -> IDLE
```

- `begin(state, reason, canReachPeer)` — a stop already under way returns the
  same state, so a second Stop is not a second settlement. No reachable peer
  means settle locally at once rather than wait.
- `onFinalSigned` — the closing figure came back countersigned. This is the
  one that bills.
- `onTimeout(state, token)` — only the timer belonging to *this* stop may end
  it.
- `onLinkGone` — during a stop this is the expected end, not a fault.
- `accepts(state, token)` — whether a callback still belongs to a live session.

The production order is now:

```
stop new app traffic (onTunPacket returns while settling, so the byte count stops moving)
say SESSION_END over the LIVE link
seller issues the closing checkpoint for what was really used
buyer verifies and countersigns it            <- bounded at 4 s
close the VPN
close the tunnel session
flush the write queue, then close the bulk link
clear the purchase state
```

`TunnelClient` and `Gateway` both drive that machine rather than keeping
private copies of the logic. Neither can be constructed in a JVM test, so the
decisions live where the tests can run the real ones. That is the same move
that fixed v0.14.1, applied on purpose this time.

## 3. Final checkpoint behaviour

On `T_SESSION_END` the seller now calls `beginFinalize`, which issues the
closing checkpoint and holds the session open for up to 4 seconds waiting for
the countersignature, instead of tearing down immediately. `checkpointIfDue`
gained a `finalIssued` guard so exactly one closing checkpoint exists per
session. On the buyer, `onCheckpoint` still works while SETTLING, countersigns,
and that is what ends the stop.

If the peer disappears first, both sides settle on the last figure both signed
and finish locally. **Nothing is invented for traffic nobody signed for.**

## 4. Short-session billing, measured

Four sessions, identical traffic rate, differing only in length:

| Session length | Periodic checkpoint fired | v0.14.1 settlement | v0.14.2 settlement |
|---|---|---|---|
| 5 s | no | 0 | what was used |
| 15 s | no | 0 | what was used |
| 29 s | no | 0 | what was used |
| 31 s | yes | the 30 s figure | the closing figure |

The test asserts, for each: charge above zero, charge at or below the signed
budget, seller net above zero, the Prok fee exactly the agreed share, one gross
entry and one fee entry, and the link closed only after the figure was signed.
At 31 seconds it additionally asserts the closing figure exceeds the periodic
one, so the traffic after the last checkpoint is billed rather than dropped.

## 5. Stale callback protection

Two mechanisms, both pure and both tested.

- **Teardown tokens.** Every transition out of SETTLING bumps one. A timer
  armed by session A firing after session B started changes nothing.
- **`BulkPlan.sendFailureIsReal(state, sendSession, closingOnPurpose)`.** A
  send failure is real only when we were not closing on purpose, a session is
  still live, and the send belonged to that session. `sendBatch` captures the
  session before handing frames to the worker thread and judges the failure
  against it. Late reports are counted in `staleCallbacksIgnored` and logged at
  info level, not as a failure.

`StreamLink.sendAndWait` also stopped claiming "no receipt within 15s" about a
link we closed a millisecond earlier; it now says the link closed while sending.

## 6. The last signature has to leave the phone

This one I found while checking my own fix, and it would have silently defeated
it. `LinkIo.close` shuts the output stream immediately and the writer thread
polls its queue on a 500 ms cycle, so a frame enqueued a moment before close
could simply die there. On a graceful stop that frame is the buyer's
countersignature on the closing figure — the exact thing this release exists to
deliver. `LinkIo.closeAfterFlush(reason, maxWaitMs)` waits for the queue to
drain and then closes, on a worker thread so the screen never blocks. It is a
wait on a condition with a bound, not a sleep: it returns the moment the queue
is empty. The graceful buyer stop and the seller's stop-sharing pass 600 ms;
every other close path is byte-for-byte unchanged.

## 7. Idempotent Stop

Three independent guards: `ProkNetNode.stoppingInternet` at the node,
`Teardown.begin` returning the same state when already settling, and
`TunnelClient.finishStop` guarding itself with `endingNow` because it can be
reached from a timer, a checkpoint and a closing link at once. Pressing Stop
twice produces one settlement, one close, one pair of ledger entries and no
FAILED state. Tested.

## 8. Immediate reconnect

`stopInternet` clears the purchase economics and the buyer intent at once, so
the screen goes quiet immediately while the protocol settles underneath. When
the settlement completes, `finishTeardown` stops the VPN, sends the bulk cancel
as an explicitly best-effort message (`quiet = true`, so a failure is not
recorded as a control-plane error), closes the link with the flush budget and
clears `stoppingInternet`. The test drives three stop-and-restart cycles and
asserts after each one that both contracts, both checkpoints, the reject reason,
the buyer error and the teardown phase are all clean.

## 9. Tests

327 Android JVM tests, up from 310. 11 server tests. All 310 previous tests
still pass, unmodified.

- `TeardownTest`, 6 tests: the normal stop, stopping twice, an old timer that
  must not end a new session, a peer that has already gone, a link that dies
  while running being a real failure, and the diagnostic words being distinct.
- `BudgetSessionTest`, 7 new: short sessions at 5, 15, 29 and 31 seconds all
  billing; the closing figure billing rather than the periodic one; Stop twice
  settling once; a peer vanishing mid-stop; a late callback being ignored; stop
  then immediately start, three times; and the proven v0.14.1 sequence
  unchanged by the new shutdown.
- `BulkPlanTest`, 2 new: a send failing because we closed the link is not a
  transport failure, and a new session is never judged by an old one's send.
- `LinkIoTest`, 2 new, over real loopback sockets: a graceful close delivers
  every queued frame, and a flush that cannot finish still closes inside its
  bound.

## 10. What I did not touch

ProviderInbox, provider activation, BLE generation recovery, ControlRetry,
L2CAP negotiation, bulk authentication, the 256 KB probe, v2 contract framing,
`Market.admitProposal`, the budget ceiling, the seller floor, the tunnel and
VPN data path, Gateway stream handling, checkpoint issue and validation, the
ledger, coverage, the Network Brain, the consumer pricing wording and the
v0.12.5 design. The only changes to the connection path are the two-step stop
and the flush on close.

Consumer megabyte check re-run: no `par Mo` or `/Mo` anywhere in MainActivity,
and the only `priceWord` call there is inside a `DiagLog` line.

## 11. Version / build / commit / hash

Build 57, versionName 0.14.2, verified with `aapt2 dump badging`.
SHA256 `af4d8c2697bbe6926b29d7a66f44ba3b1b16ecc6ef640fb4be3d9905b6b29cc5`.
Commit `81fbe06` on `main`; this report on top.
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.14.2

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```

## 12. Known limitations

- **The 4 second graceful window is a guess, not a measurement.** On the phones
  a checkpoint round trip took well under a second, so it should be generous,
  but I have no hardware figure for it yet. TESTING 60a will give us one.
- **The 600 ms flush budget is likewise unmeasured.** If the closing figure ever
  shows `timeout` on hardware, these two numbers are the first place to look.
- A stop while the seller is mid-checkpoint may produce two checkpoints in
  flight; the buyer rejects the duplicate by sequence, as it always did, and the
  closing one wins. Harmless, but it will appear in the log.
- Settlement still relies on both phones booking their own ledger entries. There
  is no cross-phone reconciliation, and there was none before.
- The seller-stops-first path settles correctly but the buyer learns about it
  from the closing checkpoint plus the link closing, not from a dedicated
  message. TESTING 60f covers it.

## 13. What is still pending on hardware

- **TESTING 60**, new: short paid session, no invented failure, three immediate
  reconnects, Stop twice, Bluetooth pulled during the stop, seller stops first.
- TESTING 59 (v0.14.1 three sessions, Bluetooth failure injection, background)
  is now partly proven by your run but was never completed as written.
- TESTING 55, 56, 57 and 58 have still never been run.
