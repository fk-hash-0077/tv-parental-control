package com.tvguard.phone

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.text.format.DateFormat as AndroidDateFormat
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener
import java.text.DateFormat
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

/**
 * Parent controls for one paired TV, backed by Firebase Realtime Database at devices/{code}.
 * This app writes config; the TV writes status, apps, usage and history.
 * Field names and defaults must match GuardConfig in the TV app.
 */
class MainActivity : AppCompatActivity() {
    private val prefs by lazy { Cloud.prefs(this) }
    private val handler = Handler(Looper.getMainLooper())

    private lateinit var notice: TextView
    private lateinit var lockOverlay: View
    private lateinit var pairSection: View
    private lateinit var waitSection: View
    private lateinit var controlSection: View
    private lateinit var codeInput: EditText
    private lateinit var connection: TextView
    private lateinit var syncResult: TextView
    private lateinit var status: TextView
    private lateinit var allowSwitch: SwitchCompat
    private lateinit var bonusLabel: TextView
    private lateinit var weekdayLimit: EditText
    private lateinit var weekendLimit: EditText
    private lateinit var scheduleSwitch: SwitchCompat
    private lateinit var bedtimeSwitch: SwitchCompat
    private lateinit var installsSwitch: SwitchCompat
    private lateinit var appsLabel: TextView
    private lateinit var usageList: TextView
    private lateinit var historyChart: HistoryChartView
    private lateinit var requestSection: View
    private lateinit var requestLabel: TextView
    private lateinit var pendingSection: LinearLayout
    private lateinit var phonesList: LinearLayout
    private lateinit var emergencyLabel: TextView
    private lateinit var updateLabel: TextView

    /** Time buttons and the config field each one edits, with its default. */
    private lateinit var timeButtons: List<Triple<Button, String, Int>>

    private var deviceRef: DatabaseReference? = null
    private val listeners = mutableListOf<Pair<Query, ValueEventListener>>()
    private var approvalListener: Pair<Query, ValueEventListener>? = null
    private var config: Map<*, *> = emptyMap<String, Any>()
    private var tvStatus: DataSnapshot? = null
    private var apps: List<Pair<String, String>> = emptyList()
    private var request: DataSnapshot? = null
    private var requestAnswer: DataSnapshot? = null
    private var phoneOnline = true
    private var pingSentAt = 0L

    // ---- Phone lock: the app opens only after the phone's own fingerprint or screen lock ----
    private var unlocked = false
    private var authenticating = false

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val refresh = object : Runnable {
        override fun run() {
            render()
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        notice = findViewById(R.id.notice)
        lockOverlay = findViewById(R.id.lockOverlay)
        pairSection = findViewById(R.id.pairSection)
        waitSection = findViewById(R.id.waitSection)
        controlSection = findViewById(R.id.controlSection)
        codeInput = findViewById(R.id.codeInput)
        connection = findViewById(R.id.connection)
        syncResult = findViewById(R.id.syncResult)
        status = findViewById(R.id.status)
        allowSwitch = findViewById(R.id.allowSwitch)
        bonusLabel = findViewById(R.id.bonusLabel)
        weekdayLimit = findViewById(R.id.weekdayLimit)
        weekendLimit = findViewById(R.id.weekendLimit)
        scheduleSwitch = findViewById(R.id.scheduleSwitch)
        bedtimeSwitch = findViewById(R.id.bedtimeSwitch)
        installsSwitch = findViewById(R.id.installsSwitch)
        appsLabel = findViewById(R.id.appsLabel)
        usageList = findViewById(R.id.usageList)
        historyChart = findViewById(R.id.historyChart)
        requestSection = findViewById(R.id.requestSection)
        requestLabel = findViewById(R.id.requestLabel)
        pendingSection = findViewById(R.id.pendingSection)
        phonesList = findViewById(R.id.phonesList)
        emergencyLabel = findViewById(R.id.emergencyLabel)
        updateLabel = findViewById(R.id.updateLabel)

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

        codeInput.addTextChangedListener(DashingWatcher(codeInput))
        findViewById<Button>(R.id.unlockButton).setOnClickListener { authenticate() }
        findViewById<Button>(R.id.pairButton).setOnClickListener { pair() }
        findViewById<Button>(R.id.cancelWait).setOnClickListener { unpair() }
        findViewById<Button>(R.id.syncNow).setOnClickListener { syncNow() }
        findViewById<Button>(R.id.unlockCode).setOnClickListener {
            CodeDialogs.showUnlockCode(this, prefs.getString("secret", "") ?: "")
        }
        findViewById<Button>(R.id.request15).setOnClickListener { answerRequest(15) }
        findViewById<Button>(R.id.request30).setOnClickListener { answerRequest(30) }
        findViewById<Button>(R.id.requestNo).setOnClickListener { answerRequest(0) }
        findViewById<Button>(R.id.bonus15).setOnClickListener { addBonus(15) }
        findViewById<Button>(R.id.bonus30).setOnClickListener { addBonus(30) }
        findViewById<Button>(R.id.bonusClear).setOnClickListener {
            write(mapOf("bonusDate" to "", "bonusMinutes" to 0))
        }
        findViewById<Button>(R.id.saveLimits).setOnClickListener { saveLimits() }
        findViewById<Button>(R.id.chooseApps).setOnClickListener { chooseApps() }
        findViewById<Button>(R.id.emergencyButton).setOnClickListener {
            CodeDialogs.setEmergencyCode(this) { code ->
                write(mapOf("emergencyCode" to code, "emergencySetAt" to System.currentTimeMillis()))
                toast("Emergency code saved")
            }
        }
        findViewById<Button>(R.id.updateTv).setOnClickListener {
            write(mapOf("updateRequestAt" to System.currentTimeMillis()))
            toast("Asked the TV to update")
        }
        findViewById<Button>(R.id.phoneUpdate).setOnClickListener {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${BuildConfig.RELEASES_REPO}/releases/latest")))
        }
        findViewById<Button>(R.id.releaseButton).setOnClickListener { confirmRelease() }
        findViewById<Button>(R.id.unpairButton).setOnClickListener { unpair() }
        allowSwitch.setOnCheckedChangeListener { button, checked ->
            if (button.isPressed) write(mapOf("unblockedDate" to if (checked) LocalDate.now().toString() else ""))
        }
        scheduleSwitch.setOnCheckedChangeListener { button, checked ->
            if (button.isPressed) write(mapOf("scheduleEnabled" to checked))
        }
        bedtimeSwitch.setOnCheckedChangeListener { button, checked ->
            if (button.isPressed) write(mapOf("bedtimeEnabled" to checked))
        }
        installsSwitch.setOnCheckedChangeListener { button, checked ->
            if (button.isPressed) write(mapOf("installsAllowed" to checked))
        }

        if (!Cloud.isConfigured(this)) {
            showNotice("Phone sync isn't set up yet. Add google-services.json and rebuild (see README).")
            findViewById<Button>(R.id.pairButton).isEnabled = false
            return
        }
        FirebaseDatabase.getInstance().getReference(".info/connected")
            .addValueEventListener(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    phoneOnline = snapshot.getValue(Boolean::class.java) == true
                    render()
                }
                override fun onCancelled(error: DatabaseError) = Unit
            })
        signIn()
    }

    override fun onStart() {
        super.onStart()
        if (!unlocked) authenticate()
        handler.post(refresh)
    }

    override fun onStop() {
        handler.removeCallbacks(refresh)
        // Lock again whenever the app leaves the screen, except while the phone's own
        // fingerprint or PIN screen is showing on top of it.
        if (!authenticating && !isChangingConfigurations) {
            unlocked = false
            lockOverlay.visibility = View.VISIBLE
        }
        super.onStop()
    }

    override fun onDestroy() {
        detach()
        super.onDestroy()
    }

    private fun authenticate() {
        if (authenticating) return
        val allowed = BIOMETRIC_WEAK or DEVICE_CREDENTIAL
        if (BiometricManager.from(this).canAuthenticate(allowed) != BiometricManager.BIOMETRIC_SUCCESS) {
            // No fingerprint or screen lock on this phone, so there is nothing to check against.
            unlocked = true
            lockOverlay.visibility = View.GONE
            showNotice("Set a screen lock on this phone so only you can open TV Guard.")
            return
        }
        authenticating = true
        val prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    authenticating = false
                    unlocked = true
                    lockOverlay.visibility = View.GONE
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    authenticating = false
                }
            })
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Open TV Guard")
                .setAllowedAuthenticators(allowed)
                .build()
        )
    }

    // ---- Pairing and approval ----

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
        val code = Cloud.code(this)
        if (code == null) {
            show(pairSection)
        } else {
            waitForApproval(code)
        }
    }

    private fun show(section: View) {
        listOf(pairSection, waitSection, controlSection).forEach {
            it.visibility = if (it === section) View.VISIBLE else View.GONE
        }
    }

    private fun pair() {
        val code = codeInput.text.toString().uppercase().filter { it.isLetterOrDigit() }
        if (code.length != 12) {
            toast("The code has 12 letters and numbers.")
            return
        }
        Cloud.device(code).child("tvUid").get()
            .addOnSuccessListener { snapshot ->
                if (!snapshot.exists()) {
                    toast("No TV found with that code. Check the TV is on, online and has the newest TV Guard.")
                } else {
                    prefs.edit().putString("code", code).apply()
                    waitForApproval(code)
                }
            }
            .addOnFailureListener { toast("Couldn't check the code: ${it.message}") }
    }

    /**
     * This phone can see the TV only once it's in the TV's parents list. The first phone is
     * approved by the TV itself; later phones by a phone that's already approved.
     */
    private fun waitForApproval(code: String) {
        val uid = Cloud.uid() ?: return
        approvalListener?.let { (q, l) -> q.removeEventListener(l) }
        val query = Cloud.device(code).child("parents").child(uid)
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                if (snapshot.exists()) {
                    if (deviceRef == null) attach(code)
                } else {
                    detach()
                    show(waitSection)
                    Cloud.device(code).child("pending").child(uid).setValue(
                        mapOf("name" to "${Build.MANUFACTURER} ${Build.MODEL}", "at" to ServerValue.TIMESTAMP)
                    )
                }
            }
            override fun onCancelled(error: DatabaseError) = showNotice("Couldn't reach the TV: ${error.message}")
        }
        query.addValueEventListener(listener)
        approvalListener = query to listener
    }

    private fun attach(code: String) {
        detach()
        hideNotice()
        show(controlSection)
        val ref = Cloud.device(code)
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
        listen(ref.child("usage")) { renderUsage(it) }
        listen(ref.child("secret")) { snapshot ->
            snapshot.getValue(String::class.java)?.let { prefs.edit().putString("secret", it).apply() }
        }
        listen(ref.child("request")) { request = it; render() }
        listen(ref.child("requestAnswer")) { requestAnswer = it; render() }
        listen(ref.child("pending")) { renderPending(it) }
        listen(ref.child("parents")) { renderPhones(it) }
        Cloud.registerToken(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun listen(query: Query, onData: (DataSnapshot) -> Unit) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) = onData(snapshot)
            override fun onCancelled(error: DatabaseError) {
                // Usually this phone was removed by another parent; go back to waiting.
                showNotice("Sync stopped: ${error.message}")
                detach()
                Cloud.code(this@MainActivity)?.let { handler.postDelayed({ waitForApproval(it) }, 2_000) }
            }
        }
        query.addValueEventListener(listener)
        listeners += query to listener
    }

    private fun detach() {
        listeners.forEach { (query, listener) -> query.removeEventListener(listener) }
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
    private fun long(key: String) = (config[key] as? Number)?.toLong() ?: 0L
    private fun packages(): List<String> =
        (config["packages"] as? List<*>)?.mapNotNull { it as? String } ?: DEFAULT_PACKAGES

    private fun todaysBonus(): Int =
        if (config["bonusDate"] == LocalDate.now().toString()) int("bonusMinutes", 0) else 0

    // ---- Rendering ----

    private fun render() {
        if (deviceRef == null) return
        allowSwitch.isChecked = config["unblockedDate"] == LocalDate.now().toString()
        scheduleSwitch.isChecked = bool("scheduleEnabled")
        bedtimeSwitch.isChecked = bool("bedtimeEnabled")
        installsSwitch.isChecked = bool("installsAllowed")
        if (!weekdayLimit.hasFocus()) weekdayLimit.setText(int("dailyLimitMinutes", 120).toString())
        if (!weekendLimit.hasFocus()) weekendLimit.setText(int("weekendLimitMinutes", 180).toString())
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

        emergencyLabel.text = if ((config["emergencyCode"] as? String).isNullOrEmpty()) {
            "Off. Set one for when your phone isn't around."
        } else {
            "Set. It works once, then you set a new one."
        }

        renderConnection()
        renderRequest()
        status.text = statusLines().joinToString("\n")
        val s = tvStatus
        val version = (s?.child("versionCode")?.value as? Number)?.toInt()
        val update = s?.child("update")?.getValue(String::class.java).orEmpty()
        updateLabel.text = listOfNotNull(
            version?.let { "Version $it" },
            update.ifEmpty { null },
        ).joinToString("\n").ifEmpty { "Waiting for the TV" }
    }

    private fun lastSeen(): Long = (tvStatus?.child("updatedAt")?.value as? Number)?.toLong() ?: 0L

    private fun renderConnection() {
        val seen = lastSeen()
        val ago = (System.currentTimeMillis() - seen) / 60_000
        val (text, color) = when {
            !phoneOnline -> "● This phone is offline" to COLOR_BAD
            seen == 0L -> "● Waiting for the TV" to COLOR_WARN
            ago < 2 -> "● Connected to the TV" to COLOR_OK
            ago < 20 -> "● TV last seen $ago min ago" to COLOR_WARN
            else -> "● TV offline, last seen ${formatWhen(seen)}" to COLOR_BAD
        }
        connection.text = text
        connection.setTextColor(color)

        val pong = (tvStatus?.child("pongAt")?.value as? Number)?.toLong() ?: 0L
        if (pingSentAt > 0 && pong >= pingSentAt) {
            syncResult.text = "The TV answered and is up to date."
            syncResult.visibility = View.VISIBLE
            pingSentAt = 0
        }
    }

    private fun renderRequest() {
        val askedAt = (request?.child("at")?.value as? Number)?.toLong() ?: 0L
        val answeredFor = (requestAnswer?.child("requestAt")?.value as? Number)?.toLong() ?: 0L
        val open = askedAt > 0 && askedAt != answeredFor && System.currentTimeMillis() - askedAt < 60 * 60_000
        requestSection.visibility = if (open) View.VISIBLE else View.GONE
        if (open) {
            val why = when (request?.child("reason")?.getValue(String::class.java)) {
                "bedtime" -> "during bedtime"
                "schedule" -> "outside allowed hours"
                else -> "after today's limit"
            }
            requestLabel.text = "The TV is asking for more time $why (${formatWhen(askedAt)})"
        }
    }

    private fun renderUsage(snapshot: DataSnapshot) {
        if (snapshot.child("date").getValue(String::class.java) != LocalDate.now().toString()) {
            usageList.text = "Nothing watched yet today"
            return
        }
        val lines = snapshot.child("apps").children.mapNotNull { app ->
            val minutes = (app.child("minutes").value as? Number)?.toInt() ?: return@mapNotNull null
            val label = app.child("label").getValue(String::class.java) ?: return@mapNotNull null
            "$label: ${formatMinutes(minutes)}"
        }
        usageList.text = if (lines.isEmpty()) "Nothing watched yet today" else lines.joinToString("\n")
    }

    private fun renderPending(snapshot: DataSnapshot) {
        pendingSection.removeAllViews()
        val code = Cloud.code(this) ?: return
        for (phone in snapshot.children) {
            val uid = phone.key ?: continue
            val name = phone.child("name").getValue(String::class.java) ?: "A phone"
            pendingSection.addView(TextView(this).apply {
                text = "$name wants to pair with this TV"
                textSize = 17f
                setPadding(0, dp(20), 0, 0)
            })
            pendingSection.addView(LinearLayout(this).apply {
                addView(Button(this@MainActivity).apply {
                    text = "Approve"
                    setOnClickListener { Cloud.approvePhone(code, uid, name) }
                })
                addView(Button(this@MainActivity).apply {
                    text = "Deny"
                    setOnClickListener { Cloud.denyPhone(code, uid) }
                })
            })
        }
        pendingSection.visibility = if (snapshot.hasChildren()) View.VISIBLE else View.GONE
    }

    private fun renderPhones(snapshot: DataSnapshot) {
        phonesList.removeAllViews()
        val code = Cloud.code(this) ?: return
        val me = Cloud.uid()
        for (phone in snapshot.children) {
            val uid = phone.key ?: continue
            val name = phone.child("name").getValue(String::class.java) ?: "Phone"
            val row = LinearLayout(this).apply { gravity = android.view.Gravity.CENTER_VERTICAL }
            row.addView(TextView(this).apply {
                text = if (uid == me) "$name (this phone)" else name
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            if (uid != me) {
                row.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                    text = "Remove"
                    setOnClickListener {
                        AlertDialog.Builder(this@MainActivity)
                            .setMessage("Remove $name? It will need approval to pair again.")
                            .setPositiveButton("Remove") { _, _ -> Cloud.removePhone(code, uid) }
                            .setNegativeButton("Cancel", null)
                            .show()
                    }
                })
            }
            phonesList.addView(row)
        }
    }

    private fun statusLines(): List<String> {
        val s = tvStatus
        if (s == null || !s.exists()) return listOf("Waiting for the TV to report in...")
        val lines = mutableListOf<String>()
        val used = (s.child("usedMinutesToday").value as? Number)?.toInt() ?: 0
        val limit = (s.child("limitMinutesToday").value as? Number)?.toInt() ?: 0
        lines += if (limit > 0) {
            "TV time today: ${formatMinutes(used)} of ${formatMinutes(limit)}"
        } else {
            "TV time today: ${formatMinutes(used)} (no limit)"
        }
        lines += when (s.child("reason").getValue(String::class.java)) {
            "bedtime" -> "The TV is locked for bedtime"
            "schedule" -> "The TV is locked: outside allowed hours"
            "limit" -> "The TV is locked: today's time is used up"
            else -> "The TV is unlocked"
        }
        val overrideUntil = (s.child("overrideUntil").value as? Number)?.toLong() ?: 0L
        if (overrideUntil > System.currentTimeMillis()) {
            lines += "Unlocked early until ${formatWhen(overrideUntil)}"
        }
        if (s.child("released").getValue(Boolean::class.java) == true) {
            lines += "Protection removed. The TV app can now be uninstalled."
        } else {
            if (s.child("deviceOwner").getValue(Boolean::class.java) != true) {
                lines += "⚠ TV app isn't Device Owner, so it can't lock anything. Run the setup again."
            }
            if (s.child("usageAccess").getValue(Boolean::class.java) != true) {
                lines += "⚠ TV app can't measure TV time. Run the setup again."
            }
        }
        return lines
    }

    private fun formatTime(minutes: Int): String =
        LocalTime.of(0, 0).plusMinutes(minutes.toLong())
            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

    private fun formatWhen(millis: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

    private fun formatMinutes(minutes: Int): String =
        if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"

    // ---- Actions ----

    private fun syncNow() {
        val db = FirebaseDatabase.getInstance()
        db.goOffline()
        db.goOnline()
        pingSentAt = System.currentTimeMillis()
        syncResult.text = "Syncing…"
        syncResult.visibility = View.VISIBLE
        write(mapOf("pingAt" to pingSentAt))
        val sent = pingSentAt
        handler.postDelayed({
            if (pingSentAt == sent) {
                pingSentAt = 0
                syncResult.text = "The TV didn't answer. It may be off, in standby without Wi-Fi, or offline."
            }
        }, 20_000)
    }

    private fun answerRequest(minutes: Int) {
        val code = Cloud.code(this) ?: return
        val askedAt = (request?.child("at")?.value as? Number)?.toLong() ?: return
        val reason = request?.child("reason")?.getValue(String::class.java) ?: "limit"
        Cloud.answerRequest(code, askedAt, reason, minutes) { ok ->
            toast(if (!ok) "Couldn't save the answer" else if (minutes > 0) "Gave $minutes more minutes" else "Told the TV no")
        }
    }

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
                // Keep listed packages that aren't installed, so they stay blocked if installed later.
                val result = chosen.filter { it !in installed } +
                    apps.filterIndexed { i, _ -> checked[i] }.map { it.first }
                deviceRef?.child("config/packages")?.setValue(result)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun confirmRelease() {
        AlertDialog.Builder(this)
            .setTitle("Remove protection?")
            .setMessage(
                "The TV unlocks everything, allows app installs again, and stops being managed, so the TV app can be uninstalled. " +
                    "Turning protection back on needs a factory reset and the setup again."
            )
            .setPositiveButton("Remove") { _, _ -> write(mapOf("releaseOwner" to true)) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun unpair() {
        val code = Cloud.code(this)
        val uid = Cloud.uid()
        if (code != null && uid != null) {
            Cloud.device(code).child("pending").child(uid).removeValue()
            Cloud.removePhone(code, uid)
        }
        approvalListener?.let { (q, l) -> q.removeEventListener(l) }
        approvalListener = null
        detach()
        prefs.edit().remove("code").remove("secret").apply()
        config = emptyMap<String, Any>()
        tvStatus = null
        apps = emptyList()
        codeInput.setText("")
        show(pairSection)
    }

    // ---- Helpers ----

    private fun showNotice(text: String) {
        notice.text = text
        notice.visibility = View.VISIBLE
    }

    private fun hideNotice() {
        notice.visibility = View.GONE
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()

    /** Formats the pairing code as XXXX-XXXX-XXXX while it's typed. */
    private class DashingWatcher(private val input: EditText) : TextWatcher {
        private var editing = false
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable) {
            if (editing) return
            val clean = s.toString().uppercase().filter { it.isLetterOrDigit() }.take(12)
            val formatted = clean.chunked(4).joinToString("-")
            if (formatted == s.toString()) return
            editing = true
            s.replace(0, s.length, formatted)
            input.setSelection(formatted.length)
            editing = false
        }
    }

    companion object {
        private val DEFAULT_PACKAGES = listOf(
            "com.google.android.youtube.tv",
            "com.google.android.youtube",
            "com.google.android.youtube.tvkids",
            "com.roblox.client",
        )
        private val COLOR_OK = Color.parseColor("#2E7D32")
        private val COLOR_WARN = Color.parseColor("#EF8F00")
        private val COLOR_BAD = Color.parseColor("#C62828")
    }
}
