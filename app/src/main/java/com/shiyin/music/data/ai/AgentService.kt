package com.shiyin.music.data.ai

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * v1.2.2 Agent 场景一:专辑曲目排序修正编排层。
 *
 * v1.3.3 编号方案(按用户定调的"AI 智能决策"原则重做):prompt 给出本地曲目列表
 * (编号仅为指代)+ 联网资料(参考),让 LLM 输出**按官方顺序排列的本地编号序列**——
 * 即"对哪一首排第几做判断"。官方名与本地写法的差异(feat. 括号/空格/繁简)由 LLM
 * 在分析时理清对应关系;本地不再做任何歌名猜测匹配,零"未识别"。
 *
 * 失败/信息不足 → 返回 null 或 orderedIndices=空,调用方(AlbumSortSkill)据此提示,
 * 不写库。写回走 MainViewModel.applyAlbumOrderIndices:AI 覆盖全部曲目直接落库,
 * 有缺走 pendingOrder 确认窗,未确定的按原相对顺序补尾。
 */
object AgentService {

    enum class Confidence { HIGH, LOW }

    data class SortResult(
        /** 按官方顺序排列的本地索引(0-based,指向调用方传入的 currentTracks)。 */
        val orderedIndices: List<Int>,
        val confidence: Confidence,
        val promptTokens: Int = 0,
        val completionTokens: Int = 0,
        /** v1.3.6: 缓存命中 token(用量面板)。 */
        val cacheTokens: Int = 0,
        /** v1.3.2: 联网是否真的搜到了资料(决定"无法确定"时给用户哪种提示)。 */
        val webSearched: Boolean = false,
        /**
         * v1.3.15: 本次喂给模型的**联网资料原文**(未搜到则 null)。
         * 诊断用:定性"排序失败怪联网还是怪模型"必须让资料与模型回复同框(见 AgentDiag.webHead)。
         */
        val webSnippet: String? = null,
        /**
         * v1.3.15: 最后一次调用的**结束原因**(stop/length…)。真机实测《龙年》拿到资料却
         * `titleLines=0`、回复为空 —— "被截断"与"模型就是不回答"是两回事,只有它+用量能分开。
         */
        val finishReason: String? = null,
        /** v1.3.3: 分析那次 LLM 调用的思考链(推理模型才有)——透出给 UI 折叠展示。 */
        val thinking: String? = null,
        /** v1.3.3: 模型明确输出 UNKNOWN(它判断不了)——与"格式不遵守"区分,
         *  用户看到的是不同的真实原因而不是同一句套话。 */
        val hadUnknown: Boolean = false,
        /**
         * v1.3.14:模型这次回复的**原文**(供诊断样本用;只用于排查"它到底回了什么",
         * 由调用方经 AgentDiag 脱敏+截断后落盘)。
         */
        val rawReply: String? = null,
    )

    /**
     * @param config    当前激活的 LLM 提供商配置(含 key/endpoint/model,见 LlmClient)
     * @param tavilyKey Tavily API key(空则无联网资料 → confidence 必为 LOW)
     * @param album     专辑名
     * @param artist    歌手名(已过 lib() override 叠加)
     * @param currentTracks 本地现有曲目标题列表(编号方案的重排对象)
     * @param onStep    步骤回调(AlbumSortSkill 起头 search RUNNING,本函数推进 DONE/analyze)
     * @return 排序结果;null=调用失败(LLM 不可达/未配 key);orderedIndices 空=信息不足
     */
    suspend fun fetchOfficialTrackOrder(
        config: LlmProviderConfig,
        tavilyKey: String,
        album: String,
        artist: String,
        currentTracks: List<String>,
        onStep: (String, com.shiyin.music.data.ai.AgentEngine.StepStatus) -> Unit = { _, _ -> },
    ): SortResult? = withContext(Dispatchers.IO) {
        if (!config.isConfigured || currentTracks.isEmpty()) return@withContext null

        // 1. Tavily 联网搜官方曲目顺序资料。主/备查询并行发——总耗时 = max(两查询)。
        //    备用查询去掉括号副标题(如《X (Deluxe Edition)》),整串搜无结果时兜底。
        //    v1.3.3: 多语言关键词——日语专辑用日语关键词,中文用中文,英文用英文,
        //    否则日语专辑搜不到(中文关键词对日语内容无效)。
        val queryLang = detectLanguage(album + artist)
        val (primaryQuery, altQuery) = when (queryLang) {
            "ja" -> "$album $artist アルバム 曲目 順番 tracklist" to "$album $artist アルバム 曲目 順番 tracklist"
            "zh" -> "$album $artist 专辑 曲目顺序 tracklist" to "$album $artist 专辑 曲目顺序 tracklist"
            else -> "$album $artist album tracklist order" to "$album $artist album tracklist order"
        }
        val tavilyContext = coroutineScope {
            val primary = async {
                runCatching {
                    com.shiyin.music.data.ai.TavilyService.search(tavilyKey, primaryQuery)
                }.getOrNull()
            }
            val altAlbum = album.replace(Regex("[(（].*[)）]"), "").trim()
            val altArtist = artist.replace(Regex("[(（].*[)）]"), "").trim()
            val alt = if (altAlbum != album || altArtist != artist) async {
                runCatching {
                    com.shiyin.music.data.ai.TavilyService.search(tavilyKey, altQuery)
                }.getOrNull()
            } else null
            primary.await()?.takeIf { it.isNotBlank() }
                ?: alt?.await()?.takeIf { it.isNotBlank() }
        }
        // 搜到资料才标 DONE;没搜到标 FAILED(✗)——面板必须诚实。
        // v1.3.3: Tavily 空是"没搜到资料"业务失败,不是 LLM 错误——清掉残留的
        // lastError,免得步骤行误显示上一轮的 LLM 报错。
        if (tavilyContext.isNullOrBlank()) com.shiyin.music.data.ai.LlmClient.clearLastError()
        onStep("search", if (tavilyContext.isNullOrBlank()) AgentEngine.StepStatus.FAILED else AgentEngine.StepStatus.DONE)

        // 2. 拼 prompt:编号方案。v1.3.3 精简——推理模型思考时长与 prompt 复杂度
        //    正相关:角色设定删掉(不必要的人格铺垫)、要求合并成 3 条硬约束、
        //    输出示例直接给(模型照抄格式,不用推理输出形态)。
        val localBlock = currentTracks.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")
        val contextBlock = if (tavilyContext.isNullOrBlank()) {
            "（未搜到网络资料,凭你的知识判断）"
        } else {
            "网络资料:\n$tavilyContext"
        }
        // v1.3.13(真机反馈"AI 判断《龙年》曲序时未能确定答案"/"没按格式输出"):换协议,
        // 但**判断权仍在模型手里**。旧协议要它"把歌名映射成编号再排" → 就崩在编号格式上。
        // 现在的分工:
        //   · **模型**:判断"官方这首 = 本地哪一首"(官方《花》在本地叫 hana、官方是 Live 版
        //     而本地没有 —— 这种只有它能对上),并**按官方顺序输出本地列表里的写法**;
        //   · **代码**:只做回填(照着它给的本地写法取下标)+ 兜底(写法有出入时的模糊匹配)。
        // ⚠️ 刻意**不**让代码接手"对应关系":那正是需要语义理解的一步,代码永远做不对。
        val prompt = """
            《$album》($artist)的本地曲目(当前顺序可能有误,编号仅指代;这也是**你输出的词表**):
            $localBlock

            $contextBlock

            请把这些本地曲目按**官方正确顺序**重排,逐行输出,每行一首。例:
            照抄上面本地列表里排第一的那首的写法
            照抄上面本地列表里排第二的那首的写法
            照抄上面本地列表里排第三的那首的写法
            约束:
            · 输出**必须照抄上面本地列表里的写法** —— 系统用你的输出去重排本地曲目,
              写成官方名/译名/罗马字就认不出来了;
            · 官方写法与本地不一致时,**对应关系由你判断**(例:官方《花》在本地是「hana」、
              官方是 Live 版而本地没有这类差异,只有你能对上),判断出来后就照抄本地写法;
            · 不确定某首对应本地哪一个,就不要输出它;
            · 完全无法判断时,只输出一行 UNKNOWN。
        """.trimIndent()
        val messages = mutableListOf("user" to prompt)

        onStep("analyze", AgentEngine.StepStatus.RUNNING)
        // 3. 调 LLM(config 驱动,任意已配置供应商)。
        // v1.3.3: maxTokens 从 512 回调到 2048——推理模型(sensenova/DeepSeek-R1 等)的
        // reasoning 也计入 completion_tokens,思考就能吃掉几百 token,512 会让 content
        // 被截断 → 解析不出排序 → 误报"未找到足够信息"(正是 JTW 排序失败的根因)。
        val first = com.shiyin.music.data.ai.LlmClient.call(config, messages, maxTokens = 2048)
        if (first == null) {
            onStep("analyze", AgentEngine.StepStatus.FAILED)
            return@withContext null
        }

        // 4. 解析:模型给的是官方曲序的**曲目名** → 映射回本地下标(0-based)。
        //    解析与匹配都是纯函数,可脱机单测(弱模型最容易错的"编号格式"不再由它负责)。
        fun parseOrder(content: String?): List<Int> =
            officialToLocalIndices(parseTitleLines(content), currentTracks)

        var resp = first
        var ordered = parseOrder(first.content)
        val hadUnknown = first.content?.lines()?.any { it.trim().equals("UNKNOWN", ignoreCase = true) } == true
        // v1.3.15 修重试闸门(真机实测的洞):原条件是 `ordered.isEmpty() && !content.isNullOrBlank()`
        // —— **回复为空时直接跳过重试**,而"回复为空"恰恰是推理/轻量模型最常见的失败形态
        // (《龙年》实测:拿到完整官方 tracklist,却回了个空串 → titleLines=0,一次都没重试)。
        // 现在只要没解析出顺序就重试,并且**给足预算**(输出按实际生成长度计费,要大了不浪费;
        // 顶到上限才可能被服务端 400,故用 LLM_MAX_OUTPUT_TOKENS 这个安全上限)。
        if (ordered.isEmpty()) {
            val retry = com.shiyin.music.data.ai.LlmClient.call(
                config,
                messages + listOfNotNull(
                    first.content?.takeIf { it.isNotBlank() }?.let { "assistant" to it },
                    "user" to "请重新输出:按官方顺序,每行一首,**照抄我上面给你的本地曲目写法**;不要编号、不要歌手名、不要解释;不确定的不要写。",
                ),
                maxTokens = com.shiyin.music.data.ai.LLM_MAX_OUTPUT_TOKENS,  // 8600 级预算,给推理留足
            )
            if (retry != null && retry.content?.isNotBlank() == true) {
                resp = retry
                ordered = parseOrder(retry.content)
            }
        }

        // 5. confidence:有联网资料且排全了 → HIGH,否则 LOW
        val confidence = if (!tavilyContext.isNullOrBlank() && ordered.size >= currentTracks.size) {
            Confidence.HIGH
        } else {
            Confidence.LOW
        }
        // v1.3.14(真机 bug):解析不出排序时**不能标 analyze 成功** —— 否则面板会出现
        // "AI 核对生成顺序 ✓,但文案说失败"这种自相矛盾的状态。这里只在真的有结果时标 DONE;
        // 判不出来就留 RUNNING,由技能按自己的结论落终态(技能失败 → MainViewModel 统一标 FAILED)。
        if (ordered.isNotEmpty()) onStep("analyze", AgentEngine.StepStatus.DONE)
        SortResult(ordered, confidence, resp.promptTokens, resp.completionTokens, cacheTokens = resp.cacheTokens, webSearched = !tavilyContext.isNullOrBlank(), webSnippet = tavilyContext, finishReason = resp.finishReason, thinking = resp.thinking, hadUnknown = hadUnknown, rawReply = resp.content)
    }

    /** 单条模糊匹配的最低分(低于它宁可不认,也不要张冠李戴)。 */
    private const val MIN_FUZZY_SCORE = 400

    /** 从模型回复里抽出"曲目名"行:去行首编号/项目符号/markdown 记号,丢掉 UNKNOWN 与空行。 */
    internal fun parseTitleLines(content: String?): List<String> {
        if (content.isNullOrBlank()) return emptyList()
        return content.lines()
            .map { it.trim().trimStart('*', '#', '-', '·', ' ') }
            .map { it.replace(Regex("""^(?:\(?\d+\)?[.、,，:：)]\s*)+"""), "").trim() }
            .map { it.trim('*', '`', '"', '“', '”', ' ') }
            .filter { it.isNotEmpty() && !it.equals("UNKNOWN", ignoreCase = true) }
    }

    /**
     * **模型给出的本地曲目名序列** → 本地下标(0-based,即官方顺序)。
     *
     * ⚠️ 分工(别搞反):**"官方这首 = 本地哪一首"的对应关系由模型判断** —— 官方《花》在本地叫
     * hana、官方是 Live 版而本地没有,这类差异只有语义理解能对上,代码永远做不对。
     * 模型被要求**照抄本地列表里的写法**输出,所以这里主要是**回填下标**;
     * 三级匹配只是"它写法有出入时"的兜底网(繁简/全半角/feat. 副标题这类机械差异)。
     *
     * 每条本地曲目**只用一次**(天然去重);匹配不上的条目跳过(本地可能缺歌)。
     * 抽成纯函数是为了能脱机单测:兜底网错了会**静默**把曲序排错,比报错更糟。
     */
    internal fun officialToLocalIndices(officialTitles: List<String>, currentTracks: List<String>): List<Int> {
        if (officialTitles.isEmpty() || currentTracks.isEmpty()) return emptyList()
        val localNorm = currentTracks.map { normTitle(it) }
        val used = HashSet<Int>()
        val out = ArrayList<Int>(officialTitles.size)
        for (title in officialTitles) {
            val on = normTitle(title)
            if (on.isEmpty()) continue
            var pick = -1
            // ① 全等
            for (i in currentTracks.indices) if (i !in used && localNorm[i] == on) { pick = i; break }
            // ② 相互包含(带长度下限:短歌名在中文曲库里太常见)
            if (pick < 0) {
                for (i in currentTracks.indices) {
                    if (i in used) continue
                    val ln = localNorm[i]
                    if (ln.isEmpty()) continue
                    if (minOf(ln.length, on.length) >= 3 && (ln.contains(on) || on.contains(ln))) { pick = i; break }
                }
            }
            // ③ 相似度最高。⚠️ 单字标题不走这一档:它的"包含"必然命中一堆无关曲目
            //    (「夜」→《夜に駆ける》),宁可这条不放(少排一首),也不要张冠李戴。
            if (pick < 0 && on.length >= 2) {
                var best = -1
                var bestScore = 0
                for (i in currentTracks.indices) {
                    if (i in used) continue
                    val m = com.shiyin.music.data.search.FuzzySearch.match(on, localNorm[i]) ?: continue
                    if (m.score > bestScore) { bestScore = m.score; best = i }
                }
                if (bestScore >= MIN_FUZZY_SCORE) pick = best
            }
            if (pick >= 0) { used += pick; out += pick }
        }
        return out
    }

    /** 括号补充说明(「晴天 (Live)」「曲名【remix】」)—— 比对前剥掉。 */
    private val BRACKET_SUFFIX = Regex("[(（\\[【].*?[)）\\]】]")

    /**
     * 曲名归一:先剥括号补充说明(「晴天 (Live)」→「晴天」),再交给
     * [com.shiyin.music.data.search.FuzzySearch.normalize] 做全半角/繁简/去空格/小写。
     *
     * 官方曲名经常带 `(Live)` `(Remastered)` `【官方版】` 这类副标题,而本地文件往往没有
     * ——只做 FuzzySearch.normalize **不剥括号**,「晴天 (Live)」就永远对不上《晴天》。
     */
    private fun normTitle(s: String): String =
        com.shiyin.music.data.search.FuzzySearch.normalize(BRACKET_SUFFIX.replace(s, ""))

    /**
     * v1.3.3: 检测文本主要语言(粗略启发式)——日语/中文/英文,用于 Tavily 查询关键词选择。
     * 日语:含平假名/片假名;中文:含汉字(但无假名);其他:英文。
     * v1.3.8: 由 private 改 internal——SongStorySkill 选搜索角度关键词时复用同一套判定,
     * 避免两处各写一份语言识别再漂移。
     */
    internal fun detectLanguage(text: String): String {
        val hasHiragana = text.any { it.code in 0x3040..0x309F }
        val hasKatakana = text.any { it.code in 0x30A0..0x30FF }
        if (hasHiragana || hasKatakana) return "ja"
        val hasHan = text.any { it.code in 0x4E00..0x9FFF }
        if (hasHan) return "zh"
        return "en"
    }
}
