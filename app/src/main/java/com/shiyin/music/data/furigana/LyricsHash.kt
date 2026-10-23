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
     * 翻译按旧 hash 存的恢复不到(实测反馈"翻译完退出后重新加载丢失")。
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
        return digest(textOnly)
    }

    /**
     * **旧算法**(v1.3.x 之前):只做行尾归一,不剥时间戳。
     *
     * 保留它**只为迁移**:算法改成功后,按新 hash 查不到升级前存下的注音/译文
     * (用户表现为"我的注音/译文不见了")。加载歌词时用旧 hash 兜底找一次,
     * 找到就把那些行改写到新 hash 下(见 MainViewModel.setCurrentLyrics)。
     * **新写入一律用 [of]**。
     */
    fun ofLegacy(raw: String): String = digest(raw.replace("\r\n", "\n").replace('\r', '\n').trimEnd())

    /**
     * 这份歌词的两个算法是否给出不同 hash(= 需要搬键)。
     * 无时间戳/无缩进的歌词两者相同 —— 绝大多数曲目**不需要**任何迁移。
     */
    fun needsMigration(raw: String): Boolean = of(raw) != ofLegacy(raw)

    private fun digest(text: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(text.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(16)
        for (i in 0 until 8) {
            val b = digest[i].toInt() and 0xFF
            sb.append(HEX[b ushr 4]).append(HEX[b and 0x0F])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}

/** 一次搬键：把 [from] 名下的行改写到 [to] 名下。 */
data class HashMigration(val from: String, val to: String)

/**
 * v1.4.0：算"这份歌词要不要搬键、从哪个 hash 搬到哪个"。**null = 不需要**。
 *
 * 三种情形各有测试钉住：
 *  - 歌词不含时间戳/缩进 → 两个算法同值 → null（绝大多数曲目走这条，不碰库）；
 *  - 含时间戳（旧版本存下的行用的是 [LyricsHash.ofLegacy] 的值）→ 给出 from/to；
 *  - 新 hash 名下**已经有行**（用户升级后又存取过）→ null，旧行属下堂。**这条防的是
 *    UPDATE 撞主键**：三张表的 PK 都含 lyricsHash，硬搬会直接报约束冲突。
 *
 * @param rowsUnderNewHash 该曲在新 hash 名下已有的行数（调用方查库后传入）
 */
internal fun planHashMigration(raw: String, rowsUnderNewHash: Int): HashMigration? {
    if (!LyricsHash.needsMigration(raw)) return null
    if (rowsUnderNewHash > 0) return null
    return HashMigration(from = LyricsHash.ofLegacy(raw), to = LyricsHash.of(raw))
}
