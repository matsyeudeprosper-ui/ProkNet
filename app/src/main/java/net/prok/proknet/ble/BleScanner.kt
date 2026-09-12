package net.prok.proknet.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Identity
import net.prok.proknet.core.toHex

/**
 * Discovers nearby ProkNet phones by scanning for the ProkNet service UUID.
 * Peers are keyed by their short ID (from the scan response), not by MAC
 * address, because Android randomises BLE addresses and rotates them.
 */
class BleScanner(
    private val adapter: BluetoothAdapter,
    private val onPeersChanged: (List<Peer>) -> Unit,
) {
    private val tag = "SCAN"
    private val main = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private val peers = LinkedHashMap<String, Peer>()
    private var notifyPending = false
    @Volatile var isScanning = false
        private set

    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onResult(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { onResult(it) } }
        override fun onScanFailed(errorCode: Int) {
            isScanning = false
            DiagLog.e(tag, "scan FAILED: " + errName(errorCode))
        }
    }

    private val expiry = object : Runnable {
        override fun run() {
            val now = System.currentTimeMillis()
            var changed = false
            synchronized(peers) {
                val it = peers.values.iterator()
                while (it.hasNext()) {
                    val p = it.next()
                    if (now - p.lastSeen > BleConstants.PEER_EXPIRY_MS) {
                        DiagLog.i(tag, "peer " + p.label + " expired (not seen for " + ((now - p.lastSeen) / 1000) + "s)")
                        it.remove(); changed = true
                    }
                }
            }
            if (changed) notifyPeers()
            if (isScanning) main.postDelayed(this, 5000)
        }
    }

    private fun onResult(result: ScanResult) {
        val address = result.device?.address ?: return
        val record = result.scanRecord
        val mfg = record?.getManufacturerSpecificData(BleConstants.MANUFACTURER_ID)
        val shortId: String = if (mfg != null && mfg.size >= 1 + Identity.SHORT_ID_LEN &&
            (mfg[0].toInt() and 0xFF) == BleConstants.ADV_VERSION
        ) {
            mfg.copyOfRange(1, 1 + Identity.SHORT_ID_LEN).toHex()
        } else {
            // Service UUID matched but no scan response (yet): show by address so it can still be used.
            "?" + address.replace(":", "").takeLast(6).lowercase()
        }
        val now = System.currentTimeMillis()
        var isNew = false
        synchronized(peers) {
            val p = peers[shortId]
            if (p == null) {
                peers[shortId] = Peer(shortId, address, result.rssi, now); isNew = true
            } else {
                if (p.address != address) DiagLog.i(tag, "peer " + p.label + " address changed " + p.address + " -> " + address)
                p.address = address; p.rssi = result.rssi; p.lastSeen = now
            }
        }
        if (isNew) DiagLog.i(tag, "NEW peer prok-" + shortId + " addr=" + address + " rssi=" + result.rssi +
            (if (mfg == null) " (no scan response)" else ""))
        notifyPeers()
    }

    /** Coalesce UI updates to at most ~2 per second. */
    private fun notifyPeers() {
        synchronized(this) {
            if (notifyPending) return
            notifyPending = true
        }
        main.postDelayed({
            synchronized(this) { notifyPending = false }
            onPeersChanged(snapshot())
        }, 500)
    }

    fun snapshot(): List<Peer> = synchronized(peers) { peers.values.sortedByDescending { it.rssi } }

    fun start(): Boolean {
        val s = adapter.bluetoothLeScanner
        if (s == null) { DiagLog.e(tag, "bluetoothLeScanner == null (is Bluetooth on?)"); return false }
        scanner = s
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID)).build())
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .setMatchMode(ScanSettings.MATCH_MODE_AGGRESSIVE)
            .setNumOfMatches(ScanSettings.MATCH_NUM_MAX_ADVERTISEMENT)
            .setReportDelay(0)
            .build()
        return try {
            s.startScan(filters, settings, callback)
            isScanning = true
            main.postDelayed(expiry, 5000)
            DiagLog.i(tag, "scan started (filter=ProkNet service, LOW_LATENCY)")
            true
        } catch (e: SecurityException) {
            DiagLog.e(tag, "missing BLUETOOTH_SCAN / location permission", e); false
        } catch (e: Exception) {
            DiagLog.e(tag, "startScan threw", e); false
        }
    }

    fun stop() {
        try { scanner?.stopScan(callback) } catch (e: Exception) { DiagLog.w(tag, "stopScan: " + e) }
        isScanning = false
        main.removeCallbacks(expiry)
        synchronized(peers) { peers.clear() }
        onPeersChanged(emptyList())
        DiagLog.i(tag, "scan stopped")
    }

    private fun errName(code: Int) = when (code) {
        ScanCallback.SCAN_FAILED_ALREADY_STARTED -> "ALREADY_STARTED(1)"
        ScanCallback.SCAN_FAILED_APPLICATION_REGISTRATION_FAILED -> "APP_REGISTRATION_FAILED(2)"
        ScanCallback.SCAN_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR(3)"
        ScanCallback.SCAN_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED(4)"
        5 -> "OUT_OF_HARDWARE_RESOURCES(5)"
        6 -> "SCANNING_TOO_FREQUENTLY(6)"
        else -> "code " + code
    }
}
