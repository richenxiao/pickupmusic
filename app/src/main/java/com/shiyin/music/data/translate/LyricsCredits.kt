package com.shiyin.music.data.translate

/**
 * v1.5.0 A56「歌词制作人行略过翻译」的**纯逻辑**识别器。
 *
 * 用户原话：作词/作曲/编曲/`XX 演奏` 等**与歌词内容无关**的行跳过翻译
 *（默认开）。附带解决一个真 bug：切换歌词源时，若上一个源没写制作人、
 * 而新源第 1~2 行正是 `作词：/作曲：`，译文行数与原文对不上会把歌词正文
 * "搬"到制作人那一行 —— 跳过这些行后行号映射自然对齐。
 *
 * **防误伤是重点**：只在「行首是已知制作人标签 + 后面紧跟分隔符 + 冒号后有内容」
 * 时才判为制作人行；歌词正文里偶然出现"作曲"二字（如 `我为你作曲`）不受影响。
 */
object LyricsCredits {

    /** 已知制作人标签（小写比较）。中文简体/繁体 + 日语 + 英语多词。 */
    private val LABELS = setOf(
        // 中文（简/繁）
        "作词", "作詞", "作曲", "编曲", "編曲", "填词", "填詞", "词", "詞", "曲", "歌词", "歌詞",
        "制作人", "製作人", "监制", "監製", "出品", "发行", "發行", "企划", "企劃", "统筹", "統籌",
        "混音", "母带", "母帶", "录音", "錄音", "录音室", "錄音室", "录音师", "錄音師",
        "和声", "和聲", "伴唱", "合声", "合聲", "配唱",
        "吉他", "贝斯", "貝斯", "鼓", "键盘", "鍵盤", "钢琴", "鋼琴", "弦乐", "弦樂",
        "打击乐", "打擊樂", "小提琴", "大提琴", "萨克斯", "薩克斯", "长笛", "長笛", "二胡", "琵琶",
        "演唱", "演奏", "原唱", "原曲", "原著", "改编", "改編", "监唱", "監唱",
        "封面", "设计", "設計", "插画", "插畫", "翻译", "翻譯", "校对", "校對", "字幕",
        "op", "sp", "策划", "策劃",
        // 日语
        "作詞者", "作曲者", "編曲者", "プロデュース", "ミックス", "マスタリング",
        "レコーディング", "ボーカル", "演奏者", "歌詞", "訳詞",
        // 英语（多词按整体比较）
        "lyrics by", "music by", "composed by", "arranged by", "written by",
        "produced by", "producer", "mixed by", "mixing", "mastered by", "mastering",
        "recorded by", "engineered by", "programmed by", "vocals", "vocal", "chorus",
        "backing vocals", "guitar", "bass", "drums", "keyboard", "piano", "strings",
        "violin", "cello", "saxophone", "trumpet", "flute", "rap", "all instruments",
        "words", "music", "lyrics",
    )

    /** 分隔符：全角/半角冒号、竖线，以及**闭括号**（`【作词】名字` 形态）。 */
    private val SEPARATORS = charArrayOf('：', ':', '｜', '|', '】', ']', '）', ')')

    /** 行首装饰（只剥**开**括号与符号；闭括号留给分隔符判定，否则 `【作词】x` 会被剥成 `作词x`）。 */
    private const val LEADING_DECOR = "【[（）。-–—・·#*※ \t　"

    /** 英文"by 形态"标签（无冒号，如 `Lyrics by John Smith`）。 */
    private val BY_LABELS = listOf(
        "lyrics by", "music by", "composed by", "arranged by", "written by",
        "produced by", "mixed by", "mastered by", "recorded by", "engineered by",
    )

    /** 复合标签的后缀（如「钢琴演奏」「和声编写」——以角色词结尾即算）。 */
    private val ROLE_SUFFIXES = listOf(
        "演奏", "演唱", "编曲", "編曲", "作曲", "作词", "作詞", "混音", "母带", "母帶",
        "吉他", "贝斯", "貝斯", "钢琴", "鋼琴", "键盘", "鍵盤", "和声", "和聲", "伴唱",
    )

    /** 制作人行一般很短；超过这个长度基本是歌词正文（防误伤长句）。 */
    private const val MAX_LEN = 60

    /**
     * 该行是否属于"制作人/演职人员"行（应跳过翻译）。
     *
     * 三种命中形态（其余一律不算，防误伤歌词正文）：
     * 1. `标签：名字`（中/日，全角半角冒号都可，如 `作词：友成空`）
     * 2. `【标签】名字`（剥掉包裹括号后同上）
     * 3. `Lyrics by 名字`（英文 by 形态，无冒号）
     * 复合标签（`钢琴演奏`）按"以角色词结尾"识别。
     */
    fun isCreditLine(line: String): Boolean {
        val s = line.trim()
        if (s.isEmpty() || s.length > MAX_LEN) return false
        val stripped = s.trimStart { it in LEADING_DECOR }
        // 形态 1/2：标签 + 分隔符 + 内容
        val sepIdx = stripped.indexOfFirst { it in SEPARATORS }
        if (sepIdx > 0) {
            val head = stripped.substring(0, sepIdx).trim().lowercase()
            if (hasLabel(head) && stripped.length > sepIdx + 1) return true
        }
        // 形态 3：英文 by 形态
        val lower = stripped.lowercase()
        for (lbl in BY_LABELS) if (lower.startsWith("$lbl ")) return true
        return false
    }

    /** 标签是否已知：完全相等，或（复合标签）以角色词结尾。 */
    private fun hasLabel(head: String): Boolean {
        if (head.isEmpty()) return false
        if (head in LABELS) return true
        return ROLE_SUFFIXES.any { head.endsWith(it) && head.length <= it.length + 6 }
    }
}
