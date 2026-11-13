package com.example.cartoon

import android.net.Uri

/**
 * Adapters that only work with their own app (proprietary or encrypted links), so CarToon can't
 * talk to them. Detected by the Bluetooth name; the link goes to the right app on Google Play.
 */
class UnsupportedAdapter(val brand: String, val appName: String, private val appPackage: String) {
    val storeUri: Uri get() = Uri.parse("https://play.google.com/store/apps/details?id=$appPackage")
    val message: String
        get() = "$brand adapters only work with their own app, so CarToon can't use this one."
}

object AdapterBrands {
    private val UNSUPPORTED = listOf(
        Triple("bluedriver", "BlueDriver", UnsupportedAdapter("BlueDriver", "BlueDriver OBD2 Scan Tool", "com.lemurmonitors.bluedriver")),
        Triple("fixd", "FIXD", UnsupportedAdapter("FIXD", "FIXD OBD2 Scanner", "com.fixdapp.two")),
        Triple("carista", "Carista", UnsupportedAdapter("Carista", "Carista OBD2", "com.prizmos.carista")),
    )

    fun detect(bluetoothName: String?): UnsupportedAdapter? {
        val n = bluetoothName?.lowercase().orEmpty()
        if (n.isEmpty()) return null
        return UNSUPPORTED.firstOrNull { n.contains(it.first) }?.third
    }

    /** Bonded Bluetooth Classic devices worth listing as possible OBD adapters. */
    fun looksLikeObd(name: String?): Boolean {
        val n = name?.lowercase().orEmpty()
        return listOf("obd", "elm", "vlink", "v-link", "vgate", "icar", "bafx", "veepeak", "konnwei", "scan", "ios-vlink")
            .any { n.contains(it) }
    }
}
