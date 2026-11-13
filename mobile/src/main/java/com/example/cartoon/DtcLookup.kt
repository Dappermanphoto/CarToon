package com.example.cartoon

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.Display
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Web lookup for a trouble code. Opens the phone's browser, so it also works from the car screen
 * (the page appears on the phone). Uses the vehicle from the Profile tab, or the demo car.
 */
@Composable
fun DtcLookupRow(code: String, vehicle: String) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    var note by remember(code) { mutableStateOf<String?>(null) }

    fun open(uri: Uri) {
        note = if (openOnPhone(context, uri)) {
            if (ui.car) "Opened on your phone." else null
        } else "No web browser found on this phone."
    }

    Column {
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Pill("Search the web", selected = true, onClick = { open(dtcSearchUri(code, vehicle)) })
            Pill("Code database", onClick = { open(dtcDatabaseUri(code)) })
        }
        if (vehicle.isBlank()) {
            Spacer(Modifier.height(6.dp))
            Text(
                "Add your vehicle in the Profile tab to make searches specific to it.",
                fontSize = ui.small,
                color = cs.onSurfaceVariant,
            )
        }
        note?.let {
            Spacer(Modifier.height(6.dp))
            Text(it, fontSize = ui.small, color = cs.primary)
        }
    }
}

fun dtcSearchUri(code: String, vehicle: String): Uri =
    Uri.parse("https://www.google.com/search?q=" + Uri.encode("$vehicle $code code meaning causes fix".trim()))

/** YouTube search for repair videos about this code on this vehicle. */
fun dtcVideoUri(code: String, vehicle: String): Uri =
    Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode("$vehicle $code how to fix".trim()))

fun dtcDatabaseUri(code: String): Uri =
    Uri.parse("https://www.obd-codes.com/" + Uri.encode(code.lowercase()))

/** Starts the browser on the phone's own screen, even when called from the car display. */
internal fun openOnPhone(context: Context, uri: Uri): Boolean {
    val intent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val options = runCatching {
        ActivityOptions.makeBasic().setLaunchDisplayId(Display.DEFAULT_DISPLAY).toBundle()
    }.getOrNull()
    return runCatching { context.startActivity(intent, options) }
        .recoverCatching { context.startActivity(intent) }
        .isSuccess
}
