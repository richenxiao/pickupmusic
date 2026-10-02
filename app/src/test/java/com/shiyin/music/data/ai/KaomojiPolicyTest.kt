package com.shiyin.music.data.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 颜文字策略(v1.3.13)单测。
 *
 * 两个方向的风险不对称,测试也就分两组:
 *  · **误删是事故**(改的是用户能看到的正文)→ 普通括注必须一字不动;
 *  · 误漏只是观感小瑕 → 清单外颜文字尽量删,删不掉不致命。
 */
class KaomojiPolicyTest {

    // ── 清单内:保留原样 ─────────────────────────────────────────────────────

    @Test
    fun `清单内的颜文字原样保留`() {
        assertEquals("今天有点累。(´ ▽ `)", KaomojiPolicy.sanitize("今天有点累。(´ ▽ `)"))
        assertEquals("我记住了。(｡・ω・｡)", KaomojiPolicy.sanitize("我记住了。(｡・ω・｡)"))
    }

    @Test
    fun `省略空格或写成全角括号的清单内颜文字同样保留`() {
        // 模型时常把 "(´ ▽ `)" 写成 "(´▽`)" 或 "（´ ▽ `）"——归一化后应视为同一条
        assertEquals("嗯。(´▽`)", KaomojiPolicy.sanitize("嗯。(´▽`)"))
        assertEquals("嗯。（´ ▽ `）", KaomojiPolicy.sanitize("嗯。（´ ▽ `）"))
    }

    @Test
    fun `行尾颜文字后的空白与标点残迹被清掉`() {
        assertEquals("知道了！", KaomojiPolicy.sanitize("知道了(＾▽＾)！"))
        assertEquals("好的。", KaomojiPolicy.sanitize("好的 (◕‿◕) 。"))
    }

    // ── 清单外:删掉 ────────────────────────────────────────────────────────

    @Test
    fun `清单外的自创颜文字被删除`() {
        assertEquals("好的。", KaomojiPolicy.sanitize("好的。(＾▽＾)"))
        assertEquals("这次没做好。", KaomojiPolicy.sanitize("这次没做好。٩(◕‿◕)۶"))
        assertEquals("收到了。", KaomojiPolicy.sanitize("收到了。（*^▽^*）"))
    }

    @Test
    fun `括号外的手臂跟着一起删,不会留下半截`() {
        // ٩ ۶ 在括号外:只删括号体的话会剩下"٩۶"这种莫名其妙的残迹
        assertEquals("好。", KaomojiPolicy.sanitize("好。٩(◕‿◕)۶"))
        // 手臂可能连着多个(و✧):量词写成单个会剩下"✧"
        assertEquals("好的。", KaomojiPolicy.sanitize("好的。(＾▽＾)و✧"))
    }

    @Test
    fun `人设里那个带手臂的颜文字被完整保留`() {
        assertEquals("这次我可是检查过了。(๑•̀ㅂ•́)و✧", KaomojiPolicy.sanitize("这次我可是检查过了。(๑•̀ㅂ•́)و✧"))
    }

    @Test
    fun `清单内的颜文字带手臂时不算越界`() {
        // 判定只看括号体——手臂不参与清单归属,否则清单内的反而被误删
        assertEquals("好。(´ ▽ `)۶", KaomojiPolicy.sanitize("好。(´ ▽ `)۶"))
    }

    @Test
    fun `句中颜文字被删后句子仍然完整可读`() {
        assertEquals("我有点不好意思，下次注意。", KaomojiPolicy.sanitize("我有点不好意思（｡>﹏<｡），下次注意。"))
    }

    // ── 误删防线(最关键) ───────────────────────────────────────────────────

    @Test
    fun `普通中文与英文括注一字不动`() {
        assertEquals("（见上文）", KaomojiPolicy.sanitize("（见上文）"))
        assertEquals("（Windows）", KaomojiPolicy.sanitize("（Windows）"))
        assertEquals("（T 恤）", KaomojiPolicy.sanitize("（T 恤）"))
        assertEquals("（2020）", KaomojiPolicy.sanitize("（2020）"))
        assertEquals("（笑）", KaomojiPolicy.sanitize("（笑）"))
        assertEquals("《夜に駆ける》（YOASOBI）", KaomojiPolicy.sanitize("《夜に駆ける》（YOASOBI）"))
    }

    @Test
    fun `含日文长音符或间隔号的普通括注不得被删`() {
        // v1.4.0 修「误删是事故」的实例:・(U+30FB) 与 ー(U+30FC) 是**普通日文标点**,
        // 不是颜文字专有字符。它们一旦算作特征,下面这些正常括注会被整段删掉。
        assertEquals("（ヨルシカ・ナブナ）", KaomojiPolicy.sanitize("（ヨルシカ・ナブナ）"))
        assertEquals("（ノーカット）", KaomojiPolicy.sanitize("（ノーカット）"))
        assertEquals("（Live）", KaomojiPolicy.sanitize("（Live）"))
        assertEquals("（feat. X）", KaomojiPolicy.sanitize("（feat. X）"))
        assertEquals("（マイケル・ジャクソン）", KaomojiPolicy.sanitize("（マイケル・ジャクソン）"))
        assertEquals("（リミックス・バージョン）", KaomojiPolicy.sanitize("（リミックス・バージョン）"))
        // 句子层面:括注被保住,句子仍完整
        assertEquals(
            "这张专辑是ヨルシカ・ナブナ做的(2020 年版)。",
            KaomojiPolicy.sanitize("这张专辑是ヨルシカ・ナブナ做的(2020 年版)。"),
        )
    }

    @Test
    fun `移除普通标点后真颜文字仍能识别 - 它们都带其它专有字符`() {
        // 这三个在既有用例里已确认属于清单内(含 ω / ▽ 等专有字符)——把 ・/ー 移出特征集
        // 不影响它们的识别与保留。
        assertEquals("今天有点累。(´ ▽ `)", KaomojiPolicy.sanitize("今天有点累。(´ ▽ `)"))
        assertEquals("我记住了。(｡・ω・｡)", KaomojiPolicy.sanitize("我记住了。(｡・ω・｡)"))
        assertEquals("嗯。(´▽`)", KaomojiPolicy.sanitize("嗯。(´▽`)"))
    }

    @Test
    fun `纯间隔号构成的颜文字不再被识别 - 这是刻意的误漏`() {
        // 明知取舍:・/ー 移出特征集后,`(・_・)` 这类只由普通标点+下划线构成的颜文字
        // 会残留不删。按文件的代价原则(误漏是观感小瑕、误删是事故),这里选择保留。
        assertEquals("(・_・)", KaomojiPolicy.sanitize("(・_・)"))
    }

    @Test
    fun `完全不含颜文字专有字符的文本走快速路径且内容不变`() {
        val t = "这首歌的编曲我很喜欢,尤其是第二段副歌(大概 2 分 40 秒那里)。"
        assertEquals(t, KaomojiPolicy.sanitize(t))
    }

    @Test
    fun `空文本与空白文本不抛异常`() {
        assertEquals("", KaomojiPolicy.sanitize(""))
        assertEquals("   ", KaomojiPolicy.sanitize("   "))
    }

    // ── 与人设的一致性 ──────────────────────────────────────────────────────

    @Test
    fun `人设里举例的颜文字必须全部在清单内`() {
        // 否则纪律自相矛盾:人设让她用某个颜文字,而 sanitize 会当场把它删掉。
        // 这条就是为发现这类矛盾而存在(初次实现时 (╥╥) 正是这种情况)。
        assertEquals(
            "persona 里出现清单外的颜文字——要么加进清单,要么把它从人设里删掉",
            AgentEngine.persona,
            KaomojiPolicy.sanitize(AgentEngine.persona),
        )
    }

    @Test
    fun `纪律段包含清单且明确禁止自创`() {
        val d = KaomojiPolicy.directive
        assertTrue("要给出允许清单", d.contains("(｡・ω・｡)"))
        assertTrue("必须说明自创会被删除", d.contains("不要自创"))
        assertTrue("必须限定场合", d.contains("一律不用"))
        assertFalse("不该把清单外的示例写进去", d.contains("(＾▽＾)"))
    }
}
