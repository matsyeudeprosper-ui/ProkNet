# CLAUDE_REPORT - Milestone 2C1: one-relay STORE -> CARRY -> FORWARD (ProkNet Lab v0.4.0)

Date: 2026-09-13
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, released, NOT yet tested on real phones** (Mike has the APK; needs three phones)

History: M1 v0.1.0 passed 2026-09-12. M2A v0.2.0 (queue + receipts) passed
2026-09-13. M2B v0.3.0 (foreground service) passed 2026-09-13: delivery with
the sender's screen off and app swiped away. Earlier reports: git history
(`4e394d7`, `db703ca`, `c3619cf`).

## 1. What I built

| Requirement | Done | How |
|---|---|---|
| Final destination ID in the packet | yes | Packet v2: 16-byte destination after the 16-byte origin |
| Preserve original sender/origin ID and message ID | yes | origin and message ID are set once by the origin and copied unchanged by the relay |
| TTL and hop count | yes | 1 byte each; default TTL 3; relay increments hops when forwarding |
| Packet not addressed to B is stored as carried | yes | `direction=carry, status=carrying` row; log `ACCEPTED FOR RELAY ... CARRYING` |
| When B later sees the exact destination, forward automatically | yes | scanner reappearance hook -> `DESTINATION SEEN` -> `FORWARDING` -> `FORWARDED` |
| C shows it as received from A, not from B | yes | stored with `peer_name=prok-A`, `via=prok-B`, `hops=1`; list shows `<- prok-A [received via prok-B, 1 hop]` |
| No duplicate storage/delivery, by message ID + origin | yes | unique index `(msg_id, direction, peer_id)` where peer_id is the origin for received and carried rows; cross-direction check before accepting custody; receipt DUPLICATE |
| Hop receipt = "B accepted custody", not "C received it" | yes | new receipt code `ACCEPTED_RELAY` (3); A stores `handed_off`, never `delivered` |
| Visible states handed off / carrying / final delivered | yes | A: `[handed_off via prok-B, not final]`; B: `~ carrying prok-A -> prok-C [carrying]` then `[forwarded to prok-C]`; C: `[received via prok-B, 1 hop]`; direct sends stay `[delivered]` |
| Logs: ACCEPTED FOR RELAY, CARRYING, DESTINATION SEEN, FORWARDING, FINAL RECEIVED | yes | exactly those strings, plus `HANDING OFF`, `HANDED OFF`, `FORWARDED`, `REFUSING custody` |
| No general flooding | yes | a relay offers a carried packet only to its exact destination; a relay refuses any packet with hops >= 1 |
| Background service kept | yes | untouched from v0.3; carried packets use the same wake lock and service |
| Kotlin only | yes | |

Also needed to make "A knows C's ID" true without typing: the scan response
now carries the **full 16-byte ID** (advertising v2), so any phone that has
ever seen C has C's real ID in its peers table and can address a packet to it.

Not added: encryption, Wi-Fi Direct, Internet sharing, wallet, payments,
routing optimisation, multi-relay.

## 2. Architecture / technology choices and why

**Handoff policy (origin side).** In `DeliveryQueue.pump()`: if the
destination is in range, send direct (unchanged behaviour, `delivered`).
Otherwise, if any other peer is in range, hand off to the strongest one
and stop: state `handed_off`, the origin does not keep trying. One custody
transfer per message keeps 2C1 small and makes the three-phone test
unambiguous (only B can deliver to C).

**Relay policy.** A relay stores a packet only when hops == 0 (it came from
the origin) and hops + 1 <= TTL. It forwards only to the exact destination,
with hops = 1. It refuses (REJECTED) packets that already went through a
relay, so a packet can never bounce between phones. The origin treats a
refused handoff as "keep pending, maybe another relay later".

**Receipt semantics extended, not changed.** `ACCEPTED` still means "the
destination stored it". The new `ACCEPTED_RELAY` means "custody accepted".
`DUPLICATE` now covers received and carried rows. The sender maps the code
to the right state for the kind of attempt it made (direct, handoff, forward).

**Identity of a message = (origin, message ID).** The SQLite unique index is
`(msg_id, direction, peer_id)`; `peer_id` is the origin for received and
carried rows, so this is exactly message ID + origin per direction. Before
accepting custody the relay also checks across directions (has it already
received or carried this?).

**Full ID in the scan response.** 2 + 2 + 1 + 16 = 21 bytes, inside the
31-byte scan-response budget. This removes the need for a GATT read of the
IDENTITY characteristic to address a peer. v1 scan responses (short ID only)
still parse; such peers are addressed by short ID with a zero tail, which
the receiver matches on its short ID.

**Schema v3** adds `dest_id, origin_id, via, ttl, hops` and migrates v0.2/v0.3
pending rows to short-ID addressing so they still go out.

Full detail: `docs/ARCHITECTURE.md` ("Packet v2", "Delivery receipt",
"One-relay STORE -> CARRY -> FORWARD").

## 3. Files / components added or changed

```
app/src/main/java/net/prok/proknet/
  core/Packet.kt            v2 layout, isFor(), nextHop(), destFromShort(), v1 decode kept
  core/MessageStore.kt      schema v3, Dir/MsgStatus with carry states, carrying(), knows(),
                            retryNowFor by destination, migration of old pending rows
  ble/BleConstants.kt       RECEIPT_ACCEPTED_RELAY, DeliveryResult.RELAYED, ADV_VERSION 2, Peer.fullId
  ble/BleAdvertiser.kt      scan response = [2][fullId x16]
  ble/BleScanner.kt         parses adv v1 and v2, fills Peer.fullId
  ble/GattServerNode.kt     onPacket returns a receipt code; logs origin/dest/hops
  ble/BleSender.kt          maps ACCEPTED_RELAY -> RELAYED
  ble/DeliveryQueue.kt      forward carried packets first; direct / HANDOFF / FORWARD attempts;
                            result mapping per attempt kind
  ble/ProkNetNode.kt        FINAL RECEIVED vs ACCEPTED FOR RELAY decision, custody refusal rules,
                            via lookup, full-ID addressing, carry count in status
  ui/MainActivity.kt        new states in the list, carried packets in the diagnostic text
app/build.gradle.kts        versionCode 4, versionName 0.4.0
README.md, docs/ARCHITECTURE.md, docs/TESTING.md (section 9), CLAUDE_REPORT.md
```

## 4. Exact APK path

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.4.0
Build 4: 0.89 MB, SHA256 `1752a9f652d1fec28b6460b199b21b6cf3f7886e2d21b5afcf9f0ff065694452`,
versionCode 4, versionName 0.4.0. Installs over v0.3, data kept and migrated.

## 5. Exact build command

```
powershell -ExecutionPolicy Bypass -File C:\Projects\ProkNet\build.ps1
```
Unchanged. About 65 s.

## 6. What Mike needs to do on his phones

Install v0.4.0 on **three** phones: A (origin), B (relay), C (destination).
Let all three see each other once with the app running (this is how A
learns C's full ID). Then `docs/TESTING.md` section 9. The one that matters (9.1):

1. Turn C off. Wait until A and B list C as NOT IN RANGE.
2. On A, tap C's NOT IN RANGE entry, send "relay test". A shows
   `[handed_off via prok-B, not final]`. B shows `~ carrying prok-A -> prok-C [carrying]`.
3. Turn A off completely.
4. Turn C on. B forwards by itself. C shows
   `<- prok-A [received via prok-B, 1 hop]: relay test`, exactly once.
5. Copy log from B and from C.

Then 9.2 (direct still wins when C is in range), 9.3 (one copy), 9.4
(B restart keeps the carried packet), 9.5 (B does not hand it to anyone else).

## 7. Known limitations

- **One relay.** A packet that already passed one relay is refused by any
  other relay. If B never meets C, the message sits in B for 48 h and expires.
  A does not know; A only knows B has custody.
- **Origin stops after handoff.** If A later meets C directly, A does not
  deliver (state is `handed_off`). By design for 2C1, to keep the test
  unambiguous; easy to change.
- **Relay choice is "strongest signal that is not the destination"**, once.
  No knowledge of whether B is likely to meet C.
- Carried packets are plaintext on B, readable in B's list. Encryption is a
  later milestone; this is the milestone that makes it urgent.
- Old phones on v0.3 cannot be relays (no ACCEPTED_RELAY code); they would
  store a v2 packet as their own received message. All phones must be on v0.4.
- Names: still `prok-<shortId>` everywhere.

## 8. What failed or is uncertain

- **Untested on hardware**, and this is the first milestone that needs three
  phones. Compiles, manifest verified, logic reviewed. One bug was caught in
  review before release: rows queued under v0.2/v0.3 had no destination and
  would have encoded a corrupt packet; fixed with a migration and a guard.
- **Uncertain: timing of DESTINATION SEEN on B** when C comes back. The scanner
  notices within ~5 s; the forward attempt then follows the normal
  connect/MTU/write/read chain. Expect 10-30 s.
- **Uncertain: scan-response size on some chipsets.** 21 bytes of manufacturer
  data is inside the spec, but if a phone rejects the v2 scan response the
  log will show `advertising FAILED: DATA_TOO_LARGE`. Fallback would be a
  GATT read of IDENTITY; not implemented until seen.
- Two compiler warnings unchanged (deprecated read callback override, unused
  parameter). Harmless.
- VPS disk 1.73 GB free; Windows Update cache cleanup still pending on Mike's
  side. Repo still private.

## 9. Git commit hash

Code + docs: `cbca1afdac42eaf56ad7cfdceb662317fec6293f` on `main`.
Release tag `v0.4.0` points at it. This report is committed on top.

## 10. Recommendation for the next step

Wait for the three-phone test. If 9.1 passes, ProkNet has done STORE,
CARRY and FORWARD end to end, and the next decisions are architectural
rather than mechanical. In order of value:

1. **Encryption (2D).** Carried packets now sit in plaintext on strangers'
   phones. Replace the random 16-byte identity with a key pair (X25519 +
   Ed25519 are available on Android without extra libraries via
   `java.security`/`javax.crypto` on API 26+, or a small vendored library),
   put the public key in the scan response or IDENTITY characteristic,
   encrypt text end to end (origin -> destination) and leave the header in
   the clear for routing. This is the last thing that must be in place before
   anyone real uses it.
2. **Relay rules v2 (2C2).** Allow more than one relay with hop limit,
   let the origin also deliver directly after a handoff (destination
   dedupes), and expire custody sensibly. Still no flooding: offer only to
   the destination or to peers that have recently seen the destination
   (a "last seen" table exchanged on contact).
3. **The Python question.** 2C2 is the point where the policy (whom to hand
   to, what to keep, how long) becomes real logic. If Python is wanted, it
   should be introduced there, as a pure policy module with the Kotlin BLE
   layer unchanged.

My recommendation is 1 before 2.
