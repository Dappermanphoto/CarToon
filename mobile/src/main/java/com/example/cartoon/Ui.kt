package com.example.cartoon

import android.graphics.Bitmap
import android.view.Display
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/* ------------------------------ Theme ------------------------------ */

val CarToonColors = darkColorScheme(
    primary = Color(0xFFFFB547),
    onPrimary = Color(0xFF2A1700),
    secondary = Color(0xFF7FD4FF),
    onSecondary = Color(0xFF00222F),
    background = Color(0xFF0E0F12),
    onBackground = Color(0xFFF2F2F5),
    surface = Color(0xFF17191E),
    onSurface = Color(0xFFF2F2F5),
    surfaceVariant = Color(0xFF23262D),
    onSurfaceVariant = Color(0xFFA9AEB9),
    outline = Color(0xFF3A3E47),
)

val AppBackground = Brush.verticalGradient(listOf(Color(0xFF181A21), Color(0xFF0B0C0F)))

/** Sizes that grow on the car screen so everything is easy to hit at arm's length. */
@Immutable
data class UiScale(val car: Boolean, val wide: Boolean) {
    val pad: Dp get() = if (car) 28.dp else 18.dp
    val control: Dp get() = if (car) 68.dp else 54.dp
    val play: Dp get() = if (car) 96.dp else 74.dp
    val art: Dp get() = if (car) 196.dp else 168.dp
    val iconButton: Dp get() = if (car) 60.dp else 46.dp
    val headline: TextUnit get() = if (car) 34.sp else 28.sp
    val title: TextUnit get() = if (car) 28.sp else 21.sp
    val body: TextUnit get() = if (car) 20.sp else 15.sp
    val small: TextUnit get() = if (car) 17.sp else 13.sp
    val lyric: TextUnit get() = if (car) 34.sp else 25.sp
}

val LocalUi = staticCompositionLocalOf { UiScale(car = false, wide = false) }

/** True when the app is running on the Android Auto screen instead of the phone. */
@Composable
fun rememberIsCarScreen(): Boolean {
    val context = LocalContext.current
    val config = LocalConfiguration.current
    return remember(config) {
        val id = runCatching { context.display?.displayId }.getOrNull() ?: Display.DEFAULT_DISPLAY
        id != Display.DEFAULT_DISPLAY
    }
}

/* ------------------------------ Icons ------------------------------ */
// Paths from Google's Material icons (Apache 2.0).

object AppIcons {
    val Play by lazy { icon("M8,5v14l11,-7z") }
    val Pause by lazy { icon("M6,19h4V5H6v14zM14,5v14h4V5h-4z") }
    val Next by lazy { icon("M6,18l8.5,-6L6,6v12zM16,6v12h2V6h-2z") }
    val Previous by lazy { icon("M6,6h2v12H6zM9.5,12l8.5,6V6z") }
    val Rewind by lazy { icon("M11,18V6l-8.5,6 8.5,6zM11.5,12l8.5,6V6l-8.5,6z") }
    val Forward by lazy { icon("M4,18l8.5,-6L4,6v12zM13,6v12l8.5,-6L13,6z") }
    val Back by lazy { icon("M20,11H7.83l5.59,-5.59L12,4l-8,8 8,8 1.41,-1.41L7.83,13H20v-2z") }
    val Folder by lazy {
        icon("M10,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V8c0,-1.1 -0.9,-2 -2,-2h-8l-2,-2z")
    }
    val Refresh by lazy {
        icon(
            "M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 " +
                "7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 " +
                "4.22,1.78L13,11h7V4l-2.35,2.35z"
        )
    }
    val Note by lazy {
        icon("M12,3v10.55c-0.59,-0.34 -1.27,-0.55 -2,-0.55 -2.21,0 -4,1.79 -4,4s1.79,4 4,4 4,-1.79 4,-4V7h4V3h-6z")
    }
    val Movie by lazy {
        icon(
            "M18,4l2,4h-3l-2,-4h-2l2,4h-3l-2,-4H8l2,4H7L5,4H4c-1.1,0 -1.99,0.9 -1.99,2L2,18c0,1.1 0.9,2 2,2h16" +
                "c1.1,0 2,-0.9 2,-2V4h-4z"
        )
    }
    val Lyrics by lazy { icon("M14,17H4v2h10v-2zM20,9H4v2h16V9zM4,15h16v-2H4v2zM4,5v2h16V5H4z") }
    val Library by lazy {
        icon(
            "M20,2H8c-1.1,0 -2,0.9 -2,2v12c0,1.1 0.9,2 2,2h12c1.1,0 2,-0.9 2,-2V4c0,-1.1 -0.9,-2 -2,-2z" +
                "M18,7h-3v5.5c0,1.38 -1.12,2.5 -2.5,2.5S10,13.88 10,12.5s1.12,-2.5 2.5,-2.5c0.57,0 1.08,0.19 " +
                "1.5,0.51V5h4v2zM4,6H2v14c0,1.1 0.9,2 2,2h14v-2H4V6z"
        )
    }

    private fun icon(path: String): ImageVector =
        ImageVector.Builder(
            defaultWidth = 24.dp,
            defaultHeight = 24.dp,
            viewportWidth = 24f,
            viewportHeight = 24f,
        ).addPath(pathData = addPathNodes(path), fill = SolidColor(Color.White)).build()
}

/* ------------------------------ Building blocks ------------------------------ */

@Composable
fun CircleIconButton(
    icon: ImageVector,
    description: String,
    size: Dp,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val cs = MaterialTheme.colorScheme
    val bg = if (primary) cs.primary else cs.surfaceVariant
    val fg = if (primary) cs.onPrimary else cs.onSurface
    val alpha = if (enabled) 1f else 0.35f
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(bg.copy(alpha = bg.alpha * alpha))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = fg.copy(alpha = alpha), modifier = Modifier.size(size * 0.46f))
    }
}

@Composable
fun Pill(
    text: String,
    color: Color = MaterialTheme.colorScheme.primary,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    Text(
        text,
        fontSize = ui.small,
        fontWeight = FontWeight.SemiBold,
        color = if (selected) cs.onPrimary else color,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) color else color.copy(alpha = 0.14f))
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = if (ui.car) 18.dp else 14.dp, vertical = if (ui.car) 10.dp else 7.dp),
    )
}

@Composable
fun ArtTile(art: Bitmap?, fallback: ImageVector, size: Dp, accent: Color, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(size * 0.14f)
    if (art != null) {
        val image = remember(art) { art.asImageBitmap() }
        Image(
            image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = modifier.size(size).clip(shape),
        )
    } else {
        Box(
            modifier
                .size(size)
                .clip(shape)
                .background(Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.45f)))),
            contentAlignment = Alignment.Center,
        ) {
            Icon(fallback, null, tint = Color(0xFF15161A), modifier = Modifier.size(size * 0.42f))
        }
    }
}

@Composable
fun EmptyState(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    Column(
        modifier.padding(28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(if (ui.car) 88.dp else 72.dp)
                .clip(CircleShape)
                .background(cs.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = cs.primary, modifier = Modifier.size(if (ui.car) 40.dp else 32.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(title, fontSize = ui.title, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            body,
            fontSize = ui.body,
            color = cs.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = ui.body * 1.35f,
            modifier = Modifier.fillMaxWidth(0.85f),
        )
        if (action != null) {
            Spacer(Modifier.height(22.dp))
            action()
        }
    }
}

@Composable
fun BigButton(text: String, onClick: () -> Unit) {
    val ui = LocalUi.current
    Button(onClick = onClick, modifier = Modifier.height(if (ui.car) 64.dp else 52.dp)) {
        Text(text, fontSize = ui.body, fontWeight = FontWeight.SemiBold)
    }
}

/* ------------------------------ Player pieces ------------------------------ */

/** Artwork, title, seek bar and transport controls. Used for local songs and other apps' songs. */
@Composable
fun TrackPane(
    title: String,
    subtitle: String,
    source: String?,
    art: Bitmap?,
    isPlaying: Boolean,
    position: Long,
    duration: Long,
    canSeek: Boolean,
    onSeek: (Long) -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    compact: Boolean,
    modifier: Modifier = Modifier,
    hasNext: Boolean = true,
    extra: @Composable () -> Unit = {},
) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    Column(modifier, verticalArrangement = Arrangement.Center) {
        if (compact) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArtTile(art, AppIcons.Note, 76.dp, cs.secondary)
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) { TitleBlock(title, subtitle, source) }
            }
        } else {
            ArtTile(art, AppIcons.Note, ui.art, cs.secondary)
            Spacer(Modifier.height(22.dp))
            TitleBlock(title, subtitle, source)
        }
        Spacer(Modifier.height(if (compact) 10.dp else 18.dp))
        SeekBar(position, duration, canSeek, onSeek)
        Spacer(Modifier.height(if (compact) 6.dp else 14.dp))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CircleIconButton(AppIcons.Previous, "Previous", ui.control, onPrevious)
            if (canSeek) {
                CircleIconButton(AppIcons.Rewind, "Back 10 seconds", ui.control, {
                    onSeek((position - 10_000).coerceAtLeast(0L))
                })
            }
            CircleIconButton(
                if (isPlaying) AppIcons.Pause else AppIcons.Play,
                if (isPlaying) "Pause" else "Play",
                ui.play,
                onPlayPause,
                primary = true,
            )
            if (canSeek) {
                CircleIconButton(AppIcons.Forward, "Forward 10 seconds", ui.control, {
                    onSeek(position + 10_000)
                })
            }
            CircleIconButton(AppIcons.Next, "Next", ui.control, onNext, enabled = hasNext)
        }
        extra()
    }
}

@Composable
private fun TitleBlock(title: String, subtitle: String, source: String?) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    Text(
        title.ifBlank { "Unknown title" },
        fontSize = ui.title,
        fontWeight = FontWeight.Bold,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        lineHeight = ui.title * 1.15f,
    )
    if (subtitle.isNotBlank()) {
        Spacer(Modifier.height(2.dp))
        Text(subtitle, fontSize = ui.body, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (source != null) {
        Spacer(Modifier.height(10.dp))
        Pill("Playing in $source", color = cs.secondary)
    }
}

@Composable
fun SeekBar(position: Long, duration: Long, canSeek: Boolean, onSeek: (Long) -> Unit) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    var dragging by remember { mutableStateOf<Float?>(null) }
    val max = duration.coerceAtLeast(1L).toFloat()
    Slider(
        value = (dragging ?: position.toFloat()).coerceIn(0f, max),
        onValueChange = { dragging = it },
        onValueChangeFinished = {
            dragging?.let { onSeek(it.toLong()) }
            dragging = null
        },
        valueRange = 0f..max,
        enabled = canSeek && duration > 0,
        colors = SliderDefaults.colors(
            thumbColor = cs.primary,
            activeTrackColor = cs.primary,
            inactiveTrackColor = cs.surfaceVariant,
            disabledThumbColor = cs.primary,
            disabledActiveTrackColor = cs.primary,
            disabledInactiveTrackColor = cs.surfaceVariant,
        ),
    )
    Row(Modifier.fillMaxWidth()) {
        Text(formatTime(position), fontSize = ui.small, color = cs.onSurfaceVariant)
        Spacer(Modifier.weight(1f))
        Text(if (duration > 0) formatTime(duration) else "", fontSize = ui.small, color = cs.onSurfaceVariant)
    }
}

/** Scrolling lyrics. The current line is highlighted and kept about a third of the way down. */
@Composable
fun LyricsPanel(
    lyrics: Lyrics?,
    position: Long,
    onSeek: ((Long) -> Unit)?,
    emptyTitle: String,
    emptyBody: String,
    modifier: Modifier = Modifier,
    emptyAction: (@Composable () -> Unit)? = null,
) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(cs.surface)
    ) {
        if (lyrics == null || lyrics.lines.isEmpty()) {
            EmptyState(AppIcons.Lyrics, emptyTitle, emptyBody, Modifier.fillMaxSize(), emptyAction)
            return@BoxWithConstraints
        }

        val listState = rememberLazyListState()
        val current = lyrics.indexAt(position)
        LaunchedEffect(current) {
            if (current >= 0) listState.animateScrollToItem(current)
        }

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = 28.dp,
                end = 28.dp,
                top = if (lyrics.synced) maxHeight * 0.3f else 28.dp,
                bottom = maxHeight * 0.6f,
            ),
            modifier = Modifier.fillMaxSize(),
        ) {
            itemsIndexed(lyrics.lines) { i, line ->
                val color by animateColorAsState(
                    when {
                        !lyrics.synced -> cs.onSurface
                        i == current -> cs.primary
                        i < current -> cs.onSurfaceVariant.copy(alpha = 0.4f)
                        else -> cs.onSurfaceVariant
                    },
                    label = "lyricColor",
                )
                val scale by animateFloatAsState(
                    if (!lyrics.synced || i == current) 1f else 0.8f,
                    label = "lyricScale",
                )
                Text(
                    line.text.ifBlank { "♪" },
                    fontSize = ui.lyric,
                    fontWeight = FontWeight.Bold,
                    color = color,
                    lineHeight = ui.lyric * 1.2f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            scaleX = scale
                            scaleY = scale
                            transformOrigin = TransformOrigin(0f, 0.5f)
                        }
                        .then(
                            if (lyrics.synced && onSeek != null) Modifier.clickable { onSeek(line.timeMs) }
                            else Modifier
                        )
                        .padding(vertical = 10.dp),
                )
            }
        }

        // Soft fade at the top and bottom edges
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(56.dp)
                .background(Brush.verticalGradient(listOf(cs.surface, Color.Transparent)))
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(72.dp)
                .background(Brush.verticalGradient(listOf(Color.Transparent, cs.surface)))
        )
    }
}

fun formatTime(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0L)
    return "%d:%02d".format(total / 60, total % 60)
}
