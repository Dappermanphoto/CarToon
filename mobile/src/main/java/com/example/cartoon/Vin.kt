package com.example.cartoon

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** What we know about a vehicle from its VIN. Blank means unknown. */
data class VinInfo(
    val year: String = "",
    val make: String = "",
    val model: String = "",
    val trim: String = "",
    val engine: String = "",
    val fuel: String = "",
    val drive: String = "",
    val transmission: String = "",
) {
    fun summary(): String = listOf(year, make, model, trim).filter { it.isNotBlank() }.joinToString(" ")
}

object Vin {
    private val VALID = Regex("[A-HJ-NPR-Z0-9]{17}")

    fun isValid(vin: String) = VALID.matches(vin)

    /**
     * Turns the adapter's answer to "09 02" into a VIN. Handles CAN (several numbered lines, with a
     * length line first) and J1850 / ISO 9141 (frames "49 02 01 .." to "49 02 05 .."). Spaces are
     * already removed. Returns null for NO DATA, a negative response or anything unreadable.
     */
    fun parse(response: String): String? {
        if (response.contains("NODATA") || response.contains("7F09") || response.contains("UNABLE")) return null
        val lines = response.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val payload = StringBuilder()
        val can = lines.filter { it.length > 2 && it[1] == ':' }
        if (can.isNotEmpty()) {
            can.forEach { payload.append(it.substring(2)) }
            val i = payload.indexOf("4902")
            if (i < 0) return null
            payload.delete(0, i + 6) // 49 02 + the count byte
        } else {
            for (l in lines) {
                val i = l.indexOf("4902")
                if (i >= 0 && l.length >= i + 6) payload.append(l.substring(i + 6)) // drop 49 02 + frame number
            }
        }
        val bytes = payload.chunked(2).mapNotNull { it.takeIf { c -> c.length == 2 }?.toIntOrNull(16) }
        val text = bytes.filter { it in 0x30..0x5A }.map { it.toChar() }.joinToString("")
        val vin = if (text.length >= 17) text.takeLast(17) else return null
        return vin.takeIf { isValid(it) }
    }

    /** Model year from the 10th character. The letters repeat every 30 years, so pick the latest sensible one. */
    fun year(vin: String): Int? {
        if (vin.length != 17) return null
        val code = "ABCDEFGHJKLMNPRSTVWXY123456789".indexOf(vin[9])
        if (code < 0) return null
        val thisYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
        var y = 1980 + code
        while (y + 30 <= thisYear + 1) y += 30
        return y
    }

    private val MAKES = mapOf(
        "1GC" to "Chevrolet", "1G1" to "Chevrolet", "2G1" to "Chevrolet", "3GN" to "Chevrolet", "1GN" to "Chevrolet",
        "1GT" to "GMC", "1GK" to "GMC", "1G2" to "Pontiac", "1G3" to "Oldsmobile", "1G4" to "Buick", "1G6" to "Cadillac",
        "1FA" to "Ford", "1FT" to "Ford", "1FM" to "Ford", "1FD" to "Ford", "2FM" to "Ford", "1ZV" to "Ford",
        "1C4" to "Chrysler", "1C3" to "Chrysler", "1B3" to "Dodge", "1D7" to "Dodge", "3D7" to "Dodge", "1J4" to "Jeep",
        "1HG" to "Honda", "2HG" to "Honda", "JHM" to "Honda", "2HK" to "Honda", "5J6" to "Honda",
        "JT2" to "Toyota", "4T1" to "Toyota", "5TD" to "Toyota", "JTD" to "Toyota", "JTE" to "Toyota", "4T3" to "Toyota",
        "1N4" to "Nissan", "JN1" to "Nissan", "3N1" to "Nissan", "5N1" to "Nissan",
        "JM1" to "Mazda", "JF1" to "Subaru", "4S3" to "Subaru", "4S4" to "Subaru",
        "KMH" to "Hyundai", "5NP" to "Hyundai", "KNA" to "Kia", "KND" to "Kia",
        "WVW" to "Volkswagen", "1VW" to "Volkswagen", "WBA" to "BMW", "WDB" to "Mercedes-Benz", "WAU" to "Audi",
        "5YJ" to "Tesla", "7SA" to "Tesla", "YV1" to "Volvo",
    )

    /** Works without internet: only the make and the model year are known. */
    fun localDecode(vin: String): VinInfo {
        val make = MAKES[vin.take(3)].orEmpty()
        return VinInfo(year = year(vin)?.toString().orEmpty(), make = make)
    }

    /** NHTSA's free VIN decoder. Falls back to the local decode when offline or when NHTSA has nothing. */
    suspend fun decode(vin: String): VinInfo = withContext(Dispatchers.IO) {
        val local = localDecode(vin)
        val online = runCatching { decodeOnline(vin) }.getOrNull() ?: return@withContext local
        VinInfo(
            year = online.year.ifBlank { local.year },
            make = online.make.ifBlank { local.make },
            model = online.model,
            trim = online.trim,
            engine = online.engine,
            fuel = online.fuel,
            drive = online.drive,
            transmission = online.transmission,
        )
    }

    private fun decodeOnline(vin: String): VinInfo? {
        val c = URL("https://vpic.nhtsa.dot.gov/api/vehicles/DecodeVINValues/$vin?format=json").openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 8000
            if (c.responseCode !in 200..299) return null
            val root = JSONObject(c.inputStream.bufferedReader().use { it.readText() })
            val r = root.getJSONArray("Results").getJSONObject(0)
            fun s(k: String) = r.optString(k, "").trim().takeIf { it.isNotEmpty() && it != "null" && it != "Not Applicable" }.orEmpty()
            val disp = s("DisplacementL").toDoubleOrNull()?.let { "%.1f".format(java.util.Locale.US, it) }
            val cyl = s("EngineCylinders")
            val engine = listOfNotNull(disp?.let { "${it}L" }, cyl.takeIf { it.isNotEmpty() }?.let { "$it-cyl" }).joinToString(" ")
            val model = s("Model")
            if (model.isEmpty() && s("Make").isEmpty()) return null
            return VinInfo(
                year = s("ModelYear"), make = s("Make").lowercase().replaceFirstChar { it.uppercase() },
                model = model, trim = s("Trim").ifEmpty { s("Series") }, engine = engine,
                fuel = s("FuelTypePrimary"), drive = s("DriveType"), transmission = s("TransmissionStyle"),
            )
        } finally {
            c.disconnect()
        }
    }

    /** True when the decoded details describe the 2001 S-10 with the 4.3 L V6. */
    fun isS10Preset(info: VinInfo): Boolean =
        info.year == "2001" && info.make.contains("Chev", true) &&
            info.model.replace("-", "").contains("S10", true)
}
