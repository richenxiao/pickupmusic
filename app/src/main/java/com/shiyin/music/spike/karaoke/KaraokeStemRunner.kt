package com.shiyin.music.spike.karaoke

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import com.shiyin.music.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.math.roundToInt
import com.shiyin.music.data.karaoke.AudioDecoder
import com.shiyin.music.data.karaoke.MdxModel
import com.shiyin.music.data.karaoke.MdxSeparator
import com.shiyin.music.data.karaoke.OrtBackend

/**
 * P0 Spike 阶段 B：一次完整跑批（解码落盘 → 流式分离 → 边算边写 WAV + 指标）。
 *
 * 峰值内存 ≈ 一个块（约 40~60MB），不再整首驻留——2026-10-11 真机 OOM 后改为流式
 * （原实现把整首解码/输出/两轨全放内存，4 分钟歌 ≈ 250MB > 256MB 堆上限）。
 */
object KaraokeStemRunner {

    class Result(
        val model: MdxModel,
        val backend: OrtBackend,
        val accName: String?,
        val vocName: String?,
        val durationSec: Float,
        val decodeMs: Long,
        val modelLoadMs: Long,
        val firstChunkMs: Long,
        val totalMs: Long,
        val inferMsTotal: Long,
        val heapBeforeMb: Long,
        val heapAfterMb: Long,
        val nativeHeapPeakMb: Long,
        val tempBeforeC: Float?,
        val tempAfterC: Float?,
        /** 每块耗时（ms），用于评估"能不能赶上播放" */
        val chunkTimesMs: LongArray,
        val chunkAudioSec: Float,
        val avgChunkMs: Long,
        val maxChunkMs: Long,
        val overBudgetChunks: Int,
    ) {
        val rtf: Float get() = if (durationSec <= 0f) 0f else (totalMs / 1000f) / durationSec
    }

    suspend fun run(
        context: Context,
        track: Track,
        model: MdxModel,
        threads: Int,
        backend: OrtBackend,
        onStatus: (String) -> Unit,
        onProgress: (String, Float) -> Unit,
    ): Result = withContext(Dispatchers.Default) {
        val heapBeforeMb = usedHeapMb()
        var peakNativeMb = nativeHeapMb()
        val tempBefore = readBatteryTemp(context)

        val tmp = File(context.cacheDir, "karaoke_src.pcm")

        onStatus("解码中…")
        val tDecode = System.nanoTime()
        val info = AudioDecoder.decodeToPcm16File(context, track.uri, tmp) { p ->
            onProgress("解码 ${(p * 100).toInt()}%", p)
        }
        val decodeMs = (System.nanoTime() - tDecode) / 1_000_000

        val targetFrames = (info.frames * 44100.0 / info.srcRate).roundToInt()
        val durationSec = targetFrames / 44100f

        var accStream: OutputStream? = null
        var vocStream: OutputStream? = null
        var accName: String? = null
        var vocName: String? = null
        var session: OrtSession? = null
        var reader: AudioDecoder.PcmFileReader? = null
        // 导出对照表要记「这次跑成没成」——提前设成失败态，成功路径末尾改成 ok
        var status = "failed"
        val token = DebugExport.newToken()

        try {
            onStatus("加载模型…")
            val modelFile = ensureModelFile(context, model)
            val tLoad = System.nanoTime()
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(threads)
                when (backend) {
                    OrtBackend.CPU -> Unit
                    OrtBackend.XNNPACK -> addXnnpack(mapOf("intra_op_num_threads" to "$threads"))
                    OrtBackend.NNAPI -> addNnapi()
                    OrtBackend.NNAPI_FP16 -> addNnapi(java.util.EnumSet.of(ai.onnxruntime.providers.NNAPIFlags.USE_FP16))
                }
            }
            session = env.createSession(modelFile.absolutePath, opts)
            val modelLoadMs = (System.nanoTime() - tLoad) / 1_000_000

            // 调试导出（隔离在 Download/PickUpMusic-debug/，见 DebugExport）。
            // 关掉导出时照样跑分离（指标仍要测），只是不落 WAV。
            val accW: DataOutputStream?
            val vocW: DataOutputStream?
            if (DebugExport.ENABLED) {
                onStatus("导出准备…")
                val (a, v) = DebugExport.open(context, token)
                val accSink = a ?: error("创建伴奏导出文件失败（${DebugExport.DIR}）")
                val vocSink = v ?: error("创建人声导出文件失败（${DebugExport.DIR}）")
                accStream = accSink.out; vocStream = vocSink.out
                accName = accSink.name; vocName = vocSink.name
                accSink.out.write(wavHeader(targetFrames))
                vocSink.out.write(wavHeader(targetFrames))
                accW = DataOutputStream(accSink.out.buffered(1 shl 16))
                vocW = DataOutputStream(vocSink.out.buffered(1 shl 16))
            } else {
                accW = null; vocW = null
            }

            onStatus("分离中…")
            reader = AudioDecoder.PcmFileReader(info)

            val sep = MdxSeparator(model, env, session)
            val sink = object : MdxSeparator.ChunkSink {
                override fun onChunk(
                    accL: FloatArray, accR: FloatArray,
                    vocL: FloatArray, vocR: FloatArray,
                    offsetInSong: Int, count: Int,
                ) {
                    accW?.let { writeChunk(it, accL, accR, count) }
                    vocW?.let { writeChunk(it, vocL, vocR, count) }
                }
            }

            var firstChunkMs = 0L
            var inferMsTotal = 0L
            val sepStart = System.nanoTime()
            val t = sep.run(targetFrames, reader, sink) { p ->
                firstChunkMs = p.firstChunkMs
                inferMsTotal = p.inferMsTotal
                if (p.chunkDone % 5 == 0) peakNativeMb = maxOf(peakNativeMb, nativeHeapMb())
                onProgress(
                    "块 ${p.chunkDone}/${p.chunkTotal} · 本块 ${p.lastChunkMs}ms · " +
                        "平均 ${p.avgChunkMs}ms · 领先 ${"%.1f".format(p.leadSec)}s",
                    p.chunkDone.toFloat() / p.chunkTotal,
                )
            }
            accW?.flush(); vocW?.flush()
            status = "ok"

            Result(
                model = model,
                backend = backend,
                accName = accName,
                vocName = vocName,
                durationSec = durationSec,
                decodeMs = decodeMs,
                modelLoadMs = modelLoadMs,
                firstChunkMs = t.firstChunkMs.takeIf { it >= 0 } ?: firstChunkMs,
                totalMs = (System.nanoTime() - sepStart) / 1_000_000,
                inferMsTotal = t.inferMsTotal.takeIf { it > 0 } ?: inferMsTotal,
                heapBeforeMb = heapBeforeMb,
                heapAfterMb = usedHeapMb(),
                nativeHeapPeakMb = maxOf(peakNativeMb, nativeHeapMb()),
                tempBeforeC = tempBefore,
                tempAfterC = readBatteryTemp(context),
                chunkTimesMs = t.chunkTimesMs,
                chunkAudioSec = t.chunkAudioSec,
                avgChunkMs = t.avgChunkMs,
                maxChunkMs = t.maxChunkMs,
                overBudgetChunks = t.overBudgetChunks,
            )
        } catch (t: Throwable) {
            // 取消与真失败要分开记：取消是用户按的（半截文件保留，标 cancelled），
            // 失败是代码/环境问题（标 failed(类型)）——对照表里一眼能分辨。
            status = if (t is java.util.concurrent.CancellationException) "cancelled"
            else "failed(${t.javaClass.simpleName})"
            throw t
        } finally {
            reader?.close()
            runCatching { accStream?.close() }
            runCatching { vocStream?.close() }
            runCatching { session?.close() }
            tmp.delete()
            // 每次运行都留一行账（含取消/失败），失败不抛——账不该把主流程带崩
            if (accName != null || vocName != null) {
                DebugExport.appendRun(
                    context, accName, vocName, model.name, backend.name, threads,
                    "${track.title} — ${track.artist}", durationSec, status,
                )
            }
        }
    }

    /**
     * 写一段交错 16bit 采样到 WAV。
     *
     * ⚠️ 2026-10-11 真机事故（第三次字节序问题）：这里原用 `DataOutputStream.writeShort`
     * ——它是**大端**，而 WAV/PCM 规定**小端** → 导出的文件在播放器里全是噪声
     * （用户描述"像电台搜不到信号"）。导出侧与解码侧必须都显式小端。
     */
    internal fun writeChunk(out: DataOutputStream, l: FloatArray, r: FloatArray, count: Int) {
        for (i in 0 until count) {
            AudioDecoder.writeShortLE(out, toPcm16(l[i]))
            AudioDecoder.writeShortLE(out, toPcm16(r[i]))
        }
    }

    internal fun ensureModelFile(context: Context, model: MdxModel): File {
        val f = File(context.cacheDir, model.assetName)
        if (!f.exists()) {
            // v1.5.0 块 4：**包内只内置 9482**（用户 A57 拍板：<40MB 才自带；另外两个各 66MB，
            // 正式版自带会超线）。所以缺 asset 是**预期情况**，必须给一句人话，
            // 而不是抛一个 FileNotFoundException 让人以为坏了。
            try {
                context.assets.open(model.assetName).use { input ->
                    f.outputStream().buffered().use { input.copyTo(it) }
                }
            } catch (e: java.io.IOException) {
                throw IllegalStateException(
                    "这个模型没有内置在安装包里（${model.displayName}）—— 当前版本只内置 9482",
                    e,
                )
            }
        }
        return f
    }

    internal fun wavHeader(frames: Int): ByteArray {
        val dataBytes = frames * 4 // 2ch × 16bit
        val buf = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataBytes)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)
        buf.putShort(1)      // PCM
        buf.putShort(2)      // 2ch
        buf.putInt(44100); buf.putInt(44100 * 4); buf.putShort(4); buf.putShort(16)
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataBytes)
        return buf.array()
    }

    private fun usedHeapMb(): Long = run {
        val rt = Runtime.getRuntime()
        (rt.totalMemory() - rt.freeMemory()) / 1048576
    }

    private fun nativeHeapMb(): Long = Debug.getNativeHeapAllocatedSize() / 1048576

    private fun toPcm16(v: Float): Int = (v.coerceIn(-1f, 1f) * 32767f).toInt()

    private fun readBatteryTemp(context: Context): Float? = runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        if (tenths > 0) tenths / 10f else null
    }.getOrNull()
}
