package com.tvguard.tv

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Full-screen lock outside TV hours ("TV is resting"), when today's TV time is used up, and for
 * the phone's Lock now.
 * Pins itself with lock task mode (allowed because the app is Device Owner), so Home and Back
 * don't leave it, and lets go once [Store.lockReason] is empty.
 *
 * A parent types arrows on the remote and presses OK: either the one-time code shown in the
 * phone app, or the emergency code set from the phone. Nothing on screen changes while the
 * arrows are typed. A right code then offers "Unlock 30 min" (for the kids, counts as TV time)
 * or "Parent mode 2 h" (nothing locked or counted, ends by 6 AM).
 *
 * When the weekday TV time is used up, or outside TV hours, and a parent sent a photo for it
 * from the phone, the photo is shown in a round frame in place of the icon.
 */
class LockActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val typed = mutableListOf<Int>()
    private var lastKeyAt = 0L
    private lateinit var store: Store
    private lateinit var askButton: Button
    private lateinit var answer: TextView
    private lateinit var photo: ImageView
    private lateinit var choice: View
    /** True while the "Unlock 30 min / Parent mode" choice is on screen after a parent's code. */
    private var choosing = false
    private var choiceEvent = "unlock"
    /** Which photo is on screen and when it was sent, so it is only redrawn when it changes. */
    private var shownPhoto: Pair<Boolean, Long>? = null

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
        photo = findViewById(R.id.photo)
        choice = findViewById(R.id.choice)
        askButton.setOnClickListener { askForMoreTime() }
        findViewById<Button>(R.id.choiceUnlock).setOnClickListener { chooseUnlock() }
        findViewById<Button>(R.id.choiceParent).setOnClickListener { chooseParentMode() }
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
        if (!choosing) askButton.requestFocus()
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
        val countdown = findViewById<TextView>(R.id.countdown)
        val now = LocalDateTime.now()
        // Older versions wrote bedtime and schedule; both mean outside TV hours now.
        val reason = when (val r = store.lockReason) {
            "bedtime", "schedule" -> "hours"
            else -> r
        }
        // When the TV unlocks again, if that's known, for the countdown.
        var unlocksAt: LocalDateTime? = null
        when (reason) {
            "hours" -> {
                icon.text = "🌙"
                title.text = "TV is resting"
                val open = Rules.nextOpen(config, now)
                detail.text = if (open == null) "" else "Back on ${describe(open, now)}, in"
                unlocksAt = open
            }
            "manual" -> {
                icon.text = "⏸"
                title.text = "A parent paused the TV"
                detail.text = lockedByText(config, now)?.let { "$it\nIt unlocks when they say so" }
                    ?: "It unlocks when they say so"
            }
            else -> {
                icon.text = "⏰"
                val limit = store.lastStatus?.limitMinutesToday ?: 0
                title.text = if (limit > 0) "That's ${formatMinutes(limit)} for today" else "TV time is used up for today"
                val open = Rules.nextOpenTomorrow(config, now)
                detail.text = if (open == null) "More TV time tomorrow" else "More TV time ${describe(open, now)}"
            }
        }
        if (unlocksAt != null) {
            val left = java.time.Duration.between(now, unlocksAt).seconds.coerceAtLeast(0)
            countdown.text = "%d:%02d:%02d".format(left / 3600, left / 60 % 60, left % 60)
            countdown.visibility = View.VISIBLE
        } else {
            countdown.visibility = View.GONE
        }
        if (reason != renderedReason) {
            renderedReason = reason
            findViewById<View>(R.id.root).background = GradientDrawable().apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = resources.displayMetrics.widthPixels * 0.75f
                setGradientCenter(0.5f, 0f)
                colors = if (reason == "hours") {
                    intArrayOf(0xFF1D2147.toInt(), 0xFF06070F.toInt())
                } else {
                    intArrayOf(0xFF23304A.toInt(), 0xFF090D14.toInt())
                }
            }
        }
        val showPhoto = when (reason) {
            "limit" -> !Rules.isWeekend(now) && showPhoto(bedtime = false)
            "hours" -> showPhoto(bedtime = true)
            else -> false
        }
        photo.visibility = if (showPhoto) View.VISIBLE else View.GONE
        icon.visibility = if (showPhoto) View.GONE else View.VISIBLE
        val used = store.lastStatus?.usedMinutesToday ?: 0
        findViewById<TextView>(R.id.watched).text = if (used > 0) "You watched ${formatMinutes(used)} today" else ""
        askButton.visibility = if (choosing) View.GONE else View.VISIBLE
        answer.text = if (choosing) "Code accepted. What would you like?" else answerText()
    }

    /** "Locked by Fahad's phone at 3:40 PM", or null when the phone that locked it didn't say. */
    private fun lockedByText(config: GuardConfig, now: LocalDateTime): String? {
        if (config.lockNowBy.isEmpty() || config.lockNowAt <= 0) return null
        val at = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(config.lockNowAt), java.time.ZoneId.systemDefault())
        val time = at.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        val day = when (java.time.temporal.ChronoUnit.DAYS.between(at.toLocalDate(), now.toLocalDate())) {
            0L -> "at $time"
            1L -> "yesterday at $time"
            else -> "on ${at.format(DateTimeFormatter.ofPattern("EEEE d MMM"))} at $time"
        }
        return "Locked by ${config.lockNowBy} $day"
    }

    /** "at 3:00 PM", "tomorrow at 3:00 PM" or "on Saturday at 9:00 AM". */
    private fun describe(t: LocalDateTime, now: LocalDateTime): String {
        val time = t.toLocalTime().format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), t.toLocalDate())
        return when (days) {
            0L -> "at $time"
            1L -> "tomorrow at $time"
            else -> "on ${t.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())} at $time"
        }
    }

    private var renderedReason = ""

    /** Draws the parent's photo into the round frame. False when there is no photo. */
    private fun showPhoto(bedtime: Boolean): Boolean {
        if (!LockPhoto.exists(this, bedtime)) {
            shownPhoto = null
            photo.setImageDrawable(null)
            return false
        }
        val wanted = bedtime to (if (bedtime) store.bedtimePhotoAt else store.photoAt)
        if (wanted != shownPhoto || photo.drawable == null) {
            val density = resources.displayMetrics.density
            val frame = (FRAME_DP * density).toInt()
            val halo = (HALO_DP * density).toInt()
            val ring = (RING_DP * density).toInt()
            val circle = LockPhoto.circle(this, frame - 2 * halo, ring, bedtime) ?: return false
            // Halo, ring and photo in one bitmap the exact size of the view, centred on each other.
            val framed = Bitmap.createBitmap(frame, frame, Bitmap.Config.ARGB_8888)
            Canvas(framed).apply {
                val c = frame / 2f
                drawCircle(c, c, c, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = HALO_COLOR })
                drawBitmap(circle, halo.toFloat(), halo.toFloat(), null)
            }
            circle.recycle()
            photo.setImageBitmap(framed)
            shownPhoto = wanted
        }
        return true
    }

    private fun formatMinutes(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
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
        // While the parent picks, the remote moves between the two buttons as usual.
        if (choosing) return super.dispatchKeyEvent(event)
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
                AppLog.i("TvGuardLock", "One-time code accepted")
                store.lastCodeCounter = counter
                store.failedAttempts = 0
                offerChoice("unlock")
            }
            emergency.isNotEmpty() && entered == emergency -> {
                AppLog.i("TvGuardLock", "Emergency code accepted")
                store.usedEmergencySetAt = store.config.emergencySetAt
                store.failedAttempts = 0
                sync?.clearEmergencyCode()
                offerChoice("emergency")
            }
            else -> {
                AppLog.i("TvGuardLock", "Wrong unlock code")
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

    /** After a parent's code: "Unlock 30 min" for the kids, or parent mode. [event] is unlock or emergency. */
    private fun offerChoice(event: String) {
        choosing = true
        choiceEvent = event
        choice.visibility = View.VISIBLE
        findViewById<Button>(R.id.choiceParent).requestFocus()
        handler.removeCallbacks(cancelChoice)
        handler.postDelayed(cancelChoice, CHOICE_MS)
        render()
    }

    /** Nobody picked within a minute: back to the plain lock screen. The code is used up. */
    private val cancelChoice = Runnable {
        choosing = false
        choice.visibility = View.GONE
        askButton.requestFocus()
        render()
    }

    private fun chooseUnlock() {
        if (!choosing) return
        AppLog.i("TvGuardLock", "Unlocked for 30 minutes (${choiceEvent} code)")
        endChoice()
        GuardService.current?.sendEvent(choiceEvent)
        grantUnlock(System.currentTimeMillis())
    }

    private fun chooseParentMode() {
        if (!choosing) return
        endChoice()
        val now = System.currentTimeMillis()
        val until = minOf(now + PARENT_MS, Rules.parentCutoff(now, java.time.ZoneId.systemDefault()))
        AppLog.i("TvGuardLock", "Parent mode started on the TV (${choiceEvent} code)")
        // Recorded here first, so it works even before the phones hear about it.
        store.recordParentPeriod(now, until)
        GuardService.current?.startParentMode(now, until)
        store.lockReason = ""
        GuardService.start(this)
        unlock()
    }

    private fun endChoice() {
        choosing = false
        handler.removeCallbacks(cancelChoice)
        choice.visibility = View.GONE
    }

    private fun grantUnlock(now: Long) {
        store.failedAttempts = 0
        store.localOverrideUntil = now + UNLOCK_MS
        store.lockReason = ""
        GuardService.start(this)
        unlock()
    }

    private fun isLocked(): Boolean =
        getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

    private fun unlock() {
        handler.removeCallbacks(check)
        if (isLocked()) runCatching { stopLockTask() }
        finish()
    }

    companion object {
        private const val CHECK_MS = 1_000L
        /** Must match the photo view's size in activity_lock.xml. */
        private const val FRAME_DP = 150
        private const val HALO_DP = 8
        private const val RING_DP = 4
        private const val HALO_COLOR = 0x55F59E0B
        private const val TYPING_GAP_MS = 10_000L
        private const val MIN_CODE = 4
        private const val MAX_TYPED = 12
        private const val MAX_FAILED = 5
        private const val LOCKOUT_MS = 60_000L
        private const val UNLOCK_MS = 30 * 60_000L
        private const val PARENT_MS = 2 * 60 * 60_000L
        private const val CHOICE_MS = 60_000L
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
