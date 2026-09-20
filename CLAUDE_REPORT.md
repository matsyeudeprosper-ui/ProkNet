# CLAUDE_REPORT - ProkNet v0.12.5 "under the sphere"

Date: 2026-09-19
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 236/236 tests pass, released. Visual only. The one-tap
hardware regression (TESTING section 50) remains the pending test.**

## 1. Why

Mike on v0.12.4: "The sphere and its animation... Perfect design. As for
prix, source and the rest may need a better design." The sphere is
untouched; the rest of the home is redone.

## 2. What changed

- The three boxed stat tiles became one slim pill strip: three values
  (sources · prix · dernière fois) separated by thin dividers, lowercase
  labels under each, one glance.
- The two text rows with chevrons became two square action cards side by
  side: icon in a tinted circle, one-word title (Partager, Carte), a tiny
  caption; "● Vous partagez" in green while sharing.

## 3. Build

Build 50, versionName 0.12.5, SHA256 `8aa0f76c3e157d4907da0bcaf8e8210457e43a7fbf526c7f4fdf20ce33050803`.
Commit `c0fdeaa` on `main`; this report on top.
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.12.5

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```

## 4. Files changed

`res/layout/activity_main.xml` (home block), `res/drawable/bg_strip.xml`,
`res/drawable/bg_icon_circle.xml`, `res/values/strings.xml` (+7),
`ui/MainActivity.kt` (one line), `build.gradle.kts`.

## 5. The test

Unchanged: TESTING section 50.
