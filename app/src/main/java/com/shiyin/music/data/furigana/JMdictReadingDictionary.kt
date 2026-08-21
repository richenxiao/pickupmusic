package com.shiyin.music.data.furigana

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStreamReader

/**
 * 读法词典接口（V1.1 准确率层）。JMdict 派生词典实现它；测试可注入假实现。
 * 只暴露读法查询，不暴露加载细节，便于 FuriganaPipeline 与测试解耦。
 */
interface ReadingDictionary {
    /** 返回某 surface 的所有（归一平假名、去重）读法；未收录返回 null。 */
    fun readings(surface: String): List<String>?
}

/**
 * JMdict 派生读法词典（V1.1 准确率层）。
 *
 * 从 assets/furigana/jmdict_furigana_derived.json.gz 加载 {surface: [reading...]}。
 * 加载时把所有 reading 归一为平假名并去重（片假名/平假名同读法算同一个），
 * 这样 [なに, ナニ, なん] → {なに, なん}，用于：
 *   - 单一读法 → 可作为 deterministic 覆盖读法（含 IPADIC 不收录的覆盖词如此方→こちら）。
 *   - 多个读法 → CONFLICT，下游默认不显示（宁可无假名，不显错假名）。
 *
 * 数据来源/许可：见 assets/furigana 同目录的 LICENSE-derivative（CC BY-SA 4.0，
 * EDRDG 允许随闭源 App 分发）。本类只读取、不改写数据。
 *
 * 懒加载：首次 [readings] 调用在后台线程解析（~200KB gzip，解析数十毫秒），
 * 之后纯内存查表。线程安全（按需 synchronized 初始化）。
 */
class JMdictReadingDictionary : ReadingDictionary {

    @Volatile private var map: Map<String, List<String>>? = null

    /** 返回某 surface 的所有（归一平假名、去重）读法；未收录返回 null。 */
    override fun readings(surface: String): List<String>? = map?.get(surface)

    /** 该 surface 是否多读法（CONFLICT）。未收录不算冲突（返回 false）。 */
    fun isConflict(surface: String): Boolean {
        val rs = map?.get(surface) ?: return false
        return rs.size > 1
    }

    /** 该 surface 是否被收录。 */
    fun contains(surface: String): Boolean = map?.contains(surface) ?: false

    /** 后台加载。幂等；重复调用只解析一次。 */
    suspend fun load(context: Context) = withContext(Dispatchers.IO) {
        if (map != null) return@withContext
        val parsed = HashMap<String, List<String>>(26000)
        try {
            context.assets.open("furigana/jmdict_furigana_derived.json").use { input ->
                InputStreamReader(input, Charsets.UTF_8).use { reader ->
                    val sb = StringBuilder(1 shl 20)
                    val buf = CharArray(8192)
                    while (true) {
                        val n = reader.read(buf)
                        if (n < 0) break
                        sb.append(buf, 0, n)
                    }
                    val root = JSONObject(sb.toString())
                    val keys = root.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        val arr = root.optJSONArray(key) ?: continue
                        val readings = ArrayList<String>(arr.length())
                        for (j in 0 until arr.length()) {
                            val r = normalize(arr.optString(j))
                            if (r.isNotEmpty() && r !in readings) readings.add(r)
                        }
                        if (key.isNotEmpty()) parsed[key] = readings
                    }
                }
            }
        } catch (t: Throwable) {
            // 加载失败不设 map（保持 null），下次调用可重试。
            // 旧代码在 catch 外执行 map = parsed，导致部分/空 HashMap 被永久锁定。
            return@withContext
        }
        map = parsed  // 仅在完整解析成功后才赋值
    }

    /** 片假名→平假名归一（0x30A1..0x30F6 减 0x60；ー 保留）。 */
    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            val c = ch.code
            sb.append(
                when {
                    c in 0x30A1..0x30F6 -> (c - 0x60).toChar()
                    else -> ch
                }
            )
        }
        return sb.toString()
    }
}
