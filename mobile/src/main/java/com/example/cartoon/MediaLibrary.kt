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

object MediaLibrary {
    private val videoExt = setOf("mp4", "mkv", "webm", "mov", "m4v", "3gp", "ts")
    private val audioExt = setOf("mp3", "m4a", "aac", "flac", "ogg", "opus", "wav")
    private const val MAX_DEPTH = 3

    /** Scans the folder (and up to 3 levels of subfolders). Call off the main thread. */
    fun scan(context: Context, treeUri: Uri): List<MediaEntry> {
        val root = DocumentFile.fromTreeUri(context, treeUri) ?: return emptyList()
        val out = ArrayList<MediaEntry>()
        walk(root, 0, out)
        return out.sortedWith(compareBy<MediaEntry>({ it.kind }, { it.title.lowercase() }))
    }

    private fun walk(dir: DocumentFile, depth: Int, out: MutableList<MediaEntry>) {
        val files = dir.listFiles()

        // "Song Name.lrc" (preferred) or "Song Name.txt" next to "Song Name.mp3"
        val lyricsByName = HashMap<String, Uri>()
        for (f in files) {
            if (!f.isFile) continue
            val name = f.name ?: continue
            val ext = name.substringAfterLast('.', "").lowercase()
            val base = name.substringBeforeLast('.').lowercase()
            if (ext == "lrc" || (ext == "txt" && base !in lyricsByName)) {
                lyricsByName[base] = f.uri
            }
        }

        for (f in files) {
            val name = f.name ?: continue
            if (f.isDirectory) {
                if (depth < MAX_DEPTH) walk(f, depth + 1, out)
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
            out += MediaEntry(base, f.uri, kind, lyricsByName[base.lowercase()])
        }
    }

    fun readText(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) }
    }.getOrNull()
}
