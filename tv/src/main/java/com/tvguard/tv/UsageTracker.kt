package com.tvguard.tv

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import android.service.dreams.DreamService
import java.time.LocalDate
import java.time.ZoneId

/**
 * Measures total TV time: how long any app was on screen, per local day. The home screen,
 * the screensaver, Settings and TV Guard itself don't count. Live TV and HDMI inputs do,
 * because the TV shows them through an app.
 */
class UsageTracker(private val context: Context) {

    data class Usage(val totalMinutes: Int, val minutesByApp: Map<String, Int>)

    fun hasAccess(): Boolean {
        val appOps = context.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        val mode = appOps.checkOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun today(): Usage = between(startOfDay(LocalDate.now()), System.currentTimeMillis())

    /** Total minutes per day for the last [days] days including today, keyed yyyy-MM-dd. */
    fun history(days: Int): Map<String, Int> {
        val today = LocalDate.now()
        val now = System.currentTimeMillis()
        return (0 until days).associate { back ->
            val day = today.minusDays(back.toLong())
            val end = minOf(now, startOfDay(day.plusDays(1)))
            day.format(Rules.DATE) to between(startOfDay(day), end).totalMinutes
        }
    }

    /** Packages whose time on screen isn't TV watching. */
    private fun excluded(): Set<String> {
        val pm = context.packageManager
        val homes = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .map { it.activityInfo.packageName }
        val dreams = pm.queryIntentServices(Intent(DreamService.SERVICE_INTERFACE), 0)
            .map { it.serviceInfo.packageName }
        return (homes + dreams + ALWAYS_EXCLUDED + context.packageName).toSet()
    }

    private fun between(start: Long, end: Long): Usage {
        if (!hasAccess() || end <= start) return Usage(0, emptyMap())
        val skip = excluded()
        val usm = context.getSystemService(UsageStatsManager::class.java)
        // Look back a little before the start, to catch an app that was already open at midnight.
        val events = usm.queryEvents(start - LOOKBACK_MS, end)
        val event = UsageEvents.Event()

        // Open intervals: per package, how many of its activities are resumed and since when.
        val openCount = HashMap<String, Int>()
        val openSince = HashMap<String, Long>()
        val intervals = mutableListOf<Triple<String, Long, Long>>()
        var lastTime = start - LOOKBACK_MS

        fun closeAll(at: Long) {
            for ((pkg, count) in openCount) {
                if (count > 0) intervals += Triple(pkg, openSince[pkg] ?: at, at)
            }
            openCount.clear()
            openSince.clear()
        }

        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val pkg = event.packageName
            val time = event.timeStamp
            @Suppress("DEPRECATION")
            when (event.eventType) {
                UsageEvents.Event.MOVE_TO_FOREGROUND -> if (pkg !in skip) {
                    val count = openCount[pkg] ?: 0
                    if (count == 0) openSince[pkg] = time
                    openCount[pkg] = count + 1
                }
                UsageEvents.Event.MOVE_TO_BACKGROUND -> {
                    val count = openCount[pkg] ?: 0
                    if (count == 1) intervals += Triple(pkg, openSince[pkg] ?: time, time)
                    if (count <= 1) {
                        openCount.remove(pkg)
                        openSince.remove(pkg)
                    } else {
                        openCount[pkg] = count - 1
                    }
                }
                // Standby, screen off or shutdown: nothing is being watched.
                EVENT_SCREEN_NON_INTERACTIVE, EVENT_DEVICE_SHUTDOWN -> closeAll(time)
                // After a restart, anything still "open" was cut off when the TV went down.
                EVENT_DEVICE_STARTUP -> closeAll(lastTime)
            }
            lastTime = time
        }
        closeAll(end)

        // Clip to the day, then sum per app and take the union for the total,
        // so two apps on screen at once (picture in picture) count once.
        val clipped = intervals.mapNotNull { (pkg, from, to) ->
            val a = maxOf(from, start)
            val b = minOf(to, end)
            if (b > a) Triple(pkg, a, b) else null
        }
        val perApp = clipped.groupBy { it.first }
            .mapValues { (_, list) -> (list.sumOf { it.third - it.second } / 60_000).toInt() }
            .filterValues { it > 0 }

        var totalMs = 0L
        var curStart = -1L
        var curEnd = -1L
        for ((_, a, b) in clipped.sortedBy { it.second }) {
            if (a > curEnd) {
                if (curEnd > curStart) totalMs += curEnd - curStart
                curStart = a
                curEnd = b
            } else if (b > curEnd) {
                curEnd = b
            }
        }
        if (curEnd > curStart) totalMs += curEnd - curStart

        return Usage((totalMs / 60_000).toInt(), perApp)
    }

    private fun startOfDay(day: LocalDate): Long =
        day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

    companion object {
        private const val LOOKBACK_MS = 12 * 60 * 60_000L

        // UsageEvents.Event constants added in API 28/29, spelled out so older SDK checks don't matter.
        private const val EVENT_SCREEN_NON_INTERACTIVE = 16
        private const val EVENT_DEVICE_SHUTDOWN = 26
        private const val EVENT_DEVICE_STARTUP = 27

        private val ALWAYS_EXCLUDED = setOf(
            "com.android.tv.settings",
            "com.android.settings",
            "com.android.systemui",
            "com.google.android.tvlauncher",
            "com.google.android.apps.tv.launcherx",
            "com.google.android.apps.tv.dreamx",
            "com.google.android.backdrop",
            "com.android.vending",
            "com.google.android.packageinstaller",
            "com.android.packageinstaller",
            "com.google.android.permissioncontroller",
        )
    }
}
