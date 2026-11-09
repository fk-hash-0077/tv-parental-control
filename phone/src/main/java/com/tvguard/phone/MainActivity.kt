package com.tvguard.phone

import android.Manifest
import android.app.TimePickerDialog
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.text.format.DateFormat as AndroidDateFormat
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.text.DateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

/**
 * Parent controls for one paired TV, backed by Firebase Realtime Database
 * at devices/{code}: this app writes config; the TV writes status, apps and history.
 * Field names and defaults must match GuardConfig in the TV app.
 */
class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("parent", Context.MODE_PRIVATE) }

    private lateinit var notice: TextView
    private lateinit var pairSection: View
    private lateinit var controlSection: View
    private lateinit var codeInput: EditText
    private lateinit var status: TextView
    private lateinit var allowSwitch: SwitchCompat
    private lateinit var bonusLabel: TextView
    private lateinit var weekdayLimit: EditText
    private lateinit var weekendLimit: EditText
    private lateinit var scheduleSwitch: SwitchCompat
    private lateinit var bedtimeSwitch: SwitchCompat
    private lateinit var appsLabel: TextView
    private lateinit var historyChart: HistoryChartView
    private lateinit var alertsSwitch: SwitchCompat

    /** Time buttons and the config field each one edits, with its default. */
    private lateinit var timeButtons: List<Triple<Button, String, Int>>

    private var deviceRef: DatabaseReference? = null
    private val listeners = mutableListOf<Pair<DatabaseReference, ValueEventListener>>()
    private var config: Map<*, *> = emptyMap<String, Any>()
    private var tvStatus: DataSnapshot? = null
    private var apps: List<Pair<String, String>> = emptyList()

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            if (granted) {
                enableAlerts(true)
            } else {
                alertsSwitch.isChecked = false
                toast("Notifications are off for this app, so alerts can't be shown.")
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        notice = findViewById(R.id.notice)
        pairSection = findViewById(R.id.pairSection)
        controlSection = findViewById(R.id.controlSection)
        codeInput = findViewById(R.id.codeInput)
        status = findViewById(R.id.status)
        allowSwitch = findViewById(R.id.allowSwitch)
        bonusLabel = findViewById(R.id.bonusLabel)
        weekdayLimit = findViewById(R.id.weekdayLimit)
        weekendLimit = findViewById(R.id.weekendLimit)
        scheduleSwitch = findViewById(R.id.scheduleSwitch)
        bedtimeSwitch = findViewById(R.id.bedtimeSwitch)
        appsLabel = findViewById(R.id.appsLabel)
        historyChart = findViewById(R.id.historyChart)
        alertsSwitch = findViewById(R.id.alertsSwitch)

        timeButtons = listOf(
            Triple(findViewById<Button>(R.id.weekdayStart), "weekdayStart", 15 * 60),
            Triple(findViewById<Button>(R.id.weekdayEnd), "weekdayEnd", 19 * 60),
            Triple(findViewById<Button>(R.id.weekendStart), "weekendStart", 9 * 60),
            Triple(findViewById<Button>(R.id.weekendEnd), "weekendEnd", 20 * 60),
            Triple(findViewById<Button>(R.id.bedtimeStart), "bedtimeStart", 21 * 60),
            Triple(findViewById<Button>(R.id.bedtimeEnd), "bedtimeEnd", 7 * 60),
        )
        timeButtons.forEach { (button, key, default) ->
            button.setOnClickListener { pickTime(key, int(key, default)) }
        }

        findViewById<Button>(R.id.pairButton).setOnClickListener { pair() }
        findViewById<Button>(R.id.bonus15).setOnClickListener { addBonus(15) }
        findViewById<Button>(R.id.bonus30).setOnClickListener { addBonus(30) }
        findViewById<Button>(R.id.bonusClear).setOnClickListener {
            write(mapOf("bonusDate" to "", "bonusMinutes" to 0))
        }
        findViewById<Button>(R.id.saveLimits).setOnClickListener { saveLimits() }
        findViewById<Button>(R.id.chooseApps).setOnClickListener { chooseApps() }
        findViewById<Button>(R.id.releaseButton).setOnClickListener { confirmRelease() }
        findViewById<Button>(R.id.unpairButton).setOnClickListener { unpair() }
        allowSwitch.setOnCheckedChangeListener { button, checked ->
            if (button.isPressed) write(mapOf("blocked" to !checked))
        }
        scheduleSwitch.setOnCheckedChangeListener { button, checked ->
            if (button.isPressed) write(mapOf("scheduleEnabled" to checked))
        }
        bedtimeSwitch.setOnCheckedChangeListener { button, checked ->
            if (button.isPressed) write(mapOf("bedtimeEnabled" to checked))
        }
        alertsSwitch.isChecked = prefs.getBoolean("alerts", false)
        alertsSwitch.setOnCheckedChangeListener { button, checked ->
            if (button.isPressed) onAlertsToggled(checked)
        }

        if (FirebaseApp.getApps(this).isEmpty()) {
            showNotice("Phone sync isn't set up yet. Add google-services.json and rebuild (see README).")
            findViewById<Button>(R.id.pairButton).isEnabled = false
            return
        }
        signIn()
    }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    // ---- Pairing and sync ----

    private fun signIn() {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            showPairedOrPairing()
            return
        }
        auth.signInAnonymously()
            .addOnSuccessListener { showPairedOrPairing() }
            .addOnFailureListener { showNotice("Couldn't connect: ${it.message}") }
    }

    private fun showPairedOrPairing() {
        val code = prefs.getString("code", null)
        if (code == null) {
            pairSection.visibility = View.VISIBLE
            controlSection.visibility = View.GONE
        } else {
            attach(code)
        }
    }

    private fun pair() {
        val code = codeInput.text.toString().uppercase().filter { it.isLetterOrDigit() }
        if (code.length != 12) {
            toast("The code has 12 letters and numbers.")
            return
        }
        // Only pair if the TV has already published its config under this code.
        FirebaseDatabase.getInstance().getReference("devices").child(code).child("config").get()
            .addOnSuccessListener { snapshot ->
                if (!snapshot.exists()) {
                    toast("No TV found with that code. Check the TV is online and the code is right.")
                } else {
                    prefs.edit().putString("code", code).apply()
                    attach(code)
                }
            }
            .addOnFailureListener { toast("Couldn't check the code: ${it.message}") }
    }

    private fun attach(code: String) {
        detach()
        hideNotice()
        pairSection.visibility = View.GONE
        controlSection.visibility = View.VISIBLE
        val ref = FirebaseDatabase.getInstance().getReference("devices").child(code)
        deviceRef = ref
        listen(ref.child("config")) { config = it.value as? Map<*, *> ?: emptyMap<String, Any>(); render() }
        listen(ref.child("status")) { tvStatus = it; render() }
        listen(ref.child("apps")) { snapshot ->
            apps = snapshot.children.mapNotNull { app ->
                val pkg = app.child("pkg").getValue(String::class.java) ?: return@mapNotNull null
                pkg to (app.child("label").getValue(String::class.java) ?: pkg)
            }
            render()
        }
        listen(ref.child("history")) { snapshot ->
            historyChart.setData(
                snapshot.children.associate { (it.key ?: "") to ((it.value as? Number)?.toInt() ?: 0) }
            )
        }
    }

    private fun listen(ref: DatabaseReference, onData: (DataSnapshot) -> Unit) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) = onData(snapshot)
            override fun onCancelled(error: DatabaseError) = showNotice("Sync stopped: ${error.message}")
        }
        ref.addValueEventListener(listener)
        listeners += ref to listener
    }

    private fun detach() {
        listeners.forEach { (ref, listener) -> ref.removeEventListener(listener) }
        listeners.clear()
        deviceRef = null
    }

    private fun write(values: Map<String, Any>) {
        deviceRef?.child("config")?.updateChildren(values)
            ?.addOnFailureListener { toast("Couldn't save: ${it.message}") }
    }

    // ---- Reading config ----

    private fun bool(key: String, default: Boolean = false) = config[key] as? Boolean ?: default
    private fun int(key: String, default: Int) = (config[key] as? Number)?.toInt() ?: default
    private fun packages(): List<String> =
        (config["packages"] as? List<*>)?.mapNotNull { it as? String } ?: DEFAULT_PACKAGES

    private fun todaysBonus(): Int =
        if (config["bonusDate"] == LocalDate.now().toString()) int("bonusMinutes", 0) else 0

    // ---- Rendering ----

    private fun render() {
        allowSwitch.isChecked = !bool("blocked")
        scheduleSwitch.isChecked = bool("scheduleEnabled")
        bedtimeSwitch.isChecked = bool("bedtimeEnabled")
        if (!weekdayLimit.hasFocus()) weekdayLimit.setText(int("dailyLimitMinutes", 60).toString())
        if (!weekendLimit.hasFocus()) weekendLimit.setText(int("weekendLimitMinutes", 120).toString())
        timeButtons.forEach { (button, key, default) -> button.text = formatTime(int(key, default)) }

        val bonus = todaysBonus()
        bonusLabel.text = if (bonus > 0) "$bonus extra minutes today" else "No bonus time today"

        val labels = apps.toMap()
        val chosen = packages()
        appsLabel.text = if (chosen.isEmpty()) {
            "None"
        } else {
            chosen.joinToString(", ") { labels[it] ?: it.substringAfterLast('.') }
        }

        status.text = statusLines().joinToString("\n")
    }

    private fun statusLines(): List<String> {
        val s = tvStatus
        if (s == null || !s.exists()) return listOf("Waiting for the TV to report in...")
        val lines = mutableListOf<String>()
        val used = (s.child("usedMinutesToday").value as? Number)?.toInt() ?: 0
        val limit = (s.child("limitMinutesToday").value as? Number)?.toInt() ?: 0
        lines += if (limit > 0) "Watched today: $used of $limit min" else "Watched today: $used min (no limit)"
        lines += when (s.child("reason").getValue(String::class.java)) {
            "bedtime" -> "Bedtime: the TV is locked"
            "blocked" -> "Blocked apps are turned off"
            "schedule" -> "Blocked apps are outside allowed hours"
            "limit" -> "Today's limit is used up"
            else -> "Blocked apps are available on the TV"
        }
        if (s.child("released").getValue(Boolean::class.java) == true) {
            lines += "Protection removed. The TV app can now be uninstalled."
        } else {
            if (s.child("deviceOwner").getValue(Boolean::class.java) != true) {
                lines += "⚠ TV app isn't Device Owner, so it can't lock apps. Run the setup script."
            }
            if (s.child("usageAccess").getValue(Boolean::class.java) != true) {
                lines += "⚠ TV app can't measure watch time. Run the setup script."
            }
        }
        val updated = (s.child("updatedAt").value as? Number)?.toLong() ?: 0L
        if (updated > 0) {
            lines += "TV last checked in at " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(updated))
        }
        return lines
    }

    private fun formatTime(minutes: Int): String =
        LocalTime.of(0, 0).plusMinutes(minutes.toLong())
            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

    // ---- Actions ----

    private fun addBonus(minutes: Int) {
        val total = todaysBonus() + minutes
        write(mapOf("bonusDate" to LocalDate.now().toString(), "bonusMinutes" to total))
        toast("$total extra minutes today")
    }

    private fun saveLimits() {
        val weekday = weekdayLimit.text.toString().toIntOrNull()
        val weekend = weekendLimit.text.toString().toIntOrNull()
        if (weekday == null || weekend == null || weekday < 0 || weekend < 0) {
            toast("Enter a number of minutes for both.")
            return
        }
        write(mapOf("dailyLimitMinutes" to weekday, "weekendLimitMinutes" to weekend))
        weekdayLimit.clearFocus()
        weekendLimit.clearFocus()
        toast("Limits saved.")
    }

    private fun pickTime(key: String, current: Int) {
        TimePickerDialog(
            this,
            { _, hour, minute -> write(mapOf(key to hour * 60 + minute)) },
            current / 60, current % 60,
            AndroidDateFormat.is24HourFormat(this),
        ).show()
    }

    private fun chooseApps() {
        if (apps.isEmpty()) {
            toast("The TV hasn't sent its app list yet. Try again in a few minutes.")
            return
        }
        val chosen = packages().toMutableSet()
        val checked = apps.map { it.first in chosen }.toBooleanArray()
        AlertDialog.Builder(this)
            .setTitle("Apps to block")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("Save") { _, _ ->
                val installed = apps.map { it.first }.toSet()
                // Keep listed packages that aren't installed, so YouTube stays blocked if it's installed later.
                val result = chosen.filter { it !in installed } +
                    apps.filterIndexed { i, _ -> checked[i] }.map { it.first }
                deviceRef?.child("config/packages")?.setValue(result)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun onAlertsToggled(on: Boolean) {
        if (on && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            enableAlerts(on)
        }
    }

    private fun enableAlerts(on: Boolean) {
        prefs.edit().putBoolean("alerts", on).apply()
        if (on) AlertWorker.schedule(this) else AlertWorker.cancel(this)
    }

    private fun confirmRelease() {
        AlertDialog.Builder(this)
            .setTitle("Remove protection?")
            .setMessage(
                "The TV unlocks everything and stops being managed, so the TV app can be uninstalled. " +
                    "Turning protection back on needs a factory reset and the setup script."
            )
            .setPositiveButton("Remove") { _, _ -> write(mapOf("releaseOwner" to true)) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun unpair() {
        detach()
        prefs.edit().remove("code").apply()
        enableAlerts(false)
        alertsSwitch.isChecked = false
        config = emptyMap<String, Any>()
        tvStatus = null
        apps = emptyList()
        codeInput.setText("")
        showPairedOrPairing()
    }

    // ---- Helpers ----

    private fun showNotice(text: String) {
        notice.text = text
        notice.visibility = View.VISIBLE
    }

    private fun hideNotice() {
        notice.visibility = View.GONE
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    companion object {
        private val DEFAULT_PACKAGES = listOf("com.google.android.youtube.tv", "com.google.android.youtube")
    }
}
