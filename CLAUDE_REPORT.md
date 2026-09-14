# CLAUDE_REPORT - ProkNet v0.9.7 "Wi-Fi Direct experiment (method B)"

Date: 2026-09-14
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 119/119 automated tests pass, released. The physics is NOT
proven: no phone has run it yet. Home Wi-Fi resale is not solved.**

## 1. What this is

A small proof, not a transport rewrite. Method A (LocalOnlyHotspot) is
untouched and is still the only path the consumer app uses. Method B lives
behind Profil -> developer screen -> Relay Lab -> **WI-FI DIRECT LAB**.

Target topology, which the phones must confirm:

```
home router (Freebox)
      |   seller stays connected: this is what must survive
seller phone
      |   Wi-Fi Direct group
buyer phone  ->  Chrome loads sites
```

## 2. The data path is NOT new

`transport/P2pLink` only builds the pipe: discovery, group formation, and a
connected TCP socket. That socket is then handed to
`WifiTransport.adoptSocket(socket, isHost, medium)`, which runs **the same**
signed HELLO/AUTH handshake, the same LinkIo framing and the same link
object as method A. Everything above it is therefore unchanged: TunnelClient,
Gateway, the VPN, the contract, the checkpoints and the ledger.

In `LinkState`, `adopt(asHost)` moves an idle machine straight to HANDSHAKE
(the peer is learned from the handshake) and returns NONE while method A is
busy, so the experiment can never interrupt a real session.

## 3. Group owner direction is not assumed

`P2pPlan.role(groupFormed, isGroupOwner)` and `socketTarget(role,
groupOwnerAddress)`: whoever ends up group owner listens on port **47742**
(its own port, so both methods can listen at once), the other dials it with
six retries. The seller asks to be the owner (`createGroup`) and the buyer
asks to be the client (`groupOwnerIntent = 0`), but if Android decides the
other way round both sides still work.

## 4. Keeping the home Wi-Fi is half the result

`P2pLink` records the phone's own Wi-Fi network **before** the group and
**after** it, and `P2pPlan.verdict(...)` is explicit about it:

| verdict | meaning |
|---|---|
| NO_GROUP | no Wi-Fi Direct group formed |
| GROUP_BUT_STA_LOST | group formed BUT the phone left its Wi-Fi network: useless for selling home Wi-Fi |
| LINK_FAILED | group and home Wi-Fi kept, but the ProkNet link did not authenticate |
| LINK_UP_STA_KEPT | the one we want |

Seller safety: `Gateway` now treats any `p2p*` interface as a local ProkNet
link, so a customer's traffic can never be routed back into the P2P group
instead of out to the router.

## 5. Diagnostics (COPY P2P DIAG)

isP2pSupported, Wi-Fi Direct enabled, phase, role, group ssid / owner /
clients / interface, the socket line (local and remote address), every IPv4
interface, the phone's Wi-Fi network before and now, whether the ProkNet
link authenticated over P2P, the peer list, the verdict, the last Android
error, plus the buyer/seller tunnel state, all networks and the last 100 log
lines.

## 6. Tests (119, +5)

`P2pPlanTest`: both group-owner directions produce the right listener and
dialler; the experiment is only attempted when the hardware, Wi-Fi and P2P
are there; the four verdicts including GROUP_BUT_STA_LOST; a p2p interface
is never an upstream (also checked through the real `Tunnel.chooseUpstream`);
and the plumbing itself, where `adopt` goes to HANDSHAKE from idle and is
refused while method A is negotiating.

No regression: method A, the relay handshake, the coverage engine and the
marketplace tests are unchanged and green.

## 7. Build

Build 20, versionName 0.9.7, 1.22 MB,
SHA256 `eef95372e0f8f09e4ebb91f3cbe8aaba9dc78cd9c64d775a5adb81f9b5f476f0`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.9.7
Commit `CODE_COMMIT` on `main`; this report on top.

## 8. What the phones must show

`docs/TESTING.md` section 25, two phones, both with mobile data OFF and the
seller on the Freebox. The useful output is COPY P2P DIAG from both phones
plus the verdict line.

I will not claim home Wi-Fi resale works until that test passes. If the
verdict comes back GROUP_BUT_STA_LOST on these phones, Wi-Fi Direct is the
wrong answer for this hardware and the next candidate has to be decided,
not assumed.

## 9. Note on the VPS

The build disk fell under the 1 GB guard again. I cleared Gradle's derived
`transforms-4` cache (431 MB, rebuilt automatically, no downloads) rather
than touching the research data in the old session scratchpad. Free space is
back to about 1.35 GB, and the Windows Update cache (8.6 GB) still needs an
RDP session.
