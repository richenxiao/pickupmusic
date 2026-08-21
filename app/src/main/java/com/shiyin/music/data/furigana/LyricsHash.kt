package com.shiyin.music.data.furigana

import java.security.MessageDigest

/**
 * 歌词文本 hash（V1.1 Song Override 绑定用）。
 *
 * Song Override 绑 mediaId + lyricsHash，使「换歌词源 / 改歌词文本」后旧修正自动失效
 * （hash 变 → 不命中 → 不复用旧注音）。同一版本 raw 文本则稳定命中。
 *
 * 实现：对 raw 歌词做行尾归一（\r\n→\n、trim 尾部空白）后取 SHA-256 前 16 hex 字符
 * （64 bit，个人曲库规模下碰撞可忽略）。归一避免无意义差异（如编辑器加尾空格）误判失效。
 * 不对歌词做语义规整——用户改文本（如修错字）本就该让旧 override 失效。
 */
object LyricsHash {
    fun of(raw: String): String {
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n').trimEnd()
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(normalized.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(16)
        for (i in 0 until 8) {
            val b = digest[i].toInt() and 0xFF
            sb.append(HEX[b ushr 4]).append(HEX[b and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
