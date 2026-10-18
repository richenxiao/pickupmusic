package com.shiyin.music.data.karaoke

/**
 * P0-B：**分离块的调度顺序**与**切换门槛**（纯逻辑，无 Android 依赖，可 JVM 单测）。
 *
 * ### 现场症状（用户 2026-10-18）
 * "切到伴奏之后又露出一两秒人声，然后又回到伴奏。"
 *
 * ### 成因（读码 + 模拟复现，见 `KaraokeChunkScheduleTest`）
 * 一块覆盖 5.78 秒音频，实测算一块要 2.37 秒。切档发生在"**播放头所在的那块**写好的瞬间"，
 * 而播放头可能正好落在**那块 95% 的位置** —— 此时缓存只领先 0.29 秒，下一块还要 2.37 秒才落盘，
 * 中间那 ~2 秒处理器读不到伴奏 ⇒ **欠载 ⇒ 系数拉回原曲**（你听到的就是"人声又回来了"），
 * 等下一块写好再淡回伴奏。分毫不差地对上"露出一两秒人声再回到伴奏"。
 *
 * 另有一个更慢的问题：分离**从第 0 块开始算**。播放头在 3 分钟处时，得先算 31 块（≈74 秒）
 * 才轮到你现在听的位置。
 *
 * ### 三条对策
 *  1. [order]：**从播放头所在块起算**（往前留 [REWIND_MARGIN_SECONDS] 一小段），算完再绕回开头
 *     —— 先服务你现在听到的地方；
 *  2. 逐块循环本身就是"首块好了立刻算下一块"，不额外等待（见 [MdxSeparator.run] 的 `order`）；
 *  3. [leadSeconds] 与 [SWITCH_LEAD_SECONDS]：**缓存领先量不足 N 秒就不切档** ——
 *     声音继续是原曲、按钮继续转圈。宁可晚一点，也不要"切过去又漏人声"。
 */
object KaraokeChunkSchedule {

    /** 缓存/播放头换算所在的域（模型域恒 44.1k）。 */
    const val MODEL_RATE = 44100

    /**
     * **切换门槛（秒）**：缓存至少要领先播放头这么多，才允许切到伴奏。用户定 4 秒，可调。
     *
     * 为什么是 4：一块要 2.37 秒才落盘，4 秒留了约 1.7 倍余量；再小就挡不住"播放头落在块尾"
     * 那种最坏情况（那一瞬只领先 0.29 秒）。
     */
    @Volatile
    var SWITCH_LEAD_SECONDS: Double = 4.0

    /**
     * 起点往前留的一小段（秒）。作用：让**第一块覆盖播放头之前也有一点点**，
     * 刚切过去时立刻往回拖几秒不会立刻欠载。取 2 秒是"略小于一块"的量级。
     */
    const val REWIND_MARGIN_SECONDS = 2.0

    /**
     * 分离的**处理顺序**：从「播放头 − [REWIND_MARGIN_SECONDS]」所在块开始，算到末尾，
     * 再绕回开头补 `[0, begin)`。
     *
     * 播放头未知（<0）时**退回从头顺序** —— 不猜。
     *
     * @return 每个元素是块号，长度恒为 `total`
     */
    fun order(total: Int, gen: Int, playModelFrame: Long): List<Int> {
        if (total <= 0) return emptyList()
        if (gen <= 0 || playModelFrame < 0) return (0 until total).toList()
        val back = (REWIND_MARGIN_SECONDS * MODEL_RATE).toLong()
        val from = (playModelFrame - back).coerceAtLeast(0L)
        val begin = (from / gen).toInt().coerceIn(0, total - 1)
        return (begin until total).toList() + (0 until begin).toList()
    }

    /**
     * 缓存**领先播放头多少秒** —— 从播放头所在块起，连续已写好的块一直延伸到哪。
     *
     * 语义（三条都要钉住，调用方靠它决定"能不能切"）：
     *  - 播放头那块**还没写好** ⇒ **0.0**（不是负数：这时"切过去"就是立刻欠载，不该切）；
     *  - 播放头已过全曲末尾 ⇒ 0.0；
     *  - 领先量按**连续**算：中间缺一块就到此为止（缺块处处理器同样读不到）。
     */
    fun leadSeconds(
        modelFrames: Int,
        gen: Int,
        playModelFrame: Long,
        has: (Int) -> Boolean,
    ): Double {
        if (gen <= 0 || modelFrames <= 0 || playModelFrame < 0) return 0.0
        val total = chunkCount(modelFrames, gen)
        val playChunk = (playModelFrame / gen).toInt()
        if (playChunk >= total) return 0.0
        if (!has(playChunk)) return 0.0
        var c = playChunk
        while (c + 1 < total && has(c + 1)) c++
        val end = minOf((c + 1).toLong() * gen, modelFrames.toLong())
        return (end - playModelFrame).toDouble() / MODEL_RATE
    }

    /** 总块数（与 [MdxSeparator.run] / [SeparationJob.totalChunksFor] 同一口径）。 */
    fun chunkCount(totalFrames: Int, gen: Int): Int {
        if (totalFrames <= 0 || gen <= 0) return 0
        val pad = if (totalFrames % gen == 0) gen else gen - (totalFrames % gen)
        return (totalFrames + pad) / gen
    }
}
