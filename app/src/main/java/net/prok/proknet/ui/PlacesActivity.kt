package net.prok.proknet.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import net.prok.proknet.ProkNetApp
import net.prok.proknet.R
import net.prok.proknet.core.CoverageModel
import net.prok.proknet.core.PlacesView
import net.prok.proknet.core.StraightLineRouter
import net.prok.proknet.core.WalkRoute
import net.prok.proknet.core.WalkRouter
import net.prok.proknet.node.PlacesSync

/**
 * v0.19.0: "Internet gratuit à Brazzaville" - the free Internet finder.
 *
 * What this screen promises and keeps:
 *  - it opens with no account, no payment, no location and no network: the cached city
 *    index is shown with "mis à jour il y a …";
 *  - a card's status words are the server's (one shared table); a stale venue leaves the
 *    default list but is found by search and filters;
 *  - a distance is a walking distance only when [router] returned a route; otherwise it is
 *    "≈ … à vol d'oiseau" and there is never an ETA; without a position there is no
 *    distance and no direction at all;
 *  - "M'y guider" shows what is left to walk and, on arrival, re-checks the connection and
 *    tells the Brain what this phone actually saw (a DEVICE check);
 *  - "Contribuer" sends a coarse sighting - the cell and a hash, never the network name -
 *    and states the scout offer first, "pas d'offre scout payée" included.
 */
class PlacesActivity : Activity() {

    private val io = Executors.newSingleThreadExecutor()
    private val node by lazy { ProkNetApp.node(this) }
    private val coverage by lazy { ProkNetApp.coverage(this) }
    private val sync by lazy { PlacesSync(node.identity, { node.brainUrlProvider?.invoke() ?: "" }, filesDir) }
    private val lm by lazy { getSystemService(Context.LOCATION_SERVICE) as? LocationManager }

    private var index: PlacesView.Index? = null
    private var refreshedAt = 0L
    private val filters = HashSet<PlacesView.Filter>()
    private var neighbourhood = ""
    private var query = ""
    private var here: PlacesView.Pos? = null

    companion object {
        /** The offline pedestrian router, when a map pack is installed; StraightLineRouter answers null. */
        @Volatile var router: WalkRouter = StraightLineRouter
        private const val REQ_LOCATION = 71
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_places)
        findViewById<View>(R.id.hdBack).setOnClickListener { finish() }
        if (intent.getStringExtra("filter") == "working_now") filters.add(PlacesView.Filter.WORKING_NOW)
        findViewById<Button>(R.id.btnPlRefresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.btnPlContribute).setOnClickListener { contributeDialog() }
        findViewById<Button>(R.id.btnPlLocation).setOnClickListener { requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOCATION) }
        findViewById<EditText>(R.id.plSearch).addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) { query = s?.toString() ?: ""; render() }
        })
        loadCache()
    }

    override fun onResume() {
        super.onResume()
        updateLocation()
        render()
        if (sync.configured) refresh()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_LOCATION) { updateLocation(); render() }
    }

    // ---- data -------------------------------------------------------------------------------

    private fun loadCache() {
        io.execute {
            val c = sync.cached()
            val idx = c?.let { PlacesView.parseIndex(it.text) }
            runOnUiThread { if (idx != null) { index = idx; refreshedAt = c.refreshedAt }; render() }
        }
    }

    private fun refresh() {
        io.execute {
            val ok = sync.refresh()
            val c = sync.cached()
            val idx = c?.let { PlacesView.parseIndex(it.text) }
            runOnUiThread {
                if (idx != null) { index = idx; refreshedAt = c.refreshedAt }
                if (!ok && sync.lastError.isNotEmpty()) text(R.id.plFooter, "Liste hors ligne : " + sync.lastError)
                render()
            }
        }
    }

    private fun hasLocationPermission(): Boolean =
        checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    private fun updateLocation() {
        here = null
        if (!hasLocationPermission()) return
        try {
            var best: Location? = null
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
                val l = try { lm?.getLastKnownLocation(p) } catch (_: Exception) { null } ?: continue
                if (best == null || l.time > best.time) best = l
            }
            best?.let { here = PlacesView.Pos(it.latitude, it.longitude) }
        } catch (_: SecurityException) {}
    }

    // ---- drawing ----------------------------------------------------------------------------

    private fun render() {
        val now = System.currentTimeMillis()
        val idx = index
        val hasLoc = here != null
        val locNote = if (hasLocationPermission()) (if (hasLoc) "" else "Position pas encore connue : distances indisponibles pour l'instant.") else PlacesView.NO_LOCATION_NOTE
        text(R.id.plLocationNote, locNote)
        show(R.id.plLocationCard, locNote.isNotEmpty())
        show(R.id.btnPlLocation, !hasLocationPermission())
        val filterRow = findViewById<LinearLayout>(R.id.plFilters)
        filterRow.removeAllViews()
        for (f in PlacesView.Filter.values()) {
            val on = f in filters
            filterRow.addView(Prok.chip(this, PlacesView.filterLabel(f), on, PlacesView.filterAvailable(f, hasLoc)) { if (on) filters.remove(f) else filters.add(f); render() })
        }
        if (idx == null) {
            text(R.id.plHeader, PlacesView.headerLine(sync.city, 0))
            text(R.id.plRefreshed, PlacesView.refreshedText(0, now))
            findViewById<LinearLayout>(R.id.plCards).removeAllViews()
            findViewById<LinearLayout>(R.id.plNeighbourhoods).removeAllViews()
            show(R.id.plEmpty, true)
            text(R.id.plFooter, sync.describe())
            return
        }
        text(R.id.plHeader, PlacesView.headerLine(idx.city, idx.count))
        text(R.id.plRefreshed, PlacesView.refreshedText(refreshedAt, now))
        val chips = findViewById<LinearLayout>(R.id.plNeighbourhoods)
        chips.removeAllViews()
        chips.addView(Prok.chip(this, "Tous quartiers", neighbourhood.isEmpty()) { neighbourhood = ""; render() })
        for (n in idx.neighbourhoods) chips.addView(Prok.chip(this, n, neighbourhood == n) { neighbourhood = if (neighbourhood == n) "" else n; render() })
        val cards = PlacesView.list(idx.venues, filters, neighbourhood, query, here, now)
        val box = findViewById<LinearLayout>(R.id.plCards)
        box.removeAllViews()
        show(R.id.plEmpty, cards.isEmpty())
        for (c in cards) box.addView(cardView(c, now))
        text(R.id.plFooter, sync.describe())
    }

    private fun cardView(c: PlacesView.Card, now: Long): LinearLayout {
        val pos = here
        val route = routeTo(c)
        val tone = when (c.status) { "WORKING_NOW" -> Prok.Tone.OK; "RECENTLY_VERIFIED" -> Prok.Tone.BRAND; "OLDER_CHECK" -> Prok.Tone.WARN; "UNAVAILABLE" -> Prok.Tone.DANGER; else -> Prok.Tone.MUTED }
        val box = Prok.card(this)
        box.addView(Prok.titleRow(this, c.name, c.statusText, tone))
        box.addView(Prok.muted(this, listOfNotNull(c.neighbourhood.takeIf { it.isNotEmpty() }, c.hoursLine(now)).joinToString(" · "), 2))
        val lines = ArrayList<String>()
        lines += c.accessText + (if (c.accessRule == "FREE_AFTER_SIGNIN" && c.signinPath.isNotEmpty()) " (" + c.signinPath + ")" else "")
        lines += c.freshnessLine() + " · confiance " + c.confidence + " %"
        PlacesView.distanceLine(c, pos, route).takeIf { it.isNotEmpty() }?.let { lines += it + (if (route == null) " · " + PlacesView.directionLine(c, pos) else "") }
        box.addView(Prok.body(this, lines.joinToString("\n"), 10))
        if (c.prokDeliverable) box.addView(Prok.pills(this, "Livrable par Prok ici" to Prok.Tone.BRAND))
        val buttons = ArrayList<View>()
        if (pos != null && c.hasEntrance) buttons += Prok.primary(this, "M'y guider") { guide(c) }
        buttons += Prok.ghost(this, "Signaler") { reportDialog(c) }
        box.addView(Prok.actions(this, *buttons.toTypedArray()))
        return box
    }

    private fun routeTo(c: PlacesView.Card): WalkRoute? {
        val pos = here ?: return null
        if (!c.hasEntrance) return null
        return try { router.route(pos.lat, pos.lon, c.lat, c.lon) } catch (_: Exception) { null }
    }

    // ---- M'y guider ----------------------------------------------------------------------------

    private fun guide(c: PlacesView.Card) {
        val view = TextView(this).apply { val p = Prok.dp(context, 22); setPadding(p, Prok.dp(context, 12), p, 0); textSize = 15f; setTextColor(getColor(R.color.text)) }
        var arrivedChecked = false
        fun update() {
            val route = routeTo(c)
            view.text = c.name + "\n" + c.freshnessLine() + "\n" + c.hoursLine(System.currentTimeMillis()) + "\n\n" +
                PlacesView.guidanceLine(c, here, route) +
                (if (route == null) "\n\nSans itinéraire piéton : la distance est à vol d'oiseau, pas un temps de marche." else "")
            val pos = here
            if (!arrivedChecked && pos != null && PlacesView.distanceM(pos.lat, pos.lon, c.lat, c.lon) <= PlacesView.ARRIVED_M) {
                arrivedChecked = true
                arrivalCheck(c) { line -> view.append("\n\n" + line) }
            }
        }
        val listener = object : LocationListener {
            override fun onLocationChanged(l: Location) { here = PlacesView.Pos(l.latitude, l.longitude); update() }
            @Deprecated("Deprecated in Java") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
        }
        update()
        val dialog = AlertDialog.Builder(this).setTitle("M'y guider").setView(view)
            .setPositiveButton("Je suis arrivé") { _, _ -> arrivalCheck(c) { line -> toast(line) } }
            .setNegativeButton("Fermer", null).create()
        dialog.setOnDismissListener { try { lm?.removeUpdates(listener) } catch (_: Exception) {}; render() }
        dialog.show()
        try {
            for (p in listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)) {
                if (p == LocationManager.GPS_PROVIDER && checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) continue
                try { lm?.requestLocationUpdates(p, 5_000L, 5f, listener) } catch (_: Exception) {}
            }
        } catch (_: SecurityException) {}
    }

    /** On arrival: what does Android say about the network this phone is on right now? Told to the Brain as a DEVICE check. */
    private fun arrivalCheck(c: PlacesView.Card, onLine: (String) -> Unit) {
        io.execute {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            val wifi = caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
            val captive = wifi && caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)
            val validated = wifi && !captive && caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            val line = when {
                validated -> "Internet validé sur le Wi-Fi du lieu."
                captive -> "Portail de connexion : pas encore d'Internet. " + (if (c.signinPath.isNotEmpty()) "Connectez-vous via " + c.signinPath else "Connectez-vous d'abord.")
                wifi -> "Wi-Fi connecté mais Internet non validé."
                else -> "Pas de Wi-Fi connecté ici : connectez-vous au réseau du lieu puis réessayez."
            }
            val out = if (wifi) sync.check(c.id, validated, "DEVICE", if (captive) "captive portal" else "") else null
            runOnUiThread { onLine(line + (if (out != null) "\n" + out.message else "")) }
        }
    }

    // ---- Signaler --------------------------------------------------------------------------------

    private fun reportDialog(c: PlacesView.Card) {
        val items = PlacesView.Report.values()
        AlertDialog.Builder(this).setTitle("Signaler · " + c.name)
            .setItems(items.map { PlacesView.reportLabel(it) }.toTypedArray()) { _, i ->
                io.execute {
                    val out = when (items[i]) {
                        PlacesView.Report.WORKED -> sync.check(c.id, true, "VISITOR")
                        PlacesView.Report.FAILED -> sync.check(c.id, false, "VISITOR")
                        PlacesView.Report.CLOSED -> sync.report(c.id, "CLOSURE", "")
                    }
                    runOnUiThread { toast(out.message); out.offer?.let { text(R.id.plOffer, it.line) }; if (out.ok) refresh() }
                }
            }.setNegativeButton("Annuler", null).show()
    }

    // ---- Contribuer ------------------------------------------------------------------------------

    private fun contributeDialog() {
        val zone = coverage.zone()
        val wifi = coverage.connectedWifi()
        val view = TextView(this).apply { val p = Prok.dp(context, 22); setPadding(p, Prok.dp(context, 12), p, 0); textSize = 14f; setTextColor(getColor(R.color.text)); text = "Vérification de l'offre scout…" }
        val body = StringBuilder()
        if (zone == CoverageModel.NO_ZONE) body.append("Zone inconnue : autorisez la position (500 m près) pour contribuer.\n\n")
        if (wifi == null) body.append("Aucun Wi-Fi connecté. Connectez-vous d'abord au réseau du lieu, puis revenez ici.\n\n")
        else body.append("Réseau connecté · Internet validé par Android : ").append(if (wifi.validated) "oui" else "non").append("\nZone : ").append(zone).append("\n\n")
        body.append("Seule une empreinte du réseau et la zone sont envoyées - jamais le nom du réseau, jamais votre position exacte. ")
            .append("Une observation ne publie aucun lieu : un opérateur doit enregistrer l'accord du lieu et deux contrôles indépendants.")
        val canSend = zone != CoverageModel.NO_ZONE && wifi != null
        val b = AlertDialog.Builder(this).setTitle("Contribuer une observation").setView(view).setNegativeButton("Annuler", null)
        if (canSend) b.setPositiveButton("Envoyer") { _, _ ->
            io.execute {
                val out = sync.sighting(zone, CoverageModel.hash16(wifi!!.rawId.lowercase()), "wifi", wifi.rssi, wifi.validated)
                runOnUiThread { toast(out.message); out.offer?.let { text(R.id.plOffer, it.line) } }
            }
        }
        b.show()
        io.execute {
            val offer = sync.offer()
            val line = if (offer == null) "Offre scout : inconnue (Brain injoignable). L'observation sera enregistrée sans promesse de crédit."
                else "Offre scout : " + offer.line
            runOnUiThread { view.text = line + "\n\n" + body; text(R.id.plOffer, offer?.line ?: "") }
        }
    }

    // ---- small helpers ---------------------------------------------------------------------------

    private fun text(id: Int, s: String) { findViewById<TextView>(id).text = s }
    private fun show(id: Int, on: Boolean) { findViewById<View>(id).visibility = if (on) View.VISIBLE else View.GONE }
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
