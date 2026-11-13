package com.example.cartoon

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * A pretend 2001 Chevrolet S-10 LS (4.3 L V6, 0411 PCM) on the other end of the Bluetooth link.
 * It answers like an ELM327 on J1850 VPW with raw hex ("41 0C 1A F8", "62 19 40 7B", ...), and
 * CarToon's real command and parsing code reads it. Mode 22 oil pressure answers "7F 22 12",
 * like the 1-wire oil switch trucks, so the unsupported-sensor handling gets exercised too.
 */
class S10Simulator {
    private val start = SystemClock.elapsedRealtime()
    private var header = "686AF1"
    private var codes = listOf("0401") // P0401, EGR flow
    private var cleared = false

    /** The VIN a demo truck reports. */
    val vin = "1GCCS14W018123456"

    private val mode1 = setOf(0x01, 0x04, 0x05, 0x06, 0x07, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F, 0x11, 0x14, 0x15, 0x20, 0x40, 0x42)
    private val gm = setOf(
        "1940", "11A6", "1201", "1202", "1203", "1204", "1205", "1206", "11F8", "11F9", "11FA", "11FB", "11FC", "11FD",
        "12C5", "1141", "1142", "199A", "1991", "1144",
    )

    private fun t(): Float = (SystemClock.elapsedRealtime() - start) / 1000f

    /** Speed in km/h: idle, pull away, town, highway, slow down, repeat every 80 seconds. */
    private fun speed(): Float {
        val p = t() % 80f
        return when {
            p < 6f -> 0f
            p < 18f -> (p - 6f) / 12f * 55f
            p < 30f -> 55f
            p < 44f -> 55f + (p - 30f) / 14f * 50f
            p < 58f -> 105f
            p < 72f -> 105f - (p - 58f) / 14f * 105f
            else -> 0f
        }
    }
    private fun gear(s: Float) = when { s < 1f -> 1; s < 25f -> 1; s < 50f -> 2; s < 80f -> 3; else -> 4 }
    private fun rpm(s: Float): Float {
        val ratio = when (gear(s)) { 1 -> 115f; 2 -> 65f; 3 -> 40f; else -> 27f }
        return maxOf(720f, s * ratio) + 20f * sin(t() * 3f)
    }
    private fun throttle(s: Float) = 8f + s * 0.25f + 5f * abs(sin(t() * 0.5f))

    private fun hex(vararg b: Int) = b.joinToString(" ") { "%02X".format(it and 0xFF) }
    private fun hex(v: Int, bytes: Int) = hex(*IntArray(bytes) { v shr (8 * (bytes - 1 - it)) })

    private fun maskFor(base: Int): String {
        var m = 0L
        for (bit in 0 until 32) {
            val pid = base + bit + 1
            if (pid in mode1) m = m or (1L shl (31 - bit))
        }
        return hex(m.toInt(), 4)
    }

    /** What the adapter sends back for [cmd], as raw text (lines separated by CR). */
    fun respond(cmd: String): String {
        val c = cmd.uppercase().replace(" ", "")
        val s = speed()
        return when {
            c == "ATZ" -> "ELM327 v1.5"
            c.startsWith("ATSH") -> { header = c.removePrefix("ATSH"); "OK" }
            c == "ATDPN" -> "A2"
            c == "ATRV" -> "%.1fV".format(14.0f + 0.15f * sin(t() * 0.3f))
            c.startsWith("AT") -> "OK"
            c == "0100" || c == "0120" || c == "0140" || c == "0160" -> {
                val base = c.substring(2).toInt(16)
                if (base == 0x60) "NO DATA" else "41 %02X %s".format(base, maskFor(base))
            }
            c == "0101" -> "41 01 ${if (cleared) "00" else "81"} 07 E5 ${if (cleared) "E5" else "05"}"
            c.length == 4 && c.startsWith("01") -> mode1(c.substring(2).toInt(16), s)
            c == "03" -> if (cleared) "NO DATA" else "43 " + codes.joinToString(" ") { it.chunked(2).joinToString(" ") }
            c == "07" -> "NO DATA"
            c == "0A" -> if (cleared) "NO DATA" else "4A 01 04 01"
            c == "020200" -> if (cleared) "NO DATA" else "42 02 00 04 01"
            c.length == 6 && c.startsWith("02") && c.endsWith("00") -> freeze(c.substring(2, 4))
            c == "04" -> { cleared = true; "44" }
            c == "0902" -> vinFrames()
            c.length == 8 && c.startsWith("22") && c.endsWith("01") -> mode22(c.substring(2, 6), s)
            else -> "?"
        }
    }

    /** Mode 02 reply: values from the moment the demo fault "happened". */
    private fun freeze(pid: String): String {
        if (cleared) return "NO DATA"
        val p = "42 $pid 00 "
        return p + when (pid) {
            "0C" -> hex(2150 * 4, 2)
            "0D" -> hex(72)
            "05" -> hex(88 + 40)
            "06" -> hex(130)
            "07" -> hex(133)
            "04" -> hex((46f * 255f / 100f).roundToInt())
            "0B" -> hex(52)
            else -> return "NO DATA"
        }
    }

    private fun mode1(pid: Int, s: Float): String {
        if (pid !in mode1) return "NO DATA"
        val p = "41 %02X ".format(pid)
        return p + when (pid) {
            0x04 -> hex((22f + throttle(s) * 0.6f).times(255f / 100f).roundToInt())
            0x05 -> hex(minOf(88f, 50f + t() * 0.5f).roundToInt() + 40)
            0x06 -> hex((128f + 3f * sin(t() * 1.3f)).roundToInt())
            0x07 -> hex((131f + 2f * sin(t() * 0.1f)).roundToInt())
            0x0B -> hex((30f + throttle(s) * 0.7f).roundToInt())
            0x0C -> hex((rpm(s) * 4f).roundToInt(), 2)
            0x0D -> hex(s.roundToInt())
            0x0E -> hex((128f + 2 * (14f + (rpm(s) - 720f) / 250f)).roundToInt())
            0x0F -> hex(32 + 40)
            0x11 -> hex((throttle(s) * 255f / 100f).roundToInt())
            0x14, 0x15 -> hex((90f + 70f * sin(t() * 4f)).roundToInt(), 1) + " FF"
            0x20 -> maskFor(0x20)
            0x40 -> maskFor(0x40)
            0x42 -> hex(((14.0f + 0.1f * sin(t() * 0.3f)) * 1000f).roundToInt(), 2)
            else -> "00"
        }
    }

    private fun mode22(pid: String, s: Float): String {
        if (pid == "115C") return "7F 22 12" // 1-wire oil switch: no pressure sensor
        if (pid !in gm) return "7F 22 31"
        val head = "62 ${pid.substring(0, 2)} ${pid.substring(2)} "
        return head + when (pid) {
            "1940" -> hex(minOf(95f, 60f + t() * 0.4f).roundToInt() + 40)
            "11A6" -> hex(if (throttle(s) > 25f) (sin(t() * 2f).coerceAtLeast(0f) * 30f).roundToInt() else 0)
            "12C5" -> hex((62f * 255f / 100f).roundToInt())
            "1141", "1142" -> hex(((2.4f + throttle(s) * 0.05f) / 0.01526f).roundToInt(), 2)
            "199A" -> hex(gear(s))
            "1991" -> hex(((if (gear(s) >= 3) 20f * sin(t()) else 400f) / 0.125f).roundToInt(), 2)
            "1144" -> hex((150f / 1.7f).roundToInt())
            else -> if (pid.startsWith("12")) hex(0) else hex(if (pid == "11F9") 3 else 0, 2)
        }
    }

    /** Five numbered frames, the way J1850 VPW sends the 17 characters. */
    private fun vinFrames(): String {
        val bytes = IntArray(3) + vin.map { it.code }
        // 3 padding bytes + 17 characters = 20 bytes = 5 frames of 4
        return bytes.toList().chunked(4).mapIndexed { i, f -> "49 02 %02X ".format(i + 1) + hex(*f.toIntArray()) }
            .joinToString("\r")
    }
}
