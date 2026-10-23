package com.shiyin.music.data.lyrics

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/**
 * v1.3.10 需求 A29:用户**自定义歌词源**。
 *
 * 动机(需求原话):"歌词的获取不用写死,释放更多可能性""也可以加入他们自己的 API"——
 * 但也要"写得好一点,不能说用户到时候要填什么参数找不到能填写的地方"。
 *
 * 所以模型刻意做成**完全显式的字段**:每个参数在设置页都有一个带说明的输入框——
 * 搜索 URL 模板、取 id 的 JSON 路径、歌词 URL 模板、取歌词的 JSON 路径、附加请求头。
 * 用户照着任何一个"GET + 返回 JSON"的歌词 API 文档都能填。
 *
 * 为什么用"URL 模板 + JSON 路径"而不是脚本/正则:
 *  - 模板与路径是**可解释、可校验**的(能给出"这个路径在响应里取不到东西"的明确报错),
 *    而脚本会让用户在设置页里写代码——那是另一种"没地方填"。
 *
 * 占位符:
 *  - [searchUrl] 可用 `{title}` `{artist}` `{album}`(自动 URL 编码);
 *  - [lyricUrl] 可用 `{id}` `{title}` `{artist}`。
 *
 * 纯逻辑(模板求值 + JSON 路径解析 + JSON 编解码),零网络——见 CustomLyricsSourceTest。
 */
data class CustomLyricsSourceDef(
    /** 稳定标识(禁用集 / 缓存 key / 日志都用它)。 */
    val id: String,
    /** 设置页与来源菜单里显示的名字(如 "我的网易镜像")。 */
    val name: String,
    /** 搜索接口模板,必须含 `{title}`;例: `https://api.x.com/search?q={title}&a={artist}` */
    val searchUrl: String,
    /** 从搜索响应里取歌曲 id 的 JSON 路径;例: `data.items[0].id` 或 `result[0].song_id` */
    val idJsonPath: String,
    /** 歌词接口模板,必须含 `{id}`;例: `https://api.x.com/lyric/{id}` */
    val lyricUrl: String,
    /** 从歌词响应里取歌词文本的 JSON 路径;例: `data.lrc` 或 `lyric` */
    val lyricJsonPath: String,
    /** 附加请求头,每行 `Key: Value`(可选;需要鉴权的源在这里填)。 */
    val headers: String = "",
)

object CustomLyricsSourceCodec {

    /**
     * 列表 ↔ JSON。用 **Gson 的树 API**(JsonParser/JsonObject)手动字面键名读写——
     * 项目规矩:release 包禁用 Gson 反射绑定([[gson-r8-reflection-ban]]),树 API 不算反射;
     * 顺带这一层能在**纯 JVM 单测**里跑(org.json 在单测里是桩,不可用)。
     */
    fun encode(list: List<CustomLyricsSourceDef>): String {
        val arr = JsonArray()
        for (d in list) {
            arr.add(JsonObject().apply {
                addProperty("id", d.id)
                addProperty("name", d.name)
                addProperty("searchUrl", d.searchUrl)
                addProperty("idJsonPath", d.idJsonPath)
                addProperty("lyricUrl", d.lyricUrl)
                addProperty("lyricJsonPath", d.lyricJsonPath)
                addProperty("headers", d.headers)
            })
        }
        return arr.toString()
    }

    fun decode(json: String): List<CustomLyricsSourceDef> = runCatching {
        val arr = JsonParser.parseString(json).asJsonArray
        (0 until arr.size()).mapNotNull { i ->
            val o = arr.get(i).takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            fun str(k: String): String =
                o.get(k)?.takeIf { it.isJsonPrimitive }?.asString.orEmpty()
            val def = CustomLyricsSourceDef(
                id = str("id"),
                name = str("name"),
                searchUrl = str("searchUrl"),
                idJsonPath = str("idJsonPath"),
                lyricUrl = str("lyricUrl"),
                lyricJsonPath = str("lyricJsonPath"),
                headers = str("headers"),
            )
            // 脏行(缺关键字段)直接丢——半截配置会让搜索链路莫名其妙地失败
            if (def.id.isBlank() || def.name.isBlank() || def.searchUrl.isBlank() ||
                def.idJsonPath.isBlank() || def.lyricUrl.isBlank() || def.lyricJsonPath.isBlank()
            ) null else def
        }
    }.getOrDefault(emptyList())

    /** 附加请求头文本 → 键值对(每行 `Key: Value`,忽略空行/无冒号行)。 */
    fun parseHeaders(text: String): List<Pair<String, String>> =
        text.lineSequence().mapNotNull { line ->
            val i = line.indexOf(':')
            if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
        }.filter { it.first.isNotBlank() && it.second.isNotBlank() }.toList()

    /**
     * 求值 URL 模板。`{key}` → 值(URL 编码);未知占位符原样保留(便于用户看出填错)。
     */
    fun fillTemplate(template: String, vars: Map<String, String>): String =
        Regex("""\{(\w+)}""").replace(template) { m ->
            vars[m.groupValues[1]]?.let { enc(it) } ?: m.value
        }

    /**
     * 按路径从 JSON 里取值(字符串化)。支持 `a.b[0].c`:
     *  - `.name` 取对象字段;`[n]` 取数组下标;
     *  - 取到就转成字符串(数字/布尔也转),取不到/类型不符返回 null。
     */
    fun resolvePath(root: JsonElement?, path: String): String? {
        if (root == null || root.isJsonNull || path.isBlank()) return null
        var cur: JsonElement = root
        // 归一成 token 序列: a.b[0].c → ["a","b","0","c"]
        val tokens = Regex("""([^.\[\]]+)|\[(\d+)]""").findAll(path).map {
            it.groupValues[1].ifBlank { it.groupValues[2] }
        }.filter { it.isNotBlank() }.toList()
        for (t in tokens) {
            cur = when {
                cur.isJsonObject -> cur.asJsonObject.get(t) ?: return null
                cur.isJsonArray -> {
                    val idx = t.toIntOrNull() ?: return null
                    val arr = cur.asJsonArray
                    if (idx in 0 until arr.size()) arr.get(idx) else return null
                }
                else -> return null
            }
            if (cur.isJsonNull) return null
        }
        return if (cur.isJsonPrimitive) cur.asString else null
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8")
}
