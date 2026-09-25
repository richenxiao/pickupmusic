package com.shiyin.music.data.ai

import com.shiyin.music.data.Track
import com.shiyin.music.data.ai.skills.SongStorySkill
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.8 歌曲背景故事技能的纯逻辑测试。
 *
 * 重点在**搜索角度的语言选择**:这是本技能最容易静默失败的地方——对日语曲目用中文
 * 关键词去搜,搜不到东西不会报错,只会一路走到"没搜到资料"的提示,看起来像"这首歌
 * 没资料",实际是查询词错了。AgentService 在排序链路上已经踩过一次同样的坑。
 *
 * 不测联网分支(Tavily 真调用会让单测慢且不稳);资料为空时的提示走 tavilyKey 为空的
 * 等价分支覆盖。
 */
class SongStorySkillTest {

    /** 只覆写 searchTracks / currentTrack,其余用 FakeSkillContext 的空实现。 */
    private class FakeCtx(
        private val searchHits: List<Track> = emptyList(),
        private val current: Track? = null,
    ) : FakeSkillContext() {
        override fun currentTrack(): Track? = current
        override fun searchTracks(query: String, limit: Int): List<Track> = searchHits
    }

    private val cfg = LlmProviderConfig("test", "test", "https://example.invalid", "", "")

    // ── 搜索角度 ──────────────────────────────────────────────────────────

    @Test
    fun `始终产出三个搜索角度`() {
        assertEquals(3, SongStorySkill.searchQueriesFor("晴天", "周杰伦").size)
        assertEquals(3, SongStorySkill.searchQueriesFor("Some Song", "").size)
    }

    @Test
    fun `中文曲目用中文关键词`() {
        val qs = SongStorySkill.searchQueriesFor("晴天", "周杰伦")
        assertTrue("应含创作背景角度,实际:$qs", qs.any { it.contains("创作背景") })
        assertTrue("应含歌词含义角度,实际:$qs", qs.any { it.contains("歌词含义") })
        for (q in qs) {
            assertTrue("查询词应带歌名:$q", q.contains("晴天"))
            assertTrue("查询词应带歌手:$q", q.contains("周杰伦"))
        }
    }

    @Test
    fun `日语曲目用日语关键词`() {
        // 含假名 → 判为日语。用中文关键词搜日语曲目拿不到资料,这条是防回归的关键。
        val qs = SongStorySkill.searchQueriesFor("ひまわりの約束", "秦基博")
        assertTrue("日语曲目不该用中文关键词,实际:$qs", qs.none { it.contains("创作背景") || it.contains("歌词含义") })
        assertTrue("应含日语查询词,实际:$qs", qs.any { it.contains("歌詞") || it.contains("楽曲") })
    }

    @Test
    fun `英文曲目用英文关键词`() {
        val qs = SongStorySkill.searchQueriesFor("Yesterday", "The Beatles")
        assertTrue("英文曲目应用英文关键词,实际:$qs", qs.any { it.contains("lyrics") || it.contains("background") })
    }

    @Test
    fun `没有歌手时查询词只用歌名`() {
        val qs = SongStorySkill.searchQueriesFor("晴天", "")
        for (q in qs) {
            assertTrue("不该出现多余空格或空歌手:$q", q.trim() == q && !q.contains("  "))
        }
    }

    // ── 参数与失败路径 ────────────────────────────────────────────────────

    @Test
    fun `没指定歌也没有正在播放时报错引导`() {
        val r = runBlocking {
            SongStorySkill.execute(FakeCtx(), emptyMap(), cfg, "") { _, _ -> }
        }
        assertFalse(r.success)
        assertTrue("应引导用户点名或先播放,实际:${r.summary}", r.summary.contains("讲讲"))
    }

    @Test
    fun `没有配置联网搜索时明说而不是编造`() {
        // 用户拍板:资料不足时明说没找到,不用模型自身知识编。
        // tavilyKey 为空 → TavilyService 直接返回 null → 走"资料为空"分支。
        val r = runBlocking {
            SongStorySkill.execute(FakeCtx(), mapOf("song" to "晴天"), cfg, "") { _, _ -> }
        }
        assertFalse(r.success)
        assertTrue("应提到没配联网搜索,实际:${r.summary}", r.summary.contains("联网搜索"))
        assertTrue("应带上被问的歌名,实际:${r.summary}", r.summary.contains("晴天"))
    }
}
