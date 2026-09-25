# ProkNet v0.18 — Prepaid credit, metered sessions, accumulated earnings, pre-registration money flow

Date: 2026-09-25 (revision 3)
From: Claude (implementation engineer)
To: Mike (product owner), ChatGPT (architect)
Status: **DESIGN UNDER REVIEW — nothing is built. Not approval to launch live payments.
No real-money implementation starts until the legal and operational path is settled.**
Every percentage, threshold and cap is an example until operator quotes and measured
data costs exist.

**Firm constraint (Mike, 2026-09-25): no MTN or Airtel APIs for the foreseeable future.**
Prok is not registered. This build uses **no operator API credentials, no sandbox calls,
no API top-ups, no API disbursements.** Operator APIs are a later option (Appendix A),
not a dependency. Manual Mobile Money app / USSD actions are allowed and are labelled
**[MANUAL]** wherever they occur.

Revision 3 changes: the whole money flow is redesigned around what can be verified
**without an API** — the operator's own SMS on a Prok-held phone — with every
non-automatable step named. Two revision-2 defects fixed: a hold now stays reserved for
the whole session (5.1), and the "free under 64 KB" rule is withdrawn (6.1.3). Offline
authorisations stay disabled in the pilot.

---

## 0. One-paragraph answer

Customers pay by an ordinary Mobile Money transfer **[MANUAL, by the customer]** to a
**Prok treasury number** (one MTN, one Airtel SIM, in one dedicated phone held by a
named person in Congo, the *treasurer*). That phone runs ProkNet in **Treasury mode**
and reads the operator's own "vous avez reçu …" SMS — the mechanism v0.16 already built
for sellers — to verify each top-up and post it to the Brain's ledger. Sessions are
metered by the existing tunnel with co-signed checkpoints; the seller, who has
Internet, reserves an **online hold** on the customer's credit before admitting a paid
session, and that hold stays reserved until the session settles. Providers and relays
**accumulate** earnings in the ledger. Once a week, or when a balance passes a
threshold, the Brain produces a **payout list**; the treasurer sends each transfer from
the Mobile Money app or USSD **[MANUAL]**, and the treasury phone confirms each one
automatically from the operator's "vous avez envoyé …" SMS. Nobody travels to a kiosk;
nobody pays a provider per session; the only human actions are typing transfers and
one daily balance check. What a ledger cannot do — move money — is done by a person, a
few times a week, from a list.

---

## 1. What exists and is reused (verified in code 2026-09-25)

| Piece | Where | Reused for |
|---|---|---|
| Buyer traffic through the seller app; bytes counted both ends; checkpoint every 30 s / 1 MB, countersigned | `vpn/ProkVpnService.kt`, `node/TunnelClient.kt`, `node/Gateway.kt`, `core/Market.kt` | Metering. Unchanged. |
| Symmetric teardown: whoever stops, a closing checkpoint is countersigned | `core/Teardown.kt` (v0.15.0) | Short sessions. Unchanged. |
| `Settlement.fromSession` = no obligation without a mutually signed checkpoint; budget sessions cost `costFor(billable)` **proportionally, no floor** | `core/Settlement.kt`, `Market.kt:164` | Charging rule. |
| Evidence verified server-side | `core/Evidence.kt`, `server/brain/evidence.py`, `settlement.py` | Ledger posting. |
| **Operator SMS reading**: `ReceiptParser` (scored, never templated, DATA rules), `ReceiptListener` (NotificationListener + `SmsReceiptReceiver`), `PaymentExpectation` (rail + amount + window, ambiguity prevented), `DeviceReceipt` (source trust) | `core/`, `service/` | **The treasury phone's verification.** Today it runs on each seller for its own number; v0.18 runs it on Prok's phone for Prok's numbers. |
| Signed remote parser rules with a pinned config key | `ReceiptRules`, `brain/publish_rules.py` | Operator rewording fixes without an app release. |
| Wallet UI never shows a "Solde" | `WalletUi.kt` | Screens. |
| Relay (live forwarding, earns nothing today) | `core/Relay.kt` | Relay share. |

**What is NOT reused in the normal flow:** per-seller destination numbers, the buyer
paying a seller directly, kiosk cash. They survive only in the exceptional path (6.5).

---

## 2. Who holds, sends, receives, and when — pre-registration

```
 Customer's own MoMo/Airtel wallet
      │  [MANUAL, customer] transfer to the Prok treasury number (app or USSD)
      ▼
 PROK TREASURY PHONE  — one dual-SIM phone, MTN + Airtel personal wallets,
      │                 held by the treasurer, ProkNet in Treasury mode
      │  reads "vous avez reçu N F de 06xxxxxxx" ─► TOPUP posting (automatic)
      │
      │  ledger, per session (signed evidence): customer −gross · provider +share
      │                                          · relay +share · prok +share
      │
      ├──► Provider / Relay  [MANUAL, treasurer] transfer from the payout list;
      │                       confirmed automatically from "vous avez envoyé …"
      ├──► Customer refund   [MANUAL, treasurer] same mechanism, to the origin number
      └──► Prok              [MANUAL] monthly, after reconciliation
```

| Who | Holds | Sends | Receives | When |
|---|---|---|---|---|
| Customer | Own wallet; a **credit at Prok** | A transfer to the Prok number **[MANUAL]** | Internet; refund of unspent credit | Top-up whenever they have cell signal (USSD needs no data). Spend per session from the signed final checkpoint. |
| Provider / relay | An **earnings balance** in the ledger | Nothing | A transfer from the treasurer **[MANUAL]** | Balance ≥ threshold (ex. 2,000 CFA) or the weekly run if ≥ minimum (ex. 500 CFA) |
| Treasurer (a named person) | The two treasury SIMs and their PINs; the treasury phone | Payouts, refunds, Prok's monthly share | Nothing of their own | Weekly payout session; one daily balance check |
| Prok | Legally, the float in the treasury wallets | — | Its share | Monthly |

**Invariant, checked daily:**
`balance(MTN) + balance(Airtel)` (typed in by the treasurer, section 7.3) `≥ Σ customer_credit
+ Σ earned + Σ active holds + payouts SENT-not-CONFIRMED`. Failure pauses the payout
list and tells Mike.

---

## 3. The ledger

Brain schema migration **5**, ALTER-based, upgrade-tested from a real schema-4 DB.
Double-entry, integer centimes, append-only, reversals by `ADJUSTMENT` with a memo and
an operator name. Every float movement carries the operator's SMS reference when it has
one.

```
ledger_accounts  id, owner_id, kind (CUSTOMER_CREDIT|EARNED|PROK_REVENUE|FLOAT_MTN|
                 FLOAT_AIRTEL|IN_FLIGHT|LOSS|FEES), balance_centimes (cache)
ledger_postings  id, ts, kind (TOPUP|HOLD|HOLD_RELEASE|SESSION|RELAY_SHARE|PROK_SHARE|
                 PAYOUT_SENT|PAYOUT_CONFIRMED|PAYOUT_FAILED|REFUND|FEE|BALANCE_CHECK|
                 ADJUSTMENT), debit, credit, amount, ref, memo, posted_by
topups           id, customer_id?, rail, sender_hash, amount, sms_hash, received_at,
                 state (MATCHED|UNASSIGNED|NEEDS_REVIEW|CLAIMED), posted_by (treasury id)
topup_intents    id, customer_id, rail, amount_tag, created_at, expires_at   (section 4.2)
holds            id, customer_id, seller_id, amount, state (PRE_SESSION|IN_SESSION|
                 CONSUMED|RELEASED|EXPIRED), created_at, last_keepalive, session_hex?
payouts          id, payee_id, rail, msisdn_hash, amount, state (SCHEDULED|SENT|
                 CONFIRMED|FAILED|NEEDS_ATTENTION), sent_at, confirmed_by_sms_hash, memo
balance_checks   id, ts, rail, balance_typed, balance_expected, delta, by
```

**Roles are enforced by the Brain, not the app.** Only the pinned **treasury identity**
(one node id per treasury phone, allow-listed on the server like the config key) may post
`TOPUP`, `PAYOUT_*`, `REFUND`, `BALANCE_CHECK`. Any other node's attempt is a 403 and an
audit row. A test proves an ordinary seller cannot post a top-up.

---

## 4. Customer payments: who receives, how verified, how posted

### 4.1 Who receives

**The Prok treasury numbers** — one MTN MoMo, one Airtel Money — shown inside the app
on **Recharger**, with a *copy* button and the amount. Nothing else ever shows a number
to a customer. ⚠ These are **personal-tier wallets** until Prok is registered:
balance caps and daily limits apply (values not published; section 9). The pilot float is
capped (ex. 50,000 CFA per rail) so it stays well inside them.

### 4.2 How each payment is verified — the operator's own SMS, on Prok's phone

1. Customer taps **Recharger** → picks rail and amount → the app registers a
   **top-up intent** with the Brain (needs a moment of Internet or a sync through the
   next seller; a top-up can also be made with no intent, see 3 below).
2. **[MANUAL, customer]** sends the transfer from their Mobile Money app or USSD to the
   Prok number. The customer pays the operator's P2P fee (ex. 50–100 CFA on ≤ 5,000).
3. The treasury phone receives the operator's "vous avez reçu N FCFA de 06xxxxxxx"
   SMS. `ReceiptParser` (extended to **DEBIT/CREDIT with counterparty**, section 4.4)
   yields amount + sender number → **only a salted hash of the sender** leaves the
   parser. Matching, in order:
   - **sender hash known** (customer registered their number once; a hash bound to an
     identity on the first matched payment) → `TOPUP MATCHED`, full amount credited;
   - sender unknown but an **intent with a unique amount tag** is pending (the app asks
     for "exactement 503 F" when the number is not yet bound — the 3 F goes to the
     customer's credit, nothing is taken) → `MATCHED`, number now bound;
   - neither → `UNASSIGNED`. The customer's app can **claim** it by entering the sending
     number; the Brain binds the hash and credits. Unclaimed after 7 days → treasurer's
     review list (refund **[MANUAL]** or leave).
   - parser `AMBIGUOUS` → `NEEDS_REVIEW`: the treasury phone shows the SMS text and two
     buttons, *Créditer …* / *Ignorer*. **[MANUAL, treasurer]** but one tap.
4. The customer's app shows "Crédit Internet : 1 250 F" on its next sync. Typical delay:
   seconds while the treasury phone is on and has signal.

**What makes this honest:** money is credited only when the operator itself says it
arrived, on the phone that holds the receiving SIM. No customer statement, screenshot or
"I paid" button exists. A parsing miss delays a credit; it never invents one. A
duplicate SMS lands on the same `sms_hash` and posts nothing twice.

### 4.3 What cannot be automated here, plainly

- **The customer's transfer itself.** A human types a number, an amount and a PIN.
- **Reading a message the parser cannot score** → one tap by the treasurer.
- **Refunding an unclaimed payment** → a manual transfer.
- **Knowing the wallet balance** → a USSD query by the treasurer (7.3).
- **A payment sent to the wrong number** → not recoverable by ProkNet; the app says so
  before showing the number.

### 4.4 Parser change

`ReceiptParser` today returns CREDIT / NOT_A_CREDIT / AMBIGUOUS with an amount and, by
design, **never a name or number** (a privacy test asserts it). Treasury mode needs the
**counterparty** for both directions. The change keeps the privacy rule for sellers and
adds a treasury-only path: `Parsed.counterpartyHash` (salted SHA-256 of the digits,
salt per treasury identity) filled **only** when the listener runs under the treasury
identity; on any other phone the field is empty and the existing test still passes. A
`DEBIT` verdict is added for "vous avez envoyé … à …" (payout confirmation, 8.2) and
"frais … " lines where the operator states the fee. Rules stay DATA under the signed
config key. **Nobody has seen a real MTN Congo / Airtel Congo message yet** — the first
pilot task is to collect twenty real ones on the treasury phone and add them to the
corpus (section 12).

---

## 5. Spending: online hold (fixed), offline fallback (disabled)

### 5.1 Online hold — reserved for the whole session

The seller has Internet. Before admitting a paid contract it calls
`POST /v1/credit/hold {customer_id, amount = contract.buyerBudget}`; the Brain moves
credit → hold **atomically** (one transaction; `UNIQUE(customer_id) WHERE state IN
(PRE_SESSION, IN_SESSION)`). The contract carries the hold id; `Gateway.enforceMax()`
already stops the session at the budget, so **a session can never exceed its hold**.

Lifecycle, fixing the revision-2 defect:

| State | Enters when | Leaves when |
|---|---|---|
| `PRE_SESSION` | hold granted | session starts → `IN_SESSION`; or **30 min** with no start → `EXPIRED` (credit back) |
| `IN_SESSION` | seller reports `session_started {hold_id, session_hex}` — sent on the same 30 s cadence the seller already runs while sharing (v0.17.10) | settlement → `CONSUMED` (remainder released); seller reports end → `RELEASED`; or **no keepalive for 15 min** → `EXPIRED` |

- **While the seller keeps sending keepalives the hold never expires**, however long the
  session runs. The 30-minute clock is only for holds that never became a session.
- Keepalive = the existing sharing-state heartbeat plus the hold id; no new timer.
- If a seller phone dies mid-session the hold expires 15 min after its last keepalive; a
  later settlement (buyer or seller reconnects with the signed final checkpoint) still
  posts the `SESSION` debit — the customer may go negative by at most one session, which
  the report shows and the next top-up clears.
- If the Brain is unreachable **when the hold is requested**, the paid session is
  refused (5.3). If it becomes unreachable **during** a session, nothing changes for the
  session: the hold's keepalives resume when it is back, and the 15-min expiry only
  affects reservation, never the signed cost.

### 5.2 Offline authorisation — DISABLED in the pilot

Design unchanged from revision 2 (`OFFLINE_MAX` 300 CFA, 6 h, one live per customer,
global daily budget, **loss borne by Prok** as its own ledger line, worst case per
customer `OFFLINE_MAX × (offline sellers reached − 1)`). It is build step 9, after
everything else is hardware-proven, and ships **switched off**. Pilot double-spend loss
is therefore zero.

### 5.3 No hold

Paid session refused with one sentence ("Le réseau Prok n'est pas joignable — session
gratuite seulement"). Free / sponsored sessions unaffected. The exceptional path (6.5)
is reachable only from here and only after the gate.

---

## 6. Flows

Example split (⚠): no relay — provider 75 / Prok 25; via relay — provider 65 / relay 10
/ Prok 25.

### 6.1 Customer

1. Recharger — section 4.
2. Session — as today, with the hold at admission.
3. **Short sessions — revision-2 rule withdrawn.** The "free under 64 KB" floor would
   let a customer open tiny sessions repeatedly and use a provider's data for nothing.
   Rule now:
   - A signed final checkpoint costs **`costFor(billable)` at the signed rate, from the
     first byte** — proportional, no free band. Budget sessions already work this way.
   - A **per-session minimum** `MIN_SESSION_CENTIMES` (ex. 100 = 1 CFA) applies whenever
     the seller's upstream returned anything (`bytesDown > 0`), covering the seller's
     fixed cost of a session (DNS, handshake).
   - **Zero only when no usable Internet was delivered**: no mutually signed checkpoint
     (already zero), or a signed final checkpoint with **`bytesDown == 0`** — nothing
     came back from the Internet, so nothing was delivered and the seller's own data
     cost was nil.
   - **Rate limit on holds**: more than `MAX_HOLDS_PER_HOUR` (ex. 6) per customer, or
     more than 3 per hour with the same seller, is refused ("Trop de sessions —
     réessayez dans une heure"). This closes the repeated-tiny-session loop even if a
     future pricing change reintroduced a floor.
   - The v0.15.0 teardown is untouched; a test settles a seller-stopped session to the
     same centime as today.
4. Dispute: the last mutually signed checkpoint, held by both phones.

### 6.2 Provider / relay

1. Gagner → willing (exists) + a **payout number** (rail + number, stored as hash on the
   Brain, plain on the payee's phone). Verified by the first confirmed payout.
2. Session → evidence → `SESSION` + shares. Wallet: **Gagné (en attente de versement)**,
   **Prochain versement : vendredi / dès 2 000 F**, and the history of past payouts with
   the operator SMS reference.
3. Payout — section 8.
4. Relay: `relayId` in the signed contract (version bump); "verified relay" after N
   relayed sessions (ex. 5) before payouts start.

### 6.3 Refunds

Unspent credit → **origin number only**, ≤ once / 30 days. The engine puts it on the
payout list; the treasurer sends it **[MANUAL]**; confirmed from the debit SMS like a
payout.

### 6.4 Prok

Its share accrues in `PROK_REVENUE`; moved out **[MANUAL]** monthly after 7.3 passes,
logged as a posting with who and when.

### 6.5 Exceptional path (unchanged from revision 2)

Only when the seller could not reach the Brain for > `KIOSK_AFTER` (ex. 24 h) **and**
the customer has no verifiable credit **and** the seller enabled "Paiement de secours".
Then the v0.16 rule: Trust cap 50 CFA, the seller's own number, its own SMS receipt; the
seller bears any loss; Prok takes no share. A test asserts no normal-flow string contains
"kiosque" or a phone number.

### 6.6 Lost phone / reinstall

Credit lives on the Brain. Recovery: the customer sends a 100 CFA top-up from the bound
number; the match proves the number; the old identity's balance moves by `ADJUSTMENT`.

---

## 7. Treasury operations (what the treasurer actually does)

### 7.1 Setup, once
A dedicated Android phone with both SIMs, ProkNet installed, **Treasury mode** unlocked
by a signed enrolment from the Brain (its node id allow-listed; Mike approves).
`RECEIVE_SMS` granted (this is Prok's phone, not a Play distribution, so the restricted
permission is acceptable); notification access as a second source. Kept on, charged, in
signal. If it is off, SMS queue at the operator and are read when it returns —
`SmsReceiptReceiver` handles the backlog; notifications alone would not.

### 7.2 Weekly, ~10–15 minutes for a 10-provider pilot
The **Versements** screen lists: name, rail, number (copy button), amount, payout id.
**[MANUAL]** the treasurer sends each one from the MoMo / Airtel Money app or USSD. As
each "vous avez envoyé N F à 06xxxxxxx" SMS arrives, the row turns green automatically
(8.2). Anything not green after the session stays `SENT` and is chased by 8.2.

### 7.3 Daily, ~1 minute
**[MANUAL]** `*xxx#` balance query on each SIM; the treasurer types the two balances into
the app. The Brain posts `BALANCE_CHECK` with expected vs typed; a delta beyond the fees
the parser saw that day pauses the payout list and reports. (If the operator sends the
balance as an SMS, the parser reads it and pre-fills; still confirmed by a person.)

### 7.4 What the treasurer cannot delegate to the ledger
Sending money (payouts, refunds, Prok's share), reading the balance, deciding a
`NEEDS_REVIEW` message, and handling an unclaimed payment. Everything else — matching,
posting, invariant, list generation, confirmation from SMS, reminders — is automatic.

---

## 8. Payout engine — automatic list, manual send, automatic confirmation

### 8.1 Schedule (⚠ examples)
Threshold: balance ≥ 2,000 CFA → appears on the next list. Weekly: Friday 17:00,
everyone ≥ 500 CFA (below that, the operator's fee eats it). Per-transfer cap 100,000.
The payee chooses rail and number; Prok never picks a number for anyone.

### 8.2 State machine

```
SCHEDULED ──(treasurer taps "envoyé" or the debit SMS arrives)──► SENT
SENT ──(debit SMS: amount + counterparty hash match)──► CONFIRMED  (automatic)
SENT ──(no matching SMS within 24 h)──► reminder to the treasurer:
        "Vérifiez dans l'historique MoMo: 2 000 F à 06…"  [MANUAL]
        ├─ treasurer confirms it went (types the operator ref) ──► CONFIRMED
        ├─ treasurer says it did not ──► back to SCHEDULED (next list)
        └─ 7 days unresolved ──► NEEDS_ATTENTION (Mike)
```

- **Never sent twice**: a payout stays `SENT` until confirmed or explicitly denied by
  the treasurer after checking the operator's own history; the engine never re-lists a
  `SENT` payout. This is the manual equivalent of "query the unknown result before
  retrying".
- Fees: the operator's sender fee appears in the debit SMS or the daily balance delta;
  posted as `FEE` from `PROK_REVENUE`. Payees receive the full amount.
- Cross-rail: each rail's float is checked against that rail's scheduled payouts; a
  starved rail is topped up **[MANUAL]** by the treasurer moving money between the two
  wallets via cash-out/cash-in or an operator-to-operator transfer where offered
  (fees posted). Until then that rail's payouts wait, visibly.

### 8.3 Why this is not "dozens of tiny payments"
Thresholds and a weekly cadence make it one short session per week. A 10-provider pilot
with typical earnings produces 3–10 transfers a week. Growth beyond ~50 payees a week is
the trigger for Appendix A, not a reason to add people.

---

## 9. What blocks real money (legal + operational) — unchanged in substance

Same counsel checklist as revision 2 (section 9.3 there; kept in Appendix B). Under the
manual flow the questions do not soften: the treasurer **holds customer funds and pays
providers**, which is intermediation whether done by API or by thumb. Pre-registration
facts that are new:

- Personal-tier wallets have **balance and daily limits** (values not published for
  Congo; ⚠ ask the operators or test with small amounts). The pilot float cap (ex.
  50,000 CFA/rail) exists for this.
- The treasurer is a **named individual holding money for others**; a written mandate
  from Prok and a log of every manual action (the ledger provides the log) are the
  minimum. Counsel to confirm what else.
- **Pilot participants are Mike's own numbers and named testers only.** No member of the
  public pays into this until questions 1–6 of Appendix B have written answers.

---

## 10. Confirmed · Assumed · Blocks real money · Pilot-only

**Confirmed**
- The tunnel meters both ends with co-signed checkpoints; seller-stop issues a closing
  checkpoint; budget sessions cost proportionally with no floor; no signed checkpoint →
  no obligation.
- `ReceiptParser` / `ReceiptListener` / `PaymentExpectation` exist and are tested
  (synthetic corpus).
- CEMAC 04/18 regulates e-money and payment services; Starlink has no Congo-B licence.

**Assumed**
- MTN Congo and Airtel Congo send an SMS for every received and sent transfer that
  states amount and counterparty number. (Standard in every MoMo market; **unverified
  for Congo** — first pilot task.)
- Personal wallet limits are above the pilot float cap.
- All example values: splits, 2,000 / 500 CFA, 30 min / 15 min hold clocks, 1 CFA
  session minimum, 6 holds/hour, 24 h kiosk gate, 50,000 CFA float cap.

**Blocks real money**
- Written answers to Appendix B items 1–6 (custody, intermediation, Prok-as-seller
  framing, ARPCE, entity).
- Twenty real operator messages parsed correctly on the treasury phone.
- Ledger + hold + SMS top-up + payout confirmation hardware-proven with Mike's own
  numbers; reconciliation running one week with zero unexplained delta.

**Pilot-only**
- Personal-tier treasury wallets and a named treasurer.
- Manual sends (payouts, refunds, cross-rail moves) — the design keeps the automatic
  list and confirmation so the manual part shrinks to typing.
- Offline authorisations disabled. Kiosk path enabled on test phones only.

---

## 11. Actual pilot user flow (what each person sees)

**Customer (pays conveniently, never meets a provider about money)**
1. Home shows "Crédit Internet : 0 F · Recharger".
2. Recharger → MTN or Airtel → 500 F → screen shows the Prok number, "envoyez exactement
   500 F" (or "503 F" the first time), copy button, "puis revenez ici".
3. **[MANUAL]** sends from their MoMo app or USSD.
4. Within seconds to minutes: "Crédit Internet : 500 F". If not: "Recharge non encore
   vue — vérifiez le numéro et le montant" with the claim button.
5. Asks for Internet as today; the provider's phone reserves the hold; the session runs;
   Home shows the cost from the signed numbers; credit decreases.

**Provider (accumulates, is paid without asking)**
1. Gagner → enter payout number once.
2. Serves sessions; Gagné grows; "Prochain versement : vendredi".
3. Friday: the operator's own SMS says the money arrived; Gagner shows it paid, with the
   reference.

**Relay** — same as provider from step 2, after N relayed sessions.

**Treasurer**
1. Once: enrol the treasury phone.
2. Daily: `*xxx#` on two SIMs, type two numbers **[MANUAL]**; tap through any
   `NEEDS_REVIEW` SMS **[MANUAL]**.
3. Friday: open Versements; send each row from the operator app **[MANUAL]**; watch rows
   turn green; chase any still yellow next day.

**Mike** — Telegram report daily: float per rail, liabilities, holds, payouts due /
sent / confirmed, review items, unclaimed top-ups, delta. Approves the monthly Prok move.

---

## 12. Build order under the no-API constraint

1. **Ledger** — migration 5, postings, roles (treasury identity allow-list), invariant,
   `GET /v1/wallet` extended. Server tests incl. "a seller cannot post a TOPUP".
2. **Hold with keepalive** — `POST /v1/credit/hold`, `session_started`, keepalive on the
   sharing heartbeat, PRE_SESSION / IN_SESSION clocks, rate limit. Tests: a 2-hour
   session never loses its hold; a dead seller's hold expires 15 min after its last
   keepalive; the 7th hold in an hour is refused.
3. **Session → ledger** — `POST /v1/settlements` posts; `relayId`; `MIN_SESSION_CENTIMES`
   and the `bytesDown == 0` zero rule; teardown untouched (same-centime test).
4. **Treasury mode + SMS top-ups** — parser counterparty/DEBIT extension behind the
   treasury identity (privacy test still green elsewhere); `topup_intents` with amount
   tags; matching order; UNASSIGNED / claim / NEEDS_REVIEW; **Recharger** screen.
   **Corpus task: 20 real MTN Congo + Airtel Congo messages** collected on the treasury
   phone with Mike's own transfers, added to the fixture. TESTING section: a real 100 F
   transfer between Mike's numbers appears as credit on the customer phone.
5. **Payout engine** — list, thresholds, Versements screen, debit-SMS confirmation,
   24 h reminder, never-twice rule, refunds on the same list, per-rail float check.
6. **Balance check + reconciliation + Telegram report.**
7. **Kiosk gating** — `KIOSK_AFTER`, secours toggle, no-kiosk-words test.
8. **Relay share** — contract version bump, verified-relay rule.
9. **Offline authorisation** — built last, shipped off.
10. **v0.19 spike** — one openNDS router at one site (unchanged; also API-free: the
    box talks to the Brain, not to an operator).

Steps 1–3 touch no money and can start once the design is approved. Steps 4–6 handle
real transfers **only between Mike's own numbers** until section 9 is settled.

---

## Appendix A — Later option: operator APIs (not a dependency)

When Prok is registered: MTN MoMo Collections/Disbursements (Congo-B listed, free
sandbox, KYC go-live), Airtel Congo OpenAPI (`openapi.airtel.cg`, unconfirmed), or an
aggregator with one balance for both rails (pawaPay lists Congo-B). The ledger, holds,
evidence and payout state machine are unchanged; `TOPUP` and `PAYOUT_CONFIRMED` become
API callbacks instead of SMS matches, the treasurer's Friday session disappears, and
`UNKNOWN` results are queried by our reference id before any retry (revision 2, 8.2).
Structure **A** (licensed partner holds funds) remains the recommendation for the
finished product; **B** (Prok-held balance) is to be avoided unless counsel says exempt.

## Appendix B — Counsel checklist (from revision 2)

1. Is prepaid ProkNet credit (non-transferable, no cash-out except refund-to-origin,
   ≤ 10,000 CFA, consumable only as Internet) electronic money under 04/18? Any
   closed-loop exemption in CEMAC practice, and does delivery by independent providers
   defeat it?
2. Is collecting from customers and paying providers a payment service under 04/18 even
   with no stored balance — and does doing it manually change anything?
3. Does "Prok is the seller; providers are subcontractors on commission" hold; VAT,
   withholding on commissions, providers' status?
4. May Prok operate under a licensed partner's licence, and in what contract form?
5. Do providers or Prok need an ARPCE authorisation to resell Internet access? Does a
   fixed captive-portal site change the answer?
6. Which entity contracts with MTN Congo / Airtel Congo; is a Congolese RCCM company with
   a local bank account required; can a French parent own it?
7. Refund-to-origin, dormant credit, complaints: applicable consumer rules.
8. AML/KYC for regular payees and for customers topping up ≤ 10,000 CFA; what a named
   treasurer holding funds for others must document.

## Appendix C — Sources (unchanged from revision 2)

MTN MoMo developer portal https://momodeveloper.mtn.com/ · MTN Congo Open API
https://www.mtn.cg/momo/momo-entreprise/open-api/ · Airtel Africa developer portal
https://developers.airtel.africa/ · pawaPay https://www.pawapay.io/ · Règlement
04/18/CEMAC/UMAC/COBAC https://www.beac.int/wp-content/uploads/2019/07/REGLEMENT-N-04-18-CEMAC-UMAC-COBAC-du-21-d%C3%A9cembre-2018.pdf
· ARPCE FAI https://www.arpce.cg/fournisseur-acces-internet · MTN MoMo Congo consumer
tariffs (third party) https://blog.iambeezy.app/fr/frais-mtn-momo-congo-2026-tarifs-complets/
· openNDS https://opennds.readthedocs.io/en/stable/ · Starlink/ARPCE
https://www.agenceecofin.com/regulation/2506-119755-congo-l-arpce-demantele-une-installation-starlink-non-autorisee-dans-une-exploitation-forestiere
