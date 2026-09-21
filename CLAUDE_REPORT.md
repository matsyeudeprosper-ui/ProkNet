# CLAUDE_REPORT - ProkNet v0.15.1 "Wallet polish + settlement trust"

Date: 2026-09-20
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 379/379 Android tests and 55/55 server tests pass, released
as build 59. The v0.15.0 seller-stop gate is now HARDWARE PROVEN on the
OUKITEL + OnePlus pair. Wallet hardware acceptance is TESTING 63 and has not
been run.**

| | |
|---|---|
| `f8496fc` | security: signed settlement evidence verification |
| `42c6629` | payments: PaymentTransaction and allocations |
| `014fc73` | ui: professional Prok Wallet redesign |

Recorded as proven for this device pair: short seller-ended sessions settle
non-zero, both phones agree on the figure, and reconnect works. That closes
the gate I would not build the money layer on top of.

---

# PART A — settlement trust

## 1. The server derives the money itself

This was the gap I flagged as the largest in v0.15.0, and you were right to
make it the condition. `/v1/settlements` believed the amounts a phone sent. A
phone could invent a session, recompute a matching settlement id from its own
invented fields, and create debt.

A phone no longer reports amounts at all. It submits **evidence**: the exact
signed bytes of the contract and the closing checkpoint, plus the four
signatures over them. `brain/evidence.py` re-derives everything and refuses
what it cannot verify, in this order:

1. the contract is a valid, paid, v2 budget session;
2. the two public keys are the parties the contract names;
3. both parties signed **those exact** contract bytes;
4. the checkpoint belongs to that session and is the closing one;
5. both parties signed **those exact** checkpoint bytes;
6. the signed cost is what the terms give, and fits the signed budget and the
   signed byte ceiling;
7. the submitter is the buyer or the seller, and is the same identity that
   signed the HTTP request;
8. gross, Prok fee, seller net and the settlement id are computed **here**.

A claimed amount or id is accepted only as a cross-check, and a disagreement
is **refused rather than corrected**. A sender that is broken or lying does
not get a row.

The binary layouts mirror `Market.Contract` and `Market.Checkpoint` exactly.
Writing that decoder found a real bug in my own assumption: contract v2 is
**88 bytes**, not the 84 I had written in the v0.14.1 notes. The Kotlin
constant was always right; my arithmetic in the report was not. The tests pin
the real number now.

## 2. Signed requests and replay

`brain/signed_request.py`. Every submission carries identity, timestamp,
nonce and a signature over the body hash, domain-separated. Refused: a bad
signature, a timestamp more than five minutes out, an altered body, a reused
nonce. Nonces are remembered for twice the skew window, so a replay cannot
slip through after its nonce is forgotten but while its timestamp is still
valid — that ordering is tested explicitly.

A failed signature does **not** burn the nonce. Otherwise an attacker could
lock out a legitimate request by guessing its nonce, which would be a denial
of service built into the defence.

Transport HTTPS and evidence are treated as different things, as you asked.
The webhook route is exempt from Prok signing because it comes from an
operator, not a Prok identity; it is authenticated by the rail's own secret.

## 3. PaymentTransaction and allocations

You were right that the simple fix was the wrong one. The old event key was
`rail | reference | settlementId`, which let one operator reference be
presented independently against unrelated obligations. But netting tiny
sessions into one transfer is deliberate, so a unique reference per obligation
would have broken the feature to fix the bug.

The unit of payment is now the transfer:

```
payment_transactions   one real operator transfer, UNIQUE(rail, operator_ref)
payment_allocations    how much of it settles which obligation, PK(payment, settlement)
```

Three 5 CFA sessions become one 15 CFA payment with three allocations.

**Invariants, all enforced in `open_payment`/`confirm_payment` and tested:**

- `sum(allocations) <= amount transferred`;
- `allocation <= what that obligation still owes`, where "still owes" nets
  every confirmed allocation already made against it;
- a confirmed obligation cannot be paid again;
- every obligation in one payment belongs to the same two parties;
- one `(rail, reference)` is one transaction, enforced by a UNIQUE constraint,
  never two;
- a duplicate confirmation is a no-op;
- a confirmation for a different amount, or the same reference reused for a
  different payment, moves the transaction **and every obligation it touches**
  to `SECURITY_REVIEW`;
- a partial payment leaves the rest owing, and a second transfer clears it.

## 4. Manual payments stay honest

A typed reference still reaches `PAYMENT_SEEN` and no further. On the phone, a
reference already used for a different seller is refused with "Cette référence
est déjà utilisée." rather than silently reused.

---

# PART B — the Wallet redesign

## 5. Information architecture

Bottom navigation unchanged, as instructed. Activité gained a segmented
switch, `[ Activité ] [ Wallet ]`, defaulting to Activité.

- **Activité** answers *what happened*.
- **Wallet** answers *what money needs attention*, and contains money events
  only.

Screen order: header, one summary card, one action card, one receiving card,
history grouped by day. Not six equal boxes.

## 6. The three promises, enforced by tests

`core/WalletUi.kt` decides the whole screen, so its states are tested rather
than argued about from a screenshot.

- **One obvious action.** `primaryAction` returns exactly one thing, ordered
  by urgency: money I owe → nowhere to be paid → money owed to me → nothing
  yet → all clear. Never an empty PAY button. A debt outranks the
  set-up-receiving prompt, because money I owe is more urgent than money I
  might earn.
- **No technical clutter.** A test sweeps every string the screen can show and
  fails on a status enum, a settlement id, a checkpoint hash, a raw identity,
  a megabyte or the word "Solde". All of it lives behind one
  "Détails techniques" sheet.
- **Honest money words.** "Payé" only after verification. A typed reference
  reads "À vérifier". No guarantee, no balance.

## 7. Hierarchy and copy

The summary card leads with **À payer** when there is debt and **À recevoir**
otherwise; the other figures go muted. The Prok fee is **not** a headline
figure — it is in the transaction detail, where it belongs.

Status words: En attente, À vérifier, Payé ✓, Reçu ✓, Échoué, Expiré,
Contesté. Each carries one of three tones, so nothing needs a rainbow of
colours.

`prok-24e480e6a1b2…` shows as **Prok 24E4**. Deliberately an abstraction over
the identity rather than an invented name, since inventing one would imply a
profile we do not have. When real names arrive, one function changes.

## 8. Earn and Home

Gagner keeps its earnings figure, adds "À recevoir" and one **Voir le Wallet**
link, and does not repeat the history. Home shows at most **one** money line,
only when something is owed, and only when nothing more urgent already needs
that space — the live-session amount still wins.

## 9. Tests

379 Android JVM tests (+16) and 55 server tests (+32).

- `WalletUiTest` (16): empty, owing one seller, owing several, three netted
  sessions, seller awaiting payment, seller with no receiving method, all
  clear, disputed, failed, expired, manual reference wording, day grouping and
  direction signs, identity display, receiving states, and the clutter sweep.
- `test_evidence.py` (21): forged evidence, each of the four signatures
  individually, a signature over different bytes, mismatched keys, a borrowed
  checkpoint, a non-final checkpoint, a cost the terms do not give, usage past
  the ceiling, a stranger submitting, free and v1 sessions, tampered amounts
  and ids, plus the full replay suite.
- `test_settlement.py` (+11): one transfer settling three sessions, reference
  uniqueness, reuse as a security review, allocations over the amount, over
  what is owed, double payment, duplicate confirmation, wrong amount,
  unverified confirmation, partial payment, mixed parties.

One test failure was my own assertion, not the code: a naive megabyte check
matched "MTN **Mo**bile Money". Fixed with a word boundary.

## 10. Version / build / commit / hash

Build 59, versionName 0.15.1, verified with `aapt2 dump badging`.
SHA256 `21b2b484c72440b74c7962bb0ea1ea1631aafe8150bf40b4753f5ffb02ed2962`.
Commits `f8496fc`, `42c6629`, `014fc73` on `main`; this report on top.
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.15.1

## 11. Hardware test

**TESTING 63.** Three short paid sessions with one seller, then open Wallet.
One summary card leading with À payer, one action card naming Prok XXXX and
saying three sessions are grouped, exactly one button, history grouped under
Aujourd'hui. Then the clutter check, a mock payment of the whole batch
surviving a restart of both apps, the manual reference reading "en attente de
vérification", the duplicate-reference refusal, and the empty and all-clear
states.

## 12. Known limitations

- **The phone does not yet submit evidence.** The server can verify it and
  refuses everything else, but the Android client still stores obligations
  locally and has no code path that builds and signs an evidence submission.
  Server and phone are ready for each other; the wire between them is the next
  piece of work. Nothing is claimed to be reconciled server-side today.
- **No real Mobile Money integration.** Unchanged. MTN and Airtel remain
  interface only, with no credentials, and refuse to initiate.
- **No webhook signing secret ships**, so no webhook can confirm anything yet.
- **Allocations are computed on the phone** and sent; the server validates them
  against what each obligation still owes, but a phone could propose a silly
  split. It would be refused, not accepted.
- A buyer that vanishes before the closing ack still pays only what was signed.
  Unchanged and inherent.
- The credit limit is still per-phone local state; a reinstall starts clean.
- `Wallet.startOfDay` still assumes UTC+1.
- TESTING 55 to 60 and 62 have still never been run.
