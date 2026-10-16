package com.shiyin.music.data.karaoke

import java.io.File

/**
 * A75：伴唱「就绪缓存目录」的**会话级寿命**（纯逻辑，**可 JVM 单测**）。
 *
 * ### 它回答的唯一问题
 * **再点伴唱时能不能直接复用已经算好的目录** —— 也就是能不能跳过
 * `KaraokeCacheJob.start` 里那段"**解码整首 + 重建 ONNX 会话**"的前奏。
 * （分离本身不会重算：`SeparationJob` 的 `shouldProcess = { !cache.hasChunk(...) }` 已跳过
 * 已写好的块；浪费的**只**是前奏。用户看到的就是"取消后再点又开始转圈"。）
 *
 * ### 三条规则（每条都对应真机上出过的事）
 *  1. **取消 / 回原曲：不清。** 已算好的块还在盘上，下次点应当秒切。
 *  2. **换曲：清。** 目录属于**上一首**；留着会让处理器按绝对帧号去读上一首的伴奏
 *     （A71 就是这么来的）。
 *  3. **源文件变了：不复用。** 同一首的 id 可能不变，但文件被替换过 —— 用**廉价**身份
 *     （路径 + 字节数 + 加入时间）挡一道。注意这只是廉价闸门；**权威**的指纹校验在
 *     [StemCache.findComplete] 里（用解码出来的音频指纹），那道要解码，这里刻意不做。
 */
class KaraokeReadyCache {

    private var dir: File? = null
    private var ownerTrackId: Long = Long.MIN_VALUE
    private var ownerSource: String = ""

    /** 分离"首段可用"：记下这个目录属于谁。 */
    fun mark(dir: File, trackId: Long, sourceIdentity: String) {
        this.dir = dir
        this.ownerTrackId = trackId
        this.ownerSource = sourceIdentity
    }

    /**
     * 取消伴唱 / 切回原曲时调用。**有意什么都不做** —— 见类注释规则 1。
     * 之所以留一个显式的方法而不是让调用方"什么都不写"：让"这里不该清"成为一个**可读的决定**，
     * 而不是一个看起来像漏了的地方。
     */
    fun keepAcrossStop() = Unit

    /** 换曲：只有真的换了曲目才清（单曲循环重播不清）。 */
    fun onTrackChanged(trackId: Long) {
        if (trackId != ownerTrackId) clear()
    }

    /** 能复用就返回目录，否则 null（调用方应走完整的分离流程）。 */
    fun reusableFor(trackId: Long, sourceIdentity: String): File? =
        dir?.takeIf { trackId == ownerTrackId && sourceIdentity == ownerSource }

    fun clear() {
        dir = null
        ownerTrackId = Long.MIN_VALUE
        ownerSource = ""
    }
}
