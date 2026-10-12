package com.shiyin.music.playback

/**
 * v1.5.0 P2 spike（分支 `spike/karaoke-p2-processor`）：**帧号 ⇄ 采样值编解码**。
 *
 * 步骤 2 要回答"处理器能不能把当前处理位置对齐到源文件的绝对帧号"。**不能拿
 * `Player.currentPosition` 当参考**——那是播放器自己的估计值，用它去验对齐等于用被测对象验自己。
 * 所以真值必须**从音频数据本身读出来**：
 *
 * 用 [tools/karaoke/make_framenum_fixture.py] 生成一个无损测试文件，把**每一帧的绝对帧号**
 * 编进该帧的两个采样值：
 * ```
 *   写（Python）: L = ( idx        & 0xFFF) - 2048
 *                 R = ((idx >> 12) & 0xFFF) - 2048
 *   读（本类）  : idx = (L + 2048) | ((R + 2048) << 12)
 * ```
 * → 24 位帧号，可无歧义表示 16,777,216 帧（44.1kHz 下 ≈ 380s），够任何一首歌。
 *
 * ⚠️ **两侧必须逐字一致**（负数处理、掩码、位移方向）。Python 侧已有往返自检；
 * Kotlin 侧的一致性由 `KaraokeFrameCodecTest` **直接读 Python 生成的文件**来钉住——
 * 这是跨语言对拍，不是各自自说自话。
 *
 * 代价（写在生成器里，这里重复一次以免误用）：framenum 文件是振幅 ±2048（≈ -24 dBFS）的
 * **测量信号**，不是能听的音频，别开大音量。
 */
object KaraokeFrameCodec {

    /** 每声道承载的位数，12+12=24 位帧号。与生成器的 `--bits` 默认值一致。 */
    const val BITS_DEFAULT = 12

    /** 帧号 → (L, R)。与 Python `encode_frame` 同规则。 */
    fun encodeFrame(idx: Int, bits: Int = BITS_DEFAULT): Pair<Int, Int> {
        val mask = (1 shl bits) - 1
        val bias = 1 shl (bits - 1)
        return ((idx and mask) - bias) to (((idx shr bits) and mask) - bias)
    }

    /** (L, R) → 帧号。与 Python `decode_frame` 同规则；`left`/`right` 是 **int16 有符号值**。 */
    fun decodeFrame(left: Int, right: Int, bits: Int = BITS_DEFAULT): Int {
        val mask = (1 shl bits) - 1
        val bias = 1 shl (bits - 1)
        val lo = (left + bias) and mask
        val hi = (right + bias) and mask
        return (hi shl bits) or lo
    }

    /** 从**交错立体声** int16 PCM 里解出第 [frame] 帧（0-based）自带的帧号。 */
    fun decodeAt(samples: ShortArray, frame: Int, bits: Int = BITS_DEFAULT): Int =
        decodeFrame(samples[frame * 2].toInt(), samples[frame * 2 + 1].toInt(), bits)
}
