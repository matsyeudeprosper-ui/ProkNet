# CLAUDE_REPORT - Milestone 2C1-HARDENING (ProkNet Lab v0.4.1)

Date: 2026-09-13
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 23/23 automated tests pass, released, NOT yet tested on phones** (three phones needed; v0.4.0 was never tested and is superseded)

History: M1 v0.1.0 passed 2026-09-12. M2A v0.2.0 passed 2026-09-13.
M2B v0.3.0 passed 2026-09-13. M2C1 v0.4.0 built 2026-09-13, untested.
Earlier reports: git history (`4e394d7`, `db703ca`, `c3619cf`, `2867eda`).

## 1. What I built

### 1.1 Explicit last-hop identity (packet v3)

| Rule | Done | Where |
|---|---|---|
| lastHopId field in the wire packet | yes | `Packet.kt`: 16 bytes at offset 36, header now 72 bytes, text max 440 |
| originId never changes | yes | set by the origin; `stamped()` copies it; test `origin_destination_and_message_id_never_change_through_a_relay` |
| destinationId never changes | yes | same |
| messageId never changes | yes | same |
| lastHopId = the phone physically transmitting this hop | yes | `Routing.outgoingPacket()` stamps `myId` on every transmission: A->B = A, B->C = B; test `last_hop_changes_A_to_B_to_C_and_hops_increment` |
| C displays "received from A via B" | yes | `ProkNetNode.onPacketReceived` takes `via` from `pkt.lastHopShort`; list shows `<- prok-A [received via prok-B, 1 hop]` |
| No Bluetooth MAC address in routing/accounting | yes | the only remaining use of the address is the GATT server's per-connection receipt map (transport bookkeeping) and a debug note in one log line |
| Backward decoding | yes | v2 (no last hop -> "?") and v1 (no destination -> addressed to receiver) decode; tests `v2_packets_still_decode_with_unknown_last_hop`, `v1_packets_still_decode_as_addressed_to_the_receiver` |

### 1.2 Automated protocol/routing tests on the VPS

All routing decisions were extracted into `core/Routing.kt`, a pure Kotlin
object with no Android, BLE or database imports. `DeliveryQueue` and
`ProkNetNode` now only feed it facts and execute its answers. 23 JUnit 4
tests in `app/src/test/java/net/prok/proknet/core/` run on the VPS JVM.
No Robolectric, no emulator, no mocking library.

| Required test | Test name(s) |
|---|---|
| packet encode/decode round trip | `encode_decode_round_trip_keeps_every_field`, `max_text_fits_in_512_bytes_and_longer_is_refused` |
| origin/destination/message ID unchanged through relay | `origin_destination_and_message_id_never_change_through_a_relay`, `outgoing_packet_never_alters_identity_...` |
| lastHop changes A -> B -> C | `last_hop_changes_A_to_B_to_C_and_hops_increment`, `end_to_end_A_hands_to_B_who_forwards_to_C_...` |
| hop count increments | same two |
| TTL rejection | `ttl_exhausted_is_rejected_for_relay_but_not_for_destination` |
| direct delivery wins when destination present | `direct_delivery_wins_when_destination_is_present` |
| handoff produces handed_off, never false delivered | `handoff_produces_handed_off_never_delivered` |
| relay accepts custody | `destination_accepts_final_and_relay_accepts_custody` |
| relay forwards only to exact destination | `relay_forwards_only_to_the_exact_destination` |
| one-relay limit enforced | `one_relay_limit_a_second_relay_refuses_custody`, end-to-end test (phone D refuses) |
| duplicate packet not delivered twice | `duplicate_packet_is_not_delivered_twice` (direct retry, relayed copy, relay-side duplicate) |
| malformed/truncated packets rejected without crashing | `malformed_and_truncated_packets_decode_to_null_without_throwing` (every truncation length of a valid packet + 500 random blobs), `malformed_packet_is_rejected_not_crashed` |
| old format decoding/migration safe | `v2_packets_still_decode_...`, `v1_packets_still_decode_...`, `legacy_destination_padding_matches_the_receiver_by_short_id` |
| extra | `carried_packets_go_before_own_messages_and_backoff_is_respected`, `direct_and_forward_results_map_to_final_states`, `transport_failures_back_off_then_give_up`, `handoff_goes_to_strongest_other_peer_when_destination_absent`, `isFor_matches_full_id_and_short_id_form_only` |

**Build gate:** `build.ps1` now runs `testDebugUnitTest` before
`assembleDebug`. A failing test fails Gradle, the script throws, and no APK
is copied to `dist\`. It also refuses if zero tests ran. Summary written to
`dist\test-results.txt`. Verified: the first run of this pass had one
failing test (a bug in the test itself: random garbage arrays could be
shorter than 2 bytes) and the script correctly refused the APK.

**Result of the final run:** `total=23 failed=0 errors=0 skipped=0`
(PacketTest 9, RoutingTest 14), 44 s including the APK.

### 1.3 Static robustness review: issues found and fixed

| Area | Finding | Fix |
|---|---|---|
| Malformed packets | `Packet.decode` could throw on a truncated buffer (BufferUnderflow) and accepted trailing bytes | strict decode: exact length, known version and type, whole body in try/catch -> null; fuzz-tested |
| Malformed packets | an exception inside the packet handler (store, decision) would crash the Bluetooth binder thread | `GattServerNode.handle` wraps `onPacket` in try/catch and answers REJECTED |
| Malformed scan records | `BleScanner.onResult` could throw on an odd manufacturer record and kill the scan callback | guarded, logged, ignored |
| Stale BLE addresses | relay identity ("via") was derived by matching the GATT client's address against the scanner's peer list; wrong when addresses rotate or differ between roles | replaced by the packet's lastHopId (the point of this milestone) |
| Stale BLE addresses | sending uses the live `Peer` object whose address the scanner updates | reviewed, unchanged; planner only ever targets in-range peers, never a stored last address |
| Duplicate receipt races | receipt is written before the write response and cleared on disconnect; a receipt read on a fresh connection without a write returns REJECTED with a zero message ID, which the sender maps to NO_RECEIPT (retry), not to a false success | reviewed, unchanged, now covered by `resultFor` tests |
| Duplicate detection | `MessageStore.knows()` matched ANY outgoing row with the same message ID regardless of origin | now matches outgoing rows only when their origin is the packet's origin |
| Restart during custody | `forwarding` rows were reset to `carrying` and `sending` to `pending` on start | reviewed, unchanged; service is START_STICKY |
| Destination appears during a handoff | the in-flight handoff completes (handed_off); if it fails, the next plan prefers DIRECT because the planner checks the destination first | reviewed; behaviour documented and tested (`direct_delivery_wins_...`) |
| Relay disappears during transfer | transport failure -> pending with backoff; next plan may choose another relay or direct; relay side drops a half-received prepared write on disconnect | reviewed, unchanged |
| Rows from v0.2/v0.3 | no destination stored -> corrupt packet (found and fixed in v0.4.0) | migration pads dest with zeros; `Routing.destBytes` guards again; a row that still cannot build a packet is marked failed instead of crashing the queue |
| Migration v0.3 -> v0.4.1 | schema v3 unchanged from v0.4.0; lastHop is stored in the existing `via` column | reviewed; SQLite itself cannot run on the JVM without Robolectric, so the SQL is reviewed, not unit-tested |
| Full-ID advertisement failure | a chipset that rejects the 21-byte scan response would leave the phone undiscoverable | `BleAdvertiser` retries with the 4-byte short-ID payload on DATA_TOO_LARGE; such a phone is addressed in short-ID form, which the receiver matches |
| Peers without a scan response | showed as `prok-?xxxxxx` and could be selected; sending would throw in hex parsing | listed as "(no ID yet)", cannot be a destination, never remembered as a known peer, never used as relay |
| Handoff to a peer that turns out to be the destination | receipt ACCEPTED on a HANDOFF | mapped to `delivered` (trust the receipt), tested |

## 2. Architecture / technology choices and why

- **Last hop in the packet, not inferred.** A 16-byte field costs 16 bytes
  of the 512-byte budget and removes a whole class of wrong-attribution bugs.
  The header is now 72 bytes; 440 bytes of text remain.
- **Pure routing module.** Every decision the three-phone test depends on
  is now a function of plain values, so it can be tested on the VPS in
  seconds. The BLE classes shrank; nothing about the air interface changed
  except the packet version.
- **JUnit 4 only.** One test dependency (`junit:junit:4.13.2`). The simulated
  phones in `RoutingTest` are 15 lines each and mirror exactly what the SQLite
  unique index does.
- **Fail closed.** The build script refuses the APK on a failing test and on
  zero tests executed, so a broken test configuration cannot silently pass.

## 3. Files / components added or changed

```
app/src/main/java/net/prok/proknet/
  core/Packet.kt            v3 with lastHopId, stamped(), strict never-throwing decode, v1/v2 kept
  core/Routing.kt           NEW  DeliveryResult, decideReceive, receiptFor/resultFor, plan, applyResult,
                                 outgoingPacket, destBytes, legacyDestHex, backoff constants
  core/MessageStore.kt      knows() origin-scoped
  ble/BleConstants.kt       receipt codes delegate to Routing; ADV_VERSION_SHORT; Peer.hasId
  ble/BleAdvertiser.kt      DATA_TOO_LARGE -> short-ID payload fallback
  ble/BleScanner.kt         guarded callbacks
  ble/BleSender.kt          uses Routing.resultFor
  ble/GattServerNode.kt     guarded handler, logs wire version and last hop
  ble/DeliveryQueue.kt      executor only; Routing.plan / applyResult / outgoingPacket
  ble/ProkNetNode.kt        Routing.decideReceive; via = lastHop; sendText refuses ID-less peers
  ui/MainActivity.kt        toast when a peer cannot be addressed
app/src/test/java/net/prok/proknet/core/PacketTest.kt    NEW  9 tests
app/src/test/java/net/prok/proknet/core/RoutingTest.kt   NEW  14 tests
app/build.gradle.kts      junit dependency, unit test options, versionCode 5 / 0.4.1
build.ps1                 tests gate the APK, dist/test-results.txt
README.md, docs/ARCHITECTURE.md, docs/TESTING.md (sections 9 and 10), CLAUDE_REPORT.md
```

## 4. Exact APK path

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.4.1
Build 5: 0.86 MB, SHA256 `f2d92d9c428662e390a1743c47a7cfe639e93dd818efa3b26289e04cdd91202d`,
versionCode 5, versionName 0.4.1. Test summary: `C:\Projects\ProkNet\dist\test-results.txt`.

## 5. Exact build command

```
powershell -ExecutionPolicy Bypass -File C:\Projects\ProkNet\build.ps1
```
Now runs the unit tests first. `-SkipTests` exists for emergencies only and
marks the summary file as skipped. About 45-75 s.

## 6. What Mike needs to do on his phones

Nothing new to learn: install v0.4.1 on all three phones (v0.4.0 must not
be mixed in: different wire version) and run `docs/TESTING.md` section 9
when the third phone is available. Test 9.1 is unchanged. The new thing to
look for in C's log is `FINAL RECEIVED ... via relay prok-B after 1 hop(s)`
where prok-B is B's real short ID, and in B's log
`ACCEPTED FOR RELAY ... (handed by prok-A)`.

## 7. Known limitations

- Unchanged from 2C1: one relay only, origin stops after handoff, relay
  choice is "strongest other peer", carried packets are plaintext.
- The SQLite migration and the BLE layer are reviewed but not unit-tested
  (would need Robolectric or a device).
- Two harmless compiler warnings remain (deprecated read callback override,
  unused parameter).

## 8. What failed or is uncertain

- One test failed on the first run; it was a bug in the test (random blobs
  of length < 2). Fixed; the gate behaved correctly (no APK).
- Still untested on hardware. The remaining risk for the first three-phone
  try is BLE timing and vendor behaviour, not routing logic.
- VPS disk 1.71 GB free. Repo still private.

## 9. Git commit hash

Code + tests + docs: `3c496ae8168ca837c6427629596704149aaa60c7` on `main`.
Release tag `v0.4.1` points at it. This report is committed on top.

## 10. Recommendation for the next step

Run the three-phone test on v0.4.1 when the third phone exists. Until then,
nothing else should change on the wire. If you want more confidence before
that day, the one addition worth its cost is a **two-phone rehearsal of the
relay path**: with only A and B, A sends to a known-but-absent C (C's ID is
in A's peers table from any earlier contact). Expected: A `handed_off via
prok-B`, B `carrying ... for prok-C`, B never forwards. That exercises
handoff, custody receipt, carrying persistence and the no-flooding rule with
two phones; only the final forward needs the third. I added nothing for this;
it works with the current build.

After 2C1 passes: encryption (2D) before any relay-policy work, as argued
in the previous report.
