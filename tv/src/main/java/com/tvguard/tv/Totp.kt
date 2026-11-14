package com.tvguard.tv

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * One-time unlock codes made of remote-control arrows, like an authenticator app's codes.
 * The phone and TV share a secret and both derive the same 6 arrows from the current
 * 2-minute window, so it works with the TV offline. Keep in step with the phone's copy.
 *
 * Arrows are numbered 0 = up, 1 = down, 2 = left, 3 = right.
 */
object Totp {
    const val STEP_SECONDS = 120L
    const val LENGTH = 6

    fun counterAt(epochMillis: Long): Long = epochMillis / 1000 / STEP_SECONDS

    fun code(secret: ByteArray, counter: Long): List<Int> {
        val mac = Mac.getInstance("HmacSHA1")
        mac.init(SecretKeySpec(secret, "HmacSHA1"))
        val message = ByteArray(8) { i -> (counter ushr (56 - 8 * i)).toByte() }
        val hash = mac.doFinal(message)
        val offset = hash[hash.size - 1].toInt() and 0x0f
        val value = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        return (LENGTH - 1 downTo 0).map { shift -> (value ushr (2 * shift)) and 3 }
    }

    /**
     * Returns the counter of the matching code, or null. Accepts the current window and the one on
     * either side (a code read just before it changed, or clocks a little apart), but never a
     * counter at or below [lastUsed], so each code works once.
     */
    fun verify(secret: ByteArray, entered: List<Int>, epochMillis: Long, lastUsed: Long): Long? {
        if (entered.size != LENGTH) return null
        val now = counterAt(epochMillis)
        return (now - 1..now + 1).firstOrNull { it > lastUsed && code(secret, it) == entered }
    }

    const val ARROWS = "↑↓←→"

    fun format(code: List<Int>): String = code.joinToString("") { ARROWS[it].toString() }
}
