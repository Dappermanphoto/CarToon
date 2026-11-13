package com.example.cartoon

import android.content.Context
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Standard (SAE J1979) mode 01 sensors CarToon doesn't show by default, with how to turn the reply bytes into a number. */
class KnownPid(val key: String, val name: String, val unit: String, val formula: String, val min: Float, val max: Float)

object PidTable {
    private val all = listOf(
        KnownPid("1F", "Run time since start", "s", "(A*256)+B", 0f, 3600f),
        KnownPid("21", "Distance with MIL on", "km", "(A*256)+B", 0f, 1000f),
        KnownPid("22", "Fuel rail pressure (vacuum)", "kPa", "((A*256)+B)*0.079", 0f, 500f),
        KnownPid("23", "Fuel rail pressure (gauge)", "kPa", "((A*256)+B)*10", 0f, 65000f),
        KnownPid("2C", "Commanded EGR", "%", "A*100/255", 0f, 100f),
        KnownPid("2D", "EGR error", "%", "(A*100/128)-100", -100f, 100f),
        KnownPid("2E", "Commanded evap purge", "%", "A*100/255", 0f, 100f),
        KnownPid("30", "Warm-ups since codes cleared", "", "A", 0f, 255f),
        KnownPid("31", "Distance since codes cleared", "km", "(A*256)+B", 0f, 10000f),
        KnownPid("32", "Evap vapor pressure", "Pa", "((A*256)+B)/4", 0f, 8000f),
        KnownPid("3C", "Catalyst temp B1S1", "°C", "((A*256)+B)/10-40", -40f, 900f),
        KnownPid("3D", "Catalyst temp B2S1", "°C", "((A*256)+B)/10-40", -40f, 900f),
        KnownPid("3E", "Catalyst temp B1S2", "°C", "((A*256)+B)/10-40", -40f, 900f),
        KnownPid("3F", "Catalyst temp B2S2", "°C", "((A*256)+B)/10-40", -40f, 900f),
        KnownPid("43", "Absolute load", "%", "((A*256)+B)*100/255", 0f, 100f),
        KnownPid("44", "Commanded air/fuel ratio (lambda)", "λ", "((A*256)+B)*2/65536", 0f, 2f),
        KnownPid("45", "Relative throttle position", "%", "A*100/255", 0f, 100f),
        KnownPid("47", "Absolute throttle B", "%", "A*100/255", 0f, 100f),
        KnownPid("49", "Accelerator pedal D", "%", "A*100/255", 0f, 100f),
        KnownPid("4A", "Accelerator pedal E", "%", "A*100/255", 0f, 100f),
        KnownPid("4C", "Commanded throttle actuator", "%", "A*100/255", 0f, 100f),
        KnownPid("4D", "Time run with MIL on", "min", "(A*256)+B", 0f, 1000f),
        KnownPid("4E", "Time since codes cleared", "min", "(A*256)+B", 0f, 10000f),
        KnownPid("52", "Ethanol fuel", "%", "A*100/255", 0f, 100f),
        KnownPid("5A", "Relative accelerator pedal", "%", "A*100/255", 0f, 100f),
        KnownPid("5E", "Engine fuel rate", "L/h", "((A*256)+B)/20", 0f, 100f),
        KnownPid("61", "Driver demand torque", "%", "A-125", -125f, 125f),
        KnownPid("62", "Actual engine torque", "%", "A-125", -125f, 125f),
        KnownPid("63", "Engine reference torque", "Nm", "(A*256)+B", 0f, 1000f),
    ) + (0x14..0x1B).map { KnownPid("%02X".format(it), "O2 sensor voltage #${it - 0x13}", "V", "A/200", 0f, 1.3f) } +
        (0x24..0x2B).map { KnownPid("%02X".format(it), "O2 sensor lambda #${it - 0x23}", "λ", "((A*256)+B)*2/65536", 0f, 2f) } +
        (0x34..0x3B).map { KnownPid("%02X".format(it), "O2 sensor lambda #${it - 0x33} (current)", "λ", "((A*256)+B)*2/65536", 0f, 2f) }

    fun find(key: String): KnownPid? = all.firstOrNull { it.key == key.uppercase() }
}

/** One sensor the car says it supports but CarToon doesn't know. */
data class UnknownEntry(val key: String, val vehicle: String, val data: String, val seen: Long, val status: String)

/**
 * Runs quietly after each connection: asks the car for every sensor it supports that CarToon has no
 * definition for, and writes what came back to a text file (unknown_pids.txt) for the hidden viewer.
 */
@Stable
class UnknownPidLog(context: Context, private val obd: ObdManager) {
    private val app = context.applicationContext
    private val file = File(app.filesDir, "unknown_pids.txt")
    private val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US)

    var entries by mutableStateOf(load())
        private set

    fun text(): String = if (file.exists()) file.readText() else "# CarToon unknown PIDs\n"

    private fun vehicleId() = Vehicles.activeId(app)

    private fun load(): List<UnknownEntry> = runCatching {
        if (!file.exists()) return emptyList()
        file.readLines().filter { it.isNotBlank() && !it.startsWith("#") }.mapNotNull { line ->
            val p = line.split(" | ")
            if (p.size < 5) null else UnknownEntry(
                p[0].removePrefix("01"), p[2], p[1], runCatching { fmt.parse(p[3])?.time }.getOrNull() ?: 0L, p[4],
            )
        }
    }.getOrDefault(emptyList())

    private fun save(list: List<UnknownEntry>) {
        entries = list
        runCatching {
            file.writeText(
                "# CarToon unknown PIDs: sensors the car reports that CarToon doesn't know yet.\n" +
                    "# 01<PID> | reply bytes | vehicle | last seen | status\n" +
                    list.joinToString("\n") { "01${it.key} | ${it.data} | ${it.vehicle} | ${fmt.format(Date(it.seen))} | ${it.status}" } + "\n"
            )
        }
    }

    fun clear() = save(emptyList())

    fun start(scope: CoroutineScope) {
        scope.launch {
            var done = false
            var stable = 0
            while (true) {
                delay(2000)
                if (obd.state != ObdManager.State.CONNECTED) {
                    done = false
                    stable = 0
                    continue
                }
                if (done || obd.supported.size <= 1) continue
                if (++stable < 2) continue // let the sensor list settle first
                done = true
                collect()
            }
        }
    }

    private suspend fun collect() {
        val known = ObdPids.everything.map { it.key }.toSet()
        val skip = setOf("00", "01", "02", "03", "20", "40", "60", "80", "A0")
        val unknown = obd.supported.filter { k -> k.length == 2 && k.all { it in "0123456789ABCDEF" } && k !in known && k !in skip }.sorted()
        if (unknown.isEmpty()) return
        val vehicle = if (obd.inDemo) obd.demoName else vehicleName()
        var list = entries
        for (key in unknown) {
            val reply = obd.sendRaw("01$key") ?: continue
            val data = reply.lines().firstNotNullOfOrNull { l ->
                val s = l.replace(" ", "")
                val i = s.indexOf("41$key")
                if (i >= 0) s.substring(i + 4).takeIf { it.isNotEmpty() } else null
            }?.chunked(2)?.joinToString(" ") ?: "(no data)"
            val old = list.firstOrNull { it.key == key && it.vehicle == vehicle }
            val entry = UnknownEntry(key, vehicle, data, System.currentTimeMillis(), old?.status.orEmpty())
            list = list.filterNot { it.key == key && it.vehicle == vehicle } + entry
        }
        save(list.sortedWith(compareBy({ it.vehicle }, { it.key })))
    }

    private fun vehicleName(): String {
        val prefs = app.getSharedPreferences("cartoon_profile", Context.MODE_PRIVATE)
        val id = vehicleId()
        fun get(k: String) = prefs.getString(if (id == Vehicles.FIRST) k else "${id}_$k", "").orEmpty()
        return listOf(get("YEAR"), get("MAKE"), get("MODEL")).filter { it.isNotBlank() }.joinToString(" ").ifBlank { "My vehicle" }
    }

    /** Looks the PID up in the built-in list. On a match it adds it as a custom sensor so it shows up in the app. */
    fun search(entry: UnknownEntry): String {
        val known = PidTable.find(entry.key) ?: return "Not in the built-in list. Tap Web to look it up online."
        val vid = vehicleId()
        val cmd = "01${entry.key}"
        val existing = CustomPids.load(app, vid)
        if (existing.none { it.command == cmd }) {
            CustomPids.save(
                app, vid,
                existing + CustomPid("u${entry.key}", known.name, known.unit, "", cmd, known.formula, known.min, known.max),
            )
            obd.refreshCustom()
        }
        save(entries.map { if (it === entry || (it.key == entry.key && it.vehicle == entry.vehicle)) it.copy(status = "Added: ${known.name}") else it })
        return "Added \"${known.name}\" (${known.unit}). Find it in the Cluster, Graph and List screens."
    }

    /** What the current reply bytes work out to, for display. */
    fun preview(entry: UnknownEntry): String? {
        val known = PidTable.find(entry.key) ?: return null
        val f = Expr.compile(known.formula) ?: return null
        val bytes = entry.data.split(" ").mapNotNull { it.toIntOrNull(16) }
        if (bytes.isEmpty()) return null
        val v = f(DoubleArray(8) { bytes.getOrNull(it)?.toDouble() ?: 0.0 })
        return "%.1f %s".format(v, known.unit)
    }
}

/* ------------------------------ Hidden viewer ------------------------------ */

@Composable
fun PinDialog(onCancel: () -> Unit, onOk: () -> Unit) {
    var pin by remember { mutableStateOf("") }
    var wrong by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Enter PIN") },
        text = {
            Column {
                OutlinedTextField(
                    pin, { pin = it.filter(Char::isDigit).take(8); wrong = false },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                )
                if (wrong) Text("Wrong PIN.", color = MaterialTheme.colorScheme.error)
            }
        },
        confirmButton = { TextButton(onClick = { if (pin == "3621") onOk() else wrong = true }) { Text("OK") } },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

@Composable
fun UnknownPidsScreen(log: UnknownPidLog, onClose: () -> Unit) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    var showText by remember { mutableStateOf(false) }
    val notes = remember { mutableStateMapOf<String, String>() }

    Column(Modifier.fillMaxSize().padding(horizontal = ui.pad, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(AppIcons.Back, "Back", ui.iconButton, onClose)
            Spacer(Modifier.width(12.dp))
            Text("Unknown PIDs", fontSize = ui.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Text(
            "Sensors your car reports that CarToon doesn't know yet. They're collected automatically after each connection and saved in unknown_pids.txt.",
            fontSize = ui.small, color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Search all", selected = true, onClick = {
                log.entries.filter { !it.status.startsWith("Added") }.forEach { notes[it.key + it.vehicle] = log.search(it) }
            })
            Pill(if (showText) "Show list" else "Show text file", onClick = { showText = !showText })
            Pill("Share file", color = cs.onSurfaceVariant, onClick = { shareTextOnPhone(context, log.text(), "CarToon unknown PIDs") })
            Pill("Clear", color = cs.onSurfaceVariant, onClick = { log.clear(); notes.clear() })
        }
        Spacer(Modifier.height(10.dp))
        if (showText) {
            Text(
                log.text(), fontFamily = FontFamily.Monospace, fontSize = ui.small,
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(cs.surface).padding(12.dp).verticalScroll(rememberScrollState()),
            )
        } else if (log.entries.isEmpty()) {
            Text("Nothing yet. Connect to your car and wait a few seconds.", fontSize = ui.body, color = cs.onSurfaceVariant)
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
                items(log.entries, key = { it.key + it.vehicle }) { e ->
                    val note = notes[e.key + e.vehicle]
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(cs.surface).padding(14.dp)) {
                        Text("PID 01${e.key}", fontSize = ui.title, fontWeight = FontWeight.Bold)
                        Text("Reply: ${e.data}", fontSize = ui.body, fontFamily = FontFamily.Monospace)
                        Text(
                            e.vehicle + " · " + SimpleDateFormat("MMM d, h:mm a", Locale.US).format(Date(e.seen)),
                            fontSize = ui.small, color = cs.onSurfaceVariant,
                        )
                        PidTable.find(e.key)?.let {
                            Text(
                                "Looks like: ${it.name}" + (log.preview(e)?.let { p -> " = $p" } ?: ""),
                                fontSize = ui.small, color = cs.primary,
                            )
                        }
                        if (e.status.isNotBlank()) Text(e.status, fontSize = ui.small, color = cs.primary, fontWeight = FontWeight.SemiBold)
                        note?.let { Text(it, fontSize = ui.small, color = cs.onSurfaceVariant) }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Pill("Search", selected = true, onClick = { notes[e.key + e.vehicle] = log.search(e) })
                            Pill("Web", color = cs.onSurfaceVariant, onClick = {
                                openOnPhone(context, Uri.parse("https://www.google.com/search?q=" + Uri.encode("OBD2 mode 01 PID 0x${e.key} meaning formula")))
                            })
                        }
                    }
                }
            }
        }
    }
}
