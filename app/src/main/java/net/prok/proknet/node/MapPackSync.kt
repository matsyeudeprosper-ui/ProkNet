package net.prok.proknet.node

import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import net.prok.proknet.core.BrainPayload
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.MapPackState
import net.prok.proknet.core.ProkMap

/**
 * v0.19: fetching the offline map pack from the Brain.
 *
 *     GET {brain}/v1/map/{city}/manifest   -> JSON, see MapPackState.parseManifest
 *     GET {brain}/v1/map/{city}/pack       -> the bytes
 *
 * Both unsigned: the pack is public data. What protects the phone is the sha256 in the
 * manifest and a full parse before anything is swapped in:
 *
 *  - the download goes to `<city>.prokmap.part`, hashed as it streams;
 *  - the length and the sha256 must match the manifest, and [ProkMap.parse] must accept
 *    every byte; a failure deletes the part file and leaves the installed pack untouched;
 *  - only then is the previous pack moved aside, the new one moved in, and the previous
 *    deleted. A phone that dies in the middle still has one valid pack on disk.
 *
 * Whether to download at all (Wi-Fi, size, storage) is decided by [MapPackState]; this
 * class never starts a download on its own.
 */
class MapPackSync(
    private val brainUrl: () -> String,
    /** The app's files directory; the packs live in `maps/` under it. */
    filesDir: File,
    val city: String = "brazzaville",
) {
    private val tag = "MAPPACK"
    private val dir = File(filesDir, "maps")
    val packFile = File(dir, "$city.prokmap")
    private val partFile = File(dir, "$city.prokmap.part")
    private val prevFile = File(dir, "$city.prokmap.prev")
    private val installedFile = File(dir, "$city.installed.json")

    class Result(val ok: Boolean, val message: String, val installed: MapPackState.Installed? = null)

    val configured: Boolean get() = brainUrl().isNotEmpty()

    /** What is on disk, from the record written after the last validated download. */
    fun installed(): MapPackState.Installed? {
        if (!installedFile.isFile || !packFile.isFile) return null
        val j = try { installedFile.readText(Charsets.UTF_8) } catch (e: Exception) { return null }
        val version = BrainPayload.field(j, "version").toLongOrNull() ?: return null
        val bytes = BrainPayload.field(j, "bytes").toLongOrNull() ?: return null
        if (packFile.length() != bytes) return null
        return MapPackState.Installed(version, BrainPayload.field(j, "generated_at").toLongOrNull() ?: 0L,
            bytes, BrainPayload.field(j, "sha256"), BrainPayload.field(j, "refreshed_at").toLongOrNull() ?: 0L)
    }

    /** The installed pack, parsed; null when there is none or it does not parse. */
    fun open(): ProkMap? {
        if (!packFile.isFile) return null
        return try { ProkMap.load(packFile) } catch (e: Exception) {
            DiagLog.w(tag, "installed pack does not parse: " + e.message)
            null
        }
    }

    /** Ask the Brain what the newest pack is. Null when unreachable or not offered. */
    fun fetchManifest(): MapPackState.Manifest? {
        if (!configured) return null
        return try {
            val c = URL(brainUrl() + "/v1/map/" + city + "/manifest").openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            val code = c.responseCode
            if (code !in 200..299) { DiagLog.w(tag, "manifest: HTTP $code"); return null }
            val text = c.inputStream.bufferedReader(Charsets.UTF_8).readText()
            val m = MapPackState.parseManifest(text)
            if (m == null) DiagLog.w(tag, "manifest unreadable")
            else if (m.city != city) { DiagLog.w(tag, "manifest is for " + m.city); return null }
            m
        } catch (e: Exception) {
            DiagLog.w(tag, "manifest: " + e.message)
            null
        }
    }

    /**
     * Download [remote], verify it, and swap it in. Blocking; call off the main thread.
     * [onProgress] gets (bytes so far, total) now and then.
     */
    fun download(remote: MapPackState.Manifest, onProgress: ((Long, Long) -> Unit)? = null): Result {
        if (!configured) return Result(false, "Brain non configuré")
        dir.mkdirs()
        partFile.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        var got = 0L
        try {
            val c = URL(brainUrl() + "/v1/map/" + city + "/pack").openConnection() as HttpURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 60_000
            val code = c.responseCode
            if (code !in 200..299) return fail("HTTP $code")
            c.inputStream.use { input ->
                FileOutputStream(partFile).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var lastReport = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        digest.update(buf, 0, n)
                        got += n
                        if (got > remote.bytes) return fail("plus long que prévu")
                        if (got - lastReport >= 256 * 1024) { lastReport = got; onProgress?.invoke(got, remote.bytes) }
                    }
                }
            }
        } catch (e: Exception) {
            return fail(e.message ?: e.javaClass.simpleName)
        }
        if (got != remote.bytes) return fail("taille $got au lieu de ${remote.bytes}")
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        if (sha != remote.sha256) return fail("empreinte différente")
        try {
            ProkMap.parse(partFile.readBytes())
        } catch (e: Exception) {
            return fail("fichier invalide: " + e.message)
        }
        // swap: the old one stays until the new one is in place
        prevFile.delete()
        if (packFile.isFile && !packFile.renameTo(prevFile)) return fail("impossible de remplacer l'ancienne carte")
        if (!partFile.renameTo(packFile)) {
            prevFile.renameTo(packFile)
            return fail("impossible d'installer la carte")
        }
        prevFile.delete()
        val installed = MapPackState.Installed(remote.version, remote.generatedAt, remote.bytes, sha, System.currentTimeMillis())
        installedFile.writeText(
            "{\"city\":\"" + city + "\",\"version\":" + installed.version + ",\"generated_at\":" + installed.generatedAt +
                ",\"bytes\":" + installed.bytes + ",\"sha256\":\"" + sha + "\",\"refreshed_at\":" + installed.refreshedAt + "}",
            Charsets.UTF_8)
        onProgress?.invoke(got, remote.bytes)
        DiagLog.i(tag, "map pack " + city + " v" + remote.version + " installed, " + MapPackState.sizeText(got))
        return Result(true, "Carte installée", installed)
    }

    private fun fail(why: String): Result {
        partFile.delete()
        DiagLog.w(tag, "map pack download failed: $why")
        return Result(false, "Téléchargement de la carte échoué : $why")
    }

    fun describe(): String {
        val i = installed()
        return "  map pack: " + (if (i == null) "none" else city + " v" + i.version + ", " + MapPackState.sizeText(i.bytes) +
            ", " + MapPackState.lastRefreshedText(i.refreshedAt, System.currentTimeMillis()))
    }
}
