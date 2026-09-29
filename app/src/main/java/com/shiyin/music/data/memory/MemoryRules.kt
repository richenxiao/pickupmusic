package com.shiyin.music.data.memory

/**
 * v1.3.10 Memory P1:**数据质量规则**(纯函数,零 Android/零 IO)。
 *
 * 为什么规则单独成一层:本轮实践反复证明"把判断交给模型"不可靠(闪念模型不会照指令办、
 * 还会把工具返回原样念出去)。所以 Memory 的分工是——模型只负责"这句值不值得记"的**提名**,
 * **能不能入库、入库后跟既有条目什么关系**全部由这里的确定性规则裁决。
 *
 * 本文件所有函数都是纯的(给定输入必得同一输出、无副作用、无时间依赖),因此可以纯 JVM 单测
 * 把数据质量钉死(见 MemoryRulesTest / MemoryManagerTest)。
 */
object MemoryRules {

    /** 单条记忆的长度上限(字符)。超出的截断——记忆应当是一句话,不是一段话。 */
    const val MAX_CONTENT_LEN = 200

    /** 检索默认返回条数。 */
    const val DEFAULT_RECALL_LIMIT = 3

    /**
     * 相关度门槛:查询词命中比例低于此值**不注入**。
     *
     * 为什么必须有门槛:否则"每次聊到日本音乐都强行说我记得你喜欢 aiko"——记忆会退化成
     * 个人资料朗读器。宁可不提,也不要乱提。
     */
    const val RELEVANCE_THRESHOLD = 0.34f

    /** 判"重复/同一件事"的相似度门槛(高于此值 → update,不新增)。 */
    private const val DUPLICATE_SIMILARITY = 0.85f

    /** 判"同主题但说法变了"的门槛(高于此值且有共同主题词 → 冲突,旧条目 superseded)。 */
    private const val SAME_TOPIC_SIMILARITY = 0.5f

    // ── 归一化与长度 ───────────────────────────────────────────────────────

    /** 归一化:去首尾空白、折叠内部空白(含全角空格)。 */
    fun normalize(raw: String): String =
        raw.replace('　', ' ').trim().replace(Regex("""\s+"""), " ")

    /** 归一化 + 截断到 [MAX_CONTENT_LEN]。 */
    fun normalizeAndLimit(raw: String): String = normalize(raw).take(MAX_CONTENT_LEN)

    /** 内容是否可用(非空,且至少含一个字母/数字/汉字——纯标点不算)。 */
    fun isUsableContent(content: String): Boolean =
        normalize(content).any { it.isLetterOrDigit() }

    /** type 是否在白名单内。 */
    fun isValidType(type: String): Boolean = type in MemoryType.ALL

    // ── 一次性情绪拒表 ─────────────────────────────────────────────────────

    /** 时间/程度标记:指向"此时此地的状态"。 */
    private val TRANSIENT_MARKERS =
        listOf("今天", "刚刚", "刚才", "这会", "现在", "有点", "最近", "这阵子", "这两天")

    /** 瞬时状态词。 */
    private val TRANSIENT_STATES =
        listOf("累", "烦", "困", "难过", "心情不好", "无聊", "饿", "头疼", "烦躁", "emo")

    /** 长期标志词:出现任一即认为不是"一次性情绪"(如"最近一直在听 X"是长期习惯)。 */
    private val LONGTERM_MARKERS =
        listOf("喜欢", "讨厌", "一直", "总是", "每次", "习惯", "偏好", "希望", "以后", "不再", "从来", "最常", "经常")

    /**
     * 是否属于"一次性情绪"——默认**不入**长期记忆。
     *
     * 判据(全部满足才判一次性):
     *  ① 命中瞬时状态词;
     *  ② 命中时间/程度标记,或本身很短(≤12 字,如"好累啊");
     *  ③ 不含任何长期标志词。
     *
     * 用户明确要求记住时**不适用**本规则(见 [isExplicitRemember]),由管理器绕过。
     */
    fun isTransient(content: String): Boolean {
        val c = normalize(content)
        if (c.isEmpty()) return false
        val hasState = TRANSIENT_STATES.any { c.contains(it) }
        if (!hasState) return false
        val hasTime = TRANSIENT_MARKERS.any { c.contains(it) } || c.length <= 12
        if (!hasTime) return false
        return LONGTERM_MARKERS.none { c.contains(it) }
    }

    // ── 用户明确要求记住 ───────────────────────────────────────────────────

    private val EXPLICIT_PATTERNS =
        listOf("记住", "记一下", "记下来", "记下", "别忘了", "别忘记", "要记得", "帮我记", "记得这件事")

    /**
     * 用户**明确要求**记住吗?看的是**用户原话**(不是被提炼过的 content——原话里的
     * "记住"必须能看见)。命中即:提高 importance 到 3、标记 source=explicit、并绕过一次性
     * 情绪拒表。
     */
    fun isExplicitRemember(userSaid: String): Boolean =
        EXPLICIT_PATTERNS.any { userSaid.contains(it) }

    // ── 主题词抽取(刻意"笨但确定") ────────────────────────────────────────

    /**
     * 中文功能词(逐段**删除**而非分词)。刻意不用分词器:
     * 这一层只需要回答"两条记忆是不是同一主题",不需要语言学正确性;确定性比精度重要,
     * 也不引入依赖。英文/数字按词抽。
     */
    private val CJK_FUNCTION_WORDS = listOf(
        "用户", "喜欢", "不喜欢", "不再", "更", "偏好", "记得", "一直", "总是", "经常",
        "有点", "今天", "现在", "以后", "最近", "他", "她", "它的", "的", "了", "是", "在",
        "和", "与", "也", "就", "都", "很", "比较", "不要", "再", "把", "被", "对",
    )

    private val ASCII_WORD = Regex("""[A-Za-z0-9][A-Za-z0-9'’\-]*""")

    /**
     * 抽"主题词"(检索与相似度都用它)。
     * - ASCII 词:小写、长度 ≥ 2;
     * - 中文:按非中文切开成段,段内删掉功能词,余下长度 ≥ 2 的保留。
     */
    fun tokens(text: String): Set<String> {
        val n = normalize(text)
        val out = LinkedHashSet<String>()
        for (m in ASCII_WORD.findAll(n)) {
            val w = m.value.lowercase()
            if (w.length >= 2) out += w
        }
        for (run in Regex("""[一-鿿]{2,}""").findAll(n)) {
            var s = run.value
            for (fw in CJK_FUNCTION_WORDS) s = s.replace(fw, "")
            if (s.length >= 2) out += s
        }
        return out
    }

    /** 相似度:主题词集合的 Jaccard;归一化后完全相同直接 1.0。 */
    fun similarity(a: String, b: String): Float {
        if (normalize(a) == normalize(b)) return 1f
        val ta = tokens(a)
        val tb = tokens(b)
        if (ta.isEmpty() || tb.isEmpty()) return 0f
        val inter = ta.count { it in tb }
        val union = (ta + tb).size
        return inter.toFloat() / union
    }

    /** 两条记忆是否"重复"(同一件事的换一种说法)→ 应当 update 而不是新增。 */
    fun isDuplicate(a: String, b: String): Boolean = similarity(a, b) >= DUPLICATE_SIMILARITY

    /**
     * 两条记忆是否"冲突/同主题修订"——同主题(有共同主题词)且相似度落在
     * [SAME_TOPIC_SIMILARITY, DUPLICATE_SIMILARITY) 区间。
     *
     * 例:「用户喜欢 aiko」vs「用户不再喜欢 aiko,更喜欢 Vaundy」→ 共同主题词 aiko、
     * 相似度 0.5 → 冲突:旧条目标 supersededBy 指向新条,而不是两条并存互相打架。
     */
    fun isSameTopicRevision(a: String, b: String): Boolean {
        val sim = similarity(a, b)
        if (sim >= DUPLICATE_SIMILARITY) return false
        if (sim < SAME_TOPIC_SIMILARITY) return false
        val ta = tokens(a)
        val tb = tokens(b)
        return ta.any { it in tb }
    }

    // ── 检索:相关度 / 门槛 / top-k ─────────────────────────────────────────

    /** 查询词命中比例(0-1):查询的每个主题词是否出现在记忆的主题词里(双向包含也算)。 */
    fun relevance(query: String, memory: Memory): Float {
        val q = tokens(query)
        if (q.isEmpty()) return 0f
        val hay = tokens(memory.content) + memory.tags.map { it.lowercase() }
        val hit = q.count { token -> hay.any { it == token || it.contains(token) || token.contains(it) } }
        return hit.toFloat() / q.size
    }

    /**
     * 检索:只保留**有效**(未被取代)、且相关度 ≥ [RELEVANCE_THRESHOLD] 的记忆,
     * 按相关度 → importance → updatedAt 排序,取前 [limit] 条。
     *
     * 找不到足够相关的就返回**空**——绝不返回"默认记忆",更不让模型编"我记得"。
     *
     * v1.3.11:身份/自我指代问句先走一次**针对性**补口(见 [IDENTITY] 段注释)。
     */
    fun rankAndFilter(query: String, memories: List<Memory>, limit: Int = DEFAULT_RECALL_LIMIT): List<Memory> {
        if (isIdentityQuery(query)) {
            val identity = memories.asSequence()
                .filter { it.active && isIdentityMemory(it) }
                .sortedWith(compareByDescending<Memory> { it.importance }.thenByDescending { it.updatedAt })
                .take(limit)
                .toList()
            if (identity.isNotEmpty()) return identity
        }
        return memories.asSequence()
            .filter { it.active }
            .map { it to relevance(query, it) }
            .filter { it.second >= RELEVANCE_THRESHOLD }
            .sortedWith(
                compareByDescending<Pair<Memory, Float>> { it.second }
                    .thenByDescending { it.first.importance }
                    .thenByDescending { it.first.updatedAt },
            )
            .take(limit)
            .map { it.first }
            .toList()
    }

    /** 记忆的检索标签(入库时由内容派生,保证 FTS/标签列始终有值)。 */
    fun tagsOf(content: String): List<String> = tokens(content).take(8).toList()

    // ── FTS 索引/查询文本的 CJK 二元切分 ───────────────────────────────────

    private val CJK_RUN = Regex("""[一-鿿]{1,}""")

    /**
     * 把文本转成 FTS4 能用的索引/查询串。
     *
     * **为什么必须做这一步**:FTS4 的默认分词器(含 simple/unicode61)不切分中文——
     * 一整段汉字会被当成**一个** token,"完整听完一张专辑"里搜"专辑"永远匹配不到。
     * 通用解法是 CJK **二元切分**(bigram):索引侧与查询侧用同一套展开规则,
     * 于是"专辑"展开成 `专辑`,而长句展开后包含 `专辑` 这个二元组 → 能匹配。
     *
     * ASCII 词原样保留(它们本来就按空白切);汉字连续段额外保留整段,
     * 让"整句原样查"也能命中。
     */
    fun ftsText(text: String): String {
        val n = normalize(text)
        // 用 LinkedHashSet 去重:两字词会"整段 == 唯一二元组"重复一次,不去重会让
        // MATCH 表达式里出现重复项(结果不受影响,但没必要)。
        val out = LinkedHashSet<String>()
        for (m in ASCII_WORD.findAll(n)) {
            val w = m.value.lowercase()
            if (w.length >= 2) out += w
        }
        for (run in CJK_RUN.findAll(n)) {
            val s = run.value
            if (s.length == 1) { out += s; continue }
            out += s                       // 整段保留(整句查询)
            for (i in 0 until s.length - 1) out += s.substring(i, i + 2)
        }
        return out.joinToString(" ")
    }

    /** 把查询串转成 FTS `MATCH` 表达式:`"词1" OR "词2"`(各项加引号防运算符/特殊字符)。 */
    fun ftsMatchQuery(query: String): String =
        ftsText(query).split(' ').filter { it.isNotBlank() }.distinct()
            .joinToString(" OR ") { "\"$it\"" }

    // ── v1.3.11 确定性「记住」解析 ──────────────────────────────────────────
    //
    // 用户明确说"记住…"时,不能再等回合后的 LLM 提名——实测弱模型经常不提名,结果就是
    // "她回了一句好的我记住了,但库里根本没有这一条"(A21 反馈的核心)。触发词命中即
    // 走确定性解析直接落库,整条链路不依赖任何 LLM。

    /** 身份类陈述句式(先抽名字,避免被当成普通偏好存)。 */
    private val IDENTITY_STATEMENT_PATTERNS = listOf(
        Regex("""^(?:我的)?(?:名字|姓名|名称)(?:是|叫|叫做|为)?\s*(.+)$"""),
        Regex("""^我(?:叫|是|名叫|叫做)\s*(.+)$"""),
        Regex("""^称呼(?:我)?(?:为|是|叫)?\s*(.+)$"""),
        Regex("""^叫我\s*(.+)$"""),
    )

    /** 疑问/指代词。出现在"名字"位置说明**这是在问**,不是给资料。 */
    private val INTERROGATIVE = listOf("什么", "啥", "谁", "哪", "吗", "?", "？")

    /** 去掉触发词后残留的引导标点/空白。 */
    private val LEAD_TRIM = charArrayOf('，', ',', '、', '：', ':', '。', '.', '!', '！', ' ', '~', '～', '\n')

    private val MUSIC_HINTS = listOf("歌", "专辑", "乐队", "歌手", "音乐", "曲子", "单曲", "唱片")
    private val PREFERENCE_HINTS = listOf("喜欢", "讨厌", "偏好", "习惯", "爱听", "不爱", "常听", "最常")

    /**
     * 从用户原话里**确定性**抽出"明确要求记住"的候选。无触发词 / 抽不出可存内容 → null。
     *
     * 刻意做的两件事:
     *  - 身份陈述归一成「用户叫 X」——与 [isIdentityMemory] 的判据对齐,保证写得进也取得出;
     *  - 疑问句一律拒(「记住我叫什么」是在问,把"什么"存成名字就是一条错记忆)。
     */
    fun explicitCandidate(userSaid: String): MemoryCandidate? {
        val said = normalize(userSaid)
        val trigger = EXPLICIT_PATTERNS.firstOrNull { said.contains(it) } ?: return null
        // 「记住X」取触发词之后;触发词落在句尾时(「这件事别忘了」)退回删掉触发词的整句
        var rest = said.substringAfter(trigger).trim(*LEAD_TRIM)
        if (rest.length < 2) rest = said.replace(trigger, "").trim(*LEAD_TRIM)
        if (rest.length < 2 || !isUsableContent(rest)) return null

        // ① 身份陈述 → 「用户叫 X」
        for (p in IDENTITY_STATEMENT_PATTERNS) {
            val m = p.matchEntire(rest) ?: continue
            val name = m.groupValues[1].trim(*LEAD_TRIM)
            if (name.length !in 1..40) continue
            if (INTERROGATIVE.any { name.contains(it) }) continue
            return MemoryCandidate(
                type = MemoryType.PERSONAL,
                content = "用户叫 $name",
                importance = 3,
                source = MemorySource.EXPLICIT,
                userSaid = userSaid,
            )
        }

        // ② 疑问句不是资料 —— 「记住我喜欢什么」不该存成事实
        if (INTERROGATIVE.any { rest.contains(it) }) return null

        // ③ 其余 → 保留用户自己的说法(改第三人称),类型按关键词粗判
        val content = if (rest.startsWith("我")) "用户" + rest.substring(1) else rest
        if (!isUsableContent(content)) return null
        val type = when {
            MUSIC_HINTS.any { content.contains(it) } -> MemoryType.MUSIC
            PREFERENCE_HINTS.any { content.contains(it) } -> MemoryType.PREFERENCE
            else -> MemoryType.PERSONAL
        }
        return MemoryCandidate(
            type = type, content = content, importance = 3,
            source = MemorySource.EXPLICIT, userSaid = userSaid,
        )
    }

    // ── v1.3.11 第二阶段:记忆状态该不该显示 / 该不该提取 ────────────────────
    //
    // 记忆 UI 不能对每句话都显示——"今天天气不错"后面挂一句「未找到相关记忆」只会把界面变吵。
    // 判据必须收紧:只有确实涉及"用户自己 / 他的长期信息"的消息才值得显示或提取。

    /** 自我信息线索:出现即说明这句话可能在交代"我是什么样的人"。 */
    private val SELF_INFO_CUES = listOf(
        "我叫", "我的名字", "我姓", "我的偏好", "我喜欢", "我不喜欢", "我讨厌", "我最",
        "我习惯", "我平时", "我经常", "我常常", "我总是", "我一直", "我最近", "我住",
        "我的生日", "我今年",
    )

    /** 回忆线索:用户在提/问过去的事,值得先查一遍记忆。 */
    private val RECALL_CUES = listOf(
        "记得", "记住", "我说过", "我告诉过", "我提过", "我的名字", "我是谁", "我叫",
        "我喜欢", "我最", "我平时", "我最近", "关于我",
    )

    /** 纯播放/控制类短指令——这类轮次不可能产生长期记忆,也不值得查记忆。 */
    private val COMMAND_WORDS = listOf(
        "暂停", "继续", "下一首", "上一首", "切歌", "快进", "快退", "播放", "放一下",
        "听听", "来一首", "加进队列", "音量", "循环", "随机", "停止",
    )

    /** 看着像"一句话指令"而不是"有信息量的话"? */
    fun looksLikeCommand(text: String): Boolean {
        val t = normalize(text)
        // 「记住下一首…」既是指令又带记忆 —— 不能当纯指令丢掉(与旧实现的 !contains("记住") 同义)
        if (isExplicitRemember(t)) return false
        return t.length <= 12 && COMMAND_WORDS.any { t.contains(it) }
    }

    /**
     * 这条消息值不值得显示记忆状态?
     *
     * 收紧的判据(理由见上):显式「记住」/ 身份问句 / 自我信息线索 / 回忆线索。
     * 普通聊天、纯指令、纯知识问答一律 false → **不显示任何记忆状态行**。
     */
    fun memoryWarrantsTrace(text: String): Boolean {
        val t = normalize(text)
        if (t.isEmpty()) return false
        if (isExplicitRemember(t) || isIdentityQuery(t)) return true
        return SELF_INFO_CUES.any { t.contains(it) } || RECALL_CUES.any { t.contains(it) }
    }

    /**
     * 值不值得在**回复前**做一次记忆提取(这句话可能形成长期记忆)?
     *
     * 比 [memoryWarrantsTrace] 更窄:
     *  - 提问("我叫什么")要显示状态,但**不该**触发提取——那是在问,不是在给资料;
     *  - 纯指令("播放我最近听的歌")不提取;
     *  - 疑问句("我最喜欢的颜色是什么")不提取。
     * 显式「记住」不走本判据(它有确定性路径,见 [explicitCandidate])。
     */
    fun shouldExtractSave(text: String): Boolean {
        val t = normalize(text)
        if (t.isEmpty() || t.length > 600) return false
        if (isIdentityQuery(t)) return false
        if (looksLikeCommand(t)) return false
        if (looksLikeQuestion(t)) return false
        return SELF_INFO_CUES.any { t.contains(it) }
    }

    private fun looksLikeQuestion(t: String): Boolean =
        t.endsWith("?") || t.endsWith("？") || t.endsWith("吗") || t.endsWith("呢") || t.contains("什么")

    // ── v1.3.11 身份/自我指代检索 ───────────────────────────────────────────
    //
    // 已确证的 bug(2026-09-29 审计):中文问句「我叫什么」的 tokens 是 {我叫什么},
    // 而「用户叫 Richen」的 tokens 是 {richen}(功能词「用户」被剥、「叫」长度不足被丢)。
    // relevance() 的双向包含在两者之间都不成立 → 0.0 < 门槛 0.34 → **名字永远召不回**。
    //
    // 修法**不是**降门槛(会污染所有普通查询)、也不是"高重要度全注入"(等于资料朗读器):
    // 只补身份这一类——类型是个人/关系、且内容带身份标记。取不到就落回普通检索。

    private val IDENTITY_QUERY_ZH = listOf(
        "我叫什么", "我叫啥", "我的名字", "我名字", "我的姓名", "我是谁",
        "知道我的名字", "记得我是谁", "记得我叫什么", "记得我", "你认识我",
    )
    private val IDENTITY_QUERY_EN = listOf(
        "my name", "who am i", "remember me", "know my name", "do you know me", "call me",
    )

    /** 身份记忆的内容标记。 */
    private val IDENTITY_CONTENT_MARKERS = listOf("名字", "姓名", "名称", "称呼", "叫")

    /** 这是不是在问"我是谁/我叫什么"?中英文都认(用户可能用任何一种问)。 */
    fun isIdentityQuery(query: String): Boolean {
        val q = normalize(query).lowercase()
        return IDENTITY_QUERY_ZH.any { q.contains(it) } || IDENTITY_QUERY_EN.any { q.contains(it) }
    }

    /** 是否为**身份类**记忆:类型是个人/关系,且内容带身份标记(名字/称呼/叫)。 */
    fun isIdentityMemory(m: Memory): Boolean =
        (m.type == MemoryType.PERSONAL || m.type == MemoryType.RELATIONSHIP) &&
            IDENTITY_CONTENT_MARKERS.any { m.content.contains(it) }
}
