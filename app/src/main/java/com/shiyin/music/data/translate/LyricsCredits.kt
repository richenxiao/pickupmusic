package com.shiyin.music.data.translate

/**
 * v1.5.0 A56「歌词制作人行略过翻译」的**纯逻辑**识别器。
 *
 * 需求原话：作词/作曲/编曲/`XX 演奏` 等**与歌词内容无关**的行跳过翻译
 *（默认开）。附带解决一个真 bug：切换歌词源时，若上一个源没写制作人、
 * 而新源第 1~2 行正是 `作词：/作曲：`，译文行数与原文对不上会把歌词正文
 * "搬"到制作人那一行 —— 跳过这些行后行号映射自然对齐。
 *
 * 2026-10-22 大扩围（用户：张震岳《浪人的…》片尾制作名单把翻译按钮"顶"出来，
 * 随后又提示"中英混排不需要翻译"）：真实平台歌词的制作人段落是**中英双语标签**
 * （`詞 Lyricist：`、`錄音師（鼓、低音吉他）Recording Engineer (…)：`、
 * `杜比全景聲混音師 Dolby Atmos Mixing Engineer｜`、`導演 DIRECTOR｜` …），
 * 旧实现只认"标签完全相等"，几乎全部漏识 ⇒ 英文署名被计入拉丁字母占比。
 * 现在支持：
 *  1. 标签**去括号注记后**再比对（`錄音室（銅管）` → `錄音室`）；
 *  2. 中英双语标签里的**英文角色词**（词边界匹配 lyricist/composer/engineer/
 *     director/photography/studio…）；
 *  3. 中文角色词**后缀**（`杜比全景聲混音師`、`歌詞英文翻譯` …）；
 *  4. 只有标签没有内容的长行（`錄音師（銅管）Recording Engineer (Brass)：`，
 *     值在下一行）也算制作人行；
 *  5. 版权/℗/© 行。
 *
 * **防误伤是重点**：必须同时满足"行首是已知标签 + 分隔符"或"英文 by/at 形态"；
 * 歌词正文里偶然出现"作曲""录音"二字（如 `我为你作曲`）不受影响。
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
        "op", "sp", "策划", "策劃", "制作", "製作", "音乐制作", "音樂製作", "影像制作", "影像製作",
        "录音棚", "錄音棚", "录音助理", "錄音助理", "制作助理", "製作助理", "制作总监", "製作總監",
        "总监", "總監", "导演", "導演", "副导", "副導", "制片", "製片", "场务", "場務",
        "摄影师", "攝影師", "灯光师", "燈光師", "美术指导", "美術指導", "造型", "化妆", "化妝",
        "发型", "髮型", "服装", "服裝", "剪辑", "剪接", "调光", "調光", "字体", "字體",
        "特别感谢", "特別感謝", "感谢", "感謝", "版权", "版權", "发行公司", "發行公司",
        "唱片公司", "厂牌", "廠牌", "经纪", "經紀", "宣传", "宣傳", "营销", "營銷",
        "商务", "商務", "法务", "法務", "行政", "财务", "財務", "助唱", "声乐", "聲樂",
        // 日语
        "作詞者", "作曲者", "編曲者", "プロデュース", "ミックス", "マスタリング",
        "レコーディング", "ボーカル", "演奏者", "歌詞", "訳詞", "ディレクター",
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

    /** 英文"by/at 形态"标签（无冒号，如 `Lyrics by John Smith` / `Recorded at ... Studio`）。 */
    private val BY_LABELS = listOf(
        "lyrics by", "music by", "composed by", "arranged by", "written by",
        "produced by", "mixed by", "mastered by", "recorded by", "engineered by",
        "recorded at", "mixed at", "mastered at", "produced at", "engineered at",
        "thanks to", "special thanks",
    )

    /** 复合标签的后缀（如「钢琴演奏」「和声编写」「杜比全景聲混音師」——以角色词结尾即算）。 */
    private val ROLE_SUFFIXES = listOf(
        "演奏", "演唱", "编曲", "編曲", "作曲", "作词", "作詞", "混音", "母带", "母帶",
        "吉他", "贝斯", "貝斯", "钢琴", "鋼琴", "键盘", "鍵盤", "和声", "和聲", "伴唱",
        "混音師", "混音师", "錄音師", "录音师", "錄音室", "录音室", "錄音助理", "录音助理",
        "製作助理", "制作助理", "製作總監", "制作总监", "製作人", "制作人",
        "編寫", "编写", "編程", "编程", "編程", "翻譯", "翻译", "統籌", "统筹",
        "導演", "导演", "副導", "副导", "攝影", "摄影", "剪接", "剪輯", "剪辑", "調光", "调光",
        "設計", "设计", "造型", "妝髮", "妆发", "燈光", "灯光", "製片", "制片",
        "特別感謝", "特别感谢", "感謝", "感谢", "電工", "电工", "司機", "司机",
        "薩克斯風", "萨克斯风", "低音吉他", "電吉他", "电吉他", "鼓技師", "鼓技师",
        "管樂", "管乐", "演員", "演员", "車主", "车主",
    )

    /** 中英双语标签里的英文角色词（**词边界**匹配，避免 hope 里的 op 之类误伤）。 */
    private val EN_ROLE_WORDS = listOf(
        "lyricist", "composer", "arranger", "arrangement", "producer", "production",
        "executive", "director", "assistant", "coordinator", "manager", "supervisor",
        "engineer", "engineering", "studio", "recorded", "recording", "mixed", "mixing",
        "mastered", "mastering", "programming", "programmer", "arranger",
        "vocals", "vocal", "chorus", "backing", "guitar", "bass", "drums", "drum",
        "keyboard", "piano", "strings", "violin", "cello", "saxophone", "sax", "trumpet",
        "trombone", "flute", "brass", "percussion", "tech", "technician",
        "photography", "photographer", "camera", "cam", "focus puller", "gaffer", "grip",
        "lighting", "electric", "make up", "makeup", "stylist", "styling", "design",
        "logotype", "editor", "edit", "colorist", "thanks", "actor", "actress",
        "extras", "drone", "underwater", "equipment", "transportation", "project manager",
        "art director", "production executive", "production assistant", "set coordinator",
        "copyright", "publisher", "publishing", "label", "management", "booking",
        "legal", "marketing", "publicity", "dolby", "atmos", "remix", "remixed",
        "composed", "written", "lyrics", "music", "all instruments", "additional",
    )

    /** 英文角色词正则（词边界 + 忽略大小写，编译一次）。 */
    private val EN_ROLE_REGEX = Regex(
        "(?i)\\b(" + EN_ROLE_WORDS.joinToString("|") { Regex.escape(it) } + ")\\b",
    )

    /** 括号注记（中英括号都要，用于"标签去注记后再比对"）。 */
    private val BRACKETS = Regex("""[（(【\[][^）)】\]]*[）)】\]]""")

    /** 制作人行一般很短；超过这个长度基本是歌词正文（防误伤长句）。
     *  2026-10-22 上调：双语长标签（`錄音師（鼓、低音吉他、吉他）Recording Engineer (Drums.Bass Guitar.Electric Guitar)：`）本身就很长。 */
    private const val MAX_LEN = 160

    /**
     * 该行是否属于"制作人/演职人员"行（应跳过翻译）。
     *
     * 命中形态（其余一律不算，防误伤歌词正文）：
     * 1. `标签：名字`（中/日/中英双语，全角半角冒号/竖线都可）
     * 2. `【标签】名字` / `标签（注记）：名字`
     * 3. `Lyrics by 名字` / `Recorded at 工作室`（英文 by/at 形态，无冒号）
     * 4. 只有标签没有内容（值在下一行）
     * 5. 版权行（© / ℗ / Copyright / All rights reserved / Unauthorized …）
     */
    fun isCreditLine(line: String): Boolean {
        val s = line.trim()
        if (s.isEmpty() || s.length > MAX_LEN) return false
        val stripped = s.trimStart { it in LEADING_DECOR }
        // 形态 5：版权行
        val lower = stripped.lowercase()
        if (stripped.startsWith("©") || stripped.startsWith("℗") ||
            lower.startsWith("copyright") || lower.startsWith("all rights reserved") ||
            lower.startsWith("unauthorized") || lower.startsWith("(p)") ||
            lower.startsWith("(c)")
        ) {
            return true
        }
        // 形态 1/2：标签 + 分隔符（有内容或只有标签都算）
        // ⚠️ 分两条判定：
        //  a) 原行 —— `【作词】友成空` 依赖 `】` 当分隔符；
        //  b) **去掉括号注记后的行** —— 双语长标签 `錄音師（鼓、低音吉他、吉他）Recording
        //     Engineer (…)：` 里 `）` 会抢先命中分隔符，导致余下的英文角色词看不到。
        val candidates = listOf(stripped, BRACKETS.replace(stripped, ""))
        for (cand in candidates) {
            val sepIdx = cand.indexOfFirst { it in SEPARATORS }
            if (sepIdx > 0) {
                val head = cand.substring(0, sepIdx).trim()
                if (hasLabel(head)) return true
            }
        }
        // 形态 3：英文 by/at 形态
        for (lbl in BY_LABELS) if (lower.startsWith("$lbl ")) return true
        return false
    }

    /**
     * 标签是否已知：
     *  - 去括号注记后完全相等；
     *  - 以角色词结尾（复合标签）；
     *  - 双语标签里含**英文角色词**（词边界）；
     *  - 中文标签开头 + 余下只是英文/符号（`詞 Lyricist`）。
     */
    private fun hasLabel(head: String): Boolean {
        val h = head.lowercase().trim()
        if (h.isEmpty()) return false
        val hb = BRACKETS.replace(h, "").trim().trim(' ', '-', '–', '—', '·', '・', '_')
        if (hb in LABELS) return true
        if (ROLE_SUFFIXES.any { hb.endsWith(it) && hb.length <= it.length + 10 }) return true
        if (EN_ROLE_REGEX.containsMatchIn(h)) return true
        // 中文标签开头 + 余下无中文（英文/符号）：`詞 lyricist`、`曲 composer`
        for (lbl in LABELS) {
            if (lbl.length >= 2 && lbl.any { it.code > 0x2E80 } && hb.startsWith(lbl)) {
                val rest = hb.substring(lbl.length)
                if (rest.isBlank() || rest.none { it.code in 0x3400..0x9FFF }) return true
            }
        }
        return false
    }
}
