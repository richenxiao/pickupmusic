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
         * v1.3.15: 联网资料里**提到了几首本地曲目**(0 = 资料很可能讲的是另一张同名作品)。
         * 诊断与置信度都用它 —— 今天这次失败就是"0 重合却报高置信度"。
         */
        val webHits: Int = 0,
        /**
         * v1.3.15: 这次顺序的**来源**(诊断用)。豆瓣路线写成 `douban:<专辑页 URL>` ——
         * 一眼能看出"用哪张页面排的",不必再猜。LLM 路线为 null。
         */
        val orderedSource: String? = null,
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
        /** 诊断回调(见 fetchDoubanTracklist) —— 技能侧接到 ctx.recordDiag 上。 */
        onDiag: (Map<String, Int>, String?, String?) -> Unit = { _, _, _ -> },
    ): SortResult? = withContext(Dispatchers.IO) {
        if (!config.isConfigured || currentTracks.isEmpty()) return@withContext null

        // ── 0. 先试**豆瓣路线**:直接拿页面上的官方曲目表 ────────────────────────────
        // 为什么放最前:通用搜索给的是"一段可能不对题的资料",模型还得照着猜;而豆瓣专辑页的
        // "曲目"段**本身就是官方顺序**(实测《龙年》:抽出来的 14 首与用户手贴的官方序一字不差)。
        // 这一步不需要模型参与 —— 名字能对上就直接排完,对不上才落到下面的模型链路。
        runCatching { fetchDoubanTracklist(tavilyKey, album, artist, currentTracks, onDiag) }.getOrNull()?.let { hit ->
            val ordered = officialToLocalIndices(hit.titles, currentTracks)
            val need = maxOf(3, kotlin.math.ceil(currentTracks.size * 0.8).toInt())
            if (ordered.size >= need) {
                android.util.Log.i(
                    "Agent",
                    "album_sort: 豆瓣曲目表命中 ${ordered.size}/${currentTracks.size} 首(${hit.url})",
                )
                onStep("search", AgentEngine.StepStatus.DONE)
                onStep("analyze", AgentEngine.StepStatus.DONE)
                return@withContext SortResult(
                    orderedIndices = ordered,
                    confidence = Confidence.HIGH,
                    webSearched = true,
                    webSnippet = hit.titles.joinToString("\n"),
                    webHits = hit.hits,
                    orderedSource = "douban:${hit.url}",
                )
            }
            android.util.Log.i("Agent", "album_sort: 豆瓣表覆盖不足(${ordered.size}/${currentTracks.size}),转模型链路")
        }

        // 1. Tavily 联网搜官方曲目顺序资料。主/备查询并行发——总耗时 = max(两查询)。
        //    备用查询去掉括号副标题(如《X (Deluxe Edition)》),整串搜无结果时兜底。
        //    v1.3.3: 多语言关键词——日语专辑用日语关键词,中文用中文,英文用英文,
        //    否则日语专辑搜不到(中文关键词对日语内容无效)。
        val queryLang = detectLanguage(album + artist)
        // v1.3.15:中文去掉写死的"曲目顺序 tracklist" —— 实测那句修饰词会把结果带偏
        // (返回一堆"专辑曲目"乐评页),朴素的"专辑 曲目"反而更准。权威曲序已由上面的
        // 豆瓣抽取路线负责,这里只是通用兜底。
        val (primaryQuery, altQuery) = when (queryLang) {
            "ja" -> "$album $artist アルバム 曲目" to "$album $artist アルバム 曲目"
            "zh" -> "$album $artist 专辑 曲目" to "$album $artist 专辑 曲目"
            else -> "$album $artist album tracklist" to "$album $artist album tracklist"
        }
        val tavilyContext = coroutineScope {
            // 主/备并行:主查询 + (专辑名带括号副标题时)去括号的查询。
            // 注:v1.3.15 一度在这里加过"只搜权威域名"(豆瓣/MusicBrainz/Discogs),**已撤** ——
            // 实测限定域名反而啥也搜不到(豆瓣命中的是移动域名 m.douban.com,被白名单挡掉,
            // 结果只剩一堆无关乐评页)。权威来源改由上面那条**豆瓣抽取路线**专门解决;
            // 这里只做通用兜底。
            val primary = async {
                runCatching { com.shiyin.music.data.ai.TavilyService.search(tavilyKey, primaryQuery) }.getOrNull()
            }
            val altAlbum = album.replace(Regex("[(（].*[)）]"), "").trim()
            val altArtist = artist.replace(Regex("[(（].*[)）]"), "").trim()
            val alt = if (altAlbum != album || altArtist != artist) async {
                runCatching { com.shiyin.music.data.ai.TavilyService.search(tavilyKey, altQuery) }.getOrNull()
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
        val webHits = webContextHits(currentTracks, tavilyContext)
        // v1.3.15:资料**一首本地曲目都没提到**时,它极可能讲的是另一张同名作品(真机实测:
        // 《龙年》搜到的是 B 站一个搬运视频,曲目表完全对不上,却因为"有资料"被判高置信度,
        // 回用户"已按官方顺序排列" —— 错得还很自信)。这种情况仍把资料给它(官方写法可能与
        // 本地不同,重合度低不等于没用),但**明说别当权威**,并且**不给高置信度**。
        val webUsable = !tavilyContext.isNullOrBlank() && webHits > 0
        if (!tavilyContext.isNullOrBlank() && webHits == 0) {
            android.util.Log.w("Agent", "album_sort: 联网资料与本地曲目重合 0 首,按'疑似不是这张专辑'处理")
        }
        val contextBlock = when {
            tavilyContext.isNullOrBlank() -> "（未搜到网络资料,凭你的知识判断）"
            webUsable -> "网络资料:\n$tavilyContext"
            else -> "网络资料(**注意:下面这段资料没有提到本地曲目里的任何一首,很可能不是这张专辑**" +
                "——同名作品/搬运视频太多。**不要把它当官方顺序**,只当线索,以你自己的知识判断):\n$tavilyContext"
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
        // v1.3.15:noThinking=true —— 这一步是**照抄曲名重排**的机械任务,不需要思考。
        // 真机实测(2026-10-02《龙年》):默认开思考时 thinkLen=11294、ct 打满 4096、
        // finish=length、正文为空 → 一直"排不出来"。关掉思考后正文才有预算。
        // ⚠️ thinking 参数是 DeepSeek 专有:换到别的"默认带思考"的 flash 模型(如 sensenova)
        // 时它不生效,那种情况下只能靠预算 —— 所以首次预算也给 4096 这一档(跨供应商安全)。
        val first = com.shiyin.music.data.ai.LlmClient.call(config, messages, maxTokens = 4096, noThinking = true)
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
                // v1.3.15:预算用 4096 而不是上限 8192 —— 上限是**按最宽松的供应商**定的,
                // 小/新供应商(如 sensenova-flash-lite)超限会直接 HTTP 400,那就从"截断"
                // 变成"整次失败"。4096 是跨供应商都安全的一档。
                maxTokens = 4096,
                noThinking = true,   // 机械任务,别让思考再吃预算(仅 DeepSeek 端点生效)
            )
            if (retry != null && retry.content?.isNotBlank() == true) {
                resp = retry
                ordered = parseOrder(retry.content)
            }
        }

        // 5. confidence:资料**确实覆盖了本地曲目**且排全了 → HIGH,否则 LOW。
        // v1.3.15:原判据只看"有没有资料",于是拿到一张同名作品的资料也敢说高置信度
        // (真机实测《龙年》重合 0 首仍报"已按官方顺序排列")。现在要求资料与本地曲目有重合。
        val confidence = if (webUsable && ordered.size >= currentTracks.size) {
            Confidence.HIGH
        } else {
            Confidence.LOW
        }
        // v1.3.14(真机 bug):解析不出排序时**不能标 analyze 成功** —— 否则面板会出现
        // "AI 核对生成顺序 ✓,但文案说失败"这种自相矛盾的状态。这里只在真的有结果时标 DONE;
        // 判不出来就留 RUNNING,由技能按自己的结论落终态(技能失败 → MainViewModel 统一标 FAILED)。
        if (ordered.isNotEmpty()) onStep("analyze", AgentEngine.StepStatus.DONE)
        SortResult(ordered, confidence, resp.promptTokens, resp.completionTokens, cacheTokens = resp.cacheTokens, webSearched = !tavilyContext.isNullOrBlank(), webSnippet = tavilyContext, webHits = webHits, finishReason = resp.finishReason, thinking = resp.thinking, hadUnknown = hadUnknown, rawReply = resp.content)
    }

    /** 单条模糊匹配的最低分(低于它宁可不认,也不要张冠李戴)。 */
    private const val MIN_FUZZY_SCORE = 400

    /** 曲名归一(与 [officialToLocalIndices] 同一套:剥括号副标题 + FuzzySearch.normalize)。 */
    internal fun normTitleForMatch(s: String): String = normTitle(s)

    /** 豆瓣路线的命中结果。[titles] 是页面上的官方曲序。 */
    private data class DoubanHit(val titles: List<String>, val url: String, val hits: Int)

    /**
     * v1.3.15: 走**豆瓣**拿官方曲目表。
     *
     * 两步:①搜索(查询词**带"豆瓣音乐"关键词**)找到专辑页 URL;②抽取该页正文 → 解析"曲目"段。
     *
     * 查询词的坑(实测):原先把"曲目顺序 tracklist"写死在查询里 → 限定豆瓣时返回一堆无关乐评页;
     * 改成「专辑名 歌手名 豆瓣音乐」后,**第 1 条就是那张专辑页**。所以这里不再拼那些修饰词。
     *
     * @return 命中且与本地曲目有重合 → 结果;否则 null(交给下面的模型链路)
     */
    private suspend fun fetchDoubanTracklist(
        tavilyKey: String,
        album: String,
        artist: String,
        localTitles: List<String>,
        /** 诊断回调:(counts, errorCode, detail)。豆瓣路线失败在哪一步**必须能看见** —— 否则
         *  "走了但没命中"和"压根没走"在真机上完全无法区分(2026-10-02 实测踩到)。 */
        onDiag: (Map<String, Int>, String?, String?) -> Unit = { _, _, _ -> },
    ): DoubanHit? {
        if (tavilyKey.isBlank()) {
            onDiag(emptyMap(), "NO_TAVILY_KEY", null)
            return null
        }
        val query = "$album $artist 豆瓣音乐"
        val searched = runCatching {
            com.shiyin.music.data.ai.TavilyService.search(tavilyKey, query, withUrls = true)
        }.getOrNull()
        val urls = DoubanTracklist.subjectUrls(searched).take(2)
        if (urls.isEmpty()) {
            android.util.Log.i("Agent", "album_sort: 豆瓣路线没搜到专辑页 URL,转模型链路")
            onDiag(mapOf("searchLen" to (searched?.length ?: 0)), "DOUBAN_NO_URL", "q=$query")
            return null
        }
        var best: DoubanHit? = null
        var extractedOk = 0
        var titlesSeen = 0
        val need = maxOf(3, kotlin.math.ceil(localTitles.size * 0.8).toInt())
        // v1.3.15:**改写成桌面版再抽**。搜索结果命中的是移动版 m.douban.com,而移动版的
        // "曲目"段是 JS 渲染的 → 抽出来是空的(实测);桌面版才有完整曲目表。先试桌面版,
        // 桌面版抽不出再退回原地址(万一将来豆瓣改版,不至于一次都抽不到)。
        for (raw in urls) {
            val desktop = DoubanTracklist.desktopUrl(raw)
            val tries = if (desktop == raw) listOf(raw) else listOf(desktop, raw)
            for (u in tries) {
                val page = runCatching {
                    com.shiyin.music.data.ai.TavilyService.extract(tavilyKey, listOf(u))
                }.getOrNull()
                if (page.isNullOrBlank()) continue
                extractedOk++
                val titles = DoubanTracklist.parseTracklist(page)
                if (titles.isEmpty()) continue
                titlesSeen += titles.size
                val ov = DoubanTracklist.overlap(titles, localTitles)
                if (best == null || ov > best.hits) best = DoubanHit(titles, u, ov)
                if (ov >= need) break
            }
            if ((best?.hits ?: 0) >= need) break   // 已够覆盖,不再多抽页面
        }
        if (best == null) {
            onDiag(
                mapOf("urls" to urls.size, "extracted" to extractedOk, "titles" to titlesSeen),
                if (extractedOk == 0) "DOUBAN_EXTRACT_FAIL" else "DOUBAN_NO_TRACKLIST",
                urls.first(),
            )
        } else {
            onDiag(
                mapOf("urls" to urls.size, "extracted" to extractedOk, "titles" to best.titles.size, "hits" to best.hits),
                if (best.hits >= need) null else "DOUBAN_LOW_COVER",
                best.url,
            )
        }
        return best?.takeIf { it.hits > 0 }
    }

    /**
     * v1.3.15: 联网资料里**能对上本地曲目的条数**。
     *
     * 为什么必须有它:通用网页搜索结果里同名作品/搬运视频极多(《龙年》实测抓到的是一段
     * B 站视频简介,曲目表完全对不上),而代码此前只看"有没有资料"就把它当权威 →
     * 排错了还报高置信度。这个数让"资料是不是这张专辑的"变成可判定的事实。
     */
    internal fun webContextHits(localTitles: List<String>, webContext: String?): Int {
        val web = normTitle(webContext.orEmpty())
        if (web.isEmpty()) return 0
        return localTitles.count { t ->
            val n = normTitle(t)
            n.length >= 2 && web.contains(n)
        }
    }

    /**
     * v1.3.15: 用户**自己在对话里口述了顺序**时,把它抽成候选项名。
     *
     * 支持两种写法:多行编号列表("1.A\n2.B"),以及一行到底("1. A 2. B 3. C")。
     * 只做"剥编号/项目符号 + 切段",不猜语义 —— 能不能对上本地曲目由
     * [officialToLocalIndices] 判定(同一套三级匹配)。
     */
    internal fun extractListedNames(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        // 编号标记作为**分隔符**:一行到底的列表也能切开
        val parts = text.split(Regex("""(?:^|\s)\d{1,2}\s*[.、,，)）:：]\s*"""))
        return parts.asSequence()
            .map { it.lines().first() }                       // 一段只取第一行(用户常在编号后跟解释)
            .map { it.trim().trim('-', '*', '·', '「', '」', '《', '》', '"', '“', '”', ' ') }
            .map { it.trimEnd('。', '.', ',', '，', '、') }
            .filter { it.isNotBlank() }
            .filter { it.length <= 60 }                       // 过长的是句子,不是曲名
            .toList()
    }

    /**
     * v1.3.15: 若用户在对话里给了**完整(或接近完整)的官方顺序** → 直接返回本地下标序列。
     *
     * 为什么必须有:排序调用的提示里**只有本地曲目表和联网资料,不含用户原话**
     * (真机实测:用户把正确顺序整段贴进聊天,程序依旧当没看见,还回"已按官方顺序排列")。
     * 用户既然自己给了顺序,那就是**最权威的依据**,不该再交给模型去猜。
     *
     * 门槛(防止把普通闲聊误判成"给了顺序"):至少 3 首,且覆盖 ≥80% 的本地曲目。
     * 不满足 → 返回空,走原来的"联网 + 模型"链路。
     */
    internal fun userGivenOrder(userMessage: String, localTitles: List<String>): List<Int> {
        val names = extractListedNames(userMessage)
        if (names.size < 3 || localTitles.isEmpty()) return emptyList()
        val mapped = officialToLocalIndices(names, localTitles)
        val need = maxOf(3, kotlin.math.ceil(localTitles.size * 0.8).toInt())
        return if (mapped.size >= need) mapped else emptyList()
    }

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
