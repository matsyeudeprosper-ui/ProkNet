package net.prok.proknet.ui

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
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
    private val dateFmt = SimpleDateFormat("d MMM HH:mm", Locale.US)

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
        v<Button>(R.id.btnStopSharing).setOnClickListener { node.setSelling(false); DiagLog.i(tag, "Stop sharing pressed"); toast("Sharing stopped"); refresh() }
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
        text(R.id.shareFee, "Prok keeps a " + node.feePct + "% fee from what you earn.")
        text(R.id.confirmFee, "Includes the " + node.feePct + "% Prok fee.")
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
        node.tunnel.state, ProkVpnService.running, if (lostDismissed) "" else node.tunnel.lastError)
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
        text(R.id.chipNode, if (running) "On" else "Off")
        val nearby = node.peers().count { it.inRange }; val offers = node.offers()
        text(R.id.homeNearby, nearby.toString() + (if (nearby == 1) " person" else " people"))
        text(R.id.homeOffers, offers.size.toString())
        val money: String
        when {
            s != ProductState.Seller.OFF -> {
                text(R.id.homeTitle, ProductState.sellerTitle(s)); text(R.id.homeSub, ProductState.sellerHint(s).ifEmpty { ProductState.priceLine(node.sellPrice) })
                money = "Earned this time: " + ProductState.cfaShort(node.gateway.totalEarnedCentimes + Market.split(node.gateway.agreedCost(), node.feePct).sellerNet)
            }
            b.active || b == ProductState.Buyer.LOST -> {
                text(R.id.homeTitle, ProductState.buyerTitle(b)); text(R.id.homeSub, ProductState.buyerHint(b, node.wifi.phase, node.tunnel.state == "TUNNEL UP" && !ProkVpnService.running, node.tunnel.lastError).ifEmpty { "Internet through a phone nearby" })
                money = if (node.tunnel.session != null) ProductState.data(sessionBytes()) + " used · " + ProductState.cfaShort(node.tunnel.runningCost()) + " so far" else ""
            }
            else -> {
                text(R.id.homeTitle, if (running) "Ready" else "Prok is off")
                val coverage = ProductState.coverageWord(ProductState.coverageNow(offers.count { !it.viaRelay }, offers.count { it.viaRelay }))
                text(R.id.homeSub, if (!running) "Tap Get Internet or Share Internet to start" else if (offers.isEmpty()) coverage else coverage + " · from " + offers.minOf { it.pricePerMb } + " CFA / MB")
                money = ""
            }
        }
        text(R.id.homeMoney, money); show(R.id.homeMoney, money.isNotEmpty())
        v<Button>(R.id.btnGetInternet).text = if (b.active) ProductState.buyerTitle(b) else "Get Internet"
        v<Button>(R.id.btnShareInternet).text = if (s != ProductState.Seller.OFF) "Sharing…" else "Share Internet"
        text(R.id.homeNote, if (running && !node.isBluetoothOn()) "Bluetooth is off. Turn it on to find people nearby." else "")
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
                text(R.id.shareTitle, ProductState.sellerTitle(s)); text(R.id.shareHint, ProductState.sellerHint(s))
                text(R.id.shareTerms, ProductState.priceLine(node.sellPrice) + " · " + ProductState.minimumLine(node.sellMinPrice) + " · " + ProductState.limitLine(node.sellMaxMb))
                val g = node.gateway; val cur = g.session
                text(R.id.shareData, ProductState.data(g.totalSoldBytes + (cur?.let { it.bytesUp + it.bytesDown } ?: 0L)))
                text(R.id.shareEarned, ProductState.cfaShort(g.totalEarnedCentimes + Market.split(g.agreedCost(), node.feePct).sellerNet))
                text(R.id.shareCustomer, if (cur == null) "No customer right now" else "1 customer: " + node.peerName(cur.peerShort) + "\n" + ProductState.data(cur.bytesUp + cur.bytesDown) + " · " + ProductState.duration(cur.durationMs) + " · " + ProductState.cfaShort(g.runningCost()))
            }
            buyerVisible -> {
                text(R.id.activeTitle, if (b == ProductState.Buyer.ONLINE) "Connected" else ProductState.buyerTitle(b))
                text(R.id.activeHint, ProductState.buyerHint(b, node.wifi.phase, node.tunnel.state == "TUNNEL UP" && !ProkVpnService.running, node.tunnel.lastError))
                show(R.id.activeProgress, b.busy); show(R.id.activeStats, node.tunnel.session != null)
                text(R.id.activeData, ProductState.data(sessionBytes())); text(R.id.activeCost, ProductState.cfaShort(node.tunnel.runningCost()))
                val c = node.tunnel.contract; val sess = node.tunnel.session
                text(R.id.activeDetail, (c?.let { ProductState.priceLine(it.pricePerMb) + " · " } ?: "") + (sess?.let { ProductState.duration(it.durationMs) + " · " } ?: "") + (node.tunnel.providerShort?.let { "via " + node.peerName(it) } ?: ""))
                v<Button>(R.id.btnStopInternet).text = if (b == ProductState.Buyer.LOST) "Close" else "Stop"
            }
            confirm -> {
                val o = pendingOffer!!
                text(R.id.confirmTitle, "Internet from " + node.peerName(o.sellerShort))
                text(R.id.confirmPrice, ProductState.priceLine(o.pricePerMb)); text(R.id.confirmMin, ProductState.minimumLine(0)); text(R.id.confirmLimit, "Limit: set by the provider")
                text(R.id.confirmSignal, ProductState.signalWord(o.rssi) + " · " + ProductState.upstreamWord(o.upstreamType) + (if (o.validated) "" else " (not checked yet)"))
            }
            setup -> text(R.id.shareUpstream, "Your Internet: " + (if (node.gateway.upstreamReady) ProductState.upstreamWord(Tunnel.upstreamType(node.gateway.upstream)) else "none yet (turn on mobile data)"))
            else -> refreshOffers()
        }
    }

    private fun refreshOffers() {
        val list = v<LinearLayout>(R.id.netOfferList); list.removeAllViews()
        val offers = node.offers()
        show(R.id.netNoOffers, offers.isEmpty())
        text(R.id.netOffersHint, if (!node.isRunning) "Prok is off" else if (offers.isEmpty()) "Looking for Internet offers near you…" else "Tap an offer to connect")
        for (o in offers) {
            val card = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; background = getDrawable(R.drawable.bg_card); setPadding(dp(18), dp(16), dp(18), dp(16)) }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT); lp.bottomMargin = dp(10); card.layoutParams = lp
            card.addView(TextView(this).apply { text = "Internet available · " + node.peerName(o.sellerShort) + (if (o.viaRelay) " · through another phone" else ""); setTextAppearance(R.style.H2) })
            card.addView(TextView(this).apply { text = ProductState.priceLine(o.pricePerMb); setTextAppearance(R.style.Big) })
            card.addView(TextView(this).apply { text = ProductState.signalWord(o.rssi) + " · " + ProductState.upstreamWord(o.upstreamType) + (if (o.validated) " · Checked" else ""); setTextAppearance(R.style.Muted) })
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
        text(R.id.earnSub, sold.toString() + (if (sold == 1) " session shared" else " sessions shared") + (if (fees > 0) " · Prok fees " + ProductState.cfaShort(fees) else ""))
        text(R.id.earnShare, if (s == ProductState.Seller.OFF) "Share your mobile data with people nearby and set your own price." else ProductState.sellerTitle(s) + " at " + ProductState.priceLine(node.sellPrice))
        v<Button>(R.id.btnEarnShare).text = if (s == ProductState.Seller.OFF) "Share Internet" else "See sharing"
        val (n, bytes) = node.store.relayStats()
        text(R.id.earnRelayStats, if (n == 0L) "Nothing carried yet" else "Carried " + n + (if (n == 1L) " message" else " messages") + " for others (" + ProductState.data(bytes) + ")")
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
            left.addView(TextView(this).apply { text = (if (ss.role == "buyer") "Internet from " else "Shared with ") + node.peerName(ss.peerShort); setTextAppearance(R.style.Body) })
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
        text(R.id.profileId, "Your Prok ID: prok-" + node.identity.shortIdHex)
        text(R.id.profileService, if (running) "Prok is running. People nearby can see you." else "Prok is off. Nobody nearby can see you.")
        v<Switch>(R.id.switchNode).isChecked = ProkNetService.running
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        v<Button>(R.id.btnBattery).text = if (pm.isIgnoringBatteryOptimizations(packageName)) "Background use allowed" else "Allow running in background"
    }

    private fun sessionBytes(): Long = node.tunnel.session?.let { it.bytesUp + it.bytesDown } ?: 0L
    private fun signedBytes(ss: StoredSession): Long = Market.Checkpoint.decode(ss.lastCheckpoint)?.billable ?: (ss.bytesUp + ss.bytesDown)
    private fun paymentWord(ss: StoredSession, e: Market.Entry?): String = if (e == null) (if (ss.finalCentimes == 0L) "Free" else "Not booked") else ProductState.paymentWord(e.status, e.payer == node.me)

    // ---- actions --------------------------------------------------------------------------------

    private fun connect() {
        val o = pendingOffer ?: return
        val peer: Peer = node.peers().firstOrNull { it.shortId == o.sellerShort } ?: run { toast("That provider is gone. Pick another offer."); pendingOffer = null; refresh(); return }
        if (!peer.offer().selling) { toast("That provider stopped sharing."); pendingOffer = null; refresh(); return }
        if (!node.hasKey(peer.shortId)) { toast("Getting the provider's details, try again in a few seconds"); return }
        DiagLog.i(tag, "CONNECT pressed: prok-" + peer.shortId + " " + o.pricePerMb + " CFA/MB")
        lostDismissed = false
        if (!node.buy(peer)) { toast("Cannot connect right now"); return }
        pendingOffer = null; refresh()
    }

    private fun stopInternet() {
        DiagLog.i(tag, "Stop Internet pressed")
        node.stopInternet("stopped by user"); lostDismissed = true; refresh()
    }

    private fun startSharing() {
        val price = v<EditText>(R.id.sharePrice).text.toString().toIntOrNull() ?: -1
        val min = v<EditText>(R.id.shareMin).text.toString().toIntOrNull() ?: -1
        val max = v<EditText>(R.id.shareMax).text.toString().toIntOrNull() ?: -1
        val err = node.setSelling(true, price, min, max)
        if (err != null) { toast(if (err == "stop buying first") "Stop your Internet session first" else "Check the price, minimum and limit"); return }
        DiagLog.i(tag, "Start sharing pressed: " + price + " CFA/MB min " + min + " max " + max + " MB")
        shareSetupOpen = false
        if (!node.gateway.upstreamReady) toast("Sharing is on, but your phone has no Internet yet")
        refresh()
    }

    private fun sessionDialog(ss: StoredSession, e: Market.Entry?) {
        val c = Market.Contract.decode(ss.contract)
        val dur = if (ss.endTs > 0) ss.endTs - ss.startTs else 0L
        val lines = ArrayList<String>()
        lines += (if (ss.role == "buyer") "Internet from " else "Shared with ") + node.peerName(ss.peerShort)
        lines += "Date: " + dateFmt.format(Date(ss.startTs))
        lines += "Data: " + ProductState.data(signedBytes(ss))
        lines += "Duration: " + ProductState.duration(dur)
        if (c != null) lines += "Price: " + ProductState.priceLine(c.pricePerMb) + (if (c.minPriceCfa > 0) " · " + ProductState.minimumLine(c.minPriceCfa) else "")
        lines += "Final cost: " + ProductState.cfaExact(ss.finalCentimes)
        if (ss.role == "seller" && c != null) lines += "Prok fee: " + ProductState.cfaExact(Market.split(ss.finalCentimes, c.feePct).fee)
        lines += "Payment: " + paymentWord(ss, e)
        if (ss.status != "ended" && ss.status != "settled") lines += "Status: " + ss.status
        val b = AlertDialog.Builder(this).setTitle("Session").setMessage(lines.joinToString("\n")).setNegativeButton("Close", null)
        if (e != null && e.status == Market.ST_PENDING) {
            if (e.payer == node.me && e.paidAt == 0L) b.setPositiveButton("Mark as paid") { _, _ -> node.ledgerAction(e.id, "paid")?.let { toast(it) }; refresh() }
            if (e.recipient == node.me && e.receivedAt == 0L) b.setPositiveButton("Mark as received") { _, _ -> node.ledgerAction(e.id, "received")?.let { toast(it) }; refresh() }
            b.setNeutralButton("Dispute") { _, _ -> node.ledgerAction(e.id, "dispute")?.let { toast(it) }; refresh() }
        }
        b.show()
    }

    private fun renameDialog() {
        val input = EditText(this); input.inputType = InputType.TYPE_CLASS_TEXT; input.setText(node.identity.displayName)
        AlertDialog.Builder(this).setTitle("Your name").setMessage("People nearby see this name.").setView(input)
            .setPositiveButton("Save") { _, _ -> val n = input.text.toString().trim(); if (n.isNotEmpty()) { Identity.saveName(applicationContext, node.identity, n); refresh() } }
            .setNegativeButton("Cancel", null).show()
    }

    private fun batterySettings() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (pm.isIgnoringBatteryOptimizations(packageName)) { toast("Already allowed"); return }
        try { startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).setData(Uri.parse("package:" + packageName))) }
        catch (e: Exception) { try { startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (_: Exception) { toast("Not available on this phone") } }
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
        else { DiagLog.e(tag, "permissions DENIED: " + denied.joinToString()); toast("Prok needs Bluetooth permission to find people nearby"); pendingAction = null }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2) { if (resultCode == RESULT_OK) pendingAction?.let { ensureRunning(it) } else { toast("Bluetooth is needed to find people nearby"); pendingAction = null } }
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
        else { DiagLog.w(tag, "VPN consent DENIED"); toast("Prok needs your OK to route Internet to your apps") }
        refresh()
    }

    // ---- node listener ----------------------------------------------------------------------------

    override fun onPeers(peers: List<Peer>) { refresh() }
    override fun onMessagesChanged() { }
    override fun onStatus(status: String) { refresh() }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
