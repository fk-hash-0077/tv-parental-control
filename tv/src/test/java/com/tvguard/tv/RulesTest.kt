package com.tvguard.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class RulesTest {
    // 2026-11-09 is a Monday, 2026-11-14 a Saturday.
    private fun monday(h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, 9, h, m)
    private fun saturday(h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, 14, h, m)

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
    fun nothingLockedByDefaultUnderTheLimit() {
        val d = Rules.decide(GuardConfig(), usedMinutes = 10, now = monday(16))
        assertFalse(d.blockApps)
        assertEquals("", d.reason)
        assertEquals(60, d.limitMinutes)
    }

    @Test
    fun weekendUsesWeekendLimit() {
        val config = GuardConfig(dailyLimitMinutes = 60, weekendLimitMinutes = 120)
        assertEquals(120, Rules.decide(config, 90, saturday(10)).limitMinutes)
        assertFalse(Rules.decide(config, 90, saturday(10)).blockApps)
        assertTrue(Rules.decide(config, 90, monday(10)).limitReached)
    }

    @Test
    fun bonusOnlyCountsOnItsDay() {
        val config = GuardConfig(dailyLimitMinutes = 60, bonusDate = "2026-11-09", bonusMinutes = 30)
        assertEquals(90, Rules.limitFor(config, monday(10)))
        assertEquals(60, Rules.limitFor(config, monday(10).plusDays(1)))
        assertFalse(Rules.decide(config, 75, monday(10)).blockApps)
    }

    @Test
    fun noLimitIgnoresBonus() {
        val config = GuardConfig(dailyLimitMinutes = 0, bonusDate = "2026-11-09", bonusMinutes = 30)
        assertEquals(0, Rules.limitFor(config, monday(10)))
        assertFalse(Rules.decide(config, 500, monday(10)).limitReached)
    }

    @Test
    fun scheduleBlocksOutsideAllowedHours() {
        val config = GuardConfig(scheduleEnabled = true, weekdayStart = 15 * 60, weekdayEnd = 19 * 60)
        assertEquals("schedule", Rules.decide(config, 0, monday(14, 59)).reason)
        assertEquals("", Rules.decide(config, 0, monday(15)).reason)
        assertEquals("schedule", Rules.decide(config, 0, monday(19)).reason)
    }

    @Test
    fun bedtimeWinsOverOtherReasons() {
        val config = GuardConfig(blocked = true, bedtimeEnabled = true, bedtimeStart = 21 * 60, bedtimeEnd = 7 * 60)
        val night = Rules.decide(config, 0, monday(22))
        assertTrue(night.bedtime)
        assertEquals("bedtime", night.reason)
        assertEquals("blocked", Rules.decide(config, 0, monday(8)).reason)
    }

    @Test
    fun configRoundTripsThroughMap() {
        val config = GuardConfig(
            blocked = true, dailyLimitMinutes = 45, packages = listOf("a.b"),
            bonusDate = "2026-11-09", bonusMinutes = 15, bedtimeEnabled = true,
        )
        assertEquals(config, GuardConfig.fromMap(config.toMap()))
    }

    @Test
    fun configReadsFirebaseLongsAndKeepsDefaults() {
        val config = GuardConfig.fromMap(mapOf("dailyLimitMinutes" to 30L, "packages" to emptyList<String>()))
        assertEquals(30, config.dailyLimitMinutes)
        assertEquals(emptyList<String>(), config.packages)
        assertEquals(GuardConfig().weekendLimitMinutes, config.weekendLimitMinutes)
    }
}
