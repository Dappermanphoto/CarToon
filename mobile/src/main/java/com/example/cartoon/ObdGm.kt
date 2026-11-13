package com.example.cartoon

/**
 * GM "Mode 22" enhanced sensors for the 2001 Chevrolet S-10 (0411 PCM). Keys are "G" plus the
 * four-digit PID, for example "G1940". They are asked for with the PCM's own header and the
 * "send once" byte, for example 22 19 40 01. Not every truck answers every one: a negative
 * response (7F 22 ..) hides that sensor for the rest of the session.
 */
object GmPids {
    const val PRESET_S10 = "S10_2001"

    /** Header for functional requests (Mode 01 and 09), and for GM's physical Mode 22 requests. */
    const val HEADER_FUNCTIONAL = "686AF1"
    const val HEADER_PCM = "6C10F1"

    private fun IntArray.a(): Float? = getOrNull(0)?.toFloat()
    private fun IntArray.ab(): Float? = if (size >= 2) (this[0] * 256 + this[1]).toFloat() else null

    private fun pid(
        key: String, name: String, unit: String, min: Float, max: Float, decimals: Int = 0,
        usUnit: String = unit, usMin: Float = min, usMax: Float = max, toUs: (Float) -> Float = { it },
        decode: (IntArray) -> Float?,
    ) = ObdPid(key, name, unit, min, max, decimals, usUnit, usMin, usMax, toUs, decode)

    val all: List<ObdPid> = buildList {
        add(pid("G1940", "Trans fluid temp", "°C", 0f, 150f, usUnit = "°F", usMin = 32f, usMax = 300f,
            toUs = { it * 1.8f + 32f }) { b -> b.a()?.minus(40f) })
        add(pid("G115C", "Oil pressure", "kPa", 0f, 700f, usUnit = "psi", usMax = 100f,
            toUs = { it * 0.145f }) { b -> b.a()?.times(4f) })
        add(pid("G11A6", "Knock retard", "°", 0f, 20f, decimals = 1) { b -> b.a()?.times(45f)?.div(256f) })
        for (cyl in 1..6) {
            add(pid("G12%02X".format(cyl), "Misfire cyl $cyl", "", 0f, 100f) { b -> b.a() })
        }
        for (cyl in 0..5) {
            add(pid("G11%02X".format(0xF8 + cyl), "Misfire hist cyl ${cyl + 1}", "", 0f, 500f) { b -> b.ab() })
        }
        add(pid("G12C5", "Fuel level (PCM)", "%", 0f, 100f) { b -> b.a()?.times(100f)?.div(255f) })
        add(pid("G1141", "Injector pulse B1", "ms", 0f, 20f, decimals = 2) { b -> b.ab()?.times(0.01526f) })
        add(pid("G1142", "Injector pulse B2", "ms", 0f, 20f, decimals = 2) { b -> b.ab()?.times(0.01526f) })
        add(pid("G199A", "Commanded gear", "", 1f, 4f) { b -> b.a() })
        add(pid("G1991", "TCC slip speed", "rpm", -500f, 500f) { b ->
            if (b.size >= 2) ((b[0] shl 8) or b[1]).toShort().toInt() * 0.125f else null
        })
        add(pid("G1144", "A/C pressure", "psi", 0f, 400f) { b -> b.a()?.times(1.7f) })
    }

    /** Sensors that go in the PID list for the S-10. */
    val keys: Set<String> = all.map { it.key }.toSet()

    fun isGm(key: String) = key.length == 5 && key[0] == 'G'

    /** The Mode 22 request for a key: "G1940" becomes "22194001". */
    fun request(key: String) = "22" + key.substring(1) + "01"
}
