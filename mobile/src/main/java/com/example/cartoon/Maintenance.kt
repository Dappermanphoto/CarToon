package com.example.cartoon

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ServiceItem(
    val id: String,
    val name: String,
    /** Distance interval in the user's unit (miles or km). 0 means no distance limit. */
    val everyDist: Int,
    val everyMonths: Int,
    val lastDist: Int,
    val lastDate: Long,
)

/** One completed service. */
data class ServiceLog(val id: String, val name: String, val date: Long, val dist: Int, val notes: String, val cost: String)

/** Service reminders and history, kept per vehicle. The pseudo vehicle "demo" is used while a demo runs. */
class MaintenanceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cartoon_maintenance", Context.MODE_PRIVATE)

    var version by mutableIntStateOf(0)
        private set

    fun odometerExact(vehicle: String): Float =
        if (prefs.contains("odof_$vehicle")) prefs.getFloat("odof_$vehicle", 0f) else prefs.getInt("odo_$vehicle", 0).toFloat()

    fun odometer(vehicle: String): Int = odometerExact(vehicle).toInt()

    fun setOdometer(vehicle: String, value: Int) {
        prefs.edit().putFloat("odof_$vehicle", value.coerceAtLeast(0).toFloat()).apply()
        version++
    }

    /** Adds driven distance (in the user's unit) to the odometer. */
    fun addDistance(vehicle: String, d: Float) {
        if (d <= 0f) return
        prefs.edit().putFloat("odof_$vehicle", odometerExact(vehicle) + d).apply()
        version++
    }

    fun items(vehicle: String): List<ServiceItem> = runCatching {
        val arr = JSONArray(prefs.getString("items_$vehicle", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            ServiceItem(
                o.getString("id"), o.getString("name"), o.getInt("dist"), o.getInt("months"),
                o.getInt("lastDist"), o.getLong("lastDate"),
            )
        }
    }.getOrDefault(emptyList())

    private fun save(vehicle: String, list: List<ServiceItem>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("dist", it.everyDist)
                    .put("months", it.everyMonths).put("lastDist", it.lastDist).put("lastDate", it.lastDate)
            )
        }
        prefs.edit().putString("items_$vehicle", arr.toString()).apply()
        version++
    }

    fun log(vehicle: String): List<ServiceLog> = runCatching {
        val arr = JSONArray(prefs.getString("log_$vehicle", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            ServiceLog(o.getString("id"), o.getString("name"), o.getLong("date"), o.getInt("dist"), o.optString("notes"), o.optString("cost"))
        }
    }.getOrDefault(emptyList())

    private fun saveLog(vehicle: String, list: List<ServiceLog>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("name", it.name).put("date", it.date).put("dist", it.dist)
                .put("notes", it.notes).put("cost", it.cost))
        }
        prefs.edit().putString("log_$vehicle", arr.toString()).apply()
        version++
    }

    fun deleteLog(vehicle: String, id: String) = saveLog(vehicle, log(vehicle).filter { it.id != id })

    /** Adds the standard reminders the first time a vehicle is looked at. */
    fun seedDefaults(vehicle: String, metric: Boolean) {
        if (prefs.getBoolean("seeded_$vehicle", false)) return
        prefs.edit().putBoolean("seeded_$vehicle", true).apply()
        if (items(vehicle).isNotEmpty()) return
        val odo = odometer(vehicle)
        val now = System.currentTimeMillis()
        save(vehicle, defaults(metric).mapIndexed { i, (n, d, m) -> ServiceItem("d$i", n, d, m, odo, now) })
    }

    /** Fresh demo data: a truck with 4,600 miles on the clock and the oil change nearly due. */
    fun resetDemo(metric: Boolean) {
        val v = "demo"
        prefs.edit().remove("log_$v").remove("alerted_$v").putBoolean("seeded_$v", true)
            .putFloat("odof_$v", if (metric) 7400f else 4600f).apply()
        val now = System.currentTimeMillis()
        save(v, defaults(metric).mapIndexed { i, (n, d, m) -> ServiceItem("d$i", n, d, m, 0, now) })
    }

    fun add(vehicle: String, name: String, dist: Int, months: Int) {
        val list = items(vehicle)
        if (list.any { it.name == name }) return
        save(vehicle, list + ServiceItem(System.nanoTime().toString(), name, dist, months, odometer(vehicle), System.currentTimeMillis()))
    }

    fun update(vehicle: String, id: String, dist: Int, months: Int) {
        save(vehicle, items(vehicle).map { if (it.id == id) it.copy(everyDist = dist, everyMonths = months) else it })
    }

    /** Records a completed service and resets that reminder to the given date and mileage. */
    fun logService(vehicle: String, id: String, date: Long, dist: Int, notes: String, cost: String) {
        val item = items(vehicle).firstOrNull { it.id == id } ?: return
        save(vehicle, items(vehicle).map { if (it.id == id) it.copy(lastDist = dist, lastDate = date) else it })
        saveLog(vehicle, listOf(ServiceLog(System.nanoTime().toString(), item.name, date, dist, notes, cost)) + log(vehicle))
    }

    fun remove(vehicle: String, id: String) = save(vehicle, items(vehicle).filter { it.id != id })

    fun alerted(vehicle: String): Set<String> = prefs.getStringSet("alerted_$vehicle", emptySet()).orEmpty()
    fun markAlerted(vehicle: String, key: String) {
        prefs.edit().putStringSet("alerted_$vehicle", alerted(vehicle) + key).apply()
    }

    /** How many services are overdue, for a quick count. */
    fun overdueCount(vehicle: String, unit: String = "mi"): Int = items(vehicle).count { due(it, odometer(vehicle), unit).overdue }

    class Due(val overdue: Boolean, val soon: Boolean, val text: String, val urgency: Float) {
        val label: String get() = if (overdue) "Overdue" else if (soon) "Due soon" else "Good"
    }

    companion object {
        private const val MONTH_MS = 30.44 * 24 * 3600 * 1000

        private val DEFAULTS = listOf(
            Triple("Oil & filter", 5000, 6),
            Triple("Transmission fluid & filter", 30000, 24),
            Triple("Air filter", 15000, 12),
            Triple("Spark plugs", 60000, 60),
            Triple("Brake inspection", 12000, 12),
            Triple("Tire rotation", 7500, 6),
        )

        private val PRESETS = listOf(
            Triple("Coolant flush", 30000, 24),
            Triple("Brake fluid", 0, 24),
            Triple("Fuel filter", 30000, 24),
            Triple("Serpentine belt", 60000, 60),
            Triple("Wiper blades", 0, 12),
            Triple("Smog / registration", 0, 12),
        )

        private fun conv(list: List<Triple<String, Int, Int>>, metric: Boolean) =
            if (!metric) list else list.map { (n, d, m) -> Triple(n, if (d == 0) 0 else (d * 1.6f / 100f).roundToInt() * 100, m) }

        fun defaults(metric: Boolean) = conv(DEFAULTS, metric)
        fun presets(metric: Boolean) = conv(PRESETS, metric)

        /** "Due soon" starts this close to the limit: 500 miles (800 km), or 30 days. */
        fun soonDistance(unit: String) = if (unit == "km") 800 else 500

        fun due(item: ServiceItem, odo: Int, unit: String = "mi", now: Long = System.currentTimeMillis()): Due {
            var overdue = false
            var soon = false
            var urgency = Float.MAX_VALUE
            val parts = ArrayList<String>()
            if (item.everyDist > 0) {
                val left = item.lastDist + item.everyDist - odo
                parts += if (left >= 0) "$left $unit left" else "${-left} $unit overdue"
                if (left < 0) overdue = true else if (left <= soonDistance(unit)) soon = true
                urgency = minOf(urgency, left.toFloat() / item.everyDist)
            }
            if (item.everyMonths > 0) {
                val days = ((item.lastDate + (item.everyMonths * MONTH_MS).toLong() - now) / 86_400_000L).toInt()
                parts += if (days >= 0) "$days days left" else "${-days} days overdue"
                if (days < 0) overdue = true else if (days < 30) soon = true
                urgency = minOf(urgency, days / (item.everyMonths * 30.44f))
            }
            return Due(overdue, soon, parts.joinToString(" · "), urgency)
        }
    }
}

private val dateFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US).apply { isLenient = false }
private fun fmtDate(ms: Long): String = dateFmt.format(Date(ms))

@Composable
fun MaintenanceSection(profile: ProfileStore, metric: Boolean, demo: Boolean = false) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { MaintenanceStore(context) }
    val vid = if (demo) "demo" else profile.activeId
    val unit = if (metric) "km" else "mi"
    LaunchedEffect(demo, vid) {
        if (demo) store.resetDemo(metric) else store.seedDefaults(vid, metric)
    }
    val version = store.version
    val items = remember(version, vid) { store.items(vid) }
    val history = remember(version, vid) { store.log(vid) }
    val odo = remember(version, vid) { store.odometer(vid) }
    var odoText by remember(vid, version) { mutableStateOf(if (odo == 0) "" else odo.toString()) }
    var logging by remember { mutableStateOf<ServiceItem?>(null) }
    var editing by remember { mutableStateOf<ServiceItem?>(null) }
    var custom by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    Text(
        if (demo) "SERVICE REMINDERS (DEMO)" else "SERVICE REMINDERS",
        fontSize = ui.small, fontWeight = FontWeight.Bold, color = cs.primary, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(cs.surface)
            .padding(18.dp)
    ) {
        if (ui.car) {
            Text(
                if (odo == 0) "Set the odometer on the phone." else "Odometer: $odo $unit",
                fontSize = ui.body,
                fontWeight = FontWeight.SemiBold,
            )
        } else {
            OutlinedTextField(
                value = odoText,
                onValueChange = {
                    odoText = it.filter(Char::isDigit).take(7)
                    store.setOdometer(vid, odoText.toIntOrNull() ?: 0)
                },
                label = { Text("Odometer now ($unit)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Text(
            if (demo) "Demo mileage speeds up while the simulated truck drives."
            else "Counts up while the adapter is connected (from the car's odometer if it reports one, otherwise from speed). Type your real reading above to correct it.",
            fontSize = ui.small,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp),
        )
        Spacer(Modifier.height(12.dp))

        val sorted = items.sortedBy { MaintenanceStore.due(it, odo, unit).urgency }
        if (sorted.isEmpty()) {
            Text("Add the services you want to keep track of.", fontSize = ui.small, color = cs.onSurfaceVariant)
        }
        sorted.forEach { item ->
            val d = MaintenanceStore.due(item, odo, unit)
            val tint = when {
                d.overdue -> Color(0xFFFF5449)
                d.soon -> Color(0xFFFFB547)
                else -> Color(0xFF4CD08A)
            }
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(item.name, fontSize = ui.body, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                        Spacer(Modifier.width(8.dp))
                        Text(d.label, fontSize = ui.small, fontWeight = FontWeight.Bold, color = tint)
                    }
                    Text(d.text, fontSize = ui.small, color = if (d.overdue || d.soon) tint else cs.onSurfaceVariant)
                }
                if (ui.car) {
                    Pill("Done", selected = d.overdue || d.soon, onClick = {
                        store.logService(vid, item.id, System.currentTimeMillis(), store.odometer(vid), "", "")
                    })
                } else {
                    Pill("Log", selected = d.overdue || d.soon, onClick = { logging = item })
                    Spacer(Modifier.width(6.dp))
                    Pill("Edit", color = cs.onSurfaceVariant, onClick = { editing = item })
                }
            }
        }

        if (!ui.car) {
            Spacer(Modifier.height(10.dp))
            Text("Add a reminder", fontSize = ui.small, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("+ Custom", selected = true, onClick = { custom = true })
                MaintenanceStore.presets(metric).filter { p -> items.none { it.name == p.first } }.forEach { (name, dist, months) ->
                    Pill(name, color = cs.onSurfaceVariant, onClick = { store.add(vid, name, dist, months) })
                }
            }
        }

        if (history.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Pill(if (showHistory) "Hide history" else "Service history (${history.size})", color = cs.onSurfaceVariant, onClick = { showHistory = !showHistory })
            if (showHistory) {
                history.forEach { h ->
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.Top) {
                        Column(Modifier.weight(1f)) {
                            Text(h.name, fontSize = ui.body, fontWeight = FontWeight.SemiBold)
                            Text(
                                listOf(fmtDate(h.date), "${h.dist} $unit", h.cost.takeIf { it.isNotBlank() }?.let { "$$it" }).filterNotNull().joinToString(" · "),
                                fontSize = ui.small, color = cs.onSurfaceVariant,
                            )
                            if (h.notes.isNotBlank()) Text(h.notes, fontSize = ui.small, color = cs.onSurfaceVariant)
                        }
                        if (!ui.car) Pill("Delete", color = cs.onSurfaceVariant, onClick = { store.deleteLog(vid, h.id) })
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(20.dp))

    logging?.let { item ->
        var date by remember(item.id) { mutableStateOf(fmtDate(System.currentTimeMillis())) }
        var miles by remember(item.id) { mutableStateOf(odo.toString()) }
        var notes by remember(item.id) { mutableStateOf("") }
        var cost by remember(item.id) { mutableStateOf("") }
        val parsed = runCatching { dateFmt.parse(date)?.time }.getOrNull()
        AlertDialog(
            onDismissRequest = { logging = null },
            title = { Text("Log: ${item.name}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(date, { date = it.take(10) }, label = { Text("Date (yyyy-mm-dd)") }, singleLine = true, isError = parsed == null)
                    OutlinedTextField(
                        miles, { miles = it.filter(Char::isDigit).take(7) }, label = { Text("Mileage ($unit)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        cost, { cost = it.filter { c -> c.isDigit() || c == '.' }.take(8) }, label = { Text("Cost (optional)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    )
                    OutlinedTextField(notes, { notes = it.take(120) }, label = { Text("Notes (optional)") })
                }
            },
            confirmButton = {
                TextButton(enabled = parsed != null && miles.isNotEmpty(), onClick = {
                    val m = miles.toIntOrNull() ?: odo
                    store.logService(vid, item.id, parsed ?: System.currentTimeMillis(), m, notes.trim(), cost)
                    if (m > store.odometer(vid)) store.setOdometer(vid, m)
                    logging = null
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { logging = null }) { Text("Cancel") } },
        )
    }

    editing?.let { item ->
        var dist by remember(item.id) { mutableStateOf(item.everyDist.toString()) }
        var months by remember(item.id) { mutableStateOf(item.everyMonths.toString()) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text(item.name) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        dist, { dist = it.filter(Char::isDigit).take(7) }, label = { Text("Every ($unit, 0 = no distance limit)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        months, { months = it.filter(Char::isDigit).take(3) }, label = { Text("Every (months, 0 = no time limit)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    store.update(vid, item.id, dist.toIntOrNull() ?: 0, months.toIntOrNull() ?: 0)
                    editing = null
                }) { Text("Save") }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { store.remove(vid, item.id); editing = null }) { Text("Remove") }
                    TextButton(onClick = { editing = null }) { Text("Cancel") }
                }
            },
        )
    }

    if (custom) {
        var name by remember { mutableStateOf("") }
        var dist by remember { mutableStateOf("") }
        var months by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { custom = false },
            title = { Text("Custom reminder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(name, { name = it.take(30) }, label = { Text("What") }, singleLine = true)
                    OutlinedTextField(
                        dist, { dist = it.filter(Char::isDigit).take(7) }, label = { Text("Every ($unit)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                    OutlinedTextField(
                        months, { months = it.filter(Char::isDigit).take(3) }, label = { Text("Every (months)") }, singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && (dist.isNotEmpty() || months.isNotEmpty()), onClick = {
                    store.add(vid, name.trim(), dist.toIntOrNull() ?: 0, months.toIntOrNull() ?: 0)
                    custom = false
                }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { custom = false }) { Text("Cancel") } },
        )
    }
}
