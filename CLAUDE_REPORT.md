# CLAUDE_REPORT - ProkNet v0.10.2 "the sequential probe"

Date: 2026-09-19
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 218/218 automated tests pass, released, hardware test pending.
One small fix to the probe, one simple test screen. No architecture change.
Bluetooth Internet is NOT claimed yet.**

## 1. What v0.10.1 proved on the phones

The seller stayed on the Freebox, no hotspot probe, no Wi-Fi Direct.
L2CAP connected, the signed authentication passed, BULK UP. Seller ->
buyer carried the full 1 MB. Buyer -> seller carried 696,320 / 802,816 /
966,656 B across three runs and was then killed by the 40 s clock. So the
link is bidirectional and the probe was the problem: two 1 MB streams
competing for one ~30-34 KB/s channel under a single timeout, and a verdict
that called a 966 KB direction "carried no bytes".

## 2. The probe is sequential and smaller

```
PROBE BUYER_TO_SELLER   buyer sends 256 KB   -> seller confirms full receipt
PROBE SELLER_TO_BUYER   seller sends 256 KB  -> buyer confirms full receipt
PROBE COMPLETE          VERDICT: BIDIRECTIONAL -> contract -> tunnel -> VPN -> Internet test
```

- `BulkPlan.probeSender(step, isHost)`: the buyer (client) sends in step
  1, the seller (host) in step 2, nobody outside a step. Tested: in every
  step exactly one side sends.
- Direction 2 does not start until the receipt report of direction 1 has
  arrived. The seller writes its confirmation BEFORE its own payload, in
  the same io task, so the buyer always sees the report first.
- Per-direction clocks: the receiver waits 30 s, the sender 36 s. A
  receiver that times out reports what did arrive, so both phones show the
  same bytes.
- Every probe state change, including the byte count, runs on the main
  thread. The old counter lived on the read thread and a direction change
  could reset it under incoming frames; that race is gone.
- 256 KB at the measured 30 KB/s is about 9 s per direction, well inside
  the clock.

## 3. Verdicts stopped lying

Per direction: `0 B = NO_DATA`, `1 B .. target-1 = PARTIAL`, `target =
PASS`. The link verdict is the result of the first direction that did not
pass, and it names the direction:

```
buyer -> seller: 243,712 / 262,144 B PARTIAL (timeout)
seller -> buyer: not run
VERDICT: PARTIAL, buyer -> seller timed out
```

The v0.10.1 numbers are a test case: a 966 KB direction is PARTIAL, never
"nothing". `Verdict` is now `NOT_RUN / BIDIRECTIONAL / PARTIAL / NO_DATA`;
only BIDIRECTIONAL proceeds, as before.

## 4. Not chased, on purpose

`bad frame length 0` and `EOFException` followed the old timeout
cancelling the link mid-transfer. If they appear after a clean sequential
probe, they get their own investigation next.

## 5. The test screen

`BtLabText` is a pure module (Snapshot in, sentence out), 3 JVM tests
cover every sentence and the copied summary. The screen:

- Seller: `Internet source: Wi-Fi ✅`, `Bluetooth: Ready ✅`, START
  SHARING, then `Waiting for another phone...`, `Phone connected ✅ /
  Testing connection...`, `Bluetooth connection works both ways ✅ /
  Internet sharing starting...`, `Sharing Internet ✅`.
- Buyer: `Seller found ✅`, CONNECT, then `Connecting...`, `Checking both
  directions...`, `Starting Internet...`, `Testing Internet...`, `INTERNET
  WORKING ✅` with the five lines (Bluetooth, seller Internet, VPN, DNS,
  HTTPS).
- The screen starts the node and asks for permissions itself, runs the
  HTTPS test through the seller itself once the tunnel is up, asks for
  the VPN itself. A failure is one sentence.
- COPY TEST RESULT: the summary you specified, then the full diagnostic.
  COPY DIAGNOSTIC stays underneath for us.

One honest note on `DNS: working`: the app itself is excluded from its own
VPN, so that line turns green on the first DNS query another app or
Android makes through the VPN. The HTTPS line is our own test through the
seller and does not depend on that.

## 6. Tests (218, +5)

Sequential order (buyer first, one sender per step, none outside a step);
PARTIAL / NO_DATA / PASS boundaries at 0, 1, target-1, target; the named
verdict and the plain sentence for both directions; direction 2 not run
names direction 1; the seller and buyer screen sentences at every stage;
the copied summary for success, a probe failure and a Bluetooth failure.
The three v0.10.0 bulk tests and the two v0.10.1 seller-path tests still
pass unchanged.

## 7. Build

Build 43, versionName 0.10.2,
SHA256 `91a49b9a7cbaaf9d2704014b527701a6b3ae91c4d86b27f594961ba85475a803`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.10.2
Commit `c805b8b` on `main`; this report on top.

## 8. The hardware test

`docs/TESTING.md` section 48. OUKITEL: BT Lab, START SHARING. OnePlus: BT
Lab, CONNECT, wait. Then COPY TEST RESULT on both and Chrome on the buyer.

## 9. Not changed

L2CAP, the signed handshake, StreamLink, Tunnel, Gateway, VPN, contracts,
accounting, the Wire bulk negotiation, the session token, the seller
access path of v0.10.1, the mobile-data hotspot path, the developer P2P
lab. No RFCOMM.

## 10. The claim rule

A copied BUYER summary with both directions PASS and `Internet: YES`, and
a page in Chrome. Not before.
