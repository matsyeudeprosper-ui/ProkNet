package net.prok.proknet.node

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Handler
import android.os.Looper
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.ShareCheck

/**
 * The v0.9.6 capability probe: try to create a local-only hotspot RIGHT NOW,
 * close it immediately, and report whether Android allowed it. It is the
 * only way to know, because `startLocalOnlyHotspot` gives an app no control
 * over the band and no way to ask in advance.
 *
 * It runs when a phone starts SHARING while its Internet is a Wi-Fi network,
 * before any customer exists, so the seller learns the answer from its own
 * screen instead of from a failed sale.
 */
object HotspotProbe {
    private const val tag = "SHARECHK"
    private const val TIMEOUT_MS = 15_000L

    class Outcome(val started: Boolean, val detail: String, val freqMhz: Int, val networkKey: String)

    @Volatile private var running = false

    /**
     * @param freqMhz   the frequency of the Wi-Fi network this phone is on (0 if unknown)
     * @param networkKey ShareCheck.key(ssid, bssid)
     */
    fun run(context: Context, freqMhz: Int, networkKey: String, cb: (Outcome) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        if (running) { cb(Outcome(false, "another probe is already running", freqMhz, networkKey)); return }
        running = true
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        var done = false
        var reservation: WifiManager.LocalOnlyHotspotReservation? = null

        fun finish(started: Boolean, detail: String) {
            if (done) return
            done = true; running = false
            try { reservation?.close() } catch (_: Exception) {}
            reservation = null
            DiagLog.i(tag, "probe result: " + (if (started) "CAN share while on " else "CANNOT share while on ") + ShareCheck.describe(freqMhz) + " - " + detail)
            cb(Outcome(started, detail, freqMhz, networkKey))
        }

        DiagLog.i(tag, "testing whether this phone can host a hotspot while staying on its Wi-Fi network (" + ShareCheck.describe(freqMhz) + ")")
        main.postDelayed({ finish(false, "Android never answered within " + (TIMEOUT_MS / 1000) + "s") }, TIMEOUT_MS)
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    reservation = res
                    // close it at once: this is a test, not a service
                    finish(true, "hotspot started and closed immediately")
                }
                override fun onFailed(reason: Int) = finish(false, "reason " + reason + hint(reason))
                override fun onStopped() { /* our own close, or the system; the result is already reported */ }
            }, main)
        } catch (e: SecurityException) {
            finish(false, "permission refused: " + e.message)
        } catch (e: Exception) {
            finish(false, "call failed: " + e.javaClass.simpleName)
        }
    }

    private fun hint(reason: Int) = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> " (no channel: the hotspot cannot use this Wi-Fi network's channel)"
        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC -> " (generic: Location on? Android hotspot off?)"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> " (incompatible mode: the Android hotspot / tethering is already active)"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> " (tethering disallowed by policy)"
        else -> ""
    }
}
