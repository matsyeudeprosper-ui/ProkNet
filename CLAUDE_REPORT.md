# CLAUDE_REPORT - ProkNet v0.9.8 "deterministic Wi-Fi Direct cleanup"

Date: 2026-09-14
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 124/124 automated tests pass, released. The Wi-Fi Direct
question is still open: this fixes the lifecycle bug the first run found,
it does not answer the physics.**

## 1. What the phone run found

A buyer that had been a seller still showed, after STOP and a later BUY:

```
phase DISCOVERING
role NONE
group ssid=DIRECT-56-OnePlus...
p2p0=192.168.49.1
socket listening :47742
peers none
```

Discovery itself works (the same phone had found the other one before), so
the fault was stale state: v0.9.7 called `removeGroup` and forgot it while
wiping its own fields at once, and `startBuyer` began discovery without
ensuring any previous group, connection or server socket was gone.

## 2. The fix: one ordered, confirmed cleanup before every role change

```
cancelConnect -> stopPeerDiscovery -> close sockets -> removeGroup -> start the new role
```

- SELL, BUY and STOP all begin with that walk. STOP only says IDLE after
  the last step is confirmed.
- Each step waits for Android's own callback; a 4 s watchdog logs a timeout
  and moves on rather than wedging the lab.
- A newer cleanup abandons an older one, so double taps are safe.
- `P2pPlan.Life` (pure) owns every visible field, so each one is cleared at
  its own step and not before: `peers` when discovery stops, the socket when
  it is really closed, and the group, the role and `groupFormed` only when
  `removeGroup` is confirmed.
- Broadcasts that arrive during a cleanup are ignored, so the group being
  torn down cannot resurrect itself in the UI.

## 3. Diagnostics added

`CLEANUP started (reason)` with the state it started from, then one line per
step carrying Android's own answer (`ok`, `nothing to undo / refused: BUSY`,
`no answer from Android within 4s, moving on`), then `CLEANUP complete` with
the resulting state and the live interfaces, marked `<-- STILL NOT CLEAN` if
anything survived. COPY P2P DIAG now prints the whole state on one line plus
`clean (nothing left from a previous role): true/false`.

## 4. Tests (124, +5)

All five scenarios asked for, driven through the pure lifecycle:

- **SELL -> STOP -> BUY**: the four steps run in order, IDLE only at the
  end, and the view is clean before discovery starts.
- **BUY -> STOP -> SELL**: same in reverse, role back to NONE.
- **repeated STOP**: three in a row, each ending clean; confirming a step
  that is not the current one changes nothing.
- **stale group before BUY**: the group survives CANCEL_CONNECT,
  STOP_DISCOVERY and CLOSE_SOCKETS and disappears exactly at REMOVE_GROUP;
  a late broadcast during the cleanup does not resurrect it.
- **stale listening server before BUY**: the socket is still there until
  CLOSE_SOCKETS and gone after it, and the declared order is asserted.

No regression: method A, the relay, the coverage engine and the marketplace
are untouched and green.

## 5. Build

Build 21, versionName 0.9.8, 1.23 MB,
SHA256 `5fe527e08659a2d536f5a6e77164cd95a1dd94d9b21410aab0c976b009fc8eab`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.9.8
Commit `CODE_COMMIT` on `main`; this report on top.

## 6. Not changed on purpose

The Wi-Fi Direct data-path design is untouched: the P2P socket is still
adopted by the existing transport, and method A is still the only path the
consumer app uses. This result has not disproved Wi-Fi Direct; the real
verdict still comes from `docs/TESTING.md` section 25, with section 26
first to confirm the cleanup.

## 7. VPS note

The build disk guard hit twice today. The cause is now identified: the
pagefile is 14.2 GB on a 140 GB disk that is 139 GB used, and it grows
during builds. I freed space by clearing Gradle derived caches (they
rebuild themselves), which works but is a treadmill. Capping the pagefile
or clearing the 8.6 GB Windows Update cache needs an RDP session and Mike's
approval, since other services run on this box.
