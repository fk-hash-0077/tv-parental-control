package com.tvguard.phone

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
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
import androidx.activity.result.PickVisualMediaRequest
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
    private lateinit var pageTitle: TextView
    private lateinit var greeting: TextView
    private lateinit var pills: FlowRow
    private lateinit var ring: RingView
    private lateinit var ringValue: TextView
    private lateinit var ringLabel: TextView
    private lateinit var leftLabel: TextView
    private lateinit var leftValue: TextView
    private lateinit var topApp: TextView
    private lateinit var nowTitle: TextView
    private lateinit var nowDetail: TextView
    private lateinit var diagConnection: TextView
    private lateinit var bonusClear: View
    private lateinit var lockNowIcon: TextView
    private lateinit var lockNowLabel: TextView
    private lateinit var unblockIcon: TextView
    private lateinit var unblockLabel: TextView
    private lateinit var bottomNav: View
    private lateinit var tabs: List<Triple<TextView, View, String>>
    private var currentTab = 0
    private var usage: DataSnapshot? = null
    private var nowPlaying: DataSnapshot? = null
    private lateinit var allowSwitch: SwitchCompat
    private lateinit var bonusLabel: TextView
    private lateinit var weekdayLimit: EditText
    private lateinit var weekendLimit: EditText
    private lateinit var scheduleSwitch: SwitchCompat
    private lateinit var tvTimeSummary: TextView
    private lateinit var parentCard: View
    private lateinit var parentTitle: TextView
    private lateinit var parentDetail: TextView
    private lateinit var parentOnButton: Button
    private lateinit var parentMore: Button
    private lateinit var parentEnd: Button
    private lateinit var protectionLabel: TextView
    /** Set once this phone has moved an old bedtime into TV hours, so it's done only once. */
    private var migratedBedtime = false
    private lateinit var installsSwitch: SwitchCompat
    private lateinit var appsLabel: TextView
    private lateinit var usageList: LinearLayout
    private lateinit var historyChart: HistoryChartView
    private lateinit var historyTotal: TextView
    private lateinit var lockNowHistory: TextView
    private lateinit var updateCard: View
    private lateinit var updateCardTitle: TextView
    private lateinit var updateCardText: TextView
    private lateinit var updateCardTv: Button
    private lateinit var updateCardPhone: Button
    private lateinit var requestSection: View
    private lateinit var requestLabel: TextView
    private lateinit var pendingSection: LinearLayout
    private lateinit var phonesList: LinearLayout
    private lateinit var emergencyLabel: TextView
    private lateinit var updateLabel: TextView
    /**
     * One lock screen photo: the weekday limit one or the bedtime one. Each goes to its own
     * Firebase node, and the TV reports when it has it saved.
     */
    private class PhotoSlot(
        val node: String,
        val statusKey: String,
        /** When it shows on the TV, e.g. "when weekday TV time is used up". */
        val shownWhen: String,
        val label: TextView,
        val remove: Button,
    ) {
        /** When this phone last sent or removed the photo, until the TV confirms it. */
        var sentAt = 0L
        var sentEmpty = false
    }

    private lateinit var limitPhoto: PhotoSlot
    private lateinit var bedtimePhoto: PhotoSlot
    /** The slot the photo picker is choosing for. */
    private var pickingFor: PhotoSlot? = null

    /** Time buttons and the config field each one edits, with its default. */
    private lateinit var timeButtons: List<Triple<Button, String, Int>>

    private var deviceRef: DatabaseReference? = null
    /** Set when Update TV is tapped, until the TV reports how the update ended. */
    private var updateAskedAt = 0L
    private var updateTextAtAsk = ""
    private val listeners = mutableListOf<Pair<Query, ValueEventListener>>()
    private var approvalListener: Pair<Query, ValueEventListener>? = null
    private var config: Map<*, *> = emptyMap<String, Any>()
    private var tvStatus: DataSnapshot? = null
    private var apps: List<Pair<String, String>> = emptyList()
    private var request: DataSnapshot? = null
    private var requestAnswer: DataSnapshot? = null
    private var diag: DataSnapshot? = null
    private var logs: DataSnapshot? = null
    private var parents: DataSnapshot? = null
    private var latestRelease: ReleaseCheck.Release? = null
    private var phoneOnline = true
    private var pingSentAt = 0L

    // ---- Phone lock: the app opens only after the phone's own fingerprint or screen lock ----
    private var unlocked = false
    private var authenticating = false

    private val pickPhoto =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            val slot = pickingFor
            if (uri != null && slot != null) sendPhoto(slot, uri)
        }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private val refresh = object : Runnable {
        override fun run() {
            // Asks GitHub at most every 30 minutes; the answer redraws the update card.
            ReleaseCheck.refresh(this@MainActivity) { latestRelease = it; render() }
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
        tvTimeSummary = findViewById(R.id.tvTimeSummary)
        parentCard = findViewById(R.id.parentCard)
        parentTitle = findViewById(R.id.parentTitle)
        parentDetail = findViewById(R.id.parentDetail)
        parentOnButton = findViewById(R.id.parentOn)
        parentMore = findViewById(R.id.parentMore)
        parentEnd = findViewById(R.id.parentEnd)
        protectionLabel = findViewById(R.id.protectionLabel)
        installsSwitch = findViewById(R.id.installsSwitch)
        appsLabel = findViewById(R.id.appsLabel)
        usageList = findViewById(R.id.usageList)
        historyChart = findViewById(R.id.historyChart)
        historyTotal = findViewById(R.id.historyTotal)
        lockNowHistory = findViewById(R.id.lockNowHistory)
        updateCard = findViewById(R.id.updateCard)
        updateCardTitle = findViewById(R.id.updateCardTitle)
        updateCardText = findViewById(R.id.updateCardText)
        updateCardTv = findViewById(R.id.updateCardTv)
        updateCardPhone = findViewById(R.id.updateCardPhone)
        latestRelease = ReleaseCheck.cached(this)
        requestSection = findViewById(R.id.requestSection)
        requestLabel = findViewById(R.id.requestLabel)
        pendingSection = findViewById(R.id.pendingSection)
        phonesList = findViewById(R.id.phonesList)
        emergencyLabel = findViewById(R.id.emergencyLabel)
        updateLabel = findViewById(R.id.updateLabel)
        pageTitle = findViewById(R.id.pageTitle)
        greeting = findViewById(R.id.greeting)
        pills = findViewById(R.id.pills)
        ring = findViewById(R.id.ring)
        ringValue = findViewById(R.id.ringValue)
        ringLabel = findViewById(R.id.ringLabel)
        leftLabel = findViewById(R.id.leftLabel)
        leftValue = findViewById(R.id.leftValue)
        topApp = findViewById(R.id.topApp)
        nowTitle = findViewById(R.id.nowTitle)
        nowDetail = findViewById(R.id.nowDetail)
        diagConnection = findViewById(R.id.diagConnection)
        bonusClear = findViewById(R.id.bonusClear)
        lockNowIcon = findViewById(R.id.lockNowIcon)
        lockNowLabel = findViewById(R.id.lockNowLabel)
        unblockIcon = findViewById(R.id.unblockIcon)
        unblockLabel = findViewById(R.id.unblockLabel)
        bottomNav = findViewById(R.id.bottomNav)
        tabs = listOf(
            Triple(findViewById<TextView>(R.id.navHome), findViewById<View>(R.id.tabHome), ""),
            Triple(findViewById<TextView>(R.id.navLimits), findViewById<View>(R.id.tabLimits), "TV time"),
            Triple(findViewById<TextView>(R.id.navApps), findViewById<View>(R.id.tabApps), "Apps"),
            Triple(findViewById<TextView>(R.id.navFamily), findViewById<View>(R.id.tabFamily), "Family"),
            Triple(findViewById<TextView>(R.id.navDiag), findViewById<View>(R.id.tabDiag), "Diagnostics"),
        )
        tabs.forEachIndexed { i, (nav, _, _) -> nav.setOnClickListener { selectTab(i) } }
        selectTab(savedInstanceState?.getInt("tab") ?: 0)
        styleTileIcons()

        timeButtons = listOf(
            Triple(findViewById<Button>(R.id.weekdayStart), "weekdayStart", 15 * 60),
            Triple(findViewById<Button>(R.id.weekdayEnd), "weekdayEnd", 19 * 60),
            Triple(findViewById<Button>(R.id.weekendStart), "weekendStart", 9 * 60),
            Triple(findViewById<Button>(R.id.weekendEnd), "weekendEnd", 20 * 60),
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
        findViewById<View>(R.id.bonus15).setOnClickListener { addBonus(15) }
        findViewById<View>(R.id.bonus30).setOnClickListener { addBonus(30) }
        findViewById<View>(R.id.lockNow).setOnClickListener { toggleLockNow() }
        findViewById<View>(R.id.unblockTile).setOnClickListener { toggleUnblock() }
        parentOnButton.setOnClickListener { chooseParentMode() }
        parentMore.setOnClickListener { extendParentMode() }
        parentEnd.setOnClickListener { endParentMode() }
        findViewById<View>(R.id.connection).setOnClickListener { syncNow() }
        bonusClear.setOnClickListener {
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
        findViewById<Button>(R.id.updateTv).setOnClickListener { requestTvUpdate() }
        updateCardTv.setOnClickListener { requestTvUpdate() }
        findViewById<Button>(R.id.phoneUpdate).setOnClickListener { openPhoneUpdate() }
        updateCardPhone.setOnClickListener { openPhoneUpdate() }
        limitPhoto = PhotoSlot(
            "photo", "photoAt", "when weekday TV time is used up",
            findViewById(R.id.photoLabel), findViewById(R.id.photoRemove),
        )
        bedtimePhoto = PhotoSlot(
            "bedtimePhoto", "bedtimePhotoAt", "outside TV hours",
            findViewById(R.id.bedtimePhotoLabel), findViewById(R.id.bedtimePhotoRemove),
        )
        findViewById<Button>(R.id.photoChoose).setOnClickListener { pickPhoto(limitPhoto) }
        findViewById<Button>(R.id.bedtimePhotoChoose).setOnClickListener { pickPhoto(bedtimePhoto) }
        listOf(limitPhoto, bedtimePhoto).forEach { slot ->
            slot.remove.setOnClickListener { confirmRemovePhoto(slot) }
        }
        findViewById<Button>(R.id.diagnosticsButton).setOnClickListener { showDiagnostics() }
        findViewById<Button>(R.id.releaseButton).setOnClickListener { confirmRelease() }
        findViewById<Button>(R.id.unpairButton).setOnClickListener { unpair() }
        allowSwitch.setOnCheckedChangeListener { button, checked ->
            if (button.isPressed) write(mapOf("unblockedDate" to if (checked) LocalDate.now().toString() else ""))
        }
        scheduleSwitch.setOnCheckedChangeListener { button, checked ->
            if (button.isPressed) write(mapOf("scheduleEnabled" to checked))
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt("tab", currentTab)
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
        val paired = section === controlSection
        bottomNav.visibility = if (paired) View.VISIBLE else View.GONE
        if (paired) selectTab(currentTab) else {
            pageTitle.text = "TV Guard"
            greeting.text = "Welcome"
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
        listen(ref.child("config")) {
            config = it.value as? Map<*, *> ?: emptyMap<String, Any>()
            migrateBedtime()
            render()
        }
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
            val (total, average) = historyChart.totals()
            historyTotal.text = "Total ${formatMinutes(total)} · about ${formatMinutes(average)} a day"
            historyTotal.visibility = if (total > 0) View.VISIBLE else View.GONE
        }
        listen(ref.child("usage")) { usage = it; render() }
        listen(ref.child("nowPlaying")) { nowPlaying = it; render() }
        listen(ref.child("secret")) { snapshot ->
            snapshot.getValue(String::class.java)?.let { prefs.edit().putString("secret", it).apply() }
        }
        listen(ref.child("request")) { request = it; render() }
        listen(ref.child("requestAnswer")) { requestAnswer = it; render() }
        listen(ref.child("pending")) { renderPending(it) }
        listen(ref.child("parents")) { parents = it; renderPhones(it); render() }
        listen(ref.child("diag")) { diag = it; renderProtection() }
        listen(ref.child("logs")) { logs = it }
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
        tvTimeSummary.text = tvTimeSummaryText()
        installsSwitch.isChecked = bool("installsAllowed")
        if (!weekdayLimit.hasFocus()) weekdayLimit.setText(int("dailyLimitMinutes", 120).toString())
        if (!weekendLimit.hasFocus()) weekendLimit.setText(int("weekendLimitMinutes", 180).toString())
        timeButtons.forEach { (button, key, default) -> button.text = formatTime(int(key, default)) }

        val bonus = todaysBonus()
        bonusLabel.text = if (bonus > 0) "+$bonus bonus minutes today" else "Tap +15 or +30 to give bonus time today"
        bonusClear.visibility = if (bonus > 0) View.VISIBLE else View.GONE

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

        renderPhoto(limitPhoto)
        renderPhoto(bedtimePhoto)
        renderConnection()
        renderRequest()
        renderHome()
        renderParentMode()
        usage?.let { renderUsage(it) }
        val warnings = warningLines()
        status.text = warnings.joinToString("\n")
        status.visibility = if (warnings.isEmpty()) View.GONE else View.VISIBLE
        val s = tvStatus
        val version = (s?.child("versionCode")?.value as? Number)?.toInt()
        val update = s?.child("update")?.getValue(String::class.java).orEmpty()
        updateLabel.text = listOfNotNull(
            version?.let { "Version ${tvVersionLabel()}" },
            update.ifEmpty { null },
        ).joinToString("\n").ifEmpty { "Waiting for the TV" }
        renderUpdateCard()
        lockNowHistory.text = Diagnostics.lockNowLines(config) { formatDayTime(it) }
            .joinToString("\n")
        announceUpdateResult(update)
    }

    private fun tvVersionLabel(): String {
        val code = (tvStatus?.child("versionCode")?.value as? Number)?.toInt() ?: 0
        return ReleaseCheck.label(tvStatus?.child("versionName")?.getValue(String::class.java), code)
    }

    private fun phoneVersionLabel(): String = ReleaseCheck.label(BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)

    /**
     * The "Update available" card on Home: shown while the phone app or the TV is older than
     * the newest release on GitHub, with a button for each one that needs it.
     */
    private fun renderUpdateCard() {
        val release = latestRelease
        val tvCode = (tvStatus?.child("versionCode")?.value as? Number)?.toInt() ?: 0
        val update = tvStatus?.child("update")?.getValue(String::class.java).orEmpty()
        val phoneOld = release != null && BuildConfig.VERSION_CODE < release.build
        val tvOld = release != null && tvCode in 1 until release.build
        updateCard.visibility = if (phoneOld || tvOld) View.VISIBLE else View.GONE
        if (release == null || !(phoneOld || tvOld)) return
        val newName = release.name
        val tvBusy = tvOld && (update.startsWith("Downloading") || update.startsWith("Installing") ||
            update.startsWith("Checking"))
        updateCardTitle.text = "⬆ Version $newName is ready"
        updateCardText.text = listOfNotNull(
            if (tvOld) {
                if (tvBusy) "TV: $update" else "TV has ${tvVersionLabel()}. Tap Update TV; it installs by itself in a few minutes."
            } else {
                null
            },
            if (phoneOld) {
                "This phone has ${phoneVersionLabel()}. Tap Update this phone, then open the downloaded file to install it."
            } else {
                null
            },
        ).joinToString("\n")
        updateCardTv.visibility = if (tvOld) View.VISIBLE else View.GONE
        updateCardTv.isEnabled = !tvBusy
        updateCardPhone.visibility = if (phoneOld) View.VISIBLE else View.GONE
    }

    private fun requestTvUpdate() {
        updateAskedAt = System.currentTimeMillis()
        updateTextAtAsk = tvStatus?.child("update")?.getValue(String::class.java).orEmpty()
        write(mapOf("updateRequestAt" to updateAskedAt))
        toast("Asked the TV to update")
    }

    /** Downloads the newest phone app straight away when known, otherwise opens the release page. */
    private fun openPhoneUpdate() {
        val apk = latestRelease?.phoneApkUrl.orEmpty()
        val url = apk.ifEmpty { "https://github.com/${BuildConfig.RELEASES_REPO}/releases/latest" }
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        if (apk.isNotEmpty()) toast("Downloading. Open the file when it finishes to install.")
    }

    /** After Update TV, tells the parent once how it ended, so they don't have to watch the label. */
    private fun announceUpdateResult(update: String) {
        if (updateAskedAt == 0L || update == updateTextAtAsk) return
        val finished = update.startsWith("Updated to") || update.startsWith("Up to date") ||
            update.startsWith("Update failed") || update.startsWith("No TV update")
        if (!finished) return
        updateAskedAt = 0L
        AlertDialog.Builder(this)
            .setTitle(if (update.startsWith("Update failed")) "TV update didn't work" else "TV update")
            .setMessage(update)
            .setPositiveButton("OK", null)
            .show()
    }

    private fun renderPhoto(slot: PhotoSlot) {
        val onTv = (tvStatus?.child(slot.statusKey)?.value as? Number)?.toLong() ?: 0L
        val waiting = slot.sentAt > 0 && (if (slot.sentEmpty) onTv != 0L else onTv < slot.sentAt)
        if (slot.sentAt > 0 && !waiting) slot.sentAt = 0
        slot.label.text = when {
            waiting && slot.sentEmpty -> "Removing the photo from the TV…"
            waiting -> "Sending the photo to the TV… It arrives the next time the TV is online."
            onTv > 0 -> "On the TV. It shows in a round frame ${slot.shownWhen}. It's saved only on the TV."
            else -> "None. Choose a photo to show on the TV ${slot.shownWhen}."
        }
        slot.remove.visibility = if (onTv > 0 || (waiting && !slot.sentEmpty)) View.VISIBLE else View.GONE
    }

    private fun lastSeen(): Long = (tvStatus?.child("updatedAt")?.value as? Number)?.toLong() ?: 0L

    private fun renderConnection() {
        val seen = lastSeen()
        val ago = (System.currentTimeMillis() - seen) / 60_000
        val (text, color) = when {
            !phoneOnline -> "● This phone is offline" to color(R.color.bad)
            seen == 0L -> "● Waiting for the TV" to color(R.color.warn)
            ago < 2 -> "● Connected to the TV" to color(R.color.ok)
            ago < 20 -> "● TV last seen $ago min ago" to color(R.color.warn)
            else -> "● TV offline, last seen ${formatWhen(seen)}" to color(R.color.bad)
        }
        connection.text = when {
            !phoneOnline || seen == 0L -> "Tap to sync ⟳"
            ago < 2 -> "Synced just now ⟳"
            else -> "Synced ${formatWhen(seen)} · tap to sync ⟳"
        }
        diagConnection.text = text.removePrefix("● ")
        diagConnection.setTextColor(color)
        connectionPill = text.removePrefix("● ") to color

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
                "hours", "bedtime", "schedule" -> "outside TV hours"
                "manual" -> "while you've locked it"
                else -> "after today's TV time"
            }
            requestLabel.text = "The TV is asking for more time $why (${formatWhen(askedAt)})"
        }
    }

    /** Today's minutes per app, most watched first. */
    private fun todaysApps(snapshot: DataSnapshot? = usage): List<Triple<String, String?, Int>> {
        if (snapshot == null || snapshot.child("date").getValue(String::class.java) != LocalDate.now().toString()) {
            return emptyList()
        }
        return snapshot.child("apps").children.mapNotNull { app ->
            val minutes = (app.child("minutes").value as? Number)?.toInt() ?: return@mapNotNull null
            val label = app.child("label").getValue(String::class.java) ?: return@mapNotNull null
            Triple(label, app.child("pkg").getValue(String::class.java) ?: app.key, minutes)
        }.sortedByDescending { it.third }
    }

    private fun renderUsage(snapshot: DataSnapshot) {
        usageList.removeAllViews()
        val parentMinutes = (tvStatus?.child("parentMinutesToday")?.value as? Number)?.toInt() ?: 0
        val rows = todaysApps(snapshot) +
            if (parentMinutes > 0) listOf(Triple(PARENT_ROW, null as String?, parentMinutes)) else emptyList()
        if (rows.isEmpty()) {
            usageList.addView(TextView(this).apply {
                text = "Nothing watched yet today"
                setTextColor(color(R.color.muted))
                setPadding(0, dp(10), 0, dp(10))
            })
            return
        }
        val max = rows.maxOf { it.third }.coerceAtLeast(1)
        val blocked = packages().toSet()
        rows.forEachIndexed { i, (label, pkg, minutes) ->
            if (i > 0) usageList.addView(View(this).apply {
                setBackgroundColor(color(R.color.line))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            })
            val row = LinearLayout(this).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(0, dp(11), 0, dp(11))
            }
            val name = LinearLayout(this).apply {
                gravity = android.view.Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            name.addView(TextView(this).apply {
                text = label
                textSize = 15f
                setTextColor(if (label == PARENT_ROW) color(R.color.parent) else color(R.color.text))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            })
            if (pkg != null && pkg in blocked && tvStatus?.child("appsAllowed")?.value != true) {
                name.addView(pill("Blocked", color(R.color.bad), small = true).apply {
                    (layoutParams as? LinearLayout.LayoutParams)?.marginStart = dp(8)
                })
            }
            row.addView(name)
            row.addView(BarView(this, minutes.toFloat() / max).apply {
                layoutParams = LinearLayout.LayoutParams(dp(90), dp(6)).apply { marginStart = dp(8) }
            })
            row.addView(TextView(this).apply {
                text = formatMinutes(minutes)
                textSize = 14f
                setTextColor(color(R.color.text))
                gravity = android.view.Gravity.END
                layoutParams = LinearLayout.LayoutParams(dp(72), LinearLayout.LayoutParams.WRAP_CONTENT)
            })
            usageList.addView(row)
        }
    }

    // ---- Home screen ----

    private var connectionPill: Pair<String, Int> = "Connecting" to 0

    private fun renderHome() {
        val s = tvStatus
        val used = (s?.child("usedMinutesToday")?.value as? Number)?.toInt() ?: 0
        val limit = (s?.child("limitMinutesToday")?.value as? Number)?.toInt() ?: 0
        val reason = s?.child("reason")?.getValue(String::class.java).orEmpty()
        val locked = s?.child("locked")?.getValue(Boolean::class.java) == true
        val left = (limit - used).coerceAtLeast(0)
        val fraction = if (limit > 0) used.toFloat() / limit else 0f
        val tone = when {
            locked || (limit > 0 && left == 0) -> color(R.color.bad)
            limit > 0 && (left <= 15 || fraction >= 0.7f) -> color(R.color.warn)
            else -> color(R.color.ok)
        }
        val parent = parentActiveUntil() > 0
        ring.set(if (limit > 0) fraction else 0f, if (parent) color(R.color.parent) else tone)
        ringValue.text = formatShort(used)
        ringLabel.text = if (limit > 0) "of ${formatShort(limit)} today" else "today, no limit"
        leftLabel.text = when {
            parent -> "Kids' time left (paused)"
            limit > 0 -> "Left today"
            else -> "Daily limit"
        }
        leftValue.text = if (limit > 0) formatMinutes(left) else "Off"
        leftValue.setTextColor(if (limit > 0) tone else color(R.color.muted))
        val top = todaysApps().firstOrNull()
        topApp.text = top?.let { "${it.first} · ${formatMinutes(it.third)}" } ?: "Nothing yet"
        renderNowPlaying(locked)

        // Status pills
        pills.removeAllViews()
        val (connText, connColor) = connectionPill
        if (connColor != 0) pills.addView(pill("● " + shortConnection(connText), connColor))
        if (s != null && s.exists()) {
            pills.addView(
                when (reason) {
                    "hours", "bedtime", "schedule" -> pill("🌙 Outside TV hours", color(R.color.bad))
                    "limit" -> pill("🔒 TV time used up", color(R.color.bad))
                    "manual" -> pill("🔒 " + lockedByShort(), color(R.color.bad))
                    else -> pill("🔓 Unlocked", color(R.color.ok))
                }
            )
            val overrideUntil = (s.child("overrideUntil").value as? Number)?.toLong() ?: 0L
            if (overrideUntil > System.currentTimeMillis()) {
                pills.addView(pill("Unlocked until ${formatWhen(overrideUntil)}", color(R.color.warn)))
            }
        }
        if (parentActiveUntil() > 0) {
            pills.addView(pill("👤 Parent mode", color(R.color.parent)))
        } else if (bool("scheduleEnabled") && reason.isEmpty()) {
            val (_, end) = hoursToday()
            pills.addView(pill("TV hours end ${formatTime(end)}", color(R.color.warn)))
        }

        // Quick action tiles
        val lockedByMe = bool("lockNow")
        lockNowIcon.text = if (lockedByMe) "🔓" else "🔒"
        lockNowLabel.text = if (lockedByMe) "Unlock TV" else "Lock now"
        val unblocked = config["unblockedDate"] == LocalDate.now().toString()
        unblockIcon.text = if (unblocked) "■" else "▶"
        unblockLabel.text = if (unblocked) "Block apps" else "Unblock apps"
        tint(unblockIcon, if (unblocked) R.color.warn else R.color.ok)
    }

    /** The "Now playing" card: the app on screen and, if the app shares it, the title. */
    private fun renderNowPlaying(locked: Boolean) {
        val n = nowPlaying
        if (n == null || !n.exists()) {
            nowTitle.text = "Waiting for the TV"
            nowDetail.text = "Shows here once the TV has the newest TV Guard."
            return
        }
        fun text(key: String) = n.child(key).getValue(String::class.java).orEmpty()
        val app = text("app")
        val title = text("title")
        val subtitle = text("subtitle")
        val playing = n.child("playing").getValue(Boolean::class.java) == true
        val titleAccess = n.child("titleAccess").getValue(Boolean::class.java) == true
        val stale = System.currentTimeMillis() - lastSeen() > 3 * 60_000L
        val since = if (stale && lastSeen() > 0) " (as of ${formatWhen(lastSeen())})" else ""
        when {
            locked -> {
                nowTitle.text = "Nothing, the TV is locked"
                nowDetail.text = ""
            }
            app.isEmpty() && title.isEmpty() -> {
                nowTitle.text = "Nothing on screen$since"
                nowDetail.text = "Home screen, standby or TV off"
            }
            title.isNotEmpty() -> {
                nowTitle.text = title
                nowDetail.text = listOf(subtitle, app, if (playing) "Playing" else "Paused")
                    .filter { it.isNotEmpty() }.joinToString(" · ") + since
            }
            else -> {
                nowTitle.text = app + since
                nowDetail.text = if (titleAccess) {
                    "This app doesn't share what's playing"
                } else {
                    "Titles are off on the TV. Turn them on once over adb (see README)."
                }
            }
        }
    }

    private fun shortConnection(text: String): String = when {
        text.startsWith("Connected") -> "Online"
        text.startsWith("This phone") -> "Phone offline"
        text.startsWith("TV offline") -> "TV offline"
        else -> text
    }

    private fun selectTab(index: Int) {
        currentTab = index
        tabs.forEachIndexed { i, (nav, page, _) ->
            val on = i == index
            page.visibility = if (on) View.VISIBLE else View.GONE
            nav.setTextColor(color(if (on) R.color.accent else R.color.muted))
            nav.setTypeface(null, if (on) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
        }
        val title = tabs[index].third
        if (title.isEmpty()) {
            greeting.text = greetingText()
            pageTitle.text = "Your TV"
        } else {
            greeting.text = "TV Guard"
            pageTitle.text = title
        }
        findViewById<android.widget.ScrollView>(R.id.content).scrollTo(0, 0)
    }

    private fun greetingText(): String = when (LocalTime.now().hour) {
        in 5..11 -> "Good morning"
        in 12..16 -> "Good afternoon"
        else -> "Good evening"
    }

    private fun styleTileIcons() {
        tint(findViewById(R.id.bonus15Icon), R.color.accent)
        tint(findViewById(R.id.bonus30Icon), R.color.accent)
        tint(lockNowIcon, R.color.bad)
        tint(unblockIcon, R.color.ok)
    }

    /** Coloured text on a soft rounded square of the same colour. */
    private fun tint(view: TextView, colorRes: Int) {
        val c = color(colorRes)
        view.setTextColor(c)
        view.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(14).toFloat()
            setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(c, 0x26))
        }
    }

    private fun pill(text: String, c: Int, small: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = if (small) 11f else 13f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setTextColor(c)
        val h = if (small) dp(7) else dp(11)
        val v = if (small) dp(2) else dp(5)
        setPadding(h, v, h, v)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(androidx.core.graphics.ColorUtils.setAlphaComponent(c, 0x22))
        }
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    }

    private fun color(res: Int) = ContextCompat.getColor(this, res)

    /** A thin rounded bar filled to [fraction]. */
    private class BarView(context: android.content.Context, private val fraction: Float) : View(context) {
        private val track = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.chip)
        }
        private val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            color = ContextCompat.getColor(context, R.color.accent)
        }
        override fun onDraw(canvas: android.graphics.Canvas) {
            val r = height / 2f
            canvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), r, r, track)
            val w = (width * fraction.coerceIn(0f, 1f)).coerceAtLeast(height.toFloat())
            canvas.drawRoundRect(0f, 0f, w, height.toFloat(), r, r, fill)
        }
    }

    private fun renderPending(snapshot: DataSnapshot) {
        pendingSection.removeAllViews()
        val code = Cloud.code(this) ?: return
        if (snapshot.hasChildren()) {
            pendingSection.addView(TextView(this).apply {
                text = "New phone wants to pair"
                textSize = 17f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(color(R.color.text))
            })
        }
        for (phone in snapshot.children) {
            val uid = phone.key ?: continue
            val name = phone.child("name").getValue(String::class.java) ?: "A phone"
            pendingSection.addView(TextView(this).apply {
                text = "$name wants to pair with this TV"
                textSize = 15f
                setTextColor(color(R.color.muted))
                setPadding(0, dp(8), 0, dp(4))
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

    /** The name shown on the TV ("Locked by …") and in the phones list, e.g. "Fahad's phone". */
    private fun renamePhone(code: String, uid: String, current: String) {
        val input = EditText(this).apply {
            setText(current)
            setSelectAllOnFocus(true)
            filters = arrayOf(android.text.InputFilter.LengthFilter(40))
        }
        val pad = (20 * resources.displayMetrics.density).toInt()
        val box = android.widget.FrameLayout(this).apply { setPadding(pad, pad / 2, pad, 0); addView(input) }
        AlertDialog.Builder(this)
            .setTitle("Name this phone")
            .setMessage("The TV shows this name when this phone locks it, for example \"Fahad's phone\".")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                Cloud.device(code).child("parents").child(uid).child("name").setValue(name)
                    .addOnFailureListener { toast("Couldn't save: ${it.message}") }
            }
            .setNegativeButton("Cancel", null)
            .show()
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
            if (uid == me) {
                row.addView(Button(this, null, android.R.attr.borderlessButtonStyle).apply {
                    text = "Rename"
                    setOnClickListener { renamePhone(code, uid, name) }
                })
            } else {
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

    /** Problems a parent needs to fix, shown in red on the home screen. Empty when all is well. */
    private fun warningLines(): List<String> {
        val s = tvStatus
        if (s == null || !s.exists()) return emptyList()
        if (s.child("released").getValue(Boolean::class.java) == true) {
            return listOf("Protection removed. The TV app can now be uninstalled.")
        }
        val lines = mutableListOf<String>()
        if (s.child("deviceOwner").getValue(Boolean::class.java) != true) {
            lines += "⚠ TV app isn't Device Owner, so it can't lock anything. Run the setup again."
        }
        if (s.child("usageAccess").getValue(Boolean::class.java) != true) {
            lines += "⚠ TV app can't measure TV time. Run the setup again."
        }
        return lines
    }

    private fun formatTime(minutes: Int): String =
        LocalTime.of(0, 0).plusMinutes(minutes.toLong())
            .format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT))

    private fun formatWhen(millis: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(millis))

    /** "at 3:40 PM" today, "yesterday at 3:40 PM", otherwise "on Fri 20 Nov at 3:40 PM". */
    private fun formatDayTime(millis: Long): String {
        val day = java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
        val time = formatWhen(millis)
        return when (java.time.temporal.ChronoUnit.DAYS.between(day, LocalDate.now())) {
            0L -> "at $time"
            1L -> "yesterday at $time"
            else -> "on ${day.format(DateTimeFormatter.ofPattern("EEE d MMM"))} at $time"
        }
    }

    /** "45 min", "2 h" or "5 h 20 min". */
    private fun formatMinutes(minutes: Int): String = when {
        minutes < 60 -> "$minutes min"
        minutes % 60 == 0 -> "${minutes / 60} h"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }

    /** "1h 26m", for the ring. */
    private fun formatShort(minutes: Int): String = when {
        minutes < 60 -> "${minutes}m"
        minutes % 60 == 0 -> "${minutes / 60}h"
        else -> "${minutes / 60}h ${minutes % 60}m"
    }

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

    private fun showDiagnostics() {
        val code = Cloud.code(this) ?: return
        Diagnostics(
            activity = this,
            code = code,
            phoneOnline = { phoneOnline },
            status = { tvStatus },
            diag = { diag },
            logs = { logs },
            config = { config },
            requestLogs = {
                val at = System.currentTimeMillis()
                write(mapOf("logsRequestAt" to at))
                at
            },
        ).show()
    }

    private fun pickPhoto(slot: PhotoSlot) {
        pickingFor = slot
        pickPhoto.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    /** Shrinks the picked photo and sends it to the TV, which saves it and deletes it from Firebase. */
    private fun sendPhoto(slot: PhotoSlot, uri: Uri) {
        val ref = deviceRef ?: return
        slot.label.text = "Preparing the photo…"
        Thread {
            val data = LockPhoto.prepare(this, uri)
            runOnUiThread {
                if (data == null) {
                    toast("Couldn't read that photo. Try another one.")
                    render()
                    return@runOnUiThread
                }
                val at = System.currentTimeMillis()
                slot.sentAt = at
                slot.sentEmpty = false
                render()
                ref.child(slot.node).setValue(mapOf("at" to at, "data" to data))
                    .addOnFailureListener { e ->
                        slot.sentAt = 0
                        render()
                        toast("Couldn't send the photo: ${e.message}")
                    }
            }
        }.start()
    }

    private fun confirmRemovePhoto(slot: PhotoSlot) {
        AlertDialog.Builder(this)
            .setTitle("Remove this photo?")
            .setMessage("The TV deletes it and shows the plain lock screen again.")
            .setPositiveButton("Remove") { _, _ ->
                val ref = deviceRef ?: return@setPositiveButton
                slot.sentAt = System.currentTimeMillis()
                slot.sentEmpty = true
                render()
                ref.child(slot.node).setValue(mapOf("at" to slot.sentAt, "data" to ""))
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun answerRequest(minutes: Int) {
        val code = Cloud.code(this) ?: return
        val askedAt = (request?.child("at")?.value as? Number)?.toLong() ?: return
        val reason = request?.child("reason")?.getValue(String::class.java) ?: "limit"
        Cloud.answerRequest(code, askedAt, reason, minutes) { ok ->
            toast(if (!ok) "Couldn't save the answer" else if (minutes > 0) "Gave $minutes more minutes" else "Told the TV no")
        }
    }

    /** This phone's name as the parents list has it, e.g. "Fahad's phone". */
    private fun myPhoneName(): String {
        val me = Cloud.uid()
        return me?.let { parents?.child(it)?.child("name")?.getValue(String::class.java) }
            ?: "${Build.MANUFACTURER} ${Build.MODEL}"
    }

    /** "Locked by this phone at 3:40 PM" / "Locked by Mum's phone at 3:40 PM" for the status pill. */
    private fun lockedByShort(): String {
        val by = config["lockNowBy"] as? String
        val at = long("lockNowAt")
        if (by.isNullOrEmpty() || at <= 0) return "Locked from a phone"
        val who = if (config["lockNowByUid"] == Cloud.uid()) "this phone" else by
        return "Locked by $who ${formatDayTime(at)}"
    }

    private fun toggleLockNow() {
        if (bool("lockNow")) {
            write(
                mapOf(
                    "lockNow" to false,
                    "lockNowOffBy" to myPhoneName(),
                    "lockNowOffAt" to System.currentTimeMillis(),
                )
            )
            toast("Unlocking the TV")
        } else {
            AlertDialog.Builder(this)
                .setMessage("Lock the TV now? It stays locked until you unlock it here.")
                .setPositiveButton("Lock") { _, _ ->
                    // Also cancels any extra time given earlier, so the lock takes effect at once.
                    write(
                        mapOf(
                            "lockNow" to true,
                            "overrideUntil" to 0,
                            // Shown on the TV's lock screen and in Diagnostics.
                            "lockNowBy" to myPhoneName(),
                            "lockNowByUid" to (Cloud.uid() ?: ""),
                            "lockNowAt" to System.currentTimeMillis(),
                        )
                    )
                    toast("Locking the TV")
                }
                .setNegativeButton("Cancel", null)
                .show()
        }
    }

    private fun toggleUnblock() {
        val unblocked = config["unblockedDate"] == LocalDate.now().toString()
        write(mapOf("unblockedDate" to if (unblocked) "" else LocalDate.now().toString()))
        toast(if (unblocked) "Blocked apps are blocked again" else "Blocked apps unblocked until midnight")
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
        usage = null
        apps = emptyList()
        codeInput.setText("")
        show(pairSection)
    }

    // ---- TV time ----

    /** Today's TV hours (start, end) as minutes of the day. */
    private fun hoursToday(): Pair<Int, Int> {
        val weekend = LocalDate.now().dayOfWeek.value >= 6
        return if (weekend) int("weekendStart", 9 * 60) to int("weekendEnd", 20 * 60)
        else int("weekdayStart", 15 * 60) to int("weekdayEnd", 19 * 60)
    }

    /** "Today: TV can be on 3:00 PM to 8:30 PM, for up to 2 h. It locks at 8:30 PM, or sooner…" */
    private fun tvTimeSummaryText(): String {
        val weekend = LocalDate.now().dayOfWeek.value >= 6
        val limit = if (weekend) int("weekendLimitMinutes", 180) else int("dailyLimitMinutes", 120)
        val hoursOn = bool("scheduleEnabled")
        val (start, end) = hoursToday()
        val amount = if (limit > 0) formatMinutes(limit) else ""
        return "📺 Today: " + when {
            hoursOn && limit > 0 ->
                "TV can be on ${formatTime(start)} to ${formatTime(end)}, for up to $amount.\n" +
                    "It locks at ${formatTime(end)}, or sooner if the $amount run out."
            hoursOn -> "TV can be on ${formatTime(start)} to ${formatTime(end)}, with no daily limit."
            limit > 0 -> "TV can be on any time, for up to $amount."
            else -> "No TV hours and no daily limit. The TV never locks by itself."
        }
    }

    /**
     * TV hours used to come with a separate bedtime. Now the end of TV hours is bedtime, so an
     * old bedtime is moved into TV hours once: they become the part of the old allowed hours
     * that is outside bedtime.
     */
    private fun migrateBedtime() {
        if (migratedBedtime || !bool("bedtimeEnabled")) return
        migratedBedtime = true
        val bedStart = int("bedtimeStart", 21 * 60)
        val bedEnd = int("bedtimeEnd", 7 * 60)
        val scheduleOn = bool("scheduleEnabled")
        fun merge(start: Int, end: Int): Pair<Int, Int> {
            if (!scheduleOn) return bedEnd to bedStart
            // Usual case: bedtime crosses midnight and both windows sit inside one day.
            if (bedEnd < bedStart && start < end) {
                val s = maxOf(start, bedEnd)
                val e = minOf(end, bedStart)
                if (s < e) return s to e
            }
            return start to end
        }
        val (wdStart, wdEnd) = merge(int("weekdayStart", 15 * 60), int("weekdayEnd", 19 * 60))
        val (weStart, weEnd) = merge(int("weekendStart", 9 * 60), int("weekendEnd", 20 * 60))
        write(
            mapOf(
                "scheduleEnabled" to true,
                "weekdayStart" to wdStart, "weekdayEnd" to wdEnd,
                "weekendStart" to weStart, "weekendEnd" to weEnd,
                "bedtimeEnabled" to false,
            )
        )
        AlertDialog.Builder(this)
            .setTitle("Bedtime is now part of TV hours")
            .setMessage(
                "TV hours are now:\nSchool days ${formatTime(wdStart)} to ${formatTime(wdEnd)}\n" +
                    "Weekend ${formatTime(weStart)} to ${formatTime(weEnd)}\n\n" +
                    "The end of TV hours is bedtime. You can change them on the TV time tab."
            )
            .setPositiveButton("OK", null)
            .show()
    }

    // ---- Parent mode ----

    /** When parent mode ends, if it's on now; otherwise 0. */
    private fun parentActiveUntil(): Long {
        val from = long("parentFrom")
        val until = minOf(long("parentUntil"), parentCutoff(from))
        return if (from > 0 && System.currentTimeMillis() in from until until) until else 0L
    }

    /** The first 6:00 AM at least an hour after [from]. Same rule as on the TV. */
    private fun parentCutoff(from: Long): Long {
        val start = java.time.Instant.ofEpochMilli(from).atZone(java.time.ZoneId.systemDefault()).plusHours(1)
        var cutoff = start.with(LocalTime.of(PARENT_CUTOFF_HOUR, 0))
        if (!cutoff.isAfter(start)) cutoff = cutoff.plusDays(1)
        return cutoff.toInstant().toEpochMilli()
    }

    private fun renderParentMode() {
        val until = parentActiveUntil()
        val on = until > 0
        parentCard.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(20).toFloat()
            setColor(if (on) color(R.color.parent) else color(R.color.card))
        }
        parentTitle.setTextColor(if (on) 0xFFFFFFFF.toInt() else color(R.color.text))
        parentDetail.setTextColor(if (on) 0xE6FFFFFF.toInt() else color(R.color.muted))
        parentTitle.text = if (on) "👤 Parent mode on · ends ${formatWhen(until)}" else "👤 Parent mode"
        parentDetail.text = if (on) {
            "No limits or blocked apps on the TV. This time isn't counted."
        } else {
            "No limits, no blocked apps. Your watching won't count. It always ends by 6:00 AM."
        }
        parentOnButton.visibility = if (on) View.GONE else View.VISIBLE
        parentMore.visibility = if (on) View.VISIBLE else View.GONE
        parentEnd.visibility = if (on) View.VISIBLE else View.GONE
    }

    private fun chooseParentMode() {
        val now = System.currentTimeMillis()
        val cutoff = parentCutoff(now)
        val options = listOf(1, 2, 3).map { h ->
            val until = minOf(now + h * HOUR_MS, cutoff)
            "$h hour${if (h > 1) "s" else ""}, until ${formatWhen(until)}" to h * HOUR_MS
        } + listOf("Until I turn it off (ends by ${formatWhen(cutoff)})" to Long.MAX_VALUE / 2)
        val labels = options.map { it.first }
        var picked = 1
        AlertDialog.Builder(this)
            .setTitle("Turn on parent mode")
            .setSingleChoiceItems(labels.toTypedArray(), picked) { _, which -> picked = which }
            .setPositiveButton("Turn on") { _, _ ->
                val start = System.currentTimeMillis()
                val until = minOf(start + options[picked].second, parentCutoff(start))
                write(mapOf("parentFrom" to start, "parentUntil" to until))
                toast("Parent mode on until ${formatWhen(until)}. The TV unlocks in a moment.")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun extendParentMode() {
        val until = parentActiveUntil()
        if (until == 0L) return
        val cutoff = parentCutoff(long("parentFrom"))
        val newUntil = minOf(until + HOUR_MS, cutoff)
        if (newUntil <= until) {
            toast("Parent mode can't go past ${formatWhen(cutoff)}.")
            return
        }
        write(mapOf("parentUntil" to newUntil))
        toast("Parent mode on until ${formatWhen(newUntil)}")
    }

    private fun endParentMode() {
        AlertDialog.Builder(this)
            .setMessage("End parent mode now? The kids' rules come back. If their TV time is used up, the TV locks.")
            .setPositiveButton("End now") { _, _ ->
                write(mapOf("parentUntil" to System.currentTimeMillis()))
                toast("Parent mode ended")
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    // ---- Protection (Diagnostics tab) ----

    private fun renderProtection() {
        protectionLabel.text = Diagnostics.protectionText(diag) {
            DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(it))
        }
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
        private const val PARENT_ROW = "👤 Parent time"
        private const val HOUR_MS = 60 * 60_000L
        /** Parent mode never lasts past 6:00 AM; must match Rules.parentCutoff on the TV. */
        private const val PARENT_CUTOFF_HOUR = 6

        private val DEFAULT_PACKAGES = listOf(
            "com.google.android.youtube.tv",
            "com.google.android.youtube",
            "com.google.android.youtube.tvkids",
            "com.roblox.client",
        )
    }
}
