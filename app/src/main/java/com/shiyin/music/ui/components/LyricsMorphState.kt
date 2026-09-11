package com.shiyin.music.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * v1.3.x morph 过渡 P1(设计文档 §3.2):歌词本↔卡片共享元素 morph 的单一
 * progress 真源。0f=卡片态,1f=全屏态。所有视觉属性(容器 bounds/圆角/各层
 * 透明度/字号插值)都是 progress 的纯函数——分段窗口,不是独立动画。
 *
 * 弹簧参数(设计文档 §4 偏差1):ζ=0.75/K=380 过冲≈2.8%(≈20dp)、稳定
 * ≈500-600ms;收起 ζ=0.85/K=550 更利落。Compose spring 无质量/刚度独立参数,
 * 原规格的 web 参数(stiffness 120-150/damping 12-16/mass 1)换算 ζ≈0.49-0.65,
 * 与此同区间——语义一致,数值按 Compose API 归位。
 *
 * v1.3.x 状态收敛(状态源四合一):本类由 MainViewModel 持有(viewModelScope),
 * 跨打开/关闭周期常驻——不再每次打开 remember 新建。phase 枚举与 snapshotFlow
 * 销毁门删除:收起收尾改调用方模式(MainViewModel.collapseLyrics 里 collapse()
 * 返回后置 lyricsOn=false);新拖拽的 snapTo 经 Animatable 内部 mutator mutex
 * 取消正在跑的 animateTo,collapse 协程以取消异常终止、收尾代码不执行——
 * 打断弹回场景天然正确,不需要 phase 判向。
 */
object LyricsMorphSpec {
    val expandSpring = spring<Float>(dampingRatio = 0.75f, stiffness = 380f)
    val collapseSpring = spring<Float>(dampingRatio = 0.85f, stiffness = 550f)
    // 交叉淡化窗口(progress 区间,设计文档 §3.2)
    const val PREVIEW_FADE_OUT_START = 0.35f
    const val PREVIEW_FADE_OUT_END = 0.60f
    const val LIST_FADE_IN_START = 0.40f
    const val LIST_FADE_IN_END = 0.65f
    const val CHROME_FADE_IN_START = 0.40f
    const val CHROME_FADE_IN_END = 0.70f
    const val PLAYER_DIM_END = 0.6f
    const val PLAYER_DIM_MIN = 0.3f
}

class LyricsMorphState(private val scope: CoroutineScope) {

    /** 0f=卡片,1f=全屏。唯一驱动源,所有视觉是它的纯函数。 */
    val progress = Animatable(0f)

    /** 卡片 window 坐标 Rect(onGloballyPositioned 持续上报,VM 透传)。 */
    var cardBounds: Rect? = null

    /** 状态收敛:屏高(px),overlay 组合时写入;onDrag/collapse 的 px→progress
     *  换算共用同一除数(卡片顶到屏顶,下限屏高 20%)。 */
    var screenH: Float = 0f

    val isRunning: Boolean get() = progress.isRunning

    /** 展开:snapTo(0) → 弹簧到 1。
     *  cardBounds==null(首帧未测得即被点击/Activity 重建)按 §3.6-5 兜底:
     *  直接 snapTo(1f) 稳态全屏(无形变)——重建场景这正是期望行为(不播动画),
     *  极端时序宁可无动画不可渲染 0×0 图层闪帧。 */
    suspend fun open(card: Rect?) {
        cardBounds = card
        if (card == null) {
            progress.snapTo(1f)
            return
        }
        progress.snapTo(0f)
        progress.animateTo(1f, LyricsMorphSpec.expandSpring)
    }

    /** 收起:弹簧到 0。
     *  速度单位修复(实测根因:现象③"松手有时正常有时闪回直接关闭"、现象④
     *  "小幅度松手闪一下就结束"):松手速度来自 VelocityTracker,单位 px/s;
     *  progress 值域 [0,1]。原实现把 px/s 原样当 progress/s 喂弹簧的
     *  initialVelocity(8349 px/s 在 [0,1] 值域里是天文数字,弹簧 1-2 帧内冲到
     *  终点=闪一下直接结束,且快慢手不手感)。换算 px/s → progress/s 用与
     *  onDrag 相同的除数(卡片顶到屏顶 dd),松手速度物理连续:手指 V px/s
     *  → progress 以 V/dd 每秒起步,衔接无顿挫。 */
    suspend fun collapse(card: Rect?, velocityPx: Float = 0f) {
        cardBounds = card
        val b = cardBounds
        val dd = if (b != null) b.top.coerceAtLeast(screenH * 0.2f) else screenH * 0.2f
        val progressVelocity = if (dd > 0f) velocityPx / dd else 0f
        progress.animateTo(0f, LyricsMorphSpec.collapseSpring, progressVelocity)
    }

    /**
     * 跟手映射(P3 手势用,P1 预留):手指 delta(px,向下为正)线性映射 progress
     * ——容器顶边位移与手指 1:1(设计文档 §3.3-4):translationY = (1-p)·b.top,
     * 故 d(已下拉量) = (1-p)·D,D = b.top(卡片顶到屏顶距离,下限屏高 20% 防卡片
     * 滚到极端位置时拖拽过敏)。p_new = 1 - (d + delta)/D,clamp [0,1]。
     * 变向对称(正负 delta 都进同一式),实时响应。
     */
    fun onDrag(deltaPx: Float) {
        val b = cardBounds ?: return
        val dd = b.top.coerceAtLeast(screenH * 0.2f)  // 1:1 映射分母(卡片顶到屏顶)
        val cur = (1f - progress.value) * dd           // 已下拉量(容器顶边位移)
        val pNew = 1f - (cur + deltaPx) / dd
        scope.launch { progress.snapTo(pNew.coerceIn(0f, 1f)) }
    }
}
