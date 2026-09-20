package net.prok.proknet.core

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Frame I/O of one authenticated ProkNet link (v0.6.1). Pure Kotlin, so the
 * whole path (handshake, writer thread, tunnel frames) is tested on the JVM
 * over loopback sockets.
 *
 *   wire: [u32 len][type 1][payload]
 *
 * Writes never happen on the caller's thread: every frame goes through one
 * writer thread fed by a bounded queue. Callers on threads that may block
 * ([enqueue] with block = true) get backpressure; callers that must not block
 * (the Android main thread) get a bounded, non-blocking offer. This is what
 * makes it impossible to hit Android's NetworkOnMainThreadException again.
 */
class LinkIo(input: InputStream, output: OutputStream, private val name: String = "link") {
    private val input = DataInputStream(input.buffered(64 * 1024))
    private val output = DataOutputStream(output.buffered(64 * 1024))
    private val queue = LinkedBlockingQueue<ByteArray>(QUEUE_CAPACITY)
    @Volatile var isOpen = true
        private set
    @Volatile var closeReason = ""
        private set
    private val sent = AtomicLong(); private val received = AtomicLong()
    val bytesSent: Long get() = sent.get()
    val bytesReceived: Long get() = received.get()
    private var writer: Thread? = null
    private var reader: Thread? = null
    @Volatile var lastWriteError: String = ""
        private set

    /** Synchronous write, for the handshake (before the writer thread exists) and tests. Throws on failure. */
    fun writeNow(type: Int, payload: ByteArray) {
        val frame = Wire.frame(type, payload)
        synchronized(output) {
            output.writeInt(frame.size); output.write(frame); output.flush()
        }
        sent.addAndGet((4 + frame.size).toLong())
    }

    /** Blocking read of one frame; null at end of stream or on a malformed length. */
    fun readFrame(): Pair<Int, ByteArray>? {
        val len = try { input.readInt() } catch (e: Exception) { return null }
        if (len < 1 || len > Wire.MAX_FRAME) { closeReason = "bad frame length " + len; return null }
        val type = input.readUnsignedByte()
        val p = ByteArray(len - 1); input.readFully(p)
        received.addAndGet((4 + len).toLong())
        return type to p
    }

    /**
     * Queue a frame for the writer thread. [block] = true waits for room
     * (backpressure for data threads); false drops the frame when the queue is
     * full and returns false (for the main thread). Returns false once closed.
     */
    fun enqueue(type: Int, payload: ByteArray, block: Boolean): Boolean {
        if (!isOpen) return false
        val frame = Wire.frame(type, payload)
        return try {
            if (block) { queue.put(frame); true } else queue.offer(frame, 50, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) { false }
    }

    val queuedFrames: Int get() = queue.size

    /** Start the writer thread. Write failures close the link with the real exception in [closeReason]. */
    fun startWriter(onError: (String) -> Unit) {
        if (writer != null) return
        writer = Thread({
            try {
                while (isOpen) {
                    val frame = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
                    synchronized(output) {
                        output.writeInt(frame.size); output.write(frame)
                        // coalesce: flush once the queue is drained or a few frames went out
                        var more = 0
                        while (more < 16) { val next = queue.poll() ?: break; output.writeInt(next.size); output.write(next); sent.addAndGet((4 + next.size).toLong()); more++ }
                        output.flush()
                    }
                    sent.addAndGet((4 + frame.size).toLong())
                }
            } catch (e: Exception) {
                if (isOpen) {
                    lastWriteError = describe(e)
                    close("write failed: " + lastWriteError)
                    try { onError(closeReason) } catch (_: Exception) {}
                }
            }
        }, name + "-writer").apply { isDaemon = true; start() }
    }

    /** Start the reader thread; [onFrame] runs on it and must not block for long. */
    fun startReader(onFrame: (Int, ByteArray) -> Unit, onClosed: (String) -> Unit) {
        if (reader != null) return
        reader = Thread({
            var why = "end of stream"
            try {
                while (isOpen) {
                    val (type, p) = readFrame() ?: break
                    try { onFrame(type, p) } catch (e: Exception) { why = "frame handler: " + describe(e); break }
                }
                if (closeReason.isNotEmpty()) why = closeReason
            } catch (e: Exception) { why = "read failed: " + describe(e) }
            val wasOpen = isOpen
            close(why)
            if (wasOpen) try { onClosed(why) } catch (_: Exception) {}
        }, name + "-reader").apply { isDaemon = true; start() }
    }

    /**
     * v0.14.2: close, but let what is already queued actually go out first.
     *
     * [close] shuts the output stream immediately, so a frame handed to the writer a
     * moment earlier can still be sitting in the queue. That is fine for a link that has
     * already broken and fatal for a graceful stop: the frame at risk is the buyer's
     * countersignature on the closing figure, and losing it costs the seller the money
     * for the whole session.
     *
     * This is a wait on a condition with a bound, not a sleep: it returns as soon as the
     * queue is empty.
     */
    fun closeAfterFlush(reason: String, maxWaitMs: Long) {
        if (!isOpen) return
        val deadline = System.currentTimeMillis() + maxWaitMs
        while (isOpen && queuedFrames > 0 && System.currentTimeMillis() < deadline) {
            try { Thread.sleep(5) } catch (e: InterruptedException) { Thread.currentThread().interrupt(); break }
        }
        if (queuedFrames > 0) closeReason = "closed with " + queuedFrames + " frame(s) still queued"
        close(reason)
    }

    fun close(reason: String) {
        if (!isOpen) return
        isOpen = false
        if (closeReason.isEmpty()) closeReason = reason
        try { input.close() } catch (_: Exception) {}
        try { output.close() } catch (_: Exception) {}
    }

    companion object {
        const val QUEUE_CAPACITY = 1024

        /** Exception class + message + first useful stack frames: never just "null". */
        fun describe(e: Throwable): String {
            val frames = e.stackTrace.take(4).joinToString(" < ") { it.className.substringAfterLast('.') + "." + it.methodName + ":" + it.lineNumber }
            return e.javaClass.simpleName + (e.message?.let { ": " + it } ?: "") + " [" + frames + "]"
        }
    }
}

/** What the handshake needs from the local identity; Identity implements it, tests use a key pair. */
interface Signer {
    val idBytes: ByteArray
    val pubBytes: ByteArray
    val displayName: String
    fun sign(data: ByteArray): ByteArray
}

/**
 * Mutual signed handshake over a fresh link: HELLO both ways, then AUTH both
 * ways. Returns the verified peer record, or null with the reason in [why].
 */
object Handshake {
    class Result(val peer: Wire.IdentityRecord?, val why: String)

    fun perform(io: LinkIo, me: Signer): Result {
        return try {
            val myNonce = Crypto.randomBytes(Wire.NONCE_LEN)
            io.writeNow(Wire.FRAME_HELLO, Wire.hello(me.idBytes, me.pubBytes, me.displayName, myNonce))
            val (t1, p1) = io.readFrame() ?: return Result(null, "connection closed before HELLO")
            if (t1 != Wire.FRAME_HELLO) return Result(null, "expected HELLO, got frame type " + t1)
            val hello = Wire.parseHello(p1) ?: return Result(null, "HELLO rejected (bad record or ID/key mismatch)")
            io.writeNow(Wire.FRAME_AUTH, me.sign(Wire.authData(me.idBytes, hello.record.id, myNonce, hello.nonce)))
            val (t2, p2) = io.readFrame() ?: return Result(null, "connection closed before AUTH")
            if (t2 != Wire.FRAME_AUTH) return Result(null, "expected AUTH, got frame type " + t2)
            val ok = Crypto.verify(hello.record.pub, Wire.authData(hello.record.id, me.idBytes, hello.nonce, myNonce), p2)
            if (!ok) Result(null, "signature from prok-" + hello.record.shortId + " INVALID") else Result(hello.record, "ok")
        } catch (e: Exception) { Result(null, "handshake error: " + LinkIo.describe(e)) }
    }
}
