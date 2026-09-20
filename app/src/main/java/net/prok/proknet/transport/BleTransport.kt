package net.prok.proknet.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import net.prok.proknet.ble.BleAdvertiser
import net.prok.proknet.ble.BleScanner
import net.prok.proknet.ble.BleSender
import net.prok.proknet.ble.GattServerNode
import net.prok.proknet.ble.Peer
import net.prok.proknet.core.BleHealth
import net.prok.proknet.core.BleLifecycle
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
    private val main = Handler(Looper.getMainLooper())
    /** v0.13.3: which generation every asynchronous piece belongs to. Booleans alone lied after an adapter restart. */
    @Volatile var lifecycle = BleLifecycle.State(generation = 0, bluetoothOn = false, phase = BleLifecycle.Phase.DOWN)
        private set
    /** Told when the stack is rebuilt, so pending control traffic can retry at once. */
    @Volatile var onGenerationChanged: ((Int) -> Unit)? = null
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
     * v0.13.3: ONE rebuild path, and it rebuilds everything.
     *
     * The old recovery recreated only the scanner and the advertiser, and
     * touched the GATT server only `if (server?.isReady != true)` — a Boolean
     * that stayed true forever after Android destroyed the service on an
     * adapter restart. The phones showed the result: `server ready, adv on`
     * on one side, `peer has no ProkNet service (services=2)` on the other.
     *
     * Now every recovery bumps the generation and reconstructs the stack in
     * order: close everything, open the GATT server, add the ProkNet service,
     * wait for Android to confirm it, and only then advertise. The scanner may
     * start at once: it does not depend on our service.
     */
    fun recoverRadio(why: String): Boolean = rebuild(why)

    fun rebuild(why: String): Boolean {
        if (!isRunning) return false
        val a = adapter ?: return false
        val l = listener ?: return false
        if (!a.isEnabled) { lifecycle = BleLifecycle.bluetoothOff(lifecycle); DiagLog.w(tag, "cannot rebuild the BLE stack: Bluetooth is off"); return false }
        val was = lifecycle.generation
        lifecycle = BleLifecycle.rebuild(lifecycle.copy(bluetoothOn = true), System.currentTimeMillis(), why)
        val gen = lifecycle.generation
        lastRecoveryWhy = why
        DiagLog.w(tag, "BLE generation changed " + was + " -> " + gen + " because " + why + ": rebuilding the whole control plane")
        // 1. everything old goes, whatever it claims about itself
        try { scanner?.stop() } catch (e: Exception) { DiagLog.w(tag, "stop scan: " + e) }
        scanner = null
        try { advertiser?.stop() } catch (e: Exception) { DiagLog.w(tag, "stop advertising: " + e) }
        advertiser = null
        try { server?.stop() } catch (e: Exception) { DiagLog.w(tag, "close gatt server: " + e) }
        server = null
        identityFetched.clear()
        // 2. the GATT server and the ProkNet service
        val srv = GattServerNode(context, identityRecord, gen, { g, ok -> main.post { onServiceAdded(g, ok) } }) { pkt -> l.onFrame(name, null, pkt.encode()) }
        server = srv
        val opened = srv.start()
        DiagLog.i(tag, "GATT server recreated (generation " + gen + "): opened=" + opened)
        lifecycle = if (opened) BleLifecycle.serverOpened(lifecycle, System.currentTimeMillis())
            else BleLifecycle.serviceAdded(lifecycle, gen, false, System.currentTimeMillis())
        // 3. the scanner needs nothing from us
        val sc = BleScanner(a) { peers -> visible = peers; l.onPeersChanged(peers); l.onLinkState(name, linkState()) }
        scanner = sc
        val scanOk = sc.start()
        lifecycle = BleLifecycle.scanStarted(lifecycle, gen, scanOk)
        DiagLog.i(tag, "scan restarted: " + scanOk)
        // 4. advertising waits for onServiceAdded; see onServiceAdded()
        recoveries++
        lastRecoveryAt = System.currentTimeMillis()
        startedAt = lastRecoveryAt
        gattTimeouts = 0
        onGenerationChanged?.invoke(gen)
        l.onLinkState(name, linkState())
        return opened || scanOk
    }

    /** Android answered. Only now may ProkNet claim to be reachable. */
    private fun onServiceAdded(generation: Int, ok: Boolean) {
        if (BleLifecycle.isStale(lifecycle, generation)) { DiagLog.i(tag, "late onServiceAdded from generation " + generation + " ignored (now " + lifecycle.generation + ")"); return }
        lifecycle = BleLifecycle.serviceAdded(lifecycle, generation, ok, System.currentTimeMillis())
        if (!ok) { DiagLog.e(tag, "the ProkNet GATT service was NOT added: ProkNet will not advertise a control plane it does not have"); return }
        DiagLog.i(tag, "ProkNet service added under generation " + generation)
        startAdvertising(generation)
    }

    private fun startAdvertising(generation: Int) {
        val a = adapter ?: return
        if (!BleLifecycle.mayAdvertise(lifecycle)) { DiagLog.w(tag, "refusing to advertise: no ProkNet GATT service"); return }
        try { advertiser?.stop() } catch (_: Exception) {}
        val adv = BleAdvertiser(a, identity)
        adv.setCapabilities(lastFlags, lastPrice)      // whatever SELL advertises must come back with it
        advertiser = adv
        val ok = adv.start()
        lifecycle = BleLifecycle.advertisingStarted(lifecycle, generation, ok)
        DiagLog.i(tag, "advertising started: " + ok + " (generation " + generation + ", flags " + lastFlags + ", price " + lastPrice + ")")
        listener?.onLinkState(name, linkState())
    }

    /** The adapter went away. Nothing Android owned survives it. */
    fun onBluetoothOff() {
        if (!lifecycle.bluetoothOn) return
        lifecycle = BleLifecycle.bluetoothOff(lifecycle)
        DiagLog.w(tag, "Bluetooth is off: the GATT server, the service, the advert and the scan are all invalid now")
    }

    /** The adapter came back: a full reconstruction, never a partial restart. */
    fun onBluetoothReturned(): Boolean {
        DiagLog.i(tag, "Bluetooth returned: rebuilding the BLE control plane from nothing")
        return rebuild("Bluetooth returned")
    }

    /**
     * The invariant, checked by the watchdog: if ProkNet advertises, its GATT
     * service must really be there. If it is not, repair it at once.
     */
    fun checkInvariant(): Boolean {
        if (!isRunning || !isBluetoothOn) return true
        val why = BleLifecycle.unhealthyReason(lifecycle) ?: return true
        if (BleLifecycle.advertising(lifecycle) && !BleLifecycle.serviceReady(lifecycle)) {
            DiagLog.e(tag, why + ": rebuilding now")
            rebuild("advertising without a GATT service")
            return false
        }
        // a service that never came back: bounded retry
        if (!BleLifecycle.serviceReady(lifecycle) && BleLifecycle.mayTryService(lifecycle, System.currentTimeMillis())) {
            DiagLog.w(tag, why + ": retrying the GATT service")
            rebuild("the GATT service was missing")
            return false
        }
        return true
    }

    val controlPlaneHealthy: Boolean get() = BleLifecycle.controlPlaneHealthy(lifecycle)
    val generation: Int get() = lifecycle.generation

    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true
    /** v0.13.3: ready means "confirmed under the generation running now", never "was true once". */
    val serverReady: Boolean get() = BleLifecycle.serviceReady(lifecycle)
    val advertising: Boolean get() = advertiser?.isAdvertising == true && BleLifecycle.advertising(lifecycle)
    val scanning: Boolean get() = scanner?.isScanning == true && BleLifecycle.scanning(lifecycle)
    override val bytesSent: Long get() = sender?.bytesSent ?: 0L
    override val bytesReceived: Long get() = server?.bytesReceived ?: 0L

    override fun start(listener: TransportListener): Boolean {
        if (isRunning) return true
        val a = adapter ?: run { DiagLog.e(tag, "no Bluetooth adapter"); return false }
        if (!a.isEnabled) { DiagLog.e(tag, "Bluetooth is OFF"); return false }
        this.listener = listener
        sender = BleSender(context, a)
        isRunning = true
        lifecycle = BleLifecycle.State(generation = 0, bluetoothOn = true, phase = BleLifecycle.Phase.DOWN)
        val ok = rebuild("node started")
        DiagLog.i(tag, "transport started: " + BleLifecycle.describe(lifecycle).replace("\n", " "))
        listener.onLinkState(name, linkState())
        return ok
    }

    override fun stop() {
        if (!isRunning) return
        scanner?.stop(); scanner = null
        advertiser?.stop(); advertiser = null
        server?.stop(); server = null
        sender = null
        visible = emptyList()
        isRunning = false
        lifecycle = BleLifecycle.bluetoothOff(lifecycle)
        listener?.onLinkState(name, linkState())
        DiagLog.i(tag, "transport stopped")
    }

    fun visiblePeers(): List<Peer> = visible

    /** v0.6: capability bits in the scan response (bit0 = providing Internet). */
    fun setCapabilities(flags: Int, price: Int = 0) {
        lastFlags = flags; lastPrice = price
        // a phone with no live GATT service must not advertise an offer it cannot serve
        if (BleLifecycle.mayAdvertise(lifecycle)) advertiser?.setCapabilities(flags, price)
        else DiagLog.w(tag, "not advertising capabilities: the ProkNet GATT service is not ready")
    }

    private fun peer(short: String): Peer? = visible.firstOrNull { it.shortId == short && it.inRange && it.hasId }

    override fun canReach(peerShort: String): Boolean = isRunning && peer(peerShort) != null

    override fun linkState(): String =
        if (!isRunning) "off" else "server " + (if (serverReady) "ready" else "not ready") + ", adv " + (if (advertising) "on" else "OFF") +
            ", scan " + (if (scanning) "on" else "OFF") + ", peers " + visible.size +
            (if (lastScanResultAt > 0) ", last result " + ((System.currentTimeMillis() - lastScanResultAt) / 1000) + "s ago" else ", no result yet") +
            (if (recoveries > 0) ", " + recoveries + " recovery" else "") +
            (if (isRunning && !controlPlaneHealthy) " [" + (BleLifecycle.unhealthyReason(lifecycle) ?: "") + "]" else "")

    /** v0.13.3: the control plane, generation by generation. */
    fun controlPlaneLine(): String = BleLifecycle.describe(lifecycle)

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
