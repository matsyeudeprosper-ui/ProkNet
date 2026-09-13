# CLAUDE_REPORT - ProkNet v0.8.0 "Consumer product UI"

Date: 2026-09-13
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 87/87 automated tests pass, released, NOT yet tested on phones**

v0.7.1 phone result (before this milestone): PASSED. Mutually signed
settlement on both phones, session b33fedf4, checkpoint #5, 11.47 MB ->
57.35 CFA, fee 2.87, seller net 54.48, the unsigned #6 ignored on both.

## 1. What v0.8 is

A consumer screen on top of the unchanged v0.7.1 engine. Not one line of
the BLE, Wi-Fi, crypto, tunnel, VPN, gateway or marketplace code changed;
the diff is `ui/`, `res/`, one new pure file `core/ProductState.kt`, its
test, the manifest (theme + LabActivity) and the version.

## 2. Screens (bottom navigation: Home / Internet / Earn / Activity / Profile)

- **Home**: Prok mark and name, On/Off chip, connection card (buyer or
  seller state in plain words), Nearby (people in range) and Internet
  offers counters, session cost while buying or earnings while sharing, two
  big actions GET INTERNET / SHARE INTERNET.
- **Internet, Get Internet**: offer cards "Internet available · <name> /
  5 CFA / MB / Good signal · Mobile data · Checked" from `node.offers()`
  (engine ranking). Tap -> confirmation (price, minimum, limit, signal,
  upstream, fee) -> CONNECT. Then the engine runs Wi-Fi link -> contract ->
  tunnel -> VPN by itself and the screen shows "Finding provider…" ->
  "Connecting…" -> "Securing connection…" -> "Starting Internet…" ->
  "Connected", with a hint line for the two Android dialogs. Active view:
  Data used, Cost so far, price · duration · via <name>, STOP.
- **Internet, Share Internet**: price per MB, minimum charge, max data per
  customer, START SHARING. Active view: "You're sharing Internet / Available
  to people nearby" or "Someone is using your Internet", terms line, Data
  shared, Earned, customer line, STOP SHARING.
- **Earn**: total earned (received minus Prok fees, from the ledger),
  sessions shared, "Help ProkNet - Let your phone help nearby users when
  possible" switch (= RELAY) with what it has carried so far.
- **Activity**: To pay / To receive / Prok fees as an accounting view ("Prok
  does not hold your money"), then session cards (who, date, data, cost,
  payment word). Tap: date/time, data, duration, price, final cost, Prok fee
  (seller), payment status; MARK AS PAID / RECEIVED / DISPUTE when pending.
  No hashes, signatures or IDs.
- **Profile**: name (change), own short Prok ID, "Keep Prok running" switch,
  background-battery permission, **Developer / Diagnostics -> Open developer
  screen** = the complete v0.7.1 lab screen (`LabActivity`, same layout and
  code, class renamed): Start/Stop, Wi-Fi link, Big test, Send file,
  BUY/SELL/RELAY, Ledger, History, dev provide/use/Net test, peers with
  IDs, raw state, log, COPY LOG, COPY DIAG.

Light and dark themes (`values-night`). Cards, chips, 56 dp primary
buttons, five vector icons, no AndroidX.

## 3. The one translation layer

`core/ProductState` (pure, 4 tests): engine inputs -> `Buyer` /
`Seller` states -> titles and hints; `cfaShort` ("57 CFA"), `data`
("11.5 MB"), `duration`, `signalWord`, `upstreamWord`, price / minimum /
limit lines, payment words, `wallet()` (to pay / to receive / Prok fees over
pending ledger entries). The test walks the whole buyer setup from IDLE to
ONLINE and LOST through the real phase / state strings, and asserts that no
seller title or hint contains "upstream", "gateway" or "provider ready".
`MainActivity` never formats an engine state itself.

## 4. Start-up without a Start button

If every permission is already granted and Bluetooth is on, opening the app
starts the foreground service. Otherwise the first GET INTERNET / SHARE
INTERNET / "Keep Prok running" walks the same permission -> notification ->
Bluetooth flow as the lab screen, then performs the tapped action. VPN
consent is requested by whichever screen is in front (both set
`node.vpnRequested` in onStart).

## 5. Tests and build

87 tests (`ProductStateTest` 4 new), all green, APK gated. Build 12,
versionName 0.8.0, label "Prok", 1.1 MB,
SHA256 `55cef51f392b46044e5027a9c91d2e266dc9b9a1776bdcfae80062f9cec31721`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.8.0
Commit `a6b8fd609d9e6ec953e0e80e2738dee0ebe08d2d` on `main`; this report on top.

## 6. Preserved

Everything: same package, same database, same identity and keys, same
notification, same protocol. A v0.7.1 phone and a v0.8.0 phone
interoperate. The lab screen is byte-for-byte the v0.7.1 screen apart from
the class name and layout name.

## 7. Decisions ChatGPT may want to change

- The confirmation screen shows "Minimum: 0 CFA" and "Limit: set by the
  provider": the BLE advert carries only the price, the minimum and limit
  arrive in the signed contract. Showing them before CONNECT would need one
  more byte pair in the scan response (cheap, but a protocol change, so not
  done in a UI milestone).
- Home's "Nearby" counts every Prok phone in range, not only sellers.
- After a lost connection the card stays with "Connection lost / Move
  closer..." until the user taps Close; nothing reconnects by itself.
- Earn's relay line shows carried messages; there is no relay reward yet,
  the text says so.

## 8. Retest

`docs/TESTING.md` section 17 (consumer flow) with its checklist; section 15
still describes the engine underneath.
