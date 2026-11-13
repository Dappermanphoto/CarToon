package com.example.cartoon

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

private fun copyText(context: Context, label: String, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
}

/** Shown once, the first time CarToon opens on the phone. */
@Composable
fun WelcomeGate(onTryDemo: () -> Unit) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("cartoon_app", Context.MODE_PRIVATE) }
    var show by remember { mutableStateOf(!prefs.getBoolean("welcomed", false)) }
    if (!show) return
    val cs = MaterialTheme.colorScheme
    val close = {
        prefs.edit().putBoolean("welcomed", true).apply()
        show = false
    }
    AlertDialog(
        onDismissRequest = close,
        containerColor = cs.surface,
        title = { Text("Welcome to CarToon", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("CarToon puts your music and your car's data on the car screen while you're parked.", color = cs.onSurfaceVariant)
                Text("• OBD gauges and trouble codes: plug in a Bluetooth OBD-II adapter, then pick it in Settings.", color = cs.onSurface)
                Text("• Lyrics for any music app: turn on Notification access in Settings.", color = cs.onSurface)
                Text("• Spotify: Settings has a step-by-step setup guide.", color = cs.onSurface)
                Text("No adapter yet? Try the demo car and look around.", color = cs.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { close(); onTryDemo() }) { Text("Try the demo car") } },
        dismissButton = { TextButton(onClick = close) { Text("Got it") } },
    )
}

/** Step-by-step Spotify setup with copy buttons for the values the Spotify dashboard asks for. */
@Composable
fun SpotifyGuideDialog(onClose: () -> Unit) {
    val context = LocalContext.current
    val cs = MaterialTheme.colorScheme
    val ui = LocalUi.current
    val sha1 = remember { appSha1(context) }

    @Composable
    fun CopyRow(label: String, value: String) {
        var copied by remember(value) { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = ui.small, color = cs.onSurfaceVariant)
                Text(value, fontSize = ui.small, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.width(8.dp))
            Pill(if (copied) "Copied" else "Copy", selected = !copied, onClick = {
                copyText(context, label, value)
                copied = true
            })
        }
    }

    AlertDialog(
        onDismissRequest = onClose,
        containerColor = cs.surface,
        title = { Text("Spotify setup", fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Spotify needs your own free \"app\" so CarToon can connect. It takes about five minutes.", color = cs.onSurfaceVariant)
                Text("1. Open developer.spotify.com/dashboard and log in with your Spotify account.", color = cs.onSurface)
                Text("2. Create app. Any name and description. Tick \"Android\" when it asks which APIs you'll use.", color = cs.onSurface)
                Text("3. Add this Redirect URI:", color = cs.onSurface)
                CopyRow("Redirect URI", SPOTIFY_REDIRECT_URI)
                Text("4. In the app's settings, add an Android package with these two values:", color = cs.onSurface)
                CopyRow("Package name", context.packageName)
                if (sha1 != null) CopyRow("SHA1 fingerprint", sha1)
                Text("5. Under User Management, add the email of your Spotify account.", color = cs.onSurface)
                Text("6. Copy the Client ID from the app's Settings page and paste it into the Spotify box in CarToon's Settings.", color = cs.onSurface)
                Text("Then open the Spotify tab and tap Connect.", color = cs.onSurfaceVariant)
                Spacer(Modifier.height(2.dp))
                Pill("Open the Spotify dashboard", selected = true, onClick = {
                    openOnPhone(context, android.net.Uri.parse("https://developer.spotify.com/dashboard"))
                })
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } },
    )
}
