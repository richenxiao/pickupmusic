package com.shiyin.music.data.stats

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * v1.3.9: 「本周起点」的日期边界 —— 锁死**周日当天本周统计为空**的真 bug。
 *
 * 原实现 `cal.set(DAY_OF_WEEK, MONDAY)` 依赖 Calendar 的 locale 首日(默认常为周日),
 * 周日调用会把日期**向后**拨到明天(下周一) → 起点 > now → 本周事件全被过滤。
 */
class WeekStartTest {

    private fun at(y: Int, m: Int, d: Int, h: Int) =
        Calendar.getInstance().apply { clear(); set(y, m, d, h, 0, 0) }.timeInMillis

    @Test
    fun `周日取到的本周一是六天前而不是明天`() {
        val sunday = at(2026, Calendar.SEPTEMBER, 27, 15)   // 2026-09-27 周日
        val start = currentWeekStartMillis(sunday)
        assertTrue("起点必须早于当前时刻(原 bug 会得到明天)", start <= sunday)
        val c = Calendar.getInstance().apply { timeInMillis = start }
        assertEquals("应是周一", Calendar.MONDAY, c.get(Calendar.DAY_OF_WEEK))
        assertEquals("应是 9/21", 21, c.get(Calendar.DAY_OF_MONTH))
        assertEquals("零点", 0, c.get(Calendar.HOUR_OF_DAY))
    }

    @Test
    fun `周一当天起点就是当天零点`() {
        val monday = at(2026, Calendar.SEPTEMBER, 28, 9)    // 2026-09-28 周一
        val start = currentWeekStartMillis(monday)
        val c = Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(Calendar.MONDAY, c.get(Calendar.DAY_OF_WEEK))
        assertEquals(28, c.get(Calendar.DAY_OF_MONTH))
        assertEquals(0, c.get(Calendar.HOUR_OF_DAY))
        assertTrue(start <= monday)
    }

    @Test
    fun `周六取到的仍是本周一`() {
        val sat = at(2026, Calendar.OCTOBER, 3, 23)         // 2026-10-03 周六
        val start = currentWeekStartMillis(sat)
        val c = Calendar.getInstance().apply { timeInMillis = start }
        assertEquals(Calendar.MONDAY, c.get(Calendar.DAY_OF_WEEK))
        assertEquals(28, c.get(Calendar.DAY_OF_MONTH))      // 9/28 周一
        assertEquals(Calendar.SEPTEMBER, c.get(Calendar.MONTH))
    }
}
