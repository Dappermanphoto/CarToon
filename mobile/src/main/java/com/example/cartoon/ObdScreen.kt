package com.example.cartoon

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Longest gauge name; keeps custom names from overrunning the cluster. */
private const val GAUGE_NAME_MAX = 16

private enum class ObdView(val label: String) {
    CODES("Codes"), CLUSTER("Cluster"), SPLIT("Split"), GRAPH("Graph"), LIST("List"), VOLTS("Voltage"),
    SMOG("Smog"), TRIPS("Trips"), HEALTH("Health"),
}

@Composable
fun ObdScreen(
    obd: ObdManager,
    settings: AppSettings,
    onOpenSettings: () -> Unit,
    modules: ObdModules? = null,
    tracker: NowPlayingTracker? = null,
) {
    val context = LocalContext.current
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val metric = settings.metricUnits

    var view by remember { mutableStateOf(if (ui.car) ObdView.CLUSTER else ObdView.CODES) }
    var graphPids by remember { mutableStateOf(listOf("0C", "0D")) }
    var editing by remember { mutableStateOf<Int?>(null) }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) obd.connect(settings.obdAddress)
    }
    val connect: () -> Unit = {
        if (ObdManager.hasPermission(context)) obd.connect(settings.obdAddress)
        else permission.launch(ObdManager.PERMISSIONS)
    }

    // Only ask the car for the sensors the current view shows.
    val gauges = obd.gauges
    val supported = obd.supported
    LaunchedEffect(view, gauges, graphPids, supported, editing) {
        val wanted = when (view) {
            ObdView.CODES, ObdView.SMOG, ObdView.TRIPS -> emptySet()
            ObdView.CLUSTER -> gauges.map { it.pid }.toSet()
            ObdView.SPLIT -> gauges.take(2).map { it.pid }.toSet()
            ObdView.HEALTH -> HealthBaseline.PIDS
            ObdView.GRAPH -> graphPids.toSet()
            ObdView.LIST -> supported
            ObdView.VOLTS -> setOf("ATRV")
        }
        // The scheduler module decides how often each wanted sensor is read.
        if (modules != null) modules.scheduler.setWanted(wanted) else obd.activePids = wanted
    }

    Column(Modifier.fillMaxSize().padding(horizontal = ui.pad, vertical = if (ui.short) 6.dp else 12.dp)) {
        if (!(ui.short && ui.wide)) {
            Text("OBD", fontSize = ui.headline, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(10.dp))
        }

        when {
            settings.obdAddress.isBlank() && obd.state == ObdManager.State.DISCONNECTED -> EmptyState(
                icon = AppIcons.Gauge,
                title = "Set up your OBD adapter",
                body = "Plug the adapter into the car, turn the ignition on, then pick it in Settings on your phone.",
                modifier = Modifier.fillMaxSize(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BigButton("Open Settings", onOpenSettings)
                    Spacer(Modifier.width(10.dp))
                    Pill("Demo: Honda Accord", color = cs.onSurfaceVariant, onClick = { obd.startDemo() })
                    Spacer(Modifier.width(8.dp))
                    Pill("Demo: 2001 S-10 LS", color = cs.onSurfaceVariant, onClick = { obd.startDemoS10() })
                }
            }

            obd.state == ObdManager.State.DISCONNECTED -> EmptyState(
                icon = AppIcons.Gauge,
                title = if (obd.message == null) "Connect to ${settings.obdName.ifBlank { "your adapter" }}" else "Not connected",
                body = obd.message ?: "Make sure the adapter is plugged in and the ignition is on.",
                modifier = Modifier.fillMaxSize(),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BigButton(if (obd.message == null) "Connect" else "Try again", connect)
                    Spacer(Modifier.width(10.dp))
                    obd.unsupportedAdapter?.let { u ->
                        val ctx = androidx.compose.ui.platform.LocalContext.current
                        Pill("Get the ${u.appName} app", selected = true, onClick = { openOnPhone(ctx, u.storeUri) })
                        Spacer(Modifier.width(8.dp))
                    }
                    Pill("Demo: Honda Accord", color = cs.onSurfaceVariant, onClick = { obd.startDemo() })
                    Spacer(Modifier.width(8.dp))
                    Pill("Demo: 2001 S-10 LS", color = cs.onSurfaceVariant, onClick = { obd.startDemoS10() })
                }
            }

            obd.state != ObdManager.State.CONNECTED -> Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                CircularProgressIndicator(color = cs.primary)
                Spacer(Modifier.height(14.dp))
                Text(
                    if (obd.state == ObdManager.State.CONNECTING) "Connecting to the adapter…"
                    else "Talking to the car… this can take a few seconds.",
                    fontSize = ui.body,
                    color = cs.onSurfaceVariant,
                )
            }

            editing != null -> GaugeEditor(
                obd = obd,
                index = editing ?: 0,
                onDone = { editing = null },
            )

            else -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ObdView.entries.forEach { v ->
                            Pill(v.label, selected = view == v, onClick = { view = v })
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Pill(if (obd.inDemo) "Exit demo" else "Disconnect", color = cs.onSurfaceVariant, onClick = { obd.disconnect() })
                }
                if (obd.inDemo) {
                    Spacer(Modifier.height(6.dp))
                    Text("Demo vehicle: ${obd.demoName} (simulated data)", fontSize = ui.small, color = cs.primary)
                }
                if (!obd.inDemo) obd.vinNote?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = ui.small, color = cs.onSurfaceVariant)
                }
                obd.message?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, fontSize = ui.small, color = cs.primary)
                }
                Spacer(Modifier.height(if (ui.short) 8.dp else 12.dp))

                when (view) {
                    ObdView.CODES -> DtcHubScreen(obd)
                    ObdView.CLUSTER -> ClusterView(obd, metric) { editing = it }
                    ObdView.SPLIT -> SplitView(obd, metric, tracker)
                    ObdView.SMOG -> SmogView(obd)
                    ObdView.TRIPS -> if (modules != null) TripsView(modules.trips, metric)
                    ObdView.HEALTH -> if (modules != null) HealthView(obd, modules.health, metric)
                    ObdView.GRAPH -> GraphView(obd, metric, graphPids) { graphPids = it }
                    ObdView.LIST -> ListView(obd, metric)
                    ObdView.VOLTS -> if (modules != null) VoltagePanel(obd, modules.voltage)
                }
            }
        }
    }
}

/* ------------------------------ Codes ------------------------------ */

@Composable
private fun CodesView(obd: ObdManager) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    var confirm by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { if (obd.storedCodes == null) obd.readCodes() }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        if (confirm) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(cs.surface)
                    .padding(18.dp)
            ) {
                Text("Clear all codes?", fontSize = ui.title, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text(
                    "This turns off the check-engine light and erases stored codes and freeze data. " +
                        "It also resets the emissions readiness monitors, so the car needs some driving " +
                        "before it can pass a smog test. If the problem isn't fixed, the code comes back. " +
                        "Do this with the ignition on and the engine off.",
                    fontSize = ui.body,
                    color = cs.onSurfaceVariant,
                )
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Pill("Cancel", color = cs.onSurfaceVariant, onClick = { confirm = false })
                    Pill("Clear codes", selected = true, onClick = {
                        confirm = false
                        obd.clearCodes()
                    })
                }
            }
            return@Column
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Read codes", selected = true, onClick = { obd.readCodes() })
            Pill("Clear codes", color = cs.onSurfaceVariant, onClick = { confirm = true })
            if (obd.codesBusy) CircularProgressIndicator(Modifier.size(22.dp), color = cs.primary, strokeWidth = 3.dp)
        }
        obd.codesNote?.let {
            Spacer(Modifier.height(10.dp))
            Text(it, fontSize = ui.body, color = cs.primary)
        }
        Spacer(Modifier.height(16.dp))
        CodeList("Stored codes", obd.storedCodes, "No stored codes.")
        Spacer(Modifier.height(16.dp))
        CodeList("Pending codes", obd.pendingCodes, "No pending codes.")
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun CodeList(title: String, codes: List<String>?, emptyText: String) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    Text(title.uppercase(), fontSize = ui.small, fontWeight = FontWeight.Bold, color = cs.primary)
    Spacer(Modifier.height(8.dp))
    when {
        codes == null -> Text("Not read yet.", fontSize = ui.body, color = cs.onSurfaceVariant)
        codes.isEmpty() -> Text(emptyText, fontSize = ui.body, color = cs.onSurfaceVariant)
        else -> codes.forEach { code ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(cs.surface)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(code, fontSize = ui.title, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(14.dp))
                Text(
                    when (code.firstOrNull()) {
                        'P' -> "Powertrain"
                        'C' -> "Chassis"
                        'B' -> "Body"
                        else -> "Network"
                    } + if (code.getOrNull(1) == '0') " · generic" else " · manufacturer-specific",
                    fontSize = ui.small,
                    color = cs.onSurfaceVariant,
                )
            }
        }
    }
}

/* ------------------------------ Cluster ------------------------------ */

@Composable
private fun ClusterView(obd: ObdManager, metric: Boolean, onEdit: (Int) -> Unit) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val gauges = obd.gauges
    val cols = if (ui.wide) 3 else 2
    Column(Modifier.fillMaxSize()) {
        gauges.chunked(cols).forEachIndexed { r, row ->
            Row(Modifier.weight(1f).fillMaxWidth()) {
                row.forEachIndexed { c, slot ->
                    val index = r * cols + c
                    Gauge(
                        slot = slot,
                        raw = obd.values[slot.pid],
                        supported = slot.pid in obd.supported,
                        metric = metric,
                        onLongPress = { onEdit(index) },
                        modifier = Modifier.weight(1f).fillMaxHeight().padding(4.dp),
                    )
                }
            }
        }
        if (!ui.short) {
            val context = LocalContext.current
            var layoutNote by remember { mutableStateOf<String?>(null) }
            Row(
                Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    layoutNote ?: "Hold a gauge to change it",
                    fontSize = ui.small,
                    color = if (layoutNote != null) cs.primary else cs.onSurfaceVariant,
                )
                Pill("Copy layout", color = cs.onSurfaceVariant, onClick = {
                    val code = GaugeLayout.encode(gauges)
                    context.getSystemService(android.content.ClipboardManager::class.java)
                        ?.setPrimaryClip(android.content.ClipData.newPlainText("CarToon layout", code))
                    layoutNote = "Layout copied. Paste it to a friend."
                })
                if (!ui.car) {
                    Pill("Paste layout", color = cs.onSurfaceVariant, onClick = {
                        val text = context.getSystemService(android.content.ClipboardManager::class.java)
                            ?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty()
                        val slots = GaugeLayout.decode(text, gauges.size)
                        if (slots == null) {
                            layoutNote = "No CarToon layout on the clipboard."
                        } else {
                            slots.forEachIndexed { i, slot -> obd.setGauge(i, slot) }
                            layoutNote = "Layout applied."
                        }
                    })
                }
            }
        }
    }
}

/** Gauge layouts as short text, so they can be shared in a message. */
object GaugeLayout {
    private const val PREFIX = "CARTOON-LAYOUT"

    fun encode(gauges: List<GaugeSlot>): String =
        PREFIX + gauges.joinToString("") { "|" + it.pid + ":" + it.name.replace("|", "").replace(":", "") }

    /** Returns null when [text] isn't a layout for exactly [count] gauges using sensors CarToon knows. */
    fun decode(text: String, count: Int): List<GaugeSlot>? {
        val parts = text.trim().split("|")
        if (parts.firstOrNull() != PREFIX || parts.size != count + 1) return null
        return parts.drop(1).map { part ->
            val pid = part.substringBefore(":")
            val known = ObdPids[pid] ?: return null
            val name = part.substringAfter(":", known.name).take(GAUGE_NAME_MAX).ifBlank { known.name }
            GaugeSlot(pid, name)
        }
    }
}

/* ------------------------------ Split dashboard ------------------------------ */

/** Now playing on one side, the first two gauges on the other. */
@Composable
private fun SplitView(obd: ObdManager, metric: Boolean, tracker: NowPlayingTracker?) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    LaunchedEffect(tracker) { tracker?.start() }
    val empty = remember { kotlinx.coroutines.flow.MutableStateFlow<NowPlaying?>(null) }
    val np by (tracker?.state ?: empty).collectAsState()
    val song = np

    val card: @Composable (Modifier) -> Unit = { m ->
        Column(
            m
                .clip(RoundedCornerShape(28.dp))
                .background(cs.surface)
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (song == null || song.title.isBlank() || tracker == null) {
                Text("Nothing playing", fontSize = ui.title, fontWeight = FontWeight.Bold)
                Text(
                    "Start music in any app. Turn on notification access in Settings if it doesn't show up.",
                    fontSize = ui.small,
                    color = cs.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            } else {
                ArtTile(song.art, AppIcons.Note, if (ui.short) 64.dp else 96.dp, cs.secondary)
                Spacer(Modifier.height(8.dp))
                Text(song.title, fontSize = ui.body, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Text(song.artist, fontSize = ui.small, color = cs.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircleIconButton(AppIcons.Previous, "Previous", ui.iconButton, tracker::previous)
                    CircleIconButton(
                        if (song.isPlaying) AppIcons.Pause else AppIcons.Play,
                        if (song.isPlaying) "Pause" else "Play",
                        ui.control,
                        tracker::playPause,
                        primary = true,
                    )
                    CircleIconButton(AppIcons.Next, "Next", ui.iconButton, tracker::next)
                }
            }
        }
    }
    val gauge: @Composable (Int, Modifier) -> Unit = { i, m ->
        val slot = obd.gauges.getOrNull(i)
        if (slot != null) {
            Gauge(
                slot = slot,
                raw = obd.values[slot.pid],
                supported = slot.pid in obd.supported,
                metric = metric,
                onLongPress = {},
                modifier = m,
            )
        }
    }

    if (ui.wide) {
        Row(Modifier.fillMaxSize()) {
            card(Modifier.weight(1.2f).fillMaxHeight().padding(4.dp))
            gauge(0, Modifier.weight(1f).fillMaxHeight().padding(4.dp))
            gauge(1, Modifier.weight(1f).fillMaxHeight().padding(4.dp))
        }
    } else {
        Column(Modifier.fillMaxSize()) {
            card(Modifier.weight(1f).fillMaxWidth().padding(4.dp))
            Row(Modifier.weight(1f).fillMaxWidth()) {
                gauge(0, Modifier.weight(1f).fillMaxHeight().padding(4.dp))
                gauge(1, Modifier.weight(1f).fillMaxHeight().padding(4.dp))
            }
        }
    }
}

@Composable
private fun Gauge(
    slot: GaugeSlot,
    raw: Float?,
    supported: Boolean,
    metric: Boolean,
    onLongPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cs = MaterialTheme.colorScheme
    val pid = ObdPids[slot.pid]
    val density = LocalDensity.current
    BoxWithConstraints(
        modifier
            .clip(RoundedCornerShape(28.dp))
            .background(cs.surface)
            .pointerInput(Unit) { detectTapGestures(onLongPress = { onLongPress() }) }
            .padding(8.dp),
        contentAlignment = Alignment.Center,
    ) {
        // The name gets its own row under the dial so it can never overlap the numbers.
        val labelHeight = (maxHeight * 0.18f).coerceIn(14.dp, 28.dp)
        val dial = minOf(maxWidth, maxHeight - labelHeight)
        val dialSp = with(density) { dial.toSp().value }
        val (lo, hi) = pid?.rangeFor(metric) ?: (0f to 1f)
        val shown = if (raw != null && pid != null) pid.value(raw, metric) else null
        val fraction = if (shown == null || hi <= lo) 0f else ((shown - lo) / (hi - lo)).coerceIn(0f, 1f)
        val track = cs.surfaceVariant
        val fill = LocalGaugeColor.current ?: cs.primary
        val valueText = if (!supported && raw == null) "N/A" else pid?.format(raw, metric) ?: "--"

        Column(
            Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(Modifier.size(dial), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = size.minDimension * 0.085f
                    val inset = stroke / 2f
                    val arcSize = Size(size.width - stroke, size.height - stroke)
                    val topLeft = Offset(inset, inset)
                    drawArc(track, 135f, 270f, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    if (fraction > 0f) {
                        drawArc(fill, 135f, 270f * fraction, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
                    }
                }
                // Value and unit stay inside the ring and shrink to fit long numbers.
                Column(Modifier.fillMaxWidth(0.62f), horizontalAlignment = Alignment.CenterHorizontally) {
                    BasicText(
                        valueText,
                        style = TextStyle(color = cs.onSurface, fontWeight = FontWeight.Black, textAlign = TextAlign.Center),
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 10.sp, maxFontSize = (dialSp * 0.24f).coerceAtLeast(11f).sp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    BasicText(
                        pid?.unitFor(metric).orEmpty(),
                        style = TextStyle(color = cs.onSurfaceVariant, textAlign = TextAlign.Center),
                        maxLines = 1,
                        autoSize = TextAutoSize.StepBased(minFontSize = 7.sp, maxFontSize = (dialSp * 0.09f).coerceAtLeast(8f).sp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            BasicText(
                slot.name.uppercase(),
                style = TextStyle(color = cs.onSurfaceVariant, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                autoSize = TextAutoSize.StepBased(minFontSize = 8.sp, maxFontSize = with(density) { (labelHeight * 0.75f).toSp() }),
                modifier = Modifier.fillMaxWidth(0.94f).height(labelHeight),
            )
        }
    }
}

/** Pick a different sensor for a gauge and rename it. */
@Composable
private fun GaugeEditor(obd: ObdManager, index: Int, onDone: () -> Unit) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val slot = obd.gauges.getOrNull(index) ?: return onDone()
    var pidKey by remember { mutableStateOf(slot.pid) }
    var name by remember { mutableStateOf(slot.name) }

    val save = {
        val finalName = name.trim().ifBlank { ObdPids[pidKey]?.name.orEmpty() }
        obd.setGauge(index, GaugeSlot(pidKey, finalName))
        onDone()
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CircleIconButton(AppIcons.Back, "Cancel", ui.iconButton, onDone)
            Spacer(Modifier.width(12.dp))
            Text("Edit gauge ${index + 1}", fontSize = ui.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Pill("Save", selected = true, onClick = save)
        }
        Spacer(Modifier.height(10.dp))

        val nameEditor: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier.verticalScroll(rememberScrollState())) {
                Text("NAME", fontSize = ui.small, fontWeight = FontWeight.Bold, color = cs.primary)
                Spacer(Modifier.height(6.dp))
                if (ui.car) {
                    Text(
                        name.ifEmpty { " " },
                        fontSize = ui.title,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(cs.surfaceVariant)
                            .padding(horizontal = 14.dp, vertical = 10.dp),
                    )
                    Spacer(Modifier.height(8.dp))
                    OnScreenKeyboard(
                        onKey = { if (name.length < GAUGE_NAME_MAX) name += it },
                        onBackspace = { name = name.dropLast(1) },
                        onClear = { name = "" },
                        onSearch = save,
                        actionLabel = "Save",
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it.take(GAUGE_NAME_MAX) },
                        singleLine = true,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
        val sensorList: @Composable (Modifier) -> Unit = { modifier ->
            Column(modifier) {
                Text("SENSOR", fontSize = ui.small, fontWeight = FontWeight.Bold, color = cs.primary)
                Spacer(Modifier.height(6.dp))
                val sorted = remember(obd.supported) { ObdPids.all.sortedByDescending { it.key in obd.supported } }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
                    items(sorted, key = { it.key }) { pid ->
                        val available = pid.key in obd.supported
                        val selected = pid.key == pidKey
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (selected) cs.primary.copy(alpha = 0.2f) else cs.surface)
                                .clickable {
                                    // Keep a custom name; replace it only if it was the old default.
                                    if (name.isBlank() || name == ObdPids[pidKey]?.name) name = pid.name
                                    pidKey = pid.key
                                }
                                .padding(horizontal = 14.dp, vertical = if (ui.short) 9.dp else 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                pid.name,
                                fontSize = ui.body,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (available) cs.onSurface else cs.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier.weight(1f),
                            )
                            if (!available) Text("not on this car", fontSize = ui.small, color = cs.onSurfaceVariant.copy(alpha = 0.6f))
                        }
                    }
                }
            }
        }

        if (ui.wide) {
            Row(Modifier.fillMaxSize()) {
                nameEditor(Modifier.weight(0.58f).fillMaxHeight())
                Spacer(Modifier.width(ui.pad))
                sensorList(Modifier.weight(0.42f).fillMaxHeight())
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                nameEditor(Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                sensorList(Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

/* ------------------------------ Graph ------------------------------ */

@Composable
private fun GraphView(obd: ObdManager, metric: Boolean, selected: List<String>, onSelect: (List<String>) -> Unit) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val available = remember(obd.supported) { ObdPids.all.filter { it.key in obd.supported } }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            available.forEach { pid ->
                val on = pid.key in selected
                Pill(pid.name, color = if (on) cs.primary else cs.onSurfaceVariant, selected = on, onClick = {
                    onSelect(
                        if (on) selected - pid.key
                        else (selected + pid.key).takeLast(3) // up to 3 graphs at once
                    )
                })
            }
        }
        Spacer(Modifier.height(8.dp))
        if (selected.isEmpty()) {
            EmptyState(AppIcons.Gauge, "Pick a sensor", "Choose up to three sensors above to graph.", Modifier.fillMaxSize())
            return@Column
        }
        val tick = obd.tick // redraw when new readings arrive
        selected.forEach { key ->
            val pid = ObdPids[key] ?: return@forEach
            val data = remember(key, tick, metric) { obd.historyOf(key).map { pid.value(it, metric) } }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(cs.surface)
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(pid.name, fontSize = ui.body, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                    Text(
                        pid.format(obd.values[key], metric) + " " + pid.unitFor(metric),
                        fontSize = ui.body,
                        fontWeight = FontWeight.Bold,
                        color = cs.primary,
                    )
                }
                val line = cs.primary
                val grid = cs.surfaceVariant
                Canvas(Modifier.fillMaxSize().padding(top = 6.dp)) {
                    drawLine(grid, Offset(0f, size.height), Offset(size.width, size.height), 2f)
                    drawLine(grid, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1f)
                    if (data.size >= 2) {
                        var lo = data.min()
                        var hi = data.max()
                        if (hi - lo < 0.001f) {
                            lo -= 1f
                            hi += 1f
                        }
                        val pad = (hi - lo) * 0.1f
                        lo -= pad
                        hi += pad
                        val stepX = size.width / (data.size - 1)
                        val path = Path()
                        data.forEachIndexed { i, v ->
                            val x = i * stepX
                            val y = size.height * (1f - (v - lo) / (hi - lo))
                            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                        }
                        drawPath(path, line, style = Stroke(width = 4f, cap = StrokeCap.Round))
                    }
                }
            }
        }
    }
}

/* ------------------------------ List ------------------------------ */

@Composable
private fun ListView(obd: ObdManager, metric: Boolean) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val available = remember(obd.supported) { ObdPids.all.filter { it.key in obd.supported } }
    LazyColumn(
        contentPadding = PaddingValues(bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(available, key = { it.key }) { pid ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(cs.surface)
                    .padding(horizontal = 16.dp, vertical = if (ui.short) 9.dp else 13.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(pid.name, fontSize = ui.body, modifier = Modifier.weight(1f))
                Text(pid.format(obd.values[pid.key], metric), fontSize = ui.title, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(6.dp))
                Text(pid.unitFor(metric), fontSize = ui.small, color = cs.onSurfaceVariant, modifier = Modifier.width(44.dp))
            }
        }
    }
}
