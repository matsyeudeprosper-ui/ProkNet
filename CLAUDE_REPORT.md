# CLAUDE_REPORT - ProkNet v0.9.3 "fail fast, say why"

Date: 2026-09-14
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 108/108 automated tests pass, released, not yet retested on phones**

## 1. What Mike reported

Buying from a phone that was sharing: the screen stayed on "Recherche d un
fournisseur..." for two minutes, then "Connexion perdue / Reessayez".

Reading the two screenshots against the code: the buyer never reached
CONNECTING, so the Android join dialog never appeared and no WIFI_OFFER
ever arrived. 15:48 -> 15:50 is exactly `STEP_TIMEOUT_MS` (120 s), so the
link state machine timed out in REQUESTING. The hint was "Reessayez",
which is what `lostHint("")` returns: the tunnel had no error because the
failure happened one layer below, in the Wi-Fi link, and the screen never
saw that reason.

I cannot say from here WHICH side failed (the seller log has it). The most
likely cause by far is the seller side: the hotspot is created by the phone
that SHARES, and `startLocalOnlyHotspot` needs Wi-Fi on and Location on. A
seller sharing its mobile data very often has Wi-Fi switched off.

## 2. What v0.9.3 changes

- **Per-step patience** (`LinkState.stepTimeoutMs`, pure + tested):
  REQUESTING 60 s, HOSTING 45 s, HANDSHAKE 30 s; 120 s only for the two
  steps where a human must tap Android dialog. `timeoutReason(step)` turns
  the expired step into a sentence instead of "step timeout in DOWN".
- **The host says it cannot host.** On any `startLocalOnlyHotspot` failure
  (callback, SecurityException, exception) the host now sends WIFI_CANCEL
  over BLE. The buyer stops in seconds with "the provider could not start
  its Wi-Fi hotspot" instead of waiting for its own timeout. The host also
  logs "starting the local-only hotspot for prok-... (wifi on/off)".
- **The reason survives.** `ProkNetNode.lastBuyError` holds why the attempt
  ended; the node clears the purchase state (so the next SELL is not
  refused) while the screen can still explain what happened.
- **Actionable French.** `ProductState.lostHint` now has three specific
  cases: nobody answered ("Sur son telephone : Wi-Fi et localisation
  actives, application ouverte"), could not create the hotspot, network not
  joined ("appuyez sur CONNECTER dans la fenetre Android"), plus the radio
  case and the generic one.
- **The warning where the fix is.** The seller sharing card and its setup
  screen warn when Wi-Fi or Location is off on THAT phone. The buyer is
  told to turn Wi-Fi on before it even tries.

## 3. Tests (108)

`LinkStateTest`: the five per-step timeouts, and a REQUESTING attempt that
survives 59 s and tears down at 61 s naming the step.
`ProductStateTest`: the three new hints are specific, none of them says
"move closer", and a plain protocol error still gets the generic sentence.

## 4. Build

Build 16, versionName 0.9.3, 1.18 MB,
SHA256 `7fc61ff1c6fd4a5dbfd755553f41284f0ae80158248fda2cda3f76888f5dadab`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.9.3
Commit `e5bd8ed7f57a4e653aeb3c3f9fc9efa3b0a6909e` on `main`; this report on top.

## 5. What this does NOT do

It does not make a failing hotspot work. If the seller phone refuses to
create a local-only hotspot, the pair still cannot link; the difference is
that both users now know within seconds what to switch on. TESTING.md
section 21 reproduces the three cases deliberately, and asks for COPY DIAG
from both phones if it still fails with everything on.

## 6. Preserved

No protocol change except one extra WIFI_CANCEL, which v0.5 already defines
and handles. The relay handshake, coverage planner, marketplace and
database are untouched.
