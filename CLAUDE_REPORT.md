# CLAUDE_REPORT - ProkNet v0.6.1 "tunnel drop fix"

Date: 2026-09-13
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 71/71 automated tests pass, released, NOT yet retested on phones**

v0.6.0 phone result: discovery, `[NET]`, WIFI UP all fine; right after
**Use Internet** the buyer went `DISCONNECTED`, Wi-Fi `DOWN`, last error
`Wi-Fi link closed: tunnel write failed: null`. Reproducible.

## 1. Root cause (found by inspection, matches every symptom)

The first tunnel frame, SESSION_START, was written to the link socket from
the **Android main thread**: link-up is reported through a main-thread
callback, which started the session, which called `WifiTransport.sendTunnel`,
which wrote to the socket directly. Android throws
`android.os.NetworkOnMainThreadException` for that, and that exception has a
**null message**, hence "tunnel write failed: null". The catch block then
tore the link down. Every v0.5 write went through worker threads (the
transfer sender runs on an executor), so the tunnel was the first path to
hit it. The keepalive ticker (main thread) and the provider's
UPSTREAM_STATE notification (main thread) had the same bug and would have
failed next.

Also confirmed while inspecting: the read side already handled frame type 5
correctly, `MAX_FRAME` had been raised, and starting Use Internet did not
otherwise touch the link; the teardown was purely the write exception.

## 2. Fix

- `core/LinkIo.kt` (new, pure): framing, a reader thread, and a **writer
  thread fed by a bounded queue** (1024 frames). Data threads enqueue with
  backpressure; the main thread enqueues without blocking (a full queue
  drops and logs). No caller writes to the socket any more, on any thread.
  `Handshake` (HELLO/AUTH) moved next to it; `Identity` implements `Signer`.
- `WifiTransport.Link` delegates to `LinkIo`; `sendTunnel` only encodes and
  enqueues; receipts and packets use the same writer.
- Error text: `LinkIo.describe(e)` = exception class + message + first 4
  stack frames, used for link failures, TCP connect failures, handler
  crashes and writer stops. "null" can no longer appear alone.
- Writer failures close the link with the real reason and report it to the
  owner; reader end-of-stream is reported to the owner once.

Crypto, routing, transfers, v0.5.1 join behaviour, VPN stack, gateway:
unchanged.

## 3. New tests (LinkIoTest, over real loopback sockets on the JVM)

| Test | What it proves |
|---|---|
| `handshake_over_loopback_verifies_both_identities` | HELLO/AUTH both ways, names and IDs come out right |
| `handshake_rejects_a_forged_record_and_a_wrong_signature` | an impostor with B's record but A's key is refused with `INVALID` |
| `session_start_sent_immediately_after_link_up_arrives_as_a_tunnel_frame` | **the failing sequence**: non-blocking enqueue right after the handshake, frame arrives on the other side, link stays open, close is reported |
| `tunnel_frames_flow_both_ways_including_large_and_many` | 16 KB frame, reply, 500-frame burst in order, byte counters agree |
| `non_blocking_enqueue_drops_when_full_and_closed_link_refuses` | main-thread path never blocks; closed link refuses |
| `malformed_length_closes_the_link_with_a_reason_and_peer_close_is_reported` | garbage length -> `bad frame length N`, owner notified |
| `describe_never_yields_just_null` | error text always carries the class |

Total 71, all green; APK gated as before.

## 4. UI

- **COPY LOG** button at the top of the screen (full diagnostic + full log).
- **COPY DIAG** next to Net test: everything above the log plus the last
  120 log lines, short enough to paste into a chat.
- The full scrolling log stays at the bottom.

## 5. Retest

`docs/TESTING.md` section 13, unchanged: B Provide Internet (data ON), A
Use Internet (data OFF), CONNECT if asked, OK on the VPN dialog, Net test,
Chrome to https://example.com. Send COPY DIAG from both phones.

## 6. Exact APK / release

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.6.1
Build 9: 1.02 MB, SHA256 `7ff40e501e80dc90db03c91520206a1aaae9a4e60741bca07f5171225c6a1ee9`,
versionCode 9, versionName 0.6.1. Commit `41a6936f0ed79eee44ef13d5746ee2a3cf890f0a`
on `main`; this report on top.

## 7. What could still go wrong on the phones, in order

1. VPN stack vs the Android kernel (`TcpFlow`), now reachable for the first
   time. Net test separates it from the tunnel: if Net test passes and Chrome
   fails, it is the TCP endpoint; the log will show the flow states.
2. Provider upstream selection on OnePlus (the `UPSTREAM ... all=[...]` line
   shows every network with its flags).
3. Throughput: one writer thread per link, 16 KB frames; fine for browsing.
