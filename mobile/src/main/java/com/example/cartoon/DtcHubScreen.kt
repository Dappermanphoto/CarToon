package com.example.cartoon

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.text.DateFormat
import java.util.Date

private val SeverityRed = Color(0xFFFF5449)
private val SeverityAmber = Color(0xFFFFB547)
private val SeverityGreen = Color(0xFF6EE7B7)

private fun DtcSeverity.color(): Color = when (this) {
    DtcSeverity.URGENT -> SeverityRed
    DtcSeverity.SOON -> SeverityAmber
    DtcSeverity.INFO -> SeverityGreen
}

/**
 * Diagnostic hub: check-engine light status, stored and pending codes with offline
 * descriptions, and clearing codes. Works on the phone and the car screen, and with the demo car.
 */
@Composable
fun DtcHubScreen(obd: ObdManager) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme

    var confirmClear by remember { mutableStateOf(false) }
    var lastRead by remember { mutableLongStateOf(0L) }
    var expanded by remember { mutableStateOf<String?>(null) }

    // Read once when the hub opens, and note the time whenever a read finishes.
    LaunchedEffect(Unit) { if (obd.storedCodes == null) obd.readCodes() }
    LaunchedEffect(Unit) {
        snapshotFlow { obd.codesBusy to obd.storedCodes }.collect { (busy, codes) ->
            if (!busy && codes != null) lastRead = System.currentTimeMillis()
        }
    }

    val stored = obd.storedCodes
    val pending = obd.pendingCodes
    val vehicle = if (obd.demo) "1994 Honda Accord" else LocalVehicle.current?.vehicleName().orEmpty()

    if (confirmClear) {
        ClearConfirm(
            count = (stored?.size ?: 0) + (pending?.size ?: 0),
            onCancel = { confirmClear = false },
            onClear = {
                confirmClear = false
                obd.clearCodes()
            },
        )
        return
    }

    // The look-up button follows the open card, or the first code if none is open.
    val focusCode = expanded?.drop(1) ?: stored?.firstOrNull() ?: pending?.firstOrNull()

    Box(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
        // Status + actions
        Row(verticalAlignment = Alignment.CenterVertically) {
            val milOn = !stored.isNullOrEmpty()
            Box(
                Modifier
                    .size(if (ui.short) 12.dp else 14.dp)
                    .clip(CircleShape)
                    .background(
                        when {
                            stored == null -> cs.onSurfaceVariant
                            milOn -> SeverityAmber
                            else -> SeverityGreen
                        }
                    )
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        stored == null -> "Codes not read yet"
                        milOn -> "${stored.size} stored code${if (stored.size == 1) "" else "s"}" +
                            if (!pending.isNullOrEmpty()) " · ${pending.size} pending" else ""
                        !pending.isNullOrEmpty() -> "No stored codes · ${pending.size} pending"
                        else -> "No trouble codes"
                    },
                    fontSize = ui.title,
                    fontWeight = FontWeight.Bold,
                )
                if (lastRead > 0) {
                    Text(
                        "Last read " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(lastRead)) +
                            if (obd.demo) " · demo car" else "",
                        fontSize = ui.small,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
            if (obd.codesBusy) {
                CircularProgressIndicator(Modifier.size(24.dp), color = cs.primary, strokeWidth = 3.dp)
                Spacer(Modifier.width(10.dp))
            }
            Pill("Read", selected = true, onClick = { obd.readCodes() })
            Spacer(Modifier.width(8.dp))
            Pill("Clear", color = cs.onSurfaceVariant, onClick = { confirmClear = true })
        }
        obd.codesNote?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = ui.body, color = cs.primary)
        }
        Spacer(Modifier.height(if (ui.short) 8.dp else 14.dp))

        LazyColumn(
            contentPadding = PaddingValues(bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxSize(),
        ) {
            item { SectionLabel("Stored codes", "Confirmed faults. These turn on the check-engine light.") }
            when {
                stored == null -> item { Hint("Tap Read to check for codes.") }
                stored.isEmpty() -> item { Hint("None. Nice.") }
                else -> items(stored, key = { "s$it" }) { code ->
                    DtcCard(code, expanded == "s$code", vehicle) { expanded = if (expanded == "s$code") null else "s$code" }
                }
            }
            item {
                Spacer(Modifier.height(8.dp))
                SectionLabel("Pending codes", "Seen once. They become stored if they happen again.")
            }
            when {
                pending == null -> item { Hint("Not read yet.") }
                pending.isEmpty() -> item { Hint("None.") }
                else -> items(pending, key = { "p$it" }) { code ->
                    DtcCard(code, expanded == "p$code", vehicle) { expanded = if (expanded == "p$code") null else "p$code" }
                }
            }
        }
    }
    androidx.compose.animation.AnimatedVisibility(
        visible = focusCode != null && !obd.codesBusy,
        enter = androidx.compose.animation.fadeIn() + androidx.compose.animation.slideInVertically { it / 2 },
        exit = androidx.compose.animation.fadeOut() + androidx.compose.animation.slideOutVertically { it / 2 },
        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
    ) {
        LookupFab(focusCode.orEmpty(), vehicle)
    }
    }
}

@Composable
private fun SectionLabel(title: String, body: String) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    Column(Modifier.padding(start = 4.dp, bottom = 2.dp)) {
        Text(title.uppercase(), fontSize = ui.small, fontWeight = FontWeight.Bold, color = cs.primary)
        Text(body, fontSize = ui.small, color = cs.onSurfaceVariant)
    }
}

@Composable
private fun Hint(text: String) {
    val ui = LocalUi.current
    Text(
        text,
        fontSize = ui.body,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp, bottom = 4.dp),
    )
}

/** One code. Tap to show what it means and what to check. */
@Composable
private fun DtcCard(code: String, open: Boolean, vehicle: String, onToggle: () -> Unit) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val info = remember(code) { DtcDescriptions.describe(code) }
    val context = androidx.compose.ui.platform.LocalContext.current
    // Official code name from obd-codes.com when available; built-in text otherwise
    val webTitle by androidx.compose.runtime.produceState<String?>(null, code) {
        value = DtcWeb.title(context, code)
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(cs.surface)
            .clickable(onClick = onToggle)
            .animateContentSize()
            .padding(horizontal = 16.dp, vertical = if (ui.short) 10.dp else 14.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(cs.primary.copy(alpha = 0.14f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(info.code, fontSize = ui.title, fontWeight = FontWeight.Black, color = cs.primary)
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(webTitle ?: info.title, fontSize = ui.body, fontWeight = FontWeight.SemiBold, maxLines = if (open) 4 else 2)
                Text(
                    info.system + if (webTitle != null) " · obd-codes.com" else "",
                    fontSize = ui.small,
                    color = cs.onSurfaceVariant,
                )
            }
            Text(if (open) "Less" else "More", fontSize = ui.small, color = cs.primary)
        }
        if (open) {
            Spacer(Modifier.height(10.dp))
            Text(info.advice, fontSize = ui.body, color = cs.onSurface, lineHeight = ui.body * 1.35f)
            Spacer(Modifier.height(6.dp))
            Text(
                if (info.generic) "Standard OBD-II code (same meaning on every car)."
                else "Manufacturer-specific code (meaning depends on the brand).",
                fontSize = ui.small,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            var videoNote by remember(code) { mutableStateOf<String?>(null) }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Pill("Watch repair videos", selected = true, onClick = {
                    videoNote = if (openOnPhone(context, dtcVideoUri(code, vehicle))) {
                        if (ui.car) "Opened on your phone." else null
                    } else "No browser or YouTube app found."
                })
                videoNote?.let { Text(it, fontSize = ui.small, color = cs.primary) }
            }
        }
    }
}

@Composable
private fun ClearConfirm(count: Int, onCancel: () -> Unit, onClear: () -> Unit) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(cs.surface)
            .padding(22.dp)
    ) {
        Text(
            if (count > 0) "Clear $count code${if (count == 1) "" else "s"}?" else "Clear codes?",
            fontSize = ui.title,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "This turns off the check-engine light and erases stored codes and freeze-frame data. " +
                "It also resets the emissions readiness monitors, so the car needs some driving before " +
                "it can pass a smog test. If the problem isn't fixed, the code comes back.\n\n" +
                "Do this with the ignition on and the engine off.",
            fontSize = ui.body,
            color = cs.onSurfaceVariant,
            lineHeight = ui.body * 1.35f,
        )
        Spacer(Modifier.height(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Pill("Cancel", color = cs.onSurfaceVariant, onClick = onCancel)
            Pill("Clear codes", selected = true, onClick = onClear)
        }
    }
}
