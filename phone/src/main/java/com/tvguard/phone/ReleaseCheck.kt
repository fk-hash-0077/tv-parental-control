package com.tvguard.phone

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks up the newest TV Guard release on GitHub, so the phone can say when the phone app or
 * the TV has a newer version waiting. Checks at most every 30 minutes and remembers the answer.
 *
 * Releases are tagged v<build number> (the hidden number that always goes up) and titled
 * "TV Guard 1.1" (the version people see).
 */
object ReleaseCheck {
    data class Release(val build: Int, val name: String, val phoneApkUrl: String)

    private const val PREFS = "release_check"
    private const val EVERY_MS = 30 * 60_000L
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var running = false

    /** The last release seen, or null before the first successful check. */
    fun cached(context: Context): Release? {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val build = p.getInt("build", 0)
        if (build <= 0) return null
        return Release(build, p.getString("name", "").orEmpty(), p.getString("phoneApk", "").orEmpty())
    }

    /** Checks GitHub in the background if the last check is old; [onDone] runs on the main thread. */
    fun refresh(context: Context, force: Boolean = false, onDone: (Release?) -> Unit) {
        val app = context.applicationContext
        val p = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (running) return
        if (!force && System.currentTimeMillis() - p.getLong("checkedAt", 0) < EVERY_MS) return
        running = true
        Thread {
            val release = runCatching { fetch() }.getOrNull()
            if (release != null) {
                p.edit()
                    .putInt("build", release.build)
                    .putString("name", release.name)
                    .putString("phoneApk", release.phoneApkUrl)
                    .putLong("checkedAt", System.currentTimeMillis())
                    .apply()
            }
            running = false
            main.post { onDone(release ?: cached(app)) }
        }.start()
    }

    private fun fetch(): Release? {
        val connection = URL("https://api.github.com/repos/${BuildConfig.RELEASES_REPO}/releases/latest")
            .openConnection() as HttpURLConnection
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("User-Agent", "tv-guard")
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        try {
            if (connection.responseCode != 200) return null
            val json = JSONObject(connection.inputStream.bufferedReader().readText())
            val build = json.optString("tag_name").trimStart('v').toIntOrNull() ?: return null
            val assets = json.optJSONArray("assets")
            val apk = (0 until (assets?.length() ?: 0))
                .map { assets!!.getJSONObject(it) }
                .firstOrNull { it.optString("name") == "phone-debug.apk" }
                ?.optString("browser_download_url")
                .orEmpty()
            return Release(build, nameOf(json.optString("name"), build), apk)
        } finally {
            connection.disconnect()
        }
    }

    /** "TV Guard 1.1" gives "1.1". Older titles ("TV Guard version 37") give "build 37". */
    fun nameOf(title: String, build: Int): String {
        val rest = title.removePrefix("TV Guard").trim()
        return if (rest.isEmpty() || rest.startsWith("version")) "build $build" else rest
    }

    /** How a version reads to people: "1.0", or "build 37" for versions from before 1.0. */
    fun label(name: String?, build: Int): String =
        if (!name.isNullOrBlank() && !name.startsWith("0.")) name else "build $build"
}
