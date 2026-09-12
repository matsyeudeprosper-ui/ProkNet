package net.prok.proknet.ble

import java.util.UUID

/** All BLE identifiers used by ProkNet v0.1 live here. */
object BleConstants {
    /** Primary ProkNet GATT service. Advertised so peers can filter scans by it. */
    val SERVICE_UUID: UUID = UUID.fromString("7a0c0001-9b1e-4c5a-8d7f-0b6e2f3a4c5d")

    /** Readable: full device ID hex + "|" + display name, UTF-8. */
    val CHAR_IDENTITY_UUID: UUID = UUID.fromString("7a0c0002-9b1e-4c5a-8d7f-0b6e2f3a4c5d")

    /** Writable: one encoded [net.prok.proknet.core.Packet] per write. */
    val CHAR_INBOX_UUID: UUID = UUID.fromString("7a0c0003-9b1e-4c5a-8d7f-0b6e2f3a4c5d")

    /**
     * Manufacturer-specific data company ID. 0xFFFF is reserved by the Bluetooth
     * SIG for internal use / testing, which is exactly what a lab build is.
     * The scan-response payload is: [advVersion:1][shortId:4].
     */
    const val MANUFACTURER_ID = 0xFFFF
    const val ADV_VERSION = 1

    /** Largest MTU Android allows; a 512-byte packet fits in one write when granted. */
    const val REQUEST_MTU = 517

    const val PEER_EXPIRY_MS = 25_000L
    const val SEND_TIMEOUT_MS = 20_000L
    const val MTU_FALLBACK_MS = 3_000L
}

/** A nearby ProkNet device as seen by the scanner. */
class Peer(
    val shortId: String,
    @Volatile var address: String,
    @Volatile var rssi: Int,
    @Volatile var lastSeen: Long,
) {
    val label: String get() = "prok-" + shortId

    fun describe(): String {
        val age = (System.currentTimeMillis() - lastSeen) / 1000
        return label + "  rssi " + rssi + "  " + address + "  " + age + "s ago"
    }
}
