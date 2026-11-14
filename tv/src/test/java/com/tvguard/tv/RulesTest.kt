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
        assertEquals("schedule", Rules.decide(config, 0, monday(14)).reason)
        assertTrue(Rules.decide(config, 0, monday(14)).lockTv)
        assertFalse(Rules.decide(config, 0, monday(15)).lockTv)
        assertTrue(Rules.decide(config, 0, monday(19)).lockTv)
    }

    @Test
    fun bedtimeWinsOverOtherReasons() {
        val config = GuardConfig(
            bedtimeEnabled = true, bedtimeStart = 21 * 60, bedtimeEnd = 7 * 60,
            scheduleEnabled = true, dailyLimitMinutes = 10,
        )
        assertEquals("bedtime", Rules.decide(config, 100, monday(22)).reason)
        assertEquals("bedtime", Rules.decide(config, 100, monday(6)).reason)
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
}
