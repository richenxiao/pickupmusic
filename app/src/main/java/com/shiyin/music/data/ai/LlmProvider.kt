package com.shiyin.music.data.ai

/**
 * v1.2.2 Agent: LLM 服务商抽象（向后兼容保留）。
 *
 * 旧设计: [DeepSeekProvider] 单供应商硬编码 endpoint/model。
 * 新设计: [LlmClient.call(LlmProviderConfig, messages)] 支持任意已配置供应商。
 * 本接口 + DeepSeekProvider 保留为薄委托,供旧的单技能 [AgentService] 继续编译;
 * Agent 引擎(Stage 2)将改用 LlmClient + LlmProviderConfig 直接驱动,不再走本接口。
 */
interface LlmProvider {
    val key: String
    suspend fun call(messages: List<Pair<String, String>>): String?
}

/**
 * DeepSeek 实现(向后兼容)。委托给 [LlmClient] 用 DeepSeek 预设备配置调用,仅返回 content。
 * [apiKey] 由 MainViewModel 启动时从 SettingsStore 同步。
 */
object DeepSeekProvider : LlmProvider {
    override val key = "deepseek"

    @Volatile var apiKey: String = ""

    override suspend fun call(messages: List<Pair<String, String>>): String? {
        val config = LlmConfig.PRESETS.firstOrNull { it.key == "deepseek" }
            ?.copy(apiKey = apiKey) ?: return null
        return LlmClient.call(config, messages)?.content
    }
}
