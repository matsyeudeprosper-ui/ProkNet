# CLAUDE_REPORT - ProkNet v0.14.1 "stable budget contracts"

Date: 2026-09-20
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 310/310 Android tests and 11/11 server tests pass,
released as build 56. This is a stability release: no new product feature.
The hardware acceptance test is TESTING 59 and has NOT been run yet.**

## 1. The exact root cause of the v0.14.0 hardware failure

You diagnosed it correctly. `Gateway.onProposal` read every signed proposal
with the **v1** body length:

```kotlin
val sb = Tunnel.parseSigned(f.data, Market.Contract.LEN)   // LEN = 62
```

A budget contract is 84 bytes. `parseSigned` checks
`d.size != bodyLen + 1 + sigLen`, that check failed, it returned null, and
the seller answered `malformed proposal` before `Contract.decode` was ever
called. Nothing was wrong with the economics, the signature, the transport
or the handshake. The envelope was read with the wrong ruler.

That is why the probe passed both ways first: everything below the contract
was already working. The failure was at the one boundary my v0.14.0 tests
did not cross. They proved `Contract.encode`/`decode` in isolation and never
put a contract on a wire.

**A second hole the audit found, which you did not report.** `Contract.decode`
accepted any body of either known length without checking it against the
declared version byte. A 62-byte body claiming version 2 would have decoded
as a budget contract with an all-zero rate, budget and ceiling. An 84-byte
body claiming version 1 would have decoded as a legacy contract with its
economics silently dropped. Neither is reachable from our own code. Both are
reachable from the wire.

## 2. Every fixed-length parser, audited

There are four `parseSigned` call sites. Only one was wrong.

| Call site | Length used | Verdict |
|---|---|---|
| `Gateway.onProposal` | `Market.Contract.LEN` | **the bug**, now version-aware |
| `Gateway.onUsageAck` | `Market.Checkpoint.LEN` (45) | correct: the checkpoint has one version |
| `TunnelClient.onAccept` | `32` | correct: `CONTRACT_ACCEPT` carries the contract **hash** |
| `TunnelClient.onCheckpoint` | `Market.Checkpoint.LEN` | correct |

`grep -rn "Contract.LEN" app/src/main` now returns **nothing**. No production
code refers to a contract body length at all. Only `bodyLenFor` does.

## 3. The signed envelope is now self-describing

The wire says its own length. `Contract.bodyLenFor(version)` is the single
source of a body length and returns -1 for a version this build does not
know, rather than guessing. `Market.framingOf` walks the envelope in one
fixed order and names where it stopped:

```
EMPTY -> UNSUPPORTED_VERSION -> TRUNCATED -> NO_SIGNATURE -> LENGTH_MISMATCH -> BAD_CONTRACT -> OK
```

`Contract.decode` refuses any body whose size does not match its declared
version. The order you asked for is the order the code runs: receive, read
the version, take exactly the contract bytes, take exactly the signature
bytes, decode, validate, verify the signature over exactly those bytes,
economic admission.

**The boundary is now a pure function.** `Gateway` needs an Android Context
and a main Looper, so it can never be constructed in a JVM test, and that is
the structural reason the failing line was untested. I moved the decision
out into `Market.admitProposal`, which returns an `Admission`: the reason
(null when agreed) plus what it saw on the way, namely the declared version,
the envelope length, whether it decoded and whether the signature verified.
`Gateway.onProposal` is now only plumbing: it calls `admitProposal` and
carries the answer to the wire and to the diagnostic. **The tests call the
same function the seller phone calls.** That is the difference between this
release and the last one.

## 4. The exact bytes are the same everywhere

`encode()` is the single producer. `hash()`, `sameTermsAs`,
`contractSignData`, `CONTRACT_ACCEPT` and `SESSION_START` all derive from it,
and a test asserts that a contract encoded, framed, put on a wire, parsed
back and decoded produces byte-identical `encode()`, `hash()` and signature
input. `contractSignData` is asserted to end with exactly `encode()`.

## 5. v1 compatibility

Preserved and pinned. A v1 contract still encodes to 62 bytes and still bills
through `sessionCost(bytes, pricePerMb, minPriceCfa)`. A test asserts that
the old `Tunnel.parseSigned(wire, Contract.LEN)` and the new
`decodeSignedContract` return byte-identical body and signature for a v1
envelope. A full v1 session runs end to end in `BudgetSessionTest` and
settles at the same figure it always did. Old stored contracts still decode.

## 6. Overflow audit

Every product is clamped **before** it is multiplied, not after.

| Function | Bound applied first |
|---|---|
| `Contract.costFor` | bytes to `min(maxBillableBytes, MAX_BILLABLE_BYTES)` |
| `Contract.uncappedCostFor` | bytes to `MAX_BILLABLE_BYTES` |
| `Pricing.chargeFor` | bytes to `MAX_BILLABLE_BYTES`, rate to `MAX_PRICE_PER_MB` |
| `Pricing.bytesForBudget` | budget to `0..MAX_BUDGET_CENTIMES`, result to `MAX_BILLABLE_BYTES` |
| `Pricing.quote` / `quoteForOffer` | budget coerced into `0..MAX_BUDGET_CENTIMES` |
| `Market.costCentimes` (v1) | `require` on both, unchanged |

New constant: `MAX_BUDGET_CENTIMES = 100_000_000`, one million CFA. The
largest legal product is 2^40 x 100 000, about 1.1e17, comfortably inside
Long's 9.2e18. Tests feed `Long.MAX_VALUE`, `MAX_BILLABLE_BYTES * 1000` and
-1 into the billing path and assert the charge stays inside `0..budget`.

## 7. The budget ceiling is absolute

`costFor` clamps to the signed budget, so no byte count can push a session
past it. Boundary tests cover exactly at the ceiling, one under, one over,
and absurdly over, plus the checkpoint the seller issues.

**But `costFor` could not be used to check the contract itself**, and this
was a real hole in my first patch. A ceiling inflated to three times the
budget still "costs" exactly the budget once clamped, so the validation
could never fire. `uncappedCostFor` gives the seller the true figure and
`acceptableProposal` admits on that. A test asserts both halves: that
`costFor` hides the inflated ceiling, and that admission still refuses it.

## 8. The seller's economics are current, not advertised

`Gateway.sellerFloorProvider` recomputes the floor from the phone's
**current** source at the moment a contract is admitted. A seller that
advertised on home Wi-Fi and moved to mobile data during the buyer's setup
refuses the old rate. A test drives exactly that transition. Rejection
reasons are now distinct: `seller floor not met` and `budget ceiling
invalid` instead of one shared message.

## 9. The buyer's side

The quote is snapshotted when `buy()` admits the deal, so changing the budget
preference mid-setup cannot alter a purchase already under way. A snapshot
older than two minutes is taken again before anything is signed, because a
Bluetooth setup can take a while and a stale quote may describe nothing real.
On any failure `TunnelClient.fail()` clears the pending proposal and the
quote, and the node clears `buyQuote` and `buyPrice`, so a rejected contract
leaves nothing behind for the next tap. Tests cover reject-then-retry and
three consecutive sessions.

## 10. Free sessions

v0.14.0's `acceptableProposal` rejected any budget contract with
`maxBillableBytes <= 0 || buyerBudgetCentimes <= 0`, which made a free
session **structurally malformed**. Now a rate of 0 is an explicit, valid
free contract: `valid()` allows it, framing accepts it, admission agrees it
even against a high seller floor, it bills 0 for any number of bytes
including `Long.MAX_VALUE`, and it books no ledger entry. It runs end to end
in `BudgetSessionTest`.

## 11. What I am NOT claiming

You asked me not to fake completeness. Two honest limits, both pinned by
tests and written into ARCHITECTURE.md:

- **The contract does not sign who pays.** Version 2 carries the rate, the
  budget, the byte ceiling, the source-cost basis, the seller policy and the
  pricing mode. It carries **no cost class and no payer**. A SPONSORED or
  PROK_FUNDED session is not cryptographically distinguishable from an
  ordinary one, and neither phone can prove to the other who was meant to
  pay. A test asserts a sponsored quote and a plain one produce identical
  signed bytes. Those fields are local policy. Signing them needs a v3.
- **The buyer does not receive the exact internal rate.** It learns only the
  advertised price, whole CFA rounded up, so the signed rate is in practice
  a multiple of 100. I chose the conservative option deliberately: the
  seller still admits on its own current floor, so the rounding can only
  favour the seller, never underpay it. Carrying the true sub-CFA rate needs
  a signed offer inside the advertisement, which this release does not add.
  The misleading comment claiming otherwise is gone.

## 12. Consumer megabyte leaks

The OnePlus diagnostic showed `3 CFA par Mo` reaching the consumer screens.
`CoverageModel.priceBandWord` replaces it on all five consumer surfaces, the
price tile, the source rows, the map cell rows, the source dialog and the
cell dialog, with `Gratuit`, `Bon marche`, `Prix moyen` or `Plus cher`.
Ranking still uses the real centime figure underneath, so nothing is lost.

`priceWord` survives on purpose, and only in Developer, COPY NETWORK and the
detail of an old v1 session. Every remaining `CFA/MB` string in the tree is
either a `DiagLog` line or a Lab screen. A test asserts no band word contains
"Mo" or "MB".

## 13. Diagnostics

`Gateway.contractDiag()` adds a `contract:` block to COPY NETWORK:

```
contract:
  version attempted: 2 | signed envelope length: 155
  decode: PASS | signature: PASS | economic admission: PASS
  reject reason: accepted
  buyer saw rejection: -
```

A failure now names which of the six steps failed and at which version,
instead of one word. The rejection log line carries the same detail.

## 14. Tests

310 Android JVM tests, up from 285. 11 server tests. Two new files:

- `ContractProtocolTest`, 12 tests: the regression itself, v1 still accepted,
  the full round trip with both signatures over the same bytes,
  version/length cross-checks in both directions, unsupported future
  versions, truncated, padded, unsigned and bad-signature envelopes,
  corrupted rate, budget and ceiling, the seller floor and a live source
  change, staleness and replay, free sessions, ceiling boundaries, money at
  maximum values, three sessions, the unsigned-payer pin, byte-identity
  across every use of the signed bytes, and the consumer wording.
- `BudgetSessionTest`, 10 tests: the whole session as the two phones exchange
  it, from propose, admit, accept and session start through checkpoints,
  final settlement and the ledger. It includes three sessions in a row, a
  rejected contract leaving both phones clean, a checkpoint past the ceiling,
  an early stop paying only actual usage, a session that moved no data,
  double settlement refused, free end to end, and a legacy v1 session.

Neither file reimplements a decision. Framing, admission, checkpoint
validation, cost and settlement all go through the production functions.
What I could not do is instantiate `Gateway` or `TunnelClient`. They need an
Android Context and a Looper, and this project has no Robolectric and no
AndroidX. That is why the decision moved into `Market` instead of being
imitated in a test.

## 15. Version / build / commit / hash

Build 56, versionName 0.14.1, verified with `aapt2 dump badging`.
SHA256 `35f35303e415a94465c0b90274c917351a07f6ca388969f213fa5173dae53870`.
Commit `590651d` on `main`; this report on top.
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.14.1

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```

Nothing in the proven transport stack, the v0.13.3 activation path or the
v0.12.5 design was touched.

## 16. What is still pending on hardware

Not run yet, and I am claiming none of it:

- **TESTING 59**, new, the acceptance test for this release: three
  consecutive budget sessions, Bluetooth failure injection during the second,
  background during the third.
- TESTING 55, 56 and 57 (v0.13.3 three cycles, Bluetooth recovery,
  background) and 58 (v0.14.0 budget session) have never been run on the
  phones either.

## 17. Known limitations

- No cost class and no payer in the signed contract, see section 11.
- The buyer signs the rounded advertised rate, not a sub-CFA rate, section 11.
- Three-phone live relay and MoMo settlement remain out of scope.
- The `Framing` reason is sent to the buyer as text. It is a diagnostic aid,
  not a protocol field, and nothing should parse it.
