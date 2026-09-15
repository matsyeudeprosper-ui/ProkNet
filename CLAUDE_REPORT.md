# CLAUDE_REPORT - ProkNet v0.9.19 "the band is the last lever"

Date: 2026-09-15
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 159/159 automated tests pass, released, not yet tested on phones.
Home Wi-Fi resale is NOT claimed.**

## 1. The measurement, with everything else in place

The v0.9.18 run produced the probe verdict on the seller, with the Wi-Fi
radio lock held:

```
15:32:18.114  LINK PROBE: a packet DID cross, U1 from 192.168.49.124, answering it twice
15:32:19.702  ... U2      15:32:21.307  ... U3      15:32:22.923  ... U4      15:32:24.514 ... U5
15:32:24.313  LINK PROBE verdict: packets arrive here but our answers do not get back
              (sent 10, unicast replies 0, broadcast replies 0, probes answered by us 4)
```

Every probe the customer sent arrived. The provider answered each one twice,
unicast and broadcast, and neither came back. The provider's own ten probes
got nothing.

Three things follow, and they are measurements, not opinions:

1. **The Wi-Fi radio lock does not fix the downlink.** v0.9.16 is answered.
2. **Broadcast does not cross either**, so the two phones failing to address
   each other is ruled out.
3. It is not the sockets, the binding, the listener lifecycle, the membership
   handshake or discovery. Every one of those is correct and has been
   measured as correct.

What is left is the radio itself. The provider's group follows its home Wi-Fi
onto 5 GHz channel 48, so one radio serves a home network and a group on one
channel, and only one direction survives.

## 2. The last software lever: ask for the other band

`P2pPlan.groupBand(staFreqMhz)` asks for 2.4 GHz when this phone's own Wi-Fi
is on 5 GHz, and lets Android choose otherwise. The group is created with
`setGroupOperatingBand(GROUP_OWNER_BAND_2GHZ)`, which requires a named group,
so it now carries a fixed name and a per-run passphrase.

It is a request, not an assumption. If Android refuses it, the plain group is
created instead and the log says which happened. `GROUP CHANNEL:` reports
what was actually granted, so the experiment cannot silently not run.

I chose this over the owner-invite association because the probe result says
the fault is a direction, not an association: the client is addressable, the
owner answers, and the answer dies in the air.

## 3. The bug that made the last run produce nothing at all

```
15:33:00.079  not starting discovery: this link already has a peer on it
16:14:17.991  (buyer) looking for it ... (0 seen, 0 with a real address): none addressable
```

v0.9.15 stops discovery when somebody joins. It never started it again when
they left: the client count reached zero a few milliseconds after that
decision was taken, so the provider sat holding a group, invisible, for forty
minutes, through four buyer attempts.

Discovery now comes back the moment the link has no peer on it, from the
client count AND from the data plane, and `keepDiscovering` re-checks the
rule instead of trusting the caller. This is why the last two runs showed
"0 seen, none addressable" and gave up "after 0 attempts".

## 4. Tests (159)

The band rule on 5 GHz, 2.4 GHz and no Wi-Fi, and that the group name is a
legal Wi-Fi Direct name. The findability rule: a provider WITH a customer
does not discover, and the same provider the moment the customer leaves does.

## 5. Build

Build 32, versionName 0.9.19, 1.29 MB,
SHA256 `c513758557a5f0c9b6d656afea72f9f91766f6337d5e4234b99ad971336b7acc`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.9.19
Commit `185082b` on `main`; this report on top.

## 6. What the next run decides

`docs/TESTING.md` section 37.

- `GROUP CHANNEL:` says 2.4 GHz and the probe verdict becomes "the link
  carries IP packets both ways": the transport is solved and the rest of
  section 30 follows.
- `GROUP CHANNEL:` says 2.4 GHz and the verdict is still one way: **the
  software levers are finished.** These two phones cannot carry a Wi-Fi
  Direct data path while the provider stays on its home Wi-Fi, and that is a
  product decision, not a bug. The honest options then are a third phone as
  the group owner, method A on a provider with mobile data, or a different
  provider handset.
- Android refuses the band: the phone will not take the request, and the same
  conclusion applies with one fewer experiment available.

## 7. The claim rule

Unchanged, section 30. Home Wi-Fi resale is not claimed, and I will not claim
it on anything but the full hardware condition.
