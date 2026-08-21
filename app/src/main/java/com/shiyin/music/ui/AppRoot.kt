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
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
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
import com.shiyin.music.ui.components.FavIcon
import com.shiyin.music.ui.components.OIcon
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

        // A track change can land on a song without lyrics while the
        // fullscreen lyric sheet is open — close it instead of a blank page.
        LaunchedEffect(vm.lyricsOn, vm.currentLyrics) {
            if (vm.lyricsOn && vm.currentLyrics?.parsed?.isEmpty != false) {
                vm.lyricsOn = false
                vm.lySheet = false
            }
        }

        // main content
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
                .graphicsLayer {
                    val p = sidebarProgress.value
                    translationX = size.width * 0.5f * p
                    scaleX = 1f - 0.05f * p
                    scaleY = 1f - 0.06f * p
                    transformOrigin = TransformOrigin(0f, 0.5f) // pivot left, so scale+translate leaves no gap
                },
        ) {
            Box(Modifier.weight(1f)) {
                val contentKey = when {
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
                    when {
                        key.startsWith("settings") -> SettingsHost(vm)
                        key == "recent" -> com.shiyin.music.ui.screens.RecentPlaysScreen(vm)
                        key == "stats" -> com.shiyin.music.ui.screens.ListeningStatsScreen(vm)
                        key == "updates" -> com.shiyin.music.ui.screens.YourUpdatesScreen(vm)
                        key == "tab:${Tab.Home}" -> HomeScreen(vm)
                        key == "tab:${Tab.Search}" -> SearchScreen(vm)
                        else -> LibraryScreen(vm)
                    }
                }
            }
            val cur = vm.trackById(vm.player.currentId)
            if (cur != null && !vm.playerOpen) MiniPlayer(vm, cur)
            BottomNav(vm)
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
                    .padding(20.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.Bottom, modifier = Modifier.padding(bottom = 12.dp)) {
                        Text("拾音", style = heading(24))
                        Text("PickUpMusic", style = body(13f, FontWeight.Normal, c.n500), modifier = Modifier.padding(start = 8.dp, bottom = 3.dp))
                    }
                    // v4: sidebar restructured — 5 items, no file-management here
                    SidebarItem(Lucide.History, "最近播放") { vm.sidebarOpen = false; vm.recentOpen = true; vm.settingsOpen = false }
                    SidebarItem(Lucide.BarChart, "收听统计") { vm.sidebarOpen = false; vm.statsOpen = true; vm.settingsOpen = false }
                    SidebarItem(Lucide.Bell, "你的更新") { vm.sidebarOpen = false; vm.updatesOpen = true; vm.settingsOpen = false }
                    SidebarItem(Lucide.Settings, "设置") { vm.sidebarOpen = false; vm.settingsOpen = true; vm.filesView = com.shiyin.music.FilesView.Root; vm.recentOpen = false; vm.statsOpen = false; vm.updatesOpen = false }
                    SidebarItem(Lucide.CircleInfo, "关于我们") { vm.sidebarOpen = false; vm.settingsOpen = true; vm.filesView = com.shiyin.music.FilesView.About }
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
            val sheetState = androidx.compose.material3.rememberModalBottomSheetState(
                skipPartiallyExpanded = true,
            )
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
                    when {
                        vm.lyricsOn -> vm.lyricsOn = false
                        vm.qSheetOpen -> { vm.qSheetOpen = false; vm.qEdit = false }
                        vm.pMenuView != null -> vm.pMenuView = if (vm.pMenuView == "timer-custom") "timer" else if (vm.pMenuView == "timer" || vm.pMenuView == "speed") "root" else null
                        else -> { vm.playerOpen = false; vm.sleepMenu = false }
                    }
                },
                sheetState = sheetState,
                modifier = Modifier.fillMaxHeight(),
                // dragHandle=null keeps the top row (返回 / 三点菜单) as the
                // only chrome at the very top — the user explicitly didn't
                // want an extra drag-strip above that row.
                dragHandle = null,
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
                // v5.2: 二级 overlay（三点菜单 / 播放队列 / 歌词本）必须和 PlayerScreen
                // 在同一个 popup 窗口里——ModalBottomSheet 是独立 Popup，原先这些 overlay
                // 留在主合成树会被它压在下面、点不到且渲染在“第二面”。移进 content 后它们
                // 盖在 PlayerScreen 之上、可正常打开。Box(fillMaxHeight) 让 overlay 的
                // fillMaxSize/scrim 对齐整页。
                Box(Modifier.fillMaxHeight()) {
                    PlayerScreen(vm)
                    PlayerMenuSheet(vm)
                    QueueSheet(vm)
                    // v5.2 歌词本 overlay 已从这里移出——它不再和 PlayerScreen 同窗口,
                    // 改为下方独立的全屏 Dialog(后建窗口,盖在 ModalBottomSheet 之上),
                    // 让右滑/predictive back 落到 Dialog 自己的 onDismissRequest 只关歌词本。
                }
            }
        }

        // v5.2 歌词本:全屏 Dialog(独立窗口,声明在 ModalBottomSheet 之后→后建窗口居上)。
        // 返回路由【不变】:右滑/predictive back 仍走 Dialog 窗口的 dismissOnBackPress→
        // onDismissRequest,只关 lyricsOn、playerOpen 不动→回播放页(不退首页)。
        // 只在 onDismissRequest 上叠加退出动画:先 animState.targetState=false 播下滑,
        // 放完(currentState→false)再 vm.lyricsOn=false 销毁 Dialog。
        // LyricsDebug 日志供回归验证 onDismissRequest 是否真的由 Dialog 触发(而非被 sheet 抢走)。
        if (vm.lyricsOn && vm.playerOpen) {
            val lTrack = vm.trackById(vm.player.currentId)
            val lBg = if (lTrack != null) {
                com.shiyin.music.ui.components.rememberCoverPalette(lTrack).first
            } else {
                c.bg
            }
            val animState = androidx.compose.runtime.remember {
                MutableTransitionState(false).apply { targetState = true }
            }
            androidx.compose.runtime.LaunchedEffect(animState.currentState, animState.targetState) {
                if (!animState.targetState && !animState.currentState) {
                    android.util.Log.d("LyricsDebug", "exit anim done → vm.lyricsOn=false")
                    vm.lyricsOn = false
                }
            }
            // v5.2 #72: 用官方 Window.setDimAmount(0f) 清 Dialog 窗口的 dim/scrim——
            // DialogWindowProvider 是 Compose 公开接口(javap 确认 public),经它拿窗口再
            // setDimAmount(0f),退出下滑期间背后不再有黑灰遮罩。返回路由(Dialog 窗口+
            // dismissOnBackPress)不变,只动 scrim。
            val winRef = androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf<android.view.Window?>(null) }
            androidx.compose.ui.window.Dialog(
                onDismissRequest = {
                    android.util.Log.d("LyricsDebug", "Dialog onDismissRequest fired (lyricsOn=${vm.lyricsOn})")
                    animState.targetState = false
                    // #72: 退出动画开始的同一时机清 dim,下滑期间无黑灰遮罩
                    winRef.value?.let { it.setDimAmount(0f); android.util.Log.d("LyricsDebug", "setDimAmount(0f) at exit-start") }
                },
                properties = androidx.compose.ui.window.DialogProperties(
                    usePlatformDefaultWidth = false,
                    dismissOnBackPress = true,
                    dismissOnClickOutside = false,
                    decorFitsSystemWindows = false,
                ),
            ) {
                // #72: Dialog 内容首次组合时,经 LocalView 的 parent 链找 DialogWindowProvider,
                // 拿窗口、setDimAmount(0f) 并缓存到 winRef(供 onDismissRequest 复用)。
                val dlgView = androidx.compose.ui.platform.LocalView.current
                androidx.compose.runtime.DisposableEffect(dlgView) {
                    var p: android.view.View? = dlgView
                    while (p != null) {
                        val wp = p as? androidx.compose.ui.window.DialogWindowProvider
                        if (wp != null) {
                            winRef.value = wp.window
                            wp.window.setDimAmount(0f)
                            android.util.Log.d("LyricsDebug", "found DialogWindowProvider → setDimAmount(0f)")
                            break
                        }
                        p = p.parent as? android.view.View
                    }
                    onDispose { }
                }
                androidx.compose.animation.AnimatedVisibility(
                    visibleState = animState,
                    enter = slideInVertically(tween(250)) { it / 6 } + fadeIn(tween(250)),
                    exit = slideOutVertically(tween(250)) { it },
                ) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(lBg)
                            .statusBarsPadding()
                            .navigationBarsPadding(),
                    ) { LyricsScreen(vm) }
                }
            }
        }

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

        ToastHost(vm)
    }
}

@Composable
private fun ArtistPickerDialog(vm: MainViewModel) {
    val c = LocalOrganic.current
    val artists = vm.artistPickerFor
    if (artists == null || artists.isEmpty()) return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.artistPickerFor = null },
        containerColor = c.surface,
        title = { Text("选择歌手", style = body(15f, FontWeight.ExtraBold, c.text)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("这首歌曲有多位歌手，请选择要查看的歌手：", style = body(13f, FontWeight.Normal, c.n600))
                Spacer(Modifier.height(8.dp))
                for (name in artists) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable { vm.artistPickerFor = null; vm.openArtist(name) }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        OIcon(Lucide.User, 18.dp, c.text)
                        Text(name, style = body(14f, FontWeight.SemiBold, c.text))
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { vm.artistPickerFor = null }) {
                Text("取消", style = body(14f, FontWeight.Normal, c.n600))
            }
        },
    )
}

@Composable
private fun LyricsSourcePickerDialog(vm: MainViewModel) {
    val c = LocalOrganic.current
    if (!vm.lyricsSourcePicker) return
    val sources = com.shiyin.music.data.lyrics.LyricsFetcher.SOURCES
    val curSrc = vm.currentLyrics?.source
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { vm.lyricsSourcePicker = false },
        containerColor = c.surface,
        title = { Text("选择歌词来源", style = body(15f, FontWeight.ExtraBold, c.text)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (src in sources) {
                    val active = src == curSrc
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                vm.lyricsSourcePicker = false
                                vm.rematchSource(src)
                            }
                            .padding(horizontal = 12.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(src, style = body(14f, FontWeight.SemiBold, if (active) c.text else c.n600), modifier = Modifier.weight(1f))
                        if (active) OIcon(Lucide.Check, 16.dp, c.accent)
                    }
                }
            }
        },
        confirmButton = {
            androidx.compose.material3.TextButton(onClick = { vm.lyricsSourcePicker = false }) {
                Text("取消", style = body(14f, FontWeight.Normal, c.n600))
            }
        },
    )
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
            androidx.compose.material3.TextButton(onClick = {
                if (importText.isNotBlank()) vm.importLrcContent("手动导入", importText, track.id)
                vm.lyricsImportDialog = false
            }) { Text("导入", style = body(14f, FontWeight.Bold, onColor)) }
        },
        dismissButton = {
            androidx.compose.material3.TextButton(onClick = { vm.lyricsImportDialog = false }) {
                Text("取消", style = body(14f, FontWeight.Normal, onSurfaceVariant))
            }
        },
    )
}

@Composable
private fun RootBackHandler(vm: MainViewModel) {
    BackHandler(
        enabled = vm.saveSheetFor != null || vm.albumPickerId != null || vm.albumMoveFor != null || vm.trackMenuFor != null || vm.pMenuView != null ||
            vm.qSheetOpen || vm.lySheet || vm.lyricsOn || vm.sleepMenu || vm.playerOpen ||
            vm.artistMerge || vm.albumKey != null || vm.artistKey != null || vm.folderKey != null || vm.plId != null ||
            vm.settingsOpen || vm.recentOpen || vm.statsOpen || vm.updatesOpen ||
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
            vm.saveSheetFor != null -> vm.saveSheetFor = null
            vm.lyricsImportDialog -> vm.lyricsImportDialog = false
            vm.lyricsSourcePicker -> vm.lyricsSourcePicker = false
            vm.albumPickerId != null -> vm.albumPickerId = null
            vm.albumMoveFor != null -> vm.albumMoveFor = null
            vm.trackMenuFor != null -> vm.trackMenuFor = null
            vm.pMenuView == "timer-custom" -> vm.pMenuView = "timer"
            vm.pMenuView == "timer" -> vm.pMenuView = "root"
            vm.pMenuView == "speed" -> vm.pMenuView = "root"
            vm.pMenuView != null -> vm.pMenuView = null
            vm.qSheetOpen -> { vm.qSheetOpen = false; vm.qEdit = false }
            vm.lySheet -> vm.lySheet = false
            vm.lyricsOn -> vm.lyricsOn = false
            vm.sleepMenu -> vm.sleepMenu = false
            vm.playerOpen -> vm.playerOpen = false
            vm.artistMerge -> vm.artistMerge = false
            vm.settingsOpen -> {
                if (vm.folderKey != null) {
                    vm.folderKey = null
                    vm.filesView = FilesView.Folders
                } else if (vm.filesView != FilesView.Root) {
                    vm.filesView = FilesView.Root
                    vm.sel = emptyMap()
                } else vm.settingsOpen = false
            }
            vm.plId != null -> vm.plId = null
            vm.albumKey != null -> { vm.albumKey = null; vm.albumEdit = false; vm.albumEditText = false }
            vm.artistKey != null -> vm.artistKey = null
            vm.q.isNotEmpty() -> vm.q = ""
            vm.tab != Tab.Home -> vm.tab = Tab.Home
        }
    }
}

// ── v1.3/v1.5 mini player: square card, bottom progress, device+fav+play ───
@Composable
private fun MiniPlayer(vm: MainViewModel, track: Track) {
    val c = LocalOrganic.current
    // v5.2 Bug1: Activity context for SystemOutputSwitcherDialogController.showDialog
    val ctx = LocalContext.current
    val durMs = if (vm.player.durationMs > 0) vm.player.durationMs else track.durationMs
    val pct = if (durMs > 0) (vm.player.positionMs.toFloat() / durMs).coerceIn(0f, 1f) else 0f
    Box(
        Modifier
            .padding(start = 12.dp, end = 12.dp, bottom = 8.dp)
            .fillMaxWidth()
            .shadowMd(RoundedCornerShape(12.dp))
            .clip(RoundedCornerShape(12.dp))
            .background(c.surface),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 10.dp, end = 10.dp, top = 8.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.clickable { vm.playerOpen = true }) {
                CoverArt(track, 40.dp, RoundedCornerShape(8.dp), fontSize = 16)
            }
            Column(
                Modifier
                    .weight(1f)
                    .clickable { vm.playerOpen = true },
            ) {
                Text(track.title, style = body(13.5f, FontWeight.Bold, c.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(track.artist, style = body(11.5f, FontWeight.Normal, c.n600), maxLines = 1)
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
                    .clickable { vm.toggleFav(track.id) },
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
                        vm.tab = tab
                        vm.settingsOpen = false
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
                SheetActionRow(Lucide.RotateCcw, "恢复播放", tint = c.a700) {
                    vm.toggleTrackHidden(track.id)
                    vm.trackMenuFor = null
                }
            } else {
                SheetActionRow(Lucide.EyeOff, "隐藏此曲") {
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
@Composable
private fun BoxScope.PlayerMenuSheet(vm: MainViewModel) {
    val c = LocalOrganic.current
    val track = vm.trackById(vm.player.currentId)
    val visible = vm.pMenuView != null && vm.playerOpen && track != null
    SheetOverlay(visible = visible, onDismiss = { vm.pMenuView = null }) {
        if (track == null) return@SheetOverlay
        Column(Modifier.padding(start = 10.dp, end = 10.dp, top = 16.dp, bottom = 10.dp)) {
            if (vm.pMenuView != "timer" && vm.pMenuView != "timer-custom" && vm.pMenuView != "speed") {
                SheetSongHeader(track)
                SheetActionRow(Lucide.ListPlus, "保存到歌单") {
                    vm.pMenuView = null
                    vm.saveSheetFor = track.id
                }
                if (track.album != com.shiyin.music.data.NO_ALBUM) {
                    SheetActionRow(Lucide.Disc, "查看专辑") { vm.goAlbumOf(track.id, highlight = false) }
                }
                SheetActionRow(Lucide.User, "查看歌手") { vm.goArtistOf(track.id) }
                // v3.0: lyrics entries in player menu
                SheetActionRow(Lucide.Captions, "打开歌词") {
                    vm.pMenuView = null
                    vm.lyricsOn = true
                    vm.lySheet = false
                    if (vm.currentLyrics == null) vm.rematchNextSource()
                }
                SheetActionRow(Lucide.ListPlus, "歌词来源", trailing = {
                    Text(
                        vm.currentLyrics?.source ?: "未匹配",
                        style = body(12.5f, FontWeight.SemiBold, c.n500),
                    )
                    OIcon(Lucide.ChevronRight, 16.dp, c.n500)
                }) {
                    vm.pMenuView = null
                    vm.lyricsSourcePicker = true
                }
                SheetActionRow(Lucide.Plus, "导入歌词文本") {
                    vm.pMenuView = null
                    vm.lyricsImportDialog = true
                }
                // v5.2 隐藏曲:播放页菜单也提供同样开关,方便在听的时候即时跳过。
                if (vm.isHidden(track.id)) {
                    SheetActionRow(Lucide.RotateCcw, "恢复播放", tint = c.a700) {
                        vm.pMenuView = null
                        vm.toggleTrackHidden(track.id)
                    }
                } else {
                    SheetActionRow(Lucide.EyeOff, "隐藏此曲") {
                        vm.pMenuView = null
                        vm.toggleTrackHidden(track.id)
                    }
                }
                SheetActionRow(
                    Lucide.Clock, "睡眠定时",
                    trailing = {
                        Text(vm.player.sleepStatusText(), style = body(12.5f, FontWeight.SemiBold, c.n500))
                        OIcon(Lucide.ChevronRight, 16.dp, c.n500)
                    },
                ) { vm.pMenuView = "timer" }
                SheetActionRow(
                    Lucide.Gauge, "播放速度",
                    trailing = {
                        val spd = if (vm.playbackSpeed == 1.0f) "正常" else "%.2f×".format(vm.playbackSpeed)
                        Text(spd, style = body(12.5f, FontWeight.SemiBold, c.n500))
                        OIcon(Lucide.ChevronRight, 16.dp, c.n500)
                    },
                ) { vm.pMenuView = "speed" }
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
                }
            }
        }
    }
}

// ── v1.6/v1.7 play queue sheet ─────────────────────────────────────────────
@Composable
private fun BoxScope.QueueSheet(vm: MainViewModel) {
    val c = LocalOrganic.current
    val visible = vm.qSheetOpen && vm.playerOpen
    SheetOverlay(
        visible = visible,
        onDismiss = { vm.qSheetOpen = false; vm.qEdit = false },
        maxHeightFraction = 0.64f,
    ) {
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
        Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 18.dp, bottom = 10.dp)) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 6.dp)
                    .padding(bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("播放队列", style = body(16f, FontWeight.ExtraBold, c.text), modifier = Modifier.weight(1f))
            }
            LazyColumn(state = qListState) {
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
) {
    val c = LocalOrganic.current
    Row(
        Modifier
            .fillMaxWidth()
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
@Composable
private fun BoxScope.SaveToPlaylistSheet(vm: MainViewModel) {
    val c = LocalOrganic.current
    val targetId = vm.saveSheetFor
    SheetOverlay(visible = targetId != null, onDismiss = { vm.saveSheetFor = null }) {
        if (targetId == null) return@SheetOverlay
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 14.dp)) {
            Text("保存到歌单", style = body(16f, FontWeight.ExtraBold, c.text), modifier = Modifier.padding(bottom = 4.dp))
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
                    androidx.compose.material3.TextButton(onClick = {
                        if (newName.isNotBlank()) {
                            vm.albumBatchMoveSheet = false
                            showNewAlbumDialog = false
                            vm.batchAssignToNewAlbum(newName.trim(), firstTrack.artist)
                        }
                    }) { Text("创建并迁移", style = body(14f, FontWeight.Bold, c.accent)) }
                },
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { showNewAlbumDialog = false }) {
                        Text("取消", style = body(14f, FontWeight.Normal, c.n600))
                    }
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
private fun SidebarItem(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
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
        com.shiyin.music.ui.components.OIcon(icon, 20.dp, c.n700)
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
            SheetActionRow(Lucide.ArrowUpDown, "调整歌曲顺序") {
                vm.albumMenuOpen = false
                vm.albumEdit = true
                vm.snapshotAlbumOrder()
            }
            SheetActionRow(Lucide.ListRows, "文本调整顺序") {
                vm.albumMenuOpen = false
                vm.albumEditText = true
            }
            SheetActionRow(Lucide.Settings, "修改专辑信息") {
                vm.albumMenuOpen = false
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
        }
    }
}

// ── v4.3: album edit dialogs — info / cover / rematch prompt / track edit ──
@Composable
private fun AlbumEditDialogs(vm: MainViewModel, trackEditFor: Long?, onTrackEditDismiss: () -> Unit) {
    val c = LocalOrganic.current
    val albumId = vm.albumKey?.removePrefix("aid:")?.toLongOrNull()
    if (albumId != null) {
        // v4.3: 修改专辑信息 (album name / artist → album_info_override)
        if (vm.albumEditInfo) {
            val first = vm.albumOrder(vm.albumKey!!).firstOrNull()
            if (first != null) {
                var name by rememberSaveable(albumId) { mutableStateOf(first.album) }
                var artist by rememberSaveable(albumId) { mutableStateOf(first.artist) }
                // v9: pre-select the album's CURRENT effective category (manual
                // override if set, else the track-count heuristic) so the chips
                // reflect reality, not a blank default. "" = 自动 (no override).
                val currentType = remember(albumId) { vm.albumTypeFor(albumId) }
                var type by rememberSaveable(albumId) { mutableStateOf(currentType) }
                androidx.compose.material3.AlertDialog(
                    onDismissRequest = { vm.albumEditInfo = false },
                    containerColor = c.surface,
                    title = { Text("修改专辑信息", style = body(15f, FontWeight.ExtraBold, c.text)) },
                    text = {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                )
                                types.forEach { (label, value) ->
                                    FilterChip(
                                        selected = type == value,
                                        onClick = { type = value },
                                        label = { Text(label, style = body(12f, FontWeight.Normal, c.text)) },
                                    )
                                }
                            }
                        }
                    },
                    confirmButton = {
                        androidx.compose.material3.TextButton(onClick = {
                            vm.albumEditInfo = false
                            vm.saveAlbumInfo(albumId, name.trim(), artist.trim(), type)
                            vm.rematchPromptFor = "album:$albumId"
                        }) { Text("保存", style = body(14f, FontWeight.SemiBold, c.a700)) }
                    },
                    dismissButton = {
                        androidx.compose.material3.TextButton(onClick = { vm.albumEditInfo = false }) {
                            Text("取消", style = body(14f, FontWeight.Normal, c.n600))
                        }
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
                    vm.saveAlbumCover(albumId, uri.toString())
                    vm.albumCoverEdit = false
                }
            }
            // Fetch iTunes candidates once per dialog open (keyed on the album's
            // first track id — stable across recompositions while dialog is open).
            var candidates by remember(first?.id) { mutableStateOf<List<ArtCache.Candidate>>(emptyList()) }
            var candidatesLoading by remember(first?.id) { mutableStateOf(true) }
            LaunchedEffect(first?.id) {
                candidatesLoading = true
                candidates = first?.let { ArtCache.loadCandidates(it) } ?: emptyList()
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
                                    .heightIn(max = 260.dp)
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
                                                vm.saveAlbumCover(albumId, cand.artUrl)
                                                vm.albumCoverEdit = false
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(0.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            androidx.compose.material3.TextButton(onClick = {
                                vm.albumCoverEdit = false
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }) { Text("选相册", style = body(14f, FontWeight.SemiBold, c.a700)) }
                            androidx.compose.material3.TextButton(onClick = {
                                vm.albumCoverEdit = false
                                vm.clearAlbumCover(albumId)
                            }) { Text("恢复默认", style = body(14f, FontWeight.Normal, c.n600)) }
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    androidx.compose.material3.TextButton(onClick = { vm.albumCoverEdit = false }) {
                        Text("取消", style = body(14f, FontWeight.Normal, c.n600))
                    }
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
                androidx.compose.material3.TextButton(onClick = {
                    vm.saveTrackInfo(editTrack.id, title.trim(), artist.trim(), note.trim())
                    vm.rematchPromptFor = "track:${editTrack.id}"
                    onTrackEditDismiss()
                }) { Text("保存", style = body(14f, FontWeight.SemiBold, c.a700)) }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = onTrackEditDismiss) {
                    Text("取消", style = body(14f, FontWeight.Normal, c.n600))
                }
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
                androidx.compose.material3.TextButton(onClick = { vm.confirmRematch(rematchTarget, true) }) {
                    Text("是", style = body(14f, FontWeight.SemiBold, c.a700))
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { vm.confirmRematch(rematchTarget, false) }) {
                    Text("否", style = body(14f, FontWeight.Normal, c.n600))
                }
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
