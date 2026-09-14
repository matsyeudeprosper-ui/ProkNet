# Decision needed: a seller phone that cannot share while it is on Wi-Fi

Date: 2026-09-14
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **DECIDED 2026-09-14 by the architect: option B. Built and released
as v0.9.6.** This document is kept as the record of why.

## 1. What happened on the phones

v0.9.5 test, two phones:

- Seller `prok-24e480e6` (OUKITEL), sharing, **connected to a Wi-Fi
  router**: every purchase was refused in about 2 s with "the provider
  could not start its Wi-Fi hotspot".
- Same seller, **Wi-Fi switched off, sharing its mobile data**: the buyer
  connected and browsed. Mike confirmed it works.

## 2. Why

To serve a customer, the seller creates a local-only Wi-Fi hotspot. On most
chipsets the hotspot must run on the same channel as the Wi-Fi network the
phone is already joined to. When the router's channel cannot be matched
(typically a 5 GHz or DFS channel) Android answers `ERROR_NO_CHANNEL` and
the hotspot never starts.

**The phone is not the problem, the combination is.** In the 3-phone relay
test the SAME phone hosted `AndroidShare_5999` on `wlan2` while it was
joined to another phone's hotspot on `wlan0` at 2412 MHz (2.4 GHz channel
1), and its Relay Lab reports `isStaApConcurrencySupported: true`. It can
do both at once; it cannot do both with this router.

**There is no app-side workaround.** `WifiManager.startLocalOnlyHotspot`
gives no control over band or channel; the `SoftApConfiguration` band
setters are system API only (we already hit that in v0.5). The app cannot
ask for 2.4 GHz.

## 3. Why this matters beyond one test

The locked coverage model lists home Wi-Fi, shop Wi-Fi and public Wi-Fi
among the six source types. "Sellers must switch Wi-Fi off" would mean:

- only **mobile-data** sellers can ever serve a customer on such phones;
- the three Wi-Fi source classes become undeliverable on them, however
  cheap or free that Internet is;
- the coverage engine would plan routes through providers that cannot
  actually host, so a GREEN zone could be a lie.

The buyer side is unaffected: a buyer always needs Wi-Fi on to join.

## 4. Options

**A. Document only.** Tell sellers on mobile data to switch Wi-Fi off.
Zero code. The Wi-Fi source classes stay broken on affected phones and the
user only finds out when a customer fails.

**B. Probe, warn, remember (recommended, small).**
1. When a seller turns sharing on, the phone tries to create the hotspot
   once, right then, and closes it. Success or failure is known before any
   customer exists.
2. On failure while connected to Wi-Fi, the sharing screen says so in one
   sentence: turn Wi-Fi off to share, or connect to a 2.4 GHz network.
3. The result is remembered per phone (and per network when we know its
   SSID), so the warning appears immediately the next time.
4. `CoverageNode` gains a `canShareWhileOnWifi` capability; a phone that
   cannot is not planned as a Wi-Fi reseller, only as a mobile-data
   provider. The planner already has the field pattern for this.

Cost: roughly a day. All of it is testable (the probe result is a pure
input to the planner, the wording is in ProductState).

**C. A different hotspot mechanism.** Wi-Fi Direct (P2P group owner)
coexists with a station connection more freely on many phones, and the
Relay Lab already reports whether P2P is supported. This is a transport
change, not a UI change, and per the v0.9 instruction we do not rewrite the
project around an unproven alternative inside one milestone. It deserves
its own probe milestone, with the 3-phone relay retest still pending.

## 5. Recommendation

Do **B** as v0.9.6. Keep **C** as a separate, later experiment with its own
capability probe and its own phone test, decided after the 3-phone relay
retest (TESTING.md section 19) is done.

## 6. One cheap test for Mike, whatever is decided

If the router has a separate 2.4 GHz network, connect the seller phone to
that one and share **without** turning Wi-Fi off. If it works, the rule is
"2.4 GHz only", not "Wi-Fi off", and option B's message should say that.

## 7. Questions for the architect

1. Approve option B as v0.9.6?
2. Should `canShareWhileOnWifi` enter the coverage model now, or wait until
   real provider data exists?
3. Does a phone that cannot share while on Wi-Fi still advertise its Wi-Fi
   upstream as a source (for a later relay or a mover), or is it demoted to
   mobile-data-only provider?

## 8. Decision and what was built (2026-09-14)

Option B approved, with three clarifications from the architect:

1. The source stays in the connectivity map as an observed candidate even
   when the phone that sees it cannot resell it. Implemented as
   `observedSources` / `deliverableSources` / `blockedSources`; only the
   PHONE is removed as a deliverer, never the network.
2. No global "turn Wi-Fi off" advice. The consumer message names this
   phone and this network, and says mobile data still works.
3. The upstream band, frequency and channel are recorded with every
   result, so a correlation with 5 GHz / DFS can be checked later.

Wi-Fi Direct stays unimplemented and is documented as the next transport
experiment. See `CLAUDE_REPORT.md` for the v0.9.6 build.
