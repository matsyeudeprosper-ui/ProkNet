# CLAUDE_REPORT - ProkNet v0.9.9 "the group owner invites, and the normal app drives it"

Date: 2026-09-14
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 128/128 automated tests pass, released. The real-phone flow
has NOT been run: home Wi-Fi resale is not claimed.**

## 1. What the v0.9.8 run established

Seller: group forms, role GROUP_OWNER, 192.168.49.1 present, **still
connected to the Freebox on wlan0**, buyer visible in its peer list,
`clients` always 0.
Buyer: sees the seller, taps it, `connect()` accepted, the seller turns
"invited", no group ever forms, role stays NONE.

So the hard part already works: a phone can host a Wi-Fi Direct group while
it stays on its home router. Only the admission was wrong.

## 2. Why, and the fix

A phone that already OWNS a group cannot join another one, so the buyer's
invitation had nowhere to land. The invitation has to travel from the owner
to the guest.

```
seller creates the group, stays on the router
buyer becomes discoverable and WAITS
buyer -> seller (BLE): P2P_REQUEST("<its Wi-Fi Direct name>")
seller finds that name in its peer list and calls connect() = invitation
buyer joins -> seller clients = 1 -> socket -> existing authenticated link
```

- `P2pPlan.joinRole(iOwnAGroup)` -> OWNER_INVITES / GUEST_WAITS.
- Android hides a phone's own P2P MAC since Android 10, so the buyer cannot
  send its address. It sends its device NAME over the BLE channel that
  already exists (`Wire.OP_P2P_REQUEST`), and `P2pPlan.matchPeer` matches it
  in the owner's peer list: exact, then case-insensitive, then contains.
- Discovery is kept alive on both sides while waiting (Android stops it on
  its own after a couple of minutes).
- The guest is never silent: `guestStep(elapsed, groupFormed, ownerVisible)`
  -> WAIT, **ASK_AGAIN at 12 s**, **TRY_MYSELF at 24 s** (only when the
  owner is visible, for the case where Android wants the guest to move),
  **GIVE_UP at 45 s** with a real message.
- Refusals have their own code `Wire.CANCEL_P2P` with the reason: "the
  provider has no Wi-Fi Direct group right now", "the provider cannot see
  this phone in its Wi-Fi Direct peer list", or Android's own error.
- The lab's peer list now INVITES when this phone owns the group, and only
  joins when it is a guest.

## 3. The normal app can run it

- Seller taps **PARTAGER INTERNET**: mobile data upstream keeps the
  hotspot; a Wi-Fi upstream whose hotspot probe says CANNOT_SHARE now
  creates a Wi-Fi Direct group instead of giving up, and advertises it with
  a new BLE bit (`Market.FLAG_P2P`, bit 6, above the upstream bits). The
  card says "Partage activé par liaison directe entre téléphones (essai)".
- Buyer taps **OBTENIR INTERNET**, picks the offer, SE CONNECTER. If the
  offer carries that bit, `buy()` takes the Wi-Fi Direct path instead of
  requesting a hotspot. The buyer sees the usual French states and never the
  words P2P, group owner or invitation.
- Stopping the purchase or switching sharing off tears the group down
  through the v0.9.8 cleanup.

## 4. Not touched, on purpose

The tunnel, the VPN, the marketplace and the accounting are unchanged: the
P2P socket is still adopted by `WifiTransport`, so everything above the link
is the same code as method A. The hotspot path is untouched and is still
what a mobile-data seller uses.

## 5. Tests (128, +4)

`P2pPlanTest`: the owner invites and the guest waits (the exact v0.9.8
shape); the guest ladder including TRY_MYSELF only when the owner is
visible and GIVE_UP with a reason; name matching in the owner's peer list
with the three fallbacks; and the advertised flag, which must not disturb
price, upstream, validated or the relay bit, plus the request and the
refusal surviving the wire.

## 6. Build

Build 22, versionName 0.9.9, 1.24 MB,
SHA256 `3a471c62324ce26ed91ada787855f76515af351157898cdc2367edea7307c084`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.9.9
Commit `757a41d87e6ae9cf3bbdc75b249646606d53bb86` on `main`; this report on top.

## 7. What must pass before anything is claimed

`docs/TESTING.md` section 27, from the normal screens: the seller stays on
the Freebox, the buyer uses OBTENIR INTERNET, the seller shows **clients
joined: 1**, the ProkNet link comes up, the VPN starts, and Chrome loads
pages whose traffic leaves through the Freebox. Until that, this is still an
experiment.
