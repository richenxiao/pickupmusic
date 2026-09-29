package com.shiyin.music.data.ai

import com.shiyin.music.data.ai.PlaybackIntentGuard.Decision
import com.shiyin.music.data.ai.PlaybackIntentGuard.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.12:播放执行意图闸门的回归测试 —— 真机 bug 的锁。
 *
 * 真机 bug:上一轮「放一下 Pretender」成功后,这一轮「秋天就喜欢听些苦情歌。」
 * 被弱模型读成"The user wants to play Pretender..." → **又放了一遍**。
 *
 * 这里钉住两件事:
 *  - **陈述/感想不是指令**(Intent.NONE → BlockNoIntent);
 *  - **每轮独立判断,不继承上一轮**(所以第 5 条与第 3 条结论必须一致)。
 */
class PlaybackIntentGuardTest {


    private fun cls(t: String) = PlaybackIntentGuard.classify(t)

    // ── ① 明确命令:要放行 ────────────────────────────────────────────────

    @Test
    fun `明确播放命令被识别为 PLAY`() {
        for (t in listOf(
            "播放 Pretender",
            "放一下 Pretender",
            "给我放 Pretender",
            "帮我放一下 Pretender",
            "来一首 Pretender",
            "我想听 Pretender",
            "想听 Pretender",
            "让我听听这首",
            "把这首放一下",
            "点一首 Pretender",
        )) {
            assertEquals("「$t」应判为 PLAY", Intent.PLAY, cls(t))
        }
    }

    @Test
    fun `明确控制指令被识别为 CONTROL`() {
        // 「换一首」是切换类控制(不带对象),归 CONTROL;两者在闸门里都 = 放行。
        for (t in listOf("暂停", "继续播放", "下一首", "上一首", "切歌", "接着放", "换一首")) {
            assertEquals("「$t」应判为 CONTROL", Intent.CONTROL, cls(t))
        }
    }

    @Test
    fun `明确要求重来被识别为 RESTART`() {
        for (t in listOf("重新播放", "再放一遍", "再来一次", "从头再放一遍", "重放这首歌")) {
            assertEquals("「$t」应判为 RESTART", Intent.RESTART, cls(t))
        }
    }

    // ── ② 感想 / 闲聊:必须拦下(本轮修复的核心) ────────────────────────

    @Test
    fun `音乐感想与闲聊不是播放指令`() {
        for (t in listOf(
            "秋天就喜欢听些苦情歌。",
            "这首歌真的很适合秋天",
            "这首歌很适合秋天",
            "最近特别喜欢这种歌",
            "我最近很喜欢这种歌",
            "这首歌前奏很好听",
            "这首歌让我想起以前",
            "我感觉这首歌有点伤感",
            "我喜欢这首",
            "你觉得 Pretender 怎么样",
            "哈哈这首歌真有意思",
            "秋天适合听什么歌",
            "帮我搜一下藤井风最新消息",
        )) {
            assertEquals("「$t」不是指令,应判为 NONE", Intent.NONE, cls(t))
        }
    }

    // ── ③ 闸门:拦下 / 放行 / 已在播放 ─────────────────────────────────

    @Test
    fun `感想句一律拦下 - 不执行播放技能`() {
        for (t in listOf("秋天就喜欢听些苦情歌。", "这首歌很适合秋天", "我喜欢这首")) {
            assertEquals("「$t」必须被拦", Decision.BlockNoIntent,
                PlaybackIntentGuard.guard(t, requestedTarget = "Pretender", currentTitle = "Pretender", isPlaying = true))
        }
    }

    @Test
    fun `上一轮放过 Pretender - 这一轮感想句仍然被拦下 不重复播放`() {
        // 用户定调:上一轮 Skill=PLAY 不能自动成为这一轮的 PLAY;每轮独立判断。
        // (闸门看不到历史,所以"没继承"这件事在结构上就是必然的 —— 这正是要钉的点。)
        val prevTurnUserSaid = "放一下 Pretender"
        assertEquals(Decision.Allow, PlaybackIntentGuard.guard(prevTurnUserSaid, "Pretender", null, false))

        val nextTurn = "秋天就喜欢听些苦情歌。"
        assertEquals("不能因为上一轮放过就放行", Decision.BlockNoIntent,
            PlaybackIntentGuard.guard(nextTurn, "Pretender", "Pretender", true))
    }

    @Test
    fun `上一轮放过 Pretender - 这一轮说 再放一遍 允许重播`() {
        assertEquals(Decision.Allow, PlaybackIntentGuard.guard("再放一遍", "Pretender", "Pretender", true))
        assertEquals(Decision.Allow, PlaybackIntentGuard.guard("重新播放", "Pretender", "Pretender", true))
    }

    @Test
    fun `已经在放的同一首 - 不再重新开始`() {
        assertEquals(Decision.AlreadyPlaying,
            PlaybackIntentGuard.guard("播放 Pretender", "Pretender", "Pretender", true))
        // 曲名带副标题也要认得出是同一首
        assertEquals(Decision.AlreadyPlaying,
            PlaybackIntentGuard.guard("播放 Pretender", "Pretender", "Pretender (Live)", true))
        assertEquals(Decision.AlreadyPlaying,
            PlaybackIntentGuard.guard("放一下 pretender", "pretender", "Pretender", true))
    }

    @Test
    fun `已经在放 Pretender - 但要求从头再放 允许 restart`() {
        assertEquals(Decision.Allow, PlaybackIntentGuard.guard("从头再放一遍", "Pretender", "Pretender", true))
    }

    @Test
    fun `点名的是另一首 - 正常放行`() {
        assertEquals(Decision.Allow,
            PlaybackIntentGuard.guard("播放 Pretender", "Pretender", "残酷な天使のテーゼ", true))
    }

    @Test
    fun `暂停状态下的播放请求不应被判成已在播放`() {
        // 播放器没在放 → 用户说"播放 X"是有效指令(要走 resume/play),不能拦
        assertEquals(Decision.Allow,
            PlaybackIntentGuard.guard("播放 Pretender", "Pretender", "Pretender", isPlaying = false))
    }

    @Test
    fun `没有点名对象的继续放 - 放行`() {
        assertEquals(Decision.Allow, PlaybackIntentGuard.guard("继续放", null, "Pretender", false))
    }

    @Test
    fun `歌名归一比对 - 全半角与繁简都算同一首`() {
        assertTrue(PlaybackIntentGuard.sameTrack("Pretender", "pretender"))
        assertTrue(PlaybackIntentGuard.sameTrack("晴天", "晴天 (Live)"))
        assertTrue(!PlaybackIntentGuard.sameTrack("晴天", "雨天"))
    }
}
