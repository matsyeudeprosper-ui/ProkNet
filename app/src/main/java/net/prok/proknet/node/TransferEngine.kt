package net.prok.proknet.node

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.io.RandomAccessFile
import net.prok.proknet.core.Crypto
import net.prok.proknet.core.DeliveryResult
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Dir
import net.prok.proknet.core.Identity
import net.prok.proknet.core.MessageStore
import net.prok.proknet.core.MsgStatus
import net.prok.proknet.core.Packet
import net.prok.proknet.core.Routing
import net.prok.proknet.core.Signed
import net.prok.proknet.core.StoredTransfer
import net.prok.proknet.core.Transfer
import net.prok.proknet.core.hexToBytes
import net.prok.proknet.core.toHex
import net.prok.proknet.transport.Frame
import net.prok.proknet.transport.Transport

/**
 * Large payload engine (v0.5): sends and receives chunked, end-to-end
 * encrypted blobs over whichever transport can reach the peer. Direct only
 * (no relay). Pure parts live in core/Transfer.kt; this class owns files,
 * rows and the send loop.
 *
 * Outgoing: encrypt once -> blob file -> row 'pending' -> pump(): pick a
 * transport (Wi-Fi if up; else ask for Wi-Fi when the blob is big, wait a
 * while, then BLE) -> sendBatch(remaining chunks) -> 'delivered'.
 * Incoming: chunks land in a RandomAccessFile by index; the received mask is
 * persisted per chunk; when complete: open, verify signature, check SHA-256,
 * write the plain data file -> 'received'.
 */
class TransferEngine(
    private val context: Context,
    private val store: MessageStore,
    private val identity: Identity,
    private val hooks: Hooks,
) {
    interface Hooks {
        fun transportFor(peerShort: String): Transport?
        fun bleReachable(peerShort: String): Boolean
        fun wifiUp(peerShort: String): Boolean
        fun wifiIdle(): Boolean
        fun requestWifi(peerShort: String): Boolean
        fun peerPub(peerShort: String): ByteArray?
        fun onChanged()
    }

    private val tag = "XFER"
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    @Volatile var inFlight: String? = null
        private set
    @Volatile var inFlightProgress = 0
        private set
    private val wifiWaitSince = HashMap<String, Long>()
    private val outDir = File(context.filesDir, "transfers/out").apply { mkdirs() }
    private val inDir = File(context.filesDir, "transfers/in").apply { mkdirs() }
    val receivedDir = File(context.filesDir, "received").apply { mkdirs() }
    private val ticker = object : Runnable {
        override fun run() { if (!running) return; pump("tick"); main.postDelayed(this, 10_000) }
    }

    fun start() { if (running) return; running = true; main.postDelayed(ticker, 3000); DiagLog.i(tag, "engine started, pending transfers=" + store.pendingTransfers().size) }
    fun stop() { running = false; main.removeCallbacks(ticker) }

    // ---- sending -------------------------------------------------------------------------------

    /** Encrypt [data] for [destShort] and queue it. Returns false when the peer's key is unknown or the payload is too big. */
    fun send(destShort: String, destFullHex: String, label: String, kind: Int, name: String, data: ByteArray): Boolean {
        if (data.size > Transfer.MAX_BLOB_BYTES - 4096) { DiagLog.e(tag, "payload too large: " + data.size + " bytes (max ~2 MB in v0.5)"); return false }
        val pub = hooks.peerPub(destShort) ?: run { DiagLog.e(tag, "cannot encrypt for prok-" + destShort + ": public key unknown (needs one BLE contact)"); return false }
        val tid = Packet.newMsgId()
        val ts = System.currentTimeMillis()
        val dest = Routing.destBytes(destFullHex, destShort)
        val header = Packet(identity.idBytes, dest, tid, ts, ByteArray(0), Packet.TYPE_CHUNK)
        val body = Transfer.blobBody(kind, name, data)
        val blob = try { Crypto.seal(pub, header.aad(), identity.buildSigned(header.aad(), Signed.KIND_BLOB, body)) } catch (e: Exception) { DiagLog.e(tag, "seal failed", e); return false }
        val file = File(outDir, tid.toHex() + ".blob")
        try { file.writeBytes(blob) } catch (e: Exception) { DiagLog.e(tag, "cannot write blob", e); return false }
        val count = Transfer.chunkCount(blob.size)
        store.insertTransfer(
            StoredTransfer(tid.toHex(), Dir.OUT, destShort, label, kind, name, data.size, blob.size, count, 0, null, MsgStatus.PENDING, "", "", ts, 0, 0,
                Crypto.sha256(data).toHex(), file.absolutePath, dest.toHex(), identity.idHex)
        )
        DiagLog.i(tag, "QUEUED transfer " + tid.toHex() + " to " + label + ": " + (if (kind == Transfer.BLOB_TEXT) "text" else "file " + name) +
            " " + data.size + " bytes -> " + blob.size + " bytes encrypted, " + count + " chunks")
        hooks.onChanged()
        pump("enqueue")
        return true
    }

    fun onPeerSeen(peerShort: String) { if (running) pump("peer seen") }
    fun onWifiChanged() { if (running) pump("wifi changed") }

    fun pump(reason: String) {
        if (!running || inFlight != null) return
        val now = System.currentTimeMillis()
        val t = store.pendingTransfers().firstOrNull { it.nextAttempt <= now && (hooks.wifiUp(it.peerId) || hooks.bleReachable(it.peerId)) } ?: return
        val wifiUp = hooks.wifiUp(t.peerId)
        if (!wifiUp) {
            val since = wifiWaitSince[t.tid]
            if (since == null && Routing.wantWifi(t.blobLen, wifiUp = false, bleReachable = hooks.bleReachable(t.peerId), wifiIdle = hooks.wifiIdle())) {
                wifiWaitSince[t.tid] = now
                DiagLog.i(tag, "transfer " + t.tid + " is " + t.blobLen + " bytes: asking prok-" + t.peerId + " for a Wi-Fi link first (BLE fallback in " + (WIFI_WAIT_MS / 1000) + "s)")
                store.updateTransfer(t.tid, error = "waiting for Wi-Fi link")
                hooks.onChanged()
                if (hooks.requestWifi(t.peerId)) return
                // could not even ask: go BLE
            } else if (since != null && now - since < WIFI_WAIT_MS) {
                return // still waiting for the link
            } else if (since != null) {
                DiagLog.w(tag, "no Wi-Fi link after " + ((now - since) / 1000) + "s, sending transfer " + t.tid + " over BLE (" + t.chunkCount + " chunks, slow)")
            }
        }
        val transport = hooks.transportFor(t.peerId) ?: return
        attempt(t, transport, reason)
    }

    private fun attempt(t: StoredTransfer, transport: Transport, reason: String) {
        val blob = try { File(t.path).readBytes() } catch (e: Exception) {
            store.updateTransfer(t.tid, status = MsgStatus.FAILED, error = "blob file missing"); hooks.onChanged(); return
        }
        if (blob.size != t.blobLen) { store.updateTransfer(t.tid, status = MsgStatus.FAILED, error = "blob size mismatch"); hooks.onChanged(); return }
        inFlight = t.tid; inFlightProgress = t.progressPercent
        store.updateTransfer(t.tid, status = MsgStatus.SENDING, transport = transport.name, error = "", bumpAttempts = true)
        val tid = t.tid.hexToBytes()
        val dest = Routing.destBytes(t.destId, t.peerId)
        val frames = ArrayList<Frame>()
        for (i in t.nextChunk until t.chunkCount) {
            val pkt = Packet(identity.idBytes, dest, tid, t.timestamp, Transfer.chunkPayload(tid, i, blob), Packet.TYPE_CHUNK, Packet.DEFAULT_TTL, 0).stamped(identity.idBytes)
            frames.add(Frame(tid, pkt.encode()))
        }
        DiagLog.i(tag, "SENDING transfer " + t.tid + " to prok-" + t.peerId + " over " + transport.name + ": chunks " + t.nextChunk + ".." + (t.chunkCount - 1) +
            " (attempt " + (t.attempts + 1) + ", trigger: " + reason + ")")
        hooks.onChanged()
        var next = t.nextChunk
        var lastLogged = System.currentTimeMillis()
        var failure: Pair<DeliveryResult, String>? = null
        transport.sendBatch(t.peerId, frames, { i, res, detail ->
            when (res) {
                DeliveryResult.DELIVERED, DeliveryResult.DUPLICATE -> {
                    next = t.nextChunk + i + 1
                    inFlightProgress = next * 100 / t.chunkCount
                    if (next % 25 == 0 || System.currentTimeMillis() - lastLogged > 3000 || next == t.chunkCount) {
                        lastLogged = System.currentTimeMillis()
                        DiagLog.i(tag, "progress " + t.tid + ": " + next + "/" + t.chunkCount + " chunks (" + inFlightProgress + "%) over " + transport.name)
                        store.updateTransfer(t.tid, nextChunk = next)
                        hooks.onChanged()
                    }
                    true
                }
                else -> { failure = res to detail; false }
            }
        }, {
            main.post { onResult(t, next, transport.name, failure) }
        })
    }

    private fun onResult(t: StoredTransfer, next: Int, transportName: String, failure: Pair<DeliveryResult, String>?) {
        inFlight = null
        store.updateTransfer(t.tid, nextChunk = next)
        if (failure == null && next >= t.chunkCount) {
            store.updateTransfer(t.tid, status = MsgStatus.DELIVERED, error = "", transport = transportName)
            wifiWaitSince.remove(t.tid)
            DiagLog.i(tag, "DELIVERED transfer " + t.tid + " to prok-" + t.peerId + " over " + transportName + " (" + t.chunkCount + " chunks, sha256 " + t.sha256.substring(0, 16) + ")")
            try { File(t.path).delete() } catch (_: Exception) {}
        } else {
            val (res, detail) = failure ?: (DeliveryResult.TRANSPORT_FAILED to "stopped early")
            val attemptNo = t.attempts + 1
            if (res == DeliveryResult.REJECTED) {
                store.updateTransfer(t.tid, status = MsgStatus.FAILED, error = "rejected by peer at chunk " + next + ": " + detail)
                DiagLog.e(tag, "FAILED transfer " + t.tid + ": rejected by prok-" + t.peerId + " (" + detail + ")")
            } else if (attemptNo >= Routing.MAX_ATTEMPTS) {
                store.updateTransfer(t.tid, status = MsgStatus.FAILED, error = "gave up after " + attemptNo + " attempts")
                DiagLog.e(tag, "FAILED transfer " + t.tid + ": gave up")
            } else {
                val b = Routing.backoff(attemptNo)
                store.updateTransfer(t.tid, status = MsgStatus.PENDING, error = res.toString() + " at chunk " + next + ": " + detail, nextAttempt = System.currentTimeMillis() + b)
                DiagLog.w(tag, "RETRY LATER transfer " + t.tid + " at chunk " + next + "/" + t.chunkCount + " (" + res + ": " + detail + "), next try in " + (b / 1000) + "s")
            }
        }
        hooks.onChanged()
        main.postDelayed({ pump("after attempt") }, 500)
    }

    // ---- receiving ------------------------------------------------------------------------------

    /** A CHUNK packet addressed to me. Returns the receipt code. Called on a transport thread. */
    fun onChunk(pkt: Packet, transportName: String): Int {
        val chunk = Transfer.parseChunk(pkt.payload) ?: run { DiagLog.w(tag, "malformed chunk from prok-" + pkt.originShort); return Routing.RECEIPT_REJECTED }
        if (!chunk.tid.contentEquals(pkt.msgId)) { DiagLog.w(tag, "chunk transfer ID does not match packet msgId"); return Routing.RECEIPT_REJECTED }
        val tid = chunk.tidHex
        synchronized(this) {
            var row = store.transfer(tid)
            if (row == null) {
                val file = File(inDir, tid + ".part")
                try { RandomAccessFile(file, "rw").use { it.setLength(chunk.total.toLong()) } } catch (e: Exception) { DiagLog.e(tag, "cannot create part file", e); return Routing.RECEIPT_REJECTED }
                row = StoredTransfer(tid, Dir.IN, pkt.originShort, "prok-" + pkt.originShort, 0, "", 0, chunk.total, chunk.count, 0, ByteArray(chunk.count),
                    MsgStatus.RECEIVING, transportName, "", pkt.timestamp, 0, 0, "", file.absolutePath, pkt.destIdHex, pkt.originIdHex)
                store.insertTransfer(row)
                DiagLog.i(tag, "RECEIVING transfer " + tid + " from prok-" + pkt.originShort + ": " + chunk.count + " chunks, " + chunk.total + " bytes, over " + transportName)
            }
            if (row.direction != Dir.IN) return Routing.RECEIPT_REJECTED
            if (row.status == MsgStatus.RECEIVED) return Routing.RECEIPT_DUPLICATE
            if (row.status == MsgStatus.FAILED) return Routing.RECEIPT_REJECTED
            val storage = object : Transfer.Storage {
                override fun write(offset: Int, data: ByteArray) { RandomAccessFile(row.path, "rw").use { it.seek(offset.toLong()); it.write(data) } }
                override fun readAll(): ByteArray = File(row.path).readBytes()
            }
            val asm = Transfer.Assembler(row.chunkCount, row.blobLen, storage, row.receivedMask)
            val fresh = try { asm.add(chunk) } catch (e: Exception) { DiagLog.e(tag, "chunk write failed", e); return Routing.RECEIPT_REJECTED }
            if (!fresh) {
                if (chunk.count != row.chunkCount || chunk.total != row.blobLen) { DiagLog.w(tag, "chunk geometry mismatch for " + tid); return Routing.RECEIPT_REJECTED }
                return Routing.RECEIPT_DUPLICATE
            }
            store.updateTransfer(tid, receivedMask = asm.received, transport = transportName)
            if (asm.receivedCount % 25 == 0 || asm.isComplete) {
                DiagLog.i(tag, "progress " + tid + ": " + asm.receivedCount + "/" + row.chunkCount + " chunks (" + asm.progressPercent() + "%)")
                hooks.onChanged()
            }
            if (asm.isComplete) complete(row, asm, pkt)
            return Routing.RECEIPT_ACCEPTED
        }
    }

    private fun complete(row: StoredTransfer, asm: Transfer.Assembler, pkt: Packet) {
        val header = Packet(pkt.originId, identity.idBytes, pkt.msgId, pkt.timestamp, ByteArray(0), Packet.TYPE_CHUNK)
        val blob = asm.bytes()
        val opened = identity.open(header.aad(), blob)
        if (opened == null) { failIn(row, "cannot decrypt (not for me, or corrupted in transit)"); return }
        val signed = Signed.parse(opened) ?: run { failIn(row, "signed plaintext malformed"); return }
        val pub = hooks.peerPub(pkt.originShort)
        val verified = if (pub == null) 0 else if (Signed.verify(pub, header.aad(), signed)) 1 else -1
        if (verified == -1) { failIn(row, "signature INVALID: not from prok-" + pkt.originShort); return }
        val body = Transfer.parseBody(signed.body) ?: run { failIn(row, "body malformed"); return }
        if (!body.integrityOk) { failIn(row, "SHA-256 mismatch"); return }
        val name = if (body.type == Transfer.BLOB_TEXT) row.tid + ".txt" else safeName(body.name, row.tid)
        val out = File(receivedDir, name)
        try { out.writeBytes(body.data) } catch (e: Exception) { failIn(row, "cannot save: " + e.message); return }
        try { File(row.path).delete() } catch (_: Exception) {}
        store.updateTransfer(row.tid, status = MsgStatus.RECEIVED, path = out.absolutePath, sha256 = body.sha256.toHex(), error = if (verified == 0) "sender key unknown: unverified" else "")
        // Update name/kind/size via a second write (insertTransfer fixed them at creation).
        store.updateTransferMeta(row.tid, body.type, body.name, body.data.size)
        DiagLog.i(tag, "RECEIVED transfer " + row.tid + " from prok-" + pkt.originShort + ": " + (if (body.type == Transfer.BLOB_TEXT) "text" else "file " + body.name) +
            " " + body.data.size + " bytes, sha256 OK, signature " + (if (verified == 1) "VERIFIED" else "unverified (key unknown)") + " -> " + out.name)
        hooks.onChanged()
    }

    private fun failIn(row: StoredTransfer, why: String) {
        store.updateTransfer(row.tid, status = MsgStatus.FAILED, error = why)
        DiagLog.e(tag, "FAILED incoming transfer " + row.tid + ": " + why)
        hooks.onChanged()
    }

    private fun safeName(name: String, tid: String): String {
        val clean = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80)
        return if (clean.isEmpty()) tid + ".bin" else tid.substring(0, 6) + "_" + clean
    }

    /** Text of a received text transfer, for the message list. */
    fun receivedText(t: StoredTransfer, max: Int = 4000): String? =
        if (t.direction == Dir.IN && t.status == MsgStatus.RECEIVED && t.kind == Transfer.BLOB_TEXT) try { File(t.path).readText().take(max) } catch (e: Exception) { null } else null

    companion object { const val WIFI_WAIT_MS = 45_000L }
}
