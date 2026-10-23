package com.shiyin.music.ui.components

/**
 * v1.5.0: 封面**显示层**的比例规则（纯逻辑，可单测）。
 *
 * 背景：部分专辑的内嵌封面不是方形（横版/特殊版式），系统 `loadThumbnail`
 * 按方形容器请求时会**居中裁切**——缓存里存的就是裁剩的方图，两侧内容不可恢复。
 * 显示层只能按"手头位图的真实比例"决定怎么摆，本文件就是那套摆法的唯一真源。
 *
 * 两条规则（用户 2026-10-24 任务书）：
 *  · [defaultMode]（规则 2，全 App 方形小槽统一）：近似方形（0.9~1.1）→ Crop；
 *    否则 → Fit + 封面主色填充（主色沿用现有取色结果，不新增取色逻辑）。
 *  · [originalMode]（规则 3，「按原始比例显示封面」开启时的播放页/专辑页大图）：
 *    按原始比例完整显示不裁切；比例钳到 [MIN_RATIO, MAX_RATIO]，超出的用钳后的槽
 *    继续 Fit + 填充。
 *
 * ⚠️ 比例一律以**位图实测宽高**为准（w/h），不要信槽位尺寸——缓存里是什么比例
 * 显示层就是什么比例。
 */
object CoverRatio {

    /** 规则 2：位图比例在此区间内视为"就是方形"，照旧 Crop（容差吸收解码取整误差）。 */
    const val SQUARE_TOLERANCE = 0.1f

    /** 规则 3：原始比例槽的钳制下/上限（0.5=竖版极限，2.0=横版极限）。 */
    const val MIN_RATIO = 0.5f
    const val MAX_RATIO = 2.0f

    /** 槽的宽高比（w/h）。位图已解析且宽高有效才返回，否则 null（按方形处理）。 */
    fun ratioOf(width: Int, height: Int): Float? =
        if (width > 0 && height > 0) width.toFloat() / height else null

    /**
     * 规则 2：默认（开关关闭）模式下，方形槽内该 Crop 还是 Fit。
     * @return true=Crop（近似方形，现状不动）；false=Fit+填充（非方形，补边）
     */
    fun shouldCropInSquare(ratio: Float?): Boolean {
        if (ratio == null) return true
        return ratio in (1f - SQUARE_TOLERANCE)..(1f + SQUARE_TOLERANCE)
    }

    /**
     * 规则 3：开关开启时大图槽的宽高比（w/h）。
     * 原始比例在 [MIN_RATIO, MAX_RATIO] 内 → 原样；超出 → 钳到边界（槽继续方形化，
     * 内容由调用方 Fit + 填充，两侧留边）。
     */
    fun slotRatio(ratio: Float?): Float? {
        if (ratio == null) return null
        return ratio.coerceIn(MIN_RATIO, MAX_RATIO)
    }
}
