package com.shiyin.music.data.ai

import com.shiyin.music.data.search.FuzzySearch

/**
 * v1.3.12:用户这句话对"播放"到底有没有**执行意图** —— 纯 Kotlin,零 Android,可单测。
 *
 * 为什么必须有这一层(真机实测的根因):
 * 弱模型把"**语义上和歌曲有关**"当成了"**用户要我播放**"。于是
 *
 *     上一轮:放一下 Pretender   → 播放成功
 *     这一轮:秋天就喜欢听些苦情歌。→ 模型:"The user wants to play Pretender..." → 又放了一遍
 *
 * 规则 2「感慨/聊音乐感受不要调工具」早就写在 prompt 里,但**光靠 prompt 挡不住弱模型**。
 * 所以这里给"执行型工具"补一道**确定性闸门**:LLM 说要放,也要先过这一关。
 *
 * 判据刻意保守(定调:误播放 < 漏掉少量播放请求):
 *  - 必须有**明确动作词**(播放/放一下/来一首/我想听/换一首…),或**明确控制词**(暂停/下一首…);
 *  - 只有陈述/感想/评论(喜欢/很适合/好听/伤感/想起…)一律 [Intent.NONE];
 *  - **更正/延续上一条指令**(「我说的是单曲」「不是这张」)算指令 —— 用户是在改命令,
 *    不是闲聊;把它当闲聊会让用户觉得"说了半天它没听见"。
 *
 * 分工(重要):本文件**只做文本判定**,不做任何执行决策、更不生成任何面向用户的文案。
 * "要不要执行 / 要不要问用户 / 怎么回话" 由 MainViewModel(闸门 + clarify 卡片)与
 * 技能自己(幂等、放单曲还是整张)负责。
 *
 * 注意:它**不**判断"该不该放这首特定的歌",只回答"用户这句话是不是在指挥播放"。
 */
object PlaybackIntentGuard {

    /** 用户消息里的播放意图。 */
    enum class Intent {
        /** 明确要放某个/某类内容(可带对象,也可不带):play, 放一下X, 我想听X, 来一首X, 换一首。 */
        PLAY,
        /** 明确要求**重新开始**:重新播放, 从头, 再放一遍, 再来一次。 */
        RESTART,
        /** 明确的播放器控制指令:暂停, 继续, 下一首, 上一首, 切歌。 */
        CONTROL,
        /** 陈述 / 感想 / 评论 / 闲聊 —— **不是**指令。 */
        NONE,
    }

    // ── 词表(刻意保守:宁可漏掉,也不要误放) ──────────────────────────────

    /** 明确"重新开始"的说法。必须先于 PLAY 判定(「再放一遍」也含"放一遍")。 */
    private val RESTART = listOf(
        "重新播放", "重新放", "重放", "再播放", "从头", "再来一次", "再来一遍",
        "再放一遍", "再听一遍", "再放一次", "再听一次",
    )

    /** 播放器控制指令:语义无歧义,不涉及"用户是不是在评论"。 */
    private val CONTROL = listOf(
        "暂停", "下一首", "上一首", "切歌", "换一首", "换首歌", "换个歌", "换首",
        "继续播放", "接着放", "接着听", "继续放", "快进", "快退", "单曲循环", "随机播放",
    )

    /**
     * 明确的"要放内容"动作词。
     *
     * 有两点必须守住:
     *  1. 覆盖自然说法(定调:「我想听 X」也算命令,不能只认"播放");
     *  2. **不能**把"评论"误收进来 ——「喜欢听些苦情歌」「这首歌前奏很好听」里都有"听",
     *     但它们不是指令。所以词表只收**带动作形态**的组合(放一下/来一首/我想听/让我听/点一首),
     *     而不是裸的"听"或"喜欢"。
     */
    private val ACTION = listOf(
        // 直接动作
        "播放", "放一下", "放一首", "放个", "放点", "放几首",
        "来一首", "来首", "来点", "来一个", "点一首", "点歌",
        // 意愿式(自然表达,仍是明确要放)
        "我想听", "想听", "给我放", "帮我放", "让我听", "放给我听",
        // 轻量祈使
        "听一下", "听听这首", "听听这", "听听", "放一段", "来一段",
    )

    /**
     * **更正 / 延续上一条播放指令**的说法。
     *
     * 真机 bug(比"感想被误当命令"更难受的一种):使用场景「给我播放某歌手的某歌」,
     * 系统放成了同名**专辑**的整张;用户接着说「我说的是单曲」——这是在**纠正**,
     * 但词表里没有动作词,闸门判成闲聊把它拦掉,用户看到的是"说了半天它没听见"。
     * 这类一律按 PLAY 放行:闸门只该拦"纯感想",没有资格拦"用户在改上一条命令"。
     */
    private val CORRECTION = listOf(
        "我说的是", "我是说", "我说的那首", "我说的这", "不是这张", "不是专辑", "不是这个",
        "是单曲", "要的是", "搞错", "弄错", "听错了", "认错", "换这首", "换那首", "换这张",
        "重放这首", "放这首", "这首歌才", "应该是",
    )

    /**
     * 这句话的播放意图。**不继承上一轮**——每轮独立判断(定调:
     * 上一轮执行过 PLAY,不能自动成为这一轮的 PLAY)。
     */
    fun classify(userText: String): Intent {
        val t = FuzzySearch.normalize(userText)
        if (t.isEmpty()) return Intent.NONE
        if (RESTART.any { t.contains(it) }) return Intent.RESTART
        // 更正优先于控制/动作判定:它同样是"要你做事",不能落进 NONE
        if (CORRECTION.any { t.contains(it) }) return Intent.PLAY
        if (CONTROL.any { t.contains(it) }) return Intent.CONTROL
        if (ACTION.any { t.contains(it) }) return Intent.PLAY
        return Intent.NONE
    }

    /**
     * 从**需求原话**确定性推断"要单曲还是整张"。
     *
     * 为什么不能只依赖模型填 scope:真机实测——用户已经明说「我说的是单曲」,模型却没填 scope,
     * 运行时的歧义判定就又弹了一次选择卡片("我都说了单曲你还问")。使用场景清楚的事,
     * 系统必须自己看得见:[ALBUM_WORDS]/[TRACK_WORDS] 命中就定死,**不再问**。
     *
     * 返回 null = 用户没说 → 有歧义时才交给 clarify 问。
     */
    fun userScope(userText: String): String? {
        val t = FuzzySearch.normalize(userText)
        if (t.isEmpty()) return null
        if (ALBUM_WORDS.any { t.contains(it) }) return SCOPE_ALBUM
        if (TRACK_WORDS.any { t.contains(it) }) return SCOPE_TRACK
        return null
    }

    const val SCOPE_ALBUM = "album"
    const val SCOPE_TRACK = "track"

    private val ALBUM_WORDS = listOf("专辑", "整张", "全张", "整碟", "整盘")
    private val TRACK_WORDS = listOf("单曲", "这首歌", "这首", "这一首", "那首歌", "首歌", "一首歌")

    /**
     * 歌名比对:剥括号副标题 + 全半角/繁简/大小写归一。
     *
     * ⚠️ 包含判定必须带**长度下限**:否则 target=「夜」会被判成与正在播放的《夜に駆ける》
     * 是同一首 → 用户明明要换一首歌,却被当成"已经在放了"(真机 bug 的放大器:短歌名/单字
     * 在中文日文曲库里太常见)。长度 ≥ [SAME_TRACK_MIN_CONTAIN] 才允许包含判定。
     */
    internal fun sameTrack(a: String, b: String): Boolean {
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        return minOf(x.length, y.length) >= SAME_TRACK_MIN_CONTAIN && (x.contains(y) || y.contains(x))
    }

    private const val SAME_TRACK_MIN_CONTAIN = 3

    private val BRACKET_SUFFIX = Regex("[(（\\[【].*?[)）\\]】]")

    private fun norm(s: String): String = FuzzySearch.normalize(BRACKET_SUFFIX.replace(s, ""))
}
