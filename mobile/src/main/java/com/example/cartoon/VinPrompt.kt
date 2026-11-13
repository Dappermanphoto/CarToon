package com.example.cartoon

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Reacts to the VIN the car reports. A VIN that matches a saved vehicle switches to it; a new VIN
 * asks first, then fills in the year, make, model, engine and so on.
 */
@Composable
fun VinPrompt(obd: ObdManager, profile: ProfileStore) {
    val vin = obd.vin
    var ask by remember { mutableStateOf<Pair<String, VinInfo>?>(null) }
    var note by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(vin) {
        if (vin == null || obd.inDemo || obd.demo) return@LaunchedEffect
        val existing = profile.findByVin(vin)
        if (existing != null) {
            profile.select(existing)
            val blank = listOf(ProfileField.YEAR, ProfileField.MAKE, ProfileField.MODEL, ProfileField.ENGINE).any { profile[it].isBlank() }
            if (blank) profile.fillFrom(vin, Vin.decode(vin))
            obd.applyProfile()
            note = "Switched to ${profile.vehicleName().ifBlank { "your saved vehicle" }}."
        } else {
            ask = vin to Vin.decode(vin)
        }
    }

    ask?.let { (v, info) ->
        val summary = info.summary().ifBlank { "an unrecognized vehicle" }
        val canAttach = profile[ProfileField.VIN].isBlank() && profile.hasVehicle
        AlertDialog(
            onDismissRequest = { ask = null },
            title = { Text("New vehicle detected") },
            text = {
                Column {
                    Text("The car reports VIN $v: $summary.")
                    Text("Create a profile for it?")
                    if (info.engine.isNotBlank()) Text("Engine: ${info.engine}")
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    if (profile.createForVin(v, info)) obd.applyProfile()
                    ask = null
                }) { Text("Create profile") }
            },
            dismissButton = {
                Column {
                    if (canAttach) {
                        TextButton(onClick = {
                            profile.fillFrom(v, info)
                            obd.applyProfile()
                            ask = null
                        }) { Text("Add to ${profile.vehicleName()}") }
                    }
                    TextButton(onClick = { ask = null }) { Text("Not now") }
                }
            },
        )
    }

    note?.let {
        AlertDialog(
            onDismissRequest = { note = null },
            confirmButton = { TextButton(onClick = { note = null }) { Text("OK") } },
            text = { Text(it) },
        )
    }
}
