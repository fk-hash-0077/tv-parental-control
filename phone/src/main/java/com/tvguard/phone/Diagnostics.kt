package com.tvguard.phone

import android.app.Activity
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.firebase.database.DataSnapshot
import java.text.DateFormat
import java.util.Date

/**
 * The Diagnostics dialog: what the phone and TV know about their connection, plus the TV's log
 * after "Send logs". Share sends it all as text, for example to paste into a chat for help.
 */
class Diagnostics(
    private val activity: Activity,
    private val code: String,
    private val phoneOnline: () -> Boolean,
    private val status: () -> DataSnapshot?,
    private val diag: () -> DataSnapshot?,
    private val logs: () -> DataSnapshot?,
    private val requestLogs: () -> Long,
    private val config: () -> Map<*, *> = { emptyMap<String, Any>() },
) {
    private val handler = Handler(Looper.getMainLooper())
    private var requestedAt = 0L

    fun show() {
        val pad = (20 * activity.resources.displayMetrics.density).toInt()
        val text = TextView(activity).apply {
            setPadding(pad, pad / 2, pad, 0)
            setTextIsSelectable(true)
            textSize = 14f
        }
        val scroll = ScrollView(activity).apply { addView(text) }
        val refresh = object : Runnable {
            override fun run() {
                text.text = summary()
                handler.postDelayed(this, 1_000)
            }
        }
        val dialog = AlertDialog.Builder(activity)
            .setTitle("Diagnostics")
            .setView(scroll)
            .setPositiveButton("Share", null)
            .setNeutralButton("Send logs", null)
            .setNegativeButton("Close", null)
            .setOnDismissListener { handler.removeCallbacks(refresh) }
            .show()
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            requestedAt = requestLogs()
            text.text = summary()
        }
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener { share() }
        handler.post(refresh)
    }

    private fun share() {
        val logText = logs()?.child("text")?.getValue(String::class.java).orEmpty()
        val body = buildString {
            append(summary())
            if (logText.isNotEmpty()) {
                append("\n\n--- TV log ---\n")
                append(logText)
            }
        }
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "TV Guard diagnostics")
            .putExtra(Intent.EXTRA_TEXT, body)
        activity.startActivity(Intent.createChooser(intent, "Share diagnostics"))
    }

    private fun summary(): String {
        val now = System.currentTimeMillis()
        val s = status()
        val d = diag()
        val l = logs()
        fun long(snap: DataSnapshot?, key: String) = (snap?.child(key)?.value as? Number)?.toLong() ?: 0L
        fun str(snap: DataSnapshot?, key: String) = snap?.child(key)?.getValue(String::class.java).orEmpty()
        fun whenAgo(millis: Long) = if (millis <= 0) "never" else "${time(millis)} (${ago(now - millis)})"

        val lines = mutableListOf<String>()
        lines += "PHONE"
        lines += "App version: ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})"
        lines += "This phone online: ${if (phoneOnline()) "yes" else "no"}"
        lines += "TV code: ${code.chunked(4).joinToString("-")}"
        lines += ""
        lines += "TV"
        lines += "Last report from the TV: ${whenAgo(long(s, "updatedAt"))}"
        if (d == null || !d.exists()) {
            lines += "No connection details yet (the TV needs version 12 or newer)."
        } else {
            val tvName = str(d, "versionName")
            lines += "TV Guard version: " +
                if (tvName.isEmpty() || tvName.startsWith("0.")) "build ${long(d, "version")}" else "$tvName (build ${long(d, "version")})"
            lines += "TV: ${str(d, "model")}, Android ${str(d, "android")}"
            lines += "TV thinks it's connected: ${if (d.child("connected").value == true) "yes" else "no"}"
            lines += "Last confirmed save: ${whenAgo(long(d, "confirmedAt"))}"
            str(d, "syncError").takeIf { it.isNotEmpty() }?.let { lines += "Last sync error: $it" }
            lines += "Reconnects since start: ${long(d, "reconnects")}"
            val lastReconnect = long(d, "lastReconnectAt")
            if (lastReconnect > 0) lines += "Last reconnect: ${whenAgo(lastReconnect)}, ${str(d, "lastReconnectReason")}"
            lines += "Running since: ${whenAgo(long(d, "serviceStartedAt"))}"
            d.child("startedBy").getValue(String::class.java)?.takeIf { it.isNotEmpty() }?.let {
                lines += "Started by: $it"
            }
            lines += "Lock: ${str(d, "lockReason").ifEmpty { "unlocked" }}"
        }
        lines += lockNowLines(config()) { "on ${time(it)}" }
        lines += "Last Sync now answered: ${whenAgo(long(s, "pongAt"))}"
        lines += ""
        lines += "PROTECTION"
        lines += protectionText(d) { time(it) }
        str(s, "update").takeIf { it.isNotEmpty() }?.let { lines += "Update: $it" }
        lines += ""
        lines += "LOGS"
        val logsAt = long(l, "at")
        val logsFor = long(l, "requestAt")
        lines += when {
            requestedAt > 0 && logsFor < requestedAt -> "Asked the TV for its log. Waiting… (the TV must be on and connected)"
            logsAt > 0 -> {
                val count = str(l, "text").lines().count { it.isNotBlank() }
                "Received ${whenAgo(logsAt)}, $count lines. Tap Share to send them."
            }
            else -> "Tap Send logs to fetch the TV's log for the last 24 hours."
        }
        return lines.joinToString("\n")
    }

    companion object {
        /**
         * Who last pressed Lock now and Unlock TV, and when. Phones before version 1.0 didn't
         * record this, so an older lock shows as "a phone (not recorded)".
         */
        /** [fmt] turns a time into a phrase like "at 3:40 PM" or "on 21/11/26 3:40 PM". */
        fun lockNowLines(config: Map<*, *>, fmt: (Long) -> String): List<String> {
            val on = config["lockNow"] as? Boolean ?: false
            val by = config["lockNowBy"] as? String
            val at = (config["lockNowAt"] as? Number)?.toLong() ?: 0L
            val offBy = config["lockNowOffBy"] as? String
            val offAt = (config["lockNowOffAt"] as? Number)?.toLong() ?: 0L
            val lines = mutableListOf<String>()
            lines += "Lock now: " + if (on) "ON" else "off"
            lines += if (!by.isNullOrEmpty() && at > 0) {
                "Last locked by $by ${fmt(at)}"
            } else if (on) {
                "Locked by a phone (not recorded; that phone has an older app)"
            } else {
                "Last locked by: not recorded yet"
            }
            if (!offBy.isNullOrEmpty() && offAt > 0) lines += "Last unlocked by $offBy ${fmt(offAt)}"
            return lines
        }

        /**
         * What keeps TV Guard running on the TV, and every time in the last 7 days it wasn't
         * running while the TV was being watched, with Android's reason. [fmt] formats a time.
         */
        fun protectionText(d: DataSnapshot?, fmt: (Long) -> String): String {
            if (d == null || !d.exists() || !d.hasChild("forceStopBlocked")) {
                return "Shows here once the TV has the newest TV Guard."
            }
            fun yes(key: String) = d.child(key).getValue(Boolean::class.java) == true
            val lines = mutableListOf<String>()
            val gaps = d.child("gaps").children.toList()
            if (gaps.isEmpty()) {
                lines += "✓ No gaps. TV Guard ran whenever the TV was used."
            } else {
                val total = gaps.sumOf { (it.child("minutes").value as? Number)?.toInt() ?: 0 }
                lines += "⚠ ${gaps.size} gap${if (gaps.size > 1) "s" else ""}: TV Guard wasn't running for $total min in total."
                for (g in gaps.reversed()) {
                    val from = (g.child("from").value as? Number)?.toLong() ?: 0L
                    val minutes = (g.child("minutes").value as? Number)?.toInt() ?: 0
                    val watched = (g.child("watched").value as? Number)?.toInt() ?: 0
                    val why = g.child("why").getValue(String::class.java).orEmpty()
                    val back = g.child("cameBack").getValue(String::class.java).orEmpty()
                    lines += ""
                    lines += "${fmt(from)}, $minutes min ($watched min watched)"
                    if (why.isNotEmpty()) lines += "  Why: $why"
                    if (back.isNotEmpty()) lines += "  Came back: $back"
                }
            }
            val crashAt = (d.child("lastCrashAt").value as? Number)?.toLong() ?: 0L
            val crash = d.child("lastCrash").getValue(String::class.java).orEmpty()
            if (crashAt > 0 && crash.isNotEmpty()) {
                lines += ""
                lines += "Last crash: ${fmt(crashAt)}"
                lines += "  $crash"
            }
            lines += ""
            lines += "${if (yes("forceStopBlocked")) "✓" else "✗"} Can't be force-stopped"
            lines += "${if (yes("lifeline")) "✓" else "✗"} Lifeline connected" +
                if (!yes("titleAccess")) " (turn on title access over adb, see README)" else ""
            return lines.joinToString("\n")
        }
    }

    private fun time(millis: Long) = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(millis))

    private fun ago(ms: Long): String {
        val minutes = ms / 60_000
        return when {
            minutes < 1 -> "just now"
            minutes < 60 -> "$minutes min ago"
            minutes < 48 * 60 -> "${minutes / 60} h ago"
            else -> "${minutes / 1440} days ago"
        }
    }
}
