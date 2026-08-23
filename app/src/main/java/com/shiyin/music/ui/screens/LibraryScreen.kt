package com.shiyin.music.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.shiyin.music.ui.components.rememberCandidateArt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import sh.calvin.reorderable.ReorderableCollectionItemScope
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiyin.music.MainViewModel
import com.shiyin.music.Tab
import com.shiyin.music.data.Track
import com.shiyin.music.data.formatDuration
import com.shiyin.music.ui.components.CoverArt
import com.shiyin.music.ui.components.CoverArtMosaic
import com.shiyin.music.ui.components.EqBars
import com.shiyin.music.ui.components.OIcon
import com.shiyin.music.ui.components.PillButton
import com.shiyin.music.ui.components.TrackRow
import com.shiyin.music.ui.components.body
import com.shiyin.music.ui.components.coverPalette
import com.shiyin.music.ui.components.heading
import com.shiyin.music.ui.components.rememberAlbumArt
import com.shiyin.music.ui.components.shadowLg
import com.shiyin.music.ui.components.shadowMd
import com.shiyin.music.ui.components.shadowSm
import com.shiyin.music.ui.components.trackSubtitle
import com.shiyin.music.ui.icons.Lucide
import com.shiyin.music.ui.theme.Caprasimo
import com.shiyin.music.ui.theme.LocalOrganic
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** v1.7 unified per-row ⋮ button. */
@Composable
fun TrackMenuButton(vm: MainViewModel, track: Track) {
    val c = LocalOrganic.current
    Box(
        Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable { vm.trackMenuFor = track.id },
        contentAlignment = Alignment.Center,
    ) { OIcon(Lucide.MoreVertical, 17.dp, c.n500) }
}

/**
 * v3.0: Playlist cover render — a chosen single album cover when the user has
 * picked one (via the ⋮ menu), otherwise a 2x2 mosaic assembled from the
 * playlist's tracks (or the ♪ placeholder when the playlist is empty).
 * Co-located in LibraryScreen because it needs [Track] but stays generic.
 */
@Composable
fun PlaylistCover(vm: MainViewModel, pid: String, size: Dp, shape: Shape) {
    val pl = vm.playlists.firstOrNull { it.id == pid }
    val tracks = remember(pid, vm.playlistTracks[pid]) { vm.playlistTrackList(pid) }
    val chosenTrack = remember(pl?.coverAlbumId) {
        pl?.coverAlbumId?.let { aid -> tracks.firstOrNull { it.albumId == aid } }
    }
    if (chosenTrack != null) {
        CoverArt(chosenTrack, size, shape = shape, modifier = Modifier.shadowSm(shape))
    } else {
        CoverArtMosaic(tracks, size, shape = shape, modifier = Modifier.shadowSm(shape))
    }
}

/**
 * Width-filling variant for grid cells: the cover stretches to its parent's
 * width and a 1:1 aspect ratio, rather than a fixed dp size.
 */
@Composable
fun PlaylistCoverFill(vm: MainViewModel, pid: String, shape: Shape) {
    val pl = vm.playlists.firstOrNull { it.id == pid }
    val tracks = remember(pid, vm.playlistTracks[pid]) { vm.playlistTrackList(pid) }
    val chosenTrack = remember(pl?.coverAlbumId) {
        pl?.coverAlbumId?.let { aid -> tracks.firstOrNull { it.albumId == aid } }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .shadowSm(shape)
            .clip(shape),
    ) {
        if (chosenTrack != null) {
            CoverArt(chosenTrack, 130.dp, shape = RoundedCornerShape(0.dp), fillToParent = true)
        } else {
            CoverArtMosaic(tracks, 130.dp, shape = RoundedCornerShape(0.dp), fillToParent = true)
        }
    }
}

@Composable
fun BackButton(onClick: () -> Unit) {
    val c = LocalOrganic.current
    Box(
        Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(c.surface)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { OIcon(Lucide.ChevronLeft, 18.dp, c.text) }
}

private val screenPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 130.dp)

/** v1.2.0 #6: 歌手页折叠头尺寸——hero 写真背景层高 / 定格顶栏高。 */
private val artistHeroH = 320.dp
private val artistBarH = 52.dp

/**
 * v1.2.0 #7: 音乐库 fast-scroll 滑动条。
 *
 * 大库（上千首）拖列表内容太慢——这里改成 drag-to-index：拖 thumb 到哪，
 * 列表直接 [androidx.compose.foundation.lazy.LazyListState.scrollToItem] 到对应
 * index，一拖到底即跳末尾。
 *
 * 轨放在 LazyColumn 的 20dp 右内边距槽（[screenPadding] end=20）里：行尾的
 * ⋮/chevron 在那 20dp 内缩区，故轨不压到任何可点按钮。内容不超过视口
 * （无需滚动）时整条不渲染，零点击区占用。
 *
 * thumb 显示位置用「(首可见 index + 该 item 已滚比例) / 总数」近似——等高列表
 * 精准，非等高足以快速定位；拖动期间改由 thumb 自身位置驱动 scrollToItem，跟手。
 */
@Composable
private fun FastScrollRail(
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier,
) {
    val c = LocalOrganic.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val layoutInfo = listState.layoutInfo
    val total = layoutInfo.totalItemsCount
    // 内容不超过视口 → 无需 fast scroll，不渲染（避免零余点击区压到下层）。
    if (!(listState.canScrollForward || listState.canScrollBackward) || total <= 1) return

    val viewportPx = (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset).coerceAtLeast(0)
    val thumbHDp = 30.dp
    val railWDp = 3.dp
    val thumbWDp = 14.dp
    val touchWDp = 16.dp
    val thumbPx = with(density) { thumbHDp.toPx() }
    val movablePx = (viewportPx - thumbPx).coerceAtLeast(0f)

    // 读 live total（layoutInfo 是 state-backed），避免 capture 旧值。
    val viewProgress by remember {
        derivedStateOf {
            val t = listState.layoutInfo.totalItemsCount
            if (t <= 1) 0f else {
                val fi = listState.firstVisibleItemIndex
                val fo = listState.firstVisibleItemScrollOffset
                val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()
                val fine = if (first != null && first.size > 0) fo.toFloat() / first.size else 0f
                ((fi + fine) / t).coerceIn(0f, 1f)
            }
        }
    }

    var isDragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableFloatStateOf(0f) } // drag 期间 thumb 顶 px
    val thumbTopPx = if (isDragging) dragPos else viewProgress * movablePx
    // v1.2.0 #7: 空闲淡出、滚动/拖动时淡入（Spotify 式）。isScrollInProgress 在滚动
    // 起停时翻转，驱动 alpha 220ms 渐变；detectHorizontalDragGestures 只认水平拖拽，竖向
    // 列表滚动不被 hijack，故触摸区常驻也无妨——从 gutter 起拖即淡入跟手。
    val visible = isDragging || listState.isScrollInProgress
    val railAlpha by animateFloatAsState(if (visible) 1f else 0f, tween(220), label = "railAlpha")

    Box(
        modifier
            .fillMaxHeight()
            .width(touchWDp)
            .graphicsLayer { alpha = railAlpha }
            .pointerInput(total) {
                val railPx = size.height.toFloat()
                val mv = (railPx - thumbPx).coerceAtLeast(0f)
                detectDragGestures(
                    onDragStart = { offset ->
                        isDragging = true
                        dragPos = (offset.y - thumbPx / 2f).coerceIn(0f, mv)
                        val idx = ((dragPos / mv) * total).toInt().coerceIn(0, total - 1)
                        scope.launch { listState.scrollToItem(idx) }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragPos = (dragPos + dragAmount.y).coerceIn(0f, mv)
                        val idx = ((dragPos / mv) * total).toInt().coerceIn(0, total - 1)
                        scope.launch { listState.scrollToItem(idx) }
                    },
                    onDragEnd = { isDragging = false },
                    onDragCancel = { isDragging = false },
                )
            },
    ) {
        // 视觉轨（细竖线）
        Box(
            Modifier
                .align(Alignment.Center)
                .fillMaxHeight()
                .width(railWDp)
                .clip(CircleShape)
                .background(if (isDragging) c.n400 else c.n200),
        )
        // thumb（圆角条，跟手移动）
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .offset { IntOffset(0, thumbTopPx.roundToInt()) }
                .size(width = thumbWDp, height = thumbHDp)
                .clip(CircleShape)
                .background(if (isDragging) c.n500 else c.n400),
        )
    }
}

@Composable
fun LibraryScreen(vm: MainViewModel) {
    // 7-B: hoist the root LazyListState so AnimatedContent can tear down/rebuild
    // LibraryRoot across album/playlist/artist detail transitions without losing
    // the user's scroll position in the music library list.
    val rootListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val subKey = when {
        vm.plId != null -> "pl:${vm.plId}"
        vm.albumKey != null -> "alb:${vm.albumKey}"
        vm.artistKey != null -> "art:${vm.artistKey}"
        else -> "root"
    }
    AnimatedContent(
        targetState = subKey,
        transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(90)) },
        label = "libSub",
    ) { key ->
        when {
            key.startsWith("pl:") -> PlaylistDetail(vm, key.removePrefix("pl:"))
            key.startsWith("alb:") -> AlbumDetail(vm, key.removePrefix("alb:"))
            key.startsWith("art:") -> ArtistDetail(vm, key.removePrefix("art:"))
            else -> {
                Box(Modifier.fillMaxSize()) {
                    LibraryRoot(vm, rootListState)
                    FastScrollRail(rootListState, Modifier.align(Alignment.CenterEnd))
                }
            }
        }
    }
}

// ── root: chips + sort + mixed list/grid + songs ───────────────────────────
private data class LibItem(
    val title: String,
    val sub: String,
    val bg: Color,
    val fg: Color,
    val initial: String,
    val shape: Shape,
    val isHeart: Boolean = false,
    val pinned: Boolean = false,
    val cover: Track? = null,
    // v3.0: when set, render this row's cover via [PlaylistCover] (mosaic or
    // chosen album) instead of the legacy generative block.
    val mosaicForPid: String? = null,
    val onOpen: () -> Unit,
)

@Composable
private fun LibraryRoot(
    vm: MainViewModel,
    listState: androidx.compose.foundation.lazy.LazyListState,
) {
    val c = LocalOrganic.current
    val chip = vm.libChip
    val songs = if (chip == "songs") vm.sortedSongs() else emptyList()
    val items = if (chip == "songs") emptyList() else buildLibItems(vm)

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = screenPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "header") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("音乐库", style = heading(24), modifier = Modifier.weight(1f))
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .clickable { vm.tab = Tab.Search },
                    contentAlignment = Alignment.Center,
                ) { OIcon(Lucide.Search, 19.dp, c.text) }
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(c.surface)
                        .clickable { vm.createPlaylist() },
                    contentAlignment = Alignment.Center,
                ) { OIcon(Lucide.Plus, 18.dp, c.text) }
            }
        }
        item(key = "chips") {
            val chips = listOf("pls" to "歌单", "albums" to "专辑", "artists" to "歌手", "songs" to "歌曲")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((key, label) in chips) {
                    val selected = vm.libChip == key
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(if (selected) c.accent else c.surface)
                            .clickable { vm.libChip = if (selected) null else key }
                            .padding(horizontal = 17.dp, vertical = 8.dp),
                    ) {
                        Text(label, style = body(13f, FontWeight.Bold, if (selected) Color.White else c.text))
                    }
                }
            }
        }
        item(key = "sort") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { vm.libSortAz = !vm.libSortAz }
                        .padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OIcon(Lucide.ArrowUpDown, 14.dp, c.text)
                    Text(if (vm.libSortAz) "名称排序" else "最近添加", style = body(12.5f, FontWeight.Bold, c.text))
                }
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { vm.libViewGrid = !vm.libViewGrid }
                        .padding(6.dp),
                ) {
                    OIcon(if (vm.libViewGrid) Lucide.ListRows else Lucide.LayoutGrid, 16.dp, c.n600)
                }
            }
        }
        if (chip == "songs") {
            items(songs, key = { it.id }) { t ->
                TrackRow(
                    track = t,
                    isCurrent = t.id == vm.player.currentId,
                    isPlaying = vm.player.isPlaying,
                    subtitle = trackSubtitle(t),
                    onClick = { vm.play(t.id) },
                    onLongClick = { vm.trackMenuFor = t.id },
                    trailing = { TrackMenuButton(vm, t) },
                    isHiddenTrack = vm.isHidden(t.id),
                )
            }
        } else if (!vm.libViewGrid) {
            items(items) { MixedListRow(vm, it) }
        } else {
            items(items.chunked(2)) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.padding(bottom = 2.dp)) {
                    row.forEach { item -> Box(Modifier.weight(1f)) { MixedGridCell(vm, item) } }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

/**
 * v5.2: Resolve the Chinese type label ("专辑"/"EP"/"单曲") for an album as
 * it should appear in the music-library list subtitle. The manual override
 * (set via the "修改专辑信息" dialog) wins; otherwise the auto-classifier's
 * track-count/duration heuristic decides. Sourced from
 * [MainViewModel.albumTypeFor] which already folds override-then-heuristic
 * priority, so this is just the English → 中文 mapping for display.
 *
 * Why not call [classifyAlbum] with the tracks here directly: the override
 * lives in the ViewModel's `albumInfoOverrides` map, and the ViewModel
 * helper is the single resolver both the album-edit dialog and the library
 * list should share — two lookups would drift out of sync. The [tracks]
 * parameter is kept for API symmetry with [classifyAlbum] and as a future
 * hook, but [vm] is the authoritative source.
 */
private fun typeLabelFor(vm: MainViewModel, albumId: Long, tracks: List<Track>): String =
    when (vm.albumTypeFor(albumId)) {
        "Single" -> "单曲"
        "EP" -> "EP"
        "Album" -> "专辑"
        "Compilation" -> "合集"
        else -> {
            // albumTypeFor returned "" (albumId <= 0 or unknown) — fall back
            // to the trajectory of the tracks themselves so we never render
            // an empty prefix.
            categoryLabel(classifyAlbum(tracks, null))
        }
    }

@Composable
private fun buildLibItems(vm: MainViewModel): List<LibItem> {
    val c = LocalOrganic.current
    val chip = vm.libChip
    val prefix = { kind: String -> if (chip == null) "$kind · " else "" }
    val list = buildList {
        if (chip == null || chip == "pls") {
            vm.playlists.forEachIndexed { i, p ->
                val tracks = vm.playlistTrackList(p.id)
                val count = tracks.size
                if (p.id == "p3") {
                    add(
                        LibItem(
                            p.name, "${prefix("歌单")}$count 首", c.a400, Color.White, "♥",
                            RoundedCornerShape(10.dp), isHeart = true, pinned = true,
                        ) { vm.openPlaylist(p.id) }
                    )
                } else {
                    val (bg, fg) = coverPalette(i)
                    add(
                        LibItem(p.name, "${prefix("歌单")}$count 首", bg, fg, "♪", RoundedCornerShape(10.dp), mosaicForPid = p.id) {
                            vm.openPlaylist(p.id)
                        }
                    )
                }
            }
        }
        if (chip == null || chip == "albums") {
            for ((key, ts) in vm.albumsMap()) {
                val first = ts.first()
                val (bg, fg) = coverPalette(first.paletteIndex)
                // v5.2: show the album's actual type as the subtitle prefix —
                // override wins (Album/EP/Single via the edit-info dialog),
                // falls back to the auto-classifier. Previously this was hard-
                // coded "专辑", so an album the user had re-categorized as
                // 单曲 still showed "专辑" in the music-library list — that
                // broke the visual contract the user set up via the editor.
                val typeLabel = typeLabelFor(vm, first.albumId, ts)
                add(
                    LibItem(
                        first.album, prefix(typeLabel) + first.artist, bg, fg,
                        first.album.first().uppercase(), RoundedCornerShape(8.dp), cover = first,
                    ) { vm.openAlbum(key) }
                )
            }
        }
        if (chip == null || chip == "artists") {
            vm.artistsMap().entries.forEachIndexed { i, e ->
                val (bg, fg) = coverPalette(i + 1)
                add(
                    LibItem(e.key, "${prefix("歌手")}${e.value.size} 首", bg, fg, e.key.first().uppercase(), CircleShape) {
                        vm.openArtist(e.key)
                    }
                )
            }
        }
    }
    val sorted = if (vm.libSortAz) {
        val collator = java.text.Collator.getInstance(java.util.Locale.CHINESE)
        list.sortedWith(compareBy(collator) { it.title })
    } else list
    val pinned = sorted.filter { it.pinned }
    return pinned + sorted.filterNot { it.pinned }
}

@Composable
private fun heartBrush(): Brush {
    val c = LocalOrganic.current
    return Brush.linearGradient(listOf(c.a400, c.s500))
}

@Composable
private fun MixedListRow(vm: MainViewModel, item: LibItem) {
    val c = LocalOrganic.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = item.onOpen)
            .padding(horizontal = 4.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // v3.0: playlist rows use the mosaic / chosen-album cover.
        if (item.mosaicForPid != null) {
            PlaylistCover(vm, item.mosaicForPid, 56.dp, item.shape)
        } else if (item.cover != null && !item.isHeart) {
            CoverArt(item.cover, 56.dp, item.shape, fontSize = 22, modifier = Modifier.shadowSm(item.shape))
        } else {
            Box(
                Modifier
                    .size(56.dp)
                    .shadowSm(item.shape)
                    .clip(item.shape)
                    .let { if (item.isHeart) it.background(heartBrush()) else it.background(item.bg) },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    item.isHeart -> OIcon(Lucide.HeartFilled, 22.dp, Color.White)
                    item.initial == "♪" -> OIcon(Lucide.ListMusic, 22.dp, item.fg)
                    else -> Text(item.initial, fontFamily = Caprasimo, style = body(22f, FontWeight.Normal, item.fg).copy(fontFamily = Caprasimo))
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(item.title, style = body(15f, FontWeight.Bold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.sub, style = body(12.5f, FontWeight.Normal, c.n600), modifier = Modifier.padding(top = 2.dp), maxLines = 1)
        }
        OIcon(Lucide.ChevronRight, 17.dp, c.n400)
    }
}

@Composable
private fun MixedGridCell(vm: MainViewModel, item: LibItem) {
    val c = LocalOrganic.current
    Column(
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = item.onOpen),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // v3.0: playlist cells use the mosaic / chosen-album cover.
        if (item.mosaicForPid != null) {
            PlaylistCoverFill(vm, item.mosaicForPid, item.shape)
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .shadowSm(item.shape)
                    .clip(item.shape)
                    .let { if (item.isHeart) it.background(heartBrush()) else it.background(item.bg) },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(130.dp)
                        .offset(x = 46.dp, y = (-44).dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.18f))
                )
                when {
                    item.isHeart -> OIcon(Lucide.HeartFilled, 48.dp, Color.White)
                    item.initial == "♪" -> OIcon(Lucide.ListMusic, 48.dp, item.fg)
                    else -> Text(item.initial, fontFamily = Caprasimo, style = body(48f, FontWeight.Normal, item.fg).copy(fontFamily = Caprasimo))
                }
            }
        }
        Column {
            Text(item.title, style = body(13.5f, FontWeight.ExtraBold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.sub, style = body(11.5f, FontWeight.Normal, c.n600), modifier = Modifier.padding(top = 2.dp), maxLines = 1)
        }
    }
}

// ── playlist detail ────────────────────────────────────────────────────────
@Composable
private fun PlaylistDetail(vm: MainViewModel, pid: String) {
    val c = LocalOrganic.current
    val pl = vm.playlists.firstOrNull { it.id == pid } ?: return
    val tracks = vm.playlistTrackList(pid)
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = screenPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BackButton { vm.plId = null }
                Text(pl.name, style = heading(24), modifier = Modifier.weight(1f))
                // v3.0: ⋮ menu for playlist management (rename / cover / delete)
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(c.surface)
                        .clickable { vm.plMenuOpen = true },
                    contentAlignment = Alignment.Center,
                ) { OIcon(Lucide.MoreVertical, 18.dp, c.text) }
            }
        }
        if (tracks.isEmpty()) {
            item {
                Text("歌单还是空的。长按任意歌曲即可加入歌单。", style = body(13f, FontWeight.Normal, c.n500))
            }
        }
        items(tracks, key = { it.id }) { t ->
            TrackRow(
                track = t,
                isCurrent = t.id == vm.player.currentId,
                isPlaying = vm.player.isPlaying,
                subtitle = trackSubtitle(t),
                onClick = { vm.play(t.id) },
                onLongClick = { vm.trackMenuFor = t.id },
                coverSize = 42.dp,
                coverRadius = 13.dp,
                trailing = { TrackMenuButton(vm, t) },
                isHiddenTrack = vm.isHidden(t.id),
            )
        }
    }

    // ── v3.0: playlist ⋮ menu sheet ────────────────────────────────────────
    if (vm.plMenuOpen) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.plMenuOpen = false },
            containerColor = c.surface,
            title = { Text(pl.name, style = body(15f, FontWeight.ExtraBold, c.text)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    MenuAction("重命名歌单", Lucide.ListMusic) { vm.plMenuOpen = false; vm.plRenameFor = pid }
                    MenuAction("更换封面", Lucide.Disc) { vm.plMenuOpen = false; vm.plCoverPickerFor = pid }
                    MenuAction("删除歌单", Lucide.Trash, tint = c.a700) { vm.plMenuOpen = false; vm.plDeleteFor = pid }
                }
            },
            confirmButton = {},
        )
    }

    // ── rename dialog ──────────────────────────────────────────────────────
    if (vm.plRenameFor == pid) {
        var newName by androidx.compose.runtime.remember { mutableStateOf(pl.name) }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.plRenameFor = null },
            containerColor = c.surface,
            title = { Text("重命名歌单", style = body(15f, FontWeight.ExtraBold, c.text)) },
            text = {
                androidx.compose.material3.OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = c.text, unfocusedTextColor = c.text,
                        cursorColor = c.accent, focusedBorderColor = c.accent, unfocusedBorderColor = c.n400,
                    ),
                )
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    if (newName.isNotBlank()) vm.renamePlaylist(pid, newName)
                    vm.plRenameFor = null
                }) { Text("确认", style = body(14f, FontWeight.Bold, c.accent)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { vm.plRenameFor = null }) {
                    Text("取消", style = body(14f, FontWeight.Normal, c.n600))
                }
            },
        )
    }

    // ── cover picker: choose an album from the playlist's tracks ────────────
    if (vm.plCoverPickerFor == pid) {
        // Collect distinct albums present in the playlist.
        val albumOptions = remember(tracks) {
            tracks.groupBy { it.albumId }.entries.map { (aid, ts) ->
                aid to (ts.first().album to ts.size)
            }.sortedBy { (_, p) -> p.first }
        }
        // Separate the "no cover" (mosaic) option from album choices.
        val currentCover = pl.coverAlbumId
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.plCoverPickerFor = null },
            containerColor = c.surface,
            title = { Text("选择封面", style = body(15f, FontWeight.ExtraBold, c.text)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    // "Revert to mosaic" option
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { vm.setPlaylistCover(pid, null); vm.plCoverPickerFor = null }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OIcon(Lucide.LayoutGrid, 18.dp, if (currentCover == null) c.accent else c.n500)
                        Text("自动拼贴", style = body(14f, FontWeight.SemiBold, if (currentCover == null) c.accent else c.text), modifier = Modifier.weight(1f))
                        if (currentCover == null) OIcon(Lucide.Check, 16.dp, c.accent)
                        else Spacer(Modifier.weight(1f))
                    }
                    for ((aid, pair) in albumOptions) {
                        val (albumName, count) = pair
                        val active = aid == currentCover
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { vm.setPlaylistCover(pid, aid); vm.plCoverPickerFor = null }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            OIcon(Lucide.Disc, 18.dp, if (active) c.accent else c.n500)
                            Column(Modifier.weight(1f)) {
                                Text(albumName, style = body(14f, FontWeight.SemiBold, if (active) c.accent else c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("$count 首", style = body(12f, FontWeight.Normal, c.n600))
                            }
                            if (active) OIcon(Lucide.Check, 16.dp, c.accent)
                        }
                    }
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = { vm.plCoverPickerFor = null }) {
                    Text("取消", style = body(14f, FontWeight.Normal, c.n600))
                }
            },
        )
    }

    // ── delete confirmation ─────────────────────────────────────────────────
    if (vm.plDeleteFor == pid) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.plDeleteFor = null },
            containerColor = c.surface,
            title = { Text("删除歌单", style = body(15f, FontWeight.ExtraBold, c.text)) },
            text = {
                Text("确定删除「${pl.name}」吗？此操作不可撤销，但歌曲本身不会被删除。", style = body(14f, FontWeight.Normal, c.n600))
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    vm.deletePlaylist(pid)
                    vm.plDeleteFor = null
                }) { Text("删除", style = body(14f, FontWeight.Bold, c.a700)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { vm.plDeleteFor = null }) {
                    Text("取消", style = body(14f, FontWeight.Normal, c.n600))
                }
            },
        )
    }
}

@Composable
private fun MenuAction(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, tint: Color = LocalOrganic.current.text, onClick: () -> Unit) {
    val c = LocalOrganic.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        OIcon(icon, 18.dp, tint)
        Text(label, style = body(14f, FontWeight.SemiBold, tint))
    }
}

// ── album detail ───────────────────────────────────────────────────────────
@Composable
private fun AlbumDetail(vm: MainViewModel, key: String) {
    val c = LocalOrganic.current
    val tracks = vm.albumOrder(key)
    if (tracks.isEmpty()) {
        // Album vanished (deleted/merged away): bounce back.
        androidx.compose.runtime.LaunchedEffect(key) { vm.albumKey = null }
        return
    }
    val first = tracks.first()
    val (bg, fg) = coverPalette(first.paletteIndex)
    val totalMin = (tracks.sumOf { it.durationSec } / 60).toInt()

    // v5.2 #72: 拖拽排序改用 sh.calvin.reorderable 库,不再手写 dragIndex/dragDelta/
    // dragVel/dragKeyId 状态机 + 自动滚屏协程。状态由下方 reorderState + ReorderableItem 接管。

    // v1.1: arriving from the player's title tap scrolls to and highlights the track
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    androidx.compose.runtime.LaunchedEffect(vm.hlId) {
        val hl = vm.hlId ?: return@LaunchedEffect
        val idx = tracks.indexOfFirst { it.id == hl }
        if (idx >= 0) {
            val headerCount = 5
            listState.animateScrollToItem(headerCount + idx)
            kotlinx.coroutines.delay(2600)
            vm.hlId = null
        }
    }

    // v5.2 #74: reorderable 状态。库 onMove 的 from/to.index 是 LazyColumn【全局 item
    // index】(含 header items:back/cover/album/artist/buttons = 5 个),
    // 但 vm.reorderAlbumTrack 要的是 albumOrder 里的【曲目 index】。两者差 headerCount,
    // 不减的话会把错误的曲换到错误位置(日志里 from=5 一直指第 0 首歌就是这个错位)。
    val headerCount = 5
    val reorderState = rememberReorderableLazyListState(lazyListState = listState) { from, to ->
        val fromTrack = from.index - headerCount
        val toTrack = to.index - headerCount
        android.util.Log.d("DragDebug", "onMove global from=${from.index} to=${to.index} | track from=$fromTrack to=$toTrack key=$key")
        if (fromTrack in tracks.indices && toTrack in tracks.indices) {
            vm.reorderAlbumTrack(key, fromTrack, toTrack)
        }
    }

    // v5.2 #78: 编辑模式下 clamp 滚动——不让列表滚到 header 区(封面/信息)。
    if (vm.albumEdit) {
        androidx.compose.runtime.LaunchedEffect(vm.albumEdit) {
            androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex }
                .collect { idx ->
                    if (idx < headerCount) {
                        listState.scrollToItem(headerCount, 0)
                    }
                }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().clipToBounds(),
        contentPadding = screenPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 12: top bar — back only. The ⋮ was moved down next to 播放专辑 per
        // v4.3 so the album's edit entry sits with the action buttons, not the
        // chrome. (The old right-side ⋮ only toggled text sort.)
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BackButton {
                    if (vm.albumEdit) vm.restoreAlbumOrderIfUncommitted(key)
                    vm.albumKey = null; vm.albumEdit = false
                }
            }
        }
        // 12: large centered cover (~75% width)
        item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier
                        .fillMaxWidth(0.75f)
                        .aspectRatio(1f)
                        .shadowLg(RoundedCornerShape(16.dp))
                        .clip(RoundedCornerShape(16.dp))
                        .background(bg),
                    contentAlignment = Alignment.Center,
                ) {
                    val art = rememberAlbumArt(first, 320.dp)
                    if (art != null) {
                        androidx.compose.foundation.Image(
                            art, null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                        )
                    } else {
                        Box(
                            Modifier
                                .size(220.dp)
                                .offset(x = 80.dp, y = (-70).dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.2f))
                        )
                        Text(
                            first.album.first().uppercase(),
                            fontFamily = Caprasimo,
                            style = body(88f, FontWeight.Normal, fg).copy(fontFamily = Caprasimo),
                        )
                    }
                }
            }
        }
        // 12: album name — large bold
        item {
            Text(
                first.album,
                style = heading(30).copy(lineHeight = 34.sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 12: artist name — clickable (7-C). Use goArtistOf so multi-artist
        // album strings (e.g. "A & B") open the collaborator picker instead of
        // navigating to a non-existent single-artist page.
        item {
            Text(
                first.artist,
                style = body(16f, FontWeight.SemiBold, c.a700),
                modifier = Modifier.clickable { vm.goArtistOf(first.id) },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 6: action buttons — Play album + ⋮ entry to the menu (drag-sort,
        // text-sort, edit-info, change-cover). In edit mode the ⋮ hides and
        // the row shows 完成 / 恢复曲目号排序 instead. In batchMove mode
        // shows 取消 / 迁移到专辑 instead.
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                if (vm.batchMoveMode) {
                    PillButton(
                        "取消",
                        onClick = { vm.batchMoveMode = false; vm.batchMoveSelected = emptySet() },
                        bg = null, textColor = c.text, borderColor = c.divider,
                    )
                    PillButton(
                        "迁移到专辑",
                        onClick = { if (vm.batchMoveSelected.isNotEmpty()) vm.albumBatchMoveSheet = true },
                        bg = if (vm.batchMoveSelected.isNotEmpty()) c.accent else c.n400,
                        textColor = Color.White,
                    )
                } else if (vm.albumEdit) {
                    PillButton(
                        "完成",
                        onClick = { vm.albumEdit = false; vm.clearAlbumEditSnapshot() },
                        bg = null, textColor = c.text, borderColor = c.divider,
                    )
                    PillButton(
                        "恢复曲目号排序",
                        onClick = { vm.resetAlbumOrder(key) },
                        bg = null, textColor = c.a700, fontSize = 13f, padH = 14.dp,
                    )
                } else {
                    PillButton("播放专辑", onClick = { vm.playAlbum(key) }, icon = Lucide.Play)
                    Box(
                        Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(c.surface)
                            .clickable { vm.albumMenuOpen = true },
                        contentAlignment = Alignment.Center,
                    ) { OIcon(Lucide.MoreVertical, 18.dp, c.text) }
                }
            }
        }
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
            if (vm.batchMoveMode) {
                // v5.2 #79: 批量迁移模式——左侧勾选框
                NumberedTrackRow(
                    vm = vm,
                    track = t,
                    idx = i + 1,
                    sub = t.artist,
                    onClick = { vm.toggleBatchMoveSelected(t.id) },
                    highlighted = vm.hlId == t.id,
                    showMenu = false,
                    checkboxSelected = t.id in vm.batchMoveSelected,
                )
            } else if (vm.albumEdit) {
                ReorderableItem(
                    state = reorderState,
                    key = t.id,
                ) {
                    NumberedTrackRow(
                        vm = vm,
                        track = t,
                        idx = i + 1,
                        sub = t.artist,
                        onClick = { vm.playAlbum(key, t.id) },
                        highlighted = vm.hlId == t.id,
                        showMenu = false,
                        reorderScope = this,
                        editControls = {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier.size(36.dp).longPressDraggableHandle()
                                        .clip(CircleShape).background(c.surface),
                                    contentAlignment = Alignment.Center,
                                ) { OIcon(Lucide.GripLines, 18.dp, c.n500) }
                            }
                        },
                    )
                }
            } else {
                NumberedTrackRow(
                    vm = vm,
                    track = t,
                    idx = i + 1,
                    sub = t.artist,
                    onClick = { vm.playAlbum(key, t.id) },
                    highlighted = vm.hlId == t.id,
                    showMenu = true,
                )
            }
        }
        // 5: footer summary — "X 首 · XX 分钟" (left-aligned)
        item {
            Text(
                "${tracks.size} 首 · $totalMin 分钟",
                style = body(12f, FontWeight.Normal, c.n600),
                modifier = Modifier.fillMaxWidth(),
                textAlign = androidx.compose.ui.text.style.TextAlign.Start,
            )
        }
    }

    // ── 7-D: 文本排序对话框 ────────────────────────────────────────────────
    if (vm.albumEditText) {
        var txt by androidx.compose.runtime.remember(key) { mutableStateOf("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.albumEditText = false },
            containerColor = c.surface,
            title = { Text("输入排序文本", style = body(15f, FontWeight.ExtraBold, c.text)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "每行一首，例如「1. 歌名A」「2 歌名B」。未列出歌曲自动追加到末尾。",
                        style = body(12f, FontWeight.Normal, c.n600),
                    )
                    androidx.compose.material3.OutlinedTextField(
                        value = txt,
                        onValueChange = { txt = it },
                        modifier = Modifier.fillMaxWidth().height(220.dp),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedTextColor = c.text, unfocusedTextColor = c.text,
                            cursorColor = c.accent, focusedBorderColor = c.accent, unfocusedBorderColor = c.n400,
                        ),
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    vm.applyAlbumOrderText(key, txt)
                    // v4.5: 全匹配成功 → apply 已落库，关框；有未匹配被暂存 → 关框
                    // 会让“取消返回修改”回不去文本，所以留框等确认窗决断。
                    if (vm.pendingOrder == null) vm.albumEditText = false
                }) { Text("应用", style = body(14f, FontWeight.Bold, c.accent)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { vm.albumEditText = false }) {
                    Text("取消", style = body(14f, FontWeight.Normal, c.n600))
                }
            },
        )
    }

    // ── 7-D: 未匹配歌名确认（v4.5）────────────────────────────────────────
    // 仅当 applyAlbumOrderText 暂存了待确认顺序时显示。用户可选“应用已识别部分”
    // 落库，或“取消返回修改”保留文本框继续编辑。
    val pending = vm.pendingOrder
    if (pending != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.cancelPendingOrder() },
            containerColor = c.surface,
            title = { Text("部分歌名未能匹配", style = body(15f, FontWeight.ExtraBold, c.text)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "以下歌名未匹配到本专辑曲目：",
                        style = body(12.5f, FontWeight.Normal, c.text),
                    )
                    Text(
                        pending.second.joinToString("，"),
                        style = body(12.5f, FontWeight.Medium, c.a700),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "应用后这些行将被跳过，其余已识别曲目仍按你输入的顺序排列，未列出的曲目自动追加到末尾。",
                        style = body(12f, FontWeight.Normal, c.n600),
                    )
                }
            },
            confirmButton = {
                androidx.compose.material3.TextButton(onClick = {
                    vm.commitPendingOrder()
                    vm.albumEditText = false
                }) { Text("应用已识别部分", style = body(14f, FontWeight.Bold, c.accent)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = {
                    // 丢弃预览；保留 albumEditText=true 让用户继续改文本。
                    vm.cancelPendingOrder()
                }) { Text("取消返回修改", style = body(14f, FontWeight.Normal, c.n600)) }
            },
        )
    }
}

@Composable
private fun NumberedTrackRow(
    vm: MainViewModel,
    track: Track,
    idx: Int,
    sub: String,
    onClick: () -> Unit,
    highlighted: Boolean = false,
    showMenu: Boolean = false,
    reorderScope: ReorderableCollectionItemScope? = null,
    editControls: (@Composable ReorderableCollectionItemScope.() -> Unit)? = null,
    checkboxSelected: Boolean? = null,
) {
    val c = LocalOrganic.current
    val isCur = track.id == vm.player.currentId
    // v5.2 隐藏曲:行变灰(透明度降到 0.45),标题颜色也压暗,让人一眼看出
    // 这是被跳过的曲。但行还在、能点,单曲点击照常播(因为用户明确点了)。
    val hidden = vm.isHidden(track.id)
    // v5.2 隐藏曲:当前正在播的那首要保持满亮(跟 TrackRow 一致),只灰掉没在
    // 播的 hidden 曲。否则正在播的隐藏曲在专辑页会比 songs 列表里暗一截。
    val rowAlpha = if (hidden && !isCur) 0.45f else 1f
    val rowBg = androidx.compose.animation.animateColorAsState(
        if (highlighted) c.a100 else Color.Transparent,
        androidx.compose.animation.core.tween(if (highlighted) 100 else 900),
        label = "hl",
    ).value
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = rowAlpha }
                .clip(RoundedCornerShape(10.dp))
                .background(rowBg)
                .clickable(onClick = onClick)
                .padding(horizontal = 4.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (checkboxSelected != null) {
                // v5.2 #79: 批量迁移模式——左侧勾选框
                Box(
                    Modifier.size(26.dp).clip(CircleShape)
                        .background(if (checkboxSelected) c.accent else Color.Transparent)
                        .border(2.dp, if (checkboxSelected) c.accent else c.n400, CircleShape),
                    contentAlignment = Alignment.Center,
                ) { if (checkboxSelected) OIcon(Lucide.CheckBold, 14.dp, Color.White) }
            }
            Text(
                "$idx",
                style = body(14f, FontWeight.Bold, if (isCur) c.a700 else c.text),
                textAlign = TextAlign.Center,
                modifier = Modifier.width(26.dp),
            )
            Column(Modifier.weight(1f)) {
                val titleColor = when {
                    isCur -> c.a700
                    hidden -> c.n500
                    else -> c.text
                }
                Text(
                    track.title,
                    style = body(15f, FontWeight.SemiBold, titleColor),
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(sub, style = body(12f, FontWeight.Normal, c.n600), modifier = Modifier.padding(top = 2.dp))
            }
            if (isCur) EqBars(vm.player.isPlaying)
            reorderScope?.let { scope -> editControls?.invoke(scope) }
            if (showMenu) TrackMenuButton(vm, track)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(c.divider)
        )
    }
}

// ── artist detail ──────────────────────────────────────────────────────────
@Composable
private fun ArtistDetail(vm: MainViewModel, name: String) {
    val c = LocalOrganic.current
    val artistTracks = vm.artistsMap()[name]
    if (artistTracks == null) {
        androidx.compose.runtime.LaunchedEffect(name) { vm.artistKey = null }
        return
    }
    val artistIdx = vm.artistsMap().keys.indexOf(name)
    val (bg, fg) = coverPalette(artistIdx + 1)
    val albums = vm.albumsMap().entries.filter { entry ->
        // v5.2 #79d: 跟 artistsMap()/BatchMoveSheet 对齐——专辑里任一曲目 split 后
        // 含该歌手即算该歌手的专辑。原来用 first().artist==name 精确匹配,多歌手合作
        // 曲(归一化后 "A, B")所在的专辑会被过滤掉,新建专辑尤其容易因此不显示。
        entry.value.any { t -> name in com.shiyin.music.data.MediaScanner.splitArtists(t.artist) } &&
            // v1.2.0 #3: 合集（多歌手杂烩单曲文件夹误判为专辑）不在歌手页展示——
            // 它不属于任一歌手，留在音乐库「合集」分类即可。
            classifyAlbum(entry.value) != AlbumCategory.Compilation
    }
    var showAllAlbums by remember { mutableStateOf(false) }
    // v4.3: song list grows 5 at a time when the user taps 展开 — no longer a
    // single 展开→all toggle. Initial cap is 5 tracks.
    var songCap by remember { mutableStateOf(5) }

    if (showAllAlbums) {
        ArtistAlbumList(
            vm = vm,
            artistName = name,
            albums = albums.associate { it.key to it.value },
            onBack = { showAllAlbums = false },
        )
        return
    }

    // v1.2.0 #6: 写真大图背景层 + 标准 nestedScroll 折叠头。向下滚动时上层内容随
    // 不透明 sheet 上移整片盖住写真图（视差：写真滞后半速）；返回折叠恢复，歌名定格
    // 进顶栏正中。写真 URL 持久化于 artist.avatar_url（复用专辑封面那套 URL→DB +
    // ArtCache 磁盘/内存两层），重启/重扫不丢。
    // 性能：折叠量走 mutableFloatState，仅在 graphicsLayer lambda 里读（deferred），
    // 滚动时 ArtistDetail 主体不重组，故不卡。
    androidx.compose.runtime.LaunchedEffect(name) { vm.fetchArtistAvatar(name) }
    val avatarUrl = vm.artistEntities[name]?.avatarUrl ?: ""
    val avatarBmp = rememberCandidateArt(avatarUrl.ifBlank { null }, 360.dp)
    val density = LocalDensity.current
    val heroMaxPx = with(density) { artistHeroH.toPx() }
    val barPx = with(density) { artistBarH.toPx() }
    val collapseRangePx = (heroMaxPx - barPx).coerceAtLeast(0f)
    val offsetPxState = remember { mutableFloatStateOf(0f) }
    val listState = rememberLazyListState()
    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                val off = offsetPxState.floatValue
                if (dy < 0f && off < collapseRangePx) { // 向上滚 → 先折叠头
                    val target = (off - dy).coerceIn(0f, collapseRangePx)
                    offsetPxState.floatValue = target
                    return Offset(0f, -(target - off))
                }
                return Offset.Zero
            }
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                val dy = available.y
                val off = offsetPxState.floatValue
                if (dy > 0f && off > 0f) { // 顶上向下滚 → 展开头
                    val target = (off - dy).coerceIn(0f, collapseRangePx)
                    offsetPxState.floatValue = target
                    return Offset(0f, off - target)
                }
                return Offset.Zero
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection)
            .clipToBounds(),
    ) {
    ArtistHeader(
        name = name, bg = bg, fg = fg, avatarBmp = avatarBmp,
        offsetPxState = offsetPxState, collapseRangePx = collapseRangePx,
        songCount = artistTracks.size, albumCount = albums.size,
    )
    Box(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .graphicsLayer { translationY = heroMaxPx - offsetPxState.floatValue }
            .background(c.bg),
    )
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().graphicsLayer { translationY = -offsetPxState.floatValue },
        contentPadding = PaddingValues(top = artistHeroH, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { ArtistActionBar(vm = vm, name = name, tracks = artistTracks) }
        if (vm.artistMerge) {
            item {
                val candidates = vm.artistsMap().keys.filter { it != name }
                var selected by remember { mutableStateOf(setOf<String>()) }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .shadowSm(RoundedCornerShape(28.dp))
                        .clip(RoundedCornerShape(28.dp))
                        .background(c.surface)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("合并以下歌手：", style = body(13.5f, FontWeight.ExtraBold, c.text))
                        if (selected.isNotEmpty()) {
                            PillButton(
                                "完成（${selected.size}）",
                                onClick = {
                                    selected.forEach { vm.mergeArtist(it, name) }
                                    selected = emptySet()
                                    vm.artistMerge = false
                                },
                                bg = null, textColor = c.a700, borderColor = c.a700, fontSize = 13f, padH = 14.dp,
                            )
                        }
                    }
                    if (candidates.isNotEmpty()) {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            candidates.chunked(2).forEach { row ->
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    row.forEach { cand ->
                                        val isSelected = cand in selected
                                        Box(
                                            Modifier
                                                .clip(RoundedCornerShape(999.dp))
                                                .background(if (isSelected) c.a100 else c.s200)
                                                .border(if (isSelected) 1.5.dp else 0.dp, if (isSelected) c.a700 else Color.Transparent, RoundedCornerShape(999.dp))
                                                .clickable { selected = if (isSelected) selected - cand else selected + cand }
                                                .padding(horizontal = 15.dp, vertical = 9.dp),
                                        ) {
                                            Text(cand, style = body(13f, FontWeight.Bold, if (isSelected) c.a700 else c.s900), maxLines = 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                    // v4.3: 说明更新 — the flow is: pick other artists (single or
                    // multi) on THIS artist's page, then their songs/albums are
                    // folded into the current artist.
                    Text(
                        "在这里勾选下方歌手（单选或多选均可），他们名下的歌曲与专辑会全部归入「$name」，用于同一歌手被识别成多个名字的情况（如 fujiikaze / 藤井风）。合并后可在「设置 · 歌手合并记录」中撤销。",
                        style = body(12f, FontWeight.Normal, c.n600).copy(lineHeight = 18.sp),
                    )
                }
            }
        }
        item { Text("歌曲", style = body(14f, FontWeight.Bold, c.n700)) }
        val displayTracks = if (artistTracks.size > songCap) artistTracks.take(songCap) else artistTracks
        itemsIndexed(displayTracks, key = { _, t -> t.id }) { i, t ->
            NumberedTrackRow(
                vm = vm,
                track = t,
                idx = i + 1,
                sub = t.album,
                onClick = { vm.play(t.id) },
                showMenu = true,
            )
        }
        // v4.3: 展开 reveals 5 more songs per tap, until all are shown.
        if (artistTracks.size > songCap) {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                    PillButton(
                        "展开",
                        onClick = { songCap = (songCap + 5).coerceAtMost(artistTracks.size) },
                        bg = null, textColor = c.text, borderColor = c.divider,
                    )
                }
            }
        }
        if (albums.isNotEmpty()) {
            // v4.3: "专辑" header row with an 展开全部 button at the far right,
            // opening the full classified list (Album / EP / Single) page.
            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("专辑", style = body(14f, FontWeight.Bold, c.n700))
                    // v5.2 Bug9: the "展开全部" entry to the Album/EP/Single
                    // classified page must always be reachable, no matter how
                    // few albums the artist has — users discover artists with
                    // just 2–3 albums still want to see them grouped by type.
                    // Previously the gate was `albums.size > 4`, so e.g. a
                    // 3-album artist had no way into that page at all (the row
                    // below already shows every album in the rail, but the type
                    // grouping view was unreachable).
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .clickable { showAllAlbums = true }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        Text(
                            "展开全部（${albums.size}）",
                            style = body(12f, FontWeight.Bold, c.a700),
                        )
                    }
                }
            }
            item {
                // v4.3: fixed — show ALL albums in the horizontal rail (previously
                // capped at 4, hiding the rest until 展开全部). Row scrolls freely.
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    for ((albKey, ts) in albums) {
                        val firstT = ts.first()
                        Column(
                            Modifier
                                .width(106.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { vm.openAlbum(albKey) },
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            CoverArt(firstT, 106.dp, RoundedCornerShape(8.dp), fontSize = 38, modifier = Modifier.shadowSm(RoundedCornerShape(8.dp)))
                            Text(firstT.album, style = body(12.5f, FontWeight.Bold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${ts.size} 首", style = body(11f, FontWeight.Normal, c.n600), modifier = Modifier.offset(y = (-4).dp))
                        }
                    }
                }
            }
        }
    }
    // ── 定格顶栏：返回常驻，歌名随折叠居中淡入
    Box(
        Modifier
            .fillMaxWidth()
            .height(artistBarH),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(c.bg)
                .graphicsLayer { alpha = (offsetPxState.floatValue / collapseRangePx.coerceAtLeast(1f)).coerceIn(0f, 1f) },
        ) {}
        Box(Modifier.align(Alignment.CenterStart).padding(start = 8.dp)) {
            BackButton { vm.artistKey = null; vm.artistMerge = false }
        }
        Text(
            name,
            style = heading(20),
            color = c.text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.Center)
                .padding(horizontal = 52.dp)
                .graphicsLayer { alpha = (offsetPxState.floatValue / collapseRangePx.coerceAtLeast(1f)).coerceIn(0f, 1f) },
        )
    }
    }
}

// ── artist header (写真背景层 + 大歌名) ───────────────────────────────────────
@Composable
private fun ArtistHeader(
    name: String,
    bg: Color,
    fg: Color,
    avatarBmp: ImageBitmap?,
    offsetPxState: androidx.compose.runtime.MutableFloatState,
    collapseRangePx: Float,
    songCount: Int,
    albumCount: Int,
) {
    Box(Modifier.fillMaxWidth().height(artistHeroH)) {
        // 写真（或占位），随折叠半速上移 = 视差（写真滞后于内容/歌名，形成层次）
        if (avatarBmp != null) {
            Image(
                bitmap = avatarBmp,
                contentDescription = name,
                modifier = Modifier.fillMaxSize().graphicsLayer { translationY = -0.5f * offsetPxState.floatValue },
                contentScale = ContentScale.Crop,
            )
        } else {
            Box(
                Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(bg, bg.copy(alpha = 0.6f)))),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    name.first().uppercase(),
                    fontFamily = Caprasimo,
                    style = body(96f, FontWeight.Normal, fg.copy(alpha = 0.45f)).copy(fontFamily = Caprasimo),
                )
            }
        }
        // 底部 scrim + 大歌名 + 统计，随折叠淡出
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.5f to Color.Black.copy(alpha = 0.15f),
                        1f to Color.Black.copy(alpha = 0.55f),
                    )
                )
                .graphicsLayer { alpha = (1f - offsetPxState.floatValue / collapseRangePx.coerceAtLeast(1f)).coerceIn(0f, 1f) },
        ) {
            Column(
                Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
            ) {
                Text(name, style = heading(28), color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text("$songCount 首歌曲 · $albumCount 张专辑", style = body(13f, FontWeight.Normal, Color.White.copy(alpha = 0.85f)))
            }
        }
    }
}

// ── artist action bar (圆形播放 + 随机播放 + 管理归属) ──────────────────────────
@Composable
private fun ArtistActionBar(vm: MainViewModel, name: String, tracks: List<Track>) {
    val c = LocalOrganic.current
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(c.accent)
                .clickable { tracks.firstOrNull()?.let { vm.play(it.id) } },
            contentAlignment = Alignment.Center,
        ) { OIcon(Lucide.Play, 22.dp, c.bg) }
        Box(
            Modifier.size(48.dp).clip(CircleShape).background(c.accent)
                .clickable { vm.playRandom(tracks.map { it.id }) },
            contentAlignment = Alignment.Center,
        ) { OIcon(Lucide.Shuffle, 20.dp, c.bg) }
        PillButton("管理归属", onClick = { vm.artistMerge = !vm.artistMerge }, bg = null, textColor = c.text, borderColor = c.divider)
    }
}

// ── artist merge sheet ──────────────────────────────────────────────────────
@Composable
private fun ArtistMergeSheet(vm: MainViewModel, name: String) {
    val c = LocalOrganic.current
    val candidates = vm.artistsMap().keys.filter { it != name }
    var selected by remember { mutableStateOf(setOf<String>()) }
    Column(
        Modifier
            .fillMaxWidth()
            .shadowSm(RoundedCornerShape(28.dp))
            .clip(RoundedCornerShape(28.dp))
            .background(c.surface)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("合并以下歌手：", style = body(13.5f, FontWeight.ExtraBold, c.text))
            if (selected.isNotEmpty()) {
                PillButton(
                    "完成（${selected.size}）",
                    onClick = {
                        selected.forEach { vm.mergeArtist(it, name) }
                        selected = emptySet()
                        vm.artistMerge = false
                    },
                    bg = null, textColor = c.a700, borderColor = c.a700, fontSize = 13f, padH = 14.dp,
                )
            }
        }
        if (candidates.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                candidates.chunked(2).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { cand ->
                            val isSelected = cand in selected
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(if (isSelected) c.a100 else c.s200)
                                    .border(if (isSelected) 1.5.dp else 0.dp, if (isSelected) c.a700 else Color.Transparent, RoundedCornerShape(999.dp))
                                    .clickable { selected = if (isSelected) selected - cand else selected + cand }
                                    .padding(horizontal = 15.dp, vertical = 9.dp),
                            ) {
                                Text(cand, style = body(13f, FontWeight.Bold, if (isSelected) c.a700 else c.s900), maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
        Text(
            "在这里勾选下方歌手（单选或多选均可），他们名下的歌曲与专辑会全部归入「$name」，用于同一歌手被识别成多个名字的情况（如 fujiikaze / 藤井风）。合并后可在「设置 · 歌手合并记录」中撤销。",
            style = body(12f, FontWeight.Normal, c.n600).copy(lineHeight = 18.sp),
        )
    }
}
