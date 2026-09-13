package net.prok.proknet.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Identity
import net.prok.proknet.core.MessageStore
import net.prok.proknet.core.MsgStatus
import net.prok.proknet.core.Packet
import net.prok.proknet.core.StoredMessage

/**
 * One ProkNet node = identity + GATT server (receive) + advertiser (be found)
 * + scanner (find others) + sender (deliver) + delivery queue (milestone 2A).
 * This is the only object the UI talks to.
 */
class ProkNetNode(private val context: Context) {
    private val tag = "NODE"
    private val main = Handler(Looper.getMainLooper())

    val identity: Identity = Identity.load(context)
    val store = MessageStore(context)

    interface Listener {
        fun onPeers(peers: List<Peer>)
        fun onMessagesChanged()
        fun onStatus(status: String)
    }

    /** Milestone 2B: several observers (the service for its notification, the Activity when visible). */
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<Listener>()
    fun addListener(l: Listener) { if (!listeners.contains(l)) listeners.add(l) }
    fun removeListener(l: Listener) { listeners.remove(l) }
    private val listener = object : Listener {
        override fun onPeers(peers: List<Peer>) { listeners.forEach { it.onPeers(peers) } }
        override fun onMessagesChanged() { listeners.forEach { it.onMessagesChanged() } }
        override fun onStatus(status: String) { listeners.forEach { it.onStatus(status) } }
    }

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    private var server: GattServerNode? = null
    private var advertiser: BleAdvertiser? = null
    private var scanner: BleScanner? = null
    private var sender: BleSender? = null
    val queue = DeliveryQueue(store, identity, { sender }, { peers() }, { main.post { listener.onMessagesChanged(); pushStatus() } },
        (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager))

    @Volatile var isRunning = false
        private set
    private var visible: List<Peer> = emptyList()
    private var lastVisibleIds: Set<String> = emptySet()

    fun isBluetoothOn(): Boolean = adapter?.isEnabled == true

    fun start(): Boolean {
        if (isRunning) { DiagLog.w(tag, "start ignored: already running"); return true }
        val a = adapter
        if (a == null) { DiagLog.e(tag, "no Bluetooth adapter on this phone"); pushStatus(); return false }
        if (!a.isEnabled) { DiagLog.e(tag, "Bluetooth is OFF - turn it on and press Start again"); pushStatus(); return false }
        DiagLog.i(tag, "starting node id=" + identity.idHex + " name=" + identity.displayName)

        server = GattServerNode(context, identity) { pkt, addr -> onPacketReceived(pkt, addr) }
        val srvOk = server!!.start()
        advertiser = BleAdvertiser(a, identity)
        val advOk = advertiser!!.start()
        scanner = BleScanner(a) { peers -> onScannerPeers(peers) }
        val scanOk = scanner!!.start()
        sender = BleSender(context, a)
        isRunning = true
        queue.start()
        DiagLog.i(tag, "node started: server=" + srvOk + " advertise=" + advOk + " scan=" + scanOk)
        main.postDelayed({ pushStatus() }, 1500)
        listener.onPeers(peers())
        pushStatus()
        return srvOk && scanOk
    }

    fun stop() {
        if (!isRunning) return
        queue.stop()
        scanner?.stop(); scanner = null
        advertiser?.stop(); advertiser = null
        server?.stop(); server = null
        sender = null
        isRunning = false
        visible = emptyList()
        lastVisibleIds = emptySet()
        DiagLog.i(tag, "node stopped")
        listener.onPeers(peers())
        pushStatus()
    }

    private fun onScannerPeers(seen: List<Peer>) {
        visible = seen
        for (p in seen) store.rememberPeer(p.shortId, p.label, p.address, p.lastSeen)
        val ids = seen.map { it.shortId }.toSet()
        val appeared = seen.filter { it.shortId !in lastVisibleIds }
        lastVisibleIds = ids
        val merged = peers()
        listener.onPeers(merged)
        for (p in appeared) queue.onPeerAppeared(p)
        queue.onPeersChanged(merged)
        pushStatus()
    }

    /** Visible peers first (with RSSI), then known peers that are not in range. */
    fun peers(): List<Peer> {
        val vis = visible
        val visIds = vis.map { it.shortId }.toSet()
        val known = store.knownPeers()
            .filter { it.shortId !in visIds }
            .map { Peer(it.shortId, it.lastAddress, 0, it.lastSeen, inRange = false) }
        return vis + known
    }

    /** Milestone 2A: never sends directly; always goes through the queue. */
    fun sendText(peer: Peer, text: String) {
        if (!isRunning) { DiagLog.e(tag, "cannot queue: node not running"); return }
        queue.enqueue(peer.shortId, peer.label, text)
    }

    /** Returns true if stored (new), false if duplicate. Called on a binder thread. */
    private fun onPacketReceived(pkt: Packet, address: String): Boolean {
        val senderShort = pkt.senderIdHex.substring(0, Identity.SHORT_ID_LEN * 2)
        val fresh = store.insert(
            StoredMessage(0, pkt.msgIdHex, "in", senderShort, "prok-" + senderShort, pkt.text, pkt.timestamp, MsgStatus.RECEIVED)
        )
        if (!fresh) DiagLog.w(tag, "duplicate message " + pkt.msgIdHex + " from prok-" + senderShort + " ignored (receipt says DUPLICATE)")
        main.post { listener.onMessagesChanged(); pushStatus("received from prok-" + senderShort) }
        return fresh
    }

    fun statusLine(extra: String? = null): String {
        val bt = if (adapter == null) "no BT" else if (adapter.isEnabled) "BT on" else "BT OFF"
        val q = "queue " + store.pendingCount() + (if (queue.inFlightMsg() != null) " (1 sending)" else "")
        if (!isRunning) return bt + " | node stopped | " + q
        val sb = StringBuilder(bt)
        sb.append(" | server ").append(if (server?.isReady == true) "ready" else "not ready")
        sb.append(" | adv ").append(if (advertiser?.isAdvertising == true) "on" else "OFF")
        sb.append(" | scan ").append(if (scanner?.isScanning == true) "on" else "OFF")
        sb.append(" | peers ").append(visible.size)
        sb.append(" | ").append(q)
        if (extra != null) sb.append(" | ").append(extra)
        return sb.toString()
    }

    private fun pushStatus(extra: String? = null) {
        val line = statusLine(extra)
        main.post { listener.onStatus(line) }
    }
}
