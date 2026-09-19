# CLAUDE_REPORT - ProkNet v0.12.3 "the sphere, second pass"

Date: 2026-09-19
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 236/236 tests pass, released. Visual only. The one-tap
hardware regression (TESTING section 50) remains the pending test.**

## 1. Why

Mike, on v0.12.2: "Better but not quite. Was that the best you have?" It
was not: a pale flat disc in three even circles, truncated tiles, one
blue everywhere.

## 2. What changed

`ui/PulseButtonView.kt`, redrawn in a software layer:
- the sphere: four-stop radial gradient from a tight near-white specular
  through the brand blue to a deep navy edge, a thin white rim, a blurred
  halo, a blurred drop shadow beneath, a soft text shadow;
- ambient light instead of a bullseye: blue high-left, violet low-right,
  two scan arcs whose stroke fades around the circle;
- the usable sources as green glowing dots on the first arc (`sources`,
  from the home refresh); while searching they drift and a radar wedge
  turns.
Layout and strings: tile captions Sources / Prix / Dernière on one line,
values auto-sized 12-18 sp; shorter door-row subtitles.

## 3. Build

Build 48, versionName 0.12.3, SHA256 `67e6164e4d413f77adbfe14d2477efd07b25930532789d6b671fd68de1a5bd7e`.
Commit `cca5b04` on `main`; this report on top.
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.12.3

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```

## 4. Files changed

`ui/PulseButtonView.kt`, `res/layout/activity_main.xml` (tiles),
`res/values/strings.xml` (5 strings), `ui/MainActivity.kt` (one line:
`sources`), `build.gradle.kts`.

## 5. The test

Unchanged: TESTING section 50.
