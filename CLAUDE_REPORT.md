# CLAUDE_REPORT - ProkNet v0.9.1 "relay introduction handshake"

Date: 2026-09-14
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 107/107 automated tests pass, released, NOT yet retested on phones**

## 1. What the 3-phone test proved

The hardware question is answered, positively. On the OUKITEL relay:
`STA+AP concurrency: true`, two interfaces live (`wlan2=10.168.138.22` for
its own hotspot, `wlan0=10.115.178.134` on the seller's), DOWN link WIFI UP
with the buyer (host socket on hotspot `AndroidShare_5999`), UP link WIFI UP
with the seller (client socket bound to network 104), both signature-verified.
**No redesign around Wi-Fi Direct.** The relay topology is supported.

What failed was the protocol: `to seller 0 B, to buyer 0 B` for 93 s while
the buyer logged `did not introduce a seller within 20 s`.

## 2. Root cause (confirmed in the code)

v0.9.0 introduced both sides exactly once, unsolicited, inside
`startSession`, which runs only when `session == null && up != null && down
!= null`. The relay's links came up at 13:27:22; the buyer tapped CONNECT
around 13:29. By then the session existed, so nothing was re-sent and
nothing could be: the buyer had no way to ask. Its 20 s timer then fired.
Worse, `sendRaw` returning true was taken as "introduced", although it only
means the frame reached the link's writer queue.

## 3. Fix: a request/answer handshake, all decisions pure

```
A taps CONNECT
  A -> B : RELAY_INTRO_REQUEST (carries A's own self-certifying record)
  B      : Relay.onIntroRequest(relayMode, downPeer, upPeer, sellerSelling, from)
  B -> A : UPSTREAM_SELLER (C's record + current price)        | or NO_UPSTREAM
  B -> C : DOWNSTREAM_BUYER (A's record)
  A -> B : INTRO_ACK (echoes C's record)   C -> B : INTRO_ACK (echoes A's record)
  A      : contract proposal, sealed for C, forwarded by B
```

- **Idempotent by construction.** The answer is a function of the relay's
  CURRENT state only, never of what was already sent, so a late request, a
  repeated request, or a request after a failed attempt all get a real
  answer. The buyer asks up to 5 times, 2.5 s apart
  (`Relay.buyerStep` -> ASK / START_CONTRACT / NO_SELLER / GIVE_UP).
- **Explicit negative answer.** A relay with no upstream seller replies
  NO_UPSTREAM, so the buyer stops in a few seconds with a real reason
  instead of a silent 20 s timeout.
- **Acknowledgement.** Each introduced side sends INTRO_ACK naming the
  identity it stored; the relay marks `ackedDown` / `ackedUp` and shows
  them in the Relay Lab, the status line and COPY RELAY DIAG. A write that
  succeeded but was never processed is now visible as
  `intro 2/2 ack -/seller`.
- **Works in every order:** buyer first, seller first, links idle for
  minutes, or a retry after a failure.

## 4. Relay link cleanup and retry

- `Relay.onLinks(relayMode, sessionUp, sessionDown, up, down)` ->
  START / RESTART / END / KEEP / IDLE replaces the ad-hoc comparison. A
  closed downstream link ENDs the session (and PEER_GONE goes to the seller,
  which drops its session with that buyer); the same buyer linking again
  STARTs a fresh one; a different upstream RESTARTs.
- `LinkState.staleLinkRequest(peer)`: a peer we still believe we are linked
  to, asking for a new link, means its side is gone. `WifiTransport` now
  tears the stale link down and hosts a fresh hotspot instead of ignoring
  the request, which is what used to leave the buyer timing out in
  REQUESTING for ever.

## 5. Consumer wording

`ProductState.lostHint(lastError)`: only a genuine radio failure ("link
closed", "network lost", "out of range", "hotspot", "could not reach",
"signal") still says "Move closer to the provider and try again". A
negotiation that failed on a perfectly good link now says
**"Couldn't build the connection. Try again."** Empty error: "Try again".

## 6. Coverage fix

`InternetSource.usable` now requires `validated` as well as an allowed
trust class and non-zero reliability, so an open but unvalidated network
(captive portal, dead uplink) is never planned on. Its rejection reason is
"not usable: not validated", kept distinct from "not redistributable".

## 7. Tests (107, +7)

New `RelayHandshakeTest` (5) reproduces the hardware failure exactly:
- **the late buyer**: links up, session already formed, introductions
  already sent once, KEEP on every link change (what v0.9.0 did) - then the
  buyer asks and gets INTRODUCE, twice in a row.
- **fail fast**: no upstream / not selling -> NO_UPSTREAM -> NO_SELLER;
  unanswered -> GIVE_UP after 5 asks; wrong buyer / not a relay ignored.
- **end to end after a late introduction**: request parsed, both sides
  re-introduced, both ACKs matched to the session, then the buyer's real
  CONTRACT_PROPOSE sealed with the static-ECDH key, forwarded UP by
  `Relay.forward`, unreadable with the relay's own key, opened by the
  seller, routed to the GATEWAY, signature verified, and the decoded
  contract still names the BUYER (the relay is never billed).
- **close and come back**: END -> nothing forwards -> START -> INTRODUCE ->
  forwards again, plus `staleLinkRequest` on a live LinkState.
- **strangers dropped**, roles well-formed, record required exactly for the
  four roles that carry one.

Also +1 CoverageTest (unvalidated source not planned on, and it does not
hide a good one) and +1 ProductStateTest (protocol failure does not tell
the user to walk; radio failure still does).

## 8. Build

Build 14, versionName 0.9.1, 1.16 MB,
SHA256 `bd603c95675994653a9d4f2cc0ecfbcb76db390d29651bfe7d5d4f25f2cb8a23`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.9.1
Commit `CODE_COMMIT` on `main`; this report on top.

## 9. Preserved

Direct buy/sell (v0.7.1) untouched: with no relay involved,
`providerShort` / `relayedBuyer` stay null and every frame goes through
`wifi.sendTunnel` as before. The sealed-frame format, the coverage planner
and the v0.8 consumer screens are unchanged apart from the hint wording.

## 10. Retest

`docs/TESTING.md` section 19: deliberately reproduce the failing order
(relay links first, buyer connects minutes later), then the other order, a
retry after Stop, and the no-upstream negative case.

## 11. Lesson recorded

A one-shot unsolicited announcement is not a handshake. Any state a peer
must have before it can act has to be obtainable ON DEMAND, and delivery
must be confirmed by the receiving application, never by a successful
write. Both halves are now pure functions with tests that fail against the
v0.9.0 rule.
