package com.example.cartoon

/** How soon a trouble code deserves attention. */
enum class DtcSeverity(val label: String) {
    INFO("Low priority"),
    SOON("Check soon"),
    URGENT("Check right away"),
}

data class DtcInfo(
    val code: String,
    val title: String,
    val system: String,
    val severity: DtcSeverity,
    val advice: String,
    val generic: Boolean,
)

/**
 * Offline descriptions for OBD-II trouble codes, written in plain English.
 * Common generic codes have specific entries; everything else is described from the
 * code's family (first letter and number range). Manufacturer-specific codes (P1xxx etc.)
 * mean different things on different brands, so those get a general description.
 */
object DtcDescriptions {

    fun describe(code: String): DtcInfo {
        val c = code.trim().uppercase()
        val system = when (c.firstOrNull()) {
            'P' -> "Powertrain"
            'C' -> "Chassis"
            'B' -> "Body"
            'U' -> "Network"
            else -> "Unknown"
        }
        val generic = c.getOrNull(1) == '0' || c.getOrNull(1) == '2' || (c.getOrNull(1) == '3' && c[0] != 'P')
        val known = exact(c) ?: pattern(c)
        if (known != null) {
            return DtcInfo(c, known.first, system, known.second, known.third, generic)
        }
        val family = family(c)
        return DtcInfo(
            c,
            family.first,
            system,
            family.second,
            if (generic) "A scan of freeze-frame data or a shop scan tool can narrow this down."
            else "This is a manufacturer-specific code. Its exact meaning depends on the brand; " +
                "look it up for your make and model.",
            generic,
        )
    }

    private val MISFIRE_ADVICE = "If the check-engine light is flashing, ease off and get it looked at soon; " +
        "unburned fuel can overheat the catalytic converter. Common causes: spark plugs, coils, injectors, vacuum leaks."
    private const val SENSOR_ADVICE = "Often a failed sensor, a loose connector or damaged wiring."
    private const val EVAP_ADVICE = "Start with the gas cap: make sure it's tight and the seal isn't cracked. " +
        "It won't affect how the car drives, but it will fail an emissions test."

    private fun exact(c: String): Triple<String, DtcSeverity, String>? = when (c) {
        "P0100" -> t("Air flow (MAF) sensor circuit problem", DtcSeverity.SOON, SENSOR_ADVICE)
        "P0101" -> t("Air flow (MAF) reading out of the expected range", DtcSeverity.SOON,
            "Often a dirty MAF sensor or an air leak after it. Cleaning the sensor with MAF cleaner can fix it.")
        "P0102" -> t("Air flow (MAF) sensor reading too low", DtcSeverity.SOON, SENSOR_ADVICE)
        "P0103" -> t("Air flow (MAF) sensor reading too high", DtcSeverity.SOON, SENSOR_ADVICE)
        "P0106" -> t("Manifold pressure (MAP) reading out of range", DtcSeverity.SOON,
            "Check the MAP sensor's vacuum hose for cracks, then the sensor.")
        "P0107" -> t("Manifold pressure (MAP) sensor reading too low", DtcSeverity.SOON, SENSOR_ADVICE)
        "P0108" -> t("Manifold pressure (MAP) sensor reading too high", DtcSeverity.SOON, SENSOR_ADVICE)
        "P0112" -> t("Intake air temp sensor reading too low", DtcSeverity.INFO, SENSOR_ADVICE)
        "P0113" -> t("Intake air temp sensor reading too high", DtcSeverity.INFO, SENSOR_ADVICE)
        "P0117" -> t("Coolant temp sensor reading too low", DtcSeverity.SOON, SENSOR_ADVICE)
        "P0118" -> t("Coolant temp sensor reading too high", DtcSeverity.SOON,
            "Usually the sensor or its wiring, but confirm the engine isn't actually overheating.")
        "P0121" -> t("Throttle position sensor reading doesn't make sense", DtcSeverity.SOON, SENSOR_ADVICE)
        "P0122" -> t("Throttle position sensor reading too low", DtcSeverity.SOON, SENSOR_ADVICE)
        "P0123" -> t("Throttle position sensor reading too high", DtcSeverity.SOON, SENSOR_ADVICE)
        "P0125" -> t("Engine takes too long to warm up enough for fuel control", DtcSeverity.INFO,
            "Often a thermostat stuck open or a weak coolant temp sensor.")
        "P0128" -> t("Coolant stays below thermostat temperature", DtcSeverity.INFO,
            "Usually a thermostat stuck open. The heater may blow cooler and fuel economy drops.")
        "P0171" -> t("Engine running lean (bank 1)", DtcSeverity.SOON,
            "Too much air or too little fuel. Common causes: vacuum leaks, a dirty MAF sensor, a weak fuel pump or clogged filter.")
        "P0172" -> t("Engine running rich (bank 1)", DtcSeverity.SOON,
            "Too much fuel. Common causes: leaking injectors, high fuel pressure, a bad MAF or O2 sensor.")
        "P0174" -> t("Engine running lean (bank 2)", DtcSeverity.SOON,
            "Too much air or too little fuel. If bank 1 is lean too, suspect a shared cause like a vacuum leak or fuel pressure.")
        "P0175" -> t("Engine running rich (bank 2)", DtcSeverity.SOON,
            "Too much fuel. Common causes: leaking injectors, high fuel pressure, a bad MAF or O2 sensor.")
        "P0300" -> t("Random or multiple cylinder misfire", DtcSeverity.URGENT, MISFIRE_ADVICE)
        "P0325" -> t("Knock sensor circuit problem (bank 1)", DtcSeverity.INFO,
            "The engine may pull timing and feel down on power. Usually the sensor or its wiring.")
        "P0335" -> t("Crankshaft position sensor circuit problem", DtcSeverity.URGENT,
            "The engine can stall or fail to start. Usually the sensor or its connector.")
        "P0340" -> t("Camshaft position sensor circuit problem", DtcSeverity.SOON,
            "Can cause hard starting. Usually the sensor or its connector; timing chain issues are less common.")
        "P0400" -> t("EGR flow problem", DtcSeverity.INFO, "Often carbon buildup in the EGR valve or passages.")
        "P0401" -> t("EGR flow too low", DtcSeverity.INFO, "Often carbon buildup in the EGR valve or passages.")
        "P0402" -> t("EGR flow too high", DtcSeverity.SOON, "The EGR valve may be stuck open, which can cause rough idle.")
        "P0420" -> t("Catalytic converter efficiency below threshold (bank 1)", DtcSeverity.INFO,
            "The car is usually fine to drive, but it will fail an emissions test. Rule out exhaust leaks and O2 sensors before replacing the converter.")
        "P0430" -> t("Catalytic converter efficiency below threshold (bank 2)", DtcSeverity.INFO,
            "The car is usually fine to drive, but it will fail an emissions test. Rule out exhaust leaks and O2 sensors before replacing the converter.")
        "P0440" -> t("Evaporative emissions (EVAP) system problem", DtcSeverity.INFO, EVAP_ADVICE)
        "P0441" -> t("EVAP purge flow incorrect", DtcSeverity.INFO, EVAP_ADVICE)
        "P0442" -> t("Small leak in the EVAP system", DtcSeverity.INFO, EVAP_ADVICE)
        "P0446" -> t("EVAP vent control problem", DtcSeverity.INFO, EVAP_ADVICE)
        "P0455" -> t("Large leak in the EVAP system", DtcSeverity.INFO, EVAP_ADVICE)
        "P0456" -> t("Very small leak in the EVAP system", DtcSeverity.INFO, EVAP_ADVICE)
        "P0457" -> t("EVAP leak: fuel cap loose or missing", DtcSeverity.INFO,
            "Tighten the gas cap until it clicks. The light clears itself after a few drives.")
        "P0500" -> t("Vehicle speed sensor problem", DtcSeverity.SOON,
            "The speedometer and shifting may act up. Usually the sensor or wiring.")
        "P0505" -> t("Idle air control problem", DtcSeverity.SOON, "Idle may hunt or stall. Often a dirty throttle body or idle valve.")
        "P0506" -> t("Idle speed lower than expected", DtcSeverity.INFO, "Often a dirty throttle body or idle control valve.")
        "P0507" -> t("Idle speed higher than expected", DtcSeverity.INFO, "Often a vacuum leak or a dirty throttle body.")
        "P0562" -> t("System voltage low", DtcSeverity.URGENT,
            "Check the battery, alternator and the battery cables. A big audio system can also pull voltage down.")
        "P0563" -> t("System voltage high", DtcSeverity.URGENT, "The alternator may be overcharging, which can damage the battery and electronics.")
        "P0600" -> t("Engine computer communication problem", DtcSeverity.SOON, "Check grounds, connectors and battery voltage first.")
        "P0700" -> t("Transmission computer has a fault", DtcSeverity.SOON,
            "The transmission computer stored its own code. A scan tool that reads the transmission module can show it.")
        "P0715" -> t("Transmission input speed sensor problem", DtcSeverity.SOON, "Shifting may be harsh or erratic. " + SENSOR_ADVICE)
        "P0720" -> t("Transmission output speed sensor problem", DtcSeverity.SOON, "Shifting may be harsh or erratic. " + SENSOR_ADVICE)
        "P0740" -> t("Torque converter clutch circuit problem", DtcSeverity.SOON,
            "Fuel economy may drop and the RPM may stay high on the highway.")
        "U0100" -> t("Lost communication with the engine computer", DtcSeverity.URGENT,
            "Check the battery, fuses, grounds and the data network wiring.")
        "U0101" -> t("Lost communication with the transmission computer", DtcSeverity.SOON,
            "Check fuses, grounds and the data network wiring.")
        "U0121" -> t("Lost communication with the ABS module", DtcSeverity.SOON,
            "ABS and traction control may be off. Check fuses and wiring.")
        "U0140" -> t("Lost communication with the body control module", DtcSeverity.SOON, "Check fuses, grounds and wiring.")
        else -> null
    }

    /** Codes that follow a numbered pattern (cylinders, O2 sensors, injectors, shift solenoids). */
    private fun pattern(c: String): Triple<String, DtcSeverity, String>? {
        if (c.length != 5 || c[0] != 'P') return null
        val n = c.substring(1).toIntOrNull() ?: return null

        // P0301–P0312: misfire in a specific cylinder
        if (n in 301..312) {
            return t("Cylinder ${n - 300} misfire", DtcSeverity.URGENT,
                "Swapping that cylinder's coil or plug with a neighbor and seeing if the misfire moves is a quick check. " + MISFIRE_ADVICE)
        }
        // P0201–P0212: injector circuit for a cylinder
        if (n in 201..212) {
            return t("Fuel injector circuit problem, cylinder ${n - 200}", DtcSeverity.SOON,
                "Check the injector's connector and wiring, then the injector itself.")
        }
        // P0130–P0167: oxygen sensors, six codes per sensor position
        if (n in 130..167) {
            val groups = listOf(130 to "bank 1, sensor 1", 136 to "bank 1, sensor 2", 142 to "bank 1, sensor 3",
                150 to "bank 2, sensor 1", 156 to "bank 2, sensor 2", 162 to "bank 2, sensor 3")
            val g = groups.lastOrNull { n >= it.first && n < it.first + 6 } ?: return null
            val what = when (n - g.first) {
                0 -> "circuit problem"
                1 -> "reading too low"
                2 -> "reading too high"
                3 -> "slow to respond"
                4 -> "no activity"
                else -> "heater circuit problem"
            }
            val where = if (g.second.endsWith("1")) "Upstream (before the catalytic converter)." else
                "Downstream (after the catalytic converter)."
            return t("Oxygen sensor $what (${g.second})", DtcSeverity.SOON, "$where $SENSOR_ADVICE")
        }
        // P0750–P0774: shift solenoids A–E
        if (n in 750..774) {
            val letter = "ABCDE"[(n - 750) / 5]
            return t("Transmission shift solenoid $letter problem", DtcSeverity.SOON,
                "Shifting may be harsh, delayed or stuck in one gear.")
        }
        // P0326–P0334: knock sensors
        if (n in 326..334) return t("Knock sensor problem", DtcSeverity.INFO,
            "The engine may pull timing and feel down on power. Usually the sensor or its wiring.")
        return null
    }

    /** General description from the code's family, used when there's no specific entry. */
    private fun family(c: String): Pair<String, DtcSeverity> {
        val second = c.getOrNull(1)
        if (c.firstOrNull() == 'P' && second == '1') return "Manufacturer-specific engine or transmission code" to DtcSeverity.SOON
        if (c.firstOrNull() == 'P' && second == '3') return "Manufacturer-specific engine code" to DtcSeverity.SOON
        return when (c.firstOrNull()) {
            'P' -> when (c.getOrNull(2)) {
                '0', '1', '2' -> "Fuel and air metering problem"
                '3' -> "Ignition system or misfire problem"
                '4' -> "Emissions control system problem"
                '5' -> "Speed, idle control or input problem"
                '6' -> "Engine computer or output circuit problem"
                '7', '8', '9' -> "Transmission problem"
                'A', 'B', 'C' -> "Hybrid or electric drive problem"
                else -> "Powertrain problem"
            } to DtcSeverity.SOON
            'C' -> "Chassis system problem (brakes, ABS, steering or suspension)" to DtcSeverity.SOON
            'B' -> "Body system problem (airbags, lighting, climate or comfort)" to DtcSeverity.SOON
            'U' -> "Network communication problem between computers" to DtcSeverity.SOON
            else -> "Unknown code" to DtcSeverity.INFO
        }
    }

    private fun t(title: String, severity: DtcSeverity, advice: String) = Triple(title, severity, advice)
}
