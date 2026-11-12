package com.example.cartoon

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.spotify.protocol.types.ListItem

private fun vectorIcon(path: String): ImageVector =
    ImageVector.Builder(defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(pathData = addPathNodes(path), fill = SolidColor(Color.Black))
        .build()

/** Material heart icons (Apache 2.0). */
val HeartFilledIcon: ImageVector by lazy {
    vectorIcon(
        "M12,21.35l-1.45,-1.32C5.4,15.36 2,12.28 2,8.5 2,5.42 4.42,3 7.5,3c1.74,0 3.41,0.81 4.5,2.09C13.09,3.81 " +
            "14.76,3 16.5,3 19.58,3 22,5.42 22,8.5c0,3.78 -3.4,6.86 -8.55,11.54L12,21.35z"
    )
}
val HeartOutlineIcon: ImageVector by lazy {
    vectorIcon(
        "M16.5,3c-1.74,0 -3.41,0.81 -4.5,2.09C10.91,3.81 9.24,3 7.5,3 4.42,3 2,5.42 2,8.5c0,3.78 3.4,6.86 " +
            "8.55,11.54L12,21.35l1.45,-1.32C18.6,15.36 22,12.28 22,8.5 22,5.42 19.58,3 16.5,3zM12.1,18.55l-0.1,0.1 " +
            "-0.1,-0.1C7.14,14.24 4,11.39 4,8.5 4,6.5 5.5,5 7.5,5c1.54,0 3.04,0.99 3.57,2.36h1.87C13.46,5.99 " +
            "14.96,5 16.5,5c2,0 3.5,1.5 3.5,3.5 0,2.89 -3.14,5.74 -7.9,10.05z"
    )
}

private enum class LibTab(val label: String) {
    PLAYLISTS("Playlists"), ALBUMS("Albums"), LIKED("Liked songs"), SEARCH("Search"), QUEUE("Up next"), FOR_YOU("For you"),
}

/** Your Spotify library as a grid of cover art. Tap to play; hold a liked song to add it to the queue. */
@Composable
fun SpotifyLibrary(spotify: SpotifyController, onSignIn: () -> Unit, modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    var tab by remember { mutableStateOf(if (spotify.web.hasAccess) LibTab.PLAYLISTS else LibTab.FOR_YOU) }

    Column(modifier.clip(RoundedCornerShape(28.dp)).background(cs.surface).padding(top = 14.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LibTab.entries.forEach { t -> Pill(t.label, selected = tab == t, onClick = { tab = t }) }
        }
        Spacer(Modifier.height(if (ui.short) 6.dp else 10.dp))
        when {
            tab == LibTab.FOR_YOU -> ForYou(spotify)
            !spotify.web.hasAccess -> EmptyState(
                icon = AppIcons.Note,
                title = "Show your library",
                body = "Sign in once more so CarToon can see your playlists, albums and liked songs.",
                modifier = Modifier.fillMaxSize(),
            ) { BigButton("Sign in with Spotify", onSignIn) }
            tab == LibTab.SEARCH -> SearchTab(spotify)
            tab == LibTab.QUEUE -> QueueTab(spotify)
            else -> WebTab(spotify, tab)
        }
    }
}

@Composable
private fun WebTab(spotify: SpotifyController, tab: LibTab) {
    val result by produceState<Result<List<WebItem>>?>(null, tab, spotify.web.hasAccess) {
        value = null
        value = when (tab) {
            LibTab.PLAYLISTS -> spotify.web.playlists()
            LibTab.ALBUMS -> spotify.web.albums()
            else -> spotify.web.liked()
        }
    }
    val r = result
    when {
        r == null -> ShimmerGrid()
        r.isFailure -> EmptyState(AppIcons.Note, "Couldn't load", r.exceptionOrNull()?.message.orEmpty(), Modifier.fillMaxSize())
        else -> {
            val list = r.getOrDefault(emptyList())
            if (list.isEmpty()) EmptyState(AppIcons.Note, "Nothing here yet", "Spotify returned an empty list.", Modifier.fillMaxSize())
            else ArtGrid {
                items(list, key = { it.uri }) { item ->
                    WebCard(item, onClick = { spotify.playUri(item.uri) }, onLong = if (item.isTrack) ({ spotify.queueUri(item.uri) }) else null)
                }
            }
        }
    }
}

@Composable
internal fun ArtGrid(content: androidx.compose.foundation.lazy.grid.LazyGridScope.() -> Unit) {
    val ui = LocalUi.current
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (ui.short) 108.dp else 128.dp),
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize(),
        content = content,
    )
}

@Composable
internal fun WebCard(item: WebItem, onClick: () -> Unit, onLong: (() -> Unit)?) {
    val art by produceState<Bitmap?>(null, item.imageUrl) {
        value = item.imageUrl?.let { WebImages.load(it) }
    }
    ArtCard(art, item.title, item.subtitle, onClick, onLong, loading = item.imageUrl != null && art == null)
}

@Composable
private fun ForYou(spotify: SpotifyController) {
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
        if (parent == null) spotify.loadRecommended(onResult, onError) else spotify.loadChildren(parent, onResult, onError)
    }

    Column(Modifier.fillMaxSize()) {
        if (stack.isNotEmpty()) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                CircleIconButton(AppIcons.Back, "Back", ui.iconButton * 0.85f, { stack = stack.dropLast(1) })
                Spacer(Modifier.width(10.dp))
                Text(
                    stack.last().title.orEmpty(),
                    fontSize = ui.title,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        val list = items
        when {
            error != null -> EmptyState(AppIcons.Note, "Couldn't load", error.orEmpty(), Modifier.fillMaxSize())
            list == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = cs.primary)
            }
            list.isEmpty() -> EmptyState(AppIcons.Note, "Nothing here", "Spotify didn't return anything.", Modifier.fillMaxSize())
            else -> ArtGrid {
                items(list) { item ->
                    var thumb by remember(item.imageUri?.raw) { mutableStateOf<Bitmap?>(null) }
                    LaunchedEffect(item.imageUri?.raw) {
                        item.imageUri?.let { uri -> spotify.thumbnail(uri) { thumb = it } }
                    }
                    ArtCard(
                        thumb,
                        item.title.orEmpty(),
                        item.subtitle.orEmpty(),
                        onClick = {
                            if (item.hasChildren) stack = stack + item
                            else if (item.playable) spotify.play(item)
                        },
                    )
                }
            }
        }
    }
}

/** Cover art with title underneath. Springs slightly when pressed. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ArtCard(
    art: Bitmap?,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    loading: Boolean = false,
) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, label = "cardPress")
    Column(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(20.dp))
            .combinedClickable(interactionSource = source, indication = null, onClick = onClick, onLongClick = onLongClick)
            .padding(4.dp)
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(if (loading) rememberShimmer() else SolidColor(cs.surfaceVariant)),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) {
                val image = remember(art) { art.asImageBitmap() }
                Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else if (!loading) {
                Icon(AppIcons.Note, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(32.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(title, fontSize = ui.small, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (subtitle.isNotBlank()) {
            Text(subtitle, fontSize = ui.small * 0.9f, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
