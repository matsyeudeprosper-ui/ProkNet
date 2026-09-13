# ProkNet

ProkNet is a decentralized communication network made from ordinary Android
phones. Core principle: **STORE -> CARRY -> FORWARD**. Phones discover each
other nearby, exchange data without Internet, carry packets for others while
people move, and forward them when another ProkNet phone is met.

```
PROK          commerce / services
  |
PROKNET       communication / connectivity network
  |-- BLE            (v0.1: this)
  |-- Wi-Fi P2P      (later)
  `-- Internet       (optional gateway, later)
```

This repository contains **ProkNet Lab**, built milestone by milestone.

## Milestones

| Version | Milestone | Status |
|---|---|---|
| v0.1.0 | Lab: BLE discovery + direct text message, Internet off | passed on two real phones (2026-09-12) |
| v0.2.0 | 2A: queued delayed delivery with delivery receipts | passed on two real phones (2026-09-13) |
| v0.3.0 | 2B: background operation via foreground service | built, awaiting phone test |

### v0.1 - what it does

Two Android phones with mobile data and Wi-Fi OFF can:

- generate a persistent unique ProkNet identity (16 random bytes, stored locally)
- discover each other over Bluetooth Low Energy (BLE)
- show nearby ProkNet devices in a list
- select one and send a short text message phone-to-phone
- store sent and received messages locally (SQLite)
- show discovery / connection / debug status on screen
- show a full internal log, with **Copy log** and **Share** buttons, so testing
  needs no ADB, no PC and no Android Studio

### v0.2 (milestone 2A) - queued delayed delivery

- A message to a phone that is not in range is stored as **pending** and
  delivered automatically when that phone is seen again. No second Send.
- Message states: `pending`, `sending`, `delivered`, `failed`, `expired`.
- Pending messages survive app restart (SQLite queue).
- **Delivery receipt**: the receiver confirms it stored the message; a bare
  BLE write acknowledgement no longer counts as delivered.
- Duplicate delivery is impossible: message IDs are unique on both sides.
- Known peers stay in the list marked "NOT IN RANGE" so you can queue for them.
- **Retry** button clears backoff and tries everything pending now.

### v0.3 (milestone 2B) - background operation

- A **foreground service** with a persistent notification owns the node.
  Start starts the service; Stop (button or notification action) stops it.
- The screen (Activity) is only a window: close it, reopen it, swipe the app
  away, the node keeps advertising, scanning, receiving and delivering.
- Screen off does not stop anything. A short wake lock guards each delivery.
- UI shows `Service: RUNNING / STOPPED` and a **Battery** button to ask
  Android to exempt ProkNet from battery optimisation (OEM background killers).
- Log lines for service start/stop, activity foreground/background, screen
  on/off, task swiped away, battery-exemption state.

Still not here: encryption, multi-hop, carrying other people's packets, Wi-Fi Direct.

## Build (on the VPS)

Everything builds on the Windows VPS. No PC, no Android Studio.

```
powershell -ExecutionPolicy Bypass -File C:\Projects\ProkNet\build.ps1
```

Output APK (always the same path):

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```

Options: `-Clean` (full rebuild), `-Offline` (no dependency downloads).
The first build downloads Gradle and the Android Gradle plugin (a few hundred MB,
several minutes). Later builds take about one to three minutes.

Toolchain on the VPS (installed once): JDK 17 at `C:\Android\jdk17`, Android
SDK at `C:\Android\sdk` (platform 34, build-tools 34.0.0), Gradle 8.7 via the
wrapper. See `docs/ARCHITECTURE.md` for how they were installed.

## Getting the APK onto a phone

Each build is also published as a GitHub release asset of this repository
(`ProkNetLab-debug.apk`). Open the release page on the phone, download, and
install (allow "install unknown apps" for the browser when asked).

## Repository layout

```
app/                      Android app (Kotlin, no AndroidX, plain Activity)
  src/main/java/net/prok/proknet/
    core/                 Identity, Packet (wire format), MessageStore (+queue), DiagLog
    ble/                  Advertiser, GATT server, Scanner, Sender, DeliveryQueue, ProkNetNode
    service/              ProkNetService (foreground service owning the node)
    ui/                   MainActivity (the one screen)
    ProkNetApp.kt         Application: creates the node once per process
build.ps1                 The build command
dist/                     Predictable APK output location (APK itself not committed)
docs/ARCHITECTURE.md      Design and technology choices
docs/TESTING.md           Step-by-step phone test procedure
CLAUDE_REPORT.md          Milestone report for the architect (ChatGPT)
```

## Roles

- ChatGPT: architect / product lead. Sets milestones, reviews reports.
- Claude Code: implementation engineer. Builds on the VPS, reports in `CLAUDE_REPORT.md`.
- Mike: tests on real phones, from the phone only.
