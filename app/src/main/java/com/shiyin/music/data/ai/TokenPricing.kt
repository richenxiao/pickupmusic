package com.shiyin.music.data.ai

/**
 * Token 成本的**估算**单价（元 / 1M tokens）。
 *
 * ⚠️ 这是估算，不是账单。各家的价格会变，还有阶梯价、缓存价、活动价、按模型分档 ——
 * 这里的数字只是**量级参考**，用于让用户看出"这个月大概花了多少"的趋势。
 * UI 必须标注「约 / 估算」，不能当准确金额展示。
 *
 * 想改价：直接改下面的表即可（每个供应商一行）。缓存命中价按输入价的 1/10 取 ——
 * DeepSeek 等厂商的缓存命中价就是这个量级，且能显著拉低成本。
 *
 * 为什么要单独抽出来：原来的成本算法写死在 UI 里（`(in*0.5 + out*1.5)/1e6*7.2`），
 * 注释却写着"按激活供应商价表估算"—— 实际没读任何价表、汇率也写死，
 * 对 OpenRouter / 智谱 / 通义完全不准，而且**缓存命中的 token 一分钱都没算进成本**。
 */
object TokenPricing {

    data class Rate(
        /** 输入（未命中缓存）单价。 */
        val inputPerM: Double,
        /** 输出单价。 */
        val outputPerM: Double,
        /** 计价币种："CNY" 或 "USD"。 */
        val currency: String = "CNY",
    ) {
        val symbol: String get() = if (currency == "USD") "$" else "¥"
    }

    /** 人民币计价的供应商。 */
    private val CNY: Map<String, Rate> = mapOf(
        "deepseek" to Rate(2.0, 8.0),
        "zhipu" to Rate(1.0, 4.0),
        "qwen" to Rate(0.8, 2.0),
    )

    /** 美元计价的供应商。 */
    private val USD: Map<String, Rate> = mapOf(
        "openrouter" to Rate(1.0, 3.0, "USD"),
    )

    /** 未知/自定义供应商的兜底价（偏保守，宁可高估也不低估）。 */
    private val FALLBACK = Rate(1.0, 3.0, "USD")

    /** 美元折算人民币的固定汇率（仅用于把不同币种统一到同一根成本轴上）。 */
    const val USD_TO_CNY = 7.2

    /** 缓存命中价相对输入价的折扣。 */
    const val CACHE_DISCOUNT = 0.1

    fun rateFor(providerKey: String): Rate =
        CNY[providerKey] ?: USD[providerKey] ?: FALLBACK

    /**
     * v1.3.10 A19: 该供应商是否有**登记过的**价表。
     * false = 走 [FALLBACK] 兜底价 —— 面板必须把这类成本标成"量级参考",
     * 不能让用户以为自定义供应商的"约 ¥x"是算准的(用户反馈"准度不够")。
     */
    fun hasKnownRate(providerKey: String): Boolean =
        CNY.containsKey(providerKey) || USD.containsKey(providerKey)

    /**
     * v1.3.10 A19: 缓存命中**省下的钱**(命中部分本按输入原价计,实际只按 1/10 计)。
     * 面板作为一个正面数字展示("缓存省了 ¥x"),而不是只画一条没人看得懂的灰线。
     *
     * [cacheTok] 是输入的子集,先夹到 [inTok] 内——脏数据(缓存数大于输入数)不该报出超额节省。
     */
    fun cacheSavingsCny(providerKey: String, inTok: Long, cacheTok: Long): Double {
        val r = rateFor(providerKey)
        val fx = if (r.currency == "USD") USD_TO_CNY else 1.0
        val cached = cacheTok.coerceIn(0L, inTok.coerceAtLeast(0L))
        return cached * r.inputPerM * (1 - CACHE_DISCOUNT) / 1_000_000.0 * fx
    }

    /**
     * 估算成本（统一折算成元，便于与曲线同一坐标轴）。
     *
     * 注意 [cacheTok] 是**输入 token 的子集**（`prompt_tokens_details.cached_tokens`），
     * 所以要先从输入里扣掉再按折扣价计，**不能**把输入与缓存相加。
     */
    fun estimateCny(providerKey: String, inTok: Long, outTok: Long, cacheTok: Long): Double {
        val r = rateFor(providerKey)
        val fx = if (r.currency == "USD") USD_TO_CNY else 1.0
        val cached = cacheTok.coerceIn(0L, inTok.coerceAtLeast(0L))
        val freshInput = (inTok - cached).coerceAtLeast(0L)
        val perM = 1_000_000.0
        return (freshInput * r.inputPerM + cached * r.inputPerM * CACHE_DISCOUNT + outTok * r.outputPerM) / perM * fx
    }

    /** 给 UI 显示的假设说明（让用户知道这个"约 ¥x"是按什么算的，而不是黑箱）。 */
    fun assumptionText(providerKey: String): String {
        val r = rateFor(providerKey)
        val sym = r.symbol
        val fxNote = if (r.currency == "USD") "，按 $USD_TO_CNY 折算" else ""
        // v1.3.10 A19: 自定义/未知供应商走的是兜底价,明说,别让数字显得比实际可信。
        val fallbackNote = if (hasKnownRate(providerKey)) "" else "（未登记价表，按兜底价）"
        return "按输入 $sym${trim(r.inputPerM)}/百万、输出 $sym${trim(r.outputPerM)}/百万估算$fxNote$fallbackNote"
    }

    private fun trim(v: Double): String =
        if (v == v.toLong().toDouble()) v.toLong().toString() else "%.2f".format(v)
}
