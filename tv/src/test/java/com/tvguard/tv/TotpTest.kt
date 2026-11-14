package com.tvguard.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TotpTest {
    // RFC 4226 test secret; its HOTP values are 1284755224 (counter 0) and 1094287082 (counter 1).
    private val secret = "12345678901234567890".toByteArray()

    @Test
    fun matchesTheStandardHotpValues() {
        // The six arrows are the low 12 bits of the HOTP value, two bits per arrow.
        assertEquals(listOf(3, 3, 0, 1, 2, 0), Totp.code(secret, 0))
        assertEquals(listOf(3, 2, 3, 2, 2, 2), Totp.code(secret, 1))
    }

    @Test
    fun codesChangeEveryTwoMinutes() {
        assertEquals(Totp.counterAt(0), Totp.counterAt(119_999))
        assertNotEquals(Totp.counterAt(0), Totp.counterAt(120_000))
    }

    @Test
    fun acceptsCurrentAndNeighbouringWindowsOnce() {
        val now = 10 * 120_000L + 5_000
        val counter = Totp.counterAt(now)
        val code = Totp.code(secret, counter)
        assertEquals(counter, Totp.verify(secret, code, now, lastUsed = 0))
        // Used once: the same code is refused.
        assertNull(Totp.verify(secret, code, now, lastUsed = counter))
        // A code read just before it changed still works.
        val previous = Totp.code(secret, counter - 1)
        assertEquals(counter - 1, Totp.verify(secret, previous, now, lastUsed = 0))
        // Old codes don't.
        assertNull(Totp.verify(secret, Totp.code(secret, counter - 3), now, lastUsed = 0))
    }

    @Test
    fun formatsAsArrows() {
        assertEquals("↑↓←→", Totp.format(listOf(0, 1, 2, 3)))
    }
}
