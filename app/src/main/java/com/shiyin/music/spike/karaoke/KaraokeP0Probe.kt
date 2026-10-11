package com.shiyin.music.spike.karaoke

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import android.content.Context
import android.os.Build
import android.system.Os
import android.system.OsConstants
import com.shiyin.music.spike.karaoke.dsp.MdxStft
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * P0 Spike A4 手机侧探针：验证「模型能在本机 ORT 上加载并跑通一个 chunk」。
 *
 * 用打包进 APK 的 9482（29.7MB，vocals 模型：对随机噪声应输出近静音——这本身就是
 * 一个粗略的正确性信号，桌面参考输出 peak≈0.02 / mix peak≈1.44）。
 *
 * 正式实现时本类整体删除，由 ModelManager/InferenceBackend 取代（规格 §4）。
 */
object KaraokeP0Probe {

    private const val MODEL_ASSET = "UVR_MDXNET_9482.onnx"
    private const val N_FFT = 6144
    private const val HOP = 1024
    private const val DIM_F = 2048
    private const val DIM_T = 256

    suspend fun runChunk(context: Context): String = withContext(Dispatchers.Default) {
        val log = StringBuilder()
        try {
            log.appendLine("设备 ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            val pageSize = runCatching { Os.sysconf(OsConstants._SC_PAGESIZE) }.getOrDefault(-1)
            log.appendLine("内存页 ${if (pageSize > 0) "$pageSize B" else "未知"}${if (pageSize == 16384L) "（16KB 设备）" else ""}")

            // 1. 模型：assets → cacheDir（ORT 需要文件路径做 mmap）
            if (context.assets.list("").orEmpty().none { it == MODEL_ASSET }) {
                log.appendLine("❌ 模型未打包进 APK（构建时缺少 .karaoke/bundled/$MODEL_ASSET）")
                return@withContext log.toString()
            }
            val t0 = System.nanoTime()
            val modelFile = File(context.cacheDir, MODEL_ASSET)
            context.assets.open(MODEL_ASSET).use { input ->
                modelFile.outputStream().buffered().use { input.copyTo(it) }
            }
            log.appendLine("模型落盘 ${modelFile.length() / 1024 / 1024}MB，耗时 ${ms(t0)}")

            // 2. 加载
            val t1 = System.nanoTime()
            val env = OrtEnvironment.getEnvironment()
            val session = env.createSession(modelFile.absolutePath)
            log.appendLine("模型加载 ${ms(t1)}")

            // 3. 构造一个 chunk 的确定性噪声输入（2 × 261120 采样 = 5.92s @44.1k）
            val chunkSamples = HOP * (DIM_T - 1)
            val rng = Random(2026)
            val mixL = FloatArray(chunkSamples) { (rng.nextFloat() * 2f - 1f) * 0.3f }
            val mixR = FloatArray(chunkSamples) { (rng.nextFloat() * 2f - 1f) * 0.3f }

            // 4. STFT → [1,4,dim_f,dim_t]（ch,ri,f,t，与参考实现一致）
            val t2 = System.nanoTime()
            val stft = MdxStft(N_FFT, HOP)
            val specL = stft.forward(mixL)
            val specR = stft.forward(mixR)
            val stftMs = ms(t2)
            log.appendLine("STFT（双声道）$stftMs")

            val nBins = stft.nBins
            val input = FloatArray(4 * DIM_F * DIM_T)
            for (t in 0 until DIM_T) {
                for (f in 0 until DIM_F) {
                    val src = (t * nBins + f) * 2
                    input[(0 * DIM_F + f) * DIM_T + t] = specL[src]
                    input[(1 * DIM_F + f) * DIM_T + t] = specL[src + 1]
                    input[(2 * DIM_F + f) * DIM_T + t] = specR[src]
                    input[(3 * DIM_F + f) * DIM_T + t] = specR[src + 1]
                }
            }

            // 5. 推理 ×3（第 1 次含预热）
            val shape = longArrayOf(1, 4, DIM_F.toLong(), DIM_T.toLong())
            var output: FloatArray? = null
            for (round in 1..3) {
                OnnxTensor.createTensor(env, FloatBuffer.wrap(input), shape).use { tensor ->
                    val t3 = System.nanoTime()
                    session.run(mapOf("input" to tensor)).use { result ->
                        val buf = (result[0] as OnnxTensor).floatBuffer
                        val out = FloatArray(buf.remaining()).also { buf.get(it) }
                        log.appendLine("推理 #${round} ${ms(t3)}")
                        output = out
                    }
                }
            }
            val out = output!!

            // 6. iSTFT 还原 +  sanity：9482 是人声模型，噪声输入应输出近静音
            val t4 = System.nanoTime()
            var peak = 0f
            var sumSq = 0.0
            var n = 0
            for (ch in 0..1) {
                val spec = FloatArray(DIM_T * nBins * 2)
                for (t in 0 until DIM_T) {
                    for (f in 0 until DIM_F) {
                        spec[(t * nBins + f) * 2] = out[((ch * 2) * DIM_F + f) * DIM_T + t]
                        spec[(t * nBins + f) * 2 + 1] = out[((ch * 2 + 1) * DIM_F + f) * DIM_T + t]
                    }
                }
                val wave = stft.inverse(spec, DIM_T)
                for (v in wave) { peak = maxOf(peak, abs(v)); sumSq += v * v; n++ }
            }
            log.appendLine("iSTFT（双声道）${ms(t4)}")
            log.appendLine("输出 peak=${"%.4f".format(peak)} rms=${"%.5f".format(sqrt(sumSq / n))}（桌面参考 peak≈0.020）")
            log.appendLine(if (peak < 0.2f) "✅ 输出量级与桌面参考一致（噪声无人声→近静音）" else "⚠️ 输出量级异常，需对拍排查")

            session.close()
        } catch (t: Throwable) {
            log.appendLine("❌ ${t.javaClass.simpleName}: ${t.message}")
        }
        log.toString()
    }

    private fun ms(t0: Long) = "${(System.nanoTime() - t0) / 1_000_000}ms"
}
