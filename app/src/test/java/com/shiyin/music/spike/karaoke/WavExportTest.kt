package com.shiyin.music.spike.karaoke

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream

/**
 * P0 Spike 阶段 B：WAV 导出字节序。
 *
 * 为什么单独立测：2026-10-11 真机事故——`DataOutputStream.writeShort` 是**大端**，
 * 而 WAV/PCM 规定**小端**，于是导出文件在播放器里全是噪声（用户："像电台搜不到信号"）。
 * 手机导出路径此前无测试覆盖，故补此测试把"导出侧必须小端"钉死。
 */
class WavExportTest {

    private fun bytesOf(f: (DataOutputStream) -> Unit): ByteArray {
        val bos = ByteArrayOutputStream()
        DataOutputStream(bos).use { f(it) }
        return bos.toByteArray()
    }

    @Test
    fun samplesAreLittleEndian() {
        // 0.5 → 16383 = 0x3FFF → 小端字节 [0xFF, 0x3F]；−0.5 → −16383 = 0xC001 → [0x01, 0xC0]
        val l = floatArrayOf(0.5f, -0.5f)
        val r = floatArrayOf(0f, 1f)
        val bytes = bytesOf { out -> KaraokeStemRunner.writeChunk(out, l, r, 2) }

        assertEquals("L0 低字节", 0xFF.toByte(), bytes[0])
        assertEquals("L0 高字节", 0x3F.toByte(), bytes[1])
        assertEquals("R0 低字节", 0x00.toByte(), bytes[2])
        assertEquals("R0 高字节", 0x00.toByte(), bytes[3])
        assertEquals("L1 低字节", 0x01.toByte(), bytes[4])
        assertEquals("L1 高字节", 0xC0.toByte(), bytes[5])
        // 1.0 会被 clamp 到 32767 = 0x7FFF → [0xFF, 0x7F]
        assertEquals("R1 低字节", 0xFF.toByte(), bytes[6])
        assertEquals("R1 高字节", 0x7F.toByte(), bytes[7])
    }

    @Test
    fun headerIsLittleEndianAndSized() {
        val frames = 1000
        val h = KaraokeStemRunner.wavHeader(frames)

        assertEquals(44, h.size)
        assertEquals("RIFF", String(h, 0, 4, Charsets.US_ASCII))
        assertEquals("WAVE", String(h, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(h, 12, 4, Charsets.US_ASCII))
        assertEquals("data", String(h, 36, 4, Charsets.US_ASCII))

        fun leInt(off: Int) = (h[off].toInt() and 0xFF) or ((h[off + 1].toInt() and 0xFF) shl 8) or
            ((h[off + 2].toInt() and 0xFF) shl 16) or ((h[off + 3].toInt() and 0xFF) shl 24)
        fun leShort(off: Int) = (h[off].toInt() and 0xFF) or ((h[off + 1].toInt() and 0xFF) shl 8)

        val dataBytes = frames * 4
        assertEquals("RIFF 长度 = 36+data", 36 + dataBytes, leInt(4))
        assertEquals("PCM 格式", 1, leShort(20))
        assertEquals("声道数", 2, leShort(22))
        assertEquals("采样率", 44100, leInt(24))
        assertEquals("字节率", 44100 * 4, leInt(28))
        assertEquals("块对齐", 4, leShort(32))
        assertEquals("位深", 16, leShort(34))
        assertEquals("data 长度", dataBytes, leInt(40))
    }
}
