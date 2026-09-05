package com.shiyin.music.data.image

/**
 * v1.2.1: 歌手写真源的运行时可配状态。由 MainViewModel 从 SettingsStore flow 同步写入,
 * 各源在 fetch/fetchAll 时读取——这样用户在「设置·写真源」里填的 API key / 关掉的源
 * 即时生效,无需改 local.properties 重新编译。
 *
 * fanartApiKey/lastfmApiKey 空串=未配(对应源跳过)。disabledSources=用户关掉的源 key。
 * v1.3.2: 新增 customSources(设置·写真源添加的自定义源,见 [CustomImageSourceDef])。
 * 字段 volatile:由主线程(flow collector)写、IO 线程(源 fetch)读,单值可见即可。
 */
internal object ImageSourceConfig {
    @Volatile var fanartApiKey: String = ""
    @Volatile var lastfmApiKey: String = ""
    @Volatile var disabledSources: Set<String> = emptySet()
    @Volatile var customSources: List<CustomImageSourceDef> = emptyList()
}

/**
 * v1.3.2: 自定义写真源定义(设置·写真源可增删)。
 *
 * [urlTemplate] 中 {name} 会被替换为 URL 编码后的歌手名,两种用法:
 *  - 模板以图片扩展名结尾(如 https://host/imgs/{name}.jpg)→ 视为直连图片 URL;
 *  - 否则请求后按 JSON 解析,递归收集响应里像图片 URL 的字符串值(去重,最多 10 张)。
 * [apiKey] 可选:URL 里含 {key} 时替换进去;否则以 Authorization: Bearer 头附带。
 *
 * [id] 稳定唯一(毫秒时间戳 36 进制),禁用状态用 "custom-{id}" 记入 disabledSources。
 */
data class CustomImageSourceDef(
    val id: String,
    val name: String,
    val urlTemplate: String,
    val apiKey: String = "",
)

/** v1.3.2: [CustomImageSourceDef] 列表 ↔ JSON(存 DataStore stringPreferencesKey)。
 *  手写字段解析:Gson 反序列化会绕过 Kotlin 构造器,旧数据缺字段时非空类型会塞进 null。 */
object CustomImageSourceCodec {
    fun encode(list: List<CustomImageSourceDef>): String = com.google.gson.Gson().toJson(list)

    fun decode(raw: String): List<CustomImageSourceDef> = try {
        val t = raw.trim()
        if (t.isEmpty()) emptyList()
        else com.google.gson.JsonParser.parseString(t).asJsonArray.mapNotNull { el ->
            val o = el.asJsonObject
            CustomImageSourceDef(
                id = o.get("id")?.asString ?: return@mapNotNull null,
                name = o.get("name")?.asString ?: "",
                urlTemplate = o.get("urlTemplate")?.asString ?: "",
                apiKey = o.get("apiKey")?.asString ?: "",
            )
        }
    } catch (_: Exception) { emptyList() }
}
