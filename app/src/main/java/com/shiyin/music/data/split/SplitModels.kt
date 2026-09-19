package com.shiyin.music.data.split

/**
 * 专辑整轨拆分为单曲 —— 合成 id 命名空间与共享模型。
 *
 * === 为什么需要合成 id ===
 * 拆分单曲共享同一个物理音频文件，但需要在所有按 mediaId 工作的下游表
 * （playlist_track / play_event / saved_lyrics / track_info_override …）里
 * 拥有各自独立的曲目身份。做法是给每首拆分单曲分配一个「合成 mediaId」——
 * 它不对应任何 MediaStore 记录，只在本 App 内部作为曲目身份使用。
 *
 * 本库既有的同类先例：playUri() 用 `-uri.hashCode()`、batchAssignToNewAlbum
 * 用 `+0x100000000L`。这里把两个新值域抬到更高位，避开它们。
 *
 * === 值域 ===
 * ```
 *  真实 MediaStore id       1 … ~2^31
 *  batchAssignToNewAlbum    0x1_0000_0000 起
 *  拆分单曲                 SPLIT_TRACK_BASE     起（2^45）
 *  整轨容器                 SPLIT_CONTAINER_BASE 起（3 × 2^44）
 * ```
 * 两个值域互不相交，配合 play_event.kind 形成统计口径的双重隔离。
 */
object SplitIds {

    /** 拆分单曲合成 id 的基址（2^45）。 */
    const val TRACK_BASE = 0x2000_0000_0000L

    /** 整轨容器（「完整专辑」）合成 id 的基址（3 × 2^44）。 */
    const val CONTAINER_BASE = 0x3000_0000_0000L

    /** split_track.id → 对外合成 mediaId。 */
    fun trackId(splitRowId: Long): Long = TRACK_BASE + splitRowId

    /** 是否为一首拆分单曲的合成 id。 */
    fun isSplitTrackId(id: Long): Boolean = id in TRACK_BASE until CONTAINER_BASE

    /** 整轨文件的 MediaStore id → 「完整专辑」容器 id（确定性派生，重扫后稳定）。 */
    fun containerId(sourceMediaId: Long): Long = CONTAINER_BASE + sourceMediaId

    /** 是否为「完整专辑」容器 id。 */
    fun isContainerId(id: Long): Boolean = id >= CONTAINER_BASE

    /** 容器 id → 整轨文件的 MediaStore id。 */
    fun sourceMediaIdOf(containerId: Long): Long = containerId - CONTAINER_BASE

    /** 是否为任意拆分相关的合成 id（单曲或容器）。 */
    fun isSynthetic(id: Long): Boolean = isSplitTrackId(id) || isContainerId(id)
}

/** startMs 的「未定」哨兵值。0 是合法起点（第一首固定 0），必须能区分。 */
const val SPLIT_START_UNKNOWN = -1L

/**
 * play_event.kind 的取值 —— 两套统计口径在数据层的隔离位。
 *
 * - [TRACK] 单曲播放会话（含拆分单曲；mediaId 是真实 id 或合成 id）
 * - [ALBUM] 「完整专辑」整轨播放会话（mediaId 是容器合成 id）
 *
 * 存量行一律为 [TRACK]，因此所有既有查询加上 `AND kind = 'track'` 后语义逐字不变。
 * 完整专辑播放**只计入专辑播放次数，绝不产生任何单曲事件**。
 */
object PlayKind {
    const val TRACK = "track"
    const val ALBUM = "album"
}

/**
 * ≤ 此秒数的曲目视为「专辑过渡」（intro / outro / interlude / skit），
 * 不是「歌」。用户定调：这类曲目**要计数**，但**不作为热度评判标准**。
 * 纯派生判定（时长即可），不落库 —— 用户改时间点后不会出现标记不同步。
 */
const val INTERLUDE_MAX_SEC = 60

/**
 * 是否为「专辑过渡曲」。抽成顶层纯函数以便单测覆盖这条规则
 * （View 层的 `MainViewModel.isInterlude` 只是它的一层包装）。
 */
fun isInterludeDuration(durationSec: Long): Boolean =
    durationSec in 1..INTERLUDE_MAX_SEC.toLong()

/** 一首拆分单曲在整轨文件时间轴上的区间。 */
data class VirtualTrack(
    /** 合成 mediaId（SplitIds.trackId(splitRowId)）。 */
    val id: Long,
    val startMs: Long,
    val endMs: Long,
    /** 曲名 —— 一并塞进 MediaItem.extras，使播放服务（通知栏切歌）无需回查 ViewModel。 */
    val title: String = "",
    val artist: String = "",
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0)
}

/**
 * 一个整轨文件的完整拆分方案：容器 id + 按 ordinal 排好的虚拟曲目。
 * 队列构造与播放引擎的边界派生都以它为单位。
 */
data class SplitPlan(
    val containerId: Long,
    val sourceMediaId: Long,
    val tracks: List<VirtualTrack>,
) {
    /** 按播放位置派生当前落在第几首（-1 = 不在任何区间内）。 */
    fun indexAt(positionMs: Long): Int {
        if (tracks.isEmpty()) return -1
        var lo = 0
        var hi = tracks.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (tracks[mid].startMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }
}
