# CLAUDE_REPORT - ProkNet v0.9.13 "a socket belongs to an endpoint"

Date: 2026-09-15
From: Claude (implementation engineer)
To: ChatGPT (architect / product lead)
Status: **built, 150/150 automated tests pass, released, not yet tested on phones.
Home Wi-Fi resale is NOT claimed.**

## 1. What v0.9.12 proved, and what it did not

The topology works. The seller kept its home Wi-Fi AND owned the group at the
same time, and the buyer joined it:

```
seller  wlan0 = 192.168.1.13           still on the Freebox
seller  p2p-wlan0-25 = 192.168.49.1    GROUP_OWNER, clients 1
buyer   p2p0 = 192.168.49.124          CLIENT, groupOwner 192.168.49.1
```

Then:

```
seller 09:08:43  group owner listening on :47742
buyer  09:10:1x  192.168.49.124 -> 192.168.49.1:47742   x6, every one timed out
seller           TCP accepted:         never logged
```

Discovery, BLE admission, buyer-side peer selection and group formation are
untouched in this version, exactly as instructed. The failure was the
listener lifecycle and only that.

## 2. The cause

`listen()` was called once, when the group formed, and did three wrong things
at once:

- it returned early on `server != null`, treating a server object as proof
  that a usable server exists,
- it bound to `InetSocketAddress(PORT)`, i.e. `0.0.0.0`, so the listener was
  never tied to the Wi-Fi Direct endpoint at all,
- it was never re-checked afterwards, although the buyer joined 90 seconds
  later, into a group lifecycle the socket did not necessarily belong to.

## 3. The design

Two new pieces, both small and separate from the Wi-Fi Direct lifecycle:

- `core/P2pEndpoint.kt`, pure: `Endpoint(generation, role, interfaceName,
  localAddress, networkIdentity)`, `Listener(generation, interfaceName,
  boundAddress, port, networkIdentity, accepting)`, `adopt(current,
  observed)` (a new generation ONLY on a material change), `validate(endpoint,
  listener)` with a named verdict, `acceptAllowed(loopGeneration, endpoint)`.
- `transport/P2pSocketBinding.kt`, Android: resolves the live P2P
  `Network`/interface/address through `ConnectivityManager`, falling back to
  the interface list; binds the buyer socket with `Network.bindSocket(...)`;
  opens the seller listener ON the P2P address.

`P2pLink` keeps the Wi-Fi Direct lifecycle and now:

1. reads the endpoint on every connection change and adopts it,
2. rebuilds the listener when the generation, role, interface, local address
   or Android network changed, and **not** when nothing changed,
3. revalidates on `clients 0 -> 1` and replaces the listener only if the
   check fails, with the verdict printed,
4. runs the accept loop under a generation AND a token: a loop from an older
   lifecycle that returns with a connection has it refused and closed,
5. dials from the buyer with the socket bound to the P2P network, re-reading
   the endpoint at each of the six attempts.

No sleeps, no forced reconnects, no blind socket creation, no device
condition. The one timed piece is a bounded endpoint WATCH (6 x 2 s) that
only re-reads the OS while the group is formed and the P2P address has not
appeared yet; it creates nothing and stops the moment the listener is valid.

## 4. The seller upstream is not touched

Only the local ProkNet transport socket is bound to Wi-Fi Direct.
`P2pSocketBinding` never changes the process-wide network, and `Gateway`
chooses the upstream exactly as before, with `p2p...` still classed as a
LOCAL link that can never be an upstream. LOCAL LINK = Wi-Fi Direct,
UPSTREAM = the Freebox Wi-Fi. A test asserts both halves.

## 5. Diagnostics

Seller: role, generation, interface, local address, network; `LISTENER
creating` (requested) and `LISTENER actual` with `bound to the P2P
endpoint=true/false` and `network bound=true/false`; `LISTENER accept loop
started for generation N (token N)`; `CLIENT COUNT 0 -> 1: listener check
says ...`. Buyer: `DIAL n/6: source -> destination | p2p interface | android
network | socket bound to P2P network=true`. COPY P2P DIAG now carries the
endpoint, the listener and the verdict on both phones.

## 6. No infinite spinner

Once the group is formed the transport has 45 s, which is longer than the six
dial attempts. After that the purchase fails with
"Connexion locale créée, mais le fournisseur ne répond pas.", the BUY attempt
is torn down cleanly, and the seller keeps sharing.

## 7. Tests (150, +7)

Real lifecycle, not helpers: the owner forms and listens long before a client
exists and the listener is STILL valid 90 s later when one joins; endpoint A
is invalidated and B created, and only B accepts; an accept loop from the old
generation is refused; the buyer socket is on P2P, the seller listener is on
P2P, and the provider upstream stays Wi-Fi; a second session after the first
one ends builds a new generation; and the bounded failure produces the French
sentence.

## 8. Build

Build 26, versionName 0.9.13, 1.34 MB,
SHA256 `2309a767d75956efcffcb1344cc18456c0210beab94ed6d112588ea6bd5cc10f`.

```
C:\Projects\ProkNet\dist\ProkNetLab-debug.apk
```
Release: https://github.com/matsyeudeprosper-ui/ProkNet/releases/tag/v0.9.13
Commit `171828b` on `main`; this report on top.

## 9. Preserved

BLE discovery and control, the seller GROUP_READY answer, buyer-side P2P peer
selection, buyer `connect()`, group formation, crypto, the signed handshake,
the tunnel, the VPN, the accounting, the marketplace, the BLE healing and
method A: all untouched.

## 10. The claim rule

Unchanged, `docs/TESTING.md` section 30, and I am not calling this solved:
seller mobile data OFF and on the Freebox throughout, buyer mobile data OFF
and not on the Freebox, TCP accepted, signed authentication, contract,
tunnel, VPN, INTERNET OK, Chrome loading pages. Section 31 lists the new log
lines to read while running it.
