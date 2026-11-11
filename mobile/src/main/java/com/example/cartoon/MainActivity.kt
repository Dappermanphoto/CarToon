package com.example.cartoon

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val PREFS = "cartoon"
private const val KEY_FOLDER = "folder_uri"

private val CarToonColors = darkColorScheme(
    primary = Color(0xFFFFB547),
    onPrimary = Color(0xFF2A1700),
    secondary = Color(0xFF7FD4FF),
    background = Color(0xFF0E0F12),
    onBackground = Color(0xFFF2F2F5),
    surface = Color(0xFF181A1F),
    onSurface = Color(0xFFF2F2F5),
    surfaceVariant = Color(0xFF23262D),
    onSurfaceVariant = Color(0xFFB4B8C2),
)

class MainActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        player = ExoPlayer.Builder(this).build()
        setContent { CarToonApp(player) }
    }

    // Android Auto hides parked apps when the car starts moving; stop playback with it.
    override fun onStop() {
        super.onStop()
        player.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }
}

@Composable
fun CarToonApp(player: ExoPlayer) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

    var folder by remember { mutableStateOf(prefs.getString(KEY_FOLDER, null)?.let(Uri::parse)) }
    var entries by remember { mutableStateOf<List<MediaEntry>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var queue by remember { mutableStateOf<List<MediaEntry>?>(null) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            prefs.edit().putString(KEY_FOLDER, uri.toString()).apply()
            folder = uri
        }
    }

    LaunchedEffect(folder, reloadKey) {
        val f = folder ?: return@LaunchedEffect
        loading = true
        entries = withContext(Dispatchers.IO) {
            runCatching { MediaLibrary.scan(context, f) }.getOrDefault(emptyList())
        }
        loading = false
    }

    MaterialTheme(colorScheme = CarToonColors) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            val q = queue
            if (q == null) {
                LibraryScreen(
                    hasFolder = folder != null,
                    loading = loading,
                    entries = entries,
                    onPickFolder = { folderPicker.launch(null) },
                    onRefresh = { reloadKey++ },
                    onPlay = { clicked ->
                        val sameKind = entries.filter { it.kind == clicked.kind }
                        val start = sameKind.indexOf(clicked).coerceAtLeast(0)
                        player.setMediaItems(sameKind.map { MediaItem.fromUri(it.uri) }, start, 0L)
                        player.prepare()
                        player.play()
                        queue = sameKind
                    },
                )
            } else {
                PlayerScreen(
                    player = player,
                    queue = q,
                    onBack = {
                        player.stop()
                        player.clearMediaItems()
                        queue = null
                    },
                )
            }
        }
    }
}

/* ------------------------------ Library ------------------------------ */

@Composable
private fun LibraryScreen(
    hasFolder: Boolean,
    loading: Boolean,
    entries: List<MediaEntry>,
    onPickFolder: () -> Unit,
    onRefresh: () -> Unit,
    onPlay: (MediaEntry) -> Unit,
) {
    var filter by remember { mutableStateOf<MediaKind?>(null) }
    val shown = if (filter == null) entries else entries.filter { it.kind == filter }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "CarToon",
                fontSize = 30.sp,
                fontWeight = FontWeight.Black,
                color = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(20.dp))
            if (hasFolder) {
                KindChip("All", filter == null) { filter = null }
                KindChip("Videos", filter == MediaKind.VIDEO) { filter = MediaKind.VIDEO }
                KindChip("Music", filter == MediaKind.AUDIO) { filter = MediaKind.AUDIO }
            }
            Spacer(Modifier.weight(1f))
            if (hasFolder) {
                TextButton(onClick = onRefresh) { Text("Refresh", fontSize = 18.sp) }
                FilledTonalButton(onClick = onPickFolder) { Text("Change folder", fontSize = 18.sp) }
            }
        }
        Spacer(Modifier.height(12.dp))

        when {
            !hasFolder -> EmptyMessage(
                title = "Pick your media folder",
                body = "Choose a folder on your phone with videos, music and lyrics. " +
                    "Lyrics are .lrc or .txt files with the same name as the song.",
                button = "Choose folder",
                onClick = onPickFolder,
            )

            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }

            shown.isEmpty() -> EmptyMessage(
                title = "Nothing here yet",
                body = "No videos or music were found in this folder.",
                button = "Choose another folder",
                onClick = onPickFolder,
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 240.dp),
                contentPadding = PaddingValues(bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                itemsIndexed(shown, key = { _, e -> e.uri.toString() }) { _, entry ->
                    MediaCard(entry) { onPlay(entry) }
                }
            }
        }
    }
}

@Composable
private fun KindChip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label, fontSize = 17.sp) },
        modifier = Modifier.padding(end = 8.dp),
    )
}

@Composable
private fun MediaCard(entry: MediaEntry, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp),
    ) {
        Row(
            Modifier
                .fillMaxSize()
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val isVideo = entry.kind == MediaKind.VIDEO
            Box(
                Modifier
                    .size(64.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isVideo) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary),
                contentAlignment = Alignment.Center,
            ) {
                Text(if (isVideo) "▶" else "♪", fontSize = 30.sp, color = Color(0xFF15161A))
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    entry.title,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (entry.lyricsUri != null) {
                    Text("Lyrics", fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun EmptyMessage(title: String, body: String, button: String, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            fontSize = 18.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(0.7f),
        )
        Spacer(Modifier.height(20.dp))
        Button(onClick = onClick, modifier = Modifier.height(56.dp)) {
            Text(button, fontSize = 20.sp)
        }
    }
}

/* ------------------------------ Player ------------------------------ */

@Composable
private fun PlayerScreen(player: ExoPlayer, queue: List<MediaEntry>, onBack: () -> Unit) {
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
            delay(200)
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
        MusicPlayer(player, entry, lyrics, isPlaying, position, duration, hasNext = index < queue.lastIndex, onBack)
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

        // Top bar: back, title, lyrics toggle
        Row(
            Modifier
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OverlayButton("← Back", onBack)
            Spacer(Modifier.width(12.dp))
            Text(
                entry.title,
                fontSize = 18.sp,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            if (lyrics != null && lyrics.lines.isNotEmpty()) {
                OverlayButton(if (showLyrics) "Hide lyrics" else "Show lyrics") { showLyrics = !showLyrics }
            }
        }

        // Current lyric line as a caption above the player controls
        val l = lyrics
        if (showLyrics && l != null && l.synced) {
            val i = l.indexAt(position)
            val line = if (i >= 0) l.lines[i].text else ""
            if (line.isNotBlank()) {
                Text(
                    line,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 110.dp, start = 24.dp, end = 24.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xB3000000))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun OverlayButton(label: String, onClick: () -> Unit) {
    Text(
        label,
        fontSize = 18.sp,
        color = Color.White,
        modifier = Modifier
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0x99000000))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
    )
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
    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(16.dp)
    ) {
        val wide = maxWidth > maxHeight
        if (wide) {
            Row(Modifier.fillMaxSize()) {
                NowPlaying(player, entry, isPlaying, position, duration, hasNext, onBack, Modifier.weight(0.42f).fillMaxHeight())
                Spacer(Modifier.width(16.dp))
                LyricsPanel(lyrics, position, onSeek = { player.seekTo(it) }, Modifier.weight(0.58f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                NowPlaying(player, entry, isPlaying, position, duration, hasNext, onBack, Modifier.fillMaxWidth())
                Spacer(Modifier.height(16.dp))
                LyricsPanel(lyrics, position, onSeek = { player.seekTo(it) }, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun NowPlaying(
    player: ExoPlayer,
    entry: MediaEntry,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    hasNext: Boolean,
    onBack: () -> Unit,
    modifier: Modifier,
) {
    var dragging by remember { mutableStateOf<Float?>(null) }

    Column(modifier, verticalArrangement = Arrangement.Center) {
        TextButton(onClick = onBack) { Text("← Library", fontSize = 18.sp) }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.secondary),
            contentAlignment = Alignment.Center,
        ) {
            Text("♪", fontSize = 56.sp, color = Color(0xFF15161A))
        }
        Spacer(Modifier.height(16.dp))
        Text(entry.title, fontSize = 24.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Spacer(Modifier.height(12.dp))

        val max = duration.coerceAtLeast(1L).toFloat()
        Slider(
            value = (dragging ?: position.toFloat()).coerceIn(0f, max),
            onValueChange = { dragging = it },
            onValueChangeFinished = {
                dragging?.let { player.seekTo(it.toLong()) }
                dragging = null
            },
            valueRange = 0f..max,
        )
        Row(Modifier.fillMaxWidth()) {
            Text(formatTime(position), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.weight(1f))
            Text(formatTime(duration), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ControlButton("⏮") { player.seekToPreviousMediaItem() }
            ControlButton("−10") { player.seekTo((player.currentPosition - 10_000).coerceAtLeast(0L)) }
            Button(
                onClick = { if (isPlaying) player.pause() else player.play() },
                shape = CircleShape,
                modifier = Modifier.size(76.dp),
                contentPadding = PaddingValues(0.dp),
            ) {
                Text(if (isPlaying) "❚❚" else "▶", fontSize = 28.sp)
            }
            ControlButton("+10") { player.seekTo(player.currentPosition + 10_000) }
            ControlButton("⏭", enabled = hasNext) { player.seekToNextMediaItem() }
        }
    }
}

@Composable
private fun ControlButton(label: String, enabled: Boolean = true, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        enabled = enabled,
        shape = CircleShape,
        modifier = Modifier.size(60.dp),
        contentPadding = PaddingValues(0.dp),
    ) {
        Text(label, fontSize = 18.sp)
    }
}

@Composable
private fun LyricsPanel(lyrics: Lyrics?, position: Long, onSeek: (Long) -> Unit, modifier: Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp, vertical = 12.dp)
    ) {
        if (lyrics == null || lyrics.lines.isEmpty()) {
            Text(
                "No lyrics for this song.\nAdd a .lrc or .txt file with the same name next to it.",
                fontSize = 18.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }

        val listState = rememberLazyListState()
        val current = lyrics.indexAt(position)

        LaunchedEffect(current) {
            if (current >= 0) listState.animateScrollToItem((current - 2).coerceAtLeast(0))
        }

        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(lyrics.lines) { i, line ->
                val active = i == current
                Text(
                    line.text.ifBlank { "♪" },
                    fontSize = if (active) 28.sp else 22.sp,
                    fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                    color = when {
                        !lyrics.synced -> MaterialTheme.colorScheme.onSurface
                        active -> MaterialTheme.colorScheme.primary
                        i < current -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (lyrics.synced) Modifier.clickable { onSeek(line.timeMs) } else Modifier)
                        .padding(vertical = 8.dp),
                )
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    return "%d:%02d".format(total / 60, total % 60)
}
