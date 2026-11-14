package com.tvguard.tv

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import java.time.LocalDate

/**
 * Runs in the background, re-checking the rules every [INTERVAL_MS] and whenever the phone
 * changes them, and keeping the connection to the phone alive.
 */
class GuardService : Service() {
    private lateinit var thread: HandlerThread
    private lateinit var handler: Handler
    private lateinit var sync: CloudSync

    private var lastCatalogAt = 0L
    private var lastUsageAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            handler.removeCallbacks(this)
            val result = Enforcer.evaluate(this@GuardService)
            sync.pushStatus(result.status)
            publishUsage(result.usage)
            publishCatalog()
            checkForUpdateRequest()
            handler.postDelayed(this, INTERVAL_MS)
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            sync.watchdog()
            handler.postDelayed(this, WATCHDOG_MS)
        }
    }

    /** Waking from standby: reconnect straight away rather than waiting for the watchdog. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            sync.reconnect()
            handler.post(tick)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            sync.reconnect()
            handler.post(tick)
        }
    }

    private fun publishUsage(usage: UsageTracker.Usage) {
        val now = System.currentTimeMillis()
        if (now - lastUsageAt < USAGE_INTERVAL_MS) return
        lastUsageAt = now
        val labels = AppCatalog.labels(this, usage.minutesByApp.keys)
        sync.pushUsage(LocalDate.now().format(Rules.DATE), usage.minutesByApp, labels)
    }

    /** App list and 7-day history change slowly, so they're sent every few minutes, not every tick. */
    private fun publishCatalog() {
        val now = System.currentTimeMillis()
        if (now - lastCatalogAt < CATALOG_INTERVAL_MS) return
        lastCatalogAt = now
        sync.pushApps(AppCatalog.launchable(this))
        sync.pushHistory(UsageTracker(this).history(HISTORY_DAYS))
    }

    private fun checkForUpdateRequest() {
        val store = Store(this)
        val requestedAt = store.config.updateRequestAt
        if (requestedAt <= store.handledUpdateRequestAt) return
        store.handledUpdateRequestAt = requestedAt
        Thread({ Updater.run(this) { handler.post(tick) } }, "update").start()
    }

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        Updater.reportFinished(this)
        thread = HandlerThread("guard").also { it.start() }
        handler = Handler(thread.looper)
        sync = CloudSync(this) { handler.post(tick) }
        current = sync
        sync.start()
        handler.post(tick)
        handler.postDelayed(watchdog, WATCHDOG_MS)

        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        ContextCompat.registerReceiver(this, screenReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        runCatching {
            getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(networkCallback)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (::handler.isInitialized) handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        handler.removeCallbacksAndMessages(null)
        thread.quitSafely()
        if (current === sync) current = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Parental control", NotificationManager.IMPORTANCE_MIN)
            )
        }
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setContentTitle(getString(R.string.app_name))
            .setContentText("Parental controls are on")
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    companion object {
        private const val CHANNEL = "guard"
        private const val NOTIFICATION_ID = 1
        private const val INTERVAL_MS = 30_000L
        private const val WATCHDOG_MS = 60_000L
        private const val USAGE_INTERVAL_MS = 60_000L
        private const val CATALOG_INTERVAL_MS = 5 * 60_000L
        private const val HISTORY_DAYS = 7

        /** The running service's connection, used by the lock screen to reach the phone. */
        @Volatile var current: CloudSync? = null
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, GuardService::class.java))
        }
    }
}
