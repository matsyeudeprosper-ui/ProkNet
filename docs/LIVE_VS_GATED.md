# ProkNet v0.19 — live versus gated (the precise register)

"Live" = deployed on the pilot Brain AND switched on for the stated cohort by an operator
with a dated reason. "Built, gated" = the software exists, is tested and deployed, and the
switch is OFF (or needs a decision record) — the app shows the server's own sentence for
it. "Blocked" = an external fact the code cannot produce. Nothing is called live because
it compiled.

Brain deployed: **0.19.0, schema 7** (2026-09-26 14:15 UTC-5); app build 83. Verified live: `/v1/flags/status?city=Brazzaville` says `enabled: false` for all nine functions; the venue index has 0 venues; real payments OFF.

| Function | Cohort | Needs decision record(s) | Status on 2026-09-26 |
|---|---|---|---|
| `public_map` (free finder, offline pack, walking guidance) | everyone in Brazzaville | none | **built, gated — switch OFF until the first consenting venues are published; the operator opens it in the console** |
| `direct_free` (a free direct session, no Brain needed) | everyone | none | **built, gated — switch OFF; opens with the map** |
| `market_browse` | everyone | none | **built, gated — switch OFF until moderation is staffed** |
| `market_paid_publish` | listed sellers | `merchant_collection_terms` | built, gated |
| `scout_rewards` | listed scouts | none (budget must be cleared in the fund) | built, gated |
| `sponsored_delivery` | listed venues/sponsors | `upstream_permission` | built, gated |
| `customer_paid_delivery` | pilot identities | `payment_classification`, `upstream_permission` | built, gated |
| `provider_payout` | pilot providers | `payment_classification` | built, gated |
| `relay_payout` | pilot relays | `payment_classification` | built, gated |
| Real-money top-ups (`PROK_PAYMENTS_LIVE` + `PROK_PILOT_IDS`) | pilot identities | counsel + operator wallet terms | **OFF** |

Blocked (external, with the evidence needed):
- Payment classification of Prok credit, manual merchant collection and payouts: a written
  opinion from Congolese counsel / a payment partner citing CEMAC 04/18 and Law 29-2019 —
  recorded as `payment_classification` with document, person, date.
- Upstream permission per source: the venue's or operator's written terms allowing
  redistribution (a residential Canalbox line does NOT qualify: CGV 2022 art. 5.1) —
  recorded as `upstream_permission` per venue.
- ARPCE position on reselling access: a letter or published regime reference — `arpce`.
- Operator wallet terms (personal-tier limits, business wallet requirements): from MTN
  Congo and Airtel Congo — noted under `merchant_collection_terms`.

This file is updated by the operator on every switch; the console's history is the source.
