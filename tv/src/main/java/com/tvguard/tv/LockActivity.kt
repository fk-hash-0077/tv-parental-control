package com.tvguard.tv

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Full-screen bedtime lock. Pins itself with lock task mode (allowed because
 * the app is Device Owner), so Home and Back don't leave it, and lets go once
 * [Store.bedtimeActive] turns false.
 */
class LockActivity : AppCompatActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val check = object : Runnable {
        override fun run() {
            if (!Store(this@LockActivity).bedtimeActive) {
                unlock()
            } else {
                handler.postDelayed(this, CHECK_MS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_lock)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = Unit
        })
    }

    override fun onResume() {
        super.onResume()
        val store = Store(this)
        if (!store.bedtimeActive) {
            unlock()
            return
        }
        val end = LocalTime.of(0, 0).plusMinutes(store.config.bedtimeEnd.toLong())
        findViewById<TextView>(R.id.unlockAt).text =
            "The TV unlocks at " + end.format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))
        if (Policy(this).isDeviceOwner && !isLocked()) {
            runCatching { startLockTask() }
        }
        handler.post(check)
    }

    override fun onPause() {
        handler.removeCallbacks(check)
        super.onPause()
    }

    private fun isLocked(): Boolean =
        getSystemService(ActivityManager::class.java).lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE

    private fun unlock() {
        if (isLocked()) runCatching { stopLockTask() }
        finish()
    }

    companion object {
        private const val CHECK_MS = 15_000L

        fun show(context: Context) {
            context.startActivity(
                Intent(context, LockActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            )
        }
    }
}
