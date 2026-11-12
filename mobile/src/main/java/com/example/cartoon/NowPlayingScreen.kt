package com.example.cartoon

import android.os.SystemClock
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Shows synced lyrics for whatever is playing in another app (Spotify, YouTube Music, ...).
 * Lyrics come from the user's own .lrc / .txt files in their CarToon folder.
 */
@Composable
fun NowPlayingScreen(
    tracker: NowPlayingTracker,
    library: Library,
    hasFolder: Boolean,
    onPickFolder: () -> Unit,
) {
    val context = LocalContext.current
    val ui = LocalUi.current

    var hasAccess by remember { mutableStateOf(NowPlayingTracker.hasAccess(context)) }
    // Re-check after the user comes back from the settings screen.
    LaunchedEffect(hasAccess) {
        if (hasAccess) {
            tracker.start()
        } else {
            while (!hasAccess) {
                delay(1000)
                hasAccess = NowPlayingTracker.hasAccess(context)
            }
        }
    }

    Column(Modifier.fillMaxSize().padding(horizontal = ui.pad, vertical = 12.dp)) {
        Text("Now Playing", fontSize = ui.headline, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(14.dp))

        if (!hasAccess) {
            EmptyState(
                icon = AppIcons.Note,
                title = "Let CarToon see what's playing",
                body = "To show lyrics for Spotify, YouTube Music and other apps, turn on " +
                    "Notification access for CarToon. If the switch is greyed out, go to " +
                    "Settings → Apps → CarToon → ⋮ → Allow restricted settings first.",
                modifier = Modifier.fillMaxSize(),
            ) {
                BigButton("Turn on access") {
                    runCatching { context.startActivity(NowPlayingTracker.accessSettingsIntent(context)) }
                        .onFailure {
                            runCatching { context.startActivity(NowPlayingTracker.fallbackSettingsIntent()) }
                        }
                }
            }
            return@Column
        }

        val np by tracker.state.collectAsState()
        val song = np
        if (song == null || song.title.isBlank()) {
            EmptyState(
                icon = AppIcons.Note,
                title = "Nothing playing",
                body = "Start a song in Spotify, YouTube Music, SoundCloud or any music app. " +
                    "It will show up here with its lyrics.",
                modifier = Modifier.fillMaxSize(),
            )
            return@Column
        }

        NowPlayingContent(tracker, song, library, hasFolder, onPickFolder)
    }
}

@Composable
private fun NowPlayingContent(
    tracker: NowPlayingTracker,
    song: NowPlaying,
    library: Library,
    hasFolder: Boolean,
    onPickFolder: () -> Unit,
) {
    val context = LocalContext.current
    val ui = LocalUi.current

    // Smooth position updates between the other app's reports
    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(100)
        }
    }

    val songKey = "${song.packageName}|${song.title}|${song.artist}"
    var lyrics by remember(songKey) { mutableStateOf<Lyrics?>(null) }
    var offsetMs by remember(songKey) { mutableLongStateOf(0L) }
    LaunchedEffect(songKey, library) {
        val uri = library.findLyrics(song.title, song.artist)
        lyrics = uri?.let { withContext(Dispatchers.IO) { MediaLibrary.readText(context, it)?.let(Lyrics::parse) } }
    }

    val position = song.positionAt(now)
    val lyricPosition = position + offsetMs
    val l = lyrics

    val emptyTitle = "No lyrics for this song"
    val emptyBody = if (hasFolder) {
        "Add a lyrics file named \"${song.artist.ifBlank { "Artist" }} - ${song.title}.lrc\" " +
            "to your CarToon folder, then tap Refresh in Library."
    } else {
        "Choose your CarToon folder, then add .lrc lyrics files named \"Artist - Title.lrc\"."
    }

    val timing: @Composable () -> Unit = {
        if (l != null && l.synced) {
            Spacer(Modifier.height(if (ui.wide) 20.dp else 10.dp))
            TimingRow(offsetMs) { offsetMs = it }
        }
    }

    val track: @Composable (Modifier, Boolean) -> Unit = { modifier, compact ->
        TrackPane(
            title = song.title,
            subtitle = song.artist,
            source = song.appName,
            art = song.art,
            isPlaying = song.isPlaying,
            position = position,
            duration = song.durationMs,
            canSeek = song.canSeek,
            onSeek = tracker::seekTo,
            onPlayPause = tracker::playPause,
            onPrevious = tracker::previous,
            onNext = tracker::next,
            compact = compact,
            modifier = modifier,
            extra = timing,
        )
    }

    val chooseFolder: @Composable () -> Unit = { BigButton("Choose folder", onPickFolder) }

    val panel: @Composable (Modifier) -> Unit = { modifier ->
        LyricsPanel(
            lyrics = l,
            position = lyricPosition,
            onSeek = if (song.canSeek) { t -> tracker.seekTo(t - offsetMs) } else null,
            emptyTitle = emptyTitle,
            emptyBody = emptyBody,
            modifier = modifier,
            emptyAction = if (!hasFolder) chooseFolder else null,
        )
    }

    if (ui.wide) {
        Row(Modifier.fillMaxSize()) {
            track(Modifier.weight(0.4f).fillMaxHeight(), false)
            Spacer(Modifier.width(ui.pad))
            panel(Modifier.weight(0.6f).fillMaxHeight())
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            track(Modifier.fillMaxWidth(), true)
            Spacer(Modifier.height(14.dp))
            panel(Modifier.weight(1f).fillMaxWidth())
        }
    }
}

/** Lets the user nudge lyrics earlier or later if their file is slightly off. */
@Composable
private fun TimingRow(offsetMs: Long, onChange: (Long) -> Unit) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        Text("Lyrics timing", fontSize = ui.small, color = cs.onSurfaceVariant)
        Pill("−0.5s", color = cs.onSurfaceVariant, onClick = { onChange(offsetMs - 500) })
        Text(
            when {
                offsetMs == 0L -> "0.0s"
                offsetMs > 0 -> "+%.1fs".format(offsetMs / 1000f)
                else -> "%.1fs".format(offsetMs / 1000f)
            },
            fontSize = ui.small,
            fontWeight = FontWeight.SemiBold,
        )
        Pill("+0.5s", color = cs.onSurfaceVariant, onClick = { onChange(offsetMs + 500) })
    }
}
