# CLAUDE_REPORT - ProkNet v0.17.11 "nobody types the Brain address"

Date: 2026-09-25
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)

Version 0.17.11, build 79. Small, deliberate, one purpose.

## 1. What changed

Clearing the app's storage wiped the Brain address, so a freshly reset phone was silently
off the network until somebody went into the Lab screen and typed a URL from memory.
That happened four times during the v0.17.5–v0.17.10 runs. Nobody in Congo will do it.

`core/BrainEndpoint.kt`: the pilot address `https://proknet.duckdns.org` is now the
**default**, not a hard-coded constant. Three states, and the difference matters:

- never told anything → the pilot Brain;
- owner saved another address → that address;
- owner saved an **empty** address → deliberately off, and it **stays off** — which is
  what TESTING 75g and 76 need, and what an off switch has to mean.

This falls out of `SharedPreferences.getString(key, default)`: the default applies only
when the key was never written. A test pins all three.

The Lab screen now **refuses a public `http://` address** with a sentence saying so. A
signed identity and a request for Internet are not things to send in the clear; the
standing rule was never to paper over missing TLS with a plain-HTTP shortcut, and the
default being HTTPS with a real certificate is the opposite of that. Plain HTTP is still
allowed to `localhost` and private ranges so a Brain on a laptop can be tested.

## 2. Numbers

8 new Android tests (`BrainEndpointTest`), server suite unchanged at 360. Totals are in
`dist/test-results.txt` for build 79.

## 3. Hardware status

Unchanged: nothing in v0.16/v0.17 beyond the local BLE path (build 72) is hardware-proven.
NEXT is still TESTING 77, 79, 78, then 75/76. Section 75's prerequisite step now says the
address is already there.

## 4. Also this session: v0.18 proposal

`docs/PAYMENTS_V018_DESIGN.md` — prepaid credit, ledger, spending authorisation,
payout engine, captive-portal gateway for fixed sites, and the CEMAC/operator facts
that are confirmed vs not. It is a proposal for Mike and ChatGPT, nothing built.

---

# CLAUDE_REPORT - ProkNet v0.17.7 "evidence, not a memory of having asked"

Date: 2026-09-23
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)

Version 0.17.7, build 75. ****721 Android tests, 360 server tests, all passing.****
Floor was 716 + 360; every one of those still passes, none removed.

Build 74 shipped the manifest fix and the pilot phone was **still stuck on the same
screen**. That one was mine, and it is the third time in two days that a guess stood in
for a fact.

## 1. The bug

```kotlin
canAskLocationInApp() =
    !prefs.getBoolean("location_asked") || shouldShowRequestPermissionRationale(COARSE)
```

Build 73 set `location_asked` the first time Mike tapped **Autoriser**. On build 73 the
permission was not in the manifest, so that request **went nowhere**. Build 74 then
inherited a flag saying "already asked" for a question that had never been put.

`shouldShowRequestPermissionRationale` is false for **both** "never asked" and "denied for
ever". The two together concluded the dialog was used up, so the app offered a settings
page and never tried — on the very build that had fixed the underlying problem.

## 2. The rule

**A memory of having asked is a guess. Only the answer is evidence.**

The flag is now written solely from what a real request returned: granted clears it,
denied-with-rationale clears it (Android will ask again), denied-without-rationale sets it
— which is precisely "don't ask again" and the only case where settings is the right
answer.

Nothing is assumed in advance. When uncertain, **try the dialog**: an unnecessary dialog
costs one tap; a wrongly-offered settings page costs the user the whole feature.

It is also **scoped to the build that observed it**. A new build may declare different
permissions — build 74 did — so a verdict reached under an older manifest means nothing.
That alone would have unstuck the phone.

## 3. A fifth state: `NOT_IN_BUILD`

The app now asks the package manager whether `ACCESS_COARSE_LOCATION` is in its own
manifest. When it is not, it says so plainly and shows **no button**, because there is
nothing to open — a settings page with no Position entry is a dead end, and that dead end
is exactly what left Mike tapping *Ouvrir* and finding nothing.

> Ce n'est pas votre téléphone : installez la dernière version de ProkNet.

A missing declaration **outranks every other diagnosis**: if the permission is not in the
build, no other answer can be true. A test pins that ordering across every combination.

This turns the class of build-time permission mistake that cost us a day from silence into
one sentence on screen.

## 4. The test that caught the design

The first build 75 **failed**, on my own assertion:

```
every_blocked_state_says_something_the_user_can_act_on
  FAILED: NOT_IN_BUILD needs a button
```

The rule it protects is still right — every blocked state must say something — so I split
it rather than weakening it: every blocked state has a title, message, note and diagnostic
line; a **button** appears exactly when there is something to tap, which is every state
except `NOT_IN_BUILD`. The test was encoding a real decision, so the decision got written
down.

Worth noting the build gate did its job: one failing test, no APK produced.

## 5. Numbers

- Android **721**, server **360**
- Brain schema **4**, Android DB **10**, no migration
- v0.17.7 / build 75

## 6. Hardware status

**Software-proven only.** Nothing in v0.16 or v0.17 has completed on a phone yet.

TESTING **79** is new and short: an upgrade must never leave the app unable to ask;
settings are offered only after a genuine refusal; and a build that cannot ask says so
without a button.

## 7. What this run has actually cost, honestly

Three milestones in two days to get one permission granted, because I kept explaining away
a contradiction instead of chasing it:

1. **v0.17.5** — the app never asked outside the map tab. Real, but not the blocker.
2. **v0.17.6** — the permission was capped at API 32 and did not exist on Android 13+.
   That was the blocker, and I found it only when I dumped the **built APK**.
3. **v0.17.7** — my own "have we asked" flag then kept the fixed build from ever asking.

The through-line: each time, the phone was telling me the truth and I trusted my model of
the code instead. The diagnostic said *no location permission* while the manifest plainly
declared it — that contradiction was the whole answer on day one.

---

# CLAUDE_REPORT - ProkNet v0.17.6 "the permission that did not exist"

Date: 2026-09-23
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)

Version 0.17.6, build 74. **716 Android tests, 360 server tests, all passing.**
Floor was 706 + 360; every one of those still passes, none removed.

I have to correct something I told you yesterday. v0.17.5 was not the fix. It was a good
change sitting in front of a door that could not open.

## 1. The root cause

I ran `aapt2 dump permissions` on the **built APK** rather than reading the source, and:

```
uses-permission: name='android.permission.ACCESS_COARSE_LOCATION' maxSdkVersion='32'
```

**On Android 13, 14 and 15 the location permission was not declared at all.**
`checkSelfPermission` could only ever return DENIED. `requestPermissions` could only ever
do nothing. The pilot OUKITEL is **Android 15** — so it could never have held a position,
granting it in settings would have changed nothing, and the v0.17.5 dialog I shipped
yesterday would have asked politely for a permission that does not exist.

That is the whole day of invisibility, and two of my own explanations for it were wrong
along the way. I told Mike the fix was stale, then that it was denied. It was neither: it
was undeclared.

**The cap was correct when written.** Location was needed only by the old radios — BLE
scan results on Android 8-11, the local-only hotspot on 8-12. Android 13+ replaced both
with `NEARBY_WIFI_DEVICES` and `BLUETOOTH_SCAN/neverForLocation`, so capping at API 32
avoided asking for something no longer needed. Then **v0.13 made the coarse zone the thing
the entire Network Brain runs on**, and nobody lifted the cap. A permission stopped being
optional and its declaration never caught up.

`ACCESS_COARSE_LOCATION` is now uncapped. `ACCESS_FINE_LOCATION` **keeps** its cap: a
500 m cell is all ProkNet needs, so on a modern phone it asks for coarse and nothing more
— which is exactly what the dialog promises. The request was narrowed to coarse to match.

## 2. Two more faults, behind that door

**The position stopped with the screen.** Updates were requested in the Activity's
`onForeground` and cancelled in `onBackground`; a fix lasts thirty minutes. A provider who
pocketed the phone had no zone half an hour later, so no presence, so invisible — breaking
the exact promise v0.17.3 was built on.

**A stationary phone never refreshed.** `LOCATION_MIN_DISTANCE_M` was 300 m, and the
network provider delivers nothing until the phone moves that far. A phone on a table got
**no updates at all**. The idle provider is by definition the phone that is not moving, so
the one case that mattered was the one case that could not work. Fixing only the first
would have failed the hardware test again for a different reason.

## 3. The fix, still without a background-location permission

ProkNet already runs a foreground service with a permanent notification. A service
declaring the **`location`** type may receive updates while the app is closed **on the
ordinary coarse permission** — no `ACCESS_BACKGROUND_LOCATION`, so the v0.17.5 rule holds
that nobody is ever sent into system settings.

- `FOREGROUND_SERVICE_LOCATION` declared; type is `connectedDevice|location`.
- The type is added at `startForeground` **only when the permission is held** — Android 14
  refuses a service declaring a type it lacks permission for, and that refusal would take
  the whole node down rather than just the zone.
- `CoverageEngine.updateZoneWatch(...)` owns the updates; the Activity no longer touches
  location.
- `MIN_DISTANCE` is **0**, so time is the only interval and a stationary phone stays fresh.

## 4. Only while it is needed

`core/ZoneWatch` decides purely: sharing, looking, or opted in to be woken — otherwise
nothing at all. Somebody neither offering nor looking has no position tracked. Released
when the reason goes and on `onDestroy`.

Nothing in that decision mentions the screen, and a test asserts the property across all
eight combinations: `needed == opted || sharing || looking`.

Self-correcting: every caller that can change the answer calls the static
`ProkNetService.refreshZoneWatch`, and the existing once-a-minute sweep calls it too, so a
missed call site costs sixty seconds rather than a silent regression.

## 5. Visible

The diagnostic gained a line, because "is this phone holding a position, and how long is
the fix good for?" was unanswerable from the phone:

```
zone watch: tracking: opted in to be woken | last fix il y a 3 min, good for 27 min
```

## 6. Numbers

- Android **716** (10 new in `ZoneWatchTest`), server **360**
- Brain schema **4**, Android DB **10**, no migration
- v0.17.6 / build 74

## 7. Hardware status

**Software-proven only.** Nothing in v0.16 or v0.17 has run successfully on a phone yet.

TESTING **78** is new and starts with the check that would have caught this in one look:
open the app's permission page and confirm **Location is listed at all**. On build 73 it
was not.

## 8. The lesson worth keeping

A permission whose role has changed needs its declaration re-read, not assumed. And the
source is not the artefact: this was invisible in `AndroidManifest.xml` unless you noticed
one attribute, and obvious in `aapt2 dump permissions` on the APK. I should have dumped
the built APK the moment the phone said "no location permission" while the manifest
clearly declared it — the contradiction was the clue, and I spent a day explaining it away
instead of chasing it.

## 9. Remaining limitations

- Android may still kill the foreground service on aggressive OEM battery settings. The
  app has a Battery button for that; TESTING 78g checks it.
- The zone is only as good as the network provider. Indoors with Wi-Fi off and no cell
  data, there may be no coarse fix at all — and ProkNet will now say so rather than going
  quiet.

---

# CLAUDE_REPORT - ProkNet v0.17.5 "a permission nobody was ever asked for"

Date: 2026-09-23
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)

Version 0.17.5, build 73. **706 Android tests, 360 server tests, all passing.**
Floor was 696 + 360; every one of those still passes, none removed.

The first defect real phones found in v0.17. It was not in the Brain.

## 1. What happened

The Brain went live on `https://proknet.duckdns.org` and saw **nothing** from either
phone for a day. No presence, no demand, no activation.

The OUKITEL was healthy in every visible way: Brain reachable over HTTPS, signed requests
succeeding, `syncs: 4, error: -`, notifications ON, Internet validated, Bluetooth on, and
a working local BLE session with the other phone - including a notification and a
PARTAGER that started the seller over `BLUETOOTH_BULK`. Its diagnostic said:

```
zone: z? (no location permission)
```

Every Brain call is gated on the zone, and each returns early **and silently**:

| call | guard |
|---|---|
| presence heartbeat | `ProviderPresence.of` returns null for `NO_ZONE` |
| job poll | same - no presence, no poll |
| coverage | `if (z.isEmpty()) return` |
| create demand | `if (z.isEmpty()) return ""` |
| `reconcile` | **no zone check** |

`reconcile` was the only one of six arriving at the server. That is how I found it - from
the Brain's access log, not from either phone, which is itself the problem.

## 2. Two faults, not one

**Nobody was told.** The provider saw nothing at all. The buyer saw *"Aucun Internet
disponible tout de suite"* - true, and describing the wrong problem. It reads as an empty
neighbourhood when in fact the entire network layer was switched off. No user could
diagnose that, and neither could I without server logs.

**Nobody was asked.** `askLocation()` existed and fired only on the **map** tab. A person
who uses Accueil and Gagner - everybody - is never asked once. The permission the whole
product depends on sat behind a screen with no reason to visit it.

## 3. The rule

A capability gated on a permission must ask for it **where it is used**, and say so on the
screens where its absence is felt. Not on one tab, not only in a diagnostic.

`core/LocationGate` decides purely between four states:

| state | when | the one tap |
|---|---|---|
| `NONE` | granted and switched on | nothing |
| `ASK_PERMISSION` | not granted, Android will still ask | the system dialog |
| `OPEN_SETTINGS` | dialog exhausted | the app's own permission page, opened directly |
| `TURN_ON_LOCATION` | granted, phone's switch off | the location screen, opened directly |

You asked for a yes/no rather than sending people to settings, and that is what this is.
The system dialog is the normal answer. The settings screens are used **only** when
Android will no longer show that dialog, and then they are opened by intent straight onto
the right page - never "go to Settings and find it", which most people cannot do.

Android cannot tell "never asked" from "permanently denied" - `shouldShowRequestPermission-
Rationale` is false for both - so the app remembers whether it has ever asked.
`canAskInApp` is passed in, so `LocationGate` stays pure and testable.

## 4. Where it asks now

- **GET INTERNET** - no zone, no demand, so the button could not work.
- **Turning on « Me prévenir quand quelqu'un cherche Internet »** - opting in to be woken
  is exactly when the zone starts to matter; without one the phone publishes no presence
  and is offered no buyer however willing it is.
- The map, as before.

After granting, the app **continues what the user pressed**. A permission granted with
nothing happening afterwards reads as a broken app.

## 5. Where it speaks now

`homeNote` on Accueil and `earnFootnote` on Gagner - both existing lines, no new banner.
Bluetooth-off still wins on Accueil, because that stops even the local path.

The note names the **consequence**: *"Zone inconnue : autorisez la position pour trouver
Internet près de vous."* Telling somebody a zone is unknown explains nothing; telling them
ProkNet cannot see anyone around them is what they are experiencing.

## 6. Privacy

The promise is restated inside the dialog every time - a zone to 500 m, never an exact
position, never a history of movements - because that dialog is the only place most users
will ever read it, and a permission granted without understanding is not consent. A test
asserts all three sentences are present.

## 7. Numbers

- Android tests: **706** (10 new in `LocationGateTest`)
- Server tests: **360**, unchanged
- Brain schema **4**, Android DB **10**, no migration
- Version / build: **v0.17.5 / 73**

Nothing in the Brain, payments, BLE, L2CAP or the VPN changed. This milestone changes only
what the phone asks for and what it admits to.

## 8. Hardware status

**Software-proven only** for everything in v0.16 and v0.17.

But the pilot run did prove something real, and it is worth recording: on build 72 the
**local BLE path works end to end on the two phones** - request received over BLE with its
signature verified, notification posted, PARTAGER starting the seller over
`BLUETOOTH_BULK`, and BLE self-healing twice from a stale scan unaided. v0.17.4 did not
regress the proven local flow.

TESTING **77** is new and comes **before** 75 and 76: until a phone has a zone, the Brain
sections cannot pass on a fresh install.

## 9. What is still blocking the Brain run

Three things on the phones, none of them code:

1. Location permission - this patch now asks for it properly.
2. The OUKITEL has **no Mobile Money number**, so `mayOfferPaidSharing()` is false and,
   since v0.17.4, both ready flags are false. The Brain will see it and offer it nothing.
   That is correct behaviour and is exactly TESTING 76a.
3. The OnePlus owes **49.33 CFA** against a 10 CFA new-buyer limit, which blocks the final
   paid session.

## 10. Remaining limitations

- The zone still comes from `getLastKnownLocation` and location updates are requested only
  while the app is in the foreground, so a fix expires 30 minutes after the app was last
  open. An idle provider with the app closed all night will have no zone by morning and
  will stop publishing presence. That is a **second, separate defect** with the same
  shape, and I have not fixed it here - it needs a decision about background location,
  which is a privacy question for you rather than a bug for me to quietly patch.

---

# CLAUDE_REPORT - ProkNet v0.17.4 "the offer class is the truth"

Date: 2026-09-22
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)

Version 0.17.4, build 72. **696 Android tests, 360 server tests, all passing.**
Floor was 687 + 352; every one of those still passes, none removed.

You were right, and it was worse than a labelling mistake: the local session would have
disagreed with the Brain about who pays.

## 1. The exact old FREE fallback bug

`ProviderPresence.of`, build 71:

```kotlin
val commercial = willing && mayOfferPaidSharing
val free       = willing && !mayOfferPaidSharing
offerClass     = if (mayOfferPaidSharing) COMMERCIAL else FREE
```

`mayOfferPaidSharing()` is `Trust.mayShareForMoney(payments.sellerReadiness())` — a valid
payment destination, payment verification readiness, seller not blocked. False means
**"cannot take money right now"**. Build 71 recorded **"is giving it away"**.

The reachable state: a seller opts in to earn, has working Internet, and has not finished
entering their Mobile Money number. Build 71 published them to the whole zone as FREE.

The second failure is the one that would have reached a buyer. `Pricing` has never looked
at `mayOfferPaidSharing` and never will — it prices from the *source*. So: Brain matches a
FREE buyer, provider taps PARTAGER, local pricing quotes a paid rate from their mobile
data. Promised one contract, offered another at the moment of connection.

## 2. The new truth rules

Three independent facts:

| | meaning | from |
|---|---|---|
| `willing` | you may ask this phone | opted in, validated upstream, a usable path — **v0.17.3, unchanged** |
| `commercialReady` | a paid session could be completed | `willing && intent == COMMERCIAL && mayOfferPaidSharing()` |
| `freeReady` | genuinely offered as a gift | `willing && intent == FREE` |

Capacity stays separate in `currentLoad` / `maxBuyers`, as in v0.17.3 — the Brain already
gates on it independently in the matcher and in `zone_status`, and folding it into
readiness would undo that patch's whole point.

| provider | commercialReady | freeReady | offerClass |
|---|---|---|---|
| commercial + payment ready | true | false | COMMERCIAL |
| commercial + payment NOT ready | false | false | **COMMERCIAL** |
| explicitly free source | false | true | FREE |
| explicitly free + paid-capable | false | true | FREE |

Row 4 is a policy choice worth naming: an explicit offer of free Internet is not withdrawn
by the seller also being able to charge. FREE stays explicit in both directions — never
inferred from payment failure, never revoked by payment success.

## 3. What explicitly controls freeReady

`ProviderPresence.intentOf(source)` → `Pricing.isFree(source)` → `source.free ||
kind == FREE_PUBLIC`.

That is **not a new opinion**. It is the exact predicate `Pricing.quote` has always used
to decide the buyer pays nothing, and `Pricing.autoRate` to return a rate of 0. I
extracted it into `Pricing.isFree` and both call sites now use it, so the Brain's
advertised class and the session's real price are the same function.

I did not invent a free preference. `ProkNetNode.mySource()` yields `MOBILE_DATA`,
`AUTHORIZED_HOME_WIFI` or `UNKNOWN` and never sets `free` — so **in production today every
real provider is COMMERCIAL and `freeReady` is false.** That is the conservative behaviour
you asked for. When a real free source or a "give it away" setting exists, it arrives at
`intentOf` and nowhere else.

## 4. offerClass rule

`offerClass` is the class being **offered**, always — never the class it happens to be
able to complete. A commercial seller that cannot charge stays COMMERCIAL and is excluded
by readiness, because `ready_for` returns false for every class when nothing is ready.

`priceHintInternal` follows the same rule: 0 for a free source (what `autoRate` would
produce), the seller's real rate otherwise. Leaving a per-MB figure on a free offer would
be the same mismatch in a different field.

## 5. Candidate matching behaviour

The server needed no change to `ready_for` — it was already correct and is now pinned:

- buyer FREE, provider commercial-intent + unready → `free_ready = 0` → **not a candidate**
- buyer COMMERCIAL, provider unready → all three ready flags 0 → **not a candidate**
- buyer FREE, provider explicitly free → candidate, `effective_class` FREE
- buyer COMMERCIAL, provider paid-ready → candidate, `effective_class` COMMERCIAL

**One server change was needed, and it is a consequence of this fix.** Before v0.17.4
nearly every provider carried `free_ready = 1` at some moment, so a FREE demand nearly
always found somebody. Now that FREE is explicit, a FREE demand in a zone of commercial
providers is genuinely unservable — and `serve_zone` hands the *oldest* waiting demand to
a provider that has just become useful. That unservable FREE demand would have absorbed
every heartbeat for its full fifteen minutes and starved the COMMERCIAL demands behind it.

So `oldest_waiting_demand` takes an optional predicate and `serve_zone` takes the provider
that triggered it, skipping demands that provider's `ready_for` refuses. Fairness is
unchanged — still oldest first, among the demands it can actually take — and the full
matcher still runs afterwards, so this only chooses which waiting buyer to try, never who
gets the job. Two tests cover it, both of which fail on build 71's server.

## 6. Is waiting demand reconsidered when commercial readiness becomes true?

**Yes.** `put_presence` has called `serve_zone` since v0.17.1 whenever the heartbeat
leaves the provider `ready_for(COMMERCIAL)` with capacity. Until v0.17.4 that branch was
nearly always taken for the wrong reason (`free_ready` was usually 1); now it is taken for
the right one.

`test_an_unready_seller_wakes_for_nobody_and_then_wakes_when_it_can_charge` walks the
whole sequence: willing, not sharing, payment unready → a FREE buyer does not wake it → a
COMMERCIAL buyer does not wake it → readiness becomes true → the next heartbeat returns
`served`, and the job in the provider's inbox is the COMMERCIAL demand, not the FREE one
it still cannot serve.

## 7. Numbers

- Android tests: 696
- Server tests: 360
- Brain schema: 4
- Android DB: 10
- Version / build: v0.17.4 / 72

One build-71 test changed rather than being added:
`readiness_describes_ability_and_not_the_gateway` asserted that an unpayable seller became
`freeReady` — it was asserting the bug. It now asserts the rule.

## 8. Hardware status

**Software-proven only.** Nothing in the v0.16 payment loop or the v0.17 Brain loop has
run on a phone. I am not claiming otherwise.

TESTING **76** adds the class checks on top of 75, which is unchanged and still the
acceptance run. 76a is the important one: an OUKITEL with no Mobile Money number must get
**no** notification, which is the opposite of what build 71 would have done.

## 9. Remaining limitations

- No phone can be a free provider today, because nothing sets `free` or `FREE_PUBLIC`.
  FREE matching is therefore correct but unexercised in the field.
- A seller who cannot be paid is now invisible to buyers, with no explanation on the
  buyer's side. That is right — a buyer should not be told about a provider they cannot
  use — but the *seller* only learns they are missing work from the Gagner readiness
  wording that already exists.
- `serve_zone` still tries one demand per heartbeat. A zone with many unservable demands
  and one servable one is fine; a zone with several servable demands and one provider
  still serves them one heartbeat at a time.

---

# CLAUDE_REPORT - ProkNet v0.17.3 "an idle provider is a real provider"

Date: 2026-09-22
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)

Version 0.17.3, build 71. **687 Android tests, 352 server tests, all passing.**
Floor was 663 + 336; every one of those still passes, none removed.

Both blockers you named were real, and both were confirmed in build-70 code before
anything was touched. They share a shape with the five faults of v0.17.2: every piece
was correct on its own, and the join between them made a state the product depends on
unreachable.

## 1. Provider presence no longer requires `sellOn`

Build 70, in `NetworkNode.wireControlPlane`:

```kotlin
sharingEnabled = e.optIn && node.sellOn
```

and `NetworkBrainSync.heartbeat` published nothing unless `sharingEnabled` was true.
`sellOn` means "the seller gateway is running right now", so the Brain could only ever
see a phone that was **already sharing**. The contradiction you described is exactly
what the code did:

- `sellOn = false` -> no presence -> no activation can ever arrive;
- `sellOn = true` -> presence exists -> PARTAGER refuses the job it is offered with
  `ALREADY_SHARING`.

Neither state completes the loop. Section 74 only passed because it told Mike to turn
PARTAGER on first, which is not how anybody will use ProkNet.

**Willingness and current sharing are now separate facts**, and neither is derived from
the other:

| | meaning | where it comes from |
|---|---|---|
| `willing` | you may ask this phone | opted in + validated upstream + a local path it could offer |
| `availableForActivation` | and it has room | `willing && currentLoad < maxBuyers` |
| `currentlySharing` | the gateway is up | `sellOn`, reported for truth, never a prerequisite |
| `currentLoad` | live seller sessions | 0 or 1 |

On the wire, `sharingEnabled` now carries `availableForActivation` - "this provider can
be asked", which is what the matcher needs. **No Brain schema change**: the server has
always gated separately on `sharing_enabled` and `has_capacity()`, so the honest
capacity numbers do the rest of the work.

One deliberate choice: a **busy** provider keeps heartbeating, with `currentLoad = 1`.
It is a real provider and its zone is genuinely covered; disappearing would make a
covered zone look empty. The matcher and `zone_status` both already refuse to count a
provider with no room, so it gets no second buyer.

`freeReady` and `commercialReady` now describe **ability**, not the gateway:
`commercialReady = willing && mayOfferPaidSharing()`, which is every v0.16 paid-seller
condition unchanged - valid payment destination, payment verification readiness, seller
not blocked. Nothing in v0.16 safety was weakened. Explicit opt-in is still required:
`notifyOptIn` false means no presence at all, so no phone becomes a provider silently.

**No parallel model.** The decision lives in one new pure object,
`core/ProviderPresence.kt`, which the presence hook calls - so the test and the phone
run the same function. `willing` itself is a single definition in `ProviderActivation`,
and the old v0.13 `availability()` is now expressed in terms of it, so the two cannot
drift apart. A test pins that equivalence.

## 2. The acknowledgement retry can now actually run

Build 70's `pollJobs`:

```kotlin
if (found.map { it.activationId } == jobs.map { it.activationId }) return
```

The stuck case is **by definition** the same activation coming back in the same state,
because the acceptance never arrived. So the one situation the retry existed for was the
one situation in which it never ran. A provider could stay stuck for ever while every
poll succeeded.

`pollJobs` now does, in this order:

1. decode and verify every job (unchanged - the buyer's signature, the id, the expiry);
2. **`onJobsSeen(verified)` - always, on every successful poll**;
3. only then, if the list actually moved, replace it and call `onJobs` for the UI.

The change-detection key is now `activationId + ":" + state`, so OFFERED becoming
ACCEPTED counts as movement. The poll gate was also wrong for the same reason: it
required `sharingEnabled`, which after PARTAGER is false because the phone is full. It
now gates on `willing`, so the phone that has just accepted is exactly the phone that
keeps polling.

Server side this already worked and is now pinned: `jobs_for_provider` never looked at
`sharing_enabled`, so a full provider can still reach its own jobs to reconcile them.

## 3. Transport failure is no longer read as a refusal

`answer()` returned a Boolean, and `false` meant "refused", "timed out", "no signal" and
"server had a fault" all at once. The retry then **removed the provider's accepted
opportunity** on `false` - so a provider who tapped PARTAGER in a bad spot could lose the
card, and with it the buyer's answer, because a packet dropped.

`answer()` now returns `BrainAnswer.Result`:

| | |
|---|---|
| `ACCEPTED` | HTTP 2xx, including the idempotent repeat the server answers with `duplicate: 1` |
| `RETRYABLE_FAILURE` | exception, no reply, 5xx, 408, 429, **and any 4xx whose reason we do not recognise** |
| `TERMINAL_REJECT` | HTTP 400 carrying a reason the Brain states explicitly |

Every `NetworkError` in the Brain is an HTTP 400, so the status code cannot carry this
distinction and matching on English prose would have been the third cross-language
string divergence in this project. A definitive refusal now carries a machine-readable
`reason` slug; a transient one carries none, and the phone keeps what it has.

```
ACTIVATION_UNKNOWN      the activation does not exist
ACTIVATION_NOT_YOURS    it was offered to somebody else
ACTIVATION_SETTLED      it was already declined or otherwise closed
ACTIVATION_EXPIRED      answered after its deadline
```

`server/tests/fixtures/brain_answer_reasons.txt` is one committed file that both
`test_network_ack.py` and `BrainAckTest.kt` read. If either side's list drifts, one of
the two fails. This is the v0.16.3 lesson applied before shipping rather than after.

Behaviour on each outcome:

- **retryable**: keep the opportunity, keep `accepted`, keep `brainActivationId`, keep
  selling, log quietly, try again on the next poll. Nothing is removed.
- **terminal**: drop the stale **card** - and only if no seller session is live.
  `BrainAck.outcome` refuses to drop one while a session exists: somebody is using the
  Internet right now and control-plane bookkeeping never cuts that off.
- **accepted**: record it, stop retrying.

## 4. Durable acknowledgement state

`ProviderInbox.Opportunity` gained `brainAcked`, appended as field **11**. The decoder
accepts 9 fields (build 69), 10 (build 70) and 11 (build 71); a missing field reads as
"not acknowledged", which costs one idempotent request and never a lost tap. An upgrade
that dropped the inbox would be a broken promise to a buyer who is already waiting.

`needsBrainAck()` is now `accepted && brainActivationId.isNotEmpty() && !brainAcked`,
so it stops being true for ever.

**The server's job state is the authority**, and `brainAcked` is only this phone's
memory of what that authority last said:

- server `OFFERED` + local `accepted` -> **resend**, even if `brainAcked` was true. A
  stale local "acknowledged" must not silence the one signal that says it was lost.
- server `ACCEPTED` / `LOCAL_LINK_SEEN` + local `accepted` -> **mark acknowledged**,
  send nothing (item 18).
- job absent from `/jobs` -> **nothing**. Absence is not a verdict; one failed or empty
  GET must not throw away a tap. An opportunity leaves only when the buyer's own request
  expires, is tombstoned, or the Brain states a terminal reason.

`offerFromBrain` preserves `accepted` as before and preserves `brainAcked` only when the
activation id is the same; a genuinely new activation has certainly not been
acknowledged.

This decision is also pure, in `core/BrainAck.kt`, and `NetworkNode` is a thin loop over
it - so the restart and retry tests exercise the code the phone runs.

## 5. What did not change

Brain schema **4**. Android DB **10**. No migration was added: this is bookkeeping, and
an appended inbox field is the right size of change for it.

Untouched: `DestinationClaim`, `PaymentExpectation`, `DevicePaymentReceipt`,
`ReceiptRules`, `PaymentSync`, settlement, wallet trust, anti-reinstall; BLE, peer auth,
L2CAP, VPN, contracts, checkpoints, graceful shutdown; `OFFER_TTL_MS` 75 s,
`LINK_WAIT_TTL_MS` 10 min, `DEMAND_TTL_MS` 15 min; the v0.17.2 signed-request carriage
and both ends' verification; the truthful UI wording; `zoneStatusAt`; `followTheBuyer`
and `demandZone`.

## 6. Tests

New, and each one describes something build 70 could not do:

- `an_idle_opted_in_provider_is_offered_to_the_brain` - item 21, the whole point.
- `a_provider_already_serving_somebody_stays_visible_but_gets_no_second_buyer` - item 22.
- `an_unchanged_offered_job_still_triggers_the_retry` - item 24's core.
- `the_first_acknowledgement_fails_and_the_second_one_lands` - the full sequence.
- `the_acceptance_survives_a_restart_and_is_sent_again_without_a_second_tap` - item 25.
- `a_dead_network_is_never_read_as_a_refusal` / `a_reason_the_brain_states_is_final` -
  item 26.
- `a_dead_activation_drops_a_card_and_never_a_session` - item 15.
- `inbox_files_from_build_69_and_build_70_still_load` - item 35.
- `an_idle_provider_that_never_shared_before_completes_the_whole_loop` - item 23, over
  real HTTP, provider not sharing at any point before PARTAGER, `/v1/sync` never called.
- `a_busy_provider_can_still_reach_its_own_jobs_to_reconcile_them` - the state a phone is
  in immediately after PARTAGER.
- `build_70s_own_expression_would_have_published_nothing_for_this_phone` and
  `build_70s_change_test_could_not_see_a_job_changing_state` - each recomputes build 70's
  actual expression beside the new one and asserts the two disagree, so a later
  simplification back to `optIn && sellOn` or to an id-only comparison fails here.

I checked the new server tests against build-70 `network.py` and `app.py`: 7 of the 16
fail there. The other 9 are regression pins on behaviour the server already had right.

## 7. Hardware status

**Software-proven only.** Nothing in the v0.16 payment loop or the v0.17 Brain loop has
run on a phone. I am not claiming otherwise.

TESTING section **75** is the acceptance run and replaces 74. It starts with the OUKITEL
**not sharing**, which is the state build 70 could not serve. 75b (acceptance through a
dead network) and 75c (acceptance across a restart) test the second blocker; 75g (Brain
off) still matters most.

## 8. Remaining limitations

- `maxBuyers` is 1. A provider serves one buyer at a time; a second buyer in a zone with
  one provider waits.
- `TERMINAL_REASONS` is deliberately short. A Brain that starts refusing acceptances for
  a reason not on that list will have phones retrying an idempotent request until the
  activation expires - noisy, never wrong.
- The reconciliation runs on the job poll, so the worst-case recovery delay after a
  restart is one poll interval, not instant.
- Nothing here improves the case where the provider's phone is genuinely offline for
  longer than the demand's 15-minute window. The demand expires and the buyer asks again.

---

# CLAUDE_REPORT - ProkNet v0.17.2 "the distant loop, actually closed"

Date: 2026-09-22
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)

Version 0.17.2, build 70. **663 Android tests, 336 server tests, all passing.**
Floor was 643 + 314; every one of those still passes, none removed.

All five faults you named were real. I confirmed each in the code before touching it.

## 1. The distant-job solution

A demand now **carries the buyer's own signed `NetRequest`**, and a job hands it to the
assigned provider. Not an unsigned replacement - `NetRequest.encodeLine` of the exact
object the buyer signed, stored byte-for-byte, never reconstructed or re-signed.

`onJobs` no longer looks anything up in the gossip store. It takes the request the job
carried, folds it into the ordinary request store, and offers it - so eligibility, the
card and tombstone-on-cancel behave exactly as for a gossiped request. No parallel
Brain-only admission model.

Ordering fixed too: `originate` stores and persists the signed request, and only then
starts the upload. There is no window where the demand exists before its local object
does.

## 2. Binding and verification

**Server**, in `check_request_line`, before anything is stored: parses, signature
verifies, `r.id == demandId`, request open, not expired, zone matches - and
`protocol.node_id(r.origin_pub)` equals the **authenticated caller**.

That identity check is the one that carries the weight. `verify_request` only proves
*somebody* signed the line, so without it a valid request belonging to A could be
uploaded by B. It compares the full node id, not `origin_short`, because a short id is a
prefix and prefixes collide. A request signed by the wrong person is refused and
**nothing is stored** - asserted.

**Phone**, in `pollJobs`: decode, verify the buyer's signature, `r.id == job.demandId`,
not expired. Fail closed - an unverifiable job produces no opportunity, no notification
and no card.

## 3. Brain schema

**3 -> 4.** One column, `network_demand.request_line`, as numbered additive migration 4.

Tested against a real schema-3 database holding a settlement, presence, a demand, an
**accepted** activation with its rendezvous window, and events: every row survives, the
window is intact, migration 4 is recorded once, and the upgraded table matches a fresh
one. A demand created by build 69 has no line, and a job from it carries an empty one -
which the phone treats as unusable rather than crashing on.

**Android DB stays at 10.** `ProviderInbox` is file-persisted; the format gained a tenth
field and a nine-field file from build 69 still loads, so an upgrade cannot drop
opportunities a provider has already accepted.

## 4. ProviderInbox activation id

`Opportunity.brainActivationId`, empty for local. `offerFromBrain` sets it and - this
matters for the retry - **does not reset `accepted`** when the same job is re-offered.

## 5. PARTAGER acknowledgement and retry

`acceptOpportunity` never called `networkSync.answer`. The activation stayed OFFERED,
and the buyer waited on somebody who had already agreed. Now: re-check eligibility,
start the seller, **persist the acceptance**, then send on the IO executor - never the
main thread.

Persisted before sending, deliberately: a dying process or a dead network must not lose
the tap.

If the send fails, the job returns OFFERED while the inbox says accepted, and
`ackAcceptedBrainJobs` sends it again. The server route is idempotent; the provider
never taps twice. It survives a restart because both the acceptance and the activation
id are on disk. If the Brain says the activation is genuinely over, the card is dropped -
but a running **session** is never touched.

## 6. Home truth wording

| | |
|---|---|
| Brain GREEN alone | "Un fournisseur est actif dans votre zone" |
| map GREEN | "Fournisseur actif dans cette zone" |
| a source this phone can use | "Internet disponible maintenant" |
| transport up | "Connecté" |
| Brain says CONNECTED, transport has not | "Connexion en cours…" |

`homeState` takes `localUsableNow` explicitly instead of inferring availability from a
colour. Four of my own older tests failed on this change, which is exactly right - they
asserted the untruthful wording.

## 7. Coverage-specific freshness

`zoneStatusAt`, set **only** when `GET /v1/network/coverage` actually returns. Home and
the map age against that, not `lastOk`. A heartbeat, demand poll, job poll, payment sync
or settlement sync can no longer keep a stale GREEN alive.

## 8. Automatic zone move

`followTheBuyer()` compares the local coarse zone with `demandZone` - the zone the
**server** last confirmed, tracked from create/reconcile/poll/move rather than guessed -
and posts a move when they differ. Only on a real change, so a heartbeat does not become
a move. Never to an unknown zone: losing a fix is not the same as having moved, and the
last known zone is kept.

## 9. Totals and artefacts

| | |
|---|---|
| Android tests | **663** (floor 643) |
| Server tests | **336** (floor 314) |
| All previous | green, none removed |
| Brain schema | **4** |
| Android DB | **10** |
| Version / build | **0.17.2 / 70** |
| APK SHA256 | `54bbddcd7743030fda94b7d146747137e48b910caf82315905d6b3358ff6ca14` |

Backup re-validated on a schema-4 database holding a settlement and a live demand:
`integrity_check ok`, 22 tables, schema 4, both present, `request_line` present. All five
VPS scripts re-parsed.

## 10. Hardware status

- **Hardware-proven, unchanged:** Bluetooth/L2CAP Internet, VPN browsing, provider
  activation and recovery, pricing and contracts, graceful stops, the signed checkpoint.
- **Software-proven only:** the Mobile Money payment stack, and all of v0.17.x including
  this release.
- **Still unproven:** real MTN/Airtel message parsing, and the whole Brain loop on actual
  phones. v0.17.2 is software-proven only until you run TESTING 74.

## 11. Remaining limitations

- The most important Android test (`the_provider_has_never_seen_this_buyer_before`)
  exercises the verification and inbox path with an empty request store, but not the live
  HTTP poll - `NetworkBrainSync` needs a socket. The server half of the same journey is
  covered end to end over real HTTP.
- No JVM test kills one Brain subsystem to watch the others; they need a `Context`.
- A build-69 demand has no signed request, so a job from one is unusable until that
  demand expires. Nothing crashes, and new demands always carry it.
- Sponsored providers are modelled, nothing produces them. Capacity is 0 or 1.
- TLS hostname still pending; the Brain binds to loopback and warns otherwise.

## 12. Definition of done

Met on the software side, in your words: a provider that has never received the buyer's
legacy request receives a self-contained, cryptographically verified activation; PARTAGER
durably acknowledges that exact activation and the buyer reaches PROVIDER_ACCEPTED;
Brain-only GREEN never masquerades as a working local path; coverage freshness cannot be
refreshed by unrelated syncs; a live demand follows real zone changes; and every v0.16
payment and security test is green.

Stopping v0.17 code changes here unless the hardware test finds a real defect.

---

# CLAUDE_REPORT - ProkNet v0.17.1 "the Brain, visibly"

Date: 2026-09-21
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)

Version 0.17.1, build 69. **643 Android tests, 314 server tests, all passing.**
Floor was 611 + 287; every one of those still passes.

## 1. What was incomplete in v0.17.0

My own report said it: the Home status model existed and no view read it, the map never
received a Brain colour, and the Activité model wrote nothing. Mike would have installed
build 68 and seen nothing new. Four behaviours were also missing or wrong:

- an accepted provider got the 75-second window it had to *answer* in, not a window to
  actually reach the buyer;
- matching happened only when a demand was created, so a provider appearing later served
  nobody;
- no fairness rule, so a long wait could be jumped;
- no policy for either party walking to a different zone.

## 2. Home - actual screen integration

A card in `activity_main.xml` (`homeNet`, `homeNetTitle`, `homeNetSub`) captioned
**INTERNET AUTOUR DE VOUS**, drawn by `renderNetworkCard()` from `refreshHome`, which runs
on every tick - so it follows the demand, the activation, the zone, local discovery, the
transport and the sync result without a screen change.

The decision is `NetworkAccess.homeState`, a pure function. `MainActivity` needs a
`Context`, so the screen gathers facts and the decision lives where a test can run it.
That is the only way "PROVIDER_ACCEPTED shows *Un fournisseur se prépare*" is verified
rather than asserted about a string constant.

The truth rule holds and is tested: the Brain reporting CONNECTED - which means *somebody*
told it a connection happened, not that this phone has one - gives "Connexion en cours…",
never "Connecté". A test loops every server status with `internetUp = true` and asserts
none can override the transport.

## 3. Map - actual screen integration

`refreshMap` now merges. `NetworkAccess.mergeZone` puts local observation above the Brain,
and **discards** a Brain answer older than five minutes rather than downgrading it - there
is no honest middle ground between "somebody was there five minutes ago" and "somebody is
there". Nothing known is RED; no demo cells. The cell the phone stands in carries the
merged colour, so map and Home cannot disagree. The label under it is the plain sentence.

## 4. Activité - actual persistence and integration

`core/NetworkHistory.kt`: a durable file, one row per real transition, rendered into the
existing `ActivityUi` rows. Dedup is the design - a poll that learns nothing returns the
same object, so the screen does not repaint. A technical kind writes nothing.

**Android DB stays at 10.** This is coordination history, not signed evidence; it holds
nothing verifiable, and `RequestGossip`/`ProviderInbox` already persist this way. A
migration with nothing to migrate would have been worse than none.

## 5. Gagner - verified, not assumed

I read the path. A Brain job becomes a `ProviderInbox.Opportunity` with `Source.BRAIN`,
which the v0.13.3 notification and Gagner card already render, and dismissing the
notification does not remove the opportunity - `ProviderInbox` only drops it on accept,
decline or expiry. `NetworkAccess.providerTitle/providerText` carry the DEMANDE PROCHE /
PRÊT À PARTAGER wording and are unit-tested.

## 6. Accepted-activation wait window

`OFFER_TTL_MS` 75 s and `LINK_WAIT_TTL_MS` 10 min are separate; `deadline_of` is the one
place that knows which applies. The accepted window is `min(demand expiry, acceptedAt +
wait)`, so it cannot outlive its request.

**`DEMAND_TTL_MS` went 10 -> 15 minutes**, and this is the interesting bit: with both
numbers equal an accepted provider swallowed the whole request, so item 19's fallback was
unreachable. A test asserted it and failed, which is how I found out.

## 7. Fallback after an accepted timeout

The window closes -> activation EXPIRED -> demand back to SEARCHING -> next eligible
provider, and the one who agreed and did not arrive is not asked again. If no time or
attempts remain the demand simply ends, which the buyer sees as "Personne ne peut partager
pour le moment".

## 8. Local provider wins, and the demand closes

`localConnectionWon` reports the result **first** (so the matcher still learns what
worked) then cancels the demand. Wired from `MainActivity` at the moment the buyer goes
ONLINE, whichever provider carried it - the Brain is not a reservation system.

## 9. Brain status reporting stays best-effort

`onLocalLink` runs on the node's IO executor and can only log. Nothing in the reporting
path can end a session. Item 41's property - Brain disappears mid-session, session
continues - holds because no transport code waits on any of it.

## 10. Zone change policy

**Buyer:** the same demand follows them. Same id, age and attempt count, because
recreating would reset their queue position and burn the cooldown; any offer left behind
closes at once; the matcher runs in the new zone. `POST /v1/network/demand/move`.

**Provider:** an unanswered offer is expired on a zone change. An accepted one is kept,
because they may be walking towards the buyer and that is what the window is for.

## 11. Capacity and upstream

`currentLoad` is `if (gateway.session != null) 1 else 0` and `maxBuyers` is 1, because the
gateway accepts one session - no multi-buyer capability is advertised. The server clamps
impossible values. A heartbeat arriving back under capacity picks up waiting work.
`upstreamAvailable` uses `accessPath != NONE`, the same readiness the sharing code uses,
not "the Wi-Fi radio is on". `sponsoredReady` is always false; nothing fakes a sponsor.

## 12. Late matching and fairness

A presence heartbeat serves the oldest waiting demand in that zone with attempts left.
Tests: provider appears later; provider appears in another zone and serves nobody;
capacity returns; oldest-first with two waiting buyers; a second provider serves the
second buyer.

## 13. Subsystem independence

`runBrainSubsystems` runs in a `finally`, outside the `/v1/sync` try, with settlements,
payments and the control plane each in their own `try/catch`. On the server they are
separate route families and separate modules.

**Honest limit:** this is verified by structure and by reading, not by a unit test - the
three subsystems need a `Context` to instantiate, so there is no JVM test that kills one
and watches the others. The server-side separation is covered by the route tests.

## 14. Schema and migrations

| | |
|---|---|
| Brain schema | **2 -> 3**, numbered migration, additive `ALTER TABLE ADD COLUMN` |
| Android DB | **10, unchanged** |

I had first put the new columns into schema 2's `CREATE TABLE`. That is the v0.16.1
mistake on the server - `CREATE TABLE IF NOT EXISTS` does nothing to an existing table -
so no Brain in the field would ever have got them. A test now builds a real schema-2
database with an accepted activation in it, upgrades, and checks the row survives, both
migrations are recorded once, and the shape matches a fresh install.

## 15. HTTPS, VPS, backup

- **HTTPS: unchanged and unclaimed.** Plain HTTP, binds to loopback, warns if bound
  elsewhere. Software ready; a real distant-phone test needs a reachable HTTPS endpoint
  that does not exist yet. Nothing insecure is hard-coded.
- All five scripts re-checked with the PowerShell parser: install, start, stop, status,
  backup.
- **Backup re-validated on a schema-3 database** holding both a settlement and a live
  network demand: `integrity_check = ok`, 22 tables, schema 3, settlement present,
  `network_demand` present, `link_deadline` column present.

## 16. Totals and artefacts

| | |
|---|---|
| Android tests | **643** (floor 611) |
| Server tests | **314** (floor 287) |
| All previous | green, unmodified |
| Version / build | **0.17.1 / 69** |
| APK SHA256 | `447e203ea4461960836326e3568a91932567ff609332c1f51cce916c925947f2` |

## 17. Hardware status

- **Hardware-proven, unchanged:** Bluetooth/L2CAP Internet, VPN browsing, provider
  activation and recovery, pricing and contracts, graceful stops, the signed final
  checkpoint.
- **Software-proven only:** the whole Mobile Money payment stack, and all of v0.17 and
  v0.17.1.
- **Still unproven:** real MTN/Airtel message parsing, and every part of the Brain loop on
  actual phones. **v0.17.1 is software-proven only** until you run TESTING 73.

## 18. Remaining limitations

- No JVM test kills one Brain subsystem to watch the others (section 13).
- Android restart uses server reconciliation rather than a local table: if the Brain is
  unreachable at start, the phone does not know it had a demand until it can ask.
- Sponsored providers are modelled and nothing produces them.
- Capacity is 0 or 1.
- A buyer's zone move needs something to call `move_demand`; the route and the policy are
  there and tested, but the phone does not yet detect its own zone change and call it.
- TLS hostname pending.

## 19. Definition of done

Met on the software side. The loop is visible in Home, Map, Gagner and Activité; an
accepted provider has ten minutes rather than seventy-five seconds; local networking
supersedes and cancels Brain coordination; the Brain can disappear without touching an
active or local session; and every v0.16 payment and security test is green.

Not moving to v0.18. TESTING 73 is the gate.

---

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
