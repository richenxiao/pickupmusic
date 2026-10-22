package com.shiyin.music.data.search

import com.shiyin.music.data.Track

/**
 * v4.3: 模糊搜索（typo-tolerant search）。
 *
 * 归一化管线：小写 → 全角转半角 → 去全部空格 → 繁体转简体（单字映射）。
 * 匹配分级：整串相等 > 子串包含（前缀额外加分）> token 顺序匹配（精确或
 * Damerau-Levenshtein 编辑距离容错，阈值仿 Meilisearch/Elasticsearch AUTO：
 * 短词从严、长词从宽、硬上限 2 个编辑）。
 * 所有命中区间均映射回原始字符串坐标，供 UI 高亮。
 */
object FuzzySearch {

    // ── 繁体 → 简体 常用字映射（音乐/日常高频字，零依赖单字表） ──────────
    private val TRAD_TO_SIMPLE: Map<Char, Char> by lazy {
        val pairs = (
            "愛爱門门問问間间閒闲開开關关閉闭風风雲云電电車车馬马鳥鸟魚鱼龍龙鳳凤華华國国學学习习書书画画" +
            "樂乐队队團团會会來来时時说說话话語语誰谁個个們们動动體体點点頭头顯显現现發发達达財财買买賣卖貴贵" +
            "費费賞赏進进遠远邊边還还這这那那從从眾众雙双對对錯错難难雜杂離离雖虽覺觉聖圣賢贤處处態态幫帮" +
            "辦办變变賓宾並并補补幣币標标參参殘残倉仓測测層层產产長长徹彻陳陈稱称遲迟齒齿蟲虫衝冲醜丑觸触" +
            "傳传創创詞词辭辞帶带單单當当黨党導导燈灯敵敌讀读賭赌頓顿斷断惡恶兒儿爾尔罰罚範范飛飞廢废豐丰" +
            "馮冯婦妇復复複复負负賦赋該该蓋盖趕赶剛刚鋼钢給给宮宫溝沟構构購购顧顾掛挂觀观廣广歸归國国過过" +
            "漢汉號号橫横紅红後后懷怀壞坏歡欢環环換换黃黄匯汇繪绘夥伙獲获禍祸機机積积極极級级幾几計计記记" +
            "際际繼继濟济夾夹價价堅坚檢检簡简將将講讲獎奖腳脚較较階阶節节結结僅仅盡尽緊紧驚惊經经徑径競竞" +
            "淨净舊旧舉举據据絕绝軍军凱凯顆颗課课庫库寬宽虧亏擴扩蘭兰藍蓝覽览類类禮礼裏里歷历厲厉麗丽聯联" +
            "連连臉脸練练涼凉兩两療疗臨临鄰邻靈灵劉刘樓楼錄录陸陆綠绿亂乱論论輪轮羅罗邏逻麥麦滿满貓猫麼么" +
            "沒没夢梦彌弥麵面滅灭廟庙鳴鸣謎谜媽妈腦脑內内擬拟寧宁農农濃浓紐纽歐欧盤盘賠赔頻频憑凭評评撲扑" +
            "僕仆樸朴齊齐騎骑豈岂啟启氣气牽牵簽签錢钱潛潜強强搶抢橋桥親亲輕輕傾倾請请慶庆窮穷區区驅驱趨趋" +
            "權权確确讓让熱热認认榮荣軟软灑洒傘伞喪丧掃扫殺杀曬晒閃闪傷伤賞赏燒烧紹绍捨舍攝摄設设審审聲声" +
            "勝胜師师詩诗時時識识實实勢势適适釋释壽寿術术樹树數数絲丝鬆松頌颂訴诉雖虽歲岁孫孙態态談谈湯汤" +
            "討讨條条聽听統统圖图襪袜灣湾彎弯萬万網网為为圍围偽伪偉伟衛卫溫温聞闻務务誤误霧雾係系細细俠侠" +
            "峽峡狹狭廈厦賢贤線线鄉乡詳详響响項项銷销寫写謝谢興兴須须虛虚許许續续選选尋寻訊讯壓压亞亚嚴严" +
            "顏颜驗验揚扬陽阳養养樣样搖摇藥药爺爷業业葉叶醫医儀仪遺遗義义藝艺譯译陰阴銀银飲饮應应營营贏赢" +
            "擁拥優优猶犹郵邮遊游漁渔與与獄狱預预譽誉員员園园願愿約约閱阅躍跃運运韻韵雜杂災灾載载讚赞臟脏" +
            "擇择賊贼贈赠佔占戰战張张漲涨帳账針针偵侦鎮镇陣阵爭争證证鄭郑隻只紙纸製制質质種种眾众週周豬猪" +
            "註注專专轉转賺赚莊庄裝装狀状準准資资總总縱纵組组鑽钻"
            ).toCharArray()
        val m = HashMap<Char, Char>(pairs.size / 2)
        var i = 0
        while (i + 1 < pairs.size) {
            m[pairs[i]] = pairs[i + 1]
            i += 2
        }
        m
    }

    /** 归一化结果：text 为归一化串，rawIndex[i] 为 text 第 i 个字符在原始串中的下标。 */
    internal class Norm(val text: String, val rawIndex: IntArray)

    /**
     * ICU 繁→简（Hant-Hans）兜底转换器。
     *
     * 2026-10-22 用户报「Wui229 / New notes 歌词识别不到」：手写单字表漏了生僻变体
     * —— 歌手「陳嫺靜」的「嫺」不在表里 ⇒ 归一化后本地「陈娴静」对不上「陈嫺静」，
     * 网易云候选因歌手闸门**全被拒**（真机日志：十条 `ah=false` 全灭，其中一条
     * 歌名完全相等、时长差 0）。按既定拍板：手写表保留（快、覆盖常用字），
     * 表里没有的 CJK 字再用 ICU（Hant-Hans 覆盖全部变体，如 嫺/嫻→娴）。
     *
     * · 单字转换，保证 Norm 的坐标映射成立（1 输入字符 → N 输出字符映射回同一下标）；
     * · 结果按字缓存 —— 搜索归一化每次按键都要跑，不能每次都进 ICU；
     * · Transliterator 非线程安全 ⇒ ThreadLocal；
     * · JVM 单测环境没有 android.icu ⇒ runCatching 回退手写表（既有单测不受影响）。
     */
    private val icuHan: ThreadLocal<android.icu.text.Transliterator?> = ThreadLocal.withInitial {
        runCatching { android.icu.text.Transliterator.getInstance("Hant-Hans") }.getOrNull()
    }

    /** 未命中手写表的 CJK 字 → ICU 简体结果。 */
    private val icuCache = java.util.concurrent.ConcurrentHashMap<Char, String>()

    /** 单字符转简体：手写表优先，未覆盖的 CJK 用 ICU，其余原样。 */
    private fun toSimple(ch: Char): String {
        TRAD_TO_SIMPLE[ch]?.let { return it.toString() }
        val isCjk = ch.code in 0x3400..0x4DBF || ch.code in 0x4E00..0x9FFF ||
            ch.code in 0xF900..0xFAFF
        if (!isCjk) return ch.toString()
        icuCache[ch]?.let { return it }
        val t = runCatching { icuHan.get() }.getOrNull() ?: return ch.toString()
        val out = runCatching { t.transliterate(ch.toString()) }.getOrNull()
        val result = if (out.isNullOrEmpty()) ch.toString() else out
        icuCache[ch] = result
        return result
    }

    internal fun norm(raw: String): Norm {
        val sb = StringBuilder(raw.length)
        val map = ArrayList<Int>(raw.length)
        for (i in raw.indices) {
            val c = raw[i]
            if (c == ' ' || c == '\t' || c == '\u3000') continue // 去全部空格
            val ch = if (c.code in 0xFF01..0xFF5E) (c.code - 0xFEE0).toChar() else c // 全角→半角
            // 繁体→简体（手写表 + ICU 兜底；可能输出多个字符，全部映射回同一原始下标）
            for (oc in toSimple(ch)) {
                map.add(i)
                sb.append(oc.lowercaseChar())
            }
        }
        return Norm(sb.toString(), map.toIntArray())
    }

    /** 归一化后的公开版本（供外部比较/展示使用）。 */
    fun normalize(raw: String): String = norm(raw).text

    /** 一次匹配结果：score 越高越接近；ranges 为命中区间在原始字符串中的坐标。 */
    class Match internal constructor(val score: Int, val ranges: List<IntRange>)

    /**
     * 判断 [query] 是否近似命中 [field]（null = 不命中）。
     * [field] 使用原始字符串，返回的区间坐标直接可用于高亮。
     */
    fun match(query: String, field: String): Match? {
        if (query.isBlank() || field.isEmpty()) return null
        val tokens = query.trim().split(Regex("""\s+""")).map { norm(it).text }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val nf = norm(field)
        if (nf.text.isEmpty()) return null
        val qJoined = tokens.joinToString("")

        // 1) 整串相等 / 子串包含（含前缀加分）
        if (qJoined == nf.text) return Match(1000, listOf(nf.rawIndex[0]..nf.rawIndex[nf.text.length - 1]))
        val at = nf.text.indexOf(qJoined)
        if (at >= 0) {
            val prefixBonus = if (at == 0) 100 else 0
            return Match(800 + prefixBonus, listOf(nf.rawIndex[at]..nf.rawIndex[at + qJoined.length - 1]))
        }

        // 2) token 顺序匹配：每个 token 精确包含或编辑距离容错
        var cursor = 0
        var fuzzy = 0
        val ranges = ArrayList<IntRange>(tokens.size)
        for (token in tokens) {
            val idx = nf.text.indexOf(token, cursor)
            if (idx >= 0) {
                ranges += nf.rawIndex[idx]..nf.rawIndex[idx + token.length - 1]
                cursor = idx + token.length
                continue
            }
            val win = bestWindow(nf.text, token, cursor)
            if (win == null) return null
            ranges += nf.rawIndex[win.first]..nf.rawIndex[win.second - 1]
            cursor = win.second
            fuzzy++
        }
        return Match(700 - 100 * fuzzy - 10 * (tokens.size - 1), ranges)
    }

    /** 命中结果（按 score 降序）。 */
    data class SearchHit(
        val track: Track,
        val score: Int,
        val titleRanges: List<IntRange> = emptyList(),
        val artistRanges: List<IntRange> = emptyList(),
        val albumRanges: List<IntRange> = emptyList(),
        val folderRanges: List<IntRange> = emptyList(),
        /** v1.5.0 搜索第三轮：专辑字段的**最佳**单 token 分（1000 精确/900 前缀/800 子串/600 模糊/0 未命中）。
         *  用来区分"强相关才给专辑推荐"——模糊(600)/罗马字(0)命中的条目**不**折叠成专辑卡。 */
        val albumBestScore: Int = 0,
    )

    /**
     * v1.2.0 #8: 分词搜索。查询按非字母数字字符（空白、- – — · / 等含全角）
     * 切成 token，每个 token 独立匹配「标题/歌手/专辑/文件夹」任一字段即算该
     * token 命中——这样「周杰伦-黑色幽默」能由「周杰伦」(歌手) × 「黑色幽默」
     * (标题) 命中本作，不再因整串当一 token 而漏掉。所有 token 都须至少命中一
     * 字段才算该曲目命中。单字段内多 token 顺序匹配仍由 [match] 保留供他用。
     */
    private fun tokenize(query: String): List<String> =
        query.split(Regex("[^\\p{L}\\p{N}]+")).map { norm(it).text }.filter { it.isNotEmpty() }

    /** 单 token 对单字段的命中（null = 不命中）。 */
    internal fun matchTokenInField(token: String, nf: Norm): Match? {
        if (token.isEmpty() || nf.text.isEmpty()) return null
        if (token == nf.text) return Match(1000, listOf(nf.rawIndex[0]..nf.rawIndex[nf.text.length - 1]))
        val at = nf.text.indexOf(token)
        if (at >= 0) {
            val prefixBonus = if (at == 0) 100 else 0
            return Match(800 + prefixBonus, listOf(nf.rawIndex[at]..nf.rawIndex[at + token.length - 1]))
        }
        val win = bestWindow(nf.text, token, 0)
            ?: return null
        // v1.5.0 搜索第二轮（A43）：模糊命中的高亮**收窄到与查询真正相等的那几个字**。
        // 旧实现把整段模糊窗口涂色（「晴天」两个字全被标亮），用户看到"标注太散开"。
        // 用 LCS 对齐找出窗口里逐字与 token 相等的位置，只标那些。
        val hl = lcsHighlightRanges(nf.text.substring(win.first, win.second), token, win.first, nf.rawIndex)
        return Match(600, hl)
    }

    /**
     * 模糊窗口的高亮区间：LCS 对齐 [window] 与 [token]，标出窗口中逐字与 token
     * 相等的位置（坐标映射回原始字符串）。
     *
     * 例：token=那天, window=天天 → 对齐后只有第 2 个「天」逐字相等 → [1..1]；
     * token=那天, window=会天 → 只有「天」相等 → [2..2]（原始坐标）。
     */
    internal fun lcsHighlightRanges(
        window: String,
        token: String,
        winStart: Int,
        rawIndex: IntArray,
    ): List<IntRange> {
        val m = window.length
        val n = token.length
        // LCS 的 DP 表
        val dp = Array(m + 1) { IntArray(n + 1) }
        for (i in 1..m) {
            for (j in 1..n) {
                dp[i][j] = if (window[i - 1] == token[j - 1]) dp[i - 1][j - 1] + 1
                else maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }
        // 回溯:收集 window 中参与 LCS 的下标
        val matched = ArrayList<Int>()
        var i = m; var j = n
        while (i > 0 && j > 0) {
            when {
                window[i - 1] == token[j - 1] -> { matched.add(i - 1); i--; j-- }
                dp[i - 1][j] >= dp[i][j - 1] -> i--
                else -> j--
            }
        }
        matched.reverse()
        if (matched.isEmpty()) return emptyList()
        // 把连续下标合并成区间，映射回原始坐标
        val ranges = ArrayList<IntRange>()
        var start = matched[0]; var prev = start
        for (k in 1 until matched.size) {
            if (matched[k] == prev + 1) { prev = matched[k] }
            else {
                ranges.add(rawIndex[winStart + start]..rawIndex[winStart + prev])
                start = matched[k]; prev = matched[k]
            }
        }
        ranges.add(rawIndex[winStart + start]..rawIndex[winStart + prev])
        return ranges
    }

    /**
     * v1.2.0 #8: 标题归一用于搜索——若标题含分隔符（- – —）且其左边（归一后）等于该
     * 曲目歌手，则只取右边歌名部分匹配（如 "揽佬skaiisyourgod-YADEA" → "YADEA"），
     * rawIndex 仍映射回原始标题坐标供高亮；否则返回完整归一标题。仅当左边等于歌手才剥，
     * 不误伤正常带 - 的歌名（如 "Born - Dead" 不会被剥，除非歌手恰好叫 Born）。
     */
    private fun titleNormForSearch(title: String, artistNormText: String): Norm {
        val full = norm(title)
        if (artistNormText.isEmpty()) return full
        val sep = full.text.indexOfFirst { it == '-' || it == '–' || it == '—' }
        if (sep <= 0) return full
        if (full.text.substring(0, sep) != artistNormText) return full
        val rightStart = sep + 1
        if (rightStart >= full.text.length) return full
        val right = full.text.substring(rightStart)
        if (right.isEmpty()) return full
        val ri = IntArray(right.length) { full.rawIndex[rightStart + it] }
        return Norm(right, ri)
    }

    /** v1.2.0 #5: 完整查询子串命中的置顶奖励（远大于字段加权，形成明确分层）。 */
    private const val FULL_QUERY_BONUS = 1_000_000

    /** v1.5.0 搜索第二轮：最佳单字段匹配的**分层加成**权重。
     *  标题精确命中(1000)×BEST = 10M，必须压过"多字段弱命中累加"（最多 ~26k），
     *  让搜「You」时标题恰为 You 的歌排在「SAVE YOUR Tears」（多字段子串累加）之上。 */
    private const val BEST_FIELD_BONUS = 10_000

    /** v1.3.10: 罗马字兜底命中(假名↔罗马字)的单 token 计分——刻意低于常规字段命中
     *  (子串 800 / 模糊 600),保证库内的直接命中永远排在罗马字兜底命中之前。 */
    private const val ROMAJI_TOKEN_SCORE = 400

    /**
     * 在曲库上执行模糊搜索：每个 token 须命中至少一个字段，标题(×4) > 歌手(×3)
     * > 专辑(×5) > 文件夹(×1) 加权求和，按相关度排序。任一字段含完整查询（连写）子串
     * 的曲目额外加 [FULL_QUERY_BONUS] 整体置顶——精确子串命中 > 共享单字模糊命中。
     */
    fun search(tracks: List<Track>, query: String): List<SearchHit> {
        if (query.isBlank() || tracks.isEmpty()) return emptyList()
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return emptyList()
        val qJoined = tokens.joinToString("")
        val hits = ArrayList<SearchHit>(tracks.size / 4)
        for (t in tracks) {
            val nfArtist = norm(t.artist)
            // v1.2.0 #8: 标题里被烤进歌手名的（文件名 artist-song 当了 title）只匹配 -
            // 右边的歌名部分，见 titleNormForSearch。
            val nfTitle = titleNormForSearch(t.title, nfArtist.text)
            val nfAlbum = norm(t.album)
            val nfFolder = norm(t.folder)
            var score = 0
            val titleRanges = ArrayList<IntRange>()
            val artistRanges = ArrayList<IntRange>()
            val albumRanges = ArrayList<IntRange>()
            val folderRanges = ArrayList<IntRange>()
            var ok = true
            // v1.5.0 搜索第二轮：记录所有 token 里最高的单字段分，用于分层加成
            // （精确命中必须压过"多字段弱命中累加"）。
            var bestFieldScore = 0
            var albumBestScore = 0  // v1.5.0 第三轮：专辑字段的最佳单 token 分（供"模糊不出专辑卡"判定）
            // v1.3.10: 罗马字兜底用,懒算——只有某个 token 常规字段全不中时才转一次。
            var romajiFields: List<String>? = null
            for (token in tokens) {
                val mt = matchTokenInField(token, nfTitle)
                val ma = matchTokenInField(token, nfArtist)
                val mal = matchTokenInField(token, nfAlbum)
                val mf = matchTokenInField(token, nfFolder)
                if (mt == null && ma == null && mal == null && mf == null) {
                    // v1.3.10 假名↔罗马字兜底:「カブトムシ」↔「kabutomushi」(库里是假名、
                    // 用户按罗马字找,纯字符串归一永远对不上)。命中只计分、**不给高亮区间**
                    // ——罗马字串的坐标映射不回原字段,硬给会让高亮错位。
                    val rf = romajiFields ?: listOf(
                        KanaRomaji.toRomaji(nfTitle.text),
                        KanaRomaji.toRomaji(nfArtist.text),
                        KanaRomaji.toRomaji(nfAlbum.text),
                        KanaRomaji.toRomaji(nfFolder.text),
                    ).also { romajiFields = it }
                    if (rf.none { it.contains(token) }) { ok = false; break }
                    score += ROMAJI_TOKEN_SCORE
                    continue
                }
                val tokenBest = maxOf(mt?.score ?: 0, ma?.score ?: 0, mal?.score ?: 0, mf?.score ?: 0)
                if (tokenBest > bestFieldScore) bestFieldScore = tokenBest
                if ((mal?.score ?: 0) > albumBestScore) albumBestScore = mal?.score ?: 0
                score += (mt?.score ?: 0) * 4 + (ma?.score ?: 0) * 3 + (mal?.score ?: 0) * 5 + (mf?.score ?: 0)
                mt?.let { titleRanges += it.ranges }
                ma?.let { artistRanges += it.ranges }
                mal?.let { albumRanges += it.ranges }
                mf?.let { folderRanges += it.ranges }
            }
            if (ok) {
                // v1.2.0 #5: 任一字段含完整查询子串的曲目整体置顶——如「伤心」命中
                // 「伤心早餐店」专辑名，必排在仅靠共享单字模糊命中的「心雨/伤跡」之上。
                val fullHit = nfTitle.text.contains(qJoined) || nfArtist.text.contains(qJoined) ||
                    nfAlbum.text.contains(qJoined) || nfFolder.text.contains(qJoined)
                if (fullHit) score += FULL_QUERY_BONUS
                // v1.5.0 搜索第二轮：最佳单字段匹配的分层加成（精确>前缀>子串>模糊），
                // 确保搜「You」时标题恰为 You 的歌排第一，不被多字段弱命中累加压过。
                score += bestFieldScore * BEST_FIELD_BONUS
                hits += SearchHit(
                    track = t, score = score,
                    titleRanges = titleRanges, artistRanges = artistRanges,
                    albumRanges = albumRanges, folderRanges = folderRanges,
                    albumBestScore = albumBestScore,
                )
            }
        }
        hits.sortByDescending { it.score }
        return hits
    }

    // ── 编辑距离 ─────────────────────────────────────────────────────────

    /**
     * 编辑距离阈值：仿 AUTO——短词从严（1 个编辑封顶），长词放宽（2 个封顶）。
     *
     * v1.5.0 搜索第二轮（A43）：**撤销了第一轮"2 字中文不容错"的收紧**。用户明确纠偏
     * 「搜索太严格了，我是允许模糊搜索的，只是反对模糊搜索后的结果标注太散开」。
     * 所以 2 字中文（如「那天」）恢复 cap=1 的容错，靠"高亮收窄到真正相等的字"
     * （见 matchTokenInField 模糊分支）来解决观感问题，而不是禁止命中。
     */
    internal fun maxEdits(token: String): Int = when {
        token.length <= 1 -> 0
        token.length <= 4 -> 1
        else -> 2
    }

    /** 是否为中日韩文字（汉字各平面 + 平/片假名，含半角片假名）。 */
    internal fun isCjkChar(c: Char): Boolean =
        c.code in 0x3040..0x30FF ||   // 平假名 / 片假名
            c.code in 0x3400..0x4DBF ||  // CJK 扩展 A
            c.code in 0x4E00..0x9FFF ||  // CJK 基本区
            c.code in 0xF900..0xFAFF ||  // CJK 兼容表意
            c.code in 0xFF66..0xFF9D     // 半角片假名

    /** Damerau-Levenshtein 距离（含相邻换位），超过 [cap] 提前返回。 */
    private fun dlDistance(a: String, b: String, cap: Int): Int {
        if (a == b) return 0
        if (kotlin.math.abs(a.length - b.length) > cap) return cap + 1
        val short: String
        val long: String
        if (a.length <= b.length) { short = a; long = b } else { short = b; long = a }
        val m = short.length
        var prevPrev = IntArray(m + 1)
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (j in 1..long.length) {
            cur[0] = j
            var rowMin = cur[0]
            for (i in 1..m) {
                val cost = if (short[i - 1] == long[j - 1]) 0 else 1
                var d = minOf(cur[i - 1] + 1, prev[i] + 1, prev[i - 1] + cost)
                if (i > 1 && j > 1 && short[i - 1] == long[j - 2] && short[i - 2] == long[j - 1]) {
                    d = minOf(d, prevPrev[i - 2] + 1) // 相邻换位算一次编辑
                }
                cur[i] = d
                if (d < rowMin) rowMin = d
            }
            if (rowMin > cap) return cap + 1 // 整行都超出上限，早退
            val t = prevPrev; prevPrev = prev; prev = cur; cur = t
        }
        return prev[m]
    }

    /** 快速预检：token 中缺失字符数超过阈值则必然不匹配（DL ≥ 缺失数）。 */
    private fun quickReject(field: String, token: String, maxEdits: Int): Boolean {
        if (field.length > 48) return true // 长字段不做整串模糊，防误配 + 控性能
        val set = field.toHashSet()
        var missing = 0
        for (c in token) {
            if (c !in set) {
                missing++
                if (missing > maxEdits) return true
            }
        }
        return false
    }

    /**
     * 在 [field] 中滑动窗口找与 [token] 最接近（编辑距离 ≤ maxEdits）的一段。
     * 返回窗口的 [start, end) 下标；找不到返回 null。[from] 为顺序匹配游标。
     */
    internal fun bestWindow(field: String, token: String, from: Int): Pair<Int, Int>? {
        val cap = maxEdits(token)
        if (quickReject(field, token, cap)) return null
        val n = field.length
        val best = IntArray(2) { cap + 1 } // best[0]=distance, best[1]=start
        var bestEnd = 0
        var minLen = (token.length - cap).coerceAtLeast(1)
        var maxLen = token.length + cap
        var s = from
        while (s < n) {
            val hi = minOf(maxLen, n - s)
            var len = minLen
            while (len <= hi) {
                val d = dlDistance(field, token, s, s + len, cap)
                if (d < best[0]) {
                    best[0] = d
                    best[1] = s
                    bestEnd = s + len
                    if (d == 0) return best[1] to bestEnd
                }
                len++
            }
            s++
        }
        return if (best[0] <= cap) best[1] to bestEnd else null
    }

    /** 子串版的 Damerau-Levenshtein（只比较 [from, to) 窗口）。 */
    private fun dlDistance(field: String, token: String, from: Int, to: Int, cap: Int): Int {
        val window = field.substring(from, to)
        return dlDistance(window, token, cap)
    }
}
