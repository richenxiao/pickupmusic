package com.shiyin.music.data.ai.skills

import com.shiyin.music.data.ai.AgentEngine
import com.shiyin.music.data.ai.LlmProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.3.8 Agent 技能⑨:播放模式与速度。
 *
 * 「随机播放」「单曲循环」「慢放」这类说法用户会自然地说出口,此前没有工具接得住。
 * 与 playback_control 同批(都是"操作播放器"这一副作用类别),因此桥方法一次加齐。
 *
 * 零 LLM、零网络。速度沿用 App 既有实现:量化到应用支持的档位(quantizeSpeed)、
 * 沿用用户的复古变速偏好、并写回设置(重启后保持)——与设置页调节速度是同一个入口。
 *
 * 支持范围与设置页一致:0.5× ~ 2.0×。
 */
object PlaybackModeSkill : AgentEngine.AgentSkill {
    override val key = "playback_mode"
    override val description =
        "调整播放模式与播放速度:随机播放开关、循环模式(不循环/列表循环/单曲循环)、播放速度。" +
            "用户说「随机播放」「关掉随机」「单曲循环」「列表循环」「取消循环」「慢放」「加快」「1.5倍速」「恢复正常速度」时用这个工具。" +
            "不涉及模式或速度的请求不要调用本工具。"
    override val parameters = """
        {"type":"object","properties":{
          "shuffle":{"type":"boolean","description":"随机播放开关。用户要开随机填 true,要关随机填 false。用户没提随机时不要填这个字段"},
          "repeat":{"type":"string","enum":["off","all","one"],"description":"循环模式:off=不循环(播完就停),all=列表循环,one=单曲循环。用户没提循环时不要填"},
          "speed":{"type":"number","description":"播放速度倍数,正常为 1,支持 0.5 到 2.0。用户说「慢放」可填 0.8,「加快」可填 1.25,「两倍速」填 2。「恢复正常速度」填 1"}
        },"required":[]}
    """.trimIndent()

    override fun panelSteps(args: Map<String, String>) = listOf(
        AgentEngine.PanelStep("apply", "调整播放模式"),
    )

    private const val MIN_SPEED = 0.5f
    private const val MAX_SPEED = 2.0f

    /** 宽松布尔:模型可能回 true / "true" / 1 / "是"。 */
    private fun asBool(v: String?): Boolean? = when (v?.trim()?.lowercase()) {
        "true", "1", "yes", "y", "是" -> true
        "false", "0", "no", "n", "否", "不" -> false
        else -> null
    }

    private fun repeatLabel(m: AgentEngine.RepeatMode): String = when (m) {
        AgentEngine.RepeatMode.OFF -> "不循环"
        AgentEngine.RepeatMode.ALL -> "列表循环"
        AgentEngine.RepeatMode.ONE -> "单曲循环"
    }

    override suspend fun execute(
        ctx: AgentEngine.SkillContext,
        args: Map<String, String>,
        config: LlmProviderConfig,
        tavilyKey: String,
        onStep: (String, AgentEngine.StepStatus) -> Unit,
    ): AgentEngine.SkillResult = withContext(Dispatchers.IO) {
        val shuffle = asBool(args["shuffle"])
        val repeat = args["repeat"]?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        val speedRaw = args["speed"]?.trim()?.toFloatOrNull()

        onStep("apply", AgentEngine.StepStatus.RUNNING)

        fun fail(msg: String): AgentEngine.SkillResult {
            onStep("apply", AgentEngine.StepStatus.FAILED)
            return AgentEngine.SkillResult(msg, success = false)
        }

        if (shuffle == null && repeat == null && speedRaw == null) {
            return@withContext fail("没听清要调什么。可以说「随机播放」「单曲循环」「慢放一点」。")
        }

        val repeatMode = when (repeat) {
            null -> null
            "off" -> AgentEngine.RepeatMode.OFF
            "all" -> AgentEngine.RepeatMode.ALL
            "one" -> AgentEngine.RepeatMode.ONE
            else -> return@withContext fail("循环模式只认得「不循环 / 列表循环 / 单曲循环」。")
        }

        // 速度先校验再落地——避免"设了一半"的中间态(随机已改、速度被拒)
        val speed = speedRaw?.let {
            if (it < MIN_SPEED || it > MAX_SPEED) {
                return@withContext fail("播放速度支持 $MIN_SPEED× ~ $MAX_SPEED×,${it}× 超出范围了。")
            }
            com.shiyin.music.playback.quantizeSpeed(it)
        }

        val changes = mutableListOf<String>()
        speed?.let { ctx.setPlaybackSpeed(it); changes += "速度 ${"%.2f".format(it)}×" }
        shuffle?.let {
            ctx.setShuffle(it)
            changes += if (it) "随机播放已开" else "随机播放已关"
        }
        repeatMode?.let { ctx.setRepeatMode(it); changes += repeatLabel(it) }

        onStep("apply", AgentEngine.StepStatus.DONE)
        // 回执带上真实当前态(sleep_timer 同款做法),让用户一眼确认生效
        AgentEngine.SkillResult(
            "已调整:${changes.joinToString("、")}。" +
                "(当前:随机播放${if (ctx.isShuffleOn()) "开" else "关"}、${repeatLabel(ctx.repeatMode())})",
        )
    }
}
