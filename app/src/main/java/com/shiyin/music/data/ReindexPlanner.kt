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

    /**
     * 常见放歌位置（相对内部存储根）。这些目录无论库里有没有已知歌曲都会被核对一遍，
     * 这样「歌散落在好几个地方」的情况依然覆盖得到。
     *
     * 为什么不是整个存储根：真机实测（2026-10-07）内部存储共 102,669 个文件，
     * 全盘遍历 17.8s（App 里 33s），其中 Android 91,178 个、Pictures 4,980 个，
     * 与音乐无关；而下列目录合计约 4,000 个文件、2s 以内。
     */
    val CURATED_DIRS = listOf(
        "Music", "Download", "Podcasts", "Ringtones", "Alarms", "Notifications",
        "Recordings", "Android/media", "DCIM", "Movies", "Documents",
    )

    /**
     * 一次核对的遍历计划。
     * [recursive] 递归遍历（子目录也要走）；[shallow] 只看这一层（存储根目录专用）。
     */
    data class WalkPlan(val recursive: List<String>, val shallow: List<String>)

    /**
     * 由「库里已知的文件夹」+ [CURATED_DIRS] 算出遍历计划。
     *
     * 存储根 "/" 只做浅层扫描：它一旦递归就等于把整个手机存储走一遍（相册、应用数据全都走），
     * 而它出现在 knownFolders 里只是因为有几首歌直接躺在根目录下。
     */
    fun planWalk(knownFolders: Set<String>, curatedDirs: List<String> = CURATED_DIRS): WalkPlan {
        val shallow = if (knownFolders.contains("/")) listOf("/") else emptyList()
        val candidates = (knownFolders - "/") + curatedDirs.map { "/$it" }
        // pruneNestedFolders 用 LinkedHashSet 保序（父目录在前），转 List 给调用方直接遍历
        return WalkPlan(pruneNestedFolders(candidates).toList(), shallow)
    }

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
