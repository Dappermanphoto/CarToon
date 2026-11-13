package com.example.cartoon

import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
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
    private lateinit var obd: ObdManager
    private lateinit var modules: ObdModules

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        player = ExoPlayer.Builder(this).build()
        tracker = NowPlayingTracker(this)
        spotify = SpotifyController(this)
        obd = ObdManager(this)
        modules = ObdModules(this, obd)
        setContent { CarToonApp(player, tracker, spotify, obd, modules) }
    }

    override fun onStart() {
        super.onStart()
        if (NowPlayingTracker.hasAccess(this)) tracker.start()
        if (spotify.wantConnected) spotify.connect(this)
        obd.polling = true
    }

    // Android Auto hides parked apps when the car starts moving; stop playback with it.
    override fun onStop() {
        super.onStop()
        player.pause()
        tracker.stop()
        spotify.disconnect()
        obd.polling = false
    }

    override fun onDestroy() {
        super.onDestroy()
        player.release()
        modules.stop()
        obd.disconnect()
    }
}

private enum class Tab(val label: String) {
    NOW_PLAYING("Playing"), SPOTIFY("Spotify"), YOUTUBE("YouTube"), OBD("OBD"),
    PROFILE("Profile"), SETTINGS("Settings"),
}

private fun Tab.icon(): ImageVector = when (this) {
    Tab.NOW_PLAYING -> AppIcons.Lyrics
    Tab.SPOTIFY -> AppIcons.Note
    Tab.YOUTUBE -> AppIcons.Video
    Tab.OBD -> AppIcons.Gauge
    Tab.PROFILE -> PersonIcon
    Tab.SETTINGS -> AppIcons.Settings
}

/** Tabs for other apps show that app's own icon when it's installed. */
private fun Tab.appPackage(): String? = when (this) {
    Tab.SPOTIFY -> "com.spotify.music"
    Tab.YOUTUBE -> "com.google.android.youtube"
    else -> null
}

@Composable
fun CarToonApp(player: ExoPlayer, tracker: NowPlayingTracker, spotify: SpotifyController, obd: ObdManager, modules: ObdModules) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }
    val car = rememberIsCarScreen()
    val settingsStore = remember { SettingsStore(context) }
    var settings by remember { mutableStateOf(settingsStore.load()) }
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val theme = remember(settings, configuration) { settings.toTheme(context) }
    val youTube = remember { YouTubeState() }
    val wallpapers = remember { WallpaperStore(context) }
    val profile = remember { ProfileStore(context) }
    val parked = modules.parked
    val landscapeScreen = configuration.screenWidthDp > configuration.screenHeightDp
    var playingVideo by remember { mutableStateOf<YtVideo?>(null) }

    var folder by remember { mutableStateOf(prefs.getString(KEY_FOLDER, null)?.let(Uri::parse)) }
    var library by remember { mutableStateOf(Library.EMPTY) }
    var loading by remember { mutableStateOf(false) }
    var reloadKey by remember { mutableIntStateOf(0) }
    var queue by remember { mutableStateOf<List<MediaEntry>?>(null) }
    // On the car screen, open straight to lyrics for whatever is playing.
    var tab by remember { mutableStateOf(Tab.NOW_PLAYING) }

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
        // On the car screen, leave Android Auto's own bars (time, signal) alone.
        if (car) return@SideEffect
        val transparent = android.graphics.Color.TRANSPARENT
        (context as? ComponentActivity)?.enableEdgeToEdge(
            statusBarStyle = if (theme.isDark) SystemBarStyle.dark(transparent)
            else SystemBarStyle.light(transparent, transparent),
            navigationBarStyle = if (theme.isDark) SystemBarStyle.dark(transparent)
            else SystemBarStyle.light(transparent, transparent),
        )
    }

    MaterialTheme(colorScheme = wallpapers.colors(theme.colors, landscapeScreen)) {
        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .background(theme.background)
        ) {
            // Your own photo, if you picked one in Settings
            WallpaperLayer(wallpapers, landscapeScreen, Modifier.fillMaxSize())
            val wideLayout = car || maxWidth > maxHeight || maxWidth >= 840.dp
            val ui = UiScale(
                car = car,
                wide = wideLayout,
                short = maxHeight < 560.dp,
                lyricScale = settings.lyricSize.scale,
                // Scale from the screen's real size: height on wide screens, width on a phone held upright.
                f = if (wideLayout) (maxHeight.value / 600f).coerceIn(0.5f, 1.25f)
                else (maxWidth.value / 411f).coerceIn(0.85f, 1.2f),
            )
            CompositionLocalProvider(
                LocalUi provides ui,
                // Default text/icon color for the theme (otherwise plain text shows up black).
                LocalContentColor provides theme.colors.onBackground,
                LocalGaugeColor provides modules.gaugeStyle.color,
                LocalVehicle provides profile,
            ) {
                val q = queue
                val video = playingVideo
                val locked = parked.blocked
                val videoQueue = q?.firstOrNull()?.kind == MediaKind.VIDEO
                // Stop local video as soon as the driving lock engages
                LaunchedEffect(locked, videoQueue) { if (locked && videoQueue) player.pause() }
                if (locked && (video != null || videoQueue)) {
                    ParkedOnly(
                        Modifier.safeDrawingPadding(),
                        onClose = {
                            playingVideo = null
                            if (queue != null) {
                                player.stop()
                                player.clearMediaItems()
                                queue = null
                            }
                        },
                    )
                } else if (video != null) {
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
                        Crossfade(targetState = tab, animationSpec = tween(220), label = "tab") { current ->
                        when (current) {
                            Tab.NOW_PLAYING -> NowPlayingScreen(
                                tracker = tracker,
                                library = library,
                                hasFolder = folder != null,
                                onPickFolder = { folderPicker.launch(null) },
                                onlineLyrics = settings.onlineLyrics,
                            )

                            Tab.SPOTIFY -> SpotifyScreen(
                                spotify = spotify,
                                clientId = settings.spotifyClientId,
                                onOpenSettings = { tab = Tab.SETTINGS },
                            )

                            Tab.YOUTUBE -> if (locked) ParkedOnly() else YouTubeScreen(
                                state = youTube,
                                apiKey = settings.youtubeKey,
                                onOpenSettings = { tab = Tab.SETTINGS },
                                onPlay = {
                                    player.pause()
                                    playingVideo = it
                                },
                            )

                            Tab.OBD -> ObdScreen(
                                obd = obd,
                                settings = settings,
                                onOpenSettings = { tab = Tab.SETTINGS },
                                modules = modules,
                                tracker = tracker,
                            )

                            Tab.PROFILE -> ProfileScreen(profile, settings.metricUnits, obd.inDemo)

                            Tab.SETTINGS -> SettingsScreen(
                                extra = {
                                    AdvancedSettingsSections(modules)
                                    WallpaperSection(wallpapers)
                                    ParkedSection(parked)
                                },
                                onSecretTap = parked::secretTap,
                                obd = obd,
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
                // Low-voltage warning floats over every screen
                VoltageBanner(
                    modules.voltage,
                    Modifier.align(Alignment.TopCenter).safeDrawingPadding(),
                )
                OverheatBanner(
                    modules.alerts,
                    settings.metricUnits,
                    Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(top = if (modules.voltage.low) 44.dp else 0.dp),
                )
                ServiceBanner(
                    modules.odometer,
                    Modifier.align(Alignment.TopCenter).safeDrawingPadding().padding(
                        top = (if (modules.voltage.low) 44.dp else 0.dp) + (if (modules.alerts.hot) 44.dp else 0.dp),
                    ),
                )
                if (!car) VinPrompt(obd, profile)
                ParkedTestBadge(
                    parked,
                    Modifier.align(Alignment.TopEnd).safeDrawingPadding().padding(top = 4.dp, end = 8.dp),
                )
                // Quietly checks GitHub when the app opens; only speaks up if there is an update.
                if (!car) UpdatePrompt()
                if (!car) WelcomeGate(onTryDemo = {
                    obd.startDemo()
                    tab = Tab.OBD
                })
            }
        }
    }
}

/** Icon-only side rail on wide screens (car, landscape); icon-only bottom bar on a phone held upright. */
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
                    .padding(vertical = if (ui.short) 4.dp else 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (!ui.short) {
                    Logo()
                    Spacer(Modifier.height(16.dp))
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val count = Tab.entries.size
                    val clockRoom = 26.dp
                    val fits = maxHeight >= ui.navTarget * count
                    val showClock = maxHeight >= ui.navTarget * count + clockRoom
                    if (fits) {
                        Column(
                            Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            Tab.entries.forEach { t -> NavItem(t, t == tab, { onTab(t) }, Modifier.fillMaxWidth()) }
                            if (showClock) RailClock()
                        }
                    } else {
                        // Very short screen: keep every button full size and let the rail scroll.
                        Column(
                            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            Tab.entries.forEach { t -> NavItem(t, t == tab, { onTab(t) }, Modifier.fillMaxWidth()) }
                        }
                    }
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

/** The time, drawn by CarToon (Android Auto's own clock isn't available to apps). */
@Composable
private fun RailClock() {
    val ui = LocalUi.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(15_000)
        }
    }
    val text = remember(now / 60_000) {
        java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(now))
    }
    Text(
        text,
        fontSize = ui.small,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
    )
}

/** Icon-only menu button. The whole button is the tap target; the name is kept for screen readers. */
@Composable
private fun NavItem(tab: Tab, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .height(ui.navTarget)
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .size(width = ui.navTarget * 1.25f, height = ui.navTarget * 0.72f)
                .clip(RoundedCornerShape(50))
                .background(
                    animateColorAsState(
                        if (selected) cs.primary.copy(alpha = 0.18f) else Color.Transparent,
                        tween(240), label = "navPill",
                    ).value
                ),
            contentAlignment = Alignment.Center,
        ) {
            val appIcon = tab.appPackage()?.let { rememberAppIcon(it) }
            if (appIcon != null) {
                Image(
                    appIcon,
                    contentDescription = tab.label,
                    alpha = if (selected) 1f else 0.75f,
                    modifier = Modifier.size(ui.navIcon).clip(RoundedCornerShape(6.dp)),
                )
            } else {
                Icon(
                    tab.icon(),
                    contentDescription = tab.label,
                    tint = if (selected) cs.primary else cs.onSurfaceVariant,
                    modifier = Modifier.size(ui.navIcon),
                )
            }
        }
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
