package com.example.cartoon

import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.WindowInsets
import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.LruCache
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spotify.android.appremote.api.ConnectionParams
import com.spotify.android.appremote.api.Connector
import com.spotify.android.appremote.api.ContentApi
import com.spotify.android.appremote.api.SpotifyAppRemote
import com.spotify.protocol.client.Subscription
import com.spotify.protocol.types.Image
import com.spotify.protocol.types.ImageUri
import com.spotify.protocol.types.ListItem
import com.spotify.protocol.types.PlayerContext
import com.spotify.protocol.types.PlayerState
import com.spotify.sdk.android.auth.AuthorizationClient
import com.spotify.sdk.android.auth.AuthorizationRequest
import com.spotify.sdk.android.auth.AuthorizationResponse
import kotlinx.coroutines.delay
import java.security.MessageDigest

/** Must match the Redirect URI in the Spotify developer dashboard. The Client ID is entered in Settings. */
const val SPOTIFY_REDIRECT_URI = "cartoon://callback"

data class SpotifyTrack(
    val title: String,
    val artist: String,
    val uri: String,
    val durationMs: Long,
    val isPaused: Boolean,
    private val basePosition: Long,
    private val baseTime: Long,
    private val speed: Float,
) {
    fun positionAt(now: Long = SystemClock.elapsedRealtime()): Long {
        val pos = if (isPaused) basePosition else basePosition + ((now - baseTime) * speed).toLong()
        return if (durationMs > 0) pos.coerceIn(0L, durationMs) else pos.coerceAtLeast(0L)
    }
}

/**
 * Talks to the Spotify app on the phone through Spotify's App Remote SDK.
 * Music plays through Spotify (Premium needed) while CarToon is the screen and the controls.
 */
@Stable
class SpotifyController(context: Context) {
    enum class Status { DISCONNECTED, CONNECTING, CONNECTED }

    private val appContext = context.applicationContext
    private var remote: SpotifyAppRemote? = null
    private var stateSub: Subscription<PlayerState>? = null
    private var artFor: String? = null
    private val thumbs = LruCache<String, Bitmap>(120)

    /** True once the user has connected, so CarToon reconnects when it comes back. */
    var wantConnected = false
        private set
    private var clientId: String = ""
    private val prefs = appContext.getSharedPreferences("cartoon_spotify", Context.MODE_PRIVATE)

    /** True after the user has approved CarToon through "Sign in with Spotify". */
    var signedIn by mutableStateOf(prefs.getBoolean("signed_in", false))
        private set

    fun markSignedIn(value: Boolean) {
        signedIn = value
        prefs.edit().putBoolean("signed_in", value).apply()
    }

    fun reportError(message: String) {
        error = message
    }

    var status by mutableStateOf(Status.DISCONNECTED)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var track by mutableStateOf<SpotifyTrack?>(null)
        private set
    var art by mutableStateOf<Bitmap?>(null)
        private set
    var shuffle by mutableStateOf(false)
        private set

    /** 0 = off, 1 = repeat one, 2 = repeat all (Spotify's values). */
    var repeatMode by mutableStateOf(0)
        private set

    fun toggleShuffle() { remote?.playerApi?.toggleShuffle() }
    fun toggleRepeat() { remote?.playerApi?.toggleRepeat() }

    /** Web API access (your playlists, albums and liked songs with cover art). */
    val web = SpotifyWeb(appContext)

    /** Name of the playlist or album that's playing, when Spotify says. */
    var playingFrom by mutableStateOf<String?>(null)
        private set

    /** True when the current song is in your Liked Songs. */
    var liked by mutableStateOf(false)
        private set
    private var likedFor: String? = null
    private var contextSub: Subscription<PlayerContext>? = null

    fun playUri(uri: String) { remote?.playerApi?.play(uri) }
    fun queueUri(uri: String) { remote?.playerApi?.queue(uri) }
    fun seekBy(ms: Long) { remote?.playerApi?.seekToRelativePosition(ms) }

    fun toggleLiked() {
        val uri = track?.uri?.takeIf { it.isNotBlank() } ?: return
        val api = remote?.userApi ?: return
        if (liked) api.removeFromLibrary(uri).setResultCallback { liked = false }
        else api.addToLibrary(uri).setResultCallback { liked = true }
    }

    private fun refreshLiked(uri: String) {
        remote?.userApi?.getLibraryState(uri)?.setResultCallback { liked = it.isAdded }
    }

    val installed: Boolean get() = SpotifyAppRemote.isSpotifyInstalled(appContext)

    fun connect(context: Context, clientId: String = this.clientId) {
        if (status != Status.DISCONNECTED) return
        if (clientId.isBlank()) {
            error = "Add your Spotify Client ID in Settings first."
            return
        }
        this.clientId = clientId
        wantConnected = true
        status = Status.CONNECTING
        error = null
        val params = ConnectionParams.Builder(clientId)
            .setRedirectUri(SPOTIFY_REDIRECT_URI)
            .showAuthView(true)
            .build()
        SpotifyAppRemote.connect(context, params, object : Connector.ConnectionListener {
            override fun onConnected(appRemote: SpotifyAppRemote) {
                remote = appRemote
                status = Status.CONNECTED
                subscribe()
            }

            override fun onFailure(throwable: Throwable) {
                remote = null
                status = Status.DISCONNECTED
                error = friendlyError(throwable)
                // Approval missing or withdrawn: show the sign-in button again.
                val name = throwable.javaClass.simpleName
                if (name == "UserNotAuthorizedException" || name == "AuthenticationFailedException") {
                    markSignedIn(false)
                }
            }
        })
    }

    /** Called when CarToon goes into the background (Spotify asks apps to do this). */
    fun disconnect() {
        stateSub?.cancel()
        stateSub = null
        contextSub?.cancel()
        contextSub = null
        remote?.let { SpotifyAppRemote.disconnect(it) }
        remote = null
        status = Status.DISCONNECTED
    }

    fun forget() {
        wantConnected = false
        disconnect()
        track = null
        art = null
    }

    fun playPause() {
        val api = remote?.playerApi ?: return
        if (track?.isPaused != false) api.resume() else api.pause()
    }

    fun next() { remote?.playerApi?.skipNext() }
    fun previous() { remote?.playerApi?.skipPrevious() }
    fun seekTo(ms: Long) { remote?.playerApi?.seekTo(ms.coerceAtLeast(0L)) }

    fun loadRecommended(onResult: (List<ListItem>) -> Unit, onError: (String) -> Unit) {
        val api = remote?.contentApi ?: return onError("Not connected to Spotify")
        api.getRecommendedContentItems(ContentApi.ContentType.AUTOMOTIVE)
            .setResultCallback { onResult(it.items?.toList().orEmpty()) }
            .setErrorCallback { onError(friendlyError(it)) }
    }

    fun loadChildren(item: ListItem, onResult: (List<ListItem>) -> Unit, onError: (String) -> Unit) {
        val api = remote?.contentApi ?: return onError("Not connected to Spotify")
        api.getChildrenOfItem(item, 50, 0)
            .setResultCallback { onResult(it.items?.toList().orEmpty()) }
            .setErrorCallback { onError(friendlyError(it)) }
    }

    fun play(item: ListItem) {
        remote?.contentApi?.playContentItem(item)
    }

    fun thumbnail(uri: ImageUri, onResult: (Bitmap) -> Unit) {
        thumbs.get(uri.raw)?.let { return onResult(it) }
        remote?.imagesApi?.getImage(uri, Image.Dimension.MEDIUM)?.setResultCallback { bmp ->
            if (bmp != null) {
                thumbs.put(uri.raw, bmp)
                onResult(bmp)
            }
        }
    }

    private fun subscribe() {
        stateSub = remote?.playerApi?.subscribeToPlayerState()
        stateSub?.setEventCallback { ps -> onPlayerState(ps) }
        contextSub = remote?.playerApi?.subscribeToPlayerContext()
        contextSub?.setEventCallback { ctx -> playingFrom = ctx.title?.takeIf { it.isNotBlank() } }
    }

    private fun onPlayerState(ps: PlayerState) {
        ps.playbackOptions?.let {
            shuffle = it.isShuffling
            repeatMode = it.repeatMode
        }
        val t = ps.track
        if (t == null) {
            track = null
            return
        }
        val artists = t.artists?.mapNotNull { it.name }?.filter { it.isNotBlank() }.orEmpty()
        track = SpotifyTrack(
            title = t.name.orEmpty(),
            artist = artists.joinToString(", ").ifBlank { t.artist?.name.orEmpty() },
            uri = t.uri.orEmpty(),
            durationMs = t.duration,
            isPaused = ps.isPaused,
            basePosition = ps.playbackPosition,
            baseTime = SystemClock.elapsedRealtime(),
            speed = if (ps.playbackSpeed > 0f) ps.playbackSpeed else 1f,
        )
        val trackUri = t.uri.orEmpty()
        if (trackUri.isNotBlank() && trackUri != likedFor) {
            likedFor = trackUri
            refreshLiked(trackUri)
        }
        val image = t.imageUri
        if (image != null && image.raw != artFor) {
            artFor = image.raw
            remote?.imagesApi?.getImage(image, Image.Dimension.LARGE)?.setResultCallback { art = it }
        }
    }

    private fun friendlyError(t: Throwable): String = when (t.javaClass.simpleName) {
        "CouldNotFindSpotifyApp" -> "The Spotify app isn't installed on this phone."
        "NotLoggedInException" -> "Open the Spotify app and log in first."
        "UserNotAuthorizedException", "AuthenticationFailedException" ->
            "Spotify needs your approval. Tap Sign in with Spotify. If that fails too, check the " +
                "Spotify dashboard: package ${appContext.packageName}, the SHA1 from CarToon's Settings, and " +
                "your account under User Management."
        "UnsupportedFeatureVersionException" -> "Update the Spotify app, then try again."
        "OfflineModeException" -> "Spotify is in offline mode. Turn it off and try again."
        else -> "Couldn't connect to Spotify."
    } + (t.message?.takeIf { it.isNotBlank() }?.let { "\n\nSpotify says: $it" } ?: "")
}

/* ------------------------------ Screen ------------------------------ */

@Composable
fun SpotifyScreen(spotify: SpotifyController, clientId: String, onOpenSettings: () -> Unit) {
    val context = LocalContext.current
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val activity = context as? Activity

    // Spotify's own sign-in page; on success CarToon is approved and connects.
    val signIn = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val response = AuthorizationClient.getResponse(result.resultCode, result.data)
        when (response.type) {
            AuthorizationResponse.Type.TOKEN -> {
                spotify.markSignedIn(true)
                spotify.web.setToken(response.accessToken, response.expiresIn)
                spotify.connect(context, clientId)
            }
            AuthorizationResponse.Type.ERROR ->
                spotify.reportError("Spotify sign-in failed.\n\nSpotify says: ${response.error}")
            else -> spotify.reportError("Sign-in was cancelled.")
        }
    }
    val launchSignIn: () -> Unit = {
        if (activity != null) {
            val request = AuthorizationRequest.Builder(
                clientId,
                AuthorizationResponse.Type.TOKEN,
                SPOTIFY_REDIRECT_URI,
            ).setScopes(
                arrayOf(
                    "app-remote-control",
                    "playlist-read-private",
                    "playlist-read-collaborative",
                    "user-library-read",
                    "user-library-modify",
                    "user-read-playback-state",
                )
            ).build()
            signIn.launch(AuthorizationClient.createLoginActivityIntent(activity, request))
        }
    }
    val connectOrSignIn: () -> Unit = {
        if (spotify.signedIn || activity == null) spotify.connect(context, clientId) else launchSignIn()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = ui.pad, vertical = 12.dp)) {
        val keyboardUp = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        if (!(ui.short && ui.wide) && !keyboardUp) {
            Text("Spotify", fontSize = ui.headline, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(12.dp))
        }

        when {
            !spotify.installed -> EmptyState(
                icon = AppIcons.Note,
                title = "Spotify isn't installed",
                body = "Install the Spotify app on your phone and log in, then come back here.",
                modifier = Modifier.fillMaxSize(),
            )

            clientId.isBlank() -> EmptyState(
                icon = AppIcons.Note,
                title = "Add your Spotify Client ID",
                body = "Make a free app at developer.spotify.com/dashboard, then paste its Client ID " +
                    "into Settings on your phone.",
                modifier = Modifier.fillMaxSize(),
            ) { BigButton("Open Settings", onOpenSettings) }

            spotify.status == SpotifyController.Status.CONNECTING -> Box(
                Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator(color = cs.primary) }

            spotify.status == SpotifyController.Status.DISCONNECTED -> EmptyState(
                icon = AppIcons.Note,
                title = if (spotify.error == null) "Connect Spotify" else "Couldn't connect",
                body = spotify.error ?: "Control Spotify and browse its car playlists right here. " +
                    "The first time, Spotify asks you to approve CarToon on your phone.",
                modifier = Modifier.fillMaxSize(),
            ) {
                BigButton(
                    when {
                        !spotify.signedIn -> "Sign in with Spotify"
                        spotify.error == null -> "Connect"
                        else -> "Try again"
                    },
                    connectOrSignIn,
                )
            }

            else -> SpotifyConnected(spotify, launchSignIn)
        }
    }
}

@Composable
private fun SpotifyConnected(spotify: SpotifyController, onSignIn: () -> Unit) {
    val ui = LocalUi.current

    var now by remember { mutableLongStateOf(SystemClock.elapsedRealtime()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = SystemClock.elapsedRealtime()
            delay(200)
        }
    }

    val t = spotify.track
    val hero: @Composable (Modifier) -> Unit = { modifier ->
        MediaHero(
            title = t?.title ?: "Nothing playing",
            subtitle = t?.artist ?: "Pick something from the list",
            source = spotify.playingFrom,
            art = spotify.art,
            isPlaying = t != null && !t.isPaused,
            position = t?.positionAt(now) ?: 0L,
            duration = t?.durationMs ?: 0L,
            canSeek = t != null,
            onSeek = spotify::seekTo,
            onPlayPause = spotify::playPause,
            onPrevious = spotify::previous,
            onNext = spotify::next,
            modifier = modifier,
            actions = {
                val s = ui.iconButton * 0.88f
                CircleIconButton(
                    if (spotify.liked) HeartFilledIcon else HeartOutlineIcon,
                    if (spotify.liked) "Remove from Liked Songs" else "Add to Liked Songs",
                    s, { spotify.toggleLiked() }, primary = spotify.liked, enabled = t != null,
                )
                CircleIconButton(AppIcons.Rewind, "Back 15 seconds", s, { spotify.seekBy(-15_000) }, enabled = t != null)
                CircleIconButton(AppIcons.Shuffle, "Shuffle", s, { spotify.toggleShuffle() }, primary = spotify.shuffle)
                CircleIconButton(
                    if (spotify.repeatMode == 1) AppIcons.RepeatOne else AppIcons.Repeat,
                    "Repeat",
                    s, { spotify.toggleRepeat() }, primary = spotify.repeatMode != 0,
                )
                CircleIconButton(AppIcons.Forward, "Forward 15 seconds", s, { spotify.seekBy(15_000) }, enabled = t != null)
            },
        )
    }

    // The whole screen takes on the color of the album cover
    val cover = remember(spotify.art) { dominantColor(spotify.art) }
    val tint by androidx.compose.animation.animateColorAsState(
        (cover ?: MaterialTheme.colorScheme.primary).copy(alpha = 0.42f),
        androidx.compose.animation.core.tween(900),
        label = "albumTint",
    )
    Box(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(32.dp))
            .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(tint, androidx.compose.ui.graphics.Color.Transparent)))
            .padding(6.dp)
    ) {
    if (ui.wide) {
        Row(Modifier.fillMaxSize()) {
            hero(Modifier.weight(0.6f).fillMaxHeight())
            Spacer(Modifier.width(ui.pad))
            SpotifyLibrary(spotify, onSignIn, Modifier.weight(0.4f).fillMaxHeight())
        }
    } else {
        // Phone held upright: a compact player on top so the library gets the room. Tap it to
        // open the full player. While the keyboard is up, the player steps aside for the results.
        var expanded by remember { mutableStateOf(false) }
        val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
        Column(Modifier.fillMaxSize()) {
            if (expanded && !imeVisible) {
                hero(Modifier.weight(1f).fillMaxWidth())
                Spacer(Modifier.height(10.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    Pill("Show library", selected = true, onClick = { expanded = false })
                }
            } else {
                if (!imeVisible) {
                    SpotifyMini(spotify, now, onClick = { expanded = true })
                    Spacer(Modifier.height(10.dp))
                }
                SpotifyLibrary(spotify, onSignIn, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
    }
}

/** Compact player for the phone: cover, title, basic controls and the wavy seek bar. */
@Composable
private fun SpotifyMini(spotify: SpotifyController, now: Long, onClick: () -> Unit) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val t = spotify.track
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(cs.surface)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ArtTile(spotify.art, AppIcons.Note, 56.dp, cs.secondary)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    t?.title ?: "Nothing playing",
                    fontSize = ui.body,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    t?.artist ?: "Pick something below",
                    fontSize = ui.small,
                    color = cs.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val s = ui.iconButton * 0.9f
            CircleIconButton(AppIcons.Previous, "Previous", s, { spotify.previous() })
            Spacer(Modifier.width(6.dp))
            CircleIconButton(
                if (t != null && !t.isPaused) AppIcons.Pause else AppIcons.Play,
                if (t != null && !t.isPaused) "Pause" else "Play",
                s, { spotify.playPause() }, primary = true,
            )
            Spacer(Modifier.width(6.dp))
            CircleIconButton(AppIcons.Next, "Next", s, { spotify.next() })
        }
        Spacer(Modifier.height(2.dp))
        WavySeekBar(
            position = t?.positionAt(now) ?: 0L,
            duration = t?.durationMs ?: 0L,
            canSeek = t != null,
            playing = t != null && !t.isPaused,
            onSeek = spotify::seekTo,
        )
    }
}

/** Spotify's recommended car content; tap a folder to open it, tap a playlist or song to play it. */
@Composable
private fun BrowsePanel(spotify: SpotifyController, modifier: Modifier) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme

    var stack by remember { mutableStateOf<List<ListItem>>(emptyList()) }
    var items by remember { mutableStateOf<List<ListItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(stack) {
        items = null
        error = null
        val key = stack
        val onResult: (List<ListItem>) -> Unit = { if (stack == key) items = it }
        val onError: (String) -> Unit = { if (stack == key) error = it }
        val parent = stack.lastOrNull()
        if (parent == null) spotify.loadRecommended(onResult, onError)
        else spotify.loadChildren(parent, onResult, onError)
    }

    Column(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(cs.surface)
            .padding(vertical = 10.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (stack.isNotEmpty()) {
                CircleIconButton(AppIcons.Back, "Back", ui.iconButton * 0.85f, { stack = stack.dropLast(1) })
                Spacer(Modifier.width(10.dp))
            }
            Text(
                stack.lastOrNull()?.title ?: "For the car",
                fontSize = ui.title,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val list = items
        when {
            error != null -> EmptyState(AppIcons.Note, "Couldn't load", error.orEmpty(), Modifier.fillMaxSize())
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = cs.primary)
            }
            list.isEmpty() -> EmptyState(AppIcons.Note, "Nothing here", "Spotify didn't return anything.", Modifier.fillMaxSize())
            else -> LazyColumn(
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(list) { item ->
                    BrowseRow(spotify, item) {
                        if (item.hasChildren) stack = stack + item
                        else if (item.playable) spotify.play(item)
                    }
                }
            }
        }
    }
}

@Composable
private fun BrowseRow(spotify: SpotifyController, item: ListItem, onClick: () -> Unit) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    var thumb by remember(item.imageUri?.raw) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(item.imageUri?.raw) {
        item.imageUri?.let { uri -> spotify.thumbnail(uri) { thumb = it } }
    }
    val size = if (ui.short) 48.dp else 58.dp
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val bmp = thumb
        if (bmp != null) {
            val image = remember(bmp) { bmp.asImageBitmap() }
            Image(
                image,
                null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(size).clip(RoundedCornerShape(10.dp)),
            )
        } else {
            ArtTile(null, AppIcons.Note, size, cs.secondary)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                item.title.orEmpty(),
                fontSize = ui.body,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!item.subtitle.isNullOrBlank()) {
                Text(item.subtitle, fontSize = ui.small, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(
            if (item.hasChildren) AppIcons.Next else AppIcons.Play,
            null,
            tint = cs.onSurfaceVariant,
            modifier = Modifier.size(if (ui.short) 20.dp else 24.dp),
        )
    }
}

/** SHA1 fingerprint of this install's signing key, in the format Spotify's dashboard asks for. */
fun appSha1(context: Context): String? = runCatching {
    val info = context.packageManager.getPackageInfo(
        context.packageName,
        android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES,
    )
    val cert = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return@runCatching null
    MessageDigest.getInstance("SHA-1").digest(cert.toByteArray())
        .joinToString(":") { "%02X".format(it) }
}.getOrNull()
