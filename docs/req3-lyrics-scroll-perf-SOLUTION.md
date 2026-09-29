# 需求3验证方案：歌词跟行滚动卡顿根因

> 2026-09-26 验证步骤设计

## 一、核心疑点：上轮修复是否真的生效

### 1.1 上轮两个优化回顾

**Commit eca0b54 + 25044d3**：
1. **@Immutable 四连**（LyricLine/ParsedLyrics/LoadedLyrics/RubySegment）  
   - 目标：数据稳定性标注，让 Compose 智能跳过参数未变的 Composable 重调用
   - 预期效果：`items(lines)` 中未变化的行跳过重组

2. **activeI 改 derivedStateOf**  
   - 目标：读点订阅行推进结果（每 2-5s 一次）而非 positionMs ticker 原始值（每 300ms 写一次）
   - 预期效果：消除全页 300ms 重组风暴

**用户实测反馈（release 包）**："歌词滚动的帧率仍然很低，一顿一顿的"

### 1.2 疑点4（最关键）：activeI 的读点位置

**问题核心**：`activeI` 的读点在 `items` 的 item lambda 内（`i <= activeI` 用于判断行颜色）：

```kotlin
// LyricsScreen.kt:313
itemsIndexed(lines) { i, line ->
    val a = when {
        i == activeI -> 1f        // ← 这里读取 activeI
        i < activeI -> 0.62f      // ← 这里读取 activeI
        else -> 0.40f
    }
    Text(line.text, style = body(...).copy(alpha = a))
}
```

**理论分析**：
- `derivedStateOf` 只能让"读点"跳过中间值（positionMs 300ms ticker 不触发重算），但**不能阻止读点所在 lambda 失效重跑**
- LazyColumn 的 `items` 内容 lambda 读 `activeI` → 行推进时 `activeI` 变化 → **整个 items lambda 失效重跑** → 全部可见行重组合
- @Immutable 只帮"参数没变跳过 Composable 调用"（如 `Text(line.text)` 中 `line` 没变就跳过），但**不帮 lambda 本身失效**

**如果疑点4成立**：
- 上轮修复只消除了 300ms ticker 风暴（播放期间 activeI 不变时不重组），但**行推进主场景的拖拽重组没修掉**
- 每次行推进（每 2-5s）仍会重组全部可见行（~15 行）→ 15×（26sp Extrabold Text 测量 + 颜色动画状态机）→ 单帧超 16ms → "一顿一顿"

## 二、验证步骤（实机 Composition tracing）

### 2.1 目标

用 Compose Composition tracing 实测一次"行推进"事件发生时：
1. 有多少个 item 的 lambda 被重新执行？（预期：全部可见行 ~15 个 vs 理想：仅 2 个（旧行+新行））
2. 每次重组耗时多少？（单帧总耗时 vs 16ms 阈值）
3. 确认 @Immutable 是否真的跳过了参数未变的 Text 组合？

### 2.2 工具选择

**方案A：Compose Compiler Metrics（编译期）**  
不适用，只分析编译期稳定性，不测运行时实际重组

**方案B：Layout Inspector 重组高亮（Android Studio）**  
- 优点：可视化，直观看到哪些组件在重组
- 缺点：release 包不支持；需要 USB 连接；实时观察难以量化

**方案C：Logcat 手动打点（推荐）**  
在关键位置加 Log，统计行推进时的重组次数和耗时

### 2.3 打点方案（Commit 0：诊断版本）

```kotlin
// LyricsScreen.kt itemsIndexed 块内
itemsIndexed(lines) { i, line ->
    // ──── 打点1：item lambda 执行计数 ────
    SideEffect {
        if (android.os.Build.VERSION.SDK_INT >= 31) {  // 只在 Android 12+ 打印，避免低版本日志风暴
            android.util.Log.d("LyricsPerfTrace", "item $i recomposed")
        }
    }
    
    val a = when {
        i == activeI -> 1f
        i < activeI -> 0.62f
        else -> 0.40f
    }
    
    // ──── 打点2：颜色动画状态机触发 ────
    val animatedAlpha by animateFloatAsState(
        a,
        animationSpec = tween(220, easing = FastOutSlowInEasing),
        label = "lyric-alpha-$i"
    )
    // SideEffect { Log.d("LyricsPerfTrace", "item $i alpha animation: $a → $animatedAlpha") }
    
    Text(
        line.text,
        style = body(26f, FontWeight.ExtraBold, LyricText).copy(
            lineHeight = 36.sp,
            alpha = animatedAlpha
        ),
        // ...
    )
}
```

**额外打点：activeI 变化事件**
```kotlin
// LyricsScreen.kt activeI 定义处（约 :112）
val activeI by remember {
    derivedStateOf {
        val pos = vm.player.positionMs
        lines.indexOfLast { it.timeMs != null && it.timeMs!! <= pos }
    }
}

// 加一个 LaunchedEffect 监听 activeI 变化
LaunchedEffect(activeI) {
    if (activeI >= 0) {
        android.util.Log.d("LyricsPerfTrace", "===== activeI changed to $activeI =====")
    }
}
```

### 2.4 实机验证步骤

**步骤1：编译并安装打点版本**
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

**步骤2：打开 logcat 过滤**
```bash
adb logcat -s LyricsPerfTrace:D | tee lyrics-perf-trace.log
```

**步骤3：播放一首有时间戳歌词的歌曲**
- 选择行数较多的歌曲（~50 行）
- 等待自动跟行推进 2-3 次（每次推进约 2-5s）

**步骤4：分析日志输出**

典型输出示例（疑点4成立时）：
```
===== activeI changed to 5 =====
item 0 recomposed
item 1 recomposed
item 2 recomposed
item 3 recomposed
item 4 recomposed
item 5 recomposed
item 6 recomposed
item 7 recomposed
item 8 recomposed
item 9 recomposed
item 10 recomposed
item 11 recomposed
item 12 recomposed
item 13 recomposed
item 14 recomposed
===== activeI changed to 6 =====
item 1 recomposed
item 2 recomposed
... (又是全部可见行)
```

**判断标准**：
- **疑点4成立**：每次 activeI 变化后，有 10+ 行 recomposed（全部可见行）
- **疑点4不成立**：每次 activeI 变化后，只有 2-3 行 recomposed（旧焦点行+新焦点行+邻近行）

### 2.5 量化性能数据（可选：Systrace）

如果需要精确的单帧耗时数据（判断是否超 16ms）：

```bash
# 抓取 Systrace（播放期间抓 10 秒）
python $ANDROID_HOME/platform-tools/systrace/systrace.py \
    -o lyrics_scroll_trace.html \
    -t 10 \
    -a com.shiyin.music \
    view dalvik sched freq idle

# 在浏览器打开 lyrics_scroll_trace.html，找 "Choreographer#doFrame" 事件，
# 看行推进时刻的帧耗时是否 > 16ms
```

## 三、如果疑点4成立：修复方向

### 3.1 根本问题

`activeI` 的读点在 item lambda 内 → lambda 失效 → 全行重组

### 3.2 修复方向：下推读点到各行内部

**目标**：让每行各自读取"我是否是焦点行"，而不是在 item lambda 顶层读 `activeI`

**方案A：key() 驱动（推荐）**
```kotlin
itemsIndexed(lines, key = { i, _ -> i }) { i, line ->
    // 不在这里读 activeI，改为传入"焦点状态"
    val isFocused = remember(activeI) { i == activeI }
    val isPast = remember(activeI) { i < activeI }
    
    val a = when {
        isFocused -> 1f
        isPast -> 0.62f
        else -> 0.40f
    }
    val animatedAlpha by animateFloatAsState(a, tween(220))
    Text(line.text, style = body(...).copy(alpha = animatedAlpha))
}
```

**原理**：
- `remember(activeI)` 读 `activeI`，但**只在 activeI 变化时重算这个 remember 块**
- item lambda 顶层不再有裸读 `activeI` → lambda 不失效
- 每行的 `remember(activeI)` 独立重算（~15 次微小开销），但不触发 Text/animateFloatAsState 重组（因为参数没变）

**方案B：derivedStateOf 包装到 VM 层（备选）**
```kotlin
// MainViewModel.kt
val lyricsActiveIndex = mutableIntStateOf(-1)  // 由 LyricsScreen 写入
val lyricsFocusMap = derivedStateOf {
    val activeI = lyricsActiveIndex.intValue
    lines.indices.associateWith { i ->
        when {
            i == activeI -> 1f
            i < activeI -> 0.62f
            else -> 0.40f
        }
    }
}

// LyricsScreen.kt
itemsIndexed(lines) { i, line ->
    val a = vm.lyricsFocusMap.value[i] ?: 0.40f  // 读 Map，而非读 activeI
    val animatedAlpha by animateFloatAsState(a, tween(220))
    Text(line.text, style = body(...).copy(alpha = animatedAlpha))
}
```

**原理**：
- 读点从 `activeI` 改为 `vm.lyricsFocusMap[i]`（Map 读取）
- Map 本身是 derivedStateOf 算出的，activeI 变化时重算 Map
- item lambda 读 Map 的某个 key，而非读会变的 activeI → 稳定性更好

**对比**：
- 方案A 实现简单，局部修改
- 方案B 更彻底解耦，但引入 VM 层状态

### 3.3 验证修复效果

修复后重新打点 + logcat，预期输出：
```
===== activeI changed to 5 =====
item 4 recomposed   // 旧焦点行（alpha 1.0 → 0.62）
item 5 recomposed   // 新焦点行（alpha 0.40 → 1.0）
===== activeI changed to 6 =====
item 5 recomposed
item 6 recomposed
```

**判断标准**：每次 activeI 变化只重组 2-3 行（焦点行+邻近行），不再是全部可见行

## 四、其余嫌疑点验证优先级（疑点4验证后再决定）

### 4.1 嫌疑2：行颜色动画 animateColorAsState

**如果疑点4修复后仍卡顿**，检查颜色动画状态机开销：

```kotlin
// 当前实现：每行一个独立的 animateFloatAsState
val animatedAlpha by animateFloatAsState(a, tween(220))
```

**问题**：
- 每行一个状态机 → 行推进时旧行+新行各起一个动画
- 动画期间（220ms）每帧驱动这两行的 lambda 重组

**验证**：
- 在打点版本中，行推进后 220ms 内，观察 item lambda 是否每帧（16ms）都被调用
- 如果是 → 颜色动画的逐帧重组是开销源之一

**修复方向**（仅在疑点4修复后仍卡顿时考虑）：
- 改用 Modifier.graphicsLayer { alpha = animatedAlpha }（延迟读，不触发组合）
- 或者用 drawWithContent 自绘 alpha（完全绕过状态机）

### 4.2 嫌疑1：26sp Extrabold Text 测量开销

**如果前两项修复后仍卡顿**，检查 Text 测量：

- 新行滑入视口时，Text 组合 + 测量是必然开销
- 26sp Extrabold + 36sp 行高 → 每个 Text 测量耗时约 1-2ms
- 一帧内多个新 item 进视口（滚动速度快时）→ 累计超 16ms

**验证**：
- Systrace 看 "TextLayoutHelper" 事件耗时
- 或 logcat 在 Text 外加 `SideEffect` 统计组合次数

**修复方向**（最后备选，实现成本高）：
- 预渲染（offscreen Composable + Bitmap 缓存）→ 复杂度高，不推荐
- 降低字号/行高（用户体验降级）→ 不可接受

### 4.3 嫌疑3：RubyText 双动画状态机

**仅影响日文歌词行**，非通用卡顿源。如果只有日文歌词卡顿、普通歌词不卡，再排查这条。

### 4.4 嫌疑5：预滚 scrollToItem 一帧跳变

**切歌瞬间**的卡顿，不是"播放中跟行推进"的卡顿源。如果切歌时卡顿明显、播放期间不卡，再看这条。

## 五、分阶段验证计划（不要一次改多处）

### 阶段1：诊断验证（必做）

1. 实现打点版本（Commit 0：+10 行 Log）
2. 装机 + logcat 收集日志
3. 分析：疑点4是否成立（全行重组 vs 仅焦点行重组）
4. **输出**：确凿数据（"每次行推进重组 X 行，耗时 Y ms"）

### 阶段2：修复疑点4（如果阶段1确认成立）

1. 实现方案A（key() + remember(activeI)，Commit 1：修改 itemsIndexed 块）
2. 保留打点 Log，重新装机 + logcat
3. 对比修复前后日志：重组行数从 15 → 2-3
4. 用户实测：滚动是否仍"一顿一顿"
5. **如果流畅** → 撤掉打点 Log，推送 Commit 1
6. **如果仍卡顿** → 进入阶段3

### 阶段3：验证嫌疑2（颜色动画，仅在阶段2后仍卡顿时）

1. 修改 animateFloatAsState 为 graphicsLayer 延迟读（Commit 2）
2. logcat 确认动画期间 item lambda 不再逐帧重组
3. 用户实测
4. 流畅 → 推送；仍卡顿 → 进入阶段4

### 阶段4：验证嫌疑1（Text 测量，仅在前三项后仍卡顿时）

- Systrace 精确定位瓶颈
- 根据数据决定是否需要更深层优化（预渲染/降级方案）

## 六、关键约束

1. **不要一次改多处**：先验证疑点4，确认后再考虑其余嫌疑
2. **每步要数据支撑**：logcat / Systrace 实测，不能只说"看起来好一点了"
3. **确认成立 or 排除**：每个嫌疑点要么"数据证实是根因"，要么"数据证明不是"
4. **release 包验证**：debug 包性能数据不准（关了 R8 minify），最终验证必须 release 包

## 七、预期结果

**最可能路径**（根据理论分析）：
1. 阶段1确认疑点4成立（全行重组）
2. 阶段2修复后流畅度显著改善
3. 如仍有轻微卡顿，阶段3解决颜色动画逐帧重组
4. 嫌疑1（Text 测量）大概率不是瓶颈（已有 @Immutable，参数未变时跳过测量）

**最坏情况**（疑点4不成立）：
- 上轮修复已真正生效，卡顿源在其他地方（网络请求/图片加载/GC）
- 需要更全面的 profiling（Systrace / Android Studio Profiler）
- 或者"一顿一顿"是主观感受与 240ms 跟行动画的节奏不匹配（调参而非性能问题）

## 八、总结

**核心验证目标**：疑点4（activeI 读点在 item lambda 内 → 全行重组）是否成立

**验证手段**：Logcat 打点 + 实机测试，量化重组行数和耗时

**修复方向**（如果疑点4成立）：下推读点到各行内部（key() + remember(activeI)）

**不要做的事**：
- 不要跳过验证直接叠加新优化（会混淆根因）
- 不要同时改多处（无法隔离哪个有效）
- 不要只凭"感觉流畅了"就结论（要数据）

**下一步**：实施阶段1（打点版本），收集实机数据，确认疑点4是否成立。
