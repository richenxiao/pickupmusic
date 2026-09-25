package com.shiyin.music.data.ai

import com.shiyin.music.data.ai.skills.AlbumDatesSkill
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.8 album_dates 技能测试。
 *
 * 这个技能是**能力空洞的补丁**:用户说"排一下周杰伦的发布日期"时,此前没有任何工具
 * 接得住,模型只能抓 library_query → 答非所问 + 空回复。这里覆盖它的判定与回执:
 * 尤其是"没有歌手时不要瞎猜一个去写库"和"失败要透传真实原因而不是笼统报错"。
 *
 * 真正联网+写库的那段(MainViewModel.sortArtistAlbumsByDate → fetchArtistAlbumDates)
 * 不在单测范围——它已在歌手页长期运行,且需真机验证。
 */
class AlbumDatesSkillTest {

    private class FakeCtx(
        private val currentArtist: String? = null,
        private val outcome: AgentEngine.AlbumDateOutcome = AgentEngine.AlbumDateOutcome(3),
    ) : FakeSkillContext() {
        var asked: String? = null
        override val artistKey: String? = currentArtist
        override suspend fun sortArtistAlbumsByDate(artistName: String): AgentEngine.AlbumDateOutcome {
            asked = artistName
            return outcome
        }
    }

    private val cfg = LlmProviderConfig("test", "test", "https://example.invalid", "", "")

    private fun run(ctx: FakeCtx, args: Map<String, String>) =
        runBlocking { AlbumDatesSkill.execute(ctx, args, cfg, "") { _, _ -> } }

    @Test
    fun `点名歌手时按该歌手排序`() {
        val ctx = FakeCtx()
        val r = run(ctx, mapOf("artist" to "周杰伦"))
        assertEquals("周杰伦", ctx.asked)
        assertTrue(r.success)
        assertTrue("回执应含歌手名与张数,实际:${r.summary}", r.summary.contains("周杰伦") && r.summary.contains("3"))
    }

    @Test
    fun `没点名时回退当前歌手页`() {
        val ctx = FakeCtx(currentArtist = "星野源")
        val r = run(ctx, emptyMap())
        assertEquals("星野源", ctx.asked)
        assertTrue(r.success)
    }

    @Test
    fun `既没点名也没打开歌手页时明确引导而不是瞎猜`() {
        // 关键:不能随便挑一个歌手就去写库——那会改错人的作品顺序
        val ctx = FakeCtx(currentArtist = null)
        val r = run(ctx, emptyMap())
        assertFalse(r.success)
        assertEquals("不该调用排序", null, ctx.asked)
        assertTrue("应给出可照说的示例,实际:${r.summary}", r.summary.contains("歌手"))
    }

    @Test
    fun `失败时原样透传真实原因`() {
        // 与歌手页状态行同一句文案——不要笼统报"失败了"
        val ctx = FakeCtx(outcome = AgentEngine.AlbumDateOutcome(0, "LLM 调用失败:HTTP 429: 额度耗尽"))
        val r = run(ctx, mapOf("artist" to "星野源"))
        assertFalse(r.success)
        assertTrue("应透传真实原因,实际:${r.summary}", r.summary.contains("429"))
    }

    @Test
    fun `失败且无原因时给可行动提示`() {
        val ctx = FakeCtx(outcome = AgentEngine.AlbumDateOutcome(0, ""))
        val r = run(ctx, mapOf("artist" to "星野源"))
        assertFalse(r.success)
        assertTrue("不该是空消息(那正是要修的 bug),实际:${r.summary}", r.summary.isNotBlank())
    }

    @Test
    fun `步骤清单声明了 fetch 与 write 两行`() {
        assertEquals(listOf("fetch", "write"), AlbumDatesSkill.panelSteps(emptyMap()).map { it.id })
    }
}
