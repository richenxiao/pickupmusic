package com.shiyin.music.data

/**
 * v1.5.0 扫描性能（B2④）：ensureArtistEntities 的纯逻辑部分（过滤/去重/链接构建），
 * 与 DB 读写解耦，便于 JVM 单测。
 *
 * 过滤规则与旧实现逐对跳过保持一致（见 MainViewModel.ensureArtistEntities v3.0）：
 * 空白名 / [UNKNOWN_ARTIST] / 与专辑同名（疑似 MediaStore 元数据错误）一律不建歌手实体。
 */
object ArtistWritePlanner {

    /**
     * 从 (trackId, artistName) 原始对里滤出合格对。
     * 返回 (合格对保持原顺序, 去重后的歌手名列表(保持出现顺序))。
     */
    fun filterPairs(
        pairs: List<Pair<Long, String>>,
        albumNamesLower: Set<String>,
    ): Pair<List<Pair<Long, String>>, List<String>> {
        val kept = ArrayList<Pair<Long, String>>(pairs.size)
        val names = LinkedHashSet<String>()
        for ((trackId, name) in pairs) {
            if (name.isBlank() || name == UNKNOWN_ARTIST) continue
            if (name.lowercase().trim() in albumNamesLower) continue
            kept += trackId to name
            names += name
        }
        return kept to names.toList()
    }

    /**
     * 由合格对 + name→id 映射构建 (trackId, artistId) 链接；
     * 映射缺失的名字跳过（与旧实现 `?: continue` 一致）。
     */
    fun buildLinks(
        pairs: List<Pair<Long, String>>,
        idByName: Map<String, Int>,
    ): List<Pair<Long, Int>> {
        val out = ArrayList<Pair<Long, Int>>(pairs.size)
        for ((trackId, name) in pairs) {
            val id = idByName[name] ?: continue
            out += trackId to id
        }
        return out
    }
}
