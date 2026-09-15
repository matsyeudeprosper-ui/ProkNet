# CLAUDE_REPORT - ProkNet v0.9.16 "the link is one way"

Date: 2026-09-15
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 154/154 automated tests pass, released, not yet tested on phones.
Home Wi-Fi resale is NOT claimed. But the fault is now MEASURED, not guessed.**

## 1. The measurement

The v0.9.15 probe returned two different verdicts, one per phone, and the
pair is the finding:

```
buyer  (client)  LINK PROBE verdict: NO IP packet crossed the Wi-Fi Direct link in either direction
                 (sent 5, replies 0, probes answered by us 0)
seller (owner)   LINK PROBE: a packet DID cross, 10 bytes from 192.168.49.124   x6
seller (owner)   LINK PROBE verdict: packets arrive here but our answers do not get back
                 (sent 5, replies 0, probes answered by us 3)
```

**Every packet the client sent arrived at the owner. Nothing the owner sent
arrived at the client.** Not the UDP answers, not the owner's own probes, and
not a TCP handshake in either dial direction. Uplink perfect, downlink dead.

This closes the question the last four versions were circling. The buyer's
SYN arrives at the owner, the owner's SYN-ACK never gets back, so the buyer
times out and the owner's `accept()` never completes. That is exactly why
`TCP accepted` was never printed while the listener was correct.

The socket layer, the binding, the endpoint identity, the membership
generations and the listener lifecycle are all correct, and the measurement
proves it. The remaining fault is below them.

## 2. Discovery, from the same run

The v0.9.15 discovery rule worked exactly as specified:

```
seller 12:03:36.433  DISCOVERY off: somebody has joined: the radio must stay on the group channel
seller 12:03:36.477  discovery stopped, the radio can stay on the group channel
seller 12:03:36.594  not starting discovery: this link already has a peer on it
buyer  12:03:57.909  DISCOVERY off: somebody has joined: the radio belongs to the data plane now
```

No discovery ran while the customer was in the group, and the link was still
one way, so scanning was not the cause either. The rule stays: it is correct
and it costs nothing. One thing it did prove is that the seller now sees the
buyer by name and with a real address, `OnePlus Nord CE 2 Lite 5G
1e:4f:f2:19:36:ce`, which the v0.9.11 run could not.

## 3. What this version does about it

A group owner has to buffer frames for a client whose radio is asleep and
deliver them at the beacon. On a phone whose single radio is also serving a
home Wi-Fi connection, that is a known place for downlink frames to die. The
driver is not ours. The sleep is refusable.

- **`transport/RadioLock.kt`**: `WIFI_MODE_FULL_HIGH_PERF` plus, on API 29+,
  `WIFI_MODE_FULL_LOW_LATENCY`, held while a group exists on this phone, on
  BOTH sides, released the moment it is gone. Holding a Wi-Fi lock during a
  data transfer is what Wi-Fi Direct expects of an application anyway, and we
  never did it. `RADIO LOCK held: ...` is printed, so a phone that refuses
  both locks says so.
- **The group channel is logged**: `GROUP CHANNEL: 5 GHz ch 48 (5240 MHz) |
  this phone's Wi-Fi: 5 GHz ch 48 (5240 MHz)`. A group forced onto the home
  Wi-Fi channel is now visible instead of assumed.
- **The probe separates unicast from broadcast**, both directions. Each side
  answers a probe twice, once to the sender and once to the group broadcast
  address, and there is a fifth verdict: `only BROADCAST crosses: the two
  phones cannot address each other directly`. A radio that drops everything
  and two phones that cannot resolve each other need different answers, and
  this tells them apart.

## 4. What I did NOT do

No sleeps, no forced reconnects, no blind sockets, no device conditions, and
no change to discovery before a client joins, BLE admission, peer selection,
group formation, the membership handshake, the binding hierarchy, crypto, the
signed handshake, the tunnel, the VPN, the accounting, the marketplace,
method A, or the bounded failure. The provider upstream is untouched.

## 5. Tests (154)

The five link verdicts including the two the phones actually produced, the
broadcast address of a group subnet, and everything from v0.9.15.

## 6. Build

Build 29, versionName 0.9.16, 1.28 MB,
SHA256 `775446712f18414ed520b29bbb6f91900e903557942c93125e4569e6048611d4`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.9.16
Commit `3087c2b` on `main`; this report on top.

## 7. If the lock does not change the downlink

Then the software levers above IP are finished, and there are two left, in
this order:

1. **The association direction.** The only session that ever carried traffic,
   v0.9.9 with 70 minutes and 34 MB, was the one where the OWNER called
   `connect()` to invite the guest. Since v0.9.12 the client joins by itself.
   The seller can now see the buyer by name again, so the owner invite is
   available as a data-plane experiment, and it is the only known difference
   between a link that carried traffic on these two phones and one that does
   not.
2. **The group band.** Android 10+ can ask for a group on a chosen band
   (`setGroupOperatingBand`). The group currently follows the seller's home
   Wi-Fi onto 5 GHz channel 48. A 2.4 GHz group would put the radio in dual
   band concurrency instead of sharing one channel.

Both are one change each, and both should be tried one at a time, with the
probe verdict as the measurement. I did not bundle them into this build
because the Wi-Fi lock has to be ruled in or out on its own.

## 8. The claim rule

Unchanged, section 30. Home Wi-Fi resale is not claimed.
