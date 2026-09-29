package com.shiyin.music.data.ai.skills

import com.shiyin.music.data.ai.AgentEngine
import com.shiyin.music.data.ai.LlmProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.3.10 需求 A27:`save_lyrics` —— 把歌词文本落到**当前曲目**。
 *
 * 补的是"agent 被做成 workflow"的那个能力空洞:内置源(LRCLIB/网易云/LrcShare)搜不到时,
 * 模型本来可以用 web_search 去网上找,但**此前没有任何工具能把找到的歌词保存下来**——
 * 于是它只能回一句"内置源没有此资源"(用户实测:"死死限定住,那就是 workflow 不是 agent")。
 * 有了这个出口,链路才成立:内置源 → 都没有 → web_search 上网找 → save_lyrics 落地 → 说明来源。
 *
 * 只写歌词,且可撤销(歌词本里能删除/换源)——符合"不可逆操作才排除在 Agent 之外"的约定。
 */
object SaveLyricsSkill : AgentEngine.AgentSkill {

    override val key = "save_lyrics"
    override val description =
        "把歌词文本保存到当前播放的歌曲(歌词本里可见,可随时删除或换源)。" +
            "**用在**:内置歌词源都没找到、你又用 web_search 从网上找到了歌词之后——" +
            "把歌词原文(LRC 带 [mm:ss.xx] 时间戳最好,纯文本也行)放进 text。 " +
            "不要凭记忆编造歌词;确实没搜到就不要调用本工具。" +
            "source 填来源标签(如「联网」或网站名),它会显示在歌词卡片的来源处。"
    override val parameters = """
        {"type":"object","properties":{
          "text":{"type":"string","description":"歌词原文(优先 LRC 带 [mm:ss.xx] 时间戳;纯文本也可)"},
          "source":{"type":"string","description":"来源标签,显示在歌词卡片(如 联网 / 网站名)"}
        },"required":["text"]}
    """.trimIndent()

    override fun panelSteps(args: Map<String, String>) =
        listOf(AgentEngine.PanelStep("save", "保存找到的歌词"))

    override suspend fun execute(
        ctx: AgentEngine.SkillContext,
        args: Map<String, String>,
        config: LlmProviderConfig,
        tavilyKey: String,
        onStep: (String, AgentEngine.StepStatus) -> Unit,
    ): AgentEngine.SkillResult = withContext(Dispatchers.IO) {
        onStep("save", AgentEngine.StepStatus.RUNNING)
        fun fail(msg: String): AgentEngine.SkillResult {
            onStep("save", AgentEngine.StepStatus.FAILED)
            return AgentEngine.SkillResult(msg, success = false)
        }

        val track = ctx.currentTrack() ?: return@withContext fail("当前没有播放歌曲,先把歌放上再保存歌词。")
        val text = args["text"]?.trim().orEmpty()
        if (text.length < 10) return@withContext fail("拿到的歌词内容太短,我没敢当歌词保存。")
        // 落地前自检:能否解析出歌词行(纯文本也有 plain 分支)——避免把一段说明文字存成歌词。
        val parsed = runCatching { com.shiyin.music.data.lyrics.LrcParser.parse(text) }.getOrNull()
        if (parsed == null || parsed.isEmpty) return@withContext fail("这段内容解析不出歌词行,没有保存。")
        val source = args["source"]?.trim()?.takeIf { it.isNotBlank() } ?: "联网"
        ctx.importLyricsForCurrentTrack(source, text)
        android.util.Log.i("Agent", "save_lyrics: 《${track.title}》来源=$source ${parsed.lines.size} 行")
        onStep("save", AgentEngine.StepStatus.DONE)
        AgentEngine.SkillResult(
            "已把《${track.title}》的歌词保存到歌词本(来源:$source,${parsed.lines.size} 行)。" +
                "不对的话可以在歌词本里换源或删除。",
        )
    }
}
