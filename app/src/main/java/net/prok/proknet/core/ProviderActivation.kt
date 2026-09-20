package net.prok.proknet.core

/**
 * v0.13: a phone that has Internet but is not sharing can be asked to share.
 * Opt-in, eligible, within the request's price, not busy, and told honestly
 * whether the request came from a phone nearby (BLE) or only from the zone
 * (the brain). Rate-limited, so one request never becomes a stream of
 * notifications.
 */
object ProviderActivation {

    data class Eligibility(
        val optIn: Boolean,
        val upstreamValidated: Boolean,
        val accessPath: BulkPlan.SellerAccessPath,
        val bluetoothOn: Boolean,
        val alreadySharing: Boolean,
        val busy: Boolean,
        val sellPriceCentimesPerMb: Int,
    )

    enum class Refusal { NOT_OPTED_IN, NO_INTERNET, NO_LOCAL_PATH, BLUETOOTH_OFF, BUSY, ABOVE_CEILING, REQUEST_NOT_OPEN, ALREADY_SHARING }

    /**
     * v0.13.2: the path this phone COULD offer right now, from the Internet it
     * actually has — not from the seller gateway, which is only started once the
     * user taps PARTAGER. Asking the gateway made a phone on a validated Freebox
     * answer NO_INTERNET while sharing was off, so no notification was ever
     * posted and the user could never tap PARTAGER.
     */
    fun potentialPath(upstreamType: Int, upstreamValidated: Boolean, bulkSupported: Boolean, bluetoothOn: Boolean): BulkPlan.SellerAccessPath =
        if (!upstreamValidated || upstreamType == Tunnel.UP_NONE) BulkPlan.SellerAccessPath.NONE
        else BulkPlan.sellerAccessPath(upstreamType == Tunnel.UP_WIFI, bulkSupported, bluetoothOn)

    /** Eligibility from the phone's real capability. [alreadySharing] is the only thing the gateway decides. */
    fun eligibility(optIn: Boolean, upstreamType: Int, upstreamValidated: Boolean, bulkSupported: Boolean, bluetoothOn: Boolean,
                    alreadySharing: Boolean, busy: Boolean, sellPriceCentimesPerMb: Int): Eligibility = Eligibility(
        optIn = optIn,
        upstreamValidated = upstreamValidated && upstreamType != Tunnel.UP_NONE,
        accessPath = potentialPath(upstreamType, upstreamValidated, bulkSupported, bluetoothOn),
        bluetoothOn = bluetoothOn, alreadySharing = alreadySharing, busy = busy, sellPriceCentimesPerMb = sellPriceCentimesPerMb)

    data class Opportunity(val requestId: String, val zone: String, val local: Boolean, val ceilingCentimesPerMb: Int, val expiresAt: Long) {
        val title: String get() = if (local) "Quelqu'un cherche Internet à proximité." else "Une demande Internet existe dans votre zone."
        val text: String get() = "Vous pouvez partager votre connexion et gagner des CFA."
    }

    fun refusal(e: Eligibility, r: NetRequest.Request, now: Long): Refusal? = when {
        !e.optIn -> Refusal.NOT_OPTED_IN
        !r.open || r.expired(now) -> Refusal.REQUEST_NOT_OPEN
        e.alreadySharing -> Refusal.ALREADY_SHARING
        !e.upstreamValidated -> Refusal.NO_INTERNET
        e.accessPath == BulkPlan.SellerAccessPath.NONE -> Refusal.NO_LOCAL_PATH
        e.accessPath == BulkPlan.SellerAccessPath.BLUETOOTH_BULK && !e.bluetoothOn -> Refusal.BLUETOOTH_OFF
        e.busy -> Refusal.BUSY
        r.ceiling != null && e.sellPriceCentimesPerMb > r.ceiling!! -> Refusal.ABOVE_CEILING
        else -> null
    }

    /** @param local true when the request arrived over BLE from a phone in range; false when it came from the brain. */
    fun opportunity(e: Eligibility, r: NetRequest.Request, now: Long, local: Boolean): Opportunity? =
        if (refusal(e, r, now) == null) Opportunity(r.id, r.zone, local, r.ceilingCentimesPerMb, r.expiresAt) else null

    // ---- the rate limit -------------------------------------------------------------------------------------

    const val PER_REQUEST_MS = 10 * 60_000L
    const val GLOBAL_MS = 2 * 60_000L

    data class Limiter(val lastByRequest: Map<String, Long> = emptyMap(), val lastGlobal: Long = 0L)

    fun allow(l: Limiter, requestId: String, now: Long): Boolean =
        (l.lastByRequest[requestId]?.let { now - it >= PER_REQUEST_MS } ?: true) && now - l.lastGlobal >= GLOBAL_MS

    fun noted(l: Limiter, requestId: String, now: Long): Limiter = Limiter(l.lastByRequest + (requestId to now), now)

    // ---- the availability heartbeat (what the brain may know; never a position) ------------------------------

    data class Availability(val zone: String, val potential: Boolean, val sharing: Boolean, val upstreamType: Int, val priceCentimesPerMb: Int, val busy: Boolean, val capable: Boolean)

    fun availability(e: Eligibility, zone: String, upstreamType: Int): Availability = Availability(
        zone, potential = e.optIn && e.upstreamValidated && e.accessPath != BulkPlan.SellerAccessPath.NONE && !e.busy,
        sharing = e.alreadySharing, upstreamType = upstreamType, priceCentimesPerMb = e.sellPriceCentimesPerMb, busy = e.busy,
        capable = e.accessPath != BulkPlan.SellerAccessPath.NONE)
}
