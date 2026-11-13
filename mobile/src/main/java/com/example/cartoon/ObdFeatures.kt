package com.example.cartoon

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/* ------------------------------ Vehicles ------------------------------ */

/** Which saved vehicle is active. Trips, baselines and service reminders are kept per vehicle. */
object Vehicles {
    const val FIRST = "v0"

    fun activeId(context: Context): String =
        context.applicationContext.getSharedPreferences("cartoon_profile", Context.MODE_PRIVATE)
            .getString("active", FIRST) ?: FIRST

    /** The special profile (such as the S-10) bound to the active vehicle, or "". */
    fun presetFor(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences("cartoon_profile", Context.MODE_PRIVATE)
        val id = activeId(context)
        return prefs.getString(if (id == FIRST) "PRESET" else "${id}_PRESET", "").orEmpty()
    }
}

/* ------------------------------ Smog readiness ------------------------------ */

data class Monitor(val name: String, val ready: Boolean)

/** What the car reports about its emissions self-tests (OBD mode 01, PID 01). */
data class MonitorStatus(
    val milOn: Boolean,
    val codeCount: Int,
    val monitors: List<Monitor>,
) {
    val incomplete: List<Monitor> get() = monitors.filter { !it.ready }

    companion object {
        private val SPARK = listOf(
            "Catalyst", "Heated catalyst", "Evaporative system", "Secondary air",
            "A/C refrigerant", "Oxygen sensor", "Oxygen sensor heater", "EGR system",
        )
        private val DIESEL = listOf(
            "NMHC catalyst", "NOx / SCR", null, "Boost pressure",
            null, "Exhaust gas sensor", "Particulate filter", "EGR / VVT",
        )

        /** [b] is the four data bytes A, B, C, D. */
        fun decode(b: IntArray): MonitorStatus {
            val a = b[0]
            val bb = b[1]
            val c = b[2]
            val d = b[3]
            val out = ArrayList<Monitor>()
            fun cont(bit: Int, name: String) {
                if (bb and (1 shl bit) != 0) out += Monitor(name, bb and (1 shl (bit + 4)) == 0)
            }
            cont(0, "Misfire")
            cont(1, "Fuel system")
            cont(2, "Components")
            val names = if (bb and 0x08 == 0) SPARK else DIESEL
            for (bit in 0..7) {
                val name = names[bit] ?: continue
                if (c and (1 shl bit) != 0) out += Monitor(name, d and (1 shl bit) == 0)
            }
            return MonitorStatus(a and 0x80 != 0, a and 0x7F, out)
        }

        /** A believable demo result: two monitors still running after a code clear. */
        fun demo(): MonitorStatus = decode(intArrayOf(0x82, 0x07, 0xE5, 0x05))
    }
}

/* ------------------------------ Trips ------------------------------ */

data class Trip(
    val start: Long,
    val end: Long,
    val km: Float,
    val maxKmh: Float,
    val avgKmh: Float,
    val idleSec: Int,
    val maxCoolant: Float?,
    val minVolts: Float?,
    val vehicle: String,
)

/**
 * Logs drives while the app is connected to the car. A trip starts when the car moves and ends
 * after three minutes stopped (or when the adapter disconnects).
 */
@Stable
class TripLog(context: Context, private val obd: ObdManager, private val scheduler: PollScheduler) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("cartoon_trips", Context.MODE_PRIVATE)

    var enabled by mutableStateOf(prefs.getBoolean("enabled", true))
        private set
    var trips by mutableStateOf(load())
        private set
    /** The drive in progress, for the Trips screen. */
    var live by mutableStateOf<Trip?>(null)
        private set
    /** The drive that just finished, shown once as a summary. */
    var summary by mutableStateOf<Trip?>(null)

    fun configure(enabled: Boolean) {
        this.enabled = enabled
        prefs.edit().putBoolean("enabled", enabled).apply()
        scheduler.require("trips", if (enabled) PIDS else emptySet())
    }

    fun delete(trip: Trip) {
        trips = trips.filter { it != trip }
        save()
    }

    fun clearAll() {
        val id = Vehicles.activeId(app)
        trips = trips.filter { it.vehicle != id }
        save()
    }

    private var tracking = false
    private var startAt = 0L
    private var lastAt = 0L
    private var km = 0f
    private var maxKmh = 0f
    private var movingSec = 0f
    private var idleSec = 0f
    private var stoppedSec = 0f
    private var maxCoolant: Float? = null
    private var minVolts: Float? = null

    fun start(scope: CoroutineScope) {
        scheduler.require("trips", if (enabled) PIDS else emptySet())
        scope.launch {
            while (true) {
                delay(1000)
                tick()
            }
        }
    }

    private fun snapshot(end: Long): Trip {
        val moving = movingSec.coerceAtLeast(1f)
        return Trip(
            start = startAt, end = end, km = km, maxKmh = maxKmh,
            avgKmh = km / (moving / 3600f), idleSec = idleSec.toInt(),
            maxCoolant = maxCoolant, minVolts = minVolts, vehicle = Vehicles.activeId(app),
        )
    }

    private fun tick() {
        val connected = obd.state == ObdManager.State.CONNECTED
        if (!enabled || !connected) {
            if (tracking) finish()
            return
        }
        val now = SystemClock.elapsedRealtime()
        val speed = obd.values["0D"] ?: 0f
        val rpm = obd.values["0C"] ?: 0f
        if (!tracking) {
            if (speed > 3f) {
                tracking = true
                startAt = System.currentTimeMillis()
                lastAt = now
                km = 0f; maxKmh = 0f; movingSec = 0f; idleSec = 0f; stoppedSec = 0f
                maxCoolant = null; minVolts = null
            }
            return
        }
        val dt = ((now - lastAt) / 1000f).coerceIn(0f, 5f)
        lastAt = now
        km += speed * dt / 3600f
        if (speed > maxKmh) maxKmh = speed
        if (speed >= 2f) {
            movingSec += dt
            stoppedSec = 0f
        } else {
            stoppedSec += dt
            if (rpm > 400f) idleSec += dt
        }
        obd.values["05"]?.let { if (maxCoolant == null || it > maxCoolant!!) maxCoolant = it }
        obd.values["ATRV"]?.let { if (it > 5f && (minVolts == null || it < minVolts!!)) minVolts = it }
        live = snapshot(System.currentTimeMillis())
        if (stoppedSec > 180f) finish()
    }

    private fun finish() {
        tracking = false
        live = null
        val trip = snapshot(System.currentTimeMillis() - (stoppedSec * 1000).toLong())
        if (trip.km >= 0.2f) {
            trips = (listOf(trip) + trips).take(200)
            save()
            summary = trip
        }
    }

    private fun load(): List<Trip> = runCatching {
        val arr = JSONArray(prefs.getString("trips", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Trip(
                start = o.getLong("start"), end = o.getLong("end"),
                km = o.getDouble("km").toFloat(), maxKmh = o.getDouble("max").toFloat(),
                avgKmh = o.getDouble("avg").toFloat(), idleSec = o.getInt("idle"),
                maxCoolant = if (o.has("coolant")) o.getDouble("coolant").toFloat() else null,
                minVolts = if (o.has("volts")) o.getDouble("volts").toFloat() else null,
                vehicle = o.optString("vehicle", Vehicles.FIRST),
            )
        }
    }.getOrDefault(emptyList())

    private fun save() {
        val arr = JSONArray()
        trips.forEach { t ->
            arr.put(
                JSONObject().put("start", t.start).put("end", t.end).put("km", t.km.toDouble())
                    .put("max", t.maxKmh.toDouble()).put("avg", t.avgKmh.toDouble()).put("idle", t.idleSec)
                    .apply {
                        t.maxCoolant?.let { put("coolant", it.toDouble()) }
                        t.minVolts?.let { put("volts", it.toDouble()) }
                    }
                    .put("vehicle", t.vehicle)
            )
        }
        prefs.edit().putString("trips", arr.toString()).apply()
    }

    companion object {
        private val PIDS = setOf("0D", "0C", "05", "ATRV")

        /** Spreadsheet-friendly export. */
        fun csv(trips: List<Trip>, metric: Boolean): String {
            val df = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)
            val dist = if (metric) "km" else "mi"
            val spd = if (metric) "kmh" else "mph"
            val temp = if (metric) "C" else "F"
            val sb = StringBuilder("start,minutes,distance_$dist,avg_$spd,max_$spd,idle_min,max_coolant_$temp,min_volts\n")
            trips.forEach { t ->
                val k = if (metric) 1f else 0.621371f
                sb.append(df.format(java.util.Date(t.start))).append(',')
                    .append("%.0f".format((t.end - t.start) / 60000f)).append(',')
                    .append("%.1f".format(Locale.US, t.km * k)).append(',')
                    .append("%.0f".format(Locale.US, t.avgKmh * k)).append(',')
                    .append("%.0f".format(Locale.US, t.maxKmh * k)).append(',')
                    .append("%.0f".format(t.idleSec / 60f)).append(',')
                    .append(t.maxCoolant?.let { "%.0f".format(Locale.US, if (metric) it else it * 9f / 5f + 32f) } ?: "").append(',')
                    .append(t.minVolts?.let { "%.1f".format(Locale.US, it) } ?: "").append('\n')
            }
            return sb.toString()
        }
    }
}

/* ------------------------------ Health baseline ------------------------------ */

/** What "normal" looks like for this car at warm idle, and how far today's readings have drifted. */
@Stable
class HealthBaseline(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("cartoon_health", Context.MODE_PRIVATE)

    class Reading(val key: String, val label: String, val tolerance: Float)

    var version by mutableStateOf(0)
        private set

    fun saved(): Map<String, Float>? = runCatching {
        val o = JSONObject(prefs.getString("base_" + Vehicles.activeId(app), null) ?: return null)
        o.keys().asSequence().associateWith { o.getDouble(it).toFloat() }
    }.getOrNull()

    fun savedAt(): Long = prefs.getLong("at_" + Vehicles.activeId(app), 0L)

    fun save(values: Map<String, Float>) {
        val o = JSONObject()
        values.forEach { (k, v) -> o.put(k, v.toDouble()) }
        prefs.edit()
            .putString("base_" + Vehicles.activeId(app), o.toString())
            .putLong("at_" + Vehicles.activeId(app), System.currentTimeMillis())
            .apply()
        version++
    }

    fun clear() {
        prefs.edit().remove("base_" + Vehicles.activeId(app)).remove("at_" + Vehicles.activeId(app)).apply()
        version++
    }

    companion object {
        /** Readings that say something about engine health at idle. Tolerance is in the sensor's own unit. */
        val READINGS = listOf(
            Reading("ATRV", "Battery voltage", 0.5f),
            Reading("07", "Long fuel trim", 4f),
            Reading("0B", "Manifold pressure", 8f),
            Reading("04", "Engine load", 10f),
            Reading("05", "Coolant temp", 12f),
            Reading("10", "Air flow (MAF)", 1.5f),
        )
        val PIDS: Set<String> = READINGS.map { it.key }.toSet() + setOf("0C", "0D")

        /** Warm and idling: the only fair time to compare. */
        fun atWarmIdle(values: Map<String, Float>): Boolean {
            val rpm = values["0C"] ?: return false
            val speed = values["0D"] ?: 0f
            val coolant = values["05"] ?: return false
            return rpm in 400f..1300f && speed < 2f && coolant >= 75f
        }
    }
}

/* ------------------------------ Audible alerts ------------------------------ */

/** Beeps and shows a banner when the engine runs hot. Low-voltage warnings also beep. */
@Stable
class AlertMonitor(
    context: Context,
    private val obd: ObdManager,
    private val voltage: VoltageMonitor,
    private val scheduler: PollScheduler,
) {
    private val prefs = context.applicationContext.getSharedPreferences("cartoon_alerts", Context.MODE_PRIVATE)

    var enabled by mutableStateOf(prefs.getBoolean("enabled", true))
        private set
    var sound by mutableStateOf(prefs.getBoolean("sound", true))
        private set
    /** Coolant limit in °C. */
    var coolantLimit by mutableStateOf(prefs.getFloat("coolant", 110f))
        private set
    var hot by mutableStateOf(false)
        private set

    val coolant: Float? get() = obd.values["05"]

    fun configure(enabled: Boolean, sound: Boolean, coolantLimit: Float) {
        this.enabled = enabled
        this.sound = sound
        this.coolantLimit = coolantLimit.coerceIn(95f, 125f)
        prefs.edit().putBoolean("enabled", enabled).putBoolean("sound", sound)
            .putFloat("coolant", this.coolantLimit).apply()
        scheduler.require("alerts", if (enabled) setOf("05") else emptySet())
        if (!enabled) hot = false
    }

    fun start(scope: CoroutineScope) {
        scheduler.require("alerts", if (enabled) setOf("05") else emptySet())

        // Demo car: a short overheat every two minutes so the alert can be tried.
        obd.demoHooks += { t, out -> if (t % 120f in 100f..108f) out["05"] = 115f }

        scope.launch {
            snapshotFlow { obd.values["05"] to obd.state }.collect { (v, state) ->
                if (state != ObdManager.State.CONNECTED || v == null || !enabled) {
                    hot = false
                    return@collect
                }
                if (!hot && v > coolantLimit) {
                    hot = true
                    beep()
                } else if (hot && v < coolantLimit - 5f) {
                    hot = false
                }
            }
        }
        scope.launch {
            snapshotFlow { voltage.low }.collect { low -> if (low && enabled) beep() }
        }
        scope.launch {
            // Keep reminding every 20 seconds while it is still hot.
            while (true) {
                delay(20_000)
                if (hot && enabled) beep()
            }
        }
    }

    private suspend fun beep() {
        if (!sound) return
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull() ?: return
        repeat(3) {
            tone.startTone(ToneGenerator.TONE_PROP_BEEP2, 250)
            delay(450)
        }
        tone.release()
    }
}


/* ------------------------------ Live mileage and service banner ------------------------------ */

/**
 * Keeps each vehicle's odometer up to date while the adapter is connected. It uses the car's own
 * odometer (PID A6) when it reports one, otherwise it adds up speed over time. It also raises a
 * banner when a service reminder crosses into "due soon" or "overdue".
 */
@Stable
class OdometerTracker(context: Context, private val obd: ObdManager, private val scheduler: PollScheduler) {
    private val app = context.applicationContext
    private val store = MaintenanceStore(app)
    private val settings = app.getSharedPreferences("cartoon_settings", Context.MODE_PRIVATE)

    /** Text for the in-app banner, or null. */
    var banner by mutableStateOf<String?>(null)
        private set

    fun dismiss() { banner = null }

    private var lastAt = 0L
    private var pending = 0f
    private var lastFlush = 0L
    private var baseOdoKm: Float? = null
    private var baseStored = 0f

    private fun metric() = settings.getBoolean("metric_units", false)
    private fun vehicle() = if (obd.inDemo) "demo" else Vehicles.activeId(app)

    fun start(scope: CoroutineScope) {
        scheduler.require("odometer", setOf("0D", "A6"))
        scope.launch {
            while (true) {
                delay(1000)
                tick()
            }
        }
    }

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val connected = obd.state == ObdManager.State.CONNECTED
        val v = vehicle()
        if (!connected) {
            lastAt = 0L
            baseOdoKm = null
        } else {
            val dt = if (lastAt == 0L) 0f else ((now - lastAt) / 1000f).coerceIn(0f, 5f)
            lastAt = now
            val metric = metric()
            val carOdo = obd.values["A6"]
            if (carOdo != null && "A6" in obd.supported && !obd.inDemo) {
                // The car's own odometer wins. Follow its changes on top of what the user entered.
                val base = baseOdoKm ?: carOdo.also {
                    baseOdoKm = it
                    baseStored = store.odometerExact(v)
                }
                val driven = (carOdo - base).coerceAtLeast(0f) * (if (metric) 1f else 0.621371f)
                val target = baseStored + driven
                val delta = target - store.odometerExact(v)
                if (delta > 0.05f) pending += delta
            } else {
                val speed = obd.values["0D"] ?: 0f // km/h
                val scale = if (obd.inDemo) 60f else 1f // demo drives 60 times faster than real time
                val dist = speed * dt / 3600f * scale * (if (metric) 1f else 0.621371f)
                pending += dist
            }
            if (pending > 0f && now - lastFlush > 5000) {
                store.addDistance(v, pending)
                pending = 0f
                lastFlush = now
            }
        }
        checkService(v)
    }

    private fun checkService(v: String) {
        val unit = if (metric()) "km" else "mi"
        val odo = store.odometer(v)
        val done = store.alerted(v)
        for (item in store.items(v)) {
            val d = MaintenanceStore.due(item, odo, unit)
            val status = if (d.overdue) "over" else if (d.soon) "soon" else continue
            val key = "${item.id}@${item.lastDist}@$status"
            if (key in done) continue
            store.markAlerted(v, key)
            banner = if (status == "over") "${item.name} is overdue (${d.text})" else "${item.name} is due soon (${d.text})"
        }
    }
}

/** Shown over the app when a service reminder is crossed. Tap to dismiss. */
@Composable
fun ServiceBanner(tracker: OdometerTracker, modifier: Modifier = Modifier) {
    val text = tracker.banner ?: return
    val ui = LocalUi.current
    Text(
        "Service: $text  \u2715",
        fontSize = ui.small,
        fontWeight = FontWeight.Bold,
        color = Color.Black,
        modifier = modifier
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(Color(0xFFFFB547))
            .clickable { tracker.dismiss() }
            .padding(horizontal = 18.dp, vertical = 8.dp),
    )
}
