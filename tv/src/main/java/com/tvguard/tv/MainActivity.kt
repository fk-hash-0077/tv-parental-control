package com.tvguard.tv

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.FirebaseApp
import java.text.DateFormat
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

/**
 * Status screen: TV time today, allowed hours, blocked apps and whether the phone is connected.
 * The pairing code shows until a phone is paired, then only on request.
 * Parents control the rules from the phone.
 */
class MainActivity : AppCompatActivity() {
    private var showCode = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.refresh).setOnClickListener {
            GuardService.start(this)
            it.postDelayed({ render() }, 1_500)
        }
        findViewById<Button>(R.id.showCode).setOnClickListener {
            showCode = !showCode
            render()
        }
    }

    override fun onResume() {
        super.onResume()
        GuardService.start(this)
        render()
        findViewById<View>(R.id.refresh).requestFocus()
    }

    private fun render() {
        val store = Store(this)
        val config = store.config
        val status = store.lastStatus
        val owner = Policy(this).isDeviceOwner
        val usage = UsageTracker(this).hasAccess()
        val cloud = FirebaseApp.getApps(this).isNotEmpty()
        val now = LocalDateTime.now()

        // Header pills
        val synced = cloud && System.currentTimeMillis() - store.syncSavedAt < 3 * 60_000L
        pill(findViewById(R.id.syncPill), if (synced) "● Connected to phone" else "● Reconnecting to phone", if (synced) OK else WARN)
        val protectedNow = owner && usage
        pill(findViewById(R.id.protectionPill), if (protectedNow) "Protection on" else "Setup needed", if (protectedNow) OK else BAD)

        // TV time
        val used = status?.usedMinutesToday ?: 0
        val limit = status?.limitMinutesToday ?: Rules.limitFor(config, now)
        findViewById<TextView>(R.id.used).text = formatShort(used)
        val left = (limit - used).coerceAtLeast(0)
        val fraction = if (limit > 0) (used.toFloat() / limit).coerceIn(0f, 1f) else 0f
        val tone = when {
            limit > 0 && left == 0 -> BAD
            limit > 0 && (left <= 15 || fraction >= 0.7f) -> WARN
            else -> OK
        }
        val fill = findViewById<View>(R.id.barFill)
        fill.layoutParams = (fill.layoutParams as LinearLayout.LayoutParams).apply { weight = fraction }
        fill.background.mutate().setTint(tone)
        findViewById<View>(R.id.bar).visibility = if (limit > 0) View.VISIBLE else View.INVISIBLE
        findViewById<TextView>(R.id.left).apply {
            text = if (limit > 0) "${formatLong(left)} left of ${formatLong(limit)}" else "No daily limit"
            setTextColor(if (limit > 0) tone else MUTED)
        }

        // Hours
        val weekend = Rules.isWeekend(now)
        val hours = findViewById<TextView>(R.id.hours)
        val hoursDetail = findViewById<TextView>(R.id.hoursDetail)
        if (config.scheduleEnabled) {
            val start = if (weekend) config.weekendStart else config.weekdayStart
            val end = if (weekend) config.weekendEnd else config.weekdayEnd
            hours.text = "${formatTime(start)} – ${formatTime(end)}"
            hoursDetail.text = if (weekend) "Weekend rules" else "Weekday rules"
        } else {
            hours.text = "Any time"
            hoursDetail.text = ""
        }
        if (config.bedtimeEnabled) {
            val bed = "Bedtime ${formatTime(config.bedtimeStart)}"
            hoursDetail.text = listOf(hoursDetail.text, bed).filter { it.isNotEmpty() }.joinToString(" · ")
        }

        // Apps
        val blocked = config.packages.filter { Policy(this).isInstalled(it) }
        val allowedToday = Rules.appsAllowed(config, now)
        findViewById<TextView>(R.id.apps).text = when {
            blocked.isEmpty() -> "None"
            allowedToday -> "Unblocked today"
            blocked.size == 1 -> "1 app"
            else -> "${blocked.size} apps"
        }
        findViewById<TextView>(R.id.appsDetail).text =
            AppCatalog.labels(this, blocked).values.sorted().joinToString(", ")

        // Pairing code
        val paired = store.paired
        findViewById<View>(R.id.pairCard).visibility = if (!paired || showCode) View.VISIBLE else View.GONE
        findViewById<TextView>(R.id.code).text = Store.formatId(store.deviceId)
        findViewById<Button>(R.id.showCode).apply {
            visibility = if (paired) View.VISIBLE else View.GONE
            text = if (showCode) "Hide pairing code" else "Show pairing code"
        }

        // Setup problems
        val problems = mutableListOf<String>()
        if (!owner) problems += "✗ TV Guard isn't Device Owner, so it can't lock anything. Run scripts/setup-tv.sh."
        if (!usage) problems += "✗ TV Guard can't measure TV time. Run scripts/setup-tv.sh."
        if (!cloud) problems += "✗ Phone sync isn't set up. Add google-services.json and rebuild."
        else if (!synced) problems += phoneSyncLine(store)
        findViewById<TextView>(R.id.problems).apply {
            text = problems.joinToString("\n")
            visibility = if (problems.isEmpty()) View.GONE else View.VISIBLE
        }

        val lastSync = if (store.syncSavedAt > 0) {
            "Last synced " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(store.syncSavedAt))
        } else {
            "Not synced yet"
        }
        findViewById<TextView>(R.id.footer).text = "$lastSync · Version ${BuildConfig.VERSION_CODE}"
    }

    private fun phoneSyncLine(store: Store): String {
        val error = store.syncError.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
        return "Phone sync is reconnecting$error"
    }

    private fun pill(view: TextView, text: String, color: Int) {
        view.text = text
        view.setTextColor(color)
        view.background = GradientDrawable().apply {
            cornerRadius = 40f * resources.displayMetrics.density
            setColor((color and 0x00FFFFFF) or 0x26000000)
        }
    }

    private fun formatTime(minutes: Int): String =
        LocalTime.of(0, 0).plusMinutes(minutes.toLong())
            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

    private fun formatShort(minutes: Int): String = when {
        minutes < 60 -> "${minutes}m"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }

    private fun formatLong(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> if (minutes == 60) "1 hour" else "${minutes / 60} hours"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }

    companion object {
        private const val OK = 0xFF34C77B.toInt()
        private const val WARN = 0xFFF5A524.toInt()
        private const val BAD = 0xFFFF6B6F.toInt()
        private const val MUTED = 0xFF8D99A6.toInt()
    }
}
