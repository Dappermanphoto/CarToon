package com.example.cartoon

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/* ------------------------------ Saved connection ------------------------------ */

/**
 * What worked last time: the adapter's address, how it was reached (BLE or Classic) and which OBD
 * protocol the car answered on. Reconnecting starts from these instead of searching.
 * Also holds the connection options from Settings.
 */
class ConnectionCache(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cartoon_conn", Context.MODE_PRIVATE)

    val address: String get() = prefs.getString("address", "").orEmpty()

    /** "BLE", "SPP" or "" (nothing saved). USB serial would be a third value. */
    val transport: String get() = prefs.getString("transport", "").orEmpty()

    fun protocolFor(address: String): String? =
        prefs.getString("protocol", null)?.takeIf { it.isNotEmpty() && address == this.address }

    /** True for BLE, false for Classic, null when this adapter hasn't been used yet. */
    fun transportFor(address: String): Boolean? =
        if (address != this.address) null else when (transport) {
            "BLE" -> true
            "SPP" -> false
            else -> null
        }

    fun remember(address: String, ble: Boolean, protocol: String) {
        prefs.edit().putString("address", address).putString("transport", if (ble) "BLE" else "SPP")
            .putString("protocol", protocol).apply()
    }

    var autoConnect: Boolean
        get() = prefs.getBoolean("auto", true)
        set(v) = prefs.edit().putBoolean("auto", v).apply()

    /** Connect when the device boots, wakes, or a Bluetooth device (like the car stereo) connects. */
    var backgroundConnect: Boolean
        get() = prefs.getBoolean("background", true)
        set(v) = prefs.edit().putBoolean("background", v).apply()

    var launchOnBoot: Boolean
        get() = prefs.getBoolean("launch_boot", false)
        set(v) = prefs.edit().putBoolean("launch_boot", v).apply()

    var showAllPaired: Boolean
        get() = prefs.getBoolean("show_all", false)
        set(v) = prefs.edit().putBoolean("show_all", v).apply()
}

/* ------------------------------ Shared objects ------------------------------ */

/** The OBD connection lives for the whole app process, so background triggers can use it too. */
object ObdHub {
    private var obdRef: ObdManager? = null
    private var modulesRef: ObdModules? = null
    private var autoRef: AutoConnector? = null

    @Synchronized
    fun obd(context: Context): ObdManager =
        obdRef ?: ObdManager(context.applicationContext).also { obdRef = it }

    @Synchronized
    fun modules(context: Context): ObdModules =
        modulesRef ?: ObdModules(context.applicationContext, obd(context)).also { modulesRef = it }

    @Synchronized
    fun auto(context: Context): AutoConnector =
        autoRef ?: AutoConnector(context.applicationContext, obd(context)).also {
            autoRef = it
            it.watch()
        }
}

/* ------------------------------ Auto connector ------------------------------ */

/**
 * Connects to the saved adapter without being asked: when the app opens, when the device boots or
 * wakes, when a Bluetooth device connects, and again after a dropped link. Attempts back off
 * (quick at first, then slower) and never show error messages.
 */
@Stable
class AutoConnector(context: Context, private val obd: ObdManager) {
    enum class Status { IDLE, SEARCHING, CONNECTING, CONNECTED, DEMO }

    private val app = context.applicationContext
    private val cache = ConnectionCache(app)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var job: Job? = null

    var running by mutableStateOf(false)
        private set

    val status: Status
        get() = when {
            obd.inDemo -> Status.DEMO
            obd.state == ObdManager.State.CONNECTED -> Status.CONNECTED
            obd.state != ObdManager.State.DISCONNECTED -> Status.CONNECTING
            running -> Status.SEARCHING
            else -> Status.IDLE
        }

    private fun address(): String {
        val saved = app.getSharedPreferences("cartoon_settings", Context.MODE_PRIVATE).getString("obd_address", "").orEmpty()
        return saved.ifBlank { cache.address }
    }

    /** After a connection drops, start trying again on its own (unless the user tapped Disconnect). */
    fun watch() {
        scope.launch {
            var last = obd.state
            snapshotFlow { obd.state }.collect { now ->
                if (last == ObdManager.State.CONNECTED && now == ObdManager.State.DISCONNECTED) trigger("dropped")
                last = now
            }
        }
    }

    /**
     * [strong] triggers come from outside the app (boot, screen on, a Bluetooth device connecting)
     * and also override an earlier Disconnect tap.
     */
    fun trigger(reason: String, strong: Boolean = false) {
        if (!cache.autoConnect) return
        if (strong && !cache.backgroundConnect) return
        if (obd.inDemo) return
        if (obd.userDisconnected && !strong) return
        if (address().isBlank() || !ObdManager.hasPermission(app)) return
        if (obd.state != ObdManager.State.DISCONNECTED || job?.isActive == true) return
        if (strong) obd.userDisconnected = false
        job = scope.launch { attempts() }
    }

    private suspend fun attempts() {
        running = true
        try {
            // Quick first tries, then slower ones; about five minutes in all
            for (wait in longArrayOf(0, 1500, 3000, 6000, 10_000, 15_000, 20_000, 30_000, 30_000, 60_000, 60_000)) {
                if (wait > 0) delay(wait)
                if (obd.inDemo || obd.userDisconnected || obd.state == ObdManager.State.CONNECTED) return
                if (obd.state == ObdManager.State.DISCONNECTED) obd.connect(address(), quiet = true)
                val started = System.currentTimeMillis()
                while (obd.state != ObdManager.State.DISCONNECTED && obd.state != ObdManager.State.CONNECTED &&
                    System.currentTimeMillis() - started < 35_000
                ) delay(150)
                if (obd.state == ObdManager.State.CONNECTED) return
            }
        } finally {
            running = false
        }
    }
}

/* ------------------------------ Background service ------------------------------ */

/**
 * Keeps the app alive while it connects (and stays connected) after a boot, wake or Bluetooth
 * trigger. Shows a small quiet notification and stops itself if the adapter can't be found.
 */
class ObdService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL, "OBD connection", NotificationManager.IMPORTANCE_MIN)
        )
        startForeground(NOTE_ID, note("Looking for your OBD adapter…"), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        val auto = ObdHub.auto(this)
        ObdHub.modules(this) // make sure the add-on modules (trips, alerts) are running too
        auto.trigger("service", strong = true)
        scope.launch {
            var idle = 0
            while (true) {
                delay(2000)
                val text = when (auto.status) {
                    AutoConnector.Status.CONNECTED -> "Connected to your car"
                    AutoConnector.Status.CONNECTING -> "Connecting…"
                    else -> "Looking for your OBD adapter…"
                }
                nm.notify(NOTE_ID, note(text))
                idle = if (auto.status == AutoConnector.Status.IDLE) idle + 1 else 0
                if (idle >= 3) {
                    stopSelf()
                    break
                }
            }
        }
        return START_NOT_STICKY
    }

    private fun note(text: String): Notification {
        val open = android.app.PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            android.app.PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("CarToon")
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    override fun onDestroy() {
        scope.coroutineContext[Job]?.cancel()
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "obd"
        private const val NOTE_ID = 7

        /** Starts the service; if Android refuses a background start, connects directly instead. */
        fun start(context: Context) {
            val app = context.applicationContext
            if (!ObdManager.hasPermission(app)) return
            runCatching { ContextCompat.startForegroundService(app, Intent(app, ObdService::class.java)) }
                .onFailure { ObdHub.auto(app).trigger("direct", strong = true) }
        }
    }
}

/* ------------------------------ Triggers ------------------------------ */

/** Boot, screen on, and "a Bluetooth device connected" (usually the car stereo). */
class CarTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val cache = ConnectionCache(context)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON" -> {
                if (cache.launchOnBoot) {
                    runCatching {
                        context.startActivity(
                            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
                if (cache.backgroundConnect) ObdService.start(context)
            }
            Intent.ACTION_SCREEN_ON -> if (cache.backgroundConnect) ObdService.start(context)
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val device = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else null
                // Our own adapter connecting isn't a sign of anything new
                if (device?.address != cache.address && cache.backgroundConnect) ObdService.start(context)
            }
        }
    }
}

class CarToonApplication : android.app.Application() {
    override fun onCreate() {
        super.onCreate()
        DtcDictionary.init(this)
        // Screen-on can't be declared in the manifest, so listen for it while the app process is alive
        runCatching {
            ContextCompat.registerReceiver(
                this, CarTriggerReceiver(),
                IntentFilter(Intent.ACTION_SCREEN_ON),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
        }
    }
}

/* ------------------------------ UI ------------------------------ */

/** Small status pill: Searching, Connecting, Connected or Demo. Never blocks the screen. */
@Composable
fun ConnectionPill(auto: AutoConnector, modifier: Modifier = Modifier) {
    val status = auto.status
    if (status == AutoConnector.Status.IDLE) return
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val (label, color) = when (status) {
        AutoConnector.Status.SEARCHING -> "Searching…" to Color(0xFFFFB547)
        AutoConnector.Status.CONNECTING -> "Connecting…" to Color(0xFF7FD4FF)
        AutoConnector.Status.CONNECTED -> "Connected" to Color(0xFF6EE7B7)
        AutoConnector.Status.DEMO -> "Demo mode" to cs.primary
        AutoConnector.Status.IDLE -> return
    }
    Row(
        modifier
            .padding(top = 4.dp, start = 8.dp)
            .clip(RoundedCornerShape(50))
            .background(cs.surface.copy(alpha = 0.92f))
            .padding(horizontal = 12.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Spacer(Modifier.width(7.dp))
        Text(label, fontSize = ui.small, fontWeight = FontWeight.SemiBold, color = cs.onSurface)
    }
}

@Composable
private fun OptionRow(title: String, body: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = ui.body, fontWeight = FontWeight.SemiBold)
            Text(body, fontSize = ui.small, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private val MAC = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")

/** Settings for automatic connection, car-stereo Bluetooth workarounds and launch on boot. */
@Composable
fun ConnectionSection(settings: AppSettings, onChange: (AppSettings) -> Unit) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val cache = remember { ConnectionCache(context) }
    var auto by remember { mutableStateOf(cache.autoConnect) }
    var background by remember { mutableStateOf(cache.backgroundConnect) }
    var boot by remember { mutableStateOf(cache.launchOnBoot) }
    var showAll by remember { mutableStateOf(cache.showAllPaired) }
    var mac by remember { mutableStateOf("") }
    var macNote by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        OptionRow(
            "Connect automatically",
            "Reconnects to your saved adapter when CarToon opens, and again if the link drops.",
            auto,
        ) { auto = it; cache.autoConnect = it }
        OptionRow(
            "Connect when I get in the car",
            "Also tries when the device boots or wakes, or when the car's Bluetooth connects. Shows a small notification while it works.",
            background,
        ) { background = it; cache.backgroundConnect = it }
        OptionRow(
            "Open CarToon when the device boots",
            "For car stereos. Android may also need \"Display over other apps\" turned on for CarToon.",
            boot,
        ) { boot = it; cache.launchOnBoot = it }
        if (boot) {
            Pill("Allow display over other apps", color = cs.onSurfaceVariant, onClick = {
                runCatching {
                    context.startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            })
        }
        OptionRow(
            "Show all paired devices",
            "For car stereos that hide non-audio Bluetooth devices. Lists everything in the scan.",
            showAll,
        ) { showAll = it; cache.showAllPaired = it }

        Spacer(Modifier.height(6.dp))
        Text("Enter the adapter's address by hand", fontSize = ui.body, fontWeight = FontWeight.SemiBold)
        Text(
            "If the adapter doesn't show up in the scan. Format: AA:BB:CC:DD:EE:FF (printed on some adapters, or shown in Android's Bluetooth settings).",
            fontSize = ui.small,
            color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                value = mac,
                onValueChange = { mac = it.uppercase().take(17); macNote = null },
                singleLine = true,
                placeholder = { Text("AA:BB:CC:DD:EE:FF") },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.weight(1f),
            )
            Pill("Use", selected = true, onClick = {
                if (MAC.matches(mac)) {
                    onChange(settings.copy(obdAddress = mac, obdName = "Adapter $mac"))
                    macNote = "Saved. Tap Connect on the OBD tab."
                } else {
                    macNote = "That isn't a valid address."
                }
            })
        }
        macNote?.let { Text(it, fontSize = ui.small, color = cs.primary, modifier = Modifier.padding(top = 4.dp)) }
    }
}
