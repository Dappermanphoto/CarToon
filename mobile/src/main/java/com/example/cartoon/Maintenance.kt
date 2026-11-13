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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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

data class ServiceItem(
    val id: String,
    val name: String,
    /** Distance interval in the user's unit (miles or km). 0 means no distance limit. */
    val everyDist: Int,
    val everyMonths: Int,
    val lastDist: Int,
    val lastDate: Long,
)

/** Service reminders, kept per vehicle. */
class MaintenanceStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cartoon_maintenance", Context.MODE_PRIVATE)

    var version by mutableIntStateOf(0)
        private set

    fun odometer(vehicle: String): Int = prefs.getInt("odo_$vehicle", 0)

    fun setOdometer(vehicle: String, value: Int) {
        prefs.edit().putInt("odo_$vehicle", value.coerceAtLeast(0)).apply()
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

    fun add(vehicle: String, name: String, dist: Int, months: Int) {
        val list = items(vehicle)
        if (list.any { it.name == name }) return
        save(vehicle, list + ServiceItem(System.nanoTime().toString(), name, dist, months, odometer(vehicle), System.currentTimeMillis()))
    }

    /** Marks a service as done today, at the current odometer reading. */
    fun done(vehicle: String, id: String) {
        save(vehicle, items(vehicle).map {
            if (it.id == id) it.copy(lastDist = odometer(vehicle), lastDate = System.currentTimeMillis()) else it
        })
    }

    fun remove(vehicle: String, id: String) = save(vehicle, items(vehicle).filter { it.id != id })

    /** How many services are overdue, for a quick count. */
    fun overdueCount(vehicle: String): Int = items(vehicle).count { due(it, odometer(vehicle)).overdue }

    class Due(val overdue: Boolean, val soon: Boolean, val text: String, val urgency: Float)

    companion object {
        private const val MONTH_MS = 30.44 * 24 * 3600 * 1000

        private val PRESETS = listOf(
            Triple("Oil & filter", 5000, 6),
            Triple("Tire rotation", 7500, 6),
            Triple("Air filter", 15000, 12),
            Triple("Brake fluid", 0, 24),
            Triple("Coolant flush", 30000, 24),
            Triple("Transmission fluid", 30000, 24),
            Triple("Spark plugs", 60000, 60),
            Triple("Smog / registration", 0, 12),
        )

        fun presets(metric: Boolean): List<Triple<String, Int, Int>> =
            if (!metric) PRESETS else PRESETS.map { (n, d, m) -> Triple(n, (d * 1.6f / 100f).roundToInt() * 100, m) }

        fun due(item: ServiceItem, odo: Int, unit: String = "mi", now: Long = System.currentTimeMillis()): Due {
            var overdue = false
            var soon = false
            var urgency = Float.MAX_VALUE
            val parts = ArrayList<String>()
            if (item.everyDist > 0) {
                val left = item.lastDist + item.everyDist - odo
                parts += if (left >= 0) "$left $unit left" else "${-left} $unit overdue"
                if (left < 0) overdue = true else if (left < item.everyDist * 0.1f) soon = true
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

@Composable
fun MaintenanceSection(profile: ProfileStore, metric: Boolean) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val context = androidx.compose.ui.platform.LocalContext.current
    val store = remember { MaintenanceStore(context) }
    val vid = profile.activeId
    val unit = if (metric) "km" else "mi"
    val version = store.version
    val items = remember(version, vid) { store.items(vid) }
    val odo = remember(version, vid) { store.odometer(vid) }
    var odoText by remember(vid, version) { mutableStateOf(if (odo == 0) "" else odo.toString()) }

    Text("SERVICE REMINDERS", fontSize = ui.small, fontWeight = FontWeight.Bold, color = cs.primary, modifier = Modifier.padding(start = 6.dp, bottom = 8.dp))
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(cs.surface)
            .padding(18.dp)
    ) {
        if (ui.car) {
            Text(
                if (odo == 0) "Set the odometer on the phone." else "Odometer: $odo $unit (update it on the phone)",
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
        Spacer(Modifier.height(12.dp))

        val sorted = items.sortedBy { MaintenanceStore.due(it, odo, unit).urgency }
        if (sorted.isEmpty()) {
            Text(
                "Add the services you want to keep track of. CarToon tells you how much distance or time is left.",
                fontSize = ui.small,
                color = cs.onSurfaceVariant,
            )
        }
        sorted.forEach { item ->
            val d = MaintenanceStore.due(item, odo, unit)
            Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontSize = ui.body, fontWeight = FontWeight.SemiBold)
                    Text(
                        d.text,
                        fontSize = ui.small,
                        color = when {
                            d.overdue -> Color(0xFFFF5449)
                            d.soon -> Color(0xFFFFB547)
                            else -> cs.onSurfaceVariant
                        },
                    )
                }
                Pill("Done", selected = d.overdue || d.soon, onClick = { store.done(vid, item.id) })
                Spacer(Modifier.width(6.dp))
                Pill("Remove", color = cs.onSurfaceVariant, onClick = { store.remove(vid, item.id) })
            }
        }

        Spacer(Modifier.height(10.dp))
        Text("Add a reminder", fontSize = ui.small, color = cs.onSurfaceVariant)
        Spacer(Modifier.height(6.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MaintenanceStore.presets(metric).filter { p -> items.none { it.name == p.first } }.forEach { (name, dist, months) ->
                Pill(name, color = cs.onSurfaceVariant, onClick = { store.add(vid, name, dist, months) })
            }
        }
        Text(
            "Reminders show here in the Profile tab. Done resets a reminder to today and the current odometer.",
            fontSize = ui.small,
            color = cs.onSurfaceVariant,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    Spacer(Modifier.height(20.dp))
}
