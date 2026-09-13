package net.prok.proknet.core

import java.nio.ByteBuffer

/**
 * Large payload transfer, pure part (v0.5). A payload is signed, encrypted
 * ONCE as a whole (Signed + Crypto.seal), then the resulting blob is cut
 * into fixed 400-byte chunks that fit a v4 packet on any transport. The
 * receiver reassembles by index, verifies completeness, opens the envelope,
 * checks the signature and the SHA-256 carried inside the blob.
 *
 * Chunk record (the CHUNK packet payload):
 *   [transfer ID 8][index u32][count u32][blob length u32][data]
 */
object Transfer {
    const val CHUNK_DATA = 400
    const val CHUNK_HEADER = 8 + 4 + 4 + 4
    const val MAX_BLOB_BYTES = 2 * 1024 * 1024
    /** Above this size we try to bring up Wi-Fi before sending. */
    const val WIFI_THRESHOLD_BYTES = 4096

    const val BLOB_TEXT = 1
    const val BLOB_FILE = 2

    init { require(CHUNK_HEADER + CHUNK_DATA <= Packet.MAX_PAYLOAD_BYTES) }

    fun chunkCount(blobLen: Int): Int = if (blobLen <= 0) 0 else (blobLen + CHUNK_DATA - 1) / CHUNK_DATA

    class Chunk(val tid: ByteArray, val index: Int, val count: Int, val total: Int, val data: ByteArray) {
        val tidHex get() = tid.toHex()
    }

    fun chunkPayload(tid: ByteArray, index: Int, blob: ByteArray): ByteArray {
        val count = chunkCount(blob.size)
        require(index in 0 until count) { "chunk index out of range" }
        val from = index * CHUNK_DATA
        val to = minOf(blob.size, from + CHUNK_DATA)
        return ByteBuffer.allocate(CHUNK_HEADER + (to - from))
            .put(tid).putInt(index).putInt(count).putInt(blob.size).put(blob, from, to - from).array()
    }

    /** Returns null (never throws) for a malformed chunk record. */
    fun parseChunk(payload: ByteArray?): Chunk? {
        if (payload == null || payload.size < CHUNK_HEADER) return null
        return try {
            val b = ByteBuffer.wrap(payload)
            val tid = ByteArray(8).also { b.get(it) }
            val index = b.int; val count = b.int; val total = b.int
            val data = ByteArray(b.remaining()).also { b.get(it) }
            if (index < 0 || count <= 0 || total <= 0 || total > MAX_BLOB_BYTES) return null
            if (count != chunkCount(total) || index >= count) return null
            val expected = if (index == count - 1) total - index * CHUNK_DATA else CHUNK_DATA
            if (data.size != expected) return null
            Chunk(tid, index, count, total, data)
        } catch (e: Exception) { null }
    }

    /** Where reassembled bytes go. In tests memory; on the phone a file. */
    interface Storage {
        fun write(offset: Int, data: ByteArray)
        fun readAll(): ByteArray
    }

    class MemoryStorage(size: Int) : Storage {
        private val buf = ByteArray(size)
        override fun write(offset: Int, data: ByteArray) { System.arraycopy(data, 0, buf, offset, data.size) }
        override fun readAll(): ByteArray = buf.copyOf()
    }

    /** Reassembles one transfer. [received] is a bit set persisted by the caller (one byte per chunk, 0/1). */
    class Assembler(val count: Int, val total: Int, private val storage: Storage, received: ByteArray? = null) {
        val received: ByteArray = received?.copyOf() ?: ByteArray(count)
        val receivedCount: Int get() = received.count { it.toInt() != 0 }
        val isComplete: Boolean get() = receivedCount == count

        /** True if this chunk was new. Rejects chunks that do not match this transfer's geometry. */
        fun add(c: Chunk): Boolean {
            if (c.count != count || c.total != total) return false
            if (received[c.index].toInt() != 0) return false
            storage.write(c.index * CHUNK_DATA, c.data)
            received[c.index] = 1
            return true
        }

        fun missing(): List<Int> = received.indices.filter { received[it].toInt() == 0 }
        fun bytes(): ByteArray = storage.readAll()
        fun progressPercent(): Int = if (count == 0) 100 else receivedCount * 100 / count
    }

    // ---- blob body: what is inside the signed plaintext of a transfer -------------------------

    /** [type 1][sha256 32][nameLen u16][name][data] */
    fun blobBody(type: Int, name: String, data: ByteArray): ByteArray {
        val n = name.toByteArray(Charsets.UTF_8)
        require(n.size <= 65535)
        return ByteBuffer.allocate(1 + 32 + 2 + n.size + data.size)
            .put(type.toByte()).put(Crypto.sha256(data)).putShort(n.size.toShort()).put(n).put(data).array()
    }

    class Body(val type: Int, val sha256: ByteArray, val name: String, val data: ByteArray) {
        val integrityOk: Boolean get() = Crypto.sha256(data).contentEquals(sha256)
    }

    fun parseBody(bytes: ByteArray?): Body? {
        if (bytes == null || bytes.size < 1 + 32 + 2) return null
        return try {
            val b = ByteBuffer.wrap(bytes)
            val type = b.get().toInt() and 0xFF
            val sha = ByteArray(32).also { b.get(it) }
            val n = b.short.toInt() and 0xFFFF
            if (b.remaining() < n) return null
            val name = String(ByteArray(n).also { b.get(it) }, Charsets.UTF_8)
            val data = ByteArray(b.remaining()).also { b.get(it) }
            Body(type, sha, name, data)
        } catch (e: Exception) { null }
    }
}
