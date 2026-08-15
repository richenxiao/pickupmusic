package com.shiyin.music.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.shiyin.music.MainViewModel
import com.shiyin.music.data.Track
import com.shiyin.music.ui.components.CoverArt
import com.shiyin.music.ui.components.OIcon
import com.shiyin.music.ui.components.PillButton
import com.shiyin.music.ui.components.body
import com.shiyin.music.ui.components.heading
import com.shiyin.music.ui.components.shadowSm
import com.shiyin.music.ui.icons.Lucide
import com.shiyin.music.ui.theme.LocalOrganic
import com.shiyin.music.ui.theme.OrganicColors

private val screenPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 130.dp)

/**
 * Full album list for an artist, with classification (Single/EP/Album) and
 * sort-by-release-date (newest-first / oldest-first).
 *
 * Shown when the user taps "显示全部" on the ArtistDetail preview row.
 */
@Composable
fun ArtistAlbumList(
    vm: MainViewModel,
    artistName: String,
    albums: Map<String, List<Track>>,
    onBack: () -> Unit,
) {
    val c = LocalOrganic.current
    var sortNewestFirst by remember { mutableStateOf(true) }

    // Load release dates from the album_art_cache table
    var releaseDates by remember { mutableStateOf<Map<Long, String>>(emptyMap()) }
    LaunchedEffect(Unit) {
        releaseDates = vm.releaseDates()
    }

    // v9: manual type overrides (album_info_override.type) — Album → 专辑, etc.
    val typeOverrides = remember(vm.albumInfoOverrides) {
        vm.albumInfoOverrides
            .filterValues { it.type.isNotBlank() }
            .mapValues { (_, v) -> v.type }
    }

    // Classify and sort albums
    val classified = remember(albums, releaseDates, sortNewestFirst, typeOverrides) {
        classifyAlbums(albums, releaseDates, sortNewestFirst, typeOverrides)
    }

    // Group by category for section display
    val grouped = remember(classified) {
        classified.groupBy { it.category }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = screenPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header: back + artist name + sort toggle
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BackButton(onBack)
                Text(artistName, style = heading(24), modifier = Modifier.weight(1f))
                Box(Modifier.padding(start = 4.dp)) {
                    PillButton(
                        text = if (sortNewestFirst) "最新优先" else "最早优先",
                        onClick = { sortNewestFirst = !sortNewestFirst },
                        bg = null,
                        textColor = c.text,
                        borderColor = c.divider,
                        icon = Lucide.ArrowUpDown,
                    )
                }
            }
        }

        // Category sections in order: Album → EP → Single
        val categories = listOf(
            AlbumCategory.Album to "专辑",
            AlbumCategory.EP to "EP",
            AlbumCategory.Single to "单曲",
        )

        for ((cat, label) in categories) {
            val items = grouped[cat] ?: continue
            if (items.isEmpty()) continue

            item {
                Text(label, style = body(14f, FontWeight.Bold, c.n700))
            }
            items(items, key = { it.key }) { album ->
                AlbumRow(album, vm, c)
            }
        }
    }
}

@Composable
private fun AlbumRow(
    album: ClassifiedAlbum,
    vm: MainViewModel,
    c: OrganicColors,
) {
    val first = album.firstTrack
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { vm.openAlbum(album.key) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CoverArt(
            first,
            56.dp,
            RoundedCornerShape(6.dp),
            fontSize = 20,
            modifier = Modifier.shadowSm(RoundedCornerShape(6.dp)),
        )
        Column(Modifier.weight(1f)) {
            Text(
                first.album,
                style = body(13.5f, FontWeight.Bold, c.text),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("${album.trackCount} 首", style = body(12f, FontWeight.Normal, c.n600))
                Text("·", style = body(12f, FontWeight.Normal, c.n400))
                Text(categoryLabel(album.category), style = body(12f, FontWeight.Normal, c.n500))
            }
        }
        OIcon(Lucide.ChevronRight, 18.dp, c.n400)
    }
}