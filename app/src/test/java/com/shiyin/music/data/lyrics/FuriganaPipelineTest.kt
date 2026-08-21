package com.shiyin.music.data.lyrics

import com.shiyin.music.data.furigana.LyricsReadingOverrides
import com.shiyin.music.data.furigana.ReadingDictionary
import com.shiyin.music.ui.components.RubySegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * V1.1 准确率流水线回归测试（FuriganaTokenizer.toSegments 四参重载）。
 *
 * 验证优先级 Song Override > 纠错小表 > JMdict(单读法/CONFLICT→无) > Kuromoji > No Reading，
 * 以及词级 RubySegment（二人/此方/真夏 整体不被逐字拆错）。用假 ReadingDictionary 注入，
 * 不依赖 Android assets（JMdict 派生词典的运行时加载在设备上验证）。
 */
class FuriganaPipelineTest {

    /** 假 JMdict：只含测试用 surface→[readings]，覆盖归一去重后多读法=CONFLICT。 */
    private val fakeJmdict = object : ReadingDictionary {
        private val m: Map<String, List<String>> = mapOf(
            "何" to listOf("なに", "なん"),           // CONFLICT（多读法）
            "二人" to listOf("ふたり", "ににん"),     // CONFLICT，但纠错小表钉 ふたり
            "明日" to listOf("あした", "あす", "みょうにち"), // CONFLICT，纠错钉 あした
            "真夏" to listOf("まなつ"),              // 单一读法（覆盖）
            "此方" to listOf("こちら", "こっち"),     // CONFLICT，纠错钉 こちら
            "此処" to listOf("ここ"),                // 单一读法
            "大人" to listOf("おとな"),
            "真実" to listOf("しんじつ", "まこと"),  // CONFLICT，用于测外部 evidence 逐字拼接
        )
        override fun readings(surface: String): List<String>? = m[surface]
    }

    private fun segs(
        line: String,
        lineOverrides: Map<Int, String> = emptyMap(),
        externalEvidence: Map<Int, Pair<Int, String>> = emptyMap(),
        jmdict: ReadingDictionary? = fakeJmdict,
    ): List<RubySegment> = FuriganaTokenizer.toSegments(
        line,
        lineOverrides = lineOverrides,
        externalEvidence = externalEvidence,
        correction = LyricsReadingOverrides,
        jmdict = jmdict,
    )

    private fun readingOf(segs: List<RubySegment>, surface: String): String? =
        segs.firstOrNull { it.surface == surface }?.reading

    @Test
    fun 何_conflict_Kuromoji读法被JMdict确认_显示なに() {
        // 何 + なん：ContextResolver 不猜；但 Kuromoji 读法 なに 在 JMdict 候选 [なに,なん] 中
        // → 被确认有效 → 显示 なに（"能确定就显示"，不再 CONFLICT→No Reading）
        val s = segs("何なんそれ")
        assertEquals("なに", readingOf(s, "何"))
        assertTrue("surface 拼接等于原文", s.joinToString("") { it.surface } == "何なんそれ")
    }

    @Test
    fun 何_songOverride_显示なん() {
        // 何 在该行 offset 0
        val s = segs("何があっても", lineOverrides = mapOf(0 to "なん"))
        assertEquals("何", "なん", readingOf(s, "何"))
    }

    @Test
    fun 何_同行多次出现_按位置分别设不同读法() {
        // "何なん 何なん 何なん"：何 出现 3 次（offset 0/4/8），按位置分别设 なに/なん/なに
        val s = segs("何なん 何なん 何なん", lineOverrides = mapOf(0 to "なに", 4 to "なん", 8 to "なに"))
        // 3 个 何 segment，各自不同读法（不归一）
        val nanReadings = s.filter { it.surface == "何" }.map { it.reading }
        assertEquals(listOf("なに", "なん", "なに"), nanReadings)
    }

    @Test
    fun 二人_纠错表_ふたり整体不拆() {
        val s = segs("二人で歩く")
        // 二人 经纠错小表 → ふたり，作为整体 RubySegment（不被拆成 二/に + 人/にん）
        assertEquals("ふたり", readingOf(s, "二人"))
        // 不应出现被拆错的 二/に 或 人/にん
        assertEquals(null, readingOf(s, "二"))
        assertEquals(null, readingOf(s, "人"))
    }

    @Test
    fun 此方_纠错表_こちら整体() {
        val s = segs("此方へ")
        assertEquals("こちら", readingOf(s, "此方"))
    }

    @Test
    fun 真夏_jmdict单一读法_まなつ整体不拆() {
        val s = segs("真夏の海")
        assertEquals("まなつ", readingOf(s, "真夏"))
    }

    @Test
    fun 大人_jmdict单一_おとな() {
        val s = segs("大人になる")
        assertEquals("おとな", readingOf(s, "大人"))
    }

    @Test
    fun 明日_纠错表_あした() {
        val s = segs("明日は晴れる")
        assertEquals("あした", readingOf(s, "明日"))
    }

    @Test
    fun 魏_songOverride前无注音_后たか() {
        // 魏 不在 JMdict/纠错表 → Kuromoji 兜底：魏 在 IPADIC 是 known(ギ)。
        // 但魏是当て字，默认应走 Song Override。有 override 时正确解析为 たか（按位置）。
        val s = segs("魏赵に捧ぐ", lineOverrides = mapOf(0 to "たか"))
        assertEquals("たか", readingOf(s, "魏"))
    }

    @Test
    fun 纯假名行_零注音() {
        val s = segs("きょうはいいてんきでした", jmdict = null)
        // 全假名无汉字 → 全部无注音
        assertTrue(s.all { it.reading == null })
        assertEquals("きょうはいいてんきでした", s.joinToString("") { it.surface })
    }

    @Test
    fun jmdict未加载_退回Kuromoji_真夏仍まなつ() {
        // JMdict 为空时，真夏 作为 Kuromoji 单 token 已知词 → まなつ（不依赖 JMdict）
        val s = segs("真夏の海", jmdict = null)
        assertEquals("まなつ", readingOf(s, "真夏"))
    }

    @Test
    fun 拼接完整性_永不破坏原文() {
        for (line in listOf("何があってもずっと大好きなのに", "二人で歩く", "真夏の海", "此方へ来ておくれ", "明日は晴れる", "何なん 何なん 何なん")) {
            val s = segs(line)
            assertEquals("行: $line", line, s.joinToString("") { it.surface })
        }
    }

    @Test
    fun startOffset_正确反映在行中位置() {
        // 何なん 何なん：第2个何 在 offset 4
        val s = segs("何なん 何なん")
        val nanSegs = s.filter { it.surface == "何" }
        assertEquals(listOf(0, 4), nanSegs.map { it.startOffset })
    }

    // ── ContextResolver 集成：何 CONFLICT 时高置信上下文消歧 ──
    @Test
    fun 何が_上下文消歧为なに_无需override() {
        // 何が → 助词が → 消歧 なに（不依赖 Song Override，自动正确）
        val s = segs("何が起きたの")
        assertEquals("なに", readingOf(s, "何"))
    }

    @Test
    fun 何だ_上下文消歧为なん_无需override() {
        val s = segs("それは何だ")
        assertEquals("なん", readingOf(s, "何"))
    }

    @Test
    fun 何なん_ContextResolver不猜_Kuromoji确认_显示なに() {
        // 何 + なん：ContextResolver 无法消歧（なん 不在助词/系动词规则）；但 Kuromoji なに
        // 在 JMdict [なに,なん] 候选中 → 被确认 → 显示 なに（用户可用 Song Override 改成 なん）
        val s = segs("何なん")
        assertEquals("なに", readingOf(s, "何"))
    }

    @Test
    fun 何なん_OccurrenceOverride_覆盖ContextResolver_设なん() {
        // 即便 ContextResolver 不消歧（返回 null），Occurrence Override 仍能强制设值
        val s = segs("何なん", lineOverrides = mapOf(0 to "なん"))
        assertEquals("なん", readingOf(s, "何"))
    }

    @Test
    fun OccurrenceOverride_优先于ContextResolver() {
        // 何が ContextResolver 给 なに；但该处 Override 设 なん → 用 なん（人工 > 自动）
        val s = segs("何が起きたの", lineOverrides = mapOf(0 to "なん"))
        assertEquals("なん", readingOf(s, "何"))
    }

    // ── 外部 evidence 层（V1.1+）──
    @Test
    fun 外部evidence_覆盖ContextResolver_何なん设なん() {
        // 何なん 语境歧义，ContextResolver 不猜 → null；但外部 evidence 给 なん → 用 なん
        val s = segs("何なん", externalEvidence = mapOf(0 to (1 to "なん")))
        assertEquals("なん", readingOf(s, "何"))
    }

    @Test
    fun 外部evidence_与ContextResolver一致_何が_なに() {
        // 何が ContextResolver→なに；外部 evidence 也→なに；结果 なに（一致，高置信）
        val s = segs("何が起きたの", externalEvidence = mapOf(0 to (1 to "なに")))
        assertEquals("なに", readingOf(s, "何"))
    }

    @Test
    fun OccurrenceOverride_优先于外部evidence() {
        // 外部 evidence 给 なん；但用户 Override 设 なに → 用 なに（人工 > 外部 > 自动）
        val s = segs("何なん", externalEvidence = mapOf(0 to (1 to "なん")), lineOverrides = mapOf(0 to "なに"))
        assertEquals("なに", readingOf(s, "何"))
    }

    @Test
    fun 外部evidence_同行多何_分别设不归一() {
        // 何なん 何なん：外部 evidence 给第1个何=なん、第2个何=なに（不同 occurrence）
        val s = segs("何なん 何なん", externalEvidence = mapOf(0 to (1 to "なん"), 4 to (1 to "なに")))
        val r = s.filter { it.surface == "何" }.sortedBy { it.startOffset }
        assertEquals(listOf("なん", "なに"), r.map { it.reading })
    }

    // ── B 修复：外部 evidence 对「合并词」按段范围拼接逐字 run，不丢后段 ──
    @Test
    fun 外部evidence_合并词逐字run拼接_しんじつ() {
        // 真実 是合并段(length 2)；UtaTen 给逐字 run：真@0(1,しん)+実@1(1,じつ)。
        // 修复前：只取段起点 run→しん（丢実）；修复后：铺满 [0,2) → しんじつ。
        val s = segs("真実もある", externalEvidence = mapOf(0 to (1 to "しん"), 1 to (1 to "じつ")))
        assertEquals("しんじつ", readingOf(s, "真実"))
    }

    @Test
    fun 外部evidence_合并词整体run_しんじつ() {
        // 真実 作为整体 run（length 2, charStart 0）→ 精确匹配段 → しんじつ
        val s = segs("真実もある", externalEvidence = mapOf(0 to (2 to "しんじつ")))
        assertEquals("しんじつ", readingOf(s, "真実"))
    }

    @Test
    fun 外部evidence_铺不满时不采用_用base_しんじつ() {
        // 真実段[0,2)，只给 真@0(1,しん) 而无 実 的 run → 铺不满 → 外部 evidence 不采用。
        // base：真実 CONFLICT → Kuromoji しんじつ 被 JMdict 确认 → 显示 しんじつ（不残缺 しん）
        val s = segs("真実もある", externalEvidence = mapOf(0 to (1 to "しん")))
        assertEquals("しんじつ", readingOf(s, "真実"))
    }

    // ── 送假名保护：splitOkurigana 成功时送假名不注音；失败时不注音（不 group-ruby）──
    @Test
    fun 続ける_送假名ける无注音_汉字続有つづ() {
        val s = segs("続ける", jmdict = null)
        // 続 → つづ（汉字段有注音）；ける → null（送假名不注音）
        assertEquals("つづ", readingOf(s, "続"))
        assertEquals(null, readingOf(s, "ける"))
    }

    @Test
    fun 美しい_送假名しい无注音_汉字美有うつく() {
        val s = segs("美しい", jmdict = null)
        assertEquals("うつく", readingOf(s, "美"))
        assertEquals(null, readingOf(s, "しい"))
    }

    @Test
    fun 受ける_送假名ける无注音() {
        val s = segs("受けた", jmdict = null)
        assertEquals("う", readingOf(s, "受"))
        assertEquals(null, readingOf(s, "けた"))
    }

    @Test
    fun 掛ける_送假名ける无注音() {
        val s = segs("掛ける", jmdict = null)
        assertEquals("か", readingOf(s, "掛"))
        assertEquals(null, readingOf(s, "ける"))
    }
}
