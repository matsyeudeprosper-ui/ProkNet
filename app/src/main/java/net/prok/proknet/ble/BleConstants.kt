package net.prok.proknet.ble

import java.util.UUID
import net.prok.proknet.core.Routing

/** All BLE identifiers and tuning values used by ProkNet live here. */
object BleConstants {
    /** Primary ProkNet GATT service. Advertised so peers can filter scans by it. */
    val SERVICE_UUID: UUID = UUID.fromString("7a0c0001-9b1e-4c5a-8d7f-0b6e2f3a4c5d")

    /** Readable: full device ID hex + "|" + display name, UTF-8. */
    val CHAR_IDENTITY_UUID: UUID = UUID.fromString("7a0c0002-9b1e-4c5a-8d7f-0b6e2f3a4c5d")

    /** Writable: one encoded [net.prok.proknet.core.Packet] per write. */
    val CHAR_INBOX_UUID: UUID = UUID.fromString("7a0c0003-9b1e-4c5a-8d7f-0b6e2f3a4c5d")

    /**
     * Readable: delivery receipt for the last packet THIS central wrote.
     * Value = [RECEIPT_VERSION][status][msgId x8]. Status codes live in Routing.
     */
    val CHAR_RECEIPT_UUID: UUID = UUID.fromString("7a0c0004-9b1e-4c5a-8d7f-0b6e2f3a4c5d")
    const val RECEIPT_VERSION = 1
    const val RECEIPT_REJECTED = Routing.RECEIPT_REJECTED
    const val RECEIPT_ACCEPTED = Routing.RECEIPT_ACCEPTED
    const val RECEIPT_DUPLICATE = Routing.RECEIPT_DUPLICATE
    const val RECEIPT_ACCEPTED_RELAY = Routing.RECEIPT_ACCEPTED_RELAY

    /**
     * Manufacturer-specific data company ID. 0xFFFF is reserved by the Bluetooth
     * SIG for internal use / testing, which is exactly what a lab build is.
     * Scan-response payload: v1 = [1][shortId x4]; v2 = [2][fullId x16].
     */
    const val MANUFACTURER_ID = 0xFFFF
    /**
     * v0.19.0: version 3 carries the rate in CENTIMES per MB (u16, up to 655.35 CFA/MB)
     * where version 2 carried whole CFA. The launch target is 0.25 CFA/MB, which whole
     * francs cannot express: a v2 advert rounded it UP to 1 CFA/MB (four times the
     * price). A v3 phone reads a v2 advert as price × 100; a v2 phone reads a v3 advert
     * wrongly, which is why every pilot phone updates together.
     */
    const val ADV_VERSION = 3
    const val ADV_VERSION_CFA = 2
    const val ADV_VERSION_SHORT = 1

    /** Largest MTU Android allows; a 512-byte packet fits in one write when granted. */
    const val REQUEST_MTU = 517

    const val PEER_EXPIRY_MS = 25_000L
    const val SEND_TIMEOUT_MS = 20_000L
    const val MTU_FALLBACK_MS = 3_000L

    // ---- delivery queue ----
    const val QUEUE_TTL_MS = Routing.QUEUE_TTL_MS
    const val QUEUE_TICK_MS = 10_000L
    const val BACKOFF_MAX_MS = Routing.BACKOFF_MAX_MS
    const val MAX_ATTEMPTS = Routing.MAX_ATTEMPTS
}

/** A ProkNet device: currently visible to the scanner, or known from before. */
const val CAP_INTERNET = 1

class Peer(
    val shortId: String,
    @Volatile var address: String,
    @Volatile var rssi: Int,
    @Volatile var lastSeen: Long,
    @Volatile var inRange: Boolean = true,
    /** Full 16-byte ID hex when the peer advertises v2; null for v1 peers. */
    @Volatile var fullId: String? = null,
    /** v0.6/0.7 capability bits from the scan response: Market.flags(). */
    @Volatile var capabilities: Int = 0,
    /** v0.7: advertised price in whole CFA per MB, rounded UP from the rate - for old screens only. */
    @Volatile var pricePerMb: Int = 0,
    /** v0.19.0: the advertised rate in centimes per MB - what the quote and the contract use. */
    @Volatile var rateCentimesPerMb: Int = 0,
) {
    val providesInternet: Boolean get() = capabilities and CAP_INTERNET != 0
    fun offer(): net.prok.proknet.core.Market.Offer = net.prok.proknet.core.Market.Offer(shortId, pricePerMb, capabilities, rssi, lastSeen, rateCentimesPerMb)
    val label: String get() = "prok-" + shortId

    /** False for devices seen without a scan response: they have no ProkNet ID yet and cannot be addressed. */
    val hasId: Boolean get() = !shortId.startsWith("?")

    fun describe(): String {
        val age = (System.currentTimeMillis() - lastSeen) / 1000
        val ageText = if (age < 60) age.toString() + "s" else if (age < 3600) (age / 60).toString() + "m" else (age / 3600).toString() + "h"
        val idNote = if (!hasId) "  (no ID yet)" else ""
        return if (inRange) label + idNote + "  rssi " + rssi + "  " + address + "  " + ageText + " ago"
        else label + "  NOT IN RANGE  last seen " + ageText + " ago"
    }
}
