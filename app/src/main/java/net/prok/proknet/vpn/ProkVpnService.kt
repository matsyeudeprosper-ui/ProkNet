package net.prok.proknet.vpn

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import net.prok.proknet.ProkNetApp
import net.prok.proknet.core.DiagLog

/**
 * Buyer-side VPN (v0.6). Captures every IPv4 packet of every app on this
 * phone (except ProkNet itself) and hands it to the TunnelClient, which runs
 * the user-space TCP/DNS endpoints and forwards over the ProkNet Wi-Fi link.
 *
 *   TUN address 10.8.0.2/24, route 0.0.0.0/0, DNS 10.8.0.1 (intercepted), MTU 1500.
 *   ProkNet's own package is excluded from the VPN (addDisallowedApplication) AND the
 *   Wi-Fi link socket is protect()ed, so the tunnel can never loop into itself.
 *   No IPv6 address/route: apps see an IPv4-only network and do not try v6.
 */
class ProkVpnService : VpnService() {
    private val tag = "VPN"
    private var tun: ParcelFileDescriptor? = null
    private var reader: Thread? = null
    @Volatile private var alive = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { shutdown("stop requested"); stopSelf(); return START_NOT_STICKY }
            else -> startTun()
        }
        return START_NOT_STICKY
    }

    private fun startTun() {
        if (alive) { DiagLog.i(tag, "already running"); return }
        val node = ProkNetApp.node(this)
        val client = node.tunnel
        try {
            val b = Builder().setSession("ProkNet Internet via prok-" + (client.providerShort ?: "?"))
                .addAddress("10.8.0.2", 24)
                .addRoute("0.0.0.0", 0)
                .addDnsServer("10.8.0.1")
                .setMtu(1500)
                .setBlocking(true)
            try { b.addDisallowedApplication(packageName) } catch (e: Exception) { DiagLog.w(tag, "disallow self: " + e) }
            node.wifi.linkSocket()?.let { if (!protect(it)) DiagLog.w(tag, "protect(link socket) returned false") else DiagLog.i(tag, "Wi-Fi link socket protected from the VPN") }
            val fd = b.establish() ?: run { DiagLog.e(tag, "establish() returned null (VPN permission revoked or another VPN active)"); client.vpnUp = false; stopSelf(); return }
            tun = fd
            alive = true
            client.vpnUp = true
            val out = FileOutputStream(fd.fileDescriptor)
            val writeLock = Object()
            client.tunWriter = { pkt -> synchronized(writeLock) { try { out.write(pkt) } catch (e: Exception) { DiagLog.w(tag, "tun write: " + e) } } }
            reader = Thread({
                val input = FileInputStream(fd.fileDescriptor)
                val buf = ByteArray(32767)
                var packets = 0L
                DiagLog.i(tag, "TUN reader started")
                while (alive) {
                    val n = try { input.read(buf) } catch (e: Exception) { if (alive) DiagLog.w(tag, "tun read: " + e); break }
                    if (n <= 0) continue
                    packets++
                    try { client.onTunPacket(buf, n) } catch (e: Exception) { DiagLog.e(tag, "packet handler", e) }
                }
                DiagLog.i(tag, "TUN reader stopped after " + packets + " packets")
            }, "proknet-tun").apply { isDaemon = true; start() }
            running = true
            DiagLog.i(tag, "VPN UP: 10.8.0.2/24, default route, DNS 10.8.0.1, MTU 1500, own app excluded")
            node.onVpnChanged()
        } catch (e: Exception) {
            DiagLog.e(tag, "VPN start failed", e)
            client.vpnUp = false
            shutdown("start failed: " + e.message)
            stopSelf()
        }
    }

    private fun shutdown(reason: String) {
        if (!alive && tun == null) return
        alive = false
        running = false
        val node = ProkNetApp.node(this)
        node.tunnel.tunWriter = null
        node.tunnel.vpnUp = false
        try { tun?.close() } catch (_: Exception) {}
        tun = null
        DiagLog.i(tag, "VPN DOWN: " + reason)
        node.onVpnChanged()
    }

    override fun onRevoke() {
        DiagLog.w(tag, "VPN revoked by the system or another VPN app")
        shutdown("revoked")
        ProkNetApp.node(this).tunnel.stop("VPN revoked")
        super.onRevoke()
    }

    override fun onDestroy() { shutdown("service destroyed"); super.onDestroy() }

    companion object {
        const val ACTION_START = "net.prok.proknet.VPN_START"
        const val ACTION_STOP = "net.prok.proknet.VPN_STOP"
        @Volatile var running = false
            private set

        fun start(ctx: android.content.Context) { ctx.startService(Intent(ctx, ProkVpnService::class.java).setAction(ACTION_START)) }
        fun stop(ctx: android.content.Context) { ctx.startService(Intent(ctx, ProkVpnService::class.java).setAction(ACTION_STOP)) }
    }
}
