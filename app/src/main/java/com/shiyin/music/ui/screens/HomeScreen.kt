package com.shiyin.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shiyin.music.MainViewModel
import com.shiyin.music.Tab
import com.shiyin.music.data.NO_ALBUM
import com.shiyin.music.data.Track
import com.shiyin.music.data.albumKeyOf
import com.shiyin.music.ui.components.CoverArt
import com.shiyin.music.ui.components.OIcon
import com.shiyin.music.ui.components.body
import com.shiyin.music.ui.components.coverPalette
import com.shiyin.music.ui.components.heading
import com.shiyin.music.ui.components.shadowSm
import com.shiyin.music.ui.icons.Lucide
import com.shiyin.music.ui.theme.Caprasimo
import com.shiyin.music.ui.theme.LocalOrganic
import java.util.Calendar

private fun greetingBase(): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when {
        h < 6 -> "夜深了"
        h < 12 -> "早上好"
        h < 18 -> "下午好"
        else -> "晚上好"
    }
}

private fun greetingFaces(): List<String> {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    return when {
        h < 6 -> listOf("(˘ω˘)", "(¦3[▓▓]", "(｡-ω-)", "ｚｚＺ")
        h < 12 -> listOf("(◍•ᴗ•◍)✧", "(*´▽`*)ﾉ", "(｡･ω･｡)", "ヽ(•̀ω•́ )ゝ")
        h < 18 -> listOf("(ﾉ◕ヮ◕)ﾉ*:･ﾟ✧", "(๑•̀ㅂ•́)و✧", "ᕙ(⇀‸↼‶)ᕗ", "(＞ω＜)")
        else -> listOf("(´｡• ᵕ •｡`)", "(˶ᵔ ᵕ ᵔ˶)", "(｡•̀ᴗ-)✧", "(´∀｀)♡")
    }
}

/** Time-of-day bucket (0=late night, 1=morning, 2=afternoon, 3=evening) used
 *  as the key for the random-face `remember`. When the hour crosses a bucket
 *  boundary (e.g. noon → afternoon good-afternoon), both the greeting base and
 *  the kaomoji re-roll — so a long-lived HomeScreen still updates across midnight
 *  while the face stays stable during normal scrolling within a bucket. */
private fun timeBucket(h: Int = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)): Int =
    when {
        h < 6 -> 0
        h < 12 -> 1
        h < 18 -> 2
        else -> 3
    }

@Composable
fun HomeScreen(vm: MainViewModel) {
    val c = LocalOrganic.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val lib = vm.lib()
    Column(
        Modifier
            .fillMaxSize()
            // v1.3.3 返回恢复:滚动位置用 VM 常驻 state——进专辑销毁重建后位置保留。
            .verticalScroll(vm.homeScroll)
            // v1.3.x 第1项:与侧边栏品牌区共用同一顶部基准(见 components.TopBarTop/Height),
            // 两处中心线一致。
            .padding(top = com.shiyin.music.ui.components.TopBarTop),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        // top bar — v3.0: hamburger menu (sidebar)
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .height(com.shiyin.music.ui.components.TopBarHeight),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // v1.3.6c: 三线与"下午好"同一起点线——图标 24dp 紧贴文字(文字左缘=
            // 图标右缘,0 gap;用户要的是"下午好对着三线");触区由 Compose 的
            // minimumInteractiveComponentSize 自动外扩到 ~48dp(点击不丢)。三线
            // 左缘=20dp 网格边距=下方卡片左缘。
            Box(
                Modifier
                    .size(28.dp)
                    .clickable { vm.sidebarOpen = true },
                contentAlignment = Alignment.CenterStart,
            ) { OIcon(Lucide.MenuWide, 24.dp, c.text) }
            val bucket = timeBucket()
            // v4.3: 颜文字按时间槽锁定 —— 同一时间槽内跨 tab 切换、旋转、进出页面均保持
            // 同一个颜文字（rememberSaveable 持久化），仅当跨过时间段边界（bucket 变化）
            // 时才重新随机更换。首次进入不触发二次随机。
            var face by rememberSaveable { mutableStateOf(greetingFaces().random()) }
            val prevBucket = remember { mutableStateOf(bucket) }
            LaunchedEffect(bucket) {
                if (prevBucket.value != bucket) {
                    prevBucket.value = bucket
                    face = greetingFaces().random()
                }
            }
            val gt = "${greetingBase()} $face"
            // v1.3.6c: "下午好"与三线垂直居中——26sp 文字的行高(约 34dp)比 24dp
            // 图标高,CenterVertically 下字形视觉偏上 2-3dp;往下挪 2dp 让字形
            // 中轴与三线中轴真正对齐(用户:"下午好要对着三线才行")。
            Text(gt, style = heading(26), modifier = Modifier.weight(1f).padding(start = 6.dp).offset(y = 2.dp))
        }

        // v2: 2×2 shortcut grid (removed 深夜书桌 + 通勤路上)
        val p0 = coverPalette(0); val p3 = coverPalette(3); val p4 = coverPalette(4)
        // v1.3.x 第8项:入口图标改用项目图标体系(Lucide)的矢量——「全部歌曲」「最近播放」
        // 此前分别是 ♪ / ◷ 两个文字字形，字号小、笔画细、在小尺寸下发虚，与整页图标语言
        // 不统一。♥ / ⇄ 的字形本身清晰，保持不动。
        data class Cut(
            val icon: String,
            val label: String,
            val bg: Color,
            val fg: Color,
            val vector: androidx.compose.ui.graphics.vector.ImageVector? = null,
            val onClick: () -> Unit,
        )
        val cuts = listOf(
            Cut("♥", "我的喜欢", p0.first, p0.second) { vm.openPlaylist("p3") },
            // 「全部歌曲」= 完整曲库/歌曲集合语义 → ListMusic(曲目列表)。
            Cut("♪", "全部歌曲", p4.first, p4.second, Lucide.ListMusic) {
                // v1.3.3 返回恢复:主动切 tab 作废下钻来源记录,防返回错误恢复。
                vm.discardPrevTab()
                vm.tab = Tab.Library; vm.libChip = "songs"; vm.plId = null; vm.albumKey = null; vm.artistKey = null; vm.settingsOpen = false
            },
            Cut("⇄", "随机播放", p3.first, p3.second) { vm.playRandom() },
            // v1.3.6: 清理建议 → 最近播放(用户定调;清理建议在设置里仍有入口)。
            // 「最近播放」= 播放历史/最近使用语义 → History(时钟+回溯箭头)。
            Cut("◷", "最近播放", c.a200, c.a800, Lucide.History) { vm.recentOpen = true; vm.settingsOpen = false },
        )
        Column(
            Modifier.padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp),
        ) {
            cuts.chunked(2).forEach { rowCuts ->
                Row(horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    rowCuts.forEach { cut ->
                        Row(
                            Modifier
                                .weight(1f)
                                .height(52.dp)
                                .shadowSm(RoundedCornerShape(8.dp))
                                .clip(RoundedCornerShape(8.dp))
                                .background(c.surface)
                                .clickable(onClick = cut.onClick),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(
                                Modifier
                                    .size(52.dp)
                                    .background(cut.bg),
                                contentAlignment = Alignment.Center,
                            ) {
                                // v1.3.x 第8项:有矢量图标时用矢量(Lucide 体系,线宽/留白
                                // 与其他图标一致、缩放不发虚);没有才回退到 Caprasimo 字形。
                                if (cut.vector != null) {
                                    OIcon(cut.vector, 24.dp, cut.fg)
                                } else {
                                    Text(cut.icon, fontFamily = Caprasimo, style = body(20f, FontWeight.Normal, cut.fg).copy(fontFamily = Caprasimo))
                                }
                            }
                            Text(
                                cut.label,
                                style = body(12.5f, FontWeight.ExtraBold, c.text),
                                maxLines = 1,
                                modifier = Modifier.padding(end = 8.dp),
                            )
                        }
                    }
                }
            }
        }

        // v2: recent — grouped by album, singles as individual cards
        val recentEntries = run {
            // v22: 用 vm.trackById —— 「完整专辑」容器曲目不在 lib() 里（只进 libById），
            // 用 lib() 映射会让专辑播放从首页「最近播放」里消失。
            vm.recentIds.mapNotNull { vm.trackById(it) }
        }
        // 2026-10-22 用户："冷启动时首页是 空 → 无封面模块 → 有封面模块，有点卡顿；
        // 想一进首页就是加载完成的界面"。
        // 做法：① 曲库还没读出来（tracksRaw 为空）时先画**骨架**（同布局灰块），
        //       不显示半成品；② 数据到了先**预载封面**（最多等 300ms，本地封面秒回、
        //       网络封面不阻塞），封面就绪后一次性显示完整内容 —— 三段闪烁变成一次成型。
        val coldStarting = vm.tracksRaw.isEmpty()
        var coversReady by remember { mutableStateOf(false) }
        androidx.compose.runtime.LaunchedEffect(coldStarting, recentEntries.take(8).map { it.id }) {
            if (coldStarting) { coversReady = false; return@LaunchedEffect }
            val items = recentEntries.take(8)
            if (items.isEmpty()) { coversReady = true; return@LaunchedEffect }
            coversReady = false
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                kotlinx.coroutines.withTimeoutOrNull(300) {
                    items.forEach { t -> runCatching { com.shiyin.music.ui.components.ArtCache.load(context, t, 212) } }
                }
            }
            coversReady = true
        }
        if (coldStarting || (recentEntries.isNotEmpty() && !coversReady)) {
            HomeSectionSkeleton(if (coldStarting) "最近播放" else "最近播放")
        }
        if (recentEntries.isNotEmpty() && !coldStarting && coversReady) {
            // Group by albumKey, dedupe albums, preserve most-recent-play order
            val albumGroups = LinkedHashMap<String, MutableList<Track>>()
            val singles = ArrayList<Track>()
            for (t in recentEntries) {
                if (t.album == NO_ALBUM) {
                    singles.add(t)
                } else {
                    val key = albumKeyOf(t.album, t.artist, t.albumId)
                    albumGroups.getOrPut(key) { mutableListOf() }.add(t)
                }
            }
            // Sort album groups by their most recent play timestamp
            val albumKeys = albumGroups.keys.toList()
            val sortedAlbums = recentEntries.mapNotNull { t ->
                val key = albumKeyOf(t.album, t.artist, t.albumId)
                if (key in albumGroups) key else null
            }.distinct().mapNotNull { key ->
                albumGroups[key]?.let { ts -> key to ts }
            }
            // Sort singles by most recent play order
            val sortedSingles = recentEntries.filter { it.album == NO_ALBUM }.distinctBy { it.id }

            if (sortedAlbums.isNotEmpty() || sortedSingles.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("最近播放", style = heading(20), modifier = Modifier.padding(horizontal = 20.dp))
                    Row(
                        Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = 20.dp),
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        // Album cards
                        for ((key, ts) in sortedAlbums) {
                            val first = ts.first()
                            Column(
                                Modifier
                                    .width(106.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { vm.openAlbum(key) },
                                verticalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                CoverArt(first, 106.dp, RoundedCornerShape(8.dp), fontSize = 38, modifier = Modifier.shadowSm(RoundedCornerShape(8.dp)))
                                Text(
                                    first.album,
                                    style = body(12.5f, FontWeight.Bold, if (first.id == vm.player.currentId) c.a700 else c.text),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    first.artist,
                                    style = body(11f, FontWeight.Normal, c.n600),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.offset(y = (-4).dp),
                                )
                            }
                        }
                        // Single track cards
                        for (t in sortedSingles) {
                            Column(
                                Modifier
                                    .width(106.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable { vm.play(t.id) },
                                verticalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                CoverArt(t, 106.dp, RoundedCornerShape(8.dp), fontSize = 38, modifier = Modifier.shadowSm(RoundedCornerShape(8.dp)))
                                Text(
                                    t.title,
                                    style = body(12.5f, FontWeight.Bold, if (t.id == vm.player.currentId) c.a700 else c.text),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    t.artist,
                                    style = body(11f, FontWeight.Normal, c.n600),
                                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.offset(y = (-4).dp),
                                )
                            }
                        }
                    }
                }
            }
        }
            // v5.2 #70: 每日推荐——最近播放下方,按日随机推荐专辑、不连日重复
            DailyRecommendSection(vm)
        Spacer(Modifier.height(4.dp))
    }
}

/**
 * v5.2 #70: 每日推荐——从音乐库随机推荐几张专辑,按日 seed 保证当天稳定、
 * 跨日不同;用 SharedPreferences 记最近 3 天选过的 key,正常不连日重复。
 * 专辑少(不够 5 张且无新鲜候选)的极端情况允许重复。
 */
@Composable
private fun DailyRecommendSection(vm: MainViewModel) {
    val c = LocalOrganic.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val picks: List<Pair<String, Track>> = remember(vm.lib().size) {
        val byAlbum = vm.lib().filter { it.album != NO_ALBUM }
            .groupBy { albumKeyOf(it.album, it.artist, it.albumId) }
        if (byAlbum.isEmpty()) emptyList()
        else {
            val cal = java.util.Calendar.getInstance()
            val y = cal.get(java.util.Calendar.YEAR)
            val doy = cal.get(java.util.Calendar.DAY_OF_YEAR)
            val prefs = context.getSharedPreferences("daily_rec", android.content.Context.MODE_PRIVATE)
            val tag = "${y}_$doy"
            val recentShown = (1..3).flatMap { d ->
                val cc = cal.clone() as java.util.Calendar
                cc.add(java.util.Calendar.DAY_OF_YEAR, -d)
                prefs.getString("${cc.get(java.util.Calendar.YEAR)}_${cc.get(java.util.Calendar.DAY_OF_YEAR)}", "")
                    ?.split(",")?.filter { it.isNotBlank() } ?: emptyList()
            }.toSet()
            val cached = prefs.getString("picks_$tag", null)
            if (cached != null) {
                cached.split(",").mapNotNull { k -> byAlbum[k]?.let { k to it.first() } }
            } else {
                val rnd = java.util.Random(y.toLong() * 1000 + doy)
                val all = byAlbum.entries.map { it.key to it.value.first() }
                val fresh = all.filter { it.first !in recentShown }
                val pool = if (fresh.size >= 5) fresh else all
                val chosen = pool.shuffled(rnd).take(minOf(5, pool.size))
                prefs.edit().putString("picks_$tag", chosen.joinToString(",") { it.first }).apply()
                chosen
            }
        }
    }
    // 冷启动：与「最近播放」同一策略 —— 曲库未读出先给骨架，读出后先预载封面再显示，
    // 避免"空 → 无封面 → 有封面"三段跳变（2026-10-22 用户反馈）。
    var coversReady by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(picks.map { it.first }) {
        coversReady = false
        if (picks.isNotEmpty()) {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                kotlinx.coroutines.withTimeoutOrNull(300) {
                    picks.forEach { (_, t) ->
                        runCatching { com.shiyin.music.ui.components.ArtCache.load(context, t, 212) }
                    }
                }
            }
        }
        coversReady = true
    }
    if (picks.isEmpty()) {
        if (vm.tracksRaw.isEmpty()) HomeSectionSkeleton("每日推荐")
        return
    }
    if (!coversReady) {
        HomeSectionSkeleton("每日推荐")
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("每日推荐", style = heading(20), modifier = Modifier.padding(horizontal = 20.dp))
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            for ((key, t) in picks) {
                Column(
                    Modifier
                        .width(106.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { vm.openAlbum(key) },
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    CoverArt(t, 106.dp, RoundedCornerShape(8.dp), fontSize = 38, modifier = Modifier.shadowSm(RoundedCornerShape(8.dp)))
                    Text(
                        t.album,
                        style = body(12.5f, FontWeight.Bold, if (t.id == vm.player.currentId) c.a700 else c.text),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        t.artist,
                        style = body(11f, FontWeight.Normal, c.n600),
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.offset(y = (-4).dp),
                    )
                }
            }
        }
    }
}

/**
 * 冷启动骨架（2026-10-22 用户："首页 空 → 无封面 → 有封面，有点卡顿"）。
 *
 * 与真实卡片**同尺寸同排版**的灰块：曲库读出来之前先占位，读出来后封面也预载好了，
 * 于是"一进首页就是加载完成的样子"，不再是三段跳变。
 */
@Composable
private fun HomeSectionSkeleton(title: String) {
    val c = LocalOrganic.current
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(title, style = heading(20), modifier = Modifier.padding(horizontal = 20.dp))
        Row(
            Modifier.padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            repeat(3) {
                Column(Modifier.width(106.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Box(
                        Modifier.size(106.dp).clip(RoundedCornerShape(8.dp)).background(c.n200),
                    )
                    Box(
                        Modifier.height(12.dp).fillMaxWidth(0.85f)
                            .clip(RoundedCornerShape(6.dp)).background(c.n200),
                    )
                    Box(
                        Modifier.height(10.dp).fillMaxWidth(0.55f)
                            .clip(RoundedCornerShape(5.dp)).background(c.n200),
                    )
                }
            }
        }
    }
}
