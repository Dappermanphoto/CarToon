package com.example.cartoon

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Html
import android.util.LruCache
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.options.IFramePlayerOptions
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/* ------------------------------ Data ------------------------------ */

data class YtVideo(val id: String, val title: String, val channel: String, val thumbnail: String)

/** Search state kept at app level so results survive switching tabs. */
@Stable
class YouTubeState {
    var query by mutableStateOf("")
    var results by mutableStateOf<List<YtVideo>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var searched by mutableStateOf(false)
    var trending by mutableStateOf<List<YtVideo>>(emptyList())
}

/** YouTube Data API v3. Each search uses 100 of the free 10,000 daily quota units (~100 searches/day). */
object YouTubeApi {
    suspend fun search(key: String, query: String): Result<List<YtVideo>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(
                "https://www.googleapis.com/youtube/v3/search?part=snippet&type=video&maxResults=24" +
                    "&videoEmbeddable=true&q=" + URLEncoder.encode(query, "UTF-8") +
                    "&key=" + URLEncoder.encode(key, "UTF-8")
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            try {
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) {
                    val msg = runCatching {
                        JSONObject(body).getJSONObject("error").getString("message")
                    }.getOrDefault("YouTube returned error $code")
                    throw IOException(msg)
                }
                val items = JSONObject(body).optJSONArray("items") ?: JSONArray()
                (0 until items.length()).mapNotNull { i ->
                    val item = items.getJSONObject(i)
                    val id = item.optJSONObject("id")?.optString("videoId").orEmpty()
                    if (id.isEmpty()) return@mapNotNull null
                    val sn = item.optJSONObject("snippet") ?: return@mapNotNull null
                    val thumbs = sn.optJSONObject("thumbnails")
                    val thumb = (thumbs?.optJSONObject("high") ?: thumbs?.optJSONObject("medium")
                        ?: thumbs?.optJSONObject("default"))?.optString("url").orEmpty()
                    YtVideo(id, decode(sn.optString("title")), decode(sn.optString("channelTitle")), thumb)
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    /** Most popular videos right now in the given country. Costs 1 quota unit. */
    suspend fun trending(key: String, region: String): Result<List<YtVideo>> = withContext(Dispatchers.IO) {
        runCatching {
            val url = URL(
                "https://www.googleapis.com/youtube/v3/videos?part=snippet&chart=mostPopular&maxResults=24" +
                    "&regionCode=" + URLEncoder.encode(region, "UTF-8") +
                    "&key=" + URLEncoder.encode(key, "UTF-8")
            )
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 10_000
            try {
                if (conn.responseCode !in 200..299) throw IOException("YouTube returned error ${conn.responseCode}")
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val items = JSONObject(body).optJSONArray("items") ?: JSONArray()
                (0 until items.length()).mapNotNull { i ->
                    val item = items.getJSONObject(i)
                    val id = item.optString("id")
                    if (id.isEmpty()) return@mapNotNull null
                    val sn = item.optJSONObject("snippet") ?: return@mapNotNull null
                    val thumbs = sn.optJSONObject("thumbnails")
                    val thumb = (thumbs?.optJSONObject("high") ?: thumbs?.optJSONObject("medium")
                        ?: thumbs?.optJSONObject("default"))?.optString("url").orEmpty()
                    YtVideo(id, decode(sn.optString("title")), decode(sn.optString("channelTitle")), thumb)
                }
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun decode(s: String): String = Html.fromHtml(s, Html.FROM_HTML_MODE_LEGACY).toString()
}

/* ------------------------------ Images ------------------------------ */

private object ImageCache {
    val cache = LruCache<String, Bitmap>(80)
}

/** Loads a web image (video thumbnails). */
@Composable
fun NetImage(url: String, modifier: Modifier = Modifier) {
    val cs = MaterialTheme.colorScheme
    var bitmap by remember(url) { mutableStateOf(ImageCache.cache.get(url)) }
    LaunchedEffect(url) {
        if (bitmap == null && url.isNotEmpty()) {
            val loaded = withContext(Dispatchers.IO) {
                runCatching { URL(url).openStream().use { BitmapFactory.decodeStream(it) } }.getOrNull()
            }
            if (loaded != null) {
                ImageCache.cache.put(url, loaded)
                bitmap = loaded
            }
        }
    }
    Box(modifier.background(cs.surfaceVariant)) {
        bitmap?.let {
            val image = remember(it) { it.asImageBitmap() }
            Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/* ------------------------------ Voice ------------------------------ */

/** Speech-to-text using the phone's built-in recognizer (works with the car's or phone's mic). */
private class VoiceSearch(private val context: Context) {
    private var recognizer: SpeechRecognizer? = null

    val available: Boolean get() = SpeechRecognizer.isRecognitionAvailable(context)

    fun start(onPartial: (String) -> Unit, onDone: (String?) -> Unit) {
        stop()
        val r = SpeechRecognizer.createSpeechRecognizer(context)
        recognizer = r
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
            override fun onError(error: Int) = onDone(null)
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()?.let(onPartial)
            }
            override fun onResults(results: Bundle?) {
                onDone(results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull())
            }
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        r.startListening(intent)
    }

    fun stop() {
        recognizer?.destroy()
        recognizer = null
    }
}

/* ------------------------------ Search screen ------------------------------ */

@Composable
fun YouTubeScreen(
    state: YouTubeState,
    apiKey: String,
    onOpenSettings: () -> Unit,
    onPlay: (YtVideo) -> Unit,
) {
    val context = LocalContext.current
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()

    // The car screen gets CarToon's own big keyboard; the phone uses its normal keyboard.
    var keyboardOpen by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    val voice = remember { VoiceSearch(context) }
    DisposableEffect(Unit) { onDispose { voice.stop() } }

    // Fill the page with what's trending until the user searches.
    LaunchedEffect(apiKey) {
        if (apiKey.isNotBlank() && state.trending.isEmpty()) {
            val region = java.util.Locale.getDefault().country.ifBlank { "US" }
            YouTubeApi.trending(apiKey, region).onSuccess { state.trending = it }
        }
    }

    fun runSearch() {
        val q = state.query.trim()
        if (q.isEmpty() || apiKey.isBlank()) return
        keyboardOpen = false
        state.loading = true
        state.error = null
        scope.launch {
            YouTubeApi.search(apiKey, q)
                .onSuccess { state.results = it }
                .onFailure { state.error = it.message ?: "Search failed" }
            state.loading = false
            state.searched = true
        }
    }

    fun startVoice() {
        listening = true
        voice.start(
            onPartial = { state.query = it },
            onDone = { text ->
                listening = false
                if (!text.isNullOrBlank()) {
                    state.query = text
                    runSearch()
                }
            },
        )
    }

    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startVoice()
    }

    Column(Modifier.fillMaxSize().padding(horizontal = ui.pad, vertical = 12.dp)) {
        if (!(ui.short && ui.wide)) {
            Text("YouTube", fontSize = ui.headline, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(12.dp))
        }

        if (apiKey.isBlank()) {
            EmptyState(
                icon = AppIcons.Video,
                title = "Add your YouTube key",
                body = "Searching YouTube needs a free YouTube Data API key. Open Settings on your " +
                    "phone and paste it into the YouTube section.",
                modifier = Modifier.fillMaxSize(),
            ) { BigButton("Open Settings", onOpenSettings) }
            return@Column
        }

        // Search bar
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (ui.car) {
                Row(
                    Modifier
                        .weight(1f)
                        .height(if (ui.short) 48.dp else 56.dp)
                        .clip(RoundedCornerShape(50))
                        .background(cs.surfaceVariant)
                        .clickable { keyboardOpen = true }
                        .padding(horizontal = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(AppIcons.Search, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        when {
                            listening && state.query.isBlank() -> "Listening…"
                            state.query.isBlank() -> "Search YouTube"
                            else -> state.query
                        },
                        fontSize = ui.body,
                        color = if (state.query.isBlank()) cs.onSurfaceVariant else cs.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                OutlinedTextField(
                    value = state.query,
                    onValueChange = { state.query = it },
                    placeholder = { Text(if (listening) "Listening…" else "Search YouTube") },
                    singleLine = true,
                    shape = RoundedCornerShape(50),
                    leadingIcon = { Icon(AppIcons.Search, null, modifier = Modifier.size(22.dp)) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { runSearch() }),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = cs.primary,
                        unfocusedBorderColor = cs.outline,
                        focusedContainerColor = cs.surface,
                        unfocusedContainerColor = cs.surface,
                    ),
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.width(10.dp))
            if (voice.available) {
                CircleIconButton(
                    AppIcons.Mic,
                    if (listening) "Stop listening" else "Voice search",
                    ui.iconButton,
                    onClick = {
                        if (listening) {
                            voice.stop()
                            listening = false
                        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                            PackageManager.PERMISSION_GRANTED
                        ) {
                            startVoice()
                        } else {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    primary = listening,
                )
                Spacer(Modifier.width(8.dp))
            }
            if (ui.car) {
                CircleIconButton(AppIcons.Keyboard, "Keyboard", ui.iconButton, { keyboardOpen = !keyboardOpen })
                Spacer(Modifier.width(8.dp))
            }
            CircleIconButton(AppIcons.Search, "Search", ui.iconButton, { runSearch() }, primary = true)
        }
        Spacer(Modifier.height(12.dp))

        when {
            ui.car && keyboardOpen -> OnScreenKeyboard(
                onKey = { state.query += it },
                onBackspace = { state.query = state.query.dropLast(1) },
                onClear = { state.query = "" },
                onSearch = { runSearch() },
                modifier = Modifier.fillMaxWidth(),
            )

            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = cs.primary)
            }

            state.error != null -> EmptyState(
                icon = AppIcons.Video,
                title = "Search didn't work",
                body = state.error.orEmpty(),
                modifier = Modifier.fillMaxSize(),
            ) { BigButton("Try again") { runSearch() } }

            state.results.isEmpty() && !state.searched && state.trending.isNotEmpty() -> Column(Modifier.fillMaxSize()) {
                Text("Trending now", fontSize = ui.title, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = if (ui.short) 200.dp else 240.dp),
                    contentPadding = PaddingValues(bottom = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(state.trending, key = { it.id }) { video ->
                        VideoCard(video) { onPlay(video) }
                    }
                }
            }

            state.results.isEmpty() -> EmptyState(
                icon = AppIcons.Search,
                title = if (state.searched) "No videos found" else "Search for a video",
                body = if (state.searched) "Try different words." else
                    "Type or say what you want to watch. Videos play while you're parked.",
                modifier = Modifier.fillMaxSize(),
            )

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = if (ui.short) 200.dp else 240.dp),
                contentPadding = PaddingValues(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.results, key = { it.id }) { video ->
                    VideoCard(video) { onPlay(video) }
                }
            }
        }
    }
}

@Composable
private fun VideoCard(video: YtVideo, onClick: () -> Unit) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(cs.surface)
            .clickable(onClick = onClick)
    ) {
        NetImage(video.thumbnail, Modifier.fillMaxWidth().aspectRatio(16f / 9f))
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            Text(
                video.title,
                fontSize = ui.body,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(video.channel, fontSize = ui.small, color = cs.onSurfaceVariant, maxLines = 1)
        }
    }
}

/* ------------------------------ On-screen keyboard ------------------------------ */

/** Big keys sized for the car screen, where the phone keyboard isn't available. */
@Composable
fun OnScreenKeyboard(
    onKey: (String) -> Unit,
    onBackspace: () -> Unit,
    onClear: () -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier,
    actionLabel: String = "Search",
) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val rows = listOf("1234567890", "qwertyuiop", "asdfghjkl'", "zxcvbnm,.-")
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        rows.forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { ch -> Key(ch.toString()) { onKey(ch.toString()) } }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Key("Clear", weight = 1.5f, onClick = onClear)
            Key("Space", weight = 5f) { onKey(" ") }
            Key("", weight = 1.5f, icon = true, onClick = onBackspace)
            Key(actionLabel, weight = 2f, accent = true, onClick = onSearch)
        }
    }
}

@Composable
private fun RowScope.Key(
    label: String,
    weight: Float = 1f,
    accent: Boolean = false,
    icon: Boolean = false,
    onClick: () -> Unit,
) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    Box(
        Modifier
            .weight(weight)
            .height(ui.key)
            .clip(RoundedCornerShape(12.dp))
            .background(if (accent) cs.primary else cs.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (icon) {
            Icon(AppIcons.Backspace, "Delete", tint = cs.onSurface, modifier = Modifier.size(22.dp))
        } else {
            Text(
                label,
                fontSize = ui.body,
                fontWeight = FontWeight.SemiBold,
                color = if (accent) cs.onPrimary else cs.onSurface,
                maxLines = 1,
            )
        }
    }
}

/* ------------------------------ Player ------------------------------ */

/** Plays a video with YouTube's official embedded player. */
@Composable
fun YouTubePlayerScreen(video: YtVideo, onBack: () -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val ui = LocalUi.current
    val activity = context as? ComponentActivity

    BackHandler(onBack = onBack)
    DisposableEffect(Unit) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        AndroidView(
            factory = { ctx ->
                YouTubePlayerView(ctx).also { player ->
                    player.enableAutomaticInitialization = false
                    // Pauses when the app is hidden (e.g. the car starts moving) and cleans up on exit.
                    activity?.lifecycle?.addObserver(player)
                    val options = IFramePlayerOptions.Builder(ctx).controls(1).build()
                    player.initialize(object : AbstractYouTubePlayerListener() {
                        override fun onReady(youTubePlayer: YouTubePlayer) {
                            youTubePlayer.loadVideo(video.id, 0f)
                        }
                    }, options)
                }
            },
            onRelease = { player ->
                activity?.lifecycle?.removeObserver(player)
                player.release()
            },
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        )

        Row(
            Modifier
                .fillMaxWidth()
                .safeDrawingPadding()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIconButton(AppIcons.Back, "Back", ui.iconButton, onBack)
            Spacer(Modifier.width(12.dp))
            Text(
                video.title,
                fontSize = ui.body,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .clip(RoundedCornerShape(50))
                    .background(Color(0x99000000))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
}
