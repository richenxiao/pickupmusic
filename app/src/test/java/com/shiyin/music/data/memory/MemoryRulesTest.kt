package com.shiyin.music.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.10 Memory P1:数据质量规则的纯逻辑单测。
 *
 * 这一层是 Memory 的"守门人"——模型只提名,能不能入库、入库后跟既有条目什么关系,
 * 全由这些规则裁决。它们错了会静默污染长期记忆(表现出来只是"她记错了/记不住"),
 * 所以逐条钉死。
 */
class MemoryRulesTest {

    // ── 1. 类型白名单 ──────────────────────────────────────────────────────

    @Test
    fun `type 白名单只收五类`() {
        for (t in listOf("preference", "personal", "episodic", "music", "relationship")) {
            assertTrue("应接受 $t", MemoryRules.isValidType(t))
        }
        for (t in listOf("", "banana", "PREFERENCE", "session_state", "other")) {
            assertFalse("应拒绝 $t", MemoryRules.isValidType(t))
        }
    }

    // ── 2. 长度限制 ────────────────────────────────────────────────────────

    @Test
    fun `超长内容被截断到上限`() {
        val long = "用户喜欢" + "很长的偏好描述".repeat(60)
        val out = MemoryRules.normalizeAndLimit(long)
        assertEquals("应截断到上限", MemoryRules.MAX_CONTENT_LEN, out.length)
    }

    @Test
    fun `归一化折叠空白但不动内容`() {
        assertEquals("用户喜欢 aiko", MemoryRules.normalize("  用户喜欢   aiko  "))
        assertEquals("用户喜欢 aiko", MemoryRules.normalize("用户喜欢　aiko"))
    }

    @Test
    fun `纯标点不算可用内容`() {
        assertFalse(MemoryRules.isUsableContent(" 。、！"))
        assertTrue(MemoryRules.isUsableContent("用户喜欢 aiko"))
    }

    // ── 3. 一次性情绪拒表 ──────────────────────────────────────────────────

    @Test
    fun `一次性情绪被识别 - 今天有点累 今天有点烦`() {
        assertTrue(MemoryRules.isTransient("今天有点累"))
        assertTrue(MemoryRules.isTransient("今天有点烦"))
        assertTrue(MemoryRules.isTransient("好累啊"))          // 短句 + 状态词
        assertTrue(MemoryRules.isTransient("刚刚心情不好"))
    }

    @Test
    fun `长期偏好不会被误判成一次性情绪`() {
        assertFalse(MemoryRules.isTransient("用户喜欢完整听完一张专辑"))
        assertFalse(MemoryRules.isTransient("用户最近一直在听 aiko"))   // 有"最近"但有长期标志
        assertFalse(MemoryRules.isTransient("用户偏好深夜听氛围音乐"))
        assertFalse(MemoryRules.isTransient("用户不喜欢随机推荐"))
    }

    // ── 4. 明确要求记住 ────────────────────────────────────────────────────

    @Test
    fun `明确要求记住的几种说法都能识别`() {
        assertTrue(MemoryRules.isExplicitRemember("记住，我喜欢 aiko"))
        assertTrue(MemoryRules.isExplicitRemember("这件事别忘了"))
        assertTrue(MemoryRules.isExplicitRemember("帮我记一下，我不吃香菜"))
        assertFalse(MemoryRules.isExplicitRemember("今天有点累"))
        assertFalse(MemoryRules.isExplicitRemember("我喜欢 aiko"))
    }

    // ── 5. 主题词 / 相似度 / 冲突判定 ──────────────────────────────────────

    @Test
    fun `主题词抽取会剥掉功能词`() {
        assertEquals(setOf("aiko"), MemoryRules.tokens("用户喜欢 aiko"))
        assertEquals(setOf("aiko"), MemoryRules.tokens("用户很喜欢 aiko"))
        assertEquals(setOf("完整听专辑"), MemoryRules.tokens("用户喜欢完整听专辑"))
        assertEquals(setOf("aiko", "vaundy"), MemoryRules.tokens("用户不再喜欢 aiko,更喜欢 Vaundy"))
    }

    @Test
    fun `换一种说法判为重复`() {
        assertTrue(MemoryRules.isDuplicate("用户喜欢 aiko", "用户很喜欢 aiko"))
        assertTrue(MemoryRules.isDuplicate("用户喜欢 aiko", "  用户喜欢   aiko "))
        assertFalse(MemoryRules.isDuplicate("用户喜欢 aiko", "用户喜欢完整听完一张专辑"))
    }

    @Test
    fun `同主题不同说法判为修订而不是重复`() {
        assertTrue(MemoryRules.isSameTopicRevision("用户喜欢 aiko", "用户不再喜欢 aiko,更喜欢 Vaundy"))
        // 不同主题:不冲突,两条并存
        assertFalse(MemoryRules.isSameTopicRevision("用户喜欢 aiko", "用户喜欢完整听完一张专辑"))
        // 已经算重复的,不再算冲突(避免同时走两条分支)
        assertFalse(MemoryRules.isSameTopicRevision("用户喜欢 aiko", "用户很喜欢 aiko"))
    }

    // ── 6. 检索:门槛 / top-k / 空结果 ─────────────────────────────────────

    @Test
    fun `相关度不足的记忆被门槛挡掉`() {
        val m = memory("1", "用户喜欢 aiko")
        assertEquals(1f, MemoryRules.relevance("aiko", m), 0.001f)
        assertEquals(0f, MemoryRules.relevance("量子力学", m), 0.001f)
        assertTrue(MemoryRules.rankAndFilter("aiko", listOf(m)).isNotEmpty())
    }

    @Test
    fun `检索只返回相关命中的前三条`() {
        val all = listOf(
            memory("1", "用户偏好完整听完一张专辑"),
            memory("2", "用户常把专辑按发行年份排序"),
            memory("3", "用户不喜欢把专辑拆成单曲"),
            memory("4", "用户喜欢深夜听氛围音乐"),
            memory("5", "用户在通勤时听播客"),
        )
        val got = MemoryRules.rankAndFilter("专辑", all)
        assertEquals("应只取 top-3", 3, got.size)
        assertTrue("不该混入无关记忆", got.none { it.id == "4" || it.id == "5" })
        assertTrue("返回的每条都应过门槛", got.all { MemoryRules.relevance("专辑", it) >= MemoryRules.RELEVANCE_THRESHOLD })
    }

    @Test
    fun `没有相关记忆时返回空 - 不制造默认记忆`() {
        val all = listOf(memory("1", "用户喜欢 aiko"), memory("2", "用户喜欢完整听完一张专辑"))
        assertTrue(MemoryRules.rankAndFilter("量子力学", all).isEmpty())
        assertTrue(MemoryRules.rankAndFilter("", all).isEmpty())
    }

    @Test
    fun `已被取代的记忆不参与检索`() {
        val dead = memory("1", "用户喜欢 aiko").copy(supersededBy = "2")
        val alive = memory("2", "用户不再喜欢 aiko,更喜欢 Vaundy")
        val got = MemoryRules.rankAndFilter("aiko", listOf(dead, alive))
        assertEquals("只应返回未被取代的那条", listOf("2"), got.map { it.id })
    }

    // ── 7. FTS 文本(CJK 二元切分) ─────────────────────────────────────────

    @Test
    fun `FTS 文本对中文做二元切分 - 长句里能搜到关键词`() {
        // FTS4 默认分词不切中文:不展开的话"完整听完一张专辑"里搜"专辑"永远搜不到
        val expanded = MemoryRules.ftsText("完整听完一张专辑").split(' ')
        assertEquals("整段保留,支持整句查询", "完整听完一张专辑", expanded.first())
        assertTrue("二元组里必须有 专辑,否则搜不到", expanded.contains("专辑"))
        assertEquals("专辑", MemoryRules.ftsText("专辑"))
    }

    @Test
    fun `FTS 文本保留英文词并小写`() {
        assertEquals("aiko", MemoryRules.ftsText("aiko"))
        assertEquals("aiko vaundy", MemoryRules.ftsText("Aiko Vaundy"))
    }

    @Test
    fun `FTS 查询表达式加引号防特殊字符`() {
        assertEquals("\"专辑\"", MemoryRules.ftsMatchQuery("专辑"))
        assertEquals("\"aiko\"", MemoryRules.ftsMatchQuery("aiko"))
        val multi = MemoryRules.ftsMatchQuery("aiko 专辑")
        assertTrue(multi.contains("\"aiko\""))
        assertTrue(multi.contains("\"专辑\""))
        assertTrue(multi.contains(" OR "))
        assertEquals("", MemoryRules.ftsMatchQuery("。、"))
    }

    // ── 8. v1.3.11 确定性「记住」解析(不依赖 LLM) ──────────────────────────
    // 用户明确说"记住"时不能再等回合后的 LLM 提名——那会出现"说记住了但库里没有"。

    @Test
    fun `明确记住时能确定性抽出候选 - 名字`() {
        val c = MemoryRules.explicitCandidate("记住,我的名字叫 Richen")
        assertNotNull("应抽出候选", c)
        assertEquals(MemoryType.PERSONAL, c!!.type)
        assertEquals("用户叫 Richen", c.content)
    }

    @Test
    fun `明确记住时能确定性抽出候选 - 我叫X`() {
        assertEquals("用户叫 Richen", MemoryRules.explicitCandidate("记住我叫 Richen")!!.content)
        assertEquals("用户叫 Richen", MemoryRules.explicitCandidate("请记住,我叫 Richen")!!.content)
    }

    @Test
    fun `明确记住时能确定性抽出候选 - 偏好`() {
        val p = MemoryRules.explicitCandidate("帮我记一下,我喜欢 aiko")!!
        assertEquals(MemoryType.PREFERENCE, p.type)
        assertEquals("用户喜欢 aiko", p.content)
    }

    @Test
    fun `明确记住时能确定性抽出候选 - 音乐偏好`() {
        val m = MemoryRules.explicitCandidate("记下来,我最喜欢的乐队是 RADWIMPS")!!
        assertEquals(MemoryType.MUSIC, m.type)
    }

    @Test
    fun `没有记住指令时不产出候选`() {
        assertNull("普通陈述不该被确定性路径收走", MemoryRules.explicitCandidate("我喜欢 aiko"))
        assertNull(MemoryRules.explicitCandidate("今天有点累"))
    }

    @Test
    fun `抽不出可存内容时不产出候选`() {
        assertNull("只有触发词", MemoryRules.explicitCandidate("记住"))
        assertNull("只有标点", MemoryRules.explicitCandidate("记住。。。"))
        assertNull("只有触发词+空白", MemoryRules.explicitCandidate("记住 "))
    }

    @Test
    fun `疑问句不能被当成名字存下来`() {
        // 「记住我叫什么」是在问,不是给资料——若把「什么」存成名字就是错记忆
        assertNull(MemoryRules.explicitCandidate("记住我叫什么"))
        assertNull(MemoryRules.explicitCandidate("记住我的名字是什么"))
    }

    // ── 9. v1.3.11 身份/自我指代检索 ───────────────────────────────────────
    // 已确证的 bug:中文问句「我叫什么」的 tokens 与「用户叫 Richen」的 tokens
    // ({richen}) 双向都不包含 → relevance=0 → 被 0.34 门槛挡掉,表现为"她明明记得却答不知道"。

    @Test
    fun `身份问句能被识别 - 中文`() {
        for (q in listOf("我叫什么?", "你知道我的名字吗", "你还记得我是谁吗", "我的名字是什么", "我叫啥", "你记得我叫什么")) {
            assertTrue("应识别为身份问句:$q", MemoryRules.isIdentityQuery(q))
        }
    }

    @Test
    fun `身份问句能被识别 - 英文`() {
        for (q in listOf("what is my name", "Do you know my name?", "who am i", "remember me?")) {
            assertTrue("应识别为身份问句:$q", MemoryRules.isIdentityQuery(q))
        }
    }

    @Test
    fun `普通问句不会被误判成身份问句`() {
        for (q in listOf("播放我最近听的歌", "这张专辑怎么样", "推荐一首歌", "我最喜欢什么颜色", "量子力学是什么")) {
            assertFalse("普通问句不该判成身份问句:$q", MemoryRules.isIdentityQuery(q))
        }
    }

    @Test
    fun `复现旧 bug - 中文问句对 Latin 名字的相关度确实是 0`() {
        assertEquals("这条断言是 bug 复现的前提,它成立才说明门槛真的会挡掉", 0f,
            MemoryRules.relevance("我叫什么", identityMemory()), 0.001f)
    }

    @Test
    fun `身份问句能召回 Latin 值的名字记忆`() {
        val got = MemoryRules.rankAndFilter("我叫什么?", listOf(identityMemory()))
        assertEquals("身份问句必须能召回名字", listOf("1"), got.map { it.id })
    }

    @Test
    fun `英文身份问句也能召回`() {
        assertEquals(listOf("1"),
            MemoryRules.rankAndFilter("what is my name?", listOf(identityMemory())).map { it.id })
    }

    @Test
    fun `身份问句只召回身份类记忆 - 不是把所有重要记忆倒进去`() {
        val got = MemoryRules.rankAndFilter("我叫什么", listOf(identityMemory(), bigPreference()))
        assertEquals("只应召回名字那条", listOf("1"), got.map { it.id })
    }

    @Test
    fun `身份问句没有身份记忆时退回普通检索 - 不乱给`() {
        assertTrue("没有身份记忆时应返回空,不硬塞",
            MemoryRules.rankAndFilter("我叫什么", listOf(bigPreference())).isEmpty())
    }

    @Test
    fun `普通查询不受身份旁路影响 - 门槛仍然生效`() {
        assertTrue("普通无关查询不能因为身份旁路而召回名字",
            MemoryRules.rankAndFilter("量子力学", listOf(identityMemory())).isEmpty())
    }

    @Test
    fun `被取代的身份记忆不参与身份召回`() {
        val dead = identityMemory().copy(supersededBy = "9")
        assertTrue(MemoryRules.rankAndFilter("我叫什么", listOf(dead)).isEmpty())
    }

    // ── 10. v1.3.11 第二阶段:要不要为这条消息显示记忆状态 ──────────────────
    // 记忆 UI 不能对每句话都显示——"今天天气不错"后面挂一句「未找到相关记忆」会把界面变吵。
    // 判据必须收得足够紧:只有确实涉及"用户自己/他的长期信息"的消息才值得显示。

    @Test
    fun `涉及用户自己的消息才值得显示记忆状态`() {
        for (t in listOf(
            "我叫什么?",                      // 身份问句
            "我的名字是什么",
            "你还记得我是谁吗",
            "我叫御晨晓!",                     // 自我介绍
            "我最近特别喜欢藤井风",              // 长期偏好
            "请记住我最喜欢胡子男的一首歌是 Pretender",  // 显式指令
            "我最喜欢的颜色是什么",              // 问自己的偏好
        )) {
            assertTrue("应显示记忆状态:$t", MemoryRules.memoryWarrantsTrace(t))
        }
    }

    @Test
    fun `普通聊天不该显示记忆状态`() {
        for (t in listOf(
            "今天天气不错。",
            "这张专辑怎么样",
            "推荐一首歌",
            "量子力学是什么",
            "帮我排一下这张专辑的曲序",
        )) {
            assertFalse("普通消息不该显示记忆状态:$t", MemoryRules.memoryWarrantsTrace(t))
        }
    }

    @Test
    fun `值得在回复前提取保存的消息`() {
        for (t in listOf("我叫御晨晓!", "我最近特别喜欢藤井风", "请记住我叫御晨晓", "我平时只听黑胶")) {
            assertTrue("应在回复前提取:$t", MemoryRules.shouldExtractSave(t))
        }
    }

    @Test
    fun `纯指令与闲话不做回复前提取 - 不白花一次 LLM 调用`() {
        for (t in listOf("今天天气不错。", "播放我最近听的歌", "下一首", "暂停", "这张专辑怎么样")) {
            assertFalse("不该在回复前提取:$t", MemoryRules.shouldExtractSave(t))
        }
    }

    @Test
    fun `回复前提取的范围比显示判据更窄 - 提问不等于给资料`() {
        // 「我叫什么」要显示状态(值得查),但它不是在给资料 → 不该触发提取保存
        assertTrue(MemoryRules.memoryWarrantsTrace("我叫什么?"))
        assertFalse(MemoryRules.shouldExtractSave("我叫什么?"))
    }

    // ── 11. v1.3.13:「指令流水」不是偏好 ──────────────────────────────────
    // 真机反馈:记忆里出现《用户喜欢 X,曾要求播放》。用户让播放某首歌只说明他此刻想听,
    // 不代表他喜欢它——这种条目会把拾光对用户的印象记歪。

    @Test
    fun `只在记录播放指令的条目被识别出来`() {
        for (t in listOf(
            "用户喜欢 X,曾要求播放",
            "用户要求播放《Pretender》",
            "用户让播放过《Pretender》",
            "用户点播了《Pretender》",
            "用户要求听这首",
            "用户播放了《Pretender》",
        )) {
            assertTrue("「$t」只是指令流水", MemoryRules.isCommandRecord(t))
        }
    }

    @Test
    fun `真正的偏好不会被当成指令流水`() {
        for (t in listOf(
            "用户喜欢 aiko",
            "用户最喜欢胡子男的《Pretender》",
            "用户偏好完整听完一张专辑",
            "用户最近常听 City Pop",
        )) {
            assertFalse("「$t」是真实偏好,不该被拒", MemoryRules.isCommandRecord(t))
        }
    }

    private fun identityMemory() = Memory(
        id = "1", type = MemoryType.PERSONAL, content = "用户叫 Richen",
        createdAt = 0, updatedAt = 0, importance = 3, source = MemorySource.EXPLICIT,
        tags = MemoryRules.tagsOf("用户叫 Richen"),
    )

    /** 一条高重要度但**与身份无关**的记忆——用来证明身份旁路不搞全量注入。 */
    private fun bigPreference() = Memory(
        id = "2", type = MemoryType.PREFERENCE, content = "用户偏好完整听完一张专辑",
        createdAt = 0, updatedAt = 0, importance = 3, source = MemorySource.EXPLICIT, tags = emptyList(),
    )

    private fun memory(id: String, content: String) = Memory(
        id = id, type = MemoryType.MUSIC, content = content,
        createdAt = 0, updatedAt = 0, importance = 2, source = MemorySource.INFERRED,
        tags = MemoryRules.tagsOf(content),
    )
}
