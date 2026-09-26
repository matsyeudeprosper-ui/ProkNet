# ProkNet operator runbook (v0.19)

Short, for the people Mike names (contract §10.4): map/venue verifier, market moderator
and support, treasury reviewer + second approver, incident lead, technical on-call. One
person may hold several roles in the pilot; the audit rows say which role acted. Every
action below is a signed request the Brain audits and may refuse.

## Roles are environment variables on the Brain (machine level, then `stop.ps1` / `start.ps1`)

| Variable | Who | Gives |
|---|---|---|
| `PROK_OPERATOR_IDS` | verifier, moderator, incident lead | the console: switches, cohorts, decision records, venue verification, market moderation, jobs |
| `PROK_TREASURY_IDS` | treasury reviewer(s) — two different ids for exceptions | the treasury queue, invoice confirmation, balance checks, reversals, identity moves |
| `PROK_PILOT_IDS` | the first real users for real money | may be shown a treasury number and be credited |
| `PROK_TEST_IDS` | test phones | may receive audited test credit |
| `PROK_TREASURY_MSISDN_MTN` / `_AIRTEL` | — | the wallet numbers customers and sellers are told |
| `PROK_PAYMENTS_LIVE` | Mike only | `1` lets an observed top-up credit a PILOT identity |
| `PROK_BRAIN_PORT` | — | `8081` on the pilot VPS |

Find a phone's full id: Lab → COPY NETWORK → `me:` line.

## Daily (10 minutes)

1. **Treasury** → *Solde du jour*: type both wallet balances. Without today's balance on a
   rail, nothing on that rail is approved (the Brain refuses). Read the reconciliation line;
   a DOUTE means the wallet holds less than the ledger expects — stop and find out why
   before approving anything.
2. **Treasury** → queue: approve, send by hand, *Marquer envoyé* once, watch for *Payé*.
   "N retraits en attente = N envois manuels" is the truth; plan the time.
3. **Treasury** → *Messages à vérifier*: credit or ignore; a claim needs number + amount
   + reference; a rebind moves an old identity's balances (never duplicates).
4. **Market moderation** queue: decide every listing with a cleared fee within one staffed
   business day; rejected listings restore the slot or open a refund (second approver).
5. **Invoice confirmation**: match reference + amount + operator txn id from the operator's
   own message or statement; one txn id pays one invoice; a screenshot is evidence for
   investigation, never for activation.
6. **Venue verification**: review claims, reports and the second-check queue; hide on a
   safety report immediately; publish only with consent recorded, hours, access rule and
   two independent successful checks.
7. Glance at `/health` and `status.ps1`; weekly `backup.ps1`.

## Switching (the console, Lab → OPÉRATEUR)

- Every switch is a dated row with a reason. Restricted functions (`customer_paid_delivery`,
  `provider_payout`, `relay_payout`, `sponsored_delivery`, `market_paid_publish`) refuse to
  open until the decision records they need exist (`payment_classification`,
  `upstream_permission`, `merchant_collection_terms`). Record a decision with the document
  name/version, the accountable person and the date.
- **Closing a function** stops NEW sessions, listings, offers or invoices at once; accepted
  obligations (a running session, a paid listing, a reserved relay block, an approved
  withdrawal) are completed under their agreed terms. The audit trail stays.
- Cohort functions also need the identity in the cohort list.

## Incidents

| Symptom | Do |
|---|---|
| Brain down | `status.ps1`; `start.ps1`; if the database is suspect, `restore.ps1 -From <last backup>` then `start.ps1`; phones keep free sessions and the cached map working meanwhile |
| Money loss / fraud suspected | close `customer_paid_delivery`, `provider_payout`, `relay_payout` with a reason; treasury stops sending; reversals need a memo and a second approver for refunds |
| A private location or password exposed | hide the venue (owner removal / safety report), record the report, notify Mike same day |
| Wrong recipient paid | *Non envoyé* is not for this (the money left); record a reversal with memo, open a recovery ticket, never re-send |
| Parser mismatch (typed balance below ledger) | approvals are blocked automatically; compare the operator statement to `unassigned:topups` and the FEE lines |

Support target: one business day for ordinary tickets, same day for safety, money loss,
private-location exposure and active fraud. Publish the support channel and hours in-app.

## Data

Location trails, radio identifiers, chat and identity evidence are minimised (hashed
identifiers, coarse cells, 500-character messages) and swept by the Brain's cleanup on
the retention schedule counsel sets; a deletion request is handled by an operator with
an audit row. Backups rotate at 30; a restore is reversible (the replaced file is kept).
