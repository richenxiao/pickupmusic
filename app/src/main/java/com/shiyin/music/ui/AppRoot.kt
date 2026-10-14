package com.shiyin.music.ui

import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.mediarouter.app.SystemOutputSwitcherDialogController
import com.shiyin.music.FilesView
import com.shiyin.music.MainViewModel
import com.shiyin.music.Tab
import com.shiyin.music.data.Track
import com.shiyin.music.data.formatDuration
import com.shiyin.music.ui.components.ArtCache
import com.shiyin.music.ui.components.CoverArt
import com.shiyin.music.ui.components.CoverTransition
import com.shiyin.music.ui.components.DialogAction
import com.shiyin.music.ui.components.DialogActionsRow
import com.shiyin.music.ui.components.FavIcon
import com.shiyin.music.ui.components.OIcon
import com.shiyin.music.ui.components.OrganicSwitch
import com.shiyin.music.ui.components.PillButton
import com.shiyin.music.ui.components.RoundCheck
import com.shiyin.music.ui.components.SheetActionRow
import com.shiyin.music.ui.components.SheetDivider
import com.shiyin.music.ui.components.SheetOverlay
import com.shiyin.music.ui.components.SheetSongHeader
import com.shiyin.music.ui.components.body
import com.shiyin.music.ui.components.coverPalette
import com.shiyin.music.ui.components.heading
import com.shiyin.music.ui.components.rememberCandidateArt
import com.shiyin.music.ui.components.rememberCoverPalette
import com.shiyin.music.ui.components.shadowLg
import com.shiyin.music.ui.components.shadowMd
import com.shiyin.music.ui.icons.Lucide
import com.shiyin.music.ui.screens.HomeScreen
import com.shiyin.music.ui.screens.LibraryScreen
import com.shiyin.music.ui.screens.LyricsScreen
import com.shiyin.music.ui.screens.OnboardingScreen
import com.shiyin.music.ui.screens.PermStep
import com.shiyin.music.ui.screens.PlayerScreen
import com.shiyin.music.ui.screens.SearchScreen
import com.shiyin.music.ui.screens.SettingsHost
import com.shiyin.music.ui.theme.LocalOrganic
import kotlin.math.roundToInt
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import com.shiyin.music.ui.components.ThreeStopSegmentedControl

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: MainViewModel) {
    val c = LocalOrganic.current
    Box(
        Modifier
            .fillMaxSize()
            .background(c.bg),
    ) {
        if (!vm.settingsLoaded) return@Box

        if (vm.isOnboarding) {
            Box(Modifier.statusBarsPadding().navigationBarsPadding()) {
                OnboardingScreen(vm)
            }
            return@Box
        }

        // Permission revoked (or auto-reset) after onboarding: show the
        // permission step again instead of an inexplicably empty library.
        if (!vm.hasMediaPermission) {
            Box(Modifier.statusBarsPadding().navigationBarsPadding()) {
                PermStep(vm)
            }
            return@Box
        }

        RootBackHandler(vm)

        // v5.2: 设备图标实时更新——从系统输出切换器/任何二级 Activity 返回(ON_RESUME)
        // 时刷新当前输出设备。refreshActiveDevice 幂等：只在真实路由变了才改图标，所以
        // 即便用户只是打开过设备列表、没真切换，图标也不会误变（满足"没切换不触发"的要求）。
        val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
        androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
            val obs = androidx.lifecycle.LifecycleEventObserver { _, e ->
                if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                    vm.deviceRouter.refreshDeviceList()
                    vm.deviceRouter.refreshActiveDevice()
                    // v1.2.0 #9: 延迟再读一次——系统 Output Switcher 返回后路由可能
                    // 尚未就绪，即时读拿到旧值（仍显示蓝牙）；切回本机不触发 BT 断连、
                    // deviceChanges 不 fire，ON_RESUME 是唯一触发点，故在此补一发延迟重读。
                    vm.deviceRouter.refreshActiveDeviceSoon()
                }
            }
            lifecycleOwner.lifecycle.addObserver(obs)
            onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
        }

        // v4.3: Spotify-style push drawer — ONE Animatable drives both the main
        // content's push+scale and the drawer's slide, synchronously (easeOut).
        val sidebarEasing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)
        val sidebarProgress = remember { Animatable(if (vm.sidebarOpen) 1f else 0f) }
        LaunchedEffect(vm.sidebarOpen) {
            sidebarProgress.animateTo(
                if (vm.sidebarOpen) 1f else 0f,
                animationSpec = tween(if (vm.sidebarOpen) 300 else 250, easing = sidebarEasing),
            )
        }

        // 侧边栏右滑阈值——过阈值后交给上面的 LaunchedEffect 接管展开动画。
        // v1.3.3: 48→20dp——用户反馈右滑常没反应,阈值过高是主因之一。
        // (手势本体在下方内容 Box 的 Final-pass pointerInput 里,见 v1.3.5 注释。)
        val openThresholdPx = with(LocalDensity.current) { 20.dp.toPx() }

        // A track change can land on a song without lyrics while the
        // fullscreen lyric sheet is open — close it instead of a blank page.
        LaunchedEffect(vm.lyricsOn, vm.currentLyrics) {
            if (vm.lyricsOn && vm.currentLyrics?.parsed?.isEmpty != false) {
                vm.closeLyricsInstant()
            }
        }

        // main content
        // v1.2.0 #6: 各页面按页加 statusBarsPadding(首页/搜索/音乐库/设置等),歌手页(ArtistDetail)不加→
        // 写真铺到 y=0。Column 不加 top padding,彻底解耦:歌手页不受其他页高度影响(原条件 if 不重组)。
        Column(
            Modifier
                .fillMaxSize()
                .then(Modifier.navigationBarsPadding())
                // 不加 imePadding——它会随键盘把整个 Column(含 MiniPlayer/BottomNav)顶起,
                // "搜索页弹键盘顶飞底部导航栏"的根源。键盘盖住底部导航即可;要避让键盘的
                // 界面各自处理:AgentScreen 根 Column 自带 imePadding,播放 sheet 内容在
                // ModalBottomSheet 内部也单独 imePadding(输入框随键盘顶起靠它们)。
                .graphicsLayer {
                    val p = sidebarProgress.value
                    translationX = size.width * 0.5f * p
                    scaleX = 1f - 0.05f * p
                    scaleY = 1f - 0.06f * p
                    transformOrigin = TransformOrigin(0f, 0.5f) // pivot left, so scale+translate leaves no gap
                },
        ) {
            // v1.3.5: 手势挂在内容 Box 自身(不再叠 overlay——旧 overlay 叠最上层把
            // down 事件命中拦截,下方卡片全部点不了)。本手势用 **Final pass**:
            // 子节点(横滚卡片行/竖滚列表/clickable)在 Main pass 先处理,处理完轮到
            // 本手势——被消费(横滚正在滚)的事件直接放弃,不与任何滚动冲突;只有
            // 横滚行翻到最左顶格、LazyRow 不再消费(事件未消费)时,继续右滑才累计
            // 过阈值开抽屉。空白区无子节点消费,右滑照常开。这正是"滑到顶再右滑
            // 拉侧边栏,其余时候互不干扰"的语义。
            val onHomePage = vm.tab == com.shiyin.music.Tab.Home &&
                vm.albumKey == null && vm.artistKey == null && vm.plId == null && vm.folderKey == null &&
                !vm.settingsOpen && !vm.recentOpen && !vm.statsOpen && !vm.updatesOpen && !vm.agentOpen
            val gestureBox = Modifier
                .weight(1f)
                .then(
                    if (onHomePage && sidebarProgress.value < 0.001f) Modifier
                        .pointerInput(openThresholdPx) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Final)
                                var netX = 0f; var absY = 0f
                                while (true) {
                                    val event = awaitPointerEvent(PointerEventPass.Final)
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    // 别人消费过(横滚行/竖滚列表/长按)→ 放弃本次
                                    if (change.isConsumed) break
                                    netX += change.positionChange().x
                                    absY += kotlin.math.abs(change.positionChange().y)
                                    if (netX >= openThresholdPx && netX >= absY * 0.45f) {
                                        vm.sidebarOpen = true
                                        change.consume()
                                        break
                                    }
                                    if (!change.pressed) break
                                }
                            }
                        }
                    else Modifier
                )
            Box(gestureBox) {
                    // v1.3.x:恢复原版交叉淡化转场(fadeIn+fadeOut)——slide 转场被
                    // 用户否决("切换途中错误渲染前一页元素"),换回淡入淡出。窗口底色
                    // 已由 values-night/themes.xml 覆盖深色、AppRoot 根 Box 铺 c.bg,
                    // fade 半透明期透出的是与主题同色的 c.bg(或同主题的 window 底),
                    // 不再透亮白窗口底。
                    // v1.3.x 第4项:设置一级页的滚动状态提升到 AnimatedContent 之上
                    // (这里),换路由销毁子页时不跟着销毁;SettingsRoot 用它做
                    // verticalScroll——返回后停在进入子页前的位置。按 view 分键,不共享。
                    val settingsScrollStates = remember { LinkedHashMap<String, ScrollState>() }
                    val contentKey = when {
                        vm.agentOpen -> "agent"
                        vm.splitEditFor != null -> "split"
                        vm.settingsOpen -> "settings:${vm.filesView}"
                        vm.recentOpen -> "recent"
                        vm.statsOpen -> "stats"
                        vm.updatesOpen -> "updates"
                        else -> "tab:${vm.tab}"
                    }
                    AnimatedContent(
                        targetState = contentKey,
                        transitionSpec = { fadeIn(tween(250)) togetherWith fadeOut(tween(150)) },
                        label = "screen",
                    ) { key ->
                        // v1.2.0 #6: 非歌手页加 statusBarsPadding(内容回到状态栏下);歌手页由 LibraryScreen 内部
                        // 处理(ArtistDetail 不加→写真 y=0)。解耦:歌手页不受这些页高度影响。
                        // v1.3.x:每页内容铺不透明 c.bg——交叉淡化时(fadeIn/fadeOut 半透明
                        // 期)新旧页元素在半透明下重叠错叠(用户:"切换途中错误渲染前一页元素")。
                        // 统一不透明背景让每页自成一个不透明平面,fade 透出的是同色 c.bg,
                        // 前景元素不再跨页交叉。
                        val pad = Modifier.fillMaxSize().statusBarsPadding().background(c.bg)
                        when {
                            key == "agent" -> Box(pad) { com.shiyin.music.ui.screens.AgentScreen(vm) }
                            // v22: 专辑整轨拆分为单曲 —— 独立的两步录入界面
                            key == "split" -> Box(pad) { com.shiyin.music.ui.screens.SplitEditorScreen(vm) }
                            key.startsWith("settings") -> {
                                // view 由 key 快照解析,不读实时 vm.filesView:转场期间
                                // 旧层(settings:旧view)保留旧页面渐隐,不跟着 filesView
                                // 改写成新页面——与其他路由(一 key 一组件)结构对齐。
                                val fv = com.shiyin.music.FilesView.valueOf(key.removePrefix("settings:"))
                                // v1.3.x 第4项:返回后恢复一级页的滚动位置。
                                // AnimatedContent 换 key 时整页销毁,一级页 Column 的
                                // rememberScrollState 跟着丢。这里按路由名把这个页面的
                                // ScrollState 提升到 AnimatedContent 之上(换 key 不销毁),
                                // 设置页各处用同一份——回一级页时停在进入子页前的位置。
                                Box(pad) {
                                    SettingsHost(
                                        vm, fv,
                                        scrollStates = settingsScrollStates,
                                    )
                                }
                            }
                            key == "recent" -> Box(pad) { com.shiyin.music.ui.screens.RecentPlaysScreen(vm) }
                            key == "stats" -> Box(pad) { com.shiyin.music.ui.screens.ListeningStatsScreen(vm) }
                            key == "updates" -> Box(pad) { com.shiyin.music.ui.screens.YourUpdatesScreen(vm) }
                            key == "tab:${Tab.Home}" -> Box(pad) { HomeScreen(vm) }
                            key == "tab:${Tab.Search}" -> Box(pad) { SearchScreen(vm) }
                            else -> LibraryScreen(vm)
                        }
                    }
                }
            val cur = vm.trackById(vm.player.currentId)
            // v1.3.0: Agent 页是独立对话界面,不显示底部导航栏和 MiniPlayer。
            // v1.3.6: MiniPlayer 进出加动画——播放页展开时向下滑出、收起时滑回,
            // 用与播放 sheet 相同的 DecelerateEasing 曲线族,与下拉动作呼应(此前
            // 瞬间消失/弹回,按帧生硬——用户反馈的正是这)。AnimatedVisibility 让
            // 布局高度同步动画(收起滑回时 BottomNav 自然下移接住,不再跳)。
            if (!vm.agentOpen) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = cur != null && !vm.playerOpen,
                    enter = androidx.compose.animation.slideInVertically(
                        animationSpec = tween(250, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)),
                    ) { it } + androidx.compose.animation.fadeIn(tween(200)),
                    exit = androidx.compose.animation.slideOutVertically(
                        animationSpec = tween(200, easing = CubicBezierEasing(0.4f, 0f, 0.2f, 1f)),
                    ) { it } + androidx.compose.animation.fadeOut(tween(150)),
                ) {
                    if (cur != null) MiniPlayer(vm, cur)
                }
                BottomNav(vm)
            }
        }

        // v4.3: Spotify-style push drawer — the main content above is pushed
        // right + scaled via graphicsLayer (driven by the same sidebarProgress
        // Animatable). The drawer slides in, and a subtle scrim dims the content.
        // Gate the whole stack on progress > 0 so that when the drawer is fully
        // closed the scrim + panel are removed from composition entirely —
        // leaving ZERO residual hit-test area over the main content. (Without
        // this guard, the always-present fillMaxSize scrim/panel intercepted
        // every touch on the underlying UI even when invisible.)
        if (sidebarProgress.value > 0.001f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = sidebarProgress.value * 0.35f }
                    .background(Color.Black)
                    .clickable(enabled = vm.sidebarOpen) { vm.sidebarOpen = false },
            )
            // drawer panel: 50 % width, rounded right edge, slides in from left
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.5f)
                    .graphicsLayer {
                        translationX = -size.width * (1f - sidebarProgress.value)
                    }
                    .shadow(20.dp, RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp), clip = false)
                    .clip(RoundedCornerShape(topEnd = 28.dp, bottomEnd = 28.dp))
                    .background(c.surface)
                    .statusBarsPadding()
                    // v1.3.x 第1项:与主内容顶栏共用同一套顶部基准(见 TopBarTop/TopBarHeight)
                    // ——两边都是 statusBarsPadding + TopBarTop，品牌区与主顶栏等行高居中，
                    // 中心线自然对齐；不再各写各的 padding 靠肉眼凑。
                    .padding(horizontal = 20.dp)
                    .padding(top = com.shiyin.music.ui.components.TopBarTop),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.height(com.shiyin.music.ui.components.TopBarHeight),
                    ) {
                        Text("拾音", style = heading(24))
                        Text("PickUpMusic", style = body(13f, FontWeight.Normal, c.n500), modifier = Modifier.padding(start = 6.dp))
                    }
                    // v4: sidebar restructured — 5 items, no file-management here.
                    // v1.3.x: Agent 提到"设置"前面(AI 是高频入口,不该排在最后),
                    // 图标 Sparkles(AI 语义)。条目间距 8→16dp(用户连续两轮要求加大)。
                    SidebarItem(Lucide.History, "最近播放") { vm.sidebarOpen = false; vm.recentOpen = true; vm.settingsOpen = false }
                    SidebarItem(Lucide.BarChart, "收听统计") { vm.sidebarOpen = false; vm.statsOpen = true; vm.settingsOpen = false }
                    SidebarItem(Lucide.Bell, "你的更新") { vm.sidebarOpen = false; vm.updatesOpen = true; vm.settingsOpen = false }
                    // v1.3.x: Agent 更名「拾光Agent」+ 换用定稿品牌图标
                    // (线稿即 res/drawable/ic_agent.xml)。
                    // vectorResource 直接把它变成 ImageVector，侧边栏签名不用改。
                    SidebarItem(
                        androidx.compose.ui.res.painterResource(com.shiyin.music.R.drawable.ic_agent),
                        "拾光Agent",
                    ) { vm.sidebarOpen = false; vm.agentOpen = true }
                    SidebarItem(Lucide.Settings, "设置") { vm.sidebarOpen = false; vm.openSettingsView(); vm.recentOpen = false; vm.statsOpen = false; vm.updatesOpen = false; vm.cleanEntry = false }
                    SidebarItem(Lucide.CircleInfo, "关于我们") { vm.sidebarOpen = false; vm.openSettingsView(); vm.navFilesTo(com.shiyin.music.FilesView.About) }
                }
            }
        }

        // v4.3: single-track edit dialog target (set from the track ⋮ menu)
        var trackEditFor by rememberSaveable { mutableStateOf<Long?>(null) }
        // v5.2 Bug6: the fullscreen player is now rendered via ModalBottomSheet,
        // which Material3 implements with a Swipeable NestedScrollConnection.
        // That gives us, out-of-the-box:
        //   • drag-by-touch anywhere on the sheet (not just a 44dp top strip),
        //   • 1:1 finger-following during the drag (the Swipeable state reads
        //     every onPreUp and offsets the sheet's translationY continuously),
        //   • Spotify-like settle when released (spring to nearest anchor),
        //   • the underlying tab content visible through the sheet while it
        //     slides (we use scrim=Transparent and let the PlayerScreen body
        //     itself paint the player UI on top of a transparent overlay).
        // The previous hand-rolled detectVerticalDragGestures + Animatable was
        // this project's most regression-prone area — replacing it with the
        // official Material3 sheet removes that surface entirely.
        if (vm.playerOpen) {
            // v5.2 hotfix 2: removed the `confirmValueChange = { it != Hidden }`
            // gate — it permanently blocked the sheet from EVER reaching the
            // Hidden state, locking the sheet open on user drag.
            //
            // v5.2 hotfix 3: the previous LaunchedEffect here had a deadlier
            // bug — it closed the player as soon as currentValue == Hidden,
            // but `rememberModalBottomSheetState` initializes currentValue to
            // Hidden. So on FIRST composition the LaunchedEffect fired
            // immediately, set `vm.playerOpen = false`, and ModalBottomSheet's
            // own internal LaunchedEffect (which would call `sheetState.show()`
            // to expand the sheet) was torn down before it could run — the
            // sheet never got a chance to appear. That was the "MiniPlayer
            // 点击没反应" bug. The fix: only honour the Hidden → close path
            // AFTER the sheet has actually been visible at least once this
            // session (sheetState.isVisible went true), so the initial Hidden
            // state doesn't pull the rug out.
            // v23 修复(报告B 实锤根因 —— sheet 抢收起手势):
            // material3 1.3.2 的 ModalBottomSheet 有**两条彼此独立**的收起通道:
            //   ① PullGate 闸门(歌词本自己的下拉,经 morph collapse);
            //   ② sheet 自身的拖拽 —— ModalBottomSheetContent 上挂了 anchoredDraggable
            //      (`onDragStopped = settleToDismiss`)与
            //      ConsumeSwipeWithinBottomSheetBoundsNestedScrollConnection(`onFling =
            //      settleToDismiss`),而 settleToDismiss = { scope.launch { settle(it) }
            //      .invokeOnCompletion { if (!isVisible) onDismissRequest() } }。
            // 歌词区下拉时若闸门判定"不该收起"(起手不在顶 → dragging 恒 false)而放行
            // 事件,②会接管这次下拉、把 sheet 拖到 Hidden,进而直接调 onDismissRequest
            // (实测 BackDebug 日志:闸门 dragging=false、UP "no collapse" 之后 ~300ms
            // sheet onDismissRequest 触发 —— 正是这条)。
            // 修法:打在 AnchoredDraggableState **唯一的仲裁点** confirmValueChange 上
            // (settle() 与 anchoredDrag() 都要过它:前者否决则回滚到原状态,后者否决则
            // 不执行 dragTo)。产品规则:歌词本打开时 sheet 不允许进入 Hidden —— 收起
            // 歌词本只能走闸门那条路径。歌词本关闭时一切照旧(拖拽关播放页不受影响)。
            //
            // ⚠️ 必须用 remember 固定 lambda 实例:rememberModalBottomSheetState 内部是
            // rememberSaveable(..., confirmValueChange, ...),lambda 是 **key** —— 每次
            // 重组换新实例会让 SheetState 被重建(initialValue=Hidden),播放页当场自闭。
            // 无捕获的默认值 `{ true }` 是 Kotlin 单例所以此前侥幸成立。
            val allowSheetHidden = androidx.compose.runtime.remember {
                { value: androidx.compose.material3.SheetValue ->
                    !(value == androidx.compose.material3.SheetValue.Hidden && vm.lyricsOn)
                }
            }
            val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
                confirmValueChange = allowSheetHidden,
            )
            // v1.3.x bug① 修复:hide 请求的协程作用域(sheet 内容注入的
            // playerExitRequest 用;lyricsOn 时 overlay 的 scope 已卸载,这里常驻)。
            val scope2 = androidx.compose.runtime.rememberCoroutineScope()
            var sheetHasBeenVisible by androidx.compose.runtime.remember { mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(sheetState.currentValue, sheetState.isVisible) {
                if (sheetState.isVisible) sheetHasBeenVisible = true
                // Only honour the dismiss once the sheet has actually been shown
                // (so the initial Hidden seed doesn't immediately close it).
                if (sheetHasBeenVisible && sheetState.currentValue == androidx.compose.material3.SheetValue.Hidden && vm.playerOpen) {
                    vm.playerOpen = false
                    vm.sleepMenu = false
                }
            }
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = {
                    // ⚠️ 诊断日志(#62)：如果手势返回没触发上面的 BackHandler、直接到这里，
                    // 说明 sheet 的 onDismissRequest 抢先消费了手势事件。
                    android.util.Log.d("BackDebug", "触发：sheet onDismissRequest (lyricsOn=${vm.lyricsOn} qSheet=${vm.qSheetOpen} pMenu=${vm.pMenuView})")
                    // v5.2 #44: 兜底——overlay 开着时只关 overlay（逐级返回），否则退播放页。
                    // v1.3.x morph P1(§3.6-1):lyricsOn 分支改走收起动画(requestLyricsExit
                    // → morph collapse),不再直接置 false 瞬间消失。
                    when {
                        vm.lyricsOn -> vm.requestLyricsExit()
                        vm.qSheetOpen -> { vm.qSheetOpen = false; vm.qEdit = false }
                        vm.pMenuView != null -> vm.pMenuView = if (vm.pMenuView == "timer-custom") "timer" else if (vm.pMenuView == "timer" || vm.pMenuView == "speed" || vm.pMenuView == "karaoke") "root" else null
                        else -> { vm.playerOpen = false; vm.sleepMenu = false }
                    }
                },
                sheetState = sheetState,
                modifier = Modifier.fillMaxHeight(),
                // dragHandle=null keeps the top row (返回 / 三点菜单) as the
                // only chrome at the very top — the user explicitly didn't
                // want an extra drag-strip above that row.
                dragHandle = null,
                // v1.3.x bug① 修复(诊断报告路径①):关掉 material3 在 sheet 窗口注册的
                // 返回回调。开 enableOnBackInvokedCallback 后系统手势先命中它→
                // material 自己把 sheet hide→hotfix-3 置 playerOpen=false→整页掉首页,
                // RootBackHandler 的 lyricsOn 分支永远轮不到。关掉后手势由 content 内
                // 新增的 BackHandler(注册在 sheet Dialog 窗口的 dispatcher)接住逐级
                // 分派:歌词开→收起动画;歌词关→requestPlayerExit 播下滑动画(不再瞬
                // 间消失)。RootBackHandler(主 Activity 窗口)保留作 sheet 外层安全网。
                properties = androidx.compose.material3.ModalBottomSheetProperties(
                    shouldDismissOnBackPress = false,
                ),
                // scrim transparent so the underlying tab content is visible
                // while the sheet slides down (matches Spotify's behaviour).
                scrimColor = Color.Transparent,
                containerColor = c.bg,
                shape = RoundedCornerShape(0.dp),
                // v5.2: material3 1.3.2 ModalBottomSheet 默认 contentWindowInsets 会在内容
                // 底部留一块由 containerColor(c.bg) 填充的导航栏 inset 区——即"单独覆盖内容
                // 的底部背景空白带"把歌词预览卡顶高。置零它，系统栏改由 PlayerScreen 自己用
                // statusBarsPadding/navigationBarsPadding 处理，背景连续填充、无单独覆盖层。
                contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
                // ModalBottomSheet's gestures are always on in 1.7.1 (the
                // swipe-to-dismiss comes from the internal Swipeable
                // NestedScrollConnection). The sheet body itself never sees
                // the gesture — the internal verticalScroll wins on
                // up-scroll which is what we want for the lyrics view.
            ) {
                // v1.3.x bug① 修复:RootBackHandler(主窗口)拿不到本组合作用域的
                // sheetState,注入 hide 请求——歌词关着时系统手势关播放页改播下滑动画
                // (不再瞬间消失),hide 完成由既有 hotfix-3(currentValue==Hidden→
                // playerOpen=false)收尾。同 LyricsMorphOverlay 注入 lyricsExitRequest
                // 模式;sheet 卸载时清空,VM 侧兜底直接关,防 requestPlayerExit 空窗口。
                androidx.compose.runtime.SideEffect {
                    vm.playerExitRequest = { scope2.launch { sheetState.hide() } }
                }
                androidx.compose.runtime.DisposableEffect(Unit) {
                    onDispose { if (vm.playerExitRequest != null) vm.playerExitRequest = null }
                }
                // v1.3.x bug① 修复(诊断报告路径①):sheet 窗口内的返回分派器。本
                // BackHandler 组合在 ModalBottomSheet 的 content 里=注册在 sheet
                // Dialog 窗口的 ComponentDialog dispatcher 上——shouldDismissOnBackPress
                // =false 关掉 material 窗口回调后,系统手势(焦点在 sheet 窗口)由此
                // 接住(API33+ 经 androidx 桥接注册到 dialog window 的
                // OnBackInvokedDispatcher;旧路径 legacy 兜底同样进 ComponentDialog
                // 的 dispatcher),逐级分派同 onDismissRequest 既有链:歌词开→收起
                // morph 动画;菜单/睡眠面板→只关面板;否则→requestPlayerExit 播
                // hide 下滑动画。注意 RootBackHandler 在主 Activity 窗口,焦点在
                // sheet Dialog 时收不到手势——不能只靠它,必须在此处接管。队列
                // (qSheetOpen)是独立窗口、自带返回处理,不入此链。
                BackHandler {
                    when {
                        vm.lyricsOn -> vm.requestLyricsExit()
                        vm.pMenuView != null -> vm.pMenuView = if (vm.pMenuView == "timer-custom") "timer" else if (vm.pMenuView == "timer" || vm.pMenuView == "speed" || vm.pMenuView == "karaoke") "root" else null
                        vm.sleepMenu -> vm.sleepMenu = false
                        else -> vm.requestPlayerExit()
                    }
                }
                // v5.2: 二级 overlay（三点菜单 / 播放队列 / 歌词本）必须和 PlayerScreen
                // 在同一个 popup 窗口里——ModalBottomSheet 是独立 Popup，原先这些 overlay
                // 留在主合成树会被它压在下面、点不到且渲染在“第二面”。移进 content 后它们
                // 盖在 PlayerScreen 之上、可正常打开。Box(fillMaxHeight) 让 overlay 的
                // fillMaxSize/scrim 对齐整页。
                Box(Modifier.fillMaxHeight()) {
                    PlayerScreen(vm)
                    // v1.3.3: PlayerMenuSheet / QueueSheet 从这里迁出——原先它们与
                    // PlayerScreen 同窗口叠加，拖拽经 sheet 根的 anchoredDraggable、
                    // 返回经 sheet window 的 dispatcher 都会作用到整个播放页（开着队列
                    // 时下拉/返回退的是播放页本身）。改声明为播放 sheet 之后的独立
                    // ModalBottomSheet（同全屏歌词本的"后建窗口居上"模式），手势/返回
                    // 落在各自 window 上，只收弹层、播放页不动。
                    // v1.3.x morph P1(设计文档 §3.1):歌词本从独立 Dialog 改为同窗口
                    // overlay——后组居上盖在 PlayerScreen 之上;容器形变/进出动画全部
                    // 在 LyricsMorphOverlay 内(单一 progress 驱动)。lyricsOn=false 时
                    // 整层不组合,零常驻开销。
                    //
                    // v1.3.x 预组合(预热挂载)已回退:实测退出歌词本后预热态 overlay 仍
                    // 挂载、触摸被拦截 → 点卡片进不去。赌的"interactive=false 整页零命中
                    // 测试"站不住(LazyColumn userScrollEnabled=false 仍参与命中,或另有
                    // pointerInput 残留)。改回纯 lyricsOn 门:打开即现场组合(fallback 路径,
                    // 1 帧延迟隔离突发),功能可用优先于帧率优化。interactive 参数化与 morph/
                    // 振假名修复保留,预热后续重新评估(需先把命中测试穿透在装机验证坐实)。
                    if (vm.lyricsOn) {
                        // v1.3.x 状态收敛:morph 状态由 VM 常驻持有(跨开关周期),
                        // 不再每次打开 remember 新建——cardBounds/progress 不再从
                        // null/0 重新起步,首帧死层/闪屏类时序坑从结构上消失。
                        com.shiyin.music.ui.screens.LyricsMorphOverlay(vm, vm.morph, interactive = true)
                    }
                    // v1.3.11: **播放页/歌词本上也要看得见提示**。
                    // ToastHost 原先只挂在主窗口(AppRoot:750),而 ModalBottomSheet 是**独立窗口**
                    // —— 播放页与歌词本上的任何提示都被它压在下面。用户实测症状:"点翻译按钮
                    // 转圈,没返回就直接停止动画、回到没按过的样式,而且**没有报错**"
                    // (代码确实调了 showToast,但那个 toast 渲染在歌词本下面,根本看不见)。
                    ToastHost(vm)
                }
            }
        }

        // v1.3.3 播放队列:独立 ModalBottomSheet(声明在播放 sheet 之后→后建窗口居上,
        // scrim 盖住播放页)。下拉/返回落在队列自己的 window:只收队列;队列收起后
        // 下拉/返回才轮到播放 sheet。沿用 v5.2 hotfix 3 的 sheetHasBeenVisible 闸门
        // ——rememberModalBottomSheetState 初始值就是 Hidden,不做闸门会在首次组合时
        // 立即触发 Hidden→关闭路径(重蹈"首次组合即误关"坑)。
        if (vm.qSheetOpen && vm.playerOpen) {
            val qSheetState = androidx.compose.material3.rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
            )
            var qSheetHasBeenVisible by androidx.compose.runtime.remember { mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(qSheetState.currentValue, qSheetState.isVisible) {
                if (qSheetState.isVisible) qSheetHasBeenVisible = true
                if (qSheetHasBeenVisible && qSheetState.currentValue == androidx.compose.material3.SheetValue.Hidden && vm.qSheetOpen) {
                    vm.qSheetOpen = false
                    vm.qEdit = false
                }
            }
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { vm.qSheetOpen = false; vm.qEdit = false },
                sheetState = qSheetState,
                // v1.3.3b review#4: 删 sheetMaxWidth=M3 1.3+ 它同时约束 scrim 宽度,
                // 宽屏上 scrim 会不满宽(两侧播放页裸露可点)。项目其他 sheet 也不传。
                dragHandle = null,
                scrimColor = Color.Black.copy(alpha = 0.35f),
                containerColor = c.bg,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                // v5.2 同播放 sheet:置零默认 inset,系统栏由内容自己处理(底部导航栏
                // padding 在 QueueSheetContent 内),背景连续填充到屏幕底缘。
                contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
            ) {
                // 高度由内容撑(替代原 SheetOverlay maxHeightFraction=0.64f):
                // LazyColumn 自带 maxHeight 约束,不足 0.64 屏时按内容收短。
                QueueSheetContent(vm)
            }
        }

        // v1.3.3 三点菜单:独立 ModalBottomSheet(与队列 sheet 同模式,声明在其后)。
        // 下拉/返回只收菜单;timer/speed 子页的返回在内容内由 vm.pMenuView 分支处理
        // (RootBackHandler 的 pMenuView 分支保留作 sheet 外层安全网)。
        if (vm.pMenuView != null && vm.playerOpen) {
            val mSheetState = androidx.compose.material3.rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
            )
            var mSheetHasBeenVisible by androidx.compose.runtime.remember { mutableStateOf(false) }
            androidx.compose.runtime.LaunchedEffect(mSheetState.currentValue, mSheetState.isVisible) {
                if (mSheetState.isVisible) mSheetHasBeenVisible = true
                if (mSheetHasBeenVisible && mSheetState.currentValue == androidx.compose.material3.SheetValue.Hidden && vm.pMenuView != null) {
                    vm.pMenuView = null
                }
            }
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { vm.pMenuView = null },
                sheetState = mSheetState,
                // v1.3.3b review#4: 同队列 sheet——不传 sheetMaxWidth,scrim 满宽。
                dragHandle = null,
                scrimColor = Color.Black.copy(alpha = 0.35f),
                containerColor = c.bg,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
            ) {
                // v1.3.3b: 播放速度子页有自定义速度输入框——contentWindowInsets 置零后
                // sheet 不再自动避让 IME,内容包 imePadding 让输入框随键盘顶起。
                Column(Modifier.imePadding()) {
                    PlayerMenuSheetContent(vm)
                }
            }
        }

        // v1.3.x morph P1(设计文档 §3.1 删除清单):歌词本旧全屏 Dialog 整块删除
        // ——由同窗口 LyricsMorphOverlay 取代(挂播放 sheet 内容 Box,见上方)。
        // 随之失效:winRef/setDimAmount(ModalBottomSheet scrim 本就透明)、
        // MutableTransitionState/AnimatedVisibility 滑入滑出(morph 取代)、
        // LyricsDebug 日志。返回路由见 RootBackHandler lyricsOn 分支(已改收起动画)。

        // (PlayerMenuSheet / QueueSheet / 全屏歌词 overlay 已移入上方 ModalBottomSheet
        //  content，与播放页同一 popup 窗口，确保盖在播放页之上、可点击。)

        // v5.2 Bug1: 设备切换主路径改为 SystemOutputSwitcherDialogController.showDialog
        // （在 PlayerScreen / MiniPlayer 的设备按钮里直接调起系统 Output Switcher）。
        // 此处原先的自建设备 SheetOverlay 已移除——它复刻了系统已提供的设备选择，
        // 且其 setPreferredDevice 路径在 ColorOS 等定制 ROM 上不生效。DeviceRouter
        // 保留用于状态展示（当前输出图标/名称），不再承担"执行音频路由"职责；
        // preferred-device 机制（requestDeviceRouting/onCustomCommand）保留为 fallback。
        // 设备列表的初始填充由 MainViewModel init 的 refreshDeviceList() 负责，
        // 连接变化由 deviceChanges() collector 刷新，不依赖弹层。

        // top-priority sheets + toast
        TrackMenuSheet(vm) { trackEditFor = it }
        SaveToPlaylistSheet(vm)
        AlbumPickerSheet(vm)
        BatchMoveSheet(vm)
        AlbumMenuSheet(vm)
        AlbumEditDialogs(vm, trackEditFor) { trackEditFor = null }
        AlbumMoveSheet(vm)

        // v3.0: multi-artist picker (player → artist-name tap on a collab track)
        ArtistPickerDialog(vm)

        // v2.0: lyrics source picker — surfaced at the AppRoot level so it shows
        // whether the user is on the player screen (lyricsOn=false) or the
        // fullscreen lyrics overlay. Used by both the player menu (三点菜单)
        // and the PlayerScreen Failed-state card.
        LyricsSourcePickerDialog(vm)
        LyricsImportDialog(vm)

        // v1.3.3: Agent 写回的排序确认窗(AppRoot 全局层)。之前确认窗定义在专辑页组合里,
        // Agent 页打开时专辑页不在组合中——窗根本不出现,Agent「写回排序」永远等不到确认,
        // 用户看来就是"写回卡死"。文本排序弹窗自己的确认窗仍在 LibraryScreen(pendingSource="text")。
        val agentPending = vm.pendingOrder
        if (agentPending != null && vm.pendingSource == "agent") {
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { vm.cancelPendingOrder() },
                containerColor = c.surface,
                title = { Text("部分曲目未确定顺序", style = body(15f, FontWeight.ExtraBold, c.text)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("AI 未能确定以下曲目的官方位置:", style = body(12.5f, FontWeight.Normal, c.text))
                        Text(
                            agentPending.second.joinToString("，"),
                            style = body(12.5f, FontWeight.Medium, c.a700),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Text(
                            "「应用」后它们按原相对顺序排在已排序曲目之后;「取消」则本次排序不写入。",
                            style = body(12f, FontWeight.Normal, c.n600),
                        )
                    }
                },
                confirmButton = {
                    DialogActionsRow(
                        listOf(
                            DialogAction("取消") { vm.cancelPendingOrder() },
                            DialogAction("应用", emphasized = true) { vm.commitPendingOrder() },
                        )
                    )
                },
            )
        }

        ToastHost(vm)
    }
}

@Composable
private fun ArtistPickerDialog(vm: MainViewModel) {
    val c = LocalOrganic.current
    val artists = vm.artistPickerFor
    if (artists == null || artists.isEmpty()) return
    // v1.3.x 头像修复(用户报告:多作者选择弹窗头像框没识别到写真):原实现
    // 每行只有通用 User 图标,没接头像链路。歌手页同款两步:fetchArtistAvatar
    // 触发解析(override→cache→Discogs 自动源,cache 命中不触网)+ artistImage
    // 取 URL + rememberCandidateArt 出位图。URL 未就绪/无写真时回退 User 图标。
    androidx.compose.runtime.LaunchedEffect(artists) {
        for (name in artists) vm.fetchArtistAvatar(name)
    }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.artistPickerFor = null },
        containerColor = c.surface,
        title = { Text("选择歌手", style = body(15f, FontWeight.ExtraBold, c.text)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("这首歌曲有多位歌手，请选择要查看的歌手：", style = body(13f, FontWeight.Normal, c.n600))
                Spacer(Modifier.height(8.dp))
                for (name in artists) {
                    val avatarUrl = vm.artistImage(name)
                    val avatarBmp = com.shiyin.music.ui.components.rememberCandidateArt(
                        avatarUrl.ifBlank { null }, 40.dp,
                        // v1.3.10: 弹窗头像同样接自愈——失败就清缓存重解析(personOnly=false,
                        // 与上面的 fetchArtistAvatar 语义一致)。
                        onRemoteLoadFailed = { vm.invalidateArtistImage(name, personOnly = false) },
                    )
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { vm.artistPickerFor = null; vm.openArtist(name) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (avatarBmp != null) {
                            androidx.compose.foundation.Image(
                                bitmap = avatarBmp,
                                contentDescription = name,
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape),
                                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                            )
                        } else {
                            OIcon(Lucide.User, 18.dp, c.text)
                        }
                        Text(name, style = body(14f, FontWeight.SemiBold, c.text))
                    }
                }
            }
        },
        confirmButton = {
            DialogActionsRow(listOf(DialogAction("取消") { vm.artistPickerFor = null }))
        },
    )
}

@Composable
private fun LyricsSourcePickerDialog(vm: MainViewModel) {
    val c = LocalOrganic.current
    if (!vm.lyricsSourcePicker) return
    val context = androidx.compose.ui.platform.LocalContext.current
    val curSrc = vm.currentLyrics?.source
    // v1.3.x: 本地 .lrc 文件——SAF 选文件读文本后按当前曲目导入。target 在启动时捕获,
    // 选文件期间切歌不会挂错歌(importLrcContent 内还有 currentId==targetId 双保险)。
    val pickTarget = vm.player.currentId
    val lrcPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && pickTarget != null) {
            try {
                val name = uri.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "导入.lrc"
                val text = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                if (text != null) vm.importLrcContent(name, text, pickTarget)
            } catch (_: Exception) { }
        }
    }
    // v1.3.x: agent websearch 需 LLM+联网搜索双配置——未配置时置灰(选项副文案已按用户要求移除)。
    val agentReady = vm.activeLlmConfig() != null && vm.tavilyApiKey.isNotBlank()
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.lyricsSourcePicker = false },
        containerColor = c.surface,
        title = { Text("选择歌词来源", style = body(15f, FontWeight.ExtraBold, c.text)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // ── 在线歌词库(秒级,与自动匹配同链路) ──
                // v1.3.10 A29:用**当前可用源**(内置去掉关掉的 + 用户自定义源)
                for (src in com.shiyin.music.data.lyrics.LyricsFetcher.activeSources()) {
                    SourcePickerRow(
                        label = src,
                        active = src == curSrc,
                        enabled = true,
                    ) {
                        vm.lyricsSourcePicker = false
                        vm.rematchSource(src)
                    }
                }
                // ── Agent 联网搜索(10-60 秒,两阶段进度+可取消,见播放页预览卡) ──
                SourcePickerRow(
                    label = "agent websearch",
                    active = curSrc == "agent websearch",
                    enabled = agentReady,
                ) {
                    vm.lyricsSourcePicker = false
                    vm.rematchWithAgentWeb()
                }
                // ── 本地 .lrc 文件(预览卡 Idle/Failed 态的快捷入口保留,此处为常驻入口) ──
                SourcePickerRow(label = "本地 lrc 文件", active = false, enabled = pickTarget != null) {
                    vm.lyricsSourcePicker = false
                    lrcPicker.launch(arrayOf("*/*"))
                }
                // ── 粘贴导入(原"导入歌词文本"根菜单行并入此处) ──
                SourcePickerRow(label = "导入歌词文本", active = false, enabled = true) {
                    vm.lyricsSourcePicker = false
                    vm.lyricsImportDialog = true
                }
            }
        },
        confirmButton = {
            DialogActionsRow(listOf(DialogAction("取消") { vm.lyricsSourcePicker = false }))
        },
    )
}

/** v1.3.x: 歌词来源弹窗的选项行——激活项浅橙高亮底+强调色文字+勾(选择效果),
 *  禁用置灰;零水平内边距,行内容与弹窗标题左对齐。 */
@Composable
private fun SourcePickerRow(
    label: String,
    active: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = LocalOrganic.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            // v1.3.10 需求 A31:选中态加实——底色 a100→a200。
            .background(if (active) c.a200 else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 11.dp),
        horizontalArrangement = Arrangement.spacedBy(11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // v1.3.10 需求 A31(二改):**去掉每源图标**——用户指出"图标跟供应商完全无关",
        // 语义图标反而误导。改成用户给的方案:**选中项前面一个实心圆点**。
        // 非选中项保留等宽空位(9dp),保证所有标签左缘对齐。
        Box(Modifier.size(9.dp), contentAlignment = Alignment.Center) {
            if (active) Box(Modifier.size(8.dp).clip(CircleShape).background(c.accent))
        }
        Text(
            label,
            style = body(
                14f,
                // 选中态:更重的字重 + 更深的强调色
                if (active) FontWeight.ExtraBold else FontWeight.SemiBold,
                when {
                    !enabled -> c.n400
                    active -> c.a800
                    else -> c.n600
                },
            ),
            modifier = Modifier.weight(1f),
        )
    }
}

/** Bug4d fix: render the lyrics-import paste dialog at top level so it works
 *  from the player menu too (previously the dialog only lived inside
 *  LyricsScreen, which is unreachable when lyricsOn=false). Uses the playing
 *  track's cover-derived palette so it matches the rest of the lyrics UI. */
@Composable
private fun LyricsImportDialog(vm: MainViewModel) {
    if (!vm.lyricsImportDialog) return
    val track = vm.trackById(vm.player.currentId) ?: run {
        vm.lyricsImportDialog = false
        return
    }
    // v5.2 #73: 改用 LocalOrganic(c.*) 而非 MaterialTheme.colorScheme——后者只映射了
    // surface/onSurface,onSurfaceVariant/outline 仍是 lightColorScheme 默认(深色),在 app
    // 深色 surface 上 placeholder/border 看不清、整片黑漆漆。改用 c.* 与其他 app dialog
    // (如 LyricsSourcePickerDialog)一致,placeholder/border 在深色背景上可读。
    val c = LocalOrganic.current
    val containerColor = c.surface
    val onColor = c.text
    val onSurfaceVariant = c.n600
    val outlineColor = c.n400
    var importText by remember(track.id) { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.lyricsImportDialog = false },
        containerColor = containerColor,
        title = { Text("导入歌词文本", style = body(15f, FontWeight.ExtraBold, onColor)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "粘贴歌词文本，支持 LRC 格式（[MM:SS.xxx]歌词）或纯文本。",
                    style = body(12.5f, FontWeight.Normal, onSurfaceVariant).copy(lineHeight = 18.sp),
                )
                androidx.compose.material3.OutlinedTextField(
                    value = importText,
                    onValueChange = { importText = it },
                    placeholder = { Text("在此粘贴歌词…", style = body(14f, FontWeight.Normal, onSurfaceVariant)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp, max = 300.dp),
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedTextColor = onColor,
                        unfocusedTextColor = onColor,
                        cursorColor = onColor,
                        focusedBorderColor = onColor,
                        unfocusedBorderColor = outlineColor,
                    ),
                )
            }
        },
        confirmButton = {
            DialogActionsRow(
                listOf(
                    DialogAction("取消") { vm.lyricsImportDialog = false },
                    DialogAction("导入", emphasized = true) {
                        if (importText.isNotBlank()) vm.importLrcContent("手动导入", importText, track.id)
                        vm.lyricsImportDialog = false
                    },
                )
            )
        },
    )
}

@Composable
private fun RootBackHandler(vm: MainViewModel) {
    BackHandler(
        enabled = vm.saveSheetFor != null || vm.albumPickerId != null || vm.albumMoveFor != null || vm.trackMenuFor != null || vm.pMenuView != null ||
            vm.qSheetOpen || vm.lySheet || vm.lyricsOn || vm.sleepMenu || vm.playerOpen ||
            vm.artistMerge || vm.albumKey != null || vm.artistKey != null || vm.folderKey != null || vm.plId != null ||
            vm.settingsOpen || vm.recentOpen || vm.statsOpen || vm.updatesOpen || vm.agentOpen || vm.splitEditFor != null ||
            vm.q.isNotEmpty() || vm.tab != Tab.Home || vm.devicePopupVisible || vm.sidebarOpen ||
            vm.albumBatchMoveSheet || vm.batchMoveMode,
    ) {
        when {
            vm.sidebarOpen -> vm.sidebarOpen = false
            vm.devicePopupVisible -> vm.devicePopupVisible = false
            vm.albumBatchMoveSheet -> vm.albumBatchMoveSheet = false
            vm.batchMoveMode -> { vm.batchMoveMode = false; vm.batchMoveSelected = emptySet() }
            vm.recentOpen -> vm.recentOpen = false
            vm.statsOpen -> vm.statsOpen = false
            vm.updatesOpen -> vm.updatesOpen = false
            vm.agentOpen -> { vm.persistAgentConversation(); vm.agentOpen = false }
            vm.splitEditFor != null -> vm.splitCancel()
            vm.saveSheetFor != null -> vm.saveSheetFor = null
            vm.lyricsImportDialog -> vm.lyricsImportDialog = false
            vm.lyricsSourcePicker -> vm.lyricsSourcePicker = false
            vm.albumPickerId != null -> vm.albumPickerId = null
            vm.albumMoveFor != null -> vm.albumMoveFor = null
            vm.trackMenuFor != null -> vm.trackMenuFor = null
            vm.pMenuView == "timer-custom" -> vm.pMenuView = "timer"
            vm.pMenuView == "timer" -> vm.pMenuView = "root"
            vm.pMenuView == "speed" -> vm.pMenuView = "root"
            vm.pMenuView == "karaoke" -> vm.pMenuView = "root"
            vm.pMenuView != null -> vm.pMenuView = null
            vm.qSheetOpen -> { vm.qSheetOpen = false; vm.qEdit = false }
            vm.lySheet -> vm.lySheet = false
            // v1.3.x morph P1(§3.6-1):物理返回键同走收起动画(overlay 完成监听
            // 到 0 才置 lyricsOn=false,同"动画完再销毁"模式)。
            vm.lyricsOn -> vm.requestLyricsExit()
            vm.sleepMenu -> vm.sleepMenu = false
            // v1.3.x bug① 修复(与 sheet 的 shouldDismissOnBackPress=false 同一改动):
            // 歌词关着时的系统返回关播放页,现在走 requestPlayerExit——sheetState.hide()
            // 播下滑动画,hotfix-3(currentValue==Hidden)收尾置 playerOpen=false。
            // 原来直接置 false 是因为 sheet 窗口自己的返回回调抢走了手势、这里只作
            // 罕见兜底;返回回调穿透后这里成为主路径,必须补回动画,否则"播放页退出
            // 瞬间消失"只是换了个地方错。
            vm.playerOpen -> vm.requestPlayerExit()
            vm.artistMerge -> vm.artistMerge = false
            vm.settingsOpen -> {
                if (vm.folderKey != null) {
                    // 文件夹内容页:清 folderKey + 弹栈回文件夹管理(不再直接写 Root)
                    vm.folderKey = null
                    vm.navFilesBack()
                } else if (vm.cleanEntry) {
                    // v1.3.5: 首页清理建议卡直接进的清理页——返回跳过"设置根"直接
                    // 回来源 tab(从哪来回哪)。退出设置即复位标记。
                    vm.cleanEntry = false
                    vm.sel = emptyMap()
                    vm.settingsOpen = false
                    vm.restoreTabIfDrillFullyClosed()
                } else {
                    // v1.3.x 第4项:统一走返回栈逐级退出(navFilesBack pop),
                    // 不再一律跳回设置根(那是"三级页手势返回跳过中间层"的根因)。
                    // 栈空(已在根)才关设置。
                    if (!vm.navFilesBack()) vm.settingsOpen = false
                    vm.sel = emptyMap()
                }
            }
            vm.plId != null -> { vm.plId = null; vm.restoreTabIfDrillFullyClosed() }
            vm.albumKey != null -> {
                vm.albumKey = null; vm.albumEdit = false; vm.albumEditText = false
                // v1.3.3 返回恢复:若下钻层已全清(如从首页进的专辑),tab 恢复原页面
                // (原来留在 Library → "回音乐库不回首页"的根因)。清歌手页快照时机也
                // 在这里:完整退出歌手页(artistKey 也空)清 artistUiState,不残留。
                if (!vm.restoreTabIfDrillFullyClosed() && vm.artistKey == null) vm.clearArtistUiState()
            }
            vm.artistKey != null -> {
                vm.artistKey = null
                // 完整退出歌手页(无更深层)→ 清该歌手的 UI 快照,下次进入从顶部开始;
                // 下钻专辑再返回时不会走到这(artistKey 非空),快照保留待恢复。
                if (vm.albumKey == null && vm.plId == null) vm.clearArtistUiState()
                vm.restoreTabIfDrillFullyClosed()
            }
            vm.q.isNotEmpty() -> vm.q = ""
            vm.tab != Tab.Home -> vm.tab = Tab.Home
        }
    }
}

// ── v1.3/v1.5 mini player: square card, bottom progress, device+fav+play ───

/** 迷你播放条封面边长(封面轨道与文字页前导留白共用,必须一致否则会错位)。 */
private val MiniCoverSize = 40.dp

/** v1.3.x 需求4:封面与歌名/歌手文字区之间的留白。
 *  原为 11dp,用户反馈"封面和歌名、歌手名之间距离太近",加大到 20dp;
 *  歌名↔歌手彼此仍保持原有紧凑行距(未动 Column 内两行)。 */
private val MiniCoverTextGap = 20.dp

/** v1.3.x 需求4:松手切歌的位移阈值 = 信息区宽的 31%(约三分之一页,符合翻页直觉)。 */
private const val SETTLE_FRACTION = 0.31f

@Composable
private fun MiniPlayer(vm: MainViewModel, track: Track) {
    val c = LocalOrganic.current
    // v5.2 Bug1: Activity context for SystemOutputSwitcherDialogController.showDialog
    val ctx = LocalContext.current
    // v22: 显示坐标 —— 拆分单曲的进度按"当前这首"算，不是整轨文件
    val durMs = if (vm.player.displayDurationMs() > 0) vm.player.displayDurationMs() else track.durationMs
    val pct = if (durMs > 0) (vm.player.displayPositionMs().toFloat() / durMs).coerceIn(0f, 1f) else 0f
    Box(
        Modifier
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
            .fillMaxWidth()
            .shadowMd(RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(c.surface),
    ) {
        // v1.3.x 需求4(横向分页重做 2026-09-15):切歌区域 = "左上一首·中当前·右下一首"
        // 三页常驻的横向分页。跟手 offsetX 是纯 graphicsLayer X 位移(逐帧 O(1)、零重组、
        // 无任何 Y 分量);松手过阈值/惯性阈值 → 沿 X 推完剩余行程后才提交 vm.player.next()
        // /prev()(提交后置,内容不在动画中途替换),未达阈值 → 沿 X 回到原位。
        // 同专辑封面稳定:当前曲与"拖动方向上的邻曲"albumId 相同时封面不随页位移(整轨
        // 静止),只有歌名/歌手横移;跨专辑时封面才随页一起横向替换。
        val density = androidx.compose.ui.platform.LocalDensity.current
        var infoWidthPx by remember { mutableIntStateOf(0) }
        val pages = remember(track.id) {
            // track 变化(任何切歌路径)重算三页——左=prev、中=当前、右=next(回绕补)。
            // 三页数据在动画开始前就已在手上,动画期间不查库、不重排。
            Triple(
                vm.player.prevTrackId()?.let { vm.trackById(it) },
                track,
                (vm.player.nextTrackId() ?: vm.player.wrappedNextTrackId())?.let { vm.trackById(it) },
            )
        }
        val scope = rememberCoroutineScope()
        // 单一真源:拖动与收尾动画都只写这一个 float state;读点全在 graphicsLayer 内
        // (延迟读),故逐帧位移不触发任何重组。
        var offsetX by remember(track.id) { mutableFloatStateOf(0f) }
        // 页宽 = 信息区实宽;首帧未测量前用 120dp 兜底,避免零位移/除零。
        val pageWidthState = rememberUpdatedState(
            with(density) { infoWidthPx.toFloat().coerceAtLeast(120.dp.toPx()) },
        )
        val velocityThresholdState = rememberUpdatedState(with(density) { 900.dp.toPx() })
        val pagesState = rememberUpdatedState(pages)
        // 沿 X 轴走完到 target:target==0 用弹簧回原位,否则用 tween 推完剩余行程。
        // 两者都只改 X 分量,结构上不可能产生纵向弹跳。
        val slidePage: suspend (Float, Float) -> Unit = { target, vel ->
            androidx.compose.animation.core.animate(
                initialValue = offsetX,
                targetValue = target,
                initialVelocity = vel,
                animationSpec = if (target == 0f) {
                    spring(dampingRatio = 0.78f, stiffness = 480f)
                } else {
                    tween(170, easing = CubicBezierEasing(0.22f, 0.72f, 0f, 1f))
                },
            ) { value, _ -> offsetX = value }
        }
        // 推到邻页位置后提交切歌。track.id 变化时 offsetX 由 remember(track.id) 随之重建
        // 为 0,新"当前页"正是刚推到中央的那一页,交接无跳帧;这里的显式复位是幂等兜底
        // (队列边界等 track 未变的异常情况下把视图拉回中央,不卡在邻页)。
        val commitThenReset: suspend (() -> Unit) -> Unit = { commit ->
            commit()
            delay(400)
            offsetX = 0f
        }
        Row(
            Modifier
                .fillMaxWidth()
                // v1.3.x 需求4(横向分页重做 2026-09-15):手势与位移全部由分页轨道承担,
                // 本 Row 只负责「信息区 + 按钮组」横向排布。原先「松手才判定、内容不跟手」
                // 的手势已删除——它与新轨道共存会双份消费同一个水平手势。
                .pointerInput(track.id) {
                    // 闭包只捕获稳定对象;页数据/阈值经 rememberUpdatedState 延迟读,
                    // 避免 pointerInput 因阈值变化重启而打断进行中的拖拽。
                    var dragging = false
                    var settleJob: Job? = null
                    val tracker = androidx.compose.ui.input.pointer.util.VelocityTracker()
                    detectHorizontalDragGestures(
                        onDragStart = {
                            settleJob?.cancel()
                            dragging = true
                            tracker.resetTracking()
                        },
                        onDragEnd = {
                            if (!dragging) return@detectHorizontalDragGestures
                            dragging = false
                            val v = tracker.calculateVelocity().x
                            val pageW = pageWidthState.value
                            val threshold = pageW * SETTLE_FRACTION
                            val velThreshold = velocityThresholdState.value
                            val cur = offsetX
                            val hasPrev = pagesState.value.first != null
                            val hasNext = pagesState.value.third != null
                            settleJob = scope.launch {
                                when {
                                    // 左滑(负位移)→ 下一首自右侧进入;右滑 → 上一首自左侧进入。
                                    (cur <= -threshold || v <= -velThreshold) && hasNext -> {
                                        slidePage(-pageW, v)
                                        commitThenReset { vm.player.next() }
                                    }
                                    (cur >= threshold || v >= velThreshold) && hasPrev -> {
                                        slidePage(pageW, v)
                                        commitThenReset { vm.player.prev() }
                                    }
                                    // 未达阈值 / 该方向无邻页:沿 X 平滑回原位(纯水平,无纵向弹跳)。
                                    else -> slidePage(0f, v)
                                }
                            }
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            // 该方向没有邻页时不产生位移(不是回弹,是压根不动)。
                            val p = pagesState.value
                            offsetX = (offsetX + dragAmount).let { x ->
                                when {
                                    x < 0f && p.third == null -> 0f
                                    x > 0f && p.first == null -> 0f
                                    else -> x
                                }
                            }
                            if (change.positionChanged()) {
                                tracker.addPosition(change.uptimeMillis, change.position)
                                change.consume()
                            }
                        },
                    )
                }
                .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 分页轨道:三页(左=prev / 中=current / 右=next)各宽 = 信息区宽,横向平铺;
            // 相邻页靠 X 位移进入 clipToBounds 的可见区。
            BoxWithConstraints(
                Modifier
                    .weight(1f)
                    .clipToBounds()
                    .onSizeChanged { infoWidthPx = it.width },
            ) {
                val pageW = constraints.maxWidth.toFloat()
                val currentTrack = pages.second
                val prevPage = pages.first
                val nextPage = pages.third
                // 固定三槽:0=左(prev) 1=中(当前) 2=右(next)。槽位必须固定——若用
                // listOfNotNull 压掉空槽,"当前页"会落到槽 0 而被整体左移一整页。
                val slots = listOf(prevPage, currentTrack, nextPage)
                // v1.3.10 需求 A17:封面不再需要 prevSameAlbum/nextSameAlbum(它曾用来决定
                // 封面跟不跟页走)。现在封面**恒定不动**,跨专辑的切换由 CoverArt 的淡化承担,
                // 所以那对判断连同 currentAlbumId 一并删掉,不留死代码。
                // 文字页轨道:三页各宽 = 信息区宽,页位 = (槽 - 1) * 页宽,整体跟手 offsetX。
                // 页内前导留白 = 封面宽 + 间距,给独立的封面轨道让位。
                slots.forEachIndexed { slotIndex, pageTrack ->
                    if (pageTrack == null) return@forEachIndexed
                    val pageBaseX = (slotIndex - 1) * pageW
                    // key 必须含槽位!仅用 track.id 会在"上一首/下一首回绕到同一首"时
                    // 产生重复 key(单曲队列 + REPEAT_ALL:previousMediaItemIndex 回绕到
                    // 自身;双曲队列:prev 与 next 是同一首)——Compose 对同层重复 key
                    // 直接抛 IllegalArgumentException 崩溃。槽位唯一,加上即无碰撞;
                    // 同时保留"该槽换曲即重建"的语义。
                    key(slotIndex, pageTrack.id) {
                        Row(
                            Modifier
                                // 与封面轨道同为 CenterStart 锚点,保证两轨垂直居中对齐
                                // (文字两行比 40dp 封面矮时不会贴顶)。
                                .align(Alignment.CenterStart)
                                .graphicsLayer {
                                    // 延迟读 offsetX:拖动逐帧只重跑本 lambda,零重组。
                                    translationX = pageBaseX + offsetX
                                }
                                .width(with(density) { pageW.toDp() }),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Spacer(Modifier.width(MiniCoverSize + MiniCoverTextGap))
                            Column(
                                Modifier
                                    .weight(1f)
                                    // v1.3.x 需求5:去涟漪。
                                    .clickable(
                                        interactionSource = androidx.compose.foundation.interaction.MutableInteractionSource(),
                                        indication = null,
                                    ) { vm.playerOpen = true },
                            ) {
                                Text(pageTrack.title, style = body(13.5f, FontWeight.Bold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                // v1.3.10: 补 overflow——原实现只有 maxLines,Compose 默认 Clip,
                                // "揽佬SKAI ISYOURGOD" 这类长名被硬裁成"揽佬SKAI"且无省略号,
                                // 看起来像名字只剩一半(用户反馈"歌手名显示不全")。与上一行同款省略号。
                                Text(pageTrack.artist, style = body(11.5f, FontWeight.Normal, c.n600), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                // ── 封面轨道:封面**位置固定,永不跟拖动走** ──────────────────────────
                // 设计意图(用户 2026-09-28 明确):左右滑切歌时"走的是歌名/歌手,封面不动"
                // ——所以封面的切换效果必须由 CoverArt 自己的**跨专辑交叉淡化**承担
                // (crossFade=true),而不是让它横移跟着页面替换。
                //
                // 因此三个槽位只按 pageBaseX 静态摆位(中间槽可见,左右槽在屏外),
                // **不加 offsetX**:拖动/回弹期间封面纹丝不动。
                // 邻槽仍然要被组合——它们的封面会在后台加载进内存 LruCache,
                // 于是提交切换后新封面第一帧就能拿到,淡化才有东西可淡(不会落到首字占位)。
                slots.forEachIndexed { slotIndex, pageTrack ->
                    if (pageTrack == null) return@forEachIndexed
                    val pageBaseX = (slotIndex - 1) * pageW
                    // v1.3.10 需求 A17:封面槽的 key **只含槽位,不含 track.id**。
                    // 为什么:换歌时若 key 变化,这个 CoverArt 实例会被重建 → 它 `remember`
                    // 里的"上一张已就绪封面"(lastArt)被清空 → 跨专辑淡化失去垫底的旧封面,
                    // 且新曲封面未就绪时会落到**首字占位**(用户报的"原封面→首字→新封面"闪烁)。
                    // 只按槽位作 key(0/1/2 天然唯一)保留实例与 lastArt,淡化才成立。
                    key(slotIndex) {
                        Box(
                            Modifier
                                .align(Alignment.CenterStart)
                                .graphicsLayer { translationX = pageBaseX }
                                .clickable { vm.playerOpen = true },
                        ) {
                            // v1.3.10 需求 A17:封面**固定不动**(见上方注释),跨专辑换封面
                            // 用**翻面**动画交代——用户明确要的"翻面过渡效果"。
                            CoverArt(
                                pageTrack, MiniCoverSize, RoundedCornerShape(8.dp), fontSize = 16,
                                transition = CoverTransition.FLIP,
                            )
                        }
                    }
                }
            }
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    // v5.2 Bug1: 主路径——调起系统 Output Switcher（非自建弹层）
                    .clickable { SystemOutputSwitcherDialogController.showDialog(ctx) },
                contentAlignment = Alignment.Center,
            ) {
                // v5.2: mini 设备按钮保持默认图标（用户反馈：随设备变图标反而违和、
                // 切蓝牙不变色。回退到恒显 Speaker，点击仍调系统 Output Switcher）。
                OIcon(Lucide.Speaker, 17.dp, c.n600)
            }
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .combinedClickable(
                        onClick = { vm.toggleFav(track.id) },
                        onLongClick = { vm.saveSheetFor = track.id },
                    ),
                contentAlignment = Alignment.Center,
            ) { FavIcon(vm.isFav(track.id), 18.dp, c.n600) }
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(c.accent)
                    .clickable { vm.player.toggle() },
                contentAlignment = Alignment.Center,
            ) {
                OIcon(if (vm.player.isPlaying) Lucide.Pause else Lucide.Play, 16.dp, Color.White)
            }
        }
        // bottom progress line, inset 10dp per v1.3
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 10.dp)
                .fillMaxWidth()
                .height(2.5.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(c.text.copy(alpha = 0.14f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(pct)
                    .height(2.5.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(c.accent)
            )
        }
    }
}

@Composable
private fun BottomNav(vm: MainViewModel) {
    val c = LocalOrganic.current
    Row(
        Modifier
            .fillMaxWidth()
            .background(c.surface)
            .padding(start = 6.dp, end = 6.dp, top = 8.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val items = listOf(
            Triple(Tab.Home, Lucide.Home, "首页"),
            Triple(Tab.Search, Lucide.Search, "搜索"),
            Triple(Tab.Library, Lucide.Library, "音乐库"),
        )
        for ((tab, icon, label) in items) {
            val active = vm.tab == tab && !vm.settingsOpen
            val tint = if (active) c.a700 else c.n500
            Column(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable {
                        // v1.2.0 #6 修复：从歌手/专辑等下钻页点底部导航应回该 tab 根，
                        // 不能只切 tab（artistKey/albumKey 仍在 → 歌手页不退 → "没反应"）。
                        vm.tab = tab
                        vm.settingsOpen = false
                        // v1.3.3 返回恢复:用户主动切 tab → 作废记录的下钻来源 tab,
                        // 且主动离开歌手页也要清其快照(不残留)。
                        vm.discardPrevTab()
                        vm.clearArtistUiState()
                        vm.artistKey = null
                        vm.albumKey = null
                        vm.plId = null
                    }
                    .padding(vertical = 5.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                OIcon(icon, 21.dp, tint)
                Text(label, style = body(10.5f, FontWeight.Bold, tint))
            }
        }
    }
}

// ── v1.6/v1.7 track ⋮ menu ─────────────────────────────────────────────────
@Composable
private fun BoxScope.TrackMenuSheet(vm: MainViewModel, onEditTrack: (Long) -> Unit) {
    val c = LocalOrganic.current
    val track = vm.trackMenuFor?.let { vm.trackById(it) }
    SheetOverlay(visible = track != null, onDismiss = { vm.trackMenuFor = null }) {
        if (track == null) return@SheetOverlay
        Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 16.dp, bottom = 10.dp)) {
            SheetSongHeader(track)
            SheetActionRow(Lucide.ListPlus, "保存到歌单") {
                vm.trackMenuFor = null
                vm.saveSheetFor = track.id
            }
            SheetActionRow(Lucide.ListQueue, "添加到播放队列") { vm.addToQueue(track.id) }
            if (track.album != com.shiyin.music.data.NO_ALBUM) {
                SheetActionRow(Lucide.Disc, "查看专辑") { vm.goAlbumOf(track.id, highlight = false) }
            }
            SheetActionRow(Lucide.User, "查看歌手") { vm.goArtistOf(track.id) }
            if (track.album == com.shiyin.music.data.NO_ALBUM) {
                SheetActionRow(Lucide.Disc, "归类到专辑") {
                    vm.trackMenuFor = null
                    vm.albumPickerId = track.id
                }
            }
            // v4.3: edit track info (title/artist/note) — display-level override
            SheetActionRow(Lucide.ListRows, "修改歌曲信息") {
                vm.trackMenuFor = null
                onEditTrack(track.id)
            }
            // v5.2 隐藏曲:整张播放时跳过、列表里变灰,但单曲点击仍能播。
            // 在 track 菜单里给一个开关让用户把不想听的曲灰掉。
            if (vm.isHidden(track.id)) {
                SheetActionRow(Icons.Filled.Visibility, "恢复播放", tint = c.a700) {
                    vm.toggleTrackHidden(track.id)
                    vm.trackMenuFor = null
                }
            } else {
                SheetActionRow(Icons.Filled.VisibilityOff, "隐藏此曲") {
                    vm.toggleTrackHidden(track.id)
                    vm.trackMenuFor = null
                }
            }
            SheetDivider()
            SheetActionRow(Lucide.Trash, "移入回收站", tint = c.a700) {
                vm.trackMenuFor = null
                vm.requestTrash(listOf(track.id))
            }
        }
    }
}

// ── v1.5/v1.6 player ⋮ menu with sleep-timer subview ───────────────────────
// v1.3.3: 外层 SheetOverlay 已移除——由 AppRoot 的独立 ModalBottomSheet 包裹本
// 内容(与 QueueSheet 同模式),手势/返回只作用菜单弹层、不动播放页。
// 子页跳转(timer/speed→root)仍在内容内用 vm.pMenuView 切换。
@Composable
private fun PlayerMenuSheetContent(vm: MainViewModel) {
    val c = LocalOrganic.current
    val track = vm.trackById(vm.player.currentId)
    if (track == null) return
    Column(Modifier.navigationBarsPadding().padding(start = 10.dp, end = 10.dp, top = 16.dp, bottom = 10.dp)) {
            if (vm.pMenuView != "timer" && vm.pMenuView != "timer-custom" &&
                vm.pMenuView != "speed" && vm.pMenuView != "karaoke") {
                SheetSongHeader(track)
                SheetActionRow(Lucide.ListPlus, "保存到歌单") {
                    vm.pMenuView = null
                    vm.saveSheetFor = track.id
                }
                if (track.album != com.shiyin.music.data.NO_ALBUM) {
                    SheetActionRow(Lucide.Disc, "查看专辑") { vm.goAlbumOf(track.id, highlight = false) }
                }
                SheetActionRow(Lucide.User, "查看歌手") { vm.goArtistOf(track.id) }
                // v1.3.x: "播放页设置"子页入口移除(用户 2026-09 拍板)——睡眠定时/
                // 播放速度直接放根菜单(带状态尾注,点击进各自子页);无缝播放/
                // 自动匹配歌词只留在系统设置页,不再在播放页镜像。
                SheetActionRow(
                    Lucide.Clock, "睡眠定时",
                    trailing = {
                        Text(vm.player.sleepStatusText(), style = body(12.5f, FontWeight.SemiBold, c.n500))
                        OIcon(Lucide.ChevronRight, 16.dp, c.n500)
                    },
                ) { vm.pMenuView = "timer" }
                SheetActionRow(
                    Icons.Filled.Speed, "播放速度",
                    trailing = {
                        val spd = if (vm.playbackSpeed == 1.0f) "正常" else "%.2f×".format(vm.playbackSpeed)
                        Text(spd, style = body(12.5f, FontWeight.SemiBold, c.n500))
                        OIcon(Lucide.ChevronRight, 16.dp, c.n500)
                    },
                ) { vm.pMenuView = "speed" }
                // v1.5.0 伴唱（规格 §3.1 第二入口）：与歌词页麦克风按钮打开同一面板。
                // 未开启伴唱功能时整行隐藏（与歌词页入口的隐藏条件一致）。
                if (vm.karaokeEnabled) {
                    SheetActionRow(
                        Lucide.Mic, "伴唱",
                        trailing = {
                            if (vm.karaokeState != com.shiyin.music.ui.components.KaraokeButtonState.Idle) {
                                Text(
                                    listOf("人声", "原曲", "伴奏")[vm.karaokeMixIndex],
                                    style = body(12.5f, FontWeight.SemiBold, c.n500),
                                )
                            }
                            OIcon(Lucide.ChevronRight, 16.dp, c.n500)
                        },
                    ) { vm.pMenuView = "karaoke" }
                }
                // v1.3.x: 图标 Captions(歌词/字幕语义)——原 ListPlus 与"保存到歌单"撞图标。
                SheetActionRow(Lucide.Captions, "歌词来源", trailing = {
                    Text(
                        vm.currentLyrics?.source ?: "未匹配",
                        style = body(12.5f, FontWeight.SemiBold, c.n500),
                    )
                    OIcon(Lucide.ChevronRight, 16.dp, c.n500)
                }) {
                    vm.pMenuView = null
                    vm.lyricsSourcePicker = true
                }
                // v1.3.x: "导入歌词文本"根菜单行移除——并入"歌词来源"弹窗第五项(粘贴弹窗
                // 本体保留,只挪入口)。
                // v5.2 隐藏曲:播放页菜单也提供同样开关,方便在听的时候即时跳过。
                if (vm.isHidden(track.id)) {
                    SheetActionRow(Icons.Filled.Visibility, "恢复播放", tint = c.a700) {
                        vm.pMenuView = null
                        vm.toggleTrackHidden(track.id)
                    }
                } else {
                    SheetActionRow(Icons.Filled.VisibilityOff, "隐藏此曲") {
                        vm.pMenuView = null
                        vm.toggleTrackHidden(track.id)
                    }
                }
                // v1.3.x: 睡眠定时/播放速度两行收进"播放页设置"子页,根菜单只留动作类入口。
                SheetDivider()
                SheetActionRow(Lucide.Trash, "移入回收站", tint = c.a700) {
                    vm.pMenuView = null
                    vm.requestTrash(listOf(track.id))
                }
            } else if (vm.pMenuView == "timer-custom") {
                // v5.2: custom duration — hours + minutes sliders, defaults 0h 30m.
                var h by remember { mutableFloatStateOf(0f) }
                var m by remember { mutableFloatStateOf(30f) }
                val total = h.roundToInt() * 60 + m.roundToInt()
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .clickable { vm.pMenuView = "timer" },
                            contentAlignment = Alignment.Center,
                        ) { OIcon(Lucide.ChevronLeft, 17.dp, c.text) }
                        Text("自定义定时", style = body(15f, FontWeight.ExtraBold, c.text))
                        Spacer(Modifier.weight(1f))
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "${h.roundToInt()} 小时 ${m.roundToInt()} 分钟后停止",
                        style = body(14.5f, FontWeight.Normal, c.text),
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("小时", style = body(13f, FontWeight.Normal, c.n500), modifier = Modifier.width(36.dp))
                        // 连续滑块，无 steps → 无刻度点；取值时 roundToInt
                        Slider(
                            value = h,
                            onValueChange = { h = it.roundToInt().toFloat().coerceIn(0f, 10f) },
                            valueRange = 0f..10f,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${h.roundToInt()}",
                            style = body(13f, FontWeight.SemiBold, c.text),
                            modifier = Modifier.width(32.dp).padding(start = 8.dp),
                        )
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("分钟", style = body(13f, FontWeight.Normal, c.n500), modifier = Modifier.width(36.dp))
                        // 连续滑块，无 steps → 无刻度点；取值时 roundToInt
                        Slider(
                            value = m,
                            onValueChange = { m = it.roundToInt().toFloat().coerceIn(0f, 59f) },
                            valueRange = 0f..59f,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            "${m.roundToInt()}",
                            style = body(13f, FontWeight.SemiBold, c.text),
                            modifier = Modifier.width(32.dp).padding(start = 8.dp),
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "开始定时",
                        style = body(14.5f, FontWeight.SemiBold, if (total > 0) c.accent else c.n400),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(enabled = total > 0) {
                                vm.player.setSleepTimer(total)
                                vm.pMenuView = null
                            }
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                    )
                }
            } else if (vm.pMenuView == "timer") {
                // timer subview
                Column {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(start = 4.dp, end = 4.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .clickable { vm.pMenuView = "root" },
                            contentAlignment = Alignment.Center,
                        ) { OIcon(Lucide.ChevronLeft, 17.dp, c.text) }
                        Text("睡眠定时", style = body(15f, FontWeight.ExtraBold, c.text))
                        Spacer(Modifier.weight(1f))
                        Text(vm.player.sleepStatusText(), style = body(12.5f, FontWeight.Normal, c.n500))
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { vm.player.setSleepEndOfTrack(); vm.pMenuView = null }
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("本曲结束后暂停", style = body(14.5f, FontWeight.Normal, c.text), modifier = Modifier.weight(1f))
                        if (vm.player.sleepMode == 2) {
                            OIcon(Lucide.CheckBold, 16.dp, c.accent)
                        }
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { vm.pMenuView = "timer-custom" }
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("自定义…", style = body(14.5f, FontWeight.Normal, c.text), modifier = Modifier.weight(1f))
                        OIcon(Lucide.ChevronRight, 16.dp, c.n500)
                    }
                    for (min in listOf(15, 30, 60)) {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { vm.player.setSleepTimer(min); vm.pMenuView = null }
                                .padding(horizontal = 10.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("$min 分钟后停止", style = body(14.5f, FontWeight.Normal, c.text), modifier = Modifier.weight(1f))
                            if (vm.player.sleepChosenMin == min && vm.player.sleepLeftSec > 0) {
                                OIcon(Lucide.CheckBold, 16.dp, c.accent)
                            }
                        }
                    }
                    Text(
                        "关闭定时",
                        style = body(14.5f, FontWeight.Normal, c.a700),
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { vm.player.setSleepTimer(0); vm.pMenuView = null }
                            .padding(horizontal = 10.dp, vertical = 12.dp),
                    )
                }
            } else if (vm.pMenuView == "speed") {
                // v2: 播放速度调节 — 0.05 步进滑块 + 自定义输入 + 复古/现代模式
                Column {
                    // 返回键：只用图标，不用文字
                    Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(34.dp).clip(CircleShape).clickable { vm.pMenuView = "root" },
                            contentAlignment = Alignment.Center,
                        ) { OIcon(Lucide.ChevronLeft, 20.dp, c.n500) }
                        Spacer(Modifier.weight(1f))
                    }
                    // 当前速度大字显示（格式化避免浮点误差）
                    val speedDisplay = "%.2f×".format(vm.playbackSpeed)
                    Text(
                        speedDisplay,
                        style = heading(36, c.text),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                        textAlign = TextAlign.Center,
                    )
                    Text(
                        if (vm.retroSpeedMode) "复古模式 · 音调随速度变化" else "现代模式 · 音调不变",
                        style = body(12f, FontWeight.Normal, c.n500),
                        modifier = Modifier.fillMaxWidth().padding(bottom = 18.dp),
                        textAlign = TextAlign.Center,
                    )
                    // 连续滑块，无 steps → 无刻度点；onValueChange 内 0.05 量化
                    var sliderVal by remember { mutableFloatStateOf(vm.playbackSpeed) }
                    androidx.compose.material3.Slider(
                        value = sliderVal,
                        onValueChange = {
                            val stepped = com.shiyin.music.playback.quantizeSpeed(it)
                            sliderVal = stepped
                            vm.setSpeed(stepped)
                        },
                        valueRange = 0.5f..2.0f,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                    // 快捷按钮 + 自定义输入
                    Row(
                        Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val presets = listOf(1.0f, 1.1f, 1.2f, 2.0f)
                        for (p in presets) {
                            val isCur = kotlin.math.abs(vm.playbackSpeed - p) < 0.01f
                            Text(
                                if (p == 1.0f) "正常" else "%.1f×".format(p),
                                style = body(12.5f, if (isCur) FontWeight.ExtraBold else FontWeight.Normal, if (isCur) c.a700 else c.n600),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(999.dp))
                                    .background(if (isCur) c.n100 else Color.Transparent)
                                    .clickable { sliderVal = p; vm.setSpeed(p) }
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                            )
                        }
                        // 自定义输入框——软键盘「完成」(onDone) 或点「应用」均立即生效
                        var inputText by remember { mutableStateOf("") }
                        var inputError by remember { mutableStateOf(false) }
                        val applyInput = {
                            val stepped = com.shiyin.music.playback.parseSpeedInput(inputText)
                            if (stepped != null) {
                                sliderVal = stepped
                                vm.setSpeed(stepped)
                                inputText = ""
                                inputError = false
                            } else {
                                inputError = true
                            }
                        }
                        androidx.compose.material3.OutlinedTextField(
                            value = inputText,
                            onValueChange = { raw ->
                                inputText = raw.filter { it.isDigit() || it == '.' }
                                inputError = false
                            },
                            placeholder = { Text("自定义", style = body(11f, FontWeight.Normal, c.n400)) },
                            singleLine = true,
                            isError = inputError,
                            textStyle = body(12.5f, FontWeight.Normal, if (inputError) c.a700 else c.text),
                            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal,
                                imeAction = androidx.compose.ui.text.input.ImeAction.Done,
                            ),
                            keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                                onDone = { applyInput() },
                            ),
                            modifier = Modifier
                                .width(64.dp)
                                .height(36.dp),
                        )
                        Text(
                            "应用",
                            style = body(12.5f, FontWeight.Bold, c.accent),
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .clickable { applyInput() }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                        )
                    }
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(c.n100)
                            .clickable { vm.setRetroMode(!vm.retroSpeedMode) }
                            .padding(horizontal = 14.dp, vertical = 13.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OIcon(if (vm.retroSpeedMode) Lucide.Disc else Lucide.Gauge, 18.dp, c.text)
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(
                                if (vm.retroSpeedMode) "复古模式" else "现代模式",
                                style = body(14f, FontWeight.ExtraBold, c.text),
                            )
                            Text(
                                if (vm.retroSpeedMode) "音调随速度变化（磁带效果）" else "变速不变调（播客效果）",
                                style = body(11f, FontWeight.Normal, c.n500),
                            )
                        }
                        OIcon(Lucide.Settings, 16.dp, c.n400)
                    }
                    KeyShiftRow(vm, c)
                }
            } else if (vm.pMenuView == "karaoke") {
                KaraokeMixPanel(vm)
            }
        }
}

// ── v1.6/v1.7 play queue sheet ─────────────────────────────────────────────
// v1.3.3: 外层 SheetOverlay 已移除——由 AppRoot 的独立 ModalBottomSheet 包裹本
// 内容(独立 window,手势/返回只作用队列弹层)。内容层(拖拽排序/auto-scroll/
// 右滑移除)不依赖 overlay 结构,原样保留。
@Composable
private fun QueueSheetContent(vm: MainViewModel) {
    val c = LocalOrganic.current
        // read to subscribe to queue mutations
        vm.player.queueVersion
        val byId = vm.lib().associateBy { it.id }
        val current = vm.player.currentQueueEntry()?.let { byId[it.id] }
        val upcoming = vm.player.upcomingEntries().mapNotNull { e -> byId[e.id]?.let { e to it } }
        // v5.2 Bug6: drag-reorder state for the upcoming-rows portion of the
        // queue. Reusing the LibraryScreen album-track drag pattern — the
        // dragged row translates with the finger, swaps neighbors at the
        // half-item threshold, lifts as a 24dp-shadow card; the LazyColumn
        // auto-scrolls when the finger nears the top/bottom edge. -1 = idle.
        var qDragIndex by androidx.compose.runtime.remember { mutableIntStateOf(-1) }
        var qDragDelta by androidx.compose.runtime.remember { mutableFloatStateOf(0f) }
        // v5.2: 被拖项的 track id——auto-scroll 按 key 在 visibleItemsInfo 里定位它
        var qDragKeyId by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<Long?>(null) }
        // v5.2 #61: 瞬时拖速（onDrag 更新，onDragEnd 归零），用于 auto-scroll 油门
        var qDragVel by androidx.compose.runtime.remember { mutableFloatStateOf(0f) }
        val qListState = rememberLazyListState()
        val density = LocalDensity.current
        val itemHeightPx = with(density) { 56.dp.toPx() }    // QueueRow ≈ 40dp cover + 18dp v-pad
        androidx.compose.runtime.LaunchedEffect(qDragIndex) {
            if (qDragIndex < 0) return@LaunchedEffect
            val edgePx = with(density) { 48.dp.toPx() }
            val frameMs = 32L
            val baseSpeed = 14f
            while (qDragIndex >= 0) {
                val info = qListState.layoutInfo
                val vs = info.viewportStartOffset
                val ve = info.viewportEndOffset
                // v5.2 #65: 提速——基速 8→14，系数 3→5，上限 36→64。
                val upcomingCount = vm.player.upcomingEntries().size
                val effSpeed = (baseSpeed + kotlin.math.abs(qDragVel) * 5f).coerceAtMost(64f)
                val dragged = qDragKeyId?.let { id ->
                    info.visibleItemsInfo.firstOrNull { it.key == id }
                }
                if (dragged != null && kotlin.math.abs(qDragDelta) > 4f) {
                    val rowTop = dragged.offset + qDragDelta
                    val rowBottom = dragged.offset + dragged.size + qDragDelta
                    when {
                        // v5.2 #58: 只在 upcoming 范围内滚——qDragIndex==0 不往上滚（不串到
                        // "正在播放"），qDragIndex==末尾不往下滚。拖动被限在接下来这几首之间。
                        rowTop < vs + edgePx && qDragIndex > 0 -> {
                            val s = ((vs + edgePx - rowTop) / edgePx).coerceIn(0f, 1f) * effSpeed
                            qListState.dispatchRawDelta(-s)
                        }
                        rowBottom > ve - edgePx && qDragIndex < upcomingCount - 1 -> {
                            val s = ((rowBottom - (ve - edgePx)) / edgePx).coerceIn(0f, 1f) * effSpeed
                            qListState.dispatchRawDelta(s)
                        }
                    }
                }
                kotlinx.coroutines.delay(frameMs)
            }
        }
        Column(Modifier.navigationBarsPadding().padding(start = 14.dp, end = 14.dp, top = 18.dp, bottom = 10.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp)
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("播放队列", style = body(16f, FontWeight.ExtraBold, c.text), modifier = Modifier.weight(1f))
            }
            // v1.3.3: 独立 sheet 内列表高度自适应——不足 max 时按内容收短,超出滚动。
            LazyColumn(state = qListState, modifier = Modifier.heightIn(max = 400.dp)) {
                if (current != null) {
                    item {
                        Text(
                            "正在播放",
                            style = body(11.5f, FontWeight.ExtraBold, c.n600).copy(letterSpacing = 1.sp),
                            modifier = Modifier.padding(start = 6.dp, top = 2.dp, bottom = 4.dp),
                        )
                    }
                    item {
                        QueueRow(vm, current, isCurrent = true, entry = null)
                    }
                }
                if (upcoming.isNotEmpty()) {
                    item {
                        Text(
                            "接下来",
                            style = body(11.5f, FontWeight.ExtraBold, c.n600).copy(letterSpacing = 1.sp),
                            modifier = Modifier.padding(start = 6.dp, top = 10.dp, bottom = 4.dp),
                        )
                    }
                    itemsIndexed(upcoming, key = { _, (e, _) -> e.id }) { i, (entry, t) ->
                        // v5.2: isDragging 按 track id 判断(不用 i==qDragIndex)——
                        // swap 后 index 时序错一帧会导致被拖项视觉跳位。按 id 稳定。
                        val isDragging = qDragKeyId == t.id
                        // v5.2 Bug6: capture the dragged track's stable id at
                        // drag start. We pass that id (not the captured QueueEntry)
                        // into moveQueueEntryById, which re-resolves the *current*
                        // absolute media-item index from the underlying controller
                        // — so the drag survives chained swaps where entry.index
                        // would have gone stale. Capturing `t.id` is safe because
                        // pointerInput(Unit) keeps this lambda's closure alive for
                        // the whole gesture.
                        val dragId = t.id
                        Box(
                            Modifier
                                // v5.2: 被拖项不用 animateItem(靠 translationY 跟手),
                                // 其他项 animateItem 平滑让位——避免位置动画与跟手冲突漂移卡顿。
                                .then(if (isDragging) Modifier
                                    else Modifier.animateItem(placementSpec = androidx.compose.animation.core.tween(200)))
                                .graphicsLayer {
                                    translationY = if (isDragging) qDragDelta else 0f
                                }
                                .zIndex(if (isDragging) 1f else 0f)
                        ) {
                            QueueRow(
                                vm = vm, track = t, isCurrent = false, entry = entry,
                                dragHandle = true,
                                onSwipeRemove = { vm.player.removeQueueEntryById(t.id) },
                                onDrag = { dy ->
                                    qDragDelta += dy
                                    qDragVel = dy   // v5.2 #61: 瞬时拖速，喂给 auto-scroll 油门
                                    val half = itemHeightPx * 0.5f
                                    // Re-resolve the current queue layout on every
                                    // swap — the underlying MediaController queue
                                    // shifts as we moveMediaItem, so the captured
                                    // `upcoming` snapshot becomes stale. We re-read
                                    // upcomingEntries() (which queries the live
                                    // controller) and look up the swap target by
                                    // position, then call moveQueueEntryById with
                                    // the target's stable track id — which the
                                    // controller re-resolves to the new absolute
                                    // index itself.
                                    fun freshIds(): List<Long> =
                                        vm.player.upcomingEntries().map { it.id }
                                    while (qDragDelta > half) {
                                        val ids = freshIds()
                                        val to = qDragIndex + 1
                                        if (to >= ids.size) break
                                        vm.player.moveQueueEntryById(dragId, ids[to])
                                        qDragIndex = to
                                        qDragDelta -= itemHeightPx
                                    }
                                    while (qDragDelta < -half) {
                                        val ids = freshIds()
                                        val to = qDragIndex - 1
                                        if (to < 0) break
                                        vm.player.moveQueueEntryById(dragId, ids[to])
                                        qDragIndex = to
                                        qDragDelta += itemHeightPx
                                    }
                                    // v5.2 #63: 撞墙——限在 upcoming 范围。已到首项还往上拖、
                                    // 或到末项还往下拖时，夹住 delta 不超过半项，行停在原位不漂
                                    // 出"接下来"区域（不串到"正在播放"）。
                                    val curIds = freshIds()
                                    if (qDragIndex == 0 && qDragDelta < -half) qDragDelta = -half
                                    if (qDragIndex >= curIds.size - 1 && qDragDelta > half) qDragDelta = half
                                },
                                onDragStart = { qDragIndex = i; qDragDelta = 0f; qDragKeyId = dragId; qDragVel = 0f },
                                onDragEnd = { qDragIndex = -1; qDragDelta = 0f; qDragKeyId = null; qDragVel = 0f },
                            )
                        }
                    }
                }
            }
        }
}

@Composable
private fun QueueRow(
    vm: MainViewModel,
    track: Track,
    isCurrent: Boolean,
    entry: com.shiyin.music.playback.PlayerController.QueueEntry?,
    dragHandle: Boolean = false,
    onDrag: ((Float) -> Unit)? = null,
    onDragStart: (() -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    onSwipeRemove: (() -> Unit)? = null,
) {
    val c = LocalOrganic.current
    // v1.1: 右滑移除（仅 upcoming 行，onSwipeRemove != null）。与 grip handle 上的
    // 长按拖拽排序分轴共存：本手势只吃水平 drag，垂直滚动/长按拖拽不受影响。
    // 过阈值 → onSwipeRemove（列表移除项后由 animateItem 退场）；不过 → animateTo(0) 回弹。
    val thresholdPx = with(LocalDensity.current) { 96.dp.toPx() }
    val offsetX = remember(track.id) { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Row(
        Modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = offsetX.value }
            .then(if (onSwipeRemove != null) Modifier.pointerInput(track.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        scope.launch {
                            if (offsetX.value >= thresholdPx) onSwipeRemove?.invoke()
                            else offsetX.animateTo(0f, tween(160))
                        }
                    },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        scope.launch { offsetX.snapTo((offsetX.value + dragAmount).coerceAtLeast(0f)) }
                    },
                )
            } else Modifier)
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = !isCurrent && !vm.qEdit) {
                entry?.let { vm.player.seekToQueueIndex(it.index) }
            }
            .padding(horizontal = 6.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (vm.qEdit && !isCurrent && entry != null) {
            Box(
                Modifier
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(c.a100)
                    .clickable { vm.player.removeQueueEntry(entry) },
                contentAlignment = Alignment.Center,
            ) { OIcon(Lucide.Minus, 13.dp, c.a700) }
        }
        CoverArt(track, 40.dp, RoundedCornerShape(8.dp), fontSize = 16)
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                style = body(14f, FontWeight.SemiBold, if (isCurrent) c.a700 else c.text),
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${track.artist} · ${formatDuration(track.durationSec)}",
                style = body(12f, FontWeight.Normal, c.n600),
                modifier = Modifier.padding(top = 1.dp),
                maxLines = 1,
            )
        }
        if (isCurrent) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .clickable { vm.player.toggle() },
                contentAlignment = Alignment.Center,
            ) {
                OIcon(if (vm.player.isPlaying) Lucide.Pause else Lucide.Play, 17.dp, c.a700)
            }
        } else if (dragHandle && onDrag != null && onDragStart != null && onDragEnd != null) {
            // v5.2 Bug6: long-press-and-drag on the grip handle reorders the
            // queue. Wired via detectDragGesturesAfterLongPress so the row
            // still accepts vertical scroll/drag for navigation when the user
            // isn't explicitly dragging — a plain detectDragGestures would
            // hijack every touch and break scrolling.
            Box(
                Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .pointerInput(Unit) {
                        // Key = Unit (constant) so the gesture survives the
                        // MediaController moveMediaItem reassignments that
                        // happen mid-drag. If we keyed on entry.index the block
                        // would re-enter and cancel the in-flight drag every
                        // time the dragged track shifted one slot. The dragId
                        // (captured from QueueSheet's `t.id`) and freshIds()
                        // inside onDrag re-resolve state live from the
                        // controller, so we don't depend on `entry` here.
                        detectDragGesturesAfterLongPress(
                            onDragStart = { onDragStart() },
                            onDrag = { change, dragAmount ->
                                change.consume()
                                onDrag(dragAmount.y)
                            },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() },
                        )
                    },
                contentAlignment = Alignment.Center,
            ) { OIcon(Lucide.GripLines, 18.dp, c.n400) }
        } else {
            OIcon(Lucide.GripLines, 18.dp, c.n400)
        }
    }
}

// ── v1.5 save-to-playlist sheet ────────────────────────────────────────────
// v1.3.x 反馈:播放页打开时,SheetOverlay 挂在 AppRoot 主树会被播放页 ModalBottomSheet
// (独立 Popup 窗口)压在下面/渲染错位到"首页"。改:播放页打开时用独立 ModalBottomSheet
// (声明在播放 sheet 之后→后建窗口居上,与队列/菜单 sheet 同模式);平时用 SheetOverlay。
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun BoxScope.SaveToPlaylistSheet(vm: MainViewModel) {
    val c = LocalOrganic.current
    val targetId = vm.saveSheetFor
    if (vm.playerOpen) {
        // 播放页打开:独立 ModalBottomSheet,盖在播放页之上。
        if (targetId != null) {
            androidx.compose.material3.ModalBottomSheet(
                onDismissRequest = { vm.saveSheetFor = null },
                dragHandle = null,
                scrimColor = Color.Black.copy(alpha = 0.35f),
                containerColor = c.bg,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                contentWindowInsets = { WindowInsets(0, 0, 0, 0) },
            ) {
                SaveToPlaylistContent(vm, targetId)
            }
        }
    } else {
        // 平时(歌词本/列表页):SheetOverlay 即可。
        SheetOverlay(visible = targetId != null, onDismiss = { vm.saveSheetFor = null }) {
            if (targetId != null) SaveToPlaylistContent(vm, targetId)
        }
    }
}

@Composable
private fun SaveToPlaylistContent(vm: MainViewModel, targetId: Long) {
    val c = LocalOrganic.current
    Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 14.dp)) {
        Text("保存到歌单", style = body(16f, FontWeight.ExtraBold, c.text), modifier = Modifier.padding(bottom = 4.dp))
        // v1.3.x 反馈:「新建歌单」入口置顶(Spotify 式)——先建后加,
        // 点一下创建新歌单并把当前歌直接加进去,不用先退出去建。
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { vm.createPlaylistAndAdd(targetId) }
                .padding(horizontal = 4.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(c.surface),
                contentAlignment = Alignment.Center,
            ) {
                OIcon(Lucide.Plus, 18.dp, c.n700)
            }
            Text("新建歌单", style = body(14.5f, FontWeight.Bold, c.accent))
        }
        for (p in vm.playlists) {
            val ids = vm.playlistTracks[p.id] ?: emptyList()
            val checked = targetId in ids
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { vm.togglePlaylistMembership(p.id, targetId) }
                    .padding(horizontal = 4.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(p.name, style = body(14.5f, FontWeight.Bold, c.text))
                    Text("${ids.size} 首", style = body(12f, FontWeight.Normal, c.n600), modifier = Modifier.padding(top = 1.dp))
                }
                RoundCheck(checked)
            }
        }
        PillButton(
            "完成",
            onClick = { vm.saveSheetFor = null },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            fontSize = 14.5f, padV = 12.dp,
        )
    }
}

// ── v2.0 album picker sheet (categorize single → album) ────────────────
@Composable
private fun BoxScope.AlbumPickerSheet(vm: MainViewModel) {
    val c = LocalOrganic.current
    val targetId = vm.albumPickerId
    val track = targetId?.let { vm.trackById(it) }
    SheetOverlay(visible = targetId != null && track != null, onDismiss = { vm.albumPickerId = null }) {
        if (track == null) return@SheetOverlay
        val albums = vm.albumsMap().entries.toList().sortedBy { (_, ts) -> ts.first().album }
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("把「${track.title}」归类到专辑", style = body(16f, FontWeight.ExtraBold, c.text))
            if (albums.isEmpty()) {
                Text("暂无专辑", style = body(14f, FontWeight.Normal, c.n600))
            } else {
                LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(albums, key = { it.key }) { (key, ts) ->
                        val first = ts.first()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { vm.assignToAlbum(targetId, first.album, first.artist) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            com.shiyin.music.ui.components.CoverArt(first, 40.dp, RoundedCornerShape(8.dp), fontSize = 16)
                            Column(Modifier.weight(1f)) {
                                Text(first.album, style = body(14f, FontWeight.SemiBold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${first.artist} · ${ts.size} 首", style = body(12f, FontWeight.Normal, c.n600))
                            }
                        }
                    }
                }
            }
            PillButton(
                "取消",
                onClick = { vm.albumPickerId = null },
                modifier = Modifier.fillMaxWidth(),
                bg = null, textColor = c.text, borderColor = c.divider,
                fontSize = 14.5f, padV = 12.dp,
            )
        }
    }
}

// v5.2 #79: 批量迁移 sheet——列该歌手的真实专辑 + 新建专辑入口
@Composable
private fun BoxScope.BatchMoveSheet(vm: MainViewModel) {
    val c = LocalOrganic.current
    val firstTrack = vm.batchMoveSelected.firstOrNull()?.let { vm.trackById(it) }
    SheetOverlay(visible = vm.albumBatchMoveSheet && firstTrack != null, onDismiss = { vm.albumBatchMoveSheet = false }) {
        if (firstTrack == null) return@SheetOverlay
        val trackArtists = remember(firstTrack.id, firstTrack.artist) {
            com.shiyin.music.data.MediaScanner.splitArtists(firstTrack.artist).toSet()
        }
        val currentAlbumId = firstTrack.albumId
        val albums = vm.albumsMap().entries
            .filter { (_, ts) ->
                ts.first().albumId > 0 && ts.first().albumId != currentAlbumId &&
                    ts.any { trackArtists.any { a -> a in com.shiyin.music.data.MediaScanner.splitArtists(it.artist) } }
            }
            .sortedBy { (_, ts) -> ts.first().album }
        var showNewAlbumDialog by remember { mutableStateOf(false) }
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("迁移 ${vm.batchMoveSelected.size} 首到专辑", style = body(16f, FontWeight.ExtraBold, c.text))
            if (albums.isEmpty()) {
                Text("该歌手暂无其他专辑，请新建", style = body(14f, FontWeight.Normal, c.n600))
            } else {
                LazyColumn(Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(albums, key = { it.key }) { (_, ts) ->
                        val first = ts.first()
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                                .clickable {
                                    vm.albumBatchMoveSheet = false
                                    vm.batchMoveToAlbum(first.albumId)
                                }.padding(vertical = 10.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            com.shiyin.music.ui.components.CoverArt(first, 40.dp, RoundedCornerShape(8.dp), fontSize = 16)
                            Column(Modifier.weight(1f)) {
                                Text(first.album, style = body(14f, FontWeight.SemiBold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${first.artist} · ${ts.size} 首", style = body(12f, FontWeight.Normal, c.n600))
                            }
                        }
                    }
                }
            }
            PillButton(
                "+ 新建专辑",
                onClick = { showNewAlbumDialog = true },
                modifier = Modifier.fillMaxWidth(),
                bg = null, textColor = c.a700, borderColor = c.a700, fontSize = 14.5f, padV = 12.dp,
            )
            PillButton(
                "取消",
                onClick = { vm.albumBatchMoveSheet = false },
                modifier = Modifier.fillMaxWidth(),
                bg = null, textColor = c.text, borderColor = c.divider, fontSize = 14.5f, padV = 12.dp,
            )
        }
        if (showNewAlbumDialog) {
            var newName by remember { mutableStateOf("") }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { showNewAlbumDialog = false },
                containerColor = c.surface,
                title = { Text("新建专辑", style = body(15f, FontWeight.ExtraBold, c.text)) },
                text = {
                    androidx.compose.material3.OutlinedTextField(
                        value = newName, onValueChange = { newName = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("输入新专辑名", style = body(14f, FontWeight.Normal, c.n600)) },
                        colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                            focusedTextColor = c.text, unfocusedTextColor = c.text,
                            cursorColor = c.accent, focusedBorderColor = c.accent, unfocusedBorderColor = c.n400,
                        ),
                    )
                },
                confirmButton = {
                    DialogActionsRow(
                        listOf(
                            DialogAction("取消") { showNewAlbumDialog = false },
                            DialogAction("创建并迁移", emphasized = true) {
                                if (newName.isNotBlank()) {
                                    vm.albumBatchMoveSheet = false
                                    showNewAlbumDialog = false
                                    vm.batchAssignToNewAlbum(newName.trim(), firstTrack.artist)
                                }
                            },
                        )
                    )
                },
            )
        }
    }
}

// ── v4.3: album-move sheet — re-parent a single into its real album ──────
// Same chrome as AlbumPickerSheet, but lists only REAL albums (albumId > 0):
// the target must be a concrete MediaStore album for the track to join it
// (virtual "归类" albums are name-based and can't host a real albumId).
@Composable
private fun BoxScope.AlbumMoveSheet(vm: MainViewModel) {
    val c = LocalOrganic.current
    val targetId = vm.albumMoveFor
    val track = targetId?.let { vm.trackById(it) }
    SheetOverlay(visible = targetId != null && track != null, onDismiss = { vm.albumMoveFor = null }) {
        if (track == null) return@SheetOverlay
        // v4.3: 只列「该曲目歌手」的专辑——用户迁周杰伦错识别单曲时只需周杰伦的专辑，
        // 列全库别的歌手的专辑纯属干扰。track.artist 在 lib() 里已被 split 成 "A, B" 形式，
        // 这里取 split 后的任一艺术家名做归属判断(支持多艺人合辑)。
        val trackArtists = remember(track.id, track.artist) {
            com.shiyin.music.data.MediaScanner.splitArtists(track.artist).toSet()
        }
        val albums = vm.albumsMap().entries
            .filter { (_, ts) ->
                val first = ts.first()
                first.albumId > 0 && first.albumId != track.albumId &&
                    ts.any { trackArtists.any { a -> a in com.shiyin.music.data.MediaScanner.splitArtists(it.artist) } }
            }
            .sortedBy { (_, ts) -> ts.first().album }
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("把「${track.title}」迁移到专辑", style = body(16f, FontWeight.ExtraBold, c.text))
            Text("用于歌曲被误识别成独立专辑的情况，迁移后它将加入该专辑并共享封面。", style = body(12f, FontWeight.Normal, c.n600))
            if (albums.isEmpty()) {
                Text("该歌手暂无其他专辑", style = body(14f, FontWeight.Normal, c.n600))
            } else {
                LazyColumn(Modifier.heightIn(max = 360.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(albums, key = { it.key }) { (_, ts) ->
                        val first = ts.first()
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { vm.moveTrackToAlbum(targetId, first.albumId) }
                                .padding(vertical = 10.dp, horizontal = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            com.shiyin.music.ui.components.CoverArt(first, 40.dp, RoundedCornerShape(8.dp), fontSize = 16)
                            Column(Modifier.weight(1f)) {
                                Text(first.album, style = body(14f, FontWeight.SemiBold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${first.artist} · ${ts.size} 首", style = body(12f, FontWeight.Normal, c.n600), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
            PillButton(
                "取消",
                onClick = { vm.albumMoveFor = null },
                modifier = Modifier.fillMaxWidth(),
                bg = null, textColor = c.text, borderColor = c.divider,
                fontSize = 14.5f, padV = 12.dp,
            )
        }
    }
}

// ── v1.5 toast with 更改 action ────────────────────────────────────────────
@Composable
private fun BoxScope.ToastHost(vm: MainViewModel) {
    val c = LocalOrganic.current
    val toast = vm.toast
    AnimatedVisibility(
        visible = toast != null,
        enter = fadeIn(tween(200)),
        exit = fadeOut(tween(200)),
        modifier = Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = 158.dp),
    ) {
        val data = toast ?: return@AnimatedVisibility
        Row(
            Modifier
                .shadowLg(RoundedCornerShape(999.dp))
                .clip(RoundedCornerShape(999.dp))
                .background(c.n900)
                .padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(data.text, style = body(12.5f, FontWeight.SemiBold, c.n100), maxLines = 1)
            if (data.changeTargetId != null) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .background(c.n100.copy(alpha = 0.16f))
                        .clickable {
                            vm.saveSheetFor = data.changeTargetId
                            vm.toast = null
                        }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                ) { Text("更改", style = body(12f, FontWeight.ExtraBold, c.a400)) }
            }
        }
    }
}

// ── v3.0: sidebar item ─────────────────────────────────────────────────────
@Composable
private fun SidebarItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) =
    SidebarItemRow(
        icon = { com.shiyin.music.ui.components.OIcon(icon, 20.dp, LocalOrganic.current.n700) },
        label = label,
        onClick = onClick,
    )

/**
 * 重载：矢量 drawable（`painterResource`）——拾光Agent 的品牌图标走这条。
 * 见 `OIcon(Painter…)` 注释：本 Compose 版本没有可用的 `vectorResource`。
 *
 * 槽位仍 20dp（行高与文字位置和 Lucide 行一致）；图标画 28.8dp 并
 * `wrapContentSize(unbounded)` 允许溢出——logo 是宽椭圆（内容 23.5×15.8 / 24），
 * 按 20dp 画出来上下只有 13.2dp，比邻居（Bell 18.98dp）矮三成。
 */
@Composable
private fun SidebarItem(icon: androidx.compose.ui.graphics.painter.Painter, label: String, onClick: () -> Unit) =
    SidebarItemRow(
        icon = {
            Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
                com.shiyin.music.ui.components.OIcon(
                    icon, 28.8.dp, LocalOrganic.current.n700,
                    modifier = Modifier.wrapContentSize(unbounded = true),
                )
            }
        },
        label = label,
        onClick = onClick,
    )

@Composable
private fun SidebarItemRow(icon: @Composable () -> Unit, label: String, onClick: () -> Unit) {
    val c = LocalOrganic.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon()
        Text(label, style = body(15f, FontWeight.SemiBold, c.text))
    }
}

// ── v4.3: album ⋮ menu (drag-sort / text-sort / edit-info / change-cover) ───
@Composable
private fun BoxScope.AlbumMenuSheet(vm: MainViewModel) {
    val c = LocalOrganic.current
    val albumId = vm.albumKey?.removePrefix("aid:")?.toLongOrNull()
    SheetOverlay(
        visible = vm.albumMenuOpen && albumId != null,
        onDismiss = { vm.albumMenuOpen = false },
        maxHeightFraction = 0.55f,
    ) {
        Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 16.dp, bottom = 10.dp)) {
            Text(
                "专辑信息编辑",
                style = body(16f, FontWeight.ExtraBold, c.text),
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 12.dp),
            )
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
            Spacer(Modifier.height(6.dp))
            // ── v22 修补: 拆分专辑禁用「调整歌曲顺序」(用户反馈③——专辑页排序
            // 与拆分编辑页的顺序是两套真源,专辑页改动会导致移动错乱;拆分后一切
            // 顺序以编辑页为准,想调顺序回编辑页拖)。
            if (!vm.albumIsSplit(vm.albumKey ?: "")) {
                SheetActionRow(Lucide.ArrowUpDown, "调整歌曲顺序") {
                    vm.albumMenuOpen = false
                    vm.albumEdit = true
                    vm.snapshotAlbumOrder()
                }
            }
            // v22 修补: 文本排序同禁——与拖拽排序同一错乱源(拆分顺序以编辑页为准)。
            if (!vm.albumIsSplit(vm.albumKey ?: "")) {
                SheetActionRow(Lucide.ListRows, "文本调整顺序") {
                    vm.albumMenuOpen = false
                    vm.albumEditText = true
                }
            }
            SheetActionRow(Lucide.Settings, "修改专辑信息") {
                vm.albumMenuOpen = false
                // v1.2.2 diag: 确认对话框是否被打开、albumKey 长什么样(是 aid: 还是 album|artist)
                android.util.Log.d("ArtistEdit", "dialog OPEN albumKey=${vm.albumKey}")
                vm.albumEditInfo = true
            }
            SheetActionRow(Lucide.Disc, "更换专辑封面") {
                vm.albumMenuOpen = false
                vm.albumCoverEdit = true
            }
            SheetActionRow(Lucide.Users, "批量迁移歌曲") {
                vm.albumMenuOpen = false
                vm.batchMoveMode = true
                vm.batchMoveSelected = emptySet()
            }
            // ── v22: 专辑整轨拆分为单曲（放在 Agent 上面，用户指定位置）─────────
            // 显示条件：专辑类型为「单曲」即可，不设时长门槛 —— 8 分钟串烧、45 分钟
            // 全长整轨专辑都能用（后者只有 1 个文件，本身就被分类为单曲）。
            if (vm.canSplitAlbum(vm.albumKey ?: "")) {
                val src = vm.splitSourceOfAlbum(vm.albumKey ?: "")
                val already = vm.albumIsSplit(vm.albumKey ?: "")
                SheetActionRow(
                    if (already) Lucide.Pencil else Lucide.ListMusic,
                    if (already) "编辑拆分" else "专辑整轨拆分为单曲",
                ) {
                    vm.albumMenuOpen = false
                    if (src != null) vm.openSplitEditor(src)
                }
                if (already && src != null) {
                    SheetActionRow(Lucide.RotateCcw, "取消拆分（合并回整轨）") {
                        vm.albumMenuOpen = false
                        vm.removeSplit(src)
                    }
                }
            }
            // v1.3.2: Agent 入口(图标与侧边栏一致)——打开对话页,Agent 打招呼 + 建议回复,
            // 不再局限于"识别排序"一种处理。v1.3.x: 更名「拾光Agent」+ 换品牌图标。
            SheetActionRow(
                androidx.compose.ui.res.painterResource(com.shiyin.music.R.drawable.ic_agent),
                "拾光Agent",
            ) {
                vm.albumMenuOpen = false
                vm.openAgentForAlbum(vm.albumKey ?: return@SheetActionRow)
            }
        }
    }
}

// ── v4.3: album edit dialogs — info / cover / rematch prompt / track edit ──

/**
 * v1.3.6: 发布日期选择器(修改专辑信息弹窗)——替代滑杆(0..2030 拉条不可用)。
 * 点开后弹**小号 AlertDialog 单选列表**(LazyColumn,Dialog 内容不受 DropdownMenu
 * 的 intrinsic 测量约束——两轮实机闪退的根因:DropdownMenu 对上万项做固有测量,
 * 先 LazyList 不支持,再 Column+scroll 撑出 340k px 高度 Constraints 爆表)。
 * 打开自动定位到当前值;0 显示「未设置」。
 */
@Composable
private fun DateField(
    label: String,
    value: Int,
    options: List<Int>,
    /** v1.5.0 A58：值为"未设置"(0) 时，下拉打开定位到哪个值——用户要求年份停在 2000，
     *  而不是停在第 0 项（那等于要从公元 1 年一路滚两千年）。 */
    unsetScrollTo: Int = 2000,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val c = LocalOrganic.current
    var open by remember { mutableStateOf(false) }
    // v1.5.0 A58：值区改成**可直接键盘输入**的窄输入框（用户："未填入的位置应该去掉变成
    // 空白，点击即键盘输入"）；下拉改成右侧的 ^ 键。
    var text by remember(value) { mutableStateOf(if (value == 0) "" else "$value") }
    val maxV = options.maxOrNull() ?: 0
    Box(modifier) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(c.surface)
                .border(1.dp, c.divider, RoundedCornerShape(10.dp))
                .padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, style = body(11f, FontWeight.Normal, c.n500))
            Spacer(Modifier.weight(1f))
            androidx.compose.foundation.text.BasicTextField(
                value = text,
                onValueChange = { raw ->
                    val digits = raw.filter { it.isDigit() }.take(4)
                    text = digits
                    val v = digits.toIntOrNull()
                    when {
                        v != null -> onSelect(v.coerceIn(0, maxV))
                        digits.isEmpty() -> onSelect(0)
                    }
                },
                singleLine = true,
                textStyle = body(12f, FontWeight.SemiBold, c.text),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(c.accent),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                ),
                modifier = Modifier.width(44.dp),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterEnd) {
                        if (text.isEmpty()) {
                            Text("", style = body(12f, FontWeight.SemiBold, c.n400))
                        }
                        inner()
                    }
                },
            )
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { open = true }
                    .padding(horizontal = 6.dp, vertical = 6.dp),
            ) { OIcon(Lucide.ChevronUp, 13.dp, c.n400) }
        }
        if (open) {
            // v1.5.0 A58：未设置(0) 时定位到 unsetScrollTo（年=2000），别再从头滚。
            val anchor = if (value == 0) {
                options.indexOf(unsetScrollTo).takeIf { it >= 0 } ?: 0
            } else {
                options.indexOf(value).coerceAtLeast(0)
            }
            val listState = androidx.compose.foundation.lazy.rememberLazyListState(
                initialFirstVisibleItemIndex = (anchor - 6).coerceAtLeast(0),
            )
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { open = false },
                containerColor = c.surface,
                title = { Text("选择$label", style = body(14f, FontWeight.ExtraBold, c.text)) },
                text = {
                    // Dialog 里 LazyColumn 安全(无 intrinsic 测量压力),定位靠
                    // initialFirstVisibleItemIndex(选当前值往上留 6 项缓冲)。
                    androidx.compose.foundation.lazy.LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    ) {
                        items(options.size) { i ->
                            val v = options[i]
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { onSelect(v); open = false }
                                    .padding(horizontal = 8.dp, vertical = 11.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    if (v == 0) "未设置" else "$v",
                                    style = body(13.5f, if (v == value) FontWeight.ExtraBold else FontWeight.Normal, if (v == value) c.accent else c.text),
                                    modifier = Modifier.weight(1f),
                                )
                                if (v == value) OIcon(Lucide.Check, 15.dp, c.accent)
                            }
                        }
                    }
                },
                confirmButton = {
                    DialogActionsRow(listOf(DialogAction("取消") { open = false }))
                },
            )
        }
    }
}

/** 年月对应天数(年/月为 0 时按 31 给上限,滑着不卡顿)。 */
private fun daysInMonth(y: Int, m: Int): Int = when (m) {
    1, 3, 5, 7, 8, 10, 12 -> 31
    4, 6, 9, 11 -> 30
    2 -> if (y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)) 29 else 28
    else -> 31
}

@Composable
private fun AlbumEditDialogs(vm: MainViewModel, trackEditFor: Long?, onTrackEditDismiss: () -> Unit) {
    val c = LocalOrganic.current
    val albumId = vm.albumKey?.removePrefix("aid:")?.toLongOrNull()
    // v1.2.2: 改支持所有专辑(含无共享 albumId 的合集)——不要求 albumId 非空,
    // 合集(albumKey 形如 "$album|$artist")也能改歌手。albumId 可能为 null。
    val effAlbumId = albumId ?: 0L
    if (vm.albumKey != null) {
        // v4.3: 修改专辑信息 (album name / artist → album_info_override)
        if (vm.albumEditInfo) {
            val first = vm.albumOrder(vm.albumKey!!).firstOrNull()
            if (first != null) {
                var name by rememberSaveable(vm.albumKey) { mutableStateOf(first.album) }
                var artist by rememberSaveable(vm.albumKey) { mutableStateOf(first.artist) }
                // v9: pre-select the album's CURRENT effective category (manual
                // override if set, else the track-count heuristic) so the chips
                // reflect reality, not a blank default. "" = 自动 (no override).
                val effAlbumId = albumId ?: (if (first.albumId > 0) first.albumId else 0L)
                val currentType = remember(effAlbumId) { vm.albumTypeFor(effAlbumId) }
                var type by rememberSaveable(vm.albumKey) { mutableStateOf(currentType) }
                // v1.3.6: 发布日期编辑——年/月/日三个**下拉单选框**(旧版滑杆 0..2030
                // 拉到崩,大跨度数值滑杆根本不可用——用户定调)。未识别日期默认"未设
                // 置";选了年月日后按年月 clamp 天数;把"未设置"选回去即清空日期。
                // v1.3.6 崩溃修复:旧版 savedDate.substring(5,7) 在空串日期(未识别)
                // 上直接 StringIndexOutOfBounds 闪退——全部改 takeIf+长度守卫。
                val savedDate = remember(vm.albumKey, vm.albumDateRevision) {
                    if (effAlbumId > 0) vm.albumDateOf(effAlbumId) else ""
                }
                val initY = if (savedDate.length >= 4) savedDate.take(4).toIntOrNull() ?: 0 else 0
                val initM = if (savedDate.length >= 7) savedDate.substring(5, 7).toIntOrNull() ?: 0 else 0
                val initD = if (savedDate.length >= 10) savedDate.substring(8, 10).toIntOrNull() ?: 0 else 0
                var dateY by rememberSaveable(vm.albumKey) { mutableIntStateOf(initY) }
                var dateM by rememberSaveable(vm.albumKey) { mutableIntStateOf(initM) }
                var dateD by rememberSaveable(vm.albumKey) { mutableIntStateOf(initD) }
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { vm.albumEditInfo = false },
                    containerColor = c.surface,
                    title = { Text("修改专辑信息", style = body(15f, FontWeight.ExtraBold, c.text)) },
                    text = {
                        // v1.3.x: 内容高(名称/歌手/类型/日期),加滚动上限——避免 text 区
                        // 过高把按钮挤出可视区("设置超过按钮界面")。
                        Column(
                            Modifier
                                .heightIn(max = 420.dp)
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            OutlinedTextField(
                                value = name,
                                onValueChange = { name = it },
                                label = { Text("专辑名称") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            OutlinedTextField(
                                value = artist,
                                onValueChange = { artist = it },
                                label = { Text("专辑歌手") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            // v9: 类型 — overrides AlbumClassifier's track-count heuristic.
                            // Tap 自动 to clear the override (falls back to the heuristic).
                            Text("专辑类型", style = body(12f, FontWeight.Bold, c.n600))
                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                val types = listOf(
                                    "自动" to "",
                                    "专辑" to "Album",
                                    "EP" to "EP",
                                    "单曲" to "Single",
                                    "合集" to "Compilation",
                                )
                                types.forEach { (label, value) ->
                                    FilterChip(
                                        selected = type == value,
                                        onClick = { type = value },
                                        label = { Text(label, style = body(12f, FontWeight.Normal, c.text)) },
                                    )
                                }
                            }
                            // v1.3.6: 发布日期编辑 UI——年/月/日三个下拉单选框(旧版
                            // 0..2030 滑杆对大跨度数值完全不可用,已废)。"未设置"可
                            // 从任何一个下拉选回(清空日期);年月定天数后日下拉项即
                            // 时收窄(31/30/29/28)。
                            Text("发布日期", style = body(12f, FontWeight.Bold, c.n600))
                            val dim = remember(dateY, dateM) { daysInMonth(dateY, dateM) }
                            if (dateD > dim) dateD = dim
                            // v1.3.6: 年上限随当前时间走(今天年份+1),不写死——
                            // App 用到 2031 年时年份选项自己长上去。
                            val curYear = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                DateField("年", dateY, (0..(curYear + 1)).toList(), modifier = Modifier.weight(1.15f)) { dateY = it }
                                DateField("月", dateM, (0..12).toList(), unsetScrollTo = 1, modifier = Modifier.weight(1f)) { dateM = it }
                                DateField("日", dateD, (0..dim).toList(), unsetScrollTo = 1, modifier = Modifier.weight(1f)) { dateD = it }
                            }
                            // v1.5.0 A58：原来这里有一行"未设置(任一下拉选「未设置」即清除日期)"
                            // 的小字——用户："正常来说是新的覆盖旧的设置日期，说这个没意义"。
                            // 已删除（符合本项目"非必要不加小字"的纪律）。
                        }
                    },
                    confirmButton = {
                        DialogActionsRow(
                            listOf(
                                DialogAction("取消") { vm.albumEditInfo = false },
                                DialogAction("保存", emphasized = true) {
                                    vm.albumEditInfo = false
                                    // v1.2.2: 有共享 albumId → 走专辑级保存(含类型/重匹配);
                                    // 无(合集 albumKey 非 aid:) → 只改歌手(整组单曲级)。
                                    android.util.Log.d("ArtistEdit", "dialog save: albumKey=${vm.albumKey} albumId=$albumId effAlbumId=$effAlbumId name=${name.trim()} artist=${artist.trim()}")
                                    if (albumId != null) {
                                        vm.saveAlbumInfo(albumId, name.trim(), artist.trim(), type)
                                        vm.rematchPromptFor = "album:$albumId"
                                        // v1.3.5: 日期一并保存(全 0 → 空串=清除)
                                        if (effAlbumId > 0) {
                                            val iso = if (dateY > 0 && dateM > 0 && dateD > 0)
                                                "%04d-%02d-%02d".format(dateY, dateM, dateD) else ""
                                            if (iso != savedDate) vm.saveAlbumReleaseDate(effAlbumId, iso)
                                        }
                                    } else {
                                        vm.setAlbumArtist(vm.albumKey!!, artist.trim())
                                    }
                                },
                            )
                        )
                    },
                )
            }
        }

        // v4.3: 更换专辑封面 — v9 expanded into a candidate grid (iTunes top
        // matches) + 选相册 + 恢复默认. The same coverUri field holds both a
        // content:// picker uri and an https:// iTunes url; ArtCache.decodeUri
        // handles both, so pinning a remote candidate needs no new storage path.
        if (vm.albumCoverEdit) {
            val first = vm.albumOrder(vm.albumKey!!).firstOrNull()
            val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
                if (uri != null) {
                    // v22: 改走"先落盘再存 file://"——原来直接存 picker 的 content:// URI，
                    // 而系统给它的读权限只到**设备重启**为止（官方 Photo Picker 文档），
                    // 于是重启后封面会裂。落盘后重启/清缓存都不受影响，也便于进备份。
                    // 注意：只读取相册里那一张图 + 在 app 私有目录写副本，不动原图、不碰音乐文件。
                    vm.setAlbumCoverFromFile(effAlbumId, uri)
                    vm.albumCoverEdit = false
                }
            }
            // Fetch iTunes candidates once per dialog open (keyed on the album's
            // first track id — stable across recompositions while dialog is open).
            var candidates by remember(first?.id) { mutableStateOf<List<ArtCache.Candidate>>(emptyList()) }
            var candidatesLoading by remember(first?.id) { mutableStateOf(true) }
            var candBatch by remember(first?.id) { mutableStateOf(0) }
            var candHasMore by remember(first?.id) { mutableStateOf(true) }
            var candLoadingMore by remember(first?.id) { mutableStateOf(false) }
            val candScope = rememberCoroutineScope()
            LaunchedEffect(first?.id) {
                candidatesLoading = true
                candBatch = 0
                candHasMore = true
                val r = first?.let { ArtCache.loadCandidates(it, offset = 0, limit = 8) } ?: emptyList()
                candidates = r
                candHasMore = r.size >= 8
                candidatesLoading = false
            }
            androidx.compose.material3.AlertDialog(
                onDismissRequest = { vm.albumCoverEdit = false },
                containerColor = c.surface,
                title = { Text("更换专辑封面", style = body(15f, FontWeight.ExtraBold, c.text)) },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (first != null) {
                            Text(
                                "从 iTunes 匹配结果中选择，或从相册挑选一张。所选封面将统一用于整张专辑。",
                                style = body(12f, FontWeight.Normal, c.n600),
                            )
                            // Candidate grid — FlowRow keeps self-sizing inside the dialog.
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 280.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                if (candidatesLoading) {
                                    Text("正在搜索候选封面…", style = body(12f, FontWeight.Normal, c.n500))
                                } else if (candidates.isEmpty()) {
                                    Text("未找到候选封面，可从相册选择。", style = body(12f, FontWeight.Normal, c.n500))
                                } else {
                                    FlowRow(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                                        verticalArrangement = Arrangement.spacedBy(10.dp),
                                    ) {
                                        candidates.forEach { cand ->
                                            CandidateCover(cand) {
                                                vm.saveAlbumCover(effAlbumId, cand.artUrl)
                                                vm.albumCoverEdit = false
                                            }
                                        }
                                        if (candLoadingMore) {
                                            Text("加载中…", style = body(11f, FontWeight.Normal, c.n500), modifier = Modifier.padding(8.dp))
                                        }
                                    }
                                }
                            }
                            // v1.2.0: 粘贴链接获取封面(同写真选择器)
                            var linkUrl by remember { mutableStateOf("") }
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                androidx.compose.material3.OutlinedTextField(
                                    value = linkUrl,
                                    onValueChange = { linkUrl = it },
                                    label = { Text("图片链接", style = body(12f, FontWeight.Normal, c.n500)) },
                                    singleLine = true,
                                    modifier = Modifier.weight(1f),
                                    textStyle = body(13f, FontWeight.Normal, c.text),
                                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                                        focusedTextColor = c.text, unfocusedTextColor = c.text, cursorColor = c.accent,
                                        focusedBorderColor = c.accent, unfocusedBorderColor = c.n400,
                                        focusedLabelColor = c.accent, unfocusedLabelColor = c.n500,
                                    ),
                                )
                                androidx.compose.material3.TextButton(onClick = {
                                    if (linkUrl.isNotBlank()) { vm.saveAlbumCover(effAlbumId, linkUrl.trim()); vm.albumCoverEdit = false }
                                }) { Text("保存", style = body(14f, FontWeight.Bold, c.accent)) }
                            }
                        }
                    }
                },
                // 「下一批」固定在按钮行右端(不随网格滚动),多图时不必下拉找。
                confirmButton = {
                    // v1.3.x: 四键统一横排右对齐——原 confirm/dismiss 双槽位会把
                    // "下一批"折到 Row(选相册/恢复默认/取消) 的上一行/下一行。
                    val acts = mutableListOf(
                        DialogAction("选相册") {
                            vm.albumCoverEdit = false
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        },
                        DialogAction("恢复默认") {
                            vm.albumCoverEdit = false
                            vm.clearAlbumCover(effAlbumId)
                        },
                        DialogAction("取消") { vm.albumCoverEdit = false },
                    )
                    if (first != null && !candidatesLoading && candidates.isNotEmpty() && candHasMore) {
                        acts.add(
                            DialogAction(
                                if (candLoadingMore) "加载中…" else "下一批",
                                emphasized = true,
                                enabled = !candLoadingMore,
                            ) {
                                candScope.launch {
                                    candLoadingMore = true
                                    val nb = candBatch + 1
                                    val r = first?.let { ArtCache.loadCandidates(it, offset = nb * 8, limit = 8) } ?: emptyList()
                                    val newOnes = r.filter { nc -> candidates.none { it.artUrl == nc.artUrl } }
                                    if (newOnes.isNotEmpty()) {
                                        candidates = candidates + newOnes
                                        candBatch = nb
                                    }
                                    candHasMore = r.size >= 8 && newOnes.isNotEmpty()
                                    candLoadingMore = false
                                }
                            }
                        )
                    }
                    DialogActionsRow(acts)
                },
            )
        }
    }

    // v4.3: 修改歌曲信息 (title/artist/note) — display-level override
    val editTrack = trackEditFor?.let { vm.trackById(it) }
    if (editTrack != null) {
        var title by remember(editTrack.id) { mutableStateOf(editTrack.title) }
        var artist by remember(editTrack.id) { mutableStateOf(editTrack.artist) }
        var note by remember(editTrack.id) { mutableStateOf("") }
        val movedAlbumId = vm.trackAlbumMoves[editTrack.id]
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onTrackEditDismiss,
            containerColor = c.surface,
            title = { Text("修改歌曲信息", style = body(15f, FontWeight.ExtraBold, c.text)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("歌曲名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = artist, onValueChange = { artist = it }, label = { Text("歌手") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = note, onValueChange = { note = it }, label = { Text("备注（仅本地显示）") }, modifier = Modifier.fillMaxWidth())
                    // v4.3: 迁移专辑 — fix scan errors where a single was given
                    // its own albumId; move it into the real album here.
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("所属专辑", style = body(12f, FontWeight.Bold, c.n600))
                            Text(
                                if (movedAlbumId != null) "${editTrack.album}（已迁移）" else editTrack.album,
                                style = body(13f, FontWeight.Normal, c.text),
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                        if (movedAlbumId != null) {
                            androidx.compose.material3.TextButton(onClick = { vm.clearTrackAlbumMove(editTrack.id) }) {
                                Text("恢复", style = body(13f, FontWeight.SemiBold, c.n600))
                            }
                        }
                        androidx.compose.material3.TextButton(onClick = {
                            // 先收起本弹层，否则它会盖住迁移选择页
                            onTrackEditDismiss()
                            vm.albumMoveFor = editTrack.id
                        }) {
                            Text("迁移到…", style = body(13f, FontWeight.SemiBold, c.a700))
                        }
                    }
                }
            },
            confirmButton = {
                DialogActionsRow(
                    listOf(
                        DialogAction("取消") { onTrackEditDismiss() },
                        DialogAction("保存", emphasized = true) {
                            vm.saveTrackInfo(editTrack.id, title.trim(), artist.trim(), note.trim())
                            vm.rematchPromptFor = "track:${editTrack.id}"
                            onTrackEditDismiss()
                        },
                    )
                )
            },
        )
    }

    // v4.3: 修改后确认是否重新匹配资源（封面 / 歌词）
    val rematchTarget = vm.rematchPromptFor
    if (rematchTarget != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { vm.confirmRematch(rematchTarget, false) },
            containerColor = c.surface,
            title = { Text("是否重新匹配资源？", style = body(15f, FontWeight.ExtraBold, c.text)) },
            text = { Text("使用修改后的内容重新搜索封面与歌词等资源。选择「否」则仅保存修改内容。", style = body(13f, FontWeight.Normal, c.n600)) },
            confirmButton = {
                DialogActionsRow(
                    listOf(
                        DialogAction("否") { vm.confirmRematch(rematchTarget, false) },
                        DialogAction("是", emphasized = true) { vm.confirmRematch(rematchTarget, true) },
                    )
                )
            },
        )
    }
}

/** v9: a single iTunes candidate cover cell in the 更换专辑封面 picker grid.
 *  Loads the remote thumbnail async; tapping pins the artUrl via saveAlbumCover. */
@Composable
private fun CandidateCover(
    cand: ArtCache.Candidate,
    onPick: () -> Unit,
) {
    val c = LocalOrganic.current
    val bmp = rememberCandidateArt(cand.artUrl, 84.dp)
    val typeLabel = when (cand.collectionType) {
        "Album" -> "专辑"
        "Single" -> "单曲"
        "EP" -> "EP"
        "Compilation" -> "合辑"
        else -> ""
    }
    Column(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onPick)
            .padding(2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Box(
            Modifier
                .size(84.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(c.n200)
        ) {
            if (bmp != null) {
                Image(
                    bmp,
                    contentDescription = cand.albumName,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("···", style = body(16f, FontWeight.Bold, c.n400))
                }
            }
        }
        Text(
            cand.albumName.ifBlank { "未知" },
            style = body(10f, FontWeight.Normal, c.n600),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(84.dp),
        )
        if (typeLabel.isNotBlank()) {
            Text(typeLabel, style = body(9f, FontWeight.Bold, c.n500))
        }
    }
}

/**
 * v1.5.0 伴唱 §8：升降 Key 行（−6…+6 半音，含 0.5 步进；点中间数字复位）。
 *
 * **只放在「播放倍速」子页一处** —— 它是全局播放控制，和播放速度同级。
 * （2026-10-14 用户指出：原先速度子页与伴唱面板各放了一份，会被误读成两个独立功能。）
 *
 * 步进（用户要求增大精度后定的）：
 *  - **点 ±** → 走 **1 个半音**（快速找调，最常用）；
 *  - **长按 ±** → 走 **0.5 个半音**（微调，跟唱对不上原调那半格时用）。
 * 内部单位是**半音步数**（+1 步 = +0.5 半音），见 [com.shiyin.music.playback.KeyShift]。
 * 总音高 = 加速模式自带音高 × 2^(半音/12)，速度不变。
 */
@Composable
private fun KeyShiftRow(vm: MainViewModel, c: com.shiyin.music.ui.theme.OrganicColors) {
    val k = com.shiyin.music.playback.KeyShift
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(c.n100)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("升降 Key", style = body(14f, FontWeight.ExtraBold, c.text))
            Text("点一下走 1 个半音 · 长按走 0.5", style = body(11f, FontWeight.Normal, c.n500))
        }
        Text(
            "−",
            style = body(18f, FontWeight.ExtraBold, if (vm.keyShift > k.MIN) c.n600 else c.n400),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(
                    enabled = vm.keyShift > k.MIN,
                    onClick = { vm.changeKeyShift(vm.keyShift - k.STEPS_PER_SEMITONE) },
                    onLongClick = { vm.changeKeyShift(vm.keyShift - 1) },
                )
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
        Text(
            k.label(vm.keyShift),
            style = body(15f, FontWeight.ExtraBold, if (vm.keyShift == 0) c.text else c.accent),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable { vm.changeKeyShift(0) }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        Text(
            "+",
            style = body(18f, FontWeight.ExtraBold, if (vm.keyShift < k.MAX) c.n600 else c.n400),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .combinedClickable(
                    enabled = vm.keyShift < k.MAX,
                    onClick = { vm.changeKeyShift(vm.keyShift + k.STEPS_PER_SEMITONE) },
                    onLongClick = { vm.changeKeyShift(vm.keyShift + 1) },
                )
                .padding(horizontal = 12.dp, vertical = 4.dp),
        )
    }
}

/**
 * v1.5.0 伴唱「分轨」面板（规格 §3.3）。
 *
 * 入口两处，打开的是同一个面板：歌词页的伴唱麦克风按钮、播放页 ⋮ 菜单的「伴唱」项。
 * 内容：三档混音滑块（人声 — 原曲 — 伴奏）+ 升降 Key + 关闭伴唱。
 *
 * ⚠️ 当前为 **UI 先行**：档位只驱动按钮状态，不改变实际声音——分离引擎与播放链路
 * 的混音属 P2（规格 §7），待 P0 Spike 结论出来后再接。
 */
@Composable
private fun KaraokeMixPanel(vm: MainViewModel) {
    val c = LocalOrganic.current
    val started = vm.karaokeState != com.shiyin.music.ui.components.KaraokeButtonState.Idle

    Column(Modifier.fillMaxWidth()) {
        // 返回行（与睡眠/速度子页同款：只用图标，不用文字）
        Row(
            Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(34.dp).clip(CircleShape).clickable { vm.pMenuView = "root" },
                contentAlignment = Alignment.Center,
            ) { OIcon(Lucide.ChevronLeft, 20.dp, c.n500) }
            Text("伴唱", style = body(15f, FontWeight.ExtraBold, c.text))
            Spacer(Modifier.weight(1f))
        }

        if (!started) {
            // 规格 O9：从菜单进入且尚未开启伴唱时，先给一个开启按钮（等同点歌词页麦克风）
            Text(
                "开启伴唱",
                style = body(14f, FontWeight.ExtraBold, c.surface),
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(c.accent)
                    .clickable { vm.toggleKaraoke() }
                    .padding(horizontal = 18.dp, vertical = 10.dp)
                    .align(Alignment.CenterHorizontally),
            )
            return@Column
        }

        // 三档混音：人声 — 原曲（默认）— 伴奏
        ThreeStopSegmentedControl(
            labels = listOf("人声", "原曲", "伴奏"),
            selectedIndex = vm.karaokeMixIndex,
            onSelect = { vm.selectKaraokeMix(it) },
        )
        Spacer(Modifier.height(12.dp))

        // v1.5.0: 升降 Key **不再**在这里出现 —— 它是**全局播放控制**（和播放速度同级），
        // 只保留在「播放倍速」子页一处。两处各放一份会让用户以为它们互不相干（用户 2026-10-14 指出）。
        Text(
            "关闭伴唱",
            style = body(13.5f, FontWeight.SemiBold, c.n600),
            modifier = Modifier
                .clip(RoundedCornerShape(12.dp))
                .background(c.n100)
                .clickable { vm.closeKaraoke() }
                .padding(horizontal = 16.dp, vertical = 9.dp),
        )
    }
}
