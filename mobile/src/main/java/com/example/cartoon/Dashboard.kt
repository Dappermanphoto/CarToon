package com.example.cartoon

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject

/** How a gauge tile draws its reading. */
enum class GaugeKind(val label: String) {
    RADIAL("Radial sweep"), DIGITAL("Digital readout"), SPARK("Mini sparkline"),
}

val AlertRed = Color(0xFFFF5449)

/** Dashboard options kept across launches (the HUD mirror flip). */
object DashPrefs {
    private var prefs: android.content.SharedPreferences? = null

    var hud by mutableStateOf(false)
        private set

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences("cartoon_dash", Context.MODE_PRIVATE)
        hud = prefs?.getBoolean("hud", false) == true
    }

    fun toggleHud() {
        hud = !hud
        prefs?.edit()?.putBoolean("hud", hud)?.apply()
    }
}

/** Converts a value shown in the user's units back to the metric value the readings are kept in. */
fun ObdPid.toRaw(shown: Float, metric: Boolean): Float {
    if (metric) return shown
    val base = toUs(0f)
    val slope = toUs(1f) - base
    return if (slope == 0f) shown else (shown - base) / slope
}

/* ------------------------------ Alert thresholds ------------------------------ */

/** Limits kept in metric units. Null means no limit on that side. */
data class Threshold(val min: Float?, val max: Float?, val sound: Boolean) {
    fun breached(raw: Float): Boolean = (min != null && raw < min) || (max != null && raw > max)
}

/** Per-sensor alert limits, saved on the phone. */
@Stable
class ThresholdStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cartoon_thresholds", Context.MODE_PRIVATE)

    var version by mutableIntStateOf(0)
        private set

    fun get(key: String): Threshold? = runCatching {
        val s = prefs.getString(key, null) ?: return null
        val o = JSONObject(s)
        Threshold(
            if (o.has("min")) o.getDouble("min").toFloat() else null,
            if (o.has("max")) o.getDouble("max").toFloat() else null,
            o.optBoolean("sound", true),
        )
    }.getOrNull()

    fun set(key: String, t: Threshold?) {
        if (t == null || (t.min == null && t.max == null)) {
            prefs.edit().remove(key).apply()
        } else {
            val o = JSONObject().put("sound", t.sound)
            t.min?.let { o.put("min", it.toDouble()) }
            t.max?.let { o.put("max", it.toDouble()) }
            prefs.edit().putString(key, o.toString()).apply()
        }
        version++
    }

    fun keys(): Set<String> = prefs.all.keys
}

/** Watches the readings against the limits and chimes (quietly, with a pause) when one is crossed. */
@Stable
class ThresholdMonitor(
    context: Context,
    private val obd: ObdManager,
    val store: ThresholdStore,
    private val scheduler: PollScheduler,
) {
    /** Sensors past their limits right now. */
    var breached by mutableStateOf<Set<String>>(emptySet())
        private set

    private val lastChime = HashMap<String, Long>()
    private var tone: ToneGenerator? = null

    fun start(scope: CoroutineScope) {
        scope.launch {
            var seen = -1
            while (true) {
                delay(1000)
                if (seen != store.version) {
                    seen = store.version
                    scheduler.require("limits", store.keys())
                }
                check()
            }
        }
    }

    private fun check() {
        if (obd.state != ObdManager.State.CONNECTED) {
            if (breached.isNotEmpty()) breached = emptySet()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val out = HashSet<String>()
        for (key in store.keys()) {
            val raw = obd.values[key] ?: continue
            val t = store.get(key) ?: continue
            if (!t.breached(raw)) continue
            out += key
            if (t.sound && now - (lastChime[key] ?: 0L) > 15_000) {
                lastChime[key] = now
                chime()
            }
        }
        if (out != breached) breached = out
    }

    private fun chime() {
        runCatching {
            val t = tone ?: ToneGenerator(AudioManager.STREAM_NOTIFICATION, 55).also { tone = it }
            t.startTone(ToneGenerator.TONE_PROP_BEEP2, 180)
        }
    }
}

/* ------------------------------ Tile styles ------------------------------ */

/** Digital readout and sparkline tiles; the radial sweep is drawn by the gauge itself. */
@Composable
fun NonRadialReadout(
    style: GaugeKind,
    value: String,
    unit: String,
    history: List<Float>,
    size: Dp,
    color: Color,
    alert: Boolean,
) {
    val cs = MaterialTheme.colorScheme
    val textColor = if (alert) AlertRed else cs.onSurface
    val sizeSp = size.value
    Column(
        Modifier.size(size),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        BasicText(
            value,
            style = TextStyle(color = textColor, fontWeight = FontWeight.Black, textAlign = TextAlign.Center),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(
                minFontSize = 12.sp,
                maxFontSize = (if (style == GaugeKind.DIGITAL) sizeSp * 0.5f else sizeSp * 0.3f).coerceAtLeast(13f).sp,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        BasicText(
            unit,
            style = TextStyle(color = cs.onSurfaceVariant, textAlign = TextAlign.Center),
            maxLines = 1,
            autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = (sizeSp * 0.12f).coerceAtLeast(9f).sp),
            modifier = Modifier.fillMaxWidth(),
        )
        if (style == GaugeKind.SPARK) {
            val line = if (alert) AlertRed else color
            Canvas(Modifier.fillMaxWidth().height(size * 0.3f)) {
                if (history.size >= 2) {
                    var lo = history.min()
                    var hi = history.max()
                    if (hi - lo < 0.001f) {
                        lo -= 1f
                        hi += 1f
                    }
                    val step = this.size.width / (history.size - 1)
                    val path = Path()
                    history.forEachIndexed { i, v ->
                        val x = i * step
                        val y = this.size.height * (1f - (v - lo) / (hi - lo))
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    drawPath(path, line, style = Stroke(width = 4f, cap = StrokeCap.Round))
                    drawLine(
                        line.copy(alpha = 0.3f),
                        Offset(0f, this.size.height),
                        Offset(this.size.width, this.size.height),
                        2f,
                    )
                }
            }
        }
    }
}
