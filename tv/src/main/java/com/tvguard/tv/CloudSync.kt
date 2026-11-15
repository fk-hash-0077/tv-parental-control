package com.tvguard.tv

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.Query
import com.google.firebase.database.ServerValue
import com.google.firebase.database.ValueEventListener

/**
 * Syncs with Firebase Realtime Database at devices/{deviceId}:
 *   tvUid          this TV's sign-in, so only it can write what the TV reports
 *   secret         shared with approved phones for one-time unlock codes
 *   parents/{uid}  approved phones; pending/{uid} phones waiting for approval
 *   config         written by phones, read here
 *   status, apps, history, usage, appHistory   written here
 *   request        "ask for more time" from the lock screen; requestAnswer is the phone's reply
 *   events         unlocks and failed attempts, turned into notifications by the cloud functions
 *
 * Standby, Wi-Fi drops and expired sign-ins used to leave the TV disconnected until a restart.
 * The connection can also look fine while nothing gets through (a dead socket after standby),
 * so the TV also watches whether its status writes are confirmed by the server, and starts a
 * fresh connection when none has been confirmed for a few minutes.
 * Now a cancelled listener re-attaches, and [watchdog] (every minute) and [reconnect] (on wake and
 * when Wi-Fi returns) force a fresh connection whenever the TV has been offline too long.
 */
class CloudSync(private val context: Context, private val onConfigChanged: () -> Unit) {
    private val store = Store(context)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var ref: DatabaseReference? = null
    private val listeners = mutableListOf<Pair<Query, ValueEventListener>>()
    private var connectedListener: ValueEventListener? = null
    private var signingIn = false
    @Volatile var lastReconnectAt = 0L
        private set
    /** Reconnects since the app started, and why the last one happened, for the phone's diagnostics. */
    @Volatile var reconnects = 0
        private set
    @Volatile var lastReconnectReason = ""
        private set

    @Volatile var connected = false
        private set
    @Volatile private var disconnectedSince = System.currentTimeMillis()
    /** When the server last confirmed a status write. Shown on the TV screen too. */
    @Volatile private var lastAckAt = System.currentTimeMillis()

    private var parents: Set<String> = emptySet()
    private var parentsLoaded = false
    private var pending: Map<String, Map<*, *>> = emptyMap()

    val isConfigured: Boolean
        get() = FirebaseApp.getApps(context).isNotEmpty()

    fun start() = main.post { startOnMain() }

    private fun startOnMain() {
        if (!isConfigured) {
            AppLog.i(TAG, "Firebase not configured; running on local settings only")
            return
        }
        watchConnection()
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            attach()
        } else if (!signingIn) {
            signingIn = true
            auth.signInAnonymously()
                .addOnSuccessListener { signingIn = false; attach() }
                .addOnFailureListener { signingIn = false; AppLog.w(TAG, "Anonymous sign-in failed", it) }
        }
    }

    private fun watchConnection() {
        if (connectedListener != null) return
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val now = snapshot.getValue(Boolean::class.java) == true
                if (now != connected) AppLog.i(TAG, if (now) "Connected" else "Disconnected")
                if (!now && connected) disconnectedSince = System.currentTimeMillis()
                connected = now
                if (now) onConfigChanged()
            }
            override fun onCancelled(error: DatabaseError) = Unit
        }
        FirebaseDatabase.getInstance().getReference(".info/connected").addValueEventListener(listener)
        connectedListener = listener
    }

    private fun attach() {
        detach()
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return
        val deviceRef = FirebaseDatabase.getInstance().getReference("devices").child(store.deviceId)
        // Claim the code first: the database rules only accept the TV's writes once tvUid is set.
        deviceRef.child("tvUid").setValue(uid).addOnCompleteListener { task ->
            if (!task.isSuccessful) AppLog.w(TAG, "Couldn't claim tvUid", task.exception)
            ref = deviceRef
            deviceRef.child("secret").setValue(Base64.encodeToString(store.unlockSecret, Base64.NO_WRAP))
            listen(deviceRef.child("config")) { snapshot ->
                val map = snapshot.value as? Map<*, *>
                if (map == null) {
                    // First run: publish the local defaults so the phone has something to show.
                    deviceRef.child("config").setValue(store.config.toMap())
                } else {
                    store.config = GuardConfig.fromMap(map)
                    onConfigChanged()
                }
            }
            listen(deviceRef.child("parents")) { snapshot ->
                parents = snapshot.children.mapNotNull { it.key }.toSet()
                parentsLoaded = true
                approveFirstPhone()
            }
            listen(deviceRef.child("pending")) { snapshot ->
                pending = snapshot.children.associate { (it.key ?: "") to (it.value as? Map<*, *> ?: emptyMap<String, Any>()) }
                approveFirstPhone()
            }
            listen(deviceRef.child("requestAnswer")) { snapshot ->
                val requestAt = (snapshot.child("requestAt").value as? Number)?.toLong() ?: return@listen
                if (requestAt != store.lastRequestAt) return@listen
                val minutes = (snapshot.child("minutes").value as? Number)?.toInt() ?: 0
                store.requestAnswer = if (minutes > 0) "approved:$minutes" else "denied"
                onConfigChanged()
            }
            onConfigChanged()
        }
    }

    /**
     * The first phone to pair is approved automatically. After that, a new phone stays pending
     * until an already-approved phone approves it.
     */
    private fun approveFirstPhone() {
        val deviceRef = ref ?: return
        if (!parentsLoaded || parents.isNotEmpty() || pending.isEmpty()) return
        val (uid, info) = pending.entries.minByOrNull { (it.value["at"] as? Number)?.toLong() ?: Long.MAX_VALUE } ?: return
        deviceRef.child("parents").child(uid).setValue(
            mapOf("name" to (info["name"] ?: "Phone"), "addedAt" to ServerValue.TIMESTAMP)
        )
        deviceRef.child("pending").child(uid).removeValue()
    }

    private fun listen(query: Query, onData: (DataSnapshot) -> Unit) {
        val listener = object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) = onData(snapshot)
            override fun onCancelled(error: DatabaseError) {
                // Usually an expired sign-in or a rules change. Without this the TV stopped
                // hearing from the phone until it was restarted.
                AppLog.w(TAG, "Listener cancelled: ${error.message}; re-attaching")
                main.postDelayed({ reconnect(force = true, reason = "listener cancelled") }, RETRY_MS)
            }
        }
        query.addValueEventListener(listener)
        listeners += query to listener
    }

    private fun detach() {
        listeners.forEach { (query, listener) -> query.removeEventListener(listener) }
        listeners.clear()
        ref = null
        parentsLoaded = false
    }

    /** Forces a fresh connection and re-attaches the listeners. Safe to call often. */
    fun reconnect(force: Boolean = false, reason: String = "") = main.post {
        if (!isConfigured) return@post
        if (!force && connected && ref != null) return@post
        val now = System.currentTimeMillis()
        if (now - lastReconnectAt < MIN_RECONNECT_GAP_MS) return@post
        lastReconnectAt = now
        reconnects++
        lastReconnectReason = reason
        AppLog.i(TAG, "Reconnecting ($reason)")
        val db = FirebaseDatabase.getInstance()
        db.goOffline()
        db.goOnline()
        val user = FirebaseAuth.getInstance().currentUser
        if (user == null) {
            startOnMain()
        } else {
            user.getIdToken(true)
                .addOnCompleteListener { attach() }
        }
    }

    /**
     * Called every minute. Reconnects if the TV has been disconnected for a while, or if no
     * status write has been confirmed for a few minutes even though it looks connected.
     */
    fun watchdog() = main.post {
        if (!isConfigured) return@post
        val now = System.currentTimeMillis()
        if (ref == null && !signingIn && now - lastReconnectAt > NO_ACK_MS) {
            lastReconnectAt = now
            startOnMain()
            return@post
        }
        val offlineTooLong = !connected && now - disconnectedSince > STALE_MS
        val nothingConfirmed = now - lastAckAt > NO_ACK_MS && now - lastReconnectAt > NO_ACK_MS
        if (offlineTooLong || nothingConfirmed) {
            AppLog.w(TAG, "Sync looks stuck (connected=$connected); starting a fresh connection")
            disconnectedSince = now
            reconnect(force = true, reason = if (offlineTooLong) "offline too long" else "nothing confirmed for 3 min")
        }
    }

    fun pushStatus(status: GuardStatus) {
        val deviceRef = ref ?: return
        deviceRef.child("status").setValue(status.toMap())
            .addOnSuccessListener {
                lastAckAt = System.currentTimeMillis()
                store.syncSavedAt = lastAckAt
                store.syncError = ""
            }
            .addOnFailureListener { e ->
                store.syncError = e.message ?: e.javaClass.simpleName
                AppLog.w(TAG, "Status write failed", e)
                // Usually an expired sign-in: refresh it and attach again.
                reconnect(force = true, reason = "status write failed")
            }
        if (status.released) {
            // Don't release again if the app is set up as Device Owner later.
            deviceRef.child("config").child("releaseOwner").setValue(false)
        }
    }

    fun pushApps(apps: List<AppCatalog.App>) {
        val deviceRef = ref ?: return
        deviceRef.child("apps").setValue(apps.map { mapOf("pkg" to it.pkg, "label" to it.label) })
    }

    fun pushHistory(minutesByDay: Map<String, Int>) {
        val deviceRef = ref ?: return
        deviceRef.child("history").updateChildren(minutesByDay.mapValues { it.value as Any })
    }

    /** Today's per-app minutes, for display on the phone. */
    fun pushUsage(date: String, minutesByApp: Map<String, Int>, labels: Map<String, String>) {
        val deviceRef = ref ?: return
        val apps = minutesByApp.entries.sortedByDescending { it.value }.map { (pkg, minutes) ->
            mapOf("pkg" to pkg, "label" to (labels[pkg] ?: pkg), "minutes" to minutes)
        }
        deviceRef.child("usage").setValue(mapOf("date" to date, "apps" to apps))
        // Kept per day for the weekly report's top apps; the cloud function trims old days.
        deviceRef.child("appHistory").child(date).setValue(apps)
    }

    /** "Ask for more time" from the lock screen. The cloud function sends it to the phones. */
    fun sendRequest(at: Long, reason: String): Boolean {
        val deviceRef = ref ?: return false
        deviceRef.child("request").setValue(mapOf("at" to at, "reason" to reason))
        return true
    }

    /** unlock, emergency or failed. Queued by Firebase if the TV is offline right now. */
    fun sendEvent(type: String) {
        val deviceRef = ref ?: return
        deviceRef.child("events").push().setValue(
            mapOf("type" to type, "at" to ServerValue.TIMESTAMP, "device" to Build.MODEL)
        )
    }

    /** Connection details for the phone's Diagnostics screen. Sent with every check. */
    fun pushDiag(serviceStartedAt: Long) {
        val deviceRef = ref ?: return
        deviceRef.child("diag").setValue(
            mapOf(
                "connected" to connected,
                "confirmedAt" to store.syncSavedAt,
                "syncError" to store.syncError,
                "reconnects" to reconnects,
                "lastReconnectAt" to lastReconnectAt,
                "lastReconnectReason" to lastReconnectReason,
                "serviceStartedAt" to serviceStartedAt,
                "lockReason" to store.lockReason,
                "version" to BuildConfig.VERSION_CODE,
                "android" to Build.VERSION.RELEASE,
                "model" to Build.MODEL,
                "at" to System.currentTimeMillis(),
            )
        )
    }

    /** Uploads the TV's recent log for the phone's "Send logs"; replaces any earlier upload. */
    fun uploadLogs(requestAt: Long, text: String) {
        val deviceRef = ref ?: return
        deviceRef.child("logs").setValue(
            mapOf(
                "requestAt" to requestAt,
                "at" to System.currentTimeMillis(),
                "version" to BuildConfig.VERSION_CODE,
                "text" to text,
            )
        )
    }

    /** Clears a used emergency code on the phone side too. */
    fun clearEmergencyCode() {
        ref?.child("config")?.updateChildren(mapOf("emergencyCode" to "", "emergencySetAt" to 0))
    }

    /**
     * Forgets every approved phone, so the next phone to pair is approved automatically.
     * Only reachable over adb (see ResetPhonesReceiver), for when every paired phone is lost.
     */
    fun resetPhones(): Boolean {
        val deviceRef = ref ?: return false
        deviceRef.child("parents").removeValue()
        deviceRef.child("tokens").removeValue()
        return true
    }

    companion object {
        private const val TAG = "TvGuardSync"
        private const val RETRY_MS = 5_000L
        private const val STALE_MS = 90_000L
        private const val NO_ACK_MS = 3 * 60_000L
        private const val MIN_RECONNECT_GAP_MS = 10_000L
    }
}
