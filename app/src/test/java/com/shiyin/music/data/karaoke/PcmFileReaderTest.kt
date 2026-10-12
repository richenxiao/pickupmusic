package com.shiyin.music.data.karaoke

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.DataOutputStream
import java.io.File
import kotlin.math.abs

/**
 * P0 Spike 阶段 B：PCM16 临时文件读取器（手机侧流式路径的入口）。
 *
 * 为什么单独立测：2026-10-11 真机 `ArrayIndexOutOfBoundsException` 就出在这里——
 * 缓冲按"帧数"分配却用交错 `idx*2+1` 索引。手机路径此前完全没有测试覆盖，
 * 所以这个 bug 直到用户跑真机才暴露。此测试把该路径钉住。
 */
class PcmFileReaderTest {

    /** 小端写入，与生产端 AudioDecoder.writeShortLE 一致（大端会字节错位）。 */
    private fun writePcm16(file: File, frames: Int, srcRate: Int, left: (Int) -> Float, right: (Int) -> Float): AudioDecoder.PcmFileInfo {
        DataOutputStream(file.outputStream().buffered()).use { out ->
            fun le(v: Int) = AudioDecoder.writeShortLE(out, v)
            for (i in 0 until frames) {
                le((left(i).coerceIn(-1f, 1f) * 32767f).toInt())
                le((right(i).coerceIn(-1f, 1f) * 32767f).toInt())
            }
        }
        return AudioDecoder.PcmFileInfo(file, srcRate, frames)
    }

    private fun tempFile(): File = File.createTempFile("pcmtest", ".pcm").apply { deleteOnExit() }

    /** 44.1k 源：逐帧精确（等价于采样率一致的直通）。 */
    @Test
    fun readsExactAt44100_withZeroPaddingAtBothEnds() {
        val frames = 5000
        val f = tempFile()
        val info = writePcm16(f, frames, 44100, { i -> i / frames.toFloat() - 0.5f }, { i -> 0.25f - i / frames.toFloat() })
        val reader = AudioDecoder.PcmFileReader(info)
        try {
            val count = 4000
            val l = FloatArray(count); val r = FloatArray(count)
            // 从 −1000 帧开始读：前 1000 帧必须是 0（两端补零语义）
            reader.readChunk(-1000, count, l, r)
            for (i in 0 until 1000) {
                assertEquals("负帧应补零 @$i", 0f, l[i], 0f)
                assertEquals("负帧应补零 @$i", 0f, r[i], 0f)
            }
            for (i in 1000 until count) {
                val src = i - 1000
                assertEquals(src / frames.toFloat() - 0.5f, l[i], 1e-4f)
                assertEquals(0.25f - src / frames.toFloat(), r[i], 1e-4f)
            }
            reader.close()
        } finally {
            f.delete()
        }
    }

    /** 48k 源：按比例线性插值（不越界、幅度合理、单调段保持单调）。 */
    @Test
    fun resamples48kWithRatio() {
        val frames = 96000 // 2 秒 @48k → 88200 帧 @44.1k
        val f = tempFile()
        val info = writePcm16(f, frames, 48000, { i -> i / frames.toFloat() - 0.5f }, { i -> 0f })
        val reader = AudioDecoder.PcmFileReader(info)
        try {
            val count = 88200
            val l = FloatArray(count); val r = FloatArray(count)
            reader.readChunk(0, count, l, r)   // 修复前：这里越界崩溃
            // 线性斜坡经线性插值仍是线性：首末值与原坡度一致
            assertEquals(0f / frames - 0.5f, l[0], 5e-3f)
            val expectedLast = (count - 1) / 44100.0 * 48000 / frames - 0.5
            assertEquals(expectedLast.toFloat(), l[count - 1], 5e-3f)
            for (i in 1 until count) {
                assert(abs(l[i] - l[i - 1]) < 0.01f) { "插值出现跳变 @$i" }
            }
            reader.close()
        } finally {
            f.delete()
        }
    }

    /** 读到歌曲尾之外：补零、不越界。 */
    @Test
    fun readsPastEndWithZeros() {
        val frames = 1000
        val f = tempFile()
        val info = writePcm16(f, frames, 44100, { 0.5f }, { -0.5f })
        val reader = AudioDecoder.PcmFileReader(info)
        try {
            val l = FloatArray(2000); val r = FloatArray(2000)
            reader.readChunk(500, 2000, l, r)
            // 文件只有 1000 帧：读到 frame 500..999 有值，1000 之后补零
            for (i in 0 until 500) assertEquals(0.5f, l[i], 1e-4f)
            for (i in 500 until 2000) assertEquals("尾后应补零 @$i", 0f, l[i], 0f)
            // 立体声两声道不串（此前缓冲不足时这里会读到错误位置）
            for (i in 0 until 500) assertEquals(-0.5f, r[i], 1e-4f)
            for (i in 500 until 2000) assertEquals("尾后应补零(右) @$i", 0f, r[i], 0f)
            reader.close()
        } finally {
            f.delete()
        }
    }

    @Test
    fun repeatedReadsAreStableAcrossChunks() {
        val frames = 30000
        val f = tempFile()
        val info = writePcm16(f, frames, 44100, { i -> (i % 100) / 100f - 0.5f }, { i -> 0.1f })
        val reader = AudioDecoder.PcmFileReader(info)
        try {
            val count = 4096
            val l = FloatArray(count); val r = FloatArray(count)
            val a = FloatArray(count); val b = FloatArray(count)

            reader.readChunk(0, count, a, r)          // 第 1 块
            reader.readChunk(count, count, b, r)      // 第 2 块
            reader.readChunk(0, count, l, r)          // 重读第 1 块
            for (i in 0 until count) assertEquals("第 1 块重读不一致 @$i", a[i], l[i], 0f)
            reader.readChunk(count, count, l, r)      // 重读第 2 块
            for (i in 0 until count) assertEquals("第 2 块重读不一致 @$i", b[i], l[i], 0f)
            reader.close()
        } finally {
            f.delete()
        }
    }
}
