# ProkNet v0.18 — Prepaid credit, metered sessions, automatic payouts

Date: 2026-09-25
From: Claude (implementation engineer)
To: Mike (product owner), ChatGPT (architect)
Status: **PROPOSAL — nothing here is built.** Sections marked ⚠ need a partnership,
a legal opinion, or a real test before they can be relied on.

---

## 0. The brief, and the one-paragraph answer

> Customers add credit with MTN MoMo or Airtel Money. A controlled gateway meters the
> real session. Providers and relays earn a share, see it in Prok Wallet, and are paid
> out automatically. Nobody sends dozens of tiny payments by hand. Don't assume an
> ordinary Android hotspot can meter or control a customer.

**Answer.** Prok (a Congolese legal entity) holds one MTN MoMo and one Airtel Money
business wallet. Customer top-ups flow into them through the operators' Collections API.
The Brain keeps a double-entry **ledger** in integer centimes: customer credit,
provider earnings, relay earnings, Prok revenue. A session is metered by the
**existing ProkNet tunnel** — every buyer packet already passes through the seller's
app, both phones count bytes, and both sign a checkpoint every 30 s / 1 MB — so the
ledger is posted from signed evidence the Brain already verifies today. For fixed
sites serving ordinary Wi-Fi devices, a small OpenWrt router with a captive portal is
the gateway; the Brain is its authentication server and it reports per-client bytes.
Earnings accumulate; a **payout engine** sends one Mobile Money disbursement per
person when their balance passes a threshold or on a weekly schedule, with retries and
a "needs attention" queue. Unused credit stays. Refunds go back only to the number
that paid.

The biggest non-code fact: **in CEMAC, holding customer balances is regulated.** The
design keeps Prok credit as a prepaid *service* (non-transferable, no cash-out except
refund-to-origin, capped), and section 9 says exactly what still needs a lawyer.

---

## 1. What already exists (so v0.18 is not a second money system)

Verified in the code on 2026-09-25:

| Piece | Where | What it gives v0.18 |
|---|---|---|
| Buyer traffic through the seller app | `vpn/ProkVpnService.kt`, `node/TunnelClient.kt`, `node/Gateway.kt` | **The phone path is already a controlled gateway.** VpnService captures every IPv4 packet of every app on the buyer (`addRoute 0.0.0.0/0`), a user-space TCP/DNS stack forwards it over Wi-Fi/Bluetooth to the seller, the seller opens the real sockets. Bytes are counted on **both** phones (`Gateway.kt:317/535/559/574`, `TunnelClient.kt:326/336/474/489/568`). |
| Signed contract + checkpoints | `core/Market.kt` — `Contract` (buyer proposes, seller admits), `Checkpoint` every 30 s or 1 MB, tolerance 64 KB / 10 %, buyer countersigns | Meter readings neither side can inflate alone. |
| Settlement + evidence | `core/Settlement.kt`, `core/Evidence.kt`, `server/brain/evidence.py`, `settlement.py` | The Brain **re-derives** gross/fee/net from the signed bytes. `POST /v1/settlements` exists. |
| Pricing | `core/Pricing.kt`: CFA per MB, centimes, seller floor 1–4 CFA/MB by policy, `DEFAULT_FEE_PCT = 5`, FREE/COMMERCIAL/SPONSORED/PROK_FUNDED sources | Rates and the fee hook. |
| Trust cap | `core/Trust.kt`, `SettlementPolicy.kt` (credit limit 50 CFA, settle threshold 25 CFA) | The "how much unpaid Internet may a stranger get" rule. In v0.18 prepaid credit replaces it for paid sessions; it stays for the offline-fallback case. |
| Wallet UI | `core/Wallet.kt`, `WalletUi.kt` — "À payer / À recevoir / Payé / Reçu / Gagné", never "Solde" | Screen scaffolding. |
| Relay | `core/Relay.kt`, `node/RelayNode.kt` — live A→B→C forwarding, AES-GCM end to end, `FLAG_VIA_RELAY` in the contract | A relay exists and **earns nothing today**. |
| Server money routes | `/v1/settlements`, `/v1/payments/initiate`, `/v1/payments/webhook`, `/v1/wallet`, `/v1/device/risk` | Routes to extend, not duplicate. |
| v0.16 direct-pay path | `ReceiptParser`, `PaymentExpectation`, `DestinationClaim`, `paybox.py` | Buyer pays the **seller's own** number at a kiosk; the seller's phone reads the operator SMS. |

**What v0.18 changes about v0.16.** v0.16 assumed "pay the seller directly, ProkNet
just watches". This brief says money goes through Prok and providers are paid out.
Those are different products. Recommendation: v0.16's direct-pay path is **frozen as
the offline fallback** (section 6.5), and prepaid credit becomes the normal way to pay.
This is a product decision for Mike and ChatGPT — say yes or no before v0.18 starts.

---

## 2. Who holds the money, who sends it, who receives it, when

```
 Customer (MTN/Airtel wallet)
      │  top-up: Collections API "request to pay", customer confirms PIN on their phone
      ▼
 PROK FLOAT  = Prok's MTN MoMo business wallet + Prok's Airtel Money business wallet
      │        (held by the Congolese Prok entity; the ledger says who it belongs to)
      │
      │  ledger, per session (from signed evidence):
      │     customer_credit  −gross
      │     provider_earned  +provider share
      │     relay_earned     +relay share   (only if FLAG_VIA_RELAY)
      │     prok_revenue     +Prok share
      │
      ├──► Provider   (Disbursements API, threshold or weekly)
      ├──► Relay      (same engine)
      ├──► Customer   (refund of UNSPENT credit to the ORIGINATING number only)
      └──► Prok bank  (operations + equipment recovery, monthly, manual approval)
```

| Who | Holds | Sends | Receives | When |
|---|---|---|---|---|
| **Customer** | Their own MoMo/Airtel wallet; a **credit balance at Prok** (a claim on Prok for Internet access, not money) | A top-up (100 – 10,000 CFA) | Internet; a refund of unspent credit (rare, section 6.3) | Top-up: any time they have signal. Spend: every session, from the last mutually signed checkpoint. |
| **Provider** | Nothing of anyone else's. An **earnings balance** at Prok | Nothing | A Mobile Money disbursement | When balance ≥ **threshold** (example 2,000 CFA) or on the **weekly** run if balance ≥ minimum (example 500 CFA). |
| **Relay** | Same as provider | Nothing | Same as provider | Same engine; relay earnings and provider earnings are one balance if the same person does both. |
| **Prok** | The float. Legally the money, operationally a custodian of the ledger liabilities | Payouts, refunds | Its share; equipment recovery | Prok's share moves float → Prok bank **monthly**, after reconciliation (section 7). |

**Invariant, checked daily:** `float_balance ≥ Σ customer_credit + Σ provider_earned +
Σ relay_earned + payouts_in_flight`. If it ever fails, payouts pause and someone is told.

---

## 3. The ledger (the thing to build first)

Brain schema migration **5**. Numbered, ALTER-based, with an upgrade test from a real
schema-4 database (the v0.16.4 lesson).

```
ledger_accounts   id, owner_id (node id | 'prok' | 'float:mtn' | 'float:airtel'),
                  kind (CUSTOMER_CREDIT | EARNED | PROK_REVENUE | FLOAT | IN_FLIGHT),
                  balance_centimes (cached, re-derivable), updated_at
ledger_postings   id, ts, kind (TOPUP | SESSION | RELAY_SHARE | PROK_SHARE | PAYOUT |
                  PAYOUT_FAILED | REFUND | ADJUSTMENT | HOLD | HOLD_RELEASE),
                  debit_account, credit_account, amount_centimes,
                  ref (settlement id | topup id | payout id), memo
topups            id, customer_id, rail (MTN|AIRTEL), msisdn_hash, amount_centimes,
                  operator_ref, state (REQUESTED|PENDING|CONFIRMED|FAILED|EXPIRED),
                  created_at, confirmed_at
holds             id, customer_id, amount_centimes, expires_at, session_id?,
                  state (ACTIVE|CONSUMED|RELEASED)
payouts           id, payee_id, rail, msisdn_hash, amount_centimes, state
                  (SCHEDULED|SENT|CONFIRMED|FAILED|RETRY|NEEDS_ATTENTION),
                  attempts, operator_ref, last_error, created_at, settled_at
```

Rules that keep it honest:

- Every posting is **double-entry**; a balance is a sum, never an assignment.
- Amounts are **integer centimes**, same as `Market`/`Settlement` today.
- A session posts **once**, keyed by settlement id. `evidence.verify` already rejects
  a second, different evidence for the same session (DISPUTED); the ledger inherits it.
- Nothing in the ledger is ever deleted. Mistakes are reversed by an `ADJUSTMENT`
  posting with a memo and an operator name.
- Operator refs (MoMo/Airtel transaction ids) are stored so every float movement can be
  matched to a bank-style statement.

---

## 4. Metering: what actually controls access

### 4.1 Phone-to-phone (exists) — the tunnel *is* the gateway

The brief's worry — "an ordinary Android hotspot app cannot meter each customer" — is
right, and ProkNet does not do that. The buyer's own VpnService routes everything into
the ProkNet app; the seller's app forwards it. Consequences:

- **Metering is exact** for what was forwarded: both ends count, both sign every 30 s
  or 1 MB, and the settlement uses the **last checkpoint both signed** (`Gateway.
  finalizeContract`). A crashed session costs at most one interval.
- **Control is real**: when the budget is exhausted or the customer's authorisation
  expires, `Gateway.enforceMax()` stops forwarding. There is no "the customer stays
  connected to my hotspot and keeps browsing".
- The seller never sees the buyer's traffic content beyond what a proxy sees; through a
  relay the middle phone sees only sizes.

**v0.18 adds one thing here:** the seller checks a **spending authorisation** before
admitting a paid contract (section 5). Everything else in the meter is unchanged.

### 4.2 Fixed site serving ordinary devices (new hardware) — captive-portal router

For equipment funded from France at a fixed location (shop, home with fibre or a 4G
router SIM), where customers connect laptops and any phone without the ProkNet app:

- **Box:** any OpenWrt-capable router (GL.iNet class, ~40–80 € landed) or a Raspberry
  Pi with a USB Wi-Fi AP. Upstream = Congo Telecom fibre or an MTN/Airtel 4G SIM.
  ⚠ Do **not** plan on Starlink: as of the sources in section 10, Starlink has no
  licence in Congo-Brazzaville and ARPCE has seized unlicensed kits.
- **Software:** OpenWrt + **openNDS** captive portal. openNDS supports per-client
  **data volume quotas, rate limits, session timeouts**, an external **Forwarding
  Authentication Service (FAS)** and a **BinAuth** hook that receives, on every deauth,
  `client_mac, bytes_incoming, bytes_outgoing, session_start, session_end, client_token`.
- **Wiring:** the FAS is a small page on the Brain. The customer's device opens the
  portal, enters their ProkNet code (or scans a QR from the ProkNet app, or pays with a
  MoMo prompt right there). The Brain answers with a quota = their credit at the site's
  rate. BinAuth posts the counted bytes back to the Brain at session end and every N
  minutes via `ndsctl json`; the Brain posts the ledger. Access stops when the quota is
  consumed — enforced **on the box**, not on trust.
- **Who is the provider here:** the **site host** (owns the upstream subscription,
  keeps the box powered) earns the provider share. Prok earns its share **plus an
  equipment-recovery share** until the box is amortised (section 7.2).
- **Not in v0.18 code.** It is a documented pilot (one box, one site) for v0.19. The
  Brain's FAS endpoint is the only server work; the box runs stock openNDS.

### 4.3 What is *not* metered and will not be sold

A plain Android hotspot to strangers (no app, no box). ProkNet must not advertise or
price it; there is no way to know who used what.

---

## 5. Spending offline without double-spending Prok's float

Sessions happen phone-to-phone with no Internet on the buyer. The Brain must let a
customer spend credit **before it can hear about the session**, without letting them
spend the same credit twice at two sellers.

**Spending authorisation** (`core/SpendAuth.kt`, pure; verified on the seller):

- Signed by a dedicated Brain key, pinned in the app the same way `ReceiptRules.
  PINNED_CONFIG_KEY` is (same ceremony, same file layout in `docs/OPERATIONS.md`).
- Fields: `customer_id, amount_centimes, issued_at, expires_at, serial`. Amount =
  `min(credit, HOLD_MAX)` — example `HOLD_MAX = 500 CFA`. Expiry = 24 h.
- Refreshed on every Brain sync (the v0.17.10 cadence already gives 5 s / 30 s /
  15 min), so a customer who was online yesterday can buy today with no signal.
- Seller admits a paid contract only if `auth.amount ≥ contract.buyerBudget` and the
  auth is unexpired; it records the serial in the session. A customer with **no** valid
  auth and no credit falls back to the v0.16 Trust cap (50 CFA) — the existing rule.
- **Double-spend is bounded, not impossible.** Two offline sellers can each accept the
  same 500 CFA auth. When both settlements arrive, the Brain pays **both sellers** in
  full (they did the work and could not know), posts the customer negative, and the
  device pseudonym + identity are blocked until the top-up clears the debt — exactly the
  `Trust.kt` / `POST /v1/device/risk` model. Worst case per customer per 24 h ≈
  `HOLD_MAX × sellers reached offline`, which is small by construction.
- The ledger `HOLD` posting is created at issue time so the daily invariant counts it.

This is the one genuinely new security object in v0.18 and it gets the cross-language
fixture treatment (`server/tests/fixtures/`), like every signed object since v0.16.3.

---

## 6. Flows, including the ugly cases

Example split throughout (⚠ examples until data cost and operator fees are measured):

| | No relay | Via relay |
|---|---|---|
| Provider | 75 % | 65 % |
| Relay | — | 10 % |
| Prok | 25 % | 25 % |

Why the provider gives up the 10 %: the relay reached a customer the provider could not.
The Prok share is fixed on gross so the ledger never has to know whether a relay existed
to compute Prok's part. Equipment sites add an equipment-recovery share (7.2).

### 6.1 Customer

1. **Recharger** → chooses MTN or Airtel, amount (chips: 200 / 500 / 1,000 / 2,000 CFA,
   max balance 10,000). The Brain calls Collections *request-to-pay*; the operator
   pushes a PIN prompt to the customer's phone. Screen says "Confirmez sur votre
   téléphone" and polls. Confirmed → `TOPUP` posting, balance visible, new
   spending authorisation issued. Failed/expired → nothing posted, one plain sentence,
   retry button. **A top-up is never "assumed": only the operator callback or a status
   query confirms it.**
2. **Session**: as today. Home shows "Crédit Internet : 1 250 F". After the final
   checkpoint the app shows what the session cost from the same numbers the seller
   signed.
3. **Failed session** (no `INTERNET_UP`, or ended before the first checkpoint): the
   contract's minimum is **not** charged in v0.18 — the hold is released, nothing is
   posted. (Today `Market.finalCost` charges the minimum with no checkpoint; for a
   prepaid customer that reads as theft. Change the rule, keep the test.)
4. **Dispute**: none needed for bytes — the last checkpoint both signed is the truth
   and both phones hold it. A customer who thinks they were overcharged sees the
   checkpoint sequence in "Détails techniques".

### 6.2 Provider

1. **Gagner** → toggles willing (exists). New: must have a **verified payout number**
   (one-time: operator, number; verified by a 1 CFA test disbursement that must be
   confirmed, or by the first successful payout). This replaces `DestinationClaim` as
   the way a provider tells the network where money goes — same signed object, now
   addressed to Prok instead of to buyers.
2. Session ends → evidence submitted (exists) → Brain verifies → `SESSION` +
   `PROK_SHARE` (+ `RELAY_SHARE`) postings. Wallet shows **Gagné (en attente de
   versement)** and **Prochain versement : vendredi** or "dès 2 000 F".
3. **Payout** → `SCHEDULED` → Disbursement API → `SENT` → callback/enquiry → `CONFIRMED`
   → posting moves earned → paid. The provider gets the operator's own "vous avez reçu"
   SMS; ProkNet shows the operator reference.
4. **Failed payout** (wrong number, wallet limit, operator down): `RETRY` with backoff
   30 min / 2 h / 12 h; after 3 failures → `NEEDS_ATTENTION`, balance untouched, the
   provider sees "Versement en attente — vérifiez votre numéro" with a button to fix
   the number (which re-verifies). Money is **never** lost or re-sent twice: payout id
   is the idempotency key on both our side and the operator's `externalId`.
5. **Provider's data cost**: an MTN/Airtel bundle is roughly 1,000–1,200 CFA/GB
   (≈ 1.2 CFA/MB). The existing seller floor of 1–4 CFA/MB is above that. Whether 75 %
   of the customer price clears it at real usage is the first number the pilot must
   report.

### 6.3 Refunds

- **Unspent credit → the originating number only**, at most once per 30 days, amount ≤
  unspent credit, via Disbursement. Not to a different number, never to cash, never to
  another customer. This is what keeps Prok credit a *service prepayment* rather than
  a wallet (section 9).
- **Session refund**: not needed by design (6.1.3). An operator `ADJUSTMENT` exists for
  goodwill, with a memo, visible in the customer's history.

### 6.4 Relay

- The contract already carries `FLAG_VIA_RELAY`. v0.18 adds `relayId` to the signed
  contract (`Contract.version` bump, old contracts still parse) so the evidence names
  the relay and the Brain can post `RELAY_SHARE`.
- A "verified relay" = an identity with a verified payout number and ≥ N completed
  relayed sessions (example N = 5); before that the relay share accrues but is not paid
  out (guards against a phone relaying itself).
- No relay: no posting, provider gets the no-relay share. Nothing else differs.

### 6.5 Fallback: no credit, no signal, no Prok (the v0.16 path)

The customer has no credit and the seller has no Brain. Today's rule stands: Trust cap
50 CFA, seller's own number, kiosk cash, SMS receipt. It is **frozen**, not removed, so a
village without coverage still works. It is labelled in the UI as "Paiement direct au
fournisseur" and Prok takes no share of it (it never touched the money).

### 6.6 Phone reinstalled / lost

Credit is on the Brain, keyed by identity. A reinstalled phone has a new identity and
starts at zero; recovery = the customer proves the payout/top-up number (a 0-CFA
request-to-pay they confirm) and the old identity's balance is moved by `ADJUSTMENT`.
Debts follow the device pseudonym as today.

---

## 7. Prok: revenue, equipment, reconciliation

### 7.1 What Prok receives

- Its share of every session (example 25 %) → `prok_revenue`.
- Moved from float to Prok's bank/MoMo **monthly**, only the amount the ledger says is
  Prok's, only after 7.3 passes, and only by a human pressing a button that logs who.

### 7.2 Equipment recovery (fixed sites)

Each box has a landed cost recorded in the Brain (example 60,000 CFA). Sessions on that
box carry an extra **equipment share** (example 15 %, taken from the provider's side by
agreement with the host) into `prok_revenue` with `memo = box id`, until the cumulative
equipment share equals the cost. Then it stops automatically and the host's share rises.
The host sees "Remboursement du matériel : 18 %" on their Gagner screen. No hidden
deduction.

### 7.3 Reconciliation (daily, automatic; blocks payouts on failure)

1. Pull the operator statement (Collections + Disbursements) or, in the pilot, the
   business wallet balance.
2. Every `TOPUP CONFIRMED` and `PAYOUT CONFIRMED` has an operator ref that appears
   there; every operator entry has a posting.
3. Invariant from section 2 holds.
4. Report to Mike by Telegram (Owl already does this pattern): float, liabilities,
   payouts due, anything unmatched.

---

## 8. Payout engine: schedule and thresholds

| Rule | Example value | Why |
|---|---|---|
| Threshold payout | balance ≥ 2,000 CFA → pay now | People want money when it is worth having |
| Weekly run | Friday 17:00, everyone ≥ 500 CFA | Predictable; "vendredi" is a sentence |
| Minimum | 500 CFA | Below that, cash-out fees (100–150 CFA on a ≤ 5,000 withdrawal at MTN Congo per third-party tariff pages) eat it |
| Per-payout cap | 100,000 CFA | Operator daily wallet limits; large balances split across days |
| Retries | 3, backoff 30 min / 2 h / 12 h | Operator outages are hours, not days |
| Rails | MTN Disbursements, Airtel Disbursements, or one aggregator (pawaPay) for both | Section 10 |

Payout **preference** belongs to the payee (MTN or Airtel, one number). Prok never
chooses a number for anyone.

Pilot mode (before API production access exists): the engine produces the **same weekly
list** and Mike sends the transfers from Prok's business wallet by hand, then marks each
payout `CONFIRMED` with the operator reference in the Owl-style admin. That is "one list
a week", not "dozens of tiny payments" — the ledger does the work either way, and the
app is identical for the provider.

---

## 9. ⚠ Legal and partnership facts (the part code cannot fix)

| Fact | Status | Consequence |
|---|---|---|
| CEMAC Règlement **04/18/CEMAC/UMAC/COBAC** (in force 2019-01-01) defines *monnaie électronique* as stored monetary value representing a claim on the issuer and accepted as a means of payment; issuing it requires a credit institution, microfinance institution or a licensed **établissement de paiement** (SA with board, **500 M CFA** paid-up capital, segregated customer account, AML). | **Confirmed** (secondary legal source, section 10) | Prok must **not** be an e-money issuer. |
| Whether a **prepaid credit usable only for Prok's own Internet service** (non-transferable, no cash-out, refund-to-origin only, capped at 10,000 CFA) is outside that definition — the way an ISP's prepaid plan or a telco data bundle is. | **Not confirmed.** The source I read shows no explicit closed-loop exemption. | **Needs a Congolese lawyer before any real customer money.** Design keeps every property that argues "service prepayment": no transfers between customers, no cash-out, cap, credit tied to consumption. If the answer is no, fall back to **pay-per-session** (request-to-pay for each session's cost, no stored balance) — the ledger and payouts are unchanged. |
| MTN MoMo Open API exists, products **Collections / Disbursements / Remittances**, sandbox free at momodeveloper.mtn.com, and **Congo (Brazzaville) is a listed market**; MTN Congo's own page points businesses to the same portal (contacts: sosclient.cg@mtn.com, WhatsApp 067000123). | **Confirmed** | Build against the sandbox now. |
| Go-live requires **KYC documents proving the business exists**, per country; contracts follow. Whether a French company without a Congolese entity can go live in Congo. | **Not confirmed.** Assume **a Congolese-registered Prok entity (RCCM) and local bank account are required.** | Start the company registration in parallel with v0.18 — it is the long pole. |
| MTN Congo merchant collection fee and API disbursement fee. | **Not published.** Public consumer tariffs (third-party pages): withdrawal 100–150 CFA on ≤ 5,000; P2P 50–100 CFA on ≤ 5,000. | Every percentage in this document is an example until MTN Congo quotes. |
| Airtel Money: Airtel Africa developer portal offers **Collection and Disbursement APIs**; Congo-B has its own hosts (`openapiuat.airtel.cg` / `openapi.airtel.cg`) referenced by third-party client libraries. | **Partly confirmed** (portal blocked automated fetch; hosts seen in client code, not on Airtel's site) | Ask Airtel Congo business team directly. |
| Aggregator **pawaPay** lists Congo-Brazzaville among its 23 markets, with deposits, refunds and payouts. | **Confirmed on their site**; operator coverage per country and onboarding not shown. | One integration for both rails; likely the fastest route to production if they accept the Prok entity. Costs unknown. |
| Provider data cost ≈ 1,000–1,200 CFA/GB (MTN 10 GB / 12,000; Airtel 12 GB / 12,000; third-party comparison pages). | Indicative | Pricing floor check in 6.2.5. |
| Starlink in Congo-Brazzaville. | **No licence** as of the sources; ARPCE seized an unlicensed kit (2024). | Fixed sites use fibre or 4G. |

---

## 10. Sources

- MTN MoMo developer portal — https://momodeveloper.mtn.com/ ; Go-Live — https://momodeveloper.mtn.com/golive ; community "Production configuration" — https://momodevelopercommunity.mtn.com/how-to-59/momo-api-production-configuration-101
- MTN Congo Open API page — https://www.mtn.cg/momo/momo-entreprise/open-api/
- MTN MoMo pricing / markets page — https://momo.mtn.com/pricing/
- Ericsson case study listing the 11 API markets incl. Congo — https://www.ericsson.com/en/cases/2023/mtn-mobile-money-open-apis
- Airtel Africa developer portal — https://developers.airtel.africa/ ; Disbursement API v2 — https://developers.airtel.africa/documentation/disbursement-apis/2.0 (both returned 403 to automated fetch)
- Airtel Congo B hosts, via client library — https://github.com/lepresk/momo-api and https://lobehub.com/skills/africandigitalassetframework-africa-stack-skills-airtel-money
- pawaPay — https://www.pawapay.io/
- Règlement 04/18/CEMAC/UMAC/COBAC, summary — https://cesttoutdroit.com/article-droit-bancaire-et-financier/nouveau-reglement-cemac-relatif-aux-services-de-paiement/ ; text — https://www.beac.int/wp-content/uploads/2019/07/REGLEMENT-N-04-18-CEMAC-UMAC-COBAC-du-21-d%C3%A9cembre-2018.pdf
- MTN MoMo Congo consumer tariffs (third party) — https://blog.iambeezy.app/fr/frais-mtn-momo-congo-2026-tarifs-complets/
- Data bundle prices (third party) — https://blog.iambeezy.app/fr/forfaits-internet-mtn-airtel-congo-2026-comparatif-plans/
- openNDS docs — https://opennds.readthedocs.io/en/stable/ ; BinAuth arguments — https://opennds.readthedocs.io/en/stable/binauth.html
- Starlink / ARPCE — https://www.agenceecofin.com/regulation/2506-119755-congo-l-arpce-demantele-une-installation-starlink-non-autorisee-dans-une-exploitation-forestiere ; https://afrique.le360.ma/economie/internet-haut-debit-en-seulement-4-ans-starlink-operationnel-dans-un-pays-africain-sur-deux_UYXWC5RPWNGGFIYLU5VHL6L7ZU/

---

## 11. The first version to build and test (v0.18, in order)

Each step ships alone, tests first, and is useful even if the next never happens.

1. **Ledger** — migration 5, postings, invariant, `GET /v1/wallet` extended with
   `credit`, `earned_pending`, `next_payout`. Server tests: double-entry, idempotent
   session posting, upgrade from a real schema-4 DB. *No phone change yet.*
2. **Session → ledger** — `POST /v1/settlements` posts on verify; `relayId` in the
   contract; failed-session rule (6.1.3). Fixture: the same evidence bytes post the same
   centimes in Python and are displayed identically in Kotlin.
3. **Spending authorisation** — key ceremony (OPERATIONS.md), `SpendAuth` pure object,
   seller-side check, cross-language fixture, double-spend test that pays both sellers
   and blocks the customer.
4. **Top-up, sandbox** — MTN Collections request-to-pay against the sandbox; webhook +
   status polling; `TOPUP` posting only on confirmation. Airtel second, or pawaPay for
   both. **Recharger** screen. TESTING section: a sandbox top-up appears as credit on a
   real phone.
5. **Payout engine, pilot mode** — weekly list + manual confirmation (section 8), then
   the Disbursements sandbox adapter behind the same state machine. **Gagner** shows
   "Prochain versement". Verified payout number.
6. **Reconciliation + Telegram report** — daily invariant, unmatched refs, payouts
   paused on failure.
7. **v0.19 spike, one router** — openNDS FAS endpoint on the Brain; one box at one site;
   the BinAuth byte report posts a session. Only after 1–6 are hardware-proven.

**Not in v0.18:** operator production credentials (needs the entity + KYC, section 9),
multi-currency, any customer-to-customer transfer, any "Solde" that can be cashed out.

**What must be true before a real customer pays real money:** lawyer's answer on the
prepaid credit question; a Congolese Prok entity with an operator business wallet;
sandbox flows hardware-proven on both phones; reconciliation report running for a week
with zero unmatched entries.
