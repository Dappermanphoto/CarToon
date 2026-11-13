package com.example.cartoon

import android.content.Context
import android.os.SystemClock
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/* ------------------------------ Fuel estimate ------------------------------ */

/**
 * Estimates fuel use from what the car reports. With an air flow (MAF) sensor it is direct.
 * Without one it uses speed-density: manifold pressure, intake air temperature, RPM and the
 * engine's displacement from the vehicle profile, with an assumed volumetric efficiency.
 */
object FuelEstimator {
    private const val AFR = 14.7f          // gasoline, stoichiometric
    private const val GRAMS_PER_LITER = 745f
    private const val VE = 0.80f           // assumed volumetric efficiency
    private val DISPLACEMENT = Regex("""(\d+(?:\.\d+)?)\s*[lL]""")

    /** Engine size in liters from the active vehicle's Engine field ("4.3L V6" gives 4.3). */
    fun displacement(context: Context, obd: ObdManager): Float {
        if (obd.inDemo) return if (obd.demoName.contains("S-10")) 4.3f else 2.2f
        val prefs = context.applicationContext.getSharedPreferences("cartoon_profile", Context.MODE_PRIVATE)
        val id = Vehicles.activeId(context)
        val engine = prefs.getString(if (id == Vehicles.FIRST) "ENGINE" else "${id}_ENGINE", "").orEmpty()
        return DISPLACEMENT.find(engine)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
    }

    /** Air flow in grams per second, or null if the needed readings aren't available. */
    fun airFlow(values: Map<String, Float>, dispL: Float): Float? {
        values["10"]?.let { return it }
        val map = values["0B"] ?: return null
        val rpm = values["0C"] ?: return null
        if (dispL <= 0f) return null
        val iat = values["0F"] ?: 25f
        val rho = map * 1000f / (287f * (iat + 273.15f)) // kg per cubic meter
        val volume = dispL / 1000f * (rpm / 120f) * VE   // cubic meters per second
        return rho * volume * 1000f
    }

    /** Fuel in liters per second. */
    fun litersPerSecond(values: Map<String, Float>, dispL: Float): Float? =
        airFlow(values, dispL)?.let { it / AFR / GRAMS_PER_LITER }

    fun mpg(litersPerSecond: Float, kmh: Float): Float? {
        if (kmh < 3f || litersPerSecond <= 0f) return null
        val per100 = litersPerSecond * 3600f / kmh * 100f
        return 235.215f / per100
    }
}

@Stable
class FuelMonitor(private val context: Context, private val obd: ObdManager) {
    var instMpg by mutableStateOf<Float?>(null)
        private set
    var litersPerHour by mutableStateOf<Float?>(null)
        private set
    var tripLiters by mutableFloatStateOf(0f)
        private set
    var tripKm by mutableFloatStateOf(0f)
        private set
    var usingMaf by mutableStateOf(false)
        private set
    var needsDisplacement by mutableStateOf(false)
        private set

    fun reset() {
        tripLiters = 0f
        tripKm = 0f
    }

    fun start(scope: CoroutineScope) {
        scope.launch {
            var last = SystemClock.elapsedRealtime()
            while (true) {
                delay(1000)
                val now = SystemClock.elapsedRealtime()
                val dt = ((now - last) / 1000f).coerceIn(0f, 5f)
                last = now
                if (obd.state != ObdManager.State.CONNECTED) {
                    instMpg = null
                    litersPerHour = null
                    continue
                }
                val disp = FuelEstimator.displacement(context, obd)
                val vals = obd.values
                usingMaf = "10" in vals
                needsDisplacement = !usingMaf && disp <= 0f
                val lps = FuelEstimator.litersPerSecond(vals, disp)
                litersPerHour = lps?.let { it * 3600f }
                val kmh = vals["0D"] ?: 0f
                instMpg = lps?.let { FuelEstimator.mpg(it, kmh) }
                if (lps != null) {
                    tripLiters += lps * dt
                    tripKm += kmh * dt / 3600f
                }
            }
        }
    }

    /** Average over the time since the last reset, in US miles per gallon. */
    fun averageMpg(): Float? =
        if (tripLiters < 0.01f || tripKm < 0.1f) null else 235.215f / (tripLiters / tripKm * 100f)
}

/* ------------------------------ 0-60 and quarter mile ------------------------------ */

/**
 * Arms when the car is stopped and times the launch: 0 to 60 mph and the quarter mile.
 * Speed readings arrive a few times a second, so times are interpolated between readings.
 */
@Stable
class PerfTimer(private val obd: ObdManager, private val scheduler: PollScheduler) {
    enum class Phase { OFF, WAITING, ARMED, RUNNING, DONE }

    var enabled by mutableStateOf(false)
        private set
    var phase by mutableStateOf(Phase.OFF)
        private set
    var elapsed by mutableFloatStateOf(0f)
        private set
    var zeroSixty by mutableStateOf<Float?>(null)
        private set
    var quarter by mutableStateOf<Float?>(null)
        private set
    var trapMph by mutableStateOf<Float?>(null)
        private set

    private var startAt = 0L
    private var lastAt = 0L
    private var lastKmh = 0f
    private var meters = 0f
    private var lastMeters = 0f
    private var doneAt = 0L

    fun arm(on: Boolean) {
        enabled = on
        phase = if (on) Phase.WAITING else Phase.OFF
        scheduler.require("perf", if (on) setOf("0D") else emptySet())
        if (on) clearResult()
    }

    private fun clearResult() {
        zeroSixty = null
        quarter = null
        trapMph = null
        elapsed = 0f
    }

    fun start(scope: CoroutineScope) {
        scope.launch {
            while (true) {
                delay(60)
                if (enabled) step()
            }
        }
    }

    private fun step() {
        if (obd.state != ObdManager.State.CONNECTED) {
            phase = Phase.WAITING
            return
        }
        val kmh = obd.values["0D"] ?: return
        val now = SystemClock.elapsedRealtime()
        val mph = kmh * 0.621371f
        when (phase) {
            Phase.WAITING, Phase.DONE -> {
                if (phase == Phase.DONE && now - doneAt < 4000) return
                if (kmh < 1f) {
                    phase = Phase.ARMED
                    if (zeroSixty != null || quarter != null) { /* keep showing the last run until the next launch */ }
                }
            }
            Phase.ARMED -> if (kmh >= 2f) {
                // Launch: count from the moment the car started moving
                clearResult()
                startAt = now
                lastAt = now
                lastKmh = kmh
                meters = 0f
                lastMeters = 0f
                phase = Phase.RUNNING
            }
            Phase.RUNNING -> {
                val dt = (now - lastAt) / 1000f
                if (dt > 0f) {
                    val avgMs = (lastKmh + kmh) / 2f / 3.6f
                    lastMeters = meters
                    meters += avgMs * dt
                    elapsed = (now - startAt) / 1000f
                    if (zeroSixty == null && mph >= 60f) {
                        val frac = if (kmh > lastKmh) (60f / 0.621371f - lastKmh) / (kmh - lastKmh) else 1f
                        zeroSixty = elapsed - dt * (1f - frac.coerceIn(0f, 1f))
                    }
                    if (quarter == null && meters >= 402.336f) {
                        val frac = if (meters > lastMeters) (402.336f - lastMeters) / (meters - lastMeters) else 1f
                        quarter = elapsed - dt * (1f - frac.coerceIn(0f, 1f))
                        trapMph = mph
                    }
                    lastAt = now
                    lastKmh = kmh
                }
                if ((zeroSixty != null && quarter != null) || kmh < 1f || elapsed > 40f) {
                    phase = Phase.DONE
                    doneAt = now
                }
            }
            Phase.OFF -> {}
        }
    }
}

/* ------------------------------ Screen ------------------------------ */

/** Fuel economy and the performance timer. */
@Composable
fun DriveView(fuel: FuelMonitor, perf: PerfTimer, metric: Boolean) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme

    @Composable
    fun Card(content: @Composable () -> Unit) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(bottom = 10.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(cs.surface)
                .padding(16.dp)
        ) { content() }
    }

    fun economy(mpg: Float?): String = when {
        mpg == null -> "--"
        metric -> "%.1f".format(235.215f / mpg)
        else -> "%.1f".format(mpg)
    }
    val unit = if (metric) "L/100 km" else "MPG"

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Card {
            Text("FUEL ECONOMY", fontSize = ui.small, fontWeight = FontWeight.Bold, color = cs.primary)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Column {
                    Text("Right now", fontSize = ui.small, color = cs.onSurfaceVariant)
                    Text(economy(fuel.instMpg) + " $unit", fontSize = ui.headline, fontWeight = FontWeight.Black)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Since reset", fontSize = ui.small, color = cs.onSurfaceVariant)
                    Text(economy(fuel.averageMpg()) + " $unit", fontSize = ui.title, fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(6.dp))
            val gal = fuel.tripLiters * 0.264172f
            val miles = fuel.tripKm * 0.621371f
            Text(
                if (metric) "%.1f km · %.2f L used".format(fuel.tripKm, fuel.tripLiters)
                else "%.1f mi · %.2f gal used".format(miles, gal),
                fontSize = ui.small,
                color = cs.onSurfaceVariant,
            )
            Text(
                when {
                    fuel.needsDisplacement -> "No air flow sensor on this car. Add the engine size (like 4.3L V6) in Profile so it can be estimated."
                    fuel.usingMaf -> "Measured with the air flow (MAF) sensor."
                    else -> "Estimated from manifold pressure, intake temperature, RPM and engine size. Good for comparing, not exact."
                },
                fontSize = ui.small,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(8.dp))
            Pill("Reset", color = cs.onSurfaceVariant, onClick = { fuel.reset() })
        }

        Card {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("PERFORMANCE TIMER", fontSize = ui.small, fontWeight = FontWeight.Bold, color = cs.primary, modifier = Modifier.weight(1f))
                Pill(if (perf.enabled) "On" else "Off", selected = perf.enabled, onClick = { perf.arm(!perf.enabled) })
            }
            Spacer(Modifier.height(8.dp))
            val status = when (perf.phase) {
                PerfTimer.Phase.OFF -> "Turn it on, stop the car, then launch when you're ready."
                PerfTimer.Phase.WAITING -> "Waiting for the car to stop…"
                PerfTimer.Phase.ARMED -> "Armed. Go when ready."
                PerfTimer.Phase.RUNNING -> "Go!  %.2f s".format(perf.elapsed)
                PerfTimer.Phase.DONE -> "Run finished. It re-arms when you stop."
            }
            Text(status, fontSize = ui.title, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Column {
                    Text("0–60 mph", fontSize = ui.small, color = cs.onSurfaceVariant)
                    Text(perf.zeroSixty?.let { "%.2f s".format(it) } ?: "--", fontSize = ui.headline, fontWeight = FontWeight.Black)
                }
                Column {
                    Text("¼ mile", fontSize = ui.small, color = cs.onSurfaceVariant)
                    Text(perf.quarter?.let { "%.2f s".format(it) } ?: "--", fontSize = ui.headline, fontWeight = FontWeight.Black)
                    perf.trapMph?.let { Text("%.0f mph".format(it), fontSize = ui.small, color = cs.onSurfaceVariant) }
                }
            }
            Text(
                "Use it on a safe, legal place such as a closed course. Times depend on how often the car answers, so treat them as estimates.",
                fontSize = ui.small,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Spacer(Modifier.width(1.dp))
    }
}
