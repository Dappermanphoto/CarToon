package com.example.cartoon

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.view.Display
import androidx.compose.foundation.background
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import java.text.DateFormat
import java.util.Date

private val ReadyGreen = Color(0xFF4CD964)
private val ReadyAmber = Color(0xFFFFB547)
private val ReadyRed = Color(0xFFFF5449)

/** Opens Android's share sheet on the phone's own screen, even when called from the car display. */
internal fun shareTextOnPhone(context: Context, text: String, subject: String): Boolean {
    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
        .putExtra(Intent.EXTRA_TEXT, text)
        .putExtra(Intent.EXTRA_SUBJECT, subject)
    val chooser = Intent.createChooser(send, subject).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val options = runCatching {
        ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()
    }.getOrNull()
    return runCatching { context.startActivity(chooser, options) }
        .recoverCatching { context.startActivity(chooser) }
        .isSuccess
}

@Composable
private fun InfoCard(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val cs = MaterialTheme.colorScheme
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(cs.surface)
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) { content() }
}

/* ------------------------------ Smog readiness ------------------------------ */

@Composable
fun SmogView(obd: ObdManager) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    LaunchedEffect(Unit) { if (obd.monitors == null) obd.readMonitors() }
    val m = obd.monitors

    val (color, headline, detail) = when {
        m == null -> Triple(cs.onSurfaceVariant, "Not read yet", "Tap Check to ask the car how its self-tests are going.")
        m.milOn -> Triple(
            ReadyRed, "Not ready: check-engine light is on",
            "A lit check-engine light fails a smog test in most places. Fix the codes first (Codes tab).",
        )
        m.incomplete.isEmpty() -> Triple(ReadyGreen, "Ready for a smog test", "Every self-test the car supports has finished.")
        else -> Triple(
            ReadyAmber, "${m.incomplete.size} test${if (m.incomplete.size == 1) "" else "s"} not finished",
            "Drive normally (a mix of city and highway, with a cold start) so the car can finish them. " +
                "Many states allow one unfinished test on older cars, but rules vary, so check yours.",
        )
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(if (ui.short) 12.dp else 14.dp).clip(CircleShape).background(color))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(headline, fontSize = ui.title, fontWeight = FontWeight.Bold)
                Text(detail, fontSize = ui.small, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.width(8.dp))
            Pill(if (obd.monitorsBusy) "Checking…" else "Check", selected = true, onClick = { obd.readMonitors() })
        }
        obd.monitorsNote?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = ui.body, color = cs.primary)
        }
        Spacer(Modifier.height(12.dp))
        if (m != null) {
            m.monitors.forEach { mon ->
                InfoCard(Modifier.padding(bottom = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(mon.name, fontSize = ui.body, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(
                            if (mon.ready) "Ready" else "Not ready",
                            fontSize = ui.body,
                            fontWeight = FontWeight.Bold,
                            color = if (mon.ready) ReadyGreen else ReadyAmber,
                        )
                    }
                }
            }
            if (m.monitors.isEmpty()) {
                Text("This car doesn't report any readiness tests.", fontSize = ui.body, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Tests reset to \"not ready\" when codes are cleared or the battery is disconnected.",
                fontSize = ui.small,
                color = cs.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

/* ------------------------------ Trips ------------------------------ */

@Composable
fun TripsView(log: TripLog, metric: Boolean) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val vehicleId = Vehicles.activeId(context)
    val mine = log.trips.filter { it.vehicle == vehicleId }
    var note by remember { mutableStateOf<String?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val dist = if (metric) "km" else "mi"
    val spd = if (metric) "km/h" else "mph"
    val k = if (metric) 1f else 0.621371f
    val dateFmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }

    fun tripText(t: Trip): String {
        val mins = ((t.end - t.start) / 60000f).toInt()
        return "%.1f %s · %d min · avg %.0f · max %.0f %s".format(t.km * k, dist, mins, t.avgKmh * k, t.maxKmh * k, spd)
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Trip log", fontSize = ui.title, fontWeight = FontWeight.Bold)
                Text(
                    if (log.enabled) "Records drives while CarToon is connected to the car." else "Trip logging is off (Settings).",
                    fontSize = ui.small,
                    color = cs.onSurfaceVariant,
                )
            }
            if (mine.isNotEmpty()) {
                Pill("Export", selected = true, onClick = {
                    val ok = shareTextOnPhone(context, TripLog.csv(mine, metric), "CarToon trips")
                    note = if (ok) (if (ui.car) "Opened on your phone." else null) else "Couldn't open the share sheet."
                })
            }
        }
        note?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, fontSize = ui.small, color = cs.primary)
        }
        Spacer(Modifier.height(10.dp))

        log.live?.let { t ->
            InfoCard(Modifier.padding(bottom = 8.dp)) {
                Column {
                    Text("Driving now", fontSize = ui.small, color = cs.primary, fontWeight = FontWeight.Bold)
                    Text(tripText(t), fontSize = ui.body, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        log.summary?.let { t ->
            InfoCard(Modifier.padding(bottom = 8.dp)) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Trip finished", fontSize = ui.body, color = cs.primary, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Pill("Dismiss", color = cs.onSurfaceVariant, onClick = { log.summary = null })
                    }
                    Text(tripText(t), fontSize = ui.body, fontWeight = FontWeight.SemiBold)
                    val extras = listOfNotNull(
                        "Idle ${t.idleSec / 60} min",
                        t.maxCoolant?.let { "Hottest %.0f %s".format(if (metric) it else it * 9f / 5f + 32f, if (metric) "°C" else "°F") },
                        t.minVolts?.let { "Lowest %.1f V".format(it) },
                    ).joinToString(" · ")
                    Text(extras, fontSize = ui.small, color = cs.onSurfaceVariant)
                }
            }
        }

        if (mine.isEmpty() && log.live == null) {
            Text(
                "No trips yet. Drive with the adapter connected and each trip is saved here when you stop. " +
                    "The demo car makes trips too.",
                fontSize = ui.body,
                color = cs.onSurfaceVariant,
            )
        }
        mine.forEach { t ->
            InfoCard(Modifier.padding(bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(dateFmt.format(Date(t.start)), fontSize = ui.body, fontWeight = FontWeight.SemiBold)
                        Text(tripText(t), fontSize = ui.small, color = cs.onSurfaceVariant)
                    }
                    Pill("Delete", color = cs.onSurfaceVariant, onClick = { log.delete(t) })
                }
            }
        }
        if (mine.isNotEmpty()) {
            Spacer(Modifier.height(6.dp))
            if (confirmClear) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Pill("Delete all trips", selected = true, onClick = { log.clearAll(); confirmClear = false })
                    Pill("Cancel", color = cs.onSurfaceVariant, onClick = { confirmClear = false })
                }
            } else {
                Pill("Clear all", color = cs.onSurfaceVariant, onClick = { confirmClear = true })
            }
        }
        Spacer(Modifier.height(16.dp))
    }
}

/* ------------------------------ Health baseline ------------------------------ */

@Composable
fun HealthView(obd: ObdManager, baseline: HealthBaseline, metric: Boolean) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val saved = remember(baseline.version) { baseline.saved() }
    val savedAt = remember(baseline.version) { baseline.savedAt() }
    val idle = HealthBaseline.atWarmIdle(obd.values)

    var recording by remember { mutableStateOf(false) }
    var samples by remember { mutableIntStateOf(0) }
    LaunchedEffect(recording) {
        if (!recording) return@LaunchedEffect
        val sums = HashMap<String, Float>()
        val counts = HashMap<String, Int>()
        samples = 0
        repeat(10) {
            delay(1000)
            HealthBaseline.READINGS.forEach { r ->
                obd.values[r.key]?.let {
                    sums[r.key] = (sums[r.key] ?: 0f) + it
                    counts[r.key] = (counts[r.key] ?: 0) + 1
                }
            }
            samples = it + 1
        }
        val avg = sums.mapValues { (k, v) -> v / (counts[k] ?: 1) }
        if (avg.isNotEmpty()) baseline.save(avg)
        recording = false
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Car health", fontSize = ui.title, fontWeight = FontWeight.Bold)
                Text(
                    if (saved == null) "Record what normal looks like, then compare later."
                    else "Baseline saved " + DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(savedAt)),
                    fontSize = ui.small,
                    color = cs.onSurfaceVariant,
                )
            }
            if (recording) {
                Pill("Recording $samples/10", selected = true)
            } else {
                Pill(if (saved == null) "Record baseline" else "Re-record", selected = saved == null, onClick = { if (idle) recording = true })
            }
            if (saved != null && !recording) {
                Spacer(Modifier.width(8.dp))
                Pill("Clear", color = cs.onSurfaceVariant, onClick = { baseline.clear() })
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            if (idle) "Warm and idling: good time to record or compare."
            else "Let the engine warm up and idle in park for the best comparison.",
            fontSize = ui.small,
            color = if (idle) ReadyGreen else cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

        HealthBaseline.READINGS.forEach { r ->
            val pid = ObdPids[r.key] ?: return@forEach
            if (r.key !in obd.supported) return@forEach
            val now = obd.values[r.key]
            val base = saved?.get(r.key)
            val drift = if (base != null && now != null && idle) now - base else null
            val bad = drift != null && kotlin.math.abs(drift) > r.tolerance
            InfoCard(Modifier.padding(bottom = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(r.label, fontSize = ui.body, fontWeight = FontWeight.SemiBold)
                        Text(
                            "Now " + pid.format(now, metric) + " " + pid.unitFor(metric) +
                                if (base != null) "   ·   Normal " + pid.format(base, metric) + " " + pid.unitFor(metric) else "",
                            fontSize = ui.small,
                            color = cs.onSurfaceVariant,
                        )
                    }
                    Text(
                        when {
                            base == null -> ""
                            drift == null -> "Idle to compare"
                            bad -> if (drift > 0) "Higher ↑" else "Lower ↓"
                            else -> "Normal"
                        },
                        fontSize = ui.small,
                        fontWeight = FontWeight.Bold,
                        color = when {
                            drift == null -> cs.onSurfaceVariant
                            bad -> ReadyAmber
                            else -> ReadyGreen
                        },
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "A reading that has drifted isn't a diagnosis by itself, but it's a good reason to look closer. " +
                "Fuel trims creeping up often point to a vacuum leak, and a falling voltage points at the battery or alternator.",
            fontSize = ui.small,
            color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(16.dp))
    }
}
