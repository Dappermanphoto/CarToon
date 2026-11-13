package com.example.cartoon

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private val WarningRed = Color(0xFFFF5449)

/**
 * Watches system voltage and warns when it sags below a threshold, for example when a big
 * amplifier pulls more current than the alternator and battery can supply.
 *
 * Voltage comes from the adapter's own voltmeter, so it's the quickest reading there is.
 */
@Stable
class VoltageMonitor(context: Context, private val obd: ObdManager, private val scheduler: PollScheduler) {
    private val prefs = context.applicationContext.getSharedPreferences("cartoon_voltage", Context.MODE_PRIVATE)

    var enabled by mutableStateOf(prefs.getBoolean("enabled", true))
        private set
    var threshold by mutableStateOf(prefs.getFloat("threshold", 12.0f))
        private set

    /** True while voltage is under the threshold. */
    var low by mutableStateOf(false)
        private set
    var lowest by mutableStateOf<Float?>(null)
        private set
    var dips by mutableStateOf(0)
        private set

    val current: Float? get() = obd.values["ATRV"]

    fun configure(enabled: Boolean, threshold: Float) {
        this.enabled = enabled
        this.threshold = threshold.coerceIn(10f, 14f)
        prefs.edit().putBoolean("enabled", this.enabled).putFloat("threshold", this.threshold).apply()
        scheduler.require("voltage", if (this.enabled) setOf("ATRV") else emptySet())
        if (!this.enabled) low = false
    }

    fun resetStats() {
        lowest = current
        dips = 0
    }

    fun start(scope: CoroutineScope) {
        scheduler.require("voltage", if (enabled) setOf("ATRV") else emptySet())

        // Demo vehicle: every 25 seconds, three seconds of heavy bass pull the voltage down.
        obd.demoHooks += { t, out ->
            val phase = t % 25f
            if (phase > 18f && phase < 21f) {
                out["ATRV"] = 11.55f + kotlin.math.sin(t * 9f) * 0.2f
            }
        }

        scope.launch {
            snapshotFlow { obd.values["ATRV"] to obd.state }.collect { (v, state) ->
                if (state != ObdManager.State.CONNECTED || v == null) {
                    low = false
                    if (state == ObdManager.State.DISCONNECTED) {
                        lowest = null
                        dips = 0
                    }
                    return@collect
                }
                if (lowest == null || v < (lowest ?: v)) lowest = v
                if (!enabled) return@collect
                // A little hysteresis so the warning doesn't flicker right at the threshold.
                if (!low && v < threshold) {
                    low = true
                    dips++
                } else if (low && v > threshold + 0.2f) {
                    low = false
                }
            }
        }
    }
}

/** Shown over every screen while voltage is low. */
@Composable
fun VoltageBanner(monitor: VoltageMonitor, modifier: Modifier = Modifier) {
    if (!monitor.low) return
    val ui = LocalUi.current
    Text(
        "Low voltage: %.1f V".format(monitor.current ?: 0f),
        fontSize = ui.body,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = modifier
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(WarningRed)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    )
}

/** The Voltage view on the OBD tab: live reading, lowest reading and a graph with the warning line. */
@Composable
fun VoltagePanel(obd: ObdManager, monitor: VoltageMonitor) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val v = monitor.current
    val color = if (monitor.low) WarningRed else LocalGaugeColor.current ?: cs.primary

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        if (v == null) "--" else "%.2f".format(v),
                        fontSize = if (ui.short) 44.sp else 64.sp,
                        fontWeight = FontWeight.Black,
                        color = color,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text("V", fontSize = ui.title, color = cs.onSurfaceVariant, modifier = Modifier.padding(bottom = 8.dp))
                }
                Text(
                    when {
                        v == null -> "Waiting for a reading…"
                        monitor.low -> "Below your ${"%.1f".format(monitor.threshold)} V warning level"
                        v >= 13.2f -> "Charging normally"
                        v >= 12.2f -> "On battery (engine off or light charging)"
                        else -> "Battery is getting low"
                    },
                    fontSize = ui.body,
                    color = if (monitor.low) WarningRed else cs.onSurfaceVariant,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Lowest  " + (monitor.lowest?.let { "%.2f V".format(it) } ?: "--"),
                    fontSize = ui.body,
                    fontWeight = FontWeight.SemiBold,
                )
                Text("Dips below warning  ${monitor.dips}", fontSize = ui.small, color = cs.onSurfaceVariant)
                Pill("Reset", color = cs.onSurfaceVariant, onClick = { monitor.resetStats() })
            }
        }
        Spacer(Modifier.height(10.dp))

        val tick = obd.tick // redraw when new readings arrive
        val data = remember(tick) { obd.historyOf("ATRV") }
        val grid = cs.surfaceVariant
        val threshold = monitor.threshold
        Canvas(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(cs.surface)
                .padding(14.dp)
        ) {
            val lo = minOf(10.5f, (data.minOrNull() ?: 10.5f) - 0.2f, threshold - 0.3f)
            val hi = maxOf(15f, (data.maxOrNull() ?: 15f) + 0.2f)
            fun y(value: Float) = size.height * (1f - (value - lo) / (hi - lo))
            drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 2f)
            if (monitor.enabled) {
                drawLine(
                    WarningRed.copy(alpha = 0.7f),
                    Offset(0f, y(threshold)),
                    Offset(size.width, y(threshold)),
                    strokeWidth = 3f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f)),
                )
            }
            if (data.size >= 2) {
                val stepX = size.width / (data.size - 1)
                val path = Path()
                data.forEachIndexed { i, value ->
                    if (i == 0) path.moveTo(0f, y(value)) else path.lineTo(i * stepX, y(value))
                }
                drawPath(path, color, style = Stroke(width = 4f, cap = StrokeCap.Round))
            }
        }
    }
}


/** Shown over every screen while the engine is running hot. */
@Composable
fun OverheatBanner(alerts: AlertMonitor, metric: Boolean, modifier: Modifier = Modifier) {
    if (!alerts.hot) return
    val ui = LocalUi.current
    val c = alerts.coolant ?: 0f
    Text(
        "Engine hot: %.0f %s".format(if (metric) c else c * 9f / 5f + 32f, if (metric) "°C" else "°F"),
        fontSize = ui.body,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        modifier = modifier
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(WarningRed)
            .padding(horizontal = 18.dp, vertical = 8.dp),
    )
}
