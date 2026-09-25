package com.shiyin.music.data.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlin.math.abs

/**
 * Online lyric matching, per handoff: query by title + artist (+album) + duration
 * with a ±2s duration tolerance. Sources rotate LRCLIB → 网易云音乐.
 */
object LyricsFetcher {
    val SOURCES = listOf("LRCLIB", "网易云音乐")
    private const val UA = "Shiyin/1.0 (Android local music player)"
    // v1.3.x 2s→5s:±2s 把"搜到正确歌曲"误判为无结果(用户实测网易云明明有歌词却报
    // 无——本地 tag/VBR 头时长与官方曲库录制时长差 3-5s 是常态:intro 差异、首段静音、
    // ms→s 截断舍入再吃 ±1s)。放宽后:搜到正确歌曲不再被时长门否决。5s 仍显著低于
    // live/广播剪辑/混音版与原版的典型时长差(≥8s),不会误配到错误版本。
    private const val DURATION_TOLERANCE_SEC = 5

    /** 括号补充说明(「晴天 (Live)」「歌名【remix】」)——归一时剥掉,只编译一次。 */
    private val BRACKET_SUFFIX = Regex("[(（\\[【].*?[)）\\]】]")

    /** Returns raw lyric text (LRC or plain) or null when no match. */
    suspend fun fetch(source: String, title: String, artist: String, album: String, durationSec: Long): String? =
        withContext(Dispatchers.IO) {
            try {
                when (source) {
                    "LRCLIB" -> fetchLrclib(title, artist, album, durationSec)
                    "网易云音乐" -> fetchNetease(title, artist, durationSec)
                    else -> null
                }
            } catch (_: Exception) {
                null
            }
        }

    private fun http(url: String, referer: String? = null): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        return try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", UA)
            if (referer != null) conn.setRequestProperty("Referer", referer)
            if (conn.responseCode !in 200..299) null
            else conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun pickLyric(o: JSONObject): String? {
        val synced = o.optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
        val plain = o.optString("plainLyrics").takeIf { it.isNotBlank() && it != "null" }
        return synced ?: plain
    }

    private fun fetchLrclib(title: String, artist: String, album: String, durationSec: Long): String? {
        // Exact match endpoint first.
        val albumParam = if (album.isNotBlank() && album != "—") "&album_name=${enc(album)}" else ""
        http(
            "https://lrclib.net/api/get?track_name=${enc(title)}&artist_name=${enc(artist)}" +
                albumParam + "&duration=$durationSec"
        )?.let { body ->
            pickLyric(JSONObject(body))?.let { return it }
        }
        // Search fallback with duration tolerance.
        val body = http("https://lrclib.net/api/search?track_name=${enc(title)}&artist_name=${enc(artist)}") ?: return null
        val arr = JSONArray(body)
        var best: String? = null
        var bestScore = Long.MAX_VALUE
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val lyric = pickLyric(o) ?: continue
            val d = o.optLong("duration", -1)
            val diff = if (d >= 0 && durationSec > 0) abs(d - durationSec) else Long.MAX_VALUE / 2
            val syncedBonus = if (o.optString("syncedLyrics").isNotBlank() && o.optString("syncedLyrics") != "null") 0 else 1000
            val score = diff + syncedBonus
            if (diff <= DURATION_TOLERANCE_SEC && score < bestScore) {
                bestScore = score
                best = lyric
            }
        }
        if (best == null && arr.length() > 0) {
            // No duration-close hit: accept the top result only if we had no duration info.
            if (durationSec <= 0) best = pickLyric(arr.getJSONObject(0))
        }
        return best
    }

    private fun fetchNetease(title: String, artist: String, durationSec: Long): String? {
        val q = enc("$title $artist".trim())
        val body = http(
            "https://music.163.com/api/search/get/web?s=$q&type=1&limit=10&offset=0",
            referer = "https://music.163.com",
        ) ?: return null
        val songs = JSONObject(body).optJSONObject("result")?.optJSONArray("songs") ?: return null
        // v1.3.x 鉴权(用户反馈⑧:阴三儿《爽歪歪》vs 大张伟《爽歪歪》同名不同歌,
        // 旧实现只按时长差挑 bestId——同名不同歌手的版本时长恰好更接近时就配错)。
        // 新规则:
        //  1. **歌手名是主判据**——返回歌曲的 artists 与本地 artist 模糊匹配
        //     (normalize 后 contains 任一方向),不匹配的候选直接拒绝;
        //  2. 时长差只做**同歌手候选内的决胜**,不再跨歌手比时长;
        //  3. 不过度追求完整匹配:同歌手候选里时长差 ≤10s 就收(本地 tag 时长
        //     与官方录制差 3-5s 是常态,收窄回 5s 又会把正确歌曲误判为无结果);
        //     全部 >10s 时才回退"无时长信息凭最高分"的旧行为(保持兜底,不空手)。
        //  4. title 也做弱校验:normalize 后有交集才收(防同名不同歌的最后一道闸)。
        val wantNorm = normText("$artist $title")
        val artistNorm = normText(artist)
        data class Cand(val id: Long, val diff: Long, val artistHit: Boolean, val titleHit: Boolean)
        val cands = ArrayList<Cand>()
        for (i in 0 until songs.length()) {
            val s = songs.getJSONObject(i)
            // 搜索返回的 artists: [{name:"歌手"}...],取全部名字归一后判交集
            val retArtists = s.optJSONArray("artists") ?: continue
            val retNames = (0 until retArtists.length()).mapNotNull { k ->
                retArtists.getJSONObject(k).optString("name").takeIf { it.isNotBlank() }
            }
            val retNameNorm = retNames.joinToString(" ") { normText(it) }
            val artistHit = artistNorm.isNotBlank() &&
                (retNameNorm.contains(artistNorm) || artistNorm.contains(retNameNorm) ||
                    retNames.any { normText(it).contains(artistNorm) || artistNorm.contains(normText(it)) })
            val retTitle = s.optString("name").takeIf { it.isNotBlank() } ?: continue
            val titleHit = normText(retTitle).contains(normText(title).take(6)) ||
                normText(title).contains(normText(retTitle).take(6))
            val durSec = s.optLong("duration", -1) / 1000
            val diff = if (durationSec > 0 && durSec > 0) abs(durSec - durationSec) else Long.MAX_VALUE / 2
            cands += Cand(s.optLong("id", -1), diff, artistHit, titleHit)
        }
        // 挑选优先级:歌手+歌名都命中 > 歌手命中 > **本地没有歌手信息时**退回按歌名+时长。
        // 第三档是必需的兜底:本地 tag 没填歌手是常见情况,若此时仍硬性要求 artistHit,
        // 候选会被全部否决 → 整首歌词永远搜不到(实测回归:注释承诺的兜底并未实现)。
        // 注意只在 artistNorm 为空时才退——本地**有**歌手却没一个候选对得上,那是
        // 「同名不同歌」的强信号(如阴三儿《爽歪歪》vs 大张伟《爽歪歪》),必须拒绝。
        val picked = cands.filter { it.artistHit && it.titleHit }.minByOrNull { it.diff }
            ?: cands.filter { it.artistHit }.minByOrNull { it.diff }
            ?: if (artistNorm.isBlank()) cands.filter { it.titleHit }.minByOrNull { it.diff } else null
        if (picked == null || picked.id < 0) return null
        // 歌手命中的候选里,时长差 >10s 且本地有时长 → 拒绝(live 版/混音版风险)
        if (durationSec > 0 && picked.diff > 10 && picked.diff != Long.MAX_VALUE / 2) return null
        // v1.3.x yrc:部分新歌歌词只有逐字格式(yrc 字段),旧 lv=1 的 lrc 为空串——
        // 客户端展示的滚动歌词来自 yrc,只读 lrc 会把"明明有歌词"误报为无结果
        // (实测:《虎头奔》揽佬SKAI ISYOURGOD id=2080172427)。yv=1/yrv=1 请求 yrc,
        // lrc 空时解析 yrc 转标准 LRC。
        val lyricBody = http(
            "https://music.163.com/api/song/lyric?id=${picked.id}&lv=1&kv=-1&tv=-1&yv=1&yrv=1",
            referer = "https://music.163.com",
        ) ?: return null
        val obj = JSONObject(lyricBody)
        val lrc = obj.optJSONObject("lrc")?.optString("lyric")?.takeIf { it.isNotBlank() }
        if (lrc != null) return lrc
        val yrc = obj.optJSONObject("yrc")?.optString("lyric")?.takeIf { it.isNotBlank() } ?: return null
        return yrcToLrc(yrc) ?: return null
    }

    /** 归一化文本:剥括号注释/空白,lowercase——与 MainViewModel 的 norm 同一逻辑。 */
    /**
     * 归一化:剥括号补充说明(「晴天 (Live)」→「晴天」),再交给 FuzzySearch.normalize
     * 做**全角→半角 + 繁体→简体 + 去空格 + 小写**。
     *
     * ⚠️ 必须复用 FuzzySearch 而不是自己 lowercase():歌手/歌名里「周杰倫 vs 周杰伦」
     * 「Jay Chou vs 周杰倫」这类差异只靠小写消不掉,而匹配的 artistHit 是硬闸门——
     * 归一旦对不上,整首歌词就永远搜不到(实测回归)。
     * 正则提成 object 级常量:原实现每次调用都 Pattern.compile 两遍,
     * 20 条候选 × 多个字段 ≈ 每搜一次 200+ 次编译。
     */
    private fun normText(s: String): String =
        com.shiyin.music.data.search.FuzzySearch.normalize(BRACKET_SUFFIX.replace(s, ""))

    /**
     * v1.3.x: 网易云逐字歌词(yrc)转标准 LRC。
     * yrc 行格式: [行起始ms,行持续ms](字起始ms,字持续ms,0)字(字起始ms,字持续ms,0)字…
     * 行头 [start,dur] 是行时间轴,后续每个 (start,dur,0) 后跟该字文本(可含空格)。
     * 转换: 取行起始 ms 作为 LRC 时间戳,把行内所有 (…) 标记剥掉、字文本顺序拼接。
     */
    private fun yrcToLrc(yrc: String): String? {
        val out = StringBuilder()
        val lineTag = Regex("""^\[(\d+),(\d+)\]""")
        val wordTag = Regex("""\((\d+),(\d+),\d+\)""")
        var anyLine = false
        for (line in yrc.lines()) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue
            val m = lineTag.find(trimmed) ?: continue
            val startMs = m.groupValues[1].toLong()
            // 剥行头后按 (start,dur,0) 标记切分,标记间的文本就是字内容
            val rest = trimmed.substring(m.range.last + 1)
            val text = wordTag.replace(rest, "").trim()
            if (text.isEmpty()) continue
            val min = startMs / 60_000
            val sec = (startMs % 60_000) / 1000
            val cs = (startMs % 1000) / 10
            out.append('[').append(min).append(':')
                .append(if (sec < 10) "0$sec" else "$sec").append('.')
                .append(if (cs < 10) "0$cs" else "$cs").append(']').append(text).append('\n')
            anyLine = true
        }
        return if (anyLine) out.toString() else null
    }
}
