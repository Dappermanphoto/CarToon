package com.example.cartoon

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

@Composable
fun SettingsScreen(
    obd: ObdManager,
    settings: AppSettings,
    onChange: (AppSettings) -> Unit,
    folder: Uri?,
    onPickFolder: () -> Unit,
    extra: @Composable () -> Unit = {},
    onSecretTap: () -> Unit = {},
) {
    val ui = LocalUi.current
    val context = LocalContext.current

    var hasAccess by remember { mutableStateOf(NowPlayingTracker.hasAccess(context)) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1500)
            hasAccess = NowPlayingTracker.hasAccess(context)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ui.pad, vertical = 12.dp)
    ) {
        Text("Settings", fontSize = ui.headline, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(16.dp))

        FeedbackButton()
        Spacer(Modifier.height(18.dp))
        UpdateSection()

        Section("Look") {
            Label("Theme")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeMode.entries.forEach { mode ->
                    Pill(mode.label, selected = settings.themeMode == mode, onClick = {
                        onChange(settings.copy(themeMode = mode))
                    })
                }
            }

            if (settings.themeMode == ThemeMode.CUSTOM) {
                Spacer(Modifier.height(18.dp))
                Label("Background")
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    GradientPreset.entries.forEach { g ->
                        GradientSwatch(g, selected = settings.gradient == g) {
                            onChange(settings.copy(gradient = g))
                        }
                    }
                }
            }

            Spacer(Modifier.height(18.dp))
            Label("Accent color")
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Accent.entries.forEach { a ->
                    AccentSwatch(
                        color = if (settings.themeMode == ThemeMode.DAY) a.dayColor else a.color,
                        label = a.label,
                        selected = settings.accent == a,
                    ) { onChange(settings.copy(accent = a)) }
                }
            }
        }

        Section("Lyrics") {
            Label("Text size")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LyricSize.entries.forEach { size ->
                    Pill(size.label, selected = settings.lyricSize == size, onClick = {
                        onChange(settings.copy(lyricSize = size))
                    })
                }
            }
        }

        Section("Car screen") {
            ToggleRow(
                title = "Open Now Playing first",
                body = "When CarToon opens on the car screen, start on the lyrics for what's playing.",
                checked = settings.carOpensNowPlaying,
            ) { onChange(settings.copy(carOpensNowPlaying = it)) }
        }

        Section("YouTube") {
            var keyText by remember(settings.youtubeKey) { mutableStateOf(settings.youtubeKey) }
            var show by remember { mutableStateOf(false) }
            val cs = MaterialTheme.colorScheme
            Text(
                "Search needs a free YouTube Data API key from Google Cloud Console. " +
                    "Set it up on your phone, then search from the car.",
                fontSize = ui.small,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = keyText,
                onValueChange = {
                    keyText = it
                    onChange(settings.copy(youtubeKey = it.trim()))
                },
                label = { Text("YouTube API key") },
                singleLine = true,
                visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = cs.primary,
                    unfocusedBorderColor = cs.outline,
                    focusedContainerColor = cs.surface,
                    unfocusedContainerColor = cs.surface,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (settings.youtubeKey.isBlank()) "No key yet" else "Key saved",
                    fontSize = ui.small,
                    color = if (settings.youtubeKey.isBlank()) cs.onSurfaceVariant else cs.primary,
                    modifier = Modifier.weight(1f),
                )
                if (settings.youtubeKey.isNotBlank()) {
                    Pill(if (show) "Hide" else "Show", color = cs.onSurfaceVariant, onClick = { show = !show })
                }
            }
        }

        Section("Spotify") {
            var idText by remember(settings.spotifyClientId) { mutableStateOf(settings.spotifyClientId) }
            val cs = MaterialTheme.colorScheme
            Text(
                "Paste the Client ID from your app at developer.spotify.com/dashboard. " +
                    "Its Redirect URI must be $SPOTIFY_REDIRECT_URI and its Android package ${context.packageName}.",
                fontSize = ui.small,
                color = cs.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))
            var guide by remember { mutableStateOf(false) }
            Pill("Step-by-step setup guide", selected = true, onClick = { guide = true })
            if (guide) SpotifyGuideDialog { guide = false }
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = idText,
                onValueChange = {
                    idText = it
                    onChange(settings.copy(spotifyClientId = it.trim()))
                },
                label = { Text("Spotify Client ID") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
                shape = RoundedCornerShape(16.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = cs.primary,
                    unfocusedBorderColor = cs.outline,
                    focusedContainerColor = cs.surface,
                    unfocusedContainerColor = cs.surface,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                if (settings.spotifyClientId.isBlank()) "No Client ID yet" else "Client ID saved",
                fontSize = ui.small,
                color = if (settings.spotifyClientId.isBlank()) cs.onSurfaceVariant else cs.primary,
            )

            val sha1 = remember { appSha1(context) }
            if (sha1 != null) {
                var copied by remember { mutableStateOf(false) }
                Spacer(Modifier.height(16.dp))
                Label("SHA1 fingerprint (for the Spotify dashboard)")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(sha1, fontSize = ui.small, color = cs.onSurfaceVariant, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(12.dp))
                    Pill(if (copied) "Copied" else "Copy", selected = !copied, onClick = {
                        context.getSystemService(android.content.ClipboardManager::class.java)
                            ?.setPrimaryClip(android.content.ClipData.newPlainText("SHA1", sha1))
                        copied = true
                    })
                }
                Text(
                    "Package name: " + context.packageName,
                    fontSize = ui.small,
                    color = cs.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }

        Section("OBD adapter") {
            val cs = MaterialTheme.colorScheme
            val scanPermission = androidx.activity.compose.rememberLauncherForActivityResult(
                androidx.activity.result.contract.ActivityResultContracts.RequestMultiplePermissions()
            ) { r -> if (r.values.all { it }) obd.startScan() }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (settings.obdAddress.isBlank()) "No adapter chosen"
                        else settings.obdName.ifBlank { settings.obdAddress },
                        fontSize = ui.body,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        "Plug the adapter into the car, turn the ignition on, then scan. " +
                            "A Vgate iCar Pro usually shows up as IOS-Vlink.",
                        fontSize = ui.small,
                        color = cs.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Pill(if (obd.scanning) "Scanning…" else "Scan", selected = !obd.scanning, onClick = {
                    if (ObdManager.hasPermission(context)) obd.startScan()
                    else scanPermission.launch(ObdManager.PERMISSIONS)
                })
            }
            val likely = listOf("vlink", "obd", "vgate", "elm", "icar")
            obd.found.sortedByDescending { d -> likely.any { d.name.lowercase().contains(it) } }.forEach { d ->
                val chosen = d.address == settings.obdAddress
                val unsupported = AdapterBrands.detect(d.name)
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (chosen) cs.primary.copy(alpha = 0.2f) else cs.surfaceVariant)
                        .clickable(enabled = unsupported == null) {
                            onChange(settings.copy(obdAddress = d.address, obdName = d.name))
                            obd.stopScan()
                        }
                        .padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(d.name, fontSize = ui.body, fontWeight = FontWeight.SemiBold)
                        Text(
                            if (d.classic) "${d.address} · Bluetooth Classic (paired)" else d.address,
                            fontSize = ui.small, color = cs.onSurfaceVariant,
                        )
                        if (unsupported != null) {
                            Text("Not supported. ${unsupported.message}", fontSize = ui.small, color = cs.error)
                        }
                    }
                    if (unsupported != null) {
                        Pill("Get the ${unsupported.appName} app", selected = true, onClick = { openOnPhone(context, unsupported.storeUri) })
                    } else if (chosen) {
                        Text("Selected", fontSize = ui.small, color = cs.primary)
                    }
                }
            }
            Text(
                "Bluetooth Classic adapters (OBDLink LX/MX+, BAFX, many ELM327 clones) must be paired in Android's Bluetooth settings first, then they appear here.",
                fontSize = ui.small,
                color = cs.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            Spacer(Modifier.height(16.dp))
            ToggleRow(
                title = "Metric units",
                body = "Show km/h, °C and kPa instead of mph, °F and psi.",
                checked = settings.metricUnits,
            ) { onChange(settings.copy(metricUnits = it)) }
            ConnectionSection(settings, onChange)
        }

        Section("Music apps") {
            val cs = MaterialTheme.colorScheme
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Notification access", fontSize = ui.body, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (hasAccess) "On. CarToon can follow Spotify, YouTube Music and others."
                        else "Off. Needed to show lyrics for other music apps.",
                        fontSize = ui.small,
                        color = cs.onSurfaceVariant,
                    )
                }
                Spacer(Modifier.width(12.dp))
                Pill(if (hasAccess) "Manage" else "Turn on", selected = !hasAccess, onClick = {
                    runCatching { context.startActivity(NowPlayingTracker.accessSettingsIntent(context)) }
                        .onFailure {
                            runCatching { context.startActivity(NowPlayingTracker.fallbackSettingsIntent()) }
                        }
                })
            }
        }

        Section("Lyrics") {
            ToggleRow(
                title = "Find lyrics online",
                body = "Looks up synced lyrics for the song playing in another app, using lrclib.net. " +
                    "Only the artist, title and length of the song are sent.",
                checked = settings.onlineLyrics,
            ) { onChange(settings.copy(onlineLyrics = it)) }
        }

        Section("Media folder") {
            val cs = MaterialTheme.colorScheme
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    folder?.let { Uri.decode(it.lastPathSegment.orEmpty()).substringAfter(':') }
                        ?.ifBlank { "Chosen folder" } ?: "Not chosen yet",
                    fontSize = ui.body,
                    color = if (folder == null) cs.onSurfaceVariant else cs.onSurface,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(12.dp))
                Pill(if (folder == null) "Choose" else "Change", selected = folder == null, onClick = onPickFolder)
            }
        }

        Spacer(Modifier.height(8.dp))
        // Settings for add-on modules
        extra()

        // Opens the profile in the Instagram app; falls back to the browser only if it isn't installed.
        val openInstagram = {
            val profile = Uri.parse("https://www.instagram.com/slowcd7/")
            val inApp = android.content.Intent(android.content.Intent.ACTION_VIEW, profile)
                .setPackage("com.instagram.android")
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            runCatching { context.startActivity(inApp) }.onFailure {
                runCatching {
                    context.startActivity(
                        android.content.Intent(android.content.Intent.ACTION_VIEW, profile)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            }
            Unit
        }
        // The logo is the Instagram app's own icon, read from the phone.
        val instagramIcon = rememberAppIcon("com.instagram.android")
        Row(
            Modifier.align(Alignment.CenterHorizontally).padding(bottom = 24.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "CarToon V2.0    Made by @SlowCD7",
                fontSize = ui.small,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = onSecretTap,
                ),
            )
            Spacer(Modifier.width(10.dp))
            if (instagramIcon != null) {
                androidx.compose.foundation.Image(
                    instagramIcon,
                    contentDescription = "Open @slowcd7 on Instagram",
                    modifier = Modifier
                        .size(if (ui.short) 30.dp else 34.dp)
                        .clip(RoundedCornerShape(9.dp))
                        .clickable(onClick = openInstagram),
                )
            } else {
                Pill("Instagram", onClick = openInstagram)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    Text(
        title.uppercase(),
        fontSize = ui.small,
        fontWeight = FontWeight.Bold,
        color = cs.primary,
        modifier = Modifier.padding(start = 6.dp, bottom = 8.dp),
    )
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(cs.surface)
            .padding(18.dp),
        content = content,
    )
    Spacer(Modifier.height(20.dp))
}

@Composable
private fun Label(text: String) {
    val ui = LocalUi.current
    Text(text, fontSize = ui.body, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 10.dp))
}

@Composable
private fun ToggleRow(title: String, body: String, checked: Boolean, onChecked: (Boolean) -> Unit) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = ui.body, fontWeight = FontWeight.SemiBold)
            Text(body, fontSize = ui.small, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked,
            onCheckedChange = onChecked,
            colors = SwitchDefaults.colors(
                checkedThumbColor = cs.onPrimary,
                checkedTrackColor = cs.primary,
            ),
        )
    }
}

@Composable
private fun GradientSwatch(preset: GradientPreset, selected: Boolean, onClick: () -> Unit) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(width = if (ui.short) 76.dp else 92.dp, height = if (ui.short) 52.dp else 62.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Brush.linearGradient(preset.colors))
                .border(
                    width = if (selected) 3.dp else 1.dp,
                    color = if (selected) cs.primary else Color.White.copy(alpha = 0.15f),
                    shape = RoundedCornerShape(14.dp),
                )
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(AppIcons.Check, null, tint = Color.White, modifier = Modifier.size(24.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(preset.label, fontSize = ui.small, color = if (selected) cs.onSurface else cs.onSurfaceVariant)
    }
}

@Composable
private fun AccentSwatch(color: Color, label: String, selected: Boolean, onClick: () -> Unit) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .size(if (ui.short) 40.dp else 46.dp)
                .clip(CircleShape)
                .background(color)
                .border(if (selected) 3.dp else 0.dp, cs.onSurface, CircleShape)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) Icon(AppIcons.Check, null, tint = Color(0xFF15161A), modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(6.dp))
        Text(label, fontSize = ui.small, color = if (selected) cs.onSurface else cs.onSurfaceVariant)
    }
}
