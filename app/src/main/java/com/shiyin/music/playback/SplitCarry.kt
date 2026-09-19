package com.shiyin.music.playback

import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.shiyin.music.data.split.SplitPlan
import com.shiyin.music.data.split.SplitPlanCodec
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
 * 纯字符串编解码在 [SplitPlanCodec]（可单测）；这里只做 Bundle 读写与 MediaItem 构造。
 */
object SplitCarry {

    const val KEY_PLAN = "split_plan"
    const val KEY_IDX = "split_idx"
    private const val KEY_CONTAINER = "split_container"
    private const val KEY_SOURCE = "split_source"

    /** 把一个方案 + 下标写进 MediaMetadata.Builder。 */
    fun put(builder: MediaMetadata.Builder, carry: PlanCarry) {
        val extras = Bundle().apply {
            putString(KEY_PLAN, SplitPlanCodec.encode(carry.plan.tracks))
            putLong(KEY_CONTAINER, carry.plan.containerId)
            putLong(KEY_SOURCE, carry.plan.sourceMediaId)
            putInt(KEY_IDX, carry.index)
        }
        builder.setExtras(extras)
    }

    /** 从 MediaItem 读回拆分信息；普通曲目（无 extras）或 extras 脏了都返回 null。 */
    fun read(item: MediaItem?): PlanCarry? {
        val ex = item?.mediaMetadata?.extras ?: return null
        val tracks = SplitPlanCodec.decode(ex.getString(KEY_PLAN)) ?: return null
        val idx = ex.getInt(KEY_IDX, 0).coerceIn(0, tracks.size - 1)
        val plan = SplitPlan(
            containerId = ex.getLong(KEY_CONTAINER, 0L),
            sourceMediaId = ex.getLong(KEY_SOURCE, 0L),
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

    /** 当前这首在整轨文件里的起点（进入某条目时把播放位置对齐到它）。 */
    fun startMsOf(carry: PlanCarry): Long = carry.current?.startMs ?: 0L
}
