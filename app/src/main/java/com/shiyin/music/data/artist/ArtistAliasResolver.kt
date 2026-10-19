package com.shiyin.music.data.artist

import com.shiyin.music.data.MediaScanner

/**
 * **歌手别名（合并）在"复合艺人串"上的解析**（纯逻辑，可 JVM 单测）。
 *
 * ### 它修的 bug（用户 2026-10-19）
 * > "如果专辑 1 是 AAA/bbb 的专辑，我合并 bbb→BBB 就不会有任何反应，只能把专辑信息
 * >  AAA/bbb 改为 AAA/BBB 才能识别出来这张专辑到 BBB。"
 *
 * 根因：解析时拿**整串**去查别名表（`aliases["AAA/bbb"]`），而别名表的键是**单个歌手名**
 * （合并对话框里选的是 "bbb"）—— 复合串永远查不到，合并等于没做。
 *
 * ### 规则
 *  1. 先按**整串**查一次（兼容"整个复合名一起合并"的用法）；
 *  2. 再按 [MediaScanner.splitArtists] 的分隔符拆开，**逐段**过别名表；
 *  3. 用 `", "` 接回去（与全 App 的显示口径一致）。
 */
object ArtistAliasResolver {

    /** @return 显示用的歌手串 */
    fun resolve(raw: String, aliases: Map<String, String>): String {
        if (raw.isBlank()) return raw
        val whole = aliases[raw] ?: raw
        // 逐段过别名表；**别名目标本身也可能是复合串**（把 A 并成 "X/Y"）⇒ 再拆一层。
        // 只拆一层，不追别名链 —— 追链会引入环（A→B→A）这类需要额外终止条件的问题。
        return MediaScanner.splitArtists(whole)
            .flatMap { part ->
                val target = aliases[part]
                if (target == null) listOf(part) else MediaScanner.splitArtists(target)
            }
            .joinToString(", ")
    }
}
