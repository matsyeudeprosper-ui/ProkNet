package net.prok.proknet.node

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.concurrent.Executors
import net.prok.proknet.ble.Peer
import net.prok.proknet.ble.ProkNetNode
import net.prok.proknet.core.Coverage
import net.prok.proknet.core.CoverageModel
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.GetInternet
import net.prok.proknet.core.InternetRequest

/**
 * v0.12: the phone as a coverage sensor, and the executor of GET INTERNET.
 *
 * Observations come from what the app already does: the BLE peer list the
 * node maintains, the Wi-Fi network the phone is on, and Android's cached
 * scan results when the app is in front and Location is granted. No scan is
 * ever started by this class. Location is coarse and only used to name a
 * ~500 m cell; nothing here stores a point. Everything is persisted through
 * the pure codec in [CoverageModel], so the model on disk is the one the
 * tests exercise.
 */
class CoverageEngine(private val context: Context) : ProkNetNode.Listener {
    private val tag = "COVER"
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val file = File(context.filesDir, "coverage.v1.txt")
    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
    private val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    @Volatile var state: CoverageModel.State = CoverageModel.EMPTY
        private set
    @Volatile private var dirty = false
    @Volatile var lastDecision: GetInternet.Decision? = null
        private set
    @Volatile var foreground = false
        private set
    @Volatile private var lastLat = Double.NaN
    @Volatile private var lastLon = Double.NaN
    @Volatile private var lastLocationAt = 0L
    @Volatile var lastWifiObservation = ""
        private set
    private val lastSightingAt = HashMap<String, Long>()
    private var node: ProkNetNode? = null

    companion object {
        const val SIGHTING_THROTTLE_MS = 60_000L
        const val LOCATION_MAX_AGE_MS = 30 * 60_000L
        const val WIFI_PERIOD_MS = 5 * 60_000L
        const val SAVE_DELAY_MS = 20_000L
        const val LOCATION_MIN_TIME_MS = 5 * 60_000L
        const val LOCATION_MIN_DISTANCE_M = 300f
    }

    init { load() }

    fun attach(n: ProkNetNode) { node = n; n.addListener(this) }

    // ---- location: coarse, opportunistic -------------------------------------------------------------

    fun hasLocationPermission(): Boolean =
        context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    fun hasFineLocation(): Boolean = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The cell this phone is in, or [CoverageModel.NO_ZONE] when it does not know. */
    fun zone(): String {
        refreshLocation()
        val now = System.currentTimeMillis()
        return if (lastLat.isNaN() || now - lastLocationAt > LOCATION_MAX_AGE_MS) CoverageModel.NO_ZONE else CoverageModel.zoneId(lastLat, lastLon)
    }

    private fun refreshLocation() {
        if (!hasLocationPermission()) return
        try {
            for (p in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
                val l = try { lm?.getLastKnownLocation(p) } catch (_: Exception) { null } ?: continue
                if (l.time > lastLocationAt) { lastLat = l.latitude; lastLon = l.longitude; lastLocationAt = l.time }
            }
        } catch (e: SecurityException) { DiagLog.w(tag, "location: " + e.message) }
    }

    private val locListener = object : LocationListener {
        override fun onLocationChanged(l: Location) {
            val moved = lastLat.isNaN() || CoverageModel.zoneId(lastLat, lastLon) != CoverageModel.zoneId(l.latitude, l.longitude)
            lastLat = l.latitude; lastLon = l.longitude; lastLocationAt = l.time
            if (moved) observeWifi("moved to another cell")
        }
        @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    fun onForeground() {
        foreground = true
        refreshLocation()
        if (hasLocationPermission()) try {
            lm?.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, LOCATION_MIN_TIME_MS, LOCATION_MIN_DISTANCE_M, locListener, Looper.getMainLooper())
        } catch (e: Exception) { DiagLog.w(tag, "location updates: " + e.message) }
        observeWifi("app in front")
        main.removeCallbacks(periodic); main.postDelayed(periodic, WIFI_PERIOD_MS)
    }

    fun onBackground() {
        foreground = false
        try { lm?.removeUpdates(locListener) } catch (_: Exception) {}
        main.removeCallbacks(periodic)
        flush()
    }

    private val periodic = object : Runnable { override fun run() { if (!foreground) return; observeWifi("periodic"); main.postDelayed(this, WIFI_PERIOD_MS) } }

    // ---- observations --------------------------------------------------------------------------------

    override fun onPeers(peers: List<Peer>) {
        val now = System.currentTimeMillis()
        val z = zone()
        for (p in peers) {
            if (!p.inRange || !p.hasId) continue
            val o = p.offer()
            sight(CoverageModel.Sighting(
                CoverageModel.SourceKind.PROKNET, p.shortId, node?.peerName(p.shortId) ?: p.label, p.rssi,
                if (p.lastSeen > 0) minOf(p.lastSeen, now) else now, z,
                if (o.selling) o.validated else null, if (o.selling) o.pricePerMb * 100 else CoverageModel.PRICE_UNKNOWN,
                if (o.selling) Coverage.Trust.AUTHORIZED_PRIVATE else Coverage.Trust.UNKNOWN, "", o.selling))
        }
    }
    override fun onMessagesChanged() {}
    override fun onStatus(status: String) {}

    class ConnectedWifi(val ssid: String, val rawId: String, val rssi: Int, val validated: Boolean, val security: String)

    /** The Wi-Fi network this phone is on, with Android's own verdict on its Internet. */
    @Suppress("DEPRECATION")
    fun connectedWifi(): ConnectedWifi? {
        try {
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                if (!caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) continue
                val validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
                val info = (if (Build.VERSION.SDK_INT >= 29) caps.transportInfo as? WifiInfo else null) ?: wm?.connectionInfo
                val ssid = info?.ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" } ?: "Wi-Fi"
                val bssid = info?.bssid?.takeIf { it.isNotEmpty() && it != "02:00:00:00:00:00" }
                return ConnectedWifi(ssid, bssid ?: ("ssid:" + ssid), info?.rssi ?: -60, validated, "")
            }
        } catch (e: Exception) { DiagLog.w(tag, "connected wifi: " + e.message) }
        return null
    }

    /** Observe what the phone can see without starting anything. */
    fun observeWifi(why: String) {
        val now = System.currentTimeMillis()
        val z = zone()
        var n = 0
        connectedWifi()?.let { w ->
            // this phone is on it, so it may use it; that is the ONLY case a Wi-Fi is trusted here
            sight(CoverageModel.Sighting(CoverageModel.SourceKind.WIFI, w.rawId, w.ssid, w.rssi, now, z, w.validated,
                if (w.validated) 0 else CoverageModel.PRICE_UNKNOWN, if (w.validated) Coverage.Trust.AUTHORIZED_PRIVATE else Coverage.Trust.UNKNOWN, w.security, false))
            n++
        }
        if (hasFineLocation()) try {
            for (r in wm?.scanResults.orEmpty()) {
                val bssid = r.BSSID ?: continue
                val ssid = (if (Build.VERSION.SDK_INT >= 33) r.wifiSsid?.toString()?.trim('"') else r.SSID) ?: ""
                // detected only: not validated, not authorized, no price known
                sight(CoverageModel.Sighting(CoverageModel.SourceKind.WIFI, bssid, ssid.ifEmpty { "(réseau masqué)" }, r.level, now, z, null,
                    CoverageModel.PRICE_UNKNOWN, Coverage.Trust.UNKNOWN, Coverage.securityOf(r.capabilities ?: ""), false))
                n++
            }
        } catch (e: SecurityException) { DiagLog.w(tag, "scan results: " + e.message) } catch (e: Exception) { DiagLog.w(tag, "scan results: " + e.message) }
        lastWifiObservation = why + ": " + n + " network(s) in " + z
    }

    private fun sight(s: CoverageModel.Sighting) {
        val id = CoverageModel.sourceId(s.kind, s.rawId)
        synchronized(this) {
            val last = lastSightingAt[id]
            if (last != null && s.at - last < SIGHTING_THROTTLE_MS) return
            lastSightingAt[id] = s.at
            val (m, o) = CoverageModel.observe(state.sources, s)
            state = state.copy(sources = m, observations = (state.observations + o).takeLast(CoverageModel.MAX_OBSERVATIONS))
            dirty = true
        }
        scheduleSave()
    }

    // ---- GET INTERNET ---------------------------------------------------------------------------------

    fun candidates(): List<GetInternet.Candidate> {
        val n = node ?: return emptyList()
        val now = System.currentTimeMillis()
        val out = ArrayList<GetInternet.Candidate>()
        connectedWifi()?.let { w ->
            if (w.validated) out += GetInternet.Candidate(CoverageModel.sourceId(CoverageModel.SourceKind.WIFI, w.rawId), GetInternet.Way.CONNECTED_WIFI, w.ssid, 0, true, w.rssi, now, true, 0.9)
        }
        val peers = n.peers()
        for (o in n.offers()) {
            val reachable = peers.any { it.shortId == o.sellerShort && it.inRange } && n.hasKey(o.sellerShort)
            val linkUp = n.buyerLinkUp() && (n.wifi.linkedPeer == o.sellerShort || n.bulk.linkedPeer == o.sellerShort)
            out += GetInternet.fromOffer(o, reachable, linkUp, state.sources[CoverageModel.sourceId(CoverageModel.SourceKind.PROKNET, o.sellerShort)], now)
        }
        return out
    }

    fun decide(ceilingCentimesPerMb: Int?): GetInternet.Decision {
        val d = GetInternet.decide(candidates(), System.currentTimeMillis(), ceilingCentimesPerMb, state.lastSuccessfulSourceId)
        lastDecision = d
        return d
    }

    fun onSuccess(sourceId: String) {
        synchronized(this) { state = state.copy(sources = CoverageModel.withSuccess(state.sources, sourceId), lastSuccessfulSourceId = sourceId); dirty = true }
        DiagLog.i(tag, "Internet reached through " + sourceId + ": remembered as the last successful source")
        scheduleSave()
    }

    fun recordRequest(r: InternetRequest.Request) {
        synchronized(this) { state = state.copy(requests = (state.requests.filter { it.id != r.id } + r).takeLast(CoverageModel.MAX_REQUESTS)); dirty = true }
        scheduleSave()
    }

    fun reachableIds(): Set<String> {
        val n = node
        val ids = HashSet<String>()
        n?.offers()?.forEach { ids += CoverageModel.sourceId(CoverageModel.SourceKind.PROKNET, it.sellerShort) }
        connectedWifi()?.let { if (it.validated) ids += CoverageModel.sourceId(CoverageModel.SourceKind.WIFI, it.rawId) }
        return ids
    }

    fun cells(): List<CoverageModel.Cell> = CoverageModel.cells(state.sources.values, System.currentTimeMillis(), reachableIds())
    fun hereStatus(): Coverage.ZoneStatus = CoverageModel.hereStatus(cells(), zone())
    fun sourcesIn(zone: String): List<CoverageModel.Source> = state.sources.values.filter { zone in it.zones }.sortedByDescending { it.lastSeen }

    // ---- persistence ------------------------------------------------------------------------------------

    private fun load() {
        try { if (file.exists()) state = CoverageModel.decode(file.readText(Charsets.UTF_8)) } catch (e: Exception) { DiagLog.w(tag, "coverage load: " + e.message) }
        DiagLog.i(tag, "coverage loaded: " + state.sources.size + " source(s), " + state.observations.size + " observation(s), " + state.requests.size + " request(s)")
    }

    private val saver = Runnable { flush() }
    private fun scheduleSave() { main.removeCallbacks(saver); main.postDelayed(saver, SAVE_DELAY_MS) }

    fun flush() {
        if (!dirty) return
        dirty = false
        val text = CoverageModel.encode(state)
        io.execute { try { file.writeText(text, Charsets.UTF_8) } catch (e: Exception) { DiagLog.w(tag, "coverage save: " + e.message) } }
    }

    // ---- diagnostics -----------------------------------------------------------------------------------

    fun diag(): String {
        val now = System.currentTimeMillis()
        val sb = StringBuilder("coverage:\n")
        sb.append("  zone: ").append(zone()).append(if (hasLocationPermission()) "" else " (no location permission)").append(if (hasFineLocation()) " fine" else " coarse-only").append("\n")
        sb.append("  sources: ").append(state.sources.size).append(" | observations: ").append(state.observations.size).append(" | requests: ").append(state.requests.size).append("\n")
        sb.append("  last wifi observation: ").append(lastWifiObservation.ifEmpty { "-" }).append("\n")
        sb.append("  last successful source: ").append(state.lastSuccessfulSourceId ?: "-").append("\n")
        val reach = reachableIds()
        for (s in state.sources.values.sortedByDescending { it.lastSeen }.take(40)) {
            sb.append("  ").append(s.id).append(" ").append(s.kind).append(" \"").append(s.name).append("\" seen ").append(s.observations).append("x, last ")
                .append(CoverageModel.ageWord(now - s.lastSeen)).append(", rssi ").append(s.lastRssi).append("/").append(s.bestRssi)
                .append(", price ").append(s.priceCentimesPerMb).append("c, validated ").append(s.validated).append(" (").append(s.validatedOk).append("/").append(s.validatedSeen).append(")")
                .append(", trust ").append(s.trust).append(if (s.security.isNotEmpty()) ", " + s.security else "").append(", selling ").append(s.selling)
                .append(", successes ").append(s.successes).append(", zones ").append(s.zones.joinToString(",")).append(if (s.id in reach) " REACHABLE" else "").append("\n")
        }
        sb.append("  cells:\n")
        for (c in cells().take(30)) sb.append("    ").append(c.zoneId).append(" ").append(c.status).append(" direct=").append(c.directSourceCount).append(" potential=").append(c.potentialSourceCount)
            .append(" price=").append(c.bestKnownPrice).append(" conf=").append(String.format("%.2f", c.confidence)).append(" last ").append(CoverageModel.ageWord(now - c.lastObservedAt)).append("\n")
        sb.append("  requests:\n")
        for (r in state.requests.takeLast(10)) sb.append("    ").append(r.id).append(" ").append(r.state).append(" zone ").append(r.zone).append(" source ").append(r.sourceId ?: "-").append(" : ").append(r.note).append("\n")
        lastDecision?.let { sb.append("  last GET INTERNET ").append(GetInternet.describe(it)) }
        return sb.toString()
    }
}
