# ProkNet v0.18.1 — handoff

Date: 2026-09-26. From Claude (implementation) to Mike (product owner) and ChatGPT
(architect). One document: what you have, what to do, what still stands in the way.

## 1. What you have

| | |
|---|---|
| **APK** | `ProkNet-v0.18.1-build81.apk` — GitHub release `v0.18.1` and sent in the conversation. Install over build 80 (higher version code, no wipe). |
| **Server** | Brain `0.18.1`, schema **6**, deployed on the pilot VPS at `https://proknet.duckdns.org` (Caddy → `127.0.0.1:8081`). `/health` says so. |
| **Migration** | 5 → 6: one added column. Applied by the Brain on start. Backup taken before: see the file name in section 3. Rollback: `deploy/brain/restore.ps1` (docs/OPERATIONS.md). |
| **Tests** | Server: see the numbers in section 3. Android: see the numbers in section 3. Fixtures the two sides share: `charging_v3.json`, `withdrawal_states.txt`, `msisdn_hash.txt`, `brain_answer_reasons.txt`, `crosslang.json`. |
| **Phone tests** | **None run.** Everything in TESTING 80 and 81 is on Mike. Nothing is "passed" until it happened on a phone. |

## 2. What the product does now (the fixed rules, as built)

- No MTN / Airtel API, sandbox or credential anywhere.
- A customer's credit lives on the Brain. Real top-ups are **off** (`PROK_PAYMENTS_LIVE`
  unset): an observed operator message is recorded and credits nobody; the app shows
  no treasury number and says top-ups are not open. Test credit on listed test
  identities exercises everything else.
- A **paid session needs a confirmed hold**. Brain unreachable, hold refused, no credit:
  the paid session is refused with a sentence; free sessions work without any Brain.
  The old "admit on the local trust rule" is gone.
- Charging is **one rule on three sides** (contract v3): what came down from the Internet
  is what is paid for, from the first byte; nothing down, nothing owed; short and
  seller-stopped sessions settle on the last checkpoint both signed.
- **Relays are in the signed contract** and are paid 10 % (example) of a relayed
  session's gross when it settles - never before.
- Providers and relays **withdraw when they choose**, above 500 CFA (example). Customers
  can ask a **refund** of unspent credit to a number they topped up from. Both land in
  the treasurer's queue, whose first line is **"N retraits en attente = N envois
  manuels"**. A person sends each one; the app records Approuvé → Envoi en cours → Payé,
  from the operator's own "vous avez envoyé" message or the treasurer's typed reference.
- **The kiosk / direct-to-seller route is off.** No seller number reaches a buyer; no
  "Payer" button; a developer long-press on the Lab screen is the only way to see it.
- **Reconciliation per rail**: the treasurer types each wallet's balance daily; a typed
  balance below what the ledger expects is doubt about a parsed message and blocks new
  approvals until a check matches. Every treasury action and every refusal is audited.
- **Reinstall / lost phone**: balances move (never duplicate) to the new identity, by a
  tagged top-up from the old number that a treasurer confirms, or by hand.

## 3. Numbers and evidence

Filled in at release time (see the CLAUDE_REPORT v0.18.1 section for the same figures):

- Server suite: **428** tests, all passing.
- Android suite: **770** tests, all passing.
- APK SHA-256: `fae6877f9cc00f72133427e12d74474f6ffabf56afb2f43cbd7f2065f7e21ebf`.
- Pre-migration backup: `C:\ProkNetBrainackupsrain-20260926-081429.db (schema 5, taken before migration 6)`.
- Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.18.1

## 4. Walkthrough (what each person does)

**Customer.** Home shows "Crédit Internet : N F". Tap **Recharger** (says "bientôt"
until Mike opens payments). Ask for Internet as always; if credit is short the app says
so before any Bluetooth. Long press the credit line for "J'ai déjà payé" (number +
exact amount + operator reference, a treasurer decides) or "Me rembourser".

**Provider.** Gagner shows "Gagné sur le réseau Prok" and "Retirable". **Retirer** →
amount + number + operator → "Retrait demandé" → "Envoi en cours" → "Payé". One open
request at a time; cancel while it is still "demandé".

**Relay.** Nothing to do; Gagner rises when a relayed session settles.

**Treasurer** (the phone whose identity is in `PROK_TREASURY_IDS`). Lab → TRÉSORERIE.
Read the first line. Per row: Approuver → *send the transfer by hand* → Marquer envoyé
(once) → it turns Payé from the operator's message, or Confirmer payé with the
reference. Daily: **Solde du jour** on both rails. "Messages à vérifier": credit or
ignore, one tap each. Long press *Crédit test*: transfer an identity.

**Mike.** Sets the four environment variables (docs/OPERATIONS.md), decides who is
treasury and which phones are test identities, runs TESTING 80 and 81, and is the only
one who ever sets `PROK_PAYMENTS_LIVE=1`.

## 5. Unavoidable human actions, all together

1. **Name the phones.** `PROK_TREASURY_IDS` = the treasury phone's full node id;
   `PROK_TEST_IDS` = the pilot phones. Machine environment variables, then
   `stop.ps1` / `start.ps1`. The Brain has seen `24e480e6…` and `0f7d57b3…`.
2. **Run TESTING 80 and 81** on the phones (three phones for the relay line), report
   PASS / FAIL / could not be tested per line. Nothing is proven until then.
3. **T85: twenty real operator messages** on the treasury phone, both operators, both
   directions, texts copied into the report. The parser has only seen synthetic text.
4. **Legal**: written answers to `docs/PAYMENTS_V018_DESIGN.md` Appendix B items 1–6
   (CEMAC 04/18 status of Prok credit and of collecting for providers; ARPCE on
   reselling Internet; which entity signs anything).
5. **Operator wallet terms**: the personal-tier limits of the treasury wallets, and
   whether a business wallet needs a registered entity - from MTN Congo and Airtel
   Congo directly.
6. **The live switch**: only after 2–5, Mike sets `PROK_PAYMENTS_LIVE=1` and
   `PROK_TREASURY_MSISDN_MTN/AIRTEL`, and only for the pilot's own numbers first.

## 6. What this release is NOT

Not ready for public real-money use: legal approval, operator wallet terms and
reliable message verification on real texts are unresolved (items 3–5 above). The
manual queue reduces admin work; it does not automate sending. Fifty withdrawals are
fifty manual sends, and the screen says so. Expansion waits for a bulk payout rail Mike
selects separately; the queue, states and audit are built to take one.
