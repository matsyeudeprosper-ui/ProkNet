# CLAUDE_REPORT - ProkNet v0.12.2 "the sonar"

Date: 2026-09-19
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 236/236 tests pass, released. Accueil and Carte redrawn
from scratch on my own design; nothing under the screens changed. The
one-tap hardware regression (TESTING section 50) remains the pending test.**

## 1. Why

Mike: "The home screen and map still sucks as design. Forget my
description of it and come up with your own version." The v0.12.1 home was
a giant filled ball inside an empty frame; the map was a green square with
a dot.

## 2. The design

One metaphor the product can honour: a sonar.

- Accueil: "Besoin d'Internet ?", a compact lit sphere (150 dp) at the
  centre of thin rings on a soft glow, drawn by `PulseButtonView`; while a
  request is alive, rings sweep outward and fade. One quiet line under it.
  Three tiles: Autour de vous (usable sources now), Meilleur prix, Dernière
  fois (from persisted requests). Two door rows with chevrons: Partager mon
  Internet, Carte. The status card is unchanged.
- Carte: a sonar of real sightings. You at the centre; each known source a
  dot placed by recency (fresh near the centre, old at the edge), coloured
  by status, labelled, tappable. Two tiles (Disponible maintenant / Vu
  récemment), an "around you" card with an honest sentence and the
  location button inside it, then source rows with a status dot. Once a
  position exists, the cell grid replaces the sonar.
- Stat tiles auto-size so amounts never wrap.

## 3. Build

Build 47, versionName 0.12.2, SHA256 `c16a343e124b34b7fa87f86fd78d3d40fbb411e2422014536abd9e9899d0293c`.
Commit `22b3809` on `main`; this report on top.
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.12.2

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```

## 4. Files changed

New `ui/PulseButtonView.kt`, `res/drawable/dot_warn.xml`. Rewritten
`ui/CoverageMapView.kt`. Patched `res/layout/activity_main.xml` (HOME and
MAP blocks, auto-size stats), `ui/MainActivity.kt` (sonar wiring, tiles,
door rows, map refresh with marks), `res/values/strings.xml` (+21),
`build.gradle.kts`.

## 5. The test

Unchanged: TESTING section 50. OUKITEL: Gagner, COMMENCER À PARTAGER.
OnePlus, mobile data OFF: OBTENIR INTERNET, wait for "Internet connecté
✅", Wikipedia. Then Carte on both, screenshots.
