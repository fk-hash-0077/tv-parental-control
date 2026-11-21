package com.tvguard.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class GuardConfigTest {
    @Test
    fun readsWhichPhonePressedLockNow() {
        val config = GuardConfig.fromMap(
            mapOf("lockNow" to true, "lockNowBy" to "Fahad's phone", "lockNowAt" to 1_763_700_000_000L)
        )
        assertEquals(true, config.lockNow)
        assertEquals("Fahad's phone", config.lockNowBy)
        assertEquals(1_763_700_000_000L, config.lockNowAt)
    }

    @Test
    fun olderPhonesThatDontSayWhoLockedLeaveItEmpty() {
        val config = GuardConfig.fromMap(mapOf("lockNow" to true))
        assertEquals("", config.lockNowBy)
        assertEquals(0L, config.lockNowAt)
    }

    @Test
    fun lockedBySurvivesSavingOnTheTv() {
        val config = GuardConfig(lockNow = true, lockNowBy = "Mum's phone", lockNowAt = 42L)
        assertEquals(config, GuardConfig.fromMap(config.toMap()))
    }
}
