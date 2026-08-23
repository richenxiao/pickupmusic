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
    private class Norm(val text: String, val rawIndex: IntArray)

    private fun norm(raw: String): Norm {
        val sb = StringBuilder(raw.length)
        val map = IntArray(raw.length)
        var k = 0
        for (i in raw.indices) {
            val c = raw[i]
            if (c == ' ' || c == '\t' || c == '\u3000') continue // 去全部空格
            var ch = if (c.code in 0xFF01..0xFF5E) (c.code - 0xFEE0).toChar() else c // 全角→半角
            ch = TRAD_TO_SIMPLE[ch] ?: ch // 繁体→简体
            ch = ch.lowercaseChar() // 小写（单字符 1:1，保持坐标映射）
            map[k] = i
            sb.append(ch)
            k++
        }
        return Norm(sb.toString(), map.copyOf(k))
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
    private fun matchTokenInField(token: String, nf: Norm): Match? {
        if (token.isEmpty() || nf.text.isEmpty()) return null
        if (token == nf.text) return Match(1000, listOf(nf.rawIndex[0]..nf.rawIndex[nf.text.length - 1]))
        val at = nf.text.indexOf(token)
        if (at >= 0) {
            val prefixBonus = if (at == 0) 100 else 0
            return Match(800 + prefixBonus, listOf(nf.rawIndex[at]..nf.rawIndex[at + token.length - 1]))
        }
        val win = bestWindow(nf.text, token, 0)
            ?: return null
        return Match(600, listOf(nf.rawIndex[win.first]..nf.rawIndex[win.second - 1]))
    }

    /**
     * 在曲库上执行模糊搜索：每个 token 须命中至少一个字段，标题(×4) > 歌手(×3)
     * > 专辑(×2) > 文件夹(×1) 加权求和，按相关度排序。
     */
    fun search(tracks: List<Track>, query: String): List<SearchHit> {
        if (query.isBlank() || tracks.isEmpty()) return emptyList()
        val tokens = tokenize(query)
        if (tokens.isEmpty()) return emptyList()
        val hits = ArrayList<SearchHit>(tracks.size / 4)
        for (t in tracks) {
            val nfTitle = norm(t.title)
            val nfArtist = norm(t.artist)
            val nfAlbum = norm(t.album)
            val nfFolder = norm(t.folder)
            var score = 0
            val titleRanges = ArrayList<IntRange>()
            val artistRanges = ArrayList<IntRange>()
            val albumRanges = ArrayList<IntRange>()
            val folderRanges = ArrayList<IntRange>()
            var ok = true
            for (token in tokens) {
                val mt = matchTokenInField(token, nfTitle)
                val ma = matchTokenInField(token, nfArtist)
                val mal = matchTokenInField(token, nfAlbum)
                val mf = matchTokenInField(token, nfFolder)
                if (mt == null && ma == null && mal == null && mf == null) { ok = false; break }
                score += (mt?.score ?: 0) * 4 + (ma?.score ?: 0) * 3 + (mal?.score ?: 0) * 2 + (mf?.score ?: 0)
                mt?.let { titleRanges += it.ranges }
                ma?.let { artistRanges += it.ranges }
                mal?.let { albumRanges += it.ranges }
                mf?.let { folderRanges += it.ranges }
            }
            if (ok) {
                hits += SearchHit(
                    track = t, score = score,
                    titleRanges = titleRanges, artistRanges = artistRanges,
                    albumRanges = albumRanges, folderRanges = folderRanges,
                )
            }
        }
        hits.sortByDescending { it.score }
        return hits
    }

    // ── 编辑距离 ─────────────────────────────────────────────────────────

    /** 编辑距离阈值：仿 AUTO——短词从严（1 个编辑封顶），长词放宽（2 个封顶）。
     *  v1.2.0 #8 fix: 1-2 字 token 直接 0 编辑（仅精确子串）——2 字 token 做 1 编辑
     *  模糊会让「伤心」靠共享单字（如「心」）命中「心雨」「月亮代表我的心」「悲伤」
     *  等大量误配（共享单字 DL=1 即命中，短 CJK 共用字太多）。3 字起再放开 1 编辑。 */
    private fun maxEdits(token: String): Int = when {
        token.length <= 2 -> 0
        token.length <= 4 -> 1
        else -> 2
    }

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
    private fun bestWindow(field: String, token: String, from: Int): Pair<Int, Int>? {
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
