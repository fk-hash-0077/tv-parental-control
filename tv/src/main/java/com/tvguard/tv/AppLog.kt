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
