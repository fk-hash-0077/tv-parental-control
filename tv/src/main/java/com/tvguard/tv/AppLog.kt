package com.tvguard.tv

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A small log kept in the app's own storage, so the phone's "Send logs" can fetch what happened
 * on the TV. Also writes to the normal Android log. The file is trimmed to its newest half once it
 * passes [MAX_BYTES]; nothing personal is logged, only what TV Guard itself did.
 */
object AppLog {
    private const val MAX_BYTES = 512 * 1024
    private val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    @Volatile private var file: File? = null

    fun init(context: Context) {
        if (file == null) file = File(context.applicationContext.filesDir, "tvguard.log")
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        write("I", tag, message)
    }

    fun w(tag: String, message: String, error: Throwable? = null) {
        if (error != null) Log.w(tag, message, error) else Log.w(tag, message)
        write("W", tag, if (error != null) "$message: ${error.javaClass.simpleName}: ${error.message}" else message)
    }

    /** An error with its stack trace (the first [MAX_TRACE_LINES] lines), e.g. a step of a check that failed. */
    fun e(tag: String, message: String, error: Throwable) {
        Log.e(tag, message, error)
        write("E", tag, "$message\n${trace(error)}")
    }

    /**
     * Called just before Android closes TV Guard for an uncaught exception: logs the stack trace
     * and keeps a short copy that the next start reports to the phone (see Protection).
     */
    fun recordCrash(context: Context, thread: Thread, error: Throwable) {
        init(context)
        val trace = trace(error)
        Log.e("TvGuardCrash", "Crashed in thread ${thread.name}", error)
        write("E", "TvGuardCrash", "Crashed in thread ${thread.name}\n$trace")
        context.getSharedPreferences("guard", Context.MODE_PRIVATE).edit()
            .putLong(KEY_CRASH_AT, System.currentTimeMillis())
            .putString(KEY_CRASH, "thread ${thread.name}: ${trace.lineSequence().take(6).joinToString(" | ")}")
            // commit, not apply: the process is about to end.
            .commit()
    }

    /** The last crash recorded by [recordCrash], as (when, short trace), or null. */
    fun lastCrash(context: Context): Pair<Long, String>? {
        val prefs = context.getSharedPreferences("guard", Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY_CRASH_AT, 0)
        val text = prefs.getString(KEY_CRASH, null)
        return if (at > 0 && text != null) at to text else null
    }

    /** The exception and its causes, one frame per line, at most [MAX_TRACE_LINES] lines. */
    private fun trace(error: Throwable): String =
        Log.getStackTraceString(error).lineSequence()
            .filter { it.isNotBlank() }
            .take(MAX_TRACE_LINES)
            .joinToString("\n") { "    " + it.trim() }

    private const val MAX_TRACE_LINES = 40
    private const val KEY_CRASH = "last_crash"
    private const val KEY_CRASH_AT = "last_crash_at"

    @Synchronized
    private fun write(level: String, tag: String, message: String) {
        val f = file ?: return
        runCatching {
            f.appendText("${time.format(Date())} $level $tag: $message\n")
            if (f.length() > MAX_BYTES) {
                val text = f.readText()
                f.writeText(text.substring(text.indexOf('\n', text.length / 2) + 1))
            }
        }
    }

    /** Lines from the last [hours] hours, newest last, at most [maxChars] characters. */
    @Synchronized
    fun recent(hours: Int, maxChars: Int): String {
        val f = file ?: return ""
        val text = runCatching { f.readText() }.getOrDefault("")
        val cutoff = time.format(Date(System.currentTimeMillis() - hours * 3_600_000L))
        val lines = text.lineSequence().filter { it.length >= 19 && it.substring(0, 19) >= cutoff }.toList()
        val joined = lines.joinToString("\n")
        return if (joined.length > maxChars) joined.substring(joined.length - maxChars) else joined
    }
}
