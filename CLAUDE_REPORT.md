# CLAUDE_REPORT - ProkNet v0.9.10 "self-healing BLE"

Date: 2026-09-14
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 136/136 automated tests pass, released, not yet retested on phones**

## 1. The good news first, and it is preserved

The v0.9.9 run did what the whole Wi-Fi Direct experiment was for: a buyer
as CLIENT, a P2P socket 192.168.49.x -> 192.168.49.1:47742, the signed
ProkNet authentication, a contract agreed, the seller upstream on Wi-Fi, VPN
UP, INTERNET OK, traffic and checkpoints, for about 4185 s (70 minutes),
4.2 MB up and 30.4 MB down. **Nothing in that path was changed.**

## 2. What broke after it

The session closed with "connection closed: end of stream". With both
phones side by side, ProkNet then showed 0 personnes, no offer, the known
seller expired after 25 s, control messages all said "no transport", two
GATT reconnects timed out after 20 s each, and the diagnostic still claimed
`server ready, adv on, scan on`. Restarting the service did nothing because
the node considered itself already running.

So the reported state was not the real state.

## 3. Real health, measured from the callbacks

`BleScanner` and `BleAdvertiser` now record what actually happened: when
advertising was confirmed and its last failure; whether the scan was
accepted, its last failure, how many results have ever arrived and when the
last one did. `BleTransport` adds consecutive GATT timeouts and the last
success, plus how many recoveries have run and why.

`core/BleHealth` (pure) turns that into a verdict: HEALTHY, NOT_RUNNING,
BLUETOOTH_OFF, BUSY, COOLING_DOWN, ADVERTISING_STALE, SCAN_STALE,
BOTH_STALE, GATT_WEDGED, and an action of NONE or RECOVER.

Two things stop the cure from being worse than the disease:
- silence is only a fault when company is expected (`expectPeers`: buying,
  selling, or a phone seen within 15 minutes), so a phone alone in a field
  is never restarted;
- a live link or a group being formed is always BUSY: hands off.

## 4. The recovery itself

`BleTransport.recoverRadio(why)` stops and recreates ONLY the scanner and
the advertiser, re-applying the advertised flags and price so a seller's
offer goes straight back on the air. The GATT server is restarted only when
it is itself not ready. The node, the identity, the queue and any live link
are untouched. The log reads:

```
BLE health check: scan stale (no scan result for 41s, gatt timeouts 2, peers expected true)
BLE radio recovery started: scan stale
scan stopped / advertising stopped
advertising restarted: true (flags 65, price 5)
scan restarted: true
BLE recovery complete (#1)
```

The watchdog runs every 10 s inside the foreground service. After a Wi-Fi
or Wi-Fi Direct teardown it also runs 3 s later, and the stale window drops
from 40 s to 15 s because the stack is the prime suspect then. Each
recovery starts a cooldown: one minute, then double, capped at five.

## 5. No more hammering a dead transport

`P2pPlan.guestTick(controlAvailable, reachableMs, unreachableMs, ...)`
returns PAUSED when the provider is not reachable over BLE, so the buyer
stops sending invitation requests into nothing. The 45 s admission timeout
only counts time when the control path was really available; 90 s out of
range becomes its own failure, "the provider is no longer in range". The
consumer screen just keeps saying "Recherche d un fournisseur...".

## 6. Tests (136, +8)

`BleHealthTest` (7): a working radio is left alone; a phone alone in a field
is never restarted; a fresh start is given time to settle; the
session-ended case recovers at 15 s while an ordinary quiet phone waits
40 s; an expired peer still counts as expected company; two GATT timeouts
with results still flowing is NOT wedged; advertising that never confirmed
is a fault on its own; a live link is never interrupted; and the backoff
grows and caps. `P2pPlanTest` (+1): the ladder pauses out of range, the
admission clock only runs when reachable, and 90 s away is its own failure.

## 7. Build

Build 23, versionName 0.9.10, 1.25 MB,
SHA256 `d142a5eb15e215edf4a80b20ff8e48e6bc111ca8ec87d6b9154aab863f1e3ffc`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.9.10
Commit `440a791d3da25c978a221533e3562f14d7e0fa48` on `main`; this report on top.

## 8. Retest

`docs/TESTING.md` section 28: run a real session, end it, then put the
phones side by side and touch nothing for two minutes. They must find each
other again by themselves, with sharing still on and the offer back,
followed by a second purchase from the normal screens. Section 27 remains
the Wi-Fi Direct verdict.

## 9. VPS

The build guard tripped again at 0.57 GB. I cleared the Windows Update
download cache, which Mike approved at the start of the project: 5 GB
recovered, 6.08 GB free now. The 14.2 GB pagefile is still the underlying
cause and capping it needs an RDP session.
