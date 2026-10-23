package com.shiyin.music.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiyin.music.ui.theme.LocalOrganic

/**
 * v1.5.0: **轻量 Markdown 渲染**（更新弹层用）。
 *
 * 背景：发现更新时，弹层直接显示 GitHub Release 正文 —— 那是 markdown 源码
 * （`## ✨ 新增功能`、`**伴唱（BETA）**`、`- 条目`），原样贴进 `Text` 会把记号
 * 一并显示出来，真机观感相当乱。
 *
 * 这里只做**本项目实际会用到的那一小撮语法**，不做通用 Markdown：
 *  - `## 标题` → 小节标题
 *  - 整行加粗 `**小标题**` → 分组标题
 *  - `- ` / `* ` 开头 → 条目（圆点 + 悬挂缩进）
 *  - 行内 `**粗体**` / `` `代码` `` → 富文本片段
 *  - `---` → 分隔（渲染为空行）
 * 解析是**纯函数**，渲染在 [MarkdownLiteText]；解析有单测。
 */
object MarkdownLite {

    sealed interface Block {
        /** `## 标题`。 */
        data class Section(val title: String) : Block
        /** 整行加粗的小标题（如 `**伴唱（BETA）**`）。 */
        data class Group(val title: String) : Block
        /** `- 条目`。 */
        data class Bullet(val text: String) : Block
        /** 普通段落。 */
        data class Paragraph(val text: String) : Block
    }

    /** 行内片段：是否加粗 / 是否等宽（`` `code` ``）。 */
    data class Span(val text: String, val bold: Boolean = false, val mono: Boolean = false)

    fun parse(markdown: String): List<Block> {
        val out = mutableListOf<Block>()
        var inCodeFence = false
        for (raw in markdown.lines()) {
            val line = raw.trim()
            // 代码围栏整体忽略（Release 正文里没有，但模型输出可能带）
            if (line.startsWith("```")) { inCodeFence = !inCodeFence; continue }
            if (inCodeFence) continue
            when {
                line.isEmpty() || line == "---" || line == "***" -> Unit // 空行/分隔线不产生块
                line.startsWith("## ") -> out += Block.Section(line.removePrefix("## ").trim())
                line.startsWith("# ") -> out += Block.Section(line.removePrefix("# ").trim())
                line.startsWith("### ") -> out += Block.Group(line.removePrefix("### ").trim())
                line.startsWith("- ") || line.startsWith("* ") || line.startsWith("· ") ->
                    out += Block.Bullet(line.drop(2).trim())
                // 整行加粗（且括号外没有别的字）→ 分组标题
                line.length > 4 && line.startsWith("**") && line.endsWith("**") &&
                    !line.removePrefix("**").removeSuffix("**").contains("**") ->
                    out += Block.Group(line.removePrefix("**").removeSuffix("**").trim())
                // 形如「1. xxx」的编号条目也当条目处理
                line.matches(Regex("""\d+[.、)]\s+.*""")) ->
                    out += Block.Bullet(line.replaceFirst(Regex("""^\d+[.、)]\s+"""), ""))
                else -> out += Block.Paragraph(line)
            }
        }
        return out
    }

    /**
     * 行内 → 片段序列。识别 `**加粗**` 与 `` `等宽` ``；其余原样。
     * 不处理嵌套（本项目的来源文本用不到）。
     */
    fun spans(text: String): List<Span> {
        val out = mutableListOf<Span>()
        var i = 0
        val sb = StringBuilder()
        fun flush() { if (sb.isNotEmpty()) { out += Span(sb.toString()); sb.clear() } }
        while (i < text.length) {
            when {
                text.startsWith("**", i) -> {
                    val end = text.indexOf("**", i + 2)
                    if (end > i) { flush(); out += Span(text.substring(i + 2, end), bold = true); i = end + 2 }
                    else { sb.append(text[i]); i++ }
                }
                text[i] == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i) { flush(); out += Span(text.substring(i + 1, end), mono = true); i = end + 1 }
                    else { sb.append(text[i]); i++ }
                }
                // 链接 [文字](url) → 只留文字
                text[i] == '[' -> {
                    val close = text.indexOf(']', i)
                    val open = if (close > 0) text.indexOf('(', close) else -1
                    val end = if (open == close + 1) text.indexOf(')', open) else -1
                    if (close > 0 && end > 0) { flush(); out += Span(text.substring(i + 1, close)); i = end + 1 }
                    else { sb.append(text[i]); i++ }
                }
                else -> { sb.append(text[i]); i++ }
            }
        }
        flush()
        return out
    }

    /** 段落行里若只有行内记号（没有块级语义），去掉 `**` 以免在正文里露出来。 */
    private fun stripInlineOnly(line: String): String =
        line.replace("**", "").replace("`", "")
}

/**
 * 把 [MarkdownLite] 解析出的块渲染成 Compose 文本。
 *
 * 尺寸按"弹层里读更新日志"的场景定：小节标题 13sp ExtraBold、分组 12.5sp Bold、
 * 正文/条目 12sp、行高 18sp —— 与「关于」页内嵌的更新日志同一套观感，两处不打架。
 */
@Composable
fun MarkdownLiteText(
    markdown: String,
    modifier: Modifier = Modifier,
    textColor: Color = LocalOrganic.current.n700,
) {
    val c = LocalOrganic.current
    val blocks = remember(markdown) { MarkdownLite.parse(markdown) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(5.dp)) {
        blocks.forEach { b ->
            when (b) {
                is MarkdownLite.Block.Section -> Text(
                    b.title,
                    style = body(13f, FontWeight.ExtraBold, c.text),
                    modifier = Modifier.padding(top = 8.dp),
                )
                is MarkdownLite.Block.Group -> Text(
                    b.title,
                    style = body(12.5f, FontWeight.Bold, c.text),
                    modifier = Modifier.padding(top = 3.dp),
                )
                is MarkdownLite.Block.Bullet -> Row(
                    Modifier.padding(start = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Top,
                ) {
                    Text("·", style = body(12f, FontWeight.Bold, c.n600))
                    MarkdownInline(b.text, textColor, Modifier.weight(1f))
                }
                is MarkdownLite.Block.Paragraph ->
                    MarkdownInline(b.text, textColor, Modifier.fillMaxWidth())
            }
        }
    }
}

/** 一行内联文本：`**粗体**` / `` `等宽` `` 转成富文本，其余原样。 */
@Composable
private fun MarkdownInline(text: String, color: Color, modifier: Modifier = Modifier) {
    val c = LocalOrganic.current
    val spans = remember(text) { MarkdownLite.spans(text) }
    val styled = buildAnnotatedString {
        spans.forEach { s ->
            val style = when {
                s.bold -> SpanStyle(fontWeight = FontWeight.Bold, color = c.text)
                s.mono -> SpanStyle(fontFamily = FontFamily.Monospace, color = c.n800)
                else -> SpanStyle(color = color)
            }
            withStyle(style) { append(s.text) }
        }
    }
    Text(
        styled,
        style = body(12f, FontWeight.Normal, color).copy(lineHeight = 18.sp),
        modifier = modifier,
    )
}
