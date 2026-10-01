package com.shiyin.music.data.ai

import com.google.gson.JsonParser
import com.shiyin.music.data.search.FuzzySearch

/**
 * v1.3.14:推荐选曲的**纯逻辑层**(零 Android、零 IO、零网络,全部可脱机单测)。
 *
 * 分工(用户定调,别搞反):
 *  - **硬约束**(歌手/专辑)**由代码保证** —— 候选池只在该歌手/专辑范围内取,不靠模型自觉;
 *  - **软约束**(适不适合"睡觉"这个场景)**由模型判断** —— 代码只把候选递过去、做合法性校验;
 *  - **降级必须显式** —— 任何"没找到合适的"都要如实说,禁止静默退化成热度排序。
 *
 * 本文件只放四个纯函数 + 它们需要的最小数据结构;上限用默认参数给(不引入配置对象):
 *  - [resolveArtists] 歌手解析(模型参数优先,代码只在原话里兜底扫描)
 *  - [buildCandidatePool] 候选池(去重、上限、多样性)
 *  - [parsePickResult] 模型选曲输出解析与校验
 *  - [mergeIntent] 多轮槽位继承(带 TTL)
 */

/** 解析到的库内歌手实体。 */
data class ResolvedArtist(val id: Long, val name: String)

/** [resolveArtists] 的结果。 */
data class ArtistResolution(
    val artists: List<ResolvedArtist>,
    /** 用户点了名但库里没有的歌手原文(非空 → 上层如实回"库里没有",**不降级**)。 */
    val unresolved: String? = null,
    /** 库里没有时的近似建议(最多 3 条),用于"你是不是想找:…"。 */
    val suggestions: List<String> = emptyList(),
)

/** 候选曲目(送给模型的最小信息)。[no] 只在本次选曲内有效,不持久化。 */
data class RecCandidate(val no: Int, val trackId: Long, val title: String, val artist: String, val album: String?)

/** 候选池的输入轨道(playCount 用于"最常听优先"与保底)。 */
data class PoolTrack(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String?,
    val playCount: Int = 0,
)

/** 模型判断的整体契合度。 */
enum class Fit { STRONG, WEAK, NONE }

/** 模型给出的一条选择。 */
data class RecPick(val no: Int, val why: String)

/** [parsePickResult] 的结果:要么可用,要么给出**可诊断的原因**(上层据此重试/显式降级)。 */
sealed interface PickOutcome {
    data class Ok(val fit: Fit, val picks: List<RecPick>) : PickOutcome
    data class Fail(val reason: String) : PickOutcome
}

/** 上一轮选曲意图(内存态,不落库)。 */
data class RecIntent(
    val scene: String?,
    val artist: String?,
    val album: String?,
    /** 产生该意图的用户消息序号。 */
    val turnIndex: Int,
    val timestampMs: Long,
)

/** [mergeIntent] 的结果。 */
data class MergedIntent(
    val scene: String?,
    val artist: String?,
    val album: String?,
    /** true = 场景是**代码**从上一轮继承来的(不是模型/用户本轮给的)。 */
    val inheritedScene: Boolean = false,
)

object RecommendationPlan {

    /** 单条模糊匹配的最低分(低于它宁可不认)。 */
    private const val MIN_FUZZY_SCORE = 400

    /** 模型选曲上限(与卡片可点选项数一致)。 */
    const val MAX_PICKS = 5

    /** 理由字段上限(字符)。 */
    const val WHY_MAX_CHARS = 30

    // ── ① 歌手解析 ─────────────────────────────────────────────────────────

    /**
     * 把"用户提到的歌手"解析成库内实体。
     *
     * 顺序(用户定调):**模型参数优先**,代码只在原话里**兜底扫描**。
     *  - [modelArtist] 非空 → 三级匹配(全等 → 包含 → 模糊)。**2 字及以下也认** ——
     *    既然是模型明确给的参数,就按它算;
     *  - [modelArtist] 为空/没命中 → 扫原话:库内歌手名(归一化后)长度须 ≥ [minScanLen]
     *    (默认 3)。**2 字歌手不参与扫描** —— 误命中率太高(「夜的第七章」里的"夜"这类);
     *  - 都没命中且模型给了名字 → `unresolved` = 该名字 + 近似建议,由上层如实告知。
     */
    fun resolveArtists(
        modelArtist: String?,
        userMessage: String,
        libraryArtists: List<Pair<Long, String>>,
        minScanLen: Int = 3,
        maxArtists: Int = 3,
    ): ArtistResolution {
        val lib = libraryArtists.map { (id, name) -> ResolvedArtist(id, name) to FuzzySearch.normalize(name) }
            .filter { it.second.isNotEmpty() }

        // ── 模型参数优先 ──
        val model = modelArtist?.trim().orEmpty()
        if (model.isNotEmpty()) {
            val target = FuzzySearch.normalize(model)
            if (target.isNotEmpty()) {
                // 注意:三个分支必须统一成 List<ResolvedArtist> ——
                // 之前模糊分支返回 Pair<.., Int>、其余返回 Pair<.., String>,
                // 分支类型不一致会让 hit 无法推断(编译期报"cannot infer type")。
                val exact: List<ResolvedArtist> = lib.filter { it.second == target }.map { p -> p.first }
                val hit: List<ResolvedArtist> = if (exact.isNotEmpty()) {
                    exact
                } else {
                    val contains = lib.filter {
                        val shorter = minOf(it.second.length, target.length)
                        shorter >= 2 && (it.second.contains(target) || target.contains(it.second))
                    }
                    if (contains.isNotEmpty()) contains.map { p -> p.first }
                    else lib.mapNotNull { p ->
                        val score = FuzzySearch.match(target, p.second)?.score ?: 0
                        if (score >= MIN_FUZZY_SCORE) p.first to score else null
                    }.sortedByDescending { pair -> pair.second }.map { pair -> pair.first }
                }
                if (hit.isNotEmpty()) {
                    return ArtistResolution(hit.take(maxArtists))
                }
            }
            // 模型给了名字但库里没有 → 明确 unresolved(+近似建议),**不降级到别的歌手**
            val suggestions = lib.mapNotNull { p ->
                val score = FuzzySearch.match(target, p.second)?.score ?: 0
                if (score >= MIN_FUZZY_SCORE) p.first.name to score else null
            }.sortedByDescending { pair -> pair.second }.map { pair -> pair.first }.take(3)
            return ArtistResolution(emptyList(), unresolved = model, suggestions = suggestions)
        }

        // ── 代码兜底扫描原话(仅 ≥ minScanLen 的歌手名)──
        val msg = FuzzySearch.normalize(userMessage)
        if (msg.isEmpty()) return ArtistResolution(emptyList())
        val scanned = lib.filter { it.second.length >= minScanLen && msg.contains(it.second) }
        // 命中多个互不包含的歌手时都保留(最多 maxArtists);被包含的更长者优先
        val chosen = scanned.map { it.first }
            .sortedByDescending { FuzzySearch.normalize(it.name).length }
            .take(maxArtists)
        return ArtistResolution(chosen)
    }

    // ── ② 候选池 ───────────────────────────────────────────────────────────

    /**
     * 构建候选池:去重 → (可选)同歌手多样性上限 → 上限截断 → 编号。
     *
     * @param cap 上限。有歌手限定传 80;仅场景传 150(调用方按场景给)。
     * @param perArtistCap 非 null 时启用"同歌手最多几首"(仅"无歌手限定"的场景用,默认 2);
     *                     有歌手限定时**不要**传 —— 那正是用户点名要的歌手,不该被削。
     * @param topFirst 超出上限时**先保底**的最常听条数(其余按专辑均匀抽样补足)。
     *
     * 输入会按 playCount 降序自行排序(不依赖调用方);同 id 只保留一条。
     */
    fun buildCandidatePool(
        tracks: List<PoolTrack>,
        cap: Int,
        perArtistCap: Int? = null,
        topFirst: Int = 40,
    ): List<RecCandidate> {
        if (tracks.isEmpty() || cap <= 0) return emptyList()
        val sorted = tracks.distinctBy { it.id }.sortedByDescending { it.playCount }
        val filtered = if (perArtistCap == null) sorted else {
            val used = HashMap<String, Int>()
            sorted.filter { t ->
                val n = used[t.artist] ?: 0
                if (n >= perArtistCap) false else { used[t.artist] = n + 1; true }
            }
        }
        val picked = if (filtered.size <= cap) filtered else {
            val head = filtered.take(minOf(topFirst, cap))
            val rest = filtered.drop(head.size)
            // 其余按专辑**均匀抽样**(轮流取,避免整批都来自同一张专辑)
            val byAlbum = LinkedHashMap<String?, ArrayDeque<PoolTrack>>()
            rest.forEach { byAlbum.getOrPut(it.album) { ArrayDeque() }.addLast(it) }
            val tail = ArrayList<PoolTrack>(cap - head.size)
            while (tail.size < cap - head.size && byAlbum.values.any { it.isNotEmpty() }) {
                for (q in byAlbum.values) {
                    if (q.isEmpty()) continue
                    tail += q.removeFirst()
                    if (head.size + tail.size >= cap) break
                }
            }
            head + tail
        }
        return picked.mapIndexed { i, t ->
            RecCandidate(no = i + 1, trackId = t.id, title = t.title, artist = t.artist, album = t.album)
        }
    }

    // ── ③ 模型选曲输出解析 ─────────────────────────────────────────────────

    /**
     * 解析模型输出:`{"fit":"strong|weak|none","picks":[{"i":序号,"why":"理由"}]}`。
     *
     * 容错与校验(单条坏 pick 只丢它自己,不整体失败):
     *  - 允许 ```json 围栏与前后多余文字(取第一个 `{` 到最后一个 `}`);
     *  - `fit` 必须是三值之一(大小写不敏感);
     *  - `i` 必须是整数且在 `1..candidateCount`,越界/重复**丢弃并计数**;
     *  - `why` 去空白并截断到 [WHY_MAX_CHARS];为空则留空;
     *  - `fit` 非 none 但一条有效 pick 都没有 → [PickOutcome.Fail]`EMPTY_PICKS`;
     *  - `fit=none` 合法,picks 可为空。
     */
    fun parsePickResult(raw: String, candidateCount: Int): PickOutcome {
        val text = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) return PickOutcome.Fail("INVALID_JSON")
        val obj = runCatching { JsonParser.parseString(text.substring(start, end + 1)).asJsonObject }.getOrNull()
            ?: return PickOutcome.Fail("INVALID_JSON")

        val fitRaw = obj.get("fit")?.takeIf { it.isJsonPrimitive }?.asString?.trim()?.lowercase()
            ?: return PickOutcome.Fail("BAD_FIT")
        val fit = when (fitRaw) {
            "strong" -> Fit.STRONG
            "weak" -> Fit.WEAK
            "none" -> Fit.NONE
            else -> return PickOutcome.Fail("BAD_FIT")
        }

        val picks = ArrayList<RecPick>()
        val seen = HashSet<Int>()
        obj.getAsJsonArray("picks")?.forEach { el ->
            val o = el?.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
            val no = o.get("i")?.takeIf { it.isJsonPrimitive }?.asInt ?: return@forEach
            if (no !in 1..candidateCount) return@forEach
            if (!seen.add(no)) return@forEach
            val why = o.get("why")?.takeIf { it.isJsonPrimitive }?.asString?.trim().orEmpty()
            picks += RecPick(no, why.take(WHY_MAX_CHARS))
        }

        if (fit != Fit.NONE && picks.isEmpty()) return PickOutcome.Fail("EMPTY_PICKS")
        return PickOutcome.Ok(fit, picks.take(MAX_PICKS))
    }

    // ── ④ 多轮槽位继承 ─────────────────────────────────────────────────────

    /** 意图有效期:最多 4 个用户回合。 */
    const val INTENT_TTL_TURNS = 4

    /** 意图有效期:最多 10 分钟。 */
    const val INTENT_TTL_MS = 10 * 60 * 1000L

    /** "短补充消息"的长度阈值(判定代码是否兜底继承场景)。 */
    const val INHERIT_MSG_MAX_CHARS = 12

    private fun intentValid(prev: RecIntent?, turnIndex: Int, nowMs: Long): Boolean {
        if (prev == null) return false
        if (turnIndex - prev.turnIndex > INTENT_TTL_TURNS) return false
        if (nowMs - prev.timestampMs > INTENT_TTL_MS) return false
        return true
    }

    /**
     * 合并本轮槽位与上一轮意图。
     *
     * 槽位以**本轮**为准;只有一种情况代码兜底继承场景(用户定调,刻意保守):
     * **本轮只给了歌手 + 本轮消息很短(≤ [INHERIT_MSG_MAX_CHARS]) + 上一轮有效且场景非空**。
     * 这正好覆盖「推荐适合睡觉的歌 → 方大同的」。
     *
     * **不继承歌手**:本轮只有场景时(「换个适合跑步的」)无法可靠区分"还是这个歌手"
     * 与"彻底换话题",错继承的代价更大 —— 那由注入提示交给模型判断。
     */
    fun mergeIntent(
        prev: RecIntent?,
        turnIndex: Int,
        nowMs: Long,
        userMessage: String,
        scene: String?,
        artist: String?,
        album: String?,
    ): MergedIntent {
        val s = scene?.takeIf { it.isNotBlank() }
        val a = artist?.takeIf { it.isNotBlank() }
        val al = album?.takeIf { it.isNotBlank() }
        if (s != null) return MergedIntent(s, a, al)          // 本轮给了场景 → 以本轮为准
        val prevOk = intentValid(prev, turnIndex, nowMs)
        val prevScene = prev?.scene?.takeIf { it.isNotBlank() }
        val inherit = prevOk && prevScene != null && a != null &&
            userMessage.trim().length <= INHERIT_MSG_MAX_CHARS
        return MergedIntent(if (inherit) prevScene else null, a, al, inheritedScene = inherit)
    }
}
