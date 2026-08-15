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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.unit.sp
import com.shiyin.music.MainViewModel
import com.shiyin.music.data.formatDuration
import com.shiyin.music.ui.components.OIcon
import com.shiyin.music.ui.components.body
import com.shiyin.music.ui.components.heading
import com.shiyin.music.ui.icons.Lucide
import com.shiyin.music.ui.theme.LocalOrganic

/**
 * v4: 最近播放 — Spotify-style "最近播放" page.
 * Shows play events from the last 3 months, newest first, grouped by day.
 * Tracks are displayed as album cards with a play button; clicking navigates
 * to the album detail page.
 */
@Composable
fun RecentPlaysScreen(vm: MainViewModel) {
    val c = LocalOrganic.current
    val context = androidx.compose.ui.platform.LocalContext.current
    var events by remember { mutableStateOf<List<com.shiyin.music.data.db.PlayEventEntity>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val dao = com.shiyin.music.data.db.AppDatabase.get(context).dao()
        events = dao.recentPlayEvents(limit = 500)
        loaded = true
    }

    if (!loaded) return

    // Group by day (simple date string from epoch millis)
    val byDay = events.groupBy { ev ->
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ev.playedAt }
        "%d-%02d-%02d".format(cal.get(java.util.Calendar.YEAR), cal.get(java.util.Calendar.MONTH) + 1, cal.get(java.util.Calendar.DAY_OF_MONTH))
    }
    val lib = vm.lib().associateBy { it.id }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        // header
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackButton { vm.recentOpen = false }
            Text("最近播放", style = heading(22), modifier = Modifier.weight(1f))
        }

        if (events.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("暂无播放记录", style = body(14f, FontWeight.Normal, c.n400))
            }
            return
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for ((day, dayEvents) in byDay.entries.take(20)) {
                item {
                    // day header
                    val dateText = try {
                        val iso = java.time.LocalDate.parse(day)
                        val today = java.time.LocalDate.now()
                        when (iso) {
                            today -> "今天"
                            today.minusDays(1) -> "昨天"
                            else -> "%d月%d日".format(iso.monthValue, iso.dayOfMonth)
                        }
                    } catch (_: Exception) { day }
                    Text("$dateText · ${dayEvents.size} 首", style = body(12f, FontWeight.ExtraBold, c.n600).copy(letterSpacing = 0.5.sp),
                        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
                }
                // Group by album
                val byAlbum = dayEvents.groupBy { ev ->
                    lib[ev.mediaId]?.let { t -> t.album to t.albumId } ?: ("--" to 0L)
                }
                for ((albumKey, albumEvents) in byAlbum) {
                    val track = lib[albumEvents.first().mediaId]
                    if (track == null) continue
                    item {
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                                vm.recentOpen = false
                                vm.openAlbum(com.shiyin.music.data.albumKeyOf(track.album, track.artist, track.albumId))
                            }.padding(horizontal = 4.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            com.shiyin.music.ui.components.CoverArt(track, 48.dp)
                            Column(Modifier.weight(1f)) {
                                Text(track.title, style = body(14f, FontWeight.SemiBold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(track.artist, style = body(12f, FontWeight.Normal, c.n500), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text(formatDuration(albumEvents.first().durationSec.toLong()), style = body(11f, FontWeight.Normal, c.n400))
                        }
                    }
                }
            }
        }
    }
}