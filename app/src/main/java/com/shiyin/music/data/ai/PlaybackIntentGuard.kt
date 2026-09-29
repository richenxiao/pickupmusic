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
 * 判据刻意保守(用户定调:误播放 < 漏掉少量播放请求):
 *  - 必须有**明确动作词**(播放/放一下/来一首/我想听/换一首…),或**明确控制词**(暂停/下一首…);
 *  - 只有陈述/感想/评论(喜欢/很适合/好听/伤感/想起…)一律 [PlayIntent.NONE] → 拦下。
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

    /** 闸门结论。 */
    sealed interface Decision {
        /** 放行:确实是指令,交给技能执行。 */
        object Allow : Decision
        /**
         * 用户点名的内容**已经在放**了 —— 不要重新从头放。
         * (只有他明确说"重新播放/从头再来"才允许 restart,那种情况走 [Intent.RESTART] → Allow。)
         */
        object AlreadyPlaying : Decision
        /** 这句不是指令(感想/闲聊) —— 禁止执行播放技能,转普通回复。 */
        object BlockNoIntent : Decision
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
     *  1. 覆盖自然说法(用户定调:「我想听 X」也算命令,不能只认"播放");
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
     * 这句话的播放意图。**不继承上一轮**——每轮独立判断(用户定调:
     * 上一轮执行过 PLAY,不能自动成为这一轮的 PLAY)。
     */
    fun classify(userText: String): Intent {
        val t = FuzzySearch.normalize(userText)
        if (t.isEmpty()) return Intent.NONE
        if (RESTART.any { t.contains(it) }) return Intent.RESTART
        if (CONTROL.any { t.contains(it) }) return Intent.CONTROL
        if (ACTION.any { t.contains(it) }) return Intent.PLAY
        return Intent.NONE
    }

    /**
     * 执行型播放技能(playback_control / play_track)的放行判定。
     *
     * @param requestedTarget 模型抽出的播放对象(可能为空:用户只说「继续放」)
     * @param currentTitle    当前正在播放的曲目标题(播放器没在放时传 null)
     * @param isPlaying       播放器此刻是否真的在放
     */
    fun guard(
        userText: String,
        requestedTarget: String?,
        currentTitle: String?,
        isPlaying: Boolean,
    ): Decision {
        when (classify(userText)) {
            // 明确要求重来 → 放行(这是唯一允许 restart 的路径)
            Intent.RESTART -> return Decision.Allow
            // 感想/陈述/闲聊 → 拦下。这里是"上一轮刚放过"最容易误触发的地方。
            Intent.NONE -> return Decision.BlockNoIntent
            Intent.CONTROL -> return Decision.Allow
            Intent.PLAY -> Unit
        }
        // 点名的那首**已经在放** → 不要重新从头放(用户定调:直接说"已经在放了")
        if (isPlaying && !requestedTarget.isNullOrBlank() && !currentTitle.isNullOrBlank() &&
            sameTrack(requestedTarget, currentTitle)
        ) {
            return Decision.AlreadyPlaying
        }
        return Decision.Allow
    }

    /** 歌名比对:剥括号副标题 + 全半角/繁简/大小写归一,再做包含判定。 */
    internal fun sameTrack(a: String, b: String): Boolean {
        val x = norm(a)
        val y = norm(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || x.contains(y) || y.contains(x)
    }

    private val BRACKET_SUFFIX = Regex("[(（\\[【].*?[)）\\]】]")

    private fun norm(s: String): String = FuzzySearch.normalize(BRACKET_SUFFIX.replace(s, ""))
}
