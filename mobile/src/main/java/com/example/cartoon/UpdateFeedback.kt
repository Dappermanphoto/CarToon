package com.example.cartoon

import android.net.Uri
import android.os.Build
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File

/** Opens a GitHub issue page, filled in with the app and phone details (no personal data). */
fun feedbackUri(versionName: String): Uri {
    val body = "**What's on your mind?** (a bug, something to add, something to remove)\n\n\n\n" +
        "---\n_Added automatically:_\nCarToon V$versionName\n" +
        "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})\n" +
        "Device: ${Build.MANUFACTURER} ${Build.MODEL}"
    return Uri.parse(
        "https://github.com/$GITHUB_REPO/issues/new?labels=feedback" +
            "&title=" + Uri.encode("Feedback: ") + "&body=" + Uri.encode(body)
    )
}

/** Big pill at the very top of Settings. */
@Composable
fun FeedbackButton() {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, label = "feedbackPress")
    var note by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { scaleX = scale; scaleY = scale }
                .clip(CircleShape)
                .background(Brush.horizontalGradient(listOf(cs.primary, cs.tertiary)))
                .clickable(interactionSource = source, indication = null) {
                    val ok = openOnPhone(context, feedbackUri(Updater.installedVersion(context)))
                    note = when {
                        !ok -> "No web browser found on this phone."
                        ui.car -> "Opened on your phone."
                        else -> null
                    }
                }
                .padding(vertical = if (ui.short) 12.dp else 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Feedback? Click Me!",
                fontSize = ui.body,
                fontWeight = FontWeight.Bold,
                color = cs.onPrimary,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            note ?: "Opens a GitHub page for bugs and ideas. A free GitHub account is needed.",
            fontSize = ui.small,
            color = if (note != null) cs.primary else cs.onSurfaceVariant,
            modifier = Modifier.padding(start = 10.dp),
        )
    }
}

private sealed interface UpState {
    data object Checking : UpState
    data object UpToDate : UpState
    data class Available(val r: ReleaseInfo) : UpState
    data class Downloading(val r: ReleaseInfo, val progress: Float) : UpState
    data class Ready(val r: ReleaseInfo, val file: File) : UpState
    data class Problem(val msg: String) : UpState
}

/** "App updates" card: checks GitHub, downloads the newest APK and starts the Android installer. */
@Composable
fun UpdateSection() {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val installed = remember { Updater.installedVersion(context) }
    var state by remember { mutableStateOf<UpState>(UpState.Checking) }
    var hint by remember { mutableStateOf<String?>(null) }

    fun installNow(r: ReleaseInfo, file: File) {
        if (Updater.sameSigner(context, file) == false) {
            state = UpState.Problem(
                "This update is signed with a different key than the CarToon on your phone " +
                    "(usually a test build). Uninstall CarToon once, then install the APK from the " +
                    "GitHub Releases page. After that, updates here install normally."
            )
            return
        }
        when (Updater.install(context, file)) {
            InstallStart.STARTED -> { hint = null; state = UpState.Ready(r, file) }
            InstallStart.NEEDS_PERMISSION -> {
                hint = "Allow CarToon to install apps on the screen that just opened, come back, and tap Install now."
                state = UpState.Ready(r, file)
            }
            InstallStart.FAILED -> state = UpState.Problem("Android couldn't open the installer.")
        }
    }

    fun download(r: ReleaseInfo) {
        state = UpState.Downloading(r, 0f)
        scope.launch {
            Updater.download(context, r) { p -> state = UpState.Downloading(r, p) }.fold(
                onSuccess = { f -> state = UpState.Ready(r, f); installNow(r, f) },
                onFailure = { state = UpState.Problem("The download failed. Check your connection and try again.") },
            )
        }
    }

    fun checkNow() {
        if (state is UpState.Downloading) return
        state = UpState.Checking
        hint = null
        scope.launch {
            Updater.latest().fold(
                onSuccess = { r ->
                    state = when {
                        r == null -> UpState.Problem("No release found on GitHub yet.")
                        r.apkUrl.isBlank() -> UpState.Problem("The latest release has no APK attached.")
                        Updater.isNewer(r.version, installed) -> UpState.Available(r)
                        else -> UpState.UpToDate
                    }
                },
                onFailure = { state = UpState.Problem("Couldn't reach GitHub. Check your internet connection.") },
            )
        }
    }

    LaunchedEffect(Unit) { if (!ui.car) checkNow() }

    Text(
        "APP UPDATES",
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
    ) {
        Text("Installed: V$installed", fontSize = ui.body, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(8.dp))
        if (ui.car) {
            Text(
                "Open CarToon's Settings on your phone to update it.",
                fontSize = ui.small,
                color = cs.onSurfaceVariant,
            )
        } else {
            when (val s = state) {
                UpState.Checking -> Text("Checking GitHub for updates…", fontSize = ui.small, color = cs.onSurfaceVariant)
                UpState.UpToDate -> {
                    Text("You're on the latest version.", fontSize = ui.small, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    Pill("Check again", onClick = { checkNow() })
                }
                is UpState.Available -> {
                    Text("Version ${s.r.version} is available", fontSize = ui.body, fontWeight = FontWeight.Bold, color = cs.primary)
                    val notes = s.r.notes.replace(Regex("[#*`>_]"), "").trim().take(240)
                    if (notes.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(notes, fontSize = ui.small, color = cs.onSurfaceVariant)
                    }
                    Spacer(Modifier.height(12.dp))
                    Pill("Download and install", selected = true, onClick = { download(s.r) })
                }
                is UpState.Downloading -> {
                    Text("Downloading… ${(s.progress * 100).toInt()}%", fontSize = ui.small, color = cs.onSurfaceVariant)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
                }
                is UpState.Ready -> {
                    Text(
                        hint ?: "Downloaded. Tap Install when Android asks you to confirm.",
                        fontSize = ui.small,
                        color = cs.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    Pill("Install now", selected = true, onClick = { installNow(s.r, s.file) })
                }
                is UpState.Problem -> {
                    Text(s.msg, fontSize = ui.small, color = cs.error)
                    Spacer(Modifier.height(10.dp))
                    Pill("Try again", onClick = { checkNow() })
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(
                "Updates come from github.com/$GITHUB_REPO. Android asks you to confirm every install.",
                fontSize = ui.small,
                color = cs.onSurfaceVariant,
            )
        }
    }
    Spacer(Modifier.height(20.dp))
}
