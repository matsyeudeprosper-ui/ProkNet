# CLAUDE_REPORT - ProkNet v0.7.0 "Connectivity Marketplace"

Date: 2026-09-13
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 80/80 automated tests pass, released, NOT yet tested on phones**

v0.6.1 phone result: A with its own data OFF browsed HTTPS through B's mobile
data: WIFI UP, TUNNEL UP, VPN UP, INTERNET OK, Net test, real usage
accounting. The core concept is proven. This block makes it tradable.

## 1. What was built

| Block | Done | Summary |
|---|---|---|
| A. SELL | yes | SELL button -> price/MB, optional minimum session price, optional max MB; seller line shows upstream type, Internet available/validated, price, current buyer, MB sold, amount earned, current session; the offer (price, upstream, validated, relay flag, seller ID) is in the BLE scan response with the signal |
| B. BUY | yes | ranked offer list (`prok-xxxx  mobile data  5 CFA/MB  signal good  available`), tap + BUY; reuses the v0.6 path unchanged: BLE -> Wi-Fi link -> authenticated tunnel -> VPN |
| C. Session agreement | yes | 62-byte contract: session id, buyer id, seller id, price/MB, min price, max MB, fee %, start time, pricing version; buyer proposes with its signature, seller accepts with its signature; both stored on both phones; SESSION_START carries the contract hash and the seller refuses anything else |
| D. Trusted usage accounting | yes | up/down/billable bytes, price, running cost, final cost, start/end, disconnect reason on both sides; seller-signed checkpoints every 30 s or 1 MB, buyer-verified and countersigned; duplicates and reconnects handled by sequence rules; final cost from the last mutually signed checkpoint on both sides |
| E. Internal ledger | yes | immutable content-hash ids, session, payer, recipient, amount (centimes), reason, timestamp, status pending/settled/disputed/cancelled; accounting only |
| F. Network fee | yes | configurable %, default 5; `Market.split` in the pure module; buyer 100 -> seller 95, Prok 5 |
| G. Settlement foundation | yes | MARK AS PAID (payer), MARK AS RECEIVED (recipient), both -> settled; dispute; cancel; balances; history. No payment API |
| H. RELAY | yes, honestly | RELAY toggle advertises the flag; diagnostics count packets this phone actually forwarded (existing carry-forward). Live Internet stays buyer -> seller -> Internet |
| I/J. Modes and auto-connect | yes | top row BUY / SELL / RELAY / Ledger / History; BUY triggers the whole v0.6 sequence; Wi-Fi link / provide / use are now `dev:` diagnostic buttons |
| K. Ranking | yes | deterministic pure score: validated (100) + price (60 minus 3 per CFA) + signal (0..40); unavailable last; ties by ID; tested |
| L. History | yes | per session: peer, date, MB signed vs counted, duration, price, final cost, status/reason |
| M. Diagnostics | yes | COPY LOG / COPY DIAG kept; market state, advertised and selected offer, agreed price, billable bytes, running and agreed cost, checkpoint number, ledger balance and entries, relay activity |
| N. Fraud groundwork | yes | see section 4 |
| O. Tests | yes | `MarketTest` 9 tests covering the whole list; 80 total gate the build |
| P. Preserve | yes | v0.6.1 tunnel, VPN, DNS/TCP, link, crypto, transfers untouched except the added frames; `useInternet` kept as a dev path |
| Q. Scope | kept | no Mobile Money API, custody, cashout, multi-hop, reputation |

## 2. Where the economics live

`core/Market.kt`, pure Kotlin, no networking, no floating point:

- **Units**: price in CFA per MB, MB = 1,000,000 bytes, costs and ledger in
  centimes (1/100 CFA), rounding half up, exact integer arithmetic with
  overflow bounds (bytes <= 1 TB, price <= 100,000 CFA/MB).
- `costCentimes(bytes, price)`, `sessionCost(bytes, price, min)`, `split(gross, feePct)`.
- Contract encode/decode/validate/hash, `acceptableProposal` (seller rule),
  `contractSignData`.
- Checkpoint encode/decode, `nextCheckpoint`, `validateCheckpoint` (buyer
  rule), `checkpointSignData`, `finalCost`.
- Ledger `Entry`, `entryId` (content hash), `sessionEntries`, `transition`
  (settlement state machine), `balance`.
- Offer flags, `score`, `rank`.

`Gateway` and `TunnelClient` only call these and move bytes.

## 3. Protocol additions

Tunnel frames 13-17 on the authenticated link: `CONTRACT_PROPOSE`
(contract + buyer sig), `CONTRACT_ACCEPT` (hash + seller sig),
`CONTRACT_REJECT` (reason, or `terms:price,min,max,fee` so the buyer can
re-propose once at the advertised price), `USAGE_CHECKPOINT` (checkpoint +
seller sig), `USAGE_ACK` (checkpoint + buyer sig). `SESSION_START` now
carries the contract hash. BLE scan response: `[2][id 16][flags 1][price u16]`.

Sequence on the phones: BUY -> (Wi-Fi link if needed) -> CONTRACT_PROPOSE ->
CONTRACT_ACCEPT -> SESSION_START(hash) -> SESSION_OK -> VPN consent -> VPN UP
-> checkpoints every 30 s -> STOP -> final checkpoint -> SETTLEMENT on both
-> ledger entries on both.

## 4. Fraud groundwork implemented

| Threat | Defence |
|---|---|
| session id reuse | seller checks new session ids against every stored session |
| replayed / duplicate receipt | checkpoint sequence must strictly increase; duplicates ignored on both sides; ledger ids are content hashes (idempotent) |
| seller inflates usage | buyer accepts a checkpoint only within 64 KB + 10% of its own counter, otherwise disputes and refuses to sign; unsigned usage is never billed |
| buyer under-reports | the seller's counter is the one in the checkpoint; the buyer can only refuse to sign, which caps the seller's loss at one interval (30 s / 1 MB) |
| price change mid-session | terms are in the signed contract; the seller accepts only its exact current terms; a new price means a new session |
| identity substitution | contract buyer/seller ids must equal the Wi-Fi-authenticated link identities on both sides; all signatures verified with the learned keys |
| malformed / negative / overflow values | rejected at decode for contracts, checkpoints, prices, fees, max MB |

Not yet: cross-phone ledger reconciliation (each phone keeps its own copy of
the same entries), signed final receipts exchanged after the session, and
any real payment verification.

## 5. Automated tests

`total=80 failed=0 errors=0 skipped=0`. New `MarketTest`:

| Required | Test |
|---|---|
| offer encode/decode | `offer_flags_and_scan_encoding_round_trip` |
| ranking | `ranking_is_deterministic_and_prefers_validated_cheap_strong` |
| byte -> CFA, rounding | `bytes_to_cfa_is_exact_integer_math_with_half_up_rounding` |
| Prok fee split | `prok_fee_split_sums_exactly` |
| session agreement, malformed values | `contract_round_trips_and_rejects_malformed_values` |
| price locking, identity mismatch, session id reuse | `seller_accepts_only_its_own_terms_with_the_link_peer_and_fresh_session_ids` |
| signed checkpoints, duplicate / out-of-order rejection | `checkpoints_are_sequenced_signed_and_checked_against_the_buyers_own_count` |
| same final cost on both sides, ledger entries, reconnect/finalisation | `both_sides_reach_the_same_final_cost_and_ledger_from_the_signed_checkpoint` |
| settlement state changes | `settlement_state_machine` |

Two tests failed on the first run, both test-side: a wrong expected number
(10 GB at 5 CFA/MB is 50,000 CFA) and a ranking where signal outweighed
price; the weights were changed so that price dominates (3 points per CFA vs
40 points across the whole signal range). The gate refused the APK until green.

## 6. Physical-phone test

`docs/TESTING.md` section 15. In short: B SELL at 5 CFA/MB; A BUY from B,
approve the prompts; browse; both lines show identical "agreed" cost at the
same checkpoint number; A STOP BUY; both logs print the same SETTLEMENT
amount; Ledger on both shows the gross entry and, on B, the fee entry; mark
paid on A, received on B. Send COPY DIAG from both after the settlement.

## 7. Exact APK / release

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.7.0
Build 10: 1.06 MB, SHA256 `e6edca3ba3b6010815b92dbcc14eeed7334546e309ea613ff7b4a52204a00011`,
versionCode 10, versionName 0.7.0. Commit `310f0897855a890c8b5714142a3d810802f17917`
on `main`; this report on top.

## 8. Known limitations and honest uncertainties

- Each phone books the session in its own ledger from the same signed
  checkpoint; there is no exchange of the final entries yet, so "same
  result" is by construction, not by mutual acknowledgement. A signed final
  receipt frame is the natural next step.
- The minimum price and max MB are not in the scan (space); the buyer learns
  them through one rejection round-trip and re-proposes at the advertised
  price. The user is not asked to confirm min/max; the log shows them.
- The buyer stops itself at max MB from its own count; the seller from its
  count: both end the session, whichever notices first.
- Untested on phones: the contract round-trip timing on a fresh link, and
  checkpoint cadence under real browsing. Both are logged step by step.
- VPS disk: 1.4 GB free; build.ps1 refuses below 1 GB.
