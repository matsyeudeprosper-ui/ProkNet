# CLAUDE_REPORT - ProkNet v0.15.0 "Prok Wallet + real settlement"

Date: 2026-09-20
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 363/363 Android tests and 23/23 server tests pass, released
as build 58. The seller-stop stability gate passed before any wallet code was
written. Hardware acceptance is TESTING 61 (gate) then 62 (wallet); neither
has been run.**

Commits, so there is a rollback point between the two halves:

| | |
|---|---|
| `9266678` | fix: symmetric seller-stop graceful settlement |
| `0611bee` | feat: settlement obligation core |
| `da4c8ef` | feat: wallet persistence, session wiring, UI, settlement backend |

---

# PART A — the stability gate

## 1. Seller-stop root cause

You were right, and it was the exact twin of the bug v0.14.2 fixed on the
other side. `Gateway.stop()` did this:

```kotlin
endSession("seller disabled")   // issues the closing checkpoint AND settles, in one breath
contract = null                 // ...then forgets the contract
```

`endSession` called `checkpointIfDue(true)`, which put a closing checkpoint on
the wire, and then immediately called `finalizeContract(c, reason)`, which
settles from `lastSigned` — the **previous** checkpoint, because the buyer had
not countersigned the new one yet. It then cleared everything, so when the
buyer's countersignature did arrive, `onUsageAck` returned at its first line:
`val c = contract ?: return`.

For a session shorter than the 30 second checkpoint interval, `lastSigned` was
null, so the seller settled at **zero for real usage**. Stop-before-checkpoint
was free again, just from the other phone.

`ProkNetNode.setSelling(false)` had the same shape one level up: it called
`gateway.stop()` and cancelled the bulk link in the next statement.

## 2. The fix

One machine, both directions. `Teardown` gained a `Cause` (LOCAL_STOP,
PEER_STOP, LINK_LOST, TIMEOUT). **The role decides which frame goes out first;
it never decides what the session costs.**

Seller stop is now: stop accepting buyer traffic, keep the link up, issue the
closing checkpoint, wait for the countersignature under the existing 4 second
bound, settle on it, tell the buyer, and only then release the providing
state. `contract`, `session`, `lastIssued`, `lastSigned` and `buyerShort` are
untouched until settlement may complete (A3). The node defers its bulk cancel
to `gateway.onStopComplete`.

The buyer treats `SESSION_END` as an ending rather than a fault (A4): it
closes the VPN via the existing attempt-failed path and shows **"Le fournisseur
a arrêté le partage."** No invented network error.

If the buyer never countersigns, both sides settle on the newest checkpoint
both signed, and nothing is invented.

## 3. Seller-stop tests (the gate)

Six, all on the production decision path:

- 5, 15, 29 and 31 second sessions the **seller** ends all bill above zero,
  at or below the budget, with the seller in profit, the fee exactly the
  agreed share, and the buyer told the provider stopped.
- **Buyer-stop and seller-stop over identical signed usage settle
  identically**, to the centime, including the split and the ledger total.
  Checked at 5, 20 and 45 seconds. This is the invariant the money layer rests
  on, and it is the one you asked for.
- Seller Stop twice settles once.
- A buyer that vanishes before the ack settles on what was already mutually
  signed.
- A seller stop with nothing signed and no link owes nothing.
- The seller can serve the next buyer at once, three times.

All 327 previous tests stayed green, unmodified. That gate was committed on
its own as `9266678` before any wallet code existed.

---

# PART B — Prok Wallet

## 4. Architecture

An **obligation layer, not a bank**. Prok coordinates and verifies payment and
holds nobody's money. Every decision below follows from that, including the
wording: À payer, À recevoir, Payé, Reçu, Gagné — never a balance.

Four pure files, no Android, integer centimes throughout:

- `core/Settlement.kt` — the obligation, its status machine, derivation,
  reconciliation, idempotent payment events.
- `core/Wallet.kt` — the view, netting per seller, the sentences a person
  reads.
- `core/SettlementPolicy.kt` — may a paid session start, given what is owed.
- `core/PaymentRails.kt` — the rail adapters.

## 5. The obligation model

`Settlement.fromSession(contract, finalSignedCheckpoint, now)` is the **only**
way to make one. The UI can never name an amount. Fields: settlementId,
sessionHex, buyerId, sellerId, finalCheckpointHash, gross, sellerNet, prokFee,
createdAt, expiresAt, status, rail, paymentReference, note. Status: PENDING,
PAYMENT_INITIATED, PAYMENT_SEEN, CONFIRMED, FAILED, EXPIRED, DISPUTED.

It returns **null** in three normal cases: a free session (rate 0), a session
with no mutually signed checkpoint, and a legacy v1 session.

## 6. Derivation from signed truth, and symmetry

Gross is the cost of the checkpoint both phones signed; fee and seller net come
from the same `Market.split` the session used. The identity

```
buyer obligation = seller receivable + Prok fee
```

is asserted on every obligation (`balanced`) and tested end to end through the
real session path, including the cross-check that it is identical whichever
phone pressed Stop.

The buyer and the seller each derive one independently and `agree()` compares
them. A mismatch is DISPUTED, and **the larger figure is never charged** —
enforced on the phone and again in the server's `report()`.

## 7. Idempotency

The settlement id is `sha256("ProkNet-settlement-1" | sessionId |
contractHash | checkpointHash)[0..32]`. A restart, a re-derivation, a repeated
sync and the other phone's copy all produce the same string, so "one session,
two payment requests" is not something the code has to remember to avoid — it
is unrepresentable. The SQLite primary key is that id; a second insert is a
no-op on the amounts, and only the payment state may move.

`applyPayment` is idempotent in both directions: a repeated webhook changes
nothing, nothing moves a CONFIRMED obligation backwards, and a **second,
different** reference claiming to confirm an already confirmed obligation is
treated as suspicious and marked DISPUTED.

## 8. Credit and debt policy

Asked **before** any Bluetooth channel, handshake or probe is paid for, in
`ProkNetNode.buy()` right after the economic quote.

- Free source: always allowed, never gated by money.
- Sponsored or growth-subsidised: allowed, creates no buyer debt.
- Small debt: allowed. One-tap Internet is not interrupted.
- At or past the credit limit (pilot default 50 CFA): REQUIRE_SETTLEMENT,
  "Réglez N CFA pour continuer", before anything expensive happens.
- A confirmed payment history earns 25 CFA more room.
- A disputed session: BLOCK_PAID_SESSION until a human looks at it.
- Over the limit with no way to pay: BLOCK, because asking them to settle
  would be useless.

Small sessions with one seller net into a single payment, because authorising
a Mobile Money transfer for 3 CFA is absurd. The threshold and limit are
`SettlementPolicy.Policy` values, not constants buried in protocol code.

## 9. Payment rails — REAL vs MOCK vs BUILT ONLY

| Rail | Status | What it actually does |
|---|---|---|
| `MOCK` | **REAL, developer only** | Simulates success, failure, duplicates. Off unless a long press on COPY NETWORK in the Lab screen enables it. Reports unavailable in consumer mode, so a mock payment can never be shown to a user as real |
| `MANUAL_PILOT` | **REAL** | The buyer pays through MTN or Airtel outside the app and enters the reference. Reaches PAYMENT_SEEN and **no further** |
| `MTN_MOMO` | **BUILT ONLY** | Interface and documented integration points. `available()` is false, `initiate()` refuses, `check()` returns PENDING |
| `AIRTEL_MONEY` | **BUILT ONLY** | Same |

**No real MTN or Airtel integration exists in this build.** There are no
merchant credentials, no collection endpoint and no webhook secret in this
repository. Three things are needed and none are ours to decide alone: a
merchant account with the operator, API credentials held **server-side**, and
a webhook whose signature the settlement service verifies. The rails refuse to
run rather than simulate success.

**ProkNet never asks for a PIN**, secret code, password or operator OTP. The
user authorises inside the operator's own flow.

## 10. Backend

`server/brain/settlement.py`, SQLite, three tables: `settlements`,
`payment_events` (keyed by rail|reference|settlement, so a retried webhook is
recognised), `settlement_audit` (append-only, never updated or deleted).

Endpoints: `POST /v1/settlements`, `GET /v1/settlements/{id}`,
`POST /v1/payments/initiate`, `POST /v1/payments/webhook`,
`GET /v1/wallet?node=`. Expiry runs on the existing cleanup timer.

`report()` verifies the submitted settlement id against the signed session
facts and refuses a forged one outright.

## 11. Fraud and replay protection

- Forged settlement id: refused, nothing stored.
- Replayed payment reference: the event key includes the settlement, so the
  same reference cannot confirm a different obligation.
- Duplicate webhook: recognised and a no-op.
- Duplicate settlement: impossible by primary key.
- Modified amount: a CONFIRMED webhook whose amount does not match the
  obligation is DISPUTED, not accepted.
- Expired obligation: cannot be paid.
- Session replay: the session id check already existed in `acceptableProposal`.
- **Unverified webhook: recorded for the audit trail and ignored.** With no
  signing secret configured, that is currently every webhook, and the code
  says so rather than defaulting to trust.

## 12. Privacy

A Mobile Money number is stored locally in `payment_destinations`, never put
into a BLE advert or gossip, and displayed masked (`•••••456`). Discovery
keeps using the Prok identity; payment identity is exchanged only for a real
settlement. No SMS permission is requested.

## 13. Cost-class honesty

Taking your recommendation: **v0.15 real settlement applies to COMMERCIAL v2
sessions only.** Contract v2 does not sign a cost class or a payer, so a
sponsored session cannot be proven to be one and real money must not rest on
an unsigned claim. Sponsored sessions keep working and create no payable
obligation. The contract protocol was not expanded.

## 14. Tests

363 Android JVM tests (+30) and 23 server tests (+12).

- `SettlementTest` (13): derivation, the budget as a ceiling on the money,
  one session one obligation, both phones agreeing, disputes, idempotent
  payments, expiry, the rails refusing to pretend, destination masking.
- `WalletTest` (13): the view, netting, the credit limit, free never gated,
  sponsored, disputes blocking, and the wording never claiming a guarantee,
  a balance, or megabytes.
- `BudgetSessionTest` (+10): the six seller-stop gate tests and four
  obligation tests through the real session path.
- `test_settlement.py` (12): the split matching the phones, forged ids,
  reconciliation, unverified webhooks, duplicates, wrong amounts, expiry,
  wallet arithmetic, audit trail.

## 15. Version / build / commit / hash

Build 58, versionName 0.15.0, verified with `aapt2 dump badging`.
SHA256 `d64926a3a668b99cb72ebf168c3d383a50350bffd0eb4a9d15a622ebbfbc042a`.
Commits `9266678`, `0611bee`, `da4c8ef` on `main`; this report on top.
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.15.0

## 16. Hardware tests

**TESTING 61 first, and the wallet is not worth testing until it passes:** a
5 to 10 second session ended by the **OUKITEL**, twice. The seller must earn
above zero, the two figures must agree, and the OnePlus must say the provider
stopped sharing rather than show an error.

**TESTING 62** then covers the obligation appearing, the receive-with setting
masking the number, a mock payment surviving a restart of both apps, the
manual rail saying "en attente de vérification" and never "paid", three
sessions netting into one payment, and the credit limit refusing before any
Bluetooth setup.

## 17. Known limitations

- **No real Mobile Money integration.** Nothing in this build can confirm a
  real payment. The manual rail reaches PAYMENT_SEEN; only a human or a future
  API moves it further.
- **The server does not yet verify phone signatures on a settlement report.**
  `actor` is taken as claimed. The idempotent id and the two-phone
  reconciliation limit the damage, and no report can cause a payment, but this
  must be signed before any real-money pilot. It is the largest gap.
- **A buyer that vanishes before the closing ack pays only what was already
  signed.** Inherent to a two-party signed protocol: nobody may be billed for
  usage they never agreed to. The 30 second checkpoint bounds the loss to the
  last window, and the credit limit bounds repeat abuse, but a determined
  buyer can get short free sessions. Worth watching in the pilot.
- The Prok fee is tracked as a receivable; how it is actually collected is
  deliberately left open, as you asked.
- The credit limit is per-phone local state. A buyer who reinstalls starts
  clean. Server-side debt needs the signed submission path above.
- `Wallet.startOfDay` assumes UTC+1. Fine for Congo-Brazzaville, wrong
  elsewhere.
- TESTING 55 to 60 have still never been run on hardware.
