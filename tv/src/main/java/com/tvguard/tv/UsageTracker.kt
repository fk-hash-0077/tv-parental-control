package com.tvguard.tv

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.time.LocalDate
import java.time.ZoneId

/** Measures how long the given apps were on screen, per local day. */
class UsageTracker(private val context: Context) {

    fun hasAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun minutesToday(packages: Collection<String>): Int =
        minutesBetween(packages, startOfDay(LocalDate.now()), System.currentTimeMillis())

    /** Minutes per day for the last [days] days including today, keyed yyyy-MM-dd. */
    fun history(packages: Collection<String>, days: Int): Map<String, Int> {
        val today = LocalDate.now()
        val now = System.currentTimeMillis()
        return (0 until days).associate { back ->
            val day = today.minusDays(back.toLong())
            val end = minOf(now, startOfDay(day.plusDays(1)))
            day.format(Rules.DATE) to minutesBetween(packages, startOfDay(day), end)
        }
    }

    private fun minutesBetween(packages: Collection<String>, start: Long, end: Long): Int {
        if (packages.isEmpty() || !hasAccess() || end <= start) return 0
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val events = usm.queryEvents(start, end)
        val event = UsageEvents.Event()

        // Resumed activity count and since-when, per package, so switching
        // between two screens of the same app doesn't double count.
        val openSince = HashMap<String, Long>()
        val openCount = HashMap<String, Int>()
        val seen = HashSet<String>()
        var totalMs = 0L

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName
            if (pkg !in packages) continue
            @Suppress("DEPRECATION")
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> {
                    seen += pkg
                    val count = openCount[pkg] ?: 0
                    if (count == 0) openSince[pkg] = event.timeStamp
                    openCount[pkg] = count + 1
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val count = openCount[pkg] ?: 0
                    if (count == 0 && pkg !in seen) {
                        // Opened before the start of the range and still open after it.
                        totalMs += event.timeStamp - start
                    } else if (count == 1) {
                        totalMs += event.timeStamp - (openSince[pkg] ?: event.timeStamp)
                    }
                    seen += pkg
                    openCount[pkg] = (count - 1).coerceAtLeast(0)
                }
            }
        }
        for ((pkg, count) in openCount) {
            if (count > 0) totalMs += end - (openSince[pkg] ?: end)
        }
        return (totalMs / 60_000).toInt()
    }

    private fun startOfDay(day: LocalDate): Long =
        day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
}
