package com.tvguard.phone

import android.content.Context
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
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
import java.util.Date

/**
 * Parent controls for one paired TV, backed by Firebase Realtime Database
 * at devices/{code}: this app writes config, the TV writes status.
 */
class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("parent", Context.MODE_PRIVATE) }

    private lateinit var notice: TextView
    private lateinit var pairSection: View
    private lateinit var controlSection: View
    private lateinit var codeInput: EditText
    private lateinit var status: TextView
    private lateinit var allowSwitch: SwitchCompat
    private lateinit var limitInput: EditText

    private var deviceRef: DatabaseReference? = null
    private val listeners = mutableListOf<Pair<DatabaseReference, ValueEventListener>>()
    private var config: DataSnapshot? = null
    private var tvStatus: DataSnapshot? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        notice = findViewById(R.id.notice)
        pairSection = findViewById(R.id.pairSection)
        controlSection = findViewById(R.id.controlSection)
        codeInput = findViewById(R.id.codeInput)
        status = findViewById(R.id.status)
        allowSwitch = findViewById(R.id.allowSwitch)
        limitInput = findViewById(R.id.limitInput)

        findViewById<Button>(R.id.pairButton).setOnClickListener { pair() }
        findViewById<Button>(R.id.saveLimit).setOnClickListener { saveLimit() }
        findViewById<Button>(R.id.releaseButton).setOnClickListener { confirmRelease() }
        findViewById<Button>(R.id.unpairButton).setOnClickListener { unpair() }
        allowSwitch.setOnCheckedChangeListener { button, checked ->
            if (button.isPressed) deviceRef?.child("config/blocked")?.setValue(!checked)
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
        listen(ref.child("config")) { config = it; render() }
        listen(ref.child("status")) { tvStatus = it; render() }
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

    private fun render() {
        val blocked = config?.child("blocked")?.getValue(Boolean::class.java) ?: false
        val limit = (config?.child("dailyLimitMinutes")?.value as? Number)?.toInt() ?: 0
        allowSwitch.isChecked = !blocked
        if (!limitInput.hasFocus()) limitInput.setText(limit.toString())

        val s = tvStatus
        val lines = mutableListOf<String>()
        if (s == null || !s.exists()) {
            lines += "Waiting for the TV to report in..."
        } else {
            val used = (s.child("usedMinutesToday").value as? Number)?.toInt() ?: 0
            val suspended = s.child("suspended").getValue(Boolean::class.java) ?: false
            val updated = (s.child("updatedAt").value as? Number)?.toLong() ?: 0L
            lines += if (limit > 0) "Watched today: $used of $limit min" else "Watched today: $used min"
            lines += if (suspended) "YouTube is locked on the TV" else "YouTube is available on the TV"
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
            if (updated > 0) {
                lines += "TV last checked in at " +
                    DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(updated))
            }
        }
        status.text = lines.joinToString("\n")
    }

    private fun saveLimit() {
        val minutes = limitInput.text.toString().toIntOrNull()
        if (minutes == null || minutes < 0) {
            toast("Enter a number of minutes.")
            return
        }
        deviceRef?.child("config/dailyLimitMinutes")?.setValue(minutes)
            ?.addOnSuccessListener { toast("Daily limit saved.") }
        limitInput.clearFocus()
    }

    private fun confirmRelease() {
        AlertDialog.Builder(this)
            .setTitle("Remove protection?")
            .setMessage(
                "The TV unlocks YouTube and stops being managed, so the TV app can be uninstalled. " +
                    "Turning protection back on needs a factory reset and the setup script."
            )
            .setPositiveButton("Remove") { _, _ ->
                deviceRef?.child("config/releaseOwner")?.setValue(true)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun unpair() {
        detach()
        prefs.edit().remove("code").apply()
        config = null
        tvStatus = null
        codeInput.setText("")
        showPairedOrPairing()
    }

    private fun showNotice(text: String) {
        notice.text = text
        notice.visibility = View.VISIBLE
    }

    private fun hideNotice() {
        notice.visibility = View.GONE
    }

    private fun toast(text: String) = Toast.makeText(this, text, Toast.LENGTH_LONG).show()
}
