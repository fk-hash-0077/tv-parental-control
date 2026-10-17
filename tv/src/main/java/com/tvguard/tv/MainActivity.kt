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

        val config = store.config
        val status = store.lastStatus
        val owner = Policy(this).isDeviceOwner
        val usage = UsageTracker(this).hasAccess()
        val cloud = FirebaseApp.getApps(this).isNotEmpty()

        val lines = mutableListOf<String>()
        lines += checkLine(owner, "Device Owner", "run scripts/setup-tv.sh")
        lines += checkLine(usage, "Usage access", "run scripts/setup-tv.sh")
        lines += checkLine(cloud, "Phone sync", "add google-services.json and rebuild")
        lines += ""
        lines += if (config.blocked) "YouTube: blocked by parent" else "YouTube: allowed"
        lines += if (config.dailyLimitMinutes > 0) {
            "Watched today: ${status?.usedMinutesToday ?: 0} of ${config.dailyLimitMinutes} min"
        } else {
            "Watched today: ${status?.usedMinutesToday ?: 0} min (no limit)"
        }
        if (status != null) {
            lines += "Last check: " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(status.updatedAt))
        }
        findViewById<TextView>(R.id.status).text = lines.joinToString("\n")
    }

    private fun checkLine(ok: Boolean, label: String, fix: String) =
        if (ok) "✓ $label" else "✗ $label: $fix"
}
