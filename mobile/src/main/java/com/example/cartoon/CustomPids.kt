package com.example.cartoon

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject

/** A sensor defined by the user: which command to send and how to turn the reply bytes into a number. */
data class CustomPid(
    val id: String,
    val name: String,
    val unit: String,
    /** Optional request header, such as 6C10F1. Blank uses the normal one. */
    val header: String,
    /** The request in hex, such as 010C or 22194001. */
    val command: String,
    /** Math on the reply bytes A, B, C, D, for example ((A*256)+B)/4. */
    val formula: String,
    val min: Float,
    val max: Float,
) {
    val key: String get() = "X$id"
}

/** Custom sensors, saved per vehicle. The active vehicle's ones are added to the sensor list. */
object CustomPids {
    private var specs: Map<String, CustomPid> = emptyMap()

    fun spec(key: String): CustomPid? = specs[key]

    private fun prefs(c: Context) = c.applicationContext.getSharedPreferences("cartoon_custom_pids", Context.MODE_PRIVATE)

    fun load(c: Context, vehicle: String): List<CustomPid> = runCatching {
        val arr = JSONArray(prefs(c).getString("pids_$vehicle", "[]"))
        (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            CustomPid(
                o.getString("id"), o.getString("name"), o.optString("unit"), o.optString("header"),
                o.getString("command"), o.getString("formula"),
                o.optDouble("min", 0.0).toFloat(), o.optDouble("max", 100.0).toFloat(),
            )
        }
    }.getOrDefault(emptyList())

    fun save(c: Context, vehicle: String, list: List<CustomPid>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("name", it.name).put("unit", it.unit).put("header", it.header)
                    .put("command", it.command).put("formula", it.formula)
                    .put("min", it.min.toDouble()).put("max", it.max.toDouble())
            )
        }
        prefs(c).edit().putString("pids_$vehicle", arr.toString()).apply()
    }

    /** Loads the active vehicle's custom sensors into the sensor list and returns them. */
    fun refresh(c: Context): List<ObdPid> {
        val list = load(c, Vehicles.activeId(c))
        specs = list.associateBy { it.key }
        val pids = list.mapNotNull { spec ->
            val f = Expr.compile(spec.formula) ?: return@mapNotNull null
            ObdPid(spec.key, spec.name, spec.unit, spec.min, spec.max, decimals = 1) { b ->
                val vars = DoubleArray(8) { i -> b.getOrNull(i)?.toDouble() ?: 0.0 }
                if (b.isEmpty()) null else f(vars).toFloat().takeIf { !it.isNaN() && !it.isInfinite() }
            }
        }
        ObdPids.setCustom(pids)
        return pids
    }
}

private typealias Node = (DoubleArray) -> Double

/** A tiny math parser: + - * / ( ) numbers and the reply bytes A to H. */
object Expr {
    /** Returns a function from the byte values (A at index 0) to the result, or null if the text isn't valid. */
    fun compile(text: String): ((DoubleArray) -> Double)? {
        val p = Parser(text.filterNot { it.isWhitespace() })
        return runCatching {
            val node = p.expr()
            if (p.pos != p.s.length) return null
            node(DoubleArray(8) { 1.0 }) // trial run so errors show up now
            node
        }.getOrNull()
    }

    private fun num(n: Double): Node = { _ -> n }
    private fun variable(i: Int): Node = { v -> v[i] }
    private fun neg(f: Node): Node = { v -> -f(v) }
    private fun bin(op: Char, l: Node, r: Node): Node = when (op) {
        '+' -> { v -> l(v) + r(v) }
        '-' -> { v -> l(v) - r(v) }
        '*' -> { v -> l(v) * r(v) }
        else -> { v -> l(v) / r(v) }
    }

    private class Parser(val s: String) {
        var pos = 0

        private fun peek(): Char? = s.getOrNull(pos)

        fun expr(): Node {
            var left = term()
            while (true) {
                val op = peek()
                if (op != '+' && op != '-') return left
                pos++
                left = bin(op, left, term())
            }
        }

        private fun term(): Node {
            var left = factor()
            while (true) {
                val op = peek()
                if (op != '*' && op != '/') return left
                pos++
                left = bin(op, left, factor())
            }
        }

        private fun factor(): Node {
            val c = peek() ?: error("unexpected end")
            if (c == '-') {
                pos++
                return neg(factor())
            }
            if (c == '+') {
                pos++
                return factor()
            }
            if (c == '(') {
                pos++
                val e = expr()
                if (peek() != ')') error("missing )")
                pos++
                return e
            }
            if (c.isDigit() || c == '.') {
                val start = pos
                while (peek()?.let { it.isDigit() || it == '.' } == true) pos++
                return num(s.substring(start, pos).toDouble())
            }
            if (c.uppercaseChar() in 'A'..'H') {
                pos++
                return variable(c.uppercaseChar() - 'A')
            }
            error("unexpected $c")
        }
    }
}

/** Builder for custom sensors, shown on the Terminal screen (phone only). */
@Composable
fun CustomPidEditor(obd: ObdManager) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val vid = Vehicles.activeId(context)
    var list by remember(vid) { mutableStateOf(CustomPids.load(context, vid)) }
    var name by remember { mutableStateOf("") }
    var unit by remember { mutableStateOf("") }
    var header by remember { mutableStateOf("") }
    var cmd by remember { mutableStateOf("") }
    var formula by remember { mutableStateOf("") }
    var min by remember { mutableStateOf("0") }
    var max by remember { mutableStateOf("100") }
    var note by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth()) {
        Text(
            "Custom sensors are saved for the active vehicle and show up in the gauge, graph and list screens.",
            fontSize = ui.small,
            color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        list.forEach { p ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(cs.surface)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontSize = ui.body, fontWeight = FontWeight.SemiBold)
                    Text(
                        (if (p.header.isNotBlank()) "${p.header} · " else "") + "${p.command} · ${p.formula}",
                        fontSize = ui.small,
                        color = cs.onSurfaceVariant,
                    )
                }
                Pill("Delete", color = cs.onSurfaceVariant, onClick = {
                    list = list.filter { it.id != p.id }
                    CustomPids.save(context, vid, list)
                    obd.refreshCustom()
                })
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("NEW SENSOR", fontSize = ui.small, fontWeight = FontWeight.Bold, color = cs.primary)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(name, { name = it.take(24) }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            OutlinedTextField(unit, { unit = it.take(8) }, label = { Text("Unit") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(header, { header = it.uppercase().take(8) }, label = { Text("Header (optional)") }, singleLine = true, modifier = Modifier.weight(1.4f))
        }
        OutlinedTextField(
            cmd, { cmd = it.uppercase().filter { c -> c in "0123456789ABCDEF" }.take(12) },
            label = { Text("Command in hex (like 010C or 22194001)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        OutlinedTextField(
            formula, { formula = it.take(60) },
            label = { Text("Formula using A, B, C, D (like ((A*256)+B)/4)") }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            OutlinedTextField(min, { min = it.take(8) }, label = { Text("Gauge min") }, singleLine = true, modifier = Modifier.weight(1f))
            OutlinedTextField(max, { max = it.take(8) }, label = { Text("Gauge max") }, singleLine = true, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill("Add sensor", selected = true, onClick = {
                val lo = min.toFloatOrNull()
                val hi = max.toFloatOrNull()
                note = when {
                    name.isBlank() -> "Give it a name."
                    cmd.length < 4 -> "The command needs a mode and a PID, like 010C."
                    Expr.compile(formula) == null -> "The formula isn't valid. Use A, B, C, D, numbers and + - * / ( )."
                    lo == null || hi == null || hi <= lo -> "Gauge min and max need to be numbers, with max bigger."
                    else -> {
                        val spec = CustomPid(System.currentTimeMillis().toString(36), name.trim(), unit.trim(), header, cmd, formula.trim(), lo, hi)
                        list = list + spec
                        CustomPids.save(context, vid, list)
                        obd.refreshCustom()
                        name = ""; cmd = ""; formula = ""
                        "Added. Find it in the sensor list."
                    }
                }
            })
            Spacer(Modifier.width(10.dp))
            note?.let { Text(it, fontSize = ui.small, color = cs.primary, modifier = Modifier.weight(1f)) }
        }
    }
}
