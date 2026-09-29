package com.shiyin.music.data.search

/**
 * v1.3.10: 假名 → 罗马字（用于搜索匹配，不做展示）。
 *
 * 动机:库里日文歌名是假名/汉字,用户却常常按罗马字找——「カブトムシ」vs「kabutomushi」
 * 在纯字符串归一里永远对不上,于是"库里明明有却说没有"。这不是模型能猜出来的东西,
 * 必须由搜索层确定性地转换。
 *
 * 范围与取舍:
 *  - 只转换**假名**(片假名先折成平假名);汉字无从确定读音,原样保留——汉字歌名的
 *    罗马字查询要等"读音索引"(Kuromoji)接入,本版不做(报告里明确列出该缺口)。
 *  - 促音「っ」→ 双写下一音节首辅音(がっこう → gakkou);长音符「ー」→ 重复上一个元音
 *    (コーヒー → koohii)。拗音按二连表处理(きょ → kyo)。
 *  - 输出一律小写、保留非假名字符(拉丁/数字原样),便于与 [FuzzySearch.normalize] 的结果
 *    直接做 contains。
 */
object KanaRomaji {

    /** 二连拗音/外来音。必须先于单字表尝试(き + ゃ 不能拆成 ki + ya)。 */
    private val DIGRAPHS: Map<String, String> = mapOf(
        "きゃ" to "kya", "きゅ" to "kyu", "きょ" to "kyo",
        "しゃ" to "sha", "しゅ" to "shu", "しょ" to "sho",
        "ちゃ" to "cha", "ちゅ" to "chu", "ちょ" to "cho",
        "にゃ" to "nya", "にゅ" to "nyu", "にょ" to "nyo",
        "ひゃ" to "hya", "ひゅ" to "hyu", "ひょ" to "hyo",
        "みゃ" to "mya", "みゅ" to "myu", "みょ" to "myo",
        "りゃ" to "rya", "りゅ" to "ryu", "りょ" to "ryo",
        "ぎゃ" to "gya", "ぎゅ" to "gyu", "ぎょ" to "gyo",
        "じゃ" to "ja", "じゅ" to "ju", "じょ" to "jo",
        "びゃ" to "bya", "びゅ" to "byu", "びょ" to "byo",
        "ぴゃ" to "pya", "ぴゅ" to "pyu", "ぴょ" to "pyo",
        "ふぁ" to "fa", "ふぃ" to "fi", "ふぇ" to "fe", "ふぉ" to "fo",
        "てぃ" to "ti", "でぃ" to "di", "うぃ" to "wi", "うぇ" to "we",
    )

    /** 单字表(平假名)。促音「っ」与长音「ー」另行处理。 */
    private val SINGLES: Map<Char, String> = mapOf(
        'あ' to "a", 'い' to "i", 'う' to "u", 'え' to "e", 'お' to "o",
        'か' to "ka", 'き' to "ki", 'く' to "ku", 'け' to "ke", 'こ' to "ko",
        'さ' to "sa", 'し' to "shi", 'す' to "su", 'せ' to "se", 'そ' to "so",
        'た' to "ta", 'ち' to "chi", 'つ' to "tsu", 'て' to "te", 'と' to "to",
        'な' to "na", 'に' to "ni", 'ぬ' to "nu", 'ね' to "ne", 'の' to "no",
        'は' to "ha", 'ひ' to "hi", 'ふ' to "fu", 'へ' to "he", 'ほ' to "ho",
        'ま' to "ma", 'み' to "mi", 'む' to "mu", 'め' to "me", 'も' to "mo",
        'や' to "ya", 'ゆ' to "yu", 'よ' to "yo",
        'ら' to "ra", 'り' to "ri", 'る' to "ru", 'れ' to "re", 'ろ' to "ro",
        'わ' to "wa", 'を' to "o", 'ん' to "n",
        'が' to "ga", 'ぎ' to "gi", 'ぐ' to "gu", 'げ' to "ge", 'ご' to "go",
        'ざ' to "za", 'じ' to "ji", 'ず' to "zu", 'ぜ' to "ze", 'ぞ' to "zo",
        'だ' to "da", 'ぢ' to "ji", 'づ' to "zu", 'で' to "de", 'ど' to "do",
        'ば' to "ba", 'び' to "bi", 'ぶ' to "bu", 'べ' to "be", 'ぼ' to "bo",
        'ぱ' to "pa", 'ぴ' to "pi", 'ぷ' to "pu", 'ぺ' to "pe", 'ぽ' to "po",
        // 小写假名(单独出现时按元音处理)
        'ぁ' to "a", 'ぃ' to "i", 'ぅ' to "u", 'ぇ' to "e", 'ぉ' to "o",
        'ゃ' to "ya", 'ゅ' to "yu", 'ょ' to "yo", 'ゎ' to "wa", 'ゔ' to "vu",
    )

    private const val KATAKANA_START = 0x30A1
    private const val KATAKANA_END = 0x30F6
    private const val KATAKANA_TO_HIRAGANA = 0x60

    /** 片假名折平假名(ー/・等符号不在范围内,原样返回)。 */
    private fun toHiragana(c: Char): Char =
        if (c.code in KATAKANA_START..KATAKANA_END) (c.code - KATAKANA_TO_HIRAGANA).toChar() else c

    /** 上一个音节末尾的元音(长音符用);没有则返回 null。 */
    private fun lastVowel(sb: StringBuilder): Char? {
        for (i in sb.length - 1 downTo 0) {
            val ch = sb[i]
            if (ch in "aeiou") return ch
            if (ch.isLetter()) return null
        }
        return null
    }

    /** 转罗马字(小写)。非假名字符原样保留(小写化)。 */
    fun toRomaji(text: String): String {
        val sb = StringBuilder(text.length * 2)
        var i = 0
        // 促音「っ」标记:下一个音节的辅音要双写
        var sokuon = false
        while (i < text.length) {
            val h = toHiragana(text[i])
            when {
                h == 'っ' -> { sokuon = true; i++ }
                h == 'ー' -> {
                    sb.append(lastVowel(sb) ?: ""); i++
                }
                else -> {
                    val pair = if (i + 1 < text.length) "$h${toHiragana(text[i + 1])}" else ""
                    val pairRomaji = DIGRAPHS[pair]
                    val single = SINGLES[h]
                    when {
                        pairRomaji != null -> {
                            if (sokuon) { sb.append(pairRomaji[0]); sokuon = false }
                            sb.append(pairRomaji); i += 2
                        }
                        single != null -> {
                            if (sokuon) { sb.append(single[0]); sokuon = false }
                            sb.append(single); i++
                        }
                        // 汉字/拉丁/数字/标点:原样保留(拉丁能顺带支持「英文标题 vs 假名查询」)
                        else -> { sokuon = false; sb.append(text[i].lowercaseChar()); i++ }
                    }
                }
            }
        }
        return sb.toString()
    }

    /** 一个字段的所有可搜写法(归一化原文 + 罗马字)。搜索层用它兜「假名↔罗马字」。 */
    fun variantsOf(field: String): List<String> = listOf(field, toRomaji(field))
}
