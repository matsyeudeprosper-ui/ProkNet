# CLAUDE_REPORT - ProkNet v0.6.0 "Internet Through Another Phone"

Date: 2026-09-13
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 64/64 automated tests pass, released, NOT yet tested on phones**

v0.5/v0.5.1 phone results: identities, E2E messaging, background, BLE
fallback, WIFI UP with signed handshake, 1 MB over Wi-Fi all passed.
Earlier reports: git history (`4e394d7`, `db703ca`, `c3619cf`, `2867eda`,
`acf9ca0`, `801f71c`, `578d3b2`).

## 1. What was built

| Block | Done | Summary |
|---|---|---|
| A. Roles | yes | **Provide Internet** / **Use Internet** buttons; provider detects a real upstream (mobile data or Wi-Fi with INTERNET, validated or not), never counts the ProkNet link; shows upstream available/type/validated and PROVIDER READY |
| B. Reuse the link | yes | tunnel frames are a new frame type on the existing authenticated Wi-Fi TCP link; BLE, hotspot, TCP and signed handshake untouched |
| C. Gateway | yes | `node/Gateway.kt`: sockets from the upstream `Network.socketFactory`, DNS `bindSocket`ed to it, per-stream threads, clean close on disconnect; framed protocol with stream IDs, no per-frame receipts |
| D. VPN client | yes | `vpn/ProkVpnService.kt` + `node/TunnelClient.kt` + `core/TcpFlow.kt`/`core/Tcpip.kt`: Android `VpnService`, user-space TCP/DNS, no root, IPv4 + TCP + DNS; UDP beyond DNS dropped and reported as such |
| E. First proof + in-app test | built | **Net test**: DNS + real TLS + HTTPS GET through the provider with status, latency, bytes; independent of the VPN. Real-phone proof still pending |
| F. Capability flag | yes | scan response `[2][id 16][flags 1]`, bit 0 = providing Internet; `[NET]` in peer lists |
| G. Accounting | yes | per session: bytes up/down, streams, DNS queries, start/end, duration, peer, disconnect reason; both sides; last 20 kept in memory |
| H. Reliability | yes | keepalive 15 s / timeout 45 s; UPSTREAM_STATE on provider changes; link loss ends the session; VPN revoke ends the session; idle streams expire; states PROVIDER READY / CONNECTING / TUNNEL UP / INTERNET OK / INTERNET LOST / DISCONNECTED |
| I. Security | yes | session bound both ways to the identity authenticated by the Wi-Fi handshake; v0.5 crypto untouched |
| J. Diagnostics | yes | role, upstream, VPN, tunnel state, provider peer, active streams/flows, DNS count, bytes, duration, last error, session history, last test; Copy log |
| K. Tests | yes | 16 new (Tcpip 5, Tunnel 5, TcpFlow 6); 64 total gate the build |
| L. Scope | kept | no payments, wallet, pricing, relay rewards, multi-hop |
| M. v0.5 behaviour | kept | v0.5.1 join code unchanged; chunk/receipt transfers unchanged and not used by the tunnel |

## 2. Exact tunnel architecture

```
link frame (existing): [u32 len][type 1][payload]        type 5 = TUNNEL
tunnel payload:        [tunnel type 1][stream id u32][data <= 16384]
```

| Type | Direction | Data |
|---|---|---|
| SESSION_START | buyer -> provider | version, buyer ID (16) |
| SESSION_OK | provider -> buyer | version, provider ID, upstream type, validated |
| SESSION_END | either | reason |
| OPEN_TCP | buyer -> provider (new id) | port, host (IP literal from the packet's destination) |
| TCP_OPEN_OK | provider -> buyer | - |
| TCP_DATA | either | bytes |
| TCP_CLOSE | either | half-close from that side |
| DNS_REQUEST / DNS_RESPONSE | buyer -> provider / back, same id | raw DNS message |
| ERROR | either | code, message; id 0 = session |
| KEEPALIVE | either | sequence; echoed |
| UPSTREAM_STATE | provider -> buyer | available, type, validated |

Streams: up to 256 concurrent, ids allocated by the buyer (TCP flows and DNS
queries share the counter). Backpressure and reliability come from the
link's own TCP; a slow stream stalls the link writer, which is acceptable
for v0.6 and noted for v0.7 (per-stream flow control).

## 3. Exact VpnService approach

`VpnService.Builder`: address 10.8.0.2/24, route 0.0.0.0/0, DNS server
10.8.0.1, MTU 1500, `setBlocking(true)`, `addDisallowedApplication(own
package)`, plus `protect(link socket)`. No IPv6 address/route: apps do not
attempt v6. The TUN reader thread hands each IPv4 packet to `TunnelClient`:

- **TCP**: one `TcpFlow` per (src port, dst IP, dst port). The flow answers
  the app's SYN with SYN-ACK (MSS 1360) and sends OPEN_TCP; app data is ACKed
  and forwarded as TCP_DATA (buffered until TCP_OPEN_OK); provider data becomes
  PSH/ACK segments within the app's advertised window, retransmitted after
  1 s without ACK progress; FIN and RST handled in both directions; idle
  5 min -> RST. Literal window, no SACK, no congestion control: the TUN is
  lossless, so this is enough. All of it is pure Kotlin with 6 JVM tests
  (handshake, buffering, MSS/window, retransmission, both close orders, RST,
  idle, 32-bit wrap).
- **DNS**: UDP to port 53 (any destination) is relayed as the raw query; the
  provider answers from its upstream's DNS servers (fallback 8.8.8.8, 1.1.1.1);
  the reply is wrapped into a UDP/IP packet to the app.
- **Other UDP**: dropped, logged once per destination port. QUIC (UDP 443) is
  therefore blocked and browsers fall back to TCP; this is expected.
- **ICMP / IPv6**: not handled (ping does not work through the tunnel).

Why not a tun2socks library: gomobile/NDK toolchains do not fit the
VPS-only workflow, and the lossless TUN makes a minimal Kotlin stack
sufficient and fully unit-testable. It is the same approach as the
well-known LocalVPN sample, written from scratch here.

## 4. Provider upstream network selection

`Tunnel.chooseUpstream` over every network Android reports: candidates have
NET_CAPABILITY_INTERNET and are not the ProkNet link (a Wi-Fi network
without INTERNET, or an `ap*`/`swlan*` interface). Preference: validated
Wi-Fi, then validated cellular, then any validated, then cellular, then
anything with INTERNET. The chosen `Network` is used for every socket
(`socketFactory.createSocket()` for TCP, `bindSocket` for DNS datagrams),
so nothing can route back into the hotspot. A `NetworkCallback` re-evaluates
on every change and pushes UPSTREAM_STATE to the buyer. Tested in
`TunnelTest.provider_upstream_selection_never_picks_the_proknet_link`.

## 5. TCP / DNS / UDP support

- TCP: any port, any IPv4 destination, concurrent streams, half-close.
- DNS: yes (UDP/53 relay, raw messages, TCP DNS not needed).
- UDP other than DNS: **not supported in v0.6** (dropped). Affects QUIC
  (falls back), VoIP/games (will not work). Planned for v0.7 as UDP_DATA
  frames with a per-flow NAT table on the provider.
- IPv6, ICMP: not supported.

## 6. Automated test results

`total=64 failed=0 errors=0 skipped=0` (108 s with the APK). New:

| Required | Test |
|---|---|
| tunnel framing | `TunnelTest.frames_round_trip_and_reject_garbage` (+ fuzz) |
| stream IDs / lifecycle | `stream_table_ids_limits_and_cleanup` |
| byte accounting | `byte_accounting_and_session_end` |
| DNS request/response | payload tests + `TcpipTest.udp_datagram_builds_and_parses`; relay path is Android code |
| multiple concurrent streams | `stream_table_ids_limits_and_cleanup` (independent state per stream, limit 3 in the test) |
| provider network selection | `provider_upstream_selection_never_picks_the_proknet_link` |
| cleanup on disconnect | `closeAll`, idle `expire`, `TcpFlow.abort`, idle RST |
| malformed frames | tunnel decode fuzz, `TcpipTest.malformed_packets_return_null_without_throwing` (500 random packets), chunk/packet fuzz from before |
| session authentication / binding | payload tests; the binding check itself is Android code (`Gateway.onSessionStart`, `TunnelClient` SESSION_OK check) |
| reconnect / error state | `LinkStateTest` (unchanged), `TcpFlowTest.retransmission_after_timeout_without_ack_progress`, close orders, RST, idle |
| checksums | `rfc1071_checksum_known_vector`, TCP/UDP checksum validity on every built packet |

One test failed once during the pass (an idle-flow rule excluded flows
created at time 0); the rule was simplified and the gate re-run green.

## 7. Android dialogs and permissions

| When | What Mike sees |
|---|---|
| Use Internet, first time | Android "Connection request: ProkNet Lab wants to set up a VPN connection" -> **OK** (once per install, unless revoked) |
| Use Internet, link not yet up | the v0.5.1 Wi-Fi join dialog on the buyer (tap CONNECT) |
| VPN active | key icon in the status bar; "ProkNet Internet via prok-xxxx" in the VPN settings |
| Another VPN already active | `establish()` returns null: logged `VPN permission revoked or another VPN active` |
| Provide Internet | no dialog; needs mobile data on |

Manifest: `ProkVpnService` with `BIND_VPN_SERVICE` and the `android.net.VpnService`
intent filter. No new runtime permissions.

## 8. Physical-phone test

`docs/TESTING.md` section 13. In one line: B `Provide Internet` (data ON),
A `Use Internet` with B selected (data OFF), tap CONNECT if the Wi-Fi dialog
appears, tap OK on the VPN dialog, press **Net test**, then open
https://example.com in Chrome on A. Send both logs and the Net test text.

## 9. Known OEM / network limitations

- Some carriers' APNs block tethered-looking traffic by TTL or by policy;
  this tunnel terminates on B itself (sockets originate from B's own stack),
  so TTL tricks do not apply, but carrier hotspot quotas do not either.
- Vendor battery killers can stop the provider's process; the existing
  foreground service and Battery exemption apply.
- Only one provider session at a time per provider (one link, one buyer).
- The tunnel shares the link with message transfers; a large transfer during
  browsing slows both.
- Throughput: each packet from the app becomes a frame on the link; expect a
  few Mbit/s on a good Wi-Fi link, less than the raw hotspot could do.
- Apps that insist on QUIC/UDP or IPv6-only endpoints will fail or fall back.

## 10. Files

```
core/Tcpip.kt, core/TcpFlow.kt, core/Tunnel.kt      NEW pure: packets/checksums, TCP endpoint, tunnel protocol + accounting + upstream choice
node/Gateway.kt, node/TunnelClient.kt              NEW provider and buyer engines
vpn/ProkVpnService.kt                              NEW
transport/WifiTransport.kt                         tunnel frame type, sendTunnel, tunnel sink, link socket for protect()
ble/BleAdvertiser.kt, BleScanner.kt, BleConstants.kt   capability flag
ble/ProkNetNode.kt, ui/MainActivity.kt, layout, manifest   roles, states, Net test, diagnostics
app/src/test/.../TcpipTest.kt, TunnelTest.kt, TcpFlowTest.kt   NEW
```

## 11. Exact APK / release

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.6.0
Build 8: 1.02 MB, SHA256 `ddcb66334fd269989fd3b66a00c5f7fcad78499059135c8bca03ccf22e034556`,
versionCode 8, versionName 0.6.0. Commit `46d13b0e18539dc74ccf586b335f49aefdf67583`
on `main`; this report on top.

## 12. Honest uncertainties

- The whole VPN path is unit-tested but has never run on a phone: the first
  risk is the TCP endpoint against a real Android kernel (window handling,
  retransmission timing); the second is DNS relay under a real resolver load
  (many parallel queries from Chrome). The Net test exercises both without
  the VPN, so it separates "tunnel broken" from "VPN stack broken".
- `establish()` behaviour on OnePlus with another VPN app installed is
  unknown.
- The provider's `chooseUpstream` heuristics for "is this the ProkNet link"
  rely on INTERNET being absent on the hotspot and specifier networks; the
  diagnostic line `UPSTREAM ... all=[...]` shows every network with flags so
  a wrong choice would be visible immediately.
