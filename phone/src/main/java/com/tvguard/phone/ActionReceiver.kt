package com.tvguard.phone

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import com.google.firebase.auth.FirebaseAuth

/** Handles the buttons on "ask for more time" and "new phone" notifications. */
class ActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val code = Cloud.code(context) ?: return
        if (!Cloud.isConfigured(context)) return
        NotificationManagerCompat.from(context).cancel(intent.getIntExtra(EXTRA_NOTIFICATION, 0))
        val pending = goAsync()
        val run = {
            when (intent.action) {
                ACTION_ANSWER -> {
                    val minutes = intent.getIntExtra(EXTRA_MINUTES, 0)
                    Cloud.answerRequest(
                        code,
                        intent.getLongExtra(EXTRA_REQUEST_AT, 0),
                        intent.getStringExtra(EXTRA_REASON) ?: "limit",
                        minutes,
                    ) { ok ->
                        val text = when {
                            !ok -> "Couldn't reach the TV. Open TV Guard to try again."
                            minutes > 0 -> "Gave $minutes more minutes"
                            else -> "Told the TV no"
                        }
                        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
                        pending.finish()
                    }
                }
                ACTION_APPROVE -> {
                    Cloud.approvePhone(code, intent.getStringExtra(EXTRA_UID) ?: "", intent.getStringExtra(EXTRA_NAME) ?: "Phone")
                    pending.finish()
                }
                ACTION_DENY -> {
                    Cloud.denyPhone(code, intent.getStringExtra(EXTRA_UID) ?: "")
                    pending.finish()
                }
                else -> pending.finish()
            }
        }
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) run() else auth.signInAnonymously().addOnCompleteListener { run() }
    }

    companion object {
        private const val ACTION_ANSWER = "com.tvguard.phone.ANSWER"
        private const val ACTION_APPROVE = "com.tvguard.phone.APPROVE"
        private const val ACTION_DENY = "com.tvguard.phone.DENY"
        private const val EXTRA_NOTIFICATION = "notification"
        private const val EXTRA_REQUEST_AT = "requestAt"
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_MINUTES = "minutes"
        private const val EXTRA_UID = "uid"
        private const val EXTRA_NAME = "name"

        /** Each button of each notification gets its own request code, so extras never mix. */
        private fun broadcast(context: Context, intent: Intent, slot: Int): PendingIntent =
            PendingIntent.getBroadcast(
                context,
                intent.getIntExtra(EXTRA_NOTIFICATION, 0) * 4 + slot,
                intent.setClass(context, ActionReceiver::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )

        fun answer(context: Context, notification: Int, requestAt: Long, reason: String, minutes: Int) =
            broadcast(
                context,
                Intent(ACTION_ANSWER)
                    .putExtra(EXTRA_NOTIFICATION, notification)
                    .putExtra(EXTRA_REQUEST_AT, requestAt)
                    .putExtra(EXTRA_REASON, reason)
                    .putExtra(EXTRA_MINUTES, minutes),
                slot = minutes / 15,
            )

        fun approve(context: Context, notification: Int, uid: String, name: String) =
            broadcast(
                context,
                Intent(ACTION_APPROVE)
                    .putExtra(EXTRA_NOTIFICATION, notification)
                    .putExtra(EXTRA_UID, uid)
                    .putExtra(EXTRA_NAME, name),
                slot = 0,
            )

        fun deny(context: Context, notification: Int, uid: String) =
            broadcast(
                context,
                Intent(ACTION_DENY).putExtra(EXTRA_NOTIFICATION, notification).putExtra(EXTRA_UID, uid),
                slot = 1,
            )
    }
}
