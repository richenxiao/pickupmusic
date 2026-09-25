# 需求 3：歌词跟行滚动仍然"一顿一顿"——先验证上轮修复是否生效

> 独立需求文档，可直接单独发给分析方。

## 现状

已做两个优化（commit eca0b54 + 25044d3）：
1. @Immutable 四连（LyricLine/ParsedLyrics/LoadedLyrics/RubySegment）——数据稳定性标注，item 跳过能力
2. `activeI` 改 `derivedStateOf`——读点订阅行推进结果（每 2-5s 一次）而非 positionMs ticker 原始值（每 300ms 写一次），消除全页 300ms 重组风暴

但用户实测（release 包）："歌词滚动的帧率仍然很低，一顿一顿的"。

**疑点 4（本条最关键）**：`activeI` 的读点在 item lambda 内（`i <= activeI`）——行推进时整个 LazyColumn 的 items 内容 lambda 失效重跑，**每次行推进全列表可见行重组合**。@Immutable 只帮"参数没变跳过 Composable 调用"，不帮"lambda 本身失效重跑"。若成立，意味着上一轮修复对"行推进"这个卡顿主场景收效有限（300ms 风暴修掉了，但行推进的拖拽重组没修掉）。

## 跟行滚动的动画链

```
activeI 变化(每 2-5s 一次)
  → LaunchedEffect(activeI) 触发
  → listState.animateScrollBy(d, tween(240, FastOutSlowInEasing))
  → LazyColumn 滚动 → 新行进入视口 → item 组合 + 26sp Text 测量布局
```

其余嫌疑（未验证，按用户要求不在此阶段动手）：
1. 26sp Extrabold 大字 + 36sp 行高的 Text 测量开销（新行滑入时组合+测量）
2. 行颜色动画 animateColorAsState(tween 220ms) 每行一个状态机
3. RubyText 双动画状态机（日文行，排版比纯 Text 贵得多）
4. 预滚 LaunchedEffect(track.id) 用 scrollToItem（无动画，切歌一帧跳变）

## 已确认的无关项

- 不是 debug 构建（release 包，R8 minify=true）
- 不是 positionMs ticker 300ms 风暴（已修；滚动期间 activeI 不变时 derivedStateOf 不重算不重组）

## 方案分析要求

不要提出新的优化点，先集中火力验证一件事——上一轮的两个修复（`@Immutable` 四连、`activeI` 改 `derivedStateOf`）是否真的在滚动时生效，尤其是"疑点4"。

**请给出：**

1. **验证"疑点4"的具体实机手段**：用 Compose Composition tracing 或 Layout Inspector 重组高亮，实测一次"行推进"事件发生时，到底有多少个 item 的 lambda 被重新执行、耗时多少。如果和"疑点4"描述的一致（`activeI` 的读点在 item lambda 内导致每次行推进都拖着可见的所有行重组），这一步要能拿出确凿的数据证明，而不是继续猜测。
2. **如果疑点4成立**，给出修复方向：`activeI` 的读点应该挪到哪里，才能让"只有真正需要变色/变化的那一两行重组，其余可见行完全跳过"。给出具体的重构思路（比如把"当前行判断"下推到每个 item 内部各自读取一个更细粒度的稳定输入，或者用 key 驱动而不是整体索引比较）。
3. **其余四个已知嫌疑点的验证优先级排序**——哪个应该在疑点4验证之后第一个测，为什么，给出验证手段（不是直接假设它是根因就去改）。
4. **不要一次改多处**：给出一个"先验证疑点4→根据结果决定下一步只改一处→装机验证→再决定要不要处理下一个嫌疑点"的顺序计划，而不是把五个嫌疑点打包一起改。

先出结论附实测依据，不要直接开始改代码。每一步验证结果都要写清楚"确认成立"还是"排除"，不能只说"看起来好一点了"就跳过验证直接归因。

## 附：关键文件

- `app/src/main/java/com/shiyin/music/ui/screens/LyricsScreen.kt`（itemsIndexed :313 / 行渲染 :314-393 / activeI derivedStateOf :112 / 跟行 LaunchedEffect :253）
- `app/src/main/java/com/shiyin/music/ui/components/RubyText.kt`
- `app/src/main/java/com/shiyin/music/data/lyrics/LrcParser.kt`
- 分支 `feature/furigana-llm-assist`

## 附：历史教训（同类陷阱）

"transformOrigin 改了毫无变化"（死层）是同类陷阱的前车之鉴：写了修复代码，但没有验证它是否真的在运行时的实际路径上起作用。本条必须优先解决"验证有没有生效"的问题，不能继续叠加新的优化猜测。
