package net.prok.proknet.core

/**
 * Can THIS phone serve a customer while it stays on its own Wi-Fi network?
 * (v0.9.6, pure.)
 *
 * A provider serves a customer through a local-only hotspot. On most
 * chipsets that hotspot must run on the channel the phone's own Wi-Fi
 * connection already uses, so a phone joined to a 5 GHz or DFS network is
 * often refused with ERROR_NO_CHANNEL. Android gives an app no way to pick
 * the band, so the only honest answer is to TEST it once, per network, and
 * remember.
 *
 * Two things must not be confused:
 *   - this phone cannot RESELL that Wi-Fi network today;
 *   - the network itself stays a candidate source on the map, because
 *     another phone may be able to deliver it later. See Coverage.
 */
object ShareCheck {

    enum class Result {
        /** Never tested on this network. */
        UNKNOWN,
        /** The upstream is not Wi-Fi, so the hotspot has nothing to fight with. */
        NOT_NEEDED,
        /** Tested: the hotspot started while the Wi-Fi connection stayed up. */
        CAN_SHARE,
        /** Tested: Android refused the hotspot while this phone was on that network. */
        CANNOT_SHARE,
    }

    /** Only a phone whose Internet IS a Wi-Fi network has to be tested. */
    fun needed(upstreamType: Int): Boolean = upstreamType == Tunnel.UP_WIFI

    /** Run the test only when it is needed and this network has no remembered answer. */
    fun shouldProbe(upstreamType: Int, remembered: Result): Boolean = needed(upstreamType) && remembered == Result.UNKNOWN

    fun verdict(upstreamType: Int, hotspotStarted: Boolean): Result = when {
        !needed(upstreamType) -> Result.NOT_NEEDED
        hotspotStarted -> Result.CAN_SHARE
        else -> Result.CANNOT_SHARE
    }

    /**
     * The capability the coverage engine uses. Null means "not known yet":
     * unknown is NOT a refusal, only a tested failure is.
     */
    fun canShareWhileOnWifi(r: Result): Boolean? = when (r) {
        Result.CANNOT_SHARE -> false
        Result.CAN_SHARE, Result.NOT_NEEDED -> true
        Result.UNKNOWN -> null
    }

    /** One answer per network: the same phone may manage one router and not another. */
    fun key(ssid: String?, bssid: String?): String {
        val b = bssid?.lowercase()?.takeIf { it.isNotEmpty() && it != "02:00:00:00:00:00" && it != "00:00:00:00:00:00" }
        if (b != null) return "bssid:" + b
        val s = ssid?.trim('"')?.takeIf { it.isNotEmpty() && it != "<unknown ssid>" }
        return if (s != null) "ssid:" + s else "unknown"
    }

    // ---- band and channel, recorded so failures can be correlated later ----------------------------

    /** DFS channels (radar sharing) are the ones a soft AP is most often refused on. */
    fun isDfs(freqMhz: Int): Boolean = freqMhz in 5260..5720

    fun band(freqMhz: Int): String = when {
        freqMhz <= 0 -> "unknown"
        freqMhz in 2401..2499 -> "2.4 GHz"
        freqMhz in 5150..5895 -> if (isDfs(freqMhz)) "5 GHz DFS" else "5 GHz"
        freqMhz in 5925..7125 -> "6 GHz"
        else -> "unknown"
    }

    /** 0 when the frequency is not a known Wi-Fi channel. */
    fun channel(freqMhz: Int): Int = when {
        freqMhz == 2484 -> 14
        freqMhz in 2401..2483 -> (freqMhz - 2407) / 5
        freqMhz in 5150..5895 -> (freqMhz - 5000) / 5
        freqMhz in 5925..7125 -> (freqMhz - 5950) / 5
        else -> 0
    }

    fun describe(freqMhz: Int): String =
        if (freqMhz <= 0) "unknown band" else band(freqMhz) + " ch " + channel(freqMhz) + " (" + freqMhz + " MHz)"
}
