package net.prok.proknet.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
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
import java.util.Date
import java.util.Locale
import net.prok.proknet.ProkNetApp
import net.prok.proknet.R
import net.prok.proknet.ble.Peer
import net.prok.proknet.ble.ProkNetNode
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Identity
import net.prok.proknet.core.Market
import net.prok.proknet.core.ProductState
import net.prok.proknet.core.ProductState.active
import net.prok.proknet.core.ProductState.busy
import net.prok.proknet.core.StoredSession
import net.prok.proknet.core.Tunnel
import net.prok.proknet.service.ProkNetService
import net.prok.proknet.vpn.ProkVpnService

/**
 * v0.8 consumer screen. Five tabs, plain words, big actions. It only observes
 * the node (owned by the Application, driven by ProkNetService) and never
 * shows engine states directly: every word comes from [ProductState].
 * The engineering tools live in [LabActivity] (Profile > Developer).
 */
class MainActivity : Activity(), ProkNetNode.Listener {
    private val tag = "UI"
    private lateinit var node: ProkNetNode
    private val main = Handler(Looper.getMainLooper())
    private val dateFmt = SimpleDateFormat("d MMM HH:mm", Locale.FRANCE)

    private enum class Tab { HOME, INTERNET, EARN, ACTIVITY, PROFILE }
    private var tab = Tab.HOME
    /** Offer the user tapped, waiting for CONNECT. */
    private var pendingOffer: Market.Offer? = null
    private var shareSetupOpen = false
    /** After the user pressed STOP on a lost connection, the old error is no longer shown. */
    private var lostDismissed = true
    private var pendingAction: (() -> Unit)? = null

    private val versionName: String by lazy { try { packageManager.getPackageInfo(packageName, 0).versionName ?: "?" } catch (e: Exception) { "?" } }
    private val ticker = object : Runnable { override fun run() { refresh(); main.postDelayed(this, 2000) } }

    private fun <T : View> v(id: Int): T = findViewById(id)
    private fun text(id: Int, s: String) { v<TextView>(id).text = s }
    private fun show(id: Int, on: Boolean) { v<View>(id).visibility = if (on) View.VISIBLE else View.GONE }
    private fun dp(x: Int): Int = (x * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        node = ProkNetApp.node(this)

        v<View>(R.id.navHome).setOnClickListener { select(Tab.HOME) }
        v<View>(R.id.navInternet).setOnClickListener { select(Tab.INTERNET) }
        v<View>(R.id.navEarn).setOnClickListener { select(Tab.EARN) }
        v<View>(R.id.navActivity).setOnClickListener { select(Tab.ACTIVITY) }
        v<View>(R.id.navProfile).setOnClickListener { select(Tab.PROFILE) }

        v<Button>(R.id.btnGetInternet).setOnClickListener { ensureRunning { shareSetupOpen = false; select(Tab.INTERNET) } }
        v<Button>(R.id.btnShareInternet).setOnClickListener { ensureRunning { openShareSetup() } }
        v<Button>(R.id.btnEarnShare).setOnClickListener { ensureRunning { openShareSetup() } }
        v<Button>(R.id.btnConnect).setOnClickListener { connect() }
        v<Button>(R.id.btnConfirmBack).setOnClickListener { pendingOffer = null; refresh() }
        v<Button>(R.id.btnStopInternet).setOnClickListener { stopInternet() }
        v<Button>(R.id.btnStartSharing).setOnClickListener { startSharing() }
        v<Button>(R.id.btnShareBack).setOnClickListener { shareSetupOpen = false; refresh() }
        v<Button>(R.id.btnStopSharing).setOnClickListener { node.setSelling(false); DiagLog.i(tag, "Stop sharing pressed"); toast(getString(R.string.toast_sharing_stopped)); refresh() }
        v<Switch>(R.id.switchRelay).setOnClickListener { node.setRelay(v<Switch>(R.id.switchRelay).isChecked); refresh() }
        v<Switch>(R.id.switchNode).setOnClickListener {
            val want = v<Switch>(R.id.switchNode).isChecked
            if (want) ensureRunning { } else { DiagLog.i(tag, "Keep Prok running switched OFF"); ProkNetService.stop(this) }
            main.postDelayed({ refresh() }, 1500)
        }
        v<Button>(R.id.btnRename).setOnClickListener { renameDialog() }
        v<Button>(R.id.btnBattery).setOnClickListener { batterySettings() }
        v<Button>(R.id.btnDeveloper).setOnClickListener { startActivity(Intent(this, LabActivity::class.java)) }
        text(R.id.profileVersion, "Prok " + versionName)
        text(R.id.shareFee, getString(R.string.share_fee, node.feePct))
        text(R.id.confirmFee, getString(R.string.confirm_fee, node.feePct))
        select(Tab.HOME)
    }

    override fun onStart() {
        super.onStart()
        ProkNetApp.visibleActivities++
        node.addListener(this)
        node.vpnRequested = { startVpnWithConsent() }
        DiagLog.i(tag, "consumer screen visible; service " + (if (ProkNetService.running) "RUNNING" else "stopped"))
        // A phone that already granted everything just runs: no button to press.
        if (!ProkNetService.running && missingPermissions().isEmpty() && !notificationPermissionMissing() && node.isBluetoothOn()) ProkNetService.start(this)
        main.post(ticker)
    }

    override fun onStop() {
        ProkNetApp.visibleActivities = maxOf(0, ProkNetApp.visibleActivities - 1)
        main.removeCallbacks(ticker)
        node.removeListener(this)
        super.onStop()
    }

    // ---- tabs ------------------------------------------------------------------------------------

    private fun select(t: Tab) {
        tab = t
        show(R.id.tabHome, t == Tab.HOME); show(R.id.tabInternet, t == Tab.INTERNET); show(R.id.tabEarn, t == Tab.EARN)
        show(R.id.tabActivity, t == Tab.ACTIVITY); show(R.id.tabProfile, t == Tab.PROFILE)
        val items = listOf(Tab.HOME to (R.id.navHomeIcon to R.id.navHomeText), Tab.INTERNET to (R.id.navInternetIcon to R.id.navInternetText), Tab.EARN to (R.id.navEarnIcon to R.id.navEarnText),
            Tab.ACTIVITY to (R.id.navActivityIcon to R.id.navActivityText), Tab.PROFILE to (R.id.navProfileIcon to R.id.navProfileText))
        val on = getColor(R.color.nav_active); val off = getColor(R.color.nav_inactive)
        for ((tt, ids) in items) { v<ImageView>(ids.first).setColorFilter(if (tt == t) on else off); v<TextView>(ids.second).setTextColor(if (tt == t) on else off) }
        refresh()
    }

    private fun openShareSetup() {
        if (node.sellOn) { select(Tab.INTERNET); return }
        v<EditText>(R.id.sharePrice).setText(node.sellPrice.toString()); v<EditText>(R.id.shareMin).setText(node.sellMinPrice.toString()); v<EditText>(R.id.shareMax).setText(node.sellMaxMb.toString())
        shareSetupOpen = true; pendingOffer = null; select(Tab.INTERNET)
    }

    // ---- refresh (everything derives from the node through ProductState) -------------------------

    private fun buyerState(): ProductState.Buyer = ProductState.buyer(node.buyerWanted != null, node.wifi.phase, node.wifi.linkedPeer != null && node.wifi.canReach(node.wifi.linkedPeer ?: ""),
        node.tunnel.state, ProkVpnService.running, buyError())

    /** Why the last attempt failed, until the user closes the card. The link layer's reason counts too. */
    private fun buyError(): String = if (lostDismissed) "" else node.tunnel.lastError.ifEmpty { node.lastBuyError }

    private fun locationOn(): Boolean = try {
        val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    } catch (e: Exception) { true }

    /** A seller cannot serve anybody without Wi-Fi and Location: Android needs both for the hotspot. */
    private fun sellerWarning(): String = when {
        // v0.9.6: tested on THIS network. Not a global rule, and mobile data still works.
        node.canShareWhileOnWifi() == false -> getString(R.string.share_wifi_impossible)
        node.shareCheck == net.prok.proknet.core.ShareCheck.Result.UNKNOWN && node.sellOn &&
            net.prok.proknet.core.ShareCheck.needed(net.prok.proknet.core.Tunnel.upstreamType(node.gateway.upstream)) -> getString(R.string.share_wifi_checking)
        !node.wifi.wifiEnabled -> getString(R.string.seller_needs_wifi)
        !locationOn() -> getString(R.string.seller_needs_location)
        else -> ""
    }
    private fun sellerState(): ProductState.Seller = ProductState.seller(node.sellOn, node.gateway.state)
    private fun buyerOn(): Boolean = node.buyerWanted != null || node.tunnel.session != null || node.tunnel.contract != null

    private fun refresh() {
        if (isFinishing) return
        val b = buyerState(); val s = sellerState()
        val running = ProkNetService.running && node.isRunning
        when (tab) {
            Tab.HOME -> refreshHome(b, s, running)
            Tab.INTERNET -> refreshInternet(b, s)
            Tab.EARN -> refreshEarn(s)
            Tab.ACTIVITY -> refreshActivity()
            Tab.PROFILE -> refreshProfile(running)
        }
    }

    private fun refreshHome(b: ProductState.Buyer, s: ProductState.Seller, running: Boolean) {
        text(R.id.chipNode, getString(if (running) R.string.node_on else R.string.node_off))
        val nearby = node.peers().count { it.inRange }; val offers = node.offers()
        text(R.id.homeNearby, if (nearby == 1) getString(R.string.home_people_one) else getString(R.string.home_people_many, nearby))
        text(R.id.homeOffers, offers.size.toString())
        val money: String
        when {
            s != ProductState.Seller.OFF -> {
                text(R.id.homeTitle, ProductState.sellerTitle(s)); text(R.id.homeSub, ProductState.sellerHint(s).ifEmpty { ProductState.priceLine(node.sellPrice) })
                money = getString(R.string.home_earned_now, ProductState.cfaShort(node.gateway.totalEarnedCentimes + Market.split(node.gateway.agreedCost(), node.feePct).sellerNet))
            }
            b.active || b == ProductState.Buyer.LOST -> {
                text(R.id.homeTitle, ProductState.buyerTitle(b)); text(R.id.homeSub, ProductState.buyerHint(b, node.wifi.phase, node.tunnel.state == "TUNNEL UP" && !ProkVpnService.running, buyError()).ifEmpty { getString(R.string.home_relay_subtitle) })
                money = if (node.tunnel.session != null) getString(R.string.home_used_cost, ProductState.data(sessionBytes()), ProductState.cfaShort(node.tunnel.runningCost())) else ""
            }
            else -> {
                text(R.id.homeTitle, getString(if (running) R.string.home_ready else R.string.home_off))
                val coverage = ProductState.coverageWord(ProductState.coverageNow(offers.count { !it.viaRelay }, offers.count { it.viaRelay }))
                text(R.id.homeSub, if (!running) getString(R.string.home_start_hint) else if (offers.isEmpty()) coverage else getString(R.string.home_from_price, coverage, offers.minOf { it.pricePerMb }))
                money = ""
            }
        }
        text(R.id.homeMoney, money); show(R.id.homeMoney, money.isNotEmpty())
        v<Button>(R.id.btnGetInternet).text = if (b.active) ProductState.buyerTitle(b) else getString(R.string.get_internet)
        v<Button>(R.id.btnShareInternet).text = getString(if (s != ProductState.Seller.OFF) R.string.sharing_now else R.string.share_internet)
        text(R.id.homeNote, if (running && !node.isBluetoothOn()) getString(R.string.home_bluetooth_off) else "")
    }

    private fun refreshInternet(b: ProductState.Buyer, s: ProductState.Seller) {
        val sellerOn = s != ProductState.Seller.OFF
        val buyerVisible = buyerOn() || (b == ProductState.Buyer.LOST && !lostDismissed)
        val confirm = !sellerOn && !buyerVisible && pendingOffer != null
        val setup = !sellerOn && !buyerVisible && !confirm && shareSetupOpen
        show(R.id.netShareActive, sellerOn); show(R.id.netActive, !sellerOn && buyerVisible); show(R.id.netConfirm, confirm); show(R.id.netShareSetup, setup)
        show(R.id.netOffers, !sellerOn && !buyerVisible && !confirm && !setup)
        when {
            sellerOn -> {
                text(R.id.shareTitle, ProductState.sellerTitle(s)); text(R.id.shareHint, sellerWarning().ifEmpty { ProductState.sellerHint(s) })
                text(R.id.shareTerms, ProductState.priceLine(node.sellPrice) + " · " + ProductState.minimumLine(node.sellMinPrice) + " · " + ProductState.limitLine(node.sellMaxMb))
                val g = node.gateway; val cur = g.session
                text(R.id.shareData, ProductState.data(g.totalSoldBytes + (cur?.let { it.bytesUp + it.bytesDown } ?: 0L)))
                text(R.id.shareEarned, ProductState.cfaShort(g.totalEarnedCentimes + Market.split(g.agreedCost(), node.feePct).sellerNet))
                text(R.id.shareCustomer, if (cur == null) getString(R.string.share_no_customer) else getString(R.string.share_one_customer, node.peerName(cur.peerShort)) + "\n" +
                    ProductState.data(cur.bytesUp + cur.bytesDown) + " · " + ProductState.duration(cur.durationMs) + " · " + ProductState.cfaShort(g.runningCost()))
            }
            buyerVisible -> {
                text(R.id.activeTitle, if (b == ProductState.Buyer.ONLINE) getString(R.string.connected) else ProductState.buyerTitle(b))
                text(R.id.activeHint, ProductState.buyerHint(b, node.wifi.phase, node.tunnel.state == "TUNNEL UP" && !ProkVpnService.running, buyError()))
                show(R.id.activeProgress, b.busy); show(R.id.activeStats, node.tunnel.session != null)
                text(R.id.activeData, ProductState.data(sessionBytes())); text(R.id.activeCost, ProductState.cfaShort(node.tunnel.runningCost()))
                val c = node.tunnel.contract; val sess = node.tunnel.session
                text(R.id.activeDetail, (c?.let { ProductState.priceLine(it.pricePerMb) + " · " } ?: "") + (sess?.let { ProductState.duration(it.durationMs) + " · " } ?: "") + (node.tunnel.providerShort?.let { getString(R.string.via_peer, node.peerName(it)) } ?: ""))
                v<Button>(R.id.btnStopInternet).text = getString(if (b == ProductState.Buyer.LOST) R.string.close else R.string.stop)
            }
            confirm -> {
                val o = pendingOffer!!
                text(R.id.confirmTitle, getString(R.string.confirm_from, node.peerName(o.sellerShort)))
                text(R.id.confirmPrice, ProductState.priceLine(o.pricePerMb)); text(R.id.confirmMin, ProductState.minimumLine(0)); text(R.id.confirmLimit, getString(R.string.confirm_limit_provider))
                text(R.id.confirmSignal, ProductState.signalWord(o.rssi) + " · " + ProductState.upstreamWord(o.upstreamType) + (if (o.validated) "" else getString(R.string.confirm_not_checked)))
            }
            setup -> {
                val warn = sellerWarning()
                text(R.id.shareUpstream, getString(R.string.share_your_internet,
                    if (node.gateway.upstreamReady) ProductState.upstreamWord(Tunnel.upstreamType(node.gateway.upstream)) else getString(R.string.share_no_internet_yet)) +
                    (if (warn.isNotEmpty()) "\n" + warn else ""))
            }
            else -> refreshOffers()
        }
    }

    private fun refreshOffers() {
        val list = v<LinearLayout>(R.id.netOfferList); list.removeAllViews()
        val offers = node.offers()
        show(R.id.netNoOffers, offers.isEmpty())
        text(R.id.netOffersHint, getString(if (!node.isRunning) R.string.offers_node_off else if (offers.isEmpty()) R.string.offers_searching else R.string.offers_tap))
        for (o in offers) {
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = getDrawable(R.drawable.bg_card); setPadding(dp(18), dp(16), dp(18), dp(16)) }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = dp(10); card.layoutParams = lp
            card.addView(TextView(this).apply { text = getString(R.string.offer_available_from, node.peerName(o.sellerShort)) + (if (o.viaRelay) getString(R.string.offer_via_relay) else ""); setTextAppearance(R.style.H2) })
            card.addView(TextView(this).apply { text = ProductState.priceLine(o.pricePerMb); setTextAppearance(R.style.Big) })
            card.addView(TextView(this).apply { text = ProductState.signalWord(o.rssi) + " · " + ProductState.upstreamWord(o.upstreamType) + (if (o.validated) getString(R.string.offer_checked) else ""); setTextAppearance(R.style.Muted) })
            card.setOnClickListener { pendingOffer = o; refresh() }
            list.addView(card)
        }
    }

    private fun refreshEarn(s: ProductState.Seller) {
        val ledger = node.store.ledger(1000)
        val gross = ledger.filter { it.recipient == node.me && it.status != Market.ST_CANCELLED }.sumOf { it.amountCentimes }
        val fees = ledger.filter { it.payer == node.me && it.recipient == Market.PROK_ID && it.status != Market.ST_CANCELLED }.sumOf { it.amountCentimes }
        val sold = node.store.sessions(1000).count { it.role == "seller" }
        text(R.id.earnTotal, ProductState.cfaShort(gross - fees))
        text(R.id.earnSub, (if (sold == 1) getString(R.string.earn_sessions_one) else getString(R.string.earn_sessions_many, sold)) +
            (if (fees > 0) getString(R.string.earn_fees, ProductState.cfaShort(fees)) else ""))
        text(R.id.earnShare, if (s == ProductState.Seller.OFF) getString(R.string.earn_share_pitch) else getString(R.string.earn_share_on, ProductState.sellerTitle(s), ProductState.priceLine(node.sellPrice)))
        v<Button>(R.id.btnEarnShare).text = getString(if (s == ProductState.Seller.OFF) R.string.share_internet else R.string.earn_see_sharing)
        val (n, bytes) = node.store.relayStats()
        text(R.id.earnRelayStats, if (n == 0L) getString(R.string.help_none)
            else if (n == 1L) getString(R.string.help_carried_one, ProductState.data(bytes)) else getString(R.string.help_carried_many, n.toInt(), ProductState.data(bytes)))
        v<Switch>(R.id.switchRelay).isChecked = node.relayOn
    }

    private fun refreshActivity() {
        val ledger = node.store.ledger(1000)
        val w = ProductState.wallet(ledger, node.me)
        text(R.id.walletPay, ProductState.cfaShort(w.toPay)); text(R.id.walletReceive, ProductState.cfaShort(w.toReceive)); text(R.id.walletFees, ProductState.cfaShort(w.prokFees))
        val list = v<LinearLayout>(R.id.activityList); list.removeAllViews()
        val sessions = node.store.sessions(100)
        show(R.id.activityEmpty, sessions.isEmpty())
        for (ss in sessions) {
            val entry = ledger.firstOrNull { it.sessionHex == ss.sessionHex && it.recipient != Market.PROK_ID }
            val card = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; background = getDrawable(R.drawable.bg_card); setPadding(dp(16), dp(14), dp(16), dp(14)) }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = dp(8); card.layoutParams = lp
            val left = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) }
            left.addView(TextView(this).apply { text = getString(if (ss.role == "buyer") R.string.activity_bought else R.string.activity_sold, node.peerName(ss.peerShort)); setTextAppearance(R.style.Body) })
            left.addView(TextView(this).apply { text = dateFmt.format(Date(ss.startTs)) + " · " + ProductState.data(signedBytes(ss)); setTextAppearance(R.style.Muted) })
            val right = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = android.view.Gravity.END }
            right.addView(TextView(this).apply { text = ProductState.cfaShort(ss.finalCentimes); setTextAppearance(R.style.H2) })
            right.addView(TextView(this).apply { text = paymentWord(ss, entry); setTextAppearance(R.style.Muted) })
            card.addView(left); card.addView(right)
            card.setOnClickListener { sessionDialog(ss, entry) }
            list.addView(card)
        }
    }

    private fun refreshProfile(running: Boolean) {
        text(R.id.profileName, node.identity.displayName)
        text(R.id.profileId, getString(R.string.profile_id, "prok-" + node.identity.shortIdHex))
        text(R.id.profileService, getString(if (running) R.string.network_running else R.string.network_stopped))
        v<Switch>(R.id.switchNode).isChecked = ProkNetService.running
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        v<Button>(R.id.btnBattery).text = getString(if (pm.isIgnoringBatteryOptimizations(packageName)) R.string.background_allowed else R.string.allow_background)
    }

    private fun sessionBytes(): Long = node.tunnel.session?.let { it.bytesUp + it.bytesDown } ?: 0L
    private fun signedBytes(ss: StoredSession): Long = Market.Checkpoint.decode(ss.lastCheckpoint)?.billable ?: (ss.bytesUp + ss.bytesDown)
    private fun paymentWord(ss: StoredSession, e: Market.Entry?): String =
        if (e == null) getString(if (ss.finalCentimes == 0L) R.string.payment_free else R.string.payment_not_booked) else ProductState.paymentWord(e.status, e.payer == node.me)

    // ---- actions --------------------------------------------------------------------------------

    private fun connect() {
        val o = pendingOffer ?: return
        val peer: Peer = node.peers().firstOrNull { it.shortId == o.sellerShort } ?: run { toast(getString(R.string.toast_provider_gone)); pendingOffer = null; refresh(); return }
        if (!peer.offer().selling) { toast(getString(R.string.toast_provider_stopped)); pendingOffer = null; refresh(); return }
        if (!node.hasKey(peer.shortId)) { toast(getString(R.string.toast_need_key)); return }
        if (!node.wifi.wifiEnabled) { toast(getString(R.string.toast_need_wifi)); return }
        DiagLog.i(tag, "CONNECT pressed: prok-" + peer.shortId + " " + o.pricePerMb + " CFA/MB")
        lostDismissed = false
        if (!node.buy(peer)) { toast(getString(R.string.toast_cannot_connect)); return }
        pendingOffer = null; refresh()
    }

    private fun stopInternet() {
        DiagLog.i(tag, "Stop Internet pressed")
        node.stopInternet("stopped by user"); lostDismissed = true; refresh()
    }

    private fun startSharing() {
        // v0.9.2: an empty minimum or limit means "none", not an invalid value, and every
        // refusal now says what is really wrong (the old code blamed the price for all of them).
        val price = v<EditText>(R.id.sharePrice).text.toString().trim().toIntOrNull()
        val min = v<EditText>(R.id.shareMin).text.toString().trim().toIntOrNull() ?: 0
        val max = v<EditText>(R.id.shareMax).text.toString().trim().toIntOrNull() ?: 0
        if (price == null || !Market.validPrice(price)) { toast(getString(R.string.toast_price_needed)); return }
        if (!Market.validMinPrice(min) || !Market.validMaxMb(max)) { toast(getString(R.string.toast_check_terms)); return }
        val err = node.setSelling(true, price, min, max)
        if (err != null) {
            DiagLog.w(tag, "start sharing refused: " + err)
            toast(when (err) {
                "stop buying first" -> getString(R.string.toast_stop_buying)
                "relay mode is on" -> getString(R.string.toast_relay_on)
                else -> getString(R.string.toast_check_terms)
            })
            return
        }
        DiagLog.i(tag, "Start sharing pressed: " + price + " CFA/MB min " + min + " max " + max + " MB")
        shareSetupOpen = false
        if (!node.gateway.upstreamReady) toast(getString(R.string.toast_sharing_no_internet))
        refresh()
    }

    private fun sessionDialog(ss: StoredSession, e: Market.Entry?) {
        val c = Market.Contract.decode(ss.contract)
        val dur = if (ss.endTs > 0) ss.endTs - ss.startTs else 0L
        val lines = ArrayList<String>()
        lines += getString(if (ss.role == "buyer") R.string.activity_bought else R.string.activity_sold, node.peerName(ss.peerShort))
        lines += getString(R.string.session_date, dateFmt.format(Date(ss.startTs)))
        lines += getString(R.string.session_data, ProductState.data(signedBytes(ss)))
        lines += getString(R.string.session_duration, ProductState.duration(dur))
        if (c != null) lines += getString(R.string.session_price, ProductState.priceLine(c.pricePerMb) + (if (c.minPriceCfa > 0) " · " + ProductState.minimumLine(c.minPriceCfa) else ""))
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
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) { toast(getString(R.string.background_allowed)); return }
        try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:" + packageName))) }
        catch (e: Exception) { try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) { toast(getString(R.string.toast_not_available)) } }
    }

    // ---- start-up: permissions, Bluetooth, service (same flow as the lab screen) --------------------

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
            if (intent != null) { DiagLog.i(tag, "VPN consent needed"); startActivityForResult(intent, 6) } else onVpnConsent(RESULT_OK)
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
