package com.example.cartoon

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

@Composable
fun LibraryScreen(
    hasFolder: Boolean,
    loading: Boolean,
    entries: List<MediaEntry>,
    onPickFolder: () -> Unit,
    onRefresh: () -> Unit,
    onPlay: (MediaEntry) -> Unit,
) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    var filter by remember { mutableStateOf<MediaKind?>(null) }
    val shown = if (filter == null) entries else entries.filter { it.kind == filter }

    Column(Modifier.fillMaxSize().padding(horizontal = ui.pad, vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Library", fontSize = ui.headline, fontWeight = FontWeight.Black)
                if (hasFolder && !loading) {
                    val videos = entries.count { it.kind == MediaKind.VIDEO }
                    val songs = entries.size - videos
                    Text(
                        "$videos video${if (videos == 1) "" else "s"} · $songs song${if (songs == 1) "" else "s"}",
                        fontSize = ui.small,
                        color = cs.onSurfaceVariant,
                    )
                }
            }
            if (hasFolder) {
                CircleIconButton(AppIcons.Refresh, "Refresh", ui.iconButton, onRefresh)
                Spacer(Modifier.width(10.dp))
                CircleIconButton(AppIcons.Folder, "Change folder", ui.iconButton, onPickFolder)
            }
        }

        if (hasFolder && entries.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Pill("All", selected = filter == null, onClick = { filter = null })
                Pill("Videos", selected = filter == MediaKind.VIDEO, onClick = { filter = MediaKind.VIDEO })
                Pill("Music", selected = filter == MediaKind.AUDIO, onClick = { filter = MediaKind.AUDIO })
            }
        }
        Spacer(Modifier.height(16.dp))

        when {
            !hasFolder -> EmptyState(
                icon = AppIcons.Folder,
                title = "Pick your media folder",
                body = "Choose a folder on your phone with your videos, music and lyrics. " +
                    "Lyrics are .lrc or .txt files with the same name as the song.",
                modifier = Modifier.fillMaxSize(),
            ) { BigButton("Choose folder", onPickFolder) }

            loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = cs.primary)
            }

            shown.isEmpty() -> EmptyState(
                icon = AppIcons.Library,
                title = "Nothing here yet",
                body = "No videos or music were found in this folder.",
                modifier = Modifier.fillMaxSize(),
            ) { BigButton("Choose another folder", onPickFolder) }

            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = if (ui.short) 220.dp else 260.dp),
                contentPadding = PaddingValues(bottom = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shown, key = { it.uri.toString() }) { entry ->
                    MediaCard(entry) { onPlay(entry) }
                }
            }
        }
    }
}

@Composable
private fun MediaCard(entry: MediaEntry, onClick: () -> Unit) {
    val ui = LocalUi.current
    val cs = MaterialTheme.colorScheme
    val isVideo = entry.kind == MediaKind.VIDEO
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(cs.surface)
            .clickable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtTile(
            art = null,
            fallback = if (isVideo) AppIcons.Movie else AppIcons.Note,
            size = if (ui.short) 50.dp else 62.dp,
            accent = if (isVideo) cs.primary else cs.secondary,
        )
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                fontSize = ui.body,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (isVideo) "Video" else "Song", fontSize = ui.small, color = cs.onSurfaceVariant)
                if (entry.lyricsUri != null) {
                    Text("  ·  ", fontSize = ui.small, color = cs.onSurfaceVariant)
                    Text("Lyrics", fontSize = ui.small, color = cs.primary, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Box(
            Modifier
                .size(if (ui.short) 36.dp else 40.dp)
                .clip(RoundedCornerShape(50))
                .background(cs.surfaceVariant),
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.material3.Icon(
                AppIcons.Play,
                contentDescription = "Play",
                tint = cs.onSurface,
                modifier = Modifier.size(if (ui.short) 18.dp else 20.dp),
            )
        }
    }
}
