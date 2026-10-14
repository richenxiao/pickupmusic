package com.shiyin.music.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.5.0 伴唱（规格 §8 升降 Key）：音高换算纯逻辑。
 *
 * ⚠️ **内部单位是「半音步数」**（+1 步 = +0.5 半音）——2026-10-14 用户要求增大精度后引入。
 * 所以数量翻倍：±6 半音 = ±12 步；"12 个半音"= 24 步。
 */
class KeyShiftTest {

    @Test
    fun `范围被夹在正负 12 步（= 正负 6 半音）`() {
        assertEquals(-12, KeyShift.clamp(-99))
        assertEquals(-12, KeyShift.clamp(-12))
        assertEquals(0, KeyShift.clamp(0))
        assertEquals(12, KeyShift.clamp(12))
        assertEquals(12, KeyShift.clamp(99))
    }

    @Test
    fun `原调系数恰好是 1`() {
        assertEquals(1.0f, KeyShift.factor(0))
    }

    @Test
    fun `二十四个半音步正好翻一倍`() {
        assertEquals(2.0f, KeyShift.factor(24), 1e-6f)
        assertEquals(0.5f, KeyShift.factor(-24), 1e-6f)
    }

    @Test
    fun `六个半音是十二平均律的半程`() {
        // 6 半音 = 12 步
        assertEquals(1.4142135f, KeyShift.factor(12), 1e-6f)
    }

    @Test
    fun `半个半音是半步_系数是四分一个音分尺度`() {
        // +0.5 半音 = 1 步：2^(1/24) ≈ 1.0293022
        assertEquals(1.0293022f, KeyShift.factor(1), 1e-6f)
        assertEquals(0.5f, KeyShift.semitones(1), 0f)
    }

    @Test
    fun `半音步数换算成半音`() {
        assertEquals(0f, KeyShift.semitones(0), 0f)
        assertEquals(1f, KeyShift.semitones(2), 0f)
        assertEquals(-3f, KeyShift.semitones(-6), 0f)
        assertEquals(1.5f, KeyShift.semitones(3), 0f)
    }

    @Test
    fun `现代模式音高不随速度变化_只叠加 Key`() {
        // 现代模式（变速不变调）：speed=1.5 时基础音高仍是 1
        assertEquals(1.0f, KeyShift.totalPitch(1.5f, retroMode = false, halfSteps = 0), 1e-6f)
        assertEquals(KeyShift.factor(4), KeyShift.totalPitch(1.5f, retroMode = false, halfSteps = 4), 1e-6f)
    }

    @Test
    fun `复古模式在速度音高之上再叠加 Key`() {
        // 复古模式（磁带效果）：speed=1.5 自带音高 1.5
        assertEquals(1.5f, KeyShift.totalPitch(1.5f, retroMode = true, halfSteps = 0), 1e-6f)
        assertEquals(1.5f * KeyShift.factor(6), KeyShift.totalPitch(1.5f, retroMode = true, halfSteps = 6), 1e-6f)
    }

    @Test
    fun `标签_整数不显示小数点_半步显示 5`() {
        assertEquals("原调", KeyShift.label(0))
        assertEquals("+1", KeyShift.label(2))
        assertEquals("−3", KeyShift.label(-6))
        // 半音步进：必须能显示 0.5
        assertEquals("+1.5", KeyShift.label(3))
        assertEquals("−0.5", KeyShift.label(-1))
        assertEquals("+6", KeyShift.label(12))
    }
}
