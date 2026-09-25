# 歌词本下拉退出功能报告

> 用户要求：本版本直接禁止下拉收起歌词本，同时产出本报告，供后续讨论修复方案。

## 一、问题

歌词本（LyricsMorphOverlay）的下拉收起手势在五轮修复后仍误触：
- **现象**：列表不在顶部时下拉仍触发收起（用户"明明不在顶层也触发"）
- **已尝试的修复**（均未根治）：
  1. 起手快照 `downAtTop`（第6轮）：按下那一刻读 `lyricsListAtTop` 快照——修复了"跟行自动滚到顶的瞬时态"误判
  2. 删 `inChrome` 触发路径（第2轮）：起手在 chrome 区（顶/底栏）的下拉允许收起——用户实测"拉着拉着就触发"
  3. 删"中途到顶接管"路径（第3轮）：起手不在顶、滚动中列表到顶的手势直接接管——用户实测"列表中部向下甩,滚动到顶时手指还在动,立刻被接管去收起"
  4. nestedScroll 守卫消费中途到顶的向下 delta（不喂 morph、不冒泡到播放 sheet）
  5. headerCount 动态计算（拆分专辑的额外 header item 导致 from.index - 5 错位）

## 二、当前实现（v1.3.x，已禁用）

### 2.1 架构

```
PlayerScreen（播放页）
  └─ if (vm.lyricsOn) LyricsMorphOverlay(vm, morph)  // 组合门
      ├─ 窗口层 Box: graphicsLayer { 非等比 scale + translate + 圆角 + clip + alpha }
      ├─ 内容层 Box: LyricsScreen（真列表，全屏原生布局、不缩放）
      │    ├─ Box (weight=1, nestedScroll 守卫)
      │    │    └─ LazyColumn (歌词行 itemsIndexed)
      │    └─ 底栏 (进度条 + 时间 + 播放键 + 振假名/翻译按钮)
      └─ 指针闸门 pointerInput (Initial pass)
```

### 2.2 下拉收起的判定链（五轮修复后的最终版）

**指针闸门**（`LyricsMorphOverlay.kt:102-183`）在 `Initial` pass 拦截：

```
awaitEachGesture {
  down = awaitFirstDown(Initial pass)
  downAtTop = vm.lyricsListAtTop          // 起手快照（第6轮修复）
  while (true) {
    event = awaitPointerEvent(Initial pass)
    if (!dragging) {
      if (|dy| > touchSlop && dy > 0) {   // 过 slop 且向下
        pullDown = true
        if (downAtTop) dragging = true    // 唯一判据：起手在顶
      }
    }
    if (dragging) {
      morph.onDrag(dy)                    // 喂 morph progress
      change.consume()                    // 拦截,不传到 LazyColumn/sheet
    }
    if (抬手) {
      if (dragging) {
        if (拉距 > 120dp 或 速度 > 2200px/s) vm.collapseLyrics(velocity)
        else animateTo(1f)                // 回弹到全开
      }
      break
    }
  }
}
```

**nestedScroll 守卫**（`LyricsScreen.kt:436-484`）兜底：

```
onPreScroll(available, source):
  if (available.y > 0 && !canScrollBackward && source == UserInput) {
    return available   // 列表在顶 + 向下 + 用户拖拽 → 消费 delta,不喂 morph、不冒泡
  }
  return Zero
```

### 2.3 仍误触的根因分析

五轮修复后，仍存在以下误触路径（无法在静态代码层面完全排除）：

1. **`lyricsListAtTop` 的时序窗口**：`LyricsScreen` 的 `LaunchedEffect(listState)` 用 `snapshotFlow { !canScrollBackward }` 写 `vm.lyricsListAtTop`。`snapshotFlow` 有帧延迟——用户快速滑动时，列表刚到顶的瞬时 `canScrollBackward` 翻 false，但 `snapshotFlow` 的 collect 可能还没跑，`lyricsListAtTop` 仍是旧值。此时用户起一手下拉，闸门读到 `downAtTop=false` 不收起——**但反过来**，列表还在中部时 `snapshotFlow` 的 collect 刚好跑了（上一帧的快照），`lyricsListAtTop=true`，用户起手下拉就误触。

2. **`awaitFirstDown` 的 Initial pass 与 LazyColumn 的 Main pass 竞态**：`Initial` pass 父先于子收事件，理论上闸门能拦截。但 `awaitFirstDown` 返回后到第一次 `awaitPointerEvent` 之间，如果 `LazyColumn` 的 `draggable` 在 `Main` pass 已开始消费（`detectDragGestures` 的 `awaitFirstDown` 用默认 `Main` pass），手势可能被两处同时消费。

3. **播放 sheet 的 `anchoredDraggable` 干扰**：`PlayerScreen` 的 `ModalBottomSheet` 的 `anchoredDraggable` 对"sheet 正文任意位置"的下拉 1:1 跟手。`Initial` pass 的闸门在 overlay 层，sheet 在 overlay 之下——如果闸门没消费（如 `dragging=false`），事件冒泡到 sheet，sheet 自己拉下，视觉上歌词本"被收起"（实际是 sheet 拉下，但用户感知一样）。

4. **`onDrag` 的 velocity 计算精度**：`VelocityTracker` 在快速甩动时计算的 velocity 波动大，`velocity > 2200px/s` 的阈值在中等速度甩动下也会触发，导致"明明只拉了一点就收起了"。

## 三、禁用方案（当前版本）

直接把指针闸门改成**纯穿透**——不消费任何手势，不判定、不收起：

```kotlin
// LyricsMorphOverlay.kt:102-120
.then(if (interactive) Modifier.pointerInput(Unit) {
    awaitEachGesture {
        val down = awaitFirstDown(Initial pass)
        while (true) {
            val event = awaitPointerEvent(Initial pass)
            if (!event.changes.any { it.pressed }) break
        }
        // 什么都不做——事件穿透,不消费,不判定,不收起
    }
} else Modifier)
```

收起路径仍保留：
- **返回键**：`vm.requestLyricsExit()` → morph `collapse()`
- **系统手势**：`BackHandler` → 同上
- **底栏播放键**：点击播放键不收起（原行为）

## 四、后续修复方向（供讨论）

### 方案 A：恢复下拉，但用 `onDrag` 的精确位移替代 velocity 阈值

去掉 velocity 判定，只用**精确拉距**（progress 从 1→0 的位移 > 50% 才收起）。velocity 波动大是误触的主因之一，纯位移判定更稳定。代价：快速甩动不再秒收，需要拉过半屏。

### 方案 B：恢复下拉，但把 `lyricsListAtTop` 改成同步读

`snapshotFlow` 有帧延迟。改成在 `awaitFirstDown` 时直接同步读 `listState.canScrollBackward`（不经过 `snapshotFlow` + VM 中转），消除时序窗口。代价：`canScrollBackward` 在 `Initial` pass 读的是上一帧的布局结果，仍有 1 帧延迟，但比 `snapshotFlow` 的 collect 延迟小。

### 方案 C：恢复下拉，但只在 chrome 区（顶/底栏）触发

列表区永不触发下拉收起，只有顶栏（返回键/标题/设置）和底栏（进度条/播放键）的下拉才收起。用 `onGloballyPositioned` 上报的 bounds 判定起手位置在 chrome 区还是列表区。代价：用户习惯"在歌词上拉下来收起"，改成"只能在顶/底栏拉"需要适应。

### 方案 D：恢复下拉，但用 sheet 的 `anchoredDraggable` 统一收起

不用 morph 自定义手势，改用 `ModalBottomSheet` 的 `anchoredDraggable`（sheet 本身的下拉收起）。morph 的 progress 跟随 sheet 的 `draggedFraction`。代价：morph 的窗口形变动画要改成跟随 sheet offset，窗口层 graphicsLayer 的 translationY 改读 sheet offset 而非 morph.progress——改动量大，可能破坏已验证的窗口落点公式。

### 推荐顺序

1. 先试 **方案 B**（同步读 `canScrollBackward`）——改动最小，直接消除时序窗口
2. 不行再试 **方案 A**（去 velocity、纯位移）——消除 velocity 波动
3. 都不行再试 **方案 C**（chrome 区限定）——语义最清晰，用户适应成本可接受

## 五、关键文件

- `app/src/main/java/com/shiyin/music/ui/screens/LyricsMorphOverlay.kt` — 指针闸门 + 窗口层 + 内容层
- `app/src/main/java/com/shiyin/music/ui/screens/LyricsScreen.kt:436-484` — nestedScroll 守卫
- `app/src/main/java/com/shiyin/music/ui/components/LyricsMorphState.kt` — morph 状态机（progress/cardBounds/screenH）
- `app/src/main/java/com/shiyin/music/MainViewModel.kt` — `lyricsListAtTop` / `requestLyricsExit` / `collapseLyrics`
