package com.example.cartoon

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Android only lets an app see other apps' media sessions (Spotify, YouTube Music, ...)
 * if the user grants it "Notification access". This empty service is what that switch turns on.
 */
class NowPlayingListener : NotificationListenerService()

/** A snapshot of what another app is playing. */
data class NowPlaying(
    val packageName: String,
    val appName: String,
    val title: String,
    val artist: String,
    val durationMs: Long,
    val art: Bitmap?,
    val isPlaying: Boolean,
    val canSeek: Boolean,
    private val basePosition: Long,
    private val baseTime: Long,
    private val speed: Float,
) {
    /** Current playback position, estimated between the app's updates. */
    fun positionAt(now: Long = SystemClock.elapsedRealtime()): Long {
        val pos = if (isPlaying) basePosition + ((now - baseTime) * speed).toLong() else basePosition
        return if (durationMs > 0) pos.coerceIn(0L, durationMs) else pos.coerceAtLeast(0L)
    }
}

/** Follows whichever media app is currently playing and exposes it as a [StateFlow]. */
class NowPlayingTracker(context: Context) {
    private val appContext = context.applicationContext
    private val sessionManager = appContext.getSystemService(MediaSessionManager::class.java)
    private val listenerComponent = ComponentName(appContext, NowPlayingListener::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val labels = HashMap<String, String>()

    private val _state = MutableStateFlow<NowPlaying?>(null)
    val state: StateFlow<NowPlaying?> = _state

    private var controllers: List<MediaController> = emptyList()
    private var active: MediaController? = null
    private var started = false

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()
        override fun onPlaybackStateChanged(state: PlaybackState?) = refresh()
        override fun onSessionDestroyed() = refresh()
    }

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list ->
        setControllers(list.orEmpty())
    }

    /** Starts following media apps. Returns false if Notification access isn't granted yet. */
    fun start(): Boolean {
        if (started) return true
        return try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, listenerComponent, handler)
            started = true
            setControllers(sessionManager.getActiveSessions(listenerComponent))
            true
        } catch (e: SecurityException) {
            false
        }
    }

    fun stop() {
        if (!started) return
        runCatching { sessionManager.removeOnActiveSessionsChangedListener(sessionsListener) }
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = emptyList()
        active = null
        started = false
        _state.value = null
    }

    fun playPause() {
        val c = active ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    fun next() { active?.transportControls?.skipToNext() }
    fun previous() { active?.transportControls?.skipToPrevious() }
    fun seekTo(ms: Long) { active?.transportControls?.seekTo(ms.coerceAtLeast(0L)) }

    private fun setControllers(list: List<MediaController>) {
        controllers.forEach { it.unregisterCallback(callback) }
        controllers = list.filter { it.packageName != appContext.packageName }
        controllers.forEach { it.registerCallback(callback, handler) }
        if (active?.let { a -> controllers.none { it.sessionToken == a.sessionToken } } != false) {
            active = null
        }
        refresh()
    }

    private fun refresh() {
        // Follow whichever app is actually playing; otherwise stay on the last one.
        val playing = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        val current = active?.takeIf { a -> controllers.any { it.sessionToken == a.sessionToken } }
        active = playing ?: current ?: controllers.firstOrNull()

        val c = active
        if (c == null) {
            _state.value = null
            return
        }

        val md = c.metadata
        val ps = c.playbackState
        val title = listOf(MediaMetadata.METADATA_KEY_TITLE, MediaMetadata.METADATA_KEY_DISPLAY_TITLE)
            .firstNotNullOfOrNull { k -> md?.getString(k)?.takeIf { it.isNotBlank() } }.orEmpty()
        val artist = listOf(
            MediaMetadata.METADATA_KEY_ARTIST,
            MediaMetadata.METADATA_KEY_ALBUM_ARTIST,
            MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE,
        ).firstNotNullOfOrNull { k -> md?.getString(k)?.takeIf { it.isNotBlank() } }.orEmpty()
        val art = listOf(
            MediaMetadata.METADATA_KEY_ALBUM_ART,
            MediaMetadata.METADATA_KEY_ART,
            MediaMetadata.METADATA_KEY_DISPLAY_ICON,
        ).firstNotNullOfOrNull { k -> md?.getBitmap(k) }

        val rawPos = ps?.position ?: 0L
        _state.value = NowPlaying(
            packageName = c.packageName,
            appName = labelFor(c.packageName),
            title = title,
            artist = artist,
            durationMs = (md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L).coerceAtLeast(0L),
            art = art,
            isPlaying = ps?.state == PlaybackState.STATE_PLAYING,
            canSeek = ps != null && (ps.actions and PlaybackState.ACTION_SEEK_TO) != 0L,
            basePosition = if (rawPos < 0) 0L else rawPos,
            baseTime = ps?.lastPositionUpdateTime?.takeIf { it > 0 } ?: SystemClock.elapsedRealtime(),
            speed = ps?.playbackSpeed?.takeIf { it > 0f } ?: 1f,
        )
    }

    private fun labelFor(pkg: String): String = labels.getOrPut(pkg) {
        runCatching {
            val pm = appContext.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrElse {
            KNOWN_APPS[pkg] ?: pkg.substringAfterLast('.').replaceFirstChar { it.uppercase() }
        }
    }

    companion object {
        private val KNOWN_APPS = mapOf(
            "com.spotify.music" to "Spotify",
            "com.google.android.apps.youtube.music" to "YouTube Music",
            "com.google.android.youtube" to "YouTube",
            "com.soundcloud.android" to "SoundCloud",
            "com.amazon.mp3" to "Amazon Music",
            "com.apple.android.music" to "Apple Music",
            "com.pandora.android" to "Pandora",
            "deezer.android.app" to "Deezer",
            "com.aspiro.tidal" to "TIDAL",
        )

        fun hasAccess(context: Context): Boolean =
            NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)

        /** Opens the system screen where the user switches on Notification access for CarToon. */
        fun accessSettingsIntent(context: Context): Intent =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(
                    Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                    ComponentName(context, NowPlayingListener::class.java).flattenToString(),
                )
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        fun fallbackSettingsIntent(): Intent =
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
