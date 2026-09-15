# CLAUDE_REPORT - ProkNet v0.9.20 "admission is symmetric"

Date: 2026-09-15
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 168/168 automated tests pass, released, not yet tested on phones.
Home Wi-Fi resale is NOT claimed. Nothing is concluded about the 2.4 GHz band:
that experiment has still never run.**

## 1. You are right about the run

v0.9.19 never reached the band experiment. The failure was one step earlier
and in the opposite direction from every previous one:

```
seller   sees "OnePlus Nord CE 2 Lite 5G" at 1e:4f:f2:19:36:ce, available
buyer    provider answered GROUP_READY, looking for "C1 Pro",
         0 peers seen, 0 real addresses, 0 join attempts
```

So there is no `CLIENT COUNT 0 -> 1`, no `GROUP CHANNEL` and no probe verdict
from it, and I am not drawing any conclusion about the band from that run.

## 2. The design

Both cases have now been seen on hardware:

```
CASE A (v0.9.11)  buyer sees seller, owner cannot identify the buyer
CASE B (v0.9.19)  seller sees buyer, buyer sees nobody
```

So admission no longer assumes a direction. BLE carries identity and intent
already; it now carries visibility, and the provider turns the two facts into
one plan that both sides obey.

```
buyer  -> seller   P2P_VISIBILITY   canSee, and the buyer's own P2P name
seller -> buyer    P2P_JOIN_PLAN    BUYER_CONNECT | SELLER_INVITE | WAIT
```

`P2pAdmission.plan(buyerSeesSeller, sellerSeesBuyer)`:

| buyer sees | seller sees | plan |
|---|---|---|
| yes | no | BUYER_CONNECT |
| yes | yes | BUYER_CONNECT (preferred: it has formed groups) |
| no | yes | SELLER_INVITE |
| no | no | WAIT, both keep looking, bounded |

The decision is taken on the provider, the only phone holding both facts. The
owner-side `invite()` machinery from v0.9.9 is promoted from a lab button to
a real coordinated admission path, used only when the provider owns the
plan, holds the buyer's exact P2P name from BLE, finds exactly that name in
its own list with a real address, and the buyer has reported it is blind.

## 3. Never guess, and never two initiators

- `P2pAdmission.look(peers, nameFromBle)` is the only way either side answers
  "can I address the other phone". A name match and a real address, or
  nothing. The v0.9.18 rule is preserved and now applies on both sides: no
  group owner fallback, no anonymous address, no lone available peer.
- One attempt owns admission for `ATTEMPT_OWN_MS` (20 s). A customer that
  suddenly sees the provider cannot start connecting while an invitation is
  in flight; an invitation cannot be sent while a join is pending; an
  invitation is not repeated while the previous one is still pending. The
  v0.9.18 accepted-connect window (15 s) is untouched and sits inside this.
- An accepted association stops discovery on the initiating side, and
  `P2pLink.resumeDiscovery` brings it back on every tick where no attempt is
  in flight. It is idempotent and refuses while the link has a peer on it, so
  the v0.9.19 findability rule still holds.

## 4. Diagnostics

Both phones print `JOIN PLAN = ...` with the reason, the buyer prints what it
can and cannot address by name, and the seller prints both halves of the
decision in one line. A purchase where neither phone could ever address the
other now fails saying exactly that instead of "could not join".

## 5. Preserved

BLE health and healing, crypto, the signed handshake, the membership and data
plane generations, the binding hierarchy, the UDP link probe, the radio lock,
the 2.4 GHz group request, the tunnel, the VPN, the accounting, the
marketplace, method A and the bounded error screen. None of them changed.

## 6. Tests (168, +9)

Every case from your list: buyer sees seller only, seller sees buyer only,
both see each other (BUYER_CONNECT only), neither sees the other, a seller
that sees only an anonymous peer never invites, a buyer that sees only a
printer never connects to it, a pending buyer connect blocks an invitation, a
pending invitation blocks a connect, an invitation is not repeated while
pending, and the customer leaving resets admission and makes the provider
findable again. Plus both new messages over the wire.

## 7. Build

Build 33, versionName 0.9.20, 1.29 MB,
SHA256 `ec51b9107320e2034df8f8f6a48255b035c40484578edb37cd9235516d88659a`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.9.20
Commit `66265b2` on `main`; this report on top.

## 8. What the next run must produce

`docs/TESTING.md` section 38, then 37 and 34. In order:

```
JOIN PLAN = BUYER_CONNECT   or   JOIN PLAN = SELLER_INVITE   (the SAME on both phones)
CLIENT COUNT 0 -> 1
GROUP CHANNEL: ...
LINK PROBE verdict: ...     (from BOTH phones)
```

Only the last line judges the 2.4 GHz band change, and only once a group has
actually formed.

## 9. The claim rule

Unchanged, section 30. Home Wi-Fi resale is not claimed.
