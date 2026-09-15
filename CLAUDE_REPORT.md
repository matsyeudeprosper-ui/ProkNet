# CLAUDE_REPORT - ProkNet v0.9.17 "a purchase starts from a clean screen"

Date: 2026-09-15
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 155/155 automated tests pass, released. v0.9.16 has still NOT
been exercised on the phones. Home Wi-Fi resale is NOT claimed.**

## 1. What happened

v0.9.16 was never tested, because the consumer screen ended every purchase
before it began:

```
13:28:44.291  UI: CONNECT pressed: prok-24e480e6 5 CFA/MB
13:28:46.815  asking prok-24e480e6 whether its Wi-Fi Direct group is ready
13:28:48.386  UI: Stop Internet pressed          <- the user, after "Connexion perdue"
```

Three attempts, three stops within four seconds each, and no group ever
formed. So there is no radio lock line, no group channel line and no probe
verdict from that run. The v0.9.16 questions are all still open.

## 2. The cause, from the diagnostic

```
wifi: DOWN / DOWN (initiator with prok-24e480e6) - could not reach the host
      (10.168.138.1: ... EHOSTUNREACH (No route to host) ...) (retry allowed in 5s)
```

That is the HOTSPOT transport (method A), holding a failure from an attempt
minutes earlier, on the 10.168.138.x hotspot subnet. `ProductState.buyer`
turns any phase beginning with `DOWN` into `LOST`, and the consumer screen
read that transport even while the purchase was going over Wi-Fi Direct.

So the very first refresh after CONNECT said "Connexion perdue". The user did
exactly the right thing and pressed stop.

This is my bug and it is a product-level one: a failure from an old attempt
must never end the next one.

## 3. The fix

- **`ProkNetNode.buyPhase()`** is now the single place that decides which
  transport the screen reflects: the Wi-Fi Direct link during a Wi-Fi Direct
  purchase, the hotspot transport otherwise.
  **`P2pPlan.buyPhase(stage, groupFormed, planeUsable, linked)`** is the pure
  mapping, so FINDING, JOINING, TCP and AUTH mean the same thing on both
  paths and the French wording layer is unchanged.
- **`buy()` clears the failure surface** before anything starts: the buyer
  error, `TunnelClient.lastError`, and any leftover hotspot state when
  nothing is linked.

## 4. A second finding, worth recording

The stale error itself is evidence. On the hotspot path the buyer had
`10.168.138.61`, an address from the seller's own hotspot, and could not
reach `10.168.138.1:47741`. That is the same shape as the Wi-Fi Direct
result: the customer gets an address from the provider and then cannot get a
connection back. I am not drawing a conclusion from one line in a diagnostic,
but if the Wi-Fi Direct probe verdict comes back one way again, these two
belong in the same sentence.

## 5. Tests (155)

The regression itself: a stale `DOWN ... could not reach the host
(10.168.138.1)` makes the buyer LOST, and the same buyer on a Wi-Fi Direct
purchase is FINDING, then CONNECTING as it joins, and only LOST when THIS
attempt fails.

## 6. Build

Build 30, versionName 0.9.17, 1.29 MB,
SHA256 `6d59476cf315989c4ec50a080329342f452499ba5423a7043727f91a9fb7cc18`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.9.17
Commit `2e34e76` on `main`; this report on top.

## 7. What the next run must produce

Install on BOTH phones, then run section 34 **without pressing STOP**: the
attempt ends by itself after about 45 seconds. The three lines that are still
unanswered since v0.9.16 are:

```
RADIO LOCK held: ...
GROUP CHANNEL: ... | this phone's Wi-Fi: ...
LINK PROBE verdict: ...        (from BOTH phones)
```

If the verdict is one way again, the next levers are the ones listed in the
v0.9.16 report: the owner invite association first, the 2.4 GHz group second,
one at a time.

## 8. The claim rule

Unchanged, section 30. Home Wi-Fi resale is not claimed.
