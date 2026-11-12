package com.example.cartoon

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Plays the user's own videos and songs from their CarToon folder. */
@Composable
fun PlayerScreen(player: ExoPlayer, queue: List<MediaEntry>, onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current

    var index by remember { mutableIntStateOf(player.currentMediaItemIndex.coerceIn(0, queue.lastIndex)) }
    var isPlaying by remember { mutableStateOf(player.isPlaying) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(0L) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                index = player.currentMediaItemIndex.coerceIn(0, queue.lastIndex)
            }

            override fun onIsPlayingChanged(playing: Boolean) {
                isPlaying = playing
            }
        }
        player.addListener(listener)
        view.keepScreenOn = true
        onDispose {
            player.removeListener(listener)
            view.keepScreenOn = false
        }
    }

    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            duration = player.duration.coerceAtLeast(0L)
            delay(100)
        }
    }

    val entry = queue[index]
    var lyrics by remember(entry.uri) { mutableStateOf<Lyrics?>(null) }
    LaunchedEffect(entry.uri) {
        lyrics = entry.lyricsUri?.let { uri ->
            withContext(Dispatchers.IO) { MediaLibrary.readText(context, uri)?.let(Lyrics::parse) }
        }
    }

    BackHandler(onBack = onBack)

    if (entry.kind == MediaKind.VIDEO) {
        VideoPlayer(player, entry, lyrics, position, onBack)
    } else {
        MusicPlayer(player, entry, lyrics, isPlaying, position, duration, index < queue.lastIndex, onBack)
    }
}

@Composable
private fun VideoPlayer(
    player: ExoPlayer,
    entry: MediaEntry,
    lyrics: Lyrics?,
    position: Long,
    onBack: () -> Unit,
) {
    val ui = LocalUi.current
    var showLyrics by remember { mutableStateOf(true) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).also { v ->
                    v.player = player
                    v.useController = true
                    v.keepScreenOn = true
                }
            },
            onRelease = { v -> v.player = null },
            modifier = Modifier.fillMaxSize(),
        )

        Row(
            Modifier
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIconButton(AppIcons.Back, "Back", ui.iconButton, onBack)
            Spacer(Modifier.width(14.dp))
            Text(
                entry.title,
                fontSize = ui.body,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (lyrics != null && lyrics.synced) {
                Pill(
                    if (showLyrics) "Lyrics on" else "Lyrics off",
                    selected = showLyrics,
                    onClick = { showLyrics = !showLyrics },
                )
            }
        }

        val l = lyrics
        if (showLyrics && l != null && l.synced) {
            val i = l.indexAt(position)
            val line = if (i >= 0) l.lines[i].text else ""
            if (line.isNotBlank()) {
                Text(
                    line,
                    fontSize = ui.lyric,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 120.dp, start = 32.dp, end = 32.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xB3000000))
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                )
            }
        }
    }
}

@Composable
private fun MusicPlayer(
    player: ExoPlayer,
    entry: MediaEntry,
    lyrics: Lyrics?,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    hasNext: Boolean,
    onBack: () -> Unit,
) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = ui.pad, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(AppIcons.Back, "Back to library", ui.iconButton, onBack)
            Spacer(Modifier.width(14.dp))
            Text("Library", fontSize = ui.body, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))

        val track: @Composable (Modifier, Boolean) -> Unit = { modifier, compact ->
            TrackPane(
                title = entry.title,
                subtitle = "From your library",
                source = null,
                art = null,
                isPlaying = isPlaying,
                position = position,
                duration = duration,
                canSeek = true,
                onSeek = { player.seekTo(it) },
                onPlayPause = { if (isPlaying) player.pause() else player.play() },
                onPrevious = { player.seekToPrevious() },
                onNext = { player.seekToNextMediaItem() },
                compact = compact,
                modifier = modifier,
                hasNext = hasNext,
            )
        }
        val panel: @Composable (Modifier) -> Unit = { modifier ->
            LyricsPanel(
                lyrics = lyrics,
                position = position,
                onSeek = { player.seekTo(it) },
                emptyTitle = "No lyrics for this song",
                emptyBody = "Add a .lrc or .txt file named \"${entry.title}\" next to the song.",
                modifier = modifier,
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
}
