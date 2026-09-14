# CLAUDE_REPORT - ProkNet v0.9.4 "the seller always answers"

Date: 2026-09-14
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 108/108 automated tests pass, released, not yet retested on phones**

## 1. What the phone diagnostic proved

Mike sent the buyer side (OnePlus CPH2409, prok-0f7d57b3, build 16):

```
16:11:47.627 WIFI: PHASE REQUESTING - WIFI_REQUEST -> prok-24e480e6 over BLE
16:11:51.535 GATT-C: frame 1/1 -> DELIVERED (RECEIPT accepted: destination stored it)
16:12:49.295 WIFI: PHASE DOWN - the provider did not answer within 60s
```

That receipt is decisive. ACCEPTED is only returned after the seller has
decrypted the envelope, verified the signature and dispatched the control
message to its Wi-Fi transport. So the seller was alive, knew the buyer's
key, and received the request. It also advertises `SELL 5 CFA/MB mobile
data`, so it is selling with a working upstream. It simply never answered.

The seller is prok-24e480e6, the same phone that was the RELAY in the
3-phone test an hour earlier, and the buyer is prok-0f7d57b3, the phone it
was hosting then.

## 2. Root cause

`LinkState.requestReceived` (unchanged since v0.5) answers nothing at all
when this phone is UP or busy:

```kotlin
if (state == State.UP) return Action.NONE
if (isBusy && !(state == State.REQUESTING && peer == peerShort)) return Action.NONE
```

A phone that still holds a link from an earlier session therefore refuses
every new customer silently, for ever: TCP does not notice a peer that
walked away while the link is idle, so nothing ever clears it. v0.9.1 fixed
exactly one case of this (the SAME peer asking again) for the relay; the
general case, and above all the silence, remained.

## 3. Fix: every request gets an answer

`LinkState.hostAnswer(peerShort, myShort, linkInUse)`, pure and tested:

| situation | answer |
|---|---|
| UP with the same peer | drop the stale link, host |
| UP with someone else, a session really running | REFUSE_BUSY |
| UP with someone else, link idle | drop the idle link, host |
| both asked at once, other ID lower | ignore (it hosts) |
| mid-negotiation for the same peer | tear down, start over |
| mid-negotiation for someone else | REFUSE_BUSY |
| idle | host |

`linkInUse` is supplied by the node (`gateway.session != null ||
tunnel.session != null || relay.session != null`): an idle link is never a
reason to refuse a customer, a live session is.

WIFI_CANCEL now carries a reason byte (CANCEL_NO_HOTSPOT, CANCEL_BUSY,
generic); older builds send none, which reads as generic, so it is
backward compatible. It is sent on REFUSE_BUSY, on any hotspot failure,
and from `teardown` whenever the link never came up, so the other phone
stops in about a second instead of running its own 60 s timeout.
`ProductState.lostHint` turns CANCEL_BUSY into "Le fournisseur est deja
occupe avec un autre telephone. Reessayez dans un moment."

## 4. Tests (108)

`LinkStateTest` drives the seven rows of that table, including the exact
shape of this failure: a host that is UP from an earlier session and is
asked again by the same peer, and by a different one, with and without a
live session.
`WireTest` round-trips the cancel reason and parses a reason-less cancel
from an older build as generic.
`ProductStateTest` checks the new French sentence and that none of the
setup failures tells the user to walk.

## 5. Build

Build 17, versionName 0.9.4, 1.3 MB,
SHA256 `128cb0309b38e70ec8e1866c11f2af9e1a3ecda9590c125c2bf0e55c8843b342`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.9.4
Commit `93d9fd1b040d353fed08825516ada336136c1449` on `main`; this report on top.

## 6. Important for the retest

**Both phones must run 0.9.4.** The buyer side of this bug is only the
symptom; the decision that was missing is on the phone that HOSTS. A seller
still running 0.9.3 or earlier will keep answering nothing.

## 7. Lesson recorded

A protocol step that can be refused must have a refusal message. Silence is
indistinguishable from a lost packet, from a dead app and from a bug, and it
costs the user a full timeout every time. The v0.9.1 relay fix and this one
are the same lesson twice: ask and always answer, never announce once and
hope.
