package com.tvguard.phone

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Instant notifications sent by the cloud functions (see functions/index.js). Every message
 * carries type, title and body; "request" and "pending" messages also get answer buttons.
 */
class PushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Cloud.registerToken(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val code = data["device"] ?: Cloud.code(this) ?: return
        if (code != Cloud.code(this)) return
        show(this, data)
    }

    companion object {
        private const val CHANNEL = "alerts"

        fun show(context: Context, data: Map<String, String>) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
            ) {
                return
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "TV alerts", NotificationManager.IMPORTANCE_HIGH)
                )
            }
            val type = data["type"] ?: "info"
            val id = when (type) {
                "request" -> 10
                "pending" -> 11 + Math.floorMod(data["uid"]?.hashCode() ?: 0, 1000)
                "limit" -> 2
                "offline" -> 3
                "weekly" -> 4
                else -> 2000 + (System.currentTimeMillis() % 1000).toInt()
            }
            val open = PendingIntent.getActivity(
                context, 0,
                Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            val builder = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_lock_lock)
                .setContentTitle(data["title"] ?: "TV Guard")
                .setContentText(data["body"] ?: "")
                .setStyle(NotificationCompat.BigTextStyle().bigText(data["body"] ?: ""))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(open)
                .setAutoCancel(true)

            when (type) {
                "request" -> {
                    val requestAt = data["requestAt"] ?: return
                    val reason = data["reason"] ?: "limit"
                    listOf(15 to "+15 min", 30 to "+30 min", 0 to "No").forEach { (minutes, label) ->
                        val intent = ActionReceiver.answer(context, id, requestAt.toLong(), reason, minutes)
                        builder.addAction(action(label, intent))
                    }
                }
                "pending" -> {
                    val uid = data["uid"] ?: return
                    val name = data["name"] ?: "Phone"
                    builder.addAction(action("Approve", ActionReceiver.approve(context, id, uid, name)))
                    builder.addAction(action("Deny", ActionReceiver.deny(context, id, uid)))
                }
            }
            NotificationManagerCompat.from(context).notify(id, builder.build())
        }

        /** Buttons that change the TV only work once the phone is unlocked. */
        private fun action(label: String, intent: PendingIntent) =
            NotificationCompat.Action.Builder(0, label, intent)
                .setAuthenticationRequired(true)
                .build()
    }
}
