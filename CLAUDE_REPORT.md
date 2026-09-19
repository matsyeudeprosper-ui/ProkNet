# CLAUDE_REPORT - ProkNet v0.9.25 "creating a group is its own stage"

Date: 2026-09-19
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 191/191 automated tests pass, released, not yet tested on phones.
Home Wi-Fi resale is NOT claimed. The reversed topology has NOT been tested yet.**

## 1. What v0.9.24 proved

The right path at last, on both phones. The buyer forced Wi-Fi Direct with no
`WIFI_REQUEST`; the seller joined as a client while staying on the Freebox
with its upstream validated; the cancel cleanup worked and the seller kept
sharing. None of that is touched here.

## 2. The blocker, and your reading is right

```
createGroup accepted (default band), waiting for the group to form
connection: formed=false role=NONE groupOwner=null
keeping Wi-Fi Direct discovery alive: the group is gone, this phone can look for peers again
starting peer discovery from a clean state
discoverPeers failed: BUSY (framework busy)
```

After an accepted `createGroup()`, Android may report `groupFormed = false`
while the group is still being created. `onConnectionInfo` read that as
"gone", released the radio and started discovery against Android's own
creation. BUSY in a loop, no group, and a SEARCH_FAIL that blamed peer
visibility for a group that never existed.

## 3. The fixes, one per requirement

1. **CREATING_GROUP owns the radio.** `P2pPlan.onFormedFalse(stage,
   createAccepted)` returns HOLD while a creation is in flight; the link
   logs `group creation pending: formed=false is normal while
   CREATING_GROUP` and does nothing else. Only `formed = true`, an explicit
   refusal, or the formation clock end a creation.
2. **An accepted-but-never-formed clock.** Each acceptance arms
   `GROUP_FORMATION_TIMEOUT_MS` (15 s). `P2pPlan.onFormationTimeout(stage,
   formed, attempt, max)` gives FORMED, IGNORE, RETRY or FAIL. A retry removes
   the half-made group and creates again, and the third silence fails as
   `GROUP_CREATE_FAIL`, a stage of its own with its own French sentence,
   never as peer visibility.
3. **No discovery before the owner group exists.** Discovery for the owner
   starts only in the `formed = true` branch, logged as `starting peer
   discovery for reversed admission`.
4. **No provider-group ladder for an owner.** `P2pPlan.asksProviderGroup`
   is false in BUYER_GROUP_OWNER; the buyer neither sends `P2P_REQUEST` nor
   waits for `P2P_STATUS`.
5. **Separate clocks.** `P2pPlan.searchedMs(...)` counts the owner's search
   only from the moment its group formed; the owner's transport clock starts
   at membership. Creation, search, association and transport are four
   stages with four clocks.
6. **Seller behaviour preserved.** The reversed join, the Freebox invariant
   and the one-unit cleanup are untouched.

## 4. Logs

Success: `createGroup attempt 1 accepted`, `group creation pending: ...`,
`GROUP FORMED after createGroup attempt 1: role GROUP_OWNER`, `GROUP
CHANNEL`, `local P2P address=192.168.49.1`, `starting peer discovery for
reversed admission`. Failure: `no group formed within 15s ... retrying group
creation attempt 2/3`, then `GROUP CREATE FAILED: Android accepted
createGroup three times but no Wi-Fi Direct group formed`.

## 5. Tests (191, +6)

`formed=false` while creating is HOLD, and GONE in every other state. A
group that forms in time is FORMED and a clock firing after the stage moved
on is IGNORE. Never-formed attempts are RETRY, RETRY, FAIL, filed under
GROUP_CREATE_FAIL with French that mentions the Wi-Fi. An owner never asks
the provider about its group; a joining customer still does. The owner's
search clock is zero until its group forms and counts from there; a joining
customer's counts from the purchase. The reversed cleanup leaves sharing and
the upstream alone.

## 6. Build

Build 38, versionName 0.9.25, 1.31 MB,
SHA256 `5ab4c8d3d15c458cc9042db2b861161bc720e6ed2c2186966843c8ee72dd51f1`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.9.25
Commit `cf6f5e9` on `main`; this report on top.

## 7. The next hardware test

`docs/TESTING.md` section 43. The first proof is only the buyer reaching
`role GROUP_OWNER` at `192.168.49.1`, with no discovery and no provider
question between the accepted createGroup and the group forming. Then the
seller as CLIENT with the Freebox still there. Only then the probe.

## 8. The claim rule

Unchanged, section 30. Nothing is claimed until the reversed topology is
proven on hardware.
