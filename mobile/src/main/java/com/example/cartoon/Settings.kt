package com.example.cartoon

import android.content.Context
import android.content.res.Configuration
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

enum class ThemeMode(val label: String) { DYNAMIC("Wallpaper"), NIGHT("Night"), DAY("Day"), CUSTOM("Custom") }

/** Soft, dark gradients for Custom mode. */
enum class GradientPreset(val label: String, val colors: List<Color>) {
    AURORA("Aurora", listOf(Color(0xFF0F2027), Color(0xFF1D3B47), Color(0xFF2A4F5E))),
    DUSK("Dusk", listOf(Color(0xFF1E1530), Color(0xFF34244F), Color(0xFF151020))),
    SUNSET("Sunset", listOf(Color(0xFF2A1426), Color(0xFF55253A), Color(0xFF1C1018))),
    OCEAN("Ocean", listOf(Color(0xFF071A2B), Color(0xFF0E3A4E), Color(0xFF06131D))),
    FOREST("Forest", listOf(Color(0xFF0E1F18), Color(0xFF1C3B2D), Color(0xFF0A1410))),
    GRAPHITE("Graphite", listOf(Color(0xFF1B1D22), Color(0xFF2B2F38), Color(0xFF111215))),
}

enum class Accent(val label: String, val color: Color, val dayColor: Color) {
    AMBER("Amber", Color(0xFFFFB547), Color(0xFFB86A00)),
    MINT("Mint", Color(0xFF6EE7B7), Color(0xFF0E8A5C)),
    SKY("Sky", Color(0xFF7FD4FF), Color(0xFF0B6FA4)),
    ROSE("Rose", Color(0xFFFF8FAB), Color(0xFFC2335B)),
    LAVENDER("Lavender", Color(0xFFC4A7FF), Color(0xFF6A49C6)),
}

enum class LyricSize(val label: String, val scale: Float) {
    SMALL("Small", 0.85f), MEDIUM("Medium", 1f), LARGE("Large", 1.2f),
}

data class AppSettings(
    val themeMode: ThemeMode = ThemeMode.DYNAMIC,
    val gradient: GradientPreset = GradientPreset.AURORA,
    val accent: Accent = Accent.AMBER,
    val lyricSize: LyricSize = LyricSize.MEDIUM,
    val carOpensNowPlaying: Boolean = true,
    val youtubeKey: String = "",
    val spotifyClientId: String = "",
    val obdAddress: String = "",
    val obdName: String = "",
    val metricUnits: Boolean = false,
    val onlineLyrics: Boolean = true,
)

/** Saves settings on the phone so they stick between launches. */
class SettingsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cartoon_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            themeMode = enumOr(prefs.getString("theme", null), d.themeMode),
            gradient = enumOr(prefs.getString("gradient", null), d.gradient),
            accent = enumOr(prefs.getString("accent", null), d.accent),
            lyricSize = enumOr(prefs.getString("lyric_size", null), d.lyricSize),
            carOpensNowPlaying = prefs.getBoolean("car_now_playing", d.carOpensNowPlaying),
            youtubeKey = prefs.getString("youtube_key", "").orEmpty(),
            spotifyClientId = prefs.getString("spotify_client_id", "").orEmpty(),
            obdAddress = prefs.getString("obd_address", "").orEmpty(),
            obdName = prefs.getString("obd_name", "").orEmpty(),
            metricUnits = prefs.getBoolean("metric_units", false),
            onlineLyrics = prefs.getBoolean("online_lyrics", true),
        )
    }

    fun save(s: AppSettings) {
        prefs.edit()
            .putString("theme", s.themeMode.name)
            .putString("gradient", s.gradient.name)
            .putString("accent", s.accent.name)
            .putString("lyric_size", s.lyricSize.name)
            .putBoolean("car_now_playing", s.carOpensNowPlaying)
            .putString("youtube_key", s.youtubeKey.trim())
            .putString("spotify_client_id", s.spotifyClientId.trim())
            .putString("obd_address", s.obdAddress)
            .putString("obd_name", s.obdName)
            .putBoolean("metric_units", s.metricUnits)
            .putBoolean("online_lyrics", s.onlineLyrics)
            .apply()
    }

    private inline fun <reified T : Enum<T>> enumOr(name: String?, default: T): T =
        name?.let { n -> runCatching { enumValueOf<T>(n) }.getOrNull() } ?: default
}

data class AppTheme(val colors: ColorScheme, val background: Brush, val isDark: Boolean)

/**
 * Builds the colors. "Wallpaper" uses Android's Material You colors taken from the phone's
 * wallpaper (like Pixel and Samsung One UI apps) and follows the phone's dark mode.
 */
fun AppSettings.toTheme(context: Context? = null): AppTheme = when (themeMode) {
    ThemeMode.DYNAMIC -> {
        val dark = context?.let {
            (it.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        } ?: true
        val base = when {
            context == null -> darkColorScheme()
            dark -> dynamicDarkColorScheme(context)
            else -> dynamicLightColorScheme(context)
        }
        AppTheme(
            // Cards sit on a slightly raised tone, like One UI / Material 3 surfaces.
            colors = base.copy(
                surface = base.surfaceContainerHigh,
                surfaceVariant = base.surfaceContainerHighest,
                outline = base.outlineVariant,
            ),
            background = Brush.verticalGradient(listOf(base.surfaceContainerLow, base.surfaceContainerLowest)),
            isDark = dark,
        )
    }


    ThemeMode.NIGHT -> AppTheme(
        colors = darkColorScheme(
            primary = accent.color,
            onPrimary = Color(0xFF1A1206),
            secondary = Color(0xFF7FD4FF),
            onSecondary = Color(0xFF00222F),
            background = Color(0xFF0E0F12),
            onBackground = Color(0xFFF2F2F5),
            surface = Color(0xFF17191E),
            onSurface = Color(0xFFF2F2F5),
            surfaceVariant = Color(0xFF23262D),
            onSurfaceVariant = Color(0xFFA9AEB9),
            outline = Color(0xFF3A3E47),
        ),
        background = Brush.verticalGradient(listOf(Color(0xFF181A21), Color(0xFF0B0C0F))),
        isDark = true,
    )

    ThemeMode.DAY -> AppTheme(
        colors = lightColorScheme(
            primary = accent.dayColor,
            onPrimary = Color.White,
            secondary = Color(0xFF0B6FA4),
            onSecondary = Color.White,
            background = Color(0xFFF6F4F0),
            onBackground = Color(0xFF1B1D22),
            surface = Color(0xE6FFFFFF),
            onSurface = Color(0xFF1B1D22),
            surfaceVariant = Color(0xFFE6E9EF),
            onSurfaceVariant = Color(0xFF5B6270),
            outline = Color(0xFFD3D7DE),
        ),
        background = Brush.linearGradient(listOf(Color(0xFFFFF3E3), Color(0xFFF1F4FA), Color(0xFFE3F0F4))),
        isDark = false,
    )

    ThemeMode.CUSTOM -> AppTheme(
        colors = darkColorScheme(
            primary = accent.color,
            onPrimary = Color(0xFF1A1206),
            secondary = Color(0xFFBFE6FF),
            onSecondary = Color(0xFF00222F),
            background = gradient.colors.last(),
            onBackground = Color(0xFFF4F5F8),
            surface = Color(0x1AFFFFFF),
            onSurface = Color(0xFFF4F5F8),
            surfaceVariant = Color(0x26FFFFFF),
            onSurfaceVariant = Color(0xFFC8CDD6),
            outline = Color(0x33FFFFFF),
        ),
        background = Brush.linearGradient(gradient.colors),
        isDark = true,
    )
}
