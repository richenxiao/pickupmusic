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
import java.io.File
import java.io.OutputStream

/**
 * P0 Spike 阶段 B：一次完整跑批（解码 → 分离 → 导出 WAV + 指标）。
 * 全部逻辑留在 spike 包，P1 会用正式的 SeparationJob/StemCache/InferenceBackend 取代。
 */
object KaraokeStemRunner {

    class Result(
        val model: MdxModel,
        val outUri: Uri?,
        val vocUri: Uri?,
        val durationSec: Float,
        val decodeMs: Long,
        val modelLoadMs: Long,
        val firstChunkMs: Long,
        val totalMs: Long,
        val inferMsTotal: Long,
        val pssBeforeMb: Long,
        val pssAfterMb: Long,
        val tempBeforeC: Float?,
        val tempAfterC: Float?,
    ) {
        val rtf: Float get() = if (durationSec <= 0f) 0f else (totalMs / 1000f) / durationSec
    }

    suspend fun run(
        context: Context,
        track: Track,
        model: MdxModel,
        threads: Int,
        onStatus: (String) -> Unit,
        onProgress: (MdxSeparator.Progress, Float) -> Unit,
    ): Result = withContext(Dispatchers.Default) {
        val pssBefore = Debug.getPss() / 1024
        val tempBefore = readBatteryTemp(context)

        onStatus("解码中…")
        val tDecode = System.nanoTime()
        val pcm = AudioDecoder.decodeToStereo44k(context, track.uri) { p ->
            onProgress(MdxSeparator.Progress(0, 0, -1, 0, 0), p)
        }
        val decodeMs = (System.nanoTime() - tDecode) / 1_000_000
        val durationSec = pcm.left.size / 44100f

        onStatus("加载模型…")
        val modelFile = ensureModelFile(context, model)
        val tLoad = System.nanoTime()
        val env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply { setIntraOpNumThreads(threads) }
        val session = env.createSession(modelFile.absolutePath, opts)
        val modelLoadMs = (System.nanoTime() - tLoad) / 1_000_000

        onStatus("分离中…")
        val sep = MdxSeparator(model, env, session)
        val tSep = System.nanoTime()
        var firstChunkMs = -1L
        var inferMsTotal = 0L
        val stems = sep.separate(pcm) { p ->
            firstChunkMs = p.firstChunkMs
            inferMsTotal = p.inferMsTotal
            onProgress(p, p.chunkDone.toFloat() / p.chunkTotal.toFloat())
        }
        val sepMs = (System.nanoTime() - tSep) / 1_000_000

        session.close()

        onStatus("导出 WAV…")
        val safeTitle = track.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(40)
        val tag = "${model.name.lowercase()}-t$threads"
        val accUri = writeWav(context, "$safeTitle-$tag-伴奏.wav", stems.accL, stems.accR)
        val vocUri = writeWav(context, "$safeTitle-$tag-人声.wav", stems.vocL, stems.vocR)

        Result(
            model = model,
            outUri = accUri,
            vocUri = vocUri,
            durationSec = durationSec,
            decodeMs = decodeMs,
            modelLoadMs = modelLoadMs,
            firstChunkMs = firstChunkMs,
            totalMs = sepMs,
            inferMsTotal = inferMsTotal,
            pssBeforeMb = pssBefore,
            pssAfterMb = Debug.getPss() / 1024,
            tempBeforeC = tempBefore,
            tempAfterC = readBatteryTemp(context),
        )
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

    /** 导出 16bit PCM WAV 到「音乐/PickUpMusic」下（MediaStore，无需权限、任意播放器可见）。 */
    private fun writeWav(context: Context, name: String, left: FloatArray, right: FloatArray): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "audio/wav")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Music/PickUpMusic")
            put(MediaStore.Audio.Media.IS_MUSIC, 1)
        }
        val uri = context.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: return null
        context.contentResolver.openOutputStream(uri)?.use { out -> writeWavStream(out, left, right) }
        return uri
    }

    private fun writeWavStream(out: OutputStream, left: FloatArray, right: FloatArray) {
        val n = minOf(left.size, right.size)
        val dataBytes = n * 4 // 2ch × 16bit
        val buf = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray(Charsets.US_ASCII))
        buf.putInt(36 + dataBytes)
        buf.put("WAVE".toByteArray(Charsets.US_ASCII))
        buf.put("fmt ".toByteArray(Charsets.US_ASCII))
        buf.putInt(16)             // fmt chunk size
        buf.putShort(1)            // PCM
        buf.putShort(2)            // channels
        buf.putInt(44100); buf.putInt(44100 * 4); buf.putShort(4); buf.putShort(16)
        buf.put("data".toByteArray(Charsets.US_ASCII))
        buf.putInt(dataBytes)
        out.write(buf.array())

        val block = java.nio.ByteBuffer.allocate(1 shl 16).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i < n) {
            block.clear()
            val end = minOf(i + (block.capacity() / 4), n)
            while (i < end) {
                block.putShort(toPcm16(left[i])); block.putShort(toPcm16(right[i])); i++
            }
            out.write(block.array(), 0, block.position())
        }
    }

    private fun toPcm16(v: Float): Short =
        (v.coerceIn(-1f, 1f) * 32767f).toInt().toShort()

    private fun readBatteryTemp(context: Context): Float? = runCatching {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val tenths = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        if (tenths > 0) tenths / 10f else null
    }.getOrNull()
}
