package com.shiyin.music.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.11:Memory 可观测状态的**文案**回归锁。
 *
 * 存在理由(用户原话):"Memory 出问题时无法判断到底是没保存 / 保存失败 / recall 没找到 /
 * recall 找到了但被门槛过滤 / 找到了没注入 / 注入了模型没用"。所以每一种相位都必须有
 * **各自**的文案——把"没找到"和"出异常"合并成一句话,可观测性就是假的。
 *
 * 另一条硬要求:文案只写用户看得懂的话,不许泄漏类名/表名/FTS 等技术细节。
 */
class MemoryTraceTest {

    @Test
    fun `每种相位都有各自的中文文案 - 不允许合并`() {
        assertEquals("查找记忆中…", label(MemoryTrace.Phase.CHECKING))
        assertEquals("已找到相关记忆", label(MemoryTrace.Phase.FOUND))
        assertEquals("未找到相关记忆", label(MemoryTrace.Phase.NOT_FOUND))
        assertEquals("记忆中…", label(MemoryTrace.Phase.SAVING))
        assertEquals("记忆已保存", label(MemoryTrace.Phase.SAVED))
        assertEquals("记忆保存失败", label(MemoryTrace.Phase.SAVE_FAILED))
        assertEquals("回忆失败", label(MemoryTrace.Phase.RECALL_FAILED))
    }

    @Test
    fun `没有命中和发生异常必须是两句不同的话`() {
        val empty = label(MemoryTrace.Phase.NOT_FOUND)
        val failed = label(MemoryTrace.Phase.RECALL_FAILED)
        assertTrue("不能是同一句", empty != failed)
        assertTrue("未命中要说清是没找到", empty.contains("未找到"))
        assertTrue("异常要说清是失败", failed.contains("失败"))
    }

    @Test
    fun `IDLE 不显示任何文案 - 它就是没有记忆活动`() {
        assertEquals("", label(MemoryTrace.Phase.IDLE))
    }

    @Test
    fun `文案不泄漏内部技术细节`() {
        for (phase in MemoryTrace.Phase.values()) {
            val l = label(phase)
            for (bad in listOf("FTS", "Room", "MemoryManager", "MemoryRules", "sqlite", "SQL", "embedding")) {
                assertFalse("文案「$l」不该出现内部名词 $bad", l.contains(bad))
            }
        }
    }

    private fun label(phase: MemoryTrace.Phase) = MemoryTrace(phase).label
}
