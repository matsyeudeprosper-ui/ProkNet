package net.prok.proknet.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.SoftApConfiguration
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import net.prok.proknet.core.Crypto
import net.prok.proknet.core.DeliveryResult
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Handshake
import net.prok.proknet.core.Identity
import net.prok.proknet.core.LinkIo
import net.prok.proknet.core.LinkState
import net.prok.proknet.core.Packet
import net.prok.proknet.core.Routing
import net.prok.proknet.core.Tunnel
import net.prok.proknet.core.Wire

/**
 * Wi-Fi as a ProkNet transport: phone-to-phone TCP over a local-only hotspot.
 * No router, no Internet.
 *
 *   initiator (wants the link)  --BLE: WIFI_REQUEST (encrypted)-->  host
 *   host: startLocalOnlyHotspot, ServerSocket  --BLE: WIFI_OFFER(ssid, pass, security, ips, port)-->  initiator
 *   initiator: join SSID (WifiNetworkSpecifier matching the hotspot's security; one system
 *              dialog on Android 10+, app must be in front), TCP connect to the DHCP server
 *              address of the granted network (= the host), then HELLO/AUTH -> link UP
 *   frames: [u32 len][type][payload]; every PACKET is answered by a RECEIPT
 *
 * v0.5.1: security-aware specifier with WPA2/WPA3 attempts, host address from
 * the granted network's link properties (DHCP server / gateway) instead of a
 * heuristic, host-side address determination that excludes the phone's own
 * Wi-Fi network, full callback logging, scan visibility check, and a linear
 * phase for the UI: REQUESTING -> OFFERED -> JOINING -> TCP -> AUTH -> WIFI UP.
 * Decisions (state, retry) still live in core/LinkState.
 */
class WifiTransport(
    private val context: Context,
    private val identity: Identity,
    private val control: ControlChannel,
    /** v0.9: a phone may run two instances: the normal one (may host) and a client-only UPSTREAM one. */
    override val name: String = Routing.TRANSPORT_WIFI,
    private val mayHost: Boolean = true,
) : Transport {
    interface ControlChannel {
        fun sendControl(peerShort: String, body: ByteArray, cb: (Boolean) -> Unit)
        fun knownPeerPub(peerShort: String): ByteArray?
        /** Is a ProkNet Activity visible? The Android join dialog needs the app in front. */
        fun appVisible(): Boolean
        /** v0.9.4: is a real session running on the current link? An idle link may be dropped for a new customer. */
        fun linkInUse(): Boolean
    }

    /** v0.6: where tunnel frames go. Called on the link's read thread; must not block for long. */
    interface TunnelSink {
        fun onTunnelFrame(peerShort: String, frame: Tunnel.Frame)
        fun onLinkClosed(peerShort: String, reason: String)
        /** v0.9: FRAME_RELAY / FRAME_RELAY_INFO payloads, untouched. Called on the link's read thread. */
        fun onRawFrame(peerShort: String, type: Int, payload: ByteArray) {}
    }
    @Volatile var tunnelSink: TunnelSink? = null

    private val tag = name.uppercase()
    override val bulkCapable = true
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private val sendExec = Executors.newSingleThreadExecutor()
    private val fsm = LinkState()
    private var listener: TransportListener? = null
    @Volatile override var isRunning = false
        private set
    private val sent = AtomicLong(); private val received = AtomicLong()
    override val bytesSent: Long get() = sent.get()
    override val bytesReceived: Long get() = received.get()

    private val wifi: WifiManager get() = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val cm: ConnectivityManager get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var serverSocket: ServerSocket? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var link: Link? = null
    private var pendingOffer: Wire.Control.WifiOffer? = null
    private var joinAttempts: List<Int> = emptyList()
    private var joinIndex = 0
    private var joinWaitingForForeground = false
    @Volatile private var lastEvent = ""
    /** Linear, human phase for the UI. */
    @Volatile var phase: String = "IDLE"
        private set
    /** True while Android's "connect to network" approval is expected on this phone. */
    @Volatile var approvalNeeded: Boolean = false
        private set
    /** v0.9 probe facts: the network Android granted us as a client, the hotspot we run as a host. */
    @Volatile var grantedNetwork: Network? = null
        private set
    @Volatile var hotspotSsid: String = ""
        private set
    @Volatile var lastHotspotError: String = ""
        private set
    /** v0.9.5: the error the OTHER phone reported when it refused to host. Shown in the diagnostic. */
    @Volatile var peerCancelDetail: String = ""
        private set
    private var hotspotRetried = false
    private val relaySent = AtomicLong(); private val relayReceived = AtomicLong()
    val relayBytesSent: Long get() = relaySent.get()
    val relayBytesReceived: Long get() = relayReceived.get()
    private val ticker = object : Runnable {
        override fun run() {
            if (!isRunning) return
            val step = fsm.state
            if (fsm.tick(now()) == LinkState.Action.TEARDOWN) teardown(timeoutReason(step))
            if (joinWaitingForForeground && control.appVisible()) { joinWaitingForForeground = false; pendingOffer?.let { startJoinAttempt(it) } }
            main.postDelayed(this, 2000)
        }
    }

    private fun now() = System.currentTimeMillis()

    /** Why a step ran out of time, in words the UI layer can classify. */
    private fun timeoutReason(s: LinkState.State) = when (s) {
        LinkState.State.REQUESTING -> "the provider did not answer within " + (fsm.stepTimeoutMs(s) / 1000) + "s (its Wi-Fi or Location may be off, or the app is not open)"
        LinkState.State.HOSTING -> "this phone could not start its Wi-Fi hotspot in time"
        LinkState.State.OFFERING -> "the other phone did not join the hotspot"
        LinkState.State.JOINING -> "the Wi-Fi network was not joined (the Android dialog was not approved?)"
        LinkState.State.HANDSHAKE -> "the secure handshake did not finish"
        else -> "step timeout in " + s
    }

    /** Is Wi-Fi on? A hotspot cannot be started without it on most phones. */
    val wifiEnabled: Boolean get() = try { wifi.isWifiEnabled } catch (e: Exception) { false }

    /** v0.9.6: the real Wi-Fi network this phone uses for Internet (never the ProkNet link, which is local-only). */
    class NetInfo(val ssid: String?, val bssid: String?, val freqMhz: Int)

    @Suppress("DEPRECATION")
    fun currentWifi(): NetInfo? {
        try {
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) continue
                val info = if (Build.VERSION.SDK_INT >= 29) caps.transportInfo as? WifiInfo else null
                val legacy = if (info == null) wifi.connectionInfo else null
                val ssid = (info?.ssid ?: legacy?.ssid)?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
                val bssid = info?.bssid ?: legacy?.bssid
                val freq = info?.frequency ?: legacy?.frequency ?: 0
                return NetInfo(ssid, bssid, freq)
            }
        } catch (e: Exception) { DiagLog.w(tag, "current wifi: " + e) }
        return null
    }
    val state: LinkState get() = fsm
    val linkedPeer: String? get() = if (fsm.isUp) fsm.peer else null

    override fun start(listener: TransportListener): Boolean {
        this.listener = listener
        isRunning = true
        fsm.reset(now())
        setPhase("IDLE")
        main.postDelayed(ticker, 2000)
        DiagLog.i(tag, "transport started (hotspot/TCP; Wi-Fi " + (if (wifi.isWifiEnabled) "enabled" else "DISABLED - turn Wi-Fi on for fast links") + ", Android " + Build.VERSION.RELEASE + " " + Build.MANUFACTURER + ")")
        return true
    }

    override fun stop() {
        if (!isRunning) return
        isRunning = false
        main.removeCallbacks(ticker)
        teardown("transport stopped")
        DiagLog.i(tag, "transport stopped")
    }

    override fun canReach(peerShort: String): Boolean = isRunning && fsm.isUp && fsm.peer == peerShort && link?.isOpen == true
    override fun linkState(): String = (if (!isRunning) "off" else phase + " / " + fsm.describe()) + (if (lastEvent.isNotEmpty()) " | " + lastEvent else "")
    private fun push(event: String) { lastEvent = event; listener?.onLinkState(name, linkState()) }
    private fun setPhase(p: String, event: String = "") {
        phase = p
        approvalNeeded = p.startsWith("JOINING")
        DiagLog.i(tag, "PHASE " + p + (if (event.isNotEmpty()) " - " + event else ""))
        push(event)
    }

    // ---- negotiation (public entry points) -------------------------------------------------------

    /** Ask [peerShort] for a Wi-Fi link. We become the initiator (we join their hotspot). */
    fun requestLink(peerShort: String): Boolean {
        if (!isRunning) return false
        if (control.knownPeerPub(peerShort) == null) { DiagLog.w(tag, "cannot negotiate with prok-" + peerShort + ": public key unknown (needs one BLE contact first)"); return false }
        if (!wifi.isWifiEnabled) { DiagLog.e(tag, "Wi-Fi is OFF on this phone: turn Wi-Fi on (the joining phone needs it)"); push("Wi-Fi is OFF"); return false }
        if (!fsm.canRetry(now())) { DiagLog.w(tag, "link attempt too soon after failure (" + fsm.describe() + ", wait " + (fsm.retryDelayMs() / 1000) + "s)"); return false }
        when (fsm.request(peerShort, now())) {
            LinkState.Action.SEND_REQUEST -> {
                setPhase("REQUESTING", "WIFI_REQUEST -> prok-" + peerShort + " over BLE")
                control.sendControl(peerShort, Wire.wifiRequest(PORT)) { ok -> if (!ok) main.post { fail("could not deliver WIFI_REQUEST over BLE") } else main.post { push("request delivered, waiting for offer") } }
                return true
            }
            else -> { DiagLog.i(tag, "request ignored: " + fsm.describe()); return false }
        }
    }

    /** A control body for us arrived (already decrypted and verified by the node). */
    fun onControl(peerShort: String, body: ByteArray) {
        val c = Wire.parseControl(body) ?: run { DiagLog.w(tag, "malformed control from prok-" + peerShort); return }
        main.post {
            when (c) {
                is Wire.Control.WifiRequest -> {
                    DiagLog.i(tag, "NEGOTIATE: WIFI_REQUEST from prok-" + peerShort)
                    if (!mayHost) { DiagLog.i(tag, "request ignored: this instance never hosts"); return@post }
                    val inUse = try { control.linkInUse() } catch (e: Exception) { false }
                    when (fsm.hostAnswer(peerShort, identity.shortIdHex, inUse)) {
                        LinkState.HostAnswer.DROP_STALE_THEN_HOST -> {
                            DiagLog.i(tag, "prok-" + peerShort + " asks for a link while I still hold an idle one (" + fsm.describe() + "): dropping it and hosting a fresh one")
                            teardown("idle link dropped for a request from prok-" + peerShort, notifyPeer = false)
                        }
                        LinkState.HostAnswer.REFUSE_BUSY -> {
                            DiagLog.w(tag, "refusing prok-" + peerShort + ": this phone is busy (" + fsm.describe() + ") - telling it so it does not wait")
                            sendCancelNow(peerShort, Wire.CANCEL_BUSY)
                            return@post
                        }
                        LinkState.HostAnswer.IGNORE_TIE_BREAK -> { DiagLog.i(tag, "both phones asked at once: prok-" + peerShort + " hosts, I join"); return@post }
                        LinkState.HostAnswer.HOST -> {}
                    }
                    when (fsm.requestReceived(peerShort, identity.shortIdHex, now())) {
                        LinkState.Action.START_HOTSPOT -> { setPhase("HOSTING", "starting hotspot for prok-" + peerShort); startHotspot() }
                        else -> { DiagLog.w(tag, "request from prok-" + peerShort + " could not be accepted: " + fsm.describe()); sendCancelNow(peerShort, Wire.CANCEL_BUSY) }
                    }
                }
                is Wire.Control.WifiOffer -> {
                    DiagLog.i(tag, "NEGOTIATE: WIFI_OFFER from prok-" + peerShort + " ssid=" + c.ssid + " security=" + secName(c.security) + " hidden=" + c.hidden + " ips=" + c.ips + " port=" + c.port)
                    when (fsm.offerReceived(peerShort, now())) {
                        LinkState.Action.JOIN_NETWORK -> { pendingOffer = c; setPhase("OFFERED", "offer received"); beginJoin(c) }
                        else -> DiagLog.i(tag, "offer ignored: " + fsm.describe())
                    }
                }
                is Wire.Control.WifiCancel -> {
                    if (fsm.peer == peerShort && !fsm.isIdle) {
                        DiagLog.w(tag, "prok-" + peerShort + " cancelled the link: " + Wire.cancelName(c.reason) + (if (c.detail.isNotEmpty()) " - ITS OWN ERROR: " + c.detail else " (no detail: it runs an older build)"))
                        peerCancelDetail = c.detail
                        teardown(Wire.cancelReasonText(c.reason) + (if (c.detail.isNotEmpty()) " [" + c.detail + "]" else ""), notifyPeer = false)
                        fsm.forgetFailures()   // it answered in a second; let the user press again at once
                        push("provider refused: " + Wire.cancelName(c.reason))
                    }
                }
            }
        }
    }

    private fun secName(s: Int) = when (s) { Wire.SEC_WPA2 -> "WPA2"; Wire.SEC_WPA3 -> "WPA3"; Wire.SEC_TRANSITION -> "WPA2/WPA3"; Wire.SEC_OPEN -> "open"; else -> "unknown" }

    // ---- host side ---------------------------------------------------------------------------------

    /** Android needs Location services ON to create a local-only hotspot, whatever the permissions say. */
    private fun locationOn(): Boolean = try {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as android.location.LocationManager
        lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(android.location.LocationManager.NETWORK_PROVIDER)
    } catch (e: Exception) { true }

    private fun startHotspot(retry: Boolean = false) {
        if (!retry) hotspotRetried = false
        if (!locationOn()) {
            lastHotspotError = "Location services are OFF on this phone"
            DiagLog.e(tag, "cannot host: " + lastHotspotError + " - Android refuses a local-only hotspot without it")
            cancelToPeer(Wire.CANCEL_NO_HOTSPOT, "Location services are off on the provider")
            fail("cannot host: Location services are off on this phone"); return
        }
        if (!wifi.isWifiEnabled) DiagLog.w(tag, "Wi-Fi is OFF on the host: the hotspot may still start on some phones, else turn Wi-Fi on")
        DiagLog.i(tag, "starting the local-only hotspot for prok-" + (fsm.peer ?: "?") + " (wifi " + (if (wifi.isWifiEnabled) "on" else "OFF") +
            ", location on, connected to " + (currentSsid() ?: "no Wi-Fi network") + (if (retry) ", RETRY after closing the previous reservation" else "") + ")")
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    main.post {
                        reservation = res
                        val cred = credentials(res)
                        if (cred.ssid == null) { fail("hotspot started but no SSID readable"); return@post }
                        hotspotSsid = cred.ssid; lastHotspotError = ""
                        DiagLog.i(tag, "hotspot started: ssid=" + cred.ssid + " security=" + secName(cred.security) + " hidden=" + cred.hidden + " band=" + cred.band)
                        var tries = 0
                        main.post(object : Runnable {
                            override fun run() {
                                if (fsm.state != LinkState.State.HOSTING) return
                                val ips = hostAddresses()
                                if (ips.isEmpty() && tries++ < 12) { main.postDelayed(this, 500); return }
                                DiagLog.i(tag, "hotspot addresses (own Wi-Fi network excluded): " + ips)
                                if (!startServer()) { fail("could not open server socket on :" + PORT); return }
                                if (fsm.hotspotUp(now()) == LinkState.Action.SEND_OFFER) {
                                    val peer = fsm.peer!!
                                    setPhase("OFFERING", "hotspot up, sending offer to prok-" + peer)
                                    control.sendControl(peer, Wire.wifiOffer(cred.ssid, cred.pass ?: "", PORT, ips, cred.security, cred.hidden)) { ok ->
                                        main.post { if (!ok) fail("could not deliver WIFI_OFFER over BLE") else push("offer delivered, waiting for the client to join") }
                                    }
                                }
                            }
                        })
                    }
                }
                override fun onFailed(reason: Int) {
                    main.post {
                        lastHotspotError = "reason " + reason + hotspotHint(reason)
                        DiagLog.e(tag, "hotspot FAILED: " + lastHotspotError)
                        // one retry after dropping a reservation this app may still hold from an earlier attempt
                        if (!hotspotRetried && fsm.state == LinkState.State.HOSTING) {
                            hotspotRetried = true
                            try { reservation?.close() } catch (_: Exception) {}; reservation = null
                            DiagLog.i(tag, "retrying the hotspot once in 1.5s")
                            main.postDelayed({ if (fsm.state == LinkState.State.HOSTING) startHotspot(retry = true) }, 1500)
                            return@post
                        }
                        cancelToPeer(Wire.CANCEL_NO_HOTSPOT, lastHotspotError)
                        fail("hotspot failed, " + lastHotspotError)
                    }
                }
                override fun onStopped() { main.post { DiagLog.w(tag, "hotspot stopped by the system"); reservation = null; if (fsm.isUp || fsm.isBusy) fail("hotspot stopped by the system") } }
            }, main)
        } catch (e: SecurityException) {
            lastHotspotError = "permission refused: " + e.message
            cancelToPeer(Wire.CANCEL_NO_HOTSPOT, "the provider is missing the Nearby devices / Location permission")
            fail("hotspot needs the Nearby devices / Location permission: " + e.message)
        } catch (e: Exception) {
            lastHotspotError = "threw " + e
            cancelToPeer(Wire.CANCEL_NO_HOTSPOT, "hotspot call failed on the provider: " + e.javaClass.simpleName)
            fail("startLocalOnlyHotspot threw " + e)
        }
    }

    /**
     * v0.9.3: we were asked to host and cannot. Say so over BLE right away;
     * otherwise the other phone sits in "looking for a provider" until its
     * step timeout, which is exactly what a user reports as "it searches then
     * says connection lost".
     */
    private fun cancelToPeer(reason: Int = Wire.CANCEL_NO_HOTSPOT, detail: String = "") { fsm.peer?.let { sendCancelOnce(it, reason, detail) } }

    @Volatile private var cancelSent = false

    private fun sendCancelNow(peer: String, reason: Int, detail: String = "") {
        DiagLog.i(tag, "telling prok-" + peer + " to stop waiting: " + Wire.cancelName(reason) + (if (detail.isNotEmpty()) " - " + detail else ""))
        try { control.sendControl(peer, Wire.wifiCancel(reason, detail)) { ok -> DiagLog.i(tag, "cancel delivered to prok-" + peer + ": " + ok) } } catch (e: Exception) { DiagLog.w(tag, "cancel: " + e) }
    }

    private fun sendCancelOnce(peer: String, reason: Int, detail: String = "") { if (!cancelSent) { cancelSent = true; sendCancelNow(peer, reason, detail) } }

    private fun hotspotHint(reason: Int) = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> " (no channel: this phone is on a Wi-Fi network whose channel cannot be shared; disconnect Wi-Fi or use mobile data)"
        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC -> " (generic: Wi-Fi on? Location on? Android hotspot/tethering off?)"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> " (incompatible mode: the Android hotspot / tethering is already active)"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> " (tethering disallowed by policy)"
        else -> ""
    }

    /** The Wi-Fi network this phone is connected to, for the log only. */
    @Suppress("DEPRECATION")
    private fun currentSsid(): String? = try {
        val info = wifi.connectionInfo
        val s = info?.ssid?.trim('"')
        if (s.isNullOrEmpty() || s == "<unknown ssid>" || info.networkId == -1) null else s
    } catch (e: Exception) { null }

    private class Cred(val ssid: String?, val pass: String?, val security: Int, val hidden: Boolean, val band: String)

    @Suppress("DEPRECATION")
    private fun credentials(res: WifiManager.LocalOnlyHotspotReservation): Cred {
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                val c = res.softApConfiguration
                val ssid = (if (Build.VERSION.SDK_INT >= 33) c.wifiSsid?.toString()?.trim('"') else null) ?: c.ssid
                val sec = when (c.securityType) {
                    SoftApConfiguration.SECURITY_TYPE_OPEN -> Wire.SEC_OPEN
                    SoftApConfiguration.SECURITY_TYPE_WPA2_PSK -> Wire.SEC_WPA2
                    SoftApConfiguration.SECURITY_TYPE_WPA3_SAE -> Wire.SEC_WPA3
                    SoftApConfiguration.SECURITY_TYPE_WPA3_SAE_TRANSITION -> Wire.SEC_TRANSITION
                    else -> Wire.SEC_UNKNOWN
                }
                // Band getters are system API only: the client-side scan diagnostic reports the frequency instead.
                Cred(ssid, c.passphrase, sec, c.isHiddenSsid, "see client scan")
            } else {
                val c: WifiConfiguration? = res.wifiConfiguration
                Cred(c?.SSID?.trim('"'), c?.preSharedKey?.trim('"'), Wire.SEC_WPA2, c?.hiddenSSID ?: false, "?")
            }
        } catch (e: Exception) { DiagLog.w(tag, "credentials: " + e); Cred(null, null, Wire.SEC_UNKNOWN, false, "?") }
    }

    /** Addresses of this phone's own (STA) Wi-Fi networks: never the hotspot side. */
    private fun staAddresses(): Set<String> {
        val out = HashSet<String>()
        try {
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val lp = cm.getLinkProperties(n) ?: continue
                for (la in lp.linkAddresses) la.address.hostAddress?.let { out.add(it) }
            }
        } catch (e: Exception) { DiagLog.w(tag, "sta addresses: " + e) }
        return out
    }

    /** IPv4 addresses of this phone that a hotspot client could reach, most likely first. */
    private fun hostAddresses(): List<String> {
        val sta = staAddresses()
        val out = ArrayList<Pair<Int, String>>()
        val seen = StringBuilder()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr !is Inet4Address) continue
                    val ip = addr.hostAddress ?: continue
                    seen.append(nif.name).append("=").append(ip).append(" ")
                    if (!addr.isSiteLocalAddress || ip in sta) continue
                    val n = nif.name.lowercase()
                    val score = (if (n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("wlan1") || n.contains("softap")) 0 else if (n.startsWith("wlan")) 1 else 2) +
                        (if (ip.endsWith(".1")) 0 else 3)
                    out.add(score to ip)
                }
            }
        } catch (e: Exception) { DiagLog.w(tag, "interfaces: " + e) }
        DiagLog.i(tag, "interfaces: " + seen.toString().trim() + (if (sta.isNotEmpty()) " | own Wi-Fi (excluded): " + sta else ""))
        return out.sortedBy { it.first }.map { it.second }.distinct().take(4)
    }

    private fun startServer(): Boolean {
        if (serverSocket != null) return true
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(PORT))
            serverSocket = ss
            io.execute { acceptLoop(ss) }
            DiagLog.i(tag, "server socket listening on :" + PORT)
            true
        } catch (e: Exception) { DiagLog.e(tag, "server socket", e); false }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (isRunning && !ss.isClosed) {
            val s = try { ss.accept() } catch (e: Exception) { break }
            DiagLog.i(tag, "TCP client connected from " + s.inetAddress.hostAddress + ":" + s.port)
            main.post { fsm.clientConnected(now()); setPhase("AUTH", "client connected, handshake") }
            val l = Link(s, isHost = true)
            if (l.handshake()) main.post { linkUp(l) } else { l.close(); main.post { fail("handshake failed (host side), see AUTH line") } }
        }
    }

    // ---- client side -------------------------------------------------------------------------------

    private fun beginJoin(offer: Wire.Control.WifiOffer) {
        if (!wifi.isWifiEnabled) { fail("Wi-Fi is OFF on this phone: turn Wi-Fi on and try again"); return }
        joinAttempts = if (Build.VERSION.SDK_INT >= 29) Wire.joinAttempts(offer.security) else listOf(Wire.SEC_WPA2)
        joinIndex = 0
        scanDiagnostic(offer.ssid)
        if (!control.appVisible()) {
            joinWaitingForForeground = true
            setPhase("JOINING (open the app to approve)", "Android needs the app in front for the join dialog")
            DiagLog.w(tag, "app is not visible: Android will not show the network approval. Waiting for the app to be opened (notification posted)")
            return
        }
        startJoinAttempt(offer)
    }

    /** One background scan so the log says whether the hotspot SSID is even visible from here. */
    @Suppress("DEPRECATION")
    private fun scanDiagnostic(ssid: String) {
        try {
            wifi.startScan()
            main.postDelayed({
                try {
                    val hits = wifi.scanResults.filter { (it.SSID ?: "") == ssid }
                    if (hits.isEmpty()) DiagLog.w(tag, "scan: SSID " + ssid + " NOT visible from this phone (" + wifi.scanResults.size + " networks seen). Band mismatch, hidden SSID or hotspot not up?")
                    else for (h in hits) DiagLog.i(tag, "scan: SSID " + ssid + " visible, " + h.frequency + " MHz, level " + h.level + " dBm, caps " + h.capabilities)
                } catch (e: Exception) { DiagLog.w(tag, "scan results: " + e) }
            }, 4000)
        } catch (e: Exception) { DiagLog.w(tag, "scan: " + e) }
    }

    private fun startJoinAttempt(offer: Wire.Control.WifiOffer) {
        if (fsm.state != LinkState.State.JOINING) return
        if (joinIndex >= joinAttempts.size) { fail("all join attempts failed (" + joinAttempts.joinToString { secName(it) } + ")"); return }
        val sec = joinAttempts[joinIndex]
        if (Build.VERSION.SDK_INT >= 29) joinWithSpecifier(offer, sec) else joinLegacy(offer)
    }

    private fun joinWithSpecifier(offer: Wire.Control.WifiOffer, sec: Int) {
        val b = WifiNetworkSpecifier.Builder().setSsid(offer.ssid)
        if (offer.hidden) b.setIsHiddenSsid(true)
        when (sec) {
            Wire.SEC_WPA3 -> b.setWpa3Passphrase(offer.pass)
            Wire.SEC_OPEN -> {}
            else -> b.setWpa2Passphrase(offer.pass)
        }
        val req = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(b.build())
            .build()
        val attemptNo = joinIndex + 1
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                DiagLog.i(tag, "requestNetwork.onAvailable network=" + network + " (attempt " + attemptNo + ", " + secName(sec) + ")")
                grantedNetwork = network
                main.post {
                    if (fsm.networkAvailable(now()) == LinkState.Action.OPEN_SOCKET) {
                        setPhase("TCP", "joined " + offer.ssid + ", connecting")
                        io.execute { openClient(network, offer) }
                    }
                }
            }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val info = if (Build.VERSION.SDK_INT >= 29) (caps.transportInfo as? WifiInfo) else null
                DiagLog.i(tag, "requestNetwork.onCapabilitiesChanged: wifi=" + caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) +
                    " internet=" + caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) +
                    (info?.let { " ssid=" + it.ssid + " rssi=" + it.rssi + " freq=" + it.frequency + "MHz" } ?: ""))
            }
            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                DiagLog.i(tag, "requestNetwork.onLinkPropertiesChanged: iface=" + lp.interfaceName + " addrs=" + lp.linkAddresses.map { it.toString() } +
                    " routes=" + lp.routes.map { it.toString() } + " dhcpServer=" + dhcpServer(lp))
            }
            override fun onLosing(network: Network, maxMsToLive: Int) { DiagLog.w(tag, "requestNetwork.onLosing in " + maxMsToLive + "ms") }
            override fun onUnavailable() {
                DiagLog.w(tag, "requestNetwork.onUnavailable (attempt " + attemptNo + ", " + secName(sec) + "): dialog declined, no matching network found within the timeout, wrong passphrase/security, or app not in front")
                main.post {
                    if (netCallback === this) { try { cm.unregisterNetworkCallback(this) } catch (_: Exception) {}; netCallback = null }
                    joinIndex++
                    if (fsm.state == LinkState.State.JOINING && joinIndex < joinAttempts.size) {
                        DiagLog.i(tag, "retrying the join with " + secName(joinAttempts[joinIndex]))
                        startJoinAttempt(offer)
                    } else fail("Wi-Fi network unavailable after " + attemptNo + " attempt(s): see the requestNetwork lines above")
                }
            }
            override fun onLost(network: Network) {
                DiagLog.w(tag, "requestNetwork.onLost network=" + network)
                main.post { if (fsm.isUp || fsm.isBusy) fail("Wi-Fi network lost") }
            }
        }
        netCallback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }
        netCallback = cb
        try {
            setPhase("JOINING (tap CONNECT in the Android dialog)", "requesting " + offer.ssid + " as " + secName(sec) + ", attempt " + attemptNo + "/" + joinAttempts.size)
            DiagLog.i(tag, "requestNetwork(" + offer.ssid + ", " + secName(sec) + (if (offer.hidden) ", hidden" else "") + ", timeout " + (JOIN_TIMEOUT_MS / 1000) + "s) - Android shows 'connect to device?': tap CONNECT")
            cm.requestNetwork(req, cb, JOIN_TIMEOUT_MS.toInt())
        } catch (e: SecurityException) { fail("requestNetwork needs permission: " + e.message) } catch (e: Exception) { fail("requestNetwork threw " + e) }
    }

    private fun dhcpServer(lp: LinkProperties): String? = try {
        if (Build.VERSION.SDK_INT >= 30) lp.dhcpServerAddress?.hostAddress else null
    } catch (e: Exception) { null }

    @Suppress("DEPRECATION")
    private fun joinLegacy(offer: Wire.Control.WifiOffer) {
        try {
            val conf = WifiConfiguration()
            conf.SSID = "\"" + offer.ssid + "\""
            conf.preSharedKey = "\"" + offer.pass + "\""
            conf.hiddenSSID = offer.hidden
            val id = wifi.addNetwork(conf)
            if (id < 0) { fail("addNetwork failed"); return }
            wifi.disconnect(); wifi.enableNetwork(id, true); wifi.reconnect()
            setPhase("JOINING (legacy)", "legacy join of " + offer.ssid)
            var waited = 0L
            main.post(object : Runnable {
                override fun run() {
                    if (fsm.state != LinkState.State.JOINING) return
                    val cur = wifi.connectionInfo?.ssid?.trim('"')
                    if (cur == offer.ssid && wifi.connectionInfo.ipAddress != 0) {
                        if (fsm.networkAvailable(now()) == LinkState.Action.OPEN_SOCKET) { setPhase("TCP", "joined (legacy), connecting"); io.execute { openClient(null, offer) } }
                    } else if (waited > JOIN_TIMEOUT_MS) fail("legacy join timed out")
                    else { waited += 1000; main.postDelayed(this, 1000) }
                }
            })
        } catch (e: Exception) { fail("legacy join threw " + e) }
    }

    /** Host candidates: the granted network's DHCP server and gateway first (that IS the host), then the offered list. */
    private fun hostCandidates(network: Network?, offer: Wire.Control.WifiOffer): List<String> {
        val out = ArrayList<String>()
        try {
            val lp = if (network != null) cm.getLinkProperties(network) else null
            if (lp != null) {
                dhcpServer(lp)?.let { out.add(it) }
                for (r in lp.routes) { val gw = r.gateway; if (gw is Inet4Address && !gw.isAnyLocalAddress) gw.hostAddress?.let { out.add(it) } }
                // same /24 as our own address with host .1, as a last guess
                for (la in lp.linkAddresses) { val a = la.address; if (a is Inet4Address) a.hostAddress?.let { ip -> out.add(ip.substringBeforeLast('.') + ".1") } }
            } else if (Build.VERSION.SDK_INT < 29) {
                @Suppress("DEPRECATION") val gw = wifi.dhcpInfo?.gateway ?: 0
                if (gw != 0) out.add(String.format("%d.%d.%d.%d", gw and 0xff, (gw shr 8) and 0xff, (gw shr 16) and 0xff, (gw shr 24) and 0xff))
            }
        } catch (e: Exception) { DiagLog.w(tag, "link properties: " + e) }
        out.addAll(offer.ips)
        return out.distinct()
    }

    private fun openClient(network: Network?, offer: Wire.Control.WifiOffer) {
        val candidates = hostCandidates(network, offer)
        DiagLog.i(tag, "TCP: host candidates in order " + candidates + " port " + offer.port)
        var lastErr = ""
        for (ip in candidates) {
            try {
                val s = if (network != null) network.socketFactory.createSocket() else Socket()
                s.connect(InetSocketAddress(InetAddress.getByName(ip), offer.port), 6000)
                DiagLog.i(tag, "TCP connected to " + ip + ":" + offer.port)
                main.post { setPhase("AUTH", "TCP up, signed handshake") }
                val l = Link(s, isHost = false)
                if (l.handshake()) { main.post { linkUp(l) }; return }
                l.close(); lastErr = "handshake failed with " + ip
            } catch (e: Exception) { lastErr = ip + ": " + LinkIo.describe(e); DiagLog.w(tag, "TCP connect " + ip + " failed: " + LinkIo.describe(e)) }
        }
        main.post { fail("could not reach the host (" + lastErr + ")") }
    }

    // ---- link ---------------------------------------------------------------------------------------

    private fun linkUp(l: Link) {
        val peerShort = l.peerRecord!!.shortId
        val expected = fsm.peer
        if (expected != null && expected != peerShort) { DiagLog.w(tag, "handshake with prok-" + peerShort + " but negotiating with prok-" + expected + " - rejected"); l.close(); fail("wrong peer on link"); return }
        link?.close()
        link = l
        fsm.handshakeOk(peerShort, now())
        listener?.onIdentity(name, l.peerRecord!!.idHex, l.peerRecord!!.pub, l.peerRecord!!.name)
        setPhase("WIFI UP", "with prok-" + peerShort + " (" + (if (l.isHost) "host" else "client") + ", both signatures verified)")
        DiagLog.i(tag, "LINK UP with prok-" + peerShort + " (" + (if (l.isHost) "host" else "client") + ", authenticated by signature)")
        io.execute { l.readLoop() }
    }

    private fun fail(reason: String) {
        DiagLog.w(tag, "link failed: " + reason)
        if (fsm.fail(reason, now()) == LinkState.Action.TEARDOWN) teardown(reason) else { setPhase("DOWN", reason) }
    }

    /** v0.9: drop the current link / attempt on purpose. */
    fun disconnect(reason: String) { if (isRunning) main.post { if (!fsm.isIdle) teardown(reason) else { fsm.reset(now()); setPhase("IDLE", reason) } } }

    private fun teardown(reason: String, notifyPeer: Boolean = true) {
        val peer = link?.peerRecord?.shortId ?: fsm.peer
        // v0.9.4: if the link never came up, the other phone is still waiting for us. Say so now.
        if (notifyPeer && link == null && peer != null && !fsm.isIdle) sendCancelOnce(peer, Wire.CANCEL_GENERIC)
        link?.close(); link = null
        if (peer != null) try { tunnelSink?.onLinkClosed(peer, reason) } catch (e: Exception) { DiagLog.w(tag, "tunnel sink: " + e) }
        pendingOffer = null
        joinWaitingForForeground = false
        grantedNetwork = null; hotspotSsid = ""
        netCallback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }; netCallback = null
        try { serverSocket?.close() } catch (_: Exception) {}; serverSocket = null
        try { reservation?.close() } catch (_: Exception) {}; reservation = null
        if (fsm.state != LinkState.State.DOWN && fsm.state != LinkState.State.IDLE) fsm.fail(reason, now())
        setPhase("DOWN", reason + (if (fsm.retryDelayMs() > 0) " (retry allowed in " + (fsm.retryDelayMs() / 1000) + "s)" else ""))
        cancelSent = false
    }

    /** v0.6: write one tunnel frame on the current authenticated link. False if there is no link. */
    fun sendTunnel(type: Int, streamId: Int, data: ByteArray = ByteArray(0)): Boolean {
        val l = link ?: return false
        if (!fsm.isUp || !l.isOpen) return false
        val bytes = try { Tunnel.encode(type, streamId, data) } catch (e: Exception) { DiagLog.e(tag, "tunnel encode: " + LinkIo.describe(e)); return false }
        return l.writeTunnel(bytes)
    }

    /** v0.9: write one relay frame (FRAME_RELAY / FRAME_RELAY_INFO) on the current link. */
    fun sendRaw(type: Int, payload: ByteArray): Boolean {
        val l = link ?: return false
        if (!fsm.isUp || !l.isOpen) return false
        val ok = l.writeRaw(type, payload)
        if (ok) relaySent.addAndGet(payload.size.toLong())
        return ok
    }

    /** The link's TCP socket, so the VPN can exclude it from the tunnel it creates. */
    fun linkSocket(): Socket? = link?.socketForProtect()

    /** v0.9 probe: where this link's socket is bound, as Android sees it. */
    fun linkDescription(): String {
        val l = link ?: return "no link"
        val s = l.socketForProtect()
        return (if (l.isHost) "host" else "client") + " socket " + s.localAddress?.hostAddress + ":" + s.localPort + " -> " + s.inetAddress?.hostAddress + ":" + s.port +
            (if (l.isHost) " (accepted on hotspot " + hotspotSsid + ")" else " (bound to network " + grantedNetwork + ")") + ", open=" + l.isOpen
    }

    val tunnelBytesSent: Long get() = link?.tunnelSent ?: 0L
    val tunnelBytesReceived: Long get() = link?.tunnelReceived ?: 0L

    override fun sendBatch(peerShort: String, frames: List<Frame>, onEach: (Int, DeliveryResult, String) -> Boolean, onDone: () -> Unit) {
        val l = link
        if (l == null || !fsm.isUp || fsm.peer != peerShort || !l.isOpen) {
            if (frames.isNotEmpty()) onEach(0, DeliveryResult.TRANSPORT_FAILED, "no Wi-Fi link to prok-" + peerShort)
            onDone(); return
        }
        sendExec.execute {
            for ((i, f) in frames.withIndex()) {
                val (res, detail) = l.sendAndWait(f)
                val cont = try { onEach(i, res, detail) } catch (e: Exception) { false }
                if (res == DeliveryResult.TRANSPORT_FAILED) { main.post { fail(detail) }; break }
                if (!cont) break
            }
            onDone()
        }
    }

    /**
     * One authenticated TCP connection on top of core/LinkIo (v0.6.1): all
     * writes go through the link's writer thread, so no caller ever touches the
     * socket on its own thread (Android forbids it on the main thread).
     */
    private inner class Link(private val socket: Socket, val isHost: Boolean) {
        private val io = LinkIo(socket.getInputStream(), socket.getOutputStream(), if (isHost) "wifi-host" else "wifi-client")
        var peerRecord: Wire.IdentityRecord? = null
        val isOpen: Boolean get() = io.isOpen
        val tunnelSent: Long get() = tunnelOut
        val tunnelReceived: Long get() = tunnelIn
        @Volatile private var tunnelOut = 0L
        @Volatile private var tunnelIn = 0L
        private val receiptLock = Object()
        private var awaitingMsg: ByteArray? = null
        private var receiptStatus = -1

        init { socket.soTimeout = 0; socket.tcpNoDelay = true; socket.keepAlive = true }

        fun socketForProtect(): Socket = socket

        fun handshake(): Boolean {
            socket.soTimeout = 15_000
            val r = Handshake.perform(io, identity)
            if (r.peer == null) { DiagLog.w(tag, "AUTH failed: " + r.why); return false }
            DiagLog.i(tag, "AUTH: HELLO from prok-" + r.peer.shortId + " \"" + r.peer.name + "\", peer signature VERIFIED")
            peerRecord = r.peer
            socket.soTimeout = 0
            io.startWriter { why -> DiagLog.e(tag, "link writer stopped: " + why); main.post { if (link === this) fail(why) } }
            return true
        }

        fun writeRaw(type: Int, bytes: ByteArray): Boolean {
            val onMain = Looper.myLooper() == Looper.getMainLooper()
            val ok = io.enqueue(type, bytes, block = !onMain)
            if (!ok) DiagLog.w(tag, "relay frame dropped (" + (if (onMain) "main thread, queue full " + io.queuedFrames else "link closed") + ")")
            return ok
        }

        /** Tunnel frames: block on data threads (backpressure), never on the main thread. */
        fun writeTunnel(bytes: ByteArray): Boolean {
            val onMain = Looper.myLooper() == Looper.getMainLooper()
            val ok = io.enqueue(Wire.FRAME_TUNNEL, bytes, block = !onMain)
            if (ok) tunnelOut += bytes.size else DiagLog.w(tag, "tunnel frame dropped (" + (if (onMain) "main thread, queue full " + io.queuedFrames else "link closed") + ")")
            return ok
        }

        fun readLoop() {
            io.startReader({ type, p ->
                when (type) {
                    Wire.FRAME_PACKET -> {
                        val pkt = Packet.decode(p)
                        val code = if (pkt == null) Routing.RECEIPT_REJECTED else try { listener?.onFrame(name, peerRecord?.shortId, p) ?: Routing.RECEIPT_REJECTED } catch (e: Exception) { DiagLog.e(tag, "frame handler", e); Routing.RECEIPT_REJECTED }
                        io.enqueue(Wire.FRAME_RECEIPT, Wire.receiptPayload(code, pkt?.msgId ?: ByteArray(8)), block = true)
                        received.addAndGet(p.size.toLong())
                    }
                    Wire.FRAME_TUNNEL -> {
                        tunnelIn += p.size
                        val f = Tunnel.decode(p)
                        val peer = peerRecord?.shortId
                        if (f == null || peer == null) DiagLog.w(tag, "malformed tunnel frame ignored (" + p.size + " bytes)")
                        else try { tunnelSink?.onTunnelFrame(peer, f) } catch (e: Exception) { DiagLog.e(tag, "tunnel sink: " + LinkIo.describe(e)) }
                    }
                    Wire.FRAME_RELAY, Wire.FRAME_RELAY_INFO -> {
                        relayReceived.addAndGet(p.size.toLong())
                        val peer = peerRecord?.shortId
                        if (peer != null) try { tunnelSink?.onRawFrame(peer, type, p) } catch (e: Exception) { DiagLog.e(tag, "relay sink: " + LinkIo.describe(e)) }
                    }
                    Wire.FRAME_RECEIPT -> {
                        val r = Wire.parseReceipt(p)
                        if (r != null) synchronized(receiptLock) {
                            if (awaitingMsg != null && r.msgId.contentEquals(awaitingMsg)) { receiptStatus = r.status; receiptLock.notifyAll() }
                        }
                    }
                    else -> {}
                }
            }, { why ->
                DiagLog.w(tag, "link read loop ended: " + why)
                synchronized(receiptLock) { receiptLock.notifyAll() }
                main.post { if (link === this) fail("connection closed: " + why) }
            })
        }

        fun sendAndWait(f: Frame): Pair<DeliveryResult, String> {
            synchronized(receiptLock) { awaitingMsg = f.msgId; receiptStatus = -1 }
            if (!io.enqueue(Wire.FRAME_PACKET, f.bytes, block = true)) return DeliveryResult.TRANSPORT_FAILED to ("link closed: " + io.closeReason)
            sent.addAndGet(f.bytes.size.toLong())
            val deadline = System.currentTimeMillis() + RECEIPT_TIMEOUT_MS
            synchronized(receiptLock) {
                while (receiptStatus < 0 && io.isOpen) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) break
                    receiptLock.wait(left)
                }
                val st = receiptStatus; awaitingMsg = null
                if (st < 0) return (if (io.isOpen) DeliveryResult.NO_RECEIPT else DeliveryResult.TRANSPORT_FAILED) to "no receipt over Wi-Fi within " + (RECEIPT_TIMEOUT_MS / 1000) + "s"
                return Routing.resultFor(st) to ("Wi-Fi receipt " + st)
            }
        }

        fun close() {
            io.close("closed by transport")
            try { socket.close() } catch (_: Exception) {}
            synchronized(receiptLock) { receiptLock.notifyAll() }
        }
    }

    companion object {
        const val PORT = 47741
        const val STEP_TIMEOUT_MS = 120_000L
        const val JOIN_TIMEOUT_MS = 60_000L
        const val RECEIPT_TIMEOUT_MS = 15_000L
    }
}
