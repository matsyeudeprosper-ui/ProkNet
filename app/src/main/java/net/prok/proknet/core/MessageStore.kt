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
    const val CARRY = "carry"  // milestone 2C1: someone else's packet I hold for its destination
}

/** Message states. */
object MsgStatus {
    // out
    const val PENDING = "pending"         // stored, waiting for the destination or a relay
    const val SENDING = "sending"         // one attempt in flight
    const val DELIVERED = "delivered"     // destination confirmed it stored the message (final)
    const val HANDED_OFF = "handed_off"   // 2C1: a relay took custody; NOT final delivery
    const val FAILED = "failed"           // rejected, or too many attempts
    const val EXPIRED = "expired"         // waited longer than the TTL
    // carry
    const val CARRYING = "carrying"       // 2C1: holding for the destination
    const val FORWARDING = "forwarding"   // 2C1: attempt to the destination in flight
    const val FORWARDED = "forwarded"     // 2C1: destination confirmed (final, from the relay's view)
    // in
    const val RECEIVED = "received"
}

class StoredMessage(
    val rowId: Long,
    val msgId: String,
    val direction: String,
    val peerId: String,      // in: origin short | out: destination short | carry: origin short
    val peerName: String,
    val text: String,
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
) {
    val destShort: String get() = if (destId.length >= 8) destId.substring(0, 8) else peerId
}

/** A peer we have seen at least once. Kept so messages can be queued for a peer that is not in range. */
class KnownPeer(val shortId: String, val label: String, val lastSeen: Long, val lastAddress: String, val fullId: String?)

/** Local SQLite store for messages, the delivery/carry queue and known peers. Plain SQLiteOpenHelper: no Room, no kapt. */
class MessageStore(context: Context) : SQLiteOpenHelper(context, "proknet.db", null, 3) {

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
                "hops INTEGER NOT NULL DEFAULT 0)"
        )
        createIndexV3(db)
        createPeers(db)
    }

    private fun createIndexV3(db: SQLiteDatabase) {
        // (message ID + origin) is the global identity of a message; peer_id holds the origin
        // for 'in' and 'carry' rows, so this index is exactly "msg ID + origin" per direction.
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
            // Rows from v0.2/v0.3 have no destination: address them by short ID (peer_id + 24 zero hex chars), TTL 3.
            db.execSQL("UPDATE messages SET dest_id = peer_id || '000000000000000000000000', ttl = 3 WHERE direction='out' AND dest_id=''")
            createPeers(db)
            try { db.execSQL("ALTER TABLE peers ADD COLUMN full_id TEXT NOT NULL DEFAULT ''") } catch (_: Exception) {}
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
        }
        return writableDatabase.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    /** Does any row for this message (from this origin) exist, in any direction? Used for duplicate detection. */
    fun knows(msgId: String, originShort: String): String? {
        readableDatabase.rawQuery(
            "SELECT direction, status FROM messages WHERE msg_id=? AND (peer_id=? OR direction='out') LIMIT 1",
            arrayOf(msgId, originShort)
        ).use { c -> return if (c.moveToFirst()) c.getString(0) + "/" + c.getString(1) else null }
    }

    fun setStatus(
        msgId: String, status: String, error: String = "", nextAttempt: Long = 0,
        bumpAttempts: Boolean = false, direction: String = Dir.OUT, via: String? = null,
    ) {
        val sql = "UPDATE messages SET status=?, last_error=?, next_attempt=?" +
            (if (bumpAttempts) ", attempts=attempts+1" else "") +
            (if (status == MsgStatus.DELIVERED || status == MsgStatus.FORWARDED) ", delivered_at=" + System.currentTimeMillis() else "") +
            (if (via != null) ", via='" + via.replace("'", "") + "'" else "") +
            " WHERE msg_id=? AND direction=?"
        writableDatabase.execSQL(sql, arrayOf(status, error, nextAttempt, msgId, direction))
    }

    /** Oldest-first pending outgoing messages. */
    fun pending(): List<StoredMessage> =
        query("SELECT * FROM messages WHERE direction='out' AND status='pending' ORDER BY ts ASC, id ASC", emptyArray())

    /** Oldest-first packets I am carrying for someone else. */
    fun carrying(): List<StoredMessage> =
        query("SELECT * FROM messages WHERE direction='carry' AND status='carrying' ORDER BY ts ASC, id ASC", emptyArray())

    fun pendingCount(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "messages", "direction='out' AND status='pending'")
    fun carryingCount(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "messages", "direction='carry' AND status='carrying'")

    /** Anything left mid-attempt after a crash/restart goes back to its waiting state. */
    fun recoverInterrupted(): Int {
        val db = writableDatabase
        db.execSQL("UPDATE messages SET status='pending', next_attempt=0 WHERE direction='out' AND status='sending'")
        val a = DatabaseUtils.longForQuery(db, "SELECT changes()", null).toInt()
        db.execSQL("UPDATE messages SET status='carrying', next_attempt=0 WHERE direction='carry' AND status='forwarding'")
        val b = DatabaseUtils.longForQuery(db, "SELECT changes()", null).toInt()
        return a + b
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

    /** Reset backoff so every waiting message is tried at the next pump. */
    fun retryAllNow() {
        writableDatabase.execSQL("UPDATE messages SET next_attempt=0 WHERE (direction='out' AND status='pending') OR (direction='carry' AND status='carrying')")
    }

    /** Reset backoff for everything waiting on [peerShort] (as destination or as relay target). Returns how many. */
    fun retryNowFor(peerShort: String): Int {
        val db = writableDatabase
        db.execSQL(
            "UPDATE messages SET next_attempt=0 WHERE ((direction='out' AND status='pending') OR (direction='carry' AND status='carrying')) AND substr(dest_id,1,8)=?",
            arrayOf(peerShort)
        )
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
}
