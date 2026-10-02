package com.shiyin.music.data.split

/**
 * 逻辑曲目列表 → 播放引擎队列的「连续段压缩」规则。
 *
 * === 为什么需要压缩 ===
 * 拆分单曲共享同一个物理文件，队列里**只放一个 MediaItem**（整轨文件）才能保证边界处
 * 零引擎动作（无 seek / 无 reload / 无爆音）。所以：
 *
 * - 同一整轨文件的**相邻且下标连续**的拆分曲目 → 合并成一条（uri 指向整轨文件，
 *   mediaId 取代表那首的合成 id，方案随 extras 走）。整张专辑因此 = 1 条。
 * - 不相邻（歌单里同时有第 1 首和第 5 首）→ 各自成条，各带方案；切条时才发生真正的
 *   引擎切换 —— 那本来就不是同一段连续音频，用户预期也是"换歌"。
 * - 普通曲目 → 自己就是代表。
 *
 * 被点的那首若在某段里，就用它当该段的代表：它前面的成员本来也排在它前面、
 * 不会被播到（在整库/歌单队列里，用户点第 5 首就是从第 5 首开始）。
 *
 * 纯逻辑，无 Android 依赖，可单测。
 */
object SplitRunCollapse {

    /** 一个曲目在某个拆分方案里的位置。 */
    data class Slot(val sourceMediaId: Long, val index: Int)

    /**
     * 一条队列条目：代表下标 + 它负责的**分段区间**（闭区间，段下标）。
     *
     * 普通曲目（slot == null）没有分段概念，区间记 [-1, -1]。
     *
     * ⚠️ 为什么必须把区间交出来：队列条目只带 `index` 时，引擎侧无从知道"这条到底
     * 管到第几首为止"，于是段内推进（ticker 越界检查 / next / 系统媒体键）会一路走完
     * **整张方案**——歌单里放一首拆分单曲，播完会接着播同一整轨文件的下一段，而不是
     * 歌单的下一首（用户实测："一个座位坐了 11 个人"）。
     */
    data class Run(val repIndex: Int, val startSegment: Int, val lastSegment: Int)

    /**
     * 返回应当保留在引擎队列里的**下标**（按原顺序）。
     *
     * @param slots 与输入曲目列表等长；`null` = 普通曲目（自己就是代表）。
     * @param tappedIndex 用户点的那首在列表里的下标；-1 = 无明确起点。
     */
    fun representativeIndices(slots: List<Slot?>, tappedIndex: Int = -1): List<Int> =
        runs(slots, tappedIndex).map { it.repIndex }

    /**
     * 与 [representativeIndices] **同一趟扫描**，额外给出每条代表负责的分段区间。
     *
     * 被点中的那首会成为代表，但**可播放区间从它自己那段起算**（它前面的成员本来也排在
     * 它前面、不会被播到）——所以 `startSegment` 取的是代表那首的段下标，而不是整段的段首。
     */
    fun runs(slots: List<Slot?>, tappedIndex: Int = -1): List<Run> {
        val out = ArrayList<Run>(slots.size)
        var i = 0
        while (i < slots.size) {
            val cur = slots[i]
            if (cur == null) {
                out += Run(i, -1, -1)
                i++
                continue
            }
            // 从 i 起，尽可能向右吃掉"同源 + 下标连续"的邻居
            var last = i
            var idx = cur.index
            while (last + 1 < slots.size) {
                val nx = slots[last + 1] ?: break
                if (nx.sourceMediaId != cur.sourceMediaId || nx.index != idx + 1) break
                last++
                idx++
            }
            // 被点的那首在这段里 → 用它当代表，可播区间从它那段起；否则用段首
            val rep = if (tappedIndex in i..last) tappedIndex else i
            out += Run(repIndex = rep, startSegment = slots[rep]!!.index, lastSegment = idx)
            i = last + 1
        }
        return out
    }
}
