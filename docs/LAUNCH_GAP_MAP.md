# Launch contract (2026-09-26) mapped against v0.18.2 — built / partial / absent / blocked

Date: 2026-09-26. Claude's map of the launch product contract against the code at tag
`v0.18.2` (build 82, Brain schema 6), as section 9 of the contract requires before any
change. **blocked** = needs a permission, a classification or money the code cannot
produce; the software is built anyway behind a switch and says so.

| # | Contract item | Status at v0.18.2 | What the milestone does |
|---|---|---|---|
| 1 | Home answers Free here / Free nearby / Internet can reach you / No verified route | **partial** — `NetworkAccess.homeState` has RED/YELLOW/GREEN from Brain coverage + local link; no "free here" from a verified public place, no "walk toward" | rebuilt on the places index + live peers (§3) |
| 2 | Source classes; never connect to arbitrary Wi-Fi, no passwords, no consent inferred | **partial** — `CoverageEngine` records hashed sightings of scanned SSIDs; `mySource()` distinguishes FREE_PUBLIC / AUTHORIZED_HOME_WIFI / MOBILE_DATA by the owner's own declaration; no venue/owner consent record, no upstream-terms record | `places` module: venue, entrance, hours, access rule, consent record, upstream-terms attestation; scanner output stays a coarse unverified sighting |
| 3 | Town-wide free map, search by neighbourhood/address, filters, counts, last update, "not exhaustive" | **absent** — the Carte is a 3×3…9×9 grid of coverage cells around the phone | places index per city with neighbourhoods, filters, counts and last-update; served signed, cached offline |
| 4 | Two layers: free places (exact approved entrance) vs coverage/sightings (coarse) | **absent** (only the coarse layer exists) | both layers, visually and verbally separated |
| 5 | Freshness labels: Working now (5 min) / Recently verified (24 h, age shown) / Older check (24 h–7 d) / out of default list at 7 d / Reported not verified / Unavailable; two failures demote; owner removal hides | **absent** | `places.status(now)` pure rule, tested on the boundaries; identical words on server and phone (fixture) |
| 6 | Walking distance/ETA along real streets; Guide me; offline map + route; approximate straight-line when no graph | **absent** | offline Brazzaville pedestrian pack (OSM data, not tiles), on-phone A*, ETA, fallback labelled approximate |
| 7 | No-data bootstrap: cached town index, offline essentials, local peer discovery, free route without Brain | **partial** — local BLE/Wi-Fi discovery and free sessions already need no Brain; no cached town index | index + pack cached; "last refreshed" shown |
| 8 | Scout observations + rewards (10 / 3 FCFA, 30/day, 100/month, 5,000/month global, 90-day expiry, dedup, second independent check) | **absent** — sightings exist, rewards do not | `rewards` rules on the server with the exact caps; promo-credit book separate from customer credit; appeal within 30 days |
| 9 | Owner onboarding: Share my Wi-Fi, checks, consent, free / earn / sponsored choice, no tariff typing, quote shown | **partial** — seller policy CHEAPER/BALANCED/EARN_MORE and `mayOfferPaidSharing`; the owner never sees a quote or a split | onboarding wizard against the quote engine; owner accepts or declines a signed offer |
| 10 | End-to-end quote: customer X per 100 MB, owner Y, relay Z, Prok W; examples at 200 MB / 1 GB; expiry | **absent** | `quotes` module: versioned rate config with effective dates and change log; signed quotes |
| 11 | Centime pricing across advert, contract, both phones, Brain | **partial** — contract carries centimes; the BLE advert rounds to whole FCFA/MB (`Pricing.advertisedPriceCfa`) so a 0.25 FCFA/MB target is impossible | advert carries centimes per MB; discovery, contract and settlement agree to the centime |
| 12 | Splits: direct 80/10/10, one relay 64/20/10/6; withdraw at 1,000 FCFA | **partial** — relay 10 % of gross fixed in code; fee 5 %; withdraw minimum 500 | splits come from the versioned config and the signed quote; withdrawal minimum from config |
| 13 | Free source + paid relay = the customer buys delivery; Free — sponsored when a sponsor pays | **absent** — a free source contract is free end to end | delivery quotes; sponsor reservation from the fund; the settlement names the paying party |
| 14 | Relay offers: carry now / stay available (20 FCFA per verified 30-min block, 4/day) / move to help (100 FCFA reserved first) | **absent** | server-side job engine with reservations and proofs; app screens to accept/decline; **gated** |
| 15 | Sponsor campaigns with caps, labels, aggregate reporting | **absent** | `fund` module: campaigns, reservations, settlements, caps; **gated** |
| 16 | Prok Market: paid posting (100/30 d, 5 photos), boost (200/7 d/zone), 5-slot package (400/90 d), storefront (2,000/30 d, 30 slots), voucher (first 100 sellers), moderation, chat, report/block, seller history | **absent** | `market` module + app screens; invoices with unique references verified by the treasurer; market book separate from the Internet ledger |
| 17 | Free Internet Fund: 40 % of net collected market/ad revenue; reservations from cleared budget; separate ledger | **absent** | `fund` book in the ledger with the 40 % rule as a dated config entry |
| 18 | Feature flags per city/cohort/function (public_map, market_browse, market_paid_publish, scout_rewards, direct_free, sponsored_delivery, customer_paid_delivery, provider_payout, relay_payout) | **partial** — `PROK_PAYMENTS_LIVE`, `PROK_PILOT_IDS`, `PROK_TEST_IDS`, `PROK_TREASURY_IDS` | `flags` module: per city, per function, allowlisted cohorts, effective dates, change log; operator can close new sessions/listings while completing accepted obligations |
| 19 | Treasurer: top-ups, withdrawals, reconciliation, refunds, identity recovery, two-person exceptions | **built** (v0.18.x) except two-person control for exceptions | second approver on reversals and refunds above a threshold |
| 20 | Manual invoice verification: unique reference, expected amount, 24 h expiry, immutable state, operator receipt matched, one payment → one invoice | **partial** — top-up intents with amount tags; no invoices | `market` invoices reuse the treasury phone's message path with a reference, plus the tag as fallback |
| 21 | Operator console: dashboard, flags, moderation queue, venue verification, invoice queue, incident status, runbook | **partial** — Treasury screen only | operator screens in the app for operator identities; runbook in docs |
| 22 | Signed production APK, distribution and update path | **absent** — debug builds only | release keystore outside git; `assembleRelease`; SHA published; in-app "new version" notice from `/health` |
| 23 | Privacy: minimal location publication, raw identifiers private, retention, deletion requests, French terms | **partial** — hashed SSIDs, coarse zones, logging rules | data-retention sweep for trails and radio ids; French terms/privacy text in-app; deletion request flow (manual, audited) |
| 24 | Monitoring/alerts/backups | **partial** — backups, status script, daily reconcile in-app | health alert to Telegram-less environments: a daily operator digest endpoint + log warnings; backups unchanged |
| 25 | Written upstream permission per source; residential Canalbox terms forbid sharing | **blocked** | provider onboarding records the attestation and the document; a source without it cannot be listed as deliverable |
| 26 | Legal/payment classification (CEMAC 04/18, Law 29-2019, ARPCE) | **blocked** | the money switches stay off; the operator console records decision, document, person, date |
| 27 | Real-device acceptance (TESTING 80/81 + §8 of the contract) | **not run** | procedures written; trusted customers run them |

**One coherent milestone = v0.19.0**: everything marked "the milestone does", with the
gated functions built and switched off, and the free finder + market ready to serve real
users once the operator roles are staffed.
