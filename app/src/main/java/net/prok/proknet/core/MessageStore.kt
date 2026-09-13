package net.prok.proknet.core

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.DatabaseUtils
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Outgoing message states (milestone 2A). Incoming messages are always RECEIVED. */
object MsgStatus {
    const val PENDING = "pending"       // stored, waiting for the peer to be reachable
    const val SENDING = "sending"       // one delivery attempt is in flight
    const val DELIVERED = "delivered"   // peer confirmed it stored the message (receipt)
    const val FAILED = "failed"         // peer rejected it, or too many attempts
    const val EXPIRED = "expired"       // waited longer than the TTL, never delivered
    const val RECEIVED = "received"     // incoming
}

class StoredMessage(
    val rowId: Long,
    val msgId: String,
    val direction: String,   // "in" or "out"
    val peerId: String,      // sender (in) or recipient (out) short ID hex
    val peerName: String,
    val text: String,
    val timestamp: Long,
    val status: String,
    val attempts: Int = 0,
    val nextAttempt: Long = 0,
    val lastError: String = "",
    val deliveredAt: Long = 0,
)

/** A peer we have seen at least once. Kept so messages can be queued for a peer that is not in range. */
class KnownPeer(val shortId: String, val label: String, val lastSeen: Long, val lastAddress: String)

/** Local SQLite store for messages, the delivery queue and known peers. Plain SQLiteOpenHelper: no Room, no kapt. */
class MessageStore(context: Context) : SQLiteOpenHelper(context, "proknet.db", null, 2) {

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
                "delivered_at INTEGER NOT NULL DEFAULT 0)"
        )
        db.execSQL("CREATE UNIQUE INDEX idx_msg ON messages(msg_id, direction)")
        createPeers(db)
    }

    private fun createPeers(db: SQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS peers(" +
                "short_id TEXT PRIMARY KEY," +
                "label TEXT NOT NULL," +
                "last_seen INTEGER NOT NULL," +
                "last_address TEXT NOT NULL)"
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            // v0.1 -> v0.2: keep existing messages, add queue columns, map old 'sent' to 'delivered'.
            db.execSQL("ALTER TABLE messages ADD COLUMN attempts INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN next_attempt INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE messages ADD COLUMN last_error TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE messages ADD COLUMN delivered_at INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE messages SET status='delivered' WHERE status='sent'")
            db.execSQL("UPDATE messages SET status='pending' WHERE status='sending' OR status='failed'")
            createPeers(db)
        }
    }

    // ---- messages ------------------------------------------------------------------------------

    /** Returns false if this msgId/direction already exists (duplicate delivery). */
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
        }
        return writableDatabase.insertWithOnConflict("messages", null, cv, SQLiteDatabase.CONFLICT_IGNORE) != -1L
    }

    fun setStatus(msgId: String, status: String, error: String = "", nextAttempt: Long = 0, bumpAttempts: Boolean = false) {
        val sql = "UPDATE messages SET status=?, last_error=?, next_attempt=?" +
            (if (bumpAttempts) ", attempts=attempts+1" else "") +
            (if (status == MsgStatus.DELIVERED) ", delivered_at=" + System.currentTimeMillis() else "") +
            " WHERE msg_id=? AND direction='out'"
        writableDatabase.execSQL(sql, arrayOf(status, error, nextAttempt, msgId))
    }

    /** Oldest-first pending outgoing messages, optionally for one peer. */
    fun pending(peerId: String? = null): List<StoredMessage> {
        val where = "direction='out' AND status='pending'" + (if (peerId != null) " AND peer_id=?" else "")
        val args = if (peerId != null) arrayOf(peerId) else emptyArray()
        return query("SELECT * FROM messages WHERE " + where + " ORDER BY ts ASC, id ASC", args)
    }

    fun pendingCount(): Long = DatabaseUtils.queryNumEntries(readableDatabase, "messages", "direction='out' AND status='pending'")

    /** Anything left in 'sending' after a crash/restart goes back to 'pending'. */
    fun recoverInterrupted(): Int {
        val db = writableDatabase
        db.execSQL("UPDATE messages SET status='pending', next_attempt=0 WHERE direction='out' AND status='sending'")
        return DatabaseUtils.longForQuery(db, "SELECT changes()", null).toInt()
    }

    fun expireOlderThan(cutoffTs: Long): List<StoredMessage> {
        val victims = query("SELECT * FROM messages WHERE direction='out' AND status='pending' AND ts<?", arrayOf(cutoffTs.toString()))
        if (victims.isNotEmpty()) {
            writableDatabase.execSQL("UPDATE messages SET status='expired', last_error='ttl exceeded' WHERE direction='out' AND status='pending' AND ts<?", arrayOf(cutoffTs))
        }
        return victims
    }

    /** Reset backoff so every pending message is tried at the next pump. */
    fun retryAllNow() {
        writableDatabase.execSQL("UPDATE messages SET next_attempt=0 WHERE direction='out' AND status='pending'")
    }

    /** Reset backoff for one peer (it just reappeared). Returns how many messages are waiting for it. */
    fun retryNowFor(peerId: String): Int {
        val db = writableDatabase
        db.execSQL("UPDATE messages SET next_attempt=0 WHERE direction='out' AND status='pending' AND peer_id=?", arrayOf(peerId))
        return DatabaseUtils.queryNumEntries(db, "messages", "direction='out' AND status='pending' AND peer_id=?", arrayOf(peerId)).toInt()
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
    )

    // ---- known peers ---------------------------------------------------------------------------

    fun rememberPeer(shortId: String, label: String, address: String, seenAt: Long) {
        val cv = ContentValues().apply {
            put("short_id", shortId); put("label", label); put("last_seen", seenAt); put("last_address", address)
        }
        writableDatabase.insertWithOnConflict("peers", null, cv, SQLiteDatabase.CONFLICT_REPLACE)
    }

    fun knownPeers(): List<KnownPeer> {
        val out = ArrayList<KnownPeer>()
        readableDatabase.rawQuery("SELECT short_id,label,last_seen,last_address FROM peers ORDER BY last_seen DESC", null).use { c ->
            while (c.moveToNext()) out.add(KnownPeer(c.getString(0), c.getString(1), c.getLong(2), c.getString(3)))
        }
        return out
    }
}
