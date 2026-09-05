package com.shiyin.music.ui.screens

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import com.shiyin.music.ui.components.rememberCandidateArt
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.platform.LocalConfiguration
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
            key.startsWith("pl:") -> Box(Modifier.fillMaxSize().statusBarsPadding()) { PlaylistDetail(vm, key.removePrefix("pl:")) }
            key.startsWith("alb:") -> Box(Modifier.fillMaxSize().statusBarsPadding()) { AlbumDetail(vm, key.removePrefix("alb:")) }
            key.startsWith("art:") -> ArtistDetail(vm, key.removePrefix("art:"))  // 写真铺到 y=0,不加 statusBarsPadding
            else -> {
                Box(Modifier.fillMaxSize().statusBarsPadding()) {
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
    // v1.2.0 #6: 歌手行——有写真时圆形头像绑照片(无则回退首字母)。lazy fetch + 缓存。
    val photoName: String? = null,
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
                // v1.2.1: "专辑"筛选排除单曲(type==Single)——单曲不是专辑,不该出现在专辑列表。
                // chip==null(全部)时仍显示单曲(带"单曲"标签),只在"专辑"筛选时排除。
                // 用户可在「修改专辑信息」把单曲改标为专辑,override 优先,则不被排除。
                if (chip == "albums" && vm.albumTypeFor(first.albumId) == "Single") continue
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
                    LibItem(e.key, "${prefix("歌手")}${e.value.size} 首", bg, fg, e.key.first().uppercase(), CircleShape, photoName = e.key) {
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

/** v1.2.0 #6: 歌手头像内容——有写真(已获取/缓存)绑照片填满,无则首字母回退;进列表时 lazy fetch。
 *  v1.3.5: 圆框头像走 personOnly(不拿专辑封面/banner 兜底——横幅塞圆框截成半张脸),
 *  且经 alias 归一(合并过的歌手写真在规范名下,原名查不到)。宽图取上部裁(脸在上,
 *  居中裁会切头——"头像显示不全"的直接原因),方/竖图居中。
 *  v1.3.6: 提供方框版 [avatarImage] 供搜索页等圆框复用同一套裁剪逻辑。 */
@Composable
internal fun ArtistAvatarContent(vm: MainViewModel, name: String, fallbackInitial: String, fallbackFg: Color, fontSize: Float, loadSize: Dp = 130.dp) {
    androidx.compose.runtime.LaunchedEffect(name) { vm.fetchArtistAvatarPerson(name) }
    val bmp = rememberCandidateArt(vm.artistImage(name).ifBlank { null }, loadSize)
    if (bmp != null) AvatarCropImage(bmp)
    else Text(fallbackInitial, fontFamily = Caprasimo, style = body(fontSize, FontWeight.Normal, fallbackFg).copy(fontFamily = Caprasimo))
}

/** v1.3.5: 圆框头像统一裁剪——宽图取上部(脸在上),方/竖图居中;fillMaxSize 绘制。 */
@Composable
internal fun AvatarCropImage(bmp: androidx.compose.ui.graphics.ImageBitmap) {
    val wide = bmp.width > bmp.height * 1.2f
    val srcW: Float; val srcH: Float; val srcOffset: androidx.compose.ui.geometry.Offset
    if (wide) {
        srcH = bmp.height.toFloat(); srcW = srcH
        srcOffset = androidx.compose.ui.geometry.Offset(((bmp.width - srcW) / 2f).coerceAtLeast(0f), bmp.height * 0.05f)
    } else {
        val side = minOf(bmp.width, bmp.height).toFloat()
        srcW = side; srcH = side
        srcOffset = androidx.compose.ui.geometry.Offset(((bmp.width - side) / 2f).coerceAtLeast(0f), ((bmp.height - side) / 2f).coerceAtLeast(0f))
    }
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        drawImage(
            image = bmp,
            srcOffset = androidx.compose.ui.unit.IntOffset(srcOffset.x.toInt(), srcOffset.y.toInt()),
            srcSize = androidx.compose.ui.unit.IntSize(srcW.toInt().coerceAtLeast(1), srcH.toInt().coerceAtLeast(1)),
            dstOffset = androidx.compose.ui.unit.IntOffset.Zero,
            dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
        )
    }
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
        } else if (item.photoName != null) {
            Box(
                Modifier.size(56.dp).shadowSm(item.shape).clip(item.shape).background(item.bg),
                contentAlignment = Alignment.Center,
            ) { ArtistAvatarContent(vm, item.photoName, item.initial, item.fg, 22f) }
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
                    item.photoName != null -> ArtistAvatarContent(vm, item.photoName, item.initial, item.fg, 48f)
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
    val (bg, fg) = coverPalette(((pid.hashCode() and 0x7fffffff) % 8) + 1)
    val chosenCover = vm.playlistCoverTrack(pid)
    // v1.2.0 #6: 歌单详情重建为专辑页样式(大封面+名+播放按钮+曲目行),但曲目行显各自专辑封面
    // (showCover=true,歌单跨专辑);大封面为用户手选 or 4 拼 mosaic(跨专辑拼各自封面)。
    LazyColumn(
        modifier = Modifier.fillMaxSize().clipToBounds(),
        contentPadding = screenPadding,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // 顶栏:返回
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                BackButton { vm.plId = null; vm.restoreTabIfDrillFullyClosed() }
            }
        }
        // 大封面(75% 宽 1:1):手选封面 / 4 拼 mosaic / 空占位
        item {
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.fillMaxWidth(0.75f).aspectRatio(1f)
                        .shadowLg(RoundedCornerShape(16.dp)).clip(RoundedCornerShape(16.dp)).background(bg),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        tracks.isEmpty() -> OIcon(Lucide.ListMusic, 64.dp, fg.copy(alpha = 0.5f))
                        chosenCover != null -> CoverArt(chosenCover, 320.dp, RoundedCornerShape(0.dp), fillToParent = true)
                        else -> CoverArtMosaic(tracks, 320.dp, RoundedCornerShape(0.dp), fillToParent = true)
                    }
                }
            }
        }
        // 歌单名
        item { Text(pl.name, style = heading(30).copy(lineHeight = 34.sp), maxLines = 2, overflow = TextOverflow.Ellipsis) }
        // 副标题
        item { Text("${tracks.size} 首", style = body(16f, FontWeight.SemiBold, c.a700)) }
        // 操作栏:播放 + ⋮
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                PillButton("播放", onClick = { vm.playPlaylist(pid) }, icon = Lucide.Play)
                Box(Modifier.size(40.dp).clip(CircleShape).background(c.surface).clickable { vm.plMenuOpen = true }, contentAlignment = Alignment.Center) {
                    OIcon(Lucide.MoreVertical, 18.dp, c.text)
                }
            }
        }
        if (tracks.isEmpty()) {
            item { Text("歌单还是空的。长按任意歌曲即可加入歌单。", style = body(13f, FontWeight.Normal, c.n500)) }
        }
        // 曲目行:像专辑页 NumberedTrackRow,但 showCover=true(歌单跨专辑→显各自封面)
        itemsIndexed(tracks, key = { _, t -> t.id }) { i, t ->
            NumberedTrackRow(vm = vm, track = t, idx = i + 1, sub = t.artist, onClick = { vm.playPlaylist(pid, t.id) }, showMenu = true, showCover = true)
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
                    // v1.2.0 #6: 内置「我的喜欢」(p3)不可删
                    if (pid != "p3") {
                        MenuAction("删除歌单", Lucide.Trash, tint = c.a700) { vm.plMenuOpen = false; vm.plDeleteFor = pid }
                    }
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
    // v1.2.2: 合集(Compilation)的曲目各自独立封面——落盘 key 走曲目级(track:mediaId),
    // 避免同 albumId 下后落盘曲目覆盖先落盘曲目的封面。正常专辑用专辑级(album:albumId)共享。
    val isCompilation = vm.albumTypeFor(first.albumId) == "Compilation"
    val trackCoverScope = if (isCompilation)
        com.shiyin.music.ui.components.ArtCache.CoverScope.TRACK
    else com.shiyin.music.ui.components.ArtCache.CoverScope.AUTO

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
        // v1.3.3b: 专辑页顶部收紧——原共用 screenPadding(top=22)+spacedBy16,返回键和
        // 封面距状态栏过远,上方大片空白。专辑页专用 padding top=10 + spacedBy 12。
        // v1.3.5: 顶部 46→10dp(用户反馈"上方还有一片空白行,显得不协调"——要的是
        // 收紧上抬,不是下移)。状态栏的空隙由 AppRoot 的 statusBarsPadding 提供,
        // 这里只留返回键与状态栏的小间距。
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 10.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
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
                    // v1.3.3 返回恢复:下钻层全清则回原 tab;完整退出歌手层则清歌手快照
                    if (!vm.restoreTabIfDrillFullyClosed() && vm.artistKey == null) vm.clearArtistUiState()
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
        // 12: album name — large bold.
        // v1.3.3b: 自适应字号——长名(如 "help ever hurt never")固定 30sp 会把单词
        // 丑陋地断在第二行开头。先按字符宽度估算一行放不放得下:放得下 30sp;放不下
        // 缩到能一行的字号(下限 20sp,再小失了标题气质);连 20sp 都要两行 → 允许
        // 两行、仍给 20sp。CJK≈1em/latin≈0.6em 估宽(与歌手页同法),0.92 安全系数。
        item {
            val availWidthPx = with(androidx.compose.ui.platform.LocalDensity.current) {
                // LazyColumn 内容宽 = 屏宽 - 2*20dp(start/end padding)
                (androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp - 40.dp).toPx()
            }
            val cjkCount = first.album.count { com.shiyin.music.data.normalize.CharUtil.isCjk(it.toString()) }
            val widthFactor = cjkCount * 1.0f + (first.album.length - cjkCount) * 0.62f
            val fitSize = (availWidthPx / widthFactor * 0.92f).let { px ->
                with(androidx.compose.ui.platform.LocalDensity.current) { px.toSp().value }
            }.coerceAtLeast(20f)
            val titleSize = if (fitSize >= 30f) 30f else fitSize.coerceAtLeast(20f)
            // v1.3.3b review#B4: 一律 maxLines=2——估宽(CJK 1em/latin 0.62em)偏小时,
            // 短名估得下 30sp 但实际渲染更宽,单行上限会末尾截字;两行上限对短名无
            // 副作用(仍一行),长名按需换行不截尾。
            Text(
                first.album,
                style = heading(titleSize.toInt()).copy(lineHeight = (titleSize * 1.15f).sp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 12: artist name — clickable (7-C). Use goArtistOf so multi-artist
        // album strings (e.g. "A & B") open the collaborator picker instead of
        // navigating to a non-existent single-artist page.
        // v1.3.5: 歌手名与日期左对齐成一条线(此前日期居中、与专辑名/歌手名错位,
        // "没对齐很乱");LazyColumn 内容本身左对齐,去掉居中即齐。
        // v1.3.6: 日期行不再紧贴歌手名——下移 10dp,落在歌手名与播放按钮行的中间
        // 呼吸位(用户定调"挨得太紧,往下一点才协调")。
        item {
            Column {
                Text(
                    first.artist,
                    style = body(16f, FontWeight.SemiBold, c.a700),
                    modifier = Modifier.clickable { vm.goArtistOf(first.id) },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // 同步读 albumDateCache(VM init 全量加载,albumDateRevision 订阅刷新)。
                vm.albumDateRevision
                val releaseDate = vm.albumDateOf(first.albumId)
                // v1.3.6f: 类型前缀跟真实分类走(EP/单曲不再误标"专辑")——分类用
                // 曲目数启发式 + 手动类型覆盖(album_info_override.type,与歌手页
                // 专辑栏同一来源),合辑显示"合集"。
                val typeOverride = first.albumId.takeIf { it > 0 }?.let { vm.albumInfoOverrides[it]?.type }?.takeIf { it.isNotBlank() }
                val typeLabel = categoryLabel(classifyAlbum(tracks, typeOverride))
                if (releaseDate.isNotBlank()) {
                    Text(
                        "$typeLabel · ${releaseDate.take(10)}",
                        style = body(12f, FontWeight.Normal, c.n600),
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
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
                    PillButton("播放", onClick = { vm.playAlbum(key) }, icon = Lucide.Play)
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
                    coverScope = trackCoverScope,
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
        // v1.2.0: 默认显示当前歌曲按序号排列(1.歌名 2.歌名…),作只读提示;复制键方便拷给 AI 排序后粘回。
        val currentList = vm.albumOrder(key).mapIndexed { i, t -> "${i + 1}.${t.title}" }.joinToString("\n")
        val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
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
                        modifier = Modifier.fillMaxWidth().height(220.dp).verticalScroll(rememberScrollState()),
                        placeholder = {
                            Text(currentList, style = body(13f, FontWeight.Normal, c.n500), modifier = Modifier.fillMaxWidth())
                        },
                        textStyle = body(13f, FontWeight.Normal, c.text),
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
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    androidx.compose.material3.TextButton(onClick = {
                        clipboard.setText(androidx.compose.ui.text.AnnotatedString(currentList))
                        vm.showToast("已复制当前列表，可粘贴给 AI 再返回排序", null)
                    }) { Text("复制", style = body(14f, FontWeight.SemiBold, c.a700)) }
                    androidx.compose.material3.TextButton(onClick = { vm.albumEditText = false }) {
                        Text("取消", style = body(14f, FontWeight.Normal, c.n600))
                    }
                }
            },
        )
    }

    // ── 7-D: 未匹配歌名确认（v4.5）────────────────────────────────────────
    // 仅当文本排序 applyAlbumOrderText 暂存了待确认顺序时显示(pendingSource="text")。
    // v1.3.3: Agent 写回(pendingSource="agent")的确认窗移到 AppRoot 全局层——本组合
    // 在 Agent 页打开时不在组合树里,Agent 场景的确认走全局窗。
    val pending = vm.pendingOrder
    if (pending != null && vm.pendingSource == "text") {
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
    showCover: Boolean = false,
    reorderScope: ReorderableCollectionItemScope? = null,
    editControls: (@Composable ReorderableCollectionItemScope.() -> Unit)? = null,
    checkboxSelected: Boolean? = null,
    coverScope: com.shiyin.music.ui.components.ArtCache.CoverScope = com.shiyin.music.ui.components.ArtCache.CoverScope.AUTO,
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
            horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                textAlign = TextAlign.Start,
                modifier = Modifier.width(20.dp),
            )
            if (showCover) {
                CoverArt(track, 40.dp, RoundedCornerShape(6.dp), modifier = Modifier.size(40.dp).shadowSm(RoundedCornerShape(6.dp)), coverScope = coverScope)
            }
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

/**
 * v1.2.0 #6: 歌手名自适应字号——短名(卢广仲)大字、长名(揽佬 SKAI ISYOURGOD)自动缩小或换两行。
 * onTextLayout 动态缩小:max 38sp → min 18sp, maxLines=2。展开时左下角白色 ExtraBold。
 */
@Composable
private fun AutoSizeArtistName(
    name: String,
    modifier: Modifier = Modifier,
    color: Color = Color.White,
    fontWeight: FontWeight = FontWeight.ExtraBold,
    maxLines: Int = 2,
    overflow: TextOverflow = TextOverflow.Ellipsis,
    minSp: Float = 22f,
    maxSp: Float = 38f,
    textAlign: TextAlign = TextAlign.Center,
) {
    var fontSize by remember(name) { mutableFloatStateOf(maxSp) }
    var ready by remember(name) { mutableStateOf(false) }
    Text(
        text = name,
        modifier = modifier.drawWithContent { if (ready) drawContent() },
        color = color,
        fontWeight = fontWeight,
        fontSize = fontSize.sp,
        lineHeight = (fontSize * 1.15f).sp,
        maxLines = maxLines,
        overflow = overflow,
        textAlign = textAlign,
        onTextLayout = { result ->
            if (result.hasVisualOverflow && fontSize > minSp) {
                fontSize -= 2f
                ready = false
            } else {
                ready = true
            }
        },
    )
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
        // v1.2.0 #6: Spotify 式归属——专辑只算「专辑位歌手」(首曲 artist split)含该歌手的；
        // 仅在某首 feat 曲出现、但专辑歌手位没他名字的,不算他的专辑(只是参与,不算作品)。
        // 合作专辑首曲 "A, B" split 后两者都算;周杰伦专辑首曲=周杰伦,feat 曲不影响归属。
        name in com.shiyin.music.data.MediaScanner.splitArtists(entry.value.first().artist) &&
            // v1.2.0 #3: 合集（多歌手杂烩单曲文件夹误判为专辑）不在歌手页展示——
            // 它不属于任一歌手，留在音乐库「合集」分类即可。
            classifyAlbum(entry.value) != AlbumCategory.Compilation
    }
    // v1.3.3 返回恢复:局部状态改 VM 快照初值 + onDispose 写回——下钻专辑再返回时
    // 恢复展开/滚动位;完整退出歌手页由 RootBackHandler 清快照(clearArtistUiState),
    // 下次进入从顶部+折叠开始,不残留。remember {} 初值只对首次组合生效。
    val savedUi = vm.artistUiState(name)
    var showAllAlbums by remember(name) { mutableStateOf(savedUi.showAllAlbums) }
    // v4.3: song list grows 5 at a time when the user taps 展开 — no longer a
    // single 展开→all toggle. Initial cap is 5 tracks.
    var songCap by remember(name) { mutableStateOf(savedUi.songCap) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState(
        savedUi.firstVisibleIndex, savedUi.firstVisibleOffset,
    )
    // 离开组合(下钻专辑/完整退出)立即写回快照——写回是恢复的充分条件,清理由
    // RootBackHandler 在"完整退出"时统一做,两层职责分开。
    // v1.3.3b bug 修:完整退出歌手页后快照仍被写回——退出路径(RootBackHandler/顶栏
    // 返回)先置 artistKey=null 触发重组移除 ArtistDetail,onDispose 这才跑、把
    // showAllAlbums=true 又写回快照,清了等于没清("退出再进回到专辑列表"的根因)。
    // 修:onDispose 时若 artistKey 已不是本歌手(页面已退出),不写回,让清理生效。
    androidx.compose.runtime.DisposableEffect(name) {
        onDispose {
            if (vm.artistKey == name) {
                vm.saveArtistUiState(
                    name,
                    com.shiyin.music.ui.ArtistUiState(showAllAlbums, songCap, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset),
                )
            }
        }
    }

    if (showAllAlbums) {
        // v1.3.4: 专辑栏是歌手主页内的"三级层"——返回必须先收它、回歌手主页,而不是
        // 直接清 artistKey 退到首页(RootBackHandler 感知不到这个内部状态,此前三级
        // 返回直接跳一级)。onBack 由 LazyColumn 顶栏返回键共用同一逻辑。
        BackHandler { showAllAlbums = false }
        ArtistAlbumList(
            vm = vm,
            artistName = name,
            albums = albums.associate { it.key to it.value },
            onBack = { showAllAlbums = false },
        )
        return
    }

    // v1.2.0 #6: 写真大图背景层 + scrollOffset 驱动折叠头。向下滚动时上层 sheet 上移
    // 整片盖住写真图（视差：写真滞后半速）；返回折叠恢复，歌名定格进顶栏。写真经
    // ArtistImageResolver 解析（override→cache→Discogs 等自动源），URL 存独立
    // artist_image_cache/override 表，重启/重扫/更新不丢，自动源不覆盖手选。
    // 性能：折叠量走 graphicsLayer 内 deferred 读 listState，滚动时主体不重组，故不卡。
    androidx.compose.runtime.LaunchedEffect(name) { vm.fetchArtistAvatar(name) }
    val avatarUrl = vm.artistImage(name)
    val avatarBmp = rememberCandidateArt(avatarUrl.ifBlank { null }, 360.dp)
    val density = LocalDensity.current
    // 沉浸式: 外层已去掉 statusBarsPadding, 内容画到屏幕顶 y=0; 状态栏高度用于 overlay 定位
    val sbTopPx = WindowInsets.statusBars.asPaddingValues().calculateTopPadding().let { with(density) { it.toPx() } }
    val heroMaxPx = with(density) { artistHeroH.toPx() }
    // (listState 已在上方与快照一起声明——v1.3.3 返回恢复)
    // collapseProgress: 0=展开,1=折叠; deferred 读 listState 无重组。
    // v1.2.1: 旧实现固定除以 heroH(320dp),短内容(可滚距离<heroH)滚到底 rawProgress 到不了 1,
    // 于是用 snap(spring)强拉到 1 → 1-2 首歌手"快到顶突然瞬移"、单首歌手"起点直跳终点"。
    // 改为按"实际可滚距离"映射满程,彻底删掉 snap:
    //   - 全部 item 可见(短内容)时可精确算总内容高,actualMaxScroll = max(0, 总高 - 视口)
    //   - effectiveMax = min(heroH, actualMaxScroll):长内容(非全部可见)算不出总高→回退 heroH,
    //     滚满 heroH 到顶(行为不变、丝滑);短内容满程=实际可滚距离,滚到底恰好到顶(无瞬移);
    //     单首/无专辑(不能滚)actualMaxScroll=0 → 恒 0 停展开位(无瞬移)
    val rawScrolled by remember {
        derivedStateOf {
            val first = listState.layoutInfo.visibleItemsInfo.firstOrNull()
            when {
                first != null && first.index == 0 -> (-first.offset.toFloat()).coerceAtLeast(0f)
                listState.firstVisibleItemIndex > 0 -> heroMaxPx
                else -> 0f
            }
        }
    }
    val actualMaxScroll by remember {
        derivedStateOf {
            val info = listState.layoutInfo
            val visible = info.visibleItemsInfo
            val total = info.totalItemsCount
            if (visible.isNotEmpty() && total > 0 && visible.size >= total) {
                val contentH = visible.sumOf { it.size }.toFloat() +
                    info.beforeContentPadding + info.afterContentPadding
                (contentH - info.viewportSize.height).coerceAtLeast(0f)
            } else {
                heroMaxPx
            }
        }
    }
    val effectiveMax = minOf(heroMaxPx, actualMaxScroll)
    val collapseProgress by remember {
        derivedStateOf {
            if (effectiveMax <= 0f) 0f
            else (rawScrolled / effectiveMax).coerceIn(0f, 1f)
        }
    }
    // v1.2.0 #6: 单标题一镜到底动画——只保留一个歌手名 Text,不进 LazyColumn(脱离滚动约束),
    // 用 graphicsLayer deferred 读 collapseProgress 驱动 translationX/Y + scale,从写真左下平滑位移
    // +缩放到顶栏居中。颜色 lerp 白→c.text(仅触发 Text 重绘,文字布局不变故廉价不卡)。展开
    // 中心 X 由 onGloballyPositioned 一次性测量:标题 overlay 布局位置固定,滚动中 graphicsLayer
    // 的 translation 是绘制期变换、不改布局坐标→不触发重测→不每帧重组(满足"动画中禁用实时测量")。
    val titleExpandedCenterY = heroMaxPx - with(density) { 44.dp.toPx() }
    val titleCollapsedCenterY = sbTopPx + with(density) { 12.dp.toPx() }
    var titleExpandedCenterX by remember(name) { mutableFloatStateOf(0f) }
    // ══ 固定层级(自底向上) ══
    // 1 Background(写真 y=0~heroH 固定, alpha 联动淡出)
    // 2 Content(LazyColumn 透明; contentPadding top=heroH → 内容恒从交界 heroH 处开始,短/长名一致,
    //   不再因歌手名占位高低导致短名按钮错位挡歌曲; c.bg 上滚覆盖写真)
    // 3 Button(overlay 交界, 跟内容纸同步滚 translationY=-progress*heroH, 淡出)
    // 4 Toolbar bg(渐变淡入) + Back button(黑圆淡出)
    // 5 Single Title(overlay, graphicsLayer 一镜到底)
    BoxWithConstraints(Modifier.fillMaxSize().background(c.bg).clipToBounds()) {
        val screenW = with(density) { maxWidth.toPx() }
        // v1.2.0 #6: 字号按名长+语种(CJK≈1em / latin≈0.6em)一次算到一行排完——不靠系统自动
        // 换行(长名如 Tsutomu Mayuruna Kade Eino 会被丑陋地折两行)。0.88 安全系数保守防溢出,
        // coerce[16,30]。v1.2.1: 拉丁系数 0.52→0.6(M/H/O 等宽字母实际更宽,旧值对短名如 HOYO-Mix
        // 打到 34 上限后边缘溢出裁切末尾字符),上限 34→30 进一步留余量。remember 只随 name/screenW 重算。
        val expandedFontSize = remember(name, screenW) {
            val availSp = with(density) { (screenW - 44.dp.toPx()).toSp() }.value
            val cjk = name.count { com.shiyin.music.data.normalize.CharUtil.isCjk(it.toString()) }
            val widthFactor = cjk * 1.0f + (name.length - cjk) * 0.6f
            (availSp / widthFactor * 0.88f).coerceIn(16f, 30f)
        }
        val titleScale = 16f / expandedFontSize
        // ── 1 Background: 写真(第1张纸, 固定; y=0 含状态栏; alpha 联动淡出避免内容覆盖时硬切) ──
        Box(
            Modifier.fillMaxWidth().height(artistHeroH).align(Alignment.TopCenter),
        ) {
            if (avatarBmp != null) {
                Image(bitmap = avatarBmp, contentDescription = name, modifier = Modifier.fillMaxSize().graphicsLayer { alpha = 1f - collapseProgress * 0.3f }, contentScale = ContentScale.Crop)
            } else {
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(bg, bg.copy(alpha = 0.6f))))) {}
            }
            Box(Modifier.fillMaxSize().background(
                Brush.verticalGradient(0f to Color.Transparent, 0.5f to Color.Transparent, 0.75f to Color.Black.copy(alpha = 0.35f), 1f to Color.Black.copy(alpha = 0.55f))
            )) {}
        }

        // ── 2 Content: LazyColumn(透明; contentPadding top=heroH→内容恒从 heroH 交界处开始,
        //    不再因歌手名高低导致短名按钮错位); 热门 c.bg 顶部留 52dp 给按钮下半(一半写真一半内容) ──
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(top = artistHeroH, bottom = 130.dp),
        ) {
            item {
                Column(Modifier.fillMaxWidth().background(c.bg).padding(top = 52.dp, bottom = 4.dp)) {
                    Text("热门", style = body(14f, FontWeight.Bold, c.n700), modifier = Modifier.padding(start = 16.dp, bottom = 4.dp))
                    // v1.2.0 #6: 歌曲按热度(播放次数)排序,不按识别早晚
                    val sortedTracks = artistTracks.sortedByDescending { vm.playCountFor(it.id) }
                    val displayTracks = if (sortedTracks.size > songCap) sortedTracks.take(songCap) else sortedTracks
                    displayTracks.forEachIndexed { i, t ->
                        Box(Modifier.padding(horizontal = 16.dp)) {
                            NumberedTrackRow(vm = vm, track = t, idx = i + 1, sub = t.album, onClick = { vm.playArtist(name, t.id) }, showMenu = true, showCover = true)
                        }
                    }
                    if (sortedTracks.size > songCap) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.Center) {
                            PillButton("展开", onClick = { songCap = (songCap + 5).coerceAtMost(sortedTracks.size) }, bg = null, textColor = c.text, borderColor = c.divider)
                        }
                    }
                }
            }
            if (albums.isNotEmpty()) {
                item {
                    Column(Modifier.fillMaxWidth().background(c.bg).padding(horizontal = 16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("专辑", style = body(14f, FontWeight.Bold, c.n700))
                            // v1.3.6: 靠右(自然顶到内容区右缘,与列表对齐)且不标数量
                            // (用户定调:数字删掉,更干净)。触区给足(整行右半段可点)。
                            Box(
                                Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { showAllAlbums = true }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            ) {
                                Text("展开全部", style = body(12f, FontWeight.Bold, c.a700))
                            }
                        }
                    }
                }
                item {
                    Row(
                        Modifier.fillMaxWidth().background(c.bg).horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        for ((albKey, ts) in albums) {
                            val firstT = ts.first()
                            Column(Modifier.width(106.dp).clip(RoundedCornerShape(10.dp)).clickable { vm.openAlbum(albKey) }, verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                CoverArt(firstT, 106.dp, RoundedCornerShape(8.dp), fontSize = 38, modifier = Modifier.shadowSm(RoundedCornerShape(8.dp)))
                                Text(firstT.album, style = body(12.5f, FontWeight.Bold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${ts.size} 首", style = body(11f, FontWeight.Normal, c.n600), modifier = Modifier.offset(y = (-4).dp))
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }

        // ── Button: overlay 交界(中心 heroH, 一半写真一半内容); translationY=-progress*heroH 跟内容纸同步滚; 淡出 ──
        // (写真半的 scrim/过渡带已移除——用户不希望看到阴影线)
        Box(
            Modifier.align(Alignment.TopEnd)
                .offset(y = with(density) { (heroMaxPx - 36.dp.toPx()).toDp() })
                .padding(end = 10.dp)
                .graphicsLayer {
                    val f = collapseProgress
                    alpha = (1f - f).coerceIn(0f, 1f)
                    translationY = -f * heroMaxPx
                },
        ) {
            // v1.3.2: 队列全集放 remember——artistQueue 每次调用都遍历全部专辑做归属分类,
            // 之前直接写在组合里,播放/重组时反复重算,歌手页滑动动画明显掉帧。
            val actionBarTracks = remember(artistTracks) { vm.artistQueue(name) }
            ArtistActionBar(vm = vm, name = name, tracks = actionBarTracks)
        }

        // ── 4 Toolbar bg(状态栏+toolbar 同步变色淡入) ──
        Box(
            Modifier.fillMaxWidth().height(with(density) { (sbTopPx + 48.dp.toPx()).toDp() })
                .graphicsLayer { alpha = collapseProgress }
                .background(c.bg),
        ) {}
        // ── 4 Back button(左, 始终可见; 黑圆展开折叠淡出→米白背景只留箭头; 图标白→c.text) ──
        Box(
            Modifier.align(Alignment.TopCenter).fillMaxWidth().height(48.dp)
                .offset(y = with(density) { (sbTopPx - 12.dp.toPx()).toDp() }),
        ) {
            Box(
                Modifier.align(Alignment.CenterStart).padding(start = 12.dp)
                    .size(40.dp)
                    .clickable {
                        vm.artistKey = null; vm.artistMerge = false
                        // v1.3.3 返回恢复:歌手页返回键——完整退出则清歌手快照(下次进入
                        // 从顶部开始)+ 回原 tab。
                        if (vm.albumKey == null && vm.plId == null) vm.clearArtistUiState()
                        vm.restoreTabIfDrillFullyClosed()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(Modifier.fillMaxSize()
                    .graphicsLayer { alpha = (1f - collapseProgress).coerceIn(0f, 1f) }
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.25f))
                ) {}
                OIcon(Lucide.ChevronLeft, 24.dp, androidx.compose.ui.graphics.lerp(Color.White, c.text, collapseProgress))
            }
        }

        // ── 5 Single Title: 单 Text overlay, graphicsLayer 一镜到底 ──
        // 布局位置=展开(写真左下, Box 中心 heroH-36); graphicsLayer deferred 读 collapseProgress:
        // translationY→顶栏中心, translationX→屏幕水平中心, scale→折叠字号; 全程 alpha=1 无交叉淡入。
        Box(
            Modifier.fillMaxWidth().height(56.dp)
                .offset(y = with(density) { (titleExpandedCenterY - 28.dp.toPx()).toDp() })
                .padding(start = 16.dp, end = 12.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                name,
                color = androidx.compose.ui.graphics.lerp(Color.White, c.text, collapseProgress),
                fontWeight = FontWeight.Bold,
                fontSize = expandedFontSize.sp,
                lineHeight = (expandedFontSize * 1.1f).sp,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Visible,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .onGloballyPositioned { coords ->
                        val cx = coords.positionInWindow().x + coords.size.width / 2f
                        if (cx > 0f) titleExpandedCenterX = cx
                    }
                    .graphicsLayer {
                        val p = collapseProgress
                        translationY = (titleCollapsedCenterY - titleExpandedCenterY) * p
                        // v1.2.1: titleExpandedCenterX 未测量(≤0,首帧/onGloballyPositioned 未触发)时
                        // 不平移——否则 translationX=screenW/2*p 会把标题左边缘推到屏幕中线,
                        // 右半截被外层 clipToBounds 裁切(短名如 HOYO-Mix 末尾 x 丢失即此因)。
                        translationX = if (titleExpandedCenterX > 0f) (screenW / 2f - titleExpandedCenterX) * p else 0f
                        // animation.core 的 Float lerp 是 internal 不可访问,手写等价插值: 1+(target-1)*p
                        val s = 1f + (titleScale - 1f) * p
                        scaleX = s
                        scaleY = s
                    },
            )
        }
    }
    // v1.2.0 #6: 管理归属改 AlertDialog（参考专辑编辑样式，完成键恒显）
    if (vm.artistMerge) ArtistMergeDialog(vm = vm, name = name)
    if (vm.artistPhotoPickerFor == name) ArtistPhotoPickerDialog(vm = vm, name = name)
}

// ── artist action bar (圆形播放 + 随机播放 + ⋯ 菜单) ───────────────────────────
// 管理归属/选择写真等收进 ⋯ 菜单，避免操作栏杂乱。
@Composable
private fun ArtistActionBar(vm: MainViewModel, name: String, tracks: List<Track>, modifier: Modifier = Modifier) {
    val c = LocalOrganic.current
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier.padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 更多菜单: 纯图标(橙色);弹主题化 AlertDialog(非白底 DropdownMenu)——选择写真/管理归属。
        Box(contentAlignment = Alignment.Center) {
            Box(
                Modifier.size(44.dp).clickable { menuOpen = true },
                contentAlignment = Alignment.Center,
            ) { OIcon(Lucide.MoreVertical, 22.dp, c.accent) }
            if (menuOpen) {
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { menuOpen = false },
                    containerColor = c.surface,
                    title = { Text("更多操作", style = body(15f, FontWeight.ExtraBold, c.text)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            MenuAction("选择写真", Lucide.User) { menuOpen = false; vm.openArtistPhotoPicker(name) }
                            MenuAction("管理归属", Lucide.Users) { menuOpen = false; vm.artistMerge = !vm.artistMerge }
                        }
                    },
                    confirmButton = {},
                )
            }
        }
        // 随机播放: 纯图标,橙色(统一风格)。队列=歌手全集且带 artist: key(resyncQueue 保持范围)
        Box(
            Modifier.size(44.dp).clickable { vm.playRandom(tracks.map { it.id }, "artist:$name") },
            contentAlignment = Alignment.Center,
        ) { OIcon(Lucide.Shuffle, 22.dp, c.accent) }
        // 主播放键: 实心圆形强调色(放大,视觉主按钮,最右); shadow 悬浮立体感(胶布贴)
        // v1.3.2: 走 playArtist(歌手全集队列),不是全局 play(全库队列)
        Box(
            Modifier.size(52.dp).shadow(8.dp, CircleShape).clip(CircleShape).background(c.accent)
                .clickable { tracks.firstOrNull()?.let { vm.playArtist(name, it.id) } },
            contentAlignment = Alignment.Center,
        ) { OIcon(Lucide.Play, 24.dp, c.bg) }
    }
}

// ── artist photo picker (选择写真) ─────────────────────────────────────────
// 并行取所有源候选写真(各源 1 张),用户选一张→setArtistImageOverride 写覆盖(永久,
// 旧覆盖被替换)→artistImages 自动刷新;「恢复自动」=清覆盖回退自动源。
@Composable
private fun ArtistPhotoPickerDialog(vm: MainViewModel, name: String) {
    val c = LocalOrganic.current
    val candidates = vm.artistImageCandidates
    // v1.2.0 #6: 选本地文件作写真(拷到 app 内部存储持久化)
    val localPicker = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia()
    ) { uri -> if (uri != null) { vm.setArtistImageFromFile(name, uri); vm.artistPhotoPickerFor = null } }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.artistPhotoPickerFor = null },
        containerColor = c.surface,
        title = { Text("选择写真", style = body(15f, FontWeight.ExtraBold, c.text)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // v1.2.0 #6: 该歌手旗下专辑封面(横向滚动,可选用作写真)
                val albumCoverTracks = remember(name) {
                    vm.albumsMap().entries.filter { e ->
                        name in com.shiyin.music.data.MediaScanner.splitArtists(e.value.first().artist) &&
                        classifyAlbum(e.value) != AlbumCategory.Compilation
                    }.map { it.value.first() }.distinctBy { it.albumId }
                }
                if (albumCoverTracks.isNotEmpty()) {
                    Row(
                        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        albumCoverTracks.forEach { t ->
                            CoverArt(t, 64.dp, RoundedCornerShape(8.dp), fontSize = 22, modifier = Modifier.shadowSm(RoundedCornerShape(8.dp)).clickable {
                                vm.setArtistImageFromAlbumCover(name, t); vm.artistPhotoPickerFor = null
                            })
                        }
                    }
                }
                // 候选写真(各源并行增量返回)+ 搜索进度
                val pending = vm.artistPickerPending
                val total = vm.artistPickerTotal
                if (pending > 0) {
                    Text("搜索中… 已完成 ${total - pending}/${total} 源", style = body(12f, FontWeight.Normal, c.n500), modifier = Modifier.padding(vertical = 4.dp))
                }
                if (candidates.isEmpty() && pending > 0) {
                    Text("正在搜索候选写真…", style = body(13f, FontWeight.Normal, c.n500), modifier = Modifier.padding(vertical = 8.dp))
                } else if (candidates.isEmpty()) {
                    Text("未找到候选写真，可粘贴链接或选本地文件。", style = body(13f, FontWeight.Normal, c.n500), modifier = Modifier.padding(vertical = 8.dp))
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.heightIn(max = 260.dp).verticalScroll(rememberScrollState())) {
                        candidates.forEach { img ->
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable {
                                    vm.setArtistImageOverride(name, img.url); vm.artistPhotoPickerFor = null
                                }.padding(4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                val bmp = rememberCandidateArt(img.url, 72.dp)
                                Box(Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)).background(c.n200), contentAlignment = Alignment.Center) {
                                    if (bmp != null) Image(bmp, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else OIcon(Lucide.User, 28.dp, c.n400)
                                }
                                Column(Modifier.weight(1f)) {
                                    Text(img.source, style = body(13f, FontWeight.Bold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    if (img.imageType.isNotBlank()) Text(img.imageType, style = body(11f, FontWeight.Normal, c.n500), maxLines = 1)
                                }
                            }
                        }
                    }
                }
                // 链接行(底部):➕选本地 + 链接框(短)+ 保存
                var linkUrl by remember { mutableStateOf("") }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(c.n200).clickable {
                            localPicker.launch(androidx.activity.result.PickVisualMediaRequest(androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        contentAlignment = Alignment.Center,
                    ) { Text("➕", style = body(18f, FontWeight.Normal, c.text)) }
                    androidx.compose.material3.OutlinedTextField(
                        value = linkUrl,
                        onValueChange = { linkUrl = it },
                        label = { Text("图片链接", style = body(12f, FontWeight.Normal, c.n500)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = body(13f, FontWeight.Normal, c.text),
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedTextColor = c.text, unfocusedTextColor = c.text, cursorColor = c.accent,
                            focusedBorderColor = c.accent, unfocusedBorderColor = c.n400,
                            focusedLabelColor = c.accent, unfocusedLabelColor = c.n500,
                        ),
                    )
                    androidx.compose.material3.TextButton(onClick = {
                        if (linkUrl.isNotBlank()) { vm.setArtistImageOverride(name, linkUrl.trim()); vm.artistPhotoPickerFor = null }
                    }) { Text("保存", style = body(14f, FontWeight.Bold, c.accent)) }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { vm.clearArtistImageOverride(name); vm.artistPhotoPickerFor = null }) {
                Text("恢复自动", style = body(14f, FontWeight.Normal, c.n600))
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { vm.artistPhotoPickerFor = null }) {
                Text("取消", style = body(14f, FontWeight.Normal, c.n600))
            }
        },
    )
}

// ── artist merge dialog（参考专辑编辑 AlertDialog 样式；完成键恒显，无选择时置灰）─
@Composable
private fun ArtistMergeDialog(vm: MainViewModel, name: String) {
    val c = LocalOrganic.current
    val candidates = vm.artistsMap().keys.filter { it != name }
    var selected by remember { mutableStateOf(setOf<String>()) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.artistMerge = false },
        containerColor = c.surface,
        title = { Text("合并歌手到「$name」", style = body(15f, FontWeight.ExtraBold, c.text)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "勾选其他歌手（可多选），他们名下的歌曲与专辑会全部归入「$name」。同一歌手被识别成多个名字时用（如 fujiikaze / 藤井风）。可在「设置 · 歌手合并记录」撤销。",
                    style = body(12f, FontWeight.Normal, c.n600).copy(lineHeight = 18.sp),
                )
                if (candidates.isEmpty()) {
                    Text("（暂无其他歌手可合并）", style = body(13f, FontWeight.Normal, c.n500))
                } else {
                    Column(
                        Modifier.fillMaxWidth().heightIn(max = 320.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
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
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = {
                    selected.forEach { vm.mergeArtist(it, name) }
                    selected = emptySet()
                    vm.artistMerge = false
                },
                enabled = selected.isNotEmpty(),
            ) {
                Text(
                    if (selected.isNotEmpty()) "完成（${selected.size}）" else "完成",
                    style = body(14f, FontWeight.Bold, if (selected.isNotEmpty()) c.accent else c.n400),
                )
            }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { vm.artistMerge = false }) {
                Text("取消", style = body(14f, FontWeight.Normal, c.n600))
            }
        },
    )
}
