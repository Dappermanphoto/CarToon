package com.example.cartoon

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile

enum class MediaKind { VIDEO, AUDIO }

/** A playable file found in the user's chosen folder, plus a matching lyrics file if there is one. */
data class MediaEntry(
    val title: String,
    val uri: Uri,
    val kind: MediaKind,
    val lyricsUri: Uri?,
)

/** A .lrc / .txt lyrics file, with the [ti:] / [ar:] tags from inside it if it has them. */
data class LyricsFile(
    val uri: Uri,
    val baseName: String,
    val tagTitle: String?,
    val tagArtist: String?,
)

data class Library(val media: List<MediaEntry>, val lyrics: List<LyricsFile>) {

    /**
     * Finds the user's lyrics file for a song playing in another app.
     * Matches "Artist - Title.lrc", "Title.lrc", or the [ti:]/[ar:] tags inside the file.
     */
    fun findLyrics(title: String, artist: String): Uri? {
        val t = TitleMatch.normalize(title)
        if (t.isEmpty()) return null
        val a = TitleMatch.normalize(artist)
        var best: LyricsFile? = null
        var bestScore = 0
        for (f in lyrics) {
            val s = TitleMatch.score(f, t, a)
            if (s > bestScore) {
                best = f
                bestScore = s
            }
        }
        return if (bestScore >= 2) best?.uri else null
    }

    companion object {
        val EMPTY = Library(emptyList(), emptyList())
    }
}

object TitleMatch {
    private val BRACKETS = Regex("""\(.*?\)|\[.*?]""")
    private val SUFFIX = Regex("""\s-\s.*\b(remaster(ed)?|version|edit|live|mix|mono|stereo)\b.*$""")
    private val FEAT = Regex("""\b(feat|ft|featuring)\b.*$""")
    private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")

    fun normalize(s: String): String = s.lowercase()
        .replace(BRACKETS, " ")
        .replace(SUFFIX, " ")
        .replace(FEAT, " ")
        .replace(NON_ALNUM, " ")
        .trim()
        .replace(Regex("""\s+"""), " ")

    fun score(f: LyricsFile, title: String, artist: String): Int {
        val name = normalize(f.baseName)
        val tagTitle = f.tagTitle?.let(::normalize)
        var s = when {
            tagTitle != null && tagTitle == title -> 3
            name == title -> 3
            title.length >= 3 && Regex("""(^|\s)${Regex.escape(title)}($|\s)""").containsMatchIn(name) -> 2
            else -> 0
        }
        if (s > 0 && artist.isNotEmpty()) {
            val tagArtist = f.tagArtist?.let(::normalize)
            if ((tagArtist != null && (tagArtist.contains(artist) || artist.contains(tagArtist))) ||
                name.contains(artist)
            ) s += 2
        }
        return s
    }
}

object MediaLibrary {
    private val videoExt = setOf("mp4", "mkv", "webm", "mov", "m4v", "3gp", "ts")
    private val audioExt = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav")
    private const val MAX_DEPTH = 3
    private val TAG_TITLE = Regex("""\[ti:\s*(.*?)\s*]""", RegexOption.IGNORE_CASE)
    private val TAG_ARTIST = Regex("""\[ar:\s*(.*?)\s*]""", RegexOption.IGNORE_CASE)

    /** Scans the folder (and up to 3 levels of subfolders). Call off the main thread. */
    fun scan(context: Context, treeUri: Uri): Library {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return Library.EMPTY
        val media = ArrayList<MediaEntry>()
        val lyrics = ArrayList<LyricsFile>()
        walk(context, root, 0, media, lyrics)
        return Library(
            media.sortedWith(compareBy<MediaEntry>({ it.kind }, { it.title.lowercase() })),
            lyrics,
        )
    }

    private fun walk(
        context: Context,
        dir: DocumentFile,
        depth: Int,
        media: MutableList<MediaEntry>,
        lyrics: MutableList<LyricsFile>,
    ) {
        val files = dir.listFiles()

        // "Song Name.lrc" (preferred) or "Song Name.txt" next to "Song Name.mp3"
        val lyricsByName = HashMap<String, Uri>()
        for (f in files) {
            if (!f.isFile) continue
            val name = f.name ?: continue
            val ext = name.substringAfterLast('.', "").lowercase()
            if (ext != "lrc" && ext != "txt") continue
            val base = name.substringBeforeLast('.')
            val key = base.lowercase()
            if (ext == "lrc" || key !in lyricsByName) lyricsByName[key] = f.uri

            val header = if (ext == "lrc") readHeader(context, f.uri) else ""
            lyrics += LyricsFile(
                uri = f.uri,
                baseName = base,
                tagTitle = TAG_TITLE.find(header)?.groupValues?.get(1)?.takeIf { it.isNotBlank() },
                tagArtist = TAG_ARTIST.find(header)?.groupValues?.get(1)?.takeIf { it.isNotBlank() },
            )
        }

        for (f in files) {
            val name = f.name ?: continue
            if (f.isDirectory) {
                if (depth < MAX_DEPTH) walk(context, f, depth + 1, media, lyrics)
                continue
            }
            val ext = name.substringAfterLast('.', "").lowercase()
            val mime = f.type.orEmpty()
            val kind = when {
                ext in videoExt || mime.startsWith("video/") -> MediaKind.VIDEO
                ext in audioExt || mime.startsWith("audio/") -> MediaKind.AUDIO
                else -> null
            } ?: continue
            val base = name.substringBeforeLast('.')
            media += MediaEntry(base, f.uri, kind, lyricsByName[base.lowercase()])
        }
    }

    private fun readHeader(context: Context, uri: Uri): String = runCatching {
        context.contentResolver.openInputStream(uri)?.use { s ->
            val buf = ByteArray(4096)
            val n = s.read(buf)
            if (n > 0) String(buf, 0, n, Charsets.UTF_8) else ""
        }.orEmpty()
    }.getOrDefault("")

    fun readText(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()
}
