package com.shiyin.music.data.ai

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * v1.2.2 Agent: 多模型提供商配置。每个 [LlmProviderConfig] 描述一个 OpenAI 兼容的 LLM
 * 服务商（endpoint + model + apiKey）。预设常见供应商（DeepSeek/OpenRouter/智谱/通义），
 * 也支持用户自定义补充。持久化在 SettingsStore（存 JSON 字符串）。
 */
data class LlmProviderConfig(
    /** 稳定标识（预设备: "deepseek"/"openrouter"/"zhipu"/"qwen"；自定义: "custom-N"）。 */
    val key: String,
    val displayName: String,
    /** chat/completions 端点（含 /v1 等路径，不含 "/chat/completions"——客户端拼接）。 */
    val baseUrl: String,
    val apiKey: String = "",
    val model: String = "",
) {
    /** 完整 chat/completions URL。
     *  v1.3.3: 容错用户输入——没写协议头自动补 https://,带尾斜杠去掉。 */
    val endpoint: String get() {
        val b = baseUrl.trim()
        val withProto = if (b.startsWith("http://") || b.startsWith("https://")) b else "https://$b"
        return withProto.trimEnd('/') + "/chat/completions"
    }
    /** [endpoint] 同款规范化的 baseUrl(listModels 用)。 */
    val normalizedBaseUrl: String get() {
        val b = baseUrl.trim()
        return if (b.startsWith("http://") || b.startsWith("https://")) b.trimEnd('/') else "https://" + b.trimEnd('/')
    }
    val isConfigured: Boolean get() = apiKey.isNotBlank() && model.isNotBlank() && baseUrl.isNotBlank()
}

object LlmConfig {

    /** 预设供应商(OpenAI 兼容)。用户可改 key/model,displayName/baseUrl 固定。
     *  model 默认值仅作占位——设置页真正选模型走运行时 GET /models 拉取(刷新键)。
     *  v1.3.6: 占位按 2026 当前主推填(DeepSeek v4 系;智谱 GLM-5 系;通义 qwen4 系;
     *  OpenRouter 是聚合端点没有"自家默认模型",留空让用户拉取后选)。 */
    val PRESETS: List<LlmProviderConfig> = listOf(
        LlmProviderConfig("deepseek", "DeepSeek", "https://api.deepseek.com", model = "deepseek-v4-flash"),
        LlmProviderConfig("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", model = ""),
        LlmProviderConfig("zhipu", "智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", model = "glm-5-flash"),
        LlmProviderConfig("qwen", "通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", model = "qwen-plus-latest"),
    )

    // 预置模型名改用运行时 GET /models 拉取(LlmClient.listModels),不再写死。

    // ── 序列化（SettingsStore 持久化）───────────────────────────────────────
    private val gson = Gson()
    private val listType = object : TypeToken<List<LlmProviderConfig>>() {}.type

    fun encode(providers: List<LlmProviderConfig>): String = gson.toJson(providers)
    /**
     * v1.3.6f: null 防御性解码。Gson 反序列化绕过 Kotlin 构造器(反射直填字段),
     * 历史 JSON 缺字段的条目会以 null 驻留——data class 的 copy() 一碰就 NPE
     * (设置页闪退根因),isConfigured 也因 model/baseUrl=null 误判"未配置"。
     * 这里全部过构造器重建:null → 安全默认值;key 为 null 的废条目丢弃;
     * 预设 key 的 model 空时回填当前占位模型。序列化侧 Gson 跳过 null 字段,
     * 所以脏条目一旦落盘会永远复现——重建后保存即根治。
     */
    fun decode(json: String): List<LlmProviderConfig> = try {
        val raw = gson.fromJson<List<LlmProviderConfig>>(json, listType) ?: return emptyList()
        raw.mapNotNull { p ->
            val k = p.key ?: return@mapNotNull null
            val preset = PRESETS.firstOrNull { it.key == k }
            LlmProviderConfig(
                key = k,
                displayName = p.displayName ?: preset?.displayName ?: k,
                baseUrl = p.baseUrl ?: preset?.baseUrl ?: "",
                apiKey = p.apiKey ?: "",
                model = p.model?.takeIf { it.isNotBlank() } ?: preset?.model ?: "",
            )
        }
    } catch (_: Exception) { emptyList() }
}
