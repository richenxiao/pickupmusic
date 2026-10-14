package com.shiyin.music.data.karaoke

/**
 * v1.5.0 块 2：**分块调度 —— "切到哪算到哪"**（纯逻辑，可单测）。
 *
 * 目标：点伴唱后**先冲出播放头之后的一小段**，让伴奏尽快能淡入；之后继续往前；
 * 用户一拖动就**立刻改道**到新位置 —— 而不是傻算整首。
 *
 * ⚠️ 这里一律用**源文件帧号**，与"哪首歌/哪个 songId"无关 —— 这正是块 2 把缓存键
 * 改成物理文件维度的原因：拆分单曲的各段共用同一个文件，帧号才是同一套坐标。
 */
object StemSchedule {

    /** 一批派发多少块。太小 → 每秒都要重新决策；太大 → 拖动后改道不及时。 */
    const val BATCH = 8

    /**
     * 下一个该算的块，按优先级从高到低。
     *
     * 顺序：从**播放头所在块**起向前扫出一批缺块；若没凑满，再从 0 往前补**任何**缺块
     * （既保证"最终会算完"，也让"往回拖"能命中）。
     *
     * @param playFrame   播放头所在的**源文件帧号**（<0 视作 0）
     * @param genSize     一块多少帧
     * @param totalChunks 总块数
     * @param hasChunk    该块是否已算好
     * @return 待算块号；空 = 全齐
     */
    fun nextChunks(
        playFrame: Long,
        genSize: Int,
        totalChunks: Int,
        hasChunk: (Int) -> Boolean,
        batch: Int = BATCH,
    ): List<Int> {
        if (genSize <= 0 || totalChunks <= 0 || batch <= 0) return emptyList()
        val from = chunkOf(playFrame, genSize, totalChunks)
        val out = ArrayList<Int>(batch)
        var i = from
        while (i < totalChunks && out.size < batch) {
            if (!hasChunk(i)) out.add(i)
            i++
        }
        var j = 0
        while (j < from && out.size < batch) {
            if (!hasChunk(j)) out.add(j)
            j++
        }
        return out
    }

    /** 播放头所在块号（UI / 日志用）。 */
    fun chunkOf(playFrame: Long, genSize: Int, totalChunks: Int): Int {
        if (genSize <= 0 || totalChunks <= 0) return 0
        return ((if (playFrame < 0) 0L else playFrame) / genSize).toInt().coerceIn(0, totalChunks - 1)
    }
}
