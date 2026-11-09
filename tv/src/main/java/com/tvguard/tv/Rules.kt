package com.tvguard.tv

import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Pure decision logic, kept free of Android classes so it can be unit tested. */
object Rules {

    data class Decision(
        val blockApps: Boolean,
        val bedtime: Boolean,
        /** bedtime, blocked, schedule, limit, or empty when nothing is locked. */
        val reason: String,
        /** Today's limit including bonus time; 0 means no limit. */
        val limitMinutes: Int,
        val limitReached: Boolean,
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

    fun limitFor(config: GuardConfig, now: LocalDateTime): Int {
        val base = if (isWeekend(now)) config.weekendLimitMinutes else config.dailyLimitMinutes
        if (base <= 0) return 0
        val bonus = if (config.bonusDate == now.format(DATE)) config.bonusMinutes.coerceAtLeast(0) else 0
        return base + bonus
    }

    fun decide(config: GuardConfig, usedMinutes: Int, now: LocalDateTime): Decision {
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

        val reason = when {
            bedtime -> "bedtime"
            config.blocked -> "blocked"
            outsideSchedule -> "schedule"
            limitReached -> "limit"
            else -> ""
        }
        return Decision(
            blockApps = reason.isNotEmpty(),
            bedtime = bedtime,
            reason = reason,
            limitMinutes = limit,
            limitReached = limitReached,
        )
    }
}
