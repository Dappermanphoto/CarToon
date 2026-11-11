package com.example.cartoon

/** One line of lyrics. [timeMs] is 0 for unsynced (plain text) lyrics. */
data class LyricLine(val timeMs: Long, val text: String)

/**
 * Lyrics loaded from a .lrc (synced) or .txt (plain) file the user supplies.
 */
class Lyrics(val lines: List<LyricLine>, val synced: Boolean) {

    /** Index of the line that should be highlighted at [positionMs], or -1 before the first line. */
    fun indexAt(positionMs: Long): Int {
        if (!synced || lines.isEmpty()) return -1
        var lo = 0
        var hi = lines.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].timeMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    companion object {
        // [mm:ss], [mm:ss.xx], [mm:ss.xxx], [mm:ss:xx]
        private val TIME = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
        private val OFFSET = Regex("""\[offset:\s*([+-]?\d+)\s*]""", RegexOption.IGNORE_CASE)
        // Word-level stamps from "enhanced" LRC files: <mm:ss.xx>
        private val WORD_STAMP = Regex("""<\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?>""")
        // Header tags like [ar:Artist], [ti:Title]
        private val TAG = Regex("""^\[[a-zA-Z#]+:.*]$""")

        fun parse(raw: String): Lyrics {
            val text = raw.removePrefix("﻿")
            val offset = OFFSET.find(text)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            val out = ArrayList<LyricLine>()

            for (line in text.lines()) {
                val stamps = TIME.findAll(line).toList()
                if (stamps.isEmpty()) continue
                val words = line.substring(stamps.last().range.last + 1)
                    .replace(WORD_STAMP, "")
                    .trim()
                for (m in stamps) {
                    val min = m.groupValues[1].toLong()
                    val sec = m.groupValues[2].toLong()
                    val frac = m.groupValues[3]
                    val fracMs = when (frac.length) {
                        0 -> 0L
                        1 -> frac.toLong() * 100
                        2 -> frac.toLong() * 10
                        else -> frac.take(3).toLong()
                    }
                    // Positive [offset] means lyrics should show earlier.
                    val t = min * 60_000 + sec * 1_000 + fracMs - offset
                    out += LyricLine(t.coerceAtLeast(0L), words)
                }
            }

            if (out.isNotEmpty()) return Lyrics(out.sortedBy { it.timeMs }, synced = true)

            // No timestamps: treat as plain lyrics.
            val plain = text.lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !TAG.matches(it) }
                .map { LyricLine(0L, it) }
            return Lyrics(plain, synced = false)
        }
    }
}
