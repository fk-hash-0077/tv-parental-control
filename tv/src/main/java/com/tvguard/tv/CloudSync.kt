package com.tvguard.tv

import android.content.Context
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener

/**
 * Syncs with Firebase Realtime Database at devices/{deviceId}:
 *   config   written by the phone, read here
 *   status   written here, read by the phone
 *   apps     written here: apps the phone can choose to block
 *   history  written here: {yyyy-MM-dd: minutes} for the blocked apps
 */
class CloudSync(private val context: Context, private val onConfigChanged: () -> Unit) {
    private val store = Store(context)
    @Volatile private var ref: DatabaseReference? = null

    val isConfigured: Boolean
        get() = FirebaseApp.getApps(context).isNotEmpty()

    val isConnected: Boolean
        get() = ref != null

    fun start() {
        if (!isConfigured) {
            Log.i(TAG, "Firebase not configured; running on local settings only")
            return
        }
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            attach()
        } else {
            auth.signInAnonymously()
                .addOnSuccessListener { attach() }
                .addOnFailureListener { Log.w(TAG, "Anonymous sign-in failed", it) }
        }
    }

    private fun attach() {
        val deviceRef = FirebaseDatabase.getInstance().getReference("devices").child(store.deviceId)
        ref = deviceRef
        deviceRef.child("config").addValueEventListener(object : ValueEventListener {
            override fun onDataChange(snapshot: DataSnapshot) {
                val map = snapshot.value as? Map<*, *>
                if (map == null) {
                    // First run: publish the local defaults so the phone has something to show.
                    deviceRef.child("config").setValue(store.config.toMap())
                    return
                }
                store.config = GuardConfig.fromMap(map)
                onConfigChanged()
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Config listener cancelled: ${error.message}")
            }
        })
        onConfigChanged()
    }

    fun pushStatus(status: GuardStatus) {
        val deviceRef = ref ?: return
        deviceRef.child("status").setValue(status.toMap())
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

    companion object {
        private const val TAG = "TvGuardSync"
    }
}
