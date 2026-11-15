package com.tvguard.tv

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.firebase.FirebaseApp
import java.text.DateFormat
import java.util.Date

/** Shows the pairing code and whether everything is set up. Parents control the rules from the phone. */
class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.refresh).setOnClickListener {
            GuardService.start(this)
            it.postDelayed({ render() }, 1_500)
        }
    }

    override fun onResume() {
        super.onResume()
        GuardService.start(this)
        render()
    }

    private fun render() {
        val store = Store(this)
        findViewById<TextView>(R.id.code).text = Store.formatId(store.deviceId)

        val status = store.lastStatus
        val owner = Policy(this).isDeviceOwner
        val usage = UsageTracker(this).hasAccess()
        val cloud = FirebaseApp.getApps(this).isNotEmpty()

        val lines = mutableListOf<String>()
        lines += checkLine(owner, "Device Owner", "run scripts/setup-tv.sh")
        lines += checkLine(usage, "Usage access", "run scripts/setup-tv.sh")
        lines += phoneSyncLine(store, cloud)
        lines += ""
        lines += when (status?.reason) {
            "bedtime" -> "Bedtime: the TV is locked"
            "schedule" -> "Outside allowed hours: the TV is locked"
            "limit" -> "Today's TV time is used up: the TV is locked"
            else -> if (status?.appsAllowed == true) "Blocked apps: unblocked for today" else "Blocked apps: blocked"
        }
        val used = status?.usedMinutesToday ?: 0
        val limit = status?.limitMinutesToday ?: 0
        lines += if (limit > 0) "TV time today: $used of $limit min" else "TV time today: $used min (no limit)"
        lines += "Version ${BuildConfig.VERSION_CODE}"
        if (status != null) {
            lines += "Last check: " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(status.updatedAt))
        }
        findViewById<TextView>(R.id.status).text = lines.joinToString("\n")
    }

    /** Ticked only when the server confirmed a write in the last few minutes, not just when set up. */
    private fun phoneSyncLine(store: Store, cloud: Boolean): String {
        if (!cloud) return checkLine(false, "Phone sync", "add google-services.json and rebuild")
        val saved = store.syncSavedAt
        if (System.currentTimeMillis() - saved < 3 * 60_000L) return checkLine(true, "Phone sync", "")
        val last = if (saved > 0) {
            "last reached at " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(saved))
        } else {
            "not reached yet"
        }
        val error = store.syncError.takeIf { it.isNotEmpty() }?.let { " ($it)" } ?: ""
        return "✗ Phone sync: $last, reconnecting$error"
    }

    private fun checkLine(ok: Boolean, label: String, fix: String) =
        if (ok) "✓ $label" else "✗ $label: $fix"
}
