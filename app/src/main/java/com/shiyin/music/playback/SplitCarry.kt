package com.shiyin.music.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.shiyin.music.data.split.SplitPlan
import com.shiyin.music.data.split.VirtualTrack

/** 一个队列条目携带的拆分信息：所属方案 + 当前落在第几首。 */
data class PlanCarry(val plan: SplitPlan, val index: Int) {
    val current: VirtualTrack? get() = plan.tracks.getOrNull(index)
}

/**
 * 拆分方案随 MediaItem 走的编解码层（存在 `MediaMetadata.extras` 里）。
 *
 * === 为什么塞进 extras 而不是在 PlayerController 里另存一张表 ===
 * 队列会被 shuffle / moveMediaItem / addMediaItem / syncQueue 反复重排，任何"与队列
 * 平行维护的下标数组"都会在重排后错位。extras 跟着 MediaItem 一起走，天然不会错位。
 *
 * === 为什么连曲名/歌手一起编码 ===
 * 无缝切歌时要构造新的 MediaItem 元数据（通知栏/锁屏/播放页标题都得跟着变）。把标题
 * 一起编码后，**播放服务进程（PlaybackService 的 MediaSession.Callback）也能独立完成
 * 切歌**，不必回调 App 进程的 ViewModel —— 通知栏/蓝牙耳机/车机的"下一首"因此可用。
 *
 * 字段分隔用控制字符（U+0001/U+0002），曲名里不可能出现，无需转义。
 */
object SplitCarry {

    const val KEY_PLAN = "split_plan"
    const val KEY_IDX = "split_idx"

    private const val SEP_FIELD = '\u0001'
    private const val SEP_ENTRY = '\u0002'

    /** 把一个方案 + 下标写进 MediaMetadata.Builder。 */
    fun put(builder: MediaMetadata.Builder, carry: PlanCarry) {
        val encoded = carry.plan.tracks.joinToString(SEP_ENTRY.toString()) { t ->
            listOf(
                t.id.toString(), t.startMs.toString(), t.endMs.toString(), t.title, t.artist,
            ).joinToString(SEP_FIELD.toString())
        }
        val extras = Bundle().apply {
            putString(KEY_PLAN, encoded)
            putLong("split_container", carry.plan.containerId)
            putLong("split_source", carry.plan.sourceMediaId)
            putInt(KEY_IDX, carry.index)
        }
        builder.setExtras(extras)
    }

    /** 从 MediaItem 读回拆分信息；普通曲目（无 extras）返回 null。 */
    fun read(item: MediaItem?): PlanCarry? {
        val ex = item?.mediaMetadata?.extras ?: return null
        val encoded = ex.getString(KEY_PLAN) ?: return null
        if (encoded.isEmpty()) return null
        val tracks = encoded.split(SEP_ENTRY).mapNotNull { entry ->
            val f = entry.split(SEP_FIELD)
            if (f.size < 5) return@mapNotNull null
            val id = f[0].toLongOrNull() ?: return@mapNotNull null
            val start = f[1].toLongOrNull() ?: return@mapNotNull null
            val end = f[2].toLongOrNull() ?: return@mapNotNull null
            VirtualTrack(id = id, startMs = start, endMs = end, title = f[3], artist = f[4])
        }
        if (tracks.isEmpty()) return null
        val idx = ex.getInt(KEY_IDX, 0).coerceIn(0, tracks.size - 1)
        val plan = SplitPlan(
            containerId = ex.getLong("split_container", 0L),
            sourceMediaId = ex.getLong("split_source", 0L),
            tracks = tracks,
        )
        return PlanCarry(plan, idx)
    }

    /**
     * 用当前 MediaItem 的**播放配置**（uri / album / artwork）+ 目标虚拟曲目的
     * **元数据**构造新条目。
     *
     * 关键：uri 与 LocalConfiguration 完全不变，只换 mediaId 与标题/歌手 —— Media3
     * 对相同 LocalConfiguration 的 replaceMediaItem 只做元数据更新，不重建 MediaSource，
     * 因此边界处音频流完全连续（无 seek、无 reload、无爆音）。这也是本方案能同时满足
     * 「无缝播放」与「切歌后通知栏/播放页标题跟着变」的原因。
     */
    fun itemFor(current: MediaItem, carry: PlanCarry): MediaItem {
        val t = carry.current ?: return current
        val mb = current.mediaMetadata.buildUpon()
            .setTitle(t.title)
            .setArtist(t.artist)
        put(mb, carry)
        return current.buildUpon()
            .setMediaId(t.id.toString())
            .setMediaMetadata(mb.build())
            .build()
    }

    /** 把 carry 里当前这首的对齐位置（进入某条目时把播放位置挪到它的起点）。 */
    fun startMsOf(carry: PlanCarry): Long = carry.current?.startMs ?: 0L
}
