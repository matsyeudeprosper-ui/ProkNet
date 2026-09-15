# CLAUDE_REPORT - ProkNet v0.9.12 "the buyer joins by itself"

Date: 2026-09-15
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 143/143 automated tests pass, released, not yet retested on phones.
Home Wi-Fi resale is NOT claimed.**

## 1. What the run showed

Both radios healthy, the offer visible, and a real group on the seller:

```
seller: sharing by Wi-Fi Direct: GROUP OWNER clients 0
seller Wi-Fi Direct peer list:  00:00:00:00:00:00  available
seller BLE at the same moment:  prok-0f7d57b3  rssi -38
```

Android anonymises the buyer in the OWNER's peer list on these phones. So
the v0.9.9 design, where the owner identifies the buyer by name and invites
it, cannot work here, and guessing from `00:00:00:00:00:00` would be
guessing.

The buyer sees the seller correctly, with its real P2P address. That is the
side that can act.

## 2. The new choreography

```
seller creates the group; the offer claims this way in ONLY while it exists
buyer picks the offer in the normal app
buyer -> seller (BLE): is your group ready? (+ the buyer's own P2P name)
seller -> buyer (BLE): GROUP_READY | REBUILDING_GROUP | NOT_AVAILABLE
                       carrying the SELLER's own Wi-Fi Direct name
buyer finds that name in ITS OWN peer list (real address, never anonymised)
buyer calls connect() -> joins -> seller clients = 1
socket, signed authentication, tunnel, VPN, accounting: unchanged
```

- `Wire.OP_P2P_STATUS` with `P2P_READY / P2P_REBUILDING / P2P_NOT_AVAILABLE`
  and the seller's name; `P2pPlan.groupStatus(...)` decides it.
- `P2pPlan.anonymous(address)` rejects `00:00:00:00:00:00`,
  `02:00:00:00:00:00` and empty everywhere, including inside
  `P2pLink.connectTo`, which refuses to dial one.
- `P2pPlan.pickSellerPeer(peers, sellerName, groupOwners)` matches by the
  name that came over BLE, and falls back to "the peer that owns a group"
  when an older seller sends no name.
- `P2pPlan.joinStep(...)` -> ASK_STATUS / WAIT_PEER / CONNECT / RETRY_BUSY /
  WAIT_REBUILD / FAIL_NOT_AVAILABLE / GIVE_UP / DONE. BUSY is retried at 3,
  6, 12 and 24 s, four attempts; NOT_AVAILABLE fails instantly; a minute
  ends it with a French sentence. One BLE request per ten seconds, as in
  v0.9.11.
- Owner-side inviting survives only as a manual button in the Wi-Fi Direct
  Lab. The normal path never needs it.

## 3. Diagnostics

The log now names each step: the group forming on either side with role and
client count, "answered GROUP_READY, its Wi-Fi Direct name is ...", the
peers this phone can really address versus how many are anonymised, "buyer
connect() requested", accepted or refused with Android's reason and the next
delay, the socket line, the signed handshake, the contract, VPN and
INTERNET OK. COPY P2P DIAG carries the last join attempt and the real peer
list.

## 4. Tests (143, +4)

The owner's list anonymises the buyer and the buyer still finds the seller;
the three seller answers and what the buyer does with each; BUSY retried
with a growing bounded delay then given up; a second purchase starting from
a clean slate; and the status message surviving the wire with the seller's
name. Nothing anywhere may act on `00:00:00:00:00:00`.

## 5. Build

Build 25, versionName 0.9.12, 1.25 MB,
SHA256 `54cc42c5d61e43c4fb5c61c2f4216f10bc89965ea1fd191fabf8a74d2adeb1e4`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.9.12
Commit `CODE_COMMIT` on `main`; this report on top.

## 6. Preserved

v0.9.10 BLE healing, the authenticated socket, the tunnel, the VPN, the
accounting, the marketplace, the normal screens, and method A for
mobile-data sellers: all untouched.

## 7. The claim rule

`docs/TESTING.md` section 30 sets the exact condition, and I will not call
home Wi-Fi resale solved before it passes: seller mobile data OFF and on the
Freebox throughout, buyer mobile data OFF and not on the Freebox, the buyer
reaching INTERNET OK and Chrome loading pages.
