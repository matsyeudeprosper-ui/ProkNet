# CLAUDE_REPORT - ProkNet v0.12.4 "the sphere is the interface"

Date: 2026-09-19
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 236/236 tests pass, released. Visual and interaction only.
The one-tap hardware regression (TESTING section 50) remains the pending
test.**

## 1. Why

Mike, on v0.12.3: "Good, I like the sphere, but design could be better.
I'm expecting less writing, more intuitive, animation, for example that
sphere would have been a breathing something." His screenshot also showed
two defects: the blur layer as a hard rectangle behind the sphere, and the
outer arc clipped.

## 2. What changed

- `ui/PulseButtonView.kt`: modes IDLE / SEARCHING / CONNECTING / ONLINE.
  Breathing at rest (3.4 s), quicker while searching with a turning radar
  wedge, pulse rings while connecting, green body / halo / arcs when
  online, a ripple on press, arcs turning slowly, source dots orbiting.
  Continuous redraw only while attached and shown. Ambient light bounded
  inside the view; arcs bounded by the view radius; height 336 dp.
- The home: no question line, no status line; the sphere stays visible
  during a request (it IS the status) with one word per state; the status
  card with details and ARRÊTER sits under it; tiles show only the value;
  door rows are a title and a chevron; "Vous partagez" only while sharing.
- A tap while a request is alive does nothing.

## 3. Build

Build 49, versionName 0.12.4, SHA256 `adecff0940972f99fae620636692e15e7b0dc7322acca1002b1aabc54d916a34`.
Commit `5f39aa7` on `main`; this report on top.
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.12.4

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```

## 4. Files changed

`ui/PulseButtonView.kt`, `res/layout/activity_main.xml` (home block),
`res/values/strings.xml` (+6), `ui/MainActivity.kt` (sphere mode / label,
ask-block visibility, tile values, tap guard), `build.gradle.kts`.

## 5. The test

Unchanged: TESTING section 50. The sphere itself now shows RECHERCHE ->
CONNEXION -> CONNECTÉ during it.
