package com.shiyin.music.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
import androidx.compose.foundation.layout.statusBarsPadding
import com.shiyin.music.ui.icons.Lucide
import com.shiyin.music.ui.theme.LocalOrganic
import com.shiyin.music.ui.theme.OrganicColors
import kotlinx.coroutines.launch

/**
 * Full album list for an artist, with classification (Single/EP/Album).
 *
 * v1.3.3b 改版(用户定调):
 *  - 去掉「最新优先/最早优先」切换 → 改「编辑」按钮:编辑模式下手动拖拽排序 +
 *    「AI 排时间」按钮(联网取各专辑/EP/单曲发布时间,按时间先后排)。
 *  - 分类隔行已区分专辑/EP/单曲 → 行下不再标「XX类型 XX首」,改标 20XX(年份,
 *    无日期不标)。
 *  - 顺序优先级:手动排序(artist_album_order 表)> 发布日期 > 专辑名。
 */
@Composable
fun ArtistAlbumList(
    vm: MainViewModel,
    artistName: String,
    albums: Map<String, List<Track>>,
    onBack: () -> Unit,
) {
    val c = LocalOrganic.current
    var editMode by remember { mutableStateOf(false) }
    var aiBusy by remember { mutableStateOf(false) }
    var aiStatus by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // 订阅日期缓存(加载完/AI 写回后 bump)
    vm.albumDateRevision
    // v1.3.3b review#B1: 拖拽会话的本地顺序——saveArtistAlbumOrder 经 launch(Main)
    // 异步写 state,连续换位时回读可能拿不到刚写的值(竞态错位)。拖拽中直接读
    // dragOrder(本地同步状态),AI 排完/无拖拽时回读 VM。
    var dragOrder by remember(artistName) { mutableStateOf<List<String>?>(null) }
    val manualOrder = dragOrder ?: vm.artistAlbumOrders[artistName]

    // v9: manual type overrides (album_info_override.type) — Album → 专辑, etc.
    val typeOverrides = remember(vm.albumInfoOverrides) {
        vm.albumInfoOverrides
            .filterValues { it.type.isNotBlank() }
            .mapValues { (_, v) -> v.type }
    }

    // Classify (排序在下面按 manualOrder/date 重排,不再用 classifyAlbums 的内置排序)
    val classified = remember(albums, typeOverrides, vm.albumDateRevision, manualOrder) {
        val flat = albums.map { (key, tracks) ->
            val first = tracks.first()
            ClassifiedAlbum(
                key = key,
                tracks = tracks,
                category = classifyAlbum(tracks, first.albumId.takeIf { it > 0 }?.let { typeOverrides[it] }),
                firstTrack = first,
                trackCount = tracks.size,
                totalMinutes = (tracks.sumOf { it.durationSec } / 60).toInt(),
                releaseDate = if (first.albumId > 0) vm.albumDateOf(first.albumId) else "",
            )
        }
        // 顺序:手动 > 日期(新→旧,最新发布在最上——与音乐平台作品列表一致的"栈"
        // 直觉;无日期沉底) > 名称
        if (manualOrder != null) {
            val pos = manualOrder.withIndex().associate { (i, k) -> k to i }
            flat.sortedBy { pos[it.key] ?: Int.MAX_VALUE }
        } else {
            val dated = flat.filter { it.releaseDate.isNotBlank() }.sortedByDescending { it.releaseDate }
            val undated = flat.filter { it.releaseDate.isBlank() }.sortedBy { it.firstTrack.album }
            dated + undated
        }
    }

    // Group by category for section display
    val grouped = remember(classified) {
        classified.groupBy { it.category }
    }

    // v1.3.3b: 编辑模式拖拽排序——跨分类整列换位(手动顺序作用于全列,分类只是
    // 视觉分区;拖到别的分类区间同样生效,按顺序串整体存)。行高 ≈ 64dp+16 间距。
    val allKeys = classified.map { it.key }
    val rowHeightPx = with(androidx.compose.ui.platform.LocalDensity.current) { 80.dp.toPx() }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragDelta by remember { mutableStateOf(0f) }

    LazyColumn(
        // v1.3.6: 歌手页(ArtistDetail)整体不加 statusBarsPadding(写真铺 y=0),
        // 专辑栏作为其子页要在自己这层补——否则返回键顶到状态栏与系统时间挤
        // (用户反馈的正是这个)。补齐后与专辑页(AppRoot 加过 statusBarsPadding
        // + contentPadding top 10dp)的顶部完全同构。
        modifier = Modifier.fillMaxSize().statusBarsPadding(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // Header: back + artist name + ⋮ 菜单
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                BackButton(onBack)
                Text(artistName, style = heading(24), modifier = Modifier.weight(1f))
                // v1.3.5: ⋮ 点开弹菜单(用户定调"编辑应该是弹出选择,不是把功能
                // 放到下面")。三项:AI 排时间(联网取发布日期自动排序)、手动拖拽排序
                // (进编辑模式,行首出拖拽手柄)、恢复自动排序(清手动序+日期重排)。
                // 手动模式下按钮变 ✕ 收起编辑。
                var menuOpen by remember { mutableStateOf(false) }
                Box {
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (editMode) c.accent else androidx.compose.ui.graphics.Color.Transparent)
                            .clickable {
                                if (editMode) { editMode = false; aiStatus = null } else menuOpen = true
                            },
                        contentAlignment = Alignment.Center,
                    ) { OIcon(if (editMode) Lucide.Close else Lucide.MoreVertical, 18.dp, if (editMode) androidx.compose.ui.graphics.Color.White else c.text) }
                    // v1.3.6c: 菜单底色跟主题(dark 下 M3 默认白底刺眼)
                    androidx.compose.material3.DropdownMenu(menuOpen, { menuOpen = false }, containerColor = c.surface) {
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("AI 排时间", style = body(13f, FontWeight.Normal, c.text)) },
                            onClick = {
                                menuOpen = false
                                if (aiBusy) return@DropdownMenuItem
                                aiBusy = true; aiStatus = null
                                scope.launch {
                                    val triples = grouped.values.flatten().map {
                                        Triple(it.key, it.firstTrack.albumId, it.firstTrack.album)
                                    }
                                    val n = vm.fetchArtistAlbumDates(artistName, triples) { aiStatus = it }
                                    aiBusy = false
                                    dragOrder = null
                                    aiStatus = if (n > 0) null else (aiStatus ?: "未能获取发布时间")
                                }
                            },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("手动拖拽排序", style = body(13f, FontWeight.Normal, c.text)) },
                            onClick = { menuOpen = false; editMode = true },
                        )
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text("恢复自动排序", style = body(13f, FontWeight.Normal, c.text)) },
                            onClick = {
                                menuOpen = false
                                dragOrder = null
                                vm.clearArtistAlbumOrder(artistName)
                            },
                        )
                    }
                }
            }
        }

        // AI 排时间的进度行(菜单触发,顶部窄条显示状态;手动模式显示拖拽提示)
        if (aiBusy || aiStatus != null) {
            item {
                Text(
                    aiStatus ?: "AI 正在联网获取发布时间…",
                    style = body(12f, FontWeight.Normal, c.a700),
                )
            }
        }
        if (editMode) {
            item {
                Text(
                    "拖动行左侧手柄调整顺序。",
                    style = body(12f, FontWeight.Normal, c.n600),
                )
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

            item { Text(label, style = body(14f, FontWeight.Bold, c.n700)) }
            items(items, key = { it.key }) { album ->
                val isDragging = dragKey == album.key
                Box(
                    Modifier
                        .graphicsLayer {
                            translationY = if (isDragging) dragDelta else 0f
                            if (isDragging) {
                                shadowElevation = 16f
                                alpha = 0.92f
                            }
                        }
                ) {
                    AlbumRow(
                        album, vm, c, editMode, allKeys, isDragging,
                        onDragStart = {
                            dragKey = album.key; dragDelta = 0f
                        },
                        onDrag = { dy ->
                            dragDelta += dy
                            // 过半行高换位(review#B1: 读本地 dragOrder 同步状态,不回读
                            // VM 的异步落账);换位只更新 dragOrder + 落库,重组即生效。
                            val half = rowHeightPx * 0.5f
                            val cur = dragOrder ?: vm.artistAlbumOrders[artistName] ?: allKeys
                            val idx = cur.indexOf(album.key)
                            if (idx < 0) return@AlbumRow
                            var order = cur
                            var i = idx
                            while (dragDelta > half && i < order.size - 1) {
                                order = order.toMutableList().apply { add(i + 1, removeAt(i)) }
                                i++
                                dragDelta -= rowHeightPx
                            }
                            while (dragDelta < -half && i > 0) {
                                order = order.toMutableList().apply { add(i - 1, removeAt(i)) }
                                i--
                                dragDelta += rowHeightPx
                            }
                            if (order !== cur) {
                                dragOrder = order
                                vm.saveArtistAlbumOrder(artistName, order)
                            }
                        },
                        onDragEnd = { dragKey = null; dragDelta = 0f },
                    )
                }
            }
        }
    }
}

/** 年份(20XX)——无日期返回空串。 */
private fun yearOf(iso: String): String =
    if (iso.length >= 4 && iso.take(4).all { it.isDigit() }) iso.take(4) else ""

@Composable
private fun AlbumRow(
    album: ClassifiedAlbum,
    vm: MainViewModel,
    c: OrganicColors,
    editMode: Boolean,
    allKeys: List<String>,
    isDragging: Boolean,
    onDragStart: () -> Unit,
    onDrag: (Float) -> Unit,
    onDragEnd: () -> Unit,
) {
    val first = album.firstTrack
    val year = yearOf(album.releaseDate)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = !editMode) { vm.openAlbum(album.key) }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // 编辑模式:长按手柄竖向拖拽换位(与播放队列同手势,不劫持普通滚动)
        if (editMode) {
            Box(
                Modifier
                    .size(34.dp)
                    .pointerInput(album.key) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { onDragStart() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount.y)
                            },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) { OIcon(Lucide.GripLines, 18.dp, c.n400) }
        }
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
            // v1.3.3b: 不再标「XX类型 XX首」(隔行已区分)——改标 20XX 年份,无日期不标。
            Text(
                year,
                style = body(12f, FontWeight.Normal, c.n600),
            )
        }
        if (!editMode) OIcon(Lucide.ChevronRight, 18.dp, c.n400)
    }
}
