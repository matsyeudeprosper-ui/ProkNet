# CLAUDE_REPORT - ProkNet v0.14.0 "human pricing and the profit engine"

Date: 2026-09-20
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 285/285 Android tests and 11/11 server tests pass,
released. The hardware-proven v0.13.3 activation path is untouched; the
budget session rides on top of it. TESTING 58 is the pending hardware test.**

## 1. Pricing architecture

`core/Pricing.kt`, pure, integer centimes only — no floating point touches
money anywhere. It answers one question, *is there a deal that is good for
both sides and what is its exact rate*, and refuses rather than invent one.
Inputs: buyer budget, source kind and declared cost, seller policy, Prok
fee, cost class, sponsor budget. Outputs: admissible, reason, internal
centime rate, max billable bytes, expected gross / fee / seller net /
source cost / seller profit, payer, subsidy, free.

The identity, asserted for every source, policy and budget:

```
buyer charge = Prok fee + seller's source cost + seller's profit
```

## 2. Source-cost model

FREE_PUBLIC, SPONSORED, PROK_FUNDED cost 0. AUTHORIZED_HOME_WIFI and
AUTHORIZED_SHOP_WIFI cost what the owner declares (default 0: a fixed
line's marginal cost really is near zero). MOBILE_DATA costs what the
bundle cost, from `mobileCostPerMb(paidCentimes, mb)` — the seller says
"1 000 CFA for 2 Go", never a CFA/MB. UNKNOWN is not sellable. An
undeclared mobile bundle uses a conservative assumed cost, never zero, so
silence cannot make a seller sell at a loss.

## 3. Seller auto-price rules

`sellerFloorPerMb = sourceCost + safety + earning`, `earning =
max(flatPerPolicy, (sourceCost + safety) x marginPerPolicy)`. CHEAPER /
BALANCED / EARN_MORE map to 1 / 2 / 4 CFing per MB flat and 15 / 30 / 60 %
proportional; safety is 10 % of source cost. `rateForFloor` grosses the
floor up by the fee so the fee never eats the floor. The advert carries
`ceil(rate/100)` whole CFA — no wire change, and the on-air price can never
sit below the floor. All constants live in `Pricing.Policy` and are tested,
not scattered.

## 4. Buyer budget rules

The budget is a ceiling, never an amount to spend. 25 / 50 / 100 CFA,
remembered, default 50, confirmed once before the first ever paid session.
`quoteForOffer(budget, rate)` checks only the buyer's own business: is the
budget above the minimum session, and does it buy at least one useful MB at
this price? Free sources quote 0 and are not rationed by money.

## 5. Contract changes

`Market.Contract` version 2 adds `rateCentimesPerMb`,
`buyerBudgetCentimes`, `maxBillableBytes`, `sourceCostBasisCentimesPerMb`,
`sellerPolicy`, `pricingMode`; `LEN_V2 = 84`. Both phones sign it.
`costFor(bytes)` bills v2 at the exact centime rate, capped at the byte
ceiling and at the signed budget; v1 bills exactly as before. `decode`
accepts either length, so every stored contract still reads.
`acceptableProposal` gained `myFloorCentimesPerMb`: for a budget contract
the seller checks that the rate leaves it its floor after the fee and that
the byte ceiling costs no more than the signed budget — it signs
economics, not a price list. All five cost call sites now go through
`costFor`.

## 6. Ledger changes

None destructive. Amounts were already centimes; historic entries and v1
contracts are untouched and still display as they always did (the two
remaining `priceLine` uses are exactly those legacy paths). New sessions
carry their economics inside the signed contract, so a session can be
audited from its own terms.

## 7. Network Brain changes

The provider heartbeat already carries a price; it now carries the
automatic one. Matching and admission are unchanged and still refuse a plan
whose delivery cost exceeds the customer ceiling. No raw pricing is exposed
to the consumer UI. Server tests: 11, unchanged, green.

## 8. Normal buyer UX

Internet tab: "Combien voulez-vous dépenser au maximum ?", 25 / 50 / 100,
the chosen one highlighted, and the honest note that ProkNet looks for the
cheapest option and pays nothing if Internet is free. One tap on the sphere
uses the remembered budget. During a session: "Vous avez dépensé 34 CFA sur
votre budget de 50 CFA", plus "Il vous reste environ X CFA" only near the
end. Offers read "Jusqu'à 50 CFA" or "Gratuit". No CFA/MB.

## 9. Normal seller UX

Gagner has no price box. "ProkNet fixe automatiquement un prix rentable
pour vous", three choices (Moins cher / Équilibré / Gagner plus, default
Équilibré), an optional "Mon forfait" (J'ai payé … Pour … Mo), a bounded
earning estimate shown only when genuinely calculable, and COMMENCER.
While sharing: "Vous avez gagné 27 CFA".

## 10. Tests added (12, total 285 Android + 11 server)

Commercial quotes never exceed the budget and always pay the seller, with
the accounting identity checked for every source x policy x budget; the
floor covers source cost, safety and a real earning, and rises with the
policy; a mobile seller is never pushed below its bundle, an unaffordable
budget is refused instead of sold at a loss, and an undeclared bundle is
protected conservatively; free stays free whatever the budget; byte
ceilings move the right way with budget and rate; the buyer pays for what
was used, zero for nothing, and is capped at the ceiling; money does not
drift over a thousand incremental billings; sponsored and growth sessions
name their payer and respect their budgets while commercial ones are never
silently subsidised; an unsellable source is never priced; every consumer
sentence is CFA and mentions no MB or centimes; a v2 contract signs the
ceiling, bills at the exact rate, cannot bill past the budget and survives
the wire; v1 contracts still decode and bill exactly as before.

## 11. Version / build / commit / hash

Build 55, versionName 0.14.0, SHA256 `dbf76da2b1bbdfe70472577a5b2fa0e3d4d033e78071a8e72283ee7620b48f43`.
Commit `ba2b910` on `main`; this report on top.
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.14.0

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```

## 12. Known limitations

- Not yet run on the phones (TESTING 58); v0.13.3's three-cycle and
  Bluetooth-recovery tests are also still outstanding.
- The advertised price is whole CFA rounded up, so the buyer pays slightly
  more than the exact internal floor; billing itself is exact to the
  centime.
- The buyer cannot see the seller's source cost, so its quote trusts the
  advertised price; the seller re-checks its own floor before signing.
- No speed or time classes: the budget controls money, bytes control
  accounting. "50 CFA = 30 minutes" is deliberately not claimed.
- Sponsored and growth-subsidy sessions are modelled, priced and tested but
  no sponsor exists yet to pay for one.
- MoMo settlement is not started; the ledger is still person-to-person.
- 3-phone relay remains modelled, not executed.

## 13. The exact Mike test

TESTING 58. OUKITEL: Gagner shows no price to type — leave Équilibré,
COMMENCER when asked. OnePlus: budget card shows 50 CFA, tap the sphere,
confirm the budget once, then the normal v0.13.3 flow to CONNECTÉ and
Wikipedia. A few CFA of browsing is enough. Check: the OnePlus says "Vous
avez dépensé N CFA sur votre budget de 50 CFA" with N small and never a
CFA/MB figure, the OUKITEL says "Vous avez gagné …" above zero, and COPY
NETWORK shows `live contract: v2 BUDGET`.
