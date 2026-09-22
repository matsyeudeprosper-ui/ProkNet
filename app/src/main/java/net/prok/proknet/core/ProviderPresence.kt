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

    /**
     * v0.17.4: what this phone is OFFERING, which is not the same as what it is able to
     * complete right now.
     *
     * THE BUG THIS EXISTS FOR. Build 71 did:
     *
     *     commercialReady = willing && mayOfferPaidSharing
     *     freeReady       = willing && !mayOfferPaidSharing
     *     offerClass      = if (mayOfferPaidSharing) COMMERCIAL else FREE
     *
     * `mayOfferPaidSharing` false means "this seller cannot currently take money" - no
     * payment destination, verification not ready, seller blocked. Build 71 read that as
     * "this seller has volunteered to give their Internet away", which is a completely
     * different sentence. A seller whose Mobile Money number was missing was advertised
     * to the whole zone as FREE.
     *
     * Two things were wrong with that at once. It breaks the locked product rule that
     * FREE must be explicit. And it creates an economic mismatch: the Brain matches a
     * buyer who asked for free, the provider taps PARTAGER, and the local pricing engine
     * - which has never looked at `mayOfferPaidSharing` - quotes a paid rate for the
     * same session.
     *
     * So intent comes from the SOURCE, using the same [Pricing.isFree] test the pricing
     * engine itself uses to decide the buyer pays nothing. Inability to charge can make a
     * provider unavailable. It can never change what it was offering.
     */
    enum class Intent { COMMERCIAL, FREE }

    /**
     * The only way a provider becomes FREE.
     *
     * Reuses the pricing engine's own predicate rather than inventing a second opinion:
     * an explicitly free source (`free = true`) or a FREE_PUBLIC one. No phone today has
     * a way to set either, so in production this is false and every real provider is
     * COMMERCIAL - which is the conservative answer and the honest one. When a real free
     * source or a user-facing "give it away" setting exists, it arrives here and nowhere
     * else.
     */
    fun intentOf(source: Pricing.Source): Intent =
        if (Pricing.isFree(source)) Intent.FREE else Intent.COMMERCIAL

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
     *   v0.17.4: this may make a provider UNAVAILABLE. It may never reclassify it.
     * @param intent what this phone is offering, from [intentOf] and therefore from the
     *   source the pricing engine will actually quote on.
     * @return null when there is no zone, because a presence without a place is useless.
     */
    fun of(zone: String,
           eligibility: ProviderActivation.Eligibility,
           currentlySharing: Boolean,
           activeSessions: Int,
           mayOfferPaidSharing: Boolean,
           intent: Intent,
           maxBuyers: Int = MAX_BUYERS): State? {
        if (zone.isEmpty() || zone == CoverageModel.NO_ZONE) return null
        val e = eligibility
        val willing = ProviderActivation.willing(e)
        val load = if (activeSessions < 0) 0 else activeSessions
        val cap = if (maxBuyers < 1) 1 else maxBuyers
        // readiness is an ABILITY - "if the user accepts this, can this phone serve it?" -
        // and not a report on the gateway. Deriving it from `sellOn` is the same mistake
        // as deriving presence from `sellOn`.
        // v0.17.4: three independent facts. Intent says what is on offer; readiness says
        // whether it can be completed; capacity says whether there is room. A provider
        // that cannot charge is simply UNAVAILABLE - both flags false - and the Brain
        // then matches it for nothing, which is the correct outcome. It is never
        // reclassified as a gift.
        val commercial = willing && intent == Intent.COMMERCIAL && mayOfferPaidSharing
        val free = willing && intent == Intent.FREE
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
            // the class it is OFFERING, always - a commercial seller that cannot charge
            // stays COMMERCIAL and is excluded by its readiness being false. Flipping the
            // class instead would be a lie the matcher would act on.
            offerClass = if (intent == Intent.FREE) FREE else COMMERCIAL,
            // and the hint matches the contract the session would really create:
            // Pricing.autoRate is 0 for a free source, so claiming a per-MB figure for
            // one would be the same mismatch in a different field
            priceHintInternal = if (intent == Intent.FREE) 0 else e.sellPriceCentimesPerMb)
    }
}
