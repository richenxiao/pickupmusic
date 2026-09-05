package com.shiyin.music.ui.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiyin.music.MainViewModel
import com.shiyin.music.data.formatDuration
import com.shiyin.music.ui.components.OIcon
import com.shiyin.music.ui.components.body
import com.shiyin.music.ui.components.heading
import com.shiyin.music.ui.components.rememberCandidateArt
import com.shiyin.music.ui.icons.Lucide
import com.shiyin.music.ui.theme.LocalOrganic

/**
 * v4: 收听统计 — Spotify Wrapped-style weekly listening stats.
 * Shows: total listening time, top album, top artist, top track for the current week.
 * Data is aggregated from the play_event table.
 */
@Composable
fun ListeningStatsScreen(vm: MainViewModel) {
    val c = LocalOrganic.current
    data class WeeklyStats(
        val totalSeconds: Long = 0,
        val topAlbums: List<Pair<String, Int>> = emptyList(),     // albumKey -> plays
        val topArtists: List<Pair<String, Int>> = emptyList(),    // artist -> plays
        val topTracks: List<Pair<Long, Int>> = emptyList(),       // mediaId -> plays
    )
    var stats by remember { mutableStateOf<WeeklyStats?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        val dao = com.shiyin.music.data.db.AppDatabase.get(context).dao()
        // This week: Monday 00:00 to now
        val cal = java.util.Calendar.getInstance()
        cal.set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY)
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        val weekStart = cal.timeInMillis
        val weekEnd = System.currentTimeMillis()
        // v1.2.1: 只数"有效播放"(累计听满 30 秒, completed=1)——跳过/误触(completed=0)
        // 仍在 play_event 里供 最近播放 显示,但不计入统计。总时长用每条的 playedSec
        // (实际累计秒数),不再用曲目总长 durationSec(新口径下会把"听满30秒"误算成"听完整首")。
        val events = dao.completedPlayEventsBetween(weekStart, weekEnd)
        if (events.isEmpty()) { stats = WeeklyStats(); return@LaunchedEffect }

        val totalSec = events.sumOf { it.playedSec.toLong() }
        val lib = vm.lib().associateBy { it.id }
        // v5.2 #52: 专辑按"循环次数"计——累计原始完成播放数，最后 ÷ 该专辑曲目数（整除
        // 向下取整）。听完一整张 10 曲专辑 = 10 次播放 ÷ 10 = 1 次（而非 10 次）。
        // v5.2 #64: 排除单曲(Single)——只有1首且标注单曲的合集不算专辑，不纳入"最常听专辑"。
        // EP 迷你专辑算专辑，保留。
        val albumTrackCounts = vm.lib()
            .groupBy { com.shiyin.music.data.albumKeyOf(it.album, it.artist, it.albumId) }
            .mapValues { it.value.size }
        // 预算每个 albumKey 是否为单曲：取该 album 任一 track 的 albumId 查 type
        val singleKeys = vm.lib()
            .groupBy { com.shiyin.music.data.albumKeyOf(it.album, it.artist, it.albumId) }
            .mapValues { (_, ts) -> ts.firstOrNull()?.albumId }
            .filterValues { id -> id != null && vm.albumTypeFor(id) == "Single" }
            .keys
        val albumPlayMap = mutableMapOf<String, Int>()
        val artistMap = mutableMapOf<String, Int>()
        val trackMap = mutableMapOf<Long, Int>()
        for (ev in events) {
            val t = lib[ev.mediaId]
            if (t != null) {
                val key = com.shiyin.music.data.albumKeyOf(t.album, t.artist, t.albumId)
                if (key !in singleKeys) {   // 跳过单曲
                    albumPlayMap[key] = (albumPlayMap[key] ?: 0) + 1
                }
            }
            // v1.3.1: 艺人聚合口径与 artistsMap()/歌手页一致——先拆分多歌手串
            // (lib() 已把 "A&B" 规范成 "A, B" 展示串,这里必须再拆回单名),
            // 否则 key 是 "周杰伦, 费玉清" 整串,写真表里只有 "周杰伦"/"费玉清",
            // 头像永远落空(歌手页有写真、统计页不显示的原因)。
            for (n in (t?.let { com.shiyin.music.data.MediaScanner.splitArtists(it.artist) } ?: listOf("未知"))) {
                artistMap[n] = (artistMap[n] ?: 0) + 1
            }
            trackMap[ev.mediaId] = (trackMap[ev.mediaId] ?: 0) + 1
        }
        val albumCycles = albumPlayMap.mapValues { (key, plays) ->
            plays / (albumTrackCounts[key] ?: 1)
        }
        stats = WeeklyStats(
            totalSeconds = totalSec,
            topAlbums = albumCycles.entries.filter { it.value > 0 }.sortedByDescending { it.value }.take(5).map { it.key to it.value },
            topArtists = artistMap.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value },
            topTracks = trackMap.entries.sortedByDescending { it.value }.take(5).map { it.key to it.value },
        )
    }

    Column(Modifier.fillMaxSize().background(c.bg)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            BackButton { vm.statsOpen = false }
            Text("收听统计", style = heading(22), modifier = Modifier.weight(1f))
        }

        val s = stats
        if (s == null) return@Column

        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            if (s.totalSeconds == 0L) {
                Text("本周暂无播放记录", style = body(14f, FontWeight.Normal, c.n400), modifier = Modifier.padding(top = 40.dp))
                return@Column
            }

            // Total listening time
            Column(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(c.surface).padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("本周总听歌时长", style = body(12f, FontWeight.Bold, c.n600).copy(letterSpacing = 1.sp))
                Spacer(Modifier.height(8.dp))
                val hours = s.totalSeconds / 3600
                val mins = (s.totalSeconds % 3600) / 60
                Text(
                    if (hours > 0) "${hours} 小时 ${mins} 分钟" else "${mins} 分钟",
                    style = heading(32).copy(color = c.accent),
                )
            }

            // Top tracks (歌曲最先)
            StatSection("本周最常听的歌曲", s.topTracks) { (mediaId, plays) ->
                val t = vm.lib().firstOrNull { it.id == mediaId }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (t != null) {
                        com.shiyin.music.ui.components.CoverArt(t, 44.dp)
                        Column(Modifier.weight(1f)) {
                            Text(t.title, style = body(14f, FontWeight.SemiBold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.artist, style = body(12f, FontWeight.Normal, c.n500), maxLines = 1)
                        }
                    } else {
                        Text("已删除", style = body(14f, FontWeight.SemiBold, c.n400), modifier = Modifier.weight(1f))
                    }
                    Text("$plays 次", style = body(12f, FontWeight.Bold, c.n500))
                }
            }

            // Top albums
            StatSection("本周最常听的专辑", s.topAlbums) { (key, plays) ->
                val t = vm.lib().firstOrNull { tr ->
                    com.shiyin.music.data.albumKeyOf(tr.album, tr.artist, tr.albumId) == key
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (t != null) {
                        com.shiyin.music.ui.components.CoverArt(t, 44.dp)
                        Column(Modifier.weight(1f)) {
                            Text(t.album, style = body(14f, FontWeight.SemiBold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(t.artist, style = body(12f, FontWeight.Normal, c.n500), maxLines = 1)
                        }
                    } else {
                        Text(key, style = body(14f, FontWeight.SemiBold, c.text), modifier = Modifier.weight(1f))
                    }
                    Text("$plays 次", style = body(12f, FontWeight.Bold, c.n500))
                }
            }

            // Top artists (最后)
            StatSection("本周最常听的艺人", s.topArtists) { (artist, plays) ->
                // v4.3: fixed — the inner Row now takes the free weight, so
                // "15 次" keeps its own space instead of being squeezed onto
                // a new line (previously displayed as a wrapped "15 次").
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        // v1.3.0: 显示歌手写真(与歌手页/资料库同一套 override→cache→自动源),
                        // 无写真回退 User 占位图标——此前这里恒为占位图标,写真已获取也不显示。
                        StatsArtistAvatar(vm, artist)
                        Text(artist, style = body(14f, FontWeight.SemiBold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    }
                    Text("$plays 次", style = body(12f, FontWeight.Bold, c.n500), maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun <T> StatSection(title: String, items: List<Pair<T, Int>>, row: @Composable (Pair<T, Int>) -> Unit) {
    val c = LocalOrganic.current
    if (items.isEmpty()) return
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = body(13f, FontWeight.ExtraBold, c.text).copy(letterSpacing = 0.5.sp),
            modifier = Modifier.padding(bottom = 8.dp))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(c.surface).padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items.forEach { row(it) }
        }
    }
}

/** v1.3.0: 收听统计歌手头像——复用资料库/歌手页的写真加载链(vm.fetchArtistAvatar →
 *  ArtistImageResolver override→cache→自动源,rememberCandidateArt 磁盘缓存出图)。
 *  无写真时保持原 User 占位图标。
 *  v1.3.5: 改用 fetchArtistAvatarPerson(personOnly)——圆框头像不拿专辑封面/banner
 *  兜底(封面横幅塞圆框会把人截成半张,"头像显示不全");经 alias 归一取图(别名合并
 *  的歌手写真存在规范名下,原名查不到 → "写真明明有却占位"的另一半根因)。
 *  v1.3.5: 宽图(背景/横幅类)圆裁时**取上部**——人脸在照片上部,居中裁会把头切掉
 *  (后位歌手"显示不全"的直接原因;前三名碰巧是方图/近方图所以没暴露)。方/竖图
 *  仍居中。 */
@Composable
private fun StatsArtistAvatar(vm: MainViewModel, name: String) {
    val c = LocalOrganic.current
    LaunchedEffect(name) { vm.fetchArtistAvatarPerson(name) }
    val bmp = rememberCandidateArt(vm.artistImage(name).ifBlank { null }, 40.dp)
    Box(Modifier.size(40.dp).clip(CircleShape).background(c.n200), contentAlignment = Alignment.Center) {
        if (bmp != null) {
            // 与资料库列表头像同一套裁剪(宽图取上部,方/竖图居中)
            AvatarCropImage(bmp)
        } else {
            OIcon(Lucide.User, 20.dp, c.n600)
        }
    }
}