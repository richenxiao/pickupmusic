package com.shiyin.music.ui.components

/** 解码结果长边上限(即使调用方要 4K 也不给比这更大的一手解码结果)。 */
internal const val MAX_DECODE_EDGE = 4096

/**
 * 该按几倍降采样解码(2 的幂,配合 `BitmapFactory.Options.inSampleSize`)。
 *
 * 为什么要它:远端写真(尤其 Discogs 的人物照)可能是 8192×6144 的扫描件,
 * **原尺寸解码 = 192MB**,Compose 一画就抛
 * `Canvas: trying to draw too large(201326592bytes) bitmap` —— 真机闪退的直接原因
 * (2026-10-01 真机报障"搜某歌手写真必崩")。此前只有本地文件那条路做了降采样,
 * 网络路径(以及从磁盘缓存回读字节)是原尺寸解码。
 *
 * 两条约束取严:
 *  - **清晰度**:尽量不低于目标显示尺寸的 2 倍(留取色/缩放余量);
 *  - **安全**:长边不超过 [MAX_DECODE_EDGE](8192 的扫描件 → 4096,字节数 ≤ 67MB,
 *    稳在 Canvas 的 100MB 上限之下)。
 *
 * 只放大到"允许的最大边长",再逐次减半 —— 结果长边必 ≤ 该边长(除非本来就更小)。
 */
internal fun bitmapSampleSize(width: Int, height: Int, targetPx: Int): Int {
    if (width <= 0 || height <= 0 || targetPx <= 0) return 1
    val edge = minOf(2 * targetPx, MAX_DECODE_EDGE).coerceAtLeast(1)
    val long = maxOf(width, height)
    var sample = 1
    // 逐次翻倍(保持 2 的幂,inSampleSize 才高效);上限 256 倍足够覆盖任何输入
    while (long / sample > edge && sample < 256) sample *= 2
    return sample
}
