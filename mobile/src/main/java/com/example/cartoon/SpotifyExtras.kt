package com.example.cartoon

import android.graphics.Bitmap
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/* ------------------------------ Shimmer ------------------------------ */

/** A soft moving highlight, shown in place of cover art while it loads. */
@Composable
fun rememberShimmer(): Brush {
    val cs = MaterialTheme.colorScheme
    val transition = rememberInfiniteTransition(label = "shimmer")
    val x by transition.animateFloat(
        initialValue = -300f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(tween(1300, easing = LinearEasing)),
        label = "shimmerX",
    )
    val base = cs.surfaceVariant
    val glow = cs.onSurface.copy(alpha = 0.12f).compositeOver(base)
    return Brush.linearGradient(listOf(base, glow, base), start = Offset(x, 0f), end = Offset(x + 300f, 120f))
}

@Composable
fun ShimmerGrid() {
    val ui = LocalUi.current
    val brush = rememberShimmer()
    LazyVerticalGrid(
        columns = GridCells.Adaptive(if (ui.short) 108.dp else 128.dp),
        contentPadding = PaddingValues(start = 10.dp, end = 10.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        userScrollEnabled = false,
        modifier = Modifier.fillMaxSize(),
    ) {
        items((0 until 8).toList()) {
            Column(Modifier.padding(4.dp)) {
                Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(16.dp)).background(brush))
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth(0.7f).height(12.dp).clip(RoundedCornerShape(6.dp)).background(brush))
                Spacer(Modifier.height(5.dp))
                Box(Modifier.fillMaxWidth(0.45f).height(10.dp).clip(RoundedCornerShape(5.dp)).background(brush))
            }
        }
    }
}

/* ------------------------------ Album color ------------------------------ */

/** The main color of the cover art, nudged to a pleasant brightness for a background. */
fun dominantColor(bmp: Bitmap?): Color? {
    if (bmp == null || bmp.width <= 0 || bmp.height <= 0) return null
    return runCatching {
        val small = Bitmap.createScaledBitmap(bmp, 12, 12, true)
        var r = 0.0
        var g = 0.0
        var b = 0.0
        var w = 0.0
        val hsv = FloatArray(3)
        for (y in 0 until 12) for (x in 0 until 12) {
            val p = small.getPixel(x, y)
            android.graphics.Color.colorToHSV(p, hsv)
            // Favor vivid pixels over grey and near-black ones
            val weight = hsv[1] * hsv[2] + 0.05
            r += android.graphics.Color.red(p) * weight
            g += android.graphics.Color.green(p) * weight
            b += android.graphics.Color.blue(p) * weight
            w += weight
        }
        val avg = android.graphics.Color.rgb((r / w).toInt(), (g / w).toInt(), (b / w).toInt())
        android.graphics.Color.colorToHSV(avg, hsv)
        hsv[2] = hsv[2].coerceIn(0.35f, 0.85f)
        Color(android.graphics.Color.HSVToColor(hsv))
    }.getOrNull()
}

/* ------------------------------ Search ------------------------------ */

@Composable
fun SearchTab(spotify: SpotifyController) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    var query by remember { mutableStateOf("") }
    var submitted by remember { mutableStateOf("") }
    var typing by remember { mutableStateOf(false) }

    val result by produceState<Result<List<WebItem>>?>(null, submitted) {
        value = null
        if (submitted.isNotBlank()) value = spotify.web.search(submitted)
    }

    // On the car screen there is no phone keyboard, so use the on-screen one.
    if (ui.car && typing) {
        Column(Modifier.fillMaxSize().padding(horizontal = 14.dp)) {
            Text(
                query.ifEmpty { "Songs, albums, artists" },
                fontSize = ui.title,
                color = if (query.isEmpty()) cs.onSurfaceVariant else cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(cs.surfaceVariant)
                    .padding(horizontal = 18.dp, vertical = 10.dp),
            )
            Spacer(Modifier.height(8.dp))
            OnScreenKeyboard(
                onKey = { query += it },
                onBackspace = { query = query.dropLast(1) },
                onClear = { query = "" },
                onSearch = {
                    typing = false
                    submitted = query.trim()
                },
                modifier = Modifier.fillMaxWidth(),
                actionLabel = "Search",
            )
        }
        return
    }

    Column(Modifier.fillMaxSize()) {
        if (ui.car) {
            Text(
                query.ifEmpty { "Search Spotify" },
                fontSize = ui.body,
                color = if (query.isEmpty()) cs.onSurfaceVariant else cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 14.dp)
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(50))
                    .background(cs.surfaceVariant)
                    .clickable { typing = true }
                    .padding(horizontal = 18.dp, vertical = 12.dp),
            )
        } else {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Songs, albums, artists") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { submitted = query.trim() }),
                shape = RoundedCornerShape(50),
                modifier = Modifier.padding(horizontal = 14.dp).fillMaxWidth(),
            )
        }
        Spacer(Modifier.height(8.dp))

        val r = result
        when {
            submitted.isBlank() -> EmptyState(
                AppIcons.Search,
                "Search Spotify",
                "Find songs, albums, playlists and artists. Tap to play, hold a song to queue it.",
                Modifier.fillMaxSize(),
            )
            r == null -> ShimmerGrid()
            r.isFailure -> EmptyState(AppIcons.Search, "Couldn't search", r.exceptionOrNull()?.message.orEmpty(), Modifier.fillMaxSize())
            else -> {
                val groups = r.getOrDefault(emptyList()).groupBy { it.kind }
                if (groups.isEmpty()) EmptyState(AppIcons.Search, "No results", "Try different words.", Modifier.fillMaxSize())
                else ArtGrid {
                    groups.forEach { (kind, list) ->
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            Text(
                                kind.uppercase(),
                                fontSize = ui.small,
                                fontWeight = FontWeight.Bold,
                                color = cs.primary,
                                modifier = Modifier.padding(start = 6.dp, top = 8.dp),
                            )
                        }
                        items(list, key = { it.kind + it.uri }) { item ->
                            WebCard(
                                item,
                                onClick = { spotify.playUri(item.uri) },
                                onLong = if (item.isTrack) ({ spotify.queueUri(item.uri) }) else null,
                            )
                        }
                    }
                }
            }
        }
    }
}

/* ------------------------------ Up next ------------------------------ */

@Composable
fun QueueTab(spotify: SpotifyController) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    var reload by remember { mutableIntStateOf(0) }
    val current = spotify.track?.uri

    val result by produceState<Result<List<WebItem>>?>(null, current, reload) {
        value = null
        delay(700) // let Spotify update its queue after a skip
        value = spotify.web.queue()
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Up next", fontSize = ui.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Pill("Refresh", onClick = { reload++ })
        }
        Spacer(Modifier.height(8.dp))
        val r = result
        when {
            r == null -> ShimmerGrid()
            r.isFailure -> EmptyState(
                AppIcons.Note,
                "Couldn't load the queue",
                r.exceptionOrNull()?.message.orEmpty() + "\n\nStart playing something in Spotify, then tap Refresh.",
                Modifier.fillMaxSize(),
            )
            else -> {
                val list = r.getOrDefault(emptyList())
                if (list.isEmpty()) EmptyState(AppIcons.Note, "Queue is empty", "Hold a song in Liked songs or Search to add it.", Modifier.fillMaxSize())
                else LazyColumn(
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(list.size) { i -> QueueRow(list[i], i + 1) { spotify.playUri(list[i].uri) } }
                }
            }
        }
    }
}

@Composable
private fun QueueRow(item: WebItem, number: Int, onClick: () -> Unit) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val art by produceState<Bitmap?>(null, item.imageUrl) { value = item.imageUrl?.let { WebImages.load(it) } }
    val size = if (ui.short) 44.dp else 52.dp
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).clickable(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$number", fontSize = ui.small, color = cs.onSurfaceVariant, modifier = Modifier.width(22.dp))
        val bmp = art
        Box(
            Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(
                if (bmp == null && item.imageUrl != null) rememberShimmer() else androidx.compose.ui.graphics.SolidColor(cs.surfaceVariant)
            )
        ) {
            if (bmp != null) {
                val image = remember(bmp) { bmp.asImageBitmap() }
                Image(image, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, fontSize = ui.body, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.subtitle, fontSize = ui.small, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
