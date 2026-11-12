package com.example.cartoon

import android.content.Context
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/**
 * Looks up the official code name on obd-codes.com (the same site the lookup button opens).
 * Only the short code title is read, kept on the phone after the first time, so codes you have
 * seen before still show their name without internet. Falls back to the built-in description.
 */
object DtcWeb {
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("cartoon_dtc_web", Context.MODE_PRIVATE)

    suspend fun title(context: Context, code: String): String? = withContext(Dispatchers.IO) {
        val key = code.uppercase()
        val prefs = prefs(context)
        prefs.getString(key, null)?.let { return@withContext it }
        val found = runCatching { fetch(key) }.getOrNull()
        if (found != null) prefs.edit().putString(key, found).apply()
        found
    }

    private fun fetch(code: String): String? {
        val conn = URL("https://www.obd-codes.com/" + code.lowercase()).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 6000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) CarToon")
            if (conn.responseCode != 200) return null
            val html = conn.inputStream.bufferedReader().use { it.readText().take(250_000) }
            return parseTitle(html, code)
        } finally {
            conn.disconnect()
        }
    }

    internal fun parseTitle(html: String, code: String): String? {
        val options = setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE)
        val candidates = listOf(
            Regex("<h1[^>]*>(.*?)</h1>", options).find(html)?.groupValues?.get(1),
            Regex("<title[^>]*>(.*?)</title>", options).find(html)?.groupValues?.get(1),
        )
        for (raw in candidates) {
            if (raw == null) continue
            var t = raw.replace(Regex("<[^>]+>"), " ")
                .replace("&amp;", "&").replace("&#039;", "'").replace("&#39;", "'")
                .replace("&quot;", "\"").replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace(Regex("\\s+"), " ").trim()
            // Drop a trailing site name such as " - OBD-Codes.com" or " | OBD Codes"
            t = t.replace(Regex("\\s*[|\u2013\u2014-]\\s*[^|\u2013\u2014-]*obd[^|\u2013\u2014-]*$", RegexOption.IGNORE_CASE), "")
            // Drop the code itself at the start
            t = t.replace(Regex("^" + Regex.escape(code) + "\\s*[:\\-\u2013\u2014]?\\s*", RegexOption.IGNORE_CASE), "").trim()
            if (t.length in 4..110 && !t.equals("OBD Codes", ignoreCase = true)) return t
        }
        return null
    }
}

/**
 * Floating "look up" button, centered at the bottom of the screen. Opens a web search for the
 * code with your vehicle. Springs when pressed.
 */
@Composable
fun LookupFab(code: String, vehicle: String, modifier: Modifier = Modifier) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) 0.94f else 1f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMedium),
        label = "fabPress",
    )
    var opened by remember(code) { mutableStateOf(false) }
    LaunchedEffect(opened) {
        if (opened) {
            delay(2200)
            opened = false
        }
    }
    val label = if (opened && ui.car) "Opened on your phone" else "Look up $code"

    Row(
        modifier
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .shadow(14.dp, CircleShape, ambientColor = cs.primary.copy(alpha = 0.5f), spotColor = cs.primary.copy(alpha = 0.5f))
            .clip(CircleShape)
            .background(Brush.horizontalGradient(listOf(cs.primary, cs.tertiary)))
            .clickable(interactionSource = source, indication = null) {
                opened = openOnPhone(context, dtcSearchUri(code, vehicle))
            }
            .padding(horizontal = if (ui.short) 20.dp else 26.dp, vertical = if (ui.short) 11.dp else 15.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Search, null, tint = cs.onPrimary, modifier = Modifier.size(if (ui.short) 18.dp else 22.dp))
        Spacer(Modifier.width(10.dp))
        AnimatedContent(
            targetState = label,
            transitionSpec = { fadeIn() togetherWith fadeOut() },
            label = "fabLabel",
        ) { text ->
            Text(text, fontSize = ui.body, fontWeight = FontWeight.SemiBold, color = cs.onPrimary, maxLines = 1)
        }
    }
}
