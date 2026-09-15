# CLAUDE_REPORT - ProkNet v0.9.22 "an accepted association owns the radio and its own clock"

Date: 2026-09-15
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 176/176 automated tests pass, released, not yet tested on phones.
Home Wi-Fi resale is NOT claimed. The 2.4 GHz result is NOT decided: run A was
contaminated and run B never formed a group.**

## 1. Run A: the group formed on 2.4 GHz, and the window was contaminated

```
18:54:58.447  connect accepted
18:54:58.447  DISCOVERY off
18:54:58.470  stopPeerDiscovery refused: BUSY
18:54:58.472  connection formed=false
18:54:58.474  starting peer discovery again
18:55:04      group formed, LINK PROBE starts
18:55:15      LINK PROBE verdict: no packets crossed
18:55:20      DISCOVERY off
```

Sixteen seconds of scanning inside the data-path window. You are right that
this is not a clean verdict on the band, and I am not treating it as one.

Two faults of mine produced it:

- Android emits `groupFormed = false` in the middle of its own join
  choreography, and v0.9.21 read that as "the attempt is dead, look again".
- On a first join the group generation AND the membership generation change
  in the same observation. The group branch won, and the line that stops
  discovery lived in the membership branch, so it never ran. That is why the
  stop finally came sixteen seconds later, from a different callback.

## 2. Run B: the attempt was killed two milliseconds after it was chosen

```
19:10:36.476  JOIN PLAN = SELLER_INVITE
19:10:36.478  the provider could see this phone, but the invitation did not complete
```

The purchase had been searching for about thirty seconds, and the deadline it
was judged against had already expired before the attempt existed. Android
had a confirmation dialog open on both phones while ProkNet tore it down.

## 3. The fixes

**One truth keeps the radio.** `P2pAdmission.associationPending(owner,
startedAt, now, hasMember, failed)`, centralised in `P2pLink` and checked by
`keepDiscovering` itself, so every caller in the node obeys it without
knowing about it. An attempt ends on membership, on an explicit Android
refusal, or on its own clock. A transient `formed=false` is none of those and
no longer releases the radio lock either.

**Stopping discovery is a post-condition of membership.** In `observePlane`,
before any branch and before the unchanged-plane early return:

```
if (now.hasMember) { stopDiscovery(...); endAssociation("membership formed") }
```

Whatever else changed in the same callback, a link with a peer on it has no
discovery.

**Two clocks, and choosing a plan starts neither.**

```
SEARCH_GIVE_UP_MS       60 s   from the start of the purchase, until an
                               association is accepted
ASSOCIATION_TIMEOUT_MS  40 s   from the moment Android ACCEPTED a connect()
                               or an invite(): long enough for a person to
                               read a dialog and tap Connect
```

`P2pAdmission.ladder(associationStartedAt, now, searchedMs)` -> SEARCH,
ASSOCIATING, GIVE_UP. Once an association is accepted the search time decides
nothing. The buyer starts its clock when its `connect()` is accepted, and
when the provider announces SELLER_INVITE, because the provider invites in
the same breath. A refusal ends the attempt at once and a replan follows.

The same constant now governs how long a plan is held and how long before an
unanswered invitation may be repeated, so nothing can interrupt a dialog a
person is reading.

## 4. Preserved

Symmetric admission, the no-guessing rule, BUYER_CONNECT preferred when both
see, SELLER_INVITE as the other path, membership semantics, the 2.4 GHz group
request, the radio lock, data plane generations, the binding hierarchy, the
UDP probe, TRANSPORT_READY, crypto, the tunnel, the VPN, the accounting,
method A, the bounded consumer failure. None of them changed.

## 5. Tests (176, +4)

A transient `formed=false` after an accepted connect, and after an accepted
invite, leaves the association pending; membership, refusal and the timeout
each end it. Both generations changing in one observation still leaves
discovery off. A plan chosen after thirty seconds of searching gets a full
fresh association clock, and the exact run B sequence cannot fail two
milliseconds later any more. An explicit refusal ends the attempt at once and
allows a replan.

## 6. Build

Build 35, versionName 0.9.22, 1.30 MB,
SHA256 `3774b26ad12f59d82e7331686b26d2202b95c26019d5ed89008ebd7bb54cf09c`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.9.22
Commit `a842f93` on `main`; this report on top.

## 7. The one clean run

`docs/TESTING.md` section 40. In order, and the third line is the one that
makes the verdict trustworthy:

```
JOIN PLAN = ...                 (the same on both phones)
ASSOCIATION started, clock starts NOW
no "starting peer discovery" between that line and the group forming
CLIENT COUNT 0 -> 1
DISCOVERY off                   (immediately, not fifteen seconds later)
GROUP CHANNEL: 2.4 GHz ...
LINK PROBE verdict: ...         (from BOTH phones)
```

If Android shows a confirmation dialog, tap CONNECT: the attempt waits for
you now.

## 8. The claim rule

Unchanged, section 30. Home Wi-Fi resale is not claimed, and the 2.4 GHz
question stays open until one uncontaminated run answers it.
