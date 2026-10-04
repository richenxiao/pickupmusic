package com.shiyin.music.ui.screens

import kotlin.math.abs

/**
 * 进度条手势方向仲裁:PENDING → DRAGGING(横向 scrub) / CANCELLED(纵向,不消费)。
 *
 * **非对称设计(针对"上下滑动误触进度条")**:
 * - 纵向 [CANCELLED] 易于确立——纵向位移过 slop 且 `|dy| > |dx|` 即触发
 *   (任何"比横向更纵向"的位移都算纵向意图,事件冒泡给外层纵滑);
 * - 横向 [DRAGGING] 难于确立——横向位移过 slop 且 `|dx| > |dy|×ratio` 才触发
 *   (必须明显横向占优,挡掉 45° 附近的模糊手势与拇指自然弧线)。
 *
 * 两者一旦确立都**不可互相转换**(纵向不可复活成横向;横向锁定防拖动中抖动)。
 * 松手结算由调用方根据 [phase] 与累计位移自行判定(轻点 / 提交 seek / 不处理)。
 *
 * 纯逻辑、无 Compose 依赖,便于单测。
 */
class SeekGestureArbiter(
    private val touchSlop: Float,
    private val directionRatio: Float = 1.5f,
) {
    var phase: SeekGesturePhase = SeekGesturePhase.PENDING
        private set

    fun onDown() {
        phase = SeekGesturePhase.PENDING
    }

    /** 喂入自按下以来的累计位移,返回当前相位。 */
    fun onMove(totalDx: Float, totalDy: Float): SeekGesturePhase {
        if (phase != SeekGesturePhase.PENDING) return phase
        val adx = abs(totalDx)
        val ady = abs(totalDy)
        phase = when {
            // 纵向过 slop 且比横向更纵向 → 永久取消(纵向一票否决,不可复活)
            ady > touchSlop && ady > adx -> SeekGesturePhase.CANCELLED
            // 横向过 slop 且明显占优(超过纵向×ratio) → 锁定 DRAGGING
            adx > touchSlop && adx > ady * directionRatio -> SeekGesturePhase.DRAGGING
            else -> SeekGesturePhase.PENDING
        }
        return phase
    }
}

enum class SeekGesturePhase { PENDING, DRAGGING, CANCELLED }
