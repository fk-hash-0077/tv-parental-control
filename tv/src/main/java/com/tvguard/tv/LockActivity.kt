package com.tvguard.tv

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.widget.Button
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Full-screen lock for bedtime, outside allowed hours and when today's limit is used up.
 * Pins itself with lock task mode (allowed because the app is Device Owner), so Home and Back
 * don't leave it, and lets go once [Store.lockReason] is empty.
 *
 * A parent can unlock it for 30 minutes by typing arrows on the remote and pressing OK:
 * either the one-time code shown in the phone app, or the emergency code set from the phone.
 * Nothing on screen changes while the arrows are typed.
 */
class LockActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val typed = mutableListOf<Int>()
    private var lastKeyAt = 0L
    private lateinit var store: Store
    private lateinit var askButton: Button
    private lateinit var answer: TextView

    private val check = object : Runnable {
        override fun run() {
            if (store.lockReason.isEmpty()) {
                unlock()
            } else {
                render()
                handler.postDelayed(this, CHECK_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock)
        store = Store(this)
        askButton = findViewById(R.id.ask)
        answer = findViewById(R.id.answer)
        askButton.setOnClickListener { askForMoreTime() }
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
    }

    override fun onResume() {
        super.onResume()
        if (store.lockReason.isEmpty()) {
            unlock()
            return
        }
        render()
        askButton.requestFocus()
        if (Policy(this).isDeviceOwner && !isLocked()) {
            runCatching { startLockTask() }
        }
        handler.post(check)
    }

    override fun onPause() {
        handler.removeCallbacks(check)
        super.onPause()
    }

    private fun render() {
        val config = store.config
        val title = findViewById<TextView>(R.id.title)
        val icon = findViewById<TextView>(R.id.icon)
        val detail = findViewById<TextView>(R.id.unlockAt)
        when (store.lockReason) {
            "bedtime" -> {
                icon.text = "🌙"
                title.text = "Time for bed"
                detail.text = "The TV unlocks at ${formatTime(config.bedtimeEnd)}"
            }
            "schedule" -> {
                icon.text = "⏸"
                title.text = "TV isn't allowed right now"
                val weekend = Rules.isWeekend(LocalDateTime.now())
                val start = if (weekend) config.weekendStart else config.weekdayStart
                val end = if (weekend) config.weekendEnd else config.weekdayEnd
                detail.text = "TV time today is ${formatTime(start)} to ${formatTime(end)}"
            }
            else -> {
                icon.text = "⏰"
                title.text = "TV time is up for today"
                detail.text = "It resets at midnight"
            }
        }
        answer.text = answerText()
    }

    private fun answerText(): String {
        val askedAt = store.lastRequestAt
        if (askedAt == 0L || System.currentTimeMillis() - askedAt > ANSWER_SHOWN_MS) return ""
        val reply = store.requestAnswer
        return when {
            reply.startsWith("approved:") -> "Approved: ${reply.substringAfter(':')} more minutes"
            reply == "denied" -> "Not this time"
            else -> "Asked. Waiting for an answer…"
        }
    }

    private fun askForMoreTime() {
        val now = System.currentTimeMillis()
        if (now - store.lastRequestAt < REQUEST_GAP_MS && store.requestAnswer.isEmpty()) {
            answer.text = "Already asked. Waiting for an answer…"
            return
        }
        val sync = GuardService.current
        if (sync == null || !sync.sendRequest(now, store.lockReason)) {
            answer.text = "Can't reach the phone right now"
            return
        }
        store.lastRequestAt = now
        store.requestAnswer = ""
        answer.text = answerText()
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val arrow = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> 0
            KeyEvent.KEYCODE_DPAD_DOWN -> 1
            KeyEvent.KEYCODE_DPAD_LEFT -> 2
            KeyEvent.KEYCODE_DPAD_RIGHT -> 3
            else -> -1
        }
        val ok = event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
            event.keyCode == KeyEvent.KEYCODE_ENTER ||
            event.keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER
        if (arrow < 0 && !ok) return super.dispatchKeyEvent(event)
        // Arrow and OK keys never move focus or click on their own, so typing shows nothing.
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true

        val now = System.currentTimeMillis()
        if (now - lastKeyAt > TYPING_GAP_MS) typed.clear()
        lastKeyAt = now

        if (arrow >= 0) {
            if (typed.size < MAX_TYPED) typed += arrow
        } else if (typed.size < MIN_CODE) {
            typed.clear()
            askForMoreTime()
        } else {
            val entered = typed.toList()
            typed.clear()
            tryUnlock(entered, now)
        }
        return true
    }

    private fun tryUnlock(entered: List<Int>, now: Long) {
        if (now < store.lockoutUntil) return
        val sync = GuardService.current
        val counter = Totp.verify(store.unlockSecret, entered, now, store.lastCodeCounter)
        val emergency = store.emergencyCode
        when {
            counter != null -> {
                store.lastCodeCounter = counter
                grantUnlock(now)
                sync?.sendEvent("unlock")
            }
            emergency.isNotEmpty() && entered == emergency -> {
                store.usedEmergencySetAt = store.config.emergencySetAt
                grantUnlock(now)
                sync?.sendEvent("emergency")
                sync?.clearEmergencyCode()
            }
            else -> {
                val failed = store.failedAttempts + 1
                if (failed >= MAX_FAILED) {
                    store.failedAttempts = 0
                    store.lockoutUntil = now + LOCKOUT_MS
                } else {
                    store.failedAttempts = failed
                }
                sync?.sendEvent("failed")
            }
        }
    }

    private fun grantUnlock(now: Long) {
        store.failedAttempts = 0
        store.localOverrideUntil = now + UNLOCK_MS
        store.lockReason = ""
        GuardService.start(this)
        unlock()
    }

    private fun formatTime(minutes: Int): String =
        LocalTime.of(0, 0).plusMinutes(minutes.toLong())
            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

    private fun isLocked(): Boolean =
        getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

    private fun unlock() {
        handler.removeCallbacks(check)
        if (isLocked()) runCatching { stopLockTask() }
        finish()
    }

    companion object {
        private const val CHECK_MS = 2_000L
        private const val TYPING_GAP_MS = 10_000L
        private const val MIN_CODE = 4
        private const val MAX_TYPED = 12
        private const val MAX_FAILED = 5
        private const val LOCKOUT_MS = 60_000L
        private const val UNLOCK_MS = 30 * 60_000L
        private const val REQUEST_GAP_MS = 10 * 60_000L
        private const val ANSWER_SHOWN_MS = 30 * 60_000L

        fun show(context: Context) {
            context.startActivity(
                Intent(context, LockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }
    }
}
