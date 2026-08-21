package com.shiyin.music.data.furigana.external

/**
 * 外部带假名歌词来源产出一个 occurrence 级读法（V1.1+ 外部 evidence 层）。
 *
 * 粒度严格为 occurrence：同一 surface（如「何」）多次出现、读法不同时，各 occurrence
 * 独立。key = (lineIndex, charStart, length)：本地歌词第 lineIndex 行、charStart 字符起、
 * length 字符长的 kanji run，其读法为 [reading]。与 position-keyed Song Override 同坐标系，
 * 可叠加（用户 override 优先级仍最高）。
 */
data class OccurrenceReading(
    val lineIndex: Int,   // 本地歌词行号（0-based）
    val charStart: Int,   // 该 kanji run 在该行中的起始字符偏移
    val length: Int,      // 该 kanji run 字符数
    val reading: String,  // 平假名读法
    val source: String,   // evidence 来源（provider 名 + matched song）
)

/** 外部歌词解析结果：flat surface（<br>→\n，空格保留）+ 各 kanji run 的读法。 */
data class ParsedExternalLyric(
    val surface: String,
    val runs: List<ExtRun>,
) {
    /** 一个 kanji run 在 surface 中的 [start, start+length) 及其读法。 */
    data class ExtRun(val startInSurface: Int, val length: Int, val reading: String)
}

/**
 * 外部带假名歌词来源 Provider 接口。Android 侧实现 HTTP 抓取 + 解析 + 对齐；
 * 测试可注入假实现或直接用 [UtaTenParser]+[LyricAligner] 跑 E2E。
 *
 * 不把网页抓取写进 FuriganaEngine——Provider 独立，多 Provider fallback。
 */
interface ExternalReadingProvider {
    val id: String
    /**
     * 搜索并匹配歌曲，获取带假名歌词，解析并与本地歌词对齐，产出 occurrence 级读法。
     * @param title/artist 本地歌曲 metadata（用于搜索匹配）
     * @param localLyricLines 本地歌词各行文本（LRC 去时间戳后的纯文本）
     * @return 对齐成功的 occurrence 读法列表；fetch/匹配/对齐失败返回 null（安全 fallback）
     */
    suspend fun resolve(title: String, artist: String, localLyricLines: List<String>): List<OccurrenceReading>?
}
