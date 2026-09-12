package net.prok.proknet.core

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale

/**
 * In-app diagnostic log. Everything the BLE layer does is written here so the
 * app can be tested from the phone alone, without ADB. Also mirrored to logcat
 * and appended to a file in the app's private storage.
 */
object DiagLog {
    private const val MAX_LINES = 600
    private const val MAX_FILE_BYTES = 512 * 1024
    private val lines = ArrayDeque<String>(MAX_LINES)
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val main = Handler(Looper.getMainLooper())
    private var file: File? = null
    private val listeners = ArrayList<(String) -> Unit>()

    fun init(context: Context) {
        val f = File(context.filesDir, "proknet-log.txt")
        if (f.exists() && f.length() > MAX_FILE_BYTES) f.delete()
        file = f
        i("LOG", "---- app start " + Date() + " ----")
    }

    fun i(tag: String, msg: String) = add("I", tag, msg)
    fun w(tag: String, msg: String) = add("W", tag, msg)
    fun e(tag: String, msg: String, t: Throwable? = null) =
        add("E", tag, if (t != null) msg + ": " + t.javaClass.simpleName + ": " + t.message else msg)

    @Synchronized
    private fun add(level: String, tag: String, msg: String) {
        val line = fmt.format(Date()) + " " + level + "/" + tag + ": " + msg
        if (lines.size >= MAX_LINES) lines.removeFirst()
        lines.addLast(line)
        when (level) {
            "E" -> Log.e(tag, msg)
            "W" -> Log.w(tag, msg)
            else -> Log.i(tag, msg)
        }
        try { file?.appendText(line + "\n") } catch (_: Exception) {}
        val snapshot = ArrayList(listeners)
        main.post { snapshot.forEach { it(line) } }
    }

    @Synchronized
    fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
        try { file?.writeText("") } catch (_: Exception) {}
    }

    @Synchronized
    fun addListener(l: (String) -> Unit) { listeners.add(l) }

    @Synchronized
    fun removeListener(l: (String) -> Unit) { listeners.remove(l) }
}
