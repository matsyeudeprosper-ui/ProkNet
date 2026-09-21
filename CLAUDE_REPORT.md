# CLAUDE_REPORT - ProkNet v0.16.0 "automatic Mobile Money receipt verification"

Date: 2026-09-21
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 455/455 Android tests and 70/70 server tests pass, released
as build 62. Hardware acceptance is TESTING 66, and it needs one real Mobile
Money transfer.**

| | |
|---|---|
| `541e458` | fix: settlement retries for ever + old-obligation backfill |
| `a10895e` | payments: signed destination, sources, parser, matcher, trust |
| `50109e7` | ui: automatic cash-to-Mobile-Money flow, capture and persistence |

The product rule held throughout: **nobody in Congo changes how they pay.**
Cash to any kiosk, the seller's ordinary number, walk away. The kiosk installs
nothing and has never heard of ProkNet. Everything below exists so the seller's
phone can notice the operator's own message and clear the debt by itself.

## 1. Receipt capture architecture

Two sources behind one abstraction, so the rest of ProkNet never asks which one
produced a candidate:

```
ReceiptCapture
 ├── SmsReceiptReceiver        DIRECT_SMS
 └── ReceiptListener           DEFAULT_SMS_NOTIFICATION
          ↓
    DeviceReceipt.Candidate  →  ReceiptParser  →  PaymentExpectation.match
                                              →  DeviceReceipt.Receipt (seller-signed)
```

Capture and interpretation are kept apart deliberately: an operator rewording
its messages must never require touching the Android plumbing, and an Android
API change must never touch the money logic.

## 2. Notification versus direct SMS

| Source | Confidence | Why |
|---|---|---|
| `DIRECT_SMS` | `DEVICE_SMS_VERIFIED` | the sender address comes from the network |
| `DEFAULT_SMS_NOTIFICATION` | `DEVICE_NOTIFICATION_VERIFIED` | a notification is a rendering, not the message |

The notification source is the practical one, because `RECEIVE_SMS` is
restricted on Play and ProkNet has no business becoming anybody's SMS app. The
direct source is feature-gated and simply never fires without the permission.

The obvious attack is that any app can post "you have received 50 CFA", so a
notification counts **only** when the phone's default SMS application posted
it, which a hostile app cannot become silently. Screenshots, clipboard text,
buyer-supplied text and typed sentences are not sources, and there is no method
in the codebase that accepts them.

## 3. Generic parser design

No template, anywhere. `if (text.startsWith("Vous avez reçu"))` would be a
payment system that stops clearing debts the day MTN adds a promotional line.

Normalise accents, case, unicode spaces, separators. Then **score**: credit
words pull one way; sending, withdrawal and debit words pull the other and
outrank them; OTP, promotional and balance-only messages are refused outright.

The dictionaries are data, so an operator rewording can arrive later as a
**signed** configuration rather than a new APK. A test proves a phrase we have
never seen starts working when it is added to the rules, with no code change.

## 4. Amount extraction

Every number competes. Each is scored on proximity to receipt verbs and to a
currency token, and pushed down by a preceding "solde"/"balance", by being nine
digits or more (a phone number or transaction id; no payment between two people
is a hundred million francs), and by sitting inside a date or clock time, which
are masked before extraction.

Thousands separators and fractions are told apart by shape rather than guessed:
`1 250`, `1.250`, `1,250` are all 1250, while `50,75` is a fraction.

The expected amount is the strongest single signal, and it can only ever
*promote* a number genuinely present. A test proves that waiting for 50 and
receiving a message saying 40 never produces 50.

## 5. No-reference matching

Seller + rail + exact amount + a twenty-minute window + a message on the
seller's phone. Nothing else, and no test in the suite supplies a reference,
because in real life nobody will. If the operator happens to include one it is
kept as extra evidence and is never required.

The amount must be **exact**. A kiosk sends what it is told, so "close enough"
means somebody else's payment.

## 6. Ambiguity handling

Prevented rather than resolved. One seller may not hold two live expectations
for the same amount on the same rail; the second buyer is asked to wait a few
minutes. Different amounts run concurrently without trouble.

Two things I deliberately did not do, both of which would have been easier:

- **No fingerprinting centimes.** Charging somebody 51 CFA instead of 50 to
  tell their transfer apart is taking their money to solve our problem.
- **No reference requirement.** It would work, and it would break the habit the
  whole release exists to preserve.

If two expectations somehow do fit, nothing is chosen. If the parser is unsure,
nothing is cleared. A false negative costs a retry; a false positive gives away
Internet.

## 7. Seller destination binding

`DestinationClaim`: seller identity, rail, normalised number, version,
timestamp, **signed by the seller**. This is what v0.15.3 was missing — the
server checked a destination table that nothing ever populated.

Only the seller may say where the seller is paid. The buyer receives the number
to read aloud at a kiosk and can never propose or alter it. A replacement needs
a strictly higher version from the same identity and the same rail, so a
replayed old claim cannot move the money back, and a change waits ten minutes
before use so a stolen phone cannot redirect payments instantly.

## 8. PaymentExpectation model

`paymentId, buyerId, sellerId, rail, destinationHash, amountCentimes,
createdAt, validFrom, expiresAt, includedSettlementIds, state`

States: `WAITING → MATCHED | EXPIRED | NEEDS_REVIEW | CANCELLED`. The id is
deterministic. An expired window leaves the debt exactly as it was; only the
attempt ends.

## 9. DevicePaymentReceipt model

`paymentId, sellerId, buyerId, rail, destinationHash, expectedCentimes,
observedCentimes, observedAt, source, sourcePackage, messageEvidenceHash,
parserVersion, confidence, matchedSettlementIds, reference`

**Signed by the seller**, because the party who benefits from a debt
disappearing is not the party who should attest it was paid. A test confirms
the buyer's key cannot verify it and that inflating the amount breaks the
signature.

## 10. Buyer trust rules

| Verified payments | Limit |
|---|---|
| 0 | 10 CFA |
| 3 | 25 CFA |
| 10 | 50 CFA |
| 25 | 100 CFA |

Policy values, not protocol constants. A stranger gets about one short session
and nothing more until a payment is **observed**. Trust never grows because
somebody pressed a button, and no such button exists. Free and sponsored
Internet are never gated. Checked before any Bluetooth channel, handshake or
probe.

## 11. Device-binding design

`sha256("ProkNet-device-v1" | app-scoped identifier)`, truncated. No hardware
serial, no IMEI, no advertising id, nothing that follows anybody to another
app. A new identity on a device that still owes money starts with **zero**
credit, not a ban: free and sponsored Internet keep working, and paying the old
debt restores everything.

Said plainly in the docs: **this is anti-abuse, not identity.** A factory reset
defeats it. Somebody determined will get through. That is exactly why the
exposure behind it is one short session — I would rather be bypassable than
collect invasive identifiers to pretend otherwise.

## 12. Privacy treatment

Nothing reads the inbox. Nothing scans history. A message is inspected only
while a payment is actually expected, and if nothing is outstanding the listener
returns immediately without looking at the content at all.

What is kept: the sha256 of the one message that matched, the parsed amount, the
time, the source package, the parser version and the confidence. The body never
travels and is never stored. A test takes a message containing a name and a
phone number and asserts neither appears anywhere in the signed receipt.

## 13. Old obligation backfill and infinite retry

`MAX_ATTEMPTS = 12` is gone. A phone whose server was down for a long weekend
could strand a real financial proof for ever. The evidence does not rot: it is
signed bytes about a session that genuinely happened and is just as valid in
three months. Retry is now indefinite with the same doubling backoff bounded at
six hours — never abandoned, never a tight loop. `longPending()` flags a long
wait in the diagnostic without giving up.

`backfill()` runs on every start and enqueues every local obligation that has
verifiable evidence and is not already queued, so anything booked before the
queue existed, or dropped by the old limit, is picked up again.

## 14. What still requires Android permissions

| | |
|---|---|
| Notification access | **Required** for the notification source. Granted by the seller in Android's own settings screen and revocable there. |
| `RECEIVE_SMS` | **Optional.** Declared; the stronger source when present. Restricted on Play, so the product must work without it, and it does. |

Neither is needed by a buyer. Neither is needed for free or sponsored sharing.
Without either, paid sharing honestly says automatic verification needs
enabling rather than pretending it works.

## 15. Device-verified versus operator-verified

`PAYMENT_DEVICE_VERIFIED` means the seller's phone observed the money arriving.
`PAYMENT_OPERATOR_VERIFIED` means MTN or Airtel confirmed it through their API,
and **nothing in this build can produce it**. A test asserts no source maps to
it. For the pilot, device-verified clears a debt; the property that matters is
that the evidence is observed by the seller rather than asserted by the buyer.

MTN and Airtel API rails remain unavailable and refuse to initiate, unchanged.
When operator access arrives it becomes an additional high-confidence verifier
rather than a rewrite.

## 16. Tests

455 Android JVM tests (+47) and 70 server tests.

- `ReceiptParserTest` (17): a wide synthetic corpus — ten ways of saying money
  arrived, accents and spacing, thousands separators, seven ways money leaves,
  balances, OTP and promotional messages, the payment chosen over the running
  balance, phone numbers and dates rejected, two-amount ambiguity, an expected
  amount never conjuring a number, optional references, and the dictionaries
  being extensible without code.
- `PaymentMatchingTest` (16): a kiosk payment clearing a debt with no reference,
  exact amounts, windows, two amounts told apart, the same-amount lock, refusing
  to guess between two fits, source eligibility including a hostile app and a
  hidden notification, deduplication, the seller signing, and the raw message
  never reaching the receipt.
- `TrustTest` (14): the first session, tiers, reinstall, the pseudonym revealing
  nothing, seller readiness, and the whole destination-claim lifecycle.

Two calibration bugs found and fixed while writing them: the scoring threshold
rejected clean messages at 56 against a threshold of 60, and a date was being
read as an amount.

## 17. Version / build / commit / hash

Build 62, versionName 0.16.0, verified with `aapt2 dump badging`.
SHA256 `7faff0ca560a6150b8a4edd29113e3746987be16876673fc08fdad2d2dd4a924`.
Commits `541e458`, `a10895e`, `50109e7` on `main`; this report on top.
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.16.0

## 18. Hardware test

**TESTING 66**, and it needs one real Mobile Money transfer. The seller sets a
number and grants notification access; a short session creates a debt; the buyer
taps PAYER and is told an amount and a masked number; somebody sends that exact
amount the ordinary way, telling ProkNet nothing; the seller's phone should
clear the debt within seconds of the operator's message, with no button pressed
by anybody.

## 19. Known limitations

- **The parser has never seen a real MTN or Airtel message.** It is built to
  survive wording it does not know, and the corpus is synthetic. The first real
  hardware run is the real test, and the dictionaries are data precisely so a
  surprise is a configuration change rather than a release.
- **The signed remote configuration is designed, not built.** Rules are
  replaceable in code today; carrying them from the Brain with a signature is
  the next step, and an unsigned one must never be accepted.
- **Receipt delivery to the buyer is local only.** The seller signs a receipt
  and applies it; `onReceiptFromSeller` exists and is tested, but nothing yet
  carries it over the tunnel or the brain, so a buyer currently sees the debt
  cleared when the two phones next meet or when the server path is wired.
- **Device history is not yet server-side.** `deviceHasUnresolvedDebt` is a
  local flag; the pseudonym and the association need the brain to be
  authoritative, otherwise a reinstall clears the local flag too.
- **The expectation lock is per-phone.** Two buyers owing the same seller the
  same amount are only serialised if the lock is visible to both, which today
  means the seller's phone; a server-side lock is the complete version.
- **Notification content can be truncated** by the system. If the amount cannot
  be read, nothing matches, which is the safe direction but will occasionally
  mean a manual retry.
- Everything from v0.15.3 still stands, including no operator API and no webhook
  signing secret.
