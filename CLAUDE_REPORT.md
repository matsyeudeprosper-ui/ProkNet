# CLAUDE_REPORT - ProkNet v0.9.6 "can this phone resell its own Wi-Fi?"

Date: 2026-09-14
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 114/114 automated tests pass, released, not yet tested on phones**

Option B from `docs/DECISION_WIFI_SHARING.md`, approved with three
clarifications, all implemented.

## 1. The probe

`node/HotspotProbe` starts a local-only hotspot, closes it immediately and
reports either "started" or the exact Android error. It runs:

- when SELL is switched on, 1.2 s after the gateway has its upstream;
- again whenever the upstream network changes under a seller;
- on demand from Relay Lab -> **TEST SHARING** (forces a fresh run).

It never runs while a Wi-Fi link or a session is in use, and it is capped
at 15 s in case Android never calls back.

## 2. The rules, pure and tested (`core/ShareCheck`)

- `needed(upstreamType)`: only a **Wi-Fi** upstream can clash with a
  hotspot. A phone selling mobile data is never tested and is always
  allowed to share.
- `shouldProbe`: only when needed and this network has no remembered
  answer.
- `verdict(upstreamType, started)` -> NOT_NEEDED / CAN_SHARE / CANNOT_SHARE.
- `canShareWhileOnWifi(result)` -> true / false / **null when never
  tested**. Unknown is not a refusal; only a tested failure is.
- `key(ssid, bssid)`: one answer **per network** (BSSID first, SSID as
  fallback, "unknown" when Android redacts both). The same phone may manage
  one router and not another.
- `band` / `channel` / `isDfs` / `describe`: every result is recorded with
  its frequency, e.g. "5 GHz DFS ch 60 (5300 MHz)".

Stored per network key in `proknet_share_cap` (verdict, Android own error
text, frequency, timestamp), so the second time the answer is instant and
no hotspot flashes.

## 3. The coverage engine

- `CoverageNode.canShareWhileOnWifi` (default true, false only after a
  tested refusal) and `InternetSource.wifiBased` / `frequencyMhz` / `band`.
- `Coverage.canDeliver(node)` now gates **every** place the planner picks a
  provider: direct, through relays, fundable, and mover routes. `score`
  refuses such a route with its own reason as a second line of defence.
- **The source is not removed from the map.** `observedSources` keeps every
  source seen, `deliverableSources` is the subset someone present can hand
  over today, `blockedSources` gives the difference with a reason. A Wi-Fi
  network that this phone cannot resell stays a candidate for a capable
  phone later, exactly as asked.

## 4. What the user sees

One sentence, on the seller own sharing card, only for the phone and the
network concerned:

> Ce telephone ne peut pas partager ce reseau Wi-Fi. Vous pouvez partager
> vos donnees mobiles a la place.

While the test runs: "Verification du partage sur ce reseau Wi-Fi...".
Sharing is **not** switched off, and there is no global advice to turn
Wi-Fi off anywhere in the app.

## 5. Tests (114, +6)

New `ShareCheckTest` (4): only a Wi-Fi upstream is tested; the test runs
once per network; unknown is not a refusal; the key is per network with the
BSSID winning and a fallback when Android redacts it; bands and channels
including DFS.

New in `CoverageTest` (2): **the decisive one** builds a phone that was
tested and refused, and proves that no route is planned through it, that
`canDeliver` is false, that the source is still in `observedSources` and
named in `blockedSources` with a reason, that a capable phone in the same
zone delivers the same source, that the same incapable phone still sells
its mobile data, and that an untested phone is not blocked. The second
checks that a source keeps its band.

## 6. Build

Build 19, versionName 0.9.6, 1.19 MB,
SHA256 `069d4857ad9b5e84ad31c011bbae60c084ccecc0a5ad70f15580db0eca58cccc`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.9.6
Commit `CODE_COMMIT` on `main`; this report on top.

## 7. Wi-Fi Direct

Not implemented, as instructed. Documented as the next transport
experiment in `docs/ARCHITECTURE.md` and in the decision record. The Relay
Lab already reports whether each phone supports P2P, so the data to decide
it will accumulate on its own.

## 8. What the phone test should produce

`docs/TESTING.md` section 24. The useful output for you is the share-check
line from COPY DIAG on each phone, which carries the verdict, the band, the
channel and Android own error. That is the data that will show whether
failures are all 5 GHz / DFS, and whether a 2.4 GHz network lets a seller
share while staying on Wi-Fi.
