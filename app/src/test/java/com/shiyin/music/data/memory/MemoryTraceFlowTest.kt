package com.shiyin.music.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.11 第二阶段:一轮 Agent Turn 的**记忆状态机**(MemoryTraceFlow)。
 *
 * 这一层要钉死的东西只有一个,但很关键:
 *
 * **一轮最多一行。**
 *
 * 上一版的 bug:保存与检索各自持有一个槽位,最后拼成 list 渲染 → 同一轮里
 * 「🧠 记忆已保存」与「🧠 已找到记忆」同时出现两行。用户看到的是"事件列表",
 * 而它本该是"这一轮 Memory 走到哪一步了"的**单一状态**。
 *
 * 所以这里的断言分两类:
 *  - [MemoryTraceFlow.uiRows] 永远 ≤ 1(结构性不变量,UI 直接渲染它);
 *  - [MemoryTraceFlow.history] 记录**经历过**的相位(用于验证流程顺序是否与规格一致)。
 */
class MemoryTraceFlowTest {

    private fun flow() = MemoryTraceFlow()



    /** 每个用例都顺手校验一次的不变量。 */
    private fun assertOneRow(f: MemoryTraceFlow) =
        assertTrue("一轮只能有一行记忆状态,实际 ${f.uiRows.size} 行", f.uiRows.size <= 1)

    // ── ① 核心不变量:任何调用顺序下都只有一行 ───────────────────────────

    @Test
    fun `无论经历多少相位 - UI 行数永远不超过 1`() {
        val f = flow()
        assertOneRow(f)
        f.begin(); assertOneRow(f)
        f.found(2); assertOneRow(f)
        f.saving(); assertOneRow(f)
        f.saved(); assertOneRow(f)
        f.recallFailed(); assertOneRow(f)
        f.saveFailed(); assertOneRow(f)
        f.nothingFound(); assertOneRow(f)
    }

    @Test
    fun `没有开始就不该有任何行 - 普通聊天不会凭空多出一行`() {
        val f = flow()
        assertNull("没调用 begin 时不该有状态", f.current)
        assertTrue("历史也应为空", f.history.isEmpty())
        assertTrue(f.uiRows.isEmpty())
    }

    // ── ② 检索:查找中 → 已找到相关记忆(一行,两个相位) ────────────────

    @Test
    fun `检索命中 - 查找记忆中 变 已找到相关记忆 而不是新增一行`() {
        val f = flow()
        f.begin()
        assertEquals("第一步应是「查找记忆中」", MemoryTrace.Phase.CHECKING, f.current?.phase)
        assertEquals("查找记忆中…", f.current?.label)

        f.found(2)
        assertEquals("同一行更新为已找到", MemoryTrace.Phase.FOUND, f.current?.phase)
        assertEquals("已找到相关记忆", f.current?.label)
        assertEquals("经历过两个相位", listOf(MemoryTrace.Phase.CHECKING, MemoryTrace.Phase.FOUND), f.history)
        assertEquals("但仍然只有一行", 1, f.uiRows.size)
    }

    // ── ③ 检索为空:查找中 → 未找到相关记忆(一行) ─────────────────────

    @Test
    fun `检索为空 - 查找记忆中 变 未找到相关记忆 `() {
        val f = flow()
        f.begin()
        f.nothingFound()
        assertEquals(MemoryTrace.Phase.NOT_FOUND, f.current?.phase)
        assertEquals("未找到相关记忆", f.current?.label)
        assertEquals(listOf(MemoryTrace.Phase.CHECKING, MemoryTrace.Phase.NOT_FOUND), f.history)
        assertEquals(1, f.uiRows.size)
    }

    // ── ④ 保存:查找中 → 记忆中 → 记忆已保存(一行) ───────────────────

    @Test
    fun `保存成功 - 查找记忆中 记忆中 记忆已保存 全程一行`() {
        val f = flow()
        f.begin()
        f.saving()
        assertEquals("记忆中…", f.current?.label)
        f.saved()
        assertEquals("记忆已保存", f.current?.label)
        assertEquals(listOf(MemoryTrace.Phase.CHECKING, MemoryTrace.Phase.SAVING, MemoryTrace.Phase.SAVED), f.history)
        assertEquals(1, f.uiRows.size)
    }

    @Test
    fun `显式记住 且 已有相关记忆 - 四个相位仍然只有一行`() {
        // 场景 D:再次告诉相同信息 → 先命中已有记忆,再按 dedupe 结果保存
        val f = flow()
        f.begin()
        f.found(1)
        f.saving()
        f.saved()
        assertEquals(listOf(MemoryTrace.Phase.CHECKING, MemoryTrace.Phase.FOUND, MemoryTrace.Phase.SAVING, MemoryTrace.Phase.SAVED), f.history)
        assertEquals(1, f.uiRows.size)
        assertEquals(MemoryTrace.Phase.SAVED, f.current?.phase)
    }

    // ── ⑤ 失败态:两行都不许出现,且失败与"没找到"必须可区分 ──────────────

    @Test
    fun `保存失败 - 终态是记忆保存失败 而不是已保存`() {
        val f = flow()
        f.begin()
        f.saving()
        f.saveFailed()
        assertEquals(MemoryTrace.Phase.SAVE_FAILED, f.current?.phase)
        assertEquals("记忆保存失败", f.current?.label)
        assertEquals(1, f.uiRows.size)
    }

    @Test
    fun `检索异常与检索为空必须是两个不同的终态`() {
        val empty = flow().begin().nothingFound()
        val failed = flow().begin().recallFailed()
        assertEquals(MemoryTrace.Phase.NOT_FOUND, empty.current?.phase)
        assertEquals(MemoryTrace.Phase.RECALL_FAILED, failed.current?.phase)
        assertTrue("两者不能是同一句话", empty.current!!.label != failed.current!!.label)
    }

    // ── ⑥ 动画只属于进行态 ───────────────────────────────────────────────

    @Test
    fun `光流动画只出现在进行中的相位`() {
        assertTrue("查找记忆中是进行态", MemoryTrace(MemoryTrace.Phase.CHECKING).running)
        assertTrue("记忆中 是进行态", MemoryTrace(MemoryTrace.Phase.SAVING).running)
        for (phase in listOf(MemoryTrace.Phase.IDLE, MemoryTrace.Phase.FOUND, MemoryTrace.Phase.NOT_FOUND, MemoryTrace.Phase.SAVED, MemoryTrace.Phase.SAVE_FAILED, MemoryTrace.Phase.RECALL_FAILED)) {
            assertTrue("$phase 不该有动画", !MemoryTrace(phase).running)
        }
    }
}
