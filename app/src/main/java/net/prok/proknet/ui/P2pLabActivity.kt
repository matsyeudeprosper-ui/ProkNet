package net.prok.proknet.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import net.prok.proknet.ProkNetApp
import net.prok.proknet.R
import net.prok.proknet.ble.Peer
import net.prok.proknet.ble.ProkNetNode
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Market
import net.prok.proknet.core.P2pPlan
import net.prok.proknet.core.ShareCheck
import net.prok.proknet.node.RelayProbe
import net.prok.proknet.transport.P2pLink
import net.prok.proknet.vpn.ProkVpnService

/**
 * v0.9.7 Wi-Fi Direct Lab, developer only. Method A (LocalOnlyHotspot) is
 * untouched; this screen drives the experimental method B and shows exactly
 * what Android did, including whether the seller kept its home Wi-Fi.
 */
class P2pLabActivity : Activity(), ProkNetNode.Listener {
    private val tag = "P2PLAB"
    private lateinit var node: ProkNetNode
    private val main = Handler(Looper.getMainLooper())
    private lateinit var peersAdapter: ArrayAdapter<String>
    private var p2pPeers: List<P2pLink.Peer> = emptyList()
    private val ticker = object : Runnable { override fun run() { refresh(); main.postDelayed(this, 2000) } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_p2p_lab)
        node = ProkNetApp.node(this)
        findViewById<Button>(R.id.btnP2pSell).setOnClickListener {
            val err = node.p2pSell()
            toast(err ?: "Creating the Wi-Fi Direct group, keep this phone on its Wi-Fi network")
            refresh()
        }
        findViewById<Button>(R.id.btnP2pBuy).setOnClickListener {
            val err = node.p2pBuy()
            toast(err ?: "Looking for Wi-Fi Direct peers...")
            refresh()
        }
        findViewById<Button>(R.id.btnP2pStop).setOnClickListener { node.p2pStop(); toast("Wi-Fi Direct stopped"); refresh() }
        findViewById<Button>(R.id.btnP2pRefresh).setOnClickListener { refresh() }
        findViewById<Button>(R.id.btnP2pDiag).setOnClickListener { copyDiag() }
        findViewById<Button>(R.id.btnP2pUse).setOnClickListener { useInternet() }
        findViewById<Button>(R.id.btnP2pNetTest).setOnClickListener { netTest() }
        peersAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, ArrayList())
        findViewById<ListView>(R.id.listP2pPeers).let { l ->
            l.adapter = peersAdapter
            l.setOnItemClickListener { _, _, pos, _ ->
                val p = p2pPeers.getOrNull(pos) ?: return@setOnItemClickListener
                val err = node.p2p.connectTo(p.address)
                toast(err ?: ("Joining " + p.name + "..."))
                refresh()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        ProkNetApp.visibleActivities++
        node.addListener(this)
        main.post(ticker)
    }

    override fun onStop() {
        ProkNetApp.visibleActivities = maxOf(0, ProkNetApp.visibleActivities - 1)
        main.removeCallbacks(ticker)
        node.removeListener(this)
        super.onStop()
    }

    // ---- the buyer side, through the adopted link ---------------------------------------------------

    private fun useInternet() {
        val peer = node.wifi.linkedPeer ?: run { toast("No ProkNet link yet: form the Wi-Fi Direct group first"); return }
        if (node.gateway.providing) { toast("This phone is the seller here"); return }
        val p = node.peers().firstOrNull { it.shortId == peer }
        val price = p?.offer()?.pricePerMb ?: 0
        DiagLog.i(tag, "P2P lab: starting the tunnel to prok-" + peer + " at " + price + " CFA/MB over the Wi-Fi Direct link")
        node.vpnRequested = { startVpnWithConsent() }
        if (!node.tunnel.start(price)) toast("Cannot start the session (see log)") else toast("Session starting over Wi-Fi Direct...")
        refresh()
    }

    private fun startVpnWithConsent() {
        try {
            val intent = android.net.VpnService.prepare(this)
            if (intent != null) { DiagLog.i(tag, "VPN consent needed"); startActivityForResult(intent, 6) } else onVpn(RESULT_OK)
        } catch (e: Exception) { DiagLog.e(tag, "VPN prepare", e) }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 6) onVpn(resultCode)
    }

    private fun onVpn(resultCode: Int) {
        if (resultCode == RESULT_OK) { DiagLog.i(tag, "VPN consent granted"); ProkVpnService.start(this) }
        else toast("VPN refused: only the in-app net test will work")
        refresh()
    }

    private fun netTest() {
        if (node.tunnel.session == null) { toast("No session yet"); return }
        toast("Testing DNS + HTTPS through the seller...")
        node.internetTest("example.com") { r -> runOnUiThread { AlertDialog.Builder(this).setTitle(if (r.ok) "Internet OK" else "Internet FAILED").setMessage(r.text).setPositiveButton("OK", null).show(); refresh() } }
    }

    // ---- display -------------------------------------------------------------------------------------

    private fun refresh() {
        val p = node.p2p
        p2pPeers = p.peers
        peersAdapter.clear(); peersAdapter.addAll(p2pPeers.map { it.describe() })
        val sta = node.wifi.currentWifi()
        findViewById<TextView>(R.id.txtP2pState).text =
            "supported " + p.supported + " | enabled " + p.p2pEnabled + " | phase " + p.phase + " | role " + p.role + "\n" +
            "my Wi-Fi network now: " + (sta?.let { (it.ssid ?: "?") + " " + ShareCheck.describe(it.freqMhz) } ?: "none") + "\n" +
            "before the test: " + p.staBefore.ifEmpty { "none" } + "\n" +
            "group: " + p.groupInfo.ifEmpty { "none" } + "\n" +
            "socket: " + p.socketInfo.ifEmpty { "none" } + "\n" +
            "ProkNet link: " + node.wifi.phase + (node.wifi.linkedPeer?.let { " with prok-" + it } ?: "") + "\n" +
            "VERDICT: " + P2pPlan.verdictText(p.verdict()) + (if (p.lastError.isNotEmpty()) "\nlast error: " + p.lastError else "")
        val t = node.tunnel; val g = node.gateway
        findViewById<TextView>(R.id.txtP2pTunnel).text =
            "buyer: " + t.state + (t.providerShort?.let { " from prok-" + it } ?: "") + " | vpn " + ProkVpnService.running +
            (t.session?.let { " | " + Market.mb(it.bytesUp + it.bytesDown) + ", " + Market.cfa(t.runningCost()) } ?: "") + "\n" +
            "seller: " + g.state + " | upstream " + g.upstreamDescription() +
            (g.session?.let { " | buyer prok-" + it.peerShort + " " + Market.mb(it.bytesUp + it.bytesDown) } ?: "")
        findViewById<TextView>(R.id.txtP2pDiag).text = p.diag()
    }

    private fun copyDiag() {
        val text = "Prok P2P DIAG " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date()) + "\n" +
            "me: prok-" + node.identity.shortIdHex + " \"" + node.identity.displayName + "\"\n" +
            node.p2p.diag() +
            "ProkNet link: " + node.wifi.linkState() + "\n  " + node.wifi.linkDescription() + "\n" +
            "buyer: " + node.tunnel.state + " vpn " + ProkVpnService.running + " flows " + node.tunnel.activeFlows() + " dns " + node.tunnel.dnsCount +
            (node.tunnel.session?.let { " | up " + it.bytesUp + " B down " + it.bytesDown + " B" } ?: "") + "\n" +
            "seller: " + node.gateway.state + " upstream " + node.gateway.upstreamDescription() +
            (node.gateway.session?.let { " | buyer prok-" + it.peerShort + " up " + it.bytesUp + " B down " + it.bytesDown + " B" } ?: "") + "\n" +
            "--- networks ---\n" + RelayProbe.networks(this).joinToString("\n") + "\n" +
            "interfaces: " + RelayProbe.interfaces().joinToString(" ") + "\n" +
            "--- last 100 log lines ---\n" + DiagLog.text().lines().takeLast(100).joinToString("\n") + "\n"
        (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Prok p2p diag", text))
        DiagLog.i(tag, "p2p diag copied (" + text.length + " chars)")
        toast("P2P diagnostic copied")
    }

    override fun onPeers(peers: List<Peer>) {}
    override fun onMessagesChanged() {}
    override fun onStatus(status: String) { refresh() }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
}
