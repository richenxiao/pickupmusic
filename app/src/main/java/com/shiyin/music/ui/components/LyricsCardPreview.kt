package com.shiyin.music.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * v1.3.x morph 过渡 P0(设计文档 §3.4-1):歌词卡片预览的共享可组合项——
 * 卡片本体与 morph overlay 的"起点态"共用同一实现,起点帧与真卡片逐像素
 * 一致,无重绘差异。
 *
 * 分两层参数化:
 *  - [LyricsCardHeaderRow]: 头行(「歌词」标签 + 来源),卡片静止态用;
 *  - [LyricsCardPreview]: 预览窗口([visibleLines] 行),[fontSize]/[lineHeight]
 *    可插值——overlay 展开动画期间按 progress 从卡片态插值到歌词本态。
 *
 * 颜色/排版参数从调用方透传(卡片与 overlay 各自的取色上下文同源)。
 */

@Composable
fun LyricsCardHeaderRow(
    sourceLabel: String?,
    dimC: Color,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("歌词", style = body(11.5f, FontWeight.ExtraBold, dimC).copy(letterSpacing = 1.8.sp))
        if (sourceLabel != null) {
            Text(sourceLabel, style = body(11f, FontWeight.Bold, dimC))
        }
    }
}

/**
 * 播放页歌词卡片的预览窗口。
 *
 * v1.3.x 三项修正（用户实测反馈）：
 * 1. **焦点行推进带滚动动画**。原实现用 `peekStart` 换窗口——整组文字瞬间替换，
 *    视觉上是"一帧切换"。改用 LazyColumn + animateScrollToItem，焦点行保持在
 *    可视区第 2 位，推进时窗口平滑滚动。
 * 2. **颜色按"唱了没"分档**。原实现除焦点行外全部同一灰度（fg×0.42）——已唱的
 *    和没唱的混在一起。现在：焦点行 100%，已唱 62%（降档但可读），没唱到的
 *    才 40%（灰）。
 * 3. 预览窗口变高（去掉「展开歌词」胶囊腾出的空间 + 卡片底部富余，见调用方），
 *    显示行数从 4 → [visibleLines]（**默认 5**，以参数默认值为准；调用方目前不传参）。
 *
 * 纯展示：`userScrollEnabled = false`——卡片里塞一个可拖动的列表会把展开歌词本
 * 的手势吃掉；展开仍然是点卡片。
 */
@Composable
fun LyricsCardPreview(
    lines: List<String>,
    activeIndex: Int,
    fg: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
    visibleLines: Int = 5,
) {
    val listState = rememberLazyListState()
    val density = LocalDensity.current
    // 换歌时重置"首帧直跳"标记——首帧 scrollToItem(无动画),之后每次推进走动画,
    // 避免进入页面/切歌瞬间从顶部滚过来一段很长的滚动。
    var didInit by remember(lines) { mutableStateOf(false) }
    LaunchedEffect(activeIndex) {
        if (activeIndex < 0) return@LaunchedEffect
        val target = (activeIndex - 1).coerceAtLeast(0)
        if (!didInit) {
            listState.scrollToItem(target)
            didInit = true
        } else if (listState.firstVisibleItemIndex != target) {
            listState.animateScrollToItem(target)
        }
    }
    LazyColumn(
        state = listState,
        userScrollEnabled = false,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 4.dp)
            .height(
                with(density) { lineHeight.toDp() } * visibleLines + 2.dp * (visibleLines - 1),
            ),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(lines) { i, text ->
            val a = when {
                i == activeIndex -> 1f
                i < activeIndex -> 0.62f
                else -> 0.40f
            }
            Text(
                text,
                // v1.3.x 第11项:关掉字体自带 padding(includeFontPadding)。
                // 行高设了 28sp，但字形盒因 font padding 比行高更高 → 容器按"行数×行高"
                // 算出来的高度装不下整数行，末行下面会**露出下一条歌词的一条黑边**
                // (用户:"第八行漏了一点点，黑黑的字")。关掉 padding 后每行正好等于行高，
                // 高度精确，不再露半行。
                style = body(fontSize.value, FontWeight.ExtraBold, fg.copy(alpha = a))
                    .copy(
                        lineHeight = lineHeight,
                        platformStyle = androidx.compose.ui.text.PlatformTextStyle(
                            includeFontPadding = false,
                        ),
                    ),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
