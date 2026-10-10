package com.shiyin.music.data.translate

import android.util.Log

/**
 * 需求6「歌词本翻译」**AI 路径的编排器**。
 *
 * 设计要点：LLM 调用以 [callLlm] 注入，本类不依赖 Android / LlmClient / 网络——
 * 于是"整歌一次调用 → 行号协议解析 → 缺行补齐 → 汇总"这条最容易出错的链路
 * 可以用假实现做完整单测（见 `LyricsTranslationPipelineTest`），真机只需验网络与体感。
 *
 * 与离线路径（[LyricsTranslationEngine]）的关系：AI 路径**不需要**句组拼接/拆回——
 * 行号协议本身保证行数对应，逐行译文由模型直接给出，对齐风险比离线低一个量级。
 *
 * 四层防御（对应方案 §3）：
 * 1. 行号协议 → 违规表现为**可检测的缺行号**，而非不可检测的整体错位；
 * 2. 缺行号 → 带语境重发一次小请求补齐；
 * 3. 截断（长歌撞 max_tokens）→ 已收到的照常入账，其余走补齐；
 * 4. 纯文本"行号|文本"而非 JSON → 避开歌词引号/标点密集导致的转义翻车。
 */
class LyricsTranslationPipeline(
    /** 注入的 LLM 调用：返回模型原始输出；null = 调用失败（网络/未配置/服务端拒绝）。 */
    private val callLlm: suspend (messages: List<Pair<String, String>>, maxTokens: Int) -> String?,
) {

    /** 翻译结果。 */
    data class Outcome(
        /** 行号（0-based，对应歌词列表下标）→ 译文。缺行号 = 该行无译文。 */
        val byLine: Map<Int, String>,
        /** 本次请求翻译的行号（0-based，升序）。 */
        val requested: List<Int>,
        /** 实际发生的 LLM 调用轮数（1 = 一次成功，未触发补齐）。 */
        val rounds: Int,
        /** 协议违规计数：同行号重复出现。 */
        val duplicates: Int,
        /** 模型返回的、不在请求范围内的行号（0-based）——它在自行编号，值得警惕。 */
        val outOfRange: List<Int>,
        /** 非 null = 整体失败（调用失败 / 一个可解析译文都没拿到）。 */
        val failure: String? = null,
    ) {
        val translatedCount: Int get() = byLine.size

        /** 覆盖率 = 已译行数 / 请求行数。用于 UI 提示与"质量是否可信"的判断。 */
        val coverage: Float
            get() = if (requested.isEmpty()) 1f else byLine.size.toFloat() / requested.size
    }

    /**
     * 翻译整首歌。
     *
     * 注意参数顺序：回调 [onProgress] 放在最后，调用方才能用尾随 lambda 写法
     * （`p.translate(t, a, lines) { done, total -> ... }`）。
     *
     * @param maxRounds 最多调用轮数。1 = 只发一次；3（默认）= 一次全量 + 最多两轮
     *                  缺行补齐（两轮之间模型可能只部分补回——第一补齐轮漏的行
     *                  第二补齐轮还有机会捞回）。
     * @param onProgress 已译行数 / 总行数的实时回调（流式与补齐后都会回调），供
     *                   "AI 翻译中… 已译 23/58 行" 的进度文案。
     */
    suspend fun translate(
        title: String,
        artist: String,
        lines: List<String>,
        maxRounds: Int = 3,
        /** A56：跳过制作人行（作词/作曲/编曲/`XX 演奏` 等）。默认开，见设置·歌词本。 */
        skipCredits: Boolean = true,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Outcome {
        val entries = LyricsTranslationProtocol.contentEntries(lines, skipCredits)
        val requested = entries.map { it.first }
        // 语种用于提示词措辞("把日语/韩语/外语歌词翻译…");判定逻辑在 protocol。
        val language = LyricsTranslationProtocol.classifyLanguage(lines)
        if (requested.isEmpty()) {
            // 区分两种"没得译":
            // ① 原文本来就没有可译内容 → 保持静默(按钮转一下就回原样);
            // ② 判成欧美歌曲、但**每一行都含汉字**(双语对照 LRC:每行都是"英文+中文")
            //    → 全部被当作用户语言过滤掉了。这种情况必须给出失败文案,否则表现
            //    为"点了完全没反应"——与用户上一轮抱怨的"按钮不管用了"是同一种症状,
            //    而 Outcom.failure=null 还会让上面那条"失败原因透出"的设计失效。
            val failure = if (language == LyricsTranslationProtocol.SongLanguage.Latin) {
                "这首歌的歌词都是中文（或中英混排），不需要翻译"
            } else {
                null
            }
            return Outcome(emptyMap(), emptyList(), 0, 0, emptyList(), failure)
        }

        val total = requested.size
        onProgress(0, total)

        val byLine = LinkedHashMap<Int, String>()
        var rounds = 0
        var duplicates = 0
        val outOfRange = LinkedHashSet<Int>()
        // 最后一轮的模型原始输出,仅在"一行都没解析出来"时打进日志(见方法末尾)
        var lastRaw: String? = null

        // ── 第 1 轮：整歌一次调用 ────────────────────────────────────────────
        rounds++
        val firstRaw = invoke(
            LyricsTranslationProtocol.buildMessages(title, artist, entries, language),
            LyricsTranslationProtocol.suggestedMaxTokens(total),
        ) ?: return Outcome(emptyMap(), requested, rounds, 0, emptyList(), "AI 翻译调用失败（网络或未配置）")
        lastRaw = firstRaw

        var parsed = LyricsTranslationProtocol.parse(firstRaw, requested)
        byLine.putAll(parsed.byLine)
        duplicates += parsed.duplicates
        outOfRange.addAll(parsed.outOfRange)
        onProgress(byLine.size, total)

        // v1.3.11:第 1 轮**正文整个是空**时不要跑补齐轮 —— 那不是"格式不符",是压根没有正文。
        // 补齐轮的请求形状与第一次一样(同样吃紧的思考余量),只会让用户再干等 8-12 秒后
        // 同样失败(实证日志:rounds=2,两轮都解析出 0 行)。LlmClient 内部已经用双倍
        // 预算重试过一次,这里再发一次纯属浪费等待时间。
        val firstBlank = firstRaw.isBlank()

        // ── 后续轮：只补缺失行 ──────────────────────────────────────────────
        var pending = if (firstBlank) emptyList() else parsed.missing
        while (pending.isNotEmpty() && rounds < maxRounds) {
            rounds++
            val raw = invoke(
                LyricsTranslationProtocol.buildCompletionMessages(title, artist, lines, pending, language = language),
                LyricsTranslationProtocol.suggestedMaxTokens(pending.size),
            ) ?: break // 补齐轮失败不推翻已有成果——已拿到的译文照常返回
            lastRaw = raw
            parsed = LyricsTranslationProtocol.parse(raw, pending)
            byLine.putAll(parsed.byLine)
            duplicates += parsed.duplicates
            outOfRange.addAll(parsed.outOfRange)
            // 只保留仍未拿到的；若本轮毫无进展则跳出，避免白烧 token
            val stillMissing = parsed.missing
            if (stillMissing.size == pending.size) break
            pending = stillMissing
            onProgress(byLine.size, total)
        }

        // v1.3.11:把"正文全空"(思考吃光预算被截断)与"有正文但解析不出行"分开报 ——
        // 两者的修法不同(前者换模型/加预算,后者看格式),混成一句会让用户与开发者都在猜。
        val failure = when {
            byLine.isNotEmpty() -> null
            firstBlank -> "模型没有返回译文正文（思考占满了输出预算）"
            else -> "AI 未返回可解析的译文（可能被拒答或格式不符）"
        }
        if (failure != null || byLine.size < total) {
            Log.w(
                TAG,
                "ai translate: ${byLine.size}/$total lines, rounds=$rounds " +
                    "dup=$duplicates outOfRange=${outOfRange.size} failure=$failure",
            )
        }
        // 一行都没解析出来时,把模型原始输出的开头打进日志。
        // 这是"格式不符"类失败唯一能自证的证据——用户把 logcat 发回来就能直接定位
        // 是"模型拒答""加了 markdown""还是换了别的格式",不用再靠猜。
        if (failure != null && lastRaw != null) {
            Log.w(TAG, "raw head(400): ${lastRaw!!.take(400).replace('\n', '⏎')}")
        }
        return Outcome(byLine, requested, rounds, duplicates, outOfRange.sorted(), failure)
    }

    /**
     * 调 LLM 并兜住异常（不让编排逻辑被网络层异常打断）。
     *
     * **CancellationException 必须原样抛出**：取消（用户再点一次译按钮 / 切歌）在协程里
     * 就是以 CancellationException 表现的。若被这里当成"调用失败"吞掉，编排会继续往下跑
     * 补齐轮——用户已经取消了，却还在烧 token 和流量。
     */
    private suspend fun invoke(messages: List<Pair<String, String>>, maxTokens: Int): String? =
        try {
            callLlm(messages, maxTokens)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "llm call threw: ${e.message}")
            null
        }

    private companion object {
        const val TAG = "LyricsTranslate"
    }
}
