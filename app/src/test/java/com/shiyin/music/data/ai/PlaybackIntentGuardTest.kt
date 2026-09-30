package com.shiyin.music.data.ai

import com.shiyin.music.data.ai.PlaybackIntentGuard.Intent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.12:播放执行意图判定的回归测试。
 *
 * 这一层**只做文本判定**,不做执行决策、不生成面向用户的文案。它的两个真机 bug 教训:
 *  1. 上一轮刚放过某首歌 → 这一轮「秋天就喜欢听些苦情歌。」被读成"再放一遍"(感想≠命令);
 *  2. 用户**更正上一条指令**(「我说的是单曲」)被当成闲聊拦下 —— 用户看到的是"说了半天
 *     它没听见 / 无动于衷"。所以更正句必须算指令。
 */
class PlaybackIntentGuardTest {

    private fun cls(t: String) = PlaybackIntentGuard.classify(t)

    // ── ① 明确命令 ─────────────────────────────────────────────────────────

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

    // ── ② 更正上一条指令:必须算指令(真机 bug 的核心) ──────────────────

    @Test
    fun `更正上一轮播放指令的说法算 PLAY - 不能当闲聊`() {
        // 真机实录:第一条「给我播放藤井风的 prema」被播成了整张专辑,用户接着说
        // 「我说的是单曲」——这句话是**改命令**,不是感慨;当成闲聊拦下就是"无动于衷"。
        for (t in listOf(
            "我说的是单曲",
            "我要的是单曲",
            "应该是另一首",
            "我都说的很清楚了,是单曲prema,不是专辑!",
            "不是这张",
            "不是这个",
            "要的是这一首",
            "搞错了,是另一首",
            "听错了,我要的是单曲",
            "换这首",
        )) {
            assertEquals("「$t」是在更正指令,应判为 PLAY", Intent.PLAY, cls(t))
        }
    }

    // ── ③ 感想 / 闲聊:必须判 NONE ────────────────────────────────────────

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

    @Test
    fun `每轮独立判断 - 上一轮说过什么不影响这一轮`() {
        // 判定只看当前这句(闸门看不到历史,所以"不继承"在结构上就是必然的)
        assertEquals(Intent.PLAY, cls("放一下 Pretender"))
        assertEquals("上一轮放过,不代表这一轮的感想是指令", Intent.NONE, cls("秋天就喜欢听些苦情歌。"))
    }

    // ── ④ 歌名比对(供"名字既是单曲又是专辑"的歧义判定用) ──────────────

    @Test
    fun `歌名归一比对 - 全半角与繁简都算同一首`() {
        assertTrue(PlaybackIntentGuard.sameTrack("Pretender", "pretender"))
        assertTrue(PlaybackIntentGuard.sameTrack("晴天", "晴天 (Live)"))
        assertTrue(!PlaybackIntentGuard.sameTrack("晴天", "雨天"))
    }

    @Test
    fun `短歌名不会被误判成同一首`() {
        // 一字/两字歌名在中日曲库里很常见,包含判定必须带长度下限
        assertTrue("「夜」不是《夜に駆ける》", !PlaybackIntentGuard.sameTrack("夜", "夜に駆ける"))
        assertTrue("「恋」不是《恋愛写真》", !PlaybackIntentGuard.sameTrack("恋", "恋愛写真"))
        assertTrue("「花」不是《花束》", !PlaybackIntentGuard.sameTrack("花", "花束"))
        // 长歌名的包含仍然成立(带副标题/后缀的同一首)
        assertTrue(PlaybackIntentGuard.sameTrack("Pretender", "Pretender - Single Version"))
    }

    @Test
    fun `同名专辑与同名单曲能被认出是同名 - 歧义判定要靠它`() {
        // VM 用它判断"这个名字既是单曲又是专辑"→ 弹 clarify 问用户
        assertTrue(PlaybackIntentGuard.sameTrack("prema", "prema"))
        assertTrue(PlaybackIntentGuard.sameTrack("Prema", "prema"))
    }

    // ── ⑤ 从用户原话推断"单曲还是整张"(说了就别再问) ────────────────────

    @Test
    fun `用户明说单曲时定死 track - 不再弹选择卡片`() {
        for (t in listOf("我说的是单曲", "我要的是单曲", "放这首单曲", "我想听这首歌", "播放这一首")) {
            assertEquals("「$t」应定死 track", PlaybackIntentGuard.SCOPE_TRACK, PlaybackIntentGuard.userScope(t))
        }
    }

    @Test
    fun `用户明说专辑时定死 album`() {
        for (t in listOf("放整张专辑 Prema", "整张放一下", "全张都放", "我要整碟")) {
            assertEquals("「$t」应定死 album", PlaybackIntentGuard.SCOPE_ALBUM, PlaybackIntentGuard.userScope(t))
        }
    }

    @Test
    fun `用户没说单曲也没说专辑时不表态 - 有歧义才问`() {
        for (t in listOf("给我播放藤井风的 prema", "放一下 Pretender", "来一首 Pretender")) {
            assertEquals("「$t」用户没表态", null, PlaybackIntentGuard.userScope(t))
        }
    }
}
