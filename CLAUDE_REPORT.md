# CLAUDE_REPORT - ProkNet v0.9.27 "BUSY is not an attempt"

Date: 2026-09-19
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 203/203 automated tests pass, released, not yet tested on phones.
Home Wi-Fi resale is NOT claimed. Whether the OnePlus can own a group is NOT concluded: one real attempt exists, not three.**

## 1. What the run proved

The v0.9.26 gate worked. Attempt 1 was the first genuinely clean creation
on the OnePlus: accepted, fifteen seconds with nothing but `pending` and
`deferred` lines, no group, role NONE. That attempt counts.

The OUKITEL did everything right and is untouched: it had owned a group at
192.168.49.1 while on the Freebox, dropped it on request, waited as a guest
reporting what it could see, and on cancel cleared the session with sharing
still on and the upstream still validated.

## 2. What the run did not prove

```
12:20:13.617  nothing to remove (BUSY)
12:20:13.618  creating attempt 2
12:20:13.620  createGroup refused BUSY
12:20:16.631  creating attempt 3
12:20:16.636  createGroup failed after 3 attempts: BUSY
```

Attempts 2 and 3 never happened. Android was still settling attempt 1 and
answered BUSY; the counter took that for evidence. And the failure went to
stage NONE with a screen that blamed the provider.

## 3. The patch

**Two counters.** `core/P2pCreation.kt`, pure: IDLE, CREATING, FORMING,
RESETTING, DONE, FAILED. A logical attempt is one Android accepted or
explicitly refused, three of them. A reset try is the framework asking for
time, eight of them a second and a half apart. BUSY consumes a reset try,
never an attempt. Nothing is committed until Android answers.

**A real reset between attempts.** cancelConnect, stopPeerDiscovery,
removeGroup, then `requestConnectionInfo` must say `formed=false` before the
next `createGroup()`. BUSY anywhere in that sequence backs off and repeats
the reset. If the framework never settles, the machine ends in a bounded
FRAMEWORK_BUSY failure after its eight tries, and says so.

**Typed failures.** NEVER_FORMED, FRAMEWORK_BUSY, REFUSED, PERMISSION, all
prefixed `GROUP_CREATE:`, the stage recorded as a typed value on the link,
and `stageOf` recognising both the prefix and the word `createGroup`. The
v0.9.26 wording itself now files as GROUP_CREATE_FAIL.

**The screen.** The provider-busy sentence now requires the provider to
have said so. A framework that stayed busy on this phone reads "Le Wi-Fi
Direct de ce téléphone est encore occupé. Attendez quelques secondes puis
réessayez." Any other creation failure reads "Ce téléphone n'a pas réussi à
créer la connexion Wi-Fi Direct. Réessayez." Neither blames the provider.

**Late callbacks.** Each purchase carries a session token. A visibility
report from a peer with no purchase running is ignored before any gate; a
deferred report is replayed only if its token still matches; stopping a
purchase bumps the token.

## 4. Preserved

The v0.9.26 admission gate, the v0.9.25 formation window, the v0.9.24
seller cleanup, the Freebox invariant, and everything above admission.

## 5. Tests (203, +8)

An accepted attempt that times out starts a reset and stays at one; five
BUSY answers in a row still leave it at one; only a clean framework starts
attempt 2 and only Android's acceptance commits it; three real attempts
then exhaustion; a framework that never leaves BUSY ends in a bounded
FRAMEWORK_BUSY after exactly eight tries; every creation failure, including
the v0.9.26 raw wording, files under GROUP_CREATE, never NONE; a busy
framework never blames the provider while a real provider-busy refusal
still does; and a stale report after a session is not deferred.

## 6. Build

Build 40, versionName 0.9.27,
SHA256 `4f1d90691973ce3cef68b97e9877c259c39112f3ecfc22d43a4fb4173e0a613f`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.9.27
Commit `e79e055` on `main`; this report on top.

## 7. The decision rule for the next run

`docs/TESTING.md` section 45. Three lines of `createGroup attempt N
accepted`, N = 1, 2, 3, each followed by a silent fifteen seconds and `no
group formed`, with a verified clean reset between them: then this OnePlus
does not create an autonomous group in this configuration and we stop
patching this topology. Anything less is not a verdict.

## 8. The claim rule

Unchanged, section 30.
