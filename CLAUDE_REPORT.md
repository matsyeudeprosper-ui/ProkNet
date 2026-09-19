# CLAUDE_REPORT - ProkNet v0.9.26 "the admission plane is dormant while the owner creates its group"

Date: 2026-09-19
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 195/195 automated tests pass, released, not yet tested on phones.
Home Wi-Fi resale is NOT claimed. Whether the OnePlus can own a group is NOT concluded.**

## 1. What the run showed

The v0.9.25 formation ladder worked exactly as designed: three accepted
attempts, three fifteen-second silences, one specific `GROUP_CREATE_FAIL`.
And the leak you found is real:

```
12:03:48  createGroup attempt 3 accepted
12:03:48  group creation pending: formed=false is normal while CREATING_GROUP
12:03:49  admission: ... group formed=false role=NONE -> GUEST_CONNECT
12:03:55  starting peer discovery from a clean state
12:04:03  GROUP CREATE FAILED
```

`onConnectionInfo` knew that creation owns the radio. `onP2pVisibility` did
not: it decided a plan with no group and no role, sent it, and its follow-up
resumed discovery against `createGroup()`. So the three failures are not yet
evidence about the phone. They may be evidence about us.

## 2. The fix: one gate, asked everywhere

```
P2pAdmission.admissionAllowed(topology, providing, stage, groupFormed, role)
  owner of the group:  stage == GROUP_OWNER && groupFormed && role == GROUP_OWNER
  guest:               stage != CREATING_GROUP
```

- `onP2pVisibility`: while the gate says no, the report is remembered and the
  phone logs `admission deferred: buyer-owned group is still being created`.
  No plan, no JOIN_PLAN message, no invitation, no connect, no discovery, no
  clock. When the group forms, the deferred report is evaluated as if it had
  just arrived.
- `onP2pJoinPlan`: ignored while the gate says no.
- the owner's wait step: while the gate says no it only re-schedules itself,
  so the search clock stays at zero and nothing resumes discovery.
- `P2pLink.keepDiscovering`: a hard net under every caller, refusing while
  the stage is CREATING_GROUP and logging `not starting discovery: group
  creation owns the radio`.

The seller's visibility loop is not touched: it keeps reporting every eight
seconds while it waits, on the Freebox, and the owner simply defers until it
can act. Production is gated the same way and behaves the same: a provider
whose group is still coming up defers, which is the v0.9.11 REBUILDING rule
in different words, and a customer that joins is never creating anything.

## 3. Preserved

The v0.9.24 seller cleanup, the v0.9.25 formation retry ladder and its
GROUP_CREATE_FAIL, the separate clocks, symmetric admission, the no-guessing
rule, the data plane, the probe, and everything above them.

## 4. Tests (195, +4)

An owner admits nobody while creating, cleaning, or with the stage saying
owner but no group; it admits once the group exists with it as owner. The
provider as a guest is not gated by creation and its loop stays alive. A
visibility received before formation produces, after formation, exactly the
plan it always would. Production is gated identically: the provider decides
once its group exists, defers while it is coming up, and the joining
customer is never blocked.

## 5. Build

Build 39, versionName 0.9.26,
SHA256 `0819e8526f0ff2c87361d915067e3ea46e3a388689e26b8a2fbb1c544979fcfd`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.9.26
Commit `85348d8` on `main`; this report on top.

## 6. The next hardware test, unchanged

`docs/TESTING.md` sections 43 and 44. First proof only: the OnePlus reaches
`role GROUP_OWNER` at `192.168.49.1` with nothing but `pending`, `deferred`
and `owns the radio` lines between the accepted createGroup and the group
forming. If it still fails three times with that window clean, THEN the
evidence is about the phone.

## 7. The claim rule

Unchanged, section 30.
