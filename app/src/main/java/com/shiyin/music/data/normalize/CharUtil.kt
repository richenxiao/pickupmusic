package com.shiyin.music.data.normalize

/**
 * v1.2.1: 共享字符判断/归一化工具,消除 4 处 CJK 检测不一致(百度源只查 0x3000..0x9FFF,
 * ArtCache/字体还查 0xFF00..0xFFEF 全角,导致全角拉丁名"ＡＢＣ"被各处分类不一致)。
 *
 * 不可触碰:PaletteExtractor 评分逻辑、PlayerScreen 封面渲染(本工具不涉及)。
 */
object CharUtil {
    /** CJK/全角判定:CJK 统一表意(0x3000..0x9FFF,含中文标点/平假名/片假名/汉字)+ 全角形式(0xFF00..0xFFEF)。
     *  全角拉丁(ＡＢＣ)算 CJK 上下文(该走原产地区域搜索、CJK 字号),与 ArtCache/LibraryScreen 既有口径一致。 */
    fun isCjk(s: String): Boolean =
        s.any { it.code in 0x3000..0x9FFF || it.code in 0xFF00..0xFFEF }

    /** 全角标点→半角(CJK 输入法常出全角逗号"，"等)。覆盖 0xFF01..0xFF5E 通用 + CJK 标点特例(，、／＆（)）。 */
    fun normalizeFullWidthPunctuation(s: String): String {
        val out = StringBuilder(s.length)
        for (c in s) {
            out.append(when (c) {
                '，', '、' -> ','
                '。' -> '.'
                '／' -> '/'
                '＆' -> '&'
                '（' -> '('
                '）' -> ')'
                '　' -> ' '
                '：' -> ':'
                '；' -> ';'
                '？' -> '?'
                '！' -> '!'
                else -> if (c.code in 0xFF01..0xFF5E) (c.code - 0xFEE0).toChar() else c
            })
        }
        return out.toString()
    }
}
