# CLAUDE_REPORT - ProkNet v0.7.1 "contract routing fix"

Date: 2026-09-13
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 83/83 automated tests pass, released, NOT yet retested on phones**

v0.7.0 phone result: offer visible, WIFI UP, signed handshake, `STATE
AGREEING - proposing 5 CFA/MB`, then `no contract answer within 15s`. The
seller never logged a proposal.

## 1. Root cause (confirmed in the code)

`ProkNetNode.tunnelSink.onTunnelFrame` routed a frame to the seller Gateway
only if it was SESSION_START or came from an already-registered buyer.
The v0.7 negotiation starts with CONTRACT_PROPOSE, before any buyer is
registered, so on the seller the first proposal went to its own buyer
TunnelClient, which ignored it (no matching provider). Gateway.onProposal
was never reached; the buyer's 15 s timer fired. Deterministic, every time.

## 2. Fix

- `Tunnel.route(type, providing)` (pure): every tunnel frame type is
  classified by direction. Buyer -> seller: CONTRACT_PROPOSE, SESSION_START,
  OPEN_TCP, DNS_REQUEST, USAGE_ACK. Seller -> buyer: CONTRACT_ACCEPT,
  CONTRACT_REJECT, SESSION_OK, TCP_OPEN_OK, DNS_RESPONSE, UPSTREAM_STATE,
  USAGE_CHECKPOINT. Both ways: SESSION_END, TCP_DATA, TCP_CLOSE, ERROR,
  KEEPALIVE. A providing phone hands buyer->seller and bidirectional frames
  to the Gateway; a non-providing phone hands seller->buyer and bidirectional
  frames to the TunnelClient; a frame in the wrong direction for the role is
  logged as misdirected and dropped. Nothing depends on `buyerShort`.
- The node uses it. Gateway.onFrame already accepted CONTRACT_PROPOSE and
  SESSION_START before a session and refuses everything else pre-session.
- A phone sells or buys on a link, never both (`setSelling` refuses while
  buying, `buy` refuses while selling), which is what makes direction + role
  sufficient.

Audit of every marketplace frame's direction and handler is in
`TunnelRoutingTest.every_marketplace_frame_is_routed_by_direction_on_both_roles`:
all 17 types are classified, and the test asserts the count.

## 3. Regression test

`TunnelRoutingTest.proposal_accept_and_session_start_sequence_on_a_fresh_link`
runs the real first-frame sequence with the real pure pieces (routing,
contract encode/decode, `acceptableProposal`, both signatures, contract
hash, SESSION_START parsing, first checkpoint direction) with only the
sockets simulated: seller providing, no buyer registered, CONTRACT_PROPOSE
arrives -> routed to GATEWAY -> validated and signed -> CONTRACT_ACCEPT
routed to CLIENT -> buyer verifies and stores -> SESSION_START under the
contract hash routed to GATEWAY and matches.
`first_contract_proposal_reaches_the_seller_gateway_before_any_buyer_is_registered`
also evaluates the v0.7.0 rule, kept as a helper in the test, and asserts
that it misroutes the same frame to CLIENT: the test documents the bug and
fails against the old rule.

Totals: 83 tests (`TunnelRoutingTest` 3 new), all green; APK gated.

## 4. Preserved

v0.6.1 link, VPN, DNS/TCP, transfers, crypto: untouched. The only code
changes are `Tunnel.route`, the sink in `ProkNetNode`, the version and docs.

## 5. Retest

`docs/TESTING.md` section 15, unchanged. Expected on both phones right after
WIFI UP: `CONTRACT AGREED ... (both signatures stored)`, then `SESSION OK`,
`VPN UP` on A, `INTERNET OK`, browsing, `CHECKPOINT #n` lines on both with
identical "agreed" amounts, `SETTLEMENT` on both after STOP BUY, ledger.

## 6. Exact APK / release

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.7.1
Build 11: 1.06 MB, SHA256 `f12bdcb806747f721bf85f348622d8bdbb3c4ced3caa30acc7bcfb47d38c8723`,
versionCode 11, versionName 0.7.1. Commit `d1b9b22396ab1ba443e5e6dbe8c77ed7c640058c`
on `main`; this report on top.

## 7. Lesson recorded

Routing that depends on state created later in the same protocol (a
registered buyer) cannot handle the first frame. Direction + role is the
right key, and it is now a pure function with a test that enumerates every
frame type, so adding a frame without classifying it fails the build.
