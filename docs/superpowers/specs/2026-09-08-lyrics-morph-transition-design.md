# 歌词本共享元素过渡动画 — 需求与技术方案

- 日期：2026-09-08
- 状态：待用户终审（本文档是唯一交付物；实施在另一会话进行）
- 已拍板决策：**路径 A — 同窗口手写 morph**（否决 Dialog 演双动画 / 官方 SharedTransitionLayout，理由见 §0.3）
- 用户补充约束：原规格 §11（Compose 实现约束），已整合进本方案；**与 §11 / 原规格存在 5 处有理由的偏差，集中在 §4，是审阅重点**

---

## 0. 阅读指引（给实施会话）

### 0.1 本文档的唯一真源代码位置

| 模块 | 文件 | 关键位置 |
|---|---|---|
| 播放 sheet（窗口宿主） | `ui/AppRoot.kt` | 378-457（sheet 定义）；448（内容 Box，overlay 挂点） |
| 歌词本旧 Dialog（**将被删除**） | `ui/AppRoot.kt` | 534-617 |
| 返回路由 | `ui/AppRoot.kt` | 828-898（RootBackHandler）；409-420（sheet onDismissRequest 兜底链） |
| 歌词卡片 | `ui/screens/PlayerScreen.kt` | 477-599（卡片 Column）；484（点击闸门）；497（peekStart 预览窗口） |
| 播放页内容根 | `ui/screens/PlayerScreen.kt` | 140-143（statusBarsPadding / 全幅绘制）；187（verticalScroll） |
| 歌词本内容 | `ui/screens/LyricsScreen.kt` | 133-179（下拉退出 nestedScroll，**将改造为 morph 驱动**）；180-189（根 Box/Column）；202-264（顶栏）；272-298（跟行滚动）；304-409（列表+渐隐遮罩）；415-540（底栏） |
| 歌词本字号基线 | `ui/screens/LyricsScreen.kt` | 378 / 388（26sp / lineHeight 36sp） |
| VM 状态 | `MainViewModel.kt` | 270-278（lyricsOn / lySheet / lyricsExitRequest / requestLyricsExit） |
| 状态栏控制 | `MainActivity.kt` | 48-52（isAppearanceLightStatusBars，**只随 app 主题，不随取色**） |
| Manifest | `app/src/main/AndroidManifest.xml` | 18（`enableOnBackInvokedCallback="true"` 已开） |

### 0.2 版本基线（勿凭记忆，以构建文件为准）

Compose BOM 2025.05.01（≈ UI 1.8.2 / material3 1.3.2，见 AppRoot.kt:432 与 build.gradle.kts:163 注释）、activity-compose 1.10.1、Kotlin 2.1.21、AGP 8.10.1、Gradle 8.13、compileSdk 36 / minSdk 31。**不使用 experimental API**（SharedTransitionLayout 不引入，路径 A 决策）。

### 0.3 路径 A 决策记录（为什么不是另外两条）

- **否 C（Dialog 演双动画）**：跨窗口无同步保证，闪帧风险结构性存在；规格 §6.1 跟手拖拽跨窗口做不了；不满足核心目标「无缝衔接/避免闪屏」。
- **否 B（官方 SharedTransitionLayout）**：同样需要同窗口化（工作量最大的部分一样要做）；API 仍 experimental；跟手拖拽框架不内置——手势层照样要自建 progress 驱动，等于 A 的活一样不少还叠加框架耦合。
- **选 A**：单一 `Float` progress 真源 + 手写插值，物理参数全可控；同窗口天然零闪帧（一次组合、连续渲染，不存在「旧窗口销毁/新窗口创建」的缝隙）。

### 0.4 红线（CLAUDE.md §六，实施前自查）

- **不动** PlayerScreen 封面渲染代码、PaletteExtractor 评分逻辑。
- 本方案对 PlayerScreen 的改动仅限：卡片 bounds 上报（`onGloballyPositioned`）、按压反馈、卡片预览内容抽成共享可组合项、内容根的淡出 graphicsLayer。预览字号的插值渲染全部发生在 **新 overlay 层**，不进入 PlayerScreen 原有结构。

---

## 1. 现状基线（代码事实，方案的出发点）

### 1.1 窗口栈（当前）

```
main window (AppRoot)
 └─ player sheet Popup 窗口 (ModalBottomSheet, AppRoot:408)   ← PlayerScreen 在此
     └─ 内容 Box (AppRoot:448) ← 【morph overlay 的唯一合法挂点】
 └─ queue sheet Popup 窗口 (v1.3.3 迁出)
 └─ menu sheet Popup 窗口 (v1.3.3 迁出)
 └─ lyrics Dialog 窗口 (AppRoot:572)  ← 【将被删除】
```

main window 里声明的 Dialog（歌词导入、来源选择等 AppRoot 级弹层）后建居上，不受本改动影响。

### 1.2 播放 sheet 的结构性行为（本方案最大风险源）

AppRoot.kt:368-377 注释明确：ModalBottomSheet 内置 Swipeable/anchoredDraggable 提供「**sheet 正文任意位置**拖拽下拉关闭、1:1 跟手」。nestedScroll 的 pre 派发从最外层连接子开始（sheet 的连接子先于歌词本自己的连接子看到 delta），因此**任何进入嵌套滚动的向下 delta 都可能被 sheet 抢走**。v1.3.3 正是因此把队列/菜单弹层迁出到独立窗口。本方案把歌词本搬**进** sheet 窗口，必须用指针层闸门隔离（§3.5），不能只靠 nestedScroll。

### 1.3 两端现状

- **卡片**（PlayerScreen:477-599）：28dp 圆角、`shadowMd`、`artBg`（`rememberCoverPalette` 动态取色）、4 行预览（`peekStart=(activeI-1).coerceIn(0, size-4)`，19sp/28lh，当前行满透明度其余 0.42）、点击置 `vm.lyricsOn=true`（仅 `lines.isNotEmpty()` 时可点，484 行）。卡片在 PlayerScreen 的 `verticalScroll` Column 内（187 行），位置随播放页滚动变化。
- **歌词本**（LyricsScreen 全文件 + AppRoot Dialog 包装）：同一 `rememberCoverPalette` 取色（**两端同色，颜色过渡零成本**）；歌词 26sp/36lh；跟行 `animateScrollBy(d, tween(240, FastOutSlowIn))`，目标偏移 `-(viewport/2-60)`；顶部下拉退出（7ddb048）：nestedScroll 连接子 + `exitDrag: Animatable` translationY，松手阈值 120dp 或速度 2200px/s，回弹 `spring(0.8)`。
- **进入/退出动画现状**：Dialog + `AnimatedVisibility`，`slideInVertically(tween(250)){it/6}+fadeIn` 进 / `slideOutVertically(tween(250))` 出——**线性缓动、无弹簧、无容器形变**，这是本次要替换的全部。

### 1.4 返回路由现状（改后必须逐一回归）

| 触发 | 现路径 | 迁移后 |
|---|---|---|
| 物理返回键（歌词本开） | Dialog 窗口 `dismissOnBackPress` → `onDismissRequest` → 播动画 → 动画完置 `lyricsOn=false` | RootBackHandler lyricsOn 分支（现 858 行**直接置 false，跳过动画——必须改为触发收起动画**） |
| 右滑 predictive back | 同上（Manifest 已开 `enableOnBackInvokedCallback`） | 同上；系统预览动画被回调拦截后播放我们的收起（可选进阶：PredictiveBackHandler 耦合手势进度，见 §3.6） |
| 左上返回键 / 顶部下拉 | `vm.requestLyricsExit()` → `lyricsExitRequest` 注入 lambda → 播动画 | **不变**，lambda 重注入为 morph 收起 |
| 播放页 sheet 的 onDismissRequest 兜底 | 409-420 链：`lyricsOn → 只关歌词本` | 同语义，但改调收起动画 |
| 歌词本开着时播放 sheet 下拉 | 不可达（Dialog 独立窗口挡住） | **新风险**：同窗口后必须由指针闸门保证不可达（§3.5） |

---

## 2. 需求规格（整合原 §1-10 与用户 §11，修正后为准）

### 2.1 核心体验目标（验收总纲）

1. **物理真实感**：弹簧过冲，禁止线性/简单缓动。
2. **无缝衔接**：用户感受是「同一张卡片在变形展开」，不是页面跳转。全程同窗口一次组合，不得闪帧。
3. **内容连续性**：卡片预览文字过渡到全屏排版不闪现、不跳变（以锚行对齐 + 交叉淡化达成，见 §3.4）。
4. **双向可逆**：展开/收起对称，收起更快更利落（阻尼更高）。

### 2.2 正向动画（点击卡片 → 展开）

| 阶段 | 区间 | 内容 |
|---|---|---|
| Phase 1 按压 | 0-100ms | 卡片 0.97 缩放 + 阴影加深（interactionSource 驱动，作用在真卡片上；点击瞬间 overlay 接管） |
| Phase 2 弹跃展开 | 100-600ms | 单一 progress `snapTo(0)` 后 `animateTo(1f, spring)`。容器 bounds 插值（卡片矩形→全屏）、圆角 28dp→0dp、背景色两端同源取色（无渐变需求，见 §4 偏差 4）。播放页其余内容（卡片区域外）透明度 1→0.3 淡出（**只淡出不滑出**，理由见 §4 偏差 5） |
| Phase 3 内容入场 | 与 Phase 2 重叠（progress 0.4 起） | 歌词本顶栏/底栏淡入 + translateY 20dp→0；预览 4 行字号 19sp→26sp、行高 28→36sp 插值，交叉淡化到列表 |
| Phase 4 稳定 | progress=1 | 标准歌词本交互（滚动/拖进度条/振假名开关） |

### 2.3 反向动画（收起）

全屏容器弹跃收缩回**当前实时卡片 bounds**（卡片位置随播放页滚动/窗口变化，收起时取最新值，见 §3.3）；圆角/内容对称反向；歌词本 chrome 淡出；播放页内容透明度恢复。弹簧阻尼比展开更高（更利落）。

### 2.4 手势交互（拖拽收起）

- **触发区**：列表已在顶 + 向下拖（沿用 7ddb048 语义）；顶栏/底栏等非列表 chrome 区任意向下拖。（原规格「任意位置向下滑」与滚动冲突，偏差见 §4 偏差 3。）
- **跟手**：容器随手指实时位移，progress 由拖拽距离线性映射（**拖拽期间线性映射，弹簧只在松手后介入**）；拖拽中反向变向实时响应。
- **释放**：累计拖距 > 120dp（**收起阈值统一为 7ddb048 既有值 120dp/2200px/s**；原规格 §6.1 的「25% 屏高」被此取代——遵循已发布交互的肌肉记忆） **或** 速度 > 2200px/s → 收起 `animateTo(0f)`（速度作为初速度传入弹簧）；否则 `animateTo(1f)` 回弹。
- **点击返回**（左上 ChevronDown / 物理键）：直接 `animateTo(0f)`，不跟手。
- **滚动优先**：列表未到顶时向下拖 = 正常滚动列表，不触发 morph。

### 2.5 状态同步（不变项）

播放不中断、进度条持续更新、进入时自动滚到当前行并高亮、卡片预览必须是当前播放位置附近歌词（现状已满足：peekStart 以 activeI 为中心）。

### 2.6 边界情况

| 场景 | 处理 |
|---|---|
| 快速连点 | 动画进行中忽略点击（闸门：`lyricsOn=false && !morph.isRunning` 才可再开） |
| 动画中切歌 | 预览冻结快照（§3.4），歌词本内容照常活更新；锚行以开启动画瞬间的快照为准 |
| 歌词不足 4 行 | bounds 捕获方案天然自适应卡片高度 |
| 无词/匹配中/失败态卡片 | 现闸门已禁止点击（484 行），不可展开，不变 |
| 旋转/分屏 | 终点 bounds = 当前窗口实时尺寸（graphicsLayer 延迟读取，布局自适应）；Activity 重建时 overlay 若开着直接 `snapTo(1f)` 稳态（不播动画） |
| 后台中断 | frame clock 暂停自然冻结，回前台续播；（可选打磨）onStop 时 snapTo 就近端点 |
| unsynced 歌词 | 列表行内同步按钮照常，morph 不受影响 |
| 歌词本内弹层（调整面板/读音编辑器/导入对话框） | 全部不动，照常叠加 |

---

## 3. 技术方案（路径 A）

### 3.1 总体架构

```
播放 sheet 内容 Box (AppRoot:448)
 ├─ PlayerScreen(vm)                      ← 淡出由 progress 驱动 graphicsLayer
 └─ LyricsMorphOverlay(vm, morph)         ← 新增；lyricsOn=true 时组合，false 时整层卸载
      └─ 全屏 LyricsScreen 内容（布局尺寸恒为全屏）
          · graphicsLayer{ scaleX/Y + translation } ← 容器形变（延迟读）
          · graphicsLayer{ shape=lerp(28dp→0dp); clip } ← 圆角插值（延迟读）
          · 内容分层交叉淡化（预览副本 ⇄ 列表）+ chrome 淡入
```

删除清单（旧路径）：
- AppRoot.kt 534-617 整块：Dialog、`winRef`/`setDimAmount`、`MutableTransitionState`、`AnimatedVisibility` 滑入滑出、随之失效的 `LyricsDebug` 日志。
- LyricsScreen 内 `exitDrag` Animatable + translationY graphicsLayer（180-189 行）——由 morph progress 取代。
- 保留：`lyricsExitRequest` VM 字段 + SideEffect/DisposableEffect 注入模式（AppRoot 562-571，code-review 加固过的写法），lambda 重注入为 `scope.launch { morph.collapse() }`。

### 3.2 状态与常量（单一真源）

```kotlin
// 顶层可调常量（设计微调入口，原规格 §5/§11.2 要求）
object LyricsMorphSpec {
    val expandSpring  = spring<Float>(dampingRatio = 0.75f, stiffness = 380f)  // §4 偏差1
    val collapseSpring = spring<Float>(dampingRatio = 0.85f, stiffness = 550f)
    val COLLAPSE_DRAG_THRESHOLD = 120.dp       // 累计拖距收起阈值，与 7ddb048 一致
    const val FLING_VELOCITY = 2200f          // px/s，与 7ddb048 一致
    // 交叉淡化窗口（progress 区间）
    const val CARD_LABELS_FADE = 0.10f..0.30f   // 卡片头行标签淡出
    const val PREVIEW_FADE_OUT = 0.35f..0.60f  // 预览副本淡出
    const val LIST_FADE_IN     = 0.40f..0.65f  // 列表淡入
    const val CHROME_FADE_IN   = 0.40f..0.70f  // 顶栏/底栏淡入(+translateY 20dp→0)
    const val PLAYER_DIM        = 0.0f..0.6f   // 播放页内容 1→0.3
}

class LyricsMorphState(scope, ...) {
    val progress = Animatable(0f)              // 0f=卡片, 1f=全屏，唯一驱动源
    var cardBounds: Rect?                       // onGloballyPositioned 持续更新，卡片随播放页滚动/布局变化实时跟随（§3.3）
    var phase: Phase                             // Opening / Opened / Closing —— 完成监听防 open() 起点误判（§3.6-4）
    val isRunning: Boolean
    suspend fun open()        // snapTo(0) → animateTo(1f, expandSpring)
    suspend fun collapse(v: Float = 0f)  // animateTo(0f, collapseSpring, initialVelocity=v)
    fun onDrag(deltaPx: Float)   // §3.5：跟手映射，双向 snapTo
}
```

- 所有权：`LyricsMorphOverlay` 组合处 `remember`，随 overlay 卸载而销毁（歌词本关闭时零开销——手势/动画机器全部不挂载）。
- VM 侧仅保留 `lyricsOn`（语义开关）与 `requestLyricsExit()`；**动画细节不进 VM**。

### 3.3 容器形变（性能关键：一次布局，变换驱动）

**禁止逐帧重排**：不做每帧 `Modifier.offset+size`（那会逐帧 relayout 整个 LazyColumn，必掉帧）。做法：

1. overlay 内容**以全屏尺寸布局一次**（恒定终点布局）。
2. 捕获起点：卡片 Column 挂 `onGloballyPositioned`，把 window 坐标 `Rect`（px，取整）持续写入 `morph.cardBounds`——持续更新同时解决「播放页滚动导致卡片位移」「切歌后收起目标漂移」两个边界。
3. 每帧在 **graphicsLayer 块内延迟读取** progress 与 cardBounds 计算变换（不触发重组）：

```kotlin
// transformOrigin 用默认（图层左上原点）；四条边随 p 线性移动，p=0 时精确等于卡片矩形
graphicsLayer {
    val b = cardBounds ?: return@graphicsLayer   // null 走 §3.6-5 兜底
    val p = progress.value
    scaleX = lerp(b.width / size.width, 1f, p)
    scaleY = lerp(b.height / size.height, 1f, p)
    translationX = lerp(b.left, 0f, p)
    translationY = lerp(b.top, 0f, p)
    shape = RoundedCornerShape(lerp(28.dp.toPx(), 0f, p)); clip = true
}
```

   - **边缘线性自检**（实施时用这条验证公式）：左 = translationX = lerp(b.left, 0, p)；右 = translationX + lerp(b.w, w1, p)；上/下同理——四边各自线性到达终点，p=0 与卡片矩形逐像素重合、p=1 全屏。（不要用卡片中心做 transformOrigin：中心锚点下两端重合所需的 origin 坐标 = b.left·w1/(w1−b.w)，随 bounds 漂移且无边缘线性保证。）
   - 圆角用 `graphicsLayer{ shape=...; clip=true }`（draw 属性，可动画），不走 shape 重排。
   - 背景：overlay 根 `background(artBg)`（与卡片/歌词本同源取色），clip 随圆角同步。
4. 跟手映射（§2.4 的 1:1）：`onDrag(deltaPx)` 内部累计拖距 d（正负对称——变向即减），映射 `p = 1 - d / D`，`D = cardBounds.top.coerceAtLeast(屏高·0.2)`——分母取「屏顶到卡片顶」距离，使容器**顶边**（视觉主导边）位移与手指严格 1:1（translationY = d），复刻 7ddb048 的跟手感；下限 clamp 防播放页滚到极端位置时 D 过小、拖拽过敏。p 结果 clamp [0,1]。松手判定用的「累计拖距」就是这里的 d。
5. 播放页淡出：PlayerScreen 内容根挂 `graphicsLayer { alpha = lerp(1f, 0.3f, (p/0.6f).coerceIn(0,1)) }`，延迟读。

### 3.4 内容连续性（防跳变设计）

1. **共享可组合项**：把卡片预览 4 行 + 头行标签从 PlayerScreen 抽成 `LyricsCardPreview(lines, peekStart, activeI, style)`，**卡片与 overlay 起点态共用同一实现**——起点帧与真卡片逐像素一致，无重绘差异。
2. **点击瞬间冻结快照**：`open()` 时快照 `lines/peekStart/activeI/trackId`，动画期间预览不随播放推进变化（避免字在放大中途被换）。
3. **overlay 内容分层**（全部由 progress 的分段窗口驱动，非独立动画）：
   - 预览副本层：字号 `lerp(19,26,p)`、行高 `lerp(28,36,p)`（这两项在组合层读 progress —— 每帧只重组 4 个 Text，可接受；其余全部延迟读），alpha 按 `PREVIEW_FADE_OUT` 窗口 1→0。
   - 列表层（真 LyricsScreen 列表）：alpha 按 `LIST_FADE_IN` 窗口 0→1；**进入动画前预滚动到位**（见 4）。
   - chrome 层（顶栏/底栏）：alpha 按 `CHROME_FADE_IN` 0→1 + translateY `lerp(20dp, 0, p)`。
4. **锚行对齐**：预览中当前行（通常第 2 可见行）的屏幕 Y，与列表预滚后该行 item 的 Y 做对齐——列表用与跟行同一公式 `scrollToItem(activeI, -(viewport/2-60))` 预置，再以两 Y 差 `scrollBy` 校正。验收：交叉淡化区间内锚行视觉位移 ≤ 8dp（慢动作录屏核验）。允许余行布局差异由交叉淡化吸收——**只锚一行，不逐行几何 morph**（YAGNI，原规格 §11.5 已认可交叉淡化路线）。
5. **跟行暂停**：`LaunchedEffect(activeI)`（LyricsScreen:281）在 `progress < 0.95` 时跳过，避免 morph 中途列表自己滚。

### 3.5 手势体系（最大风险区，双层防御）

**必须同时做到**：歌词本展开时 (a) 列表正常滚动，(b) 列表顶下拉 = morph 收起跟手，(c) **播放 sheet 永远不动**，(d) 水平拖动（进度条 scrub）与长按（读音编辑）不受干扰。

1. **指针层闸门（主防御，必须做）**：overlay 根挂自建 `pointerInput`，用初始通道（`awaitEachGesture` + 按事件消费）在**指针层**拦截，让事件根本到不了 LazyColumn 和 sheet 的 drag 检测（nestedScroll 的 pre 派发对 sheet 连接子优先，靠它不可靠，见 §1.2）：
   - down 不消费（保持点击/长按正常）；
   - 过 touch slop 后判主轴：**垂直且向下** 且（列表已在顶 || 起点在 chrome 区）→ 从该事件起消费整条手势流，直接驱动 `morph.onDrag`；
   - 其余（垂直向上、水平、列表未到顶）→ 不消费，正常派发给列表（列表未到顶向下 = 列表滚，sheet 由下条守卫兜底）。
2. **nestedScroll 守卫（兜底，防漏）**：在 LazyColumn 与 sheet 之间（歌词本根 Column）挂连接子，把「列表在顶 + 向下」的**剩余** delta 全量消费并喂给 `morph.onDrag`——即使指针闸门没截住（例如列表滚动惯性顶到顶的瞬间），delta 也不会冒到 sheet。
3. **松手判定**：`onPreFling` / 闸门手势结束时——累计拖距换算的 progress 剩余 > `COLLAPSE_DRAG_THRESHOLD` 对应值 **或** 速度 > `FLING_VELOCITY` → `collapse(velocity)`；否则 `animateTo(1f, collapseSpring)` 回弹。变向实时：`onDrag` 对正负 delta 对称处理（拖距可增可减，天然支持中途变向）。
4. **「列表已在顶」缓存**：`derivedStateOf { !listState.canScrollBackward }`，指针闸门与守卫共用（7ddb048 的 `atTop()` 同款）。
5. **真机必验**（§5 回归清单 G1-G4）：列表顶下拉（收起，sheet 不动）、列表中部下拉（滚列表，sheet 不动）、chrome 区下拉（收起）、进度条横拖/行长按（不被劫持）。若仍出现 sheet 被拖动 → 闸门升级为「列表在顶时消费全部向下指针流」。

### 3.6 返回路由改造

1. `RootBackHandler`（AppRoot:858）`lyricsOn` 分支：`vm.lyricsOn = false` → `vm.requestLyricsExit()`（走收起动画；动画到 0 由 overlay 的完成监听置 `lyricsOn=false`，同现在 Dialog 层 `LaunchedEffect(animState)` 的「动画完再销毁」模式）。
2. sheet `onDismissRequest` 兜底链（AppRoot:415）同改。
3. `lyricsExitRequest` 注入（AppRoot:562-571 模式保留）：重注入为 `scope.launch { morph.collapse() }`。
4. overlay 完成监听（含**起点误判防护**）：`phase` 在 `open()` 入口先置 `Opening`，完成（progress=1）置 `Opened`；`collapse()` 置 `Closing`。销毁条件 = `snapshotFlow { phase == Closing && !isRunning && progress.value == 0f }` → `vm.lyricsOn = false`。若只看 progress==0 && !isRunning，`open()` 起点 `snapTo(0)` 的那一帧会被误判成收起完成——phase 门是必须的。
5. **cardBounds 为 null 兜底**（首帧未测得即被点击等极端时序）：不得渲染 0×0 图层——进度挂起在卡片上方小面板态（快速 fadeIn）或直接走 `snapTo(1f)` 无形变展开，宁可无动画不可闪帧。
5. **MVP 不做** predictive back 手势耦合（系统预览动画被拦截后直接播我们的收起，行为可接受）；**可选进阶**：`PredictiveBackHandler` 把手势进度喂给 morph progress（手势进 → 收起预览，松手 → 完成/回弹）。进阶项独立 commit，失败可回退。

### 3.7 状态栏与沉浸（修正后需求）

本 App 两端都是全幅取色深底、图标颜色只随 app 主题（MainActivity:48-52），**不存在也不需要「黑→白状态栏过渡」**（原规格 §11.6 基于浅色播放页的设定不适用，见 §4 偏差 2）。实际需求仅两条：
1. overlay 画到状态栏后方（sheet 窗口已全幅，`contentWindowInsets` 已置零——现状满足，不新增代码）；
2. 歌词本内容避让 insets：现状 `statusBarsPadding()/navigationBarsPadding()` 由 overlay 根部继承原 Dialog 包装层职责（AppRoot:612-613 的 padding 移到 overlay 根）。

### 3.8 性能设计

- **零逐帧重排**：容器/圆角/淡出/位移全部 graphicsLayer 延迟读（§3.3）；唯二的组合层读 progress：预览 4 个 Text 的字号插值、chrome 层参数（≤10 个节点，可接受；若实测掉帧，字号可改「分层 scaleY 近似」备选，但默认方案即可）。
- 列表渐隐遮罩沿用 9c66adc 优化后的外层 Box DstIn 方案，不动。
- 跟行动画 morph 期间暂停（§3.4-5）。
- 歌词本关闭时整层 `if (lyricsOn)` 卸载，零常驻开销。

---

## 4. 与原规格 / §11 的偏差与理由（⚠️ 审阅重点）

| # | 规格原文 | 本方案 | 理由 |
|---|---|---|---|
| 1 | §11.2 弹簧起点 `DampingRatioMediumBouncy(0.5)+StiffnessMedium(1500f)` | 展开 `ζ=0.75, K=380`；收起 `ζ=0.85, K=550` | Compose spring 无质量/刚度独立参数（只有 dampingRatio+stiffness）。ζ=0.5 在 ~800dp 行程上过冲 ≈16%（≈130dp）——远超「轻微过冲」；stiffness 1500 峰值速度过高观感暴烈。ζ=0.75 过冲 ≈2.8%（≈20dp）、稳定 ≈500-600ms，恰好落在原规格 §5（总时长 500-700ms、轻微过冲）的目标带内。原 §5 的 web 弹簧参数（stiffness 120-150 / damping 12-16 / mass 1）换算 ζ≈0.49-0.65，与建议值同区间，语义一致 |
| 2 | §11.6 状态栏文字黑→白过渡 | 不做（两端同深底、图标只随主题） | MainActivity:48-52 是全局主题驱动，本 App 播放页不是浅色米白；做了反而引入规格外行为 |
| 3 | §6.1 屏幕任意位置向下滑 | 列表顶 + chrome 区；列表中部 = 滚动 | 「任意位置」与歌词滚动互斥；沿用已发布的 7ddb048 语义（用户已验收过的交互） |
| 4 | 原规格 §4.1 卡片 #4A4A7A → 全屏 #3D6B6B 背景渐变 | 不做色相过渡（两端同源取色） | 本项目卡片与歌词本本就同为 `rememberCoverPalette` 一色；规格 §8 也要求不写死色值。颜色唯一需要处理的是 clip 联动（§3.3-3） |
| 5 | §11.5 播放页封面 translateY 滑出 + 透明度 | 只做透明度 1→0.3 | morph 本身已是唯一的强位移元素；叠加滑出抢视觉焦点且多一层变换。原规格 §4.1 本就给出二选一，选淡出 |
| 6 | §11.4 手势用 `detectVerticalDragGestures` | 自建指针闸门 + nestedScroll 守卫（§3.5） | `detectVerticalDragGestures` 挂在 overlay 根会与 LazyColumn 抢正常滚动；且它不走消费链，防不住 sheet 的 anchoredDraggable 抢手势。§1.2 的结构性风险要求指针级拦截 |

---

## 5. 实施步骤建议（commit 切分，供实施会话排期）

每个 commit 均须过 `gradlew.bat assembleDebug` + 对应真机自测项。

| 阶段 | 内容 | 验证 |
|---|---|---|
| P0 素材 | 抽 `LyricCardPreview` 共享可组合项 + 卡片 `onGloballyPositioned` bounds 上报（无行为变化） | 卡片视觉与现在逐像素一致 |
| P1 骨架 | `LyricsMorphState` + overlay 宿主 + 容器形变（bounds/圆角/背景 clip）；点击开（弹簧展开），左上返回/物理键收（弹簧收起）；删 Dialog 块与重接返回路由（§3.6-1/2/4） | 展开/收起无闪帧、有弹簧感；**暂不含拖拽手势**——列表滚动先允许「顶下拉退出暂时失效」的中间态 |
| P2 内容 | 快照冻结 + 交叉淡化 + 锚行对齐 + chrome 淡入 + 播放页淡出 + 跟行暂停 | 慢动作录屏：锚行位移 ≤8dp、无文字闪现 |
| P3 手势 | 指针闸门 + nestedScroll 守卫 + 松手判定（§3.5 全量） | G1-G4 手势矩阵 |
| P4 边界 | 连点防抖、旋转/重建稳态、切歌冻结、（可选）predictive back 耦合 | §2.6 边界表逐项 |

---

## 6. 验收与回归清单

### 真机手势矩阵（全部必过）

| # | 操作 | 期望 |
|---|---|---|
| G1 | 歌词本列表滚到顶后继续向下拖 | 容器跟手收缩；**播放 sheet 全程纹丝不动** |
| G2 | 列表中部向下拖 | 列表正常滚动；sheet 不动 |
| G3 | 顶栏/底栏向下拖 | 容器跟手收缩；sheet 不动 |
| G4 | 底部进度条横向拖、歌行长按 | scrub/读音编辑正常，不被垂直闸门劫持 |
| G5 | 松手 < 阈值 / > 阈值 / 高速甩 | 分别回弹全屏 / 收起 / 收起（速度衔接，无顿挫） |
| G6 | 拖拽中反向变向 | 实时响应，无粘滞跳变 |
| G7 | 物理返回键 / 右滑 back / 左上返回 | 三路都走同一收起动画（不瞬间消失） |
| G8 | 动画中连点卡片 / 快速开-关-开 | 状态不乱，动画排队或忽略 |
| G9 | 展开中后台→回前台 | 续播或稳定端点，不闪 |
| G10 | 旋转/分屏后开合 | 终点取当前窗口尺寸 |
| G11 | 展开中切歌 | 预览不跳变；收起落点为新卡片的正确 bounds |
| G12 | 歌词 <4 行 / unsynced / 无词卡片点击 | 自适应高度 morph / 行内同步按钮正常 / 不可展开 |

### 既有功能回归（本改动触碰的邻接面）

- 播放 sheet 原有下拉关闭、三点菜单、队列 sheet、调整面板（lySheet）、读音编辑 AlertDialog、歌词导入/来源选择 Dialog（AppRoot 级，窗口叠加关系不变）。
- 振假名全链路（あ 开关、行高动画、UtaTen/LLM 增强）在 overlay 宿主下照常。
- 深色/浅色主题、刘海屏安全区。

### 构建门禁

`gradlew.bat assembleDebug` 通过；工作区干净；按 CLAUDE.md：feature 分支 `feature/lyrics-morph-transition`（从 develop 拉），PR 合回 develop。

---

## 7. §11 约束映射表（逐条核对）

| §11 条目 | 本方案位置 | 状态 |
|---|---|---|
| 11.1 同窗口 overlay / 单一 progress / zIndex / scrim | §3.1、§3.2 | ✅ 全采纳（scrim 本就透明且在窗口底层） |
| 11.2 Animatable + snapTo/animateTo / 弹簧可调常量 / 禁独立动画 | §3.2（分段窗口是 progress 的函数，非独立动画） | ✅ 采纳，参数值修正见偏差 1 |
| 11.3 bounds 捕获 / graphicsLayer 硬件加速 | §3.3 | ✅ 采纳并强化（禁逐帧重排） |
| 11.4 手势（detectVerticalDragGestures） | §3.5 | ⚠️ 机制替换，见偏差 6 |
| 11.5 内容过渡（字号/截断/标签/淡入窗口） | §3.4 | ✅ 采纳，窗口数值落在规格 30%-40% 起点 |
| 11.6 状态栏黑→白 | §3.7 | ⚠️ 不适用，见偏差 2 |
| 11.7 derivedStateOf / 跟行降级 / 连点防抖 | §3.8、§2.6 | ✅ 采纳（重组控制以延迟读为主） |
| 11.8 回归测试点 | §6 | ✅ 全部纳入 |
