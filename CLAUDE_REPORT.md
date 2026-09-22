# CLAUDE_REPORT - ProkNet v0.17.0 "the live Network Brain"

Date: 2026-09-21
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)

Version 0.17.0, build 68. **611 Android tests, 287 server tests, all passing.**
Baseline was 583 + 210; every one of those still passes untouched.

Your spec arrived in two halves and the second was cut off mid-item-102, so items past
~102 are not addressed. **Read section 12 first: three UI surfaces are built and tested
as models but are not yet drawn on the screens.** I would rather say that plainly than
have you find it on a phone.

## 1. What I found before writing anything

You asked me to inspect main first, and it changed the shape of the work. Most of the
model layer already existed:

| v0.17 needs | already there |
|---|---|
| a coarse zone id | `CoverageModel.zoneId`, 0.005 deg cells, returns `z?` with no location |
| a signed demand | `NetRequest.Request` + `RequestGossip`, with TTL and tombstones |
| a provider inbox | `ProviderInbox`, and it already had `Source.BRAIN` from v0.13.3 |
| provider eligibility | `ProviderActivation.eligibility` |
| the notification | `NetworkNode.alertHook` |
| zone colours | `Coverage.ZoneStatus` |
| demand + presence + job tables | `internet_requests`, `provider_availability`, `jobs` |
| a matcher | `brain/matching.py` |

So v0.17 became: make the durable control plane real and reachable over signed routes,
give it TTLs and a deterministic matcher, and feed the existing client models - not a
second network stack. Nothing parallel was created.

## 2. Core architecture

`server/brain/network.py`, as Brain migration **2** with a numbered `schema_version`.
Six tables: presence, demand, activation, reliability, events, per-buyer cooldown.

The Brain coordinates and never carries. Bulk Internet still goes over the L2CAP path
hardware-proven since v0.10.2, which I did not touch.

## 3. Local-first, and unchanged without the Brain

Order is still: usable local source, then locally visible ProkNet provider, then the
Brain. `NetworkNode.originate` was already the one place meaning "ask the network", so
the demand is created there - no second button, and the buyer presses GET INTERNET once.

With the Brain unreachable, ProkNet behaves exactly as in v0.16.5. Nothing in the control
plane is on the critical path of a session, and `NetworkBrainSync` failing costs a log
line and a backoff.

## 4. Demand lifecycle, and nothing skipping

CREATED -> SEARCHING -> PROVIDER_FOUND -> ACTIVATION_SENT -> PROVIDER_ACCEPTED ->
WAITING_FOR_LOCAL_LINK -> CONNECTED, plus EXPIRED / CANCELLED / FAILED.

`DEMAND_NEXT` is an explicit table and a move that is not in it is **refused rather than
applied**, so nothing can report itself CONNECTED out of nowhere. A test walks every
server word and asserts none of them produces CONNECTED on the phone.

## 5. The honesty rule, enforced structurally

`core/NetworkAccess.kt` is the one model every screen reads. `onDemandStatus` cannot
produce CONNECTED from **any** server word - including the server's own "CONNECTED",
which means somebody told the Brain a connection happened, not that this phone has one.
Only `LinkEvent.INTERNET_UP`, from the transport, produces it.

So a provider accepting reads "Un fournisseur se prépare", never "Internet disponible".
A test sweeps every state's wording for "disponible maintenant" and for protocol words.
The model has no field for a provider identity, which is the point.

## 6. Matching

Free, then sponsored, then cheapest commercial; then reliability, freshness, spare
capacity, and the provider id as a stable last tie-break. No randomness - a test runs the
same state five times and asserts the same order.

A buyer asking for FREE is never given a commercial provider. An unpriced commercial
offer is not a candidate because it cannot honestly be compared. A phone is never matched
to itself. At capacity is excluded. Already-asked is excluded, so a decliner is not
re-asked for the same request.

One activation at a time; at most 3 attempts; a 60 s cooldown after a cancellation;
presence TTL 120 s; activation TTL 75 s; demand TTL 10 min. All constants in one place.

Network reliability is separate from payment trust, deliberately - a provider with poor
signal is not a bad payer. A new provider scores 0.5, not 0.

## 7. Privacy

Presence holds a coarse zone, capability flags and an internal price hint. A test reads
the table definition and asserts there is no column for a number or a position.

**There is no route that lists providers in a zone.** Coverage answers a colour and an
`updatedAt` only, because in a thin zone "1 provider" is one identifiable household. The
buyer is told somebody is preparing and never which phone - asserted over HTTP.

`/health` now returns `{ok, version, protocol, schema}` and nothing else. It used to
report how many nodes, requests and sources the Brain knew about, which told anybody who
asked how many people used ProkNet and roughly where. Counts moved behind a signature.

A client-supplied zone is a matching hint, **not** security evidence. A fake zone can
waste an activation; it cannot move money, gain trust or bypass a payment check.

## 8. Authorization and identity ownership

Every `/v1/network/*` route is signed with the hardened v0.16.3 form and
`require_bound`. The verified identity is the only actor: a body field called `buyerId`
or `providerId` is **not read at all**, so there is nothing for it to disagree with. A
demand signed by A claiming to be B belongs to A, and B cannot read it - tested.

Presence writes only the caller's own row. A provider inbox holds only its own jobs. Only
the assigned provider may answer. Only the two parties may report. Anyone else: refused.

## 9. Idempotency and rate limits

Presence is an upsert. Demand is idempotent on a client-generated id. Accept twice is
`duplicate: true` and counts once. Cancel twice is fine.

One live demand per buyer - a second, different demand returns the existing one.
Rate limits are per node **and per kind**, so a heartbeat budget cannot be exhausted by
demand creation; 15 heartbeats in a row all pass.

## 10. A deadlock I caused and hit

`allow_network` took `STATE.lock` while the POST path already held it, and that lock is
not reentrant. The first HTTP test **hung** rather than failed. The limiter now documents
that the caller owns the lock.

## 11. Ops, backup, logging

`deploy/brain/{install,start,stop,status,backup}.ps1`.

`install.ps1` registers a Scheduled Task `ProkNetBrain` - built into Windows, restarts at
boot, no extra dependency on a box that runs live services.

`status.ps1` checks the **listening socket**, not the process: a python that is alive and
not listening looks healthy and serves nobody. `stop.ps1` counts processes afterwards.

`backup.ps1` uses SQLite's **online backup API**, not a file copy, then runs
`integrity_check`. I ran it against a real database with a settlement in it: 22 tables,
schema 2, and the settlement row present in the copy.

Logging is a `RotatingFileHandler`, 8 MB x 5. Ids cut to twelve characters. Never a
number, an SMS body, a key, a signature or a position.

## 12. What is NOT done - read this

Three UI surfaces exist as tested models but are **not yet rendered**:

- **Home status area** (items 41-45). `NetworkAccess.title/hint` produce the exact lines
  you specified and are unit-tested, but no view shows them yet. The buyer today sees the
  v0.16.5 sphere text.
- **Map zone colours** (items 38-40). `NetworkAccess.zoneStatus/zoneLabel` and the
  server's coverage route are done; `CoverageMapView` is not fed from them yet.
- **Activité lines** (items 49-50). `NetworkAccess.eventLine` and `dedupKey` are done and
  tested; nothing writes them into the history yet.

The provider side **is** wired: a Brain job becomes a `ProviderInbox` opportunity, so the
notification and the Gagner card work through the existing v0.13.3 path.

Also not done: items past ~102, because your message was cut off there.

## 13. Totals, version, artefacts

| | |
|---|---|
| Android tests | **611** (was 583) |
| Server tests | **287** (was 210) |
| Version / build | **0.17.0 / 68** |
| Brain schema | **2** |
| Android DB | **10, unchanged** - network state is not financial |
| APK SHA256 | `b1c57f633823170eef0bef235798f035dc6c047b0630c2f5ab2f171c0d56a4b7` |

## 14. Hardware status - unchanged

- **Hardware-proven:** Bluetooth/L2CAP Internet, VPN browsing, provider activation and
  recovery, pricing and contracts, graceful stops, the signed final checkpoint.
- **Software-proven only:** the whole Mobile Money payment stack, and all of v0.17.
- Real MTN/Airtel message parsing stays unproven until you make an actual payment.
- **No part of v0.17 has run on a phone.** TESTING 72 is the procedure, and it needs the
  Brain deployed and reachable from both handsets.

## 15. Remaining limitations

- TLS: **deployment ready, hostname pending.** The Brain speaks plain HTTP, binds to
  loopback, and warns if bound elsewhere. No temporary insecure public HTTP anywhere.
- An accepted activation has no separate longer wait window yet (item 77): it expires
  with the activation TTL, and the demand then tries another candidate.
- Sponsored providers are modelled but nothing sets `sponsoredReady`.
- Capacity is 0 or 1, because the gateway accepts one session.
- The three UI surfaces in section 12.

## 16. Definition of done

Partly. The control plane is real, durable, authorised, deterministic and tested on both
sides, and a provider does get woken through the existing notification. A buyer's screen
does not yet *show* the new states, so on a phone today the flow works without being
visible. That is the honest position, and section 12 is the list.

---

# CLAUDE_REPORT - ProkNet v0.16.5 "the cooling timestamp is signed"

Date: 2026-09-21
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)

Version 0.16.5, build 67. **583 Android tests, 210 server tests, all passing.**

Both bugs were real. Confirmed in the code before touching anything.

## 1. Destination v2 canonical bytes

```
ProkNet-destination-claim-2|<sellerId>|<RAIL>|<normalised msisdn>|<version>|<createdAt>
```

One implementation each in `DestinationClaim.signDataV2` and `Destination.sign_data_v2`,
pinned together by a fixture rather than by two people reading the same sentence.

Wire: `pay1.dest|dest2|sellerId|rail|msisdn|version|createdAt|sig`. The line already
carried `createdAt`; now the signature covers it, so moving it in transit breaks the claim
instead of quietly moving the cooling window. Tested in both directions - plus ten minutes
and minus ten minutes - on both sides.

## 2. Legacy v1 compatibility

`dest1` claims are still read and still verify against `ProkNet-destination-claim-1`.
Nothing after build 66 creates one. A seller who has not changed their number is never
asked to re-enter it.

The prefix decides which bytes were signed and is never inferred: a `dest1` claim
relabelled `dest2` does **not** verify, and neither does the reverse. Otherwise a carrier
could have an unsigned timestamp treated as authenticated.

`timeIsSigned` / `time_is_signed` say which kind a claim is, so nothing in the system
describes a legacy timestamp as authenticated.

**No second database migration** (your item 20). The table keeps fields and a signature,
not the wire line, so the format is derived when a stored claim is re-encoded:
`DestinationClaim.formatOf` tries the format we produce now and falls back to the one we
used to. The Brain stores the whole line, so its prefix is already there, and no historical
signed bytes are rewritten.

## 3. v1 to v2 version transition

The signature format and the claim version are separate. A legacy claim at version 3 is
followed by a v2 claim at version **4**. Versions stay seller-global and monotonic, so an
old claim can never come back, and a legacy claim cannot overwrite a newer v2 one - tested
at both equal and lower versions.

## 4. Cross-language signature fixture

`server/tests/fixtures/crosslang.json` gains a `destination_v2` section: canonical bytes,
a Python signature, a signature captured from a real Kotlin run, and a wire line with only
the timestamp moved.

- Python signs -> Kotlin verifies, through `PayWire.parseDestinationClaim` and the real
  `DestinationClaim.verify`.
- Kotlin signs -> Python verifies, through the real `Destination.verify`.
- The tampered line is refused by both, and a test asserts only the timestamp differs
  between the two lines - otherwise it would be proving something else.

The timeline cases now carry each claim's format and include a mixed legacy/v2 history
before, at and after the cooling boundary. 14 cases, both sides.

## 5. createdAt tamper test

Six places: Kotlin `moving_the_timestamp_invalidates_the_claim` (both directions),
`the_wire_line_carries_the_timestamp_and_it_is_checked` (edits the wire field, keeps the
signature), Python `test_moving_the_timestamp_forward_breaks_the_claim` and
`..._backward_...`, `test_the_server_refuses_a_tampered_claim_outright` (nothing is
stored), and the cross-language `tampered_wire`.

## 6. Brain expectation-time validation

`put_expectation` used `active_destination(seller, now, cooling)` where `now` is arrival.
It now uses `acceptable_destinations(seller, now, e.created_at, cooling)` - what was active
when the buyer asked, and what is active now, as (rail, hash) pairs.

`DestinationClaim.acceptableHashes` on the phone returns the same set, so the seller's own
decision and the Brain's cannot differ. I kept "and what is active now" on both sides
deliberately: a single-value rule would have made the two disagree for an expectation
created before a change and naming the seller's current number.

Arrival time now decides only whether the expectation has expired.

I also kept the rail check that was there - the hash covers the rail, but the expectation
carries a rail field of its own beside it, so the pair is compared rather than the hash
alone. Removing it would have been a silent regression.

## 7. Cooling-boundary tests

With v1 MTN at T0, v2 Airtel at T1, cooling 10m, boundary = T1 + 10m:

| case | createdAt | uploaded | result |
|---|---|---|---|
| A | boundary - 1ms | after boundary | MTN **accepted** |
| B | boundary | after boundary | MTN refused, Airtel accepted |
| C | boundary + 1ms | after boundary | MTN refused, Airtel accepted |

Checked that these bite: reverting `put_expectation` to use `now` fails two of them.

## 8. Delayed-upload test

An expectation created 1s into the cooling window and uploaded 15 minutes later - long
after the switch - is accepted. One created the same way and uploaded after 21 minutes is
refused, because it has genuinely expired.

A buyer cannot backdate to reach an old destination: `createdAt` is inside the buyer's own
signed expectation, so editing it on the wire breaks the expectation.

## 9. Totals, version, artefacts

| | |
|---|---|
| Android tests | **583** (was 568) |
| Server tests | **210** (was 185) |
| Version / build | **0.16.5 / 67** |
| Database version | **10, unchanged** - no migration needed |
| APK SHA256 | `fabdd875301dd3976158d9a7c946024770d8e0f9535ed89150b686eb36c23c2b` |

## 10. Hardware status - unchanged

- **Hardware-proven:** Bluetooth/L2CAP Internet, VPN browsing, provider activation and
  recovery, pricing and contracts, buyer/seller graceful stops, the signed final
  checkpoint.
- **Software-proven only:** automatic Mobile Money receipt detection, the payment
  expectation flow, seller-signed receipts, the Brain payment path, anti-reinstall risk,
  signed parser updates, and everything in v0.16.4 and v0.16.5.
- Real MTN/Airtel message parsing stays unproven until you make an actual Mobile Money
  payment.

## 11. Remaining limitations

- A legacy claim's timestamp is still the one cooling uses. It is not signed and cannot
  retroactively be. The exposure lasts until that seller next changes their number, and
  nothing claims otherwise.
- The migration still has not run on a real upgraded phone (TESTING 70a), and 71a now
  depends on the same thing: a phone not wiped since an earlier v0.16 build.
- The parser has still never seen a real MTN or Airtel message.
- A seller has one active receiving destination.
- `PAYMENT_OPERATOR_VERIFIED` is produced by nothing; no operator webhook secret.
- A buyer learns a new destination on the sync cadence, not sooner.

## 12. Definition of done

The timestamp that controls destination cooling is cryptographically bound to the seller's
claim, and the Brain validates an expectation against the destination that was active when
that expectation was created rather than when it arrived.

Payment architecture work stops here unless hardware testing finds a real defect.

---

# CLAUDE_REPORT - ProkNet v0.16.4 "migration + destination rotation"

Date: 2026-09-21
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)

Version 0.16.4, build 66. **568 Android tests, 185 server tests, all passing.**

Your message was cut off mid-item-10 ("Once:"), then the rest arrived. Everything
through item 22 is implemented.

## 1. Database version

8 or 9 -> **10**. `MessageStore` now takes its version from `Migrations.DB_VERSION`, so
the number and the migration cannot drift apart.

## 2. The exact migration

`if (oldVersion < 10) { createSettlements(db); migrateV10(db) }`.

`migrateV10` reads `PRAGMA table_info`, asks the pure `Migrations.toV10` for a plan, and
runs it **in one transaction**. An empty plan is the normal case, so it is safe to run
twice.

`payment_receipts` - rebuilt whenever any column is missing:

```
CREATE TABLE payment_receipts_v10(<the v10 shape>)
INSERT INTO payment_receipts_v10(<all columns>) SELECT <present columns, literals for the rest>
DROP TABLE payment_receipts
ALTER TABLE payment_receipts_v10 RENAME TO payment_receipts
```

**Rebuilt rather than ALTERed, deliberately.** `ALTER TABLE ADD COLUMN` appends, so an
upgraded phone would carry `..., sig, delivered` while a fresh install has
`..., delivered, sig`. Nothing breaks today - every query is by name - but a phone whose
table is a different shape from every test database is exactly the situation this work
exists to end.

`destination_claims` - rebuilt whenever the key is not `(seller_id, rail, version)`:

```
CREATE TABLE destination_claims_v10(... PRIMARY KEY(seller_id, rail, version))
INSERT INTO destination_claims_v10(...) SELECT seller_id, rail, msisdn, version,
    MAX(created_at), sig FROM destination_claims GROUP BY seller_id, rail, version
DROP TABLE destination_claims
ALTER TABLE destination_claims_v10 RENAME TO destination_claims
```

The GROUP BY exists so a database that somehow holds two rows for one version keeps the
later one instead of failing the whole upgrade - a phone that cannot open its own
database is worse than a duplicate.

Nothing is fabricated: if `payment_receipts` lacked `sig`, `payment_id`, `seller_id` or
`buyer_id`, the migration refuses. A receipt with an invented signature looks like
evidence that money arrived and verifies against nobody.

## 3. Old schemas actually tested

The `CREATE TABLE` text is copied from the commits, not reconstructed:

| fixture | source | what is wrong with it |
|---|---|---|
| build 62 | commit `6f22ec6` | `payment_receipts` has no `delivered`; `destination_claims` is `PRIMARY KEY(seller_id, rail)` |
| build 63 | commit `867dae6` | correct already |
| build 64 | build 63 + `pay_sync` + `receipt_rules` | correct already |

Each is a real SQLite database (`org.xerial:sqlite-jdbc`, **test scope only, never in
the APK**), and what runs against it is `Migrations.toV10`.

A test asserts build 62 really is missing `delivered` and really does key on
`(seller_id, rail)` - if that stops being true the rest of the file proves nothing. Another
shows the failure directly: on a build-62 database, inserting claim v2 leaves **one** row,
so there is no previous claim and cooling cannot work.

## 4. Rows preserved

- every receipt survives, count unchanged;
- `sig` is compared byte for byte after the rebuild - a receipt whose signature changed is
  not evidence;
- an existing receipt comes through as `delivered=0`, the safe direction: it is offered to
  the buyer again rather than silently treated as done;
- every destination claim survives, and a new one can then be added beside it;
- `receipt_rules`, `payment_expectations` and `settlements` are untouched - asserted by
  comparing their full shape before and after, and by reading back a live expectation, a
  signed rule config and an outstanding settlement.

## 5. Fresh vs upgraded

Identical. A test compares, for both tables, the **name, type, NOT NULL, default, key
ordinal and column order** of every column plus the index list, between a database built
fresh and one upgraded from build 62.

## 6. Receipt redelivery after migration

`receiptDelivered`'s exact SQL is run against the migrated database: 0 before marking, 1
after. Before the migration that query would have failed outright.

## 7. Destination history preservation

`previousDestinationClaim`'s exact SQL is run against the migrated database and returns
the older number - which is what cooling falls back to.

## 8. Local destination rotation

`sendDestinationTo` now sends `activeDestination(now)` with **that version's** signature,
not the newest configured claim. Sending the newest was a real bug: during the ten-minute
window the buyer built an expectation against a number the seller was not watching, and
the seller refused its own buyer's payment.

`configuredDestination()` and `activeDestination(now)` are separate on purpose. The
settings screen may say "Airtel, en attente" while every payment still goes to MTN.

The buyer picks its destination with the same shared function, so both sides of one
conversation are looking at the same claim.

## 9. Brain destination rotation

`GET /v1/pay/destinations` already returned the active claim; it now decides which one
that is from the claim's **signed `created_at`** rather than `stored_at`. See 12.

The phone also pushes **every** claim the Brain has not seen, not only the newest. A phone
that was offline when it made v1 and came back after making v2 would otherwise leave the
Brain believing v2 was the only claim there had ever been, and it would hand buyers the new
number with no cooling at all.

## 10. Periodic destination refresh

`creditorsWithoutDestination()` -> `creditorsNeedingDestinationRefresh()`: **every**
outstanding creditor, on the normal sync cadence, whether or not a claim is already held.
Repeating the question is a no-op - the same version is idempotent, a lower one is refused,
only a higher one changes anything - and the log line only appears when something actually
changed.

## 11. Expectations stay pinned

`sellerDecision` took one destination hash, so the moment cooling ended it began refusing
expectations it had itself asked for minutes earlier. It now takes a set, and
`DestinationClaim.acceptableHashes(claims, now, askedAt)` returns what is active now AND
what was active when that buyer asked. Normally that is the same single hash.

A brand-new expectation against the old number after the switch is still refused: the pin
is for expectations that already exist, not a second live destination.

## 12. Kotlin / Python active-destination agreement

**A second cross-language divergence was already present.** The phone measured the cooling
window from the claim's signed `createdAt`; the server measured it from `stored_at`, the
moment it happened to receive the claim - a number the phone cannot see. A phone offline
for an hour would have moved to its new number while the Brain still sent buyers to the
old one, and neither side could have reported the disagreement.

`server/tests/fixtures/crosslang.json` now carries ten timeline cases: only v1; inside
cooling; one millisecond before the boundary; the exact boundary; after it; the same for a
same-operator number change; three claims (the PREVIOUS one, not the oldest); and a claim
whose timestamp is in the future. `DestinationClaim.active` and `PayBox.active_destination`
must both produce `expected_version`. I checked it bites: restoring `stored_at` fails five
of these.

## 13. Outstanding-only destination authorization

`Settlements.has_outstanding_between` uses `OUTSTANDING` - PENDING, PAYMENT_INITIATED,
PAYMENT_SEEN - so there is one answer to "is this still owed" rather than a copy in
`app.py` that can drift. CONFIRMED, EXPIRED, DISPUTED and SECURITY_REVIEW do not qualify.

## 14. Paid former buyer

403. A test settles the obligation and then asserts the refusal body contains no number in
either form, no rail, no destination hash and no `destinations` key at all. Tests also
cover expired/disputed/security-review, each of the three owed states, and the seller
always being able to read its own.

## 15. Totals, version, artefacts

| | |
|---|---|
| Android tests | **568** (was 536) |
| Server tests | **185** (was 174) |
| Version / build | **0.16.4 / 66** |
| APK SHA256 | `0c36d2c4c271554f46a8d4a867d318cd84c4b3b48b901ad4655fd475230c913a` |

## 16. Hardware status - unchanged

- **Hardware-proven:** Bluetooth/L2CAP Internet, VPN browsing, provider activation and
  recovery, pricing and contracts, buyer/seller graceful stops, the signed final
  checkpoint.
- **Software-proven only:** automatic Mobile Money receipt detection, the payment
  expectation flow, seller-signed receipts, the Brain payment path, anti-reinstall risk,
  signed parser updates, and everything in v0.16.4.
- Real MTN/Airtel message parsing stays unproven until you make an actual Mobile Money
  payment.

## 17. Remaining limitations

- The migration is proven against real databases built from the historical SQL, but has
  **not** run on a real upgraded phone. TESTING 70a is that test, and it needs a phone that
  has not been wiped since build 62. If both phones have been reinstalled, say so rather
  than reporting a pass.
- The parser has still never seen a real MTN or Airtel message.
- A seller has one active receiving destination. Simultaneous MTN and Airtel would be a
  product change, not a bug fix.
- `PAYMENT_OPERATOR_VERIFIED` is still produced by nothing; no operator webhook secret.
- Key rotation for parser rules still needs a new APK.
- A buyer refreshes destinations on the sync cadence, so a seller who changes their number
  while a buyer is completely offline is learned about at the next sync, not sooner.

## 18. Definition of done

A phone on any earlier v0.16 build can upgrade without losing or corrupting payment state;
the local and Brain paths expose the same active destination; changes pass through cooling
cleanly; buyers with unresolved debt keep learning newer destinations; and once the debt is
paid they lose access to that number.

Payment architecture work stops here unless hardware testing finds a real defect. v0.17
next when you say so.

---

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
