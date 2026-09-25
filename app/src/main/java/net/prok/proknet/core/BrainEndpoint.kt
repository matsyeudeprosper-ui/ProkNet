package net.prok.proknet.core

/**
 * v0.17.11: where the Network Brain is, without anybody typing it.
 *
 * Mike asked the obvious question after typing this URL by hand for the fourth time:
 * clearing an app's storage wipes the Brain address, so a phone that has just been reset
 * is silently off the network until somebody remembers to go into the Lab screen and
 * enter a URL from memory. Nobody in Congo is going to do that.
 *
 * So the pilot address is the DEFAULT, not a hard-coded constant. The distinction is the
 * whole design:
 *
 *  - a phone that has never been told anything uses [PILOT];
 *  - a phone whose owner saved a different address uses that;
 *  - a phone whose owner saved an EMPTY address is deliberately off the network, and
 *    stays off - which is exactly what TESTING 75g and 76 need, and what a "Brain off"
 *    switch has to mean.
 *
 * That falls out of `SharedPreferences.getString(key, default)` for free: the default
 * applies only when the key was never written, so an explicit empty string is respected
 * rather than helpfully replaced.
 *
 * On the security rule this does NOT break: the standing constraint was never to
 * hard-code a temporary *insecure* public address to paper over missing TLS. [PILOT] is
 * HTTPS with a real certificate, behind the same reverse proxy that redirects plain HTTP,
 * and [secure] exists so a test can hold that line.
 */
object BrainEndpoint {

    /** The pilot Brain. Public, HTTPS, documented in docs/OPERATIONS.md. */
    const val PILOT = "https://proknet.duckdns.org"

    /** Turning the Brain off is an address too: the empty one. */
    const val OFF = ""

    /**
     * One trailing slash, one stray space, and every signed request would be signed over
     * a path nobody else agrees with. Normalising in one place is cheaper than finding
     * that out from a 401.
     */
    fun normalise(raw: String): String = raw.trim().trimEnd('/')

    fun configured(url: String): Boolean = normalise(url).isNotEmpty()

    /**
     * Is this address one the Brain may be spoken to over?
     *
     * HTTPS anywhere; plain HTTP only to a machine on this phone or this network, which
     * is how somebody tests against a Brain on their own laptop. A plain-HTTP address on
     * the public Internet is refused, because every request carries a signed identity and
     * a demand for Internet is not something to send in the clear.
     */
    fun secure(url: String): Boolean {
        val u = normalise(url).lowercase()
        if (u.isEmpty()) return true                 // off is not insecure
        if (u.startsWith("https://")) return true
        if (!u.startsWith("http://")) return false   // anything else is not an address we speak
        val host = u.removePrefix("http://").substringBefore('/').substringBefore(':')
        return host == "localhost" || host == "127.0.0.1" || host == "::1" ||
            host.startsWith("192.168.") || host.startsWith("10.") ||
            (host.startsWith("172.") && (host.split(".").getOrNull(1)?.toIntOrNull() ?: 0) in 16..31)
    }

    /** One plain sentence for the Lab screen when an address is refused. */
    fun refusal(url: String): String? = when {
        secure(url) -> null
        normalise(url).lowercase().startsWith("http://") ->
            "Utilisez https:// - une adresse publique en http n'est pas acceptée."
        else -> "Adresse invalide : commencez par https://"
    }
}
