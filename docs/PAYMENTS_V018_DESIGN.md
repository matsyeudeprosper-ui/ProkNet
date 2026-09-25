# ProkNet v0.18 — Prok credit, signed-session earnings, withdrawal on request

Date: 2026-09-25 (revision 4 — the product specification, as decided by Mike and ChatGPT)
From: Claude (implementation engineer)
To: Mike (product owner), ChatGPT (architect)
Status: **Specification fixed by the product owners. This document is the technical plan
and test list for it. Real customer payments stay DISABLED until the legal and
operational questions (Appendix B) are resolved.** Values marked (ex.) are configurable
examples.

**Constraints that are not negotiable in this build**
- **No MTN or Airtel APIs.** No sandbox, no registration, no future API integration is a
  dependency of anything here.
- A ledger cannot send money. **N withdrawals = N manual Mobile Money sends** by the
  treasurer. The admin screen and the report say that number plainly. No local-agent
  chain, no "automatic payout". Expansion waits for a bulk payout rail Mike selects
  separately.
- Offline spending authorisations: not built in this version.

---

## 1. Pilot flow (the specification)

1. A customer tops up Prok credit **[MANUAL: a Mobile Money transfer to the Prok
   treasury number]**. Verified Internet usage — the existing co-signed checkpoints and
   server-verified evidence — creates **provider and relay earnings** in the ledger.
2. Earnings **accumulate**. The provider **chooses when to withdraw**, subject to a
   configurable minimum (ex. 500 CFA). There is **no scheduled payout of everyone**.
3. A withdrawal request enters the **treasury queue**. The treasurer **sends each
   approved amount manually** through Mobile Money **[MANUAL]**; the app records the
   result and matches the operator's SMS where it is reliable. The provider's screen says
   exactly one of **"Retrait demandé"**, **"Envoi en cours"**, **"Payé"** — never a state
   the ledger cannot vouch for.
4. The provider receives the transfer on their own phone. They never visit a kiosk to
   collect a session's earnings.

Small, capped pilot: float cap (ex. 50,000 CFA per rail), participants = Mike's own
numbers and named testers, until Appendix B is answered.

---

## 2. Who holds, sends, receives

```
 Customer wallet ──[MANUAL transfer]──► PROK TREASURY PHONE (MTN SIM + Airtel SIM,
                                          named treasurer, ProkNet Treasury mode)
                                             │ operator SMS "vous avez reçu …"
                                             ▼
                                        LEDGER (Brain): TOPUP → customer credit
 Session (signed evidence) ──────────► LEDGER: customer −gross, provider +share,
                                                relay +share, Prok +share
 Provider taps Retirer ───────────────► LEDGER: withdrawal REQUESTED → treasury queue
 Treasurer sends [MANUAL] ───────────► withdrawal SENT → operator SMS "vous avez envoyé …"
                                        matches → PAID (or treasurer confirms by hand)
```

| Who | Holds | Does | Sees |
|---|---|---|---|
| Customer | Own wallet; **Prok credit** (a ledger balance) | Tops up [MANUAL]; uses Internet | "Crédit Internet : N F", session cost from signed numbers |
| Provider / relay | **Earnings** in the ledger | Taps **Retirer** when they want, ≥ minimum | Gagné · Retirable · one of Retrait demandé / Envoi en cours / Payé |
| Treasurer | Two treasury SIMs and PINs; the treasury phone | Approves and **sends each withdrawal by hand**; reviews unmatched SMS; types the daily balance | Queue with the honest count: "**12 retraits en attente = 12 envois manuels**" |
| Prok | The float, legally | Monthly move of its share [MANUAL] | Daily report |

**Invariant (daily):** typed balances (MTN + Airtel) ≥ Σ customer credit + Σ earnings +
Σ active holds + Σ withdrawals SENT-not-PAID. Failure blocks approvals and reports.

---

## 3. Ledger

Brain schema **migration 5**, ALTER-based, upgrade-tested from a real schema-4 DB.
Double-entry, integer centimes, append-only; reversals are `ADJUSTMENT` postings with a
memo and the actor's identity.

```
ledger_postings   id, ts, kind, debit_account, credit_account, amount_centimes,
                  ref, memo, actor            -- kinds: TOPUP HOLD HOLD_RELEASE SESSION
                                              --   RELAY_SHARE PROK_SHARE WITHDRAW_RESERVE
                                              --   WITHDRAW_PAID WITHDRAW_CANCEL FEE
                                              --   BALANCE_CHECK ADJUSTMENT
                  -- accounts are strings: credit:<node> earned:<node> prok:revenue
                  --   float:mtn float:airtel inflight:withdrawals loss fees
ledger_holds      id, customer_id, seller_id, amount_centimes, state
                  (PRE_SESSION|IN_SESSION|CONSUMED|RELEASED|EXPIRED),
                  created_at, last_keepalive, session_hex, settled_ref
ledger_withdrawals id, payee_id, rail, msisdn_hash, amount_centimes, state
                  (REQUESTED|APPROVED|SENT|PAID|DENIED|CANCELLED|NEEDS_ATTENTION),
                  requested_at, approved_at, approved_by, sent_at, sent_by,
                  paid_at, paid_evidence (sms_hash | 'treasurer:<ref>'), memo
ledger_topups     id, rail, sender_hash, amount_centimes, sms_hash UNIQUE,
                  observed_at, observed_by, state (MATCHED|UNASSIGNED|NEEDS_REVIEW|
                  CLAIMED|REJECTED), customer_id, matched_by
ledger_intents    id, customer_id, rail, amount_centimes, amount_tag, created_at,
                  expires_at, state
ledger_bindings   customer_id, rail, sender_hash, bound_at, bound_by (topup id)
ledger_audit      id, ts, actor, action, target, detail     -- every treasury action
```

Rules:
- A settlement posts **once**, keyed by settlement id (`settlement.py`'s DISPUTED rule
  carries over).
- **Roles are the server's:** only allow-listed **treasury identities** may approve /
  mark sent / confirm / deny withdrawals, record top-ups, adjust. Any other node → 403 +
  audit row.
- `PROK_PAYMENTS_LIVE` (server env, default **off**): while off, `TOPUP` postings are
  refused (recorded as `REJECTED` with reason `payments_disabled`), the customer app shows
  no treasury number, and holds can only be funded by an audited **test credit**
  adjustment on named test identities. Everything else — sessions, earnings, withdrawal
  requests, the queue — runs.

---

## 4. Metering and charging (unchanged rules, restated)

- The tunnel meters both ends; checkpoints every 30 s / 1 MB, countersigned; whoever
  stops, `Teardown` issues a closing checkpoint and waits for the countersignature.
- Budget sessions cost `costFor(billable)` **proportionally from the first byte**; no free
  band. New: `MIN_SESSION_CENTIMES` (ex. 100) when `bytesDown > 0`; **zero only** when
  there is no mutually signed checkpoint or the final one has `bytesDown == 0`.
- Split (ex.): provider 75 / Prok 25; via relay provider 65 / relay 10 / Prok 25. The
  relay is named by `relayId` in the signed contract.

---

## 5. Holds — credit cannot be released while a session may still settle

Seller (it has Internet) calls `POST /v1/credit/hold {customer_id, amount}` before
admitting a paid contract; atomic; one active hold per customer; the contract carries
the hold id; `Gateway.enforceMax()` keeps the session ≤ hold.

| State | Enters | Leaves |
|---|---|---|
| PRE_SESSION | granted | session start report → IN_SESSION; or `PRE_TTL` (ex. 30 min) → EXPIRED |
| IN_SESSION | seller reports `session_started {hold, session_hex}` | evidence for `session_hex` verified → CONSUMED (remainder released); seller reports `session_ended` **with the signed final checkpoint** → CONSUMED; no keepalive for `KEEPALIVE_TTL` (ex. 15 min) → **STALE**, not released |
| STALE | keepalives stopped | evidence arrives (any time) → CONSUMED; **`STALE_TTL` (ex. 24 h) with no evidence** → EXPIRED, credit back, and the session, if it ever settles, still posts (customer may go negative by ≤ one hold; reported) |

Keepalive = the seller's existing 30-second sharing heartbeat carrying the hold id.
The change from revision 3: a hold whose keepalives stopped is **not** released after
15 minutes — a session may still settle when a phone reconnects — it waits a full day.

Rate limit: `MAX_HOLDS_PER_HOUR` per customer (ex. 6) and per customer-seller pair (ex. 3).

---

## 6. Top-ups — verification without an API

1. Customer: **Recharger** → rail, amount → the app registers an **intent** (with a
   unique amount tag the first time, ex. "envoyez exactement 503 F"; the 3 F is credited).
   Shows the treasury number **only when `PROK_PAYMENTS_LIVE`**.
2. **[MANUAL, customer]** sends the transfer.
3. Treasury phone reads the operator's "vous avez reçu N F de 06…" SMS (`ReceiptParser`
   extended, treasury identity only: counterparty **hash**, DEBIT verdict). Posted to
   `POST /v1/treasury/topups` with the SMS hash (UNIQUE — a duplicate posts nothing).
4. Match, in order: (a) sender hash **already bound** to a customer **and** an intent
   with that amount exists → `MATCHED`, credited; (b) unbound sender **and** an intent
   with a **unique amount tag** matches exactly → `MATCHED`, number bound; (c) otherwise
   → **`UNASSIGNED`, held for review**.
5. **Claiming an unassigned top-up:** the customer supplies the sending number **and**
   the exact amount **and** the operator's transaction reference or the date/time from
   their own SMS; this creates a `NEEDS_REVIEW` item; **the treasurer confirms** against
   the SMS text. **A number alone never claims anything, and no claim credits without a
   treasurer.** Unclaimed after 7 days → treasurer's list (refund [MANUAL] or leave).
6. Parser `AMBIGUOUS` → `NEEDS_REVIEW` with the text and *Créditer …* / *Ignorer*.

Honest by construction: credit only when the operator itself says the money arrived, on
the phone that holds the receiving SIM. A miss delays; it never invents.

---

## 7. Earnings and withdrawal (provider / relay)

- Evidence verified → `SESSION` + `PROK_SHARE` (+ `RELAY_SHARE`) postings. Gagner shows
  **Gagné** (lifetime) and **Retirable** (earned − reserved by open withdrawals).
- **Retirer**: enabled when Retirable ≥ `WITHDRAW_MIN` (server-configurable, ex. 500 CFA)
  and no withdrawal is open. The provider enters nothing but the amount (≤ Retirable)
  and confirms rail + number (stored once; hashed on the Brain). Creates `REQUESTED`
  and a `WITHDRAW_RESERVE` posting (earned → inflight), so the same money cannot be
  requested twice.
- One open withdrawal per payee. The payee can **cancel** while `REQUESTED` (reserve
  reversed). After `APPROVED` only the treasurer can change it.
- Screen text, from the server state and nothing else:
  `REQUESTED / APPROVED` → **"Retrait demandé"** · `SENT` → **"Envoi en cours"** ·
  `PAID` → **"Payé"** (+ date, operator ref when known) · `DENIED` → "Refusé — <memo>"
  (reserve reversed) · `NEEDS_ATTENTION` → "En vérification".

---

## 8. Treasury queue — manual sends, honest count, duplicate protection, audit

Screen (treasury identity only): **"12 retraits en attente = 12 envois manuels"**, then
rows: payee label, rail, number (copy), amount, age, state.

Per row, the treasurer's actions, each an audited `POST`:
- **Approuver** → `APPROVED` (checks: payee not blocked, float per rail ≥ approved sum,
  invariant green). **Refuser** with a memo → `DENIED`.
- **Marquer envoyé** → `SENT`, `sent_by`, `sent_at`. The button is enabled **only after
  Approuver** and is **disabled once tapped**; a second `SENT` on the same id is a 409.
- The phone then watches for the operator's "vous avez envoyé N F à 06…" SMS: amount and
  counterparty hash match **exactly one** `SENT` row → `PAID` with `paid_evidence =
  sms_hash`. Two candidate rows → no auto-match, both flagged for the treasurer.
- **Confirmer payé** [MANUAL] with the operator reference typed → `PAID` with
  `paid_evidence = treasurer:<ref>`. Used when no SMS matched.
- **Non envoyé** → back to `APPROVED` (only from `SENT`, only by a treasurer, audited).
- `SENT` for > 24 h with no evidence → row turns amber: "Vérifiez l'historique MoMo";
  > 7 days → `NEEDS_ATTENTION` and Mike is told.

**Duplicate-payment protection**, stated as guarantees with tests:
1. One open withdrawal per payee (DB unique index on `payee_id WHERE state IN (REQUESTED,
   APPROVED, SENT)`).
2. A withdrawal id can be marked `SENT` once; the second attempt is refused and audited.
3. The queue never shows a `SENT` row as something to send.
4. Reserve at request time means the ledger cannot over-commit earnings.
5. Every state change carries actor + timestamp in `ledger_audit`.

**What the queue does not do:** send money. The daily report repeats the count:
"Retraits en attente : 12 (= 12 envois manuels). Envoyés non confirmés : 3."

---

## 9. Treasurer routine

Once: enrol the treasury phone (node id allow-listed by Mike; `RECEIVE_SMS` +
notification access on Prok's own phone). Daily (~1 min): `*xxx#` on each SIM, type the
two balances [MANUAL]; tap through `NEEDS_REVIEW` items [MANUAL]. When there is a queue:
approve, send each one from the operator app [MANUAL], rows turn to Payé as SMS match;
confirm by hand any that don't.

Cannot be automated: any send (withdrawal, refund, Prok share, cross-rail move), reading
the balance, deciding an unreadable SMS, resolving an unclaimed top-up.

---

## 10. Technical plan (implementation order as specified)

| # | Deliverable | Server | Android | Moves real money? |
|---|---|---|---|---|
| 1 | **Ledger + signed-session posting** | `brain/ledger.py` (migration 5, postings, balances, invariant, roles), hook in `POST /v1/settlements` after `evidence.verify`; `GET /v1/wallet` extended (`credit`, `earned`, `withdrawable`, `withdrawal`) | `core/LedgerView.kt` pure model of the wallet answer; `node/LedgerSync.kt` fetch on the Brain sweep | No |
| 2 | **Holds** | `POST /v1/credit/hold`, `/hold/started`, `/hold/ended`, keepalive via the presence heartbeat, sweep PRE→EXPIRED / IN_SESSION→STALE / STALE→EXPIRED, rate limit, `PROK_PAYMENTS_LIVE` gate + audited test credit | Seller: hold before admitting a paid contract (IO executor, bounded wait, refuse with a sentence on timeout); hold id in the contract; keepalive on the sharing heartbeat | No (test credit only) |
| 3 | **Earnings UI + withdrawal requests** | `POST /v1/withdrawals` (request/cancel), `GET /v1/withdrawals/mine`, `WITHDRAW_MIN` in `/v1/wallet` | Gagner: Gagné / Retirable / **Retirer**, state text from server state only; payout number entry | No |
| 4 | **Treasury queue** | `GET /v1/treasury/queue`, `POST /v1/treasury/withdrawals/{id}/{approve,deny,sent,unsent,paid}`, audit, duplicate guards, honest count in `/v1/treasury/summary` and the Telegram report | `TreasuryActivity` (shown only when the server says this identity is treasury): count line, rows, actions, copy number | No — a human sends |
| 5 | **SMS matching** | `POST /v1/treasury/topups`, `/v1/treasury/debits`, matching order, intents + amount tags, bindings, claim → NEEDS_REVIEW, review endpoints | `ReceiptParser` treasury extension (counterparty hash, DEBIT), `ReceiptListener` forwards under treasury identity only; Recharger screen (number hidden unless live); claim form | **Posting refused while `PROK_PAYMENTS_LIVE` is off** |

Not in this version: offline authorisations, relay verification beyond `relayId`,
captive-portal router, any operator API.

---

## 11. Tests (what proves each guarantee)

**Server (`server/tests/`)**
- `test_ledger.py`: double-entry sums to zero; balances re-derivable; a settlement posts
  once (second identical → no-op, different → DISPUTED, no posting); split sums exactly to
  gross; relay share only with `relayId`; `MIN_SESSION_CENTIMES` when `bytesDown > 0`;
  zero when `bytesDown == 0`; a non-treasury identity cannot post TOPUP/ADJUSTMENT (403 +
  audit row); invariant flags a shortfall.
- `test_ledger_upgrade.py`: real schema-4 DB with settlements → migration 5 → columns,
  indexes, order compared; old rows intact.
- `test_holds.py`: atomic (two concurrent holds for one customer → one wins); hold ≤
  credit; a 2-hour session with keepalives never expires; keepalives stop → STALE at 15
  min, **still reserved**; evidence at 20 h → CONSUMED; no evidence at 24 h → EXPIRED and
  a late settlement still posts (customer negative, reported); 7th hold in an hour
  refused; `PROK_PAYMENTS_LIVE=0` → TOPUP refused and recorded, test credit on a named
  test identity works and is audited.
- `test_withdrawals.py`: below minimum refused; second open request refused (unique
  index); reserve moves earned → inflight; cancel only while REQUESTED; state texts map
  1:1 (`REQUESTED/APPROVED→"Retrait demandé"`, `SENT→"Envoi en cours"`, `PAID→"Payé"`)
  via a shared fixture read by Kotlin too; approve refused when rail float < approved
  sum; `sent` twice → 409 + audit; `unsent` only from SENT by treasury; debit SMS matching
  exactly one SENT row → PAID, two candidates → none auto-paid; 24 h / 7 d escalations;
  the summary's manual-send count equals the number of REQUESTED+APPROVED rows.
- `test_topups.py`: duplicate SMS hash posts nothing; bound sender + intent → MATCHED;
  unbound + unique tag → MATCHED and bound; anything else → UNASSIGNED; **claim with
  number only → 400**; claim with number + amount + reference → NEEDS_REVIEW, no credit
  until a treasurer confirms; AMBIGUOUS → NEEDS_REVIEW; unclaimed 7 d → listed.
- `test_api.py` additions: every new route over a real socket with signed requests;
  treasury routes 403 for a seller identity.
- Cross-language fixtures (`server/tests/fixtures/`): `withdrawal_states.txt` (state →
  French text), `ledger_split.json` (gross/fee/relay cases), `treasury_sms.json`
  (canonical SMS → amount, counterparty hash, verdict) — Python writes, Kotlin reads.

**Android (`app/src/test/`)**
- `LedgerViewTest`: wallet JSON → Gagné / Retirable / state text; unknown state → never
  "Payé"; `Retirer` enabled only ≥ minimum and with no open withdrawal.
- `HoldAdmissionTest` (pure `core/HoldGate.kt`): admit only with a granted hold ≥ budget;
  timeout → refuse with the sentence; free sessions need no hold; the hold id lands in
  the contract and in the keepalive.
- `TeardownSettlementTest` (existing, extended): a seller-stopped session settles to the
  same centime as today; `bytesDown == 0` → zero; `MIN_SESSION_CENTIMES` applied.
- `ReceiptParserTreasuryTest`: counterparty hash filled only under the treasury identity
  (existing privacy test still passes for sellers); DEBIT verdict for "vous avez envoyé";
  fixture cases from `treasury_sms.json`.
- `TreasuryQueueUiTest` (pure model): count line reads "N retraits en attente = N envois
  manuels"; a SENT row never shows the Marquer envoyé button; amber at 24 h.
- `WithdrawalStatesTest`: reads the same fixture as Python; every server state has
  exactly one French text; none of them is a state the ledger cannot vouch for.
- Layout regression: every `R.id` used in MainActivity/TreasuryActivity exists.

**Hardware (docs/TESTING.md, new sections)**
- T80 Ledger: a session between two test phones appears as earnings on the seller and a
  debit on the buyer's test credit, to the centime of the phones' own settlement.
- T81 Hold: a 40-minute session keeps its hold; kill the seller app mid-session → STALE
  visible; reopen, settlement posts, hold CONSUMED.
- T82 Withdrawal: Retirer → "Retrait demandé" → treasurer approves → sends 100 F
  between Mike's own numbers [MANUAL] → "Envoi en cours" → SMS match → "Payé".
- T83 Duplicate: tap Marquer envoyé twice → second refused; queue count unchanged.
- T84 Top-up (only with `PROK_PAYMENTS_LIVE=1` and Mike's own numbers): 503 F transfer →
  credit on the customer phone; a second identical SMS posts nothing; a claim with a
  number alone is refused.
- T85 Corpus: 20 real MTN Congo + Airtel Congo messages collected on the treasury phone
  and added to the fixture before T84 runs.

---

## 12. Confirmed · Assumed · Blocks real money · Pilot-only

**Confirmed:** metering, teardown, proportional cost, evidence verification, receipt
parser/listener exist and are tested (synthetic corpus). CEMAC 04/18 regulates e-money
and payment services.
**Assumed:** operators send an SMS for each received and sent transfer with amount and
counterparty (unverified for Congo — T85); personal-wallet limits exceed the float cap;
every (ex.) value.
**Blocks real money:** Appendix B items 1–6 answered in writing; T85 corpus; T80–T83
passed on hardware; `PROK_PAYMENTS_LIVE` flipped only by Mike after that.
**Pilot-only:** personal-tier treasury wallets, a named treasurer, manual sends
(N = N), Mike's numbers only.

---

## Appendix A — Later option, not a dependency
A bulk payout rail selected by Mike replaces the treasurer's manual sends; the queue,
states, audit and screens are unchanged (`SENT`/`PAID` become rail callbacks).

## Appendix B — Counsel checklist
1. Is Prok credit (non-transferable, no cash-out except refund-to-origin, capped,
   consumable only as Internet) electronic money under 04/18/CEMAC/UMAC/COBAC; any
   closed-loop exemption; does delivery by independent providers defeat it?
2. Is collecting from customers and paying providers a payment service under 04/18 even
   with no stored balance, and does doing it manually change anything?
3. Does "Prok is the seller; providers are subcontractors on commission" hold; VAT,
   withholding, providers' status?
4. May Prok operate under a licensed partner's licence; contract form?
5. Do providers or Prok need an ARPCE authorisation to resell Internet access?
6. Which entity contracts with the operators later; is a Congolese RCCM company with a
   local bank account required; can a French parent own it?
7. Refund-to-origin, dormant credit, complaints: consumer rules.
8. AML/KYC for regular payees and small top-ups; what a named treasurer holding funds for
   others must document.

## Appendix C — Sources
MTN MoMo developer portal https://momodeveloper.mtn.com/ · MTN Congo Open API
https://www.mtn.cg/momo/momo-entreprise/open-api/ · Airtel Africa developer portal
https://developers.airtel.africa/ · pawaPay https://www.pawapay.io/ · Règlement
04/18/CEMAC/UMAC/COBAC https://www.beac.int/wp-content/uploads/2019/07/REGLEMENT-N-04-18-CEMAC-UMAC-COBAC-du-21-d%C3%A9cembre-2018.pdf
· ARPCE FAI https://www.arpce.cg/fournisseur-acces-internet · MTN MoMo Congo consumer
tariffs (third party) https://blog.iambeezy.app/fr/frais-mtn-momo-congo-2026-tarifs-complets/
· openNDS https://opennds.readthedocs.io/en/stable/
