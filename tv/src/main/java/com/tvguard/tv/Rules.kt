package com.tvguard.tv

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Pure decision logic, kept free of Android classes so it can be unit tested. */
object Rules {

    data class Decision(
        /** The listed apps (YouTube, Roblox...) are suspended. */
        val blockApps: Boolean,
        /** The whole TV shows the lock screen. */
        val lockTv: Boolean,
        /** Why the whole TV is locked: bedtime, schedule or limit; empty when it isn't. */
        val reason: String,
        /** Today's total limit including bonus time; 0 means no limit. */
        val limitMinutes: Int,
        val limitReached: Boolean,
        /** True while an unlock code or a parent's approval is keeping the TV unlocked. */
        val overridden: Boolean,
        /**
         * Whole minutes until the TV locks, if it will lock within the next hour; otherwise null.
         * Used for the 5-minute warning.
         */
        val minutesToLock: Int?,
    )

    val DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    fun isWeekend(now: LocalDateTime) =
        now.dayOfWeek == DayOfWeek.SATURDAY || now.dayOfWeek == DayOfWeek.SUNDAY

    /** True if [minute] falls in [start, end). Windows may cross midnight; start == end is empty. */
    fun inWindow(minute: Int, start: Int, end: Int): Boolean = when {
        start == end -> false
        start < end -> minute in start until end
        else -> minute >= start || minute < end
    }

    /** Minutes from [minute] until the clock next reads [target], 1..1440. */
    private fun minutesUntil(minute: Int, target: Int): Int {
        val diff = ((target - minute) % 1440 + 1440) % 1440
        return if (diff == 0) 1440 else diff
    }

    fun limitFor(config: GuardConfig, now: LocalDateTime): Int {
        val base = if (isWeekend(now)) config.weekendLimitMinutes else config.dailyLimitMinutes
        if (base <= 0) return 0
        val bonus = if (config.bonusDate == now.format(DATE)) config.bonusMinutes.coerceAtLeast(0) else 0
        return base + bonus
    }

    /** The listed apps are unblocked only on the day the phone unblocked them. */
    fun appsAllowed(config: GuardConfig, now: LocalDateTime) = config.unblockedDate == now.format(DATE)

    /**
     * @param usedMinutes total TV time today.
     * @param overrideUntil epoch millis until which the TV stays unlocked, or 0.
     * @param nowMillis the same moment as [now], in epoch millis.
     */
    fun decide(
        config: GuardConfig,
        usedMinutes: Int,
        now: LocalDateTime,
        overrideUntil: Long = 0,
        nowMillis: Long = 0,
    ): Decision {
        val minute = now.hour * 60 + now.minute
        val bedtime = config.bedtimeEnabled && inWindow(minute, config.bedtimeStart, config.bedtimeEnd)

        val (start, end) = if (isWeekend(now)) {
            config.weekendStart to config.weekendEnd
        } else {
            config.weekdayStart to config.weekdayEnd
        }
        val outsideSchedule = config.scheduleEnabled && !inWindow(minute, start, end)

        val limit = limitFor(config, now)
        val limitReached = limit > 0 && usedMinutes >= limit

        val wouldLock = when {
            bedtime -> "bedtime"
            outsideSchedule -> "schedule"
            limitReached -> "limit"
            else -> ""
        }
        val overridden = wouldLock.isNotEmpty() && overrideUntil > nowMillis
        val reason = if (overridden) "" else wouldLock

        // What would lock the TV next, and when.
        val candidates = mutableListOf<Int>()
        if (reason.isEmpty()) {
            if (overridden) {
                candidates += (((overrideUntil - nowMillis) + 59_999) / 60_000).toInt()
            } else {
                if (config.bedtimeEnabled && config.bedtimeStart != config.bedtimeEnd) {
                    candidates += minutesUntil(minute, config.bedtimeStart)
                }
                if (config.scheduleEnabled) candidates += minutesUntil(minute, end)
                if (limit > 0) candidates += limit - usedMinutes
            }
        }
        val minutesToLock = candidates.filter { it in 1..60 }.minOrNull()

        return Decision(
            blockApps = !appsAllowed(config, now) || reason.isNotEmpty(),
            lockTv = reason.isNotEmpty(),
            reason = reason,
            limitMinutes = limit,
            limitReached = limitReached,
            overridden = overridden,
            minutesToLock = minutesToLock,
        )
    }
}
