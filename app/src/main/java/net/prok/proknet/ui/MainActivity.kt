package net.prok.proknet.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.InputType
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import net.prok.proknet.ProkNetApp
import net.prok.proknet.R
import net.prok.proknet.ble.Peer
import net.prok.proknet.ble.ProkNetNode
import net.prok.proknet.core.Coverage
import net.prok.proknet.core.CoverageModel
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.GetInternet
import net.prok.proknet.core.Identity
import net.prok.proknet.core.InternetRequest
import net.prok.proknet.core.Market
import net.prok.proknet.core.PaymentRails
import net.prok.proknet.core.ProductState
import net.prok.proknet.core.Settlement
import net.prok.proknet.core.Wallet
import net.prok.proknet.core.ProductState.active
import net.prok.proknet.core.ProductState.busy
import net.prok.proknet.core.Pricing
import net.prok.proknet.core.ProviderInbox
import net.prok.proknet.core.StoredSession
import net.prok.proknet.core.Tunnel
import net.prok.proknet.core.Crypto
import net.prok.proknet.core.NetRequest
import net.prok.proknet.core.toHex
import net.prok.proknet.node.CoverageEngine
import net.prok.proknet.node.NetworkNode
import net.prok.proknet.service.ProkNetService
import net.prok.proknet.vpn.ProkVpnService

/**
 * v0.12 consumer screen. One big button: OBTENIR INTERNET. The decision is
 * [GetInternet] (pure), the request is [InternetRequest] (pure), the
 * transport underneath is the proven stack chosen by the proven rules; this
 * Activity only executes and shows words from [ProductState].
 */
class MainActivity : Activity(), ProkNetNode.Listener {
    private val tag = "UI"
    private lateinit var node: ProkNetNode
    private lateinit var cover: CoverageEngine
    private lateinit var network: NetworkNode
    private val main = Handler(Looper.getMainLooper())
    private val dateFmt = SimpleDateFormat("dd/MM HH:mm", Locale.FRANCE)
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.FRANCE)

    private enum class Tab { HOME, INTERNET, MAP, EARN, ACTIVITY }
    private var tab = Tab.HOME
    /** Offer the user tapped by hand, waiting for CONNECTER. */
    private var pendingOffer: Market.Offer? = null
    /** After the user closed a lost connection, the old error is no longer shown. */
    private var lostDismissed = true
    private var pendingAction: (() -> Unit)? = null
    /** v0.12: the one-tap request being served. */
    private var request: InternetRequest.Request? = null
    private var requestStartedAt = 0L
    private var locationAsked = false
    private val money by lazy { getSharedPreferences("proknet_money", Context.MODE_PRIVATE) }

    // ---- v0.14: the buyer's budget and the seller's earning policy live here ----------------------
    private var budgetCentimes: Long
        get() = money.getLong("budget", Pricing.DEFAULT_BUDGET_CENTIMES)
        set(v) { money.edit().putLong("budget", v).apply(); node.buyBudgetCentimes = v }
    private var budgetConfirmed: Boolean
        get() = money.getBoolean("budget_ok", false)
        set(v) { money.edit().putBoolean("budget_ok", v).apply() }
    private var sellerPolicy: Pricing.SellerPolicy
        get() = try { Pricing.SellerPolicy.valueOf(money.getString("policy", "BALANCED")!!) } catch (e: Exception) { Pricing.SellerPolicy.BALANCED }
        set(v) { money.edit().putString("policy", v.name).apply(); node.sellerPolicy = v; node.refreshAutoPrice() }
    private var bundleCostCentimesPerMb: Int
        get() = money.getInt("bundle", -1)
        set(v) { money.edit().putInt("bundle", v).apply(); node.sourceCostCentimesPerMb = v; node.refreshAutoPrice() }

    companion object {
        const val SEARCH_WINDOW_MS = 15_000L
        const val LONG_SEARCH_MS = 2_500L
    }

    private val versionName: String by lazy { try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" } }
    private val ticker = object : Runnable { override fun run() { driveRequest(); refresh(); main.postDelayed(this, 1000) } }

    private fun <T : View> v(id: Int): T = findViewById(id)
    private fun text(id: Int, s: String) { v<TextView>(id).text = s }
    private fun show(id: Int, on: Boolean) { v<View>(id).visibility = if (on) View.VISIBLE else View.GONE }
    private fun dp(x: Int): Int = (x * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        node = ProkNetApp.node(this)
        cover = ProkNetApp.coverage(this)
        network = ProkNetApp.network(this)
        v<Switch>(R.id.switchNotify).setOnClickListener { network.notifyOptIn = v<Switch>(R.id.switchNotify).isChecked; refresh() }
        v<Switch>(R.id.switchShareCoverage).setOnClickListener { network.shareCoverage = v<Switch>(R.id.switchShareCoverage).isChecked; refresh() }

        v<View>(R.id.navHome).setOnClickListener { select(Tab.HOME) }
        v<View>(R.id.navInternet).setOnClickListener { select(Tab.INTERNET) }
        v<View>(R.id.navMap).setOnClickListener { select(Tab.MAP) }
        v<View>(R.id.navEarn).setOnClickListener { select(Tab.EARN) }
        v<View>(R.id.navActivity).setOnClickListener { select(Tab.ACTIVITY) }

        v<PulseButtonView>(R.id.btnGetInternet).label = getString(R.string.sphere_idle)
        v<PulseButtonView>(R.id.btnGetInternet).setOnClickListener { getInternet() }
        v<Button>(R.id.btnHomeStop).setOnClickListener { if (node.sellOn) stopSharing() else stopAll() }
        v<View>(R.id.rowShare).setOnClickListener { ensureRunning { select(Tab.EARN) } }
        v<View>(R.id.rowMap).setOnClickListener { select(Tab.MAP) }
        v<Button>(R.id.btnConnect).setOnClickListener { connect() }
        v<Button>(R.id.btnConfirmBack).setOnClickListener { pendingOffer = null; refresh() }
        v<Button>(R.id.btnStopInternet).setOnClickListener { stopAll() }
        v<Button>(R.id.btnStartSharing).setOnClickListener { ensureRunning { startSharing() } }
        v<Button>(R.id.btnStopSharing).setOnClickListener { stopSharing() }
        v<Button>(R.id.btnInboxShare).setOnClickListener { shareForDemand() }
        v<TextView>(R.id.budget25).setOnClickListener { budgetCentimes = 2_500; refresh() }
        v<TextView>(R.id.budget50).setOnClickListener { budgetCentimes = 5_000; refresh() }
        v<TextView>(R.id.budget100).setOnClickListener { budgetCentimes = 10_000; refresh() }
        v<TextView>(R.id.policyCheaper).setOnClickListener { sellerPolicy = Pricing.SellerPolicy.CHEAPER; refresh() }
        v<TextView>(R.id.policyBalanced).setOnClickListener { sellerPolicy = Pricing.SellerPolicy.BALANCED; refresh() }
        v<TextView>(R.id.policyEarnMore).setOnClickListener { sellerPolicy = Pricing.SellerPolicy.EARN_MORE; refresh() }
        v<Button>(R.id.btnBundleSave).setOnClickListener { saveBundle() }
        // the node starts from what this person already chose
        node.buyBudgetCentimes = budgetCentimes
        node.sellerPolicy = sellerPolicy
        node.sourceCostCentimesPerMb = bundleCostCentimesPerMb
        v<TextView>(R.id.btnShareOptions).setOnClickListener { val o = v<View>(R.id.shareOptions); o.visibility = if (o.visibility == View.VISIBLE) View.GONE else View.VISIBLE }
        v<Button>(R.id.btnMapLocation).setOnClickListener { askLocation() }
        v<CoverageMapView>(R.id.mapView).onCellTap = { zone, cell -> cellDialog(zone, cell) }
        v<CoverageMapView>(R.id.mapView).onMarkTap = { m -> cover.state.sources[m.id]?.let { sourceDialog(it, m.status, System.currentTimeMillis()) } }
        v<Switch>(R.id.switchRelay).setOnClickListener { node.setRelay(v<Switch>(R.id.switchRelay).isChecked); refresh() }
        v<Switch>(R.id.switchNode).setOnClickListener {
            val want = v<Switch>(R.id.switchNode).isChecked
            if (want) ensureRunning { } else { DiagLog.i(tag, "Keep Prok running switched OFF"); ProkNetService.stop(this) }
            main.postDelayed({ refresh() }, 1500)
        }
        v<Button>(R.id.btnRename).setOnClickListener { renameDialog() }
        v<Button>(R.id.btnBattery).setOnClickListener { batterySettings() }
        v<Button>(R.id.btnDeveloper).setOnClickListener { startActivity(Intent(this, LabActivity::class.java)) }
        text(R.id.profileVersion, "ProkNet " + versionName)
        text(R.id.shareFee, getString(R.string.share_fee, node.feePct))
        text(R.id.confirmFee, getString(R.string.confirm_fee, node.feePct))
        select(Tab.HOME)
    }

    override fun onStart() {
        super.onStart()
        ProkNetApp.visibleActivities++
        node.addListener(this)
        node.vpnRequested = { startVpnWithConsent() }
        network.inboxChanged = { main.post { refresh() } }
        if (intent?.getStringExtra("tab") == "earn") select(Tab.EARN)
        DiagLog.i(tag, "consumer screen visible; service " + (if (ProkNetService.running) "RUNNING" else "stopped"))
        if (!ProkNetService.running && missingPermissions().isEmpty() && !notificationPermissionMissing() && node.isBluetoothOn()) ProkNetService.start(this)
        cover.onForeground()
        main.post(ticker)
    }

    override fun onStop() {
        ProkNetApp.visibleActivities = maxOf(0, ProkNetApp.visibleActivities - 1)
        main.removeCallbacks(ticker)
        node.removeListener(this)
        network.inboxChanged = null
        cover.onBackground()
        super.onStop()
    }

    // ---- tabs ------------------------------------------------------------------------------------

    private fun select(t: Tab) {
        tab = t
        show(R.id.tabHome, t == Tab.HOME); show(R.id.tabInternet, t == Tab.INTERNET); show(R.id.tabMap, t == Tab.MAP)
        show(R.id.tabEarn, t == Tab.EARN); show(R.id.tabActivity, t == Tab.ACTIVITY)
        val items = listOf(Tab.HOME to (R.id.navHomeIcon to R.id.navHomeText), Tab.INTERNET to (R.id.navInternetIcon to R.id.navInternetText), Tab.MAP to (R.id.navMapIcon to R.id.navMapText),
            Tab.EARN to (R.id.navEarnIcon to R.id.navEarnText), Tab.ACTIVITY to (R.id.navActivityIcon to R.id.navActivityText))
        val on = getColor(R.color.nav_active); val off = getColor(R.color.nav_inactive)
        for ((tt, ids) in items) { v<ImageView>(ids.first).setColorFilter(if (tt == t) on else off); v<TextView>(ids.second).setTextColor(if (tt == t) on else off) }
        if (t == Tab.MAP && !cover.hasLocationPermission() && !locationAsked) askLocation()
        refresh()
    }

    // ---- GET INTERNET: one tap, then the decision and the proven stack ------------------------------

    private fun getInternet() {
        if (node.sellOn) { toast(getString(R.string.toast_stop_sharing_first)); return }
        if (request?.active == true) return
        // v0.14: one tap, but never a surprise bill. The first paid session is confirmed once.
        if (!budgetConfirmed) { confirmBudgetThenGo(); return }
        ensureRunning {
            if (buyerOn()) { refresh(); return@ensureRunning }
            // v0.13.1: a new request starts from a clean screen. The error of a session the user
            // stopped earlier is not this request's error, and must never show as "Connexion perdue".
            node.clearLastFailure()
            lostDismissed = true
            val now = System.currentTimeMillis()
            val r = InternetRequest.oneTap(Crypto.randomBytes(8).toHex(), now, cover.zone())
            request = r; requestStartedAt = now
            cover.recordRequest(r)
            DiagLog.i(tag, "GET INTERNET pressed: request " + r.id + " in zone " + r.zone)
            driveRequest(); refresh()
        }
    }

    /** One tick of the request: decide, execute, or follow the session. Everything else is the proven stack. */
    private fun driveRequest() {
        val r = request ?: return
        if (r.terminal) return
        val now = System.currentTimeMillis()
        when (r.state) {
            InternetRequest.State.SEARCHING, InternetRequest.State.NETWORK_NEEDED -> {
                if (!node.isRunning) return
                val d = cover.decide(r.ceiling)
                val c = d.chosen
                if (d.action == GetInternet.Action.CONNECT_NOW && c != null) {
                    if (c.way == GetInternet.Way.CONNECTED_WIFI) {
                        DiagLog.i(tag, "GET INTERNET: " + d.reason + " (" + c.name + ")")
                        update(InternetRequest.online(InternetRequest.apply(r, d, now), now).copy(note = getString(R.string.already_online, c.name)))
                        return
                    }
                    val peer = node.peers().firstOrNull { it.shortId == c.peerShort }
                    if (peer == null || !node.hasKey(peer.shortId)) return          // its key is on the way; next tick
                    DiagLog.i(tag, "GET INTERNET decision: " + c.name + " via " + c.way + " at " + CoverageModel.priceWord(c.priceCentimesPerMb) + ": " + d.reason)
                    val found = InternetRequest.apply(r, d, now)
                    if (node.buy(peer)) update(InternetRequest.connecting(found, now))
                    else update(InternetRequest.failed(found, node.lastBuyError.ifEmpty { "cannot start the purchase" }, now))
                } else if (r.state == InternetRequest.State.SEARCHING && now - requestStartedAt > SEARCH_WINDOW_MS) {
                    DiagLog.i(tag, "GET INTERNET: nothing usable after " + (SEARCH_WINDOW_MS / 1000) + " s: " + d.reason + "; asking the network")
                    update(InternetRequest.apply(r, d, now))
                    // v0.13: the request leaves this phone: signed, carried by the phones around, uploaded by one with Internet
                    if (network.mine(r.id) == null) network.originate(r.id, r.zone)
                }
            }
            InternetRequest.State.DIRECT_SOURCE_FOUND, InternetRequest.State.CONNECTING -> {
                val b = buyerState()
                if (b == ProductState.Buyer.ONLINE) { update(InternetRequest.online(r, now)); r.sourceId?.let { cover.onSuccess(it) }; network.end(r.id, NetRequest.State.FULFILLED) }
                else if (b == ProductState.Buyer.LOST || (b == ProductState.Buyer.IDLE && !buyerOn())) {
                    lostDismissed = false
                    update(InternetRequest.failed(r, ProductState.lostHint(buyError()), now)); network.end(r.id, NetRequest.State.CANCELLED)
                }
            }
            else -> {}
        }
    }

    private fun update(r: InternetRequest.Request) { request = r; cover.recordRequest(r) }

    private fun stopAll() {
        val now = System.currentTimeMillis()
        request?.let { if (!it.terminal) { update(InternetRequest.cancelled(it, now)); network.end(it.id, NetRequest.State.CANCELLED) } }
        request = null
        DiagLog.i(tag, "Stop pressed")
        node.stopInternet("stopped by user"); lostDismissed = true; refresh()
    }

    private fun stopSharing() { node.setSelling(false); DiagLog.i(tag, "Stop sharing pressed"); toast(getString(R.string.toast_sharing_stopped)); refresh() }

    /**
     * v0.13.3: PARTAGER on the Gagner demand card. The same function the
     * notification calls, so a dismissed or forbidden notification never loses
     * the request: everything is re-checked here.
     */
    private fun shareForDemand() {
        ensureRunning {
            val err = network.acceptOpportunity(null)
            toast(err ?: getString(R.string.toast_sharing_started))
            refresh()
        }
    }

    // ---- refresh (everything derives from the node through ProductState) -------------------------

    private fun buyerState(): ProductState.Buyer = ProductState.buyer(node.buyerWanted != null, node.buyPhase(), node.buyerLinkUp(),
        node.tunnel.state, ProkVpnService.running, buyError(), checking = node.linkChecking())
    /**
     * v0.13.1: never the reason of a session the user stopped on purpose, and never
     * an error at all once a fresh request has taken the screen.
     */
    private fun buyError(): String {
        if (lostDismissed) return ""
        val e = node.tunnel.lastError.ifEmpty { node.lastBuyError }
        return if (ProductState.isUserStop(e)) "" else e
    }
    private fun locationOn(): Boolean = try {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (e: Exception) { true }

    /** The hotspot needs Wi-Fi and Location and may be refused on a network; the Bluetooth path needs none of that. */
    private fun sellerWarning(): String {
        val path = node.sellerAccessPath()
        return when {
            node.p2pFallbackActive -> getString(R.string.share_wifi_direct)
            !ProductState.sellerNeedsHotspotWarnings(path) ->
                if (path == net.prok.proknet.core.BulkPlan.SellerAccessPath.NONE && node.gateway.upstreamReady) getString(R.string.share_needs_bluetooth) else ""
            node.canShareWhileOnWifi() == false -> getString(R.string.share_wifi_impossible)
            node.shareCheck == net.prok.proknet.core.ShareCheck.Result.UNKNOWN && node.sellOn &&
                net.prok.proknet.core.ShareCheck.needed(Tunnel.upstreamType(node.gateway.upstream)) -> getString(R.string.share_wifi_checking)
            !node.wifi.wifiEnabled -> getString(R.string.seller_needs_wifi)
            !locationOn() -> getString(R.string.seller_needs_location)
            else -> ""
        }
    }
    private fun sellerSource(): String = ProductState.sellerSourceLine(node.sellerAccessPath(), Tunnel.upstreamType(node.gateway.upstream), node.wifi.currentWifi()?.ssid)
    private fun sellerState(): ProductState.Seller = ProductState.seller(node.sellOn, node.gateway.state)
    private fun buyerOn(): Boolean = node.buyerWanted != null || node.tunnel.session != null || node.tunnel.contract != null

    private fun refresh() {
        if (isFinishing) return
        val b = buyerState(); val s = sellerState()
        val running = ProkNetService.running && node.isRunning
        when (tab) {
            Tab.HOME -> refreshHome(b, s, running)
            Tab.INTERNET -> refreshInternet(b)
            Tab.MAP -> refreshMap()
            Tab.EARN -> refreshEarn(s)
            Tab.ACTIVITY -> refreshActivity(running)
        }
    }

    private fun refreshHome(b: ProductState.Buyer, s: ProductState.Seller, running: Boolean) {
        text(R.id.chipNode, getString(if (running) R.string.node_on else R.string.node_off))
        v<TextView>(R.id.chipNode).setCompoundDrawablesRelativeWithIntrinsicBounds(if (running) R.drawable.dot_ok else R.drawable.dot_muted, 0, 0, 0)
        val now = System.currentTimeMillis()
        val r = request
        val sellerOn = s != ProductState.Seller.OFF
        // v0.13.1: a live request outranks a lost card from a purchase that is over
        val owner = ProductState.homeOwner(sellerOn, buyerOn(), r != null && r.state != InternetRequest.State.CANCELLED,
            b == ProductState.Buyer.LOST && !lostDismissed)
        val buyerVisible = owner == ProductState.HomeOwner.PURCHASE
        val requestVisible = owner == ProductState.HomeOwner.REQUEST
        val status = owner != ProductState.HomeOwner.IDLE
        show(R.id.homeAsk, !sellerOn); show(R.id.homeStatus, status)
        val sphere = v<PulseButtonView>(R.id.btnGetInternet)
        sphere.mode = when {
            buyerVisible && b == ProductState.Buyer.ONLINE -> PulseButtonView.Mode.ONLINE
            buyerVisible && b != ProductState.Buyer.LOST -> PulseButtonView.Mode.CONNECTING
            r != null && r.active -> PulseButtonView.Mode.SEARCHING
            else -> PulseButtonView.Mode.IDLE
        }
        sphere.label = getString(when {
            buyerVisible && b == ProductState.Buyer.ONLINE -> R.string.sphere_online
            buyerVisible && b == ProductState.Buyer.LOST -> R.string.sphere_retry
            buyerVisible -> R.string.sphere_connecting
            r != null && r.state == InternetRequest.State.NETWORK_NEEDED -> R.string.sphere_request
            r != null && r.active -> R.string.sphere_searching
            r != null && r.state == InternetRequest.State.FAILED -> R.string.sphere_retry
            else -> R.string.sphere_idle
        })
        val stop = v<Button>(R.id.btnHomeStop)
        when {
            sellerOn -> {
                text(R.id.homeTitle, ProductState.sellerHeadline(s)); text(R.id.homeSub, sellerWarning().ifEmpty { ProductState.sellerHint(s) })
                show(R.id.homeProgress, false)
                val g = node.gateway
                text(R.id.homeMoney, Pricing.earnedWord(g.totalEarnedCentimes + Market.split(g.agreedCost(), node.feePct).sellerNet))
                show(R.id.homeMoney, true)
                stop.text = getString(R.string.stop_sharing)
            }
            buyerVisible -> {
                text(R.id.homeTitle, if (b == ProductState.Buyer.ONLINE) getString(R.string.connected) else ProductState.buyerTitle(b))
                text(R.id.homeSub, ProductState.buyerHint(b, node.buyPhase(), node.tunnel.state == "TUNNEL UP" && !ProkVpnService.running, buyError()))
                show(R.id.homeProgress, b.busy)
                // v0.14: the buyer reads money, never megabytes
                val c = node.tunnel.contract
                val spent = node.tunnel.runningCost()
                val money = when {
                    c == null -> ""
                    c.budgetSession && c.rateCentimesPerMb == 0 -> getString(R.string.free_session)
                    c.budgetSession -> Pricing.spentWord(spent, c.buyerBudgetCentimes) +
                        (if (Pricing.nearlyExhausted(spent, c.buyerBudgetCentimes)) "\n" + Pricing.remainingWord(spent, c.buyerBudgetCentimes) else "")
                    else -> getString(R.string.home_used, ProductState.data(sessionBytes())) + "\n" + getString(R.string.home_cost, ProductState.cfaShort(spent))
                }
                text(R.id.homeMoney, money); show(R.id.homeMoney, money.isNotEmpty())
                stop.text = getString(if (b == ProductState.Buyer.LOST) R.string.close_big else R.string.stop_big)
            }
            requestVisible -> {
                val rr = r!!
                text(R.id.homeTitle, InternetRequest.title(rr.state, now - requestStartedAt > LONG_SEARCH_MS))
                text(R.id.homeSub, when (rr.state) {
                    InternetRequest.State.FAILED, InternetRequest.State.ONLINE -> rr.note
                    InternetRequest.State.NETWORK_NEEDED -> NetRequest.hint(NetRequest.State.NETWORK_REQUESTED, now - requestStartedAt, false)
                    InternetRequest.State.DIRECT_SOURCE_FOUND -> NetRequest.hint(NetRequest.State.NETWORK_REQUESTED, 0, true)
                    else -> InternetRequest.hint(rr.state)
                })
                show(R.id.homeProgress, rr.active); show(R.id.homeMoney, false)
                stop.text = getString(if (rr.active) R.string.cancel_big else R.string.close_big)
            }
        }
        // three quiet numbers, and the line under the sonar
        val cands = if (running) cover.candidates() else emptyList()
        val usable = cands.filter { GetInternet.blocker(it, now, null) == null }
        val sourcesWord = if (usable.isEmpty()) getString(R.string.sources_none) else if (usable.size == 1) getString(R.string.sources_one) else getString(R.string.sources_many, usable.size)
        text(R.id.tileAround, usable.size.toString())
        v<PulseButtonView>(R.id.btnGetInternet).sources = usable.size
        text(R.id.tilePrice, if (usable.isEmpty()) "—" else CoverageModel.priceBandWord(usable.minOf { it.priceCentimesPerMb }))
        val lastOnline = cover.state.requests.filter { it.state == InternetRequest.State.ONLINE }.maxOfOrNull { it.updatedAt }
        text(R.id.tileLast, if (lastOnline == null) getString(R.string.never) else CoverageModel.ageWord(now - lastOnline).removePrefix("il y a "))
        v<TextView>(R.id.rowShareSub).apply { text = getString(if (sellerOn) R.string.row_share_caption_on else R.string.row_share_caption); setTextColor(getColor(if (sellerOn) R.color.ok else R.color.text_muted)) }
        // v0.13.3: a quiet badge so waiting demand is visible without opening Gagner
        val waiting = ProviderInbox.active(network.inbox, now).count { !it.accepted }
        v<TextView>(R.id.rowShareBadge).text = if (waiting == 1) getString(R.string.inbox_badge_one) else getString(R.string.inbox_badge_many, waiting)
        show(R.id.rowShareBadge, waiting > 0)
        text(R.id.homeNote, if (running && !node.isBluetoothOn()) getString(R.string.home_bluetooth_off) else "")
    }

    private fun refreshInternet(b: ProductState.Buyer) {
        refreshBudget()
        show(R.id.budgetCard, !buyerOn() && pendingOffer == null)
        val buyerVisible = ProductState.homeOwner(false, buyerOn(), request?.let { it.state != InternetRequest.State.CANCELLED } == true,
            b == ProductState.Buyer.LOST && !lostDismissed) == ProductState.HomeOwner.PURCHASE
        val confirm = !buyerVisible && pendingOffer != null
        show(R.id.netActive, buyerVisible); show(R.id.netConfirm, confirm); show(R.id.netOffers, !buyerVisible && !confirm)
        when {
            buyerVisible -> {
                text(R.id.activeTitle, if (b == ProductState.Buyer.ONLINE) getString(R.string.connected) else ProductState.buyerTitle(b))
                text(R.id.activeHint, ProductState.buyerHint(b, node.buyPhase(), node.tunnel.state == "TUNNEL UP" && !ProkVpnService.running, buyError()))
                show(R.id.activeProgress, b.busy); show(R.id.activeStats, node.tunnel.session != null)
                text(R.id.activeData, ProductState.data(sessionBytes())); text(R.id.activeCost, ProductState.cfaShort(node.tunnel.runningCost()))
                val c = node.tunnel.contract; val sess = node.tunnel.session
                text(R.id.activeDetail, (c?.let { (if (it.budgetSession) Pricing.budgetLine(it.buyerBudgetCentimes) else ProductState.priceLine(it.pricePerMb)) + " · " } ?: "") +
                    (sess?.let { ProductState.duration(it.durationMs) + " · " } ?: "") + (node.tunnel.providerShort?.let { getString(R.string.via_peer, node.peerName(it)) } ?: ""))
                v<Button>(R.id.btnStopInternet).text = getString(if (b == ProductState.Buyer.LOST) R.string.close else R.string.disconnect)
            }
            confirm -> {
                val o = pendingOffer!!
                text(R.id.confirmTitle, offerTitle(o) + " · " + node.peerName(o.sellerShort))
                text(R.id.confirmPrice, ProductState.offerPriceWord(o.pricePerMb, budgetCentimes))
                text(R.id.confirmMin, ProductState.minimumLine(0)); text(R.id.confirmLimit, getString(R.string.confirm_limit_provider))
                text(R.id.confirmSignal, ProductState.signalWord(o.rssi) + " · " + ProductState.upstreamWord(o.upstreamType) + (if (o.validated) "" else getString(R.string.confirm_not_checked)))
            }
            else -> refreshOffers()
        }
    }

    private fun offerTitle(o: Market.Offer): String = when {
        o.pricePerMb == 0 -> getString(R.string.offer_free)
        o.upstreamType == Tunnel.UP_WIFI -> getString(R.string.offer_wifi)
        o.upstreamType == Tunnel.UP_CELLULAR -> getString(R.string.offer_mobile)
        else -> getString(R.string.offer_available)
    }

    private fun refreshOffers() {
        val list = v<LinearLayout>(R.id.netOfferList); list.removeAllViews()
        val offers = node.offers()
        show(R.id.netNoOffers, offers.isEmpty())
        text(R.id.netOffersHint, getString(if (!node.isRunning) R.string.offers_node_off else if (offers.isEmpty()) R.string.offers_searching else R.string.offers_tap))
        for (o in offers) {
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = getDrawable(R.drawable.bg_card); setPadding(dp(22), dp(20), dp(22), dp(20)) }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = dp(12); card.layoutParams = lp
            card.addView(TextView(this).apply { text = offerTitle(o) + (if (o.viaRelay) getString(R.string.offer_via_relay) else ""); setTextAppearance(R.style.H2) })
            card.addView(TextView(this).apply { text = ProductState.offerPriceWord(o.pricePerMb, budgetCentimes); setTextAppearance(R.style.Big) })
            card.addView(TextView(this).apply { text = ProductState.signalWord(o.rssi) + (if (o.validated) getString(R.string.offer_checked) else ""); setTextAppearance(R.style.Muted) })
            val btn = Button(this).apply { text = getString(R.string.connect_big); setBackgroundResource(R.drawable.bg_primary); setTextColor(getColor(R.color.on_brand)); isAllCaps = false; stateListAnimator = null; textSize = 16f; typeface = android.graphics.Typeface.DEFAULT_BOLD }
            val blp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54)); blp.topMargin = dp(16); btn.layoutParams = blp
            btn.setOnClickListener { pendingOffer = o; refresh() }
            card.addView(btn)
            list.addView(card)
        }
    }

    // ---- map -------------------------------------------------------------------------------------------

    private fun refreshMap() {
        val now = System.currentTimeMillis()
        val cells = cover.cells(); val zone = cover.zone(); val reach = cover.reachableIds()
        val sources = cover.state.sources.values.sortedByDescending { it.lastSeen }
        val statusOf = { s: CoverageModel.Source -> CoverageModel.cellStatus(listOf(s), now, reach) }
        val nameOf = { s: CoverageModel.Source ->
            if (s.kind == CoverageModel.SourceKind.WIFI && (s.name == "Wi-Fi" || s.name.isEmpty())) getString(R.string.map_wifi_connected)
            else if (s.kind == CoverageModel.SourceKind.WIFI) "Wi-Fi " + s.name else s.name }
        val marks = sources.map { CoverageMapView.Mark(it.id, nameOf(it), statusOf(it), now - it.lastSeen) }
        v<CoverageMapView>(R.id.mapView).set(cells, zone, marks)
        val located = CoverageModel.zoneIndex(zone) != null
        text(R.id.mapHint, getString(if (located) R.string.map_grid_hint else R.string.map_radar_hint))
        val nowCount = marks.count { it.status == Coverage.ZoneStatus.GREEN }
        val recentCount = marks.count { it.status == Coverage.ZoneStatus.YELLOW }
        text(R.id.mapNow, nowCount.toString()); text(R.id.mapRecent, recentCount.toString())
        val here = CoverageModel.hereStatus(cells, zone)
        text(R.id.mapHereTitle, CoverageModel.cellWord(here))
        text(R.id.mapHereSub, when {
            !cover.hasLocationPermission() -> getString(R.string.map_place_sub)
            !located -> getString(R.string.map_no_fix)
            else -> getString(R.string.map_around_sources, sources.size)
        })
        show(R.id.btnMapLocation, !cover.hasLocationPermission())
        val list = v<LinearLayout>(R.id.mapList); list.removeAllViews()
        show(R.id.mapEmpty, sources.isEmpty())
        for (s in sources.take(12)) {
            val st = statusOf(s)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; background = getDrawable(R.drawable.bg_card); setPadding(dp(18), dp(15), dp(16), dp(15)) }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = dp(8); row.layoutParams = lp
            val dot = View(this).apply { background = getDrawable(when (st) { Coverage.ZoneStatus.GREEN -> R.drawable.dot_ok; Coverage.ZoneStatus.YELLOW -> R.drawable.dot_warn; else -> R.drawable.dot_muted }) }
            dot.layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(14) }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            texts.addView(TextView(this).apply { text = nameOf(s); setTextAppearance(R.style.H2) })
            texts.addView(TextView(this).apply { text = CoverageModel.cellWord(st) + " · " + CoverageModel.ageWord(now - s.lastSeen) + " · " + CoverageModel.priceBandWord(s.priceCentimesPerMb); setTextAppearance(R.style.Muted) })
            val chevron = TextView(this).apply { text = getString(R.string.chevron); textSize = 24f; setTextColor(getColor(R.color.text_muted)) }
            row.addView(dot); row.addView(texts); row.addView(chevron)
            row.setOnClickListener { sourceDialog(s, st, now) }
            list.addView(row)
        }
        // v0.13: what the network has seen, kept apart, never "available now"
        val localZones = cover.state.sources.values.flatMap { it.zones }.toSet()
        for (c in cover.shared.filter { it.status != Coverage.ZoneStatus.RED }.sortedByDescending { it.lastSeen }.take(8)) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.CENTER_VERTICAL; background = getDrawable(R.drawable.bg_card_alt); setPadding(dp(18), dp(15), dp(16), dp(15)) }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = dp(8); row.layoutParams = lp
            val dot = View(this).apply { background = getDrawable(R.drawable.dot_warn) }
            dot.layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { marginEnd = dp(14) }
            val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            texts.addView(TextView(this).apply { text = getString(R.string.map_shared) + (if (c.zone in localZones || c.zone == zone) " · " + getString(R.string.map_here) else ""); setTextAppearance(R.style.H2) })
            texts.addView(TextView(this).apply { text = CoverageModel.cellWord(c.status) + " · " + getString(R.string.map_shared_sources, c.potential) + " · " + CoverageModel.priceBandWord(c.bestPrice) + " · " + CoverageModel.ageWord(now - c.lastSeen); setTextAppearance(R.style.Muted) })
            row.addView(dot); row.addView(texts)
            list.addView(row)
        }
        show(R.id.mapEmpty, sources.isEmpty() && cover.shared.isEmpty())
    }

    private fun sourceDialog(s: CoverageModel.Source, st: Coverage.ZoneStatus, now: Long) {
        val lines = listOf(
            CoverageModel.cellWord(st) + (if (st == Coverage.ZoneStatus.YELLOW) " (vu récemment)" else ""),
            getString(R.string.map_last_seen, CoverageModel.ageWord(now - s.lastSeen)),
            getString(R.string.map_type, CoverageModel.kindWord(s.kind)),
            getString(R.string.map_best_price, CoverageModel.priceBandWord(s.priceCentimesPerMb)),
            getString(R.string.map_seen_times, s.observations),
        )
        AlertDialog.Builder(this).setTitle(s.name).setMessage(lines.joinToString("\n")).setPositiveButton(R.string.close, null).show()
    }

    private fun cellDialog(zone: String, cell: CoverageModel.Cell?) {
        val now = System.currentTimeMillis()
        val msg = if (cell == null) getString(R.string.map_nothing) else listOf(
            getString(R.string.map_last_seen, CoverageModel.ageWord(now - cell.lastObservedAt)),
            getString(R.string.map_direct, cell.directSourceCount),
            getString(R.string.map_potential, cell.potentialSourceCount),
            getString(R.string.map_best_price, CoverageModel.priceBandWord(cell.bestKnownPrice)),
        ).joinToString("\n")
        AlertDialog.Builder(this).setTitle(CoverageModel.cellWord(cell?.status ?: Coverage.ZoneStatus.RED)).setMessage(msg).setPositiveButton(R.string.close, null).show()
    }

    private fun askLocation() {
        locationAsked = true
        AlertDialog.Builder(this).setTitle(R.string.map_location_title).setMessage(R.string.map_location_explain)
            .setPositiveButton(R.string.continue_btn) { _, _ -> requestPermissions(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION), 7) }
            .setNegativeButton(R.string.close, null).show()
    }

    // ---- earn ------------------------------------------------------------------------------------------

    private fun startOfToday(): Long = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis

    private fun refreshEarn(s: ProductState.Seller) {
        val now = System.currentTimeMillis()
        // v0.14: the seller picks what to earn; ProkNet computes the price
        val pol = sellerPolicy
        paintChoice(R.id.policyCheaper, pol == Pricing.SellerPolicy.CHEAPER)
        paintChoice(R.id.policyBalanced, pol == Pricing.SellerPolicy.BALANCED)
        paintChoice(R.id.policyEarnMore, pol == Pricing.SellerPolicy.EARN_MORE)
        text(R.id.shareEstimate, Pricing.earningEstimate(Pricing.quote(budgetCentimes, node.mySource(), pol)) ?: "")
        val demand = ProviderInbox.active(network.inbox, now)
        // the demand card is visible whatever the notification did, and only while sharing is off
        val waiting = demand.filter { !it.accepted }
        show(R.id.earnInbox, waiting.isNotEmpty() && s == ProductState.Seller.OFF)
        if (waiting.isNotEmpty()) {
            text(R.id.earnInboxTitle, ProviderInbox.cardTitle(network.inbox, now))
            text(R.id.earnInboxSub, ProviderInbox.cardSub(network.inbox, now))
        }
        val other = ProviderInbox.otherDemandLine(network.inbox, now)
        text(R.id.shareOtherDemand, other); show(R.id.shareOtherDemand, other.isNotEmpty() && s != ProductState.Seller.OFF)
        val on = s != ProductState.Seller.OFF
        show(R.id.netShareSetup, !on); show(R.id.netShareActive, on)
        if (!on) {
            text(R.id.shareUpstream, if (node.gateway.upstreamReady) sellerSource() else getString(R.string.share_no_internet_yet_big))
            text(R.id.shareWarning, sellerWarning())
        } else {
            text(R.id.shareTitle, ProductState.sellerHeadline(s)); text(R.id.shareHint, sellerWarning().ifEmpty { ProductState.sellerHint(s) })
            text(R.id.shareTerms, (if (node.gateway.upstreamReady) sellerSource() + "\n" else "") + getString(R.string.share_auto_price))
            val g = node.gateway; val cur = g.session
            val today = startOfToday()
            val todaySessions = node.store.sessions(300).filter { it.role == "seller" && it.startTs >= today }
            val liveBytes = cur?.let { it.bytesUp + it.bytesDown } ?: 0L
            val liveEarned = Market.split(g.agreedCost(), node.feePct).sellerNet
            text(R.id.shareClients, if (cur == null) "0" else "1")
            text(R.id.shareData, ProductState.data(todaySessions.sumOf { signedBytes(it) } + liveBytes))
            text(R.id.shareEarned, ProductState.cfaShort(todaySessions.sumOf { Market.split(it.finalCentimes, node.feePct).sellerNet } + liveEarned))
            text(R.id.shareCustomer, if (cur == null) getString(R.string.share_no_customer) else getString(R.string.share_one_customer, node.peerName(cur.peerShort)) + "\n" + Pricing.earnedWord(liveEarned) + "\n" +
                getString(R.string.data_shared) + " : " + ProductState.data(liveBytes) + " · " + ProductState.duration(cur.durationMs) + " · " + getString(R.string.earned) + " : " + ProductState.cfaShort(liveEarned))
        }
        val ledger = node.store.ledger(1000)
        val gross = ledger.filter { it.recipient == node.me && it.status != Market.ST_CANCELLED }.sumOf { it.amountCentimes }
        val fees = ledger.filter { it.payer == node.me && it.recipient == Market.PROK_ID && it.status != Market.ST_CANCELLED }.sumOf { it.amountCentimes }
        val sold = node.store.sessions(1000).count { it.role == "seller" }
        text(R.id.earnTotal, ProductState.cfaShort(gross - fees))
        text(R.id.earnSub, (if (sold == 1) getString(R.string.earn_sessions_one) else getString(R.string.earn_sessions_many, sold)) + (if (fees > 0) getString(R.string.earn_fees, ProductState.cfaShort(fees)) else ""))
        val (n, bytes) = node.store.relayStats()
        text(R.id.earnRelayStats, if (n == 0L) getString(R.string.help_none) else if (n == 1L) getString(R.string.help_carried_one, ProductState.data(bytes)) else getString(R.string.help_carried_many, n.toInt(), ProductState.data(bytes)))
        v<Switch>(R.id.switchRelay).isChecked = node.relayOn
        v<Switch>(R.id.switchNotify).isChecked = network.notifyOptIn
        v<Switch>(R.id.switchShareCoverage).isChecked = network.shareCoverage
    }

    private fun startSharing() {
        // v0.14: no price to type. ProkNet computes a profitable one from the source and the policy.
        node.sellerPolicy = sellerPolicy
        node.sourceCostCentimesPerMb = bundleCostCentimesPerMb
        val price = Pricing.advertisedPriceCfa(node.autoRateCentimesPerMb())
        val err = node.setSelling(true, price, 0, 0)
        if (err != null) {
            DiagLog.w(tag, "start sharing refused: " + err)
            toast(when (err) { "stop buying first" -> getString(R.string.toast_stop_buying); "relay mode is on" -> getString(R.string.toast_relay_on); else -> getString(R.string.toast_check_terms) })
            return
        }
        DiagLog.i(tag, "Start sharing pressed: automatic price " + price + " CFA/MB (" + node.mySource().kind + ", " + sellerPolicy + ")")
        if (!node.gateway.upstreamReady) toast(getString(R.string.toast_sharing_no_internet))
        refresh()
    }

    // ---- activity + account ------------------------------------------------------------------------------

    private fun sessionBytes(): Long = node.tunnel.session?.let { it.bytesUp + it.bytesDown } ?: 0L
    private fun signedBytes(ss: StoredSession): Long = Market.Checkpoint.decode(ss.lastCheckpoint)?.billable ?: (ss.bytesUp + ss.bytesDown)
    private fun paymentWord(ss: StoredSession, e: Market.Entry?): String =
        if (ss.finalCentimes == 0L) getString(R.string.payment_free) else if (e == null) getString(R.string.payment_not_booked) else ProductState.paymentWord(e.status, e.payer == node.me)

    private fun refreshActivity(running: Boolean) {
        val ledger = node.store.ledger(1000)
        // v0.15: the three figures now come from real signed obligations, not from the
        // internal ledger view. They are amounts owed between people; Prok holds nothing.
        val obligations = node.obligations()
        val w = Wallet.view(obligations, node.identity.idHex, System.currentTimeMillis())
        text(R.id.walletPay, ProductState.cfaShort(w.toPayCentimes))
        text(R.id.walletReceive, ProductState.cfaShort(w.toReceiveCentimes))
        text(R.id.walletFees, ProductState.cfaShort(obligations.filter { Settlement.isOutstanding(it.status) && it.sellerId == node.identity.idHex }.sumOf { it.prokFeeCentimes }))
        renderPayCard(obligations)
        renderReceiveWith()
        val list = v<LinearLayout>(R.id.activityList); list.removeAllViews()
        val sessions = node.store.sessions(100)
        show(R.id.activityEmpty, sessions.isEmpty())
        val today = startOfToday()
        for (ss in sessions) {
            val entry = ledger.firstOrNull { it.sessionHex == ss.sessionHex && it.recipient != Market.PROK_ID }
            val buyer = ss.role == "buyer"
            val card = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = getDrawable(R.drawable.bg_card); setPadding(dp(20), dp(16), dp(20), dp(16)) }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = dp(10); card.layoutParams = lp
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            left.addView(TextView(this).apply { text = getString(if (buyer) R.string.activity_used else R.string.activity_shared); setTextAppearance(R.style.H2) })
            left.addView(TextView(this).apply { text = ProductState.data(signedBytes(ss)) + " · " + paymentWord(ss, entry); setTextAppearance(R.style.Muted) })
            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.END }
            val amount = if (buyer) ProductState.cfaShort(ss.finalCentimes) else "+" + ProductState.cfaShort(Market.split(ss.finalCentimes, node.feePct).sellerNet)
            right.addView(TextView(this).apply { text = amount; setTextAppearance(R.style.H2) })
            right.addView(TextView(this).apply { text = (if (ss.startTs >= today) timeFmt else dateFmt).format(Date(ss.startTs)); setTextAppearance(R.style.Muted) })
            card.addView(left); card.addView(right)
            card.setOnClickListener { sessionDialog(ss, entry) }
            list.addView(card)
        }
        text(R.id.profileName, node.identity.displayName)
        text(R.id.profileId, getString(R.string.profile_id, "prok-" + node.identity.shortIdHex))
        text(R.id.profileService, getString(if (running) R.string.network_running else R.string.network_stopped))
        v<Switch>(R.id.switchNode).isChecked = ProkNetService.running
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        v<Button>(R.id.btnBattery).text = getString(if (pm.isIgnoringBatteryOptimizations(packageName)) R.string.background_allowed else R.string.allow_background)
    }

    // ---- v0.15: paying, and being paid ----------------------------------------------------------

    /** The one creditor it makes most sense to settle with now. Small sessions add up first. */
    private fun biggestCreditor(obligations: List<Settlement.Obligation>): Pair<String, Long>? {
        val me = node.identity.idHex
        return obligations.filter { it.buyerId == me && Settlement.isOutstanding(it.status) }
            .groupBy { it.sellerId }
            .map { (seller, list) -> seller to list.sumOf { it.buyerOwes } }
            .maxByOrNull { it.second }
    }

    private fun renderPayCard(obligations: List<Settlement.Obligation>) {
        val due = biggestCreditor(obligations)
        show(R.id.walletPayCard, due != null)
        if (due == null) return
        val (seller, amount) = due
        text(R.id.walletPayWho, node.peerName(seller.substring(0, 8)) + " · " + Market.cfa(amount))
        v<Button>(R.id.btnWalletPay).text = getString(R.string.wallet_pay_button, Market.cfa(amount))
        v<Button>(R.id.btnWalletPay).setOnClickListener { payDialog(seller, amount) }
    }

    private fun payDialog(sellerId: String, amount: Long) {
        val rails = node.paymentRails.filter { it.available() }
        if (rails.isEmpty()) { toast(getString(R.string.wallet_pay_none)); return }
        val names = rails.map { it.displayName() }.toTypedArray()
        AlertDialog.Builder(this).setTitle(Market.cfa(amount)).setItems(names) { _, i ->
            val note = node.payTo(sellerId, rails[i])
            if (rails[i].rail == Settlement.Rail.MANUAL_PILOT) referenceDialog(sellerId, note)
            else { toast(note); refresh() }
        }.setNegativeButton(R.string.close, null).show()
    }

    /**
     * The buyer pays through the operator and types the reference. That reference is a
     * claim, never a confirmation: the obligation stays "en attente de vérification".
     */
    private fun referenceDialog(sellerId: String, instruction: String) {
        val input = EditText(this).apply { hint = getString(R.string.wallet_reference_hint) }
        AlertDialog.Builder(this).setTitle(R.string.wallet_reference_title).setMessage(instruction).setView(input)
            .setPositiveButton(R.string.wallet_receive_save) { _, _ ->
                val ref = input.text.toString().trim()
                val manual = PaymentRails.ManualPilotRail()
                if (!manual.looksLikeReference(ref)) { toast(getString(R.string.wallet_reference_hint)); return@setPositiveButton }
                val me = node.identity.idHex
                for (o in Wallet.payableTo(node.obligations(), me, sellerId))
                    node.store.saveSettlement(Settlement.applyPayment(o, manual.check(ref), Settlement.Rail.MANUAL_PILOT, ref, System.currentTimeMillis()))
                toast(getString(R.string.wallet_reference_pending)); refresh()
            }.setNegativeButton(R.string.close, null).show()
    }

    private fun renderReceiveWith() {
        val d = node.store.paymentDestination(node.identity.idHex)
        text(R.id.walletReceiveWith, if (d == null) "—" else railName(d.rail) + " · " + d.masked())
        v<Button>(R.id.btnWalletReceive).setOnClickListener { receiveWithDialog() }
    }

    private fun railName(r: Settlement.Rail): String = when (r) {
        Settlement.Rail.MTN_MOMO -> "MTN Mobile Money"
        Settlement.Rail.AIRTEL_MONEY -> "Airtel Money"
        else -> "Mobile Money"
    }

    private fun receiveWithDialog() {
        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_PHONE
            setText(node.store.paymentDestination(node.identity.idHex)?.msisdn ?: "")
        }
        val rails = arrayOf(Settlement.Rail.MTN_MOMO, Settlement.Rail.AIRTEL_MONEY)
        AlertDialog.Builder(this).setTitle(R.string.wallet_receive_with).setView(input)
            .setItems(rails.map { railName(it) }.toTypedArray()) { _, _ -> }
            .setPositiveButton("MTN") { _, _ -> saveDestination(Settlement.Rail.MTN_MOMO, input.text.toString()) }
            .setNeutralButton("Airtel") { _, _ -> saveDestination(Settlement.Rail.AIRTEL_MONEY, input.text.toString()) }
            .setNegativeButton(R.string.close, null).show()
    }

    private fun saveDestination(rail: Settlement.Rail, msisdn: String) {
        val d = PaymentRails.Destination(rail, msisdn.filter { it.isDigit() || it == '+' }, node.identity.displayName)
        if (!d.valid) { toast(getString(R.string.wallet_reference_hint)); return }
        node.store.savePaymentDestination(node.identity.idHex, d, System.currentTimeMillis())
        toast(getString(R.string.wallet_receive_saved)); refresh()
    }

    private fun sessionDialog(ss: StoredSession, e: Market.Entry?) {
        val c = Market.Contract.decode(ss.contract)
        val dur = if (ss.endTs > 0) ss.endTs - ss.startTs else 0L
        val lines = ArrayList<String>()
        lines += getString(if (ss.role == "buyer") R.string.activity_bought else R.string.activity_sold, node.peerName(ss.peerShort))
        lines += getString(R.string.session_date, dateFmt.format(Date(ss.startTs)))
        lines += getString(R.string.session_data, ProductState.data(signedBytes(ss)))
        lines += getString(R.string.session_duration, ProductState.duration(dur))
        if (c != null) lines += if (c.budgetSession) Pricing.budgetLine(c.buyerBudgetCentimes)
            else getString(R.string.session_price, ProductState.priceLine(c.pricePerMb) + (if (c.minPriceCfa > 0) " · " + ProductState.minimumLine(c.minPriceCfa) else ""))
        lines += getString(R.string.session_final, ProductState.cfaExact(ss.finalCentimes))
        if (ss.role == "seller" && c != null) lines += getString(R.string.session_fee, ProductState.cfaExact(Market.split(ss.finalCentimes, c.feePct).fee))
        lines += getString(R.string.session_payment, paymentWord(ss, e))
        if (ss.status != "ended" && ss.status != "settled") lines += getString(R.string.session_status, ss.status)
        val b = AlertDialog.Builder(this).setTitle(R.string.session).setMessage(lines.joinToString("\n")).setNegativeButton(R.string.close, null)
        if (e != null && e.status == Market.ST_PENDING) {
            if (e.payer == node.me && e.paidAt == 0L) b.setPositiveButton(R.string.mark_paid) { _, _ -> node.ledgerAction(e.id, "paid")?.let { toast(it) }; refresh() }
            if (e.recipient == node.me && e.receivedAt == 0L) b.setPositiveButton(R.string.mark_received) { _, _ -> node.ledgerAction(e.id, "received")?.let { toast(it) }; refresh() }
            b.setNeutralButton(R.string.dispute) { _, _ -> node.ledgerAction(e.id, "dispute")?.let { toast(it) }; refresh() }
        }
        b.show()
    }

    private fun renameDialog() {
        val input = EditText(this); input.inputType = InputType.TYPE_CLASS_TEXT; input.setText(node.identity.displayName)
        AlertDialog.Builder(this).setTitle(R.string.your_name).setMessage(R.string.your_name_hint).setView(input)
            .setPositiveButton(R.string.save) { _, _ -> val n = input.text.toString().trim(); if (n.isNotEmpty()) { Identity.saveName(applicationContext, node.identity, n); refresh() } }
            .setNegativeButton(R.string.cancel, null).show()
    }

    private fun batterySettings() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isIgnoringBatteryOptimizations(packageName)) { toast(getString(R.string.background_allowed)); return }
            startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, android.net.Uri.parse("package:" + packageName)))
        } catch (e: Exception) { DiagLog.e(tag, "battery settings", e); toast(getString(R.string.toast_not_available)) }
    }

    // ---- manual connect ------------------------------------------------------------------------------------

    private fun connect() {
        val o = pendingOffer ?: return
        val peer: Peer = node.peers().firstOrNull { it.shortId == o.sellerShort } ?: run { toast(getString(R.string.toast_provider_gone)); pendingOffer = null; refresh(); return }
        if (!peer.offer().selling) { toast(getString(R.string.toast_provider_stopped)); pendingOffer = null; refresh(); return }
        if (!node.hasKey(peer.shortId)) { toast(getString(R.string.toast_need_key)); return }
        if (ProductState.buyerNeedsWifi(o.bulkBt, o.upstreamType) && !node.wifi.wifiEnabled) { toast(getString(R.string.toast_need_wifi)); return }
        DiagLog.i(tag, "CONNECT pressed by hand: prok-" + peer.shortId + " " + o.pricePerMb + " CFA/MB")
        node.clearLastFailure()
        lostDismissed = false
        request = null
        if (!node.buy(peer)) { toast(getString(R.string.toast_cannot_connect)); return }
        pendingOffer = null; refresh()
    }

    // ---- permissions / service / VPN -------------------------------------------------------------------------

    private fun requiredPermissions(): Array<String> {
        val p = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) { p += Manifest.permission.BLUETOOTH_SCAN; p += Manifest.permission.BLUETOOTH_ADVERTISE; p += Manifest.permission.BLUETOOTH_CONNECT }
        else p += Manifest.permission.ACCESS_FINE_LOCATION
        if (Build.VERSION.SDK_INT >= 33) p += Manifest.permission.NEARBY_WIFI_DEVICES
        else if (Manifest.permission.ACCESS_FINE_LOCATION !in p) p += Manifest.permission.ACCESS_FINE_LOCATION
        if (Manifest.permission.ACCESS_FINE_LOCATION in p) p += Manifest.permission.ACCESS_COARSE_LOCATION
        return p.toTypedArray()
    }
    private fun missingPermissions() = requiredPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    private fun notificationPermissionMissing(): Boolean = Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED

    /**
     * v0.14: before the first ever paid connection, say what the ceiling is. Free
     * Internet never needs this; the question is only about money.
     */
    private fun confirmBudgetThenGo() {
        AlertDialog.Builder(this)
            .setTitle(R.string.budget_confirm_title)
            .setMessage(getString(R.string.budget_confirm, Pricing.cfa(budgetCentimes)))
            .setPositiveButton(R.string.continue_btn) { _, _ -> budgetConfirmed = true; getInternet() }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun saveBundle() {
        val paid = v<EditText>(R.id.bundlePaid).text.toString().trim().toLongOrNull()
        val mb = v<EditText>(R.id.bundleMb).text.toString().trim().toLongOrNull()
        if (paid == null || mb == null || paid <= 0 || mb <= 0) { toast(getString(R.string.toast_check_terms)); return }
        bundleCostCentimesPerMb = Pricing.mobileCostPerMb(paid * 100, mb)
        DiagLog.i(tag, "bundle declared: " + paid + " CFA for " + mb + " MB = " + bundleCostCentimesPerMb + " centimes/MB")
        toast(getString(R.string.share_bundle_saved))
        refresh()
    }

    private fun paintChoice(id: Int, on: Boolean) {
        v<TextView>(id).setBackgroundResource(if (on) R.drawable.bg_primary else R.drawable.bg_secondary)
        v<TextView>(id).setTextColor(getColor(if (on) R.color.on_brand else R.color.text))
    }

    private fun refreshBudget() {
        text(R.id.budgetValue, Pricing.cfa(budgetCentimes))
        paintChoice(R.id.budget25, budgetCentimes == 2_500L)
        paintChoice(R.id.budget50, budgetCentimes == 5_000L)
        paintChoice(R.id.budget100, budgetCentimes == 10_000L)
    }

    /** Runs [then] once the service is running, asking for what is missing on the way. */
    private fun ensureRunning(then: () -> Unit) {
        if (ProkNetService.running && node.isRunning) { then(); return }
        pendingAction = then
        val missing = missingPermissions()
        if (missing.isNotEmpty()) { DiagLog.i(tag, "requesting permissions: " + missing.joinToString()); requestPermissions(missing.toTypedArray(), 1); return }
        if (notificationPermissionMissing()) { requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3); return }
        if (!node.isBluetoothOn()) { try { startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), 2) } catch (e: Exception) { DiagLog.e(tag, "enable BT", e) }; return }
        DiagLog.i(tag, "starting foreground service from the consumer screen")
        ProkNetService.start(this)
        main.postDelayed({ refresh(); pendingAction?.let { pendingAction = null; it() } }, 800)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7) { cover.onForeground(); refresh(); return }
        val denied = permissions.filterIndexed { i, _ -> grantResults.getOrNull(i) != PackageManager.PERMISSION_GRANTED }
        if (requestCode == 3) { if (denied.isNotEmpty()) DiagLog.w(tag, "notification permission denied"); pendingAction?.let { ensureRunning(it) }; return }
        val bluetoothDenied = denied.filter { it.contains("BLUETOOTH") || (Build.VERSION.SDK_INT < 31 && it.contains("LOCATION")) }
        if (bluetoothDenied.isEmpty()) { if (denied.isNotEmpty()) DiagLog.w(tag, "Wi-Fi permission denied: " + denied.joinToString()); pendingAction?.let { ensureRunning(it) } }
        else { DiagLog.e(tag, "permissions DENIED: " + denied.joinToString()); toast(getString(R.string.toast_need_bluetooth_perm)); pendingAction = null }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2) { if (resultCode == RESULT_OK) pendingAction?.let { ensureRunning(it) } else { toast(getString(R.string.toast_need_bluetooth)); pendingAction = null } }
        if (requestCode == 6) onVpnConsent(resultCode)
    }

    private fun startVpnWithConsent() {
        try {
            val intent = android.net.VpnService.prepare(this)
            if (intent == null) { onVpnConsent(RESULT_OK); return }
            DiagLog.i(tag, "VPN consent needed: explaining first")
            AlertDialog.Builder(this).setTitle(R.string.vpn_explain_title).setMessage(R.string.vpn_explain)
                .setPositiveButton(R.string.continue_btn) { _, _ -> try { startActivityForResult(intent, 6) } catch (e: Exception) { DiagLog.e(tag, "VPN prepare", e) } }
                .setNegativeButton(R.string.close) { _, _ -> onVpnConsent(RESULT_CANCELED) }
                .setCancelable(false).show()
        } catch (e: Exception) { DiagLog.e(tag, "VPN prepare", e) }
    }

    private fun onVpnConsent(resultCode: Int) {
        if (resultCode == RESULT_OK) { DiagLog.i(tag, "VPN consent granted, starting VPN service"); ProkVpnService.start(this) }
        else { DiagLog.w(tag, "VPN consent DENIED"); toast(getString(R.string.toast_need_vpn)) }
        refresh()
    }

    // ---- node listener ----------------------------------------------------------------------------

    override fun onPeers(peers: List<Peer>) { refresh() }
    override fun onMessagesChanged() { }
    override fun onStatus(status: String) { refresh() }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
