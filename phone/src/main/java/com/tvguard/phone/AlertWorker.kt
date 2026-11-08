package com.tvguard.phone

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Tasks
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/**
 * Checks the TV's status about every 15 minutes (Android's shortest periodic
 * interval) and notifies once when today's limit is used up or the TV stops reporting.
 */
class AlertWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("parent", Context.MODE_PRIVATE)
        val code = prefs.getString("code", null) ?: return Result.success()
        if (FirebaseApp.getApps(applicationContext).isEmpty()) return Result.success()

        val status: DataSnapshot = try {
            val auth = FirebaseAuth.getInstance()
            if (auth.currentUser == null) Tasks.await(auth.signInAnonymously(), 30, TimeUnit.SECONDS)
            val ref = FirebaseDatabase.getInstance().getReference("devices").child(code).child("status")
            Tasks.await(ref.get(), 30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            return Result.retry()
        }
        if (!status.exists()) return Result.success()

        val today = LocalDate.now().toString()
        if (status.child("reason").getValue(String::class.java) == "limit" &&
            prefs.getString("limitAlertDate", null) != today
        ) {
            notify(LIMIT_ID, "Today's limit is used up", "Blocked apps are locked on the TV until tomorrow.")
            prefs.edit().putString("limitAlertDate", today).apply()
        }

        val updatedAt = (status.child("updatedAt").value as? Number)?.toLong() ?: 0L
        val offline = System.currentTimeMillis() - updatedAt > OFFLINE_AFTER_MS
        if (offline && !prefs.getBoolean("offlineAlerted", false)) {
            notify(OFFLINE_ID, "The TV stopped checking in", "It may be switched off, offline, or TV Guard stopped running.")
            prefs.edit().putBoolean("offlineAlerted", true).apply()
        } else if (!offline) {
            prefs.edit().putBoolean("offlineAlerted", false).apply()
        }
        return Result.success()
    }

    private fun notify(id: Int, title: String, text: String) {
        val context = applicationContext
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        createChannel(context)
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(title)
            .setContentText(text)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(id, notification)
    }

    companion object {
        private const val WORK_NAME = "tv-alerts"
        private const val CHANNEL = "alerts"
        private const val LIMIT_ID = 1
        private const val OFFLINE_ID = 2
        private const val OFFLINE_AFTER_MS = 20 * 60_000L

        fun createChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "TV alerts", NotificationManager.IMPORTANCE_DEFAULT)
            )
        }

        fun schedule(context: Context) {
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AlertWorker>(15, TimeUnit.MINUTES).build(),
            )
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        }
    }
}
