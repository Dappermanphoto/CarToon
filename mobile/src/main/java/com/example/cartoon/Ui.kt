package com.example.cartoon

import android.graphics.Bitmap
import android.view.Display
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.graphics.luminance
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

/* ------------------------------ Sizing ------------------------------ */

/**
 * Sizes adapt to the space available. "short" covers small car screens (800x480, like most
 * aftermarket head units) and phones held sideways, so nothing gets cut off.
 */
@Immutable
data class UiScale(
    val car: Boolean,
    val wide: Boolean,
    val short: Boolean,
    val lyricScale: Float = 1f,
) {
    val pad: Dp get() = if (short) 14.dp else 20.dp
    val control: Dp get() = if (short) 46.dp else 56.dp
    val play: Dp get() = if (short) 60.dp else 76.dp
    val art: Dp get() = if (short) 136.dp else 184.dp
    val iconButton: Dp get() = if (short) 42.dp else 48.dp
    val headline: TextUnit get() = if (short) 22.sp else 28.sp
    val title: TextUnit get() = if (short) 18.sp else 22.sp
    val body: TextUnit get() = if (short) 14.sp else 16.sp
    val small: TextUnit get() = if (short) 12.sp else 13.sp
    val lyric: TextUnit get() = (if (short) 22.sp else 27.sp) * lyricScale
    val railWidth: Dp get() = if (short) 84.dp else 104.dp
    val navIcon: Dp get() = if (short) 22.dp else 26.dp
    val key: Dp get() = if (short) 40.dp else 52.dp
}

val LocalUi = staticCompositionLocalOf { UiScale(car = false, wide = false, short = false) }

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

    val Settings by lazy {
        icon(
            "M19.14,12.94c0.04,-0.3 0.06,-0.61 0.06,-0.94c0,-0.32 -0.02,-0.64 -0.07,-0.94l2.03,-1.58" +
                "c0.18,-0.14 0.23,-0.41 0.12,-0.61l-1.92,-3.32c-0.12,-0.22 -0.37,-0.29 -0.59,-0.22l-2.39,0.96" +
                "c-0.5,-0.38 -1.03,-0.7 -1.62,-0.94L14.4,2.81c-0.04,-0.24 -0.24,-0.41 -0.48,-0.41h-3.84" +
                "c-0.24,0 -0.43,0.17 -0.47,0.41L9.25,5.35C8.66,5.59 8.12,5.92 7.63,6.29L5.24,5.33" +
                "c-0.22,-0.08 -0.47,0 -0.59,0.22L2.74,8.87C2.62,9.08 2.66,9.34 2.86,9.48l2.03,1.58" +
                "C4.84,11.36 4.8,11.69 4.8,12s0.02,0.64 0.07,0.94l-2.03,1.58c-0.18,0.14 -0.23,0.41 -0.12,0.61" +
                "l1.92,3.32c0.12,0.22 0.37,0.29 0.59,0.22l2.39,-0.96c0.5,0.38 1.03,0.7 1.62,0.94l0.36,2.54" +
                "c0.05,0.24 0.24,0.41 0.48,0.41h3.84c0.24,0 0.44,-0.17 0.47,-0.41l0.36,-2.54" +
                "c0.59,-0.24 1.13,-0.56 1.62,-0.94l2.39,0.96c0.22,0.08 0.47,0 0.59,-0.22l1.92,-3.32" +
                "c0.12,-0.22 0.07,-0.47 -0.12,-0.61L19.14,12.94zM12,15.6c-1.98,0 -3.6,-1.62 -3.6,-3.6" +
                "s1.62,-3.6 3.6,-3.6s3.6,1.62 3.6,3.6S13.98,15.6 12,15.6z"
        )
    }
    val Video by lazy {
        icon("M20,4H4C2.9,4 2,4.9 2,6v12c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V6C22,4.9 21.1,4 20,4zM9.5,16.5v-9l7,4.5L9.5,16.5z")
    }
    val Search by lazy {
        icon(
            "M15.5,14h-0.79l-0.28,-0.27C15.41,12.59 16,11.11 16,9.5 16,5.91 13.09,3 9.5,3S3,5.91 3,9.5 " +
                "5.91,16 9.5,16c1.61,0 3.09,-0.59 4.23,-1.57l0.27,0.28v0.79l5,4.99L20.49,19l-4.99,-5z" +
                "M9.5,14C7.01,14 5,11.99 5,9.5S7.01,5 9.5,5 14,7.01 14,9.5 11.99,14 9.5,14z"
        )
    }
    val Mic by lazy {
        icon(
            "M12,14c1.66,0 2.99,-1.34 2.99,-3L15,5c0,-1.66 -1.34,-3 -3,-3S9,3.34 9,5v6c0,1.66 1.34,3 3,3z" +
                "M17.3,11c0,3 -2.54,5.1 -5.3,5.1S6.7,14 6.7,11H5c0,3.41 2.72,6.23 6,6.72V21h2v-3.28" +
                "c3.28,-0.48 6,-3.3 6,-6.72h-1.7z"
        )
    }
    val Backspace by lazy {
        icon(
            "M22,3H7c-0.69,0 -1.23,0.35 -1.59,0.88L0,12l5.41,8.11c0.36,0.53 0.9,0.89 1.59,0.89h15" +
                "c1.1,0 2,-0.9 2,-2V5c0,-1.1 -0.9,-2 -2,-2zM19,15.59L17.59,17 14,13.41 10.41,17 9,15.59 " +
                "12.59,12 9,8.41 10.41,7 14,10.59 17.59,7 19,8.41 15.41,12 19,15.59z"
        )
    }
    val Keyboard by lazy {
        icon(
            "M20,5H4c-1.1,0 -1.99,0.9 -1.99,2L2,17c0,1.1 0.9,2 2,2h16c1.1,0 2,-0.9 2,-2V7c0,-1.1 -0.9,-2 -2,-2z" +
                "M11,8h2v2h-2V8zM11,11h2v2h-2v-2zM8,8h2v2H8V8zM8,11h2v2H8v-2zM7,13H5v-2h2v2zM7,10H5V8h2v2z" +
                "M16,17H8v-2h8v2zM16,13h-2v-2h2v2zM16,10h-2V8h2v2zM19,13h-2v-2h2v2zM19,10h-2V8h2v2z"
        )
    }
    val Check by lazy { icon("M9,16.17L4.83,12l-1.42,1.41L9,19 21,7l-1.41,-1.41z") }

    val Gauge by lazy {
        icon(
            "M20.38,8.57l-1.23,1.85a8,8 0,0 1,-0.22 7.58L5.07,18A8,8 0,0 1,15.58 6.85l1.85,-1.23" +
                "A10,10 0,0 0,3.35 19a2,2 0,0 0,1.72 1h13.85a2,2 0,0 0,1.74 -1,10 10,0 0,0 -0.27,-10.44z" +
                "M10.59,15.41a2,2 0,0 0,2.83 0l5.66,-8.49 -8.49,5.66a2,2 0,0 0,0 2.83z"
        )
    }

    val Shuffle by lazy {
        icon(
            "M10.59,9.17L5.41,4 4,5.41l5.17,5.17 1.42,-1.41zM14.5,4l2.04,2.04L4,18.59 5.41,20 17.96,7.46 20,9.5V4h-5.5z" +
                "M14.83,13.41l-1.41,1.41 3.13,3.13L14.5,20H20v-5.5l-2.04,2.04 -3.13,-3.13z"
        )
    }
    val Repeat by lazy { icon("M7,7h10v3l4,-4 -4,-4v3H5v6h2V7zM17,17H7v-3l-4,4 4,4v-3h12v-6h-2v4z") }
    val RepeatOne by lazy {
        icon("M7,7h10v3l4,-4 -4,-4v3H5v6h2V7zM17,17H7v-3l-4,4 4,4v-3h12v-6h-2v4zM13,15V9h-1l-2,1v1h1.5v4H13z")
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
    val source = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val press by animateFloatAsState(if (pressed) 0.93f else 1f, label = "pillPress")
    val fill by animateColorAsState(if (selected) color else color.copy(alpha = 0.14f), label = "pillFill")
    Text(
        text,
        fontSize = ui.small,
        fontWeight = FontWeight.SemiBold,
        color = if (selected) cs.onPrimary else color,
        maxLines = 1,
        modifier = Modifier
            .graphicsLayer { scaleX = press; scaleY = press }
            .clip(RoundedCornerShape(50))
            .background(fill)
            .then(if (onClick != null) Modifier.clickable(interactionSource = source, indication = null, onClick = onClick) else Modifier)
            .padding(horizontal = if (ui.short) 12.dp else 14.dp, vertical = if (ui.short) 6.dp else 7.dp),
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
            Icon(
                fallback,
                null,
                tint = if (accent.luminance() > 0.45f) Color(0xFF15161A) else Color.White,
                modifier = Modifier.size(size * 0.42f),
            )
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
        modifier.padding(if (ui.short) 12.dp else 28.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(if (ui.short) 56.dp else 72.dp)
                .clip(CircleShape)
                .background(cs.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, null, tint = cs.primary, modifier = Modifier.size(if (ui.short) 26.dp else 32.dp))
        }
        Spacer(Modifier.height(if (ui.short) 10.dp else 18.dp))
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
            Spacer(Modifier.height(if (ui.short) 12.dp else 22.dp))
            action()
        }
    }
}

@Composable
fun BigButton(text: String, onClick: () -> Unit) {
    val ui = LocalUi.current
    Button(onClick = onClick, modifier = Modifier.height(if (ui.short) 46.dp else 52.dp)) {
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
    BoxWithConstraints(modifier) {
    // Everything except the artwork needs roughly this much height.
    val fixed = if (ui.short) 236.dp else 300.dp
    val artSize = if (maxHeight == Dp.Infinity) ui.art else (maxHeight - fixed).coerceIn(0.dp, ui.art)
    val rowLayout = compact || artSize < 92.dp
    Column(Modifier.fillMaxWidth().align(Alignment.CenterStart)) {
        if (rowLayout) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArtTile(art, AppIcons.Note, if (ui.short) 60.dp else 76.dp, cs.secondary)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) { TitleBlock(title, subtitle, source) }
            }
        } else {
            ArtTile(art, AppIcons.Note, artSize, cs.secondary)
            Spacer(Modifier.height(if (ui.short) 12.dp else 20.dp))
            TitleBlock(title, subtitle, source)
        }
        Spacer(Modifier.height(if (rowLayout || ui.short) 6.dp else 14.dp))
        SeekBar(position, duration, canSeek, onSeek)
        Spacer(Modifier.height(if (rowLayout || ui.short) 4.dp else 12.dp))
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
}

@Composable
private fun TitleBlock(title: String, subtitle: String, source: String?) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    Text(
        title.ifBlank { "Unknown title" },
        fontSize = ui.title,
        fontWeight = FontWeight.Bold,
        maxLines = if (ui.short) 1 else 2,
        overflow = TextOverflow.Ellipsis,
        lineHeight = ui.title * 1.15f,
    )
    if (subtitle.isNotBlank()) {
        Spacer(Modifier.height(2.dp))
        Text(subtitle, fontSize = ui.body, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (source != null) {
        Spacer(Modifier.height(if (ui.short) 6.dp else 10.dp))
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

/** The real icon of an app installed on the phone (such as Spotify), or null if it isn't installed. */
@Composable
fun rememberAppIcon(packageName: String): androidx.compose.ui.graphics.ImageBitmap? {
    val context = LocalContext.current
    return remember(packageName) {
        runCatching {
            val drawable = context.packageManager.getApplicationIcon(packageName)
            val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, 128, 128)
            drawable.draw(android.graphics.Canvas(bitmap))
            bitmap.asImageBitmap()
        }.getOrNull()
    }
}
