# CLAUDE_REPORT - ProkNet v0.16.3 "final payment hardening"

Date: 2026-09-21
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)

Version 0.16.3, build 65. **536 Android tests, 174 server tests, all passing.**

## 1. Config public key handling

A real dedicated P-256 keypair now exists. Key id `9410c707`.

Public half, committed in exactly two places and identical in both:

- `ReceiptRules.PINNED_CONFIG_KEY` (the APK)
- `CONFIG_PUBLIC_KEY` in `brain/app.py` (overridable by `PROK_CONFIG_PUBLIC_KEY`)

It is **not** the Brain's transport identity and **not** any user identity. That
separation is the whole argument: whoever takes the public server can withhold or
delay a configuration - phones survive that, the built-in rules keep working - but
can never forge one.

A test asserts the server's key is 128 hex characters, parses, and is not the test
fixture key. Another walks the entire repository looking for private-key markers and
fails if one is ever committed.

## 2. Private key storage and publishing workflow

Private key: `C:\ProkNetKeys\receipt_rules_config_key.pem` on the VPS. Outside the
repository. `.gitignore` refuses `*.pem` and friends as a second line of defence.
Full instructions, including what to do if it is ever exposed, are in the new
`docs/OPERATIONS.md`.

`brain/publish_rules.py` is the only thing in the project that touches it:

```powershell
$env:PROK_CONFIG_PRIVATE_KEY_FILE = "C:\ProkNetKeys\receipt_rules_config_key.pem"
python -m brain.publish_rules rules.json --db C:\ProkNetBrain\brain.db
```

Never an argument, so it cannot reach shell history or a process listing.
`PROK_CONFIG_PRIVATE_KEY` (the PEM itself) also works. `--dry-run` signs and prints.
`--url` posts to a running Brain instead.

The running Brain never holds the private key; a test reads `app.py` and asserts it.

**One thing I got wrong and fixed.** My first version of the tool built its rule
store from *its own* public key, so any key could write into the Brain's database and
the operator would be told it worked - while every phone silently refused the result.
It now resolves what the deployment pins and refuses before signing anything.

## 3. Cross-language config verification

`server/tests/fixtures/crosslang.json` is written by Python, committed, and read by
`CrossLanguageFixtureTest`. Both directions, with signatures captured from real runs
of each language:

- Python signs a configuration -> Kotlin accepts and activates it
- Kotlin signs one -> Python accepts it, and `RuleStore.publish` stores it
- the canonical bytes are compared as strings, not re-derived
- the exact server response shape the phone parses is pinned

**This caught a bug that would have shipped.** `ReceiptRules.canonical` emitted its
categories in declaration order; `ruleconfig.canonical` sorts them. Both sides were
self-consistent, both suites were green, and every configuration the server published
would have been refused by every phone. A finished-looking feature that could not have
worked once. Terms may no longer contain a quote or a backslash, because escaping is
where two independent canonicalisations drift apart.

## 4. Airtel through the Brain

`GET /v1/pay/destinations?seller=` returns what the seller signed, on whatever rail.
Nothing in the Brain retrieval path names an operator. The old named-rail route now
refuses to guess: no rail is a 400, never a default.

A seller has ONE place it is paid, and which operator that is can change. So versions
count **per seller**, matching `DestinationClaim.nextVersion` on the phone. Two real
bugs fell out of aligning them:

- the server counted versions per (seller, rail), so a seller moving to Airtel would
  have left a stale MTN claim looking current for ever;
- `DestinationClaim.mayReplace` refused any claim on a different rail, so a seller's
  own phone rejected the claim it had just made. A seller could not change operator.

Cooling holds across a rail change: MTN keeps receiving until the window closes, so a
transfer already on its way still lands. Tests cover MTN-only, Airtel-only, the
switch, the cooling window, an unknown rail name, a stale republish, and a stranger.

## 5. Settlement GET authorization

`GET /v1/settlements/{id}` required nothing at all. A settlement id is derived from
signed session bytes and both phones hold it, so it is not a secret - and knowing one
returned the amount, both parties, the payment state and the whole audit trail.

Now: **401** unsigned, **403** for anybody who is not the buyer or the seller, **404**
for an unknown id, said identically to a party and to a stranger so the route cannot
be used to enumerate ids. A test asserts the 403 body leaks neither amount nor either
party id.

## 6. Transaction rollback tests

`_set` and `_audit` no longer commit; the caller owns the transaction. Every money
operation wraps its whole change in one `with self.db:`.

`server/tests/test_atomicity.py` injects a failure in the middle and compares a full
snapshot of `settlements`, `settlement_audit`, `payment_transactions`,
`payment_allocations` and `payment_events` before against after:

- two allocations, failure before the second -> no transaction row, no allocation
  row, no settlement moved, no audit row, and the reference is not burned so the
  buyer can retry;
- a confirmation over two obligations, failure halfway -> payment still
  PAYMENT_INITIATED, both obligations still PAYMENT_INITIATED, and a retry succeeds;
- the same for `_flag_payment`, `record`, `expire` and `webhook`.

Each has a positive control beside it. I checked these actually bite: putting the
commit back into `_set` fails six of the eleven.

## 7. Legacy-signature rejection

`require_bound=True` is the default. Every route under `/v1/settlements`,
`/v1/wallet`, `/v1/pay/`, `/v1/payments/` and `/v1/device/` refuses a body-only
signature with 401. Asking for a bound check without supplying a target raises
`ValueError` rather than silently accepting everything - a route that forgot to pass
its target would otherwise have looked like it was checking one.

Tests: bound -> 200, body-only -> 401 on three different money routes, a signature
for one endpoint replayed at another -> 401, a GET signature tied to its query, a
reused nonce -> 401. A legacy signature is also shown to be *valid* - it is refused
for what it covers, not because it is malformed.

## 8. Canonical request target

```
ProkNet-api-1|<ts>|<nonce>|<sha256 body>|<METHOD>|<canonical target>
```

The target includes the query, because `?payment=A` and `?payment=B` ask about two
different people's money. The rule: keep the path exactly as sent, drop an empty
query, otherwise sort the raw `k=v` pieces and rejoin with `&`. **Nothing is
decoded** - `%2F` must not quietly become `/` on one side only.

One implementation each in `signed_request.canonical_target` and
`SignedApi.canonicalTarget`, pinned together by the fixture.

## 9. Cross-language signed-request fixture

Same file. A deliberately out-of-order target, a fixed timestamp and nonce, real body
bytes, the exact signing line, and signatures from both languages. Python verifies a
signature Android produced, through the real `signed_request.verify`; Kotlin verifies
Python's through the real `SignedApi.verify`. Both then check the same signature is
refused at another endpoint and with another query. Test keys only.

## 10. Totals, version, artefacts

| | |
|---|---|
| Android tests | **536** (was 514) |
| Server tests | **174** (was 112) |
| Version / build | **0.16.3 / 65** |
| APK SHA256 | `7a579ad9ca6596fb6b0ab83d0421901dfc9cd3dd134f779c3f3353c841103a53` |

## 11. Hardware status - unchanged and honest

- **Hardware-proven:** the Internet / Bluetooth L2CAP path; pricing, contracts and
  stops.
- **Software-proven only:** automatic Mobile Money payment, the Brain payment path,
  and everything in v0.16.3.
- **Nothing in the v0.16 payment work has been hardware-tested at any point.** My
  v0.16.2 report said section 67 was the last hardware-tested payment path. That was
  wrong - section 67 is written but you have not reported running it. Corrected in
  `CLAUDE_REPORT.md`.

## 12. Remaining limitations

- The parser has still never seen a real MTN or Airtel message. The corpus is
  synthetic. This is the largest remaining unknown in the payment milestone and no
  amount of code closes it.
- `PAYMENT_OPERATOR_VERIFIED` is still produced by nothing. Only a real MTN/Airtel
  API may, and there is none.
- No operator webhook secret, so no webhook can be verified and none may confirm a
  payment.
- A seller has one active receiving destination. If a real seller needs MTN and
  Airtel simultaneously, that is a product change, not a bug fix - the route already
  returns a list so it would not need a protocol change.
- Key rotation is manual and needs a new APK: the pinned key *is* the trust and there
  is no revocation list. That is the cost of not letting a server rewrite how phones
  recognise money.
- `/v1/sync` still uses its own signing path (`protocol.parse_upload`), untouched by
  any of this. It is not a money route.

## 13. Definition of done

No known architectural hold-up remains in the payment/trust milestone. Parser updates
are genuinely usable without an APK release, both operators work through the Brain,
private settlement data is authorised, financial database changes are atomic, and
every money endpoint requires a method/path-bound replay-resistant signature.

Payment work stops here. Next is v0.17 when you say so.

---

# CLAUDE_REPORT - ProkNet v0.16.2 "the payment loop, through the Brain"

Date: 2026-09-21
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)

Version 0.16.2, build 64. 514 Android tests, 112 server tests, all passing.
APK SHA256 in the release notes.

## What was asked, and what is now true

**1. The payment path works through the Brain.** The four signed payment
objects now also travel through the server when the phones are apart. A buyer
can learn where to pay, leave an expectation, get an answer, and receive the
receipt without ever meeting the seller again. New: `server/brain/paybox.py`,
`app/.../node/PaymentSync.kt`, `app/.../core/BrainPayload.kt`.

**2. Reinstall debt is server-backed.** `POST /v1/device/risk` takes a
domain-separated pseudonym and returns what that PHONE owes, derived from the
server's own verified settlements across every identity it has seen on that
phone. The phone never asserts its own innocence. The admission gate in
`ProkNetNode` now reads that number instead of a local flag, and the local flag
is gone.

**3. Parser wording can be updated as signed data.** `core/ReceiptRules.kt` and
`server/brain/ruleconfig.py`: a pinned dedicated key, word lists only, hard
bounds, and a downgrade check. `PINNED_CONFIG_KEY` is empty in this build, so
**no remote configuration is accepted yet** - the built-in rules are the only
rules until there is a real key ceremony.

## Security items

- **Durable nonces.** `Nonces` is SQLite-backed with a unique constraint.
  Restarting the server used to undo replay protection for every request in the
  skew window.
- **Signatures bound to method and path.** `ProkNet-api-1|ts|nonce|hash|METHOD|path`.
  Old-style signatures still verify, so v0.16.1 phones keep working.
- **Private endpoints authorised.** `/v1/wallet` needs a signed request and
  returns only your own. `/v1/pay/*` needs a signed request; a seller sees only
  its own inbox, a buyer only its own receipts, and the seller's Mobile Money
  number is readable only by somebody who actually owes that seller.
- **Webhook DoS fixed.** An unverified webhook used to push a payment and every
  obligation it touched into SECURITY_REVIEW, so anybody who could reach the
  endpoint could freeze a real seller's money. It is now audited and ignored.
- **Atomic writes.** Storing a receipt and closing its window are one
  transaction or neither.

## Two things I found while checking my own work

- The far phone could not verify anything the Brain carried: it had no way to
  get the other phone's public key. Fixed by carrying the key with the object,
  which is safe only because a node id IS the hash of the key - the phone
  re-derives it and refuses a key that does not match (`BrainPayload.pubFor`).
  Without this the whole Brain path was fail-closed and useless.
- A reinstalled phone blocked by server-backed debt would have been told
  "Réglez 0 F pour continuer", because its LOCAL debt is zero. The refusal now
  shows the larger of the two.

## What is proven and what is not

- **Software-proven:** everything above, by 514 Android and 112 server tests,
  including the whole Brain loop end to end and the endpoint authorisation over
  real HTTP.
- **Hardware-proven:** nothing in the v0.16 payment work, at any point. Section 67
  is written but Mike has not reported running it, so v0.16.1 is not hardware-tested
  either. (Corrected in v0.16.3: an earlier version of this line said it was.)
- **Not built, deliberately:** `PAYMENT_OPERATOR_VERIFIED`. No code produces it.
  Only an MTN/Airtel API may, and there is none.
- **Needs a decision from you:** the parser-rule signing key. Until there is a
  key ceremony, remote rules are off and the feature is inert by design.

## To test on hardware

`docs/TESTING.md` section 68. It needs the Brain actually running and reachable
from both phones; 68d re-runs section 67 with the Brain OFF to prove the local
path did not regress.


---

# CLAUDE_REPORT - ProkNet v0.16.1 "the payment loop, wired"

Date: 2026-09-21
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 476/476 Android tests and 70/70 server tests pass, released
as build 63. The loop is wired end to end over the local path. Hardware
acceptance is TESTING 67 and has NOT been run, so nothing here is claimed as
hardware-proven.**

| | |
|---|---|
| `c8acdba` | payments: wire the whole loop between two phones |
| `5bb03fa` | privacy: capture only while expecting; buyer-only trust counting |

You were right that v0.16.0 stopped at models. Each object stayed on whichever
phone created it, so the flow could not complete. Below is where each one now
actually goes.

## 1. Exact destination route

```
seller sets number  →  claimDestination() signs it  →  saved locally
                    →  Gateway.onDebtorLearned fires the moment a debt is booked
                    →  PayWire DESTINATION_CLAIM over the authenticated control channel
                    →  buyer verifies deriveId(sellerPub) == claim.sellerId,
                       the signature, and version > stored
                    →  buyer stores it and may now offer PAYER
```

Pushed while the link is still up, because the buyer walks away shortly after.
Also re-pushed by `publishDestination()` whenever a debtor comes back in range.
The buyer can never alter the number, rail, seller or version: all four are
inside the signature, and tests prove each substitution fails.

## 2. Exact expectation route

```
buyer taps PAYER  →  beginPayment() builds and SIGNS the expectation
                  →  PayWire EXPECTATION to the seller
                  →  seller verifies the buyer signed it, that it names this
                     seller, that the destination hash is the ACTIVE one, that
                     the window is sane, and that the amount equals what those
                     sessions still owe IN THE SELLER'S OWN RECORDS
                  →  ACCEPTED, or BUSY_SAME_AMOUNT / BAD_AMOUNT / ...
                  →  PayWire EXPECTATION_REPLY back to the buyer
                  →  only then does the buyer's screen say it is ready
```

The amount rule is stricter than the spec asked for, and one of my own tests
caught it: every named session must be known **and still outstanding**. A list
padded with an already-settled session was being accepted because the sum still
matched. Padding is exactly how a receipt would later reach a debt the payment
never covered, so it is now refused.

## 3. Exact receipt route

```
operator SMS arrives  →  privacy gate: is a payment expected?
                      →  default-SMS check  →  parser  →  exactly one ACCEPTED
                         expectation fits
                      →  seller signs a DevicePaymentReceipt
                      →  PERSISTED FIRST, then delivery attempted
                      →  PayWire RECEIPT to the buyer
                      →  buyer verifies seller signature, that it is for this
                         buyer, this payment, this amount, this destination,
                         and that every settlement it names was in the buyer's
                         own expectation
                      →  obligations CONFIRMED, Wallet shows Payé ✓
                      →  buyer sends EXPECTATION_END so the seller frees the
                         amount immediately rather than after 20 minutes
```

If the buyer is out of range the receipt stays in an undelivered queue,
restored from disk on start and retried whenever a peer reappears.

## 4. Local path and Brain path

**Local: complete.** All five messages ride `Wire.OP_PAYMENT` on the existing
authenticated, signature-verified control channel. Nothing in the proven
transport stack changed beyond adding one op.

**Brain: not built in this release, and I am not going to claim otherwise.**
The encoding is a single self-describing line precisely so the same bytes can
go over HTTP or be carried by a third phone without a second protocol, but the
server endpoints and the sync hooks are not written. The consequence is stated
plainly in the limitations: the separated-phones case works when the phones
meet again, not while they are apart.

## 5. Signature verification at every hop

| Message | Signed by | Receiver checks |
|---|---|---|
| DESTINATION_CLAIM | seller | id derives from the key, signature, version is newer |
| EXPECTATION | buyer | id derives from the key, signature, names me, active destination, sane window, amount equals my own records |
| EXPECTATION_REPLY | — | advisory only; it can never move money |
| RECEIPT | seller | id derives from the key, signature, my payment, my amount, my destination, only my settlements |
| EXPECTATION_END | buyer | id derives from the key, signature |

Domain separators differ per type, and a test proves a signature made for one
message cannot be replayed as another.

## 6. Offline and restart behaviour

Expectations and receipts are persisted, and the undelivered queue is rebuilt
on start. The seller signs and persists before attempting delivery, so a
receipt is never lost because the buyer happened to be out of range at that
second. Settlement evidence still retries for ever with bounded backoff.

## 7. Same-amount locking

Moved to the seller, which is the only phone that can see every buyer's window
at once. A second buyer owing the same amount gets BUSY_SAME_AMOUNT and the
sentence asking them to retry in a few minutes. Different amounts coexist. A
matched or cancelled window sends EXPECTATION_END so the amount frees at once.

Still no fingerprinting centimes and still no reference. Both would have been
easier and both would have broken the habit.

## 8. Capture privacy gate

`ReceiptCapture.expecting()` is asked **first**, and both sources return before
touching a notification title or an SMS body. The callback exposes one boolean,
so the listener never learns what is owed, to whom, or how much. The seller
side also matches against its **accepted** expectations rather than the buyer's
local ones, which is both more correct and narrower.

## 9. Buyer trust fix

`receiptCount` counted buyer **or** seller, so selling Internet raised your own
borrowing allowance. Split into `buyerReceiptCount` and `sellerReceiptCount`;
only the buyer count feeds `Trust.admitPaidSession`. Earning money is not
evidence that you pay your debts.

## 10. Server-backed anti-reinstall

**Not built.** `deviceHasUnresolvedDebt` is still a local flag, so a reinstall
clears it. This is the largest thing in the spec I did not complete, and I
would rather say so than describe the design as if it shipped. The pieces that
exist are the pseudonym and the tier logic; what is missing is carrying the
pseudonym in the signed Brain request and having the server derive the risk.

## 11. Signed parser configuration

**Not built.** The rules are already data and a test proves a new phrase works
without a code change, but the signed distribution from the Brain and the
pinned config key are not written. Built-in rules remain the only source.

## 12. Tests

476 Android JVM tests (+21) and 70 server tests. Every earlier test unchanged
and green.

`PayWireTest` (21) walks the loop: the destination surviving the wire and
resisting alteration, replayed old claims, the cooling window keeping the old
number active, expectation signing and verification, strangers, wrong sellers,
wrong destinations, padded and mismatched amounts, absurd windows, a seller that
cannot verify, the busy lock, receipts refused for another buyer, another
payment, an inflated amount, a substituted destination or a settlement the
expectation never named, malformed lines, and domain separation.

## 13. Version / build / commit / hash

Build 63, versionName 0.16.1, verified with `aapt2 dump badging`.
SHA256 `1c6fac1a50aff9f50a960f04c81a451c616ba320d3bec788a0de36ac8e42dc18`.
Commits `c8acdba`, `5bb03fa` on `main`; this report on top.
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.16.1

## 14. Hardware acceptance result

**Not run.** TESTING 67 is written and needs one real Mobile Money transfer.
The pass condition is the one you set: not that the seller recognised an SMS,
but that the **buyer's debt clears by itself** and a paid session works again
immediately afterwards.

## 15. What was actually hardware-proven

Nothing new in this release. What remains proven from earlier runs is the
Internet path, the budget contract, both graceful stops and short-session
billing. Everything in v0.16.x is unit-tested only until section 67 runs.

## 16. Remaining limitations

- **No Brain path for payments.** Local control channel only. The separated-
  phones case completes when the phones meet again, not while apart. The
  encoding is ready for HTTP; the endpoints are not written.
- **Anti-reinstall is still local**, so it does not yet stop the abuse it was
  designed for.
- **No signed parser configuration.** An operator rewording still needs a
  release, even though the rules are data.
- **The parser has still never seen a real MTN or Airtel message.**
- **The expectation lock is per-seller-phone.** Correct whenever the seller is
  reachable, which is the normal case, but a server-side lock is the complete
  version.
- The buyer waits a fixed 1.2 seconds for the seller's reply before showing the
  result. It is honest either way, but a push would be better than a delay.
- MTN and Airtel APIs remain unavailable, and no webhook signing secret ships.
