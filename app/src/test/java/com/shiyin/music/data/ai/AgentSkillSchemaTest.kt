package com.shiyin.music.data.ai

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.3.8 技能工具化的契约测试。
 *
 * 重点不是"技能能不能跑"（那需要真机 + API key），而是守住两条**静默失败**的契约:
 *
 *  1. 每个技能声明的 `parameters` 必须是合法的 JSON Schema,且 `required` 里点名的字段
 *     必须真的在 `properties` 里存在——写错了不会编译报错,只会让模型永远填不出参数,
 *     表现为"这个技能忽然不工作了"。AgentTools.buildTools 对坏 schema 是**静默降级**
 *     成空对象 schema 的,所以只能靠这条测试兜住。
 *
 *  2. fastPathPlan 的收窄(D2 决策):只保留 album_sort。若哪天有人把 artist_fix /
 *     track_rename 的正则加回来,这里会红——提醒"语义消歧应交给工具 description",
 *     避免新旧两套消歧机制并存。
 */
class AgentSkillSchemaTest {

    @Test
    fun `每个技能的 parameters 都是合法 JSON Schema`() {
        assertTrue("技能列表不应为空", AgentEngine.skills.isNotEmpty())
        for (skill in AgentEngine.skills) {
            val json = runCatching { JsonParser.parseString(skill.parameters).asJsonObject }.getOrNull()
            assertNotNull("技能 ${skill.key} 的 parameters 不是合法 JSON 对象", json)

            assertEquals("技能 ${skill.key} 的 schema 顶层 type 必须是 object", "object", json!!.get("type").asString)
            val props = json.getAsJsonObject("properties")
            assertNotNull("技能 ${skill.key} 的 schema 缺少 properties", props)
        }
    }

    @Test
    fun `required 点名的字段必须存在于 properties`() {
        for (skill in AgentEngine.skills) {
            val json = JsonParser.parseString(skill.parameters).asJsonObject
            val props = json.getAsJsonObject("properties") ?: continue
            val required = json.getAsJsonArray("required") ?: continue
            for (el in required) {
                val name = el.asString
                assertTrue(
                    "技能 ${skill.key} 的 required 里点了不存在的字段「$name」——模型将永远填不出这个参数",
                    props.has(name),
                )
            }
        }
    }

    @Test
    fun `技能 key 唯一且可被分发层解析`() {
        val keys = AgentEngine.skills.map { it.key }
        assertEquals("技能 key 不能重复", keys.size, keys.toSet().size)
        for (skill in AgentEngine.skills) {
            assertTrue("技能 ${skill.key} 的 key 不能为空", skill.key.isNotBlank())
            assertTrue("技能 ${skill.key} 的 description 不能为空", skill.description.isNotBlank())
            assertTrue(
                "skillByKey 应能解析出 ${skill.key}",
                AgentEngine.skillByKey(skill.key) === skill,
            )
        }
    }

    // ── D2: 快速路径收窄 ──────────────────────────────────────────────────

    @Test
    fun `album_sort 快速路径保留`() {
        val hit = AgentEngine.fastPathPlan("把《魔杰座》排成正确顺序")
        assertNotNull("album_sort golden path 必须保留(零 LLM 往返)", hit)
        assertEquals("album_sort", hit!!.skillKey)
        assertEquals("魔杰座", hit.args["album"])
    }

    @Test
    fun `其余技能的本地正则消歧已删除`() {
        // 这些以前都会命中 fastPath;现在应全部落回模型(交给工具 description 判断)
        val removed = listOf(
            "《魔杰座》的歌手名识别反了",                    // 曾是 artist_fix
            "把每首歌名后面的-歌手名后缀去掉",                // 曾是 track_rename(规则路径)
            "把「搁浅」改成「擱淺」",                        // 曾是 track_rename(点名直改)
            "找一下这首歌的歌词",                            // 曾是 lyrics_fetch
            "库里有没有晴天这首歌",                          // 曾是 library_query(v1.3.6 起也没进快速路径)
        )
        for (text in removed) {
            assertNull("「$text」不应再走本地快速路径(语义消歧交给工具描述)", AgentEngine.fastPathPlan(text))
        }
    }

    @Test
    fun `闲聊不会误命中快速路径`() {
        // 「顺序」二字出现在聊天里不应触发排序技能
        assertNull(AgentEngine.fastPathPlan("为什么会卡在写回顺序上"))
    }

    // ── P2: 步骤面板由技能声明(取代 MainViewModel 里的映射表) ──────────────

    @Test
    fun `每个技能都声明了非空的步骤清单且 id 不重复`() {
        for (skill in AgentEngine.skills) {
            val panel = skill.panelSteps(emptyMap())
            assertTrue("技能 ${skill.key} 未声明 panelSteps——步骤面板会一片空白", panel.isNotEmpty())
            val ids = panel.map { it.id }
            assertEquals("技能 ${skill.key} 的 panelSteps id 有重复", ids.size, ids.toSet().size)
            for (p in panel) {
                assertTrue("技能 ${skill.key} 的步骤 id 不能为空", p.id.isNotBlank())
                assertTrue("技能 ${skill.key} 的步骤文案不能为空", p.label.isNotBlank())
            }
        }
    }

    @Test
    fun `panelSteps 的 id 必须与技能回调的语义 id 一致`() {
        // P2 的核心契约:面板行 id == execute 里 onStep 用的 id。
        // 改了 execute 的回调 id 却没同步声明,就在这条上红——这正是旧映射表时代
        // 会静默错位的那类 bug。
        assertEquals(
            listOf("tracks", "search", "analyze", "writeback"),
            AgentEngine.skillByKey("album_sort")!!.panelSteps(emptyMap()).map { it.id },
        )
        assertEquals(
            listOf("search", "analyze", "writeback"),
            AgentEngine.skillByKey("artist_fix")!!.panelSteps(emptyMap()).map { it.id },
        )
        // v1.3.10: library_query 不再做第二次 LLM 润色(输出改成带 track_id 的候选,
        // "人话"由模型在同一次对话里自己组织)→ answer 步骤随之删除。
        assertEquals(
            listOf("search"),
            AgentEngine.skillByKey("library_query")!!.panelSteps(emptyMap()).map { it.id },
        )
        assertEquals(
            listOf("search"),
            AgentEngine.skillByKey("lyrics_fetch")!!.panelSteps(emptyMap()).map { it.id },
        )
        // v1.3.15 推荐重构:先解析歌手/专辑(硬约束),再取候选池;有场景才多一步"让模型看
        // 哪几首贴合"(没有场景不调模型,面板上也不该挂这一行)。
        assertEquals(
            listOf("resolve", "pool"),
            AgentEngine.skillByKey("discover_music")!!.panelSteps(emptyMap()).map { it.id },
        )
        assertEquals(
            listOf("resolve", "pool", "pick"),
            AgentEngine.skillByKey("discover_music")!!.panelSteps(mapOf("scene" to "睡觉")).map { it.id },
        )
        assertEquals(
            listOf("apply"),
            AgentEngine.skillByKey("play_track")!!.panelSteps(emptyMap()).map { it.id },
        )
    }

    @Test
    fun `track_rename 三条路径的步骤清单按 args 分流`() {
        val skill = AgentEngine.skillByKey("track_rename")!!
        // 点名直改 —— 同时带 rule 时也必须走 locate/rename(execute 里 new_title 先判,
        // 快速路径会给点名直改一并带上 rule,顺序搞反就会显示错清单)
        assertEquals(
            listOf("locate", "rename"),
            skill.panelSteps(mapOf("new_title" to "新名", "rule" to "改成新名")).map { it.id },
        )
        // 批量规则
        assertEquals(
            listOf("analyze", "writeback"),
            skill.panelSteps(mapOf("rule" to "去掉歌名后缀")).map { it.id },
        )
        // 联网比对官方曲目表
        assertEquals(
            listOf("search", "analyze", "writeback"),
            skill.panelSteps(emptyMap()).map { it.id },
        )
    }
}
