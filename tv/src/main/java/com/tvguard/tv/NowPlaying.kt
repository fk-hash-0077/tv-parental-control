package com.tvguard.tv

import android.content.ComponentName
import android.content.Context
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/**
 * What the TV is playing, for the phone's "Now playing" card.
 *
 * Titles come from the media sessions that apps like YouTube and Netflix publish. Android only
 * shares those with an app that has notification access, which is turned on once over adb:
 *   adb shell cmd notification allow_listener com.tvguard.tv/com.tvguard.tv.MediaListener
 * Nothing is polled: Android calls back when a title or play state changes.
 */
object NowPlaying {
    data class Media(val pkg: String, val title: String, val subtitle: String, val playing: Boolean)

    private val main = Handler(Looper.getMainLooper())
    private var manager: MediaSessionManager? = null
    private val controllers = mutableListOf<Pair<MediaController, MediaController.Callback>>()
    @Volatile private var sessions: List<Media> = emptyList()

    /** Called when a title or play state changes. Set by the service. */
    @Volatile var onChange: (() -> Unit)? = null

    fun hasAccess(context: Context): Boolean =
        NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

    /** The media to show for [foreground]: that app's own session, or anything else playing. */
    fun mediaFor(foreground: String?): Media? {
        val list = sessions
        return list.firstOrNull { it.pkg == foreground && it.title.isNotEmpty() }
            ?: list.firstOrNull { it.playing && it.title.isNotEmpty() }
    }

    /** Asks Android to connect the listener again if access is on (it sometimes isn't after an update). */
    fun rebind(context: Context) {
        if (!hasAccess(context)) return
        runCatching {
            NotificationListenerService.requestRebind(ComponentName(context, MediaListener::class.java))
        }
    }

    internal fun attach(context: Context) = main.post {
        detachOnMain()
        val msm = context.getSystemService(MediaSessionManager::class.java) ?: return@post
        val component = ComponentName(context, MediaListener::class.java)
        runCatching {
            msm.addOnActiveSessionsChangedListener(sessionsChanged, component, main)
            manager = msm
            track(msm.getActiveSessions(component))
        }.onFailure { AppLog.w(TAG, "Can't read media sessions", it) }
    }

    internal fun detach() = main.post { detachOnMain() }

    private fun detachOnMain() {
        manager?.removeOnActiveSessionsChangedListener(sessionsChanged)
        manager = null
        untrack()
        update()
    }

    private val sessionsChanged = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        track(list.orEmpty())
    }

    private fun track(list: List<MediaController>) {
        untrack()
        for (controller in list) {
            val callback = object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) = update()
                override fun onPlaybackStateChanged(state: PlaybackState?) = update()
                override fun onSessionDestroyed() = update()
            }
            controller.registerCallback(callback, main)
            controllers += controller to callback
        }
        update()
    }

    private fun untrack() {
        controllers.forEach { (controller, callback) -> runCatching { controller.unregisterCallback(callback) } }
        controllers.clear()
    }

    private fun update() {
        val now = controllers.map { (c, _) ->
            val meta = c.metadata
            fun text(key: String) = meta?.getString(key)?.trim().orEmpty()
            val title = text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE).ifEmpty { text(MediaMetadata.METADATA_KEY_TITLE) }
            val subtitle = text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)
                .ifEmpty { text(MediaMetadata.METADATA_KEY_ARTIST) }
                .ifEmpty { text(MediaMetadata.METADATA_KEY_ALBUM) }
            Media(c.packageName, title, subtitle, c.playbackState?.state == PlaybackState.STATE_PLAYING)
        }
        if (now == sessions) return
        sessions = now
        onChange?.invoke()
    }

    private const val TAG = "TvGuardNowPlaying"
}

/**
 * Only here so Android shares media titles with TV Guard (see [NowPlaying]).
 * Notifications themselves are ignored.
 */
class MediaListener : NotificationListenerService() {
    override fun onListenerConnected() {
        AppLog.init(this)
        AppLog.i("TvGuardNowPlaying", "Title access connected")
        NowPlaying.attach(applicationContext)
    }

    override fun onListenerDisconnected() {
        NowPlaying.detach()
    }
}
