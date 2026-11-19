package com.tvguard.tv

import android.content.Context
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast

/**
 * The "5 minutes left" warning: a small card in the top-right corner over whatever is playing,
 * gone after a few seconds. It needs the "display over other apps" permission, granted once by
 * scripts/setup-tv.sh; without it a plain toast is shown instead.
 */
object WarningPopup {
    private const val SHOWN_MS = 10_000L
    private var current: View? = null

    fun show(context: Context, minutesToLock: Int, locksAt: String) {
        val title = if (minutesToLock <= 1) "1 minute left" else "$minutesToLock minutes left"
        note(context, "⏰", title, "The TV locks at $locksAt.")
    }

    /** Any short note in the same corner card, e.g. "Parent mode is on". */
    fun note(context: Context, icon: String, title: String, detail: String) {
        val app = context.applicationContext
        Handler(Looper.getMainLooper()).post {
            if (!Settings.canDrawOverlays(app) || !showCard(app, icon, title, detail)) {
                Toast.makeText(app, "TV Guard: $title. $detail", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun showCard(context: Context, icon: String, title: String, detail: String): Boolean = try {
        val wm = context.getSystemService(WindowManager::class.java)
        current?.let { old -> runCatching { wm.removeView(old) } }
        val view = LayoutInflater.from(context).inflate(R.layout.warning_popup, null)
        view.findViewById<TextView>(R.id.warningIcon).text = icon
        view.findViewById<TextView>(R.id.warningTitle).text = title
        view.findViewById<TextView>(R.id.warningDetail).text = detail
        val density = context.resources.displayMetrics.density
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Never takes the remote's focus, so the show keeps playing and responding.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.END
            x = (32 * density).toInt()
            y = (32 * density).toInt()
            windowAnimations = android.R.style.Animation_Toast
        }
        wm.addView(view, params)
        current = view
        Handler(Looper.getMainLooper()).postDelayed({
            runCatching { wm.removeView(view) }
            if (current === view) current = null
        }, SHOWN_MS)
        true
    } catch (e: Exception) {
        AppLog.w("TvGuardWarn", "Couldn't show the warning card", e)
        false
    }
}
