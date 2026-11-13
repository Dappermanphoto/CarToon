package com.example.cartoon

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest

/**
 * Finds synced lyrics online from LRCLIB (lrclib.net), a free community lyrics database.
 * Only the artist, title and length of the song are sent. Results are cached on the phone.
 */
object LyricsWeb {
    private const val BASE = "https://lrclib.net/api"
    private val memory = HashMap<String, Lyrics?>()

    suspend fun fetch(context: Context, artist: String, title: String, durationMs: Long): Lyrics? {
        val cleanTitle = clean(title)
        val cleanArtist = artist.substringBefore(",").substringBefore(" & ").trim()
        if (cleanTitle.isBlank()) return null
        val key = "$cleanArtist|$cleanTitle|${durationMs / 5000}"
        synchronized(memory) { if (memory.containsKey(key)) return memory[key] }

        val result = withContext(Dispatchers.IO) {
            val cache = File(File(context.cacheDir, "lyrics").also { it.mkdirs() }, sha(key) + ".lrc")
            if (cache.exists()) {
                val raw = cache.readText()
                return@withContext if (raw.isBlank()) null else Lyrics.parse(raw)
            }
            val raw = runCatching { lookup(cleanArtist, cleanTitle, durationMs) }.getOrNull()
            // Only remember "not found" when the server really answered
            if (raw != null) cache.writeText(raw)
            raw?.takeIf { it.isNotBlank() }?.let(Lyrics::parse)
        }
        synchronized(memory) { memory[key] = result }
        return result
    }

    /** Returns the lyrics text, "" when LRCLIB has none, or throws on a network error. */
    private fun lookup(artist: String, title: String, durationMs: Long): String {
        val q = "artist_name=${enc(artist)}&track_name=${enc(title)}" +
            if (durationMs > 0) "&duration=${durationMs / 1000}" else ""
        val exact = get("$BASE/get?$q")
        if (exact != null) pick(JSONObject(exact))?.let { return it }
        val list = get("$BASE/search?q=${enc("$artist $title")}") ?: return ""
        val arr = JSONArray(list)
        for (i in 0 until minOf(arr.length(), 5)) {
            pick(arr.getJSONObject(i))?.let { return it }
        }
        return ""
    }

    private fun pick(o: JSONObject): String? {
        val synced = if (o.isNull("syncedLyrics")) "" else o.optString("syncedLyrics")
        if (synced.isNotBlank()) return synced
        val plain = if (o.isNull("plainLyrics")) "" else o.optString("plainLyrics")
        return plain.takeIf { it.isNotBlank() }
    }

    /** null for 404 (nothing found); throws for other errors so they aren't cached. */
    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 8000
            c.readTimeout = 8000
            c.setRequestProperty("User-Agent", "CarToon (https://github.com/Dappermanphoto/CarToon)")
            if (c.responseCode == 404) return null
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** "Song (feat. X) - Remastered 2011" becomes "Song". */
    private fun clean(title: String): String =
        title.replace(Regex("""\s*[(\[].*?[)\]]"""), "")
            .replace(Regex("""\s+-\s+.*$"""), "")
            .trim()

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun sha(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
