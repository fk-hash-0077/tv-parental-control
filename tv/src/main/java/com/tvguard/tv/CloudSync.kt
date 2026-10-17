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
 *   config  written by the phone, read here
 *   status  written here, read by the phone
 */
class CloudSync(private val context: Context, private val onConfigChanged: () -> Unit) {
    private val store = Store(context)
    private var ref: DatabaseReference? = null

    val isConfigured: Boolean
        get() = FirebaseApp.getApps(context).isNotEmpty()

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
                if (!snapshot.exists()) {
                    // First run: publish the local defaults so the phone has something to show.
                    deviceRef.child("config").setValue(toMap(store.config))
                    return
                }
                store.config = fromSnapshot(snapshot)
                onConfigChanged()
            }

            override fun onCancelled(error: DatabaseError) {
                Log.w(TAG, "Config listener cancelled: ${error.message}")
            }
        })
    }

    fun pushStatus(status: GuardStatus) {
        val deviceRef = ref ?: return
        deviceRef.child("status").setValue(
            mapOf(
                "usedMinutesToday" to status.usedMinutesToday,
                "limitReached" to status.limitReached,
                "suspended" to status.suspended,
                "deviceOwner" to status.deviceOwner,
                "usageAccess" to status.usageAccess,
                "released" to status.released,
                "updatedAt" to status.updatedAt,
            )
        )
        if (status.released) {
            // Don't release again if the app is set up as Device Owner later.
            deviceRef.child("config").child("releaseOwner").setValue(false)
        }
    }

    companion object {
        private const val TAG = "TvGuardSync"

        fun toMap(config: GuardConfig) = mapOf(
            "blocked" to config.blocked,
            "dailyLimitMinutes" to config.dailyLimitMinutes,
            "packages" to config.packages,
            "releaseOwner" to config.releaseOwner,
        )

        fun fromSnapshot(snapshot: DataSnapshot): GuardConfig {
            val defaults = GuardConfig()
            val packages = snapshot.child("packages").children
                .mapNotNull { it.getValue(String::class.java) }
                .filter { it.isNotBlank() }
            return GuardConfig(
                blocked = snapshot.child("blocked").getValue(Boolean::class.java) ?: defaults.blocked,
                dailyLimitMinutes = (snapshot.child("dailyLimitMinutes").value as? Number)?.toInt()
                    ?: defaults.dailyLimitMinutes,
                packages = packages.ifEmpty { defaults.packages },
                releaseOwner = snapshot.child("releaseOwner").getValue(Boolean::class.java) ?: false,
            )
        }
    }
}
