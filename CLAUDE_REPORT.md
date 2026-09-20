# CLAUDE_REPORT - ProkNet v0.13.1 "the stale error and the deaf radio"

Date: 2026-09-20
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 252/252 Android tests and 11/11 server tests pass,
released. Two bug fixes and one diagnostic. The proven transport stack and
the v0.12.5 design are untouched. TESTING section 51 is still the pending
hardware test.**

## 1. Version / build / commit / hash

Build 52, versionName 0.13.1, SHA256 `89e68355a243a5fe56d8b5bff2e766397313bc3db7fd0952f1408805504926e3`.
Commit `b55cc1c` on `main`; this report on top.
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.13.1
Android JVM tests 252 (+4); server tests 11 (unchanged, rerun green).

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```

## 2. Root cause of the stale LOST UI

`MainActivity.getInternet()` set `lostDismissed = false` and created the
request, but nothing cleared the node's last error. `clearLastFailure()`
(which clears `tunnel.lastError` and `lastBuyError`) only runs inside
`node.buy()`, and with no usable source `buy()` is never called. So the
string left by the previous session, "Wi-Fi link closed: customer stopped:
stopped by user", was still there; `ProductState.buyer` returns LOST for a
non-empty error with `wanted = false`; and `buyerVisible = buyerOn() || (b
== LOST && !lostDismissed)` was checked before the request in the
when-chain, so the lost card owned the screen: RÉESSAYER / Connexion perdue
/ Rapprochez-vous du fournisseur, on a request that had not tried anything.

## 3. The fix

Three layers, all pure-testable:

1. `ProkNetNode.clearLastFailure()` is public and called by
   `getInternet()`: a new request starts from a clean screen, exactly like
   a direct purchase. `lostDismissed` starts true for the request.
2. `ProductState.isUserStop(lastError)`: "stopped by user", "customer
   stopped", "sharing stopped" and friends are decisions, not failures;
   `buyError()` never returns one.
3. `ProductState.homeOwner(sellerOn, purchaseActive, requestActive,
   showLost)`: SELLER > PURCHASE > REQUEST > lost card > IDLE. An active
   request can no longer be hidden by a card about a purchase that is over.
   Used by the home and the Internet tab.

A real failure still shows: the driver sets `lostDismissed = false` when it
marks the request FAILED, so the request's own reason appears; manual
CONNECTER also clears first, then shows its own errors.

## 4. Did BLE recovery need a code change? Yes

`scanStale` is gated on `expectPeers`, false when the last peer is older
than `PEER_MEMORY_MS` (15 min). The OnePlus last saw the OUKITEL 55 minutes
earlier, so after Bluetooth went OFF (15:30) and came back (15:58) every
verdict was HEALTHY, while `scanning` and `advertising` still claimed true
(an adapter restart does not call the failure callbacks) and no scan result
had arrived for thousands of seconds. Nothing could ever recover.

Two bounded rules in `BleHealth`:

- `BLUETOOTH_RETURNED`: the node itself notices OFF → ON inside the
  watchdog (no receiver, caught within 10 s) and passes
  `bluetoothReturnedAt`. One recovery per transition
  (`lastRecoveryAt < bluetoothReturnedAt`), after the 12 s grace.
- `SCAN_SILENT`: the scanner **has heard peers before**
  (`lastScanResultAt > 0`) and has heard nothing at all for 10 minutes,
  with nobody "expected" so no other rule looks.

A phone that never heard anybody is alone in a field, not wedged, and is
still left alone: the v0.9.10 test `a_working_radio_is_left_alone` failed
against my first, blanket version of this rule, and it was right to — I
narrowed the rule instead of changing that test. Both new verdicts sit
after the `linkBusy` guard and inside the existing backoff, so they never
touch an active bulk or tunnel session and never loop.

## 5. COPY NETWORK

Five lines at the top, before anything technical:

```
Nearby ProkNet phones: 1 (prok-24e480e6)
Last peer seen: 3 s ago
Request state: NETWORK_REQUESTED (e6d0623b39ba8fe0, 27 min left)
Last request forwarded to: prok-24e480e6 (e6d0623b39ba8fe0, new request)
Provider activation notification sent: NO - no request reached this phone
```

## 6. Tests added (4)

- a live request outranks a lost card from a purchase that is over, and the
  lost card still shows when nothing is running (`homeOwner`, all cases);
- a session the user stopped is not an error for the next request, using
  the exact diagnostic string from the phone, while real failures stay
  errors and an empty error is IDLE not LOST;
- Bluetooth OFF → ON recovers the radio exactly once, is given its grace,
  respects the backoff, and never fires during a link or when stopped;
- a scanner that heard peers before and has been silent for ten minutes
  restarts, while one that never heard anybody is left alone.

## 7. Point 2 of the report: no propagation without a peer

Confirmed and unchanged: the request was created correctly and there was
simply nobody to hand it to (`ble: peers 0`, the OUKITEL 55 minutes stale).
Nothing pretends it propagated. What v0.13.1 changes is that the phone can
now get its radio back by itself after Bluetooth returns, and that COPY
NETWORK says "Nearby ProkNet phones: 0" at the top so the cause is obvious
in one line.

## 8. Point 3: the buyer's own Wi-Fi

Behaviour is correct and unchanged ("this phone already has validated
Internet over Wi-Fi" → CONNECT_NOW on the free source). For the offline
test the OnePlus must not be on any Wi-Fi; that is now written into
TESTING section 51's setup.

## 9. The exact simple hardware test

Put the phones side by side for ~30 s. On the OnePlus, COPY NETWORK must
say `Nearby ProkNet phones: 1`.
OUKITEL: Freebox ON, mobile data OFF, Bluetooth ON, sharing OFF, Gagner →
"Me prévenir quand quelqu'un cherche Internet près de moi" ON.
OnePlus: mobile data OFF, **not on any Wi-Fi**, Bluetooth ON. Tap the
sphere: RECHERCHE → after ~15 s DEMANDE.
OUKITEL: "Quelqu'un cherche Internet à proximité" → PARTAGER.
OnePlus, no more taps: CONNEXION → CONNECTÉ → Wikipedia.
TESTING sections 51 and 53.
