package net.prok.proknet.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import net.prok.proknet.ble.BleAdvertiser
import net.prok.proknet.ble.BleScanner
import net.prok.proknet.ble.BleSender
import net.prok.proknet.ble.GattServerNode
import net.prok.proknet.ble.Peer
import net.prok.proknet.core.BleHealth
import net.prok.proknet.core.DeliveryResult
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Identity
import net.prok.proknet.core.Routing
import net.prok.proknet.core.Wire

/**
 * BLE as a ProkNet transport: discovery (advertise + scan), control-sized
 * frames over GATT writes with receipts, identity records over a GATT read.
 * Not bulk capable (a few hundred bytes per second), but always available
 * and needs no user dialog.
 */
class BleTransport(
    private val context: Context,
    private val identity: Identity,
    private val identityRecord: () -> ByteArray,
) : Transport {
    private val tag = "BLE"
    override val name = Routing.TRANSPORT_BLE
    override val bulkCapable = false

    private val adapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
    private var server: GattServerNode? = null
    private var advertiser: BleAdvertiser? = null
    private var scanner: BleScanner? = null
    private var sender: BleSender? = null
    private var listener: TransportListener? = null
    @Volatile override var isRunning = false
        private set
    @Volatile private var visible: List<Peer> = emptyList()
    private val identityFetched = HashSet<String>()

    // ---- v0.9.10: what the radio is really doing --------------------------------------------------
    @Volatile var startedAt = 0L
        private set
    @Volatile var gattTimeouts = 0
        private set
    @Volatile var lastGattOkAt = 0L
        private set
    @Volatile var recoveries = 0
        private set
    @Volatile var lastRecoveryAt = 0L
        private set
    @Volatile var lastRecoveryWhy = ""
        private set
    private var lastFlags = 0
    private var lastPrice = 0

    val lastScanResultAt: Long get() = scanner?.lastResultAt ?: 0L
    val scanResults: Long get() = scanner?.results ?: 0L
    val scanFailedAt: Long get() = scanner?.failedAt ?: 0L
    val scanFailure: String get() = scanner?.lastFailure ?: ""
    val advertiseFailedAt: Long get() = advertiser?.failedAt ?: 0L
    val advertiseFailure: String get() = advertiser?.lastFailure ?: ""
    val advertiseOkAt: Long get() = advertiser?.startedOkAt ?: 0L

    /** Called by the node when a GATT operation to a known peer finished. */
    fun noteGatt(ok: Boolean) {
        if (ok) { gattTimeouts = 0; lastGattOkAt = System.currentTimeMillis() } else gattTimeouts++
    }

    /**
     * v0.9.10: stop and recreate ONLY the scanner and the advertiser. The GATT
     * server, the identity, the queue and any live link are left alone; the
     * server is restarted only if it is itself not ready.
     */
    fun recoverRadio(why: String): Boolean {
        if (!isRunning) return false
        val a = adapter ?: return false
        if (!a.isEnabled) { DiagLog.w(tag, "cannot recover the radio: Bluetooth is off"); return false }
        val l = listener ?: return false
        lastRecoveryWhy = why
        DiagLog.w(tag, "BLE radio recovery started: " + why)
        try { scanner?.stop() } catch (e: Exception) { DiagLog.w(tag, "stop scan: " + e) }
        scanner = null
        try { advertiser?.stop() } catch (e: Exception) { DiagLog.w(tag, "stop advertising: " + e) }
        advertiser = null
        if (server?.isReady != true) {
            DiagLog.w(tag, "the GATT server is not ready either: restarting it too")
            try { server?.stop() } catch (_: Exception) {}
            server = GattServerNode(context, identityRecord) { pkt -> l.onFrame(name, null, pkt.encode()) }
            DiagLog.i(tag, "GATT server restarted: " + server!!.start())
        }
        val adv = BleAdvertiser(a, identity)
        adv.setCapabilities(lastFlags, lastPrice)      // whatever SELL advertises must come back with it
        advertiser = adv
        val advOk = adv.start()
        DiagLog.i(tag, "advertising restarted: " + advOk + " (flags " + lastFlags + ", price " + lastPrice + ")")
        val sc = BleScanner(a) { peers -> visible = peers; l.onPeersChanged(peers); l.onLinkState(name, linkState()) }
        scanner = sc
        val scanOk = sc.start()
        DiagLog.i(tag, "scan restarted: " + scanOk)
        recoveries++
        lastRecoveryAt = System.currentTimeMillis()
        startedAt = lastRecoveryAt
        gattTimeouts = 0
        identityFetched.clear()
        DiagLog.i(tag, "BLE recovery complete (#" + recoveries + "): advertising " + advOk + ", scan " + scanOk)
        l.onLinkState(name, linkState())
        return advOk || scanOk
    }

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true
    val serverReady: Boolean get() = server?.isReady == true
    val advertising: Boolean get() = advertiser?.isAdvertising == true
    val scanning: Boolean get() = scanner?.isScanning == true
    override val bytesSent: Long get() = sender?.bytesSent ?: 0L
    override val bytesReceived: Long get() = server?.bytesReceived ?: 0L

    override fun start(listener: TransportListener): Boolean {
        if (isRunning) return true
        val a = adapter ?: run { DiagLog.e(tag, "no Bluetooth adapter"); return false }
        if (!a.isEnabled) { DiagLog.e(tag, "Bluetooth is OFF"); return false }
        this.listener = listener
        server = GattServerNode(context, identityRecord) { pkt -> listener.onFrame(name, null, pkt.encode()) }
        val srvOk = server!!.start()
        advertiser = BleAdvertiser(a, identity)
        val advOk = advertiser!!.start()
        scanner = BleScanner(a) { peers -> visible = peers; listener.onPeersChanged(peers); listener.onLinkState(name, linkState()) }
        val scanOk = scanner!!.start()
        sender = BleSender(context, a)
        isRunning = true
        startedAt = System.currentTimeMillis()
        gattTimeouts = 0
        identityFetched.clear()
        DiagLog.i(tag, "transport started: server=" + srvOk + " advertise=" + advOk + " scan=" + scanOk)
        listener.onLinkState(name, linkState())
        return srvOk && scanOk
    }

    override fun stop() {
        if (!isRunning) return
        scanner?.stop(); scanner = null
        advertiser?.stop(); advertiser = null
        server?.stop(); server = null
        sender = null
        visible = emptyList()
        isRunning = false
        listener?.onLinkState(name, linkState())
        DiagLog.i(tag, "transport stopped")
    }

    fun visiblePeers(): List<Peer> = visible

    /** v0.6: capability bits in the scan response (bit0 = providing Internet). */
    fun setCapabilities(flags: Int, price: Int = 0) { lastFlags = flags; lastPrice = price; advertiser?.setCapabilities(flags, price) }

    private fun peer(short: String): Peer? = visible.firstOrNull { it.shortId == short && it.inRange && it.hasId }

    override fun canReach(peerShort: String): Boolean = isRunning && peer(peerShort) != null

    override fun linkState(): String =
        if (!isRunning) "off" else "server " + (if (serverReady) "ready" else "not ready") + ", adv " + (if (advertising) "on" else "OFF") +
            ", scan " + (if (scanning) "on" else "OFF") + ", peers " + visible.size +
            (if (lastScanResultAt > 0) ", last result " + ((System.currentTimeMillis() - lastScanResultAt) / 1000) + "s ago" else ", no result yet") +
            (if (recoveries > 0) ", " + recoveries + " recovery" else "")

    /** v0.9.10: everything BleHealth needs, plus what a human reads in the diagnostic. */
    fun healthLine(): String =
        "adv " + (if (advertising) "on since " + ((System.currentTimeMillis() - advertiseOkAt) / 1000) + "s" else "OFF") +
            (if (advertiseFailure.isNotEmpty()) " [last failure " + advertiseFailure + "]" else "") +
            " | scan " + (if (scanning) "on" else "OFF") + ", " + scanResults + " results" +
            (if (lastScanResultAt > 0) ", last " + ((System.currentTimeMillis() - lastScanResultAt) / 1000) + "s ago" else ", none yet") +
            (if (scanFailure.isNotEmpty()) " [last failure " + scanFailure + "]" else "") +
            " | gatt timeouts " + gattTimeouts + (if (lastGattOkAt > 0) ", last ok " + ((System.currentTimeMillis() - lastGattOkAt) / 1000) + "s ago" else "") +
            " | recoveries " + recoveries + (if (lastRecoveryWhy.isNotEmpty()) " (last: " + lastRecoveryWhy + ")" else "")

    override fun sendBatch(peerShort: String, frames: List<Frame>, onEach: (Int, DeliveryResult, String) -> Boolean, onDone: () -> Unit) {
        val s = sender; val p = peer(peerShort)
        if (s == null || p == null) {
            if (frames.isNotEmpty()) onEach(0, DeliveryResult.TRANSPORT_FAILED, "prok-" + peerShort + " not in BLE range")
            onDone(); return
        }
        // Learn the peer's key on the same connection if we do not have it yet (free ride).
        val wantIdentity = identityFetched.add(peerShort)
        s.sendBatch(p, frames, onEach, onDone, readIdentity = wantIdentity, onIdentity = { rec -> handleIdentity(rec, p) })
    }

    /** Read the peer's identity record (ID, public key, name) over a dedicated connection. */
    fun fetchIdentity(peerShort: String, cb: (Boolean) -> Unit) {
        val s = sender; val p = peer(peerShort)
        if (s == null || p == null) { cb(false); return }
        identityFetched.add(peerShort)
        s.readIdentity(p) { rec -> cb(handleIdentity(rec, p)) }
    }

    private fun handleIdentity(bytes: ByteArray?, p: Peer): Boolean {
        val rec = Wire.parseIdentityRecord(bytes)
        if (rec == null) {
            if (bytes != null) DiagLog.w(tag, "identity record from " + p.label + " rejected (malformed or ID does not match key)")
            identityFetched.remove(p.shortId) // try again next time
            return false
        }
        if (rec.shortId != p.shortId) { DiagLog.w(tag, "identity record ID " + rec.shortId + " does not match advertised " + p.shortId + " - ignored"); return false }
        listener?.onIdentity(name, rec.idHex, rec.pub, rec.name)
        return true
    }

    fun identityKnownThisSession(peerShort: String) = identityFetched.contains(peerShort)
}
