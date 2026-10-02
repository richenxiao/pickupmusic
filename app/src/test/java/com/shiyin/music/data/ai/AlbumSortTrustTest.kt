package com.shiyin.music.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.15:排序链路的**两个信任问题**(真机 2026-10-02《龙年》实测暴露):
 *
 *  ① **用户自己给了顺序,程序却当没看见** —— 排序调用的提示里只有"本地曲目表 + 联网资料",
 *     不含用户原话。用户整段贴了 `1.枣庄市马思唯 2.轨道飞虎 …`,程序仍回"已按官方顺序排列"。
 *  ② **资料不是这张专辑的,却被当权威** —— Tavily 抓到一段同名作品的 B 站视频简介,
 *     与本地曲目**重合 0 首**,代码只看"有没有资料"就报高置信度 → 排错了还很自信。
 *
 * 测试向量直接取自当天真机数据(用户给的顺序 / 那段视频简介),不再另造。
 */
class AlbumSortTrustTest {

    /** 用户当天贴进对话的官方顺序(他口述的,逐字保留)。 */
    private val userPastedOrder = """
        1.枣庄市马思唯
        2.轨道飞虎
        3.山东王FREESTYLE
        4.底层特律
        5.老乡日
        6. 爽文
        7.十男九痔
        8.常胜将军
        9.顶男
        10. SHXT CHANGE
        11.过肺
        12.枣庄一夜
        13.川渝女孩
        14.天下谁人不识君这个
    """.trimIndent()

    /** 本地专辑的 14 首(故意乱序,模拟"当前顺序可能有误")。 */
    private val localTitles = listOf(
        "山东王FREESTYLE", "天下谁人不识君", "老乡日", "十男九痔", "过肺", "轨道飞虎", "顶男",
        "川渝女孩", "枣庄一夜", "常胜将军", "底层特律", "爽文", "枣庄市马思唯", "SHXT CHANGE",
    )

    /** 当天那段"资料"(B 站同名/搬运视频简介,曲目与本地**一首都不重合**)。 */
    private val badWeb = "【『Hi-Res无损』分Part版｜华云龙KLE《龙年》专辑｜14首全专收录_哔哩哔哩】 " +
        "02:31 2. Pain Wit Da Beatz 03:21 3. Cash Money 2 03:32 4. YioWerr药娃儿 03:01 5. Sin House " +
        "03:41 6. 插到底 03:21 7. 盆满钵满 02:58 12. “持续输出” Bonus Track 1. 555 Freestyle"

    // ── ① 用户给了顺序 → 直接按它排 ─────────────────────────────────────────

    @Test
    fun `用户贴出的顺序能原样映射成重排下标`() {
        val order = AgentService.userGivenOrder(userPastedOrder, localTitles)
        assertEquals("14 首应全部对上", 14, order.size)
        // 映射出来的顺序必须**正是用户写的那个顺序**
        assertEquals(
            listOf(
                "枣庄市马思唯", "轨道飞虎", "山东王FREESTYLE", "底层特律", "老乡日", "爽文", "十男九痔",
                "常胜将军", "顶男", "SHXT CHANGE", "过肺", "枣庄一夜", "川渝女孩", "天下谁人不识君",
            ),
            order.map { localTitles[it] },
        )
    }

    @Test
    fun `一行到底的编号列表也能解析`() {
        val oneLine = "1. 轨道飞虎 2. 枣庄市马思唯 3. 老乡日 4. 过肺"
        assertEquals(
            listOf("轨道飞虎", "枣庄市马思唯", "老乡日", "过肺"),
            AgentService.extractListedNames(oneLine),
        )
    }

    @Test
    fun `闲聊不会被当成"给了顺序"`() {
        // 只提到两三首、或者全是大白话 → 不抢原链路(仍走联网 + 模型)
        assertEquals(emptyList<Int>(), AgentService.userGivenOrder("这个顺序是错误的,你真的联网搜索了吗", localTitles))
        assertEquals(emptyList<Int>(), AgentService.userGivenOrder("轨道飞虎 老乡日", localTitles))
    }

    @Test
    fun `给了大半但不足八成 - 仍交给模型判断`() {
        // 6/14 覆盖不够:用户可能只是举例,不能据此重排整张专辑
        val partial = "1.轨道飞虎\n2.老乡日\n3.过肺\n4.顶男\n5.爽文\n6.常胜将军"
        assertEquals(emptyList<Int>(), AgentService.userGivenOrder(partial, localTitles))
    }

    // ── ② 资料是不是这张专辑的 ─────────────────────────────────────────────

    @Test
    fun `同名作品的资料与本地曲目重合为 0 - 这正是当天的翻车点`() {
        assertEquals(
            "那段 B 站简介与本地 14 首一首都不重合 —— 必须能判出来",
            0, AgentService.webContextHits(localTitles, badWeb),
        )
    }

    @Test
    fun `资料确实提到本地曲目时重合数不为 0`() {
        val goodWeb = "华云龙KLE《龙年》曲目: 1.枣庄市马思唯 2.轨道飞虎 3.山东王FREESTYLE 4.底层特律"
        assertTrue(AgentService.webContextHits(localTitles, goodWeb) >= 4)
    }

    @Test
    fun `没有资料时重合数为 0 而不是崩`() {
        assertEquals(0, AgentService.webContextHits(localTitles, null))
        assertEquals(0, AgentService.webContextHits(localTitles, "   "))
        assertEquals(0, AgentService.webContextHits(emptyList(), "任意资料"))
    }
}
