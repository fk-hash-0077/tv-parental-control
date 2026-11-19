package com.tvguard.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class RulesTest {
    // 2026-11-09 is a Monday, 2026-11-14 a Saturday.
    private fun monday(h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, 9, h, m)
    private fun saturday(h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, 14, h, m)
    private fun millis(t: LocalDateTime) = t.atZone(ZoneId.of("UTC")).toInstant().toEpochMilli()

    @Test
    fun windowsIncludeStartAndExcludeEnd() {
        assertTrue(Rules.inWindow(900, 900, 1140))
        assertFalse(Rules.inWindow(1140, 900, 1140))
        assertFalse(Rules.inWindow(600, 600, 600))
    }

    @Test
    fun windowsCanCrossMidnight() {
        assertTrue(Rules.inWindow(23 * 60, 21 * 60, 7 * 60))
        assertTrue(Rules.inWindow(6 * 60, 21 * 60, 7 * 60))
        assertFalse(Rules.inWindow(12 * 60, 21 * 60, 7 * 60))
    }

    @Test
    fun listedAppsAreBlockedByDefaultButTvIsNotLocked() {
        val d = Rules.decide(GuardConfig(), usedMinutes = 10, now = monday(16))
        assertTrue(d.blockApps)
        assertFalse(d.lockTv)
        assertEquals("", d.reason)
        assertEquals(120, d.limitMinutes)
    }

    @Test
    fun unblockingLastsOnlyForThatDay() {
        val config = GuardConfig(unblockedDate = "2026-11-09")
        assertFalse(Rules.decide(config, 10, monday(16)).blockApps)
        assertTrue(Rules.decide(config, 10, LocalDateTime.of(2026, 11, 10, 0, 1)).blockApps)
    }

    @Test
    fun weekendUsesWeekendLimit() {
        val config = GuardConfig(dailyLimitMinutes = 60, weekendLimitMinutes = 120)
        assertEquals(120, Rules.decide(config, 90, saturday(10)).limitMinutes)
        assertFalse(Rules.decide(config, 90, saturday(10)).lockTv)
        assertTrue(Rules.decide(config, 90, monday(10)).lockTv)
    }

    @Test
    fun limitLocksTheWholeTv() {
        val config = GuardConfig(dailyLimitMinutes = 60, unblockedDate = "2026-11-09")
        val d = Rules.decide(config, 60, monday(16))
        assertTrue(d.lockTv)
        assertTrue(d.blockApps)
        assertTrue(d.limitReached)
        assertEquals("limit", d.reason)
    }

    @Test
    fun zeroMeansNoLimit() {
        val d = Rules.decide(GuardConfig(dailyLimitMinutes = 0), 600, monday(16))
        assertEquals(0, d.limitMinutes)
        assertFalse(d.lockTv)
    }

    @Test
    fun bonusOnlyCountsOnItsDay() {
        val config = GuardConfig(dailyLimitMinutes = 60, bonusDate = "2026-11-09", bonusMinutes = 30)
        assertEquals(90, Rules.decide(config, 0, monday(16)).limitMinutes)
        assertEquals(60, Rules.decide(config, 0, LocalDateTime.of(2026, 11, 10, 16, 0)).limitMinutes)
    }

    @Test
    fun outsideAllowedHoursLocksTheWholeTv() {
        val config = GuardConfig(scheduleEnabled = true, weekdayStart = 15 * 60, weekdayEnd = 19 * 60)
        assertEquals("hours", Rules.decide(config, 0, monday(14)).reason)
        assertTrue(Rules.decide(config, 0, monday(14)).lockTv)
        assertFalse(Rules.decide(config, 0, monday(15)).lockTv)
        assertTrue(Rules.decide(config, 0, monday(19)).lockTv)
    }

    @Test
    fun oldBedtimeCountsAsOutsideTvHours() {
        val config = GuardConfig(
            bedtimeEnabled = true, bedtimeStart = 21 * 60, bedtimeEnd = 7 * 60,
            scheduleEnabled = true, dailyLimitMinutes = 10,
        )
        assertEquals("hours", Rules.decide(config, 100, monday(22)).reason)
        assertEquals("hours", Rules.decide(config, 100, monday(6)).reason)
    }

    @Test
    fun overrideUnlocksBedtimeUntilItEnds() {
        val config = GuardConfig(bedtimeEnabled = true, bedtimeStart = 21 * 60, bedtimeEnd = 7 * 60)
        val now = monday(22)
        val until = millis(now) + 30 * 60_000
        val d = Rules.decide(config, 0, now, overrideUntil = until, nowMillis = millis(now))
        assertFalse(d.lockTv)
        assertTrue(d.overridden)
        assertEquals(30, d.minutesToLock)
        val later = monday(22, 31)
        assertTrue(Rules.decide(config, 0, later, overrideUntil = until, nowMillis = millis(later)).lockTv)
    }

    @Test
    fun overrideAlsoUnlocksTheLimitAndHours() {
        val config = GuardConfig(dailyLimitMinutes = 60, scheduleEnabled = true)
        val now = monday(10)
        val until = millis(now) + 30 * 60_000
        assertFalse(Rules.decide(config, 90, now, until, millis(now)).lockTv)
    }

    @Test
    fun warnsBeforeTheLimitRunsOut() {
        val config = GuardConfig(dailyLimitMinutes = 60)
        assertEquals(5, Rules.decide(config, 55, monday(16)).minutesToLock)
        assertNull(Rules.decide(GuardConfig(dailyLimitMinutes = 0), 55, monday(16)).minutesToLock)
    }

    @Test
    fun warnsBeforeBedtimeAndEndOfAllowedHours() {
        val bedtime = GuardConfig(dailyLimitMinutes = 0, bedtimeEnabled = true, bedtimeStart = 21 * 60)
        assertEquals(4, Rules.decide(bedtime, 0, monday(20, 56)).minutesToLock)
        val hours = GuardConfig(dailyLimitMinutes = 0, scheduleEnabled = true, weekdayStart = 15 * 60, weekdayEnd = 19 * 60)
        assertEquals(3, Rules.decide(hours, 0, monday(18, 57)).minutesToLock)
        assertNull(Rules.decide(hours, 0, monday(16)).minutesToLock)
    }

    @Test
    fun configRoundTripsThroughAMap() {
        val config = GuardConfig(
            unblockedDate = "2026-11-14", dailyLimitMinutes = 45, packages = listOf("a.b"),
            emergencyCode = "0123", emergencySetAt = 5, overrideUntil = 9, installsAllowed = true,
        )
        assertEquals(config, GuardConfig.fromMap(config.toMap()))
    }

    @Test
    fun missingFieldsKeepDefaults() {
        val config = GuardConfig.fromMap(mapOf("dailyLimitMinutes" to 30L))
        assertEquals(30, config.dailyLimitMinutes)
        assertEquals(GuardConfig.DEFAULT_PACKAGES, config.packages)
        assertFalse(config.installsAllowed)
    }

    @Test
    fun lockNowLocksTheWholeTvUntilTurnedOff() {
        val config = GuardConfig(lockNow = true, dailyLimitMinutes = 0)
        val locked = Rules.decide(config, 0, monday(16))
        assertTrue(locked.lockTv)
        assertEquals("manual", locked.reason)
        assertFalse(Rules.decide(config.copy(lockNow = false), 0, monday(16)).lockTv)
        // An unlock code still works on top of it.
        val now = monday(16)
        val overridden = Rules.decide(config, 0, now, millis(now) + 60_000, millis(now))
        assertFalse(overridden.lockTv)
    }

    // ---- Parent mode ----

    @Test
    fun parentModeUnlocksAndUnblocksEverything() {
        val config = GuardConfig(dailyLimitMinutes = 60, lockNow = true, scheduleEnabled = true)
        val now = monday(23)
        val until = millis(now) + 90 * 60_000
        val d = Rules.decide(config, 200, now, nowMillis = millis(now), parentUntil = until)
        assertFalse(d.lockTv)
        assertFalse(d.blockApps)
        assertEquals("", d.reason)
        assertTrue(d.parentMode)
        assertEquals(90, d.parentMinutesLeft)
        // Once it has ended, the kids' rules apply again.
        val after = monday(23).plusMinutes(91)
        assertTrue(Rules.decide(config, 200, after, nowMillis = millis(after), parentUntil = until).lockTv)
    }

    @Test
    fun warnsBeforeParentModeEndsOnlyIfTheTvWillLock() {
        val now = monday(16)
        val until = millis(now) + 4 * 60_000
        val usedUp = GuardConfig(dailyLimitMinutes = 60)
        assertEquals(4, Rules.decide(usedUp, 60, now, nowMillis = millis(now), parentUntil = until).minutesToLock)
        val timeLeft = GuardConfig(dailyLimitMinutes = 120)
        assertNull(Rules.decide(timeLeft, 60, now, nowMillis = millis(now), parentUntil = until).minutesToLock)
    }

    @Test
    fun parentModeEndsBy6amAtTheLatest() {
        val utc = ZoneId.of("UTC")
        // Started at 8 PM: ends by 6 AM the next morning.
        assertEquals(millis(LocalDateTime.of(2026, 11, 10, 6, 0)), Rules.parentCutoff(millis(monday(20)), utc))
        // Started at 2 AM: ends by 6 AM the same morning.
        assertEquals(millis(LocalDateTime.of(2026, 11, 9, 6, 0)), Rules.parentCutoff(millis(monday(2)), utc))
        // Started at 5:30 AM: too close, so the next morning.
        assertEquals(millis(LocalDateTime.of(2026, 11, 10, 6, 0)), Rules.parentCutoff(millis(monday(5, 30)), utc))
        val from = millis(monday(23))
        assertEquals(
            millis(LocalDateTime.of(2026, 11, 10, 6, 0)),
            Rules.parentEnd(from, from + 24 * 3600_000L, utc),
        )
        assertEquals(from + 3600_000L, Rules.parentEnd(from, from + 3600_000L, utc))
        assertEquals(0L, Rules.parentEnd(from, from, utc))
    }

    @Test
    fun parentTimeIsCutOutOfWatching() {
        val watched = listOf(Triple("tv", 0L, 100L), Triple("yt", 150L, 200L))
        val cut = Rules.subtract(watched, listOf(20L to 40L, 90L to 160L))
        assertEquals(listOf(Triple("tv", 0L, 20L), Triple("tv", 40L, 90L), Triple("yt", 160L, 200L)), cut)
        assertEquals(watched, Rules.subtract(watched, emptyList()))
        assertEquals(110L, Rules.unionMillis(cut.map { it.second to it.third }))
        assertEquals(100L, Rules.unionMillis(listOf(0L to 60L, 50L to 100L)))
    }

    @Test
    fun configKeepsParentMode() {
        val config = GuardConfig(parentFrom = 5, parentUntil = 10)
        assertEquals(config, GuardConfig.fromMap(config.toMap()))
    }

    // ---- TV hours ----

    @Test
    fun nextOpenFindsWhenTvHoursStart() {
        val config = GuardConfig(scheduleEnabled = true, weekdayStart = 15 * 60, weekdayEnd = 20 * 60 + 30)
        assertEquals(monday(15), Rules.nextOpen(config, monday(9)))
        // After 8:30 PM on Monday, back on Tuesday at 3 PM.
        assertEquals(LocalDateTime.of(2026, 11, 10, 15, 0), Rules.nextOpen(config, monday(21)))
        // Friday evening: the weekend window starts Saturday 9 AM.
        assertEquals(saturday(9), Rules.nextOpen(config, LocalDateTime.of(2026, 11, 13, 21, 0)))
        // Time used up on Monday: more on Tuesday when TV hours start.
        assertEquals(LocalDateTime.of(2026, 11, 10, 15, 0), Rules.nextOpenTomorrow(config, monday(17)))
        assertEquals(LocalDateTime.of(2026, 11, 10, 0, 0), Rules.nextOpenTomorrow(GuardConfig(), monday(17)))
    }
}
