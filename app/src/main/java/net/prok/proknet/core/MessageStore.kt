package net.prok.proknet.core

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Message directions. */
object Dir {
    const val IN = "in"        // addressed to me, stored as received
    const val OUT = "out"      // written by me
    const val CARRY = "carry"  // someone else's packet I hold for its destination
}

/** Message states. */
object MsgStatus {
    // out
    const val PENDING = "pending"         // stored, waiting for the destination or a relay
    const val SENDING = "sending"         // one attempt in flight
    const val DELIVERED = "delivered"     // destination confirmed it stored the message (final)
    const val HANDED_OFF = "handed_off"   // a relay took custody; NOT final delivery
    const val FAILED = "failed"           // rejected, or too many attempts
    const val EXPIRED = "expired"         // waited longer than the TTL
    // carry
    const val CARRYING = "carrying"       // holding for the destination
    const val FORWARDING = "forwarding"   // attempt to the destination in flight
    const val FORWARDED = "forwarded"     // destination confirmed (final, from the relay's view)
    // in
    const val RECEIVED = "received"
    // transfers (v0.5)
    const val RECEIVING = "receiving"
}

class StoredMessage(
    val rowId: Long,
    val msgId: String,
    val direction: String,
    val peerId: String,      // in: origin short | out: destination short | carry: origin short
    val peerName: String,
    val text: String,        // plaintext (in/out); "" for carried packets (opaque)
    val timestamp: Long,
    val status: String,
    val attempts: Int = 0,
    val nextAttempt: Long = 0,
    val lastError: String = "",
    val deliveredAt: Long = 0,
    val destId: String = "",     // full destination ID hex (out, carry, in)
    val originId: String = "",   // full origin ID hex (carry, in)
    val via: String = "",        // in: relay short ID it came through | out: relay that took custody
    val ttl: Int = 0,
    val hops: Int = 0,
    val payload: ByteArray? = null, // v0.5: the on-air payload (ciphertext) for out and carry rows
    val enc: Int = 0,               // 1 = end-to-end encrypted
    val verified: Int = 0,          // in: 1 = origin signature verified, -1 = failed, 0 = unknown key
    val transport: String = "",     // "ble" / "wifi" of the last attempt or receipt
) {
    val destShort: String get() = if (destId.length >= 8) destId.substring(0, 8) else peerId
}

/** A peer we have seen at least once. */
class KnownPeer(val shortId: String, val label: String, val lastSeen: Long, val lastAddress: String, val fullId: String?)

/** A peer's public identity record (v0.5). */
class PeerKey(val shortId: String, val fullId: String, val pub: ByteArray, val name: String, val learnedAt: Long)

/** A large payload transfer (v0.5). */
class StoredTransfer(
    val tid: String,
    val direction: String,     // out / in
    val peerId: String,        // out: destination short | in: origin short
    val peerName: String,
    val kind: Int,             // Transfer.BLOB_TEXT / BLOB_FILE
    val name: String,
    val dataSize: Int,
    val blobLen: Int,
    val chunkCount: Int,
    val nextChunk: Int,        // out: next chunk index to send
    val receivedMask: ByteArray?, // in: one byte per chunk
    val status: String,
    val transport: String,
    val error: String,
    val timestamp: Long,
    val attempts: Int,
    val nextAttempt: Long,
    val sha256: String,
    val path: String,          // out: blob file; in: reassembly file / final data file
    val destId: String,
    val originId: String,
) {
    val progressPercent: Int get() = if (chunkCount == 0) 0 else when (direction) {
        Dir.OUT -> nextChunk * 100 / chunkCount
        else -> (receivedMask?.count { it.toInt() != 0 } ?: 0) * 100 / chunkCount
    }
}

/** Local SQLite store: messages, delivery/carry queue, known peers, peer keys, transfers. Plain SQLiteOpenHelper. */
class MessageStore(context: Context) : SQLiteOpenHelper(context, "proknet.db", null, 4) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE messages(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "msg_id TEXT NOT NULL," +
                "direction TEXT NOT NULL," +
                "peer_id TEXT NOT NULL," +
                "peer_name TEXT NOT NULL," +
                "text TEXT NOT NULL," +
                "ts INTEGER NOT NULL," +
                "status TEXT NOT NULL," +
                "attempts INTEGER NOT NULL DEFAULT 0," +
                "next_attempt INTEGER NOT NULL DEFAULT 0," +
                "last_error TEXT NOT NULL DEFAULT ''," +
                "delivered_at INTEGER NOT NULL DEFAULT 0," +
                "dest_id TEXT NOT NULL DEFAULT ''," +
                "origin_id TEXT NOT NULL DEFAULT ''," +
                "via TEXT NOT NULL DEFAULT ''," +
                "ttl INTEGER NOT NULL DEFAULT 0," +
                "hops INTEGER NOT NULL DEFAULT 0," +
                "payload BLOB," +
                "enc INTEGER NOT NULL DEFAULT 0," +
                "verified INTEGER NOT NULL DEFAULT 0," +
                "transport TEXT NOT NULL DEFAULT '')"
        )
        createIndexV3(db)
        createPeers(db)
        createV4(db)
    }

    private fun createIndexV3(db: SQLiteDatabase) {
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS idx_msg3 ON messages(msg_id, direction, peer_id)")
    }

    private fun createPeers(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS peers(" +
                "short_id TEXT PRIMARY KEY," +
                "label TEXT NOT NULL," +
                "last_seen INTEGER NOT NULL," +
                "last_address TEXT NOT NULL," +
                "full_id TEXT NOT NULL DEFAULT '')"
        )
    }

    private fun createV4(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS peer_keys(" +
                "short_id TEXT PRIMARY KEY," +
                "full_id TEXT NOT NULL," +
                "pub BLOB NOT NULL," +
                "name TEXT NOT NULL DEFAULT ''," +
                "learned_at INTEGER NOT NULL)"
        )
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS transfers(" +
                "tid TEXT PRIMARY KEY," +
                "direction TEXT NOT NULL," +
                "peer_id TEXT NOT NULL," +
                "peer_name TEXT NOT NULL," +
                "kind INTEGER NOT NULL," +
                "name TEXT NOT NULL," +
                "data_size INTEGER NOT NULL," +
                "blob_len INTEGER NOT NULL," +
                "chunk_count INTEGER NOT NULL," +
                "next_chunk INTEGER NOT NULL DEFAULT 0," +
                "received_mask BLOB," +
                "status TEXT NOT NULL," +
                "transport TEXT NOT NULL DEFAULT ''," +
                "error TEXT NOT NULL DEFAULT ''," +
                "ts INTEGER NOT NULL," +
                "attempts INTEGER NOT NULL DEFAULT 0," +
                "next_attempt INTEGER NOT NULL DEFAULT 0," +
                "sha256 TEXT NOT NULL DEFAULT ''," +
                "path TEXT NOT NULL DEFAULT ''," +
                "dest_id TEXT NOT NULL DEFAULT ''," +
                "origin_id TEXT NOT NULL DEFAULT ''," +
                "delivered_at INTEGER NOT NULL DEFAULT 0)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("ALTER TABLE messages ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN next_attempt INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN last_error TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE messages ADD COLUMN delivered_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE messages SET status='delivered' WHERE status='sent'")
            db.execSQL("UPDATE messages SET status='pending' WHERE status='sending' OR status='failed'")
            createPeers(db)
        }
        if (oldVersion < 3) {
            db.execSQL("ALTER TABLE messages ADD COLUMN dest_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE messages ADD COLUMN origin_id TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE messages ADD COLUMN via TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE messages ADD COLUMN ttl INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN hops INTEGER NOT NULL DEFAULT 0")
            db.execSQL("DROP INDEX IF EXISTS idx_msg")
            createIndexV3(db)
            db.execSQL("UPDATE messages SET dest_id = peer_id || '000000000000000000000000', ttl = 3 WHERE direction='out' AND dest_id=''")
            createPeers(db)
            try { db.execSQL("ALTER TABLE peers ADD COLUMN full_id TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE messages ADD COLUMN payload BLOB")
            db.execSQL("ALTER TABLE messages ADD COLUMN enc INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN verified INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN transport TEXT NOT NULL DEFAULT ''")
            createV4(db)
            // v0.5 sends only encrypted packets; plaintext rows still waiting cannot be sent as they are
            // (no key material stored with them). They are marked failed with an explanation, not lost.
            db.execSQL("UPDATE messages SET status='failed', last_error='v0.5: pending plaintext row cannot be encrypted after the fact; resend' WHERE direction='out' AND status IN ('pending','sending')")
            db.execSQL("UPDATE messages SET status='expired', last_error='v0.5: carried plaintext packet dropped on upgrade' WHERE direction='carry' AND status IN ('carrying','forwarding')")
        }
    }

    // ---- messages ------------------------------------------------------------------------------

    /** Returns false if this (msgId, direction, peerId) already exists (duplicate). */
    fun insert(m: StoredMessage): Boolean {
        val cv = ContentValues().apply {
            put("msg_id", m.msgId)
            put("direction", m.direction)
            put("peer_id", m.peerId)
            put("peer_name", m.peerName)
            put("text", m.text)
            put("ts", m.timestamp)
            put("status", m.status)
            put("attempts", m.attempts)
            put("next_attempt", m.nextAttempt)
            put("last_error", m.lastError)
            put("delivered_at", m.deliveredAt)
            put("dest_id", m.destId)
            put("origin_id", m.originId)
            put("via", m.via)
            put("ttl", m.ttl)
            put("hops", m.hops)
            if (m.payload != null) put("payload", m.payload)
            put("enc", m.enc)
            put("verified", m.verified)
            put("transport", m.transport)
        }
        return writableDatabase.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    /** Does any row for this message (from this origin) exist, in any direction? Used for duplicate detection. */
    fun knows(msgId: String, originShort: String): String? {
        readableDatabase.rawQuery(
            "SELECT direction, status FROM messages WHERE msg_id=? AND " +
                "((direction IN ('in','carry') AND peer_id=?) OR (direction='out' AND substr(origin_id,1,8)=?)) LIMIT 1",
            arrayOf(msgId, originShort, originShort)
        ).use { c -> return if (c.moveToFirst()) c.getString(0) + "/" + c.getString(1) else null }
    }

    fun setStatus(
        msgId: String, status: String, error: String = "", nextAttempt: Long = 0,
        bumpAttempts: Boolean = false, direction: String = Dir.OUT, via: String? = null, transport: String? = null,
    ) {
        val sql = "UPDATE messages SET status=?, last_error=?, next_attempt=?" +
            (if (bumpAttempts) ", attempts=attempts+1" else "") +
            (if (status == MsgStatus.DELIVERED || status == MsgStatus.FORWARDED) ", delivered_at=" + System.currentTimeMillis() else "") +
            (if (via != null) ", via='" + via.replace("'", "") + "'" else "") +
            (if (transport != null) ", transport='" + transport.replace("'", "") + "'" else "") +
            " WHERE msg_id=? AND direction=?"
        writableDatabase.execSQL(sql, arrayOf(status, error, nextAttempt, msgId, direction))
    }

    fun pending(): List<StoredMessage> =
        query("SELECT * FROM messages WHERE direction='out' AND status='pending' ORDER BY ts ASC, id ASC", emptyArray())

    fun carrying(): List<StoredMessage> =
        query("SELECT * FROM messages WHERE direction='carry' AND status='carrying' ORDER BY ts ASC, id ASC", emptyArray())

    fun pendingCount(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "messages", "direction='out' AND status='pending'")
    fun carryingCount(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "messages", "direction='carry' AND status='carrying'")

    fun recoverInterrupted(): Int {
        val db = writableDatabase
        db.execSQL("UPDATE messages SET status='pending', next_attempt=0 WHERE direction='out' AND status='sending'")
        val a = DatabaseUtils.longForQuery(db, "SELECT changes()", null).toInt()
        db.execSQL("UPDATE messages SET status='carrying', next_attempt=0 WHERE direction='carry' AND status='forwarding'")
        val b = DatabaseUtils.longForQuery(db, "SELECT changes()", null).toInt()
        db.execSQL("UPDATE transfers SET status='pending', next_attempt=0 WHERE direction='out' AND status='sending'")
        val c = DatabaseUtils.longForQuery(db, "SELECT changes()", null).toInt()
        return a + b + c
    }

    fun expireOlderThan(cutoffTs: Long): List<StoredMessage> {
        val victims = query(
            "SELECT * FROM messages WHERE ((direction='out' AND status='pending') OR (direction='carry' AND status='carrying')) AND ts<?",
            arrayOf(cutoffTs.toString())
        )
        if (victims.isNotEmpty()) {
            writableDatabase.execSQL(
                "UPDATE messages SET status='expired', last_error='ttl exceeded' WHERE ((direction='out' AND status='pending') OR (direction='carry' AND status='carrying')) AND ts<?",
                arrayOf(cutoffTs)
            )
        }
        return victims
    }

    fun retryAllNow() {
        writableDatabase.execSQL("UPDATE messages SET next_attempt=0 WHERE (direction='out' AND status='pending') OR (direction='carry' AND status='carrying')")
        writableDatabase.execSQL("UPDATE transfers SET next_attempt=0 WHERE direction='out' AND status='pending'")
    }

    fun retryNowFor(peerShort: String): Int {
        val db = writableDatabase
        db.execSQL(
            "UPDATE messages SET next_attempt=0 WHERE ((direction='out' AND status='pending') OR (direction='carry' AND status='carrying')) AND substr(dest_id,1,8)=?",
            arrayOf(peerShort)
        )
        db.execSQL("UPDATE transfers SET next_attempt=0 WHERE direction='out' AND status='pending' AND peer_id=?", arrayOf(peerShort))
        return DatabaseUtils.queryNumEntries(
            db, "messages",
            "((direction='out' AND status='pending') OR (direction='carry' AND status='carrying')) AND substr(dest_id,1,8)=?", arrayOf(peerShort)
        ).toInt()
    }

    fun recent(limit: Int = 100): List<StoredMessage> =
        query("SELECT * FROM messages ORDER BY ts DESC, id DESC LIMIT " + limit, emptyArray()).reversed()

    fun count(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "messages")

    private fun query(sql: String, args: Array<String>): List<StoredMessage> {
        val out = ArrayList<StoredMessage>()
        readableDatabase.rawQuery(sql, args).use { c -> while (c.moveToNext()) out.add(row(c)) }
        return out
    }

    private fun row(c: Cursor) = StoredMessage(
        rowId = c.getLong(c.getColumnIndexOrThrow("id")),
        msgId = c.getString(c.getColumnIndexOrThrow("msg_id")),
        direction = c.getString(c.getColumnIndexOrThrow("direction")),
        peerId = c.getString(c.getColumnIndexOrThrow("peer_id")),
        peerName = c.getString(c.getColumnIndexOrThrow("peer_name")),
        text = c.getString(c.getColumnIndexOrThrow("text")),
        timestamp = c.getLong(c.getColumnIndexOrThrow("ts")),
        status = c.getString(c.getColumnIndexOrThrow("status")),
        attempts = c.getInt(c.getColumnIndexOrThrow("attempts")),
        nextAttempt = c.getLong(c.getColumnIndexOrThrow("next_attempt")),
        lastError = c.getString(c.getColumnIndexOrThrow("last_error")),
        deliveredAt = c.getLong(c.getColumnIndexOrThrow("delivered_at")),
        destId = c.getString(c.getColumnIndexOrThrow("dest_id")),
        originId = c.getString(c.getColumnIndexOrThrow("origin_id")),
        via = c.getString(c.getColumnIndexOrThrow("via")),
        ttl = c.getInt(c.getColumnIndexOrThrow("ttl")),
        hops = c.getInt(c.getColumnIndexOrThrow("hops")),
        payload = c.getColumnIndex("payload").let { if (it >= 0 && !c.isNull(it)) c.getBlob(it) else null },
        enc = c.getInt(c.getColumnIndexOrThrow("enc")),
        verified = c.getInt(c.getColumnIndexOrThrow("verified")),
        transport = c.getString(c.getColumnIndexOrThrow("transport")),
    )

    // ---- known peers ---------------------------------------------------------------------------

    fun rememberPeer(shortId: String, label: String, address: String, seenAt: Long, fullId: String?) {
        val db = writableDatabase
        val cv = ContentValues().apply {
            put("short_id", shortId); put("label", label); put("last_seen", seenAt); put("last_address", address)
        }
        if (fullId != null) cv.put("full_id", fullId)
        if (db.update("peers", cv, "short_id=?", arrayOf(shortId)) == 0) {
            if (fullId == null) cv.put("full_id", "")
            db.insertWithOnConflict("peers", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
        }
    }

    fun knownPeers(): List<KnownPeer> {
        val out = ArrayList<KnownPeer>()
        readableDatabase.rawQuery("SELECT short_id,label,last_seen,last_address,full_id FROM peers ORDER BY last_seen DESC", null).use { c ->
            while (c.moveToNext()) {
                val full = c.getString(4)
                out.add(KnownPeer(c.getString(0), c.getString(1), c.getLong(2), c.getString(3), if (full.isNullOrEmpty()) null else full))
            }
        }
        return out
    }

    // ---- peer keys (v0.5) ----------------------------------------------------------------------

    fun savePeerKey(shortId: String, fullId: String, pub: ByteArray, name: String) {
        val cv = ContentValues().apply {
            put("short_id", shortId); put("full_id", fullId); put("pub", pub); put("name", name); put("learned_at", System.currentTimeMillis())
        }
        writableDatabase.insertWithOnConflict("peer_keys", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun peerKey(shortId: String): PeerKey? {
        readableDatabase.rawQuery("SELECT short_id,full_id,pub,name,learned_at FROM peer_keys WHERE short_id=?", arrayOf(shortId)).use { c ->
            return if (c.moveToFirst()) PeerKey(c.getString(0), c.getString(1), c.getBlob(2), c.getString(3), c.getLong(4)) else null
        }
    }

    fun peerKeyCount(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "peer_keys")

    // ---- transfers (v0.5) ----------------------------------------------------------------------

    fun insertTransfer(t: StoredTransfer): Boolean {
        val cv = ContentValues().apply {
            put("tid", t.tid); put("direction", t.direction); put("peer_id", t.peerId); put("peer_name", t.peerName)
            put("kind", t.kind); put("name", t.name); put("data_size", t.dataSize); put("blob_len", t.blobLen)
            put("chunk_count", t.chunkCount); put("next_chunk", t.nextChunk)
            if (t.receivedMask != null) put("received_mask", t.receivedMask)
            put("status", t.status); put("transport", t.transport); put("error", t.error); put("ts", t.timestamp)
            put("attempts", t.attempts); put("next_attempt", t.nextAttempt); put("sha256", t.sha256); put("path", t.path)
            put("dest_id", t.destId); put("origin_id", t.originId)
        }
        return writableDatabase.insertWithOnConflict("transfers", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun transfer(tid: String): StoredTransfer? {
        readableDatabase.rawQuery("SELECT * FROM transfers WHERE tid=?", arrayOf(tid)).use { c ->
            return if (c.moveToFirst()) transferRow(c) else null
        }
    }

    fun transfers(limit: Int = 50): List<StoredTransfer> {
        val out = ArrayList<StoredTransfer>()
        readableDatabase.rawQuery("SELECT * FROM transfers ORDER BY ts DESC LIMIT " + limit, null).use { c -> while (c.moveToNext()) out.add(transferRow(c)) }
        return out
    }

    fun pendingTransfers(): List<StoredTransfer> {
        val out = ArrayList<StoredTransfer>()
        readableDatabase.rawQuery("SELECT * FROM transfers WHERE direction='out' AND status='pending' ORDER BY ts ASC", null).use { c -> while (c.moveToNext()) out.add(transferRow(c)) }
        return out
    }

    fun updateTransfer(
        tid: String, status: String? = null, nextChunk: Int? = null, receivedMask: ByteArray? = null, error: String? = null,
        transport: String? = null, nextAttempt: Long? = null, bumpAttempts: Boolean = false, path: String? = null, sha256: String? = null,
    ) {
        val cv = ContentValues()
        if (status != null) { cv.put("status", status); if (status == MsgStatus.DELIVERED || status == MsgStatus.RECEIVED) cv.put("delivered_at", System.currentTimeMillis()) }
        if (nextChunk != null) cv.put("next_chunk", nextChunk)
        if (receivedMask != null) cv.put("received_mask", receivedMask)
        if (error != null) cv.put("error", error)
        if (transport != null) cv.put("transport", transport)
        if (nextAttempt != null) cv.put("next_attempt", nextAttempt)
        if (path != null) cv.put("path", path)
        if (sha256 != null) cv.put("sha256", sha256)
        if (cv.size() > 0) writableDatabase.update("transfers", cv, "tid=?", arrayOf(tid))
        if (bumpAttempts) writableDatabase.execSQL("UPDATE transfers SET attempts=attempts+1 WHERE tid=?", arrayOf(tid))
    }

    /** Incoming transfers learn their kind/name/size only after decryption. */
    fun updateTransferMeta(tid: String, kind: Int, name: String, dataSize: Int) {
        val cv = ContentValues().apply { put("kind", kind); put("name", name); put("data_size", dataSize) }
        writableDatabase.update("transfers", cv, "tid=?", arrayOf(tid))
    }

    private fun transferRow(c: Cursor) = StoredTransfer(
        tid = c.getString(c.getColumnIndexOrThrow("tid")),
        direction = c.getString(c.getColumnIndexOrThrow("direction")),
        peerId = c.getString(c.getColumnIndexOrThrow("peer_id")),
        peerName = c.getString(c.getColumnIndexOrThrow("peer_name")),
        kind = c.getInt(c.getColumnIndexOrThrow("kind")),
        name = c.getString(c.getColumnIndexOrThrow("name")),
        dataSize = c.getInt(c.getColumnIndexOrThrow("data_size")),
        blobLen = c.getInt(c.getColumnIndexOrThrow("blob_len")),
        chunkCount = c.getInt(c.getColumnIndexOrThrow("chunk_count")),
        nextChunk = c.getInt(c.getColumnIndexOrThrow("next_chunk")),
        receivedMask = c.getColumnIndex("received_mask").let { if (it >= 0 && !c.isNull(it)) c.getBlob(it) else null },
        status = c.getString(c.getColumnIndexOrThrow("status")),
        transport = c.getString(c.getColumnIndexOrThrow("transport")),
        error = c.getString(c.getColumnIndexOrThrow("error")),
        timestamp = c.getLong(c.getColumnIndexOrThrow("ts")),
        attempts = c.getInt(c.getColumnIndexOrThrow("attempts")),
        nextAttempt = c.getLong(c.getColumnIndexOrThrow("next_attempt")),
        sha256 = c.getString(c.getColumnIndexOrThrow("sha256")),
        path = c.getString(c.getColumnIndexOrThrow("path")),
        destId = c.getString(c.getColumnIndexOrThrow("dest_id")),
        originId = c.getString(c.getColumnIndexOrThrow("origin_id")),
    )
}
