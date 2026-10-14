package com.shiyin.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 步骤 3 的**系数插值**是本轮最容易"看着对、实际错"的一处纯逻辑：
 * 从 (0,1) 切到 (1,−1) 时两个分量要走的距离不同（1 和 2），
 * 按固定每帧步长推进会**先到一个、晚到另一个**，中间那段"半程状态"会听到比例不对的声音。
 * 这几条用例把"两个分量必须同时到位"钉住。
 */
class CoefficientRampTest {

    private fun r(go: Float, ga: Float) = CoefficientRamp(go, ga)

    @Test
    fun `等距切换_中点正好是一半`() {
        val ramp = r(1f, 0f)
        ramp.moveTo(0f, 1f, 100)
        assertFalse(ramp.atTarget)
        ramp.advance(50)
        assertEquals(0.5f, ramp.curGo, 1e-4f)
        assertEquals(0.5f, ramp.curGa, 1e-4f)
        ramp.advance(50)
        assertTrue(ramp.atTarget)
        assertEquals(0f, ramp.curGo, 1e-6f)
        assertEquals(1f, ramp.curGa, 1e-6f)
    }

    @Test
    fun `距离不等的切换_走直线而不是折线`() {
        // (0,1) → (1,−1)：g_o 走 1，g_a 走 2。**各走各的速率** ⇒ 路径是系数空间的直线：
        // 中点应当是 (0.5, 0.0)。若共用一个按最大分量归一化的速率，走得近的 g_o 会先到并停住，
        // 中点变成 (1.0, 0.0) —— 那是折线，不是规格说的"系数空间线性插值"。
        val ramp = r(0f, 1f)
        ramp.moveTo(1f, -1f, 100)
        ramp.advance(50)
        assertEquals(0.5f, ramp.curGo, 1e-4f)
        assertEquals(0.0f, ramp.curGa, 1e-4f)
        assertFalse("50 帧时不该已经到位", ramp.atTarget)
        ramp.advance(50)
        assertTrue("两个分量应在同一帧同时到位", ramp.atTarget)
        assertEquals(1f, ramp.curGo, 1e-6f)
        assertEquals(-1f, ramp.curGa, 1e-6f)
    }

    @Test
    fun `不会越过目标_逐帧推进也不越界`() {
        val ramp = r(1f, 0f)
        ramp.moveTo(0f, 1f, 30)
        repeat(200) { ramp.advance(1) }
        assertEquals(0f, ramp.curGo, 1e-6f)
        assertEquals(1f, ramp.curGa, 1e-6f)
    }

    @Test
    fun `淡化时长为零或目标相同_立即到位`() {
        val ramp = r(1f, 0f)
        ramp.moveTo(0f, 1f, 0)
        ramp.advance(1)
        assertTrue(ramp.atTarget)

        val same = r(1f, 0f)
        same.moveTo(1f, 0f, 300)
        same.advance(1)
        assertTrue(same.atTarget)
    }

    @Test
    fun `三档之间的系数向量与用户定义一致`() {
        assertEquals(1f, KaraokeMixControl.coeffOf(KaraokeMixControl.MODE_ORIGINAL)[0], 0f)
        assertEquals(0f, KaraokeMixControl.coeffOf(KaraokeMixControl.MODE_ORIGINAL)[1], 0f)
        assertEquals(0f, KaraokeMixControl.coeffOf(KaraokeMixControl.MODE_ACCOMP)[0], 0f)
        assertEquals(1f, KaraokeMixControl.coeffOf(KaraokeMixControl.MODE_ACCOMP)[1], 0f)
        // 人声 = 原曲 − 伴奏
        assertEquals(1f, KaraokeMixControl.coeffOf(KaraokeMixControl.MODE_VOCAL)[0], 0f)
        assertEquals(-1f, KaraokeMixControl.coeffOf(KaraokeMixControl.MODE_VOCAL)[1], 0f)
    }
}
