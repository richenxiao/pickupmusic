package com.shiyin.music.playback

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * v1.5.0 伴唱（规格 §8 升降 Key）：音高换算纯逻辑。
 * 规格原文：范围 ±6 半音、步进 1、音高系数 2^(n/12)、速度不变；
 * 总音高 = 加速度模式自带音高 × 2^(n/12)。
 */
class KeyShiftTest {

    @Test
    fun `范围被夹在正负 6 半音`() {
        assertEquals(-6, KeyShift.clamp(-99))
        assertEquals(-6, KeyShift.clamp(-6))
        assertEquals(0, KeyShift.clamp(0))
        assertEquals(6, KeyShift.clamp(6))
        assertEquals(6, KeyShift.clamp(99))
    }

    @Test
    fun `原调系数恰好是 1`() {
        assertEquals(1.0f, KeyShift.factor(0))
    }

    @Test
    fun `十二个半音正好翻一倍`() {
        assertEquals(2.0f, KeyShift.factor(12), 1e-6f)
        assertEquals(0.5f, KeyShift.factor(-12), 1e-6f)
    }

    @Test
    fun `六个半音是十二平均律的半程`() {
        assertEquals(1.4142135f, KeyShift.factor(6), 1e-6f)
    }

    @Test
    fun `现代模式音高不随速度变化_只叠加 Key`() {
        // 现代模式（变速不变调）：speed=1.5 时基础音高仍是 1
        assertEquals(1.0f, KeyShift.totalPitch(1.5f, retroMode = false, semitones = 0), 1e-6f)
        assertEquals(KeyShift.factor(2), KeyShift.totalPitch(1.5f, retroMode = false, semitones = 2), 1e-6f)
    }

    @Test
    fun `复古模式在速度音高之上再叠加 Key`() {
        // 复古模式（磁带效果）：speed=1.5 自带音高 1.5
        assertEquals(1.5f, KeyShift.totalPitch(1.5f, retroMode = true, semitones = 0), 1e-6f)
        assertEquals(1.5f * KeyShift.factor(3), KeyShift.totalPitch(1.5f, retroMode = true, semitones = 3), 1e-6f)
    }

    @Test
    fun `标签用原调与带符号半音数`() {
        assertEquals("原调", KeyShift.label(0))
        assertEquals("+2", KeyShift.label(2))
        assertEquals("−3", KeyShift.label(-3))
    }
}
