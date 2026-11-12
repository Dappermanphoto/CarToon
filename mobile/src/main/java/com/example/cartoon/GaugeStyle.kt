package com.example.cartoon

import android.content.Context
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

/** Custom gauge color, or null to follow the app's accent color. */
val LocalGaugeColor = staticCompositionLocalOf<Color?> { null }

/** "#FF9A1F" or "FF9A1F" to a color; null if it isn't a 6-digit hex code. */
fun parseHexColor(text: String): Color? {
    val h = text.trim().removePrefix("#")
    if (h.length != 6 || !h.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return null
    return Color(0xFF000000 or h.toLong(16))
}

/** Lets the gauge clusters match the car's dashboard lighting with a hex color code. */
@Stable
class GaugeStyle(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cartoon_gauge_style", Context.MODE_PRIVATE)

    /** Saved hex code without the #, or empty to use the app's accent color. */
    var hex by mutableStateOf(prefs.getString("hex", "").orEmpty())
        private set

    val color: Color? get() = parseHexColor(hex)

    fun choose(value: String) {
        hex = value.trim().removePrefix("#").uppercase()
        prefs.edit().putString("hex", hex).apply()
    }

    companion object {
        /** Common dashboard lighting colors. */
        val PRESETS = listOf(
            "Amber" to "FF9A1F",
            "Red" to "FF2A2A",
            "Ice blue" to "7FD4FF",
            "Green" to "39FF14",
            "White" to "F2F2F2",
            "Purple" to "B26BFF",
        )
    }
}
