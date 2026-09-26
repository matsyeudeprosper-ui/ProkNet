package net.prok.proknet.core

/**
 * v0.19: the download / update decision for the offline map pack, without a phone.
 *
 * The launch contract (10.2) sets the rules and this object is where they live so a test
 * can read them back: show the ACTUAL byte size before any download; update on Wi-Fi by
 * default and on cellular only when the person explicitly asks; say when the pack was
 * last refreshed; never start a download the storage cannot hold - and because the old
 * pack is kept until the new one validates, both must fit at once.
 *
 * [net.prok.proknet.node.MapPackSync] does the fetching; this decides and words it.
 */
object MapPackState {

    /** What the Brain says about the newest pack. */
    class Manifest(val city: String, val cityName: String, val version: Long, val generatedAt: Long,
                   val bytes: Long, val sha256: String, val attribution: String)

    /** What this phone has on disk. */
    class Installed(val version: Long, val generatedAt: Long, val bytes: Long, val sha256: String,
                    /** When THIS phone downloaded it - the "last refreshed" the person sees. */
                    val refreshedAt: Long)

    enum class Decision {
        /** Nothing known from the Brain and nothing on the phone. */
        NOTHING,
        /** A pack is on the phone and the Brain has nothing newer (or is unreachable). */
        CURRENT,
        /** No pack yet: the first download, the whole size shown. */
        DOWNLOAD,
        /** A newer pack exists: an update, the whole size shown (packs are not diffed). */
        UPDATE,
    }

    /** Room kept free beyond the pack itself: the temporary file, the index, the rest of the app. */
    const val STORAGE_MARGIN_BYTES = 20L * 1024 * 1024

    /** After this long a pack is called old on screen, whatever the Brain says. */
    const val STALE_AFTER_MS = 60L * 24 * 3_600_000

    /**
     * The outer object only. The manifest nests `source: {bytes: ...}` and `counts: {...}`,
     * and [BrainPayload.field] takes the FIRST match of a key, so the nested objects are
     * cut out before any key is read.
     */
    fun topLevelOnly(json: String): String {
        val sb = StringBuilder(json.length)
        var depth = 0
        var inString = false
        var escaped = false
        for (ch in json) {
            if (inString) {
                if (depth <= 1) sb.append(ch)
                if (escaped) escaped = false
                else if (ch == '\\') escaped = true
                else if (ch == '"') inString = false
                continue
            }
            when (ch) {
                '"' -> { inString = true; if (depth <= 1) sb.append(ch) }
                '{', '[' -> { depth++; if (depth <= 1) sb.append(ch) }
                '}', ']' -> { if (depth <= 1) sb.append(ch); depth-- }
                else -> if (depth <= 1) sb.append(ch)
            }
        }
        return sb.toString()
    }

    fun parseManifest(text: String): Manifest? {
        val json = topLevelOnly(text)
        val city = BrainPayload.field(json, "city")
        val version = BrainPayload.field(json, "version").toLongOrNull() ?: return null
        val bytes = BrainPayload.field(json, "bytes").toLongOrNull() ?: return null
        val sha = BrainPayload.field(json, "sha256")
        if (city.isEmpty() || bytes <= 0 || sha.length != 64) return null
        return Manifest(city, BrainPayload.field(json, "city_name").ifEmpty { city },
            version, BrainPayload.field(json, "generated_at").toLongOrNull() ?: 0L,
            bytes, sha, BrainPayload.field(json, "attribution").ifEmpty { "© OpenStreetMap contributors, ODbL 1.0" })
    }

    fun compare(installed: Installed?, remote: Manifest?): Decision = when {
        installed == null && remote == null -> Decision.NOTHING
        installed == null -> Decision.DOWNLOAD
        remote == null -> Decision.CURRENT
        remote.version > installed.version && remote.sha256 != installed.sha256 -> Decision.UPDATE
        else -> Decision.CURRENT
    }

    /** Wi-Fi by default; cellular only when the person explicitly asked for THIS download. */
    fun mayDownload(onWifi: Boolean, cellularRequested: Boolean): Boolean = onWifi || cellularRequested

    /** The new pack, the temporary copy is the same file, plus the margin; the old pack is already on disk. */
    fun storageNeeded(packBytes: Long): Long = packBytes + STORAGE_MARGIN_BYTES

    fun storageOk(packBytes: Long, freeBytes: Long): Boolean = freeBytes >= storageNeeded(packBytes)

    /** "12,3 Mo", "353 Ko" - the real number, French decimal comma. */
    fun sizeText(bytes: Long): String = when {
        bytes >= 1_000_000L -> {
            val tenths = (bytes + 50_000) / 100_000
            (tenths / 10).toString() + "," + (tenths % 10) + " Mo"
        }
        bytes >= 1_000L -> ((bytes + 500) / 1000).toString() + " Ko"
        else -> "$bytes o"
    }

    /** "jamais téléchargée", "mise à jour à l'instant", "mise à jour il y a 3 h", "... il y a 12 jours". */
    fun lastRefreshedText(refreshedAt: Long, now: Long): String {
        if (refreshedAt <= 0L) return "Carte jamais téléchargée"
        val age = now - refreshedAt
        val s = when {
            age < 60_000L -> "à l'instant"
            age < 3_600_000L -> "il y a " + (age / 60_000L) + " min"
            age < 24 * 3_600_000L -> "il y a " + (age / 3_600_000L) + " h"
            age < 2 * 24 * 3_600_000L -> "hier"
            else -> "il y a " + (age / (24 * 3_600_000L)) + " jours"
        }
        return "Carte mise à jour $s"
    }

    fun isStale(installed: Installed?, now: Long): Boolean =
        installed != null && now - installed.refreshedAt > STALE_AFTER_MS

    class Plan(val decision: Decision, val allowedNow: Boolean, val storageOk: Boolean,
               /** The one line under the button. */
               val text: String,
               /** The button label, or empty when there is nothing to press. */
               val action: String)

    /** Everything a screen needs, in one call. */
    fun plan(installed: Installed?, remote: Manifest?, onWifi: Boolean, cellularRequested: Boolean,
             freeBytes: Long, now: Long): Plan {
        val d = compare(installed, remote)
        val refreshed = lastRefreshedText(installed?.refreshedAt ?: 0L, now)
        when (d) {
            Decision.NOTHING -> return Plan(d, false, true,
                "Carte hors ligne indisponible pour l'instant. La liste des lieux reste utilisable.", "")
            Decision.CURRENT -> return Plan(d, false, true,
                refreshed + if (isStale(installed, now)) " — ancienne, vérifiez sur Wi-Fi" else "", "")
            else -> {}
        }
        val r = remote!!
        val size = sizeText(r.bytes)
        val allowed = mayDownload(onWifi, cellularRequested)
        val room = storageOk(r.bytes, freeBytes)
        val what = if (d == Decision.DOWNLOAD) "Télécharger la carte de " + r.cityName else "Mettre à jour la carte de " + r.cityName
        val text = when {
            !room -> "Pas assez d'espace : il faut " + sizeText(storageNeeded(r.bytes)) + " libres, il reste " + sizeText(freeBytes) + "."
            onWifi -> "$size sur Wi-Fi. " + refreshed + "."
            cellularRequested -> "$size sur données mobiles, à votre demande. " + refreshed + "."
            else -> "$size. Sans Wi-Fi : appuyez pour télécharger sur données mobiles. " + refreshed + "."
        }
        val action = when {
            !room -> ""
            allowed -> "$what ($size)"
            else -> "$what ($size, données mobiles)"
        }
        return Plan(d, allowed && room, room, text, action)
    }
}
