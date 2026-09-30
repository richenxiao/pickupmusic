package com.shiyin.music.data.memory

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.10 Memory P1:MemoryManager 的写入裁决与检索单测(内存 store + 注入时钟)。
 *
 * 覆盖用户点名的 10 条:重复不新增 / 相似 update / 冲突 superseded / 一次性情绪默认拒 /
 * 明确"记住"可写入 / 超长截断 / 非法 type 拒 / 检索门槛与 top-3 / 无相关返回空 /
 * 时间与 importance 与 supersededBy 正确。
 */
class MemoryManagerTest {

    /** 内存实现:只为测试规则编排,不模拟 Room。 */
    private class InMemoryStore : MemoryStore {
        val rows = LinkedHashMap<String, Memory>()
        var inserts = 0
        override suspend fun all(): List<Memory> = rows.values.toList()
        override suspend fun byId(id: String): Memory? = rows[id]
        override suspend fun insert(m: Memory) { rows[m.id] = m; inserts++ }
        override suspend fun update(m: Memory) { rows[m.id] = m }
        override suspend fun delete(id: String) { rows.remove(id) }
        override suspend fun candidates(query: String): List<Memory> = rows.values.toList()
    }

    private var clock = 1_000L
    private var seq = 0
    private fun manager(store: InMemoryStore) =
        MemoryManager(store, now = { clock }, newId = { "m${++seq}" })

    private fun cand(type: String, content: String, importance: Int = 2, userSaid: String = "") =
        MemoryCandidate(type = type, content = content, importance = importance, userSaid = userSaid)

    // ── ① 同一偏好重复写入不新增 ② 相似内容 update ─────────────────────────

    @Test
    fun `同一偏好重复写入不会无限新增 - 第二次是 update`() = runBlocking {
        val s = InMemoryStore(); val m = manager(s)
        val first = m.remember(cand(MemoryType.MUSIC, "用户喜欢 aiko"))
        clock = 2_000
        val second = m.remember(cand(MemoryType.MUSIC, "用户很喜欢 aiko"))   // 换一种说法

        assertTrue(first is RememberOutcome.Saved)
        assertTrue("相似内容应走 update", second is RememberOutcome.Updated)
        assertEquals("只应有一条", 1, s.rows.size)
        assertEquals("只应 insert 一次", 1, s.inserts)
    }

    @Test
    fun `update 刷新 updatedAt 但保留 createdAt 与更高 importance`() = runBlocking {
        val s = InMemoryStore(); val m = manager(s)
        m.remember(cand(MemoryType.MUSIC, "用户喜欢 aiko", importance = 3))
        clock = 2_000
        m.remember(cand(MemoryType.MUSIC, "用户喜欢 aiko", importance = 1))   // 重复,importance 更低

        val row = s.rows.values.single()
        assertEquals("createdAt 不变", 1_000L, row.createdAt)
        assertEquals("updatedAt 刷新", 2_000L, row.updatedAt)
        assertEquals("取更高的 importance", 3, row.importance)
    }

    @Test
    fun `重复时用户明确说记住 - 来源升级为 explicit`() = runBlocking {
        val s = InMemoryStore(); val m = manager(s)
        m.remember(cand(MemoryType.MUSIC, "用户喜欢 aiko"))
        clock = 2_000
        m.remember(cand(MemoryType.MUSIC, "用户喜欢 aiko", importance = 1, userSaid = "记住，我喜欢 aiko"))

        val row = s.rows.values.single()
        assertEquals(MemorySource.EXPLICIT, row.source)
        assertEquals(3, row.importance)
    }

    // ── ③ 冲突 → superseded ───────────────────────────────────────────────

    @Test
    fun `新旧偏好冲突时旧记录被 superseded`() = runBlocking {
        val s = InMemoryStore(); val m = manager(s)
        m.remember(cand(MemoryType.MUSIC, "用户喜欢 aiko"))
        clock = 2_000
        val out = m.remember(cand(MemoryType.MUSIC, "用户不再喜欢 aiko,更喜欢 Vaundy"))

        assertTrue(out is RememberOutcome.Superseded)
        assertEquals("m1", (out as RememberOutcome.Superseded).oldId)
        assertEquals("m2", out.newId)
        assertEquals("两条都在(保留历史)", 2, s.rows.size)
        assertEquals("旧条目指向新条目", "m2", s.rows["m1"]!!.supersededBy)
        assertNull("新条目不应被标记取代", s.rows["m2"]!!.supersededBy)
    }

    @Test
    fun `被取代的旧记忆不再出现在检索与列表里`() = runBlocking {
        val s = InMemoryStore(); val m = manager(s)
        m.remember(cand(MemoryType.MUSIC, "用户喜欢 aiko"))
        clock = 2_000
        m.remember(cand(MemoryType.MUSIC, "用户不再喜欢 aiko,更喜欢 Vaundy"))

        assertEquals(listOf("m2"), m.recall("aiko").map { it.id })
        assertEquals(listOf("m2"), m.list().map { it.id })
    }

    // ── ④ 一次性情绪默认拒 ⑤ 明确"记住"可写入 ────────────────────────────

    @Test
    fun `一次性情绪默认不入库`() = runBlocking {
        val s = InMemoryStore(); val m = manager(s)
        for (text in listOf("今天有点累", "今天有点烦")) {
            val out = m.remember(cand(MemoryType.PERSONAL, text))
            assertTrue("「$text」应被拒", out is RememberOutcome.Rejected)
            assertEquals(RememberOutcome.Reason.TRANSIENT, (out as RememberOutcome.Rejected).reason)
        }
        assertTrue("库里应仍为空", s.rows.isEmpty())
    }

    @Test
    fun `用户明确说记住时可以写入 - 并绕过拒表与抬 importance`() = runBlocking {
        val s = InMemoryStore(); val m = manager(s)
        val out = m.remember(
            cand(MemoryType.PERSONAL, "今天有点累", importance = 1, userSaid = "记住，我今天有点累"),
        )
        assertTrue(out is RememberOutcome.Saved)
        val row = s.rows.values.single()
        assertEquals(3, row.importance)
        assertEquals(MemorySource.EXPLICIT, row.source)
    }

    // ── ⑥ 超长截断 ⑦ 非法 type 拒 (附:空内容拒) ─────────────────────────

    @Test
    fun `超长内容被限制在上限内`() = runBlocking {
        val s = InMemoryStore(); val m = manager(s)
        val out = m.remember(cand(MemoryType.PREFERENCE, "用户喜欢" + "很长的偏好描述".repeat(60)))
        assertTrue(out is RememberOutcome.Saved)
        assertTrue(s.rows.values.single().content.length <= MemoryRules.MAX_CONTENT_LEN)
    }

    @Test
    fun `非法 type 被拒绝且不落库`() = runBlocking {
        val s = InMemoryStore(); val m = manager(s)
        val out = m.remember(cand("banana", "用户喜欢 aiko"))
        assertTrue(out is RememberOutcome.Rejected)
        assertEquals(RememberOutcome.Reason.BAD_TYPE, (out as RememberOutcome.Rejected).reason)
        assertTrue(s.rows.isEmpty())
    }

    @Test
    fun `空内容或纯标点被拒绝`() = runBlocking {
        val s = InMemoryStore(); val m = manager(s)
        for (text in listOf("", "   ", "。、！")) {
            val out = m.remember(cand(MemoryType.MUSIC, text))
            assertEquals(RememberOutcome.Reason.BLANK, (out as RememberOutcome.Rejected).reason)
        }
        assertTrue(s.rows.isEmpty())
    }

    // ── ⑧ 检索门槛与 top-3 ⑨ 无相关返回空 ────────────────────────────────

    @Test
    fun `检索只返回满足门槛的前三条`() = runBlocking {
        val s = InMemoryStore(); val m = manager(s)
        for ((i, text) in listOf(
            "用户偏好完整听完一张专辑",
            "用户常把专辑按发行年份排序",
            "用户不喜欢把专辑拆成单曲",
            "用户喜欢深夜听氛围音乐",
            "用户在通勤时听播客",
        ).withIndex()) {
            clock = 1_000L + i
            m.remember(cand(MemoryType.PREFERENCE, text))
        }
        val got = m.recall("专辑")
        assertEquals("应只取 top-3", 3, got.size)
        assertTrue("无关记忆不得混入", got.none { it.content.contains("氛围音乐") || it.content.contains("播客") })
    }

    @Test
    fun `没有足够相关的记忆时返回空而不是默认记忆`() = runBlocking {
        val s = InMemoryStore(); val m = manager(s)
        m.remember(cand(MemoryType.MUSIC, "用户喜欢 aiko"))
        assertTrue("不相关查询应返回空", m.recall("量子力学").isEmpty())
        assertTrue("空查询应返回空", m.recall("").isEmpty())
    }

    // ── ⑩ 时间 / 标签 / 用户控制 ──────────────────────────────────────────

    @Test
    fun `入库时间与派生标签正确`() = runBlocking {
        val s = InMemoryStore(); val m = manager(s)
        clock = 12_345L
        m.remember(cand(MemoryType.MUSIC, "用户喜欢 aiko"))
        val row = s.rows.values.single()
        assertEquals(12_345L, row.createdAt)
        assertEquals(12_345L, row.updatedAt)
        assertEquals(listOf("aiko"), row.tags)
        assertNull("P1 不写入 embedding,列只预留", row.embedding)
    }

    @Test
    fun `用户可以改内容与忘记指定条目`() = runBlocking {
        val s = InMemoryStore(); val m = manager(s)
        val id = (m.remember(cand(MemoryType.MUSIC, "用户喜欢 aiko")) as RememberOutcome.Saved).id
        clock = 9_000
        assertTrue(m.update(id, "用户非常喜欢 aiko"))
        assertEquals(9_000L, s.rows[id]!!.updatedAt)
        assertEquals("用户非常喜欢 aiko", s.rows[id]!!.content)

        assertFalse("改不存在的条目应返回 false", m.update("不存在", "x"))
        assertTrue(m.forget(id))
        assertTrue(s.rows.isEmpty())
        assertFalse("重复忘记应返回 false", m.forget(id))
    }

    @Test
    fun `列表可按类型过滤`() = runBlocking {
        val s = InMemoryStore(); val m = manager(s)
        m.remember(cand(MemoryType.MUSIC, "用户喜欢 aiko"))
        clock = 2_000
        m.remember(cand(MemoryType.PREFERENCE, "用户偏好完整听完一张专辑"))
        assertEquals(1, m.list(type = MemoryType.MUSIC).size)
        assertEquals(2, m.list().size)
    }

    // ── ⑪ v1.3.10 P3:「拾光记忆」管理页**手加**的条目 ───────────────────────

    @Test
    fun `手加的记忆不被一次性情绪拒表挡住 - 来源记为 manual`() = runBlocking {
        val s = InMemoryStore(); val m = manager(s)
        // 同一句话由"对话提取"进来会被拒(见「一次性情绪默认不入库」);但用户在管理页
        // 亲手点「添加」= 明确指令,再拒就说不通了。
        val out = m.remember(
            MemoryCandidate(
                MemoryType.PERSONAL, "今天有点累",
                source = MemorySource.MANUAL, userSaid = "今天有点累",
            ),
        )
        assertTrue(out is RememberOutcome.Saved)
        val row = s.rows.values.single()
        assertEquals("来源是 manual,不冒充 explicit", MemorySource.MANUAL, row.source)
        assertEquals("手加视为明确指令 → 抬到最高重要度", 3, row.importance)
    }

    @Test
    fun `手加撞上既有推断条目时来源升级为 manual`() = runBlocking {
        val s = InMemoryStore(); val m = manager(s)
        m.remember(cand(MemoryType.MUSIC, "用户喜欢 aiko"))   // inferred
        clock = 2_000
        val out = m.remember(
            MemoryCandidate(
                MemoryType.MUSIC, "用户喜欢 aiko",
                source = MemorySource.MANUAL, userSaid = "用户喜欢 aiko",
            ),
        )
        assertTrue("重复应走 update", out is RememberOutcome.Updated)
        assertEquals(
            "用户亲手给的条目不该被降级成 inferred",
            MemorySource.MANUAL, s.rows.values.single().source,
        )
    }

    @Test
    fun `explicit 不会被 manual 降级`() = runBlocking {
        val s = InMemoryStore(); val m = manager(s)
        m.remember(cand(MemoryType.MUSIC, "用户喜欢 aiko", userSaid = "记住，我喜欢 aiko"))  // explicit
        clock = 2_000
        m.remember(
            MemoryCandidate(
                MemoryType.MUSIC, "用户喜欢 aiko",
                source = MemorySource.MANUAL, userSaid = "用户喜欢 aiko",
            ),
        )
        assertEquals(
            "两者都是用户给的,保留更强的 explicit",
            MemorySource.EXPLICIT, s.rows.values.single().source,
        )
    }

    // ── ⑬ v1.3.11 身份召回端到端(写入 → 新会话检索) ──────────────────────

    @Test
    fun `确定性写入名字后 - 问 我叫什么 必须召回`() = runBlocking {
        val s = InMemoryStore(); val m = manager(s)
        // 走确定性解析(不依赖 LLM)→ 同一路径落库
        val candidate = MemoryRules.explicitCandidate("记住,我的名字叫 Richen")!!
        val out = m.remember(candidate.copy(userSaid = "记住,我的名字叫 Richen"))
        assertTrue("必须真的落库", out is RememberOutcome.Saved)
        assertEquals(MemorySource.EXPLICIT, s.rows.values.single().source)

        val got = m.recall("我叫什么?")
        assertEquals("名字必须被召回", 1, got.size)
        assertTrue("召回的应是名字那条", got.single().content.contains("Richen"))
    }

    @Test
    fun `确定性写入名字后 - 英文身份问句也能召回`() = runBlocking {
        val s = InMemoryStore(); val m = manager(s)
        m.remember(MemoryRules.explicitCandidate("记住,我叫 Richen")!!)
        assertTrue(m.recall("Do you know my name?").any { it.content.contains("Richen") })
    }

    @Test
    fun `确定性写入偏好后 - 普通查询仍走原门槛`() = runBlocking {
        val s = InMemoryStore(); val m = manager(s)
        m.remember(MemoryRules.explicitCandidate("记住,我喜欢 aiko")!!)
        assertTrue("问名字时不该把偏好当身份召回", m.recall("我叫什么").none { it.content.contains("aiko") })
        assertTrue("问 aiko 时仍能正常召回", m.recall("aiko").any { it.content.contains("aiko") })
    }

    @Test
    fun `没有任何记忆时身份问句返回空 - 不编造`() = runBlocking {
        val s = InMemoryStore(); val m = manager(s)
        assertTrue(m.recall("我叫什么").isEmpty())
    }

    // ── ⑭ v1.3.12:设置项「连情绪与近况也记」───────────────────────────────

    @Test
    fun `默认仍然拒收一次性情绪 - 开关没开时行为不变`() = runBlocking {
        val s = InMemoryStore(); val m = manager(s)
        val out = m.remember(cand(MemoryType.PERSONAL, "今天有点累"))
        assertTrue(out is RememberOutcome.Rejected)
        assertTrue(s.rows.isEmpty())
    }

    @Test
    fun `开了连情绪也记 - 一次性情绪可入库`() = runBlocking {
        val s = InMemoryStore(); val m = manager(s)
        val out = m.remember(cand(MemoryType.PERSONAL, "今天有点累"), allowTransient = true)
        assertTrue("用户主动开了这个口子,就该真的记下来", out is RememberOutcome.Saved)
        assertEquals("今天有点累", s.rows.values.single().content)
    }

    @Test
    fun `开关不影响其他拒表 - 空内容与非法类型照旧拒`() = runBlocking {
        val s = InMemoryStore(); val m = manager(s)
        assertTrue(m.remember(cand(MemoryType.PERSONAL, "  "), allowTransient = true) is RememberOutcome.Rejected)
        assertTrue(m.remember(cand("banana", "今天有点累"), allowTransient = true) is RememberOutcome.Rejected)
        assertTrue(s.rows.isEmpty())
    }

    // ── ⑮ v1.3.13:「用户让播放过 X」不是偏好 ────────────────────────────────

    @Test
    fun `把点播当偏好的条目默认被拒`() = runBlocking {
        val s = InMemoryStore(); val m = manager(s)
        val out = m.remember(cand(MemoryType.MUSIC, "用户喜欢《Pretender》,曾要求播放"))
        assertTrue("这类条目会把对用户的印象记歪,必须拒", out is RememberOutcome.Rejected)
        assertEquals(
            RememberOutcome.Reason.COMMAND_RECORD,
            (out as RememberOutcome.Rejected).reason,
        )
        assertTrue(s.rows.isEmpty())
    }

    @Test
    fun `用户明说要记住时仍然放行 - 那是他亲自给的`() = runBlocking {
        val s = InMemoryStore(); val m = manager(s)
        val out = m.remember(
            cand(MemoryType.MUSIC, "用户要求播放《Pretender》", userSaid = "记住,我要求播放《Pretender》"),
        )
        assertTrue("用户明确要求记住 → 不该被这条规则挡", out is RememberOutcome.Saved)
    }

    // ── ⑯ v1.3.14:写入结果的日志标签必须稳定(不能用类名) ────────────────────
    // 真机实测:使用日志里出现 `memory C · preference` —— release 经 R8 把 Saved/Updated
    // 缩成了单字母,诊断信息自己先失真。这条测试钉住"标签是显式字符串、不是类名"。

    @Test
    fun `写入结果的日志标签是稳定的显式字符串 - 不随 R8 混淆变化`() {
        assertEquals("Saved", RememberOutcome.Saved("x").logTag)
        assertEquals("Updated", RememberOutcome.Updated("x").logTag)
        assertEquals("Superseded", RememberOutcome.Superseded("a", "b").logTag)
        assertEquals("Rejected", RememberOutcome.Rejected(RememberOutcome.Reason.BLANK).logTag)
        for (tag in listOf(
            RememberOutcome.Saved("x").logTag,
            RememberOutcome.Updated("x").logTag,
            RememberOutcome.Superseded("a", "b").logTag,
            RememberOutcome.Rejected(RememberOutcome.Reason.TRANSIENT).logTag,
        )) {
            assertTrue("标签「$tag」太短,像是被混淆过的类名", tag.length >= 4)
            assertTrue("标签「$tag」不该是单字母", tag.any { it.isLetter() })
        }
    }
}
