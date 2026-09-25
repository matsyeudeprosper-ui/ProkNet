# ProkNet v0.18 — Prepaid credit, metered sessions, automatic payouts

Date: 2026-09-25 (revision 2, after Mike's design review)
From: Claude (implementation engineer)
To: Mike (product owner), ChatGPT (architect)
Status: **DESIGN UNDER REVIEW — nothing here is built, and this is not approval to
launch live payments.** Every percentage and threshold is an example until operator
quotes and measured data costs exist.

Revision 2 changes, from the review: (1) the legal section no longer assumes prepaid
credit is outside payment regulation; it compares a licensed partner holding funds with
pay-per-session and lists what counsel must confirm. (2) Offline spending has a hard
loss bound and a named bearer; the normal path is an **online hold by the seller**, who
has Internet by definition. (3) The finished payout flow is automatic; unknown operator
results are **queried, never retried blind**; cross-rail treasury is specified.
(4) Short sessions keep the v0.15.0 rule — a signed final checkpoint decides — and
charge zero only when no usable Internet was delivered. (5) The kiosk path is
exceptional by construction, not by labelling. Section 12 classifies everything as
**Confirmed / Assumed / Blocks real money / Pilot-only**.

---

## 0. The brief, and the one-paragraph answer

> Customers add credit with MTN MoMo or Airtel Money. A controlled gateway meters the
> real session. Providers and relays earn a share, see it in Prok Wallet, and are paid
> out automatically. Nobody sends dozens of tiny payments by hand.

**Answer.** Customer money enters through the operators' Collections API into a
business wallet held by **Prok's Congolese entity or a licensed partner (section 9
decides which)**. The Brain keeps a double-entry **ledger** in integer centimes. A
session is metered by the **existing ProkNet tunnel** — every buyer packet passes
through the seller's app, both phones count bytes, both sign a checkpoint every
30 s / 1 MB — and the ledger is posted from the same signed evidence the Brain verifies
today. Before admitting a paid session the seller, who has Internet, asks the Brain for
an **atomic hold** on the customer's credit; a small signed offline authorisation covers
the case where the Brain is unreachable, with a hard loss cap that Prok bears. For
fixed sites serving ordinary devices, an OpenWrt router with openNDS is the gateway and
the Brain is its authentication server. A **payout engine** pays each provider and relay
by Mobile Money disbursement when their balance passes a threshold or on a weekly run,
with status queries before any retry and a treasury rule for paying an Airtel payee from
MTN-collected funds. The kiosk path survives only where Prok is unreachable and never
appears in the normal flow.

---

## 1. What already exists (so v0.18 is not a second money system)

Verified in the code on 2026-09-25:

| Piece | Where | What it gives v0.18 |
|---|---|---|
| Buyer traffic through the seller app | `vpn/ProkVpnService.kt`, `node/TunnelClient.kt`, `node/Gateway.kt` | **The phone path is already a controlled gateway.** VpnService captures every IPv4 packet of every app on the buyer, a user-space TCP/DNS stack forwards it over Wi-Fi/Bluetooth to the seller, the seller opens the real sockets. Bytes are counted on **both** phones. |
| Signed contract + checkpoints | `core/Market.kt` — `Contract` (buyer proposes, seller admits), `Checkpoint` every 30 s or 1 MB, tolerance 64 KB / 10 %, buyer countersigns | Meter readings neither side can inflate alone. |
| Symmetric teardown | `core/Teardown.kt` (v0.15.0) | **Whoever stops, a closing checkpoint is issued and countersigned before settlement.** A seller-stopped short session is charged for what it delivered. |
| Settlement + evidence | `core/Settlement.kt` (`fromSession` returns **no obligation without a mutually signed checkpoint**), `core/Evidence.kt`, `server/brain/evidence.py`, `settlement.py` | The Brain re-derives gross/fee/net from signed bytes. |
| Pricing | `core/Pricing.kt`: CFA per MB, centimes, seller floor 1–4 CFA/MB by policy, `DEFAULT_FEE_PCT = 5` | Rates and the fee hook. |
| Trust cap | `core/Trust.kt`, `SettlementPolicy.kt` (credit limit 50 CFA) | Stays for the exceptional path only. |
| Wallet UI | `core/Wallet.kt`, `WalletUi.kt` — never shows a "Solde" | Screen scaffolding. |
| Relay | `core/Relay.kt`, `node/RelayNode.kt` — live A→B→C forwarding, `FLAG_VIA_RELAY` | Exists, **earns nothing today**. |
| Server money routes | `/v1/settlements`, `/v1/payments/*`, `/v1/wallet`, `/v1/device/risk` | Extended, not duplicated. |
| v0.16 direct-pay path | `ReceiptParser`, `PaymentExpectation`, `DestinationClaim`, `paybox.py` | The kiosk path (section 6.5). |

**Product decision embedded here (needs a yes from Mike and ChatGPT):** money goes
through Prok (or its licensed partner) and providers are paid out; the v0.16 "pay the
seller's own number" path becomes exceptional.

---

## 2. Who holds the money, who sends it, who receives it, when

```
 Customer (MTN/Airtel wallet)
      │  Collections API "request to pay" — PIN prompt on the customer's own phone
      ▼
 FLOAT  = business wallet(s) at MTN and Airtel, held by [Prok entity | licensed partner]
      │  the ledger says whose money it is; the daily invariant proves it
      │
      │  per session, from signed evidence:
      │     customer_credit −gross · provider +share · relay +share · prok +share
      │
      ├──► Provider   Disbursements API, threshold or weekly, automatic
      ├──► Relay      same engine
      ├──► Customer   refund of UNSPENT credit to the ORIGINATING number only
      └──► Prok       its share, monthly, after reconciliation, human-approved
```

| Who | Holds | Sends | Receives | When |
|---|---|---|---|---|
| Customer | Own MoMo/Airtel wallet; a **credit at Prok** (form decided in section 9) | Top-up 100–10,000 CFA | Internet; refund of unspent credit (6.3) | Top-up when they have cell signal (the PIN prompt is USSD, no data needed). Spend per session from the signed final checkpoint. |
| Provider | An earnings balance | Nothing | A disbursement | Balance ≥ threshold (ex. 2,000 CFA) or weekly run if ≥ minimum (ex. 500 CFA) |
| Relay | Same | Nothing | Same | Same engine; one balance per person |
| Prok / partner | The float | Payouts, refunds | Prok's share; equipment recovery | Monthly, after 7.3 |

**Invariant, checked daily and before every payout run:**
`float(MTN) + float(Airtel) ≥ Σ customer_credit + Σ earned + Σ holds + payouts_in_flight`.
If it fails, payouts pause and Mike is told.

---

## 3. The ledger (build first)

Brain schema migration **5**, ALTER-based, upgrade-tested from a real schema-4 DB.

```
ledger_accounts   id, owner_id, kind (CUSTOMER_CREDIT|EARNED|PROK_REVENUE|
                  FLOAT_MTN|FLOAT_AIRTEL|IN_FLIGHT|LOSS), balance_centimes (cache)
ledger_postings   id, ts, kind (TOPUP|HOLD|HOLD_RELEASE|SESSION|RELAY_SHARE|PROK_SHARE|
                  PAYOUT|PAYOUT_CONFIRMED|PAYOUT_FAILED|REFUND|TREASURY_MOVE|
                  DOUBLE_SPEND_LOSS|ADJUSTMENT),
                  debit_account, credit_account, amount_centimes, ref, memo
topups            id, customer_id, rail, msisdn_hash, amount, operator_ref,
                  state (REQUESTED|PENDING|CONFIRMED|FAILED|EXPIRED|UNKNOWN)
holds             id, customer_id, seller_id, amount, kind (ONLINE|OFFLINE_AUTH),
                  expires_at, session_id?, state (ACTIVE|CONSUMED|RELEASED|EXPIRED)
payouts           id (= operator reference id), payee_id, rail, msisdn_hash, amount,
                  state (SCHEDULED|SENT|UNKNOWN|CONFIRMED|FAILED|NEEDS_ATTENTION),
                  attempts, operator_txn_id, last_error, created_at, settled_at
treasury_moves    id, from_rail, to_rail, amount, method, ref, state
```

- Double-entry; balances are sums. Integer centimes. Nothing deleted; reversals are
  `ADJUSTMENT` with a memo and an operator name.
- A session posts once, keyed by settlement id; `evidence.verify`'s DISPUTED rule
  carries over.
- Every float movement has an operator reference so reconciliation is a join, not a
  guess.

---

## 4. Metering: what actually controls access

### 4.1 Phone-to-phone (exists) — the tunnel *is* the gateway

Both ends count, both sign every 30 s or 1 MB, settlement is the **last checkpoint both
signed**. When the budget or hold is exhausted `Gateway.enforceMax()` stops forwarding.
A crashed session costs at most one interval. v0.18 adds only the pre-admission hold
check (section 5).

### 4.2 Fixed site serving ordinary devices — captive-portal router (v0.19 spike)

OpenWrt router (GL.iNet class) + **openNDS**: per-client data quotas, rate limits,
session timeouts, an external **FAS** (the Brain) that authorises a client, and a
**BinAuth** hook that reports `client_mac, bytes_incoming, bytes_outgoing,
session_start, session_end` on every deauth. Access stops **on the box** when the quota
is consumed. The **site host** is the provider; Prok adds an equipment-recovery share
(7.2). Upstream: fibre or 4G SIM. **Not Starlink** — no licence in Congo-Brazzaville
per section 11.

### 4.3 Not metered, not sold

A plain Android hotspot to strangers. ProkNet must never price it.

---

## 5. Spending: the hold, the offline fallback, and the hard loss bound

### 5.1 Normal path — ONLINE hold by the seller (no double-spend possible)

The seller is the phone **with** Internet. Before admitting a paid contract it calls
`POST /v1/credit/hold {customer_id, amount = contract.buyerBudget}`; the Brain moves
credit → `holds` **atomically** (one SQL transaction, `UNIQUE (customer_id) WHERE state
= ACTIVE` so a customer cannot hold twice). Response ≤ 2 s on the seller's own upstream.
Session end → evidence → `HOLD` consumed, remainder released. A hold not consumed within
`HOLD_TTL` (ex. 30 min) is released by the sweep.

With the Brain reachable there is **no** double-spend and **no** loss: the same credit
cannot be held by two sellers.

### 5.2 Fallback — signed OFFLINE authorisation (bounded loss, Prok bears it)

Used **only** when the seller's hold call fails (Brain down, or the seller's upstream
cannot reach it). The customer's phone carries `SpendAuth {customer_id, amount, issued_at,
expires_at, serial}` signed by a dedicated Brain key (pinned like `ReceiptRules`).

Hard limits, all enforced in code and each pinned by a test:

| Limit | Example value | Enforced by |
|---|---|---|
| `OFFLINE_MAX` per authorisation and per session | 300 CFA | seller refuses a contract whose budget exceeds it |
| Validity | 6 h from issue | seller checks `expires_at` |
| Issued only to customers with ≥ 1 confirmed top-up and no open debt | — | Brain at issue time |
| One live authorisation per customer | — | serial; Brain reissues only after the previous expires or is reported consumed |
| Global daily budget for offline-auth settlements | ex. 20,000 CFA/day | Brain stops **issuing** when the day's offline settlements pass it; existing ones expire within 6 h |

**Worst-case loss per customer per 6 h = `OFFLINE_MAX × (number of distinct offline
sellers reached − 1)`.** The first seller is paid from the customer's real credit; each
further one is the loss. With `OFFLINE_MAX = 300` and, say, 4 sellers in 6 h, that is
900 CFA — and the global daily budget caps the sum across all customers regardless.

**Who bears it: Prok.** Sellers who served in good faith on a valid authorisation are
paid in full — the ledger posts `DOUBLE_SPEND_LOSS` from `PROK_REVENUE` (never from any
provider, never from the float's customer liabilities). The customer's identity and
device pseudonym are blocked from *further* offline authorisations and from new
sessions until the negative balance is topped up; Mike's review is right that this
recovers nothing — it only stops repetition. The loss is a **measured, capped cost of
doing business** and appears as its own line in the daily report. If it ever exceeds the
budget, the Brain turns offline authorisations off and everybody falls to 5.3.

**Pilot-only rule:** offline authorisations are **disabled** in the pilot. Loss from
double-spend is therefore exactly zero while the pilot runs; sellers that cannot reach
the Brain refuse paid sessions (free ones unaffected) and the app says why. The offline
path is switched on only after the online path is hardware-proven.

### 5.3 No hold, no authorisation

Paid session refused. Free / sponsored sessions unaffected. The exceptional kiosk path
(6.5) is reachable only from here and only when the Brain has been unreachable from the
seller for longer than `KIOSK_AFTER` (ex. 24 h).

---

## 6. Flows, including the ugly cases

Example split (⚠ examples): no relay — provider 75 / Prok 25; via relay — provider 65 /
relay 10 / Prok 25. Equipment sites add an equipment-recovery share (7.2).

### 6.1 Customer

1. **Recharger** → rail, amount (chips 200 / 500 / 1,000 / 2,000; max balance 10,000).
   Brain calls request-to-pay; the operator pushes a PIN prompt over USSD; the app says
   "Confirmez sur votre téléphone" and polls the Brain, which polls the operator.
   `CONFIRMED` → `TOPUP` posting. `FAILED`/`EXPIRED` → nothing posted, one sentence,
   retry. **`UNKNOWN`** (operator timed out, no callback) → keep querying by our
   reference id for up to 24 h; never issue a second request-to-pay for the same top-up
   under a new id while the first is unknown; the customer sees "Recharge en cours de
   vérification".
2. **Session**: as today, with the hold at admission. After the closing checkpoint the
   screen shows the cost from the numbers the seller signed.
3. **Short or seller-stopped session — the v0.15.0 rule stands.** `Teardown` issues a
   closing checkpoint and waits for the countersignature whichever side stopped; the
   settlement is `costFor(final.billable)`. **New in v0.18: zero when no usable Internet
   was delivered** — a signed final checkpoint whose `billable` is below
   `USABLE_FLOOR` (ex. 64 KB, the existing tolerance constant) settles at 0 and releases
   the hold; nothing below that is "Internet" a person could have used. No signed final
   checkpoint → no obligation, unchanged (`Settlement.fromSession` already returns null).
4. **Dispute**: the last mutually signed checkpoint is the truth and both phones hold it.

### 6.2 Provider

1. **Gagner** → willing (exists) + a **verified payout number** (rail + number; verified
   by the first confirmed payout, or by a 1 CFA test disbursement that must reach
   `CONFIRMED`). Replaces `DestinationClaim` as the destination of record; same signed
   object, addressed to Prok.
2. Session ends → evidence → `SESSION` + `PROK_SHARE` (+ `RELAY_SHARE`). Wallet shows
   **Gagné (en attente de versement)** and **Prochain versement : vendredi / dès 2 000 F**.
3. **Automatic payout** — section 8.
4. **Data cost check**: bundles ≈ 1,000–1,200 CFA/GB; existing floor 1–4 CFA/MB. Whether
   the provider share clears it at real usage is the pilot's first number.

### 6.3 Refunds

Unspent credit → **the originating number only**, ≤ once per 30 days, ≤ unspent credit,
via Disbursement. Never to another number, never to cash, never to another customer.
Session refunds are not needed (6.1.3); goodwill = `ADJUSTMENT` with a memo.

### 6.4 Relay

`relayId` added to the signed contract (`Contract.version` bump; old contracts parse).
A "verified relay" = verified payout number + ≥ N relayed sessions (ex. 5) before
payouts start; the share accrues from the first. No relay → no posting.

### 6.5 Exceptional path: kiosk / pay the provider directly

**Exceptional by construction, not by label.** It can only be entered when:

- the seller has been unable to reach the Brain for > `KIOSK_AFTER` (ex. 24 h), **and**
- the customer holds no valid authorisation and no credit the seller can verify, **and**
- the seller has explicitly enabled "Paiement de secours" in Réglages du partage.

Then, and only then, the v0.16 rule applies: Trust cap 50 CFA, seller's own number, SMS
receipt. Prok takes no share (it never touched the money) and the **seller** bears any
Trust-cap loss, as today. The normal Home / Gagner / Wallet screens never mention a
kiosk, a provider's number, or paying a person; those words exist only inside the
secours screen. A test asserts no normal-flow string contains "kiosque" or a phone
number.

### 6.6 Phone reinstalled / lost

Credit lives on the Brain by identity. Recovery = the customer proves the top-up number
(a 0-CFA request-to-pay they confirm); the old identity's balance moves by `ADJUSTMENT`.
Debts follow the device pseudonym as today.

---

## 7. Prok: revenue, equipment, reconciliation

### 7.1 Prok's share → moved float → Prok bank monthly, only after 7.3, only by a human.

### 7.2 Equipment recovery (fixed sites)
Landed cost per box recorded; sessions on that box carry an **equipment share** (ex.
15 %, from the host's side by written agreement) until cumulative = cost, then it stops
automatically and the host's share rises. Shown on the host's Gagner screen, never hidden.

### 7.3 Reconciliation (daily; failure pauses payouts)
1. Operator statements (Collections + Disbursements) per rail, or wallet balances in the
   pilot. 2. Every `CONFIRMED` top-up/payout has an operator ref present there; every
   operator entry has a posting. 3. Invariant (section 2). 4. Telegram report: float per
   rail, liabilities, payouts due, `UNKNOWN` items, double-spend loss, unmatched refs.

---

## 8. Payout engine — automatic in the finished flow

### 8.1 Schedule and thresholds (⚠ examples)

| Rule | Example | Why |
|---|---|---|
| Threshold payout | balance ≥ 2,000 CFA → next hourly run | Money when it is worth having |
| Weekly run | Friday 17:00, everyone ≥ 500 CFA | "Vendredi" is a sentence |
| Minimum | 500 CFA | Withdrawal fees 100–150 CFA on ≤ 5,000 at MTN Congo (third-party tariff pages) |
| Per-payout cap | 100,000 CFA | Wallet limits; larger balances split over days |
| Rail | the payee's choice, one number | Prok never picks a number for anyone |

### 8.2 State machine — an unknown result is not a failure

```
SCHEDULED ─► SENT ──► CONFIRMED  (operator status SUCCESSFUL, or callback verified)
              │
              ├─► FAILED  (operator status FAILED with reason)  ─► RETRY (≤3, 30m/2h/12h)
              │                                                    └─► NEEDS_ATTENTION
              └─► UNKNOWN (timeout, 5xx, no callback)
                     └─► query status by OUR reference id every 5 min for up to 24 h
                           ├─► SUCCESSFUL → CONFIRMED
                           ├─► FAILED     → RETRY
                           └─► not found after 24 h → NEEDS_ATTENTION (human decides)
```

- The payout id **is** the operator reference (MTN: the client-chosen `X-Reference-Id`
  that `GET /disbursement/v1_0/transfer/{referenceId}` reads; Airtel: the client
  transaction id passed to its enquiry endpoint). A retry reuses the **same** reference
  only if the operator reports it FAILED or absent; a new reference is never minted
  while the old one is `UNKNOWN`. This is what makes "never sent twice" true.
- A `FAILED` for a wrong / inactive number goes straight to `NEEDS_ATTENTION` (retrying
  cannot fix it); the payee sees "Versement en attente — vérifiez votre numéro" with a
  button that re-verifies.
- Money moves in the ledger only on `CONFIRMED`; `SENT`/`UNKNOWN` sit in `IN_FLIGHT`
  and count against the invariant.

### 8.3 Cross-rail treasury — paying an Airtel payee from MTN-collected money

Collections land per rail; payees choose per person. Three ways, in order of preference:

1. **Aggregator with one balance** (pawaPay lists Congo-Brazzaville; also others to
   quote): deposits from both rails and payouts to both rails settle against **one**
   merchant balance, so the netting is theirs. Simplest; cost unknown.
2. **Direct operator APIs + treasury moves**: the engine computes `due(rail)` = payouts
   scheduled on that rail; if `float(rail) < due(rail) + reserve`, it raises a
   `treasury_move` (MTN business wallet → Prok bank → Airtel business wallet, or a
   business-to-business transfer if the operators offer one) and **pauses that rail's
   payouts** until the move is `CONFIRMED`. Moves are human-approved in the pilot,
   automatic later with a daily cap. Bank moves take a day; the weekly cadence absorbs
   that, threshold payouts on a starved rail wait.
3. **Never**: paying an Airtel payee by a person-to-person transfer from somebody's
   personal wallet.

The float invariant is per rail **and** total, so a starved rail is visible before it
blocks anyone.

### 8.4 Pilot-only: manual confirmation
The engine computes the identical list; Mike executes the transfers from the business
wallet and marks each `CONFIRMED` with the operator ref in the admin. Same state
machine, same ledger, same app screens. This is the pilot's mode **only** because
production API credentials do not exist yet (section 9); it is not the design.

---

## 9. Legal structure — what counsel must confirm (⚠ nothing here is confirmed)

### 9.1 Why this is not obviously "just a prepaid service"

Under **Règlement 04/18/CEMAC/UMAC/COBAC** (in force 2019-01-01) electronic money is
monetary value stored electronically representing a claim on the issuer and accepted as
a means of payment; issuing it needs a credit institution, a microfinance institution
or a licensed *établissement de paiement* (SA, 500 M CFA capital, segregated customer
account, AML). The same regulation covers **payment services** — collecting funds on
behalf of third parties and transferring them — not only stored value.

The review's point stands: **the Internet is delivered by independent providers**, so
Prok collecting from customers and paying providers looks like *intermediating
payments between third parties*, which may be a regulated payment service **even with
no stored balance at all**. A "we only sell our own service" argument requires Prok to
actually be the seller.

### 9.2 Three structures, compared

| | A. Licensed partner holds the funds | B. Prok entity holds a prepaid balance | C. Pay-per-session (no balance) |
|---|---|---|---|
| Who holds customer money | A licensed EMI / *établissement de paiement* / aggregator; Prok is technical operator and merchant of record for its own share | Prok's business wallets at MTN/Airtel; Prok is the debtor of every customer's credit | The operator until a session is paid; Prok holds nothing between sessions |
| Regulatory exposure | Lowest — the licence is theirs; Prok signs a merchant/agent contract | Highest — likely e-money issuance **and** payment intermediation | Middle — no e-money, but payment intermediation question remains |
| UX | Same app; top-up prompt may show the partner's name | Best; what the brief describes | A PIN prompt per session; unusable for 50 CFA sessions after per-transaction fees; a minimum session (ex. 200 CFA) is forced |
| Cost | Partner fees on every top-up and payout | Operator fees only | Operator fee on **every** session |
| Time to production | Partner onboarding (weeks) | Licence route (18 months+ and 500 M CFA) or a lawyer's opinion that it is exempt | Same operator KYC as A/B |
| Ledger / app impact | Identical ledger; the float accounts belong to the partner | Identical | Identical ledger; `TOPUP` becomes per-session |

**Recommendation for the finished product: A.** Prok does not become a financial
institution; the ledger, holds, evidence and payouts are unchanged; the partner's
licence covers custody and payment intermediation. **C is the fallback** if no partner
will take a Congolese pilot, and it is the only structure that needs *no* custody
answer at all — at the price of per-session fees and a minimum session.
**B is the design to avoid** unless counsel says in writing that it is exempt.

Whichever is chosen, the strongest commercial framing is also the truest one:
**Prok is the service provider; providers and relays are Prok's subcontractors paid a
commission**; customers buy Internet from Prok. That is what the equipment model and the
Prok share already say. Counsel must confirm it holds.

### 9.3 The checklist for Congo / CEMAC counsel

1. Is a customer's prepaid ProkNet credit (non-transferable, no cash-out except
   refund-to-origin, ≤ 10,000 CFA, consumable only as Internet) electronic money under
   04/18? Is there any closed-loop / limited-network exemption in CEMAC practice, and
   does the fact that **independent providers deliver the service** defeat it?
2. Is Prok collecting from customers and paying providers a **payment service**
   (intermediation / transfer of funds) under 04/18 even with **no** stored balance?
3. Does the "Prok is the seller, providers are subcontractors on commission" framing
   hold, and what does it imply for VAT, withholding tax on commissions, and the
   providers' status (self-employed vs. employee)?
4. May Prok operate under a licensed partner's licence as a merchant / technical
   operator / agent, and what contract form does COBAC expect?
5. Do providers reselling Internet access need an **ARPCE** authorisation, declaration
   or ISP licence (ARPCE lists an "FAI" regime with administrative, technical and
   financial conditions); does Prok? Does a fixed site with a captive portal change the
   answer?
6. Which entity signs with MTN Congo and Airtel Congo: is a Congolese-registered company
   (RCCM) with a local bank account required for API go-live, and can a French parent
   own it?
7. Refund-to-origin, dormant credit, and complaint handling: what consumer rules apply.
8. AML/KYC: what identification is required for a *payee* (provider) receiving regular
   disbursements, and for a customer topping up ≤ 10,000 CFA.

**Real money does not move until questions 1–6 have written answers.**

---

## 10. Operator and partner facts (what a real integration needs)

| Fact | Status |
|---|---|
| MTN MoMo Open API: Collections / Disbursements / Remittances; free sandbox at momodeveloper.mtn.com; **Congo (Brazzaville) listed**; MTN Congo's page routes businesses to the same portal (sosclient.cg@mtn.com, WhatsApp 067000123) | **Confirmed** |
| MTN go-live: KYC documents proving the business, per country; production portal momoapi.mtn.com; contracts follow | **Confirmed** (community docs); which documents for Congo: **not confirmed** |
| MTN disbursement: client-generated reference id used for status query; `externalId` for our own reconciliation; status SUCCESSFUL / PENDING / FAILED with reason | **Confirmed** (API docs and client libraries) |
| Airtel Africa developer portal: Collection + Disbursement APIs, OAuth2, sandbox; Congo-B hosts `openapiuat.airtel.cg` / `openapi.airtel.cg` | **Partly confirmed** — hosts appear in third-party client code; the portal blocked automated reading |
| pawaPay: Congo-Brazzaville among 23 markets; deposits, refunds, payouts | **Confirmed on their site**; operators per country, fees, whether they accept the Prok entity: **not confirmed** |
| Fees: MTN Congo merchant collection and API disbursement fees | **Not published**; consumer tariffs (third party): withdrawal 100–150 CFA on ≤ 5,000, P2P 50–100 |
| Provider data cost ≈ 1,000–1,200 CFA/GB | **Indicative** (third-party comparison pages) |
| Starlink in Congo-Brazzaville | **No licence**; ARPCE seized an unlicensed kit (2024) |

---

## 11. Sources

- MTN MoMo developer portal — https://momodeveloper.mtn.com/ ; Go-Live — https://momodeveloper.mtn.com/golive ; production configuration — https://momodevelopercommunity.mtn.com/how-to-59/momo-api-production-configuration-101 ; disbursement status semantics via client docs — https://www.npmjs.com/package/mtn-momo , https://github.com/lepresk/momo-api
- MTN Congo Open API — https://www.mtn.cg/momo/momo-entreprise/open-api/ ; MTN MoMo markets/pricing page — https://momo.mtn.com/pricing/
- Airtel Africa developer portal — https://developers.airtel.africa/ ; Disbursement API v2 — https://developers.airtel.africa/documentation/disbursement-apis/2.0
- pawaPay — https://www.pawapay.io/
- Règlement 04/18/CEMAC/UMAC/COBAC — text https://www.beac.int/wp-content/uploads/2019/07/REGLEMENT-N-04-18-CEMAC-UMAC-COBAC-du-21-d%C3%A9cembre-2018.pdf ; summary https://cesttoutdroit.com/article-droit-bancaire-et-financier/nouveau-reglement-cemac-relatif-aux-services-de-paiement/
- ARPCE, Fournisseur d'accès internet — https://www.arpce.cg/fournisseur-acces-internet
- MTN MoMo Congo consumer tariffs (third party) — https://blog.iambeezy.app/fr/frais-mtn-momo-congo-2026-tarifs-complets/ ; data bundles — https://blog.iambeezy.app/fr/forfaits-internet-mtn-airtel-congo-2026-comparatif-plans/
- openNDS — https://opennds.readthedocs.io/en/stable/ ; BinAuth — https://opennds.readthedocs.io/en/stable/binauth.html
- Starlink / ARPCE — https://www.agenceecofin.com/regulation/2506-119755-congo-l-arpce-demantele-une-installation-starlink-non-autorisee-dans-une-exploitation-forestiere

---

## 12. Confirmed · Assumed · Blocks real money · Pilot-only

**Confirmed (verified in code or on the source's own site)**
- The phone tunnel meters both ends with co-signed checkpoints; seller-stop issues a
  closing checkpoint (v0.15.0); no signed checkpoint → no obligation.
- MTN MoMo API lists Congo-B; sandbox is free; go-live needs business KYC.
- pawaPay lists Congo-B. openNDS reports per-client bytes to BinAuth.
- CEMAC 04/18 regulates both e-money and payment services; licence needs 500 M CFA.
- Starlink has no Congo-B licence.

**Assumed (design choices that stand until measured or quoted)**
- All splits, thresholds, caps, TTLs (25 %, 10 % relay, 2,000 / 500 CFA, 300 CFA offline
  max, 6 h, 20,000 CFA/day, 64 KB usable floor, 24 h kiosk gate).
- Provider data cost ≈ 1.2 CFA/MB. Sellers can reach the Brain within 2 s in the field.
- Airtel Congo-B API hosts and onboarding route. Aggregator fees.
- That "Prok is the seller, providers are subcontractors" is the right framing.

**Blocks real money (must exist before a real customer pays)**
- Written answers to counsel checklist 9.3 items 1–6, and a decision A / C (not B).
- A Congolese Prok entity (or a partner contract) and a business wallet with production
  API credentials on at least one rail.
- Ledger + hold + evidence posting hardware-proven on both phones against the sandbox.
- Reconciliation running one week with zero unmatched entries.
- ARPCE answer on whether providers or Prok need an authorisation.

**Pilot-only (deliberately not the finished design)**
- Manual weekly payout confirmation (8.4).
- Offline authorisations **disabled** (5.2) — double-spend loss is zero in the pilot.
- One rail (whichever gives production access first); cross-rail treasury by hand.
- Kiosk path enabled only on test phones.

---

## 13. Build order (v0.18, each step useful alone)

1. **Ledger** — migration 5, postings, invariant, `GET /v1/wallet` extended. Server
   tests: double-entry, idempotent session posting, upgrade from a real schema-4 DB.
2. **Online hold** — `POST /v1/credit/hold` atomic; seller-side admission check; hold
   TTL sweep; contract carries the hold id.
3. **Session → ledger** — `POST /v1/settlements` posts on verify; `relayId` in the
   contract; `USABLE_FLOOR` zero rule with the v0.15.0 teardown untouched (a test
   settles the same seller-stopped session to the same centime as today).
4. **Top-up, sandbox** — MTN Collections request-to-pay; `UNKNOWN` handling by
   reference id; **Recharger** screen; TESTING section on real phones.
5. **Payout engine** — full state machine incl. `UNKNOWN` queries and NEEDS_ATTENTION,
   verified payout number, per-rail float check; pilot mode = manual confirm behind the
   same machine; then the Disbursements sandbox adapter.
6. **Reconciliation + Telegram report**, per rail and total.
7. **Kiosk path gating** — `KIOSK_AFTER`, the secours toggle, the no-kiosk-words test.
8. **Offline authorisation** — `SpendAuth`, key ceremony, cross-language fixture, loss
   bound tests, disabled by default. Last, and only after 1–6 are hardware-proven.
9. **v0.19 spike** — one openNDS box, FAS endpoint, BinAuth posting.

Not in v0.18: production credentials, multi-currency, customer-to-customer transfers,
any cash-out "Solde".
