package com.shiyin.music.data.karaoke.dsp

import org.jtransforms.fft.FloatFFT_1D
import kotlin.math.PI
import kotlin.math.cos

/**
 * P0 Spike A3：MDX-Net 流水线的 STFT/iSTFT，语义逐一对齐 torch.stft / torch.istft
 * （参考实现：UVR separate.py / python-audio-separator 的 MDX 处理，见
 * tools/karaoke/ref_uvr_mdx_infer_separate.py）。
 *
 * 已用 tools/karaoke/stft_mirror.py 的 numpy 镜像与 torch 对拍确认约定：
 *  - 窗：周期 Hann，w[n] = 0.5 - 0.5·cos(2πn/N)（periodic=True）
 *  - center=True：两端各 reflect 补 N/2（**边缘不重复**），istft 后再裁掉
 *  - 无归一化（normalized=False）；istft 用窗平方和包络除法（包络 <1e-11 不除）
 *
 * 布局：频谱 = nFrames × nBins × 2（re, im 交错），nBins = n_fft/2+1（onesided）。
 * 长度关系：nFrames = 1 + L/hop；inverse 返回 (nFrames-1)·hop 个采样，
 * 与 torch 默认（length=null）一致。
 *
 * 线程：非线程安全（内部复用 FloatFFT_1D 与缓冲）。每个推理线程各持有一个实例。
 */
class MdxStft(val nFft: Int, val hop: Int) {

    val nBins: Int = nFft / 2 + 1

    private val fft = FloatFFT_1D(nFft.toLong())
    private val window = FloatArray(nFft) {
        (0.5 - 0.5 * cos(2.0 * PI * it / nFft)).toFloat()
    }
    private val wsq = FloatArray(nFft) { window[it] * window[it] }
    private val buf = FloatArray(2 * nFft)

    /** 与 torch reflect pad 一致：k<0 → x[-k]；k≥L → x[2L-2-k]（边缘不重复，可多重反射）。 */
    internal fun reflectIndex(k: Int, len: Int): Int {
        var i = k
        while (i < 0 || i >= len) {
            i = if (i < 0) -i else 2 * len - 2 - i
        }
        return i
    }

    fun frameCount(inputSamples: Int): Int = 1 + inputSamples / hop

    /** x → 频谱（nFrames × nBins × 2，re/im 交错）。 */
    fun forward(x: FloatArray): FloatArray {
        val pad = nFft / 2
        val nFrames = frameCount(x.size)
        val out = FloatArray(nFrames * nBins * 2)
        for (t in 0 until nFrames) {
            val start = t * hop - pad
            for (i in 0 until nFft) {
                buf[2 * i] = x[reflectIndex(start + i, x.size)] * window[i]
                buf[2 * i + 1] = 0f
            }
            fft.complexForward(buf)
            System.arraycopy(buf, 0, out, t * nBins * 2, nBins * 2)
        }
        return out
    }

    /** 频谱 → 时域（(nFrames-1)·hop 采样，含 center 裁剪与包络除法）。 */
    fun inverse(spec: FloatArray, nFrames: Int): FloatArray {
        require(spec.size == nFrames * nBins * 2) { "spec size mismatch" }
        val pad = nFft / 2
        val outLen = (nFrames - 1) * hop + nFft
        val out = FloatArray(outLen)
        val env = FloatArray(outLen)
        for (t in 0 until nFrames) {
            // onesided → 全谱：X[N-k] = conj(X[k])
            val base = t * nBins * 2
            buf.fill(0f)
            System.arraycopy(spec, base, buf, 0, nBins * 2)
            for (k in nBins until nFft) {
                buf[2 * k] = buf[2 * (nFft - k)]
                buf[2 * k + 1] = -buf[2 * (nFft - k) + 1]
            }
            fft.complexInverse(buf, true) // true = 除以 N（与 numpy/torch 的 irfft 一致）
            val s = t * hop
            for (i in 0 until nFft) {
                out[s + i] += buf[2 * i] * window[i]
                env[s + i] += wsq[i]
            }
        }
        for (i in 0 until outLen) {
            if (env[i] > 1e-11f) out[i] /= env[i]
        }
        return out.copyOfRange(pad, outLen - pad)
    }
}
