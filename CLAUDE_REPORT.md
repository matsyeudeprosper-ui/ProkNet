# CLAUDE_REPORT - ProkNet v0.9.23 "the clean answer, and the reversed topology"

Date: 2026-09-15
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 181/181 automated tests pass, released, not yet tested on phones.
Home Wi-Fi resale is NOT claimed.**

## 1. The 2.4 GHz question is answered, and the answer is no

v0.9.22 produced the first uncontaminated run:

```
19:38:15.047  formed=true role=CLIENT groupOwner=192.168.49.1
19:38:15.059  DISCOVERY off
19:38:15.072  GROUP CHANNEL: 2.4 GHz ch 6 (2437 MHz)
19:38:31.109  LINK PROBE verdict: NO IP packet crossed the Wi-Fi Direct link in either direction
              (sent 10, unicast replies 0, broadcast replies 0, probes answered by us 0)
```

Both v0.9.22 lifecycle fixes worked: a fresh association clock, no discovery
after membership, a real group, and the 2.4 GHz request granted. So the
result counts: **forcing the group to 2.4 GHz does not fix the
provider-as-group-owner topology on these two phones.**

Every layer above it passed in the same run: BLE control, symmetric
admission, the Android confirmation, the association, the membership
exchange, TRANSPORT_READY. Only the IP path failed.

## 2. The state bug you found

```
19:38:31  association in flight for 16s
19:38:55  the provider could see this phone, but the invitation did not complete
```

False, and my bug. `ladder(...)` did not know membership had happened, so the
association clock started before the join eventually expired and killed a
session that was already past admission.

`ladder(associationStartedAt, now, searchedMs, hasMember)` now returns
MEMBER_JOINED as soon as a client is on the link, forever. The node clears
the admission clock at that moment and logs `MEMBER JOINED: admission is
over, the transport now has 45s`. From there only the transport deadline can
end the session.

## 3. Failures are filed by stage

```
SEARCH_FAIL       neither phone could address the other
ASSOCIATION_FAIL  an invitation or a join did not complete
TRANSPORT_FAIL    the group exists and no IP packet crosses it
TUNNEL_FAIL / INTERNET_FAIL
```

`P2pAdmission.stageOf(reason)` files every ending, the log prints `PURCHASE
FAILED at stage ...`, and the French sentence matches. The v0.9.22 run was a
TRANSPORT_FAIL, so the screen now says the direct link was created and the
network link between the two phones does not answer, instead of blaming an
invitation that had succeeded.

## 4. The evidence survives cleanup

`core/P2pReport.kt`: one record per phone, written as the attempt happens,
replaced only when the next test starts. Time, topology, role, group channel,
home channel, peer, association, membership, when discovery stopped, both IP
addresses, the four UDP counters, TCP accepted and connected, verdict and
failure stage. It prints at the end of the Wi-Fi Direct diagnostic. That is
how the seller's probe counters for the 19:38 run will not be lost again.

## 5. The reversed topology

```
SELLER_GROUP_OWNER   production, unchanged
BUYER_GROUP_OWNER    the customer owns the group, the provider joins it as a
                     client and keeps its Freebox connection
```

- `P2pLink.startGroupOwner(providing)` / `startGuest(providing)` separate the
  Wi-Fi Direct role from the ProkNet role. The provider is still the
  authenticated host whichever phone created the group.
- The admission plan is named by Wi-Fi Direct role, so one rule serves both
  arrangements: `GUEST_CONNECT`, `OWNER_INVITE`, `WAIT`. The group owner is
  always the phone that decides, and the guest is always the one that
  reports what it can see.
- The customer announces the topology over BLE (`OP_P2P_TOPOLOGY`) when the
  purchase starts; the provider obeys, drops its own group and joins as a
  client, logging that it is staying on the Freebox while it does.
- The toggle is in the Wi-Fi Direct Lab, on the buyer. Production is
  untouched and still defaults to SELLER_GROUP_OWNER.

I have not assumed this will work. It is an experiment with its own
diagnostics, and a failure is still an answer.

## 6. Preserved

Crypto, the signed handshake, the marketplace, the accounting, the tunnel,
the VPN, BLE identity and control, the no-guessing rule, the transport
abstraction, the data plane generations, the binding hierarchy, the UDP
probe, the radio lock, the 2.4 GHz request and the v0.9.22 discovery rule.
Only the Wi-Fi Direct role beneath them can change now.

## 7. Tests (181, +5)

Membership makes the ladder MEMBER_JOINED forever, including at the exact
moment v0.9.22 died. Every failure reason maps to its stage, and the French
for a transport failure no longer mentions an invitation. Who owns the group
is not who sells. The plan rule is identical in both topologies. The saved
record survives and is replaced only by a new test.

## 8. Build

Build 36, versionName 0.9.23, 1.31 MB,
SHA256 `a6ac34837acf458deddf40d7b12ca6cdf68ef0daa350c37e5cc91445ba5026b5`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.9.23
Commit `1b170b7` on `main`; this report on top.

## 9. The next hardware test

`docs/TESTING.md` section 41. Turn the topology toggle on in the Wi-Fi Direct
Lab on the BUYER only, then buy from the normal screen. The question is
whether the OUKITEL can be a Wi-Fi Direct CLIENT while it stays on the
Freebox, when it cannot be a usable group owner.

Copy the LAST P2P TEST RESULT block from both phones whatever happens.

## 10. The claim rule

Unchanged, section 30. Home Wi-Fi resale is not claimed and will not be until
the reversed topology is proven on hardware.
