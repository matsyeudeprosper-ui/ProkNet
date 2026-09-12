# CLAUDE_REPORT - Milestone 1: ProkNet Lab v0.1

Date: 2026-09-12
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, APK produced, NOT yet tested on real phones** (Mike has the APK)

## 1. What I built

An Android app, **ProkNet Lab v0.1**, in which two phones with mobile data
and Wi-Fi off discover each other over Bluetooth Low Energy and send a text
message directly, phone to phone.

Implemented from the milestone list:

| Requirement | Done | How |
|---|---|---|
| Persistent unique ProkNet device identity | yes | 16 random bytes, generated once, SharedPreferences; local display name |
| Basic nearby-device discovery | yes | BLE advertise + scan filtered on the ProkNet service UUID |
| Show nearby ProkNet devices | yes | list with short ID, RSSI, address, age; expires after 25 s unseen |
| Select a nearby device | yes | tap in list |
| Send a text message directly | yes | GATT connect -> MTU 517 -> write one packet -> ack -> disconnect |
| Store sent/received messages locally | yes | SQLite, duplicate delivery ignored by message ID |
| Show connection/discovery/debug status | yes | one status line: BT / server / adv / scan / peers / last send |
| Internal logs visible in the app | yes | live log pane, last 600 lines, every BLE step with raw status codes |
| Copy / export logs | yes | **Copy log** (clipboard) and **Share** (any app) with device, identity, status, peers, log |
| Very basic UI | yes | one Activity, stock widgets, no design |

Plus the VPS build pipeline and the four documents requested.

## 2. Architecture and technology choices, and why

**Transport: BLE GATT, every phone is both peripheral and central.**
Simplest reliable offline link on Android: no pairing dialog, no group-owner
negotiation, no IP layer, works foreground on every phone since Android 5.
The advertisement carries the ProkNet service UUID; the scan response carries
a 4-byte short ID (manufacturer data, company ID 0xFFFF = Bluetooth SIG test
ID). Peers are keyed by short ID because Android rotates BLE MAC addresses.
The GATT service has an IDENTITY (read) and an INBOX (write) characteristic.

**Wire format:** one binary packet, max 512 bytes (`core/Packet.kt`):
magic "PK", version, type, 16-byte sender ID, 8-byte message ID, timestamp,
text. The sender asks for MTU 517 so a packet fits in one write; if the peer
grants less, Android's long-write procedure is used and the server reassembles
prepared-write chunks. Both paths are implemented.

**Kotlin only, no Python yet.** The project direction is Python for protocol
logic and thin Kotlin for Android APIs. In v0.1 the protocol is 80 lines of
packet encode/decode; everything else is Android BLE plumbing that must be
native. Embedding Python now (Chaquopy is the realistic option) would add a
large dependency, a slower build on the shared VPS and a second runtime to
debug, for nothing yet. The boundary is prepared (`core/` has no Android BLE
imports). **This is a decision I want you to confirm or overrule** before
milestone 2, because store-carry-forward routing is where Python would start
to earn its place.

**No AndroidX, no Room, no Material.** Plain `android.app.Activity`, stock
widgets, `SQLiteOpenHelper`. Dependency tree = Kotlin stdlib. Keeps the build
at ~1 minute and the APK at 0.8 MB on a 12 GB VPS that also runs live
services.

**No background service.** Both phones must have the app open. Deliberate for
v0.1; it is the first thing to add once the link is proven.

Full detail: `docs/ARCHITECTURE.md`.

## 3. Files / components added

```
build.ps1                                   the build command (see 5)
build.gradle.kts, settings.gradle.kts, gradle.properties, gradle/wrapper/*
app/build.gradle.kts, app/src/main/AndroidManifest.xml
app/src/main/res/layout/activity_main.xml   the one screen
app/src/main/res/values, drawable, mipmap   name + icon
app/src/main/java/net/prok/proknet/
  core/Identity.kt        persistent ID + display name
  core/Packet.kt          wire format encode/decode
  core/MessageStore.kt    SQLite messages table
  core/DiagLog.kt         in-app log ring buffer + file + listeners
  ble/BleConstants.kt     UUIDs, manufacturer ID, timeouts, Peer model
  ble/BleAdvertiser.kt    be discoverable
  ble/GattServerNode.kt   receive packets (incl. prepared-write reassembly)
  ble/BleScanner.kt       discover peers, expiry, address rotation handling
  ble/BleSender.kt        connect/MTU/discover/write state machine, retry, timeout
  ble/ProkNetNode.kt      owns the four above; the only object the UI uses
  ui/MainActivity.kt      UI, permissions, Bluetooth-enable flow, copy/share log
README.md, docs/ARCHITECTURE.md, docs/TESTING.md, CLAUDE_REPORT.md
```

About 1,100 lines of Kotlin.

## 4. Exact APK path

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Also inside the build tree at `app\build\outputs\apk\debug\ProkNetLab-debug.apk`.

Published for the phone at:
https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.1.0
(private repo; Mike's phone browser must be logged in to GitHub). The APK
was also sent to Mike directly through the Claude session.

Build 1: 0.81 MB, SHA256 `16b3d7d54ead67c43dff161d98b860d7f00fd0144e0df513eaa9598657f4e95b`,
package `net.prok.proknet.lab`, versionCode 1, minSdk 26 (Android 8),
targetSdk 34, debug-signed.

## 5. Exact build command

```
powershell -ExecutionPolicy Bypass -File C:\Projects\ProkNet\build.ps1
```
Options: `-Clean`, `-Offline`. It sets JAVA_HOME / ANDROID_HOME, runs
`gradlew.bat --no-daemon assembleDebug`, copies the APK to `dist\`, prints
size and SHA256. Refuses to run with under 1 GB free disk. A rebuild takes
about 60 s.

Toolchain installed on the VPS, all under `C:\Android` (nothing else touched):
Temurin JDK 17.0.20, Android cmdline-tools, platforms;android-34,
build-tools;34.0.0, platform-tools, Gradle 8.7 (wrapper), AGP 8.5.2,
Kotlin 1.9.24.

## 6. What Mike needs to do on his phones

Full steps in `docs/TESTING.md`. Short version:

1. Download and install `ProkNetLab-debug.apk` on both phones (allow
   "install unknown apps"; Play Protect: "install anyway").
2. On both: mobile data OFF, Wi-Fi OFF, Bluetooth ON. Android 8-11 only:
   Location ON too (Android requires it for BLE scanning).
3. Open the app on both, press **Start**, grant the "Nearby devices"
   (or Location) permission, press Start again if needed.
4. Status line should say `server ready | adv on | scan on`. Within ~5 s each
   phone lists the other as `prok-xxxxxxxx`.
5. Tap the peer, type a text, **Send**. Sender shows `[sent]`, receiver shows
   `<- prok-... [received]`.
6. If anything fails: **Copy log** or **Share** on the failing phone and paste
   the text back. The checklist in TESTING.md section 6 is what I need
   answered for the milestone verdict.

## 7. Known limitations

- App must be in the foreground on both phones (no background service).
- No encryption. Anyone with the UUID can read the packets over the air.
- One message per GATT connection, max 474 bytes of text, a few hundred ms
  per message. Fine for a lab, not for bulk data.
- Identity is a random number, not a key pair; the display name is local only
  (the other phone shows `prok-<shortId>`, not the name).
- Some cheap phones cannot advertise (`bluetoothLeAdvertiser == null`). They
  can still find others and send, but cannot be discovered. The log says so.
- Android 8-11 need Location ON for scanning; Android 12+ do not.
- Debug signing key lives on the VPS (`~/.android/debug.keystore`). If it is
  ever lost, phones must uninstall before installing a new build.
- Windows Update download cache (8.5 GB) still needs clearing on the VPS
  (harness blocks me from that path; Mike approved and has the one-liner).
  Free disk is 1.9 GB; build.ps1 stops below 1 GB.

## 8. What failed or is uncertain

- **Not tested on real hardware.** I have no phones. The build compiles,
  the manifest and signature verify, but BLE behaviour differs per vendor.
  The most likely first-run problems, all logged with hints in the app:
  status 133 on connect (retried once automatically), a peripheral that never
  answers the MTU request (3 s fallback), and OEM battery managers killing
  the scan.
- **Uncertain: automatic long write.** If a phone grants MTU 23, Android should
  split the write into prepared writes; the server handles that, but I could
  not exercise it. TESTING.md includes a 300-character message for this.
- **AGP's own SDK download hangs on this VPS** (Java TLS to dl.google.com is
  reset; curl and sdkmanager work). Fixed by `android.builder.sdkDownload=false`;
  missing SDK parts must be installed with sdkmanager, which is documented.
- **Hard-coded `0xFFFF` manufacturer ID** is the SIG test ID; correct for a
  lab, must not ship in a product.
- **GitHub release is on a private repo.** Downloading from the phone needs a
  logged-in browser. If that is a nuisance, make the repo public (there are no
  secrets in it) or I serve the APK from the VPS.

## 9. Git commit hash

Code and docs: `ae90ab4c25d49d9bbb932e5de9969491ca804543` (branch `main`,
https://github.com/matsyeudeprosper-ui/ProkNet). This report is committed
on top of it. Release tag `v0.1.0` points at the built commit.

## 10. Recommendation for the next step

Do not start milestone 2 until Mike's two-phone test passes the checklist in
`docs/TESTING.md`. Expect one or two fix-and-rebuild rounds first (that is
what the in-app log is for).

Once the link is proven, my recommended milestone 2 is **"the link survives
real life"**, still BLE, still two phones:

1. Foreground service so discovery and receive work with the screen off and
   the app in the background (this is a precondition for CARRY).
2. Show the peer's display name (read the IDENTITY characteristic on first
   contact, cache it).
3. Delivery receipts and a resend queue: a message written while the peer
   is out of range waits and goes out when the peer is next seen. That is
   STORE and FORWARD in its smallest form, for the sender's own messages.
4. Decide the Python question (section 2) before any routing logic exists.

Encryption, multi-hop, Wi-Fi Direct, Internet sharing and payments stay out
until 1-3 are solid.
