package com.tvguard.phone

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessaging
import java.time.LocalDate

/**
 * Shared by the app screen and notification buttons. The TV's data lives at devices/{code};
 * field names must match GuardConfig and CloudSync in the TV app.
 */
object Cloud {

    fun prefs(context: Context) = context.getSharedPreferences("parent", Context.MODE_PRIVATE)

    fun isConfigured(context: Context) = FirebaseApp.getApps(context).isNotEmpty()

    /** The paired TV's code, or null. */
    fun code(context: Context): String? = prefs(context).getString("code", null)

    fun device(code: String): DatabaseReference =
        FirebaseDatabase.getInstance().getReference("devices").child(code)

    fun uid(): String? = FirebaseAuth.getInstance().currentUser?.uid

    /** Registers this phone for instant notifications. Needs the phone to be approved. */
    fun registerToken(context: Context, token: String? = null) {
        if (!isConfigured(context)) return
        val code = code(context) ?: return
        val uid = uid() ?: return
        if (token != null) {
            device(code).child("tokens").child(uid).setValue(token)
            return
        }
        FirebaseMessaging.getInstance().token.addOnSuccessListener {
            device(code).child("tokens").child(uid).setValue(it)
        }
    }

    /**
     * Answers "ask for more time". For a limit lock the minutes become bonus time; for bedtime
     * or allowed hours the TV stays unlocked for that long. 0 minutes means no.
     */
    fun answerRequest(code: String, requestAt: Long, reason: String, minutes: Int, done: (Boolean) -> Unit = {}) {
        val ref = device(code)
        val answer = mapOf("requestAt" to requestAt, "minutes" to minutes, "at" to System.currentTimeMillis())
        if (minutes <= 0) {
            ref.child("requestAnswer").setValue(answer).addOnCompleteListener { done(it.isSuccessful) }
            return
        }
        ref.child("config").get().addOnSuccessListener { snapshot ->
            val today = LocalDate.now().toString()
            val updates = if (reason == "limit") {
                val existing = if (snapshot.child("bonusDate").value == today) {
                    (snapshot.child("bonusMinutes").value as? Number)?.toInt() ?: 0
                } else {
                    0
                }
                mapOf("bonusDate" to today, "bonusMinutes" to existing + minutes)
            } else {
                val current = (snapshot.child("overrideUntil").value as? Number)?.toLong() ?: 0L
                val from = maxOf(current, System.currentTimeMillis())
                mapOf("overrideUntil" to from + minutes * 60_000L)
            }
            ref.child("config").updateChildren(updates)
            ref.child("requestAnswer").setValue(answer).addOnCompleteListener { done(it.isSuccessful) }
        }.addOnFailureListener { done(false) }
    }

    fun approvePhone(code: String, uid: String, name: String) {
        val ref = device(code)
        ref.child("parents").child(uid).setValue(mapOf("name" to name, "addedAt" to System.currentTimeMillis()))
        ref.child("pending").child(uid).removeValue()
    }

    fun denyPhone(code: String, uid: String) {
        device(code).child("pending").child(uid).removeValue()
    }

    fun removePhone(code: String, uid: String) {
        val ref = device(code)
        ref.child("tokens").child(uid).removeValue()
        ref.child("parents").child(uid).removeValue()
    }
}
