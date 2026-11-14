package com.tvguard.tv

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * "Update TV" from the phone: downloads the newest tv-debug.apk from the repo's latest GitHub
 * release and installs it silently (allowed because TV Guard is Device Owner). Installs are
 * blocked on the TV, so they're allowed for a few minutes while this runs, then blocked again.
 * Progress goes to [Store.updateState], which the phone shows.
 */
object Updater {
    private const val TAG = "TvGuardUpdate"
    private const val APK_NAME = "tv-debug.apk"

    fun run(context: Context, onStateChanged: () -> Unit) {
        val store = Store(context)
        fun state(text: String) {
            store.updateState = text
            onStateChanged()
        }
        try {
            state("Checking for updates…")
            val release = fetchJson("https://api.github.com/repos/${BuildConfig.RELEASES_REPO}/releases/latest")
            val version = release.optString("tag_name").trimStart('v').toIntOrNull()
            val assets = release.optJSONArray("assets")
            val url = (0 until (assets?.length() ?: 0))
                .map { assets!!.getJSONObject(it) }
                .firstOrNull { it.optString("name") == APK_NAME }
                ?.optString("browser_download_url")
            if (version == null || url.isNullOrEmpty()) {
                state("No TV update found on GitHub")
                return
            }
            if (version <= BuildConfig.VERSION_CODE) {
                state("Up to date (version ${BuildConfig.VERSION_CODE})")
                return
            }

            state("Downloading version $version…")
            val apk = File(context.cacheDir, "update.apk")
            download(url, apk)

            state("Installing version $version…")
            store.updateTarget = version
            store.updatingSince = System.currentTimeMillis()
            Policy(context).setInstallsAllowed(true)
            install(context, apk)
            // The app restarts once the install finishes; BootReceiver brings the service back.
        } catch (e: Exception) {
            Log.w(TAG, "Update failed", e)
            store.updatingSince = 0
            state("Update failed: ${e.message ?: e.javaClass.simpleName}")
        }
    }

    /** Called when the service starts: reports whether an update that was in progress worked. */
    fun reportFinished(context: Context) {
        val store = Store(context)
        val target = store.updateTarget
        if (target <= 0) return
        if (BuildConfig.VERSION_CODE >= target) {
            store.updateState = "Updated to version ${BuildConfig.VERSION_CODE}"
            store.updateTarget = 0
            store.updatingSince = 0
        }
    }

    private fun fetchJson(url: String): JSONObject {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "tv-guard")
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        try {
            if (connection.responseCode != 200) error("GitHub answered ${connection.responseCode}")
            return JSONObject(connection.inputStream.bufferedReader().readText())
        } finally {
            connection.disconnect()
        }
    }

    private fun download(url: String, target: File) {
        var current = url
        // GitHub redirects release downloads to its file host; follow by hand to be safe.
        repeat(5) {
            val connection = URL(current).openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("User-Agent", "tv-guard")
            connection.connectTimeout = 15_000
            connection.readTimeout = 60_000
            try {
                when (connection.responseCode) {
                    in 300..399 -> {
                        current = connection.getHeaderField("Location") ?: error("Bad redirect")
                    }
                    200 -> {
                        connection.inputStream.use { input -> target.outputStream().use { input.copyTo(it) } }
                        return
                    }
                    else -> error("Download answered ${connection.responseCode}")
                }
            } finally {
                connection.disconnect()
            }
        }
        error("Too many redirects")
    }

    private fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val intent = PendingIntent.getBroadcast(
                context, sessionId, Intent(context, UpdateReceiver::class.java), flags
            )
            session.commit(intent.intentSender)
        }
    }
}

/** Receives the install result. On success the app is replaced and restarted instead. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_SUCCESS) return
        val store = Store(context)
        store.updatingSince = 0
        store.updateTarget = 0
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "status $status"
        store.updateState = "Update failed: $message"
        GuardService.start(context)
    }
}
