# CLAUDE_REPORT - ProkNet v0.9.24 "the reversed experiment has to start, and has to end"

Date: 2026-09-19
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 185/185 automated tests pass, released, not yet tested on phones.
Home Wi-Fi resale is NOT claimed. The reversed topology has NOT been tested yet.**

## 1. What the run showed

The topology command reached the provider and the provider entered guest
mode. Then the customer sent a `WIFI_REQUEST`, the hotspot path, and the
experiment never created the group it exists to test. The buyer stayed at
`role=NONE`, and the seller kept looking for a customer-owned group that did
not exist, for minutes, after the customer had cancelled.

Both faults are mine, and your reading of the code is exactly right.

## 2. Fault one: the purchase decision was gated on the provider's group

`if (offer.p2p) startP2pBuy() else requestWifi()`. A provider in
BUYER_GROUP_OWNER mode drops its own group on purpose, so its offer stops
advertising `p2p`, so the customer fell back to the hotspot. A customer that
owns the group must not wait for the provider to own one.

`P2pAdmission.buyPath(topology, offerP2p, linkUp, viaRelay)`:

| link up | topology | offer p2p | path |
|---|---|---|---|
| yes | any | any | the existing link (relay introduction if sold that way) |
| no | BUYER_GROUP_OWNER | any | Wi-Fi Direct, buyer creates the group |
| no | SELLER_GROUP_OWNER | yes | Wi-Fi Direct, buyer joins the provider group |
| no | SELLER_GROUP_OWNER | no | the hotspot request, unchanged |

Production is untouched: the forced path exists only when the customer chose
the experiment in the lab. The log now says `BUY decision: forcing Wi-Fi
Direct because buyer owns the group` and `starting buyer-owned P2P group`,
and there is no `WIFI_REQUEST` in such a purchase.

## 3. Fault two: the provider's reversed session never ended

Five loose fields held the guest state and nothing cleared them. They are
now two objects, reset as one unit:

- `P2pAdmission.GuestSession`: the customer, the plan, the visibility clock
  and the connect clock. The guest ladder ticks only while
  `ticks(peer, providing, linked, hasMember)` holds.
- `P2pAdmission.OwnerDecision`: the plan in force, who holds it, when, the
  invitation clock, and whether it failed.

`clearReversedSession(peer, why)` resets both, forgets the customer's name,
stops the temporary Wi-Fi Direct client attempt if nothing is linked, and
logs `REVERSED TOPOLOGY session cleared for prok-... | still sharing: true,
upstream Wi-Fi`. It runs when the customer cancels, when another customer
takes over, when the customer goes back to the production topology, and
when the provider stops sharing. The Freebox connection is never part of it.

The customer now sends a cancel over BLE whenever it stops a Wi-Fi Direct
purchase, and the provider routes that cancel to the reversed session when
it belongs to that customer.

## 4. Preserved

Production transport selection, the Freebox upstream invariant, symmetric
admission, the no-guessing rule, membership semantics, the 2.4 GHz request,
the radio lock, the data plane, the binding hierarchy, the probe, the saved
test record, crypto, the tunnel, the VPN, the accounting, method A and the
bounded consumer failure.

## 5. Tests (185, +4)

BUYER_GROUP_OWNER with `p2p=false` goes to Wi-Fi Direct and never to the
hotspot; SELLER_GROUP_OWNER with `p2p=false` still goes to the hotspot; an
existing link is used in either topology. A cancelled reversed purchase
clears the session and the guest ladder cannot tick. A new reversed purchase
starts with a clean guest session and a clean owner decision. The ladder
stops by itself on membership, on a live link, and when sharing stops, and
clearing the session says nothing about whether the phone is sharing.

## 6. Build

Build 37, versionName 0.9.24, 1.31 MB,
SHA256 `5bbdd30f912acb52c3c427156aa4b6becc11db5401fc21c33538a60f100bd95c`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.9.24
Commit `61e1ca3` on `main`; this report on top.

## 7. The next hardware test

`docs/TESTING.md` section 42. The roles first:

```
ONEPLUS   topology BUYER_GROUP_OWNER, role GROUP_OWNER, group formed, local 192.168.49.1
OUKITEL   topology BUYER_GROUP_OWNER, role CLIENT, wlan0 still 192.168.1.x,
          p2p0 192.168.49.x, upstream Wi-Fi validated
```

Only once both are present does the probe verdict count. Then cancel and
check that the seller prints `REVERSED TOPOLOGY session cleared` and goes
quiet, then buy again from the same two phones.

## 8. The claim rule

Unchanged, section 30. Nothing is claimed until the reversed topology is
proven on hardware.
