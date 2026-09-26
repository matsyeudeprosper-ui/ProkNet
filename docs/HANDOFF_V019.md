# ProkNet v0.19.3 — handoff (the launch contract build, as a radar)

Date: 2026-09-26. From Claude (implementation) to ChatGPT (architect) and Mike (owner).
Product contract: `ProkNet_Product_Vision_2026-09-26-1.md` §1–§10. Gap map:
`docs/LAUNCH_GAP_MAP.md`. Register: `docs/LIVE_VS_GATED.md`. Runbook: `docs/RUNBOOK.md`.

## 1. What you have

| Item | Value |
|---|---|
| Signed release APK | `dist/ProkNet-release.apk` — attached to GitHub release **v0.19.3** (build 86: same features as 0.19.0; the home is a radar - Internet, free Wi-Fi and articles orbit the globe, each opens a sliding card; publishing is photo + title + price) |
| APK SHA-256 | `fd0d91ae994f9f094e0f63891d52aece53c5c6a02299271fe2f795ec42452ce8` |
| Signing certificate SHA-256 | `a7e12b2f97af64637a2297e9621fd7a6a5d70ae936567fb030a0d6f7ec3796d3` (keystore `C:\ProkNetKeys\proknet-release.jks`, alias `proknet`, never in git) |
| versionCode / versionName | 86 / 0.19.3 (`applicationId net.prok.proknet.lab`, unchanged so it installs over 0.18.x) |
| Debug APK (lab only) | `dist/ProkNetLab-debug.apk`, SHA-256 `31680743a1153c1b7a07e3102f529d9c7055516b408264d1f0e0254783e35a9d` |
| Brain | **deployed** `https://proknet.duckdns.org` → `{"ok": true, "version": "0.19.0", "schema": 7}` (PID 16368 on 8081 behind Caddy) |
| Rollback point | `C:\ProkNetBrain\backups\brain-20260926-141455.db` (schema 6, taken before the upgrade); `deploy/brain/restore.ps1 -From <file>` + the v0.18.2 checkout |
| Offline map pack | `/v1/map/brazzaville/pack`, 3,027,460 bytes (3.03 MB, contract says < 50 MB), SHA-256 `268e9dfdc1ead4e723ba1828e158720c5648c9adda10fc9e6c555b69f8f611ef`, 133,784 nodes / 149,202 walking edges from Geofabrik OSM (ODbL attribution in the manifest); verified through the public URL byte-for-byte |
| Tests | Brain **596** (`python -m unittest discover -s tests -t .` in `server/`), Android **835** JVM (`build.ps1 -Release`) — both green on this commit; the build refuses to produce an APK when a test fails |
| Shared fixtures (Python writes, Kotlin reads) | `charging_v3.json`, `quote_examples.json`, `place_status.txt`, `market_prices.txt`, `relay_offer_states.txt`, `crosslang.json`, `withdrawal_states.txt`, `msisdn_hash.txt`, `brain_answer_reasons.txt` |

## 2. What is live, what is gated, what is blocked

**Live (deployed and reachable today, no switch needed):**
- Brain 0.19.0 with every v0.19 module mounted (`flags`, `places`, `market`, `quotes`, `fund`, `jobs`, `map`); public GETs answer: `/v1/flags/status`, `/v1/places/index`, `/v1/market/prices`, `/v1/market/browse`, `/v1/map/brazzaville/manifest|pack`.
- The market price list at the contract's §10 numbers: publish 100 F / 30 d, boost 200 F / 7 d, package 400 F / 5 slots / 90 d, storefront 2,000 F / 30 d / 30 listings, one free voucher per new seller (100 first).
- The v0.18 money machinery (ledger, holds, withdrawals on request ≥ 1,000 F, treasury queue, refunds, reversals, reconciliation) — unchanged, still with real payments **OFF**.

**Built and gated (switch OFF; the app shows the Brain's own sentence):** every one of the
nine functions — `public_map`, `market_browse`, `market_paid_publish`, `scout_rewards`,
`direct_free`, `sponsored_delivery`, `customer_paid_delivery`, `provider_payout`,
`relay_payout`. Verified live: `/v1/flags/status?city=Brazzaville` returns `enabled: false`
for all nine. The ones tied to money or upstream permission also need a **decision
record** (`payment_classification`, `upstream_permission`, `merchant_collection_terms`)
before the switch will take — the console refuses otherwise.

**Nothing is called live because it compiled.** No function has been switched on. The
operator console is reachable by nobody until `PROK_OPERATOR_IDS` is set (§4).

**Blocked (external — the exact evidence needed is in §6).**

## 3. What v0.19.0 built (by contract section)

| Contract | Built | Where |
|---|---|---|
| §3 Free finder + offline Brazzaville map | venues/sightings/checks/reports/claims, freshness labels 5 min / 24 h / 7 d, non-exhaustive note, walking guidance (A* on the pack, straight-line fallback), no location needed to browse | `server/brain/places.py`, `mappack.py`, `tools/build_map_pack.py`; `core/PlacesView.kt`, `ProkMap.kt`, `WalkRouter.kt`, `MapPackState.kt`; `ui/PlacesActivity.kt`, `OfflineMapView.kt` |
| §3 Scout rewards | 10 F sighting / 3 F check, caps 30 F/day, 100 F/month, 5,000 F/month global, 90-day expiry, promo book separate from cash, appeals | `places.py` rewards + `ledger.py` `promo:*` accounts |
| §4 Owner onboarding + signed quotes | controls-network / validated / attestation checks, free–earn–sponsored choice, HMAC-signed quote (0.25 F per decimal MB; 80/10/10 direct; 64/20/10/6 with a relay), accept/decline only — no tariff field | `quotes.py`; `core/OwnerQuoteView.kt`; `ui/OwnerOnboardingActivity.kt` |
| §4 Relay jobs | CARRY (from the settlement), STAY (20 F per verified 30-min block, max 4/day, reachability probes), MOVE (100 F fixed, reserved first); pending is never cash; settings without a tariff | `jobs.py`; `core/RelayOffersView.kt`; `ui/RelayOffersActivity.kt` |
| §4 Free Internet Fund | 40 % of net market revenue allocated, campaigns/reservations, `fund:*` books | `fund.py` |
| §5 Prok Market | listings with 24-h unique-reference invoices, review before publish, reject never leaves the seller charged, boosts/packages/vouchers, in-app chat with blocks and reports, photos ≤ 300 KB × 5 | `market.py`; `core/MarketView.kt`; `ui/MarketActivity.kt`, `MarketModerationActivity.kt` |
| §5 Manual payment verification | treasury confirms an invoice only on exact reference + exact amount + never-used operator transaction id; "paste a message" matches or sends to review; never on a number alone | `market.py` `confirm_invoice` / `match_message`; the v0.18 ledger path for credit |
| §7 Feature flags | per city / cohort / function, dated switch rows with reasons, required decision records, console | `flags.py`; `core/FlagsView.kt`; `ui/OperatorActivity.kt` |
| §9 Charging | one rule on all three sides (`costFor`: zero when nothing came down), BLE advert v3 in centimes/MB | `core/Market.kt`, `evidence.py`, `ble/*` |
| Ops | migration 7 (upgrade from 6 tested with rows intact), signed release build, map pack tooling | `db.py`, `build.ps1`, `tests/test_flags.py` `SchemaSevenUpgradeTest` |

## 4. Exact configuration still needed (machine environment variables on the VPS, then `stop.ps1` / `start.ps1`)

| Variable | Set to | Why |
|---|---|---|
| `PROK_OPERATOR_IDS` | the 32-hex node id of the phone that verifies venues / moderates / flips switches | nobody can open the console until this exists |
| `PROK_TREASURY_IDS` | the treasury phone's id (a different person from the operator, on purpose) | confirms market invoices, moves withdrawals |
| `PROK_TEST_IDS` | pilot phones that may receive audited test credit | dry runs without money |
| `PROK_PILOT_IDS` | **leave empty** until counsel answers | real-money allowlist |
| `PROK_PAYMENTS_LIVE` | **leave unset (off)** | real top-ups |
| `PROK_QUOTE_SECRET` | optional; a random value already lives in `C:\ProkNetKeys\quote_secret.txt` | signs quotes |

Pilot phone ids the Brain has already seen: `24e480e608179779229aa30d80f5f697`,
`0f7d57b3057d068e57a55e1983e146c9`. Mike decides which is which.

## 5. Real-device acceptance — NOT RUN

I have no phone, SIM or ADB on this machine. Every line of `docs/TESTING.md` §82
(launch acceptance) and the v0.18 §80/§81 lines is **NOT RUN**. Specifically unproven on
hardware: the offline map rendering and walking route on a real screen; the BLE v3
advert between two phones; photo upload from a phone camera; the operator console
against a phone's real identity; any MTN/Airtel message through the parser (it has
still never seen a real one). Do not call any of these passed until Mike reports.

## 6. External blockers (finish-all-else done; these need a fact only a human can produce)

| Blocker | Gates | Evidence that unblocks it |
|---|---|---|
| Payment classification | `customer_paid_delivery`, `provider_payout`, `relay_payout`, `PROK_PILOT_IDS` | written opinion from Congolese counsel or a licensed partner on Prok credit + manual collection under CEMAC 04/18 and Law 29-2019 → recorded in the console as `payment_classification` (document, person, date) |
| Upstream permission per source | `sponsored_delivery`, paid delivery from any venue | the venue's / ISP's written terms allowing redistribution; a residential Canalbox line does **not** qualify (CGV 2022 art. 5.1) → `upstream_permission` per venue |
| ARPCE position | reselling access at all | a letter or a published regime reference → `arpce` |
| Merchant collection terms | `market_paid_publish` | MTN Congo / Airtel Congo terms for the treasury wallet (personal-tier limits, business wallet) → `merchant_collection_terms` |
| First consenting venues | `public_map` usefully on | at least a handful of venues verified on foot with owner consent recorded (`/v1/places/ops/consent`) — today the index has **0 venues** |
| Moderation staffed | `market_browse`, `market_paid_publish` | a named operator id in `PROK_OPERATOR_IDS` who will clear the review queue daily |

## 7. Walkthrough for the first day (after §4 is set)

1. Install `ProkNet-release.apk` on both pilot phones (it replaces 0.18.x, data kept).
2. Operator phone: Lab → OPÉRATEUR. The console lists the nine switches, all OFF, and the
   decision records (none). Record the external decisions you actually hold; leave the
   rest blank — a blank one keeps its switch closed.
3. Walk to 3–5 venues, submit them from "Vérification des lieux", record consent, publish.
   Then open `public_map` and `direct_free` for Brazzaville with a reason. The home
   screen's "Gratuit près de moi" fills in on the next refresh; without network the phone
   uses its cached index and the offline pack.
4. Market: open `market_browse` only when someone is assigned to the review queue.
   Paid publishing stays closed until `merchant_collection_terms` is recorded.
5. Money switches stay closed until §6 line 1 is recorded. Nothing in the app can move
   real money before that: `PROK_PAYMENTS_LIVE` is off and `PROK_PILOT_IDS` is empty.

## 8. What this release is NOT

- Not a proof that any screen works on a phone (§5).
- Not an approval to move money: real payments OFF, no MTN/Airtel API anywhere.
- Not a complete city map: the pack is roads/paths for walking; the venue index starts empty.
- Not a promise of speed: the owner screen says so in its own words.
