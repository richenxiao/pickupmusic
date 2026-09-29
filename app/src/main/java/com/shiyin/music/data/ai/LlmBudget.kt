package com.shiyin.music.data.ai

/**
 * v1.3.11:思考型模型(reasoning)的**输出预算**规则 —— 纯逻辑,零 Android 依赖。
 *
 * ## 为什么需要这个
 *
 * 用户报"歌词翻译往常 2-3 秒就出来,现在时好时坏,点两三次才成功"。翻日志定案:
 * `deepseek-flash`(思考模型)在 `max_tokens` 里**把思考 token 也算进去** ——
 * 同一个提示词,思考短就正常返回译文,思考一长就把预算吃光,`content` 返回空串。
 *
 * 实证(同一次复现,同一首歌、同一模型):
 * ```
 * 23:17:03  9227ms → "content":"" , reasoning_content:"We need translate..."   ← 失败
 * 23:17:06  2926ms → "content":"1|因为没完没了\n2|只能在这里画上句号..."        ← 成功
 * ```
 * 于是"成功与否"取决于模型这次想了多久 —— 用户感受到的就是"随机"。
 *
 * ## 修法为什么与供应商无关
 *
 * 各家关思考的参数各不相同(`reasoning_effort` / `thinking:{type}` / `enable_thinking` …),
 * 乱传有把别的供应商打挂的风险。这里只用**所有 OpenAI 兼容端点都成立**的事实:
 *  1. 正文空 + 思考非空 → 不是拒答(拒答会有正文),是内容被预算截断;
 *  2. 那就用**双倍预算**再要一次。一次点击最多多花一次调用,把"时好时坏"变成"基本必成"。
 */
internal const val LLM_MAX_OUTPUT_TOKENS = 8192

/**
 * 是否该"加大预算重试一次"。
 *
 * 判据:[content] 为空/全空白,但 [thinking] 非空 —— 思考型模型被预算截断的独有形态。
 * [finishReason] 为 `"length"` 是铁证;部分网关不回该字段,故 null 也放行
 * (拒答场景会有正文,不会误判)。已经顶到 [LLM_MAX_OUTPUT_TOKENS] 就不再重试。
 */
internal fun shouldRetryWithBiggerBudget(
    content: String?,
    thinking: String?,
    finishReason: String?,
    maxTokens: Int,
): Boolean =
    content.isNullOrBlank() &&
        !thinking.isNullOrBlank() &&
        (finishReason == null || finishReason == "length") &&
        maxTokens < LLM_MAX_OUTPUT_TOKENS

/** 双倍预算,封顶 [LLM_MAX_OUTPUT_TOKENS](超上限会被服务端 HTTP 400 拒绝)。 */
internal fun doubledBudget(maxTokens: Int): Int =
    (maxTokens * 2).coerceAtMost(LLM_MAX_OUTPUT_TOKENS)
