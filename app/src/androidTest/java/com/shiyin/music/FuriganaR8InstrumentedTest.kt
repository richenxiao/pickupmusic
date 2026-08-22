package com.shiyin.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shiyin.music.data.lyrics.FuriganaTokenizer
import com.shiyin.music.ui.components.RubySegment
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * v1.2.0 阶段一回归：在模拟器/真机上、走 release 变体（经 R8）验证 Kuromoji
 * 振假名词表加载与分词未受 R8 裁剪影响。Kuromoji 内部按字符串反射加载 IPADIC
 * 词表条目与特征类，是 R8 最高风险点；若被误删，tokenize 抛异常被调用方
 * catch 吞掉 → toSegments 退化为单段无 reading 的兜底。本测试断言「音楽」
 * 能正常分词、surface 拼回原文、且产出振假名读法 —— 三者全过才证明 Kuromoji
 * 在 R8 后运行时完好。
 *
 * 跑法：./gradlew connectedReleaseAndroidTest（release 变体经 R8，验证 R8）。
 */
@RunWith(AndroidJUnit4::class)
class FuriganaR8InstrumentedTest {

    @Test
    fun kuromoji_loadsDictionaryAndTokenizesKanji() {
        // 首次调用触发 lazy Tokenizer() 初始化 —— 真正加载 IPADIC 词表（反射路径）。
        val segs: List<RubySegment> = FuriganaTokenizer.toSegments("音楽")

        assertTrue("segments should not be empty", segs.isNotEmpty())

        // surface 必须拼回原文：分词成功、未被 R8 破坏内部连接。
        val surface = segs.joinToString("") { it.surface }
        assertTrue("surface should reconstruct '音楽' but was '$surface'", surface == "音楽")

        // 「音楽」应产出读法（おんがく）—— 证明 reading 提取 + 假名转换链路完好。
        // 若 Kuromoji 被 R8 伤到，tokenize 返回 null → 退化为单段无 reading → 此断言失败。
        val hasReading = segs.any { !it.reading.isNullOrBlank() }
        assertTrue("at least one segment should carry a furigana reading", hasReading)
    }

    @Test
    fun kuromoji_handlesMixedLineWithoutCrash() {
        // 混合行（汉字+假名）不应崩，surface 拼回原文（覆盖更真实歌词的形态）。
        val line = "今日は音楽を聴く"
        val segs = FuriganaTokenizer.toSegments(line)
        val surface = segs.joinToString("") { it.surface }
        assertTrue("mixed line surface should reconstruct input but was '$surface'", surface == line)
    }

    @Test
    fun gson_jsonParser_parsesItunesStyleJsonUnderR8() {
        // 验证 Gson JsonParser 树解析在 R8 release 变体下未受影响。
        // 生产中 iTunes/MusicBrainz/DeepSeek 的封面·歌手·歌词数据均经此路径。
        val json = """{"resultCount":1,"results":[{"artworkUrl100":"https://example/cover.jpg","collectionName":"音楽","collectionType":"Album"}]}"""
        val obj = com.google.gson.JsonParser.parseString(json).asJsonObject
        val results = obj.getAsJsonArray("results")
        assertTrue("results array present", results != null && results!!.size() == 1)
        val art = results!![0].asJsonObject.get("artworkUrl100")?.asString
        assertTrue("artworkUrl100 parsed: $art", art == "https://example/cover.jpg")
    }
}
