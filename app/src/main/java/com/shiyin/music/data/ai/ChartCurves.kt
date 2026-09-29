package com.shiyin.music.data.ai

/**
 * v1.3.10 需求 A19:用量趋势图的**曲线过渡数学**(纯逻辑,零 Compose 依赖)。
 *
 * 背景(用户要求):"切换天数导致数据变化时,线条需要有一个过渡的渲染动画"。
 *
 * 难点:切换时间范围时点数是变的(1/3/7/14/30 天 → 1/3/7/14/30 个点),两组曲线没有
 * 逐点对应关系,没法直接插值 —— 只能硬切。所以先把每条曲线**线性重采样到统一分辨率**,
 * 于是"换范围"变成同一坐标系下的一次平滑形变。
 *
 * 为什么单独抽出来:插值边界(空列表/单点/两序列长度不一致)错了,图会静默画歪
 * (不是崩,是"看起来不太对"),肉眼很难发现 —— 所以用单测钉死(见 ChartCurvesTest)。
 */
class ChartCurves(
    val inp: FloatArray,
    val out: FloatArray,
    val cache: FloatArray,
    val cost: FloatArray,
)

/**
 * 线性重采样到 [n] 点,**首尾对齐**。
 *  - 空列表 → 全 0(还没数据的形态);
 *  - 单点 → 拉平成常数线(当天只有一个数据点时不能画成"零"或崩)。
 */
fun resampleTo(vals: List<Float>, n: Int): FloatArray {
    if (n <= 0) return FloatArray(0)
    val out = FloatArray(n)
    if (vals.isEmpty()) return out
    if (vals.size == 1) {
        java.util.Arrays.fill(out, vals[0])
        return out
    }
    // n == 1:取首值。⚠️ 不能走下面的插值 —— `i/(n-1)` 在这里是 0/0 = NaN,
    // 会把整条曲线变成 NaN(画出来什么都没有)。单测钉住了这个分支。
    if (n == 1) {
        out[0] = vals.first()
        return out
    }
    for (i in 0 until n) {
        val p = i.toFloat() / (n - 1) * (vals.size - 1)
        val i0 = p.toInt().coerceIn(0, vals.size - 1)
        val i1 = (i0 + 1).coerceAtMost(vals.size - 1)
        out[i] = vals[i0] + (vals[i1] - vals[i0]) * (p - i0)
    }
    return out
}

/**
 * 两组曲线按 [t] 逐点线性插值(0 = 全 [a],1 = 全 [b])。
 * 长度以较短者为准(正常路径两条都来自同一次重采样,长度必然相同)。
 */
fun blendCurves(a: ChartCurves, b: ChartCurves, t: Float): ChartCurves {
    fun m(x: FloatArray, y: FloatArray): FloatArray {
        val n = minOf(x.size, y.size)
        return FloatArray(n) { i -> x[i] + (y[i] - x[i]) * t }
    }
    return ChartCurves(m(a.inp, b.inp), m(a.out, b.out), m(a.cache, b.cache), m(a.cost, b.cost))
}
