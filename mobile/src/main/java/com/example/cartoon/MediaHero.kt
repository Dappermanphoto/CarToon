package com.example.cartoon

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * Large "now playing" card in the style of Android's and One UI's media players:
 * big artwork, a soft blurred copy of the artwork behind everything, and big controls.
 * Adapts between side-by-side (car screen, landscape) and stacked (phone).
 */
@Composable
fun MediaHero(
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
    modifier: Modifier = Modifier,
    hasNext: Boolean = true,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val image = remember(art) { art?.asImageBitmap() }

    Box(
        modifier
            .clip(RoundedCornerShape(32.dp))
            .background(cs.surface)
    ) {
        // Blurred artwork wash behind the card
        if (image != null) {
            Image(
                image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                alpha = 0.45f,
                modifier = Modifier.fillMaxSize().blur(48.dp),
            )
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(cs.surface.copy(alpha = 0.35f), cs.surface.copy(alpha = 0.85f))))
            )
        }

        BoxWithConstraints(Modifier.fillMaxSize().padding(if (ui.short) 14.dp else 22.dp)) {
            val sideBySide = maxWidth > maxHeight * 1.15f
            val details: @Composable (Modifier) -> Unit = { m ->
                Column(m, verticalArrangement = Arrangement.Center) {
                    Text(
                        title.ifBlank { "Unknown title" },
                        fontSize = ui.title * 1.25f,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = ui.title * 1.4f,
                    )
                    if (subtitle.isNotBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            subtitle,
                            fontSize = ui.body,
                            color = cs.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (source != null) {
                        Spacer(Modifier.height(if (ui.short) 6.dp else 10.dp))
                        Pill("Playing in $source", color = cs.secondary)
                    }
                    Spacer(Modifier.height(if (ui.short) 6.dp else 14.dp))
                    WavySeekBar(position, duration, canSeek, isPlaying, onSeek)
                    Spacer(Modifier.height(if (ui.short) 4.dp else 10.dp))
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircleIconButton(AppIcons.Previous, "Previous", ui.control, onPrevious)
                        CircleIconButton(
                            if (isPlaying) AppIcons.Pause else AppIcons.Play,
                            if (isPlaying) "Pause" else "Play",
                            ui.play,
                            onPlayPause,
                            primary = true,
                        )
                        CircleIconButton(AppIcons.Next, "Next", ui.control, onNext, enabled = hasNext)
                    }
                    if (actions != null) {
                        Spacer(Modifier.height(if (ui.short) 6.dp else 12.dp))
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
                            verticalAlignment = Alignment.CenterVertically,
                            content = actions,
                        )
                    }
                }
            }

            if (sideBySide) {
                val artSize = minOf(maxHeight, maxWidth * 0.42f)
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    ArtTile(art, AppIcons.Note, artSize, cs.secondary)
                    Spacer(Modifier.width(if (ui.short) 16.dp else 28.dp))
                    details(Modifier.weight(1f))
                }
            } else {
                // Room left for the text and controls under the artwork
                val artSize = minOf(maxWidth * 0.78f, maxHeight - 260.dp).coerceAtLeast(72.dp)
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                    ArtTile(art, AppIcons.Note, artSize, cs.secondary)
                    Spacer(Modifier.height(16.dp))
                    details(Modifier.fillMaxWidth().weight(1f))
                }
            }
        }
    }
}

/** Small round toggle used for shuffle and repeat. */
@Composable
fun ToggleIcon(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, on: Boolean, onClick: () -> Unit) {
    val ui = LocalUi.current
    CircleIconButton(icon, label, ui.iconButton, onClick, primary = on)
}
