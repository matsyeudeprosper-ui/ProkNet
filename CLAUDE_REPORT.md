# CLAUDE_REPORT - ProkNet v0.9.2 "French app + sharing fix"

Date: 2026-09-14
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 108/108 automated tests pass, released, not yet tested on phones**

Two things Mike asked for: SHARE INTERNET refused to start with a message
about the price although a price was set, and the app must speak French for
the first customers.

## 1. The sharing bug: two causes, one wrong message

`startSharing` mapped every refusal except one to "Check the price, minimum
and limit", so three different problems looked like a price problem.

- **Empty fields.** An empty minimum or limit was read as `-1` (invalid),
  not as "none". Now blank means 0, and only a missing or out-of-range
  PRICE gets the price message.
- **Relay mode.** `setSelling` returns "relay mode is on" when the phone is
  the relay of the 3-phone test. That is almost certainly what Mike hit:
  after the v0.9.x relay runs, RELAY MODE stays on in the Relay Lab. It now
  says so: "Le mode relais est active. Desactivez-le dans Developpeur, puis
  reessayez."
- **A failed purchase that was never cleared.** `buyerWanted` was only
  cleared by an explicit Stop. A purchase that failed on its own (no
  contract answer, seller gone, relay never introduced) left it set, so
  `setSelling` returned "stop buying first" - again shown as a price error.
  `TunnelClient.fail` now calls a new hook `onAttemptFailed`; the node
  clears the buyer state AND stops the VPN, which also fixes a real user
  problem: after a failed attempt the VPN stayed up capturing the phone's
  traffic with no tunnel behind it.

Every refusal now has its own French message and is logged with the raw
reason.

## 2. French

The consumer app is French: five tabs, dialogs, toasts, notification.

- `res/values/strings.xml` holds the layout and Activity words (with
  `%1$s` arguments so names and amounts are inserted, never concatenated
  English fragments).
- `core/ProductState` holds everything derived from engine state and stays
  pure and tested, now in French. Number formatting is pinned to
  `Locale.FRANCE`: "11,5 Mo", "512 Ko", "1,20 Go", "57,35 CFA" - the same
  on a phone set to English, because it never uses the default locale.
- The notification no longer shows the engineering status line. It shows
  "Vous etes en ligne", "Vous partagez votre Internet", or "3 personnes a
  proximite . 1 offre(s) Internet". The full status line still goes to the
  log.
- The developer screen, the Relay Lab and the log stay English on purpose
  (engineering tools, and ChatGPT reads them). Engine strings stay English
  too; ProductState is what turns them into words.

Adding another language later is `res/values-xx/strings.xml` plus one
switch inside ProductState. No screen would change.

## 3. Tests (108, +1)

- `ProductStateTest.the_whole_consumer_wording_is_french` walks every buyer
  title, seller title and hint, coverage word, lost hint, payment word,
  signal and upstream word and fails if any of 19 English fragments is
  still there.
- The existing ProductState tests now assert the French strings and the
  French number formats.
- Everything else unchanged: 108 green, APK gated.

## 4. Build

Build 15, versionName 0.9.2, 1.18 MB,
SHA256 `690fc4089a2c66b1fce494aadb941c4959136450b323c5c003aa97a4fd076d09`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.9.2
Commit `ef06c851360a6fe2bf271bab8c0f1cd07979fcd9` on `main`; this report on top.

## 5. Preserved

No engine change. The v0.9.1 relay handshake, the coverage planner, the
marketplace and the database are untouched; the only Kotlin outside the UI
is the new `onAttemptFailed` hook.

## 6. Still open

The 3-phone relay retest (TESTING.md section 19) has not been run yet; this
build contains it unchanged. Section 20 adds the French and sharing checks.
