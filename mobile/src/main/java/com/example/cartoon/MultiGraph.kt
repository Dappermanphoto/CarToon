package com.example.cartoon

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private val LINE_COLORS = listOf(Color(0xFFFFB547), Color(0xFF7FD4FF), Color(0xFFFF6B8A))

/**
 * Two or three sensors drawn on top of each other, each scaled to its own range so the shapes
 * can be compared. Drag on the chart to read values at a moment; Pause freezes it; Zoom narrows
 * the time window.
 */
@Composable
fun OverlayGraph(obd: ObdManager, metric: Boolean, selected: List<String>, onSelect: (List<String>) -> Unit) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val available = remember(obd.supported) { ObdPids.everything.filter { it.key in obd.supported } }
    var paused by remember { mutableStateOf(false) }
    var frozen by remember { mutableStateOf<Map<String, List<Float>>>(emptyMap()) }
    var zoom by remember { mutableStateOf(1) } // 1 = everything kept, 2 = last half, 4 = last quarter
    var cursor by remember { mutableStateOf<Float?>(null) } // 0..1 across the chart
    val tick = obd.tick // redraw when new readings arrive

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            available.forEach { pid ->
                val on = pid.key in selected
                Pill(pid.name, color = if (on) cs.primary else cs.onSurfaceVariant, selected = on, onClick = {
                    onSelect(if (on) selected - pid.key else (selected + pid.key).takeLast(3))
                })
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill(if (paused) "Resume" else "Pause", selected = paused, onClick = {
                if (!paused) frozen = selected.associateWith { obd.historyOf(it) }
                paused = !paused
                cursor = null
            })
            listOf(1, 2, 4).forEach { z ->
                Pill(if (z == 1) "All" else "Zoom ×$z", color = if (zoom == z) cs.primary else cs.onSurfaceVariant, selected = zoom == z, onClick = { zoom = z })
            }
            Text("Drag on the chart to inspect", fontSize = ui.small, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
        if (selected.size < 2) {
            EmptyState(AppIcons.Gauge, "Pick two or three sensors", "They are drawn on top of each other so you can compare them.", Modifier.fillMaxSize())
            return@Column
        }

        val series = selected.mapNotNull { key ->
            val pid = ObdPids[key] ?: return@mapNotNull null
            val all = if (paused) frozen[key].orEmpty() else obd.historyOf(key)
            val keep = (all.size / zoom).coerceAtLeast(minOf(all.size, 8))
            Triple(pid, all.takeLast(keep).map { pid.value(it, metric) }, tick)
        }

        // Legend with the live (or inspected) values
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            series.forEachIndexed { i, (pid, data, _) ->
                val index = cursor?.let { (it * (data.size - 1)).toInt().coerceIn(0, (data.size - 1).coerceAtLeast(0)) }
                val v = if (data.isEmpty()) null else data[index ?: data.lastIndex]
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(LINE_COLORS[i % LINE_COLORS.size]))
                    Spacer(Modifier.width(6.dp))
                    Column {
                        Text(pid.name, fontSize = ui.small, color = cs.onSurfaceVariant, maxLines = 1)
                        Text(
                            (v?.let { "%.${pid.decimals}f".format(it) } ?: "--") + " " + pid.unitFor(metric),
                            fontSize = ui.body, fontWeight = FontWeight.Bold,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))

        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(cs.surface)
                .padding(10.dp)
        ) {
            val grid = cs.surfaceVariant
            val cursorColor = cs.onSurfaceVariant
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { cursor = (it.x / size.width).coerceIn(0f, 1f) },
                            onDragEnd = { },
                            onDragCancel = { },
                        ) { change, _ -> cursor = (change.position.x / size.width).coerceIn(0f, 1f) }
                    }
                    .pointerInput(Unit) { detectTapGestures(onDoubleTap = { cursor = null }) }
            ) {
                for (i in 0..4) {
                    val y = size.height * i / 4f
                    drawLine(grid, Offset(0f, y), Offset(size.width, y), 1f)
                }
                series.forEachIndexed { i, (pid, data, _) ->
                    if (data.size < 2) return@forEachIndexed
                    val (lo, hi) = pid.rangeFor(metric)
                    val span = if (hi - lo <= 0f) 1f else hi - lo
                    val step = size.width / (data.size - 1)
                    val path = Path()
                    data.forEachIndexed { j, v ->
                        val x = j * step
                        val y = size.height * (1f - ((v - lo) / span).coerceIn(0f, 1f))
                        if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, LINE_COLORS[i % LINE_COLORS.size], style = Stroke(width = 5f, cap = StrokeCap.Round))
                }
                cursor?.let { c ->
                    val x = size.width * c
                    drawLine(cursorColor, Offset(x, 0f), Offset(x, size.height), 2f)
                }
            }
        }
    }
}
