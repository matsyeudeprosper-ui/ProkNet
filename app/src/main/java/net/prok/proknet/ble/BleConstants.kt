package net.prok.proknet.ble

import java.util.UUID

/** All BLE identifiers and tuning values used by ProkNet live here. */
object BleConstants {
    /** Primary ProkNet GATT service. Advertised so peers can filter scans by it. */
    val SERVICE_UUID: UUID = UUID.fromString("7a0c0001-9b1e-4c5a-8d7f-0b6e2f3a4c5d")

    /** Readable: full device ID hex + "|" + display name, UTF-8. */
    val CHAR_IDENTITY_UUID: UUID = UUID.fromString("7a0c0002-9b1e-4c5a-8d7f-0b6e2f3a4c5d")

    /** Writable: one encoded [net.prok.proknet.core.Packet] per write. */
    val CHAR_INBOX_UUID: UUID = UUID.fromString("7a0c0003-9b1e-4c5a-8d7f-0b6e2f3a4c5d")

    /**
     * Readable (milestone 2A): delivery receipt for the last packet THIS central wrote.
     * Value = [RECEIPT_VERSION][status][msgId x8]. The sender reads it right after
     * its write is acknowledged; only a matching msgId with status ACCEPTED or
     * DUPLICATE counts as delivered.
     */
    val CHAR_RECEIPT_UUID: UUID = UUID.fromString("7a0c0004-9b1e-4c5a-8d7f-0b6e2f3a4c5d")
    const val RECEIPT_VERSION = 1
    const val RECEIPT_REJECTED = 0
    const val RECEIPT_ACCEPTED = 1
    const val RECEIPT_DUPLICATE = 2

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

    // ---- delivery queue (milestone 2A) ----
    const val QUEUE_TTL_MS = 48L * 3600_000L       // pending longer than this -> expired
    const val QUEUE_TICK_MS = 10_000L              // periodic pump
    const val BACKOFF_BASE_MS = 5_000L             // wait after a failed attempt, doubles each time
    const val BACKOFF_MAX_MS = 60_000L
    const val MAX_ATTEMPTS = 50                    // then -> failed
}

/** Outcome of one delivery attempt, as seen by the sender. */
enum class DeliveryResult {
    DELIVERED,        // receipt: peer stored the message
    DUPLICATE,        // receipt: peer already had it (an earlier attempt got through) -> counts as delivered
    REJECTED,         // receipt: peer refused the packet -> failed, no retry
    NO_RECEIPT,       // write acked but receipt missing/mismatched -> retry later
    TRANSPORT_FAILED, // could not connect / write -> retry later
}

/** A ProkNet device: currently visible to the scanner, or known from before. */
class Peer(
    val shortId: String,
    @Volatile var address: String,
    @Volatile var rssi: Int,
    @Volatile var lastSeen: Long,
    @Volatile var inRange: Boolean = true,
) {
    val label: String get() = "prok-" + shortId

    fun describe(): String {
        val age = (System.currentTimeMillis() - lastSeen) / 1000
        val ageText = if (age < 60) age.toString() + "s" else if (age < 3600) (age / 60).toString() + "m" else (age / 3600).toString() + "h"
        return if (inRange) label + "  rssi " + rssi + "  " + address + "  " + ageText + " ago"
        else label + "  NOT IN RANGE  last seen " + ageText + " ago"
    }
}
