package com.example.cartoon

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** Settings for the add-on OBD features. Shown inside the main Settings screen. */
@Composable
fun AdvancedSettingsSections(modules: ObdModules) {
    GaugeColorSection(modules.gaugeStyle)
    VoltageSection(modules.voltage)
    PollingSection(modules.scheduler)
}

@Composable
internal fun AdvSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    Text(
        title.uppercase(),
        fontSize = ui.small,
        fontWeight = FontWeight.Bold,
        color = cs.primary,
        modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(cs.surface)
            .padding(18.dp),
        content = content,
    )
    Spacer(Modifier.height(20.dp))
}

@Composable
internal fun AdvSwitchRow(title: String, body: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = ui.body, fontWeight = FontWeight.SemiBold)
            Text(body, fontSize = ui.small, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(checkedThumbColor = cs.onPrimary, checkedTrackColor = cs.primary),
        )
    }
}

@Composable
internal fun AdvSlider(value: Float, range: ClosedFloatingPointRange<Float>, steps: Int = 0, onChange: (Float) -> Unit) {
    val cs = MaterialTheme.colorScheme
    Slider(
        value = value.coerceIn(range.start, range.endInclusive),
        onValueChange = onChange,
        valueRange = range,
        steps = steps,
        colors = SliderDefaults.colors(
            thumbColor = cs.primary,
            activeTrackColor = cs.primary,
            inactiveTrackColor = cs.surfaceVariant,
        ),
    )
}

/* ------------------------------ Gauge color ------------------------------ */

@Composable
private fun GaugeColorSection(style: GaugeStyle) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    AdvSection("Gauge color") {
        var text by remember(style.hex) { mutableStateOf(style.hex) }
        val current = style.color

        Text(
            "Match the gauges to your dash lighting. Pick a preset, type a hex code, or use the sliders.",
            fontSize = ui.small,
            color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill("App accent", selected = style.hex.isEmpty(), onClick = { style.choose("") })
            GaugeStyle.PRESETS.forEach { (name, hex) ->
                val color = parseHexColor(hex) ?: Color.White
                val selected = style.hex.equals(hex, ignoreCase = true)
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier
                            .size(if (ui.short) 38.dp else 44.dp)
                            .clip(CircleShape)
                            .background(color)
                            .border(if (selected) 3.dp else 0.dp, cs.onSurface, CircleShape)
                            .clickable { style.choose(hex) }
                    )
                    Text(name, fontSize = ui.small, color = cs.onSurfaceVariant)
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(current ?: cs.primary)
            )
            Spacer(Modifier.width(12.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { typed ->
                    text = typed.removePrefix("#").filter { it.isLetterOrDigit() }.take(6).uppercase()
                    if (parseHexColor(text) != null) style.choose(text)
                },
                label = { Text("Hex code") },
                prefix = { Text("#") },
                singleLine = true,
                isError = text.isNotEmpty() && parseHexColor(text) == null,
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f),
            )
        }

        // Hue / saturation / brightness sliders
        val hsv = remember(style.hex) {
            FloatArray(3).also { out ->
                val c = current
                if (c != null) {
                    android.graphics.Color.RGBToHSV(
                        (c.red * 255).toInt(), (c.green * 255).toInt(), (c.blue * 255).toInt(), out,
                    )
                } else {
                    out[0] = 35f
                    out[1] = 0.85f
                    out[2] = 1f
                }
            }
        }
        fun apply(h: Float, s: Float, v: Float) {
            val argb = android.graphics.Color.HSVToColor(floatArrayOf(h, s, v))
            style.choose("%06X".format(argb and 0xFFFFFF))
        }
        Spacer(Modifier.height(8.dp))
        Text("Hue", fontSize = ui.small, color = cs.onSurfaceVariant)
        AdvSlider(hsv[0], 0f..359f) { apply(it, hsv[1], hsv[2]) }
        Text("Saturation", fontSize = ui.small, color = cs.onSurfaceVariant)
        AdvSlider(hsv[1], 0f..1f) { apply(hsv[0], it, hsv[2]) }
        Text("Brightness", fontSize = ui.small, color = cs.onSurfaceVariant)
        AdvSlider(hsv[2], 0.2f..1f) { apply(hsv[0], hsv[1], it) }
    }
}

/* ------------------------------ Voltage ------------------------------ */

@Composable
private fun VoltageSection(monitor: VoltageMonitor) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    AdvSection("Voltage warning") {
        AdvSwitchRow(
            title = "Warn on low voltage",
            body = "Shows a warning on every screen when system voltage drops, for example under heavy amplifier draw.",
            checked = monitor.enabled,
        ) { monitor.configure(it, monitor.threshold) }
        if (monitor.enabled) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Warn below %.1f V".format(monitor.threshold),
                fontSize = ui.body,
                fontWeight = FontWeight.SemiBold,
            )
            // 10.5 V to 13.5 V in 0.1 V steps
            AdvSlider(monitor.threshold, 10.5f..13.5f, steps = 29) {
                monitor.configure(true, Math.round(it * 10f) / 10f)
            }
            Text(
                "About 12.0 V suits most cars. With the engine running, healthy charging is 13.5 to 14.5 V.",
                fontSize = ui.small,
                color = cs.onSurfaceVariant,
            )
        }
    }
}

/* ------------------------------ Polling ------------------------------ */

@Composable
private fun PollingSection(scheduler: PollScheduler) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    AdvSection("OBD polling speed") {
        AdvSwitchRow(
            title = "Prioritize fast sensors",
            body = "Reads RPM, speed, throttle and voltage every round, and slow-changing sensors less often.",
            checked = scheduler.prioritize,
        ) { scheduler.configure(it, scheduler.mediumEvery, scheduler.slowEvery) }
        if (scheduler.prioritize) {
            Spacer(Modifier.height(12.dp))
            Text(
                "Medium sensors (fuel trims, O2): every ${scheduler.mediumEvery} rounds",
                fontSize = ui.body,
            )
            AdvSlider(scheduler.mediumEvery.toFloat(), 1f..10f, steps = 8) {
                scheduler.configure(true, Math.round(it), scheduler.slowEvery)
            }
            Text(
                "Slow sensors (temperatures, fuel level): every ${scheduler.slowEvery} rounds",
                fontSize = ui.body,
            )
            AdvSlider(scheduler.slowEvery.toFloat(), 2f..40f, steps = 37) {
                scheduler.configure(true, scheduler.mediumEvery, Math.round(it))
            }
            Text(
                "Higher numbers make the fast gauges smoother. The demo car always updates everything.",
                fontSize = ui.small,
                color = cs.onSurfaceVariant,
            )
        }
    }
}
