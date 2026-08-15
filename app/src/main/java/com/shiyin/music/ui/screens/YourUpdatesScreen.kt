package com.shiyin.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiyin.music.MainViewModel
import com.shiyin.music.data.db.NewAlbumEntity
import com.shiyin.music.ui.components.OIcon
import com.shiyin.music.ui.components.body
import com.shiyin.music.ui.components.heading
import com.shiyin.music.ui.icons.Lucide
import com.shiyin.music.ui.theme.LocalOrganic
import com.shiyin.music.ui.components.CoverArt

/**
 * v4: 你的更新 — one-shot unread reminders of newly-scanned albums.
 * Shows albums that were detected as brand-new during the last scan.
 * Tapping an album opens its detail page, which automatically marks it
 * as "viewed" (removes it from this list via the new_album table's
 * delete-on-view lifecycle). No history, no archiving — exactly a
 * "what's new since last time" feed.
 */
@Composable
fun YourUpdatesScreen(vm: MainViewModel) {
    val c = LocalOrganic.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val dao = remember { com.shiyin.music.data.db.AppDatabase.get(context).dao() }
    val newAlbums by dao.newAlbumsFlow().collectAsState(emptyList())
    val lib = vm.lib().associateBy { it.id }
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    Column(Modifier.fillMaxSize().background(c.bg)) {
        // header
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackButton { vm.updatesOpen = false }
            Text("你的更新", style = heading(22), modifier = Modifier.weight(1f))
            // v5.2 Bug2: "清空历史" — one-tap to wipe new_album residuals left
            // over from earlier-version scans (e.g. v4.3 → v5.1 upgrade users
            // whose onboarded flag pre-existed and so didn't benefit from the
            // v5.1 first-scan gate). Confirmation prompt inside the click.
            if (newAlbums.isNotEmpty()) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable {
                            vm.clearAlbumUpdatesHistory()
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("清空历史", style = body(12.5f, FontWeight.Bold, c.a700))
                }
            }
        }

        if (newAlbums.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OIcon(Lucide.Info, 32.dp, c.n300)
                    Text("暂无新内容", style = body(14f, FontWeight.Normal, c.n400))
                    Text("扫描文件夹后，新识别到的专辑会自动出现在这里", style = body(12f, FontWeight.Normal, c.n300))
                }
            }
            return
        }

        // Group by scan date (firstSeenAt)
        val byDate = newAlbums.groupBy { na ->
            val cal = java.util.Calendar.getInstance().apply { timeInMillis = na.firstSeenAt }
            "%d-%02d-%02d".format(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for ((date, albums) in byDate) {
                item {
                    val dateLabel = try {
                        val iso = java.time.LocalDate.parse(date)
                        val today = java.time.LocalDate.now()
                        when {
                            iso == today -> "今天"
                            iso == today.minusDays(1) -> "昨天"
                            else -> "%d月%d日".format(iso.monthValue, iso.dayOfMonth)
                        }
                    } catch (_: Exception) { date }
                    Text(dateLabel, style = body(12f, FontWeight.ExtraBold, c.n600).copy(letterSpacing = 0.5.sp),
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
                }
                items(albums, key = { it.albumId }) { na ->
                    AlbumUpdateRow(vm, na, dao)
                }
            }
        }
    }
}

@Composable
private fun AlbumUpdateRow(vm: MainViewModel, na: NewAlbumEntity, dao: com.shiyin.music.data.db.ShiyinDao) {
    val c = LocalOrganic.current
    // Find a track from this album to show the cover
    val track = vm.lib().firstOrNull { it.albumId == na.albumId }
    val albumName = track?.album ?: "未知专辑"
    val artistName = track?.artist ?: "未知艺人"

    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable {
                vm.updatesOpen = false
                if (track != null) {
                    vm.openAlbum(com.shiyin.music.data.albumKeyOf(track.album, track.artist, track.albumId))
                    // openAlbum already calls dao.markAlbumViewed
                }
            }
            .padding(horizontal = 4.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (track != null) {
            CoverArt(track, 52.dp)
        } else {
            Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(c.n200), contentAlignment = Alignment.Center) {
                OIcon(Lucide.Info, 22.dp, c.n400)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(albumName, style = body(14f, FontWeight.SemiBold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(artistName, style = body(12f, FontWeight.Normal, c.n500), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        OIcon(Lucide.ChevronRight, 16.dp, c.n300)
    }
}