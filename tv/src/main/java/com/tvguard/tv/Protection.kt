package com.tvguard.tv

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.ApplicationExitInfo
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Keeps TV Guard running, and records when it wasn't.
 *
 * On this TCL TV the built-in cleaner can force-stop TV Guard. After a force-stop Android
 * cancels its jobs and alarms and won't send it "TV turned on" again until someone opens it by
 * hand. So TV Guard comes back several ways:
 *  - Device Owner marks it as an app the user (and Settings) can't force-stop (Policy.harden).
 *  - The Now playing title access is a "lifeline": Android reconnects it by itself after
 *    power-on and after TV Guard dies, and that starts the guard (MediaListener).
 *  - A 5-minute alarm, re-armed on every check, so it only goes off when checks have stopped.
 *  - The 15-minute keep-alive job and the boot / wake broadcasts, as before.
 *
 * Every start compares with the last check. If TV Guard was away while the TV was being
 * watched, that's a gap: logged with Android's own record of why it stopped, shown on the
 * phone's Diagnostics, and sent to the phones as a notification.
 */
object Protection {
    private const val TAG = "TvGuardProtect"
    private const val ALARM_MS = 5 * 60_000L
    /** Shorter than this isn't a gap: checks run every 30 seconds, and a restart takes a moment. */
    private const val GAP_MS = 3 * 60_000L
    private const val KEEP_DAYS = 7
    private const val MAX_GAPS = 30

    /** (Re)arms the 5-minute alarm. Called on every check, so it only fires once checks stop. */
    fun armAlarm(context: Context) {
        runCatching {
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            am.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + ALARM_MS,
                alarmIntent(context),
            )
        }.onFailure { AppLog.w(TAG, "Couldn't set the 5-minute alarm", it) }
    }

    private fun alarmIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, AlarmReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** True if Android won't let anyone force-stop TV Guard or clear its data. */
    fun forceStopBlocked(context: Context): Boolean = Policy(context).forceStopBlocked

    /**
     * Called once when the service starts, with when the last check ran before it.
     * Runs on a background thread: reading usage takes a moment.
     */
    fun checkGap(context: Context, lastAliveAt: Long, startedBy: String) {
        val now = System.currentTimeMillis()
        val why = lastExit(context, lastAliveAt)
        if (why.isNotEmpty()) AppLog.i(TAG, "Last stop: $why")
        if (lastAliveAt <= 0 || now - lastAliveAt < GAP_MS) return
        val minutes = ((now - lastAliveAt) / 60_000).toInt()
        val watched = runCatching { UsageTracker(context).watchedBetween(lastAliveAt, now) }.getOrDefault(0)
        AppLog.i(TAG, "TV Guard wasn't running for $minutes min; $watched min watched meanwhile")
        // TV off or in standby the whole time is normal. Only a gap with watching matters.
        if (watched <= 0) return
        val gap = JSONObject()
            .put("from", lastAliveAt)
            .put("to", now)
            .put("minutes", minutes)
            .put("watched", watched)
            .put("why", why.ifEmpty { "Unknown" })
            .put("cameBack", startedBy)
        val store = Store(context)
        val weekAgo = now - KEEP_DAYS * 86_400_000L
        store.gaps = (store.gaps.filter { it.optLong("to") > weekAgo } + gap).takeLast(MAX_GAPS)
        GuardService.current?.sendEvent(
            "gap",
            mapOf("minutes" to minutes, "watched" to watched, "since" to clock(lastAliveAt)),
        )
    }

    /** For the phone's Diagnostics: the protection switches and the last week's gaps. */
    fun diag(context: Context): Map<String, Any> {
        val weekAgo = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
        val gaps = Store(context).gaps.filter { it.optLong("to") > weekAgo }.map { g ->
            mapOf(
                "from" to g.optLong("from"),
                "to" to g.optLong("to"),
                "minutes" to g.optInt("minutes"),
                "watched" to g.optInt("watched"),
                "why" to g.optString("why"),
                "cameBack" to g.optString("cameBack"),
            )
        }
        val crash = AppLog.lastCrash(context)?.takeIf { it.first > weekAgo }
        return mapOf(
            "lastCrashAt" to (crash?.first ?: 0L),
            "lastCrash" to (crash?.second ?: ""),
            "forceStopBlocked" to forceStopBlocked(context),
            "lifeline" to NowPlaying.connected,
            "titleAccess" to NowPlaying.hasAccess(context),
            "gaps" to gaps,
        )
    }

    /** Android's record of why TV Guard's last process ended, in plain words, if it was after [since]. */
    private fun lastExit(context: Context, since: Long): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return ""
        val bootAt = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val info = runCatching {
            context.getSystemService(ActivityManager::class.java)
                .getHistoricalProcessExitReasons(context.packageName, 0, 1)
                .firstOrNull()
        }.getOrNull()
        if (info == null || info.timestamp < since - 60_000) {
            return if (since in 1 until bootAt) "The TV was switched off or restarted" else ""
        }
        val text = when (info.reason) {
            ApplicationExitInfo.REASON_USER_REQUESTED -> "Force-stopped (by the TV's cleaner or in Settings)"
            ApplicationExitInfo.REASON_USER_STOPPED -> "Stopped by the user"
            ApplicationExitInfo.REASON_LOW_MEMORY -> "Closed by Android to free memory"
            ApplicationExitInfo.REASON_SIGNALED, ApplicationExitInfo.REASON_OTHER -> "Closed by the TV's system"
            ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Closed for using too much battery or memory"
            ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE -> "TV Guard crashed"
            ApplicationExitInfo.REASON_ANR -> "TV Guard froze and was closed"
            ApplicationExitInfo.REASON_PERMISSION_CHANGE -> "Closed after a permission change"
            ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "Closed because a system part it uses stopped"
            ApplicationExitInfo.REASON_EXIT_SELF -> "TV Guard closed itself (update)"
            else -> "Closed (reason unknown)"
        }
        val detail = info.description?.takeIf { it.isNotBlank() }?.let { " [$it]" }.orEmpty()
        // For a crash, add where it happened, saved by the crash handler just before.
        val crash = AppLog.lastCrash(context)
            ?.takeIf { (at, _) -> info.reason == ApplicationExitInfo.REASON_CRASH && at >= info.timestamp - 60_000 }
            ?.let { (_, where) -> " in $where" }.orEmpty()
        return "$text at ${clock(info.timestamp)}$detail$crash"
    }

    private fun clock(millis: Long): String =
        Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()).toLocalTime()
            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
}

/** The 5-minute alarm: brings the service back if checks stopped. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (GuardService.current == null) {
            AppLog.init(context)
            AppLog.i("TvGuard", "Starting from the 5-minute alarm")
            runCatching { GuardService.start(context, "5-minute check") }
                .onFailure { AppLog.w("TvGuard", "5-minute alarm couldn't start the service", it) }
        }
        Protection.armAlarm(context)
    }
}
