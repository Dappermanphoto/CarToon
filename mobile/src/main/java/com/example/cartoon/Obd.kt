package com.example.cartoon

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/* ------------------------------ Sensors ------------------------------ */

/**
 * One live-data sensor. Values are decoded in metric; [toUs] converts for US units.
 * [key] is the OBD-II mode 01 PID in hex ("0C" = RPM), or "ATRV" for the adapter's own voltmeter.
 */
class ObdPid(
    val key: String,
    val name: String,
    val unit: String,
    val min: Float,
    val max: Float,
    val decimals: Int = 0,
    val usUnit: String = unit,
    val usMin: Float = min,
    val usMax: Float = max,
    val toUs: (Float) -> Float = { it },
    val decode: (IntArray) -> Float?,
) {
    fun value(raw: Float, metric: Boolean): Float = if (metric) raw else toUs(raw)
    fun unitFor(metric: Boolean): String = if (metric) unit else usUnit
    fun rangeFor(metric: Boolean): Pair<Float, Float> = if (metric) min to max else usMin to usMax
    fun format(raw: Float?, metric: Boolean): String =
        if (raw == null) "--" else "%.${decimals}f".format(value(raw, metric))
}

private fun IntArray.a(): Float? = getOrNull(0)?.toFloat()
private fun IntArray.ab(): Float? = if (size >= 2) (this[0] * 256 + this[1]).toFloat() else null

private val cToF: (Float) -> Float = { it * 9f / 5f + 32f }
private val kpaToPsi: (Float) -> Float = { it * 0.1450377f }

private fun temp(key: String, name: String, max: Float = 130f) = ObdPid(
    key, name, "°C", 0f, max, usUnit = "°F", usMin = 32f, usMax = cToF(max), toUs = cToF,
) { b -> b.a()?.minus(40f) }

private fun percent(key: String, name: String) = ObdPid(key, name, "%", 0f, 100f) { b -> b.a()?.times(100f / 255f) }

private fun trim(key: String, name: String) = ObdPid(key, name, "%", -25f, 25f, decimals = 1) { b ->
    b.a()?.let { (it - 128f) * 100f / 128f }
}

private fun o2(key: String, name: String) = ObdPid(key, name, "V", 0f, 1.2f, decimals = 2) { b -> b.a()?.div(200f) }

private fun pressure(key: String, name: String, max: Float, scale: Float = 1f) = ObdPid(
    key, name, "kPa", 0f, max, usUnit = "psi", usMax = kpaToPsi(max), decimals = 0, toUs = kpaToPsi,
) { b -> b.a()?.times(scale) }

object ObdPids {
    val all: List<ObdPid> = listOf(
        ObdPid("0C", "RPM", "rpm", 0f, 7000f) { b -> b.ab()?.div(4f) },
        ObdPid("0D", "Speed", "km/h", 0f, 200f, usUnit = "mph", usMax = 120f, toUs = { it * 0.621371f }) { b -> b.a() },
        temp("05", "Coolant temp"),
        percent("04", "Engine load"),
        percent("11", "Throttle"),
        ObdPid("ATRV", "Battery voltage", "V", 10f, 16f, decimals = 1) { null },
        ObdPid("42", "Module voltage", "V", 10f, 16f, decimals = 1) { b -> b.ab()?.div(1000f) },
        temp("0F", "Intake air temp", 80f),
        pressure("0B", "Manifold pressure", 255f),
        ObdPid("10", "Air flow (MAF)", "g/s", 0f, 300f, decimals = 1) { b -> b.ab()?.div(100f) },
        ObdPid("0E", "Timing advance", "°", -20f, 60f, decimals = 1) { b -> b.a()?.let { it / 2f - 64f } },
        trim("06", "Short fuel trim B1"),
        trim("07", "Long fuel trim B1"),
        trim("08", "Short fuel trim B2"),
        trim("09", "Long fuel trim B2"),
        o2("14", "O2 sensor B1S1"),
        o2("15", "O2 sensor B1S2"),
        o2("18", "O2 sensor B2S1"),
        o2("19", "O2 sensor B2S2"),
        ObdPid("44", "AFR (commanded)", ":1", 10f, 20f, decimals = 1) { b -> b.ab()?.let { it / 32768f * 14.7f } },
        percent("2F", "Fuel level"),
        pressure("0A", "Fuel pressure", 765f, scale = 3f),
        temp("5C", "Oil temp", 150f),
        pressure("33", "Barometric pressure", 255f),
        temp("46", "Outside temp", 60f),
    )
    private val byKey = all.associateBy { it.key }
    operator fun get(key: String): ObdPid? = byKey[key]
}

data class GaugeSlot(val pid: String, val name: String)

data class ObdDevice(val name: String, val address: String)

/* ------------------------------ Connection ------------------------------ */

/**
 * Talks to an ELM327-style Bluetooth LE OBD adapter (such as the Vgate iCar Pro BLE):
 * live sensor data, reading trouble codes, and clearing them.
 */
@SuppressLint("MissingPermission")
@Stable
class ObdManager(context: Context) {
    enum class State { DISCONNECTED, CONNECTING, STARTING, CONNECTED }

    private val appContext = context.applicationContext
    private val adapter: BluetoothAdapter? =
        appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = appContext.getSharedPreferences("cartoon_obd", Context.MODE_PRIVATE)

    var state by mutableStateOf(State.DISCONNECTED)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    /** Sensors this car answers. */
    var supported by mutableStateOf<Set<String>>(emptySet())
        private set

    /** Latest value per sensor, in metric. */
    val values = mutableStateMapOf<String, Float>()

    /** Goes up after each round of readings, so graphs redraw. */
    var tick by mutableIntStateOf(0)
        private set
    private val history = HashMap<String, ArrayDeque<Float>>()

    var storedCodes by mutableStateOf<List<String>?>(null)
        private set
    var pendingCodes by mutableStateOf<List<String>?>(null)
        private set
    var codesBusy by mutableStateOf(false)
        private set
    var codesNote by mutableStateOf<String?>(null)
        private set

    /** Which sensors the screen currently needs. Fewer sensors = faster updates. */
    @Volatile var activePids: Set<String> = emptySet()

    /** False while CarToon is in the background. */
    @Volatile var polling = true

    var gauges by mutableStateOf(loadGauges())
        private set

    /** True while showing the simulated demo vehicle instead of a real adapter. */
    var demo by mutableStateOf(false)
        private set
    private var demoCodes = listOf<String>()
    private var demoPending = listOf<String>()

    /**
     * Hooks for add-on modules to shape the demo vehicle. Each tick they can put sensor
     * values into the map to override or add to the simulation (for example voltage dips).
     */
    val demoHooks = ArrayList<(t: Float, out: MutableMap<String, Float>) -> Unit>()
    private val demoOverrides = HashMap<String, Float>()

    /** Extra sensors the demo vehicle reports, added by modules. */
    val demoExtraSensors = HashSet<String>()

    val found = mutableStateListOf<ObdDevice>()
    var scanning by mutableStateOf(false)
        private set

    private var gatt: BluetoothGatt? = null
    private var writeChar: BluetoothGattCharacteristic? = null
    private val buffer = StringBuilder()
    private var pendingResponse: CompletableDeferred<String>? = null
    private var ready: CompletableDeferred<Boolean>? = null
    private val mutex = Mutex()
    private var job: Job? = null
    private var protocol = ""

    /* ---------- Scanning ---------- */

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName ?: return
            val address = result.device.address ?: return
            scope.launch {
                if (found.none { it.address == address }) found += ObdDevice(name, address)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scope.launch {
                scanning = false
                message = "Bluetooth scan failed (error $errorCode)."
            }
        }
    }

    fun startScan() {
        val scanner = adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner
        if (scanner == null) {
            message = "Turn on Bluetooth first."
            return
        }
        if (scanning) return
        found.clear()
        message = null
        scanning = true
        runCatching { scanner.startScan(scanCallback) }.onFailure {
            scanning = false
            message = "Couldn't start the Bluetooth scan."
            return
        }
        scope.launch {
            delay(12_000)
            stopScan()
        }
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
    }

    /* ---------- Connecting ---------- */

    fun connect(address: String) {
        if (state != State.DISCONNECTED) return
        val a = adapter
        if (a == null || !a.isEnabled) {
            message = "Turn on Bluetooth first."
            return
        }
        if (!hasPermission(appContext)) {
            message = "CarToon needs the Nearby devices permission."
            return
        }
        val device = runCatching { a.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            message = "Pick your OBD adapter again in Settings."
            return
        }
        stopScan()
        state = State.CONNECTING
        message = null
        val r = CompletableDeferred<Boolean>()
        ready = r
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        job = scope.launch {
            val ok = withTimeoutOrNull(15_000) { r.await() } ?: false
            if (!ok) {
                fail("Couldn't reach the adapter. Check that it's plugged in and the ignition is on.")
                return@launch
            }
            state = State.STARTING
            if (!initAdapter()) {
                fail("The adapter didn't answer like an OBD adapter.")
                return@launch
            }
            state = State.CONNECTED
            pollLoop()
        }
    }

    fun disconnect() {
        monitors = null
        job?.cancel()
        job = null
        if (demo) {
            demo = false
            storedCodes = null
            pendingCodes = null
            codesNote = null
            values.clear()
            history.clear()
            supported = emptySet()
        }
        teardown()
        message = null
    }

    private fun fail(text: String) {
        teardown()
        message = text
    }

    private fun teardown() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        writeChar = null
        pendingResponse?.cancel()
        pendingResponse = null
        state = State.DISCONNECTED
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ready?.complete(false)
                scope.launch {
                    if (g === gatt && state != State.DISCONNECTED) {
                        job?.cancel()
                        fail("The adapter disconnected.")
                    }
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val pair = pickCharacteristics(g)
            if (pair == null) {
                ready?.complete(false)
                return
            }
            val (notify, write) = pair
            writeChar = write
            g.setCharacteristicNotification(notify, true)
            val cccd = notify.getDescriptor(CCCD)
            if (cccd == null) {
                ready?.complete(true)
                return
            }
            val value = if (notify.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            } else {
                BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            }
            g.writeDescriptor(cccd, value)
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            ready?.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            val done: Pair<CompletableDeferred<String>, String>? = synchronized(buffer) {
                buffer.append(String(value, Charsets.ISO_8859_1))
                val end = buffer.indexOf(">")
                val waiting = pendingResponse
                if (end >= 0 && waiting != null) {
                    val text = buffer.substring(0, end)
                    buffer.setLength(0)
                    pendingResponse = null
                    waiting to text
                } else {
                    null
                }
            }
            done?.first?.complete(done.second)
        }
    }

    /** Finds the adapter's serial-style channel: one characteristic to listen on, one to write to. */
    private fun pickCharacteristics(g: BluetoothGatt): Pair<BluetoothGattCharacteristic, BluetoothGattCharacteristic>? {
        val notifyMask = BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE
        val writeMask = BluetoothGattCharacteristic.PROPERTY_WRITE or
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        val services = g.services.filterNot { it.uuid.toString().startsWith("000018") }
            .sortedByDescending { it.uuid in KNOWN_SERVICES }
        for (s in services) {
            val notify = s.characteristics.firstOrNull { it.properties and notifyMask != 0 } ?: continue
            val write = s.characteristics.firstOrNull {
                it.properties and writeMask != 0 && it.uuid != notify.uuid
            } ?: s.characteristics.firstOrNull { it.properties and writeMask != 0 } ?: continue
            return notify to write
        }
        return null
    }

    /* ---------- Adapter commands ---------- */

    private suspend fun command(cmd: String, timeoutMs: Long = 2500): String? = mutex.withLock {
        val g = gatt
        val c = writeChar
        if (g == null || c == null) {
            null
        } else {
            val d = CompletableDeferred<String>()
            synchronized(buffer) {
                buffer.setLength(0)
                pendingResponse = d
            }
            val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
            val rc = g.writeCharacteristic(c, (cmd + "\r").toByteArray(Charsets.US_ASCII), type)
            if (rc != BluetoothStatusCodes.SUCCESS) {
                delay(60)
                null
            } else {
                withTimeoutOrNull(timeoutMs) { d.await() }?.let { clean(it, cmd) }
            }
        }
    }

    private fun clean(raw: String, cmd: String): String =
        raw.replace('\r', '\n').lines()
            .map { it.trim().replace(" ", "").uppercase() }
            .filter { it.isNotEmpty() && it != cmd.uppercase() && !it.startsWith("SEARCHING") }
            .joinToString("\n")

    private suspend fun initAdapter(): Boolean {
        val hello = command("ATZ", 4000) ?: command("ATZ", 4000) ?: return false
        if (hello.isEmpty()) return false
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1", "ATSP0")) command(c)
        loadSupported()
        return true
    }

    /** Asks the car which sensors it has. Also wakes up the adapter's protocol search. */
    private suspend fun loadSupported() {
        val out = HashSet<String>()
        var base = 0
        while (base <= 0x60) {
            val prefix = "41%02X".format(base)
            val resp = command("01%02X".format(base), if (base == 0) 12_000 else 2500) ?: break
            var mask = 0L
            var any = false
            for (line in resp.lines()) {
                val i = line.indexOf(prefix)
                if (i < 0) continue
                val hex = line.substring(i + 4).take(8)
                if (hex.length == 8) {
                    hex.toLongOrNull(16)?.let {
                        mask = mask or it
                        any = true
                    }
                }
            }
            if (!any) break
            for (bit in 0 until 32) {
                if (mask and (1L shl (31 - bit)) != 0L) out += "%02X".format(base + bit + 1)
            }
            if (mask and 1L == 0L) break
            base += 0x20
        }
        if (out.isNotEmpty()) {
            protocol = command("ATDPN").orEmpty()
            out += "ATRV"
            supported = out
            message = null
        } else {
            supported = setOf("ATRV")
            message = "Adapter connected, but the car isn't answering. Turn the ignition on."
        }
    }

    private suspend fun query(key: String): Float? {
        if (key == "ATRV") {
            val r = command("ATRV") ?: return null
            return Regex("""\d+(\.\d+)?""").find(r)?.value?.toFloatOrNull()
        }
        val pid = ObdPids[key] ?: return null
        val r = command("01$key") ?: return null
        val prefix = "41$key"
        val hex = r.lines().firstNotNullOfOrNull { line ->
            val i = line.indexOf(prefix)
            if (i >= 0) line.substring(i + prefix.length) else null
        } ?: return null
        val bytes = hex.chunked(2).mapNotNull { it.takeIf { c -> c.length == 2 }?.toIntOrNull(16) }.toIntArray()
        return pid.decode(bytes)
    }

    private suspend fun pollLoop() {
        while (state == State.CONNECTED) {
            if (supported.size <= 1) {
                loadSupported()
                if (supported.size <= 1) {
                    values["ATRV"] = query("ATRV") ?: values["ATRV"] ?: 0f
                    delay(2500)
                    continue
                }
            }
            val pids = if (polling) activePids.filter { it in supported } else emptyList()
            if (pids.isEmpty()) {
                delay(300)
                continue
            }
            for (key in pids) {
                if (state != State.CONNECTED) return
                val v = query(key) ?: continue
                values[key] = v
                val h = history.getOrPut(key) { ArrayDeque() }
                h.addLast(v)
                while (h.size > HISTORY) h.removeFirst()
            }
            tick++
            delay(20)
        }
    }

    fun historyOf(key: String): List<Float> = history[key]?.toList().orEmpty()

    /* ---------- Demo vehicle ---------- */

    /** A simulated 1994 Honda Accord, for trying the screens without an adapter. */
    fun startDemo() {
        if (state != State.DISCONNECTED) return
        stopScan()
        demo = true
        // A believable set: worn catalytic converter, lean condition, and a one-off misfire.
        demoCodes = listOf("P0420", "P0171")
        demoPending = listOf("P0301")
        message = null
        values.clear()
        history.clear()
        supported = DEMO_PIDS + demoExtraSensors
        storedCodes = demoCodes
        pendingCodes = demoPending
        codesNote = null
        state = State.CONNECTED
        job = scope.launch { demoLoop() }
    }

    private fun put(key: String, raw: Float) {
        val v = if (demo) demoOverrides[key] ?: raw else raw
        values[key] = v
        val h = history.getOrPut(key) { ArrayDeque() }
        h.addLast(v)
        while (h.size > HISTORY) h.removeFirst()
    }

    /** Loops a short drive: idle, pull away, town speed, highway, slow down, stop. */
    private suspend fun demoLoop() {
        // seconds to target speed in km/h
        val phases = listOf(6f to 0f, 12f to 50f, 10f to 50f, 14f to 105f, 15f to 105f, 10f to 40f, 8f to 0f)
        val cycle = phases.sumOf { it.first.toDouble() }.toFloat()
        val dt = 0.15f
        var t = 0f
        var speed = 0f
        var throttle = 0f
        var rpm = 750f
        fun wave(rate: Float): Float = kotlin.math.sin(t * rate)
        fun noise(amount: Float): Float = (kotlin.random.Random.nextFloat() - 0.5f) * 2f * amount
        while (state == State.CONNECTED && demo) {
            if (polling) {
                var into = t % cycle
                var target = 0f
                for ((length, v) in phases) {
                    if (into < length) {
                        target = v
                        break
                    }
                    into -= length
                }
                demoOverrides.clear()
                demoHooks.forEach { it(t, demoOverrides) }
                val before = speed
                speed = if (target > speed) minOf(target, speed + 8f * dt) else maxOf(target, speed - 10f * dt)
                val accel = (speed - before) / dt
                val wantThrottle = when {
                    accel > 0.5f -> 22f + accel * 4.5f
                    speed < 2f -> 0f
                    accel < -0.5f -> 1f
                    else -> 10f + speed * 0.09f
                }
                throttle += (wantThrottle - throttle) * 0.25f
                // 4-speed automatic
                val ratio = when {
                    speed < 20f -> 115f
                    speed < 45f -> 65f
                    speed < 75f -> 40f
                    else -> 25f
                }
                val wantRpm = maxOf(750f, speed * ratio) + throttle * 4f + noise(15f)
                rpm += (wantRpm - rpm) * 0.3f

                put("0C", rpm)
                put("0D", speed)
                put("11", throttle.coerceIn(0f, 100f))
                put("04", (18f + throttle * 0.8f + noise(1f)).coerceIn(0f, 100f))
                put("0B", (30f + throttle * 0.68f + noise(0.8f)).coerceIn(20f, 101f))
                put("0E", 14f + (rpm - 750f) / 5500f * 22f - throttle * 0.08f + noise(0.5f))
                put("05", minOf(90f, 45f + t * 0.6f) + wave(0.05f) * 1.5f)
                put("0F", 31f + wave(0.02f) * 3f)
                put("ATRV", 14.1f + wave(0.3f) * 0.12f + noise(0.03f))
                put("06", wave(1.3f) * 2.2f + noise(0.6f))
                put("07", 1.6f + wave(0.04f) * 0.8f)
                put("14", (0.45f + wave(4f) * 0.38f + noise(0.03f)).coerceIn(0.05f, 0.95f))
                put("15", 0.62f + wave(0.5f) * 0.06f)
                put("2F", maxOf(5f, 62f - t * 0.002f))
                put("33", 100f)
                put("46", 22f)
                // Sensors that only modules simulate
                demoOverrides.keys.filter { it !in DEMO_PIDS }.forEach { put(it, demoOverrides[it] ?: 0f) }
                tick++
            }
            t += dt
            delay(150)
        }
    }

    /* ---------- Readiness monitors (smog check) ---------- */

    var monitors by mutableStateOf<MonitorStatus?>(null)
    var monitorsBusy by mutableStateOf(false)
    var monitorsNote by mutableStateOf<String?>(null)

    fun readMonitors() {
        if (state != State.CONNECTED || monitorsBusy) return
        if (demo) {
            monitors = MonitorStatus.demo()
            monitorsNote = null
            return
        }
        scope.launch {
            monitorsBusy = true
            monitorsNote = null
            val r = command("0101", 5000)
            val hex = r?.lines()?.firstNotNullOfOrNull { line ->
                val i = line.indexOf("4101")
                if (i >= 0) line.substring(i + 4) else null
            }
            val bytes = hex?.chunked(2)?.mapNotNull { it.takeIf { c -> c.length == 2 }?.toIntOrNull(16) }?.toIntArray()
            if (bytes != null && bytes.size >= 4) {
                monitors = MonitorStatus.decode(bytes)
            } else {
                monitorsNote = "The car didn't answer. Is the ignition on?"
            }
            monitorsBusy = false
        }
    }

    /* ---------- Trouble codes ---------- */

    fun readCodes() {
        if (state != State.CONNECTED || codesBusy) return
        if (demo) {
            storedCodes = demoCodes
            pendingCodes = demoPending
            codesNote = null
            return
        }
        scope.launch {
            codesBusy = true
            codesNote = null
            storedCodes = fetchCodes("03", "43")
            pendingCodes = fetchCodes("07", "47")
            if (storedCodes == null) codesNote = "The car didn't answer. Is the ignition on?"
            codesBusy = false
        }
    }

    fun clearCodes() {
        if (state != State.CONNECTED || codesBusy) return
        if (demo) {
            demoCodes = emptyList()
            demoPending = emptyList()
            storedCodes = demoCodes
            pendingCodes = demoPending
            codesNote = "Codes cleared (demo)."
            return
        }
        scope.launch {
            codesBusy = true
            val r = command("04", 6000)
            val ok = r != null && r.contains("44")
            delay(800)
            storedCodes = fetchCodes("03", "43")
            pendingCodes = fetchCodes("07", "47")
            codesNote = if (ok) "Codes cleared." else
                "The car didn't confirm the clear. Try with the ignition on and the engine off."
            codesBusy = false
        }
    }

    private suspend fun fetchCodes(mode: String, prefix: String): List<String>? {
        val resp = command(mode, 6000) ?: return null
        if (resp.contains("NODATA")) return emptyList()
        if (resp.contains("UNABLE") || resp.contains("ERROR") || resp == "?") return null
        val lines = resp.lines().filter { it.isNotEmpty() }
        val isCan = protocol.lastOrNull()?.let { it in "6789ABC" } == true
        val multi = lines.filter { it.length > 2 && it[1] == ':' }
        val frames = ArrayList<String>()
        if (multi.isNotEmpty()) frames += multi.joinToString("") { it.substring(2) }
        frames += lines.filter { it.startsWith(prefix) }
        val out = LinkedHashSet<String>()
        for (f in frames) {
            val i = f.indexOf(prefix)
            if (i < 0) continue
            var body = f.substring(i + 2)
            if (isCan && body.length >= 2) body = body.substring(2) // CAN adds a code-count byte
            var p = 0
            while (p + 4 <= body.length) {
                val code = body.substring(p, p + 4)
                p += 4
                if (code != "0000" && code.all { it in "0123456789ABCDEF" }) out += dtc(code)
            }
        }
        return out.toList()
    }

    private fun dtc(hex: String): String {
        val n = hex[0].digitToInt(16)
        return "PCBU"[n shr 2].toString() + (n and 3) + hex.substring(1)
    }

    /* ---------- Gauge cluster layout ---------- */

    fun setGauge(index: Int, slot: GaugeSlot) {
        gauges = gauges.toMutableList().also { if (index in it.indices) it[index] = slot }
        val arr = JSONArray()
        gauges.forEach { arr.put(JSONObject().put("pid", it.pid).put("name", it.name)) }
        prefs.edit().putString("gauges", arr.toString()).apply()
    }

    private fun loadGauges(): List<GaugeSlot> {
        val saved = runCatching {
            val arr = JSONArray(prefs.getString("gauges", "[]"))
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                GaugeSlot(o.getString("pid"), o.getString("name"))
            }
        }.getOrDefault(emptyList())
        if (saved.size == DEFAULT_GAUGES.size) return saved
        return DEFAULT_GAUGES.map { GaugeSlot(it, ObdPids[it]?.name.orEmpty()) }
    }

    companion object {
        private const val HISTORY = 120
        private val DEMO_PIDS = setOf(
            "0C", "0D", "11", "04", "0B", "0E", "05", "0F", "ATRV", "06", "07", "14", "15", "2F", "33", "46",
        )
        private val DEFAULT_GAUGES = listOf("0C", "0D", "05", "11", "ATRV", "04")
        private val CCCD = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** Serial services used by common ELM327 BLE adapters (Vgate / LELink, FFF0, FFE0). */
        private val KNOWN_SERVICES = setOf(
            UUID.fromString("e7810a71-73ae-499d-8c15-faa9aef0c3f2"),
            UUID.fromString("0000fff0-0000-1000-8000-00805f9b34fb"),
            UUID.fromString("0000ffe0-0000-1000-8000-00805f9b34fb"),
        )

        val PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

        fun hasPermission(context: Context): Boolean = PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
