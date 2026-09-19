# CLAUDE_REPORT - ProkNet v0.11.0 "the consumer path"

Date: 2026-09-19
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 222/222 automated tests pass, released. Integration and UX
over the proven stack; nothing under it changed. Hardware regression of the
normal UI pending (TESTING section 49).**

## 0. Product status truth

Two-phone home-Wi-Fi sharing over Bluetooth: **HARDWARE PROVEN on this
device pair** (v0.10.2, 2026-09-19). OUKITEL Android 15 seller on the
Freebox, OnePlus Android 14 buyer. Bluetooth L2CAP authenticated, 256 KB
PASS both directions, contract and session, VPN, DNS, HTTPS (TLS 1.3,
HTTP 200), Chrome Wikipedia, accounting and checkpoint. Recorded in the
README ("Proven"), ARCHITECTURE and TESTING. Not generalised to every
Android device.

## 1. Normal seller flow

Open ProkNet -> Partager mon Internet -> price -> Commencer le partage.
`BulkPlan.sellerAccessPath` picks the link (home Wi-Fi + Bluetooth ->
Bluetooth; mobile data -> hotspot; home Wi-Fi without Bluetooth -> NONE,
"Activez le Bluetooth : il sert à connecter vos clients sur ce réseau
Wi-Fi."). The card reads "Vous partagez votre Internet / Disponible pour
les personnes à proximité", then "Source : Wi-Fi (Freebox) ✅" and the
price. With a customer: "Quelqu'un utilise votre Internet", 1 client, data,
earnings. The hotspot warnings (Wi-Fi on, Location on, "this network
refuses a hotspot") no longer appear on the Bluetooth path
(`ProductState.sellerNeedsHotspotWarnings`). No protocol word on screen.

## 2. Normal buyer flow

Open ProkNet -> Internet -> the offer card (price, signal, source "Wi-Fi")
-> Connecter. Then, by itself: Connexion… -> Vérification de la
connexion… -> Démarrage d'Internet… -> Internet connecté ✅ with price,
data used, cost, and Arrêter. `ProductState.buyerNeedsWifi` stops CONNECT
from demanding Wi-Fi on the customer for a Bluetooth provider.

## 3. Automatic transport rules

- authenticated link exists -> reuse (`BuyPath.LINK_UP`)
- provider on home Wi-Fi advertising Bluetooth -> Bluetooth bulk
- provider on mobile data -> LocalOnlyHotspot
- Wi-Fi Direct: never in the normal flow (no normal provider advertises a
  group since v0.10.1; `startP2pFallback` refuses without the developer
  flag). A test walks every combination and asserts WIFI_DIRECT never
  comes out.

## 4. Stale P2P cleanup

`ProkNetNode.start` calls `P2pLink.clearStaleGroup()` unless the developer
P2P lab is on. It runs the Android-confirmed STOP walk (cancel connect,
stop discovery, close sockets, remove group), which the lifecycle runs
"always, even when this phone believes it is idle". Only the p2p
interface is touched; wlan0 stays on the Freebox. Log line:
`consumer start: removing any stale Wi-Fi Direct group ... (wlan0
untouched)`. Cleanup only.

## 5. Consumer state mapping

`ProductState.Buyer` gained CHECKING (fed by `ProkNetNode.linkChecking()`:
link up, quick check running). Mapping from the real engine strings:

| engine | user reads |
|---|---|
| REQUESTED / OFFERED ("FINDING") | Recherche d'un fournisseur… |
| CONNECTING / AUTH | Connexion… |
| UP + check running | Vérification de la connexion… |
| AGREEING / CONNECTING / TUNNEL UP without VPN | Démarrage d'Internet… |
| TUNNEL UP + VPN, INTERNET OK | Internet connecté ✅ |
| INTERNET LOST / failure | Connexion perdue + one sentence |

Failures: probe -> "La connexion à proximité est trop faible. Rapprochez
les téléphones et réessayez."; Bluetooth off -> "Le Bluetooth est éteint.
Activez-le pour vous connecter."; provider lost upstream -> "Le
fournisseur a perdu son Internet."; unmapped -> "Impossible de se
connecter à ce fournisseur. Réessayez.". The technical text stays under
Développeur.

`BulkPlan.afterProbe(verdict)` is now the rule the node applies after the
check: BIDIRECTIONAL -> start the contract; else end the attempt. The
VPN request is the existing `onSessionUp -> vpnRequested` path (proved on
hardware in v0.10.2); MainActivity now explains it in one sentence with
CONTINUER before Android's prompt, and later sessions start without
asking once Android has the consent.

## 6. Files changed

`core/ProductState.kt` (CHECKING, four words, plain failures,
buyerNeedsWifi, sellerNeedsHotspotWarnings, sellerSourceLine),
`core/BulkPlan.kt` (afterProbe), `ble/ProkNetNode.kt` (linkChecking,
afterProbe, clearStaleGroup at start), `transport/P2pLink.kt`
(clearStaleGroup), `ui/MainActivity.kt` (checking state, Bluetooth-aware
seller warnings and source line, Wi-Fi gate on CONNECT, VPN explanation),
`res/values/strings.xml` (5 strings, "Internet connecté ✅"),
`res/layout/activity_lab.xml` ("BT diagnostics"), tests
`ProductStateTest`, `P2pAdmissionTest`, `BulkPlanTest`, `build.gradle.kts`.

## 7. Tests (222, +4)

- home Wi-Fi seller + Bluetooth -> BLUETOOTH_BULK; mobile data -> HOTSPOT
  (provider side, v0.10.1 rule re-asserted next to the buyer side)
- buyer sees a Bluetooth-capable Wi-Fi seller -> BLUETOOTH_BULK with no
  preference; mobile-data seller -> HOTSPOT; existing link -> LINK_UP
- every combination of bulk/wifi/linkUp in the production topology ->
  never WIFI_DIRECT
- afterProbe: BIDIRECTIONAL -> START_CONTRACT, PARTIAL / NO_DATA / NOT_RUN
  -> END_ATTEMPT
- the real buyPhase strings map to Connexion / Vérification / Démarrage /
  Connecté; no title or hint contains L2CAP, PSM, BULK, GATT, probe, 256;
  the VPN hint names the OK to press
- the four plain failure sentences
- buyerNeedsWifi, sellerNeedsHotspotWarnings, sellerSourceLine (no protocol
  word)
- developer diagnostics: BtLabTextTest (3) still pass; the lab screen is
  untouched
Three old pins were updated on purpose: SECURING now reads "Démarrage
d'Internet…", the generic failure sentence changed, and the probe hint no
longer contains the word Bluetooth.

## 8. Build

Build 44, versionName 0.11.0,
SHA256 `a4f0ede38fef1f0ae4956b3a2c17904fdcff5dd3ac538a481e417d6e3af43558`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.11.0
Commit `ef6e624` on `main`; this report on top.

## 9. The exact simple hardware test

OUKITEL: open ProkNet, Partager mon Internet, Commencer le partage.
OnePlus: open ProkNet, Internet, tap the offer, Connecter. Wait for
"Internet connecté ✅", open Wikipedia. No Developer screen. Then stop both
and run it a second time: the VPN must not ask again. TESTING section 49.

## 10. Not changed

BluetoothBulkTransport, L2CAP, StreamLink, the signed handshake, Tunnel,
Gateway, VPN, contracts and accounting, the v0.10.2 sequential probe (its
size and timing untouched: gather timings over several sessions first),
the mobile-data hotspot path, the developer labs. No 3-phone relay work.
