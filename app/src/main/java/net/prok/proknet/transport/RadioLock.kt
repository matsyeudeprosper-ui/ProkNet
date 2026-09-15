package net.prok.proknet.transport

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import net.prok.proknet.core.DiagLog

/**
 * v0.9.16: **keep the Wi-Fi radio awake while a Wi-Fi Direct link is live.**
 *
 * The v0.9.15 phone run measured the link for the first time, and the two
 * verdicts did not agree, which is the whole finding:
 *
 * ```
 * buyer  (client) LINK PROBE verdict: NO IP packet crossed ... (sent 5, replies 0, answered by us 0)
 * seller (owner)  LINK PROBE: a packet DID cross, 10 bytes from 192.168.49.124   x6
 * seller (owner)  LINK PROBE verdict: packets arrive here but our answers do not get back
 * ```
 *
 * Every packet the CLIENT sent reached the owner. Nothing the owner sent
 * reached the client: not the UDP answers, not the TCP handshake, in either
 * dial direction. The link is one way, uplink only.
 *
 * Downlink to a client that is asleep is the classic cause: a group owner has
 * to buffer frames for a power saving client and deliver them at the beacon,
 * and on a phone whose single radio is also serving a home Wi-Fi connection
 * that delivery is where things are dropped. An application cannot fix the
 * driver, but it CAN refuse to let the radio sleep, which is what a Wi-Fi
 * lock is for and what every Wi-Fi Direct data transfer is supposed to hold.
 *
 * It is held while a group exists on this phone, on BOTH sides, and released
 * the moment the group is gone. Nothing else about the radio is touched.
 */
class RadioLock(context: Context) {

    private val tag = "P2P"
    private val app = context.applicationContext
    private var high: WifiManager.WifiLock? = null
    private var low: WifiManager.WifiLock? = null

    /** What is held right now, for the log and the diagnostic. */
    @Volatile var state: String = "none"
        private set

    val held: Boolean get() = high?.isHeld == true || low?.isHeld == true

    private fun wifi(): WifiManager? = try {
        app.getSystemService(Context.WIFI_SERVICE) as? WifiManager
    } catch (e: Exception) { null }

    fun acquire(why: String) {
        if (held) return
        val wm = wifi()
        if (wm == null) { state = "no WifiManager"; DiagLog.w(tag, "RADIO LOCK: no WifiManager"); return }
        val got = ArrayList<String>()
        try {
            @Suppress("DEPRECATION")
            val h = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "prok-p2p-high")
            h.setReferenceCounted(false)
            h.acquire()
            high = h
            if (h.isHeld) got.add("HIGH_PERF")
        } catch (e: Exception) { DiagLog.w(tag, "RADIO LOCK high perf refused: " + e) }
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                val l = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "prok-p2p-low")
                l.setReferenceCounted(false)
                l.acquire()
                low = l
                if (l.isHeld) got.add("LOW_LATENCY")
            } catch (e: Exception) { DiagLog.w(tag, "RADIO LOCK low latency refused: " + e) }
        }
        state = if (got.isEmpty()) "none" else got.joinToString("+")
        DiagLog.i(tag, "RADIO LOCK held: " + state + " (" + why + ")" +
            (if (got.contains("LOW_LATENCY")) " | low latency needs the screen on to take effect" else ""))
    }

    fun release(why: String) {
        var had = false
        try { high?.let { if (it.isHeld) { it.release(); had = true } } } catch (e: Exception) { DiagLog.w(tag, "RADIO LOCK release: " + e) }
        try { low?.let { if (it.isHeld) { it.release(); had = true } } } catch (e: Exception) { DiagLog.w(tag, "RADIO LOCK release: " + e) }
        high = null
        low = null
        state = "none"
        if (had) DiagLog.i(tag, "RADIO LOCK released (" + why + ")")
    }
}
