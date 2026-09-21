# CLAUDE_REPORT - ProkNet v0.15.2 "Gagner and Activité, redesigned"

Date: 2026-09-20
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 396/396 Android tests and 55/55 server tests pass, released
as build 60. A UI release: no new capability, no protocol change, nothing
removed. Hardware acceptance is TESTING 64.**

Commit `e1730d5`.

## 1. What was actually wrong

**Gagner** had grown to seven cards of equal visual weight, stacked around the
button that does the work:

| | |
|---|---|
| 1 | source line + warning |
| 2 | auto-price text + estimate + three price chips + fee note |
| 3 | "Mon forfait" toggle hiding two number fields and a save button |
| 4 | the start button |
| 5 | three stat boxes, then a customer line, then a stop button |
| 6 | total earned + receivable + a heavy Wallet button |
| 7 | notifications switch, coverage switch, relay panel |

Nothing told the eye what mattered. A first-time user meets a price policy and
a form before they meet the offer, so the screen reads as paperwork.

**Activité** had a different problem. When the Wallet arrived in v0.15.1 the old
money widgets stayed behind, so the screen still carried three money boxes, a
custody note, a payment card and a receiving-method card — all of them one tap
away in the Wallet — and the account settings were buried under them. Two
screens were doing one job.

## 2. Gagner: one question, three cards

The screen asks one thing: *do you want to share your Internet right now?*

1. **The hero.** One state, one title, one sentence, **one button**. The
   button's meaning comes from the state, so the two panes and two buttons
   (`netShareSetup`/`netShareActive`, `btnStartSharing`/`btnStopSharing`)
   collapsed into `btnEarnAction`. While sharing, the three live figures sit
   *inside* the hero instead of competing with it as three cards. A dot carries
   the state: grey, amber when visible, green when someone is connected.
2. **Money.** One big figure, what is owed underneath, and a quiet text link to
   the Wallet instead of a heavy button.
3. **Réglages du partage.** One row, a summary line, a chevron. Everything
   expert-level lives behind it.

Four states, all tested: no source, ready, waiting, serving.

## 3. Nothing was removed, only moved

Behind the Réglages row: the three price choices, the bundle form, the fee
note, notifications, coverage sharing, relay, and the diagnostic source detail.
The summary line ("Équilibré · Alertes activées") shows the state without
opening it.

I verified this mechanically rather than by eye: every `R.id` referenced in
`MainActivity` is checked against the layouts, and none is missing. The four
ids that disappeared were deliberately replaced by the single hero button.

## 4. Two wording rules the tests enforce

- **No subtitle is ever two sentences.** This caught a real one: the sharing
  state read "Votre téléphone est visible. Vous serez payé dès que quelqu'un se
  connecte." The first half is already implied by the title "Vous partagez", so
  it is gone.
- **Nothing says "clients".** A person counts people, so the live figure is
  "Personne connectée", not "Clients".

Each price choice now explains itself in one line, so the three words are not a
riddle: cheaper means more people connect, earn more means more per person.

**Stopping is calm.** It was a red `DangerButton`. Stopping sharing is
completely reversible, so it is now a secondary button, and the filled one is
reserved for the action we actually want. (My first attempt set the text colour
to the danger red *on* the danger background, which would have been invisible.
Caught before building.)

## 5. Activité: a history, and only that

The duplicated widgets were **deleted**, not hidden: three money boxes, the
custody note, the payment card, the receiving-method card, and the two render
functions that fed them. Everything they did is reachable in the Wallet pane.

Activité is now sessions grouped by day, each row showing what it was, who with,
the amount and its state. The account moved out from underneath into its own
**Compte** section.

`core/ActivityUi.kt` deliberately reuses `WalletUi.dayLabel`,
`WalletUi.shortName` and the Wallet's status words, so the two histories cannot
drift apart visually or disagree about the same fact. A test asserts that.

A free session reads **Gratuit**, not "0 CFA" — a zero reads like something went
wrong.

## 6. Design rules applied

One card radius and one card background across both screens. Spacing on a
4-point rhythm, 26dp inside the important cards and 20dp elsewhere. One filled
button per screen. Status colour carried by three tones rather than a palette.
Section headings instead of a flat stack. Amounts in the strong weight, labels
muted and small. No uppercase, no monospace, no dense paragraphs.

## 7. Tests

396 Android JVM tests (+17) and 55 server tests, all previous ones unmodified.

- `EarnUiTest` (10): the four states, the no-source explanation, the resting
  offer, sharing and serving, one button per state, the live figures, the
  earnings block, the settings summary, the policy hints, the demand prompt,
  and a sweep for enums, protocol words, "clients" and shouting.
- `ActivityUiTest` (7): row content, free sessions, wording shared with the
  Wallet, day grouping, the empty state, and a sweep for raw identities,
  megabytes, enums and session ids.

Two failures during the work were my own assertions, not the code: a naive
"shouting" check flagged "11 CFA" because CFA is an acronym and digits are not
letters, and a megabyte check had earlier flagged "MTN **Mo**bile Money".

## 8. Version / build / commit / hash

Build 60, versionName 0.15.2, verified with `aapt2 dump badging`.
SHA256 `a392664d23935500b69822073470cd0739585723d375c7d27a7a171afe2ba055`.
Commit `e1730d5` on `main`; this report on top.
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.15.2

## 9. Hardware test

**TESTING 64.** Count the buttons on Gagner before opening Réglages: there must
be one. Open Réglages and confirm every control still works — price choice,
bundle, all three switches. Start sharing, connect the OnePlus, watch the hero
change state, stop. Then check Activité shows only history plus Compte, and
that no money figure appears on both Activité and Wallet.

## 10. Known limitations

- **This is a visual and structural change only.** No protocol, pricing,
  shutdown or settlement code was touched, which is why the 379 existing tests
  passed unmodified throughout.
- The Réglages pane is an expanding section rather than a proper bottom sheet.
  It is honest and simple; a real sheet would feel better and needs a component
  this project does not have without AndroidX.
- The hero has no animation. The sphere on Home sets the bar and Gagner does not
  meet it yet; a state transition there would be the next visual step.
- The account section is compact but still a stack of cards. Home, Internet and
  Map were not touched in this pass.
- Everything from the v0.15.1 report still stands, including the largest gap:
  the phone does not yet submit signed settlement evidence to the server.
