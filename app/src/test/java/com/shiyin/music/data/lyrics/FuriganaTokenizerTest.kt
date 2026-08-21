package com.shiyin.music.data.lyrics

import com.atilika.kuromoji.ipadic.Tokenizer
import org.junit.Test

/**
 * 验证 FuriganaTokenizer 的真实分词输出（Kuromoji API + 送假名拆分算法）。
 * 不是断言式单测（读音会随词表版本微调），而是打印各段供人眼核对拼接 / 注音 / 拆分。
 * 运行：gradle :app:testDebugUnitTest --tests "*.FuriganaTokenizerTest"
 * Windows 控制台 mojibake，读 app/build/test-results/testDebugUnitTest/TEST-*.xml 看 UTF-8。
 */
class FuriganaTokenizerTest {

    private fun dump(line: String) {
        val segs = FuriganaTokenizer.toSegments(line)
        val joined = segs.joinToString("") { it.surface }
        val ok = joined == line
        println("\n=== \"$line\"  (拼接${if (ok) "OK" else "失配!"}) ===")
        segs.forEach { s ->
            println("  surface=${s.surface}  reading=${s.reading ?: "—"}")
        }
    }

    /** dump 原始 Kuromoji token：surface / 原始片假名 reading / baseForm / isKnown / POS。
     *  用来定位「気→*」这类异常字符到底来自引擎返回值还是后续转换，以及判断
     *  当て字候选（如魏）的词性是否可用于置信度判定。 */
    private fun dumpRaw(line: String) {
        val tok = Tokenizer()
        val tokens = tok.tokenize(line)
        println("\n### RAW \"$line\" ###")
        for (t in tokens) {
            println("  surface=[${t.surface}] reading=[${t.reading}] baseForm=[${t.baseForm}] isKnown=${t.isKnown} pos1=[${t.partOfSpeechLevel1}] pos2=[${t.partOfSpeechLevel2}]")
        }
    }

    @Test
    fun printSegments() {
        println("\n\n######## FuriganaTokenizer 真实分词输出 ########")
        // M1 demo 的 5 行
        dump("君の名は")
        dump("明日に向かって歩く")
        dump("美しい空の下で")
        dump("雨後の生")
        dump("昨日電車で見かけたあの娘の横顔が忘れられないまま")
        // 送假名拆分 / 熟语 / 中间送假名 边界用例
        dump("思い出")
        dump("美しい")
        dump("歩く")
        dump("帰る")
        dump("教える")
        dump("電車")
        dump("今日")
        dump("人々")
        dump("新しい曲を聴きたい")
        dump("夜明け前")
        // 纯假名 / 英数 / 标点 混排（不应注音）
        dump("Lofi Beats でリラックス")
        dump("♪～終わり～")
        // 用户截图标红的问题行（验证 fix 后无 "*"、気→き）
        dump("危ない")
        dump("前世さん")
        dump("気がするの")
        dump("友情こと游びましょ")
        dump("本当ですか嘘ですか")
        dump("恋していい")
        dump("危")
        // 复合词误切分：此方应读こちら，验证 Kuromoji 是否切成 此+方
        dump("此方")
        dump("此方へ来ておくれ")
        dump("此方へ")
        // V1 新增测试用例
        dump("君の名は")
        dump("真夏")
        dump("明日に向かって歩く")
        dump("貴方")
        dump("此処")
        dump("生きる")
        dump("魏")
        dump("赵")
        dump("魏赵に捧ぐ")
        // 藤井風《何なんw》方言——排查「何」是否产生格式非法 reading
        dump("何なんw")
        dump("何があってもずっと大好きなのに")
        dump("何")
        dump("何なん")
        dump("きか否かで少し悩んでる")
        dump("口にしない方がいい真実もあるから")
        dump("知らない方がよかったなんて言わないでいて")
    }

    @Test
    fun printRawProblemLines() {
        println("\n\n######## 问题行原始 token dump ########")
        // 用户截图里标红的位置
        dumpRaw("危ない")
        dumpRaw("前世さん")
        dumpRaw("気がするの")
        dumpRaw("友情こと游びましょ")
        dumpRaw("本当ですか嘘ですか")
        dumpRaw("恋していい")
        // 额外几个常见单字汉字，确认引擎本身能处理
        dumpRaw("気")
        dumpRaw("危")
        dumpRaw("嘘")
        dumpRaw("恋")
        dumpRaw("前世")
        // 复合词切分：此方
        dumpRaw("此方")
        dumpRaw("此方へ来ておくれ")
        dumpRaw("此方へ")
        dumpRaw("此")
        dumpRaw("方")
        dumpRaw("魏")
        dumpRaw("赵")
        dumpRaw("真夏")
        dumpRaw("貴方")
        dumpRaw("此処")
        dumpRaw("生きる")
        // B 类：已知词但合法多读法（用户真实 V1 测试发现的问题）
        dumpRaw("二人")
        dumpRaw("一人")
        dumpRaw("何ん")
        dumpRaw("何ん")
        dumpRaw("大人")
        dumpRaw("今日")
        dumpRaw("明日")
        dumpRaw("二十歳")
        // 藤井風《何なんw》方言歌词——排查「何」是否产生格式非法的 reading
        dumpRaw("何なんw")
        dumpRaw("何があってもずっと大好きなのに")
        dumpRaw("何")
        dumpRaw("何なん")
        dumpRaw("きか否かで少し悩んでる")
        dumpRaw("口にしない方がいい真実もあるから")
        dumpRaw("知らない方がよかったなんて言わないでいて")
    }
}
