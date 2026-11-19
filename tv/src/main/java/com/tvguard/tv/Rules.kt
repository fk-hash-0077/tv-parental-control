package com.tvguard.tv

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Pure decision logic, kept free of Android classes so it can be unit tested.
 *
 * The kids' rules are two questions:
 *  - When can the TV be on? "TV hours", one window for school days and one for the weekend.
 *    Outside it the TV is resting (reason "hours"). Older setups also had a separate bedtime;
 *    if one is still switched on it simply counts as outside TV hours.
 *  - How much? "Daily TV time", a total for the day (reason "limit").
 * Plus the phone's Lock now (reason "manual").
 *
 * Parent mode sits above all of it: while it's on nothing is locked or blocked, and the time
 * isn't counted (see [subtract] and UsageTracker).
 */
object Rules {

    data class Decision(
        /** The listed apps (YouTube, Roblox...) are suspended. */
        val blockApps: Boolean,
        /** The whole TV shows the lock screen. */
        val lockTv: Boolean,
        /** Why the whole TV is locked: hours, limit or manual; empty when it isn't. */
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
        /** True while parent mode is on. */
        val parentMode: Boolean = false,
        /** Whole minutes until parent mode ends, while it's on. */
        val parentMinutesLeft: Int? = null,
    )

    val DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /** Parent mode never lasts past this time of day (6:00 AM), so it can't be left on overnight. */
    const val PARENT_CUTOFF_MINUTE = 6 * 60

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

    /** TV hours for [now]'s kind of day, as (start, end) minutes of the day. */
    fun hoursFor(config: GuardConfig, now: LocalDateTime): Pair<Int, Int> =
        if (isWeekend(now)) config.weekendStart to config.weekendEnd else config.weekdayStart to config.weekdayEnd

    /** True when the TV should be resting at [now]: outside TV hours (or inside an old bedtime). */
    fun outsideHours(config: GuardConfig, now: LocalDateTime): Boolean {
        val minute = now.hour * 60 + now.minute
        val bedtime = config.bedtimeEnabled && inWindow(minute, config.bedtimeStart, config.bedtimeEnd)
        val (start, end) = hoursFor(config, now)
        return bedtime || (config.scheduleEnabled && !inWindow(minute, start, end))
    }

    /** The next moment after [now] when the TV hours allow watching, within two days; null if never. */
    fun nextOpen(config: GuardConfig, now: LocalDateTime): LocalDateTime? {
        var t = now.withSecond(0).withNano(0).plusMinutes(1)
        repeat(2 * 1440) {
            if (!outsideHours(config, t)) return t
            t = t.plusMinutes(1)
        }
        return null
    }

    /** When the kids can watch again after today's TV time ran out: the first open minute tomorrow. */
    fun nextOpenTomorrow(config: GuardConfig, now: LocalDateTime): LocalDateTime? {
        val midnight = now.toLocalDate().plusDays(1).atStartOfDay()
        return if (!outsideHours(config, midnight)) midnight else nextOpen(config, midnight)
    }

    /**
     * When parent mode that started at [from] must end at the latest: the first 6:00 AM at
     * least an hour after it started. Started in the evening or night, that's the next morning.
     */
    fun parentCutoff(from: Long, zone: ZoneId): Long {
        val start = Instant.ofEpochMilli(from).atZone(zone).plusHours(1)
        var cutoff = start.with(LocalTime.of(PARENT_CUTOFF_MINUTE / 60, PARENT_CUTOFF_MINUTE % 60))
        if (!cutoff.isAfter(start)) cutoff = cutoff.plusDays(1)
        return cutoff.toInstant().toEpochMilli()
    }

    /** When parent mode set from the phone really ends: its end, or the 6 AM cutoff if sooner. */
    fun parentEnd(from: Long, until: Long, zone: ZoneId): Long =
        if (from <= 0 || until <= from) 0 else minOf(until, parentCutoff(from, zone))

    /**
     * @param usedMinutes kids' TV time today (parent mode time already left out).
     * @param overrideUntil epoch millis until which the TV stays unlocked, or 0.
     * @param nowMillis the same moment as [now], in epoch millis.
     * @param parentUntil epoch millis until which parent mode is on, or 0.
     */
    fun decide(
        config: GuardConfig,
        usedMinutes: Int,
        now: LocalDateTime,
        overrideUntil: Long = 0,
        nowMillis: Long = 0,
        parentUntil: Long = 0,
    ): Decision {
        val kids = decideKids(config, usedMinutes, now, overrideUntil, nowMillis)
        if (parentUntil <= nowMillis) return kids
        val left = ((parentUntil - nowMillis + 59_999) / 60_000).toInt()
        // Warn before parent mode ends only if the kids' rules will lock the TV then.
        val atEnd = now.plusMinutes(left.toLong())
        val endMillis = nowMillis + left * 60_000L
        val locksAfter = decideKids(config, usedMinutes, atEnd, overrideUntil, endMillis).lockTv
        return kids.copy(
            blockApps = false,
            lockTv = false,
            reason = "",
            overridden = false,
            minutesToLock = if (locksAfter && left in 1..60) left else null,
            parentMode = true,
            parentMinutesLeft = left,
        )
    }

    private fun decideKids(
        config: GuardConfig,
        usedMinutes: Int,
        now: LocalDateTime,
        overrideUntil: Long,
        nowMillis: Long,
    ): Decision {
        val minute = now.hour * 60 + now.minute
        val resting = outsideHours(config, now)
        val (_, end) = hoursFor(config, now)

        val limit = limitFor(config, now)
        val limitReached = limit > 0 && usedMinutes >= limit

        val wouldLock = when {
            resting -> "hours"
            limitReached -> "limit"
            config.lockNow -> "manual"
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

    /**
     * [intervals] (pkg, from, to) with every part that falls inside one of [cuts] (from, to)
     * removed. Used to leave parent mode out of the kids' TV time.
     */
    fun subtract(
        intervals: List<Triple<String, Long, Long>>,
        cuts: List<Pair<Long, Long>>,
    ): List<Triple<String, Long, Long>> {
        if (cuts.isEmpty()) return intervals
        val sortedCuts = cuts.filter { it.second > it.first }.sortedBy { it.first }
        val result = mutableListOf<Triple<String, Long, Long>>()
        for ((pkg, from, to) in intervals) {
            var start = from
            for ((a, b) in sortedCuts) {
                if (b <= start || a >= to) continue
                if (a > start) result += Triple(pkg, start, a)
                start = maxOf(start, b)
                if (start >= to) break
            }
            if (start < to) result += Triple(pkg, start, to)
        }
        return result
    }

    /** Total length in milliseconds of the union of [ranges]. */
    fun unionMillis(ranges: List<Pair<Long, Long>>): Long {
        var total = 0L
        var curStart = -1L
        var curEnd = -1L
        for ((a, b) in ranges.filter { it.second > it.first }.sortedBy { it.first }) {
            if (a > curEnd) {
                if (curEnd > curStart) total += curEnd - curStart
                curStart = a
                curEnd = b
            } else if (b > curEnd) {
                curEnd = b
            }
        }
        if (curEnd > curStart) total += curEnd - curStart
        return total
    }
}
