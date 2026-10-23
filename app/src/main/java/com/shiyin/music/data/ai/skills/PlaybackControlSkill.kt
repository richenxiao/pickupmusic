package com.shiyin.music.data.ai.skills

import com.shiyin.music.data.ai.AgentEngine
import com.shiyin.music.data.ai.LlmProviderConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * v1.3.8 Agent 技能⑧:播放控制。
 *
 * 补的是一个**能力空洞**:在此之前用户最自然的日常说法(「放一下《晴天》」「暂停」
 * 「下一首」)没有任何工具接得住,只会被当成闲聊回一句——这是可用性硬伤,优先级高于
 * 锦上添花的查询类技能。
 *
 * 零 LLM、零网络:动作与目标都由模型在分发那一次工具调用里抽成结构化参数,execute
 * 只是落地到 PlayerController。
 *
 * **只操作播放状态,不碰音乐库内容**——与项目约定一致:不可逆/改库操作永不接入
 * 自然语言执行路径(删除、收藏、改元数据这些一律不做)。
 */
object PlaybackControlSkill : AgentEngine.AgentSkill {

    /** 括号补充说明(「晴天 (Live)」「歌名【remix】」)——比对前剥掉。 */
    private val BRACKET_SUFFIX = Regex("[(（\\[【].*?[)）\\]】]")
    override val key = "playback_control"
    override val description =
        "播放/暂停/切歌。用户想听某个具体内容时用它(「放一下X」「我想听X」「来一首X」「放一首X的歌」):" +
            "它一次调用就完成查找并开始播放,不需要再调别的工具。歌名不必说准——别名、译名、罗马字、" +
            "只记得片段、记错一两个字都能匹配上。暂停/继续/下一首/上一首也用它。" +
            "**用户要整张专辑时把 scope 填 album**(「放整张X」「X 这张专辑」);只说作品名默认按单曲放。" +
            "**名字既是单曲又是专辑名时不要自己猜**——用 clarify 问一句(选项写「播放单曲 X」与「播放整张专辑 X」)。" +
            "用户没点名内容、要你替他挑时用 discover_music;只是**问**库里有没有某首歌时用 library_query。" +
            "只控制播放,不改音乐库内容。"
    override val parameters = """
        {"type":"object","properties":{
          "action":{"type":"string","enum":["play","pause","resume","next","previous"],"description":"要做的播放动作:play=播放(可用 target 指定内容),pause=暂停,resume=从暂停处继续,next=下一首,previous=上一首"},
          "target":{"type":"string","description":"要播放的歌曲名/专辑名/歌手名。仅 action=play 且用户点名了内容时填,用用户说的原样中文;用户只是说「继续放」时不要填"},
          "scope":{"type":"string","enum":["track","album"],"description":"用户要的是**整张专辑**时填 album(说了「专辑/整张/这张专辑」);要单首歌/歌手时省略或填 track。点名的名字在库里**既是单曲又是专辑**时不要自己猜,先用 clarify 问用户放哪个"},
          "restart":{"type":"boolean","description":"仅当用户**明确要求重新开始**时填 true(「重新播放」「从头」「再放一遍」);其它情况省略"}
        },"required":["action"]}
    """.trimIndent()

    override fun panelSteps(args: Map<String, String>) = listOf(
        AgentEngine.PanelStep("apply", "控制播放"),
    )

    /**
     * 规范化:剥括号补充说明(「晴天 (Live)」→「晴天」),再交给 FuzzySearch.normalize
     * 做**全角→半角 + 繁体→简体 + 去空格 + 小写**。
     *
     * ⚠️ 不要自己 lowercase()+filter { isLetterOrDigit }:候选来自 ctx.searchTracks,
     * 而定这个技能要与之比对的是「库内标题」——全 App 的规范归一就是
     * FuzzySearch.normalize。自造版对「繁体/简体」「全角/半角」「带版注/无版注」
     * 全部判不相等 → 精确命中失败 → 退化成"把 30 条搜索结果全部塞进队列",
     * 用户点名单曲却先听到一首不相干的。
     */
    private fun normalize(s: String): String =
        com.shiyin.music.data.search.FuzzySearch.normalize(BRACKET_SUFFIX.replace(s, ""))

    override suspend fun execute(
        ctx: AgentEngine.SkillContext,
        args: Map<String, String>,
        config: LlmProviderConfig,
        tavilyKey: String,
        onStep: (String, AgentEngine.StepStatus) -> Unit,
    ): AgentEngine.SkillResult = withContext(Dispatchers.IO) {
        val action = args["action"]?.trim()?.lowercase().orEmpty()
        val target = args["target"]?.trim().orEmpty()
        android.util.Log.i("Agent", "playback_control: action=$action target=$target ready=${ctx.playerReady()}")

        onStep("apply", AgentEngine.StepStatus.RUNNING)

        fun done(msg: String): AgentEngine.SkillResult {
            onStep("apply", AgentEngine.StepStatus.DONE)
            return AgentEngine.SkillResult(msg)
        }

        fun fail(msg: String): AgentEngine.SkillResult {
            onStep("apply", AgentEngine.StepStatus.FAILED)
            return AgentEngine.SkillResult(msg, success = false)
        }

        // v1.3.9: 控制器未连接时后续所有调用都是**无声 no-op**(playQueue 在 controller
        // 为 null 时直接 return),技能却会回报"开始播放"——用户听不到声音又看不到原因。
        // 这里前置拦下,给出可读原因。
        if (!ctx.playerReady()) {
            android.util.Log.w("Agent", "playback_control: 播放器未连接,拒绝执行 action=$action")
            return@withContext fail("播放器还没准备好(后台服务未连接),过一会儿再说一次试试。")
        }

        // ── play + 点名了内容 ────────────────────────────────────────────────
        // v1.3.12 真机 bug 修正:原来**先找专辑**——使用场景「给我播放某歌手的某歌」,
        // 库里正好有同名专辑,于是整张 9 首被播了,而他要的是那首单曲。
        // 现在:默认按**单曲**找(用户点作品名时想要的就是那首歌);只有明确 scope=album
        // (使用场景了"专辑/整张")才播整张;两者都存在时由上层用**选择卡片**问用户,不猜。
        if (action == "play" && target.isNotBlank()) {
            val wantsAlbum = args["scope"]?.trim().equals("album", ignoreCase = true)
            val restart = args["restart"]?.trim().equals("true", ignoreCase = true)

            fun playAlbum(): AgentEngine.SkillResult? {
                val key = ctx.findAlbum(target) ?: return null
                val tracks = ctx.albumTracksOf(key)
                if (tracks.isEmpty()) return fail("专辑《$target》里没有曲目。")
                ctx.playTracks(tracks, tracks.first().id)
                return done("开始播放专辑《${tracks.first().album}》,共 ${tracks.size} 首。")
            }

            if (wantsAlbum) playAlbum()?.let { return@withContext it }

            // v1.3.10: 检索改用 [AgentLibrarySearch]——FuzzySearch(全半角/繁简/编辑距离)
            // + 假名↔罗马字 + 译名/别名写法兜底。旧实现直接 ctx.searchTracks(关键词包含匹配),
            // 使用场景别名/译名/罗马字/记错一个字就"库里没找到",还回一句"请说完整歌名"——
            // 这正是实测反馈"必须百分百匹配歌名才能播放"的直接来源。弱模型也不会自己再搜一次。
            val hits = AgentLibrarySearch.resolve(ctx, config, target, 30)
            if (hits.isEmpty()) {
                // 单曲里没有 → 也许用户要的是同名专辑(他没明说"专辑",但库里只有这张叫这名)
                playAlbum()?.let { return@withContext it }
                return@withContext fail(
                    "音乐库里确实没有「$target」(改名/译名/罗马字/错别字都试过了)。" +
                        "换个说法可以再试,或者我按你现在的心情从库里给你挑一首?",
                )
            }
            // 精确同名 → 只放这一首。
            val exact = hits.firstOrNull { normalize(it.title) == normalize(target) }
            if (exact != null) {
                // v1.3.12 幂等(**在技能里**,不在运行时):同一首已经在放就别重新从头放。
                // 技能自己有真实播放状态,由它说这句人话——不必让运行时替它说话。
                // 只有需求方明确要求重来(上层把 restart 注进来)才 restart。
                val cur = ctx.currentTrack()
                if (!restart && ctx.isPlaying() && cur?.id == exact.id) {
                    return@withContext done("《${exact.title}》已经在放了。想从头听就说一句「重新播放」。")
                }
                ctx.playTracks(listOf(exact), exact.id)
                return@withContext done("正在播放《${exact.title}》— ${exact.artist}。")
            }
            // 目标是**歌手**(对得上命中曲目里的歌手名)→ 放这个歌手的一批,最常听在前。
            // 从**命中结果自身**的歌手字段判断(而不是再打一次 searchArtists 桥):能被搜到的
            // 曲目就是候选,判断自洽;也少一次库扫描。多歌手串按 splitArtists 拆开比。
            val targetKey = normalize(target)
            val artistIntent = hits.any { h ->
                com.shiyin.music.data.MediaScanner.splitArtists(h.artist).any { normalize(it) == targetKey }
            }
            if (artistIntent) {
                ctx.playTracks(hits, hits.first().id)
                return@withContext done("开始播放「$target」的 ${hits.size} 首(最常听的在最前)。")
            }
            // 都不是 → 视作"用户记的歌名不完全准":放**最接近的一首**,并如实说明放了哪首、
            // 允许纠正。比回一句"请说完整歌名"有用得多(用户要的是听歌,不是被要求改名)。
            val best = hits.first()
            ctx.playTracks(listOf(best), best.id)
            done("库里没有完全同名的《$target》,给你放了最接近的《${best.title}》— ${best.artist}。不对的话说一声,我换。")
        }

        // ── 其余动作 ───────────────────────────────────────────────────────
        when (action) {
            "play", "resume" -> {
                if (ctx.isPlaying()) return@withContext done("已经在播放了。")
                if (ctx.currentTrack() == null) {
                    return@withContext fail("还没有选歌。可以说「放一下《歌名》」,或者从库里点一首。")
                }
                ctx.resumePlayback()
                done("继续播放。")
            }
            "pause" -> {
                if (!ctx.isPlaying()) return@withContext done("现在没有在播放。")
                ctx.pausePlayback()
                done("已暂停。")
            }
            "next" -> {
                if (ctx.currentTrack() == null) return@withContext fail("还没有在放歌,没有下一首。")
                ctx.nextTrack()
                done("已切到下一首。")
            }
            "previous" -> {
                if (ctx.currentTrack() == null) return@withContext fail("还没有在放歌,没有上一首。")
                ctx.previousTrack()
                done("已回到上一首。")
            }
            else -> fail("没听清要做什么播放操作。可以说「放一下《歌名》」「暂停」「下一首」。")
        }
    }
}
