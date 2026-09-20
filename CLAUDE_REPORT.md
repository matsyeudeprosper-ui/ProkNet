# CLAUDE_REPORT - ProkNet v0.13.2 "could this phone become a seller?"

Date: 2026-09-20
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 255/255 Android tests and 11/11 server tests pass,
released. One blocker fixed. The proven transport stack, the v0.12.5 UI,
the gossip protocol and the Network Brain architecture are untouched.
TESTING section 51 is the acceptance test.**

## 1. Root cause

The OUKITEL diagnostic: the request arrived, the signature verified, the
request stored — then `not activating for 091d740cdf159f37: NO_INTERNET`,
three times, while the same file showed `wlan0=192.168.1.13`, a validated
Wi-Fi source and `cells: z? GREEN direct=1`.

`NetworkNode.eligibility()` read `node.gateway.upstream?.validated` and
`node.sellerAccessPath()`. Both are gateway state. `Gateway.start()` runs
inside `setSelling(true)`, which runs when the user taps PARTAGER. With
sharing off there is no upstream callback registered and `upstreamView` is
null, so `upstreamValidated` was false and the refusal was NO_INTERNET —
before Bluetooth, price or anything else was even considered.

The loop: sharing OFF → gateway.upstream null → NO_INTERNET → no
notification → the user is never offered PARTAGER → the gateway never
starts. Nothing the user could do would break it.

The deeper error: eligibility answered "is the seller gateway running?"
when the question is "could this phone become a seller right now?".

## 2. New upstream-capability design

`node/Upstream.kt`:

- `networks(context)` — the ConnectivityManager scan (INTERNET, VALIDATED,
  CELLULAR, WIFI, ProkNet-local-link detection). This is the body that was
  private inside `Gateway`; it moved here and `Gateway.networks()` is now
  one line calling it. One network truth, shared, not duplicated.
- `now(context)` = `Tunnel.chooseUpstream(networks(...))` — the Internet
  this phone would sell if it started now, `null` when it has none.
- `describe(view)` — "WI-FI validated" / "MOBILE DATA NOT validated" /
  "NONE" for the diagnostic.

`core/ProviderActivation.kt` (pure):

- `potentialPath(upstreamType, upstreamValidated, bulkSupported,
  bluetoothOn)`: not validated or UP_NONE → NONE; UP_WIFI →
  `BulkPlan.sellerAccessPath(...)` (BLUETOOTH_BULK, or NONE without
  Bluetooth / L2CAP); anything else validated → HOTSPOT.
- `eligibility(...)` composes the whole thing; `alreadySharing` is the only
  field the gateway still decides.

`node/NetworkNode.kt`:

- `currentUpstream()` = `gateway.upstream` when SELL is on (authoritative
  then), `Upstream.now(context)` when SELL is off.
- `eligibility()` builds from that. The gateway is never pre-started to
  answer a question, and the PARTAGER flow is unchanged:
  `setSelling(true)` → `gateway.start()` → its own upstream discovery →
  the existing seller access path → the Bluetooth bulk advert → the buyer
  auto-connects.

## 3. Files changed

New `node/Upstream.kt`. Patched `node/Gateway.kt` (its scan now delegates),
`core/ProviderActivation.kt` (`potentialPath`, `eligibility` factory),
`node/NetworkNode.kt` (`currentUpstream`, `eligibility`, diagnostics),
`app/src/test/.../NetworkBrainTest.kt` (+3 tests), `build.gradle.kts`,
README / ARCHITECTURE / TESTING.

## 4. Tests added (3, total 255)

- **the exact hardware case**: sharing OFF, validated Wi-Fi, Bluetooth ON,
  bulk supported, opt-in ON, not busy, request open → eligible,
  `BLUETOOTH_BULK`, no refusal, and the "à proximité" opportunity; plus
  mobile data OFF-sharing → eligible through HOTSPOT, with no Bluetooth
  needed for that path;
- `potentialPath` reads capability, never the gateway: no Internet and
  unvalidated Internet → NONE / NO_INTERNET; validated Wi-Fi without
  Bluetooth or without L2CAP → NONE / NO_LOCAL_PATH; validated Wi-Fi with
  both → BLUETOOTH_BULK; other validated → HOTSPOT;
- the other refusals still hold on a capable phone: NOT_OPTED_IN,
  ALREADY_SHARING, BUSY, REQUEST_NOT_OPEN, ABOVE_CEILING (and accepted at
  or under the ceiling), and the availability heartbeat follows.

All 252 previous Android tests and the 11 server tests still pass
unchanged.

## 5. Diagnostics

`COPY NETWORK`, provider section:

```
current phone Internet: WI-FI validated
potential seller path: BLUETOOTH_BULK
seller gateway running: NO
notifications: ON | internet validated: true | bluetooth: true | busy: false | price: 500c
```

and in the five-line summary at the top:

```
This phone could share: WI-FI validated -> BLUETOOTH_BULK
```

## 6. Version / build / commit / hash

Build 53, versionName 0.13.2, SHA256 `631ae9bb87d852df919da2ef761ebbeef1d3bb8471419b93bd40e14cc11b04e6`.
Commit `8e010c9` on `main`; this report on top.
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.13.2

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```

## 7. The exact Mike test

OUKITEL: Freebox Wi-Fi ON, mobile data OFF, Bluetooth ON, sharing OFF,
Gagner → "Me prévenir quand quelqu'un cherche Internet près de moi" ON.
OnePlus: mobile data OFF, **not connected to any Wi-Fi**, Bluetooth ON.
Phones side by side ~30 s; COPY NETWORK on the OnePlus must say
`Nearby ProkNet phones: 1`.

OnePlus: tap the sphere → RECHERCHE → after ~15 s DEMANDE.
OUKITEL: "Quelqu'un cherche Internet à proximité." → tap PARTAGER once.
OnePlus, without another tap: CONNEXION → CONNECTÉ. Open Wikipedia.

TESTING sections 54 (one glance at COPY NETWORK first) then 51.
