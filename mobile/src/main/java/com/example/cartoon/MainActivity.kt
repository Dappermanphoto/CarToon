package com.example.cartoon

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PREFS = "cartoon"
private const val KEY_FOLDER = "folder_uri"

class MainActivity : ComponentActivity() {

    private lateinit var player: ExoPlayer
    private lateinit var tracker: NowPlayingTracker
    private lateinit var spotify: SpotifyController

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        player = ExoPlayer.Builder(this).build()
        tracker = NowPlayingTracker(this)
        spotify = SpotifyController(this)
        setContent { CarToonApp(player, tracker, spotify) }
    }

    override fun onStart() {
        super.onStart()
        if (NowPlayingTracker.hasAccess(this)) tracker.start()
        if (spotify.wantConnected) spotify.connect(this)
    }

    // Android Auto hides parked apps when the car starts moving; stop playback with it.
    override fun onStop() {
        super.onStop()
        player.pause()
        tracker.stop()
        spotify.disconnect()
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
    }
}

private enum class Tab(val label: String) {
    LIBRARY("Library"), NOW_PLAYING("Now Playing"), SPOTIFY("Spotify"), YOUTUBE("YouTube"), SETTINGS("Settings"),
}

private fun Tab.icon(): ImageVector = when (this) {
    Tab.LIBRARY -> AppIcons.Library
    Tab.NOW_PLAYING -> AppIcons.Lyrics
    Tab.SPOTIFY -> AppIcons.Note
    Tab.YOUTUBE -> AppIcons.Video
    Tab.SETTINGS -> AppIcons.Settings
}

@Composable
fun CarToonApp(player: ExoPlayer, tracker: NowPlayingTracker, spotify: SpotifyController) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val car = rememberIsCarScreen()
    val settingsStore = remember { SettingsStore(context) }
    var settings by remember { mutableStateOf(settingsStore.load()) }
    val theme = remember(settings) { settings.toTheme() }
    val youTube = remember { YouTubeState() }
    var playingVideo by remember { mutableStateOf<YtVideo?>(null) }

    var folder by remember { mutableStateOf(prefs.getString(KEY_FOLDER, null)?.let(Uri::parse)) }
    var library by remember { mutableStateOf(Library.EMPTY) }
    var loading by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var queue by remember { mutableStateOf<List<MediaEntry>?>(null) }
    // On the car screen, open straight to lyrics for whatever is playing.
    var tab by remember { mutableStateOf(if (car && settings.carOpensNowPlaying) Tab.NOW_PLAYING else Tab.LIBRARY) }

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
        library = withContext(Dispatchers.IO) {
            runCatching { MediaLibrary.scan(context, f) }.getOrDefault(Library.EMPTY)
        }
        loading = false
    }

    // Light or dark status bar icons to match the theme
    SideEffect {
        val transparent = android.graphics.Color.TRANSPARENT
        (context as? ComponentActivity)?.enableEdgeToEdge(
            statusBarStyle = if (theme.isDark) SystemBarStyle.dark(transparent)
            else SystemBarStyle.light(transparent, transparent),
            navigationBarStyle = if (theme.isDark) SystemBarStyle.dark(transparent)
            else SystemBarStyle.light(transparent, transparent),
        )
    }

    MaterialTheme(colorScheme = theme.colors) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(theme.background)
        ) {
            val ui = UiScale(
                car = car,
                wide = car || maxWidth > maxHeight || maxWidth >= 840.dp,
                short = maxHeight < 560.dp,
                lyricScale = settings.lyricSize.scale,
            )
            CompositionLocalProvider(
                LocalUi provides ui,
                // Default text/icon color for the theme (otherwise plain text shows up black).
                LocalContentColor provides theme.colors.onBackground,
            ) {
                val q = queue
                val video = playingVideo
                if (video != null) {
                    YouTubePlayerScreen(video, onBack = { playingVideo = null })
                } else if (q != null) {
                    PlayerScreen(
                        player = player,
                        queue = q,
                        onBack = {
                            player.stop()
                            player.clearMediaItems()
                            queue = null
                        },
                    )
                } else {
                    MainScaffold(tab, onTab = { tab = it }) {
                        when (tab) {
                            Tab.LIBRARY -> LibraryScreen(
                                hasFolder = folder != null,
                                loading = loading,
                                entries = library.media,
                                onPickFolder = { folderPicker.launch(null) },
                                onRefresh = { reloadKey++ },
                                onPlay = { clicked ->
                                    val sameKind = library.media.filter { it.kind == clicked.kind }
                                    val start = sameKind.indexOf(clicked).coerceAtLeast(0)
                                    player.setMediaItems(sameKind.map { MediaItem.fromUri(it.uri) }, start, 0L)
                                    player.prepare()
                                    player.play()
                                    queue = sameKind
                                },
                            )

                            Tab.NOW_PLAYING -> NowPlayingScreen(
                                tracker = tracker,
                                library = library,
                                hasFolder = folder != null,
                                onPickFolder = { folderPicker.launch(null) },
                            )

                            Tab.SPOTIFY -> SpotifyScreen(
                                spotify = spotify,
                                clientId = settings.spotifyClientId,
                                onOpenSettings = { tab = Tab.SETTINGS },
                            )

                            Tab.YOUTUBE -> YouTubeScreen(
                                state = youTube,
                                apiKey = settings.youtubeKey,
                                onOpenSettings = { tab = Tab.SETTINGS },
                                onPlay = {
                                    player.pause()
                                    playingVideo = it
                                },
                            )

                            Tab.SETTINGS -> SettingsScreen(
                                settings = settings,
                                onChange = {
                                    settings = it
                                    settingsStore.save(it)
                                },
                                folder = folder,
                                onPickFolder = { folderPicker.launch(null) },
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Side rail on wide screens (car, landscape); bottom bar on a phone held upright. */
@Composable
private fun MainScaffold(tab: Tab, onTab: (Tab) -> Unit, content: @Composable () -> Unit) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    if (ui.wide) {
        Row(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(
                Modifier
                    .fillMaxHeight()
                    .width(ui.railWidth)
                    .padding(vertical = if (ui.short) 6.dp else 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (ui.short) Arrangement.SpaceEvenly else Arrangement.Top,
            ) {
                if (!ui.short) {
                    Logo()
                    Spacer(Modifier.height(24.dp))
                }
                Tab.entries.forEach { t ->
                    NavItem(t, t == tab, { onTab(t) }, Modifier.padding(vertical = if (ui.short) 0.dp else 4.dp))
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight().padding(top = if (ui.short) 4.dp else 12.dp)) { content() }
        }
    } else {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(top = 8.dp)) { content() }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(cs.surface)
                    .padding(vertical = 4.dp),
            ) {
                Tab.entries.forEach { t ->
                    NavItem(t, t == tab, { onTab(t) }, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun NavItem(tab: Tab, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(vertical = if (ui.short) 4.dp else 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(if (selected) cs.primary.copy(alpha = 0.18f) else Color.Transparent)
                .padding(horizontal = if (ui.short) 16.dp else 20.dp, vertical = if (ui.short) 4.dp else 6.dp),
        ) {
            Icon(
                tab.icon(),
                contentDescription = tab.label,
                tint = if (selected) cs.primary else cs.onSurfaceVariant,
                modifier = Modifier.size(ui.navIcon),
            )
        }
        Spacer(Modifier.height(if (ui.short) 2.dp else 4.dp))
        Text(
            tab.label,
            fontSize = ui.small,
            color = if (selected) cs.onSurface else cs.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
        )
    }
}

@Composable
private fun Logo() {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(50.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(Brush.linearGradient(listOf(cs.primary, Color(0xFFFF7A59)))),
        contentAlignment = Alignment.Center,
    ) {
        Text("CT", fontSize = 20.sp, fontWeight = FontWeight.Black, color = Color(0xFF1A1206))
    }
}
