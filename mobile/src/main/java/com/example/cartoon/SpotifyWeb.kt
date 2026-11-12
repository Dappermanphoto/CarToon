package com.example.cartoon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** A playlist, album or song from the user's Spotify library. */
data class WebItem(
    val uri: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val isTrack: Boolean = false,
    /** Search results are grouped by this ("Songs", "Albums", ...). */
    val kind: String = "",
)

/**
 * Spotify Web API, for the parts the phone app can't give us: your own playlists, saved albums
 * and liked songs with their cover art. Uses the access token from "Sign in with Spotify".
 * The token lasts about an hour; after that Spotify asks you to sign in again.
 */
class SpotifyWeb(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("cartoon_spotify_web", Context.MODE_PRIVATE)
    private var token by mutableStateOf(prefs.getString("token", "").orEmpty())
    private var expiresAt by mutableLongStateOf(prefs.getLong("expires", 0L))

    val hasAccess: Boolean get() = token.isNotEmpty() && System.currentTimeMillis() < expiresAt

    fun setToken(value: String, expiresInSeconds: Int) {
        token = value
        expiresAt = System.currentTimeMillis() + (expiresInSeconds - 60).coerceAtLeast(60) * 1000L
        prefs.edit().putString("token", token).putLong("expires", expiresAt).apply()
    }

    fun clear() {
        token = ""
        expiresAt = 0L
        prefs.edit().remove("token").remove("expires").apply()
    }

    suspend fun playlists(): Result<List<WebItem>> = load("/me/playlists?limit=50") { o ->
        WebItem(
            uri = o.str("uri"),
            title = o.str("name"),
            subtitle = o.obj("owner")?.str("display_name").orEmpty().let { if (it.isBlank()) "Playlist" else "By $it" },
            imageUrl = o.imageUrl(),
        )
    }

    suspend fun albums(): Result<List<WebItem>> = load("/me/albums?limit=50") { entry ->
        val o = entry.obj("album") ?: return@load null
        WebItem(o.str("uri"), o.str("name"), o.artists(), o.imageUrl())
    }

    suspend fun liked(): Result<List<WebItem>> = load("/me/tracks?limit=50") { entry ->
        val o = entry.obj("track") ?: return@load null
        WebItem(o.str("uri"), o.str("name"), o.artists(), o.obj("album")?.imageUrl(), isTrack = true)
    }

    /** What plays next (needs an active Spotify player). */
    suspend fun queue(): Result<List<WebItem>> = load("/me/player/queue", "queue") { o ->
        WebItem(o.str("uri"), o.str("name"), o.artists(), o.obj("album")?.imageUrl(), isTrack = true)
    }

    suspend fun search(query: String): Result<List<WebItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val q = java.net.URLEncoder.encode(query.trim(), "UTF-8")
            val root = getJson("/search?q=$q&type=track,album,playlist,artist&limit=10")
            val out = ArrayList<WebItem>()
            fun section(key: String, label: String, parse: (JsonObject) -> WebItem) {
                val arr = root.obj(key)?.get("items")?.takeIf { it.isJsonArray }?.asJsonArray ?: return
                arr.forEach { e ->
                    if (e.isJsonObject) {
                        val item = parse(e.asJsonObject)
                        if (item.uri.isNotBlank()) out.add(item.copy(kind = label))
                    }
                }
            }
            section("tracks", "Songs") { o ->
                WebItem(o.str("uri"), o.str("name"), o.artists(), o.obj("album")?.imageUrl(), isTrack = true)
            }
            section("albums", "Albums") { o -> WebItem(o.str("uri"), o.str("name"), o.artists(), o.imageUrl()) }
            section("playlists", "Playlists") { o ->
                WebItem(o.str("uri"), o.str("name"), o.obj("owner")?.str("display_name").orEmpty(), o.imageUrl())
            }
            section("artists", "Artists") { o -> WebItem(o.str("uri"), o.str("name"), "Artist", o.imageUrl()) }
            out
        }
    }

    private suspend fun load(
        path: String,
        arrayKey: String = "items",
        parse: (JsonObject) -> WebItem?,
    ): Result<List<WebItem>> =
        withContext(Dispatchers.IO) {
            runCatching {
                val items = getJson(path).get(arrayKey)
                if (items == null || !items.isJsonArray) emptyList()
                else items.asJsonArray.mapNotNull { e ->
                    if (e.isJsonObject) parse(e.asJsonObject)?.takeIf { it.uri.isNotBlank() } else null
                }
            }
        }

    private fun getJson(path: String): JsonObject {
        val c = URL("https://api.spotify.com/v1$path").openConnection() as HttpURLConnection
        try {
            c.setRequestProperty("Authorization", "Bearer $token")
            c.connectTimeout = 8000
            c.readTimeout = 10000
            val code = c.responseCode
            if (code == 401) {
                clear()
                error("Your Spotify access ran out. Sign in again.")
            }
            if (code == 403) error("Spotify didn't allow that. Sign in again and approve the permissions, and check your account is added under User Management in the dashboard.")
            if (code !in 200..299) error("Spotify answered with error $code.")
            return JsonParser.parseString(c.inputStream.bufferedReader().readText()).asJsonObject
        } finally {
            c.disconnect()
        }
    }

    private fun JsonObject.str(key: String): String =
        get(key)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()

    private fun JsonObject.obj(key: String): JsonObject? =
        get(key)?.takeIf { it.isJsonObject }?.asJsonObject

    /** Cover art: the middle size when there is one (images come largest first). */
    private fun JsonObject.imageUrl(): String? {
        val arr = get("images")?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
        val pick: JsonElement = arr.getOrNull(1) ?: arr.firstOrNull() ?: return null
        return if (pick.isJsonObject) pick.asJsonObject.str("url").ifBlank { null } else null
    }

    private fun JsonObject.artists(): String {
        val arr = get("artists")?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()
        return arr.mapNotNull { if (it.isJsonObject) it.asJsonObject.str("name").ifBlank { null } else null }
            .joinToString(", ")
    }
}

/** Downloads and remembers cover art from the Web API. */
object WebImages {
    private val cache = LruCache<String, Bitmap>(160)

    suspend fun load(url: String): Bitmap? {
        cache.get(url)?.let { return it }
        val bmp = withContext(Dispatchers.IO) {
            runCatching { URL(url).openStream().use { BitmapFactory.decodeStream(it) } }.getOrNull()
        }
        if (bmp != null) cache.put(url, bmp)
        return bmp
    }
}
