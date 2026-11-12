package com.example.cartoon

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.sin

/**
 * Seek bar in the Android 16 style: the played part is a wavy line that flows while music plays
 * and settles flat when paused. Tap or drag to seek.
 */
@Composable
fun WavySeekBar(
    position: Long,
    duration: Long,
    canSeek: Boolean,
    playing: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction ?: if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f

    // 1 while playing, 0 when paused; eased so the wave smooths out instead of snapping flat
    val amplitude by animateFloatAsState(if (playing) 1f else 0f, tween(650), label = "waveAmplitude")
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(playing) {
        if (playing) {
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                phase = (phase + (now - last) / 1_000_000_000f * 6.5f) % (2f * PI.toFloat())
                last = now
            }
        }
    }

    val active = cs.primary
    val rest = cs.primary.copy(alpha = 0.26f)
    val interactive = canSeek && duration > 0

    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier
                .fillMaxWidth()
                .height(if (ui.short) 30.dp else 38.dp)
                .pointerInput(duration, interactive) {
                    if (interactive) detectTapGestures { o ->
                        onSeek((o.x / size.width * duration).toLong().coerceIn(0L, duration))
                    }
                }
                .pointerInput(duration, interactive) {
                    if (interactive) detectHorizontalDragGestures(
                        onDragStart = { o -> dragFraction = (o.x / size.width).coerceIn(0f, 1f) },
                        onHorizontalDrag = { change, _ -> dragFraction = (change.position.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            dragFraction?.let { onSeek((it * duration).toLong().coerceIn(0L, duration)) }
                            dragFraction = null
                        },
                        onDragCancel = { dragFraction = null },
                    )
                }
        ) {
            val stroke = 4.dp.toPx()
            val wave = 24.dp.toPx()
            val amp = 3.5.dp.toPx() * amplitude
            val cy = size.height / 2f
            val x1 = size.width * fraction
            val gap = 7.dp.toPx()

            // Played part: a wave that eases in from the left edge
            val end = (x1 - gap).coerceAtLeast(0f)
            if (end > 0f) {
                val path = Path()
                var x = 0f
                path.moveTo(0f, cy)
                while (x <= end) {
                    val ease = (x / wave).coerceIn(0f, 1f)
                    path.lineTo(x, cy + amp * ease * sin(x / wave * 2f * PI.toFloat() + phase))
                    x += 2f
                }
                drawPath(path, active, style = Stroke(stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }

            // Remaining part: a flat line
            val start = (x1 + gap).coerceAtMost(size.width)
            if (start < size.width) {
                drawLine(rest, Offset(start, cy), Offset(size.width, cy), strokeWidth = stroke, cap = StrokeCap.Round)
            }

            // Handle
            val handleW = 5.dp.toPx()
            val handleH = if (dragFraction != null) 28.dp.toPx() else 22.dp.toPx()
            drawRoundRect(
                active,
                topLeft = Offset((x1 - handleW / 2f).coerceIn(0f, size.width - handleW), cy - handleH / 2f),
                size = Size(handleW, handleH),
                cornerRadius = CornerRadius(handleW / 2f),
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            val shown = dragFraction?.let { (it * duration).toLong() } ?: position
            Text(wavyTime(shown), fontSize = ui.small, color = cs.onSurfaceVariant)
            Text(if (duration > 0) wavyTime(duration) else "", fontSize = ui.small, color = cs.onSurfaceVariant)
        }
    }
}

private fun wavyTime(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}
