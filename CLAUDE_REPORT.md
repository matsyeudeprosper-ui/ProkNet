# CLAUDE_REPORT - ProkNet v0.9.14 "membership is part of the transport"

Date: 2026-09-15
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 151/151 automated tests pass, released, not yet tested on phones.
Home Wi-Fi resale is NOT claimed.**

## 1. The v0.9.13 run, and what it disproved

```
seller  p2p-wlan0-26 = 192.168.49.1   GROUP_OWNER   network 158
seller  listener 192.168.49.1:47742   generation 1  accepting true
seller  CLIENT COUNT 0 -> 1           listener check says VALID
buyer   p2p0 = 192.168.49.124         android network none
buyer   socket bound to P2P network=false
buyer   DIAL 1..6 -> 192.168.49.1:47742   all six timed out
seller  TCP accepted                  never
```

Every field matched, our validator said valid, and the link was dead. The
abstraction was optimistic in exactly the way you named: it proved the
listener still described the same endpoint, not that a newly joined client
could reach it.

## 2. The comparison with the v0.9.9 success, before changing anything

I read `757a41d` against `171828b`, as asked. The result is worth stating
plainly, because it rules out the obvious fix.

| | v0.9.9 (70 min session WORKED) | v0.9.13 (failed) |
|---|---|---|
| when the listener was created | at group formation, clients 0 | at group formation, clients 0 |
| listener bind | `0.0.0.0:47742` | `192.168.49.1:47742` |
| buyer dial socket | plain `Socket()`, unbound | unbound (no Network exposed) |
| when the buyer dialled | on its own `onConnectionInfo` | same |
| peer discovery during it | kept alive on both sides | same |
| cleanup between group and TCP | none | none |
| who associated | **the OWNER invited the guest** | **the CLIENT joined by itself** |

So the socket code and its timing are the same in the run that carried
34 MB and the run that carried nothing, and the listener was built before
any client existed in BOTH. The one behavioural difference is who starts the
association, which changed in v0.9.12 because the owner's peer list
anonymises the buyer.

Two conclusions, and I did not regress the choreography on either:

- binding alone was never the cure, so "bind harder" is not the fix,
- a listener created in the empty-group phase is not evidence of anything,
  and the data path must not depend on which side began the association.

## 3. The design

**`core/P2pDataPlane.kt` (pure).** `Plane(groupGeneration,
membershipGeneration, role, interfaceName, localAddress, networkIdentity,
clientCount)` with `endpointReady`, `hasMember` and `usable`.
`advance(current, observed, clientCount, groupFormed)` gives a new GROUP
generation on a real endpoint change, a new MEMBERSHIP generation the moment
this phone gains a live peer, and the same object when nothing moved.
`validate(plane, listener)` returns `NO_ENDPOINT`, `NO_MEMBER`,
`NO_LISTENER`, `STALE_GROUP`, **`STALE_MEMBERSHIP`**, `WRONG_INTERFACE`,
`WRONG_ADDRESS`, `WRONG_NETWORK`, `NOT_ACCEPTING`, `VALID`.
`acceptAllowed(born, now)` compares BOTH counters.

**The 0 -> 1 transition** is a transport event. The endpoint is read again,
the membership generation increments, the old listener is named as stale for
the live membership, and a listener is armed for that generation. Nothing is
armed while the group is empty, so there is no listener to carry forward any
more.

**Both phones dial.** Android can bind an outgoing socket to a network and
has no public way to bind a listening one. The provider is precisely the
phone that also holds a home Wi-Fi network, so its listening socket is the
one that cannot be tied to Wi-Fi Direct. It no longer depends on being
dialled:

```
buyer joins -> member at once, arms its own listener
buyer -> seller (BLE):  P2P_MEMBER 192.168.49.124:47742
seller sees clients 0 -> 1, arms the listener for membership 1
seller -> buyer (BLE):  TRANSPORT_READY 192.168.49.1:47742 membership 1
buyer dials the seller        seller dials the buyer (ANDROID_NETWORK bound)
first authenticated socket wins, the link state machine closes the loser
```

This is the separation you proposed: `GROUP_READY` means only "you may
join"; `TRANSPORT_READY` means "you are joined and my listener is armed for
your membership". The owner cannot learn a client IP from Android (the
client list carries MAC addresses and `/proc/net` is closed to apps), so the
member sends its address over the BLE control channel it is already using.

**The binding hierarchy**, on every outgoing socket:

```
Android network exists -> Network.bindSocket(socket)      ANDROID_NETWORK
else a P2P address     -> socket.bind(192.168.49.124, 0)  LOCAL_ADDRESS
else                   -> refuse to open the socket       NONE
```

`binding NONE` is a transport error with a message, never a dial. The
OnePlus reported `android network none` while holding
`p2p0 = 192.168.49.124`, and that is now bound explicitly.

## 4. What is unchanged

BLE discovery and control, BLE healing, the seller GROUP_READY answer,
buyer-side peer selection, `connect()`, group formation, crypto, the signed
handshake, the tunnel, the VPN, the accounting, the marketplace, method A,
and the bounded failure with
"Connexion locale créée, mais le fournisseur ne répond pas."

The provider upstream is untouched: only the local transport socket is tied
to Wi-Fi Direct, `p2p...` stays a LOCAL link that can never be an upstream,
and nothing binds the process.

The one timed element is a bounded 8 s wait before a buyer dials without
having heard `TRANSPORT_READY`, plus the existing bounded endpoint watch.
Neither is a sleep in a retry loop: each is a single decision with a reason.

## 5. Tests (151)

The hardware transition itself: an owner with a listener from the empty
group, a client joining much later, and the old listener coming back
`STALE_MEMBERSHIP` although its address, interface and network all still
match; a buyer that may only dial once the transport is ready for ITS
membership, or after the bounded wait; `LOCAL_ADDRESS` binding when Android
exposes no network and `ANDROID_NETWORK` when it does, and a refusal when
there is neither; a second customer getting membership 2 while a loop from
membership 1 is refused; the group being rebuilt starting the count again;
the provider upstream staying Wi-Fi; and both new control messages over the
wire.

## 6. Build

Build 27, versionName 0.9.14, 1.36 MB,
SHA256 `11ae4af354ccfe38c892eb91c72b83f09ca59b5a635243c3c9cfe5962a9a1e7a`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.9.14
Commit `c0d0297` on `main`; this report on top.

## 7. What the next run answers

`docs/TESTING.md` section 32 lists the lines to read. The one thing I want
from the hardware is **which side produced the socket**: the seller
accepting (`TCP accepted ... membership generation 1`) or the seller dialling
out (`TCP connected ... binding ANDROID_NETWORK`). That single fact decides
whether a group owner on a home network can ever be dialled on these phones,
and it is the last open question in the transport.

## 8. The claim rule

Unchanged, section 30, and I am not calling this solved: seller mobile data
OFF and on the Freebox throughout, buyer mobile data OFF and not on the
Freebox, TCP link, signed authentication, contract, tunnel, VPN, INTERNET
OK, Chrome loading pages.
