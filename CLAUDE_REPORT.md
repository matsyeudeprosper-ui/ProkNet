# CLAUDE_REPORT - ProkNet v0.9.15 "the radio has to be on the group channel"

Date: 2026-09-15
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 153/153 automated tests pass, released, not yet tested on phones.
Home Wi-Fi resale is NOT claimed.**

## 1. What the v0.9.14 run proved

It did exactly what it was built to do: it removed the socket layer from the
list of suspects.

```
seller  p2p-wlan0-27 = 192.168.49.1   GROUP_OWNER  network 159  clients 1
seller  listener 192.168.49.1:47742   generation 1.1  valid for this live membership
seller  DIAL 1..6  192.168.49.1 -> 192.168.49.124:47742   binding ANDROID_NETWORK
buyer   p2p0 = 192.168.49.124         CLIENT       generation 1.1
buyer   listener 192.168.49.124:47742 valid for this live membership
buyer   DIAL 1..6  192.168.49.124 -> 192.168.49.1:47742   binding LOCAL_ADDRESS
BOTH    every attempt: SocketTimeoutException after 4000 ms
```

The membership handshake worked end to end: `P2P MEMBER` arrived, the seller
armed and answered `TRANSPORT_READY` in 0.2 s, the buyer dialled 2 s later.
Two listeners, both armed for the live membership, both on their own P2P
address, dialling each other, twelve timeouts.

Not one `ECONNREFUSED`, not one `ENETUNREACH`. Nothing answered in either
direction. **No IP packet crosses this link.** The socket layer is not the
problem, and neither is the binding.

## 2. What both logs show at the same time

Every thirty seconds, through the entire dial window, on BOTH phones:

```
seller 10:56:52  starting peer discovery from a clean state / discoverPeers accepted
buyer  10:57:02  starting peer discovery from a clean state / discoverPeers accepted
seller 10:57:22  starting peer discovery from a clean state / discoverPeers accepted
```

Wi-Fi Direct discovery takes a single-radio phone OFF the group channel to
scan the social channels, and Android keeps a find running for about two
minutes once it is accepted. Association survives that; beacons are cheap. A
four second TCP handshake does not.

We have been running discovery continuously on both sides since v0.9.9,
when the owner needed a peer list in order to invite the guest. Since v0.9.12
the buyer joins by itself, so after somebody has joined nothing needs it at
all.

## 3. The change

**Discovery belongs to admission, and admission ends when somebody joins.**
The rule is tied to MEMBERSHIP, not to the group, precisely so that the
proven admission path is untouched:

```
seller sharing, group empty      -> discovery ON  (a buyer must still find it)
buyer looking for the seller     -> discovery ON
somebody joined (clients 0 -> 1) -> discovery OFF, on both phones, in the
                                    FRAMEWORK (stopPeerDiscovery), not only
                                    in our own 30 s loop
the group is gone                -> discovery ON again
```

`P2pPlan.discoveryWanted(want, hasLiveMember)` is the whole rule, and it is
tested. A seller with an empty group still discovers, exactly as in every run
so far.

## 4. Measure the link instead of guessing

A timed out SYN says nothing about why. Each side now opens a UDP echo on its
own P2P address, port 47743, for as long as its listener is armed, and the
dialling side sends up to five probes:

```
LINK PROBE listening on 192.168.49.1:47743
LINK PROBE 1/5 to 192.168.49.124: REPLY in 14 ms
LINK PROBE verdict: the link carries IP packets both ways
```

Three possible verdicts, and the next run will produce one of them:

- `the link carries IP packets both ways` -> the radio is fine and the
  remaining fault is above IP, which would be new,
- `packets arrive here but our answers do not get back` -> a one way path,
- `NO IP packet crossed the Wi-Fi Direct link in either direction` -> the
  v0.9.14 situation, and then the fault is the radio or the driver, not us.

It is a diagnostic, never a gate: TCP still runs exactly as before.

## 5. What I did NOT do

No sleeps, no forced reconnects, no repeated blind sockets, no device
conditions. I did not touch discovery before a client joins, the BLE
admission, the buyer-side peer selection, group formation, the membership
handshake, the binding hierarchy, crypto, the signed handshake, the tunnel,
the VPN, the accounting, the marketplace, method A, or the bounded failure.

The provider upstream is untouched: the probe sockets are bound to the P2P
endpoint like every other local socket, and `p2p...` stays a LOCAL link that
can never be an upstream.

## 6. Tests (153)

The discovery rule on every combination, including the one that must NOT
change: a seller whose group is empty still discovers. The link proof: not
run, nothing crossed, one way, alive. Plus everything from v0.9.14.

## 7. Build

Build 28, versionName 0.9.15, 1.44 MB,
SHA256 `b0bbd0e212c2eae40f9900da1b32c6c935923a8f159c73bee4a304d341ffd16b`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.9.15
Commit `ffc4fee` on `main`; this report on top.

## 8. What the next run answers

`docs/TESTING.md` section 33. Two things, and the second one matters even if
the first still fails:

1. does `DISCOVERY off` appear on both phones when the buyer joins, and does
   nothing start discovery again while the customer is there,
2. what does `LINK PROBE verdict` say on each phone.

That verdict separates a radio problem from a software problem for good. If
it says the link carries packets both ways and TCP still times out, the fault
is somewhere new and I will have the evidence to find it. If it says nothing
crossed even with the radio parked on the group channel, then these two
phones cannot carry a Wi-Fi Direct data path while the provider stays on its
home Wi-Fi, and that is a product level answer, not a bug.

## 9. The claim rule

Unchanged, section 30. Home Wi-Fi resale is not claimed.
