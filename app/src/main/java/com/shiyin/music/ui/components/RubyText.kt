package com.shiyin.music.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 一段振假名文本：surface 为显示原文，reading 为该 surface 对应的假名注音。
 * reading 为 null 表示该段无需注音（纯假名 / 标点 / 送假名）。该模型由分词器
 * （M2: Kuromoji）以"汉字段 + 送假名段"预切分形式直接产出——即每段 surface
 * 的 reading 与整段对齐，渲染时标注在 surface 上方，无需再做送假名剥除。
 *
 * startOffset：该段 surface 在原文中的起始字符偏移（V1.1 Song Override 按"出现位置"
 * 存取用，使同一 surface 在歌里多次出现、读法不同时可分别设——如「何」有的なに
 * 有的なん）。RubyText 渲染不读此字段。
 */
data class RubySegment(val surface: String, val reading: String?, val startOffset: Int = 0)

/**
 * 自绘振假名（ruby / furigana）文本组件，用于「歌词本」全屏页的日文歌词。
 *
 * 渲染策略：用 Compose TextMeasurer 对整行做正常段落排版（免费得到正确的日文
 * shaping / 自动换行 / 居中），再用 Canvas 把假名逐段画在对应汉字上方。
 *
 * 布局正确性（v1.1 修复"换行后振假名脱离正文"）：
 *   - 每个 segment 用零宽不换行符 U+2060（WORD JOINER）包裹，使 Skia 不会在
 *     segment 内部断行——换行只发生在 segment 之间。每个带振假名的文本单元作为
 *     不可分割的布局单元参与测量与换行，换行后假名跟随其 surface 整体落到新行。
 *     原文偏移↔测量文本偏移用 offMap 互转。
 *   - 全部坐标（getBoundingBox / getLineForOffset）取自单一 baseLayout，杜绝
 *     "探测布局"与"绘制布局"因行高不同在边界字符上换行漂移。
 *
 * 宽注音处理（v1.1 重做：废弃竖排堆叠，改 shrink-to-fit）：
 *   单字汉字对应多假名时，水平假名可能比汉字宽、居中后侵入相邻假名范围。此前用过
 *   "竖排堆叠"补丁——实测占纵向空间过大、违背日语排版习惯，已废弃。现改为：当某段
 *   假名（默认字号）宽于其可用宽度（到下一段注音的距离）时，按比例缩小该段假名字号
 *   直到横向塞下（下限 [rubyShrinkMin]），始终水平排列在汉字正上方，绝不竖排。
 *
 * 行高：带振假名的行 lineHeight = 文字自然行高(baseVal×1.3 兜底) + 单层 ruby 槽
 * (rubySize + gap)，Bottom 对齐把 ruby 槽压在文字上方。单一 ruby 槽（无堆叠），
 * 所有带振假名行行高一致、稳定。不带振假名的行沿用调用方 lineHeight，行高紧凑。
 */
@Composable
fun RubyText(
    text: String,
    segments: List<RubySegment>,
    style: TextStyle,
    modifier: Modifier = Modifier,
    rubySizeRatio: Float = 0.46f,
    rubyGap: Dp = 2.dp,
    rubyShrinkMin: Float = 0.55f,
    color: Color = style.color,
    textAlign: TextAlign = TextAlign.Start,
) {
    // 安全检查：segments 拼接必须等于 text。若不一致（produceState 竞态、
    // splitOkurigana 边界问题等），安全降级为整行无注音——绝不崩溃。
    if (segments.joinToString("") { it.surface } != text) {
        val safeSegments = listOf(RubySegment(text, null, 0))
        // 用 safeSegments 继续渲染（无注音，但显示正文）
        // 直接 fallback 到普通 Text 渲染
        androidx.compose.material3.Text(text, style = style, modifier = modifier)
        return
    }
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current

    val baseVal = if (style.fontSize.isSpecified) style.fontSize.value else 16f
    val rubyVal = baseVal * rubySizeRatio
    val rubySize = rubyVal.sp
    val gapPx = with(density) { rubyGap.toPx() }
    val rubyStyle = remember(style, rubyVal, color) {
        // Trim.Both 让假名布局高度=字号本身（剥掉字体 ascent/descent 额外量），
        // 否则 kanaH≈字号×1.2 会溢出 ruby 槽顶顶到上一行。
        style.copy(
            fontSize = rubySize,
            color = color,
            lineHeight = rubySize,
            lineHeightStyle = LineHeightStyle(
                alignment = LineHeightStyle.Alignment.Center,
                trim = LineHeightStyle.Trim.Both,
            ),
        )
    }

    BoxWithConstraints(modifier) {
        val maxW = with(density) { maxWidth.toPx() }.toInt().coerceAtLeast(1)
        val hasRuby = segments.any { it.reading != null }
        // 重新测量键：输入文本 + 各段假名 + 字号 + 槽位 + 颜色 + 对齐 + 可用宽度 + 是否带振假名
        val key = buildString {
            append(text); append('|')
            segments.forEach { append(it.reading ?: ""); append(',') }
            append('|'); append(baseVal); append(':'); append(rubyVal); append(':')
            append(gapPx); append(':'); append(color.value.toLong()); append(':')
            append(textAlign.toString()); append(':'); append(maxW); append(':'); append(hasRuby)
        }
        val measured = remember(key) {
            // 1. 各段在原文 text 中的 [start, end) 偏移 + 默认字号假名水平布局
            var off = 0
            val ranges = ArrayList<IntArray>(segments.size)
            val defaultKanaLayouts = ArrayList<TextLayoutResult?>(segments.size)
            for (seg in segments) {
                val start = off
                val end = off + seg.surface.length
                ranges.add(intArrayOf(start, end))
                defaultKanaLayouts.add(
                    if (seg.reading != null) {
                        measurer.measure(
                            seg.reading, rubyStyle,
                            constraints = Constraints(maxWidth = Int.MAX_VALUE),
                        )
                    } else null,
                )
                off = end
            }

            // 1b. 构造「各 segment 原子化」的测量文本：用零宽不换行 U+2060（WORD JOINER）
            //     包裹每个 segment，使 Skia 不会在 segment 内部断行——换行只发生在
            //     segment 之间。每个带振假名的文本单元作为不可分割的布局单元参与换行，
            //     换行后假名跟随其 surface 整体落到新行，杜绝脱离。offMap[原文offset]=测量offset。
            val WJ = '⁠'
            val measureText = StringBuilder(text.length + segments.size * 2)
            val offMap = IntArray(text.length + 1)
            var oOrig = 0
            for (idx in segments.indices) {
                val segStart = ranges[idx][0]
                while (oOrig < segStart) {
                    offMap[oOrig] = measureText.length
                    measureText.append(text[oOrig])
                    oOrig++
                }
                measureText.append(WJ)
                val segLen = segments[idx].surface.length
                // v1.2.0: 带注音段字间也插 WJ，防 CJK 字间断行把多字 surface（如「二人」）
                // 拆到两行致注音脱离（原 v1.1 只在段边界插 WJ，挡不住段内字间断行）。
                // 无注音段不加，保留长送假名/标点串的内部断行能力免得整段溢出。
                val atomic = segments[idx].reading != null
                for (c in 0 until segLen) {
                    offMap[oOrig] = measureText.length
                    measureText.append(text[oOrig])
                    oOrig++
                    if (atomic && c < segLen - 1) measureText.append(WJ)
                }
                measureText.append(WJ)
            }
            while (oOrig <= text.length) {
                offMap[oOrig] = measureText.length
                if (oOrig < text.length) measureText.append(text[oOrig])
                oOrig++
            }
            val mText = measureText.toString()
            val mRanges = Array(segments.size) { i ->
                intArrayOf(offMap[ranges[i][0]], offMap[ranges[i][1]])
            }

            // 2. 单一 baseLayout（带单层 ruby 槽）。换行骨架与绘制坐标全部从此取。
            val rubySizePx = with(density) { rubySize.toPx() }
            val singleRubySp = with(density) { (rubySizePx / density.density).sp }
            val lineGapSp = with(density) { gapPx / density.density }
            val baseStyle = if (hasRuby) {
                style.copy(
                    color = color,
                    textAlign = textAlign,
                    lineHeight = (baseVal * 1.3f + singleRubySp.value + lineGapSp).sp,
                    lineHeightStyle = LineHeightStyle(
                        alignment = LineHeightStyle.Alignment.Bottom,
                        trim = LineHeightStyle.Trim.None,
                    ),
                )
            } else {
                style.copy(color = color, textAlign = textAlign)
            }
            val baseLayout = measurer.measure(
                mText, baseStyle,
                softWrap = true,
                constraints = Constraints(maxWidth = maxW),
            )

            // 3. 逐注音段判定 shrink 比例：若默认字号假名宽于其可用宽度（到下一段注音
            //    的距离）则按比例缩小到 [rubyShrinkMin, 1]，始终水平。预量缩小后的假名。
            val kanaLayouts = ArrayList<TextLayoutResult?>(segments.size)
            for (i in segments.indices) {
                val hLayout = defaultKanaLayouts[i]
                val reading = segments[i].reading
                if (hLayout == null || reading == null) {
                    kanaLayouts.add(null)
                    continue
                }
                val mStart = mRanges[i][0]
                val lineStart = baseLayout.getLineForOffset(mStart)
                val surfaceLeft = baseLayout.getBoundingBox(mStart).left
                // 可用宽度 = 到同一下一段注音段左边界（或行右边界）的距离 - 间隙
                var nextLeft = baseLayout.getLineRight(lineStart)
                for (j in (i + 1) until segments.size) {
                    if (segments[j].reading == null) continue
                    val nStart = mRanges[j][0]
                    if (baseLayout.getLineForOffset(nStart) == lineStart) {
                        nextLeft = baseLayout.getBoundingBox(nStart).left
                        break
                    } else break
                }
                val available = (nextLeft - surfaceLeft - gapPx).coerceAtLeast(0f)
                val kanaW = hLayout.size.width.toFloat()
                val scale = if (kanaW > available) {
                    (available / kanaW).coerceIn(rubyShrinkMin, 1f)
                } else 1f
                kanaLayouts.add(
                    if (scale >= 0.999f) hLayout
                    else {
                        val shrunkStyle = rubyStyle.copy(fontSize = (rubyVal * scale).sp)
                        measurer.measure(
                            reading, shrunkStyle,
                            constraints = Constraints(maxWidth = Int.MAX_VALUE),
                        )
                    },
                )
            }
            Measured(baseLayout, mRanges, kanaLayouts)
        }
        val baseLayout = measured.baseLayout
        val mRanges = measured.mRanges
        val kanaLayouts = measured.kanaLayouts

        val h = with(density) { baseLayout.size.height.toDp() }
        val rubySlotPx = with(density) { rubySize.toPx() } + gapPx
        Canvas(Modifier.fillMaxWidth().height(h)) {
            drawText(baseLayout, color = color, topLeft = Offset.Zero)
            if (!hasRuby) return@Canvas
            for (i in segments.indices) {
                val kana = kanaLayouts[i] ?: continue
                val mStart = mRanges[i][0]
                val mEnd = mRanges[i][1]
                val lineStart = baseLayout.getLineForOffset(mStart)
                val lineEnd = baseLayout.getLineForOffset((mEnd - 1).coerceAtLeast(mStart))
                val runLeft: Float
                val runRight: Float
                if (lineStart == lineEnd) {
                    runLeft = baseLayout.getBoundingBox(mStart).left
                    runRight = baseLayout.getBoundingBox((mEnd - 1).coerceAtLeast(mStart)).right
                } else {
                    // 防御：segment 原子化后不应跨行；若仍跨行（极长单段超行宽），注音标首行
                    val firstLineLast = (baseLayout.getLineEnd(lineStart) - 1).coerceAtLeast(mStart)
                    runLeft = baseLayout.getBoundingBox(mStart).left
                    runRight = baseLayout.getBoundingBox(firstLineLast).right
                }
                val lineTop = baseLayout.getLineTop(lineStart)
                val cx = (runLeft + runRight) / 2f
                val kanaW = kana.size.width.toFloat()
                val kanaH = kana.size.height.toFloat()
                // 水平居中于 surface，clamp 到画布内保证完整可见（防最左边假名被裁半边）
                val x = (cx - kanaW / 2f).coerceIn(0f, (maxW.toFloat() - kanaW).coerceAtLeast(0f))
                // 垂直居中于 ruby 槽（文字上方）
                val y = lineTop + ((rubySlotPx - kanaH) / 2f).coerceAtLeast(0f)
                drawText(kana, color = color, topLeft = Offset(x, y))
            }
        }
    }
}

private class Measured(
    val baseLayout: TextLayoutResult,
    val mRanges: Array<IntArray>,
    val kanaLayouts: List<TextLayoutResult?>,
)
