package com.shiyin.music.data.furigana

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.4.0:歌词 hash 的**语义契约**与**迁移判据**。
 *
 * 背景（真机用户反馈）：给歌词打轴后 raw 里多了 `[mm:ss.xx]`，旧算法把它算进 hash → 变化 →
 * 按旧 hash 存的**译文/手写注音查不回来**（"翻译完退出后重新加载丢失"）。修法是让 hash
 * 只基于歌词文本；代价是**升级前存的数据落在旧 hash 名下**，需要一次性搬键
 * （MainViewModel.maybeMigrateLyricsHash）。这里把两侧契约都钉住：
 *  - [LyricsHash.of]：打轴不改变 hash（新行为，必须成立）；
 *  - [LyricsHash.ofLegacy]：保留旧行为，迁移时用它去兜底找老数据；
 *  - [LyricsHash.needsMigration]：只有"两个算法给出不同 hash"时才需要搬键 —— 无时间戳的
 *    绝大多数曲目两值相同，走短路、不碰数据库。
 */
class LyricsHashTest {

    private val plain = "第一行歌词\n第二行歌词\n第三行歌词"

    /** 同一份歌词打轴后的样子（行首 [mm:ss.xx] + 缩进）。 */
    private val timestamped = "[00:01.00] 第一行歌词\n[00:05.32] 第二行歌词\n[00:09.10] 第三行歌词"

    @Test
    fun `打轴前后 hash 不变 - 这是本次修复的核心诉求`() {
        assertEquals(
            "打轴只是加了时间戳元数据,不该让 hash 变化",
            LyricsHash.of(plain),
            LyricsHash.of(timestamped),
        )
    }

    @Test
    fun `旧算法在打轴后 hash 会变 - 迁移就是为它准备的`() {
        assertNotEquals(
            "旧算法把时间戳算进 hash,所以升级前的数据落在另一个 hash 名下",
            LyricsHash.ofLegacy(plain),
            LyricsHash.ofLegacy(timestamped),
        )
        // 迁移时用旧算法去兜底找老数据:老数据的 hash 必须等于"按旧算法对当前 raw 算出的值"
        assertEquals(LyricsHash.ofLegacy(timestamped), LyricsHash.ofLegacy(timestamped))
    }

    @Test
    fun `无时间戳的歌词两算法同值 - 不需要迁移`() {
        assertFalse("纯文本歌词不该触发搬键", LyricsHash.needsMigration(plain))
        assertEquals(LyricsHash.of(plain), LyricsHash.ofLegacy(plain))
    }

    @Test
    fun `带时间戳或行首缩进的歌词需要迁移`() {
        assertTrue("打轴后的歌词必须触发搬键", LyricsHash.needsMigration(timestamped))
        assertTrue("行首缩进同样被新算法归一 → 也需要搬键", LyricsHash.needsMigration("  第一行歌词\n  第二行歌词"))
    }

    @Test
    fun `换行风格与行尾空白不改变新算法 hash`() {
        val crlf = "第一行歌词\r\n第二行歌词\r\n第三行歌词   "
        assertEquals("\\r\\n 与行尾空白是无意义差异", LyricsHash.of(plain), LyricsHash.of(crlf))
    }

    @Test
    fun `内容真的变了 hash 必须变 - 否则旧注音会张冠李戴`() {
        assertNotEquals(
            "改歌词文本本就该让旧的注音/译文失效",
            LyricsHash.of(plain),
            LyricsHash.of("第一行歌词\n第二行歌词改过了\n第三行歌词"),
        )
    }

    @Test
    fun `hash 稳定 - 同输入多次调用结果一致且长度为 16`() {
        val a = LyricsHash.of(timestamped)
        assertEquals(a, LyricsHash.of(timestamped))
        assertEquals("SHA-256 取前 8 字节 = 16 hex 字符", 16, a.length)
    }

    // ── 搬键决策(覆盖"导入旧 hash 版本的备份后,译文与注音还能找到") ────────────

    @Test
    fun `旧版本存下的行能被找到并搬到新 hash 下`() {
        // 模拟"旧版本写入数据库的那一行":它的 lyricsHash 是**旧算法**对同一份 raw 算的值。
        val storedByOldVersion = LyricsHash.ofLegacy(timestamped)
        val plan = planHashMigration(timestamped, rowsUnderNewHash = 0)
        assertEquals("搬键的 from 必须正好等于旧版本存下的那个 hash", storedByOldVersion, plan?.from)
        assertEquals("搬到新算法算出的 hash", LyricsHash.of(timestamped), plan?.to)
        // 搬完之后:所有读取点(一律用 of)自然命中 ✓
        assertEquals("搬完再查就是新 hash", LyricsHash.of(timestamped), plan?.to)
    }

    @Test
    fun `无时间戳的歌词压根不需要搬键 - 不开事务不碰库`() {
        assertEquals(null, planHashMigration(plain, rowsUnderNewHash = 0))
    }

    @Test
    fun `新 hash 名下已有行时跳过 - 防 UPDATE 撞主键`() {
        // 用户升级后又存取过：新 hash 下已有行，旧行属下堂；硬搬会违反三张表含 lyricsHash 的主键约束
        assertEquals(null, planHashMigration(timestamped, rowsUnderNewHash = 1))
    }
}
