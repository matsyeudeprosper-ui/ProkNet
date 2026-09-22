package net.prok.proknet.core

/**
 * v0.17.3: what this phone tells the Brain about itself, decided in one pure place.
 *
 * THE BUG THIS FILE EXISTS FOR. Build 70 built the presence as:
 *
 *     sharingEnabled = e.optIn && node.sellOn
 *
 * and the heartbeat only published when `sharingEnabled` was true. `sellOn` means "the
 * seller gateway is running right now", so the Brain could only ever see a phone that was
 * ALREADY sharing. That is a contradiction with the product flow, which is:
 *
 *     idle, opted-in phone with Internet
 *       -> the Brain knows it exists
 *       -> a buyer's demand is offered to it
 *       -> the notification arrives
 *       -> the user taps PARTAGER
 *       -> sharing starts
 *
 * With `sellOn = false` there was no presence and therefore no activation could ever
 * arrive; with `sellOn = true` the presence existed but PARTAGER then refused the job with
 * ALREADY_SHARING. Neither state could complete the loop, so the distant two-phone
 * scenario could not work on hardware however green the tests were.
 *
 * So this separates two things build 70 had collapsed into one flag:
 *
 *  - WILLING: "you may ask me." Opted in, real validated Internet, a local path it could
 *    actually offer. Says nothing about whether it is serving anybody.
 *  - CURRENTLY SHARING: "the gateway is up." A fact about now, and never a prerequisite
 *    for being discoverable.
 *
 * Capacity is carried separately and honestly by [State.currentLoad] / [State.maxBuyers],
 * which is what the Brain already uses to decide whether a provider may be given another
 * buyer. A phone that is sharing stays visible - a busy provider is still a provider, and
 * the zone is genuinely covered - it simply has no room.
 *
 * Pure on purpose: the presence hook in `NetworkNode` calls exactly this, so the test and
 * the phone run the same function rather than two hopefully-identical ones.
 */
object ProviderPresence {

    /** The pilot gateway serves one buyer at a time. */
    const val MAX_BUYERS = 1

    const val COMMERCIAL = "COMMERCIAL"
    const val FREE = "FREE"

    data class State(
        val zone: String,
        /** Opted in, has usable Internet, has a path it could offer. NOT "is sharing". */
        val willing: Boolean,
        /** Willing AND with room for another buyer: the Brain may offer a job. */
        val availableForActivation: Boolean,
        /** The seller gateway is running. Reported for truth, never a prerequisite. */
        val currentlySharing: Boolean,
        val upstreamAvailable: Boolean,
        val upstreamClass: String,
        val commercialReady: Boolean,
        val freeReady: Boolean,
        val sponsoredReady: Boolean,
        val currentLoad: Int,
        val maxBuyers: Int,
        val offerClass: String,
        /** Internal only. The buyer is never shown a per-MB figure. */
        val priceHintInternal: Int,
    ) {
        /**
         * Does the Brain still need to hear from us at all?
         *
         * A willing phone keeps its presence alive even while it is full, because
         * disappearing would make a covered zone look empty. An unwilling phone withdraws
         * at once: a buyer matched to somebody who has opted out wastes a real walk.
         */
        val shouldPublish: Boolean get() = willing && upstreamAvailable && zone.isNotEmpty()
    }

    /**
     * @param eligibility the existing v0.13 capability check, from the phone's REAL
     *   current Internet - not from the seller gateway, which is not running yet.
     * @param currentlySharing `sellOn`: the gateway is up.
     * @param activeSessions live seller sessions, 0 or 1 in the pilot.
     * @param mayOfferPaidSharing every v0.16 paid-seller safety condition, unchanged:
     *   a valid payment destination, payment verification readiness, seller not blocked.
     * @return null when there is no zone, because a presence without a place is useless.
     */
    fun of(zone: String,
           eligibility: ProviderActivation.Eligibility,
           currentlySharing: Boolean,
           activeSessions: Int,
           mayOfferPaidSharing: Boolean,
           maxBuyers: Int = MAX_BUYERS): State? {
        if (zone.isEmpty() || zone == CoverageModel.NO_ZONE) return null
        val e = eligibility
        val willing = ProviderActivation.willing(e)
        val load = if (activeSessions < 0) 0 else activeSessions
        val cap = if (maxBuyers < 1) 1 else maxBuyers
        // readiness is an ABILITY - "if the user accepts this, can this phone serve it?" -
        // and not a report on the gateway. Deriving it from `sellOn` is the same mistake
        // as deriving presence from `sellOn`.
        val commercial = willing && mayOfferPaidSharing
        val free = willing && !mayOfferPaidSharing
        return State(
            zone = zone,
            willing = willing,
            availableForActivation = willing && load < cap,
            currentlySharing = currentlySharing,
            // the path it COULD offer, not merely a bar of signal
            upstreamAvailable = e.accessPath != BulkPlan.SellerAccessPath.NONE,
            upstreamClass = if (e.upstreamValidated) "VALIDATED" else "UNVALIDATED",
            commercialReady = commercial,
            freeReady = free,
            sponsoredReady = false,
            currentLoad = load,
            maxBuyers = cap,
            offerClass = if (mayOfferPaidSharing) COMMERCIAL else FREE,
            priceHintInternal = e.sellPriceCentimesPerMb)
    }
}
