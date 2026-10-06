package com.shiyin.music.data

/**
 * v1.5.0 扫描性能（B2①）：决定哪些文件还需要交给 MediaStore 重新索引。
 *
 * 背景（2026-10-07 真机取证）：设置→「重新扫描文件」时 walk 出 4090 个音频文件，
 * 旧实现把它们**全部**丢给 MediaStoreConnection.scanFile 并等回调，其中 2066 个
 * MediaStore 早已收录 —— 12.4s 的等待是纯重复劳动。
 *
 * 语义边界：forceReindex 存在的原因（v2.0）是「新加进来的文件 MediaStore 没索引到」，
 * 那是**新路径**；已被 MediaStore 收录的路径重扫只会让它重新解析一遍标签，不会改变收录结果。
 * 因此在「别的工具改了标签但没动文件」这类边界上，本过滤与旧行为一致地不覆盖
 * （该边界用户已在 B2① 拍板接受，手动兜底入口仍在）。
 */
object ReindexPlanner {

    /** MediaStore 尚未收录的文件（保持 [fsFiles] 的原有遍历顺序）。 */
    fun filesNeedingReindex(fsFiles: List<String>, indexedPaths: Set<String>): List<String> =
        fsFiles.filterNot { it in indexedPaths }

    /**
     * 去掉已被另一个已知文件夹覆盖的嵌套目录。
     *
     * walkTopDown 遍历父目录时本来就覆盖它所有子目录，重复列子目录会把同一批文件走多遍。
     * 真机取证（2026-10-07）：库里有 10 首歌直接躺在存储根下 → Track.folder == "/"，
     * 于是 16 个 knownFolders 里 "/" 是其余全部目录的祖先，同一个文件系统被走了两遍
     * （实测 4112 条记录对应 2077 个文件，后台核对平白翻倍）。
     *
     * 两个必须守住的边界：
     * ① "/Music2" 不是 "/Music" 的子目录 → 按 "/" 结尾判定，不能用裸 startsWith；
     * ② 根目录 "/" 本身的子路径前缀是 "/"（不是 "//"）→ 单独处理，否则它吸收不掉任何目录。
     */
    fun pruneNestedFolders(folders: Set<String>): Set<String> {
        val sorted = folders.sorted() // 前缀总排在自身扩展之前 → 父目录必先出现
        val kept = ArrayList<String>(sorted.size)
        for (f in sorted) {
            if (kept.none { covers(it, f) }) kept += f
        }
        return kept.toSet()
    }

    /** [parent] 是否覆盖 [child]（含相等）。根目录覆盖一切。 */
    private fun covers(parent: String, child: String): Boolean = when {
        child == parent -> true
        parent == "/" -> child.startsWith("/")
        else -> child.startsWith("$parent/")
    }
}
