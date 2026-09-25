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
    /**
     * v1.3.x 打轴修复:打轴后 raw 里包含 [mm:ss.xx] 时间戳,导致 hash 变化,
     * 翻译按旧 hash 存的恢复不到(用户反馈"翻译完退出后重新加载丢失")。
     * 修复:hash 只基于歌词**文本内容**——剥掉行首 [mm:ss.xx] 时间戳,
     * 打轴不改文本内容 → hash 不变 → 翻译正常恢复。
     */
    fun of(raw: String): String {
        val normalized = raw.replace("\r\n", "\n").replace('\r', '\n').trimEnd()
        // 剥掉每行的 [mm:ss.xx] 时间戳前缀——时间戳是元数据,不是歌词内容。
        // 打轴后 raw 里多了时间戳,但歌词文本没变,hash 不该变。
        val textOnly = normalized.lines().joinToString("\n") { line ->
            val t = line.trim()
            // 匹配 [mm:ss.xx] 或 [mm:ss] 前缀(含前导空格),剥掉后保留纯文本
            t.replace(Regex("""^\[\d{1,3}:\d{1,2}(?:[.:]\d{1,3})?]\s*"""), "")
        }
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(textOnly.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(16)
        for (i in 0 until 8) {
            val b = digest[i].toInt() and 0xFF
            sb.append(HEX[b ushr 4]).append(HEX[b and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
