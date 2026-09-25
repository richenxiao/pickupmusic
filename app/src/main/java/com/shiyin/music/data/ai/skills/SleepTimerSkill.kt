package com.shiyin.music.data.ai.skills

import com.shiyin.music.data.ai.AgentEngine
import com.shiyin.music.data.ai.LlmProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.3.8 Agent 技能⑥:睡眠定时。
 *
 * 用户自然语言 → 工具调用已经把「再听56分钟就停」「半小时后关」「听完这首就停」里的
 * 时长/mode 抽成了结构化参数,**技能本身不需要 LLM**——纯参数落地,零网络往返。
 * 这是原生工具调用相比旧的 prompt+JSON 链路最直接的收益之一:参数抽取由模型在
 * 分发那一次调用里顺带完成,不再需要额外的解析调用。
 *
 * 落地到项目既有能力(PlayerController):
 *  - 定时到点 → 指数淡出并暂停(setSleepTimer)
 *  - 本曲结束 → 尾段渐弱并在曲末暂停(setSleepEndOfTrack)
 *  - 取消     → setSleepTimer(0)
 *
 * 只读/设置类操作(不改音乐库内容),不需要确认闸门。
 */
object SleepTimerSkill : AgentEngine.AgentSkill {
    override val key = "sleep_timer"
    override val description =
        "设置或取消睡眠定时(到时间后自动淡出并暂停播放)。用户说「再听X分钟就停」「半小时后关」「听完这首就停」时用这个工具;" +
            "用户要取消睡眠定时时把 cancel 填 true。注意:这是设置类操作,不要和「暂停播放」混淆——用户只想立刻暂停时不要调用本工具。"
    override val parameters = """
        {"type":"object","properties":{
          "minutes":{"type":"integer","description":"要听多少分钟后停止。用户给了时间长度时填这个,必须换算成分钟:「半小时」=30,「一小时」=60,「再听56分钟」=56,「一个半小时」=90。用户说的是「听完这首就停」时不要填这个"},
          "end_of_track":{"type":"boolean","description":"用户要求「这首听完就停」「本曲结束后停」时填 true"},
          "cancel":{"type":"boolean","description":"用户要求取消/关闭睡眠定时时填 true"}
        },"required":[]}
    """.trimIndent()

    override fun panelSteps(args: Map<String, String>) = listOf(
        AgentEngine.PanelStep("apply", "设置睡眠定时"),
    )

    /** 24 小时上限:超过基本是模型把时长解析错了,"到点停"没有合理场景需要更久。 */
    private const val MAX_MINUTES = 24 * 60

    override suspend fun execute(
        ctx: AgentEngine.SkillContext,
        args: Map<String, String>,
        config: LlmProviderConfig,
        tavilyKey: String,
        onStep: (String, AgentEngine.StepStatus) -> Unit,
    ): AgentEngine.SkillResult = withContext(Dispatchers.IO) {
        val cancel = asFlag(args["cancel"])
        val endOfTrack = asFlag(args["end_of_track"])
        val minutes = args["minutes"]?.trim()?.toIntOrNull()

        onStep("apply", AgentEngine.StepStatus.RUNNING)

        when {
            // 取消优先:用户改主意说"算了不用了"时模型可能同时带上别的字段
            cancel -> {
                ctx.setSleepTimer(0)
                onStep("apply", AgentEngine.StepStatus.DONE)
                AgentEngine.SkillResult("已关闭睡眠定时,继续正常播放。")
            }

            endOfTrack -> {
                ctx.setSleepTimerEndOfTrack()
                onStep("apply", AgentEngine.StepStatus.DONE)
                AgentEngine.SkillResult("好,这首放完就停(在曲末渐弱后暂停)。要取消随时说。")
            }

            minutes != null && minutes in 1..MAX_MINUTES -> {
                ctx.setSleepTimer(minutes)
                onStep("apply", AgentEngine.StepStatus.DONE)
                // 回执带回真实状态(sleepStatusText),让用户一眼确认生效
                AgentEngine.SkillResult("已设置睡眠定时:${describeMinutes(minutes)}后自动暂停(当前:${ctx.sleepTimerStatus()})。")
            }

            minutes != null -> {
                onStep("apply", AgentEngine.StepStatus.FAILED)
                AgentEngine.SkillResult(
                    "时长 ${minutes} 分钟不太合理(支持 1~$MAX_MINUTES 分钟)。可以说「再听30分钟就停」。",
                    success = false,
                )
            }

            else -> {
                // 模型没抽出任何参数 —— 不要瞎设一个默认值,问清楚
                onStep("apply", AgentEngine.StepStatus.FAILED)
                AgentEngine.SkillResult(
                    "没说多久后停。可以说「再听30分钟就停」「听完这首就停」,或者「关掉睡眠定时」。",
                    success = false,
                )
            }
        }
    }

    /** 宽松布尔解析。模型可能回 true / "true" / 1 / "是" —— 只认严格 "true" 太脆。 */
    private fun asFlag(v: String?): Boolean =
        v?.trim()?.lowercase() in setOf("true", "1", "yes", "y", "是")

    /** 分钟数 → 口语化描述,回执里比裸数字好读。 */
    private fun describeMinutes(min: Int): String {
        val h = min / 60
        val m = min % 60
        return when {
            h > 0 && m > 0 -> "${h}小时${m}分钟"
            h > 0 -> "${h}小时"
            else -> "${m}分钟"
        }
    }
}
