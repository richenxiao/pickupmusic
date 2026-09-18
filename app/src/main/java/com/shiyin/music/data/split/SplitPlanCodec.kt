package com.shiyin.music.data.split

/**
 * 拆分方案的字符串编解码 —— 与 Android `Bundle` 解耦，可纯 JVM 单测。
 *
 * 方案要随 MediaItem 走（存在 `MediaMetadata.extras` 里），因为队列会被
 * shuffle / moveMediaItem / addMediaItem / syncQueue 反复重排，任何"与队列平行维护
 * 的下标数组"都会在重排后错位。塞进 extras 后天然跟着条目走。
 *
 * 编码里**连曲名/歌手一起带上**，这样播放服务进程（PlaybackService 的
 * MediaSession.Callback）也能独立完成切歌，不必回调 App 进程的 ViewModel ——
 * 通知栏 / 蓝牙耳机 / 车机的「上一首/下一首」因此可用。
 *
 * 字段分隔用控制字符（U+0001 / U+0002）：曲名里不可能出现，因此无需转义。
 */
object SplitPlanCodec {

    const val SEP_FIELD = '\u0001'
    const val SEP_ENTRY = '\u0002'

    /** 每条的字段数：id / startMs / endMs / title / artist。 */
    private const val FIELD_COUNT = 5

    fun encode(tracks: List<VirtualTrack>): String =
        tracks.joinToString(SEP_ENTRY.toString()) { t ->
            listOf(
                t.id.toString(), t.startMs.toString(), t.endMs.toString(), t.title, t.artist,
            ).joinToString(SEP_FIELD.toString())
        }

    /**
     * 解码。任何不合法输入（空串、字段数不足、数字解析失败、条目为空）都返回 null，
     * 由调用方降级为"非拆分曲目"——宁可少一次无缝切歌，也不要因为脏 extras 崩掉播放。
     */
    fun decode(encoded: String?): List<VirtualTrack>? {
        if (encoded.isNullOrEmpty()) return null
        val out = ArrayList<VirtualTrack>(4)
        for (entry in encoded.split(SEP_ENTRY)) {
            val f = entry.split(SEP_FIELD)
            if (f.size < FIELD_COUNT) return null
            val id = f[0].toLongOrNull() ?: return null
            val start = f[1].toLongOrNull() ?: return null
            val end = f[2].toLongOrNull() ?: return null
            out += VirtualTrack(id = id, startMs = start, endMs = end, title = f[3], artist = f[4])
        }
        return out.ifEmpty { null }
    }

    /** 曲名里出现控制字符时（理论上不该有）会被编码破坏 —— 仅供诊断使用。 */
    fun isEncodable(tracks: List<VirtualTrack>): Boolean =
        tracks.none { t ->
            t.title.any { it == SEP_FIELD || it == SEP_ENTRY } ||
                t.artist.any { it == SEP_FIELD || it == SEP_ENTRY }
        }
}
