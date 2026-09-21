# CLAUDE_REPORT - ProkNet v0.15.3 "end-to-end settlement trust"

Date: 2026-09-21
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 408/408 Android tests and 70/70 server tests pass, released
as build 61. Three gaps closed, no new features. Hardware acceptance is
TESTING 65.**

| | |
|---|---|
| `ed69f52` | android: persist and submit signed settlement evidence |
| `f8a1ba6` | server: enforce settlement and payment submitter authorization |
| `c9bd13c` | ui: Compte belongs to Activité only |

All three gaps you named were real. I checked each in the code before
changing anything.

---

## 1. Android evidence persistence

**Almost nothing had to be stored that was not already stored**, which is the
best possible answer to your second point. The `sessions` table already keeps
the contract bytes with both signatures; the `checkpoints` table already keeps
each checkpoint body with the seller signature and the buyer countersignature.
Every byte the server's verifier needs was durable from the moment the session
ended.

The only new table is `settlement_sync`: the queue state, not the evidence.
Schema version 6 to 7, migrating by creating the new table.

`core/Evidence.kt` assembles the package **from the database**, never from a
live object. That is what makes a settlement survive a restart: the session
ended days ago and every byte it signed is still on disk. Public keys come from
`identity.pubBytes` and the `peer_keys` row learned during the handshake.

It sends evidence, not results. The claimed settlement id and gross travel as
optional cross-checks the server may refuse us on, exactly as you specified.

**No unsigned fallback.** `Evidence.build` returns a `Missing` reason rather
than throwing, and there is deliberately no branch that asks the server to
trust a local amount. The reasons are ordinary: a free session owes nothing, a
session whose closing checkpoint was never countersigned has nothing anybody
may be billed for, a v1 session is out of scope. In each case no submission is
ever attempted, and the phone still shows the local obligation honestly.

## 2. Signed request

`core/SignedApi.kt` is the phone half of `signed_request.py`: identity,
timestamp, nonce, and a signature over
`ProkNet-api-1|ts|nonce|sha256(body)`.

**The body is built once and the same array is signed and written.** Your
warning about re-serialising was the right one to give: a different key order
or one extra space changes the hash. The code makes it structurally impossible
rather than merely avoiding it, and a test flips one byte of the body to prove
the verification fails.

I also ported the verifier to Kotlin so the two halves can be checked against
each other on this side of the wire, rather than discovering a mismatch on the
phones.

## 3. Sync queue, retry and offline behaviour

`node/SettlementSync.kt`. Queued on settlement by both phones independently;
drained on the existing brain-sync timer and immediately after a session, on a
background executor.

- Doubling backoff from 30 seconds to a 6 hour ceiling, capped at 12 attempts.
  No tight loop, and a phone back from a week offline does not hammer the
  server.
- **A failed submission never touches the local obligation.** The queue row
  carries the failure; the money is untouched.
- A retry is a new timestamp, a new nonce and a new signature over the **same
  deterministic settlement**, so it is a fresh request rather than a replay.
- **A duplicate is success.** The id is derived from signed facts, so "already
  reported" is exactly the outcome we wanted.
- 400 and 403 stop the retries, because the evidence itself is wrong and
  resending the same bytes cannot help. 401 (clock skew, reused nonce), 5xx and
  timeouts stay retryable.

**Offline-first is preserved.** The server is a witness, not a participant. Two
phones still find each other, agree a price, share Internet and settle with
nobody else involved. Both phones report independently and the server
reconciles; neither waits for the other.

## 4. Server: payment initiation authorization

Your reading was exact. `/v1/payments/initiate` verified the request signature
and then trusted `buyer_id` from the JSON body, so any valid Prok identity
could start a payment in somebody else's name.

- the authenticated submitter must **be** the buyer named in the payment, or
  403, and the stored buyer is now taken from the verified identity rather than
  the body;
- a payment must name its seller, and every allocation must belong to that same
  buyer and seller, so one transfer cannot pay one seller for another's work;
- `payment_destinations` records where a seller is paid per rail; a payment
  naming a different destination for a seller already on record is refused as a
  security review rather than sent somewhere new on a phone's say-so.

Request replay and payment idempotency stay separate, as you insisted: a fresh
nonce satisfies the request layer, while the same rail and operator reference
is still recognised as the same real transfer. A test asserts both at once.

## 5. Activity / Wallet / Compte

You were right, and I confirmed it in the layout before touching it: the Compte
section closed **outside** both `activityPane` and `walletPane`, so switching to
Wallet left the account, network and developer cards under the money screen.

The whole section moved inside `activityPane`. Selecting Wallet now hides it
with the history.

Two smaller things while I was there. The summary card led with nothing when
both figures were zero, leaving three equal zeros; it now leads with whatever
needs action and falls back to the informational figure. And server
verification state appears in the transaction detail under "Détails
techniques" and nowhere else — a consumer card never mentions a server, and an
obligation that has not reached one is not shown as wrong.

## 6. Tests

408 Android JVM tests (+12) and 70 server tests (+15).

- `EvidenceTest` (10): the package shape against the server's required fields,
  both roles, the cross-check, every `Missing` case, signature over exact bytes
  with a tampered-byte check, skew, retry freshness, bounded backoff, and how
  each HTTP answer is read.
- `WalletUiTest` (+2): the leading figure, and server state confined to the
  advanced sheet.
- `tests/test_api.py` (15): **driven over a real socket against the real
  handler**, so the wiring is under test and not just the pieces. Valid
  evidence accepted; both phones reporting one settlement; retry with a new
  nonce idempotent; reused nonce 401; body modified after signing 401; stranger
  400; signing identity not matching the evidence submitter 401; unsigned 401;
  and the five payment authorization cases including the impostor, the foreign
  obligation, the two-seller batch and the contradicting destination.

All 396 previous Android tests and all 55 previous server tests pass
unmodified.

## 7. Version / build / commit / hash

Build 61, versionName 0.15.3, verified with `aapt2 dump badging`.
SHA256 `375facae6f54054d090e609797fbaafde6f76b263267ef19abd7b3e7b1623862`.
Commits `ed69f52`, `f8a1ba6`, `c9bd13c` on `main`; this report on top.
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.15.3

## 8. Hardware test

**TESTING 65**, and 65b is the one that matters: run a paid session with the
server unreachable and confirm the session, the settlement and the Wallet all
work with **no error reaching the user**. Then make the server reachable and
confirm the evidence uploads and the detail sheet reads "Vérifié". Then repeat
with a force-stop before the sync. Finally check that no account, network or
Developer card appears under the Wallet.

## 9. Known limitations

- **Still no real Mobile Money.** MTN and Airtel remain unavailable and refuse
  to initiate, exactly as before. This release is trust wiring, not operator
  integration.
- **No webhook signing secret ships**, so nothing can be confirmed by webhook.
- **The server accepts a phone's proposed allocations** after validating them
  against what each obligation still owes and who the parties are. A phone can
  propose a silly split; it will be refused, not accepted.
- **The destination check only bites once a seller is on record.** The first
  payment to a new seller establishes the destination. Until operator
  integration exists there is no independent source to check the first one
  against, and I would rather say that than pretend otherwise.
- The sync queue drains on the brain timer and after a session. There is no
  connectivity-change trigger, so a phone that regains signal may wait for the
  next tick.
- `Evidence.interpret` reads the server's verdict by substring on the JSON. It
  is adequate for two known response shapes and should become a real parse if
  the response grows.
- Everything from v0.15.2 still stands, including that the Gagner hero has no
  animation.
