# ProkNet v0.18.2 — handoff

Date: 2026-09-26. From Claude (implementation) to Mike (product owner) and ChatGPT
(architect). One document: what you have, what was verified and by whom, the exact
configuration still needed, and the blockers. Nothing here is called "passed" unless it
happened; every phone line below says **NOT RUN** until Mike reports it.

## 1. What you have

| | |
|---|---|
| **APK** | `ProkNet-v0.18.2-build82.apk`, SHA-256 `744b2d150c74b87c2a5ac898bd57361a7343581d81974653602721ded3f4cd98`. GitHub release `v0.18.2`, also sent in the conversation. Installs over 80/81. |
| **Commit** | tag `v0.18.2` on `main` (the hash is in the release notes and the conversation). |
| **Brain** | `0.18.2`, schema **6**, deployed at `https://proknet.duckdns.org` (Caddy → `127.0.0.1:8081`). `/health` says so. |
| **Migration** | 5 → 6 (one column) was applied with v0.18.1; v0.18.2 adds no schema change. |
| **Backups** | Pre-migration: `C:\ProkNetBrain\backups\brain-20260926-081429.db` (schema 5). Latest: `C:\ProkNetBrainackupsrain-20260926-094624.db` (schema 6). |
| **Rollback drill** | run on the pilot Brain 2026-09-26 09:46 UTC+2: fresh backup brain-20260926-094624.db (schema 6) -> stop.ps1 -> restore.ps1 -From that backup (integrity check ok; the current file kept as brain-replaced-20260926-094629.db) -> start.ps1 -> status.ps1 and public /health on 0.18.2 / schema 6. restore.ps1 also refuses a missing file and refuses to run while the Brain is up. The first attempt of the drill found that the script could not be invoked at all (its -Db parameter collided with PowerShell's -Debug alias); fixed to -Database. That is what a drill is for. |
| **Tests** | Server **437** (all pass; run independently by ChatGPT on the 428 of v0.18.1). Android **772** (all pass). |
| **Phone tests** | **NONE RUN** — see section 4. |

## 2. What v0.18.2 changed (the two review gaps)

1. **Pilot allowlist**, `PROK_PILOT_IDS`, enforced on the server. With
   `PROK_PAYMENTS_LIVE=1`, only listed identities are shown a treasury number and only
   they are credited from an observed message. An unlisted identity's valid message is
   held for review (`not_in_pilot`) in the suspense account; a treasurer cannot credit it
   until the identity is listed. Tested from the API, including a valid SMS for an
   unlisted identity and a number bound while listed then unlisted.
2. **No approval without today's balance.** A withdrawal or refund is approved only
   against a balance typed for that rail within 24 h that is not below the ledger.
   Missing, stale, low, matching, the other rail and the one-day boundary are tested.
   The treasury screen names the reason.

Everything from v0.18.1 stands (contract v3, relay in the signature, hold required,
kiosk route off, refunds, reversals, identity recovery, reconciliation).

## 3. Exact configuration still needed (machine environment variables on the VPS, then `stop.ps1` / `start.ps1`)

| Variable | Set to | Status |
|---|---|---|
| `PROK_BRAIN_PORT` | `8081` | set |
| `PROK_TREASURY_IDS` | full node id of the treasury phone | **not set** — Mike decides which phone |
| `PROK_TEST_IDS` | full node ids of the pilot phones (the Brain has seen `24e480e608179779229aa30d80f5f697` and `0f7d57b3057d068e57a55e1983e146c9`) | **not set** |
| `PROK_PILOT_IDS` | full node ids of the first real users (and the test phones) | **not set** — nobody can move real money until it is |
| `PROK_TREASURY_MSISDN_MTN` / `_AIRTEL` | the treasury wallet numbers | **not set** |
| `PROK_PAYMENTS_LIVE` | **leave unset** until section 5 is cleared; then `1`, by Mike | unset |

Find a phone's full id: Lab → COPY NETWORK → `me:` line (the short form is the first 8
characters of the full id; the full id is in the diagnostic text). Before T80–T83 only
`PROK_TREASURY_IDS` and `PROK_TEST_IDS` are needed.

## 4. Real-device acceptance — status per line

Every line: **NOT RUN.** Claude has no phone, SIM or ADB on the VPS; these are Mike's to
run, in one session, and to report as PASS / FAIL (what was seen) / could not be tested
(why). The procedures are in `docs/TESTING.md` sections 80 and 81.

| Line | What | Status |
|---|---|---|
| T80 | a session becomes earnings, to the centime | NOT RUN |
| T81 | hold survives a long session and a dead seller | NOT RUN |
| T82 | withdrawal, Retirer → Payé, honest queue line | NOT RUN |
| T83 | a withdrawal cannot be sent twice | NOT RUN |
| T85 | **twenty real MTN Congo + Airtel Congo messages**, both directions, texts into the report | NOT RUN — the parser has read only synthetic text; **nothing about real money proceeds before this** |
| T84 | a customer top-up (live switch, own numbers, after T85) | NOT RUN — blocked by section 5 |
| 81a | direct paid session | NOT RUN |
| 81b | relayed paid session (3 phones), relay paid only after settlement | NOT RUN |
| 81c | long session, 40+ min | NOT RUN |
| 81d | seller crash mid-session, hold STALE then CONSUMED | NOT RUN |
| 81e | Brain outage: paid refused with the sentence, free works | NOT RUN |
| 81f | short session settles on the signed final checkpoint | NOT RUN |
| 81g | zero-byte session settles at 0 and releases the hold | NOT RUN |
| 81h | duplicate and late evidence post nothing twice | NOT RUN |
| 81i | withdrawal (= T82) | NOT RUN |
| 81j | refund to the origin number only (live switch, own numbers) | NOT RUN — blocked by section 5 |
| 81k | top-up guards (live switch, own numbers, after T85) | NOT RUN — blocked by section 5 |
| 81l | missing SMS → amber → confirm by reference | NOT RUN |
| 81m | reinstall → balances move, never duplicate | NOT RUN |
| 81n | duplicate-send guard (= T83) | NOT RUN |
| 81o | direct pay is off everywhere on a normal phone | NOT RUN |
| 81p | the open v0.17 acceptance (75–79) | NOT RUN |

What Claude did verify without a phone: the server suite over a real socket, the Android
JVM suite, the migration on a real schema-5 database, and the rollback drill on the pilot
Brain. Those are not phone results and are not counted as any.

## 5. Blockers before real money (all external to the code)

1. **Section 4**, at least T80–T83, 81a–81h, 81l–81p, run and reported.
2. **T85** with real operator messages; any that the parser cannot read become the
   corpus and a fix, then a rebuild — before any customer sends money.
3. **Written legal answers** to `docs/PAYMENTS_V018_DESIGN.md` Appendix B items 1–6
   (CEMAC 04/18 on Prok credit and on collecting for providers; ARPCE on reselling
   Internet; which entity signs).
4. **Operator wallet terms** from MTN Congo and Airtel Congo: personal-tier limits of the
   treasury wallets; whether a business wallet needs a registered entity.
5. Only then: `PROK_PILOT_IDS` with the first real users, `PROK_TREASURY_MSISDN_*`, and
   `PROK_PAYMENTS_LIVE=1` — set by Mike, own numbers first.

## 6. Walkthrough (unchanged from v0.18.1)

**Customer.** Home shows "Crédit Internet". "Recharger" says "(bientôt)" until live AND
listed. Ask for Internet as always; no credit → told before any Bluetooth. Long press the
credit line: "J'ai déjà payé" / "Me rembourser".
**Provider.** Gagner → Retirer → "Retrait demandé" → "Envoi en cours" → "Payé".
**Relay.** Nothing to do; Gagner rises when a relayed session settles.
**Treasurer.** Lab → TRÉSORERIE. First line = manual sends. **Type both wallet balances
every day before approving anything** (the Brain refuses otherwise). Approuver → send by
hand → Marquer envoyé (once) → Payé from the operator's message or by reference.
"Messages à vérifier": one tap each. Long press *Crédit test*: transfer an identity.
**Mike.** Sets the variables, runs section 4, owns the live switch.

## 7. What this release is NOT

Not tested on a phone. Not ready for public real-money use. The manual queue reduces
admin work; fifty withdrawals are fifty manual sends. Expansion waits for a bulk payout
rail Mike selects separately.
