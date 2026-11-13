package com.example.cartoon

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/** Power tools: a raw serial terminal to the adapter, and the custom sensor builder. */
@Composable
fun TerminalView(obd: ObdManager) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    var tab by remember { mutableStateOf(0) }

    Column(Modifier.fillMaxSize()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Terminal", selected = tab == 0, color = if (tab == 0) cs.primary else cs.onSurfaceVariant, onClick = { tab = 0 })
            Pill("Custom sensors", selected = tab == 1, color = if (tab == 1) cs.primary else cs.onSurfaceVariant, onClick = { tab = 1 })
        }
        Spacer(Modifier.height(10.dp))
        if (tab == 0) Terminal(obd) else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { CustomPidEditor(obd) }
    }
}

@Composable
private fun Terminal(obd: ObdManager) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    val lines = remember { mutableStateListOf<String>() }
    var input by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val context = LocalContext.current

    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.animateScrollToItem(lines.lastIndex) }

    fun send(text: String) {
        val cmd = text.trim()
        if (cmd.isEmpty() || busy) return
        busy = true
        lines += "> ${cmd.uppercase()}"
        input = ""
        scope.launch {
            val reply = obd.sendRaw(cmd)
            if (reply == null) lines += "(not connected)" else reply.lines().filter { it.isNotBlank() }.forEach { lines += it.trim() }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize()) {
        Text(
            "Sends exactly what you type to the adapter (like ATZ, ATDPN, 0100 or 22194001). Changing settings with AT commands can confuse the connection; Disconnect and reconnect to reset.",
            fontSize = ui.small,
            color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("ATZ", "ATI", "ATDPN", "ATRV", "0100", "0902").forEach { c -> Pill(c, onClick = { send(c) }) }
        }
        Spacer(Modifier.height(8.dp))
        LazyColumn(
            state = listState,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(cs.surface)
                .padding(12.dp),
        ) {
            if (lines.isEmpty()) item { Text("Nothing sent yet.", fontSize = ui.small, color = cs.onSurfaceVariant) }
            items(lines) { l ->
                Text(
                    l,
                    fontFamily = FontFamily.Monospace,
                    fontSize = ui.small,
                    color = if (l.startsWith(">")) cs.primary else cs.onSurface,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                input,
                { input = it.uppercase().filter { c -> c.isLetterOrDigit() || c == ' ' }.take(40) },
                singleLine = true,
                label = { Text("Command") },
                modifier = Modifier.weight(1f),
            )
            Pill("Send", selected = true, onClick = { send(input) })
            Pill("Copy log", color = cs.onSurfaceVariant, onClick = {
                shareTextOnPhone(context, lines.joinToString("\n"), "CarToon terminal log")
            })
            Pill("Clear", color = cs.onSurfaceVariant, onClick = { lines.clear() })
        }
    }
}

