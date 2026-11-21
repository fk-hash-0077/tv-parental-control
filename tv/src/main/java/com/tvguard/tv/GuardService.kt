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
    private val startedAt = System.currentTimeMillis()
    private var startedBy = ""
    /** When the previous run last checked the rules, read before this run's first check. */
    private var previousAliveAt = 0L

    private val tick = object : Runnable {
        override fun run() {
            handler.removeCallbacks(this)
            // Each step on its own: a failure is logged with its stack trace and the rest of the
            // check still runs, instead of the whole app crashing and leaving the TV unguarded.
            try {
                step("rules") {
                    val result = Enforcer.evaluate(this@GuardService)
                    sync.pushStatus(result.status)
                    lastForeground = result.usage.foreground
                    step("usage") { publishUsage(result.usage) }
                }
                step("now playing") { publishNowPlaying() }
                step("app list") { publishCatalog() }
                step("update request") { checkForUpdateRequest() }
                step("logs request") { checkForLogsRequest() }
                step("diagnostics") { sync.pushDiag(startedAt, startedBy, Protection.diag(this@GuardService)) }
                step("keep-alive") {
                    Store(this@GuardService).lastAliveAt = System.currentTimeMillis()
                    Protection.armAlarm(this@GuardService)
                }
            } finally {
                handler.postDelayed(this, INTERVAL_MS)
            }
        }
    }

    /** Runs one step of a check; a failure is logged with its stack trace instead of crashing. */
    private fun step(name: String, block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            AppLog.e(TAG, "Check step \"$name\" failed", e)
        }
    }

    private val watchdog = object : Runnable {
        override fun run() {
            try {
                sync.watchdog()
            } catch (e: Exception) {
                AppLog.e(TAG, "Watchdog failed", e)
            } finally {
                handler.postDelayed(this, WATCHDOG_MS)
            }
        }
    }

    /** Waking from standby: reconnect straight away rather than waiting for the watchdog. */
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // The old connection often died during standby while still looking open.
            sync.reconnect(force = true, reason = "screen on")
            handler.post(tick)
        }
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            sync.reconnect(force = true, reason = "network available")
            handler.post(tick)
        }
    }

    /** The app on screen at the last check, for [publishNowPlaying] between checks. */
    private var lastForeground: String? = null

    /** Sent only when something changed, so this costs nothing while one show keeps playing. */
    private fun publishNowPlaying() {
        val app = lastForeground
        val media = NowPlaying.mediaFor(app)
        val pkg = app ?: media?.pkg.orEmpty()
        val label = if (pkg.isEmpty()) "" else AppCatalog.labels(this, setOf(pkg))[pkg] ?: pkg
        sync.pushNowPlaying(
            mapOf(
                "pkg" to pkg,
                "app" to label,
                "title" to media?.title.orEmpty(),
                "subtitle" to media?.subtitle.orEmpty(),
                "playing" to (media?.playing == true),
                "titleAccess" to NowPlaying.hasAccess(this),
            )
        )
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

    /** The phone's "Send logs": upload the last 24 hours of the TV's log, up to about 100 KB. */
    private fun checkForLogsRequest() {
        val store = Store(this)
        val requestedAt = store.config.logsRequestAt
        if (requestedAt <= store.handledLogsRequestAt) return
        store.handledLogsRequestAt = requestedAt
        AppLog.i(TAG, "Uploading logs for the phone")
        sync.uploadLogs(requestedAt, AppLog.recent(hours = 24, maxChars = 100_000))
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
        AppLog.init(this)
        AppLog.i(TAG, "Started, version ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
        previousAliveAt = Store(this).lastAliveAt
        startInForeground()
        Updater.reportFinished(this)
        runCatching { KeepAliveJob.schedulePeriodic(this) }
        NowPlaying.onChange = { handler.post { publishNowPlaying() } }
        NowPlaying.rebind(this)
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
        // Every start goes through startForegroundService (keep-alive job, 5-minute alarm,
        // lifeline, boot), and Android needs startForeground after *each* of them, not just the
        // first. This TV's Android crashed TV Guard 5 seconds after a repeat start without it
        // ("did not then call Service.startForeground"), so call it every time, first thing.
        startInForeground()
        if (startedBy.isEmpty()) {
            // What brought TV Guard up, so the logs show whether the TV let it start by itself.
            startedBy = intent?.getStringExtra(EXTRA_REASON) ?: "restarted by Android"
            AppLog.i(TAG, "Started by: $startedBy")
            val since = previousAliveAt
            val by = startedBy
            Thread({ runCatching { Protection.checkGap(this, since, by) } }, "gap-check").start()
        }
        if (::handler.isInitialized) handler.post(tick)
        return START_STICKY
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(networkCallback) }
        NowPlaying.onChange = null
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
        private const val TAG = "TvGuard"
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

        private const val EXTRA_REASON = "reason"

        /** [reason] is logged when this starts the service, e.g. "TV turned on". */
        fun start(context: Context, reason: String = "TV Guard opened") {
            ContextCompat.startForegroundService(
                context,
                Intent(context, GuardService::class.java).putExtra(EXTRA_REASON, reason),
            )
        }
    }
}
