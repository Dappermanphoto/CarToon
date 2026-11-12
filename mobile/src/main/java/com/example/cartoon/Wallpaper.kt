package com.example.cartoon

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.center
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/* ------------------------------ Image helpers ------------------------------ */

/** Copies a picked photo into the app's own storage, scaled down and turned upright. */
internal fun importScaledJpeg(context: Context, uri: Uri, dest: File, maxEdge: Int): Boolean {
    return try {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxEdge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val raw = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return false

        val degrees = resolver.openInputStream(uri)?.use { s ->
            when (ExifInterface(s).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        val scale = maxEdge.toFloat() / maxOf(raw.width, raw.height)
        val matrix = Matrix().apply {
            if (degrees != 0f) postRotate(degrees)
            if (scale < 1f) postScale(scale, scale)
        }
        val out = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)

        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".tmp")
        tmp.outputStream().use { out.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        if (dest.exists()) dest.delete()
        tmp.renameTo(dest)
    } catch (e: Exception) {
        false
    }
}

internal fun loadJpeg(file: File): ImageBitmap? =
    if (file.exists()) runCatching { BitmapFactory.decodeFile(file.path)?.asImageBitmap() }.getOrNull() else null

/* ------------------------------ Storage ------------------------------ */

/** One wallpaper (portrait or landscape) with its own simple edits. */
class WallpaperSlot(private val prefs: SharedPreferences, private val key: String, val file: File) {
    var version by mutableIntStateOf(prefs.getInt("${key}_v", 0))
        private set
    var hasImage by mutableStateOf(file.exists())
        private set
    var brightness by mutableFloatStateOf(prefs.getFloat("${key}_bright", DEFAULT_BRIGHTNESS))
        private set
    var vignette by mutableFloatStateOf(prefs.getFloat("${key}_vig", DEFAULT_VIGNETTE))
        private set
    var tintHex by mutableStateOf(prefs.getString("${key}_tint", "").orEmpty())
        private set
    var tintOpacity by mutableFloatStateOf(prefs.getFloat("${key}_tint_a", DEFAULT_TINT_OPACITY))
        private set

    val tintColor: Color? get() = parseHexColor(tintHex)

    fun changeBrightness(v: Float) {
        brightness = v
        prefs.edit().putFloat("${key}_bright", v).apply()
    }

    fun changeVignette(v: Float) {
        vignette = v
        prefs.edit().putFloat("${key}_vig", v).apply()
    }

    fun setTint(hex: String) {
        tintHex = hex
        prefs.edit().putString("${key}_tint", hex).apply()
    }

    fun changeTintOpacity(v: Float) {
        tintOpacity = v
        prefs.edit().putFloat("${key}_tint_a", v).apply()
    }

    fun resetEdits() {
        changeBrightness(DEFAULT_BRIGHTNESS)
        changeVignette(DEFAULT_VIGNETTE)
        setTint("")
        changeTintOpacity(DEFAULT_TINT_OPACITY)
    }

    suspend fun import(context: Context, uri: Uri): Boolean {
        val ok = withContext(Dispatchers.IO) { importScaledJpeg(context, uri, file, 2400) }
        if (ok) {
            version++
            hasImage = true
            prefs.edit().putInt("${key}_v", version).apply()
        }
        return ok
    }

    fun clear() {
        file.delete()
        hasImage = false
        version++
        prefs.edit().putInt("${key}_v", version).apply()
    }

    companion object {
        const val DEFAULT_BRIGHTNESS = 0.75f
        const val DEFAULT_VIGNETTE = 0.35f
        const val DEFAULT_TINT_OPACITY = 0.25f
    }
}

/** Custom wallpapers: one for upright screens (the phone) and one for wide screens (the car). */
class WallpaperStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("cartoon_wallpaper", Context.MODE_PRIVATE)
    val portrait = WallpaperSlot(prefs, "portrait", File(app.filesDir, "wallpaper_portrait.jpg"))
    val landscape = WallpaperSlot(prefs, "landscape", File(app.filesDir, "wallpaper_landscape.jpg"))

    /** The wallpaper to show. If only one photo was chosen it covers both. */
    fun active(landscapeScreen: Boolean): WallpaperSlot? {
        val first = if (landscapeScreen) landscape else portrait
        val second = if (landscapeScreen) portrait else landscape
        return when {
            first.hasImage -> first
            second.hasImage -> second
            else -> null
        }
    }

    /** Makes cards slightly see-through so the photo shows around and behind them. */
    fun colors(base: ColorScheme, landscapeScreen: Boolean): ColorScheme =
        if (active(landscapeScreen) == null) base
        else base.copy(
            surface = base.surface.copy(alpha = 0.80f),
            surfaceVariant = base.surfaceVariant.copy(alpha = 0.88f),
        )
}

/* ------------------------------ Drawing ------------------------------ */

/** The photo with brightness, color tint and vignette applied. */
@Composable
fun WallpaperImage(slot: WallpaperSlot, modifier: Modifier = Modifier) {
    val bitmap by produceState<ImageBitmap?>(null, slot.version, slot.hasImage) {
        value = if (slot.hasImage) withContext(Dispatchers.IO) { loadJpeg(slot.file) } else null
    }
    val image = bitmap ?: return
    val b = slot.brightness
    val filter = remember(b) {
        ColorFilter.colorMatrix(
            ColorMatrix(
                floatArrayOf(
                    b, 0f, 0f, 0f, 0f,
                    0f, b, 0f, 0f, 0f,
                    0f, 0f, b, 0f, 0f,
                    0f, 0f, 0f, 1f, 0f,
                )
            )
        )
    }
    val tint = slot.tintColor
    val vignette = slot.vignette
    Box(modifier.clipToBounds()) {
        Image(
            image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            colorFilter = filter,
            modifier = Modifier.fillMaxSize(),
        )
        if (tint != null && slot.tintOpacity > 0.01f) {
            Box(Modifier.fillMaxSize().background(tint.copy(alpha = slot.tintOpacity)))
        }
        if (vignette > 0.01f) {
            Box(
                Modifier.fillMaxSize().drawBehind {
                    val radius = kotlin.math.hypot(size.width, size.height) / 2f
                    drawRect(
                        Brush.radialGradient(
                            0.45f to Color.Transparent,
                            1f to Color.Black.copy(alpha = vignette),
                            center = size.center,
                            radius = radius,
                        )
                    )
                }
            )
        }
    }
}

/** Full-screen background layer. Does nothing until a photo has been chosen. */
@Composable
fun WallpaperLayer(store: WallpaperStore, landscapeScreen: Boolean, modifier: Modifier = Modifier) {
    val slot = store.active(landscapeScreen) ?: return
    WallpaperImage(slot, modifier)
}

/* ------------------------------ Settings ------------------------------ */

private val TINTS = listOf(
    "Midnight" to "0B1F3A",
    "Black" to "000000",
    "Amber" to "FFB547",
    "Teal" to "14B8A6",
    "Rose" to "F43F5E",
    "Violet" to "8B5CF6",
)

@Composable
fun WallpaperSection(store: WallpaperStore) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var landscape by remember { mutableStateOf(ui.car) }
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val slot = if (landscape) store.landscape else store.portrait

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                busy = true
                note = if (slot.import(context, uri)) null else "Couldn't read that photo. Try another one."
                busy = false
            }
        }
    }

    AdvSection("Wallpaper") {
        Text(
            "Use your own photos as the background. The upright photo shows on the phone, the wide photo " +
                "on the car screen. If you only choose one, it's used for both.",
            fontSize = ui.small,
            color = cs.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Pill("Portrait (phone)", selected = !landscape, onClick = { landscape = false; note = null })
            Pill("Landscape (car)", selected = landscape, onClick = { landscape = true; note = null })
        }
        Spacer(Modifier.height(14.dp))

        // Live preview
        Box(
            Modifier
                .height(if (landscape) (if (ui.short) 110.dp else 150.dp) else (if (ui.short) 180.dp else 240.dp))
                .aspectRatio(if (landscape) 16f / 9f else 9f / 16f)
                .clip(RoundedCornerShape(18.dp))
                .background(cs.surfaceVariant)
                .align(Alignment.CenterHorizontally),
            contentAlignment = Alignment.Center,
        ) {
            if (slot.hasImage) WallpaperImage(slot, Modifier.fillMaxSize())
            else Text("No photo yet", fontSize = ui.small, color = cs.onSurfaceVariant)
        }
        Spacer(Modifier.height(12.dp))

        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!ui.car) {
                Pill(
                    if (busy) "Loading…" else if (slot.hasImage) "Change photo" else "Choose photo",
                    selected = !slot.hasImage,
                    onClick = {
                        if (!busy) picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )
            }
            if (slot.hasImage) {
                Pill("Reset edits", color = cs.onSurfaceVariant, onClick = { slot.resetEdits() })
                Pill("Remove", color = cs.onSurfaceVariant, onClick = { slot.clear() })
            }
        }
        if (ui.car && !slot.hasImage) {
            Spacer(Modifier.height(8.dp))
            Text("Choose photos on the phone, then edit them here.", fontSize = ui.small, color = cs.primary)
        }
        note?.let {
            Spacer(Modifier.height(8.dp))
            Text(it, fontSize = ui.small, color = cs.primary)
        }

        if (slot.hasImage) {
            Spacer(Modifier.height(14.dp))
            Text("Brightness", fontSize = ui.small, color = cs.onSurfaceVariant)
            AdvSlider(slot.brightness, 0.2f..1.5f) { slot.changeBrightness(it) }
            Text("Vignette (darker edges)", fontSize = ui.small, color = cs.onSurfaceVariant)
            AdvSlider(slot.vignette, 0f..0.9f) { slot.changeVignette(it) }

            Spacer(Modifier.height(4.dp))
            Text("Color tint", fontSize = ui.small, color = cs.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill("None", selected = slot.tintHex.isEmpty(), onClick = { slot.setTint("") })
                TINTS.forEach { (name, hex) ->
                    val selected = slot.tintHex.equals(hex, ignoreCase = true)
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .size(if (ui.short) 36.dp else 42.dp)
                                .clip(CircleShape)
                                .background(parseHexColor(hex) ?: Color.White)
                                .border(if (selected) 3.dp else 1.dp, if (selected) cs.onSurface else cs.outline, CircleShape)
                                .clickable { slot.setTint(hex) }
                        )
                        Text(name, fontSize = ui.small, color = cs.onSurfaceVariant)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            var hexText by remember(slot.tintHex, landscape) { mutableStateOf(slot.tintHex) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(slot.tintColor ?: cs.surfaceVariant)
                )
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { typed ->
                        hexText = typed.removePrefix("#").filter { it.isLetterOrDigit() }.take(6).uppercase()
                        if (parseHexColor(hexText) != null) slot.setTint(hexText)
                    },
                    label = { Text("Hex code") },
                    prefix = { Text("#") },
                    singleLine = true,
                    isError = hexText.isNotEmpty() && parseHexColor(hexText) == null,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.weight(1f),
                )
            }
            if (slot.tintHex.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Tint strength: ${(slot.tintOpacity * 100).toInt()}%",
                    fontSize = ui.small,
                    fontWeight = FontWeight.SemiBold,
                    color = cs.onSurfaceVariant,
                )
                AdvSlider(slot.tintOpacity, 0f..0.8f) { slot.changeTintOpacity(it) }
            }
        }
    }
}
