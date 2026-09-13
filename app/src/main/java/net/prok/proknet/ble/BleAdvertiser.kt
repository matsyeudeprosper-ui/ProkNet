package net.prok.proknet.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.os.ParcelUuid
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Identity

/**
 * Makes this phone visible as a ProkNet device.
 *
 * Advertisement packet : flags + 128-bit ProkNet service UUID (fits in 31 bytes).
 * Scan response packet : manufacturer data 0xFFFF = [ADV_VERSION][shortId x4].
 */
class BleAdvertiser(private val adapter: BluetoothAdapter, private val identity: Identity) {
    private val tag = "ADV"
    private var advertiser: BluetoothLeAdvertiser? = null
    @Volatile var isAdvertising = false
        private set

    private val callback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            isAdvertising = true
            DiagLog.i(tag, "advertising started (mode=" + settingsInEffect.mode +
                " tx=" + settingsInEffect.txPowerLevel + " connectable=" + settingsInEffect.isConnectable + ")")
        }

        override fun onStartFailure(errorCode: Int) {
            isAdvertising = false
            DiagLog.e(tag, "advertising FAILED: " + errName(errorCode))
            if (errorCode == AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE && !shortPayload) {
                // This chipset refuses the 16-byte full-ID scan response: fall back to the v1 4-byte short ID.
                // Peers can still address us (short-ID addressing) and relay for us.
                DiagLog.w(tag, "retrying with the short-ID scan response (adv v1)")
                shortPayload = true
                startInternal()
            }
        }
    }

    private var shortPayload = false
    /** v0.6 capability bits advertised in the scan response (bit0 = providing Internet). */
    @Volatile var capabilityFlags = 0
        private set

    @Volatile var pricePerMb = 0
        private set

    /** Change the advertised capability bits and price (restarts advertising). */
    fun setCapabilities(flags: Int, price: Int = pricePerMb) {
        val p = price.coerceIn(0, 65535)
        if (flags == capabilityFlags && p == pricePerMb) return
        capabilityFlags = flags; pricePerMb = p
        if (isAdvertising) { stop(); startInternal() }
    }

    fun start(): Boolean {
        shortPayload = false
        return startInternal()
    }

    private fun startInternal(): Boolean {
        val adv = adapter.bluetoothLeAdvertiser
        if (adv == null) {
            DiagLog.e(tag, "this phone does not support BLE advertising (bluetoothLeAdvertiser == null). " +
                "It can still discover others and send, but others cannot discover it.")
            return false
        }
        advertiser = adv
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .setIncludeTxPowerLevel(false)
            .addServiceUuid(ParcelUuid(BleConstants.SERVICE_UUID))
            .build()
        // v2 (2C1): the scan response carries the FULL 16-byte ID so peers can address
        // packets to us without a GATT read. 2+2+1+16 = 21 bytes, fits in 31.
        val payload: ByteArray
        if (shortPayload) {
            payload = ByteArray(1 + Identity.SHORT_ID_LEN)
            payload[0] = BleConstants.ADV_VERSION_SHORT.toByte()
            System.arraycopy(identity.shortIdBytes, 0, payload, 1, Identity.SHORT_ID_LEN)
        } else {
            // v0.7: [2][id 16][flags 1][price u16] = 20 bytes payload, 24 with headers, fits 31.
            payload = ByteArray(1 + Identity.ID_LEN + 1 + 2)
            payload[0] = BleConstants.ADV_VERSION.toByte()
            System.arraycopy(identity.idBytes, 0, payload, 1, Identity.ID_LEN)
            payload[1 + Identity.ID_LEN] = capabilityFlags.toByte()   // Market.flags(): sell/relay/validated/upstream
            payload[2 + Identity.ID_LEN] = (pricePerMb ushr 8).toByte(); payload[3 + Identity.ID_LEN] = pricePerMb.toByte()
        }
        val scanResponse = AdvertiseData.Builder()
            .setIncludeDeviceName(false)
            .addManufacturerData(BleConstants.MANUFACTURER_ID, payload)
            .build()
        return try {
            adv.startAdvertising(settings, data, scanResponse, callback)
            DiagLog.i(tag, "startAdvertising requested, shortId=" + identity.shortIdHex)
            true
        } catch (e: SecurityException) {
            DiagLog.e(tag, "missing BLUETOOTH_ADVERTISE permission", e); false
        } catch (e: Exception) {
            DiagLog.e(tag, "startAdvertising threw", e); false
        }
    }

    fun stop() {
        try { advertiser?.stopAdvertising(callback) } catch (e: Exception) { DiagLog.w(tag, "stopAdvertising: " + e) }
        isAdvertising = false
        DiagLog.i(tag, "advertising stopped")
    }

    private fun errName(code: Int) = when (code) {
        AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE -> "DATA_TOO_LARGE(1)"
        AdvertiseCallback.ADVERTISE_FAILED_TOO_MANY_ADVERTISERS -> "TOO_MANY_ADVERTISERS(2)"
        AdvertiseCallback.ADVERTISE_FAILED_ALREADY_STARTED -> "ALREADY_STARTED(3)"
        AdvertiseCallback.ADVERTISE_FAILED_INTERNAL_ERROR -> "INTERNAL_ERROR(4)"
        AdvertiseCallback.ADVERTISE_FAILED_FEATURE_UNSUPPORTED -> "FEATURE_UNSUPPORTED(5)"
        else -> "code " + code
    }
}
