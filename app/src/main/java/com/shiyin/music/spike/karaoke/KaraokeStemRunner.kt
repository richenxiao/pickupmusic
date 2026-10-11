package com.shiyin.music.spike.karaoke

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.BatteryManager
import android.os.Debug
import android.provider.MediaStore
import com.shiyin.music.data.Track
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.File
import java.io.OutputStream
import kotlin.math.roundToInt

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

            onStatus("导出准备…")
            val safeTitle = track.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40)
            val tag = "${model.name.lowercase()}-${backend.name.lowercase()}-t$threads"
            val (accUri, accOut0) = openWav(context, "$safeTitle-$tag-伴奏.wav", targetFrames)
            val (vocUri, vocOut0) = openWav(context, "$safeTitle-$tag-人声.wav", targetFrames)
            val accOut = accOut0 ?: error("无法写入伴奏文件（MediaStore 拒绝）")
            val vocOut = vocOut0 ?: error("无法写入人声文件（MediaStore 拒绝）")
            accStream = accOut; vocStream = vocOut
            accName = accUri?.lastPathSegment
            vocName = vocUri?.lastPathSegment

            onStatus("分离中…")
            reader = AudioDecoder.PcmFileReader(info)
            val accW = DataOutputStream(accOut.buffered(1 shl 16))
            val vocW = DataOutputStream(vocOut.buffered(1 shl 16))

            val sep = MdxSeparator(model, env, session)
            val sink = object : MdxSeparator.ChunkSink {
                override fun onChunk(
                    accL: FloatArray, accR: FloatArray,
                    vocL: FloatArray, vocR: FloatArray,
                    offsetInSong: Int, count: Int,
                ) {
                    writeChunk(accW, accL, accR, count)
                    writeChunk(vocW, vocL, vocR, count)
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
            accW.flush(); vocW.flush()

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
        } finally {
            reader?.close()
            runCatching { accStream?.close() }
            runCatching { vocStream?.close() }
            runCatching { session?.close() }
            tmp.delete()
        }
    }

    private fun writeChunk(out: DataOutputStream, l: FloatArray, r: FloatArray, count: Int) {
        for (i in 0 until count) {
            out.writeShort(toPcm16(l[i]))
            out.writeShort(toPcm16(r[i]))
        }
    }

    private fun ensureModelFile(context: Context, model: MdxModel): File {
        val f = File(context.cacheDir, model.assetName)
        if (!f.exists()) {
            context.assets.open(model.assetName).use { input ->
                f.outputStream().buffered().use { input.copyTo(it) }
            }
        }
        return f
    }

    /** 建 MediaStore 条目并写好 WAV 头（总长已知），返回 (uri, 输出流)。 */
    private fun openWav(context: Context, name: String, totalFrames: Int): Pair<Uri?, OutputStream?> {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/PickUpMusic")
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null to null
        val out = context.contentResolver.openOutputStream(uri) ?: return uri to null
        out.write(wavHeader(totalFrames))
        return uri to out
    }

    private fun wavHeader(frames: Int): ByteArray {
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
