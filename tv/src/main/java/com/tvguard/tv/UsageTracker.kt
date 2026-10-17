package com.tvguard.tv

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.util.Calendar

/** Measures how long the given apps were on screen since local midnight. */
class UsageTracker(private val context: Context) {

    fun hasAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun minutesToday(packages: Collection<String>): Int {
        if (packages.isEmpty() || !hasAccess()) return 0
        val usm = context.getSystemService(UsageStatsManager::class.java)
        val start = startOfToday()
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(start, now)
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
                        // Opened before midnight and still open after it.
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
            if (count > 0) totalMs += now - (openSince[pkg] ?: now)
        }
        return (totalMs / 60_000).toInt()
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis
}
