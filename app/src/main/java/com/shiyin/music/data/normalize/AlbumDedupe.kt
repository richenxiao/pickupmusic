package com.shiyin.music.data.normalize

import com.shiyin.music.data.Track
import com.shiyin.music.data.split.SplitIds

/**
 * v1.5.0: 专辑内"同一文件"折叠（原逻辑内联在 MainViewModel.albumsMap）。
 *
 * **为什么需要它**：MediaStore 偶尔会把同一个文件收录成两条；不折叠的话专辑里会出现
 * 两条一模一样的曲目。**但不能按"标题"折叠** —— 用户实测：一张 EP 里的
 * `YOU` / `YOU（feat.UMI）` / `YOU-Instrumental` 三个文件的 TITLE 都是 "YOU"
 * （下载器把版本差异只留在文件名里），按标题折叠会让整张 EP 只剩一首。
 *
 * 所以折叠判据 = **确为同一个文件**：
 * · 优先 `dataPath`（同一路径 = MediaStore 重复收录，必然同文件）；
 * · 路径为空时退化为「标题 + 时长 + 大小」全等（任一不同 = 不同版本，保留）。
 */
object AlbumDedupe {

    /** 折叠 [tracks] 中指向同一物理文件的条目，保持原顺序。 */
    fun dedupeSameFile(tracks: List<Track>): List<Track> {
        val seen = HashSet<String>(tracks.size)
        return tracks.filter { t ->
            // ⚠️ 拆分单曲（以及「完整专辑」容器）共享同一个物理文件，`dataPath` 必然相同 ——
            // 它们是**用户显式拆出来的不同曲目**，不是 MediaStore 重复收录，绝不能折叠。
            // 2026-10-12 用户真机反馈：少这一条时，整轨拆完后**歌曲列表有 N 首、专辑页只剩 1 条**
            // （专辑页走 albumsMap() → 这里；歌曲列表直接走 lib()，不经过折叠）。
            if (SplitIds.isSynthetic(t.id)) return@filter true
            val key = t.dataPath.ifBlank { "${t.title}|${t.durationMs}|${t.sizeBytes}" }
            seen.add(key)
        }
    }
}
