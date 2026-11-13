package com.example.cartoon

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Provided at the top of the app so any screen can use the saved vehicle (for example web lookups). */
val LocalVehicle = staticCompositionLocalOf<ProfileStore?> { null }

enum class ProfileField(val label: String, val example: String, val maxLength: Int, val numeric: Boolean = false, val hidden: Boolean = false) {
    NAME("Your name", "Name", 30),
    YEAR("Year", "2001", 4, numeric = true),
    MAKE("Make", "Chevrolet", 24),
    MODEL("Model", "S10", 24),
    TRIM("Sub-model / trim", "LS", 24),
    ENGINE("Engine", "4.3L V6", 30),
    VIN("VIN (17 characters)", "1GCCS14W018123456", 17),
    FUEL("Fuel", "Gasoline", 20),
    DRIVE("Drive", "RWD", 20),
    TRANSMISSION("Transmission", "4-speed automatic", 30),
    /** Which built-in vehicle profile applies (such as the 2001 S-10). Not shown as a field. */
    PRESET("Preset", "", 20, hidden = true),
}

/** The driver and their vehicles, saved on the phone. Several vehicles can be saved; one is active. */
class ProfileStore(context: Context) {
    private val app = context.applicationContext
    private val prefs = app.getSharedPreferences("cartoon_profile", Context.MODE_PRIVATE)

    /** Ids of saved vehicles, in order. The first one keeps the original (unprefixed) preference keys. */
    var vehicleIds by mutableStateOf(loadIds())
        private set
    var activeId by mutableStateOf(prefs.getString("active", Vehicles.FIRST)?.takeIf { it in vehicleIds } ?: vehicleIds.first())
        private set

    private fun key(id: String, name: String) = if (id == Vehicles.FIRST) name else "${id}_$name"
    private fun loadIds(): List<String> = runCatching {
        val arr = org.json.JSONArray(prefs.getString("vehicles", null) ?: "[\"${Vehicles.FIRST}\"]")
        (0 until arr.length()).map { arr.getString(it) }.ifEmpty { listOf(Vehicles.FIRST) }
    }.getOrDefault(listOf(Vehicles.FIRST))

    private fun fileFor(id: String) =
        File(app.filesDir, if (id == Vehicles.FIRST) "vehicle_photo.jpg" else "vehicle_photo_$id.jpg")

    val photoFile: File get() = fileFor(activeId)

    private val fields = ProfileField.entries.associateWith {
        mutableStateOf(prefs.getString(key(activeId, it.name), "").orEmpty())
    }
    var photoVersion by mutableIntStateOf(prefs.getInt("photo_v", 0))
        private set
    var hasPhoto by mutableStateOf(photoFile.exists())
        private set

    operator fun get(field: ProfileField): String = fields.getValue(field).value

    operator fun set(field: ProfileField, value: String) {
        val clean = value.take(field.maxLength).let {
            when {
                field.numeric -> it.filter(Char::isDigit)
                field == ProfileField.VIN -> it.uppercase().filter { c -> c.isLetterOrDigit() }
                else -> it
            }
        }
        fields.getValue(field).value = clean
        prefs.edit().putString(key(activeId, field.name), clean.trim()).apply()
    }

    /** "2001 Chevrolet S10 LS" */
    fun vehicleName(): String =
        listOf(ProfileField.YEAR, ProfileField.MAKE, ProfileField.MODEL, ProfileField.TRIM)
            .map { this[it].trim() }.filter { it.isNotEmpty() }.joinToString(" ")

    val hasVehicle: Boolean get() = vehicleName().isNotEmpty()

    /** Short name for the vehicle picker. */
    fun labelFor(id: String): String {
        val name = listOf(ProfileField.YEAR, ProfileField.MAKE, ProfileField.MODEL)
            .map { prefs.getString(key(id, it.name), "").orEmpty().trim() }
            .filter { it.isNotEmpty() }.joinToString(" ")
        return name.ifEmpty { "Vehicle ${vehicleIds.indexOf(id) + 1}" }
    }

    private fun reloadFields() {
        ProfileField.entries.forEach { fields.getValue(it).value = prefs.getString(key(activeId, it.name), "").orEmpty() }
        hasPhoto = photoFile.exists()
        photoVersion++
        prefs.edit().putInt("photo_v", photoVersion).apply()
    }

    /** Switches to a saved vehicle; returns true when the active vehicle changed. */
    fun select(id: String) {
        if (id !in vehicleIds || id == activeId) return
        activeId = id
        prefs.edit().putString("active", id).apply()
        reloadFields()
    }

    fun addVehicle(): String? {
        if (vehicleIds.size >= 6) return null
        val id = "v" + (System.currentTimeMillis() % 1_000_000_000L)
        vehicleIds = vehicleIds + id
        saveIds()
        select(id)
        return id
    }

    /** Adds a vehicle pre-filled as the 2001 Chevrolet S-10 LS (4.3L V6, 0411 PCM). */
    fun addS10Template() {
        if (addVehicle() == null) return
        this[ProfileField.YEAR] = "2001"
        this[ProfileField.MAKE] = "Chevrolet"
        this[ProfileField.MODEL] = "S-10"
        this[ProfileField.TRIM] = "LS"
        this[ProfileField.ENGINE] = "4.3L V6"
        this[ProfileField.FUEL] = "Gasoline"
        this[ProfileField.DRIVE] = "RWD"
        this[ProfileField.TRANSMISSION] = "4-speed automatic"
        this[ProfileField.PRESET] = GmPids.PRESET_S10
    }

    /** The saved vehicle that has this VIN, if any. */
    fun findByVin(vin: String): String? =
        vehicleIds.firstOrNull { prefs.getString(key(it, ProfileField.VIN.name), "").orEmpty().equals(vin, true) }

    /** Fills in the VIN and any blank detail of the active vehicle. Existing entries are kept. */
    fun fillFrom(vin: String, info: VinInfo) {
        this[ProfileField.VIN] = vin
        fun blank(f: ProfileField, v: String) { if (this[f].isBlank() && v.isNotBlank()) this[f] = v }
        blank(ProfileField.YEAR, info.year)
        blank(ProfileField.MAKE, info.make)
        blank(ProfileField.MODEL, info.model)
        blank(ProfileField.TRIM, info.trim)
        blank(ProfileField.ENGINE, info.engine)
        blank(ProfileField.FUEL, info.fuel)
        blank(ProfileField.DRIVE, info.drive)
        blank(ProfileField.TRANSMISSION, info.transmission)
        if (Vin.isS10Preset(info) && this[ProfileField.PRESET].isBlank()) this[ProfileField.PRESET] = GmPids.PRESET_S10
    }

    /** Creates a vehicle for a VIN the app hasn't seen and makes it the active one. */
    fun createForVin(vin: String, info: VinInfo): Boolean {
        if (addVehicle() == null) return false
        fillFrom(vin, info)
        return true
    }

    /** True if the active vehicle uses the S-10 profile. */
    val isS10: Boolean get() = this[ProfileField.PRESET] == GmPids.PRESET_S10

    /** Removes the active vehicle (never the last one) and its photo. */
    fun removeActive() {
        if (vehicleIds.size <= 1) return
        val gone = activeId
        val next = vehicleIds.first { it != gone }
        ProfileField.entries.forEach { prefs.edit().remove(key(gone, it.name)).apply() }
        fileFor(gone).delete()
        vehicleIds = vehicleIds - gone
        saveIds()
        activeId = next
        prefs.edit().putString("active", next).apply()
        reloadFields()
    }

    private fun saveIds() {
        val arr = org.json.JSONArray()
        vehicleIds.forEach { arr.put(it) }
        prefs.edit().putString("vehicles", arr.toString()).apply()
    }

    suspend fun importPhoto(context: Context, uri: Uri): Boolean {
        val ok = withContext(Dispatchers.IO) { importScaledJpeg(context, uri, photoFile, 1600) }
        if (ok) {
            photoVersion++
            hasPhoto = true
            prefs.edit().putInt("photo_v", photoVersion).apply()
        }
        return ok
    }

    fun clearPhoto() {
        photoFile.delete()
        hasPhoto = false
        photoVersion++
        prefs.edit().putInt("photo_v", photoVersion).apply()
    }
}

/** Material "person" icon (Apache 2.0). */
val PersonIcon: ImageVector by lazy {
    ImageVector.Builder(
        defaultWidth = 24.dp,
        defaultHeight = 24.dp,
        viewportWidth = 24f,
        viewportHeight = 24f,
    ).addPath(
        pathData = addPathNodes(
            "M12,12c2.21,0 4,-1.79 4,-4s-1.79,-4 -4,-4 -4,1.79 -4,4 1.79,4 4,4zM12,14c-2.67,0 -8,1.34 -8,4v2h16v-2c0,-2.66 -5.33,-4 -8,-4z"
        ),
        fill = SolidColor(Color.Black),
    ).build()
}

/* ------------------------------ Screen ------------------------------ */

@Composable
fun ProfileScreen(store: ProfileStore, metric: Boolean = false, demo: Boolean = false) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // On the car screen there's no phone keyboard, so fields open the on-screen keyboard.
    var editing by remember { mutableStateOf<ProfileField?>(null) }
    var photoNote by remember { mutableStateOf<String?>(null) }
    var confirmRemove by remember { mutableStateOf(false) }
    var decoding by remember { mutableStateOf(false) }
    var vinNote by remember { mutableStateOf<String?>(null) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) {
            scope.launch {
                photoNote = if (store.importPhoto(context, uri)) null else "Couldn't read that photo."
            }
        }
    }
    val pickPhoto = {
        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
    }

    val field = editing
    if (field != null) {
        KeyboardEditor(
            field = field,
            value = store[field],
            onChange = { store[field] = it },
            onDone = { editing = null },
        )
        return
    }

    val photoCard: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            VehiclePhotoCard(store, onTap = if (ui.car) null else pickPhoto)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (!ui.car) Pill(if (store.hasPhoto) "Change photo" else "Add photo", selected = !store.hasPhoto, onClick = pickPhoto)
                if (store.hasPhoto) Pill("Remove", color = cs.onSurfaceVariant, onClick = { store.clearPhoto() })
            }
            if (ui.car && !store.hasPhoto) {
                Text("Add the photo on the phone.", fontSize = ui.small, color = cs.onSurfaceVariant)
            }
            photoNote?.let { Text(it, fontSize = ui.small, color = cs.primary) }
        }
    }

    val form: @Composable (Modifier) -> Unit = { m ->
        Column(m) {
            ProfileInput(store, ProfileField.NAME, ui.car) { editing = it }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProfileInput(store, ProfileField.YEAR, ui.car, Modifier.weight(0.7f)) { editing = it }
                ProfileInput(store, ProfileField.MAKE, ui.car, Modifier.weight(1.3f)) { editing = it }
            }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProfileInput(store, ProfileField.MODEL, ui.car, Modifier.weight(1f)) { editing = it }
                ProfileInput(store, ProfileField.TRIM, ui.car, Modifier.weight(1f)) { editing = it }
            }
            Spacer(Modifier.height(10.dp))
            ProfileInput(store, ProfileField.ENGINE, ui.car) { editing = it }
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ProfileInput(store, ProfileField.FUEL, ui.car, Modifier.weight(1f)) { editing = it }
                ProfileInput(store, ProfileField.DRIVE, ui.car, Modifier.weight(1f)) { editing = it }
            }
            Spacer(Modifier.height(10.dp))
            ProfileInput(store, ProfileField.TRANSMISSION, ui.car) { editing = it }
            Spacer(Modifier.height(10.dp))
            ProfileInput(store, ProfileField.VIN, ui.car) { editing = it }
            val vinText = store[ProfileField.VIN]
            if (vinText.length == 17 && !ui.car) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Pill(if (decoding) "Decoding…" else "Decode VIN", selected = true, onClick = {
                        if (!decoding) {
                            decoding = true
                            scope.launch {
                                val info = Vin.decode(vinText)
                                store.fillFrom(vinText, info)
                                vinNote = if (info.summary().isBlank()) "Couldn't decode that VIN." else "Filled in: ${info.summary()}"
                                decoding = false
                            }
                        }
                    })
                    vinNote?.let { Text(it, fontSize = ui.small, color = cs.primary) }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Saved on this phone only. Used for code lookups in the OBD Codes tab. Connect the OBD adapter and CarToon reads the VIN for you.",
                fontSize = ui.small,
                color = cs.onSurfaceVariant,
            )
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = ui.pad, vertical = 12.dp)
    ) {
        Text("Profile", fontSize = ui.headline, fontWeight = FontWeight.Black)
        Spacer(Modifier.height(10.dp))
        // Vehicle picker: one chip per saved vehicle
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            store.vehicleIds.forEach { id ->
                Pill(store.labelFor(id), selected = id == store.activeId, onClick = { store.select(id) })
            }
            if (store.vehicleIds.size < 6) {
                Pill("+ Add vehicle", color = cs.onSurfaceVariant, onClick = { store.addVehicle() })
                Pill("+ 2001 Chevy S-10 LS", color = cs.onSurfaceVariant, onClick = { store.addS10Template() })
            }
            if (store.vehicleIds.size > 1) {
                if (confirmRemove) {
                    Pill("Remove this vehicle?", selected = true, onClick = { store.removeActive(); confirmRemove = false })
                    Pill("Cancel", color = cs.onSurfaceVariant, onClick = { confirmRemove = false })
                } else {
                    Pill("Remove", color = cs.onSurfaceVariant, onClick = { confirmRemove = true })
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        if (ui.wide) {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                photoCard(Modifier.weight(0.9f))
                form(Modifier.weight(1.1f))
            }
        } else {
            photoCard(Modifier.fillMaxWidth())
            Spacer(Modifier.height(18.dp))
            form(Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(24.dp))
        MaintenanceSection(store, metric, demo)
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun VehiclePhotoCard(store: ProfileStore, onTap: (() -> Unit)?) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val photo by produceState<ImageBitmap?>(null, store.photoVersion, store.hasPhoto) {
        value = if (store.hasPhoto) withContext(Dispatchers.IO) { loadJpeg(store.photoFile) } else null
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(if (ui.short) 2f else 1.5f)
            .clip(RoundedCornerShape(28.dp))
            .background(cs.surface)
            .then(if (onTap != null) Modifier.clickable(onClick = onTap) else Modifier),
        contentAlignment = Alignment.Center,
    ) {
        val image = photo
        if (image != null) {
            Image(image, contentDescription = "Your vehicle", contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(PersonIcon, null, tint = cs.onSurfaceVariant, modifier = Modifier.size(if (ui.short) 36.dp else 48.dp))
                Spacer(Modifier.height(6.dp))
                Text(
                    if (onTap != null) "Tap to add a photo of your vehicle" else "No vehicle photo",
                    fontSize = ui.small,
                    color = cs.onSurfaceVariant,
                )
            }
        }
        // Name and vehicle across the bottom
        val name = store[ProfileField.NAME].trim()
        val vehicle = store.vehicleName()
        val engine = store[ProfileField.ENGINE].trim()
        if (name.isNotEmpty() || vehicle.isNotEmpty()) {
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f))))
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                if (vehicle.isNotEmpty()) {
                    Text(
                        vehicle,
                        fontSize = ui.title,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                val sub = listOf(engine, if (name.isNotEmpty()) name else "").filter { it.isNotEmpty() }.joinToString("  ·  ")
                if (sub.isNotEmpty()) {
                    Text(sub, fontSize = ui.small, color = Color.White.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ProfileInput(
    store: ProfileStore,
    field: ProfileField,
    car: Boolean,
    modifier: Modifier = Modifier.fillMaxWidth(),
    onEdit: (ProfileField) -> Unit,
) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    if (car) {
        Column(
            modifier
                .clip(RoundedCornerShape(18.dp))
                .background(cs.surface)
                .clickable { onEdit(field) }
                .padding(horizontal = 16.dp, vertical = if (ui.short) 8.dp else 12.dp)
        ) {
            Text(field.label, fontSize = ui.small, color = cs.onSurfaceVariant)
            Text(
                store[field].ifBlank { "Tap to enter" },
                fontSize = ui.body,
                fontWeight = FontWeight.SemiBold,
                color = if (store[field].isBlank()) cs.onSurfaceVariant else cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    } else {
        OutlinedTextField(
            value = store[field],
            onValueChange = { store[field] = it },
            label = { Text(field.label) },
            placeholder = { Text(field.example, color = cs.onSurfaceVariant.copy(alpha = 0.6f)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (field.numeric) KeyboardType.Number else KeyboardType.Text,
                capitalization = if (field.numeric) KeyboardCapitalization.None else KeyboardCapitalization.Words,
            ),
            shape = RoundedCornerShape(16.dp),
            modifier = modifier,
        )
    }
}

/** Car-screen text entry for one profile field. */
@Composable
private fun KeyboardEditor(field: ProfileField, value: String, onChange: (String) -> Unit, onDone: () -> Unit) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    Column(Modifier.fillMaxSize().padding(horizontal = ui.pad, vertical = 8.dp)) {
        Text(field.label, fontSize = ui.title, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(cs.surface)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Text(
                value.ifEmpty { field.example },
                fontSize = ui.title,
                color = if (value.isEmpty()) cs.onSurfaceVariant.copy(alpha = 0.6f) else cs.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
        OnScreenKeyboard(
            onKey = { k ->
                // Capital letter at the start of each word (not for the year)
                val capital = !field.numeric && (value.isEmpty() || value.endsWith(" "))
                onChange(value + if (capital) k.uppercase() else k)
            },
            onBackspace = { onChange(value.dropLast(1)) },
            onClear = { onChange("") },
            onSearch = onDone,
            modifier = Modifier.fillMaxWidth(),
            actionLabel = "Done",
        )
    }
}
