# CLAUDE_REPORT - ProkNet v0.12.1 "the design pass"

Date: 2026-09-19
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 236/236 tests pass, released. Visual only; nothing under
the screens changed. The one-tap hardware regression (TESTING section 50)
is still the pending test.**

## 1. Why

Mike's screenshots of v0.12.0: a flat circle, flat cards, a legend with
light text on light pills, and the Carte card saying "Internet disponible"
right above "Position inconnue". It did not look like a product.

## 2. What changed

One design system, no new library:

- Palette in light and night with the same token names (deep navy night,
  one brand blue, soft glows, fixed pastel map colours with dark legend
  text so the legend reads everywhere).
- Type scale (H1 30, H2 19, Body 15, Muted 13, Caption 11 caps, Big 36,
  Stat 22) and three button styles (gradient primary, bordered secondary,
  flat danger, 58 dp).
- Drawables: bordered rounded cards, an accent card for live status, a
  hero glow behind the button, the one-tap button as two glow rings around
  a radially lit sphere with a pressed state, a top-rounded nav bar,
  legend pills, status dots.
- The map view: glowing "me" dot, gapped cells, text colour chosen against
  the fill.
- Words: "Actif" / "En pause" chip with a dot; the Carte card now says "Vu
  par ce téléphone, sans position. Autorisez la position approximative
  pour placer les observations sur la carte."; an unnamed connected
  network reads "Wi-Fi connecté".

## 3. Build

Build 46, versionName 0.12.1, SHA256 `209300f9ab571b8c8bd5233b7df65f10d963d4bb0ba73aa22cfba0cda4786866`.
Commit `ee60b5e` on `main`; this report on top.
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.12.1

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```

## 4. Files changed

`res/values/colors.xml`, `res/values-night/colors.xml`,
`res/values/styles.xml`, `res/values-night/styles.xml`, 15 drawables under
`res/drawable/` (`bg_card`, `bg_card_alt`, `bg_card_accent`, `bg_chip`,
`bg_primary`, `bg_secondary`, `bg_danger`, `bg_hero`, `bg_nav`,
`bg_big_button`, `bg_pill_ok/warn/muted`, `dot_ok`, `dot_muted`),
`res/layout/activity_main.xml`, `ui/CoverageMapView.kt`,
`ui/MainActivity.kt` (card paddings, chip dot, source titles),
`res/values/strings.xml` (4 strings), `build.gradle.kts`.

## 5. The test

Unchanged: TESTING section 50. OUKITEL: Gagner, COMMENCER À PARTAGER.
OnePlus, mobile data OFF: OBTENIR INTERNET, wait for "Internet connecté
✅", Wikipedia. Then Carte on both, screenshots if anything looks wrong.
