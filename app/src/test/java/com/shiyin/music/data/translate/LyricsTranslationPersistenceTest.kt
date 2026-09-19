package com.shiyin.music.data.translate

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 翻译结果的**持久化载荷契约**测试。
 *
 * `lyrics_translation.payload` 存的是 `Map<Int, String>` 的 Gson 序列化，读写两端分别是
 * `MainViewModel.translateLyricsWithAi`（写）与 `restoreLyricsTranslation`（读）。
 * 这里把同一对调用原样搬过来验证，防止"存进去了但读不回来"——
 * 尤其是 **Int 键**：JSON 对象的键是字符串，Gson 必须能把它还原成 Int，
 * 否则恢复时整批译文会静默丢失（用户看到的就是"翻译没保存"）。
 */
class LyricsTranslationPersistenceTest {

    /** 与 VM 写入端完全一致的序列化。 */
    private fun write(map: Map<Int, String>): String = Gson().toJson(map)

    /** 与 VM 读取端完全一致的反序列化。 */
    private fun read(json: String): Map<Int, String> = Gson().fromJson(
        json,
        object : TypeToken<Map<Int, String>>() {}.type,
    )

    @Test
    fun roundTripsIntKeyedMap() {
        val map = mapOf(0 to "第一行", 1 to "第二行", 5 to "第六行")
        assertEquals(map, read(write(map)))
    }

    /**
     * 钉住线上格式：JSON 对象的键**必然是字符串**（`{"3":"…"}`），而我们的 Map 是
     * `Map<Int,String>`。也就是说"键能还原成 Int"这件事**完全依赖 Gson 的键类型转换**——
     * 换序列化库或改 Map 键类型时，这条断言会第一时间报警。
     */
    @Test
    fun serializedFormUsesStringKeysAsJsonRequires() {
        assertEquals("""{"3":"第三行"}""", write(mapOf(3 to "第三行")))
    }

    /** 译文里可能出现的引号/反斜杠/换行不能破坏 JSON。 */
    @Test
    fun escapesAwkwardCharactersInTranslationText() {
        val map = mapOf(
            0 to """他说"你好"""",
            1 to """反斜杠 C:\path""",
            2 to "第一行\n第二行",
            3 to "emoji 🎵 与全角｜竖线",
        )
        assertEquals(map, read(write(map)))
    }

    /** 缺行是常态（模型漏译 / 对齐失败），空 map 也必须能安全往返。 */
    @Test
    fun roundTripsEmptyMap() {
        assertEquals(emptyMap<Int, String>(), read(write(emptyMap())))
    }

    /** 大歌：行号上百也不能丢。 */
    @Test
    fun roundTripsLongSong() {
        val map = (0 until 160).associateWith { "第 $it 行译文" }
        assertEquals(map, read(write(map)))
    }
}
