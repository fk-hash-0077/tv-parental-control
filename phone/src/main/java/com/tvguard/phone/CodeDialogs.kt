package com.tvguard.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.Gravity
import android.widget.Button
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog

/** The "Unlock code" and "Set emergency code" dialogs. */
object CodeDialogs {

    /**
     * Shows the current one-time arrow code, refreshed as it changes every 2 minutes.
     * [secret] is the TV's unlock secret, Base64, cached on this phone so it works offline.
     */
    fun showUnlockCode(context: Context, secret: String) {
        val key = runCatching { Base64.decode(secret, Base64.NO_WRAP) }.getOrNull()
        if (key == null || key.isEmpty()) {
            AlertDialog.Builder(context)
                .setMessage("The TV hasn't shared its code yet. Make sure the TV is on and has the newest TV Guard, then try again.")
                .setPositiveButton("OK", null)
                .show()
            return
        }
        val pad = (24 * context.resources.displayMetrics.density).toInt()
        val codeView = TextView(context).apply {
            textSize = 44f
            gravity = Gravity.CENTER
            letterSpacing = 0.15f
        }
        val timer = TextView(context).apply { gravity = Gravity.CENTER }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, 0)
            addView(codeView)
            addView(timer)
            addView(TextView(context).apply {
                text = "On the TV lock screen, press these arrows on the remote, then OK. " +
                    "Nothing shows on the TV while you type. It unlocks the TV for 30 minutes and works once."
                setPadding(0, pad / 2, 0, 0)
            })
        }
        val handler = Handler(Looper.getMainLooper())
        val update = object : Runnable {
            override fun run() {
                val now = System.currentTimeMillis()
                codeView.text = Totp.format(Totp.code(key, Totp.counterAt(now)))
                val left = Totp.STEP_SECONDS - (now / 1000) % Totp.STEP_SECONDS
                timer.text = "Changes in %d:%02d".format(left / 60, left % 60)
                handler.postDelayed(this, 1_000)
            }
        }
        AlertDialog.Builder(context)
            .setTitle("Unlock code")
            .setView(layout)
            .setPositiveButton("Done", null)
            .setOnDismissListener { handler.removeCallbacks(update) }
            .show()
        handler.post(update)
    }

    /** Lets the parent tap in an arrow sequence; [onSave] gets digits 0-3 (up, down, left, right). */
    fun setEmergencyCode(context: Context, onSave: (String) -> Unit) {
        val typed = StringBuilder()
        val pad = (24 * context.resources.displayMetrics.density).toInt()
        val preview = TextView(context).apply {
            textSize = 32f
            gravity = Gravity.CENTER
            minHeight = pad * 2
        }
        fun refresh() {
            preview.text = if (typed.isEmpty()) "–" else typed.map { Totp.ARROWS[it - '0'] }.joinToString("")
        }
        val grid = GridLayout(context).apply {
            columnCount = 3
            useDefaultMargins = true
        }
        // Laid out like a remote's arrow pad.
        val cells = listOf(-1, 0, -1, 2, -2, 3, -1, 1, -1)
        cells.forEach { cell ->
            val view = when {
                cell >= 0 -> Button(context).apply {
                    text = Totp.ARROWS[cell].toString()
                    textSize = 22f
                    setOnClickListener {
                        if (typed.length < MAX) typed.append(cell)
                        refresh()
                    }
                }
                cell == -2 -> Button(context).apply {
                    text = "⌫"
                    setOnClickListener {
                        if (typed.isNotEmpty()) typed.setLength(typed.length - 1)
                        refresh()
                    }
                }
                else -> TextView(context)
            }
            grid.addView(view)
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad / 2, pad, 0)
            addView(TextView(context).apply {
                text = "For when your phone isn't around. Pick $MIN to $MAX arrows. On the TV lock screen, " +
                    "press them on the remote, then OK. It unlocks the TV for 30 minutes and works once; " +
                    "after that, set a new one here."
            })
            addView(preview)
            addView(grid)
        }
        refresh()
        val dialog = AlertDialog.Builder(context)
            .setTitle("Emergency code")
            .setView(layout)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .show()
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (typed.length < MIN) {
                preview.text = "Use at least $MIN arrows"
                return@setOnClickListener
            }
            onSave(typed.toString())
            dialog.dismiss()
        }
    }

    private const val MIN = 6
    private const val MAX = 10
}
