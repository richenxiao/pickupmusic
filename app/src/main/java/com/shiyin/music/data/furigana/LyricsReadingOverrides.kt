package com.shiyin.music.data.furigana

/**
 * 歌词高频特殊读法纠错小表（V1.1 准确率层）。
 *
 * 【职责边界——硬约束，勿违反】
 * 本表**只是**第三方词典（JMdict/Kuromoji）在歌词语境下无法自动确定读法时的
 * **少量高置信度偏好层**：
 *   - 只收录「绝大多数歌词都这么读、且属于 JMdict CONFLICT（多合法读法）或 Kuromoji
 *     默认会读错」的高频词。
 *   - 形态素分析、活用、复合词切分、助词处理等**全部由 Kuromoji 负责**，本表不参与
 *     分词，只做 surface 级读法偏好。
 *   - **不得演变成主识别引擎**：当某词本表无法确定时，应走 Song Override 或直接
 *     No Reading，**而不是往本表加词**。
 *   - **规模封顶 ~30 词**。新增词须满足：① 高频出现在歌词；② JMdict 多读法或
 *     Kuromoji 默认读错；③ 歌词语境读法高度稳定（不因歌而异）。不满足则不加。
 *
 * 【数据来源与许可】自撰（词读法是事实、不可版权；不从 JMdict/Wiktionary 抄录以
 * 免继承 CC BY-SA），MIT 许可随闭源 App 分发。
 *
 * 【不收录示例】何（なに/なん 因歌而异 → Song Override）、方言读法 → Song Override。
 */
object LyricsReadingOverrides {

    // surface → 歌词常用读法（平假名）。手动维护，勿从外部词典抄录。
    private val table: Map<String, String> = mapOf(
        // 计数/人物（多读法：ふたり vs ににん 等，歌词几乎都读 ふたり/ひとり）
        "二人" to "ふたり",
        "一人" to "ひとり",
        // 时间（多读法，歌词常用 きょう/あした/きのう）
        "今日" to "きょう",
        "明日" to "あした",
        "昨日" to "きのう",
        // 人物/称呼（多读法，歌词常用 おとな/あなた/かなた）
        "大人" to "おとな",
        "貴方" to "あなた",
        "貴女" to "あなた",
        "彼方" to "かなた",
        // 指示代词（多读法/未收录，歌词常用 こちら/ここ/そちら 等）
        "此方" to "こちら",
        "其方" to "そちら",
        "何方" to "どちら",
        "此処" to "ここ",
        "其処" to "そこ",
        "彼処" to "あそこ",
        // 年岁
        "二十歳" to "はたち",
        // 常见疑问词（多读法，歌词常用 いつ/なぜ）
        "何時" to "いつ",
        "何故" to "なぜ",
        // 常见复合（IPADIC 可能拆解/误读，歌词常用 ほんとう）
        "本当" to "ほんとう",
    )

    /** 命中返回歌词常用读法，否则 null。 */
    fun reading(surface: String): String? = table[surface]

    /** 是否收录。 */
    fun contains(surface: String): Boolean = surface in table
}
