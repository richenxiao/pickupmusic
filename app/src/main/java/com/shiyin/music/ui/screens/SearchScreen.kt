package com.shiyin.music.ui.screens

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.shiyin.music.MainViewModel
import com.shiyin.music.data.search.FuzzySearch
import com.shiyin.music.ui.components.CoverArt
import com.shiyin.music.ui.components.OIcon
import com.shiyin.music.ui.components.TrackRow
import com.shiyin.music.ui.components.body
import com.shiyin.music.ui.components.coverPalette
import com.shiyin.music.ui.components.heading
import com.shiyin.music.ui.components.shadowSm
import com.shiyin.music.ui.icons.Lucide
import com.shiyin.music.ui.theme.Caprasimo
import com.shiyin.music.ui.theme.Figtree
import com.shiyin.music.ui.theme.LocalOrganic

@Composable
fun SearchScreen(vm: MainViewModel) {
    val c = LocalOrganic.current
    val query = vm.q
    // v4.3: fuzzy search — 繁简互通 / 空格灵活 / 编辑距离容错，结果按相关度排序。
    // lib() 带缓存，列表实例不变时 remember 直接命中缓存，输入过程零重复计算。
    val lib = vm.lib()
    val results = remember(query, lib) {
        if (query.isBlank()) emptyList() else FuzzySearch.search(lib, query)
    }
    // v1.2.0 #5: 搜专辑/EP/合集命中折叠成一张专辑卡（不显示专辑歌曲），单曲按曲目行
    // （歌名+歌手）。按 albumId 分组取最高分代表作；单曲与专辑卡按相关度混排。
    val displayItems = remember(results) {
        val grouped = LinkedHashMap<String, FuzzySearch.SearchHit>()
        val singles = ArrayList<FuzzySearch.SearchHit>()
        for (hit in results) {
            val t = hit.track
            val type = vm.albumTypeFor(t.albumId)
            if (t.albumId > 0 && (type == "Album" || type == "EP" || type == "Compilation")) {
                grouped.putIfAbsent("aid:${t.albumId}", hit)
            } else {
                singles.add(hit)
            }
        }
        val items = ArrayList<Pair<FuzzySearch.SearchHit, Boolean>>()
        singles.forEach { items.add(it to false) }
        grouped.values.forEach { items.add(it to true) }
        items.sortedByDescending { it.first.score }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 22.dp, bottom = 130.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item(key = "title") { Text("搜索", style = heading(30)) }
        item(key = "field") {
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(999.dp))
                    .background(c.surface)
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OIcon(Lucide.Search, 18.dp, c.n600)
                BasicTextField(
                    value = vm.q,
                    onValueChange = { vm.q = it },
                    textStyle = body(15f, FontWeight.Normal, c.text).copy(fontFamily = Figtree),
                    cursorBrush = SolidColor(c.accent),
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                        imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                    ),
                    keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                        onSearch = { if (vm.q.isNotBlank()) vm.commitSearch() },
                    ),
                    modifier = Modifier.weight(1f),
                    decorationBox = { inner ->
                        Box {
                            if (vm.q.isEmpty()) {
                                Text("搜索歌曲、歌手", style = body(15f, FontWeight.Normal, c.n500))
                            }
                            inner()
                        }
                    },
                )
                if (vm.q.isNotEmpty()) {
                    Box(
                        Modifier.size(24.dp).clip(CircleShape).clickable { vm.q = "" },
                        contentAlignment = Alignment.Center,
                    ) { OIcon(Lucide.Close, 16.dp, c.n600) }
                }
            }
        }

        if (query.isNotBlank()) {
            item { Text("${displayItems.size} 个结果", style = body(13f, FontWeight.Normal, c.n600)) }
            items(displayItems, key = { it.first.track.id }) { (hit, isAlbum) ->
                val t = hit.track
                if (isAlbum) {
                    // v1.2.0 #5: 专辑卡——专辑名 + 分类·歌手，点击进专辑（不播单曲、不列曲目）
                    val typeStr = vm.albumTypeFor(t.albumId)
                    val typeCn = when (typeStr) { "EP" -> "EP"; "Compilation" -> "合集"; else -> "专辑" }
                    val artistDisplay = if (typeStr == "Compilation") "多位歌手" else t.artist
                    // v1.2.0: 专辑名命中区间高亮（如「伤心早餐店」里的「伤心」）
                    val albumAnnotated = remember(t.album, hit.albumRanges, c) {
                        buildAnnotatedString {
                            append(t.album)
                            for (r in hit.albumRanges) {
                                if (r.first >= 0 && r.last < t.album.length) {
                                    addStyle(SpanStyle(color = c.a600, fontWeight = FontWeight.ExtraBold), r.first, r.last + 1)
                                }
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .clickable {
                                vm.commitSearch()
                                vm.openAlbum(com.shiyin.music.data.albumKeyOf(t.album, t.artist, t.albumId))
                            }
                            .padding(horizontal = 4.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoverArt(t, 56.dp, RoundedCornerShape(8.dp), fontSize = 22, modifier = Modifier.shadowSm(RoundedCornerShape(8.dp)))
                        Column(Modifier.weight(1f)) {
                            Text(albumAnnotated, style = body(15f, FontWeight.Bold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("$typeCn · $artistDisplay", style = body(12.5f, FontWeight.Normal, c.n600), modifier = Modifier.padding(top = 2.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        OIcon(Lucide.ChevronRight, 17.dp, c.n400)
                    }
                } else {
                    // v1.2.0 #5: 单曲行只显歌手名（去 folder）
                    TrackRow(
                        track = t,
                        isCurrent = t.id == vm.player.currentId,
                        isPlaying = vm.player.isPlaying,
                        subtitle = t.artist,
                        onClick = { vm.commitSearch(); vm.play(t.id) },
                        onLongClick = { vm.trackMenuFor = t.id },
                        coverSize = 42.dp,
                        coverRadius = 13.dp,
                        titleHighlights = hit.titleRanges,
                        subtitleHighlights = hit.artistRanges,
                        trailing = { com.shiyin.music.ui.screens.TrackMenuButton(vm, t) },
                        isHiddenTrack = vm.isHidden(t.id),
                    )
                }
            }
        } else {
            if (vm.searchHistory.isNotEmpty()) {
                item {
                    Row(
                        Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("搜索历史", style = body(14f, FontWeight.ExtraBold, c.text))
                        Text(
                            "清除",
                            style = body(12.5f, FontWeight.Normal, c.n600),
                            modifier = Modifier.clickable { vm.clearSearchHistory() },
                        )
                    }
                }
                item {
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(end = 4.dp),
                    ) {
                        items(vm.searchHistory) { term ->
                            Row(
                                Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(c.surface)
                                    .clickable { vm.q = term }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                OIcon(Lucide.History, 14.dp, c.n600)
                                Text(term, style = body(13f, FontWeight.Medium, c.text), maxLines = 1)
                            }
                        }
                    }
                }
            }
            item { Text("按文件夹浏览", style = body(14f, FontWeight.ExtraBold, c.text), modifier = Modifier.padding(top = 4.dp)) }
            val folderTiles = vm.foldersMap().entries.toList()
            items(folderTiles.withIndex().chunked(2)) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(11.dp), modifier = Modifier.padding(bottom = 0.dp)) {
                    row.forEach { (i, e) ->
                        val (bg, fg) = coverPalette(i)
                        Box(Modifier.weight(1f)) {
                            SearchTile(e.key, "${e.value.size} 个文件", bg, fg, circle = false) { vm.q = e.key }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            item { Text("歌手", style = body(14f, FontWeight.ExtraBold, c.text), modifier = Modifier.padding(top = 6.dp)) }
            val artistTiles = vm.artistsMap().entries.toList()
            items(artistTiles.withIndex().chunked(2)) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                    row.forEach { (i, e) ->
                        val (bg, fg) = coverPalette(i + 1)
                        Box(Modifier.weight(1f)) {
                            SearchTile(
                                e.key, "${e.value.size} 首", bg, fg,
                                circle = true, initial = e.key.first().uppercase(),
                            ) { vm.openArtist(e.key) }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SearchTile(
    name: String,
    sub: String,
    bg: Color,
    fg: Color,
    circle: Boolean,
    initial: String? = null,
    onClick: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(88.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(bg)
            .clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 13.dp)) {
            Text(name, style = body(14.5f, FontWeight.ExtraBold, fg), maxLines = 1)
            Text(
                sub,
                style = body(11.5f, FontWeight.SemiBold, fg.copy(alpha = 0.75f)),
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        // tilted decorative block, bottom-right
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 14.dp, y = 16.dp)
                .size(64.dp)
                .rotate(22f)
                .clip(if (circle) CircleShape else RoundedCornerShape(16.dp))
                .background(Color.White.copy(alpha = 0.25f)),
            contentAlignment = Alignment.Center,
        ) {
            if (initial != null) {
                Text(
                    initial,
                    fontFamily = Caprasimo,
                    style = body(26f, FontWeight.Normal, fg.copy(alpha = 0.7f)).copy(fontFamily = Caprasimo),
                    modifier = Modifier.rotate(-22f),
                )
            } else {
                OIcon(Lucide.Folder, 26.dp, fg.copy(alpha = 0.7f))
            }
        }
    }
}
