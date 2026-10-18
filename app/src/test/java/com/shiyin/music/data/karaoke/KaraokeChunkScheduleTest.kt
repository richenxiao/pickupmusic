package com.shiyin.music.data.karaoke

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P0-B（用户 2026-10-18）：**"切到伴奏后露出一两秒人声再回到伴奏"的复现与验收**。
 *
 * 现场：一块覆盖 5.78 秒音频，实测算一块要 2.37 秒（真机 RTF 0.413）。
 * 切档的时机是"**播放头所在那块写好**"的瞬间。那一刻缓存还剩多少领先，取决于播放头**恰好落在
 * 块的哪个位置**：
 * ```
 *   领先 ≥ 一块的耗时(2.37s)  ⇒ 下一块来得及，没事
 *   领先 <  一块的耗时        ⇒ 中间读不到伴奏 ⇒ 欠载 ⇒ 系数拉回原曲（听到人声），
 *                              等下一块写好再淡回伴奏
 * ```
 * 播放头相对块的位置是**任意**的，所以这是"偶尔"发生 —— 与用户描述的"有时会露出一两秒"一致。
 *
 * 本文件做两件事：
 *  1. **离散时间模拟**（[Timeline]）：在整段播放头上**扫一遍**，找出旧策略最坏会漏多久；
 *  2. 用同一套模拟证明修法（[KaraokeChunkSchedule.order] + [KaraokeChunkSchedule.SWITCH_LEAD_SECONDS]）
 *     在**同一个最坏位置**下切换后 30 秒零欠载。
 *
 * ⚠️ 全部是**模型算出来的时间线**，不是真机实测。真机项见《待设备验证清单》。
 */
class KaraokeChunkScheduleTest {

    // ── 真机参数（来自 `_map.tsv` / RTF 日志；startup 是估计值，见报告）──────
    private val gen = 254_976                 // 模型域帧/块 ≈ 5.78s（1024×255 − 6144）
    private val total = 45                    // lost you twice（258.1s）的块数
    private val chunkCostMs = 2_369L          // 真机 avgChunk
    private val startupMs = 4_500L            // 解码整首 + 建 ONNX 会话（**估计**，无实测）

    /**
     * 离散时间线：按 `orderOf(播放头)` 依次算块，每块耗 [costMs]，从 [startupMs] 起算。
     *
     * ⚠️ **顺序是在"解码+建模完成"那一刻**按**当时的**播放头定的（与 [KaraokeCacheJob] 一致）——
     * 不是按点击时刻。这一点很关键：等待期间播放头还在往前走。
     */
    private class Timeline(
        val total: Int,
        val gen: Int,
        val play0: Long,
        val startupMs: Long,
        val costMs: Long,
        orderOf: (Long) -> List<Int>,
    ) {
        val order: List<Int> = orderOf(frameAt(startupMs))
        val readyMs = LongArray(total) { Long.MAX_VALUE }

        init {
            var t = startupMs
            for (c in order) {
                t += costMs
                readyMs[c] = t
            }
        }

        fun frameAt(t: Long): Long = play0 + t * KaraokeChunkSchedule.MODEL_RATE / 1000L

        fun has(t: Long, c: Int): Boolean = c in 0 until total && readyMs[c] <= t

        fun lead(t: Long): Double = KaraokeChunkSchedule.leadSeconds(
            modelFrames = total * gen, gen = gen, playModelFrame = frameAt(t),
        ) { c -> has(t, c) }

        /** 播放头所在的块是否已写好（= 处理器能不能读到伴奏）。 */
        fun covered(t: Long): Boolean {
            val c = (frameAt(t) / gen).toInt()
            return c >= total || has(t, c)
        }

        /** 首次满足条件的**块完成时刻**。 */
        fun firstReadyTimeWhere(cond: (Long) -> Boolean): Long? =
            readyMs.filter { it != Long.MAX_VALUE }.sorted().firstOrNull { cond(it) }

        /** [fromMs, toMs) 内的欠载区间（10ms 步长扫）。 */
        fun underruns(fromMs: Long, toMs: Long): List<Pair<Long, Long>> {
            val out = ArrayList<Pair<Long, Long>>()
            var start = -1L
            var t = fromMs
            while (t < toMs) {
                val bad = !covered(t)
                if (bad && start < 0) start = t
                if (!bad && start >= 0) {
                    out += start to t
                    start = -1
                }
                t += 10
            }
            if (start >= 0) out += start to toMs
            return out
        }
    }

    /** 旧策略：从第 0 块顺序算（不管播放头在哪）。 */
    private fun oldOrder(): List<Int> = (0 until total).toList()

    /** 旧策略下，给定点击位置：切换时刻与切换后 30 秒的欠载合计毫秒。 */
    private fun oldPolicy(play0: Long): Triple<Long, Long, Long>? {
        val tl = Timeline(total, gen, play0, startupMs, chunkCostMs) { oldOrder() }
        val sw = tl.firstReadyTimeWhere { tl.covered(it) } ?: return null
        val gap = tl.underruns(sw, sw + 30_000).sumOf { it.second - it.first }
        return Triple(play0, sw, gap)
    }

    // ── 一、复现旧策略的漏人声（扫遍所有相位找最坏）─────────────────────────

    @Test
    fun `旧策略_扫遍播放头相位_最坏会漏一两秒人声`() {
        var worst: Triple<Long, Long, Long>? = null
        var leakers = 0
        var n = 0
        // 从 60s 扫到 120s，步长 0.5s —— 覆盖"副歌点伴奏"的常见落点
        var s = 60_000L
        while (s <= 120_000L) {
            val r = oldPolicy(s * 44100L / 1000L)
            if (r != null) {
                n++
                if (r.third > 0) leakers++
                if (worst == null || r.third > worst!!.third) worst = r
            }
            s += 500
        }
        assertTrue("应该能在这些位置里等到切换", worst != null)
        val w = worst!!

        println(
            "[P0-B] 旧策略：扫 $n 个位置，其中 $leakers 个会漏人声（${100.0 * leakers / n}%）；" +
                "最坏在播放头 ${w.first / 44100.0}s 处 —— 第 ${w.second / 1000.0}s 才切，" +
                "切换后漏 ${w.third / 1000.0}s",
        )
        assertTrue("最坏情况必须达到'一两秒'量级，实测 ${w.third / 1000.0}s", w.third >= 1_000)
    }

    @Test
    fun `旧策略_播放头在3分钟处_要等一分钟以上才轮到`() {
        val play0 = 180L * 44100L
        val old = Timeline(total, gen, play0, startupMs, chunkCostMs) { oldOrder() }
        val oldSwitch = old.firstReadyTimeWhere { old.covered(it) }
        assertTrue("旧策略：3 分钟处应能等到切换", oldSwitch != null)

        val new = Timeline(total, gen, play0, startupMs, chunkCostMs) {
            KaraokeChunkSchedule.order(total, gen, it)
        }
        val newSwitch = new.firstReadyTimeWhere {
            new.lead(it) >= KaraokeChunkSchedule.SWITCH_LEAD_SECONDS
        }
        assertTrue("新策略：3 分钟处应能等到切换", newSwitch != null)

        println(
            "[P0-B] 3 分钟处点伴奏 ⇒ 旧策略 ${oldSwitch!! / 1000.0}s 才切，" +
                "新策略 ${newSwitch!! / 1000.0}s 才切（省 ${(oldSwitch - newSwitch) / 1000.0}s）",
        )
        assertTrue("新策略必须显著快于旧策略", newSwitch < oldSwitch / 2)
    }

    // ── 二、验收修法：同一个最坏位置，切换后 30 秒零欠载 ────────────────────

    @Test
    fun `新策略_旧策略最坏的那个位置_切换后30秒零欠载`() {
        // 找出旧策略最坏的点击位置
        var worst: Triple<Long, Long, Long>? = null
        var s = 60_000L
        while (s <= 120_000L) {
            oldPolicy(s * 44100L / 1000L)?.let { if (worst == null || it.third > worst!!.third) worst = it }
            s += 500
        }
        val play0 = worst!!.first

        val tl = Timeline(total, gen, play0, startupMs, chunkCostMs) {
            KaraokeChunkSchedule.order(total, gen, it)
        }
        val sw = tl.firstReadyTimeWhere { tl.lead(it) >= KaraokeChunkSchedule.SWITCH_LEAD_SECONDS }!!
        val lead = tl.lead(sw)

        println(
            "[P0-B] 新策略：同一位置（${play0 / 44100.0}s，旧策略在这里漏 ${worst!!.third / 1000.0}s）" +
                " ⇒ 第 ${sw / 1000.0}s 切，切换瞬间领先 ${"%.2f".format(lead)}s",
        )
        assertTrue("切换瞬间必须已过门槛", lead >= KaraokeChunkSchedule.SWITCH_LEAD_SECONDS)
        assertEquals("同一位置，切换后 30 秒必须 0 欠载", 0, tl.underruns(sw, sw + 30_000).size)
    }

    @Test
    fun `新策略_扫遍播放头相位_全程零欠载`() {
        var bad = 0
        var n = 0
        var s = 60_000L
        while (s <= 120_000L) {
            val play0 = s * 44100L / 1000L
            val tl = Timeline(total, gen, play0, startupMs, chunkCostMs) {
                KaraokeChunkSchedule.order(total, gen, it)
            }
            val sw = tl.firstReadyTimeWhere { tl.lead(it) >= KaraokeChunkSchedule.SWITCH_LEAD_SECONDS }
            if (sw != null) {
                n++
                if (tl.underruns(sw, sw + 30_000).isNotEmpty()) bad++
            }
            s += 500
        }
        println("[P0-B] 新策略：扫 $n 个位置，切换后 30 秒内有欠载的 $bad 个")
        assertEquals("新策略必须一个都不漏", 0, bad)
    }

    // ── 三、纯逻辑单测 ────────────────────────────────────────────────────

    @Test
    fun `顺序_从播放头起算_长度不变_覆盖每一块`() {
        val play = 20L * gen + gen / 2
        val o = KaraokeChunkSchedule.order(total, gen, play)
        assertEquals("长度必须等于块数", total, o.size)
        assertEquals("必须覆盖每一块且不重复", (0 until total).toSet(), o.toSet())
        // 播放头 − 2 秒仍落在第 20 块 ⇒ 从第 20 块起
        assertEquals("应从播放头所在块起算", 20, o.first())
        assertEquals("后半段是绕回开头的 [0,20)", (0 until 20).toList(), o.takeLast(20))
    }

    @Test
    fun `顺序_播放头未知时退回从头顺序`() {
        assertEquals((0 until total).toList(), KaraokeChunkSchedule.order(total, gen, -1))
        assertEquals((0 until total).toList(), KaraokeChunkSchedule.order(total, 0, 100))
        assertEquals(emptyList<Int>(), KaraokeChunkSchedule.order(0, gen, 100))
    }

    @Test
    fun `顺序_播放头在开头时就是从头顺序`() {
        assertEquals((0 until total).toList(), KaraokeChunkSchedule.order(total, gen, 0))
    }

    @Test
    fun `领先量_播放头那块没写好就是零`() {
        val play = 3L * gen + 100
        assertEquals(0.0, KaraokeChunkSchedule.leadSeconds(total * gen, gen, play) { it == 2 }, 1e-9)
    }

    @Test
    fun `领先量_数连续块到缺口为止`() {
        val play = 3L * gen + 100
        // 第 3、4、5 块已写好，第 6 块缺 ⇒ 只算到第 5 块末
        val lead = KaraokeChunkSchedule.leadSeconds(total * gen, gen, play) { it in 3..5 }
        assertEquals(((6L * gen) - play) / 44100.0, lead, 1e-9)
    }

    @Test
    fun `领先量_播放头已在末尾之外返回零`() {
        assertEquals(
            0.0,
            KaraokeChunkSchedule.leadSeconds(total * gen, gen, (total + 5L) * gen) { true },
            1e-9,
        )
    }

    @Test
    fun `块数口径与真机一致`() {
        val frames = 11_382_514            // lost you twice 的模型域帧数（= 12,389,131 × 44100/48000）
        assertEquals("真机实测就是 45 块", 45, KaraokeChunkSchedule.chunkCount(frames, gen))
    }
}
