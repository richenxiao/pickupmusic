package com.shiyin.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
 *  - [LyricsCardPreview]: 预览 4 行文字,[fontSize]/[lineHeight] 可插值——
 *    overlay 展开动画期间按 progress 从卡片态(19sp/28sp)插值到歌词本态
 *    (26sp/36sp),每帧只重组 4 个 Text。
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

@Composable
fun LyricsCardPreview(
    lines: List<String>,
    activeIndex: Int,
    peekStart: Int,
    fg: Color,
    fontSize: TextUnit,
    lineHeight: TextUnit,
) {
    Column(Modifier.padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        lines.drop(peekStart).take(4).forEachIndexed { j, text ->
            val isActive = peekStart + j == activeIndex
            Text(
                text,
                style = body(fontSize.value, FontWeight.ExtraBold, if (isActive) fg else fg.copy(alpha = 0.42f))
                    .copy(lineHeight = lineHeight),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 「展开歌词」胶囊(卡片静止态的尾行,overlay 起点态同样需要)。 */
@Composable
fun LyricsCardExpandPill(btnBg: Color, fg: Color) {
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
        Box(
            Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(btnBg)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) { Text("展开歌词", style = body(12.5f, FontWeight.Bold, fg)) }
    }
}
