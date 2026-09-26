package net.prok.proknet.node

import java.net.HttpURLConnection
import java.net.URL
import net.prok.proknet.core.BrainPayload
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.FlagsView
import net.prok.proknet.core.Identity
import net.prok.proknet.core.SignedApi

/**
 * v0.19.0: the phone's copy of the operator's switches, refreshed on the Brain sweep,
 * and the operator's own calls (set a flag, add to a cohort, record a decision).
 * Signed like every other Brain call. The last answer is cached in memory and in the
 * preferences the caller passes in, so a phone with no signal still knows what it may do.
 */
class FlagsSync(private val identity: Identity, private val brainUrl: () -> String, private val city: () -> String,
                private val load: () -> String, private val save: (String) -> Unit) {
    private val tag = "FLAGS"
    @Volatile var status: FlagsView.Status = FlagsView.parse(load(), 0L) ?: FlagsView.unknown(city())
        private set
    @Volatile var lastError = ""

    val configured: Boolean get() = brainUrl().isNotEmpty()

    fun run() {
        if (!configured) return
        try {
            val (code, text) = get("/v1/flags/status?city=" + java.net.URLEncoder.encode(city(), "UTF-8"))
            if (code == 200) {
                val s = FlagsView.parse(text, System.currentTimeMillis())
                if (s != null) { status = s; save(text); lastError = "" }
            } else lastError = "HTTP " + code
        } catch (e: Exception) { lastError = e.message ?: e.javaClass.simpleName; DiagLog.w(tag, "status: " + lastError) }
    }

    fun console(): String? = try { val (c, t) = get("/v1/flags/console?city=" + java.net.URLEncoder.encode(city(), "UTF-8")); if (c == 200) t else null } catch (e: Exception) { null }

    fun setFlag(function: String, enabled: Boolean, reason: String): LedgerSync.Outcome =
        call("/v1/flags/set", json("function" to function, "city" to city(), "enabled" to enabled, "reason" to reason))

    fun cohort(function: String, nodeId: String, add: Boolean): LedgerSync.Outcome =
        call(if (add) "/v1/flags/cohort/add" else "/v1/flags/cohort/remove", json("function" to function, "city" to city(), "node_id" to nodeId))

    fun decision(kind: String, summary: String, document: String, accountable: String, decidedAt: Long): LedgerSync.Outcome =
        call("/v1/flags/decision", json("kind" to kind, "city" to city(), "summary" to summary, "document" to document, "accountable" to accountable, "decided_at" to decidedAt))

    private fun call(path: String, body: ByteArray): LedgerSync.Outcome {
        if (!configured) return LedgerSync.Outcome(false, "Réseau Prok non configuré")
        return try {
            val (code, text) = post(path, body)
            if (code == 200) { run(); LedgerSync.Outcome(true, "Enregistré") }
            else LedgerSync.Outcome(false, BrainPayload.field(text, "error").ifEmpty { "HTTP " + code }, BrainPayload.field(text, "reason"))
        } catch (e: Exception) { LedgerSync.Outcome(false, "Réseau Prok injoignable : " + (e.message ?: e.javaClass.simpleName)) }
    }

    private fun json(vararg pairs: Pair<String, Any?>): ByteArray {
        val sb = StringBuilder("{"); var first = true
        for ((k, v) in pairs) {
            if (!first) sb.append(","); first = false
            sb.append('"').append(k).append("\":")
            when (v) { is Number, is Boolean -> sb.append(v.toString()); else -> sb.append('"').append(BrainPayload.escape(v?.toString() ?: "")).append('"') }
        }
        return sb.append("}").toString().toByteArray(Charsets.UTF_8)
    }

    private fun post(path: String, body: ByteArray) = send("POST", path, body)
    private fun get(path: String) = send("GET", path, ByteArray(0))
    private fun send(method: String, path: String, body: ByteArray): Pair<Int, String> {
        val c = URL(brainUrl() + path).openConnection() as HttpURLConnection
        c.requestMethod = method; c.connectTimeout = 15_000; c.readTimeout = 20_000
        for ((k, v) in SignedApi.sign(body, identity, System.currentTimeMillis(), method = method, path = path).asMap()) c.setRequestProperty(k, v)
        if (method == "POST") { c.doOutput = true; c.setRequestProperty("Content-Type", "application/json; charset=utf-8"); c.outputStream.use { it.write(body) } }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
        return code to text
    }
}
