# CLAUDE_REPORT - ProkNet v0.9.21 "a group is a room, membership is admission"

Date: 2026-09-15
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 172/172 automated tests pass, released, not yet tested on phones.
Home Wi-Fi resale is NOT claimed. The 2.4 GHz band experiment has still never run.**

## 1. Your diagnosis is exactly right

```
17:56:05.441  admission: the customer "OnePlus Nord CE 2 Lite 5G" cannot address me,
              and I can address "OnePlus Nord CE 2 Lite 5G" at 1e:4f:f2:19:36:ce
              -> SELLER_INVITE
              role = GROUP_OWNER, group formed = true, clients = 0
17:56:05      INVITING ...     never printed
```

`mayInvite` required `!groupFormed`. In this architecture the provider
creates and owns its group BEFORE any customer arrives, so `groupFormed` is
true from the moment sharing starts, and the invitation the code had just
decided on could never be sent. A logic bug of mine, not an Android mystery.

## 2. The correction

`groupFormed` is the room. `P2pDataPlane.Plane.hasMember` is somebody being
in it: a client count above zero for an owner, and "I joined" for a client.
That is what "admission is over" means, and the data plane already modelled
it since v0.9.14.

- `keepOwner(owner, sinceMs, failed, hasMember)`
- `heldPlan(current, owner, sinceMs, failed, hasMember, fresh)`
- `mayInvite(plan, sellerSight, ownsGroup, hasMember, invitedMsAgo)`

`ownsGroup` is separate and explicit, because a provider must own a group
before it can invite anybody into it. The state the invitation exists FOR is
exactly the one the phone was in:

```
plan SELLER_INVITE, this phone can address the exact customer,
role GROUP_OWNER, group formed, NO member, no invitation pending
-> INVITE
```

Ownership now ends on live membership, an explicit failure, or the 20 s
timeout. Not on the existence of an empty group.

## 3. Two corrections that came with it

**A customer waiting for an invitation keeps reporting what it can see.** In
v0.9.20 `WAIT_FOR_INVITE` stopped the visibility reports, and those reports
are what make the provider decide again. A failed invitation therefore had
nothing to retry it. `buyerStep` now reports first and labels the wait
second; the plan is still held by the provider, so re-reporting cannot start
a competing attempt.

**Every ending names the stage that failed.**

```
SELLER_INVITE  -> the provider could see this phone, but the Wi-Fi Direct invitation did not complete
BUYER_CONNECT  -> the customer could see the provider, but the Wi-Fi Direct join did not complete
WAIT           -> neither phone could address the other over Wi-Fi Direct
```

The last run ended with the third, which was false. Each has its own French
sentence on the customer's screen.

## 4. Diagnostics

The seller's admission line now carries the whole decision:

```
admission: the customer "..." cannot address me, and I can address "..." at 1e:4f:f2:19:36:ce
| group formed=true role=GROUP_OWNER hasMember=false owner=SELLER -> SELLER_INVITE
```

and when it does NOT invite it says which condition failed:
`not inviting right now: a customer is already on this link` / `this phone
does not own a group yet` / `the customer is not addressable` / `the last
invitation is still pending (Ns)`.

## 5. Preserved

The symmetric decision itself, the no-guessing rule, the single admission
owner, discovery recovery, the 2.4 GHz group request, the radio lock, the
data plane generations, the binding hierarchy, the UDP link probe, crypto,
the signed handshake, the tunnel, the VPN, the marketplace, method A and the
bounded consumer error. This was a semantic correction, not a redesign.

## 6. Tests (172, +4, and the backwards ones replaced)

The assertion that encoded the bug, "never once the group exists", is gone.
In its place: a provider holding an EMPTY group with the exact customer in
sight MUST invite; a provider whose customer has joined must not; a provider
with no group of its own must not; an invitation is not repeated inside the
ownership window and may be retried after it with no member; membership ends
ownership; and the customer leaving resets admission and leaves an empty
group ready to invite the next one. Plus the three endings and their French.

## 7. Build

Build 34, versionName 0.9.21, 1.29 MB,
SHA256 `92fd423c751e400394afc3a02a1f157dde632dc81d95eacaa9d80a572cb2e7cd`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.9.21
Commit `30b3a89` on `main`; this report on top.

## 8. What the next run must produce

`docs/TESTING.md` section 39, then 37 and 34:

```
JOIN PLAN = SELLER_INVITE      (the same on both phones)
INVITING the customer into my group: "OnePlus Nord CE 2 Lite 5G" (1e:4f:f2:19:36:ce)
invitation ... accepted by Android, waiting for it to join
CLIENT COUNT 0 -> 1
GROUP CHANNEL: ...
LINK PROBE verdict: ...        (from BOTH phones)
```

Only the last line judges the 2.4 GHz band, and only once membership exists.

## 9. The claim rule

Unchanged, section 30. Home Wi-Fi resale is not claimed.
