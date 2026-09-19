# CLAUDE_REPORT - ProkNet v0.12.0 "one tap"

Date: 2026-09-19
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 236/236 automated tests pass, released. The proven stack is
untouched. The one-tap flow on the phones is the pending hardware regression
(TESTING section 50).**

## 1. Version / build / commit / hash

Build 45, versionName 0.12.0, SHA256 `69c0931c4dd516d1a0c4111e31e2aa808b8fe0d9419343bb0e49e967304f27b8`.
Commit `0833ef1` on `main`; this report on top.
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.12.0

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```

## 2. The one-tap home

Accueil opens on "Besoin d'Internet ?", a 220 dp round button OBTENIR
INTERNET, "Gratuit si possible / Le moins cher sinon", and a coverage hint.
Tap: "Recherche d'Internet…" (2.5 s) then "Recherche du meilleur
Internet…"; the engine decides every second; a chosen ProkNet provider is
bought through `node.buy(peer)`, which applies the proven transport rules;
then the v0.11 words (Connexion… / Vérification… / Démarrage… / Internet
connecté ✅) with price, used, cost and ARRÊTER. Nothing usable after 15 s
-> "Aucun Internet disponible tout de suite. / ProkNet continue de chercher
autour de vous." and the search goes on while the app is open; a source
that appears is connected automatically. A connected, validated Wi-Fi ->
"Vous avez déjà Internet par le Wi-Fi X" and no session. The user never
picks a transport, a provider or a network; the Internet tab keeps the
manual list underneath.

## 3. Automatic source-selection rules (`core/GetInternet.kt`)

Blocked: not reachable now, stale (> 90 s), not authorized, not validated,
above the ceiling, unreliable. Ranked: usable free first; then
`price + (1 - reliability) x 3 CFA/MB + setup / 20 MB`; the last successful
source gets a 0.4 CFA tie-break only; then signal. Reasons: "free validated
reachable source", "cheapest validated reachable source", "more reliable
than the cheaper X for a little more", "the last one that worked", "this
phone already has validated Internet over Wi-Fi", "no source usable right
now (why); keep searching", "no source known". Actions CONNECT_NOW /
REQUEST_NETWORK / WAIT / NONE exist; only CONNECT_NOW executes. Reliability
comes from the source's history (validation ratio, how often seen,
successes) and the signal now.

## 4. Coverage data model (`core/CoverageModel.kt`)

Source: id (`prok:<id>` / `wifi:<hash16(BSSID)>`), kind, name, first / last
seen, observations, best / last rssi, price (centimes, -1 unknown), last
validation + history, trust (OPEN_REUSABLE / AUTHORIZED_PRIVATE /
CAPTIVE_PORTAL / UNKNOWN / NOT_ALLOWED), security, zones seen (max 20),
last zone, selling, successes. Observation: source, time, rssi, zone,
validated?, price, selling. Cell: zoneId, lastObservedAt, direct and
potential source counts, best known price, status, confidence. One Freebox
seen by eight sightings is one source; one provider seen repeatedly is one
source (tested).

## 5. Observation engine (`node/CoverageEngine.kt`)

Sources of sightings: the node's peer list (BLE, already scanning), the
connected Wi-Fi with Android's validated / captive-portal verdict, cached
scan results only with fine location and only when the app is in front. No
`startScan` anywhere. One observation per source per minute. Saves 20 s
after a change and on background. Location: last known + network-provider
updates every 5 min / 300 m in the foreground only.

## 6. Map behaviour

Carte draws the 9 x 9 cells around the phone's cell (green available,
yellow can be organised, grey not covered), the phone as a dot, "around
you" with the status word, the known sources with kind, name, status, age
and price, and a dialog per source or per cell ("Dernière observation : il
y a 8 min / Type / Prix"). Without a position it draws one "Autour de vous"
cell. It is a schematic map, not tiles: it answers "where has ProkNet
actually seen Internet" with what this phone knows.

## 7. Privacy model

Wi-Fi ids are SHA-256 hashes (16 hex) of the BSSID; the BSSID is never
stored. Zones are 0.005 degree cells (~500 m). No point is stored, no
history of where the person was, no password ever seen. Coarse location is
asked once, on Carte, with: "ProkNet utilise votre position approximative
(à 500 m près) pour se souvenir où Internet a été vu. Jamais votre
position exacte, jamais d'historique de vos déplacements." Everything
stays on the phone; the codec is designed so aggregated sync can be added
without changing the records.

## 8. Internet request model (`core/InternetRequest.kt`)

id, createdAt, zone, desiredMb (0 = flexible), desiredMinutes (0 =
flexible), maxPriceCentimesPerMb (-1 = automatic), urgency (NOW / SOON /
FLEXIBLE), state, sourceId, updatedAt, note. States IDLE, SEARCHING,
DIRECT_SOURCE_FOUND, NETWORK_NEEDED, CONNECTING, ONLINE, FAILED,
CANCELLED. The last 50 persist.

## 9. Planner / economic rules

Hops with roles PROVIDER / ANCHOR / RELAY / MOVER / COURIER; a plan carries
the source, hops, expected price, expected reliability, movement required,
subsidy required, cost class (COMMERCIAL / SPONSORED / GROWTH_SUBSIDY) and
delivery cost. `admissible`: commercial -> delivery cost <= customer
ceiling (a 300 CFA delivery of a 50 CFA session is refused, tested);
sponsored -> within the sponsor budget; growth -> the excess within the
growth budget. `rank`: no movement first, then cheapest, then most
reliable. v0.12 builds only the direct plan.

## 10. Persistence

`filesDir/coverage.v1.txt`, a versioned line codec (sources, the last 500
observations, the last 50 requests, the last successful source), tolerant
to a damaged line. Round-trip and damage are tested. Transient transport
objects are never stored. The last successful source is used as the
tie-break above.

## 11. Files changed

New: `core/CoverageModel.kt`, `core/GetInternet.kt`,
`core/InternetRequest.kt`, `node/CoverageEngine.kt`,
`ui/CoverageMapView.kt`, `res/drawable/bg_big_button.xml`,
`res/drawable/ic_map.xml`, `test/.../GetInternetTest.kt` (14 tests).
Rewritten: `ui/MainActivity.kt`, `res/layout/activity_main.xml`.
Patched: `ProkNetApp.kt` (owns the engine), `core/ProductState.kt`
(`sellerHeadline`), `ui/LabActivity.kt` + `res/layout/activity_lab.xml`
(COPY COVERAGE, coverage block in the diagnostic), `res/values/strings.xml`
(+55), `test/.../ProductStateTest.kt`, `build.gradle.kts`.

## 12. Tests (236, +14)

Free validated reachable beats paid; an open SSID merely detected is not
free Internet; cheap unreachable / stale does not beat reachable; more
reliable beats slightly cheaper, cheapest wins when both reliable; last
successful wins ties only; no candidate -> NONE / REQUEST_NETWORK ->
NETWORK_NEEDED with the truthful sentence; one tap chooses the proven
Bluetooth provider (the OUKITEL offer) and the transport rule underneath is
BLUETOOTH_BULK, a mobile-data provider stays on HOTSPOT, a stopped provider
is not reachable, an existing link is reused; same Wi-Fi / same provider
stay one source; detection is not authorization, stale is never GREEN,
YELLOW for recent-not-reachable, RED for nothing; cells count real
sources, words hide colours; zones are cells not points; the one-tap
request asks nothing and walks to ONLINE; the commercial planner refuses
300-for-50, sponsored / growth allow it explicitly, movement ranks last;
coverage and requests survive a reload, a damaged line loses only itself;
the seller headline and buyer words carry no transport word. All v0.10 /
v0.11 transport tests unchanged.

## 13. Known limitations (honest)

- Carte is schematic cells, not map tiles; no place names.
- Wi-Fi scan results are read only when fine location is granted; by
  default the app asks coarse location only, so most phones observe the
  connected Wi-Fi and ProkNet peers, not every network around.
- Location updates and Wi-Fi observation run only while the app is in
  front; the BLE peer sightings run whenever the node runs.
- NETWORK_NEEDED keeps searching only while the app is open.
- "Partagé / gagné aujourd'hui" use the phone's local day.
- A connected validated Wi-Fi ends the request as "already online"; no
  ProkNet session is started, by design.
- No backend, no sync, no relay execution, no mover payments.
- The one-tap flow has not yet been run on the phones.

## 14. The exact simple hardware test

OUKITEL: open ProkNet, Gagner, COMMENCER À PARTAGER.
OnePlus: mobile data OFF, open ProkNet, tap OBTENIR INTERNET, wait for
"Internet connecté ✅", open Wikipedia. Then open Carte on both and send
screenshots if anything looks wrong. TESTING section 50.
