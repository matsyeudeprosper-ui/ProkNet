package net.prok.proknet.core

/**
 * v0.19.0: what this phone may do here, as the Brain last said it.
 *
 * The server is the authority (flags.py): per city, per function, per cohort, dated, and
 * the restricted functions also need a decision record. The phone only mirrors the
 * answer and shows the server's own sentence for what is switched off. A phone that has
 * never heard from the Brain treats EVERY function as off except what needs no Brain at
 * all: browsing a cached free map and a free direct session.
 */
object FlagsView {

    /** The launch city (contract §10): searchable citywide, Bacongo the first recruited zone. */
    const val LAUNCH_CITY = "Brazzaville"

    val FUNCTIONS = listOf("public_map", "market_browse", "market_paid_publish", "scout_rewards", "direct_free",
        "sponsored_delivery", "customer_paid_delivery", "provider_payout", "relay_payout")

    /** Functions that work from the cache with no Brain at all. */
    val OFFLINE_OK = setOf("public_map", "direct_free")

    class Fn(val name: String, val enabled: Boolean, val switchOn: Boolean, val cohort: Boolean, val missingDecisions: List<String>, val offSentence: String)

    class Status(val city: String, val operator: Boolean, val functions: Map<String, Fn>, val fetchedAt: Long) {
        fun enabled(name: String): Boolean = functions[name]?.enabled == true
        fun offSentence(name: String): String = functions[name]?.offSentence ?: DEFAULT_OFF
        /** The honest status line for a switched-off function on the operator's console. */
        fun why(name: String): String {
            val f = functions[name] ?: return "inconnu"
            return when {
                f.enabled -> "activé"
                !f.switchOn -> "interrupteur fermé"
                f.missingDecisions.isNotEmpty() -> "décision manquante : " + f.missingDecisions.joinToString(", ")
                !f.cohort -> "vous n'êtes pas dans la cohorte"
                else -> "fermé"
            }
        }
    }

    const val DEFAULT_OFF = "Cette fonction n'est pas encore ouverte."

    /** French names for the console. */
    fun label(name: String): String = when (name) {
        "public_map" -> "Carte publique"
        "market_browse" -> "Prok Market — consulter"
        "market_paid_publish" -> "Prok Market — publier (payant)"
        "scout_rewards" -> "Récompenses scout"
        "direct_free" -> "Connexion gratuite directe"
        "sponsored_delivery" -> "Livraison sponsorisée"
        "customer_paid_delivery" -> "Livraison payante"
        "provider_payout" -> "Retraits fournisseur"
        "relay_payout" -> "Gains relais"
        else -> name
    }

    fun parse(text: String, now: Long): Status? {
        if (text.isBlank() || !text.contains("\"functions\"")) return null
        val city = BrainPayload.field(text, "city")
        val operator = BrainPayload.field(text, "operator") == "true"
        val fns = LinkedHashMap<String, Fn>()
        for (name in FUNCTIONS) {
            val o = LedgerView.obj(text, name) ?: continue
            val missing = Regex("\"missing_decisions\"\\s*:\\s*\\[([^\\]]*)\\]").find(o)?.groupValues?.get(1)
                ?.split(',')?.map { it.trim().trim('"') }?.filter { it.isNotEmpty() } ?: emptyList()
            fns[name] = Fn(name, BrainPayload.field(o, "enabled") == "true", BrainPayload.field(o, "switch_on") == "true",
                BrainPayload.field(o, "cohort") == "true", missing, BrainPayload.field(o, "off_sentence"))
        }
        if (fns.isEmpty()) return null
        return Status(city, operator, fns, now)
    }

    /** No Brain ever heard from: everything off except what the cache supports. */
    fun unknown(city: String): Status = Status(city, false, FUNCTIONS.associateWith { name ->
        Fn(name, name in OFFLINE_OK, false, false, emptyList(), if (name in OFFLINE_OK) "" else DEFAULT_OFF)
    }, 0L)
}
