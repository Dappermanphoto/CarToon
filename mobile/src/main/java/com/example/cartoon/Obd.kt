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
import android.bluetooth.BluetoothSocket
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
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
        // Odometer, on newer cars only (the 2001 S-10 doesn't have it). 0.1 km per bit.
        ObdPid("A6", "Odometer", "km", 0f, 500000f, usUnit = "mi", usMax = 310000f, toUs = { it * 0.621371f }) { b ->
            if (b.size >= 4) ((b[0].toLong() shl 24) or (b[1].toLong() shl 16) or (b[2].toLong() shl 8) or b[3].toLong()) / 10f else null
        },
    ) + GmPids.all
    private val byKey = all.associateBy { it.key }
    private val base = all
    private var extra: Map<String, ObdPid> = emptyMap()

    /** Built-in sensors plus the custom ones for the active vehicle. */
    val everything: List<ObdPid> get() = base + extra.values

    /** Replaces the custom sensors (see [CustomPids]). */
    fun setCustom(list: List<ObdPid>) {
        extra = list.associateBy { it.key }
    }

    operator fun get(key: String): ObdPid? = byKey[key] ?: extra[key]
}

data class GaugeSlot(val pid: String, val name: String, val style: GaugeKind = GaugeKind.RADIAL)

data class ObdDevice(val name: String, val address: String, val classic: Boolean = false)

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

    /** Set when the chosen adapter is a brand that only works with its own app. */
    var unsupportedAdapter by mutableStateOf<UnsupportedAdapter?>(null)
    /** The vehicle's VIN, once the car has reported it. */
    var vin by mutableStateOf<String?>(null)
    var vinNote by mutableStateOf<String?>(null)
    /** Name shown while a demo vehicle is running. Empty when no demo is running. */
    var demoName by mutableStateOf("")
    val inDemo: Boolean get() = demoName.isNotEmpty()

    private var simulator: S10Simulator? = null
    private val connCache = ConnectionCache(appContext)
    private var currentAddress = ""
    private var currentBle = true
    private var quiet = false
    private var customHeaderActive = false

    /** True once the user taps Disconnect; auto-connect then leaves the adapter alone. */
    @Volatile var userDisconnected = false

    var permanentCodes by mutableStateOf<List<String>?>(null)
        private set
    var freezeFrame by mutableStateOf<Map<String, Float>?>(null)
        private set
    var freezeCode by mutableStateOf<String?>(null)
        private set
    var freezeBusy by mutableStateOf(false)
        private set
    var freezeNote by mutableStateOf<String?>(null)
        private set
    private var gmMode = false
    private var header = ""
    private val deadPids = HashSet<String>()
    private val misses = HashMap<String, Int>()
    private var discovering = false
    @Volatile private var mtu = 23
    private var socket: BluetoothSocket? = null
    private var readJob: Job? = null

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
            val address = result.device.address ?: return
            val name = result.device.name ?: result.scanRecord?.deviceName
                ?: (if (connCache.showAllPaired) address else return)
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
        // Bluetooth Classic adapters (OBDLink LX/MX+, BAFX) must be paired in Android first
        runCatching { adapter?.bondedDevices }.getOrNull()?.forEach { d ->
            val n = d.name
            if (connCache.showAllPaired) {
                // Some car stereos hide non-audio devices, so list everything that's paired
                found += ObdDevice(n ?: d.address, d.address, classic = d.type != BluetoothDevice.DEVICE_TYPE_LE)
            } else if (n != null && d.type != BluetoothDevice.DEVICE_TYPE_LE && AdapterBrands.looksLikeObd(n)) {
                found += ObdDevice(n, d.address, classic = true)
            }
        }
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

    /** [quiet] is for automatic attempts: failures don't leave a message on screen. */
    fun connect(address: String, quiet: Boolean = false) {
        if (state != State.DISCONNECTED) return
        this.quiet = quiet
        if (!quiet) userDisconnected = false
        val a = adapter
        if (a == null || !a.isEnabled) {
            if (!quiet) message = "Turn on Bluetooth first."
            return
        }
        if (!hasPermission(appContext)) {
            if (!quiet) message = "CarToon needs the Nearby devices permission."
            return
        }
        val device = runCatching { a.getRemoteDevice(address) }.getOrNull()
        if (device == null) {
            if (!quiet) message = "Pick your OBD adapter again in Settings."
            return
        }
        currentAddress = address
        val brand = AdapterBrands.detect(runCatching { device.name }.getOrNull())
        if (brand != null) {
            unsupportedAdapter = brand
            message = brand.message
            return
        }
        unsupportedAdapter = null
        stopScan()
        state = State.CONNECTING
        message = null
        vin = null
        vinNote = null
        deadPids.clear()
        misses.clear()
        header = ""
        job = scope.launch {
            // Bluetooth Low Energy first for LE and unknown devices, then Bluetooth Classic (SPP)
            var order = when (device.type) {
                BluetoothDevice.DEVICE_TYPE_CLASSIC -> listOf(false)
                BluetoothDevice.DEVICE_TYPE_LE -> listOf(true)
                else -> listOf(true, false)
            }
            // The transport that worked last time goes first, so reconnecting is quick
            val cachedBle = connCache.transportFor(address)
            if (cachedBle != null && cachedBle in order) order = listOf(cachedBle) + order.filter { it != cachedBle }
            var ok = false
            for (ble in order) {
                ok = if (ble) openBle(device) else openSpp(device)
                if (ok) {
                    currentBle = ble
                    break
                }
                closeLink()
            }
            if (!ok) {
                fail(
                    "Couldn't reach the adapter. Check that it's plugged in and the ignition is on. " +
                        "OBDLink LX/MX+ and BAFX adapters must be paired in Android's Bluetooth settings first."
                )
                return@launch
            }
            state = State.STARTING
            if (!initAdapter()) {
                fail("The adapter didn't answer like an OBD adapter.")
                return@launch
            }
            state = State.CONNECTED
            scope.launch { readVin() }
            pollLoop()
        }
    }

    private suspend fun openBle(device: BluetoothDevice): Boolean {
        val r = CompletableDeferred<Boolean>()
        ready = r
        discovering = false
        mtu = 23
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        return withTimeoutOrNull(15_000) { r.await() } ?: false
    }

    private suspend fun openSpp(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            adapter?.cancelDiscovery()
            val s = runCatching { device.createRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() } }
                .getOrElse { device.createInsecureRfcommSocketToServiceRecord(SPP_UUID).also { it.connect() } }
            socket = s
            val input = s.inputStream
            readJob = scope.launch(Dispatchers.IO) {
                val buf = ByteArray(512)
                while (isActive) {
                    val n = try { input.read(buf) } catch (e: Exception) { -1 }
                    if (n < 0) {
                        scope.launch {
                            if (state == State.CONNECTED || state == State.STARTING) {
                                job?.cancel()
                                fail("The adapter disconnected.")
                            }
                        }
                        break
                    }
                    feed(buf.copyOf(n))
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun writeSpp(s: BluetoothSocket, bytes: ByteArray): Boolean =
        runCatching { s.outputStream.write(bytes); s.outputStream.flush() }.isSuccess

    /** Closes the Bluetooth link without touching the connection state. */
    private fun closeLink() {
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        writeChar = null
        runCatching { socket?.close() }
        socket = null
        readJob?.cancel()
        readJob = null
    }

    fun disconnect() {
        userDisconnected = true
        monitors = null
        job?.cancel()
        job = null
        if (demo || simulator != null) {
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
        message = if (quiet) null else text
    }

    private fun teardown() {
        closeLink()
        simulator = null
        demoName = ""
        pendingResponse?.cancel()
        pendingResponse = null
        state = State.DISCONNECTED
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                // Ask for bigger packets first; discovery follows (or starts after a short wait)
                if (!g.requestMtu(247)) startDiscovery(g)
                scope.launch {
                    delay(1500)
                    startDiscovery(g)
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                ready?.complete(false)
                scope.launch {
                    if (g === gatt && (state == State.CONNECTED || state == State.STARTING)) {
                        job?.cancel()
                        fail("The adapter disconnected.")
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) this@ObdManager.mtu = mtu
            startDiscovery(g)
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
            feed(value)
        }
    }

    private fun startDiscovery(g: BluetoothGatt) {
        if (discovering) return
        discovering = true
        g.discoverServices()
    }

    /** Adds bytes from the adapter. A reply is complete when the ">" prompt arrives. */
    private fun feed(value: ByteArray) {
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

    private suspend fun command(cmd: String, timeoutMs: Long = 2500, clean: Boolean = true): String? = mutex.withLock {
        val sim = simulator
        val sock = socket
        val g = gatt
        val c = writeChar
        if (sim != null) {
            delay(25)
            val raw = sim.respond(cmd)
            return@withLock if (clean) clean(raw, cmd) else raw
        }
        if (sock == null && (g == null || c == null)) return@withLock null
        val d = CompletableDeferred<String>()
        synchronized(buffer) {
            buffer.setLength(0)
            pendingResponse = d
        }
        val bytes = (cmd + "\r").toByteArray(Charsets.US_ASCII)
        val sent = if (sock != null) {
            withContext(Dispatchers.IO) { writeSpp(sock, bytes) }
        } else if (writeInChunks(g!!, c!!, bytes)) {
            true
        } else {
            false
        }
        if (!sent) {
            delay(60)
            return@withLock null
        }
        withTimeoutOrNull(timeoutMs) { d.await() }?.let { if (clean) clean(it, cmd) else it.trim() }
    }

    /** Writes in pieces that fit the negotiated packet size (20 bytes if the adapter never answered). */
    private suspend fun writeInChunks(g: BluetoothGatt, c: BluetoothGattCharacteristic, bytes: ByteArray): Boolean {
        val type = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        }
        val size = (mtu - 3).coerceIn(20, 240)
        var i = 0
        while (i < bytes.size) {
            val part = bytes.copyOfRange(i, minOf(bytes.size, i + size))
            if (g.writeCharacteristic(c, part, type) != BluetoothStatusCodes.SUCCESS) return false
            i += size
            if (i < bytes.size) delay(20)
        }
        return true
    }

    private fun clean(raw: String, cmd: String): String =
        raw.replace('\r', '\n').lines()
            .map { it.trim().replace(" ", "").uppercase() }
            .filter { it.isNotEmpty() && it != cmd.uppercase() && !it.startsWith("SEARCHING") }
            .joinToString("\n")

    private suspend fun useHeader(h: String): Boolean {
        if (h == header) return true
        val r = command("ATSH$h") ?: return false
        if (r.contains("?")) return false
        header = h
        return true
    }

    private suspend fun setHeader(h: String): Boolean = if (!gmMode) true else useHeader(h)

    /** Puts the normal header back before a standard request (after a custom sensor changed it). */
    private suspend fun stdHeader(pcm: Boolean): Boolean {
        if (gmMode) return useHeader(if (pcm) GmPids.HEADER_PCM else GmPids.HEADER_FUNCTIONAL)
        if (customHeaderActive) {
            customHeaderActive = false
            return useHeader(defaultHeader())
        }
        return true
    }

    private fun defaultHeader(): String = when (protocol.removePrefix("A").take(1)) {
        "1" -> "616AF1"
        "2", "3", "4", "5" -> "686AF1"
        "7", "9", "B", "C" -> "18DB33F1"
        else -> "7DF"
    }

    private suspend fun initAdapter(): Boolean {
        val hello = command("ATZ", 4000) ?: command("ATZ", 4000) ?: return false
        if (hello.isEmpty()) return false
        header = ""
        customHeaderActive = false
        for (c in listOf("ATE0", "ATL0", "ATS0", "ATH0", "ATAT1")) command(c)
        gmMode = simulator != null || Vehicles.presetFor(appContext) == GmPids.PRESET_S10
        // Fast path: start with the protocol that worked last time instead of searching for one
        val cached = if (simulator == null) connCache.protocolFor(currentAddress) else null
        val first = cached ?: if (gmMode) "2" else "0"
        command("ATSP$first")
        setHeader(GmPids.HEADER_FUNCTIONAL)
        loadSupported()
        if (supported.size <= 1 && first != "0") {
            // The remembered protocol didn't work (different car?). Fall back to automatic.
            command("ATSP0")
            header = ""
            loadSupported()
        }
        if (supported.size > 1 && simulator == null && currentAddress.isNotEmpty()) {
            val digit = protocol.removePrefix("A").take(1)
            if (digit.isNotEmpty() && digit != "0") connCache.remember(currentAddress, currentBle, digit)
        }
        return true
    }

    /** Asks the car which sensors it has. Also wakes up the adapter's protocol search. */
    private suspend fun loadSupported() {
        val out = HashSet<String>()
        var base = 0
        while (base <= 0xA0) {
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
            if (gmMode) {
                // J1850 VPW answers show up as protocol 2 (or A2 when it was chosen automatically)
                out += GmPids.keys.filter { it !in deadPids }
            }
            out += "ATRV"
            out += CustomPids.refresh(appContext).map { it.key }
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
        CustomPids.spec(key)?.let { return queryCustom(key, pid, it) }
        val gm = GmPids.isGm(key)
        // Only change the header when it differs from the last one used
        if (!stdHeader(gm)) return null
        val cmd = if (gm) GmPids.request(key) else "01$key"
        val r = command(cmd) ?: return null
        if (gm && r.contains("7F22")) {
            // The PCM doesn't know this one. Skip it for the rest of the session.
            deadPids += key
            supported = supported - key
            return null
        }
        if (gm && (r.contains("NODATA") || r.contains("?"))) {
            val n = (misses[key] ?: 0) + 1
            misses[key] = n
            if (n >= 3) {
                deadPids += key
                supported = supported - key
            }
            return null
        }
        val prefix = if (gm) "62" + key.substring(1) else "41$key"
        val hex = r.lines().firstNotNullOfOrNull { line ->
            val i = line.indexOf(prefix)
            if (i >= 0) line.substring(i + prefix.length) else null
        } ?: return null
        val bytes = hex.chunked(2).mapNotNull { it.takeIf { c -> c.length == 2 }?.toIntOrNull(16) }.toIntArray()
        return pid.decode(bytes)
    }

    /** Call after the active vehicle changes: turns on the S-10 sensors if that profile is now active. */
    fun applyProfile() {
        if (state != State.CONNECTED || simulator != null) return
        val s10 = Vehicles.presetFor(appContext) == GmPids.PRESET_S10
        if (s10 && !gmMode) {
            scope.launch {
                gmMode = true
                header = ""
                command("ATSP2")
                setHeader(GmPids.HEADER_FUNCTIONAL)
                loadSupported()
                if (supported.size <= 1) {
                    command("ATSP0")
                    header = ""
                    loadSupported()
                }
            }
        }
    }

    private suspend fun queryCustom(key: String, pid: ObdPid, spec: CustomPid): Float? {
        val hdr = spec.header.uppercase().filter { it.isLetterOrDigit() }
        val cmd = spec.command.uppercase().filter { it in "0123456789ABCDEF" }
        if (cmd.length < 4) return null
        val mode = cmd.substring(0, 2).toInt(16)
        if (hdr.isNotEmpty()) {
            if (!useHeader(hdr)) return null
            customHeaderActive = true
        } else if (!stdHeader(mode == 0x22)) {
            return null
        }
        val r = command(cmd) ?: return null
        if (r.contains("NODATA") || r.contains("?") || r.contains("7F")) {
            val n = (misses[key] ?: 0) + 1
            misses[key] = n
            if (n >= 3) {
                deadPids += key
                supported = supported - key
            }
            return null
        }
        val pidLen = if (mode == 0x22 || mode == 0x21 || mode == 0x23) 2 else 1
        val prefix = "%02X".format(mode + 0x40) + cmd.substring(2, minOf(cmd.length, 2 + pidLen * 2))
        val hex = r.lines().firstNotNullOfOrNull { line ->
            val i = line.indexOf(prefix)
            if (i >= 0) line.substring(i + prefix.length) else null
        } ?: return null
        val bytes = hex.chunked(2).mapNotNull { it.takeIf { c -> c.length == 2 }?.toIntOrNull(16) }.toIntArray()
        return pid.decode(bytes)
    }

    /** Re-reads the custom sensors saved for the active vehicle (after editing or switching vehicles). */
    fun refreshCustom() {
        val keys = CustomPids.refresh(appContext).map { it.key }.toSet()
        deadPids.removeAll { it.startsWith("X") }
        if (state == State.CONNECTED) supported = supported.filterNot { it.startsWith("X") }.toSet() + keys
    }

    /** Sends a command typed by the user and returns the adapter's raw reply. */
    suspend fun sendRaw(cmd: String): String? {
        val text = cmd.trim().uppercase()
        if (text.isEmpty()) return null
        if (demo) return demoRaw(text)
        if (state != State.CONNECTED) return null
        val r = command(text, 8000, clean = false)
        if (text.startsWith("AT")) {
            // The user may have changed the header or protocol; make the next normal request set it again
            header = ""
            if (text.startsWith("ATSH")) customHeaderActive = true
        }
        return r
    }

    private fun demoRaw(c: String): String = when {
        c == "ATZ" -> "ELM327 v1.5 (demo)"
        c == "ATRV" -> "14.1V"
        c == "ATDPN" -> "A6"
        c.startsWith("AT") -> "OK"
        c == "010C" -> "41 0C 1A F8"
        c == "010D" -> "41 0D 3C"
        c == "0105" -> "41 05 7B"
        else -> "NO DATA"
    }

    /** Reads the VIN (mode 09, PID 02) and keeps it for the profile matcher. */
    private suspend fun readVin() {
        vinNote = null
        if (!stdHeader(false)) header = ""
        var found: String? = null
        for (attempt in 0 until 2) {
            val r = command("0902", 6000)
            if (r == null) break
            if (r.contains("NODATA") || r.contains("7F09") || r.contains("UNABLE") || r.contains("?")) break
            found = Vin.parse(r)
            if (found != null) break
        }
        if (found != null) {
            vin = found
        } else {
            vinNote = "This car didn't report a VIN. You can type it in on the Profile tab."
        }
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
            val pids = if (polling) activePids.filter { it in supported }.sortedBy { GmPids.isGm(it) } else emptyList()
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
        demoName = "1994 Honda Accord"
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

    /** A simulated 2001 Chevy S-10 LS. Raw replies run through the same parser as a real adapter. */
    fun startDemoS10() {
        if (state != State.DISCONNECTED) return
        stopScan()
        simulator = S10Simulator()
        demoName = "2001 Chevy S-10 LS"
        message = null
        values.clear()
        history.clear()
        storedCodes = null
        pendingCodes = null
        codesNote = null
        vin = null
        vinNote = null
        deadPids.clear()
        misses.clear()
        header = ""
        state = State.STARTING
        job = scope.launch {
            if (!initAdapter()) {
                fail("Simulator failed to start.")
                return@launch
            }
            state = State.CONNECTED
            scope.launch { readVin() }
            pollLoop()
        }
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
            permanentCodes = emptyList()
            codesNote = null
            return
        }
        scope.launch {
            codesBusy = true
            codesNote = null
            if (!stdHeader(false)) header = ""
            storedCodes = fetchCodes("03", "43")
            pendingCodes = fetchCodes("07", "47")
            permanentCodes = fetchCodes("0A", "4A")
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
            permanentCodes = fetchCodes("0A", "4A")
            freezeFrame = null
            freezeCode = null
            codesNote = if (ok) "Codes cleared." else
                "The car didn't confirm the clear. Try with the ignition on and the engine off."
            codesBusy = false
        }
    }

    /** Mode 02: the snapshot the car took when the fault happened. */
    fun readFreezeFrame() {
        if (state != State.CONNECTED || freezeBusy) return
        if (demo) {
            freezeCode = demoCodes.firstOrNull()
            freezeFrame = if (freezeCode == null) null else mapOf(
                "0C" to 2150f, "0D" to 72f, "05" to 88f, "06" to 1.6f, "07" to 3.1f, "04" to 46f,
            )
            freezeNote = if (freezeCode == null) "No freeze frame stored." else null
            return
        }
        scope.launch {
            freezeBusy = true
            freezeNote = null
            freezeFrame = null
            freezeCode = null
            if (!stdHeader(false)) header = ""
            val dtcResp = command("020200", 4000)
            val dhex = dtcResp?.lines()?.firstNotNullOfOrNull { l ->
                val i = l.indexOf("420200")
                if (i >= 0) l.substring(i + 6) else null
            }
            if (dhex != null && dhex.length >= 4 && dhex.take(4) != "0000") freezeCode = dtc(dhex.take(4))
            val out = LinkedHashMap<String, Float>()
            for (key in listOf("0C", "0D", "05", "06", "07", "04", "11", "0B")) {
                val r = command("02${key}00", 3000) ?: continue
                val prefix = "42${key}00"
                val hex = r.lines().firstNotNullOfOrNull { l ->
                    val i = l.indexOf(prefix)
                    if (i >= 0) l.substring(i + prefix.length) else null
                } ?: continue
                val bytes = hex.chunked(2).mapNotNull { it.takeIf { c -> c.length == 2 }?.toIntOrNull(16) }.toIntArray()
                ObdPids[key]?.decode?.invoke(bytes)?.let { out[key] = it }
            }
            if (out.isEmpty()) {
                freezeNote = "No freeze frame stored. The car only saves one when a fault sets a code."
            } else {
                freezeFrame = out
            }
            freezeBusy = false
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
        saveGauges()
    }

    private fun loadGauges(): List<GaugeSlot> {
        val saved = runCatching {
            val arr = JSONArray(prefs.getString("gauges", "[]"))
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                val style = runCatching { GaugeKind.valueOf(o.optString("style", "RADIAL")) }.getOrDefault(GaugeKind.RADIAL)
                GaugeSlot(o.getString("pid"), o.getString("name"), style)
            }
        }.getOrDefault(emptyList())
        if (saved.isNotEmpty()) return saved
        return DEFAULT_GAUGES.map { GaugeSlot(it, ObdPids[it]?.name.orEmpty()) }
    }

    private fun saveGauges() {
        val arr = JSONArray()
        gauges.forEach { arr.put(JSONObject().put("pid", it.pid).put("name", it.name).put("style", it.style.name)) }
        prefs.edit().putString("gauges", arr.toString()).apply()
    }

    fun replaceGauges(list: List<GaugeSlot>) {
        gauges = list.take(MAX_GAUGES)
        saveGauges()
    }

    fun addGauge(slot: GaugeSlot) {
        if (gauges.size < MAX_GAUGES) replaceGauges(gauges + slot)
    }

    fun removeGauge(index: Int) {
        if (gauges.size > 1 && index in gauges.indices) replaceGauges(gauges.filterIndexed { i, _ -> i != index })
    }

    fun moveGauge(index: Int, delta: Int) {
        val to = index + delta
        if (index !in gauges.indices || to !in gauges.indices) return
        val list = gauges.toMutableList()
        list.add(to, list.removeAt(index))
        replaceGauges(list)
    }

    companion object {
        private const val HISTORY = 120
        const val MAX_GAUGES = 12
        private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
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
            UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
        )

        val PERMISSIONS = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

        fun hasPermission(context: Context): Boolean = PERMISSIONS.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }
}
