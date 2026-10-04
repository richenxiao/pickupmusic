package com.shiyin.music.ui.screens

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekGestureArbiterTest {
    private val slop = 24f

    @Test
    fun `horizontal drag enters DRAGGING`() {
        val a = SeekGestureArbiter(slop)
        a.onDown()
        assertEquals(SeekGesturePhase.PENDING, a.onMove(10f, 2f))
        assertEquals(SeekGesturePhase.DRAGGING, a.onMove(40f, 5f))
    }

    @Test
    fun `vertical scroll enters CANCELLED and never revives`() {
        val a = SeekGestureArbiter(slop)
        a.onDown()
        assertEquals(SeekGesturePhase.CANCELLED, a.onMove(3f, 40f))
        // 之后即使横向分量变大,也不得复活成 DRAGGING
        assertEquals(SeekGesturePhase.CANCELLED, a.onMove(200f, 45f))
    }

    @Test
    fun `diagonal below ratio enters DRAGGING`() {
        val a = SeekGestureArbiter(slop, directionRatio = 1.5f)
        a.onDown()
        // |dx|=60 > 24, 且 60 > 30×1.5=45 → 横向
        assertEquals(SeekGesturePhase.DRAGGING, a.onMove(60f, 30f))
    }

    @Test
    fun `diagonal above ratio enters CANCELLED`() {
        val a = SeekGestureArbiter(slop, directionRatio = 1.5f)
        a.onDown()
        // 45°(dx=dy):纵向只需 |dy|>|dx| 才取消,等号时不满足 → PENDING;
        // 横向需 |dx|>|dy|×1.5=90,60>90 不成立 → 也不 DRAGGING
        assertEquals(SeekGesturePhase.PENDING, a.onMove(60f, 60f))
        // 转为纵向占优:100>70 → CANCELLED(纵向一票否决,不可复活)
        assertEquals(SeekGesturePhase.CANCELLED, a.onMove(70f, 100f))
    }

    @Test
    fun `DRAGGING is sticky once entered`() {
        val a = SeekGestureArbiter(slop)
        a.onDown()
        a.onMove(40f, 5f) // DRAGGING
        // 拖动中手指转为纵移,不退出(锁定,防抖)
        assertEquals(SeekGesturePhase.DRAGGING, a.onMove(42f, 90f))
    }

    @Test
    fun `onDown resets to PENDING`() {
        val a = SeekGestureArbiter(slop)
        a.onDown()
        a.onMove(40f, 5f)
        a.onDown()
        assertEquals(SeekGesturePhase.PENDING, a.phase)
    }

    @Test
    fun `sub-slop jitter stays PENDING`() {
        val a = SeekGestureArbiter(slop)
        a.onDown()
        assertEquals(SeekGesturePhase.PENDING, a.onMove(5f, 5f))
        assertEquals(SeekGesturePhase.PENDING, a.onMove(20f, 10f))
    }
}
