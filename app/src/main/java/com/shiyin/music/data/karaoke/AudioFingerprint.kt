package com.shiyin.music.data.karaoke

import java.security.MessageDigest

/**
 * v1.5.0 P1：**音频内容指纹**——缓存身份的一部分（规格 P1 修正 一.4）。
 *
 * 关键约束（用户明确要求）：**只能依赖音频内容本身**，不得依赖文件大小、mtime、
 * 整文件哈希。原因：本应用会往音频文件里写封面/歌词标签（ID3 等），写完标签后
 * 文件的字节与大小都会变，但**音频一点没变** —— 缓存必须仍然命中。
 *
 * 做法：取音频**解码后的前 N 帧 PCM**（默认 20 秒）算 SHA-256。标签写在容器元数据里，
 * 不进解码输出；而 songId 已在缓存路径里，指纹的职责只是"同一个 songId 背后的音频
 * 是否被换掉了"——前 20 秒足以判定。
 *
 * 纯函数，可单测（见 AudioFingerprintTest）。
 */
object AudioFingerprint {

    /** 参与指纹的前导帧数（20 秒 @44.1k）。 */
    const val LEAD_FRAMES = 44_100 * 20

    private const val VERSION = 1

    /**
     * @param pcm 交错立体声 16bit（L,R,L,R…），长度 ≥ frames*2
     * @param frames 有效帧数（不足 LEAD_FRAMES 时按实际）
     */
    fun of(pcm: ShortArray, frames: Int): String {
        val n = minOf(frames, pcm.size / 2)
        val md = MessageDigest.getInstance("SHA-256")
        md.update("karaoke-pcm-v$VERSION:".toByteArray(Charsets.US_ASCII))
        val buf = ByteArray(4096)
        val perBatch = buf.size / 4        // 每帧 4 字节（L,R 各 int16）
        var i = 0
        while (i < n) {
            val end = minOf(i + perBatch, n)
            var b = 0
            for (f in i until end) {
                val l = pcm[f * 2].toInt()
                val r = pcm[f * 2 + 1].toInt()
                buf[b++] = (l and 0xFF).toByte()
                buf[b++] = ((l shr 8) and 0xFF).toByte()
                buf[b++] = (r and 0xFF).toByte()
                buf[b++] = ((r shr 8) and 0xFF).toByte()
            }
            md.update(buf, 0, b)
            i = end
        }
        return md.digest().joinToString("") { "%02x".format(it) }.take(16)
    }
}
