package com.shiyin.music.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow as boundsInWindowExt
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shiyin.music.MainViewModel
import com.shiyin.music.data.formatDuration
import com.shiyin.music.ui.components.DialogAction
import com.shiyin.music.ui.components.DialogActionsRow
import com.shiyin.music.ui.components.OIcon
import com.shiyin.music.ui.components.RubySegment
import com.shiyin.music.ui.components.RubyText
import com.shiyin.music.ui.components.body
import com.shiyin.music.ui.components.coverPalette
import com.shiyin.music.ui.components.rememberCoverPalette
import com.shiyin.music.ui.components.shadowMd
import com.shiyin.music.ui.icons.Lucide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.3.x 预组合(morph 帧率收尾):interactive=false 时整页零命中测试——所有
 * pointerInput/clickable 条件挂载、LazyColumn userScrollEnabled 关。overlay 可
 * 在预热期以隐形(progress=0 时两层 alpha 本来就是 0)+可穿透状态提前挂载,
 * 把首组合/双窗口测量/预滚/取色/振假名 pipeline 全部搬到静止的播放页完成,
 * 点卡打开时零首组合突发。默认 true = 现有行为不变。
 */

@Composable
fun LyricsScreen(vm: MainViewModel, interactive: Boolean = true) {
    val track = vm.trackById(vm.player.currentId) ?: return
    val ly = vm.currentLyrics ?: return
    // v3.3: adaptive-contrast tint. The extractor returns the cover's main
    // colour as `bg` *and* an `fg` chosen so white-on-dark or dark-on-light
    // whichever keeps ≥4.5:1 — light covers keep their light bg and use dark
    // text instead of being force-darkened. This block:
    //   1. adopts bg/fg from the cover mood, not the old fixed pastel block;
    //   2. renders the active line in `fg`, inactive lines dimmer;
    //   3. falls back to a dark neutral until/unless extraction resolves.
    val raw = rememberCoverPalette(track)
    val fallbackPair = coverPalette(track.paletteIndex)
    val isResolved = raw != fallbackPair
    val lyricsMode = vm.lyricsColorMode
    val artBgRaw = if (isResolved) raw.first else Color(0xFF2A2A2E)
    val artBg = artBgRaw
    // v1.3.x 歌词颜色档修正（用户：选"白色"后**整个页面都跟着变**，背离"只改歌词
    // 颜色"的初衷）。根因：此前 `artFg = LyricText`，而 artFg 不只画歌词——头行、
    // 图标、进度、按钮全部画它，歌词档一变，整页 UI 跟着换色。
    // 现在拆成两条互不干扰的色路：
    //  - artFg / dimC / inactiveC：**UI 控件**色，恒取封面自适应色，与歌词颜色档无关；
    //  - LyricText / lyricInactiveC：**歌词正文与译文**色，跟随歌词颜色档。
    val artFg = if (isResolved) raw.second else Color.White
    val dimC = artFg.copy(alpha = 0.55f)
    val btnBg = artFg.copy(alpha = 0.14f)
    val LyricText = when (lyricsMode) {
        "white" -> Color.White
        "black" -> Color(0xFF1A1A1E)
        else -> artFg
    }
    val lyricInactiveC = LyricText.copy(alpha = 0.34f) // 歌词"非焦点行"降档（控件侧是 inactiveC）
    androidx.compose.runtime.LaunchedEffect(track.id, isResolved) {
        android.util.Log.d("PaletteTrace", "LyricsScreen track=${track.id} albumId=${track.albumId} resolved=$isResolved artBg=$artBg fg=$LyricText")
    }
    val safeBg = artBg // bg colour, named for backward-compat with the button/dialog uses below
    val lines = ly.parsed.lines
    // v22 修补(用户反馈⑥"歌词本还是整轨时长"):拆分模式下读**显示时长**
    // (当前这首的分段时长),不读 vm.player.durationMs(整轨文件总时长)。
    // displayDurationMs() 内部 currentCarry() 为 null(普通曲目)时回退 durationMs,
    // 与旧路径语义一致;拆分单曲时返回该曲的 endMs-startMs,进度条/底栏时长全对。
    val durMs = vm.player.displayDurationMs().takeIf { it > 0 } ?: track.durationMs
    // v1.3.x 跟行滚动卡顿修复(重组风暴根因):positionMs 是组合级读——
    // PlayerController ticker 每 300ms 写一次,整个 LyricsScreen 函数体
    // (头行/按钮/列表容器/渐隐层/底栏/全部可见行)每 300ms 全量重组,
    // 26sp 大字全部重测——滚动/跟行的周期性卡顿源。derivedStateOf 惰性
    // 派生:读点订阅的是派生结果 activeI(只在行推进时变,每 2-5s 一次),
    // 不是 ticker 原始值——300ms 重组风暴消失;行推进时行高亮/跟行滚动
    // 照常更新(本来就要更新)。
    // v1.3.x 跟行/高亮延迟修复(用户反馈⑯:唱到该行还慢半拍才亮):
    // 与"重组风暴"同一类问题——positionMs 是 ticker 每 300ms 刷的状态,派生值
    // 最多滞后一个 tick;再叠 220ms 颜色渐变,感知可到 ~0.5s。
    // 修复:①位置改用 framePositionMs()(ExoPlayer currentPosition 自带时钟插值,
    // 帧级精确);②用一个每帧推进的 frameClock 驱动派生重算——**只在行号真正
    // 变化时才通知下游**(值不变不重组),所以不会退回 300ms 全页重组风暴。
    // 暂停时帧循环不跑,但 positionMs 仍是依赖(seek 等高亮照常更新)。
    val frameClock = remember { androidx.compose.runtime.mutableLongStateOf(0L) }
    androidx.compose.runtime.LaunchedEffect(vm.player.isPlaying, interactive) {
        if (!interactive || !vm.player.isPlaying) return@LaunchedEffect
        while (true) {
            androidx.compose.runtime.withFrameNanos { frameClock.longValue = it }
        }
    }
    val activeI by remember(ly, durMs) {
        androidx.compose.runtime.derivedStateOf {
            // 两个依赖都读:positionMs 兜"暂停时 seek";frameClock 让播放中每帧重算。
            @Suppress("UNUSED_EXPRESSION") vm.player.positionMs
            @Suppress("UNUSED_EXPRESSION") frameClock.longValue
            ly.parsed.activeIndex(vm.player.frameDisplayPositionMs(), durMs, ly.offsetMs)
        }
    }
    // v1.3.x 用户反馈("点时钟锚定后,被点到的那行应该在本行亮起,像被唱到了一样"):
    // 未打轴的歌词 activeIndex 恒为 -1(整列都不亮),而打轴正是逐行点时钟的场景——
    // 所以"刚锚定的那一行"要单独亮起来。记最后一次锚定的行;全部锚定完成后
    // (synced=true)高亮交回 activeIndex。
    var tappedLine by remember(ly.mediaId) { androidx.compose.runtime.mutableIntStateOf(-1) }
    val unsynced = !ly.parsed.synced

    // v1.1.0: 振假名（furigana）。仅对日文歌词生效——以「是否存在平/片假名」
    // 判定（中文无假名，可区分）。开关只影响本全屏页，不影响播放页迷你预览条。
    // 分词在后台线程 eagerly 进行（Kuromoji 冷启动 ~0.5s），开关切换不重复分词，
    // 只切换渲染分支。
    // v1.3.x 歌词本设置:日语歌默认开启(设置·歌词本,默认 true);每首日语歌
    // 进歌词本各自判定(切歌回来不保留上一首的手动开关)。
    // v1.3.x code-review(F6):remember 按 track.id 重置——预组合让 overlay 跨曲
    // 常驻(lyricsOn||prewarm 门),无 key 时 furiganaOn 跨曲保留:歌 A 关掉振假名
    // 后切到日语歌 B 仍 false(B 应默认开却没开)。key track.id 每曲重置回默认。
    val hasKana = remember(lines) {
        lines.any { line -> line.text.any { ch -> ch.code in 0x3040..0x30FF } }
    }
    // 第6轮:翻译按钮只对**外语歌词**显示。当前版本用户语言只有中文(简/繁),
    // 纯中文歌词没有翻译需求——按钮整个不挂载(与「あ」只对日语歌显示同规则)。
    // v1.3.10:改按设置档位判定(never=永不显示 / majority=外文较多时,现状 / any=检测到
    // 外文就显示)——档位是 key,改设置立即重算;自动翻译仍走占比判定(见 vm.isForeignLyrics)。
    // 未来做多语言版本时,这里要跟用户语言走(如日语用户看中文歌也应显示)。
    val foreignLyrics = remember(lines, vm.translateButtonMode) {
        vm.shouldShowTranslateButton(lines.map { it.text })
    }
    var furiganaOn by remember(track.id) { mutableStateOf(hasKana && vm.furiganaDefaultOn) }
    // V1.1: 跑准确率流水线（Song Override>纠错表>JMdict>Kuromoji>No Reading）。
    // key 含 furiganaRevision，保存/删除 Song Override 后 bump 即重算该曲。
    val segmentsByLine by produceState<List<List<RubySegment>>?>(initialValue = null, ly, vm.furiganaRevision) {
        if (!hasKana) { value = emptyList(); return@produceState }
        value = withContext(Dispatchers.Default) {
            vm.furiganaSegmentsFor(ly)
        }
    }
    // v1.3.x: 开启振假名即自动跑三层增强(UtaTen 权威对照 + LLM 缺口兜底)。
    // 每曲每版本只自动跑一次(evidence 表缓存判重);结果经 furiganaRevision 触发上面的
    // produceState 重算,注音自动刷新。失败各自降级并写 furiganaAssistStatus,调整
    // 弹层里可见并可手动重跑。切歌(ly 变)后对新曲再判一次。
    // v1.3.x 歌词本设置:furiganaAutoVerify 开关(默认 true)管自动校验;hasKana
    // 已确保只有日语歌进这里——中英文歌(hasKana=false)振假名开关根本不显示,
    // 也不会触发联网校验(用户反馈中英文歌也被自动获取振假名,根因是旧代码只靠
    // furiganaOn 判定,而 furiganaOn 在中英文歌上默认 false 但用户可能误触开启;
    // 现在双条件 + 开关只对日语歌可见,语义收紧)。
    // v1.3.x code-review(F2):网络/LLM pipeline 限歌词本实际打开才跑。预热期
    // 只跑本地分词 produceState(那才是 RubyText 换装突发的来源,已隐形期处理掉);
    // 预热期烧 UtaTen/LLM 配额与"完全本地"立场冲突,且设置页文案写的是"打开
    // 歌词本时"。加 vm.lyricsOn 门:打开才跑,关着不烧;打开后 evidence 到达
    // 只 bump furiganaRevision 触发 produceState 重算(RubyText 已挂,只更新读法,
    // 不再是 Text→RubyText 换装突发)。
    LaunchedEffect(furiganaOn, ly, vm.lyricsOn) {
        if (furiganaOn && hasKana && vm.furiganaAutoVerify && vm.lyricsOn) vm.ensureFuriganaEvidence()
    }
    // v1.3.x code-review(F7):切歌清上一首的校验状态(单字段不按曲隔离,旧值
    // 残留新歌弹层)。key 用 mediaId 稳定——offset 微调 copy 不触发清。
    LaunchedEffect(ly.mediaId) { vm.clearFuriganaAssistStatus() }
    // v1.3.x 需求6:切歌/换歌词源 → 同步翻译状态。
    // 键含 lyricsHash:换源或改歌词文本后 hash 变,旧译文自然不再适用(与振假名
    // evidence 同键法)。syncLyricsTranslation 内部对"同曲同版本"直接返回——
    // 关掉歌词本再打开不会误杀正在跑的翻译,也不会把用户隐藏的状态重置。
    //
    // ⚠️ 「恢复」与「自动翻译」必须走**同一个**入口顺序执行,不能拆成两个
    // LaunchedEffect 并发跑:恢复要 await 读库,自动翻译只看内存 → 会抢跑翻一遍,
    // 恢复结果再被"进行中"守卫丢掉(用户反馈"译好的退出后又要重新加载")。
    val trLyricsHash = remember(ly.raw) { com.shiyin.music.data.furigana.LyricsHash.of(ly.raw) }
    LaunchedEffect(ly.mediaId, trLyricsHash, vm.translateAutoForeign) {
        vm.enterLyricsTranslation(ly, vm.translateAutoForeign)
    }
    // V1.1: 长按编辑 Song Override 入口（最小可用）
    var pickerLine by remember { mutableStateOf<Int?>(null) }
    // (lineIndex, segment, currentReading)：按出现位置存，同 surface 多次出现可分别设
    var editing by remember { mutableStateOf<Triple<Int, RubySegment, String>?>(null) }

    // v1.3.x morph P1:歌词本旧下拉退出(7ddb048 nestedScroll+exitDrag)删除——
    // morph overlay 架构下:①位移内层 Column 会被容器 clip 裁掉;②松手
    // requestLyricsExit 又触发 morph 收起,同一次拖拽跑两段位移("执行两次"
    // 实机反馈的根源)。拖拽收起手势由 P3 在 overlay 层重建(设计文档 §3.5,
    // 指针闸门+nestedScroll 守卫双层防御)。listState 提前声明保留(P3 守卫
    // 还要用 canScrollBackward)。
    val listState = rememberLazyListState()
    Box(Modifier
        .fillMaxSize()
        .background(safeBg)
    ) {
    Column(
        Modifier
            .fillMaxSize()
    ) {
        // Top bar — three-zone layout (back | title/artist centered | settings+close)
        // mirroring the player's top row so the visual hierarchy matches the
        // page behind. The lyric page previously had nothing on the left
        // (only settings+close on the right), which left the top corner feeling
        // empty — re-adding the centered title + artist fixes that without
        // crowding the lyric body.
        // v1.3.x 歌词本头行三轮定稿:top padding 16→8→4→0(贴到状态栏 inset 下缘,
    // 不画进状态栏前提下的极限);左右 padding 22 与下方歌词列同边界。
    // v1.3.x 第二轮排查补充:padding 对齐的是触区 Box 的边缘,但图标在 38dp
    // 触区内【居中】,字形左/右缘还缩进 ~(38-图标宽)/2 ≈ 7.5dp——视觉上仍没
    // 对齐。改图标贴边:返回键 CenterStart(字形左缘贴内容左边界)、编辑键
    // CenterEnd(字形右缘贴内容右边界),触区大小不变(点击面积不减)。
    Box(
            Modifier
                .fillMaxWidth()
                .padding(start = 22.dp, end = 22.dp, top = 0.dp, bottom = 8.dp),
        ) {
            // v1.3.10 需求 A33(第2版,用户实测反馈):**标题居中 + 返回键不挪位**。
            // 第1版在左侧加 38dp 占位让标题居中,却把返回键一并推右了(用户实测:
            // "最左侧的返回按钮被往右边移动了")——根因是占位与返回键是 Row 里两个
            // 独立兄弟,占位占掉了最左的位置。
            // 现在改 Box + **绝对居中**:标题居中于整行(与左右键数量无关),返回键
            // 单独 CenterStart 贴内容左边界(位置与无校对键时完全一致);标题左右各留
            // [titleInset] 对称内边距——对称所以仍严格居中,且长标题不会钻到图标下面。
            val showCalibKey = vm.tapSyncMode == MainViewModel.TapSyncMode.NotStarted &&
                ly.canAdjust && (unsynced || vm.lyricsCalibAlwaysOn)
            // 右侧键总宽(给标题留位);打轴模式右侧是"已锚定 x/y + 退出键",宽度不定→兜底值。
            val rightKeysW = if (vm.tapSyncMode == MainViewModel.TapSyncMode.NotStarted) {
                (if (showCalibKey) 38.dp else 0.dp) + (if (ly.canAdjust) 38.dp else 0.dp)
            } else 132.dp
            val titleInset = maxOf(rightKeysW, 38.dp) + 4.dp
            // v1.3.x 虚影终版(用户三轮反馈定调):问题从来不是图标描边/实心风格,
            // 是图标外那层圆形色块背景本身不该存在。去掉 background(含全部半透明/
            // 黑纱/实心变体),Box 只保留尺寸+clip+clickable 当触区;图标还原描边
            // 风格,直接悬浮在歌词背景上。无底色=无叠加=无虚影。
            // v1.3.x 四项体验:①clickable 无 indication——底色已去,涟漪圈成了
            // 唯一残留的"触碰反馈块",一并去掉(用户:"不需要这个触碰反馈")。
            // ③图标调大调粗补偿存在感(ChevronDown 19→23,Sliders 16→19,描边
            // 用 Lucide 里的加粗变体)。④返回改调 requestLyricsExit()——与物理
            // 返回键同一下拉退出动画,不再瞬间消失(动画状态机在 AppRoot 的
            // Dialog 层,按钮原来直接置 lyricsOn=false 跳过了它)。
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .size(38.dp)
                    .clip(CircleShape)
                    .then(if (interactive) Modifier.clickable(
                        interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                        indication = null,
                    ) { vm.requestLyricsExit() } else Modifier),
                contentAlignment = Alignment.CenterStart,
            ) { OIcon(Lucide.ChevronDownBold, 23.dp, artFg) }
            Column(
                // A33 第2版:标题**居中于整行**(不再用 weight(1f)——那样会被左右键宽窄
                // 影响中心);左右对称内边距 = 较宽一侧的键宽,既保持严格居中又避开图标。
                Modifier.align(Alignment.Center)
                    .fillMaxWidth()
                    .padding(horizontal = titleInset),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    track.title,
                    style = body(15f, FontWeight.ExtraBold, artFg),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    track.artist,
                    style = body(12f, FontWeight.SemiBold, dimC),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // v1.3.x 打轴模式:入口在顶栏右侧(与编辑键并排)。打轴进行中时替换为
            // 「进度 + 退出」。
            // v1.3.10 需求 A26(终版):校对入口的显示规则——
            //  · 默认:**只有未同步(无时间戳、需要人工打轴)的歌词**显示;
            //    正常在线抓到的歌词本来就有时间戳,不该摆一个校对入口在那儿;
            //  · 用户开了「始终显示时间轴校对」后:已同步歌词也常显(便于反复微调)。
            // (中途曾一刀切改成"永远显示",用户实测反馈后收敛回这个规则。)
            if (vm.tapSyncMode == MainViewModel.TapSyncMode.NotStarted) {
                if (showCalibKey) {
                    Box(
                        Modifier
                            // 右端锚定;若编辑键也在,则左移一个键宽与它并排(offset 不占布局,
                            // 所以标题的 [titleInset] 计算不受影响)。
                            .align(Alignment.CenterEnd)
                            .offset(x = if (ly.canAdjust) (-38).dp else 0.dp)
                            .size(38.dp)
                            .clip(CircleShape)
                            .then(if (interactive) Modifier.clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null,
                            ) { vm.tapSyncStart() } else Modifier),
                        contentAlignment = Alignment.Center,
                    ) { OIcon(Lucide.Crosshair, 19.dp, artFg) }
                }
                if (ly.canAdjust) {
                    // v1.3.x 虚影终版:同关闭按钮——无背景,图标悬浮,clickable 触区。
                    Box(
                        Modifier
                            .align(Alignment.CenterEnd)
                            .size(38.dp)
                            .clip(CircleShape)
                            .then(if (interactive) Modifier.clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null,
                            ) { vm.lySheet = !vm.lySheet } else Modifier),
                        contentAlignment = Alignment.CenterEnd,
                    ) { OIcon(Lucide.SlidersBold, 19.dp, artFg) }
                }
            } else {
                // 打轴模式顶栏:进度 + 退出。A33 第2版:Box 父级下这三个子项必须自己
                // 横排 + 靠右锚定(原来的 Row 父级已换成 Box,否则会叠在一起)。
                Row(
                    Modifier.align(Alignment.CenterEnd),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val tapped = lines.count { it.timeMs != null }
                    Text(
                        "已锚定 $tapped / ${lines.size}",
                        style = body(13f, FontWeight.Bold, artFg),
                    )
                    Spacer(Modifier.size(10.dp))
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .then(if (interactive) Modifier.clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null,
                            ) { vm.tapSyncExit() } else Modifier),
                        contentAlignment = Alignment.Center,
                    ) { OIcon(Lucide.Close, 19.dp, artFg) }
                }
            }
        }

        // lyric lines, auto-centered on the active line
        // (listState 已提前声明在 Box 顶部:下拉退出的 nestedScroll 连接子要用)
        // v1.3.0: 切歌立刻回顶——此前 listState 跨曲保留,新歌 activeI 从同值出发时
        // LaunchedEffect(activeI) 不触发,列表停在上首歌滚到的位置,等唱到那行才跳
        // (用户:"切到下一首时歌词还停留在最后的位置,应该是下一首自动更新到最上面")。
        // 换曲瞬间先无动画归零,让跟随从第一行/前奏位置重新开始。
        LaunchedEffect(track.id) {
            // v1.3.x morph P2(§3.4-4 锚行对齐):overlay 架构下首次组合即预滚到
            // 当前播放行(与跟行同公式无动画版)——morph 交叉淡化区间里预览当前行
            // 与列表该行位置对齐,锚行视觉位移 ≤8dp。旧"切歌回顶"语义由 morph
            // 时代替:overlay 每次进入都是新组合,每次都滚到 activeI。
            // v1.3.x 预组合:预热期也跑这条(把双窗口测量+预滚搬到隐形期)。
            // v1.3.x 滚动位置修正:计算 scrollOffset 时减去当前行高度的一半,
            // 让当前行的**中心**（而非顶部）对齐到视口中心,避免"看起来在前1-2行"。
            if (activeI >= 0) {
                val viewport = listState.layoutInfo.viewportSize.height
                val itemHeight = listState.layoutInfo.visibleItemsInfo
                    .find { it.index == activeI }?.size ?: 0
                listState.scrollToItem(activeI, scrollOffset = -(viewport / 2 - 60) + itemHeight / 2)
            } else {
                listState.scrollToItem(0)
            }
        }
        // v1.3.x 预组合:打开瞬间(interactive false→true)补一次无动画归位——
        // 预热期列表停在挂载时的行,用户停几秒后打开时 activeI 可能已推进数行;
        // 在 frame 0(content alpha=0,fracRange(0,.40,.65))做掉,不可见。全新
        // 挂载路径(fallback)与上条 track.id 预滚同目标,第二次 scrollToItem 无
        // 触发位移=无开销。
        // v1.3.x 滚动位置修正:同上,计算时减去当前行高度的一半。
        LaunchedEffect(interactive) {
            if (interactive && activeI >= 0) {
                val viewport = listState.layoutInfo.viewportSize.height
                val itemHeight = listState.layoutInfo.visibleItemsInfo
                    .find { it.index == activeI }?.size ?: 0
                listState.scrollToItem(activeI, scrollOffset = -(viewport / 2 - 60) + itemHeight / 2)
            }
        }
        // v1.3.x 滚动流畅度(用户反馈"逐行切换僵硬、帧率低,想要振假名切换那种
        // 流畅度"):animateScrollToItem 用默认 spring,与全页 240ms FastOutSlowIn
        // 动画语言不一致。可见行(逐行推进的常态)改 animateScrollBy 精确差值,
        // spec 统一 240ms FastOutSlowIn——与行高/振假名动画同节奏;不可见行
        // (seek 大跳)保留 animateScrollToItem。item.offset 即该行顶部当前在
        // 视口中的位置,滚动差 = offset − (视口半高−60),正值向上滚。
        // v1.3.x morph P2(§3.4-5 跟行暂停):morph 进行中(progress<0.95)跳过,
        // 避免动画中途列表自己滚(锚行已由上条预滚对齐)。
        // v1.3.x 预组合:预热期(!interactive)不跟行——隐形滚动白费,打开时由
        // LaunchedEffect(interactive) 补位。
        // v1.3.x 需求6:键里加上"译文是否显示"——译文出现/隐藏会让**每一行变高变矮**,
        // 活动行的屏上偏移随之变化(上方 20 行各长 21dp,活动行就偏了 400+dp)。
        // 复用同一套精确差值滚动重新居中,不另写一份(否则两套滚动逻辑会打架)。
        // 收窄订阅:用 derivedStateOf 只盯这个布尔,避免 byLine 每次变化都全页重组。
        val trVisible by remember { androidx.compose.runtime.derivedStateOf { vm.lyricsTranslation.hasContent } }
        // v1.3.x 打轴模式:当前待锚定行自动滚动到可视区居中——锚定后前进到下一句
        // 时,用户不需要手动找回焦点(用户反馈 Bug A 的交互层修复:打轴模式下
        // 当前行始终在可视区中央,点击屏幕任意位置即可锚定)。
        LaunchedEffect(vm.tapSyncCurrentLine, vm.tapSyncMode) {
            if (vm.tapSyncMode != MainViewModel.TapSyncMode.InProgress) return@LaunchedEffect
            if (!interactive) return@LaunchedEffect
            val info = listState.layoutInfo
            val viewport = info.viewportSize.height
            val target = info.visibleItemsInfo.firstOrNull { it.index == vm.tapSyncCurrentLine }
            if (target != null) {
                val d = target.offset - (viewport / 2 - 60)
                if (kotlin.math.abs(d) > 0.5f) {
                    listState.animateScrollBy(
                        d.toFloat(),
                        tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                    )
                }
            } else {
                listState.animateScrollToItem(vm.tapSyncCurrentLine, scrollOffset = -(viewport / 2 - 60))
            }
        }
        LaunchedEffect(activeI, trVisible) {
            if (!interactive) return@LaunchedEffect
            if (vm.morph.progress.value in 0.001f..0.949f) return@LaunchedEffect
            if (activeI >= 0) {
                val info = listState.layoutInfo
                val viewport = info.viewportSize.height
                val target = info.visibleItemsInfo.firstOrNull { it.index == activeI }
                if (target != null) {
                    val d = target.offset - (viewport / 2 - 60)
                    if (kotlin.math.abs(d) > 0.5f) {
                        listState.animateScrollBy(
                            d.toFloat(),
                            tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        )
                    }
                } else {
                    listState.animateScrollToItem(activeI, scrollOffset = -(viewport / 2 - 60))
                }
            }
        }
        // v1.3.x 渐隐遮罩第三稿(实机打回两轮后定稿):上一稿把 DstIn+drawWithContent
        // 挪到外层 Box 却没带 Offscreen 离屏隔离——DstIn 失去隔离边界后把页面背景
        // 一起挖穿,播放条上方一条带透出 Dialog 窗口背后的播放页(实机:能看到
        // 播放页按钮位置,点击无响应)。弃用 DstIn:滚动内容上方叠一层与页面同色
        // (safeBg)的透明渐变——文字"渐隐入背景色"视觉等价,零混合模式,物理上
        // 不可能挖穿任何层。纯绘制,不拦截触摸(无 clickable 即不参与命中)。
        // v1.3.x morph P3(§3.5):列表区 bounds 上报给 overlay 指针闸门(chrome 区
        // = 列表区外);nestedScroll 守卫(§3.5-2 兜底):指针闸门没截住的"列表在
        // 顶+向下"剩余 delta 全量消费喂 morph 拖拽,不让它冒到播放 sheet 的
        // anchoredDraggable(§1.2 结构性风险:pre 派发 sheet 连接子先看到)。
        androidx.compose.runtime.LaunchedEffect(listState) {
            androidx.compose.runtime.snapshotFlow { !listState.canScrollBackward }
                .collect { vm.lyricsListAtTop = it }
        }
        // v1.3.x 第6轮反馈:下拉退出的**唯一起手判据**是"本手势按下那一刻列表是否在顶"。
        // 该判据由 LyricsMorphOverlay 的指针闸门独占实现(它在 awaitFirstDown 时读
        // vm.lyricsListAtTop 做快照,再决定是否 dragging→morph.onDrag),因为那边
        // 能在 Initial pass 抢在播放 sheet 的 anchoredDraggable 之前消费事件。
        //
        // ⚠️ 这里原先还有一份"起手快照 + 喂 morph"的兜底副本,已删除,原因有两条:
        //  1) 它**不生效**:快照是普通局部 var,被 pointerInput(Unit) 冻结成陈旧闭包
        //     (block 只在首个指针事件时取一次,key 不变就不会重跑),而 nestedScroll
        //     每次重组都是新的匿名对象、读的是**新的** Ref —— 写与读指向不同对象,
        //     守卫恒为 false。更糟的是它**非确定性**:两次组合之间若没有重组,两边
        //     恰好共用同一个 Ref,这条路径又活了。
        //  2) 它**冗余**:闸门在 Initial pass 已消费指针,LazyColumn 的 drag 起不来、
        //     也就不会派发 nested-scroll delta,这条路径只可能在"闸门没消费"时触发,
        //     而那正是它不该驱动的状态(会重复喂 morph)。
        // 保留的职责只剩一条:消费中途到顶的向下 delta,不让它冒泡到播放 sheet。
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .onGloballyPositioned { coords ->
                    val b = coords.boundsInWindowExt()
                    vm.lyricsListTopPx = b.top
                    vm.lyricsListBottomPx = b.bottom
                }
                // v1.3.x 第9项:撤掉"点屏幕任意位置锚定当前行"的全屏点击层。
                // 校准改成"点某一行=选中它、点该行时钟=把当前播放时间绑给它",
                // 全屏点击锚定与新交互冲突,且会让选句丢失上下文。
                .nestedScroll(object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
                    override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                        // 只拦"列表在顶+向下+用户拖拽"——其余全放行。
                        // bug⑤ 第2轮:加 source==Drag 过滤——SideEffect(程序滚动,
                        // 跟行 animateScrollBy)在列表恰好在顶时产生的 delta 也流过这里,
                        // 不滤的话唱到下一句 morph 自己动(无用户手势的"自动收起")。
                        if (available.y > 0f && !listState.canScrollBackward &&
                            source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput
                        ) {
                            // 中途到顶的手势:delta 仍消费(防冒泡到播放 sheet 的
                            // anchoredDraggable)但**不喂 morph**——起手不在顶的手势
                            // 永不转收起(用户规则)。想收起请抬手重新起一手。
                            return available
                        }
                        return androidx.compose.ui.geometry.Offset.Zero
                    }
                }),
        ) {
        LazyColumn(
            state = listState,
            // v1.3.x 预组合:预热期(!interactive)关用户滚动——零命中测试(滚动手势
            // 不进列表,触摸穿透到播放页卡片/按钮)。userScrollEnabled=false 时
            // LazyList 不挂 scrollable 修饰符(源码 .then(if(userScrollEnabled))。
            userScrollEnabled = interactive,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 22.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(top = 10.dp, bottom = 150.dp),
        ) {
            itemsIndexed(lines) { i, line ->
                // v1.3.x 跟行滚动卡顿修复(疑点4):activeI 的读点从 item lambda 挪到
                // item 内部的 derivedStateOf——行推进时只有 isActive 结果变化的
                // item 重组(真正变色/变化的那一两行),其余可见行完全跳过。
                // 原实现 i <= activeI 直接读在 lambda 里,activeI 一变整个 items
                // 块失效重跑,@Immutable 只帮"参数没变跳过 Composable 调用",
                // 不帮"lambda 本身失效重跑"——行推进仍拖全列表可见行重组。
                val isActive by androidx.compose.runtime.remember(ly) {
                    // 第9项 A:无时间戳歌词**默认整列全亮**——它不是"还没唱到"，
                    // 而是根本没有时间轴可依，所以不参与 i <= activeI 的时间判断。
                    // 同时:key 用 ly——原来 remember 无键，切歌后 item 槽位复用会
                    // 继续持有**上一首**的闭包（activeI 是旧的 State 引用），高亮跟着
                    // 上一首的时间走。
                    androidx.compose.runtime.derivedStateOf {
                        !ly.parsed.synced || i <= activeI
                    }
                }
                // v1.3.x 打轴模式:当前待锚定行高亮——背景色块 + 加粗 + accent 色,
                // 与普通行/已播放行视觉上明确区分。已锚定行左侧加小圆点标记。
                val isTapSyncCurrent = vm.tapSyncMode == MainViewModel.TapSyncMode.InProgress && i == vm.tapSyncCurrentLine
                val isTapped = line.timeMs != null
                // 仅用于抑制涟漪(indication = null);不再挂 Press/Release 诊断收集器。
                val rowClickSrc = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        // v1.3.x 打轴模式:打轴模式下禁用歌词行原有点击行为(防冲突),
                        // 全屏点击由外层 pointerInput 接管(见下方 tapSyncTapBox)。
                        // v1.3.x 第9项:校准模式点某一行 = 把它设为当前校准目标
                        // (与 index 绑定,不依赖列表位置);时钟按钮另有其职(绑定当前播放时间)。
                        // 正常模式保持原有的 seek/长按行为。
                        .then(
                            when {
                                vm.tapSyncMode == MainViewModel.TapSyncMode.InProgress -> Modifier.clickable(
                                    interactionSource = rowClickSrc,
                                    indication = null,
                                ) { vm.calibSelect(i) }
                                interactive && vm.tapSyncMode == MainViewModel.TapSyncMode.NotStarted -> Modifier.combinedClickable(
                                    interactionSource = rowClickSrc,
                                    indication = null,
                                    onClick = {
                                        // v23: target 是**当前曲目内**的毫秒(时间戳/按行数均分),
                                        // 必须走 seekToDisplayMs —— 走 seekToMs 会被当成整轨文件
                                        // 绝对坐标,拆分单曲下 seek 到区间外 → 边界检查切到第一首
                                        // (用户实测:"点歌词行会退出播放,转到专辑的第一首歌")。
                                        val target = line.timeMs?.let { (it - ly.offsetMs).coerceAtLeast(0) }
                                            ?: (i.toLong() * durMs / lines.size.coerceAtLeast(1))
                                        vm.player.seekToDisplayMs(target)
                                    },
                                    onLongClick = {
                                        // V1.1: 长按带振假名的行 → 打开该行注音段选择器（编辑 Song Override）
                                        if (furiganaOn && hasKana && segmentsByLine != null) pickerLine = i
                                    },
                                )
                                else -> Modifier
                            }
                        )
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // v1.3.x 打轴模式:已锚定行左侧小圆点标记(已打轴的行有记号,
                    // 未打轴的行没有——一眼看出哪些已锚定)。
                    if (isTapped && vm.tapSyncMode == MainViewModel.TapSyncMode.InProgress) {
                        Box(
                            Modifier.size(6.dp).clip(CircleShape).background(artFg),
                        )
                        Spacer(Modifier.size(6.dp))
                    }
                    // v1.2.0 #1a: 句间过渡用 animateColorAsState 渐变，不再硬切。
                    // v1.3.x 打轴模式修正(用户反馈"打轴根本没有变亮"):打轴模式下
                    // **只有当前待锚定行亮**,其余行(含已唱过的)全部暗——聚光灯效果,
                    // 跟唱的反转。原实现 isTapSyncCurrent 和 isActive 都是 artFg,
                    // 播放中已唱行也在亮,当前行混在里面完全看不出。
                    val lineColor by androidx.compose.animation.animateColorAsState(
                        when {
                            vm.tapSyncMode == MainViewModel.TapSyncMode.InProgress ->
                                if (isTapSyncCurrent) LyricText else lyricInactiveC
                            // 未打轴时 activeIndex 恒 -1:刚点时钟锚定的那一行单独亮起
                            // (用户要求"像被唱到了一样")。
                            unsynced && i == tappedLine -> LyricText
                            // v1.3.x 歌词颜色档:行色用歌词色路(LyricText/lyricInactiveC),
                            // 不再用 artFg——否则改歌词颜色会连带整页图标/按钮变色。
                            else -> if (isActive) LyricText else lyricInactiveC
                        },
                        animationSpec = tween(220),
                        label = "lineColor",
                    )
                    val rubySegs = segmentsByLine?.getOrElse(i) { null }
                    // v1.3.x 动画重构(实机验证打回后重做):hp/kp 必须在
                    // furiganaOn 分支【外】——此前写在分支内,开启时 RubyText
                    // 首次组合 animateFloatAsState 初始值=目标值(无 0→1 可播),
                    // 关闭时整支卸载动画状态机直接销毁,两个方向都瞬间跳变。
                    // 现在振假名可用的日文行【始终挂载 RubyText】不随开关装卸,
                    // p=0 时渲染与 Text 分支逐字节一致(无槽行高=调用方 36sp),
                    // 开关切换由 hp/kp 连续驱动过渡。
                    val hasRubyLine = hasKana && rubySegs != null
                    // 单进度 hp 同步驱动行高增量+文字下沉("腾出空间"和"文字
                    // 下沉"一个连贯动作),假名淡入+从稍高处下滑归位。
                    // v1.3.x 反馈:关闭也改同 240ms——原"kp 110ms 先淡出、hp 240ms
                    // 再收拢"两段式被用户指认拖沓("一步到位,收纳和调整高度一步进行")。
                    // 现在开/关都是 hp/kp 同 240ms 同步驱动(淡出与收拢同时进行)。
                    val hp by androidx.compose.animation.core.animateFloatAsState(
                        if (furiganaOn && hasRubyLine) 1f else 0f,
                        animationSpec = tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        label = "rubyHp",
                    )
                    val kp by androidx.compose.animation.core.animateFloatAsState(
                        if (furiganaOn && hasRubyLine) 1f else 0f,
                        animationSpec = tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                        label = "rubyKp",
                    )
                    // v1.3.x 需求6:译文挂在原文下方。weight(1f) 从原文两分支移到外层
                    // Column(原文两分支改 fillMaxWidth),译文另起一行;缺行的行不渲染
                    // 占位文案(宁可缺行不可错行——该行无译文就是真的没译出来)。
                    // v1.3.x 反馈第3轮:【去掉 animateContentSize】——它包着原文+译文
                    // 整列,振假名 Canvas 行高变化也被它二次动画化(位置/高度全被打乱,
                    // 用户实测"比上一版还差、出来的位置都变了")。高度变化交给自然
                    // 重组(即时),淡入淡出+下滑由 trProgress 一条动画驱动(一步到位,
                    // 无第二条赛跑)。
                    Column(Modifier.weight(1f)) {
                        if (hasRubyLine) {
                            RubyText(
                                text = line.text,
                                segments = rubySegs,
                                style = body(26f, FontWeight.ExtraBold, lineColor)
                                    .copy(lineHeight = 36.sp),
                                color = lineColor,
                                modifier = Modifier.fillMaxWidth(),
                                heightProgress = hp,
                                kanaProgress = kp,
                            )
                        } else {
                            Text(
                                line.text,
                                style = body(26f, FontWeight.ExtraBold, lineColor)
                                    .copy(lineHeight = 36.sp),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                        val trText = if (vm.lyricsTranslation.hasContent) {
                            vm.lyricsTranslation.translationFor(i)
                        } else {
                            null
                        }
                        // v1.3.x 需求6 修补:译文弹入/收起**真正一步到位**。
                        // lastTrText 保留退出动画期间的旧值,trProgress 归零前始终渲染。
                        // v1.3.x 反馈①(收起卡顿):条件分支让 Text 在淡出期间占位、归零瞬间
                        // 移除——布局塌陷。改:高度由 trProgress 直接驱动,
                        // 与 alpha/translationY 同一条动画——收字与收高同时进行,无第二条。
                        // trProgress 归零后 displayTr=lastTrText 仍渲染但 height=0+alpha=0,
                        // 零占位零可见,不塌陷。
                        // ⚠️ 必须按曲 key:歌词本是同窗口 overlay,切歌不重建组合,不 key 的话
                        // 上一首的译文会在新歌页面上淡出 240ms(用户会看到"串台")。
                        var lastTrText by remember(ly.mediaId) { mutableStateOf<String?>(null) }
                        if (trText != null) lastTrText = trText
                        val trProgress by androidx.compose.animation.core.animateFloatAsState(
                            if (trText != null) 1f else 0f,
                            animationSpec = tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                            label = "trProgress",
                        )
                        // v1.3.x 反馈⑤:译文颜色与正文**同步渐变**——正文用 animateColorAsState
                        // (220ms),译文此前是 if 瞬间切换(用户实测"正文亮的比翻译慢")。
                        // 同一 animateColorAsState 同一 spec:两者同时开始、同时到达。
                        // v1.3.x 反馈⑩(译文与正文不同时亮起/部分行只有翻译亮):原实现 trColor
                        // 直接读 `i <= activeI`(组合级),而 lineColor 走 derivedStateOf(延迟读)。
                        // 两条路径对 activeI 的快照时机不同,行推进瞬间同一行正文与译文短暂
                        // 不同步。改:trColor 也用 derivedStateOf 派生 isActive,与正文同源。
                        val trActive by androidx.compose.runtime.remember(ly) {
                            // 与正文同源（第9项：正文/翻译必须基于**同一个** active 状态）：
                            // 无时间戳歌词同样是"全亮"，不各算各的。
                            androidx.compose.runtime.derivedStateOf {
                                !ly.parsed.synced || i <= activeI
                            }
                        }
                        val trColor by androidx.compose.animation.animateColorAsState(
                            // 译文是歌词内容的一部分,跟歌词色路(LyricText),不用控件色路。
                            if (trActive) LyricText.copy(alpha = 0.85f) else lyricInactiveC,
                            animationSpec = tween(220),
                            label = "trColor",
                        )
                        val displayTr = trText ?: lastTrText
                        // 译文弹入/收起 = **高度驱动展开**,与振假名(RubyText 的
                        // heightProgress)同一种手感:容器高度随 trProgress 长开、
                        // clipToBounds 把文字按"卷帘"露出。v1.3.x 曾把这里改成
                        // "内容撑开 + 只有 alpha/translationY",展开失去高度维度,
                        // 用户实测"弹出手感不流畅了"——改回高度驱动。
                        //
                        // 与旧版的唯一区别:高度按**实测行数**算(trFullH = 行高 × 行数),
                        // 不再写死单行。因此长译文换行后完整显示(需求:译文不被截断),
                        // 而展开过程依旧是顺滑的卷帘,而不是"瞬间占满再淡入"。
                        // 行高用 21.sp.toDp() 随字号缩放(fontScale 大时不会裁字)。
                        var trFullH by remember(ly.mediaId) { mutableStateOf(0.dp) }
                        val trLineH = with(androidx.compose.ui.platform.LocalDensity.current) { 21.sp.toDp() }
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height((5.dp + trFullH) * trProgress)
                                .clipToBounds(),
                        ) {
                            if (displayTr != null && trProgress > 0f) {
                                Text(
                                    displayTr,
                                    style = body(14f, FontWeight.Bold, trColor)
                                        .copy(lineHeight = 21.sp),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .graphicsLayer {
                                            alpha = trProgress
                                            translationY = (1f - trProgress) * 6.dp.toPx()
                                        }
                                        .padding(top = 5.dp),
                                    onTextLayout = { r ->
                                        // 首次布局即拿到真实行数;trProgress 从 0 长开的
                                        // 240ms 内完成修正,肉眼不可辨。
                                        trFullH = trLineH * r.lineCount
                                    },
                                )
                            }
                        }
                    }
                    // v1.3.x 第9项 D/E/Q:校准模式下每行右侧是**固定时间区**——
                    // 上=时钟(把当前播放时间绑为本行时间戳),下=时间胶囊(本行已绑定的
                    // 时间戳)。宽高固定,歌词一行还是两行都不影响它的位置(不会跑到文字
                    // 行尾)。**正常模式不显示**(第9项 C:时钟只在校准模式出现;
                    // 原来只在"无时间戳歌词"下常驻,正常阅读时也挂着,已撤)。
                    if (vm.tapSyncMode == MainViewModel.TapSyncMode.InProgress) {
                        CalibTimeArea(
                            timeMs = vm.calibDisplayLines.getOrNull(i)?.timeMs,
                            selected = i == vm.tapSyncCurrentLine,
                            atBoundary = i == vm.tapSyncCurrentLine && vm.calibAtBoundary,
                            fg = LyricText,
                            bg = safeBg,
                            enabled = interactive,
                            onClock = { vm.calibSelect(i); vm.calibBindCurrentTime() },
                        )
                    }
                }
            }
        }
            // 底部渐隐:叠一层 safeBg 透明渐变(压在滚动内容之上)。同 DstIn 旧版
            // 的视觉(文字在底缘渐隐),但无混合模式;宽度和列表对齐(含 22dp 边距外
            // 的整宽,与旧行为一致——旧 DstIn 作用域也是整个列表宽度)。
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(110.dp)
                    .align(Alignment.BottomCenter)
                    .background(Brush.verticalGradient(listOf(safeBg.copy(alpha = 0f), safeBg))),
            )
        } // 渐隐遮罩包裹 Box 闭合

        // v1.3.x 打轴模式:底部控制条替换——进行中显示撤销/跳过/微调;预览模式
        // 显示确认/放弃。普通模式保持原播放控制条。
        if (vm.tapSyncMode == MainViewModel.TapSyncMode.InProgress) {
            Column(
                Modifier.padding(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // 进度条(可拖动,打轴时需要精确定位)
                val dispDurMs = vm.player.displayDurationMs().takeIf { it > 0 } ?: durMs
                val pct = if (interactive && dispDurMs > 0) (vm.player.displayPositionMs().toFloat() / dispDurMs).coerceIn(0f, 1f) else 0f
                var scrubFraction by remember { mutableStateOf<Float?>(null) }
                val shownPct = scrubFraction ?: pct
                val shownPosMs = (scrubFraction?.let { it * dispDurMs } ?: if (interactive) vm.player.displayPositionMs() else 0L).toLong()
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .then(if (interactive) Modifier.pointerInput(durMs) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false)
                                do {
                                    val event = awaitPointerEvent()
                                    event.changes.firstOrNull()?.let { change ->
                                        val f = (change.position.x / size.width).coerceIn(0f, 1f)
                                        scrubFraction = f
                                        change.consume()
                                    }
                                } while (event.changes.any { it.pressed })
                                scrubFraction?.let { vm.player.seekToDisplayFraction(it) }
                                scrubFraction = null
                            }
                        } else Modifier),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(btnBg))
                    Box(Modifier.fillMaxWidth(shownPct).height(4.dp).clip(RoundedCornerShape(2.dp)).background(artFg))
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Text(
                        formatDuration(shownPosMs / 1000),
                        style = body(11.5f, FontWeight.Bold, dimC),
                        modifier = Modifier.width(44.dp),
                    )
                    Text(
                        formatDuration(durMs / 1000),
                        style = body(11.5f, FontWeight.Bold, dimC),
                        modifier = Modifier.width(44.dp),
                        textAlign = TextAlign.End,
                    )
                }
                // 第9项 O/P:当前播放时间是**独立的读数行**（在按钮行上方），
                // 与"每行已绑定的时间戳"是两回事;显示到毫秒（校准需要看这个精度）。
                Text(
                    calibStamp((if (interactive) vm.player.displayPositionMs() else 0L)),
                    style = body(12f, FontWeight.Bold, dimC),
                )
                // 第9项 O:播放/暂停**严格居中**。顺序为 撤销 / -0.5s / -0.1s / ▶ /
                // +0.1s / +0.5s / 跳过——左右两组宽度对称(44+40+40 = 40+40+44),
                // 中间的 ▶ 严格落在控制区正中;撤销/跳过在两侧,±微调贴着 ▶。
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // 撤销——打轴模式按钮加大到 44dp,加边框让涟漪反馈在封面取色
                    // 背景下可见(用户反馈"按钮没有反馈":36dp 小按钮 + 无底纹
                    // 涟漪在深色背景上几乎看不见)。
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(btnBg)
                            .border(1.dp, artFg.copy(alpha = 0.2f), CircleShape)
                            .then(if (interactive) Modifier.clickable { vm.tapSyncUndo() } else Modifier),
                        contentAlignment = Alignment.Center,
                    ) { OIcon(Lucide.RotateCcw, 20.dp, artFg) }
                    CalibAdjustButton("-0.5s", -500, interactive, artFg, btnBg, vm)
                    CalibAdjustButton("-0.1s", -100, interactive, artFg, btnBg, vm)
                    // 播放/暂停(打轴时需要反复试听)
                    Box(
                        Modifier.size(52.dp).shadowMd(CircleShape).clip(CircleShape).background(artFg)
                            .then(if (interactive) Modifier.clickable { vm.player.toggle() } else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        OIcon(
                            if (vm.player.isPlaying) Lucide.Pause else Lucide.Play,
                            24.dp, safeBg,
                            modifier = if (vm.player.isPlaying) Modifier else Modifier.padding(start = 3.dp),
                        )
                    }
                    CalibAdjustButton("+0.1s", 100, interactive, artFg, btnBg, vm)
                    CalibAdjustButton("+0.5s", 500, interactive, artFg, btnBg, vm)
                    // 跳过
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(btnBg)
                            .border(1.dp, artFg.copy(alpha = 0.2f), CircleShape)
                            .then(if (interactive) Modifier.clickable { vm.tapSyncSkip() } else Modifier),
                        contentAlignment = Alignment.Center,
                    ) { OIcon(Lucide.SkipForward, 20.dp, artFg) }
                }
            }
        } else if (vm.tapSyncMode == MainViewModel.TapSyncMode.Preview) {
            // 预览模式:确认/放弃
            Column(
                Modifier.padding(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "打轴完成,播放预览确认无误后保存",
                    style = body(12.5f, FontWeight.Normal, dimC),
                )
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.clip(RoundedCornerShape(999.dp)).background(btnBg)
                            .clickable { vm.tapSyncDiscard() }
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                    ) { Text("放弃", style = body(13.5f, FontWeight.Bold, artFg)) }
                    Box(
                        Modifier.clip(RoundedCornerShape(999.dp)).background(artFg)
                            .clickable { vm.tapSyncConfirm() }
                            .padding(horizontal = 24.dp, vertical = 10.dp),
                    ) { Text("确认保存", style = body(13.5f, FontWeight.Bold, safeBg)) }
                }
            }
        } else {
        // bottom bar: slim progress + time row + play/pause.
        // Order per v4.3 spec: progress bar on top, time row hugging it just
        // below (not at the very bottom), play/pause centered between the time
        // row and the screen bottom.
        Column(
            Modifier.padding(start = 22.dp, end = 22.dp, top = 8.dp, bottom = 4.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // v1.3.x code-review(F12,预组合):预热期(!interactive)短路 positionMs 读——
            // 300ms ticker 不再订阅底栏 lambda,隐形期零抖动;打开后才订阅实时进度。
            // 短路语义:interactive=false 时 && 不求值 positionMs → 无状态订阅。
            // v22: 进度环/拖动用**显示坐标**（拆分模式下是当前这首的区间），
            // 而 activeIndex 仍用整轨文件坐标 —— 歌词是按文件绝对时间戳存的，
            // 跨拆分边界继续走反而更自然。两者互不影响。
            val dispDurMs = vm.player.displayDurationMs().takeIf { it > 0 } ?: durMs
            val pct = if (interactive && dispDurMs > 0) (vm.player.displayPositionMs().toFloat() / dispDurMs).coerceIn(0f, 1f) else 0f
            // v5.3: 进度条支持拖动 scrub(与 PlayerScreen 同一逻辑)。原只有
            // detectTapGestures 点按跳转,按住拖动无响应。按下/拖动期间 scrubFraction
            // 覆盖到手指位置,抬手时 commit 一次 seek,期间忽略 ticker 写回。
            var scrubFraction by remember { mutableStateOf<Float?>(null) }
            val shownPct = scrubFraction ?: pct
            // v1.3.x code-review(F12):同上,预热期短路 positionMs(scrubFraction 为 null 时才读)。
            val shownPosMs = (scrubFraction?.let { it * dispDurMs } ?: if (interactive) vm.player.displayPositionMs() else 0L).toLong()
            Box(
                Modifier
                    .fillMaxWidth()
                    // 第6轮:触摸范围=条本身显示范围(4dp)。原 18dp 隐形触区在条上下
                    // 各扩 7dp,滚歌词/点底栏时误触跳转(用户实测)。要拖就拖条本身。
                    .height(4.dp)
                    .then(if (interactive) Modifier.pointerInput(durMs) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false)
                            do {
                                val event = awaitPointerEvent()
                                event.changes.firstOrNull()?.let { change ->
                                    val f = (change.position.x / size.width).coerceIn(0f, 1f)
                                    scrubFraction = f
                                    change.consume()
                                }
                            } while (event.changes.any { it.pressed })
                            scrubFraction?.let { vm.player.seekToDisplayFraction(it) }
                            scrubFraction = null
                        }
                    } else Modifier),
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(btnBg)
                )
                Box(
                    Modifier
                        .fillMaxWidth(shownPct)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(artFg)
                )
            }
            // v4.3: time numbers hug the progress bar directly beneath it.
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    formatDuration(shownPosMs / 1000),
                    style = body(11.5f, FontWeight.Bold, dimC),
                    modifier = Modifier.width(44.dp),
                )
                Text(
                    formatDuration(durMs / 1000),
                    style = body(11.5f, FontWeight.Bold, dimC),
                    modifier = Modifier.width(44.dp),
                    textAlign = TextAlign.End,
                )
            }
            // v1.1.0: 播放键同行左侧放「あ」振假名开关（仅日文歌词显示）。
            // v1.3.x 需求6:右槽由"占位"改为「譯」翻译按钮(所有语种都有翻译需求);
            // 左槽:有「あ」放「あ」,无「あ」补等宽空位——左右各固定 38dp,播放键仍居中。
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (hasKana) {
                    // v1.3.x 虚影终版:同上两个按钮——去掉圆形底色块。未激活态:
                    // 无背景,あ 字原色悬浮;激活态保留 artFg 实心盘(这个是功能
                    // 状态指示,用户验证过满意,保留)。四项体验:涟漪一并去掉
                    // (激活/未激活都不需要触碰反馈块)。
                    Box(
                        Modifier
                            .size(38.dp)
                            .clip(CircleShape)
                            .background(if (furiganaOn) artFg else androidx.compose.ui.graphics.Color.Transparent)
                            .then(if (interactive) Modifier.clickable(
                                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                                indication = null,
                            ) { furiganaOn = !furiganaOn } else Modifier),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "あ",
                            // v1.2.0 #1b: Trim.Both 剥掉 CJK 字体 ascent/descent 额外量，使「あ」在圆里居中
                            // （includeFontPadding API 在 Compose 1.6+ 已移除，lineHeightStyle.Trim 等效）。
                            style = body(18f, FontWeight.ExtraBold, if (furiganaOn) safeBg else artFg).copy(
                                lineHeight = 18.sp,
                                lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                                    alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                                    trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
                                ),
                            ),
                        )
                    }
                } else {
                    Spacer(Modifier.size(38.dp))
                }
                Spacer(Modifier.weight(1f))
                // play/pause centered between the time row and the screen bottom.
                Box(
                    Modifier
                        .size(58.dp)
                        .shadowMd(CircleShape)
                        .clip(CircleShape)
                        .background(artFg)
                        .then(if (interactive) Modifier.clickable { vm.player.toggle() } else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    OIcon(
                        if (vm.player.isPlaying) Lucide.Pause else Lucide.Play,
                        22.dp, safeBg,
                        modifier = if (vm.player.isPlaying) Modifier else Modifier.padding(start = 3.dp),
                    )
                }
                Spacer(Modifier.weight(1f))
                // v1.3.x 需求6:「譯」翻译按钮(繁体,用户已定)。与「あ」同 38dp、同一行平行。
                // 三态:①初始=无底色悬浮「譯」;②进行中=**实心圆**(同「あ」激活态)+ 圆周
                // 顺时针加载弧;③已译=**圆角方块** + 右上角**纯文字 AI**(无圆圈底,用户明确)。
                // 点击语义与振假名一致——**数据持久化,按钮只管显示/隐藏**:
                //   进行中→取消;已显示→隐藏(数据留);有数据未显示→立刻显示(不调 API);无数据→发起翻译。
                // 第6轮:只对外语歌词挂载(foreignLyrics);纯中文歌词补等宽空位保持播放键居中。
                if (foreignLyrics) {
                val tr = vm.lyricsTranslation
                val spinning = tr.running
                // v1.3.x 第7轮:加载弧。头/尾**各自单调前进**,弧长变化由相对速度差产生——
                // 原实现 startDeg=rotation-sweep,弧长变长时尾巴在**倒退**(相对旋转方向),
                // 实测"尾部一伸一缩"。现规则(与 Material 旋转弧同思路):
                //   · 头:匀速前进,1568ms/圈,永不停;
                //   · 目标弧长:10°↔290°,ease-in-out 1333ms 半程往返(与 1568 不同步);
                //   · 尾:**只前进不倒退**——要变长时尾巴钉住、头继续跑把弧拉长;
                //     要收缩时尾巴前追。尾部永无反向运动,无伸缩闪回。
                val headDeg = remember { mutableFloatStateOf(0f) }
                val tailDeg = remember { mutableFloatStateOf(0f) }
                LaunchedEffect(spinning) {
                    if (!spinning) return@LaunchedEffect
                    headDeg.floatValue = 0f
                    tailDeg.floatValue = 0f
                    val t0 = withFrameNanos { it }
                    var last = t0
                    while (true) {
                        withFrameNanos { now ->
                            val dtMs = (now - last) / 1_000_000f
                            last = now
                            headDeg.floatValue += dtMs * 360f / 1568f
                            val elapsedMs = (now - t0) / 1_000_000f
                            val half = (elapsedMs % 2666f) / 1333f           // 0→1→2
                            val u = if (half <= 1f) half else 2f - half      // 0→1→0
                            val eased = androidx.compose.animation.core.FastOutSlowInEasing.transform(u)
                            val sweepTarget = 10f + eased * 280f
                            val targetTail = headDeg.floatValue - sweepTarget
                            if (targetTail > tailDeg.floatValue) tailDeg.floatValue = targetTail
                        }
                    }
                }
                // 圆圈→方块过渡:角半径动画(圆=19dp 全径→方=11dp)。加载中=纯圆,
                // 加载完成后才从圆形向四周扩散变成圆角方块(240ms)。
                val cornerR by androidx.compose.animation.core.animateDpAsState(
                    if (spinning || (!tr.hasContent)) 19.dp else 11.dp,
                    animationSpec = tween(240, easing = androidx.compose.animation.core.FastOutSlowInEasing),
                    label = "trCorner",
                )
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(cornerR))
                        .background(
                            if (spinning || tr.hasContent) artFg
                            else androidx.compose.ui.graphics.Color.Transparent
                        )
                        .then(
                            // 加载弧:drawBehind 画在外圈,读动画值(draw 期读,只重绘不重组)。
                            // 「譯」字在 Box 内容层,进度环在 drawBehind——文字始终可见。
                            if (spinning) Modifier.drawBehind {
                                val sw = 2.dp.toPx()
                                val inset = sw / 2f
                                val arcSize = Size(size.width - sw, size.height - sw)
                                val topLeft = Offset(inset, inset)
                                drawArc(
                                    color = safeBg.copy(alpha = 0.25f),
                                    startAngle = 0f, sweepAngle = 360f, useCenter = false,
                                    topLeft = topLeft, size = arcSize,
                                    style = Stroke(sw),
                                )
                                drawArc(
                                    color = safeBg,
                                    startAngle = tailDeg.floatValue,
                                    sweepAngle = (headDeg.floatValue - tailDeg.floatValue).coerceIn(0f, 360f),
                                    useCenter = false,
                                    topLeft = topLeft, size = arcSize,
                                    style = Stroke(width = sw, cap = StrokeCap.Round),
                                )
                            } else Modifier
                        )
                        .then(if (interactive) Modifier.clickable(
                            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                            indication = null,
                        ) {
                            when {
                                // 进行中再点 = **真取消**（中止 LLM 调用）；切歌走的是
                                // clearLyricsTranslation（只复位显示，让任务跑完落库，不白花 token）。
                                tr.running -> vm.cancelLyricsTranslation()
                                tr.hasContent -> vm.hideLyricsTranslation()
                                tr.hasData -> vm.showLyricsTranslation()
                                else -> if (!vm.translateLyricsWithAi()) {
                                    vm.showToast(vm.lyricsTranslation.error ?: "无法翻译")
                                }
                            }
                        } else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    // v1.3.x 反馈:加载中「譯」字保留,进度环(drawBehind)叠加在外圈。
                    // 第6轮:AI/本地角标改为**锚定譯字本身**(内层 Box 包住文字,
                    // 角标对齐文字的 TopEnd 再微偏出)——原实现对齐 38dp 外盒角落,
                    // 与居中的字形天然隔着一圈空白,怎么调 padding 都贴不近。
                    Box {
                        Text(
                            "譯",
                            // CJK 单字垂直居中要 Trim.Both(同「あ」,v1.2.0 #1b)。
                            style = body(18f, FontWeight.ExtraBold, if (spinning || tr.hasContent) safeBg else artFg).copy(
                                lineHeight = 18.sp,
                                lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                                    alignment = androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center,
                                    trim = androidx.compose.ui.text.style.LineHeightStyle.Trim.Both,
                                ),
                            ),
                        )
                        // 已译:右上角**纯文字**来源角标,贴着「譯」字右上角但不压字形。
                        // 角标内容跟着**实际来源**走而不是写死 "AI"——离线档(ML Kit)接线后
                        // 同一按钮会承载两种译文,写死就会撒谎。
                        if (!spinning && tr.hasContent) {
                            Text(
                                if (tr.source == "offline") "本地" else "AI",
                                style = body(7f, FontWeight.ExtraBold, safeBg),
                                modifier = Modifier
                                    .align(Alignment.TopEnd)
                                    // 内层 Box 紧贴字形(Trim.Both 剥掉行高余量):
                                    // 右移 5dp 出字形右缘,上移 4dp 出字形顶缘——贴近不重叠。
                                    .offset(x = 5.dp, y = (-4).dp),
                            )
                        }
                    }
                }
                } else {
                    // 纯中文歌词:无翻译需求,补等宽空位保持播放键居中(同左侧「あ」的 else 分支)。
                    Spacer(Modifier.size(38.dp))
                }
            }
            // breathing room so the play button sits above the bottom edge.
            Spacer(Modifier.height(4.dp))
        }
        }
    }
    // Bug4c: render the adjust sheet as an overlay ON TOP of the lyrics
    // column. It slides down from above with a tween, full-screen scrim
    // catches outside taps to dismiss.
    if (vm.lySheet && ly.canAdjust) {
        LyricsAdjustSheet(
            onDismiss = { vm.lySheet = false },
            artFg = artFg,
            dimC = dimC,
            btnBg = btnBg,
            safeBg = safeBg,
            ly = ly,
            onBumpOffset = { vm.bumpLyricsOffset(it) },
            onOpenSourcePicker = { vm.lyricsSourcePicker = true },
            onRefetchFurigana = { vm.ensureFuriganaEvidence(force = true) },
            furiganaAssistStatus = vm.furiganaAssistStatus,
            showFuriganaVerify = hasKana,
            onSave = { vm.saveLyrics() },
            onDelete = { vm.deleteLyrics() },
            onDeleteFuriganaCache = { vm.deleteFuriganaCache() },
            onDeleteTranslationCache = { vm.deleteTranslationCache() },
        )
    }
    // V1.1: 长按编辑 Song Override —— 行注音段选择器
    // 对话框用与歌词页同源的暗色 colorScheme（封面取色 safeBg/artFg），避免 Material3
    // 默认亮色方案在暗色歌词页上"亮得刺眼"。
    val dialogScheme = androidx.compose.material3.darkColorScheme(
        surface = safeBg, background = safeBg,
        surfaceContainer = safeBg, surfaceContainerHigh = safeBg,
        onSurface = artFg, onBackground = artFg,
        onSurfaceVariant = dimC, surfaceVariant = btnBg,
        primary = artFg, onPrimary = safeBg, outline = btnBg,
    )
    pickerLine?.let { idx ->
        val segs = segmentsByLine?.getOrNull(idx) ?: emptyList()
        val editable = segs.filter { it.surface.any { ch -> ch.code in 0x4E00..0x9FFF || ch.code in 0x3400..0x4DBF || ch.code in 0xF900..0xFAFF } }
        androidx.compose.material3.MaterialTheme(colorScheme = dialogScheme) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { pickerLine = null },
                title = { Text("选择要修正的词", style = body(14f, FontWeight.ExtraBold, artFg)) },
                text = {
                    if (editable.isEmpty()) {
                        Text("这行没有可注音的汉字。", style = body(12f, FontWeight.Normal, dimC))
                    } else {
                        Column(Modifier.verticalScroll(rememberScrollState())) {
                            editable.forEach { seg ->
                                Row(
                                    Modifier.fillMaxWidth().clickable {
                                        editing = Triple(idx, seg, seg.reading ?: "")
                                        pickerLine = null
                                    }.padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(seg.surface, style = body(15f, FontWeight.ExtraBold, artFg))
                                    Text(
                                        seg.reading?.ifEmpty { null } ?: "无注音",
                                        style = body(12f, FontWeight.Normal, if (seg.reading.isNullOrEmpty()) dimC else artFg),
                                    )
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    // 暗色弹窗:secondary 用前景色(取色 fg)——主题灰在深底上不可读。
                    DialogActionsRow(listOf(DialogAction("取消") { pickerLine = null }), secondaryColor = artFg)
                },
            )
        }
    }
    // V1.1: 编辑器 —— 原文 + 读音输入 + 保存/删除（仅当前曲生效，换源/改文本自动失效）
    // editing = (lineIndex, segment, currentReading)：按"出现位置"存，同一 surface 多次
    // 出现可分别设不同读法（解决「何」在歌里有的なに有的なん、不能归一的问题）。
    editing?.let { (lineIdx, seg, current) ->
        val surface = seg.surface
        val charStart = seg.startOffset
        var input by remember(surface, current) { mutableStateOf(current) }
        // v1.2.0: 收集本曲该 surface 全部出现位置 + 当前出现的不同读法（含别处刚改好的，
        // bump 后 segmentsByLine 已重算进来），供「同字速选」chip 与「批量应用」开关用。
        val (occurrences, quickReadings) = remember(surface, segmentsByLine) {
            val occs = ArrayList<Pair<Int, Int>>()
            val reads = LinkedHashSet<String>()
            segmentsByLine?.forEachIndexed { li, segs ->
                segs.forEach { s ->
                    if (s.surface == surface) {
                        occs.add(li to s.startOffset)
                        s.reading?.let { r -> if (r.isNotEmpty()) reads.add(r) }
                    }
                }
            }
            occs to reads.toList()
        }
        var batch by remember(surface) { mutableStateOf(false) }
        androidx.compose.material3.MaterialTheme(colorScheme = dialogScheme) {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { editing = null },
                title = { Text("修正读法", style = body(14f, FontWeight.ExtraBold, artFg)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("原文：$surface", style = body(13f, FontWeight.Bold, dimC))
                        androidx.compose.material3.OutlinedTextField(
                            value = input,
                            onValueChange = { input = it },
                            label = { Text("读音（平假名，留空清除）") },
                            singleLine = true,
                            textStyle = body(15f, FontWeight.Normal, artFg),
                        )
                        // v1.2.0: 同字速选——本曲该字当前出现的不同读法，点一下填入输入框，
                        // 免得每次手输；含别处刚改好的读法（bump 后已重算进来）。
                        if (quickReadings.isNotEmpty()) {
                            Text("同字读法速选：", style = body(11f, FontWeight.Bold, dimC))
                            Row(
                                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                for (r in quickReadings) {
                                    Box(
                                        Modifier.clip(RoundedCornerShape(999.dp))
                                            .background(btnBg)
                                            .clickable { input = r }
                                            .padding(horizontal = 12.dp, vertical = 6.dp),
                                    ) { Text(r, style = body(13f, FontWeight.Normal, artFg)) }
                                }
                            }
                        }
                        // v1.2.0: 批量——同时把本曲该 surface 全部出现位置改成输入读法。
                        if (occurrences.size > 1) {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .clickable { batch = !batch }
                                    .padding(vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                androidx.compose.material3.Checkbox(
                                    checked = batch,
                                    onCheckedChange = { batch = it },
                                )
                                Text(
                                    "同时应用本曲全部「$surface」（共 ${occurrences.size} 处）",
                                    style = body(12f, FontWeight.Normal, artFg),
                                )
                            }
                        }
                        Text(
                            if (batch && occurrences.size > 1)
                                "批量：本曲该字全部 ${occurrences.size} 处改为输入读法（换歌词源/改文本自动失效）。"
                            else
                                "仅对当前这首歌的此处生效（第${lineIdx + 1}行），换歌词源/改文本自动失效。",
                            style = body(10.5f, FontWeight.Normal, dimC).copy(lineHeight = 14.sp),
                        )
                    }
                },
                confirmButton = {
                    // 暗色弹窗:主操作用前景色、取消用 dim——与 dialogScheme 同源。
                    DialogActionsRow(
                        listOf(
                            DialogAction("取消") { editing = null },
                            DialogAction("保存", emphasized = true) {
                                if (batch && occurrences.size > 1) {
                                    vm.saveReadingOverrideBatch(occurrences, surface, input)
                                } else {
                                    vm.saveReadingOverrideAt(lineIdx, charStart, surface, input)
                                }
                                editing = null
                            },
                        ),
                        emphasizedColor = artFg,
                        secondaryColor = dimC,
                    )
                },
            )
        }
    }
    // v3.0: lyrics import dialog moved to AppRoot (LyricsImportDialog) so it is
    // reachable from the player menu as well as the fullscreen lyrics view —
    // previously the menu set lyricsImportDialog=true but only LyricsScreen
    // rendered the dialog, so clicking "导入歌词文本" from the player did
    // nothing until the user manually opened LyricsScreen first.
    }
}

/**
 * v1.3.x 第9项 D/E/Q:校准模式下每一行右侧的**固定时间区**。
 *
 * 上=时钟(把当前播放时间绑为本行时间戳),下=时间胶囊(本行已绑定的时间戳)。
 * 宽高固定、位置在右侧——歌词一行还是两行都不影响它的位置,时间不会跟着文字换行漂。
 *
 * 配色走**中性黑白对比**(第9项 E):颜色取自歌词本的前景色(歌词正文色,明暗随
 * 专辑封面),不依赖红/黄——背景是专辑取色,红黄可能与背景撞色。选中行用"反转"
 * (实心 + 反色文字);顶到相邻边界时再提高一档对比并出现一个极小的关联符号
 * (第9项 I/M),不把歌词正文染色。
 */
@Composable
private fun CalibTimeArea(
    timeMs: Long?,
    selected: Boolean,
    atBoundary: Boolean,
    fg: Color,
    bg: Color,
    enabled: Boolean,
    onClock: () -> Unit,
) {
    Column(
        Modifier.width(58.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        // 时钟 = 「把当前播放时间绑到这一句」(第9项 Q)。它不负责进出校准模式。
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (selected) fg else fg.copy(alpha = 0.14f))
                .then(if (enabled) Modifier.clickable { onClock() } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            OIcon(Lucide.Clock, 15.dp, if (selected) bg else fg)
        }
        // 时间胶囊:本行**已绑定**的时间戳(与底部"当前播放时间"是两回事)。
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            modifier = Modifier
                .clip(RoundedCornerShape(999.dp))
                .background(
                    when {
                        selected -> fg                       // 选中:反转,高对比
                        atBoundary -> fg.copy(alpha = 0.28f) // 顶到边界:再提高一档
                        else -> fg.copy(alpha = 0.12f)
                    },
                )
                .padding(horizontal = 7.dp, vertical = 3.dp),
        ) {
            if (atBoundary) {
                // 极小的"关联/向后传播"提示(第9项 I/M):不用警告三角、不用红黄。
                OIcon(Lucide.ListQueue, 9.dp, if (selected) bg else fg)
            }
            Text(
                text = timeMs?.let { calibStamp(it) } ?: "--:--",
                style = body(9.5f, FontWeight.Bold, if (selected || atBoundary) bg else fg),
                maxLines = 1,
            )
        }
    }
}

/** 时间戳格式 `mm:ss.mmm`(如 00:30.500)——时间胶囊的读数。 */
private fun calibStamp(ms: Long): String =
    "%02d:%02d.%03d".format(ms / 60_000, (ms % 60_000) / 1000, ms % 1000)

/**
 * v1.3.x 第9项 H/I/J:校准的 ±微调按钮。
 *
 *  - **单击**：只调当前选中行一步（[MainViewModel.tapSyncAdjust]，带相邻边界钳制）。
 *  - **长按**（>400ms 起连续步进，每 90ms 一步）：顶到下一句的时间戳就**停**
 *    （第一次到达边界=拦截，不偷偷把后面一起挪）。
 *  - **第二次同向长按**：进入涟漪平移（[MainViewModel.calibRippleStep]），选中句
 *    与后面所有已有时间戳的行整体平移同一 offset，松手一次性提交。
 */
@Composable
private fun CalibAdjustButton(
    label: String,
    deltaMs: Long,
    enabled: Boolean,
    fg: Color,
    bg: Color,
    vm: MainViewModel,
) {
    val scope = rememberCoroutineScope()
    var holdJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    Box(
        Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(if (enabled) bg else bg.copy(alpha = 0.4f))
            .border(1.dp, fg.copy(alpha = 0.2f), CircleShape)
            .then(
                if (enabled) Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var didHold = false
                        holdJob = scope.launch {
                            kotlinx.coroutines.delay(400)
                            didHold = true
                            while (true) {
                                val blocked = vm.calibHoldStep(deltaMs)
                                if (blocked) break   // 顶到边界 = 这次长按到此为止
                                kotlinx.coroutines.delay(90)
                            }
                        }
                        waitForUpOrCancellation()
                        val wasHolding = didHold
                        holdJob?.cancel()
                        holdJob = null
                        if (wasHolding) vm.calibHoldRelease() else vm.tapSyncAdjust(deltaMs)
                    }
                } else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = body(10.5f, FontWeight.Bold, fg)) }
}

@Composable
private fun SheetPill(text: String, bg: Color, fg: Color, onClick: () -> Unit) {    Box(
        Modifier
            .clip(RoundedCornerShape(999.dp))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 7.dp),
    ) { Text(text, style = body(12f, FontWeight.ExtraBold, fg), maxLines = 1) }
}

/** Bug4c: fullscreen overlay for the lyrics adjust panel. A scrim catches
 *  outside taps to dismiss; the panel itself slides down from above with a
 *  tween so the appearance feels anchored at the Sliders button, not a flash.
 *  Anchored at top-center, well below the top bar so it doesn't cover the
 *  Sliders/Close buttons (user can still tap Close to leave lyrics entirely). */
@Composable
private fun LyricsAdjustSheet(
    onDismiss: () -> Unit,
    artFg: Color,
    dimC: Color,
    btnBg: Color,
    safeBg: Color,
    ly: com.shiyin.music.LoadedLyrics,
    onBumpOffset: (Long) -> Unit,
    onOpenSourcePicker: () -> Unit,
    onRefetchFurigana: () -> Unit,
    furiganaAssistStatus: String?,
    // v1.3.x code-review(F5):振假名校验行只对日语歌(hasKana)显示——中英文歌
    // 点重新校验会把汉字段当空缺喂 LLM(烧 token 写无效 evidence 行)。
    showFuriganaVerify: Boolean,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    // v1.3.x 需求6:两个缓存清理入口(紧接「保存在本地」下一行)。
    onDeleteFuriganaCache: () -> Unit,
    onDeleteTranslationCache: () -> Unit,
) {
    // v5.2 #76: 面板用固定高对比配色,不再依赖封面取色 artFg/safeBg——取色失败时
    // artFg 落到深灰、在半透明黑底(0.78)上几乎看不见("黑漆漆看不清")。改固定白字
    // + 半透明白按钮 + 深底,任何取色结果都可读。
    val panelFg = Color.White
    val panelDim = Color.White.copy(alpha = 0.55f)
    val panelBtnBg = Color.White.copy(alpha = 0.14f)
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.45f))
            .clickable(onClick = onDismiss),
        contentAlignment = Alignment.TopCenter,
    ) {
        // v5.2 Bug4: bubble-style sheet anchored right under the Sliders button
        // (top-right of the lyrics page). No drop shadow (the old shadow was
        // the ugly piece) — instead the panel is a solid dark surface with a
        // triangular pointer drawn at its top edge to visually connect it to
        // the button, like a chat bubble. The slide-in tween mirrors the
        // player's queue sheet for a consistent "pop in" feel.
        AnimatedVisibility(
            visible = true,
            enter = slideInVertically(animationSpec = tween(260, easing = androidx.compose.animation.core.EaseOutBack)) { -it / 3 } + fadeIn(animationSpec = tween(180)),
            exit = slideOutVertically(animationSpec = tween(160, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { -it / 4 } + fadeOut(animationSpec = tween(120)),
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .padding(top = 76.dp)
                .fillMaxWidth()
                .clickable(enabled = false) { /* swallow clicks so they don't dismiss */ },
        ) {
            Column(
                Modifier
                    // v5.2 Bug4: draw the bubble pointer (triangle) at the top
                    // edge, right-aligned so it points up at the Sliders
                    // button. The pointer is part of the panel background so
                    // the rounded-corner clip doesn't cut it.
                    .drawWithContent {
                        val pointerW = 18.dp.toPx()
                        val pointerH = 9.dp.toPx()
                        val pointerX = size.width - 32.dp.toPx() - pointerW
                        drawContent()
                        drawRect(
                            color = Color.Black.copy(alpha = 0.78f),
                            topLeft = androidx.compose.ui.geometry.Offset(pointerX, 0f),
                            size = androidx.compose.ui.geometry.Size(pointerW, 0.5.dp.toPx()),
                        )
                        // triangle pointer
                        val path = androidx.compose.ui.graphics.Path().apply {
                            moveTo(pointerX, 0f)
                            lineTo(pointerX + pointerW, 0f)
                            lineTo(pointerX + pointerW / 2f, pointerH)
                            close()
                        }
                        drawPath(path = path, color = Color.Black.copy(alpha = 0.78f))
                    }
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.78f))
                    .padding(horizontal = 17.dp, vertical = 15.dp),
                verticalArrangement = Arrangement.spacedBy(13.dp),
            ) {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("时间微调", style = body(12.5f, FontWeight.ExtraBold, panelFg))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        SheetPill("−0.5s", panelBtnBg, panelFg) { onBumpOffset(-500) }
                        val off = ly.offsetMs / 1000.0
                        Text(
                            (if (off > 0) "+" else "") + "%.1f 秒".format(off),
                            style = body(12.5f, FontWeight.ExtraBold, panelFg),
                            modifier = Modifier.width(54.dp),
                            textAlign = TextAlign.Center,
                        )
                        SheetPill("+0.5s", panelBtnBg, panelFg) { onBumpOffset(500) }
                    }
                }
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "匹配来源 · ${ly.source}",
                        style = body(12.5f, FontWeight.ExtraBold, panelFg),
                        maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    SheetPill("切换来源", panelBtnBg, panelFg) { onOpenSourcePicker() }
                }
                // v1.3.x: 旧"AI 补全"行已下线(DeepSeekService 直连无联网、依赖的 deepseek-chat
                // 模型名已停用)——联网搜歌词并入歌词来源菜单的"agent websearch"项,进度在
                // 播放页预览卡实时显示、可取消。
                // v1.3.x: 振假名三层校验(UtaTen 权威对照 + LLM 缺口兜底)——开启「あ」时
                // 已自动跑过一次;这里显示结果状态 + 手动"重新校验"(force,逐层重跑,
                // UtaTen 失败换网络窗口/LLM 失败换 Key 后用得上)。旧"外部假名校验(实验)"
                // 单层按钮由本入口取代(同一 evidence 表,旧缓存数据继续有效)。
                // v1.3.x code-review(F5):仅日语歌(showFuriganaVerify=hasKana)显示——
                // 中英文歌点重新校验会把汉字段当空缺喂 LLM 烧 token 写无效行。
                if (showFuriganaVerify) Row(Modifier.fillMaxWidth()) {
                    val status = furiganaAssistStatus
                    val running = status == ""
                    val label = when {
                        running -> "振假名校验中…"
                        status.isNullOrBlank() -> "振假名校验(开启时已自动跑)"
                        else -> "校验:$status"
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(999.dp))
                            .background(panelBtnBg)
                            .clickable(enabled = !running) { onRefetchFurigana() }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, style = body(12.5f, FontWeight.ExtraBold, panelFg))
                    }
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(999.dp))
                            .background(panelFg)
                            .clickable { onSave() }
                            .padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (ly.saved) "已保存到本地 ✓" else "确认并保存到本地",
                            style = body(13f, FontWeight.ExtraBold, Color.Black),
                        )
                    }
                    Box(
                        Modifier
                            .clip(RoundedCornerShape(999.dp))
                            .background(panelBtnBg)
                            .clickable { onDelete() }
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("删除歌词", style = body(13f, FontWeight.ExtraBold, panelFg)) }
                }
                // v1.3.x 需求6:缓存清理行——紧接「保存在本地」下一行,一行两个。
                // 「删除振假名缓存」只清**外部 evidence**(UtaTen 等抓来的注音缓存),
                // 不动用户手改的 Song Override(那是用户数据不是缓存);
                // 「删除翻译缓存」清本曲本版本的已保存译文。两者都按
                // (mediaId + lyricsHash) 精确定位,不牵连其它歌、也不牵连换源前的旧版本。
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(999.dp))
                            .background(panelBtnBg)
                            .clickable { onDeleteFuriganaCache() }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("删除振假名缓存", style = body(12f, FontWeight.Bold, panelFg)) }
                    Box(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(999.dp))
                            .background(panelBtnBg)
                            .clickable { onDeleteTranslationCache() }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text("删除翻译缓存", style = body(12f, FontWeight.Bold, panelFg)) }
                }
                Text(
                    "保存后写入本地歌词库，下次打开自动加载，无需重新匹配。",
                    style = body(11f, FontWeight.Normal, panelDim).copy(lineHeight = 16.sp),
                )
            }
        }
    }
}

// ── v3.3 note ───────────────────────────────────────────────────────────────
// Contrast is guaranteed inside `PaletteExtractor` (it returns an fg that
// clears WCAG 4.5:1 against its bg, adapting to dark-on-light or light-on-dark
// as the cover demands). The old per-screen `ensureContrast`/luminance helpers
// here were removed with v3.3 — the page now just trusts (bg, fg) from the
// extractor and paints with them directly.

