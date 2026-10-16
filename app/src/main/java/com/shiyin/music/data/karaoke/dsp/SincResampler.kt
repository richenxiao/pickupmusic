package com.shiyin.music.data.karaoke.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * **多相窗 sinc 重采样器**（纯逻辑、无 Android 依赖，**可 JVM 单测**）。
 *
 * ### 为什么必须有它（P0-1 升调问题的后半段）
 * 分离引擎只在 **44.1kHz 模型域**里工作（模型的输入必须是 44.1k）。所以对一首 48k 的歌：
 * ```
 *   源域 48k ──(provider 重采样)──▶ 44.1k 模型域 ──(分离)──▶ 44.1k 伴奏
 *                                                              │
 *   播放按**源帧号**取伴奏 ◀──────────────────────────────────┘   ← 这里必须是 44.1k→48k
 * ```
 * 少了最后这一步，44.1k 的伴奏按 48k 播出来就是**升调 + 加速** `48/44.1 = 1.0884`
 * （`12·log2(1.0884) ≈ +1.47` 半音，与用户实测一致）。
 *
 * ### 为什么不用线性插值
 * 线性插值在 44.1k→48k 这种上采样里会把高频段做成"阶梯"，等效一个 sinc² 低通 + 镜像分量；
 * 听感上正是**沙沙/毛刺**那一类。这里用 **16 抽头 Blackman 窗 sinc**，阻带足够低。
 * 降采样（源率 < 44.1k）时把截止频率按 `outRate/inRate` 缩放，避免折叠。
 *
 * ### 成本
 * 相位表在构造时算好（默认 128 相位 × 16 抽头）。音频线程上每个输出采样 ≈16 次乘加：
 * 一块 4608 帧 × 双声道 ≈ 15 万次乘加 ≈ 0.1ms 量级，相对 100ms 的块长可忽略。
 */
class SincResampler(
    val inRate: Int,
    val outRate: Int,
    baseTaps: Int = 16,
    val phases: Int = 128,
) {
    /**
     * 实际抽头数：**降采样时翻倍**。
     * 折叠抑制需要更陡的过渡带 —— 16 抽头的 Blackman 窗 sinc 只够上采样用；
     * 我们主路径是上采样（44.1k 缓存 → ≥44.1k 播放），降采样只出现在源率 < 44.1k 的少数文件上。
     */
    val taps: Int = if (outRate < inRate) baseTaps * 2 else baseTaps

    init {
        require(inRate > 0 && outRate > 0) { "采样率必须为正：in=$inRate out=$outRate" }
        require(baseTaps >= 4 && baseTaps % 2 == 0) { "抽头数必须是 ≥4 的偶数：$baseTaps" }
        require(phases >= 2) { "相位数至少 2：$phases" }
    }

    /** 每个**输出**采样前进多少个**输入**采样。 */
    val step: Double = inRate.toDouble() / outRate.toDouble()

    /** 是否恒等（源率与缓存率一致）——此时调用方应走**直通快路径**，逐位不变。 */
    val isIdentity: Boolean = inRate == outRate

    private val half = taps / 2

    /** 归一化截止：降采样时按输出/输入缩放，避免折叠；上采样时为 1。 */
    private val cutoff: Double = if (outRate < inRate) outRate.toDouble() / inRate else 1.0

    /** 相位表：`table[phase][k]`，每行已归一化到和为 1（消掉 DC 起伏）。 */
    private val table: Array<DoubleArray> = Array(phases) { p ->
        val frac = p.toDouble() / phases
        val row = DoubleArray(taps)
        var sum = 0.0
        for (k in 0 until taps) {
            val t = (k - half + 1) - frac          // 相对当前采样点的距离（输入采样为单位）
            val x = cutoff * t
            val sinc = if (abs(x) < 1e-9) 1.0 else sin(PI * x) / (PI * x)
            // Blackman 窗（|t| ≤ half）
            val w = if (abs(t) >= half) 0.0
            else 0.42 + 0.5 * cos(PI * t / half) + 0.08 * cos(2.0 * PI * t / half)
            row[k] = cutoff * sinc * w
            sum += row[k]
        }
        if (sum != 0.0) for (k in 0 until taps) row[k] /= sum
        row
    }

    /**
     * 把交错的 int16 输入重采样成交错的 int16 输出。
     *
     * @param src 交错 `[L,R]` 输入（调用方需在需要的范围外**各留 [half] 帧余量**；
     *            越界抽头按 0 计，余量不足会在边界留下极短的淡入淡出）
     * @param srcFirst 输出第 0 帧对应的**输入位置**（输入帧为单位，可为小数/负）
     * @param outFrames 输出帧数
     * @param dst 交错 `[L,R]` 输出，长度 ≥ `outFrames*2`
     */
    fun process(src: ShortArray, srcFirst: Double, outFrames: Int, dst: ShortArray) {
        val srcFrames = src.size / 2
        var pos = srcFirst
        for (n in 0 until outFrames) {
            val i0 = Math.floor(pos).toInt()
            val frac = pos - i0
            val ph = (frac * phases).toInt().coerceIn(0, phases - 1)
            val h = table[ph]
            var l = 0.0
            var r = 0.0
            for (k in 0 until taps) {
                val idx = i0 - half + 1 + k
                if (idx < 0 || idx >= srcFrames) continue
                val w = h[k]
                l += src[idx * 2] * w
                r += src[idx * 2 + 1] * w
            }
            dst[n * 2] = clamp16(l)
            dst[n * 2 + 1] = clamp16(r)
            pos += step
        }
    }

    private fun clamp16(v: Double): Short {
        val i = Math.round(v)
        return (if (i > 32767L) 32767L else if (i < -32768L) -32768L else i).toShort()
    }
}
