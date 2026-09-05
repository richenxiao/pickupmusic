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
        /** v1.3.3: 分析那次 LLM 调用的思考链(推理模型才有)——透出给 UI 折叠展示。 */
        val thinking: String? = null,
        /** v1.3.3: 模型明确输出 UNKNOWN(它判断不了)——与"格式不遵守"区分,
         *  用户看到的是不同的真实原因而不是同一句套话。 */
        val hadUnknown: Boolean = false,
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
        val prompt = """
            《$album》($artist)本地曲目(编号仅指代,当前顺序可能有误):
            $localBlock

            $contextBlock

            输出官方正确顺序的编号序列,每行一个数字,从第一首到最后一首。例:
            3
            1
            2
            约束:只输出上面出现过的编号;每首恰好一次;官方名与本地写法有差异(feat.注记/空格/繁简)由你对应,不要跳过;完全无法判断时输出 UNKNOWN。不要输出其他任何内容。
        """.trimIndent()
        val messages = mutableListOf("user" to prompt)

        onStep("analyze", AgentEngine.StepStatus.RUNNING)
        // 3. 调 LLM(config 驱动,任意已配置供应商)。
        // v1.3.3: maxTokens 从 512 回调到 2048——推理模型(sensenova/DeepSeek-R1 等)的
        // reasoning 也计入 completion_tokens,思考就能吃掉几百 token,512 会让 content
        // 被截断 → 解析不出编号 → 误报"未找到足够信息"(正是 JTW 排序失败的根因)。
        val first = com.shiyin.music.data.ai.LlmClient.call(config, messages, maxTokens = 2048)
        if (first == null) {
            onStep("analyze", AgentEngine.StepStatus.FAILED)
            return@withContext null
        }

        // 4. 解析编号序列(0-based):行首整数(容错「3.」「3、」「(3)」),或单行全编号串「3,1,2」
        fun parseIndices(content: String?): List<Int> {
            if (content.isNullOrBlank()) return emptyList()
            val out = mutableListOf<Int>()
            for (raw in content.lines()) {
                val line = raw.trim().trimStart('*', '#', '-', '·', ' ')
                if (line.isEmpty() || line.equals("UNKNOWN", ignoreCase = true)) continue
                if (Regex("""^[\d\s,，、.。:：()()-]+$""").matches(line)) {
                    Regex("""\d+""").findAll(line).forEach { out.add(it.value.toInt() - 1) }
                    continue
                }
                Regex("""^\D*?(\d+)""").find(line)?.groupValues?.get(1)?.let { out.add(it.toInt() - 1) }
            }
            return out.filter { it in currentTracks.indices }.distinct()
        }

        var resp = first
        var ordered = parseIndices(first.content)
        val hadUnknown = first.content?.lines()?.any { it.trim().equals("UNKNOWN", ignoreCase = true) } == true
        if (ordered.isEmpty() && !first.content.isNullOrBlank() && !hadUnknown) {
            // 模型没按编号格式输出(且不是明确 UNKNOWN)→ 追加纠错重试一次(只在异常时多花一次调用)
            val retry = com.shiyin.music.data.ai.LlmClient.call(
                config,
                messages + listOf(
                    "assistant" to first.content,
                    "user" to "你输出的不是编号格式。请重新只输出编号列表:每行一个数字,按官方顺序排列,不要歌名、不要解释。",
                ),
                maxTokens = 2048,  // v1.3.3: 512→2048,纠错轮推理模型同样要吃 reasoning token
            )
            if (retry != null) {
                resp = retry
                ordered = parseIndices(retry.content)
            }
        }

        // 5. confidence:有联网资料且排全了 → HIGH,否则 LOW
        val confidence = if (!tavilyContext.isNullOrBlank() && ordered.size >= currentTracks.size) {
            Confidence.HIGH
        } else {
            Confidence.LOW
        }
        onStep("analyze", AgentEngine.StepStatus.DONE)
        SortResult(ordered, confidence, resp.promptTokens, resp.completionTokens, cacheTokens = resp.cacheTokens, webSearched = !tavilyContext.isNullOrBlank(), thinking = resp.thinking, hadUnknown = hadUnknown)
    }

    /**
     * v1.3.3: 检测文本主要语言(粗略启发式)——日语/中文/英文,用于 Tavily 查询关键词选择。
     * 日语:含平假名/片假名;中文:含汉字(但无假名);其他:英文。
     */
    private fun detectLanguage(text: String): String {
        val hasHiragana = text.any { it.code in 0x3040..0x309F }
        val hasKatakana = text.any { it.code in 0x30A0..0x30FF }
        if (hasHiragana || hasKatakana) return "ja"
        val hasHan = text.any { it.code in 0x4E00..0x9FFF }
        if (hasHan) return "zh"
        return "en"
    }
}
