# 迷你播放条横向分页切歌 —— 落地交接（2026-09-16）

> 来源：接手 Claude Code 会话 `f5b325e5-5c6a-4f04-9412-f0b231143c49`（2026-09-13 ~ 09-16，8743 行）。
> 会话在 09-15 17:52 一次 `Edit` 落盘后即被中断，随后连续 400/429 API 错误，工作区留下一个**编译不过**的 `AppRoot.kt`。
> 本文只记**已落地的结果**与**尚未完成的事**，不含推理过程。

---

## 一、根因：中断的那次 Edit 漏了一个花括号

编译器报了 33 条错误，其中 30 条是 `Modifier 'private' is not applicable to 'local function'` + 一条 `Syntax error: Expecting '}'`。**这些全是假象**，真相只有一个：

新增的内层 `Row`（分页轨道包裹层）只加了开括号 `) {`，**闭合 `}` 被错当成了外层 `Row` 的闭合** →
外层 `Row` 一直开着 → 进度条 `Box` 落进 `RowScope`（`.align()` 报 `cannot be called with an implicit receiver`）→
`MiniPlayer` 函数永不闭合 → 文件末尾 `Expecting '}'`。

**修法**：删掉多余的内层 `Row`，把它的手势/尺寸测量移到外层 `Row` 与 `BoxWithConstraints` 上。括号随即平衡，
30 条 `local function` 假错误全部消失。

> 排查手段：花括号深度分析器（见 `~/.workbuddy-ai/skills/take-over-claude-session/scripts/brace_depth.py`）。
> 定位顺序：先 `grep -n "private fun MiniPlayer"` 拿准函数首行，再从首行起算。

## 二、同时修掉的 4 个真实缺陷（不是括号问题，是逻辑问题）

| # | 缺陷 | 后果 | 修法 |
|---|---|---|---|
| 1 | `listOfNotNull(first, current, third)` 压掉空槽 | 队首时"当前页"落到槽 0，被整体左移一整页 | 固定三槽 + 空槽跳过 |
| 2 | 封面放在会平移的页 `Row` 内部 | 同专辑时封面仍跟手移动，且同一张图滑出/滑入读作重影 | 拆出**独立封面轨道**，同专辑位移恒为 0 |
| 3 | `offsetX.value` 在**组合期**读 | 拖动逐帧触发全量重组 | 移进 `graphicsLayer` 延迟读（符合项目"逐帧视觉走 graphicsLayer"纪律） |
| 4 | 每个拖动事件 `scope.launch { snapTo() }` | 协程调度延迟 → 跟手不精确 | 直接写 `mutableFloatStateOf`（单一真源），收尾动画用 `animate()` 写同一状态 |

附带清理：
- `DisposableEffect(track.id) { _ -> ... }` 双参 lambda（`DisposableEffect` 只收 1 参）已删；
- 外层 `Row` 上"松手才判定、内容不跟手"的**过时手势**已删——它与新手势共存会双份消费同一个水平手势；
- 未用导入 `Spring` / `snapshotFlow` 已删。

## 三、需求逐条对照

| 需求原话 | 落点 |
|---|---|
| 手势区域 = 底部迷你播放器整块歌曲信息区域 | 手势挂在外层 `Row`（信息区 + 按钮组） |
| 向左拖 → 当前曲跟手左移，下一首从右侧进入 | `translationX = pageBaseX + offsetX`，右槽 = `nextTrackId() ?: wrappedNextTrackId()` |
| 向右拖 → 当前曲右移，上一首从左侧进入 | 左槽 = `prevTrackId()`（队首为 null 时该方向不产生位移） |
| 只能水平移动，Y 轴固定，绝不上下滑入/回弹/跳动 | 全程只有 `translationX` 一个分量，结构上不存在 Y 位移 |
| 拖多少移动多少，两曲同时存在 | 拖动直接写 float state，无协程中转；三页常驻、页宽 = 信息区宽 |
| 松手过阈值 → 沿水平完成剩余移动并停在中央 | `animate(offsetX → ∓pageW, tween(170))`，目标页正好落在中央 |
| 未达阈值 → 沿水平平滑回原位 | `animate(offsetX → 0, spring(0.78, 480), initialVelocity = v)` |
| 完成动画只能有 X 轴位移 | 同上，只有 `translationX` |
| 切歌状态提交在横向过渡完成后 | `slidePage()` 跑完才 `commitThenReset { vm.player.next() }` |
| 同专辑封面必须完全稳定（不位移/缩放/淡入淡出） | 封面轨道独立位移：同专辑恒 0；左右两槽封面被推到 ∓一页宽（屏外），不叠重影 |
| 跨专辑封面随曲横向替换 | 封面轨道位移 = `offsetX`，新旧封面左右进出 |
| 不改变其他按钮/布局/功能 | 设备/收藏/播放三个按钮原样未动 |

## 四、验证结果

- `:app:compileDebugKotlin` → **BUILD SUCCESSFUL**（此前 33 条错误全清）
- `:app:testDebugUnitTest` → **194 用例全通过**（177 原有 + 17 新增，0 失败 / 0 错误 / 0 跳过）
- `:app:assembleRelease` → **BUILD SUCCESSFUL**，release APK **27.2 MiB**

## 五、⚠️ 需求6「歌词本翻译」：ML Kit 方案实测体积回归 60 MiB，已暂缓

方案原文预估"APK +2~4MB（R8 后）；当前 71MB → 约 73-75MB"。**实测远不止：**

| 项 | 数值 |
|---|---|
| 加依赖前 release APK | 28,567,811 B（27.2 MiB） |
| 加依赖后 release APK | 91,762,269 B（87.5 MiB） |
| **净增** | **+63,194,458 B（+60.3 MiB）** |

原因：**R8 裁不了原生库**。`libtranslate_jni.so` 四个 ABI 全带：
arm64-v8a 15.60 + armeabi-v7a 11.07 + x86_64 16.57 + x86 16.46 = 59.7 MiB。

**好消息**：方案风险点 #3（16KB page size 对齐）**实测通过** —— `PT_LOAD p_align = 16384`。

**处置选项（待拍板）**：

| 选项 | release APK | 代价 |
|---|---|---|
| ① 接受现状 | 87.5 MiB | 无 |
| ② release 只留 arm 两 ABI | ≈54.5 MiB | 模拟器 x86_64 的 instrumented test 会受影响（需给 debug 保留 x86） |
| ③ 仅 arm64-v8a | ≈43.4 MiB | 老 arm32 设备不支持；同上影响模拟器 |
| ④ 放弃离线路径，只做 AI 翻译 | 27.2 MiB | 失去"免流量兜底"，且 AI 需用户自配 key + 联网 |

**当前工作区状态**：依赖已回退（`build.gradle.kts` 里保留完整说明注释），
`MlKitLyricsTranslator.kt` 暂存于 `docs/pending/MlKitLyricsTranslator.kt.txt`（含 16KB 对齐说明、
语言包下载无进度回调的看门狗、`translate()` 原样返回 bug 的重试缓解）。
拍板后：放开依赖那一行 + 把 `.txt` 移回 `app/src/main/java/com/shiyin/music/data/translate/` 即可。

**已落地且与决策无关的部分**（离线 + AI 两条路径的**纯逻辑核心**，全部有单测覆盖）：

| 文件 | 作用 | 说明 |
|---|---|---|
| `data/translate/LyricsTranslationEngine.kt` | **离线**路径：行分组 → 组内拼接 → 译后拆回对齐 | 核心纪律 **"宁可缺行，不可错行"**：拆回对不齐时整组译文只挂第一行、其余行不给译文 |
| `data/translate/LyricsTranslationProtocol.kt` | **AI**路径的线上协议：提示词构造 + "行号\|译文"解析 | 行号协议让"模型合并行"表现为**可检测的缺行号**，而非不可检测的整体错位；纯文本格式避开 JSON 转义翻车 |
| `data/translate/LyricsTranslationPipeline.kt` | **AI**路径编排：整歌一次调用 → 解析 → 缺行带语境补齐 → 汇总 | LLM 以构造函数注入，**不依赖 Android/LlmClient/网络**，故整条链路可用假实现做完整单测 |
| `ui/components/LyricsTranslationState.kt` | 翻译 UI 状态机（档位 / 进度 / 行号→译文 / 错误 / 归属曲目） | **按曲隔离**（`mediaId`）：切歌必须 `reset()`，否则上一首译文会挂到新歌行上（振假名 F7 同坑） |
| `MainViewModel.translateLyricsWithAi()` | AI 翻译入口：前置校验 → 起 Job → 进度回写 → token 入账 | 未配置 LLM / 无歌词时**不发请求**，直接给文案停在初始态 |

四份共 **62 个单测**（17 + 20 + 17 + 8），覆盖：句组边界、字数上限、对齐兜底不变量、
协议宽容度（全角竖线/冒号/有序列表写法）、`3.5 升` 不被误判成行号、
缺行补齐、截断修复、补齐轮失败不推翻已有成果、"毫无进展就跳出别白烧 token"、
异常兜底、**取消必须原样穿透**、进度单调性、按曲隔离、部分成功不误判为失败。

### 顺手修掉的一个真实 bug

`LyricsTranslationPipeline` 原先用 `runCatching` 兜异常，**会把 `CancellationException` 一起吞掉**。
后果：用户取消（再点译按钮 / 切歌）在协程里正是以 `CancellationException` 表现的，被吞后编排
会当成"调用失败"继续跑补齐轮——**用户已经取消了，却还在烧 token 和流量**。
已改为单独 `catch (e: CancellationException) { throw e }`，并补两个单测守住。

**尚未做**（都卡在决策或需要真机）：设置页与语言包管理、离线路径的 ML Kit 接线（等体积决策）。

### ➡️ 前端（界面 + 动画）已落地

用户一度要求把界面与动画交给另一个更擅长前端的模型，并指定按钮字用**繁体「譯」**；
交接方案写好后用户又决定由当前模型直接完成，故**已实现并验证**。

**交接方案 + 落地记录：`docs/handoff-2026-09-16-lyrics-translation-frontend.md`**

该文档自包含，含：**已落地内容**（「譯」三态按钮 / 译文渲染 / 切歌清理，只改 `LyricsScreen.kt` +132/-21）、
设计语言（Organic 配色 / Figtree+Caprasimo / 「虚影终版」无涟漪按钮语言 / CJK 单字 `Trim.Both` 居中）、
VM 契约、6 条硬性约束、真机验证清单、以及明确的范围外事项。
**§2 起的设计语言部分仍是后续任何歌词本 UI 改动的权威参考。**

## 六、待拍板清单

1. ~~封面↔文字间距~~ → 已按横向 11dp → 20dp 实现（常量 `MiniCoverTextGap`），仍待真机确认观感。
2. **ML Kit 体积回归**：选 ①②③④ 哪个？
3. ~~翻译按钮字~~ → **已定并已实现：繁体「譯」**。
4. 「譯」按钮已译时再点：**关闭（当前实现）** 还是重译？
5. "默认 AI 校验"具体指哪个候选：(a) 离线译文出来后自动让 LLM 校对润色（每首歌多一次调用+token，不建议默认开）；
   (b) 其实是指振假名那个"自动校验"在翻译场景的类似物（已有，不用动）？
6. 是否按原定 5 个 commit 开工？（前端已完成，commit 划分需重排）

## 七、本次未触碰的内容

主干工作区存在**另一窗口的并行开发未提交改动**，本次未做任何修改：

- `app/src/main/AndroidManifest.xml`（+5，SpikeScrubActivity 注册）
- `app/src/main/java/com/shiyin/music/SpikeScrubActivity.kt`（新增，未跟踪）
- `MainViewModel.kt`（+7）/ `PlayerController.kt`（+8）的并行改动原样保留
- `docs/pending-requirements-2026-09-12.md`、`docs/req1~3-*.md`（未跟踪）
