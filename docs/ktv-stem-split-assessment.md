# 需求一「本地音乐分轨（KTV 模式）」可行性评估

> 结论：**按需求原文不通过** —— 有 5 处硬冲突必须先重新决策，其中 2 处是需求文档里没提到的工作量/体积。
> 本报告全部数字为**实测**（下载 AAR 量 .so、解包 sherpa JNI、查官方导出脚本与 issue），来源链接附在末尾。

---

## 0. 结论速览

| # | 需求原文 | 实测事实 | 性质 |
|---|---|---|---|
| 1 | 「模型 FP16 量化后约 19MB」 | **是每个模型 19MB，需要 2 个（vocals + accompaniment）→ 共 ~38MB** | 🔴 下载量翻倍 |
| 2 | 「参考框架：sherpa-onnx 已支持 Spleeter，优先复用」 | 支持属实，但**它强制依赖完整版 ORT（arm64 24.6MB）→ 单 ABI ~29MB**，超预算 3 倍 | 🔴 该建议应推翻 |
| 3 | 「模型 ONNX」 | 官方导出是**频谱域**模型（输入 `[2, num_splits, 512, 1024]` 幅度谱）→ **必须自己写 STFT / iSTFT / 软掩码** | 🔴 需求未提及的巨大工作量 |
| 4 | 「缓存 AAC/MP3，3~5MB/首」 | AAC 有 **2112 采样（≈48ms）编码延迟**，而 MediaMuxer **不写 iTunSMPB** → Media3 不会补偿 → **人声与伴奏错开 48ms** | 🔴 必须处理，否则三锚点混合直接坏掉 |
| 5 | 「骁龙 8 Gen 3 上 4 分钟歌 5~12 秒」 | 公开实测 RK3588 4 线程 ≈30s；8 Gen 3 合理估计 **10~20s**。且 **FP16 在 ARM CPU 上不加速**（只省体积） | 🟡 预期需下调 |
| 6 | 「APK 增量 ≤10MB」 | 用 ORT Mobile 1.18.0 可守（arm64+armv7 = 7.8MB），但**它只有 4KB 页对齐，16KB 页设备上会加载失败**，且该 artifact 已停更 | 🟡 你已选择"要体积" |

**你已经拍板的两条**：① 用 ORT Mobile 1.18.0 守体积（接受 16KB 设备不可用，我会做启动检测隐藏入口）；② 缓存两条 stem（人声+伴奏）。

---

## 1. Gradle / minSdk / NDK 现状（你要的第 1 项）

| 项 | 值 | 对分轨的影响 |
|---|---|---|
| `compileSdk` | 36 | 无影响 |
| `minSdk` | **31**（Android 12） | ORT 只要 21+，**兼容** ✓ |
| `targetSdk` | 36 | 触发 16KB 页对齐要求（见下） |
| `ndk {}` 配置 | **无** | 用 ORT 预编译 AAR **不需要 NDK** ✓；但若要走"自建裁剪版 .so"就必须加 |
| `abiFilters` | **无** | ⚠️ 默认打包**全部 4 个 ABI** → ORT 全 ABI = 17.6MB，**会直接超预算**，必须显式加 |
| `isMinifyEnabled` / `isShrinkResources` | 均 true | 需要给 ORT 的 JNI 类加 keep 规则（它走反射/JNI 绑定） |
| `packaging { resources { excludes } }` | 已排除若干 META-INF | ORT 若也带 META-INF 冲突，按同一套路加排除 |

---

## 2. ONNX Runtime AAR 兼容性（第 2 项）—— 实测三个版本

| artifact | arm64-v8a .so | 16KB 页对齐 | 状态 |
|---|---|---|---|
| `onnxruntime-mobile:1.18.0` | **4.50 MB** | ❌ 仅 4096 | **已停更**（该 artifact 最后一个版本就是 1.18.0） |
| `onnxruntime-android:1.30.0` | **33.10 MB** | ✅ 16384 | 在维护，但体积 3 倍超预算 |
| sherpa-onnx（`lib-sherpa-onnx`） | JNI 4.41MB + **完整 ORT 24.6MB** | — | ≈29MB/ABI，**不可行** |

`.so` 在 APK 内**不压缩**（AGP 8 起 `extractNativeLibs=false`，为 mmap 直载而页对齐存放），
所以 **APK 增量 ≈ .so 解压后大小**，不是压缩后大小。

**16KB 页的后果**：`p_align=4096` 的 .so 在 16KB 页设备上 `dlopen` 会失败（不是变慢，是直接加载不了）。
你已选择守体积，所以实现时必须：
- 启动时探测 `Os.sysconf(OsConstants._SC_PAGESIZE)`，**16KB 页设备上隐藏麦克风入口**（而不是让它崩）
- 或者退一步：用 `System.loadLibrary` 包 try-catch，加载失败就禁用该功能并提示

---

## 3. APK 体积增量估算（第 3 项）

以 release 基线 **28.55 MB** 计：

| 组成 | 增量 |
|---|---|
| `onnxruntime-mobile` arm64-v8a | +4.50 MB |
| `onnxruntime-mobile` armeabi-v7a | +3.32 MB |
| ORT 的 Java 层（`onnxruntime-*.jar`，R8 后） | +约 0.3 MB |
| 我自己写的 STFT/iSTFT + 软掩码 + 编解码胶水（Kotlin，R8 后） | +约 0.1 MB |
| **合计** | **≈ +8.2 MB → 28.55 → 约 36.8 MB** ✅ 守住 10MB |
| （模型文件） | 不计入（首次启动下载，**但注意是 ~38MB 不是 19MB**） |

> 若哪天要支持 16KB 页，只能走"自建 minimal build 的 .so"或换 TFLite，
> 体积账要重算（见 §7 路线 B/C）。

---

## 4. 模型下载 / 解压 / 缓存的实现架构（第 4 项）

### 4.1 模型获取（你已定：放你自己的 GitHub Releases，按需下载、不强制）

```
首次点麦克风
  → 若模型缺失：弹出说明（约 38MB、仅此一次）→ 用户确认 → 开始下载
  → 下载到 filesDir/models/stem/{vocals.onnx, accompaniment.onnx}.part
  → 校验 SHA-256 → 改名去掉 .part（原子落位，避免半包）
  → 失败可重试；另提供「从本地选 .onnx 文件导入」兜底（离线/下载不通时自救）
```

- 下载通道：GitHub Releases 直链（`github.com/.../releases/download/...`，**是 github.com 域，比 api.github.com 国内容易通**）
- 断点续传：HTTP `Range`（可选，先做简单重试）
- **不放 assets、不进 APK** ✓ 符合你的体积要求
- 可选：把"模型已就绪"状态记进 DataStore，避免每次启动 stat 文件

### 4.2 分离流水线（这是需求文档完全没提、但工作量最大的一块）

实测确认：**官方 Spleeter ONNX 是频谱域模型**，输入是**幅度谱**，输出是 mask 域。
所以整条链必须自己实现：

```
解码音频（MediaExtractor + MediaCodec）
  → 重采样到 44.1kHz / 单声道? 否，Spleeter 要立体声
  → 分帧 STFT（frame=4096, hop=1024, Hann 窗）  ← 要自己写
  → 取幅度谱 sqrt(re²+im²)，reshape 成 [2, num_splits, 512, 1024]
  → 分别跑 vocals.onnx / accompaniment.onnx       ← ORT 推理
  → 软掩码 v²/(v²+a²)
  → 掩码乘回**原始复数 STFT**（保相位）            ← 要自己写
  → iSTFT + 重叠相加                               ← 要自己写
  → 两条 PCM（人声 / 伴奏）
  → 编码缓存（见 §5.2 的延迟处理）
```

- **必须用 FP32 推理**：FP16 在 ARM CPU 上 ORT 会插 Cast 转回 fp32，**不加速**，只省磁盘。
  （模型文件可以用 FP16 版省下载量，但别指望提速 —— 甚至可能因 Cast 更慢）
- 线程数：ORT `intra_op_num_threads` 设 2~4（低端机设 2，避免抢 CPU 导致播放卡顿）
- 全程在 `Dispatchers.Default` + 单独线程池；播放/UI 不受影响

### 4.3 缓存（你已定：两条 stem，LRU 最多 1 首）

```
filesDir/stem_cache/<mediaId>/{vocals.m4a, accompaniment.m4a, meta.json}
```
- 切歌时删掉上一首的整个目录（LRU 保留 1 首）✓ 符合需求
- 磁盘占用：2 × 3~5MB ≈ **6~10MB**（你已确认接受）
- `meta.json` 记：源文件路径+大小、模型版本、采样率、**编码延迟采样数**（§5.2 要用）、STFT 参数

---

## 5. 无缝切换的技术风险点（第 5 项）—— 这里有两个必须解决的坑

### 5.1 双播放器混合的相位/漂移风险（🟡 中）

你已选"双播放器交叉淡化"。两个 ExoPlayer 独立解码同一首歌的不同 stem：

- **两端锚点安全**：纯人声 / 纯伴奏时只有一个 player 出声，无相位问题
- **中间混合有风险**：两个 player 都出声时，若位置差几毫秒，会产生**梳状滤波**（听感"空洞/金属声"）
- 缓解：
  1. 两个 player 用**同一次 `seekTo` 的绝对位置**对齐，并在混合期间每 500ms 抽查 `currentPosition` 差值，超过阈值（如 15ms）就纠正
  2. **三锚点用同一组 stem**（你已选缓存两条 stem）→ 人声/原曲/伴奏三者内部天然一致，不依赖"原曲文件"参与混合 ✓
  3. 混合位置（滑块中间）只在用户主动拖动时出现，默认停在锚点

### 5.2 🔴 AAC 编码延迟 ≈48ms —— 会让「人声」与「伴奏」直接错开

实测/查证：
- AAC 编码器标准 priming = **2112 采样**（@44.1kHz ≈ **48ms**，Apple 官方文档）
- Media3 的 `Format.encoderDelay/encoderPadding` 是从 **iTunSMPB**（MP4 user data / ID3 注释）解析的，由 `GaplessInfoHolder` 读取，再交 `AudioSink` 裁剪
- **Android 的 MediaMuxer 不写 iTunSMPB**（未找到反例）→ **用 MediaCodec + MediaMuxer 缓存 AAC/MP4，这 48ms 不会被补偿**

后果（**这是致命的**，不是小事）：
- 「原曲」锚点 = 人声 + 伴奏同时播；若两者各自都有 48ms 延迟 → **相对**对齐 ✓（同延迟相互抵消）
- 但「从原始文件无缝切换到 stem」时，stem 比原曲**晚 48ms** → 交叉淡化那 200ms 内会有明显双重奏/梳状感
- 若两条 stem 编码参数不一致（比如一个 AAC 一个 MP3）→ **人声与伴奏直接错开 48ms**，三锚点全废

**候选解法**（按推荐排序，均需实测确认）：
1. **两条 stem 用完全相同的编码参数**（相对对齐）＋ 切换前把**原曲 player 也偏移 48ms**（或对 stem 施加 `-48ms` 补偿），使三方对齐
2. 用 `MediaItem.ClippingConfiguration.setStartPositionMs(encoderDelayMs)` 在播放时**裁掉 priming**（纯 API 层，但会引入 seek，需验证是否破坏无缝）
3. 自己给 MP4 写 iTunSMPB（让 Media3 自动裁）—— 最干净但要自己封装容器
4. 改用**无 priming 的格式**：WAV/FLAC 无损无延迟，但 4 分钟立体声 **WAV ≈ 42MB/首、FLAC ≈ 25MB/首** ❌ 与"3~5MB/首"冲突
   （Opus 在 Ogg 里天然带 pre-skip，但 **Android MediaCodec 没有 Opus 编码器**，要引 libopus + NDK）

> **评估结论**：需求写的"缓存 AAC/MP3 3~5MB/首"在体积上成立，但**必须额外处理这 48ms**，
> 否则「人声-原曲-伴奏」三锚点混合会直接坏掉。这是需求文档完全没覆盖的点。

### 5.3 其他风险

- **切换时机**：需求说"分离完成后不要立刻切换，先加载到第二音轨、位置对齐后再交叉淡化 200ms" —— 双 player 方案下"第二音轨"就是第二个 ExoPlayer，可行 ✓
- **进度条/时长**：stem 文件时长应与原曲一致；若因帧对齐多出少量样本（sherpa 实现里尾部会多几帧），需要把 duration 对齐到原曲，否则进度条会飘
- **切歌竞态**：分离中切歌要立刻取消（你已定）→ 需取消 ORT 推理（ORT 不支持中断，只能丢弃结果 + 清理临时文件；推理线程要设低优先级）
- **内存**：4 分钟 44.1kHz 立体声 PCM = 42MB/条；STFT 中间量更大。低端机（4GB RAM）必须**分块流式处理**，不能一次性把整首读进内存

---

## 6. 麦克风按钮插入 + 振假名右移的布局方案（第 6 项）

歌词页底部那一行是（`LyricsScreen.kt` 约 549 行）：

```
Row(horizontalArrangement = Center) {
    if (hasKana) { 「あ」按钮 }      ← 振假名开关，38dp 圆形，激活时 artFg 实心盘
    Spacer(weight) / 播放键(居中)
    Spacer(weight)                    ← 右侧等宽占位，保证播放键居中
}
```

**改法**（最小改动、不破坏"播放键始终居中"这个既有约束）：

```
Row(horizontalArrangement = Center) {
    // 新增：麦克风按钮（放最左 = 振假名隔壁）
    MicButton(...)                    // 同样 38dp 圆形，与「あ」同一套视觉语言
    if (hasKana) { 「あ」按钮 }        // 右移一格
    Spacer(weight)
    播放键（居中）
    Spacer(weight)
}
```

- 两个按钮都用 38dp 圆形 + 同一套"未激活透明 / 激活实心盘"状态样式 → 视觉成对
- 麦克风的三种状态：**按下反馈**（缩放 0.92）→ **加载中**（环形弧旋转）→ **激活**（实心盘 + 常亮）
- 右移后仍要保持播放键居中：左侧多了一个按钮，所以右侧占位要**同步加宽**（否则播放键会偏）—— 这是最容易漏的一点
- 非日文歌词（`hasKana == false`）时「あ」不显示，此时麦克风就是最左第一个按钮，播放键仍居中 ✓

---

## 7. 建议路线（三选一）

| 路线 | 体积 | 16KB | 工作量 | 说明 |
|---|---|---|---|---|
| **A. ORT Mobile 1.18.0 + 自写 DSP**（你已选） | +8.2MB ✅ | ❌ | **大**：STFT/iSTFT/软掩码/重采样全自己写 | 唯一能守住 10MB 的路线 |
| B. ORT Android 1.30.0 | +33MB ❌ | ✅ | 中（DSP 仍要自己写） | 体积 3 倍超预算 |
| C. 自建 minimal ORT .so | +3~5MB ✅ | ✅ | **很大**：需 NDK + 自建流水线，本机无法验证 | 唯一同时满足两者的路线，但要你本地跑构建 |

**无论哪条路线，§4.2 的 DSP 都必须自己写**（模型是频谱域的），
这一点需求文档没有预估到 —— 它是这个功能里最大的一块工作量。

---

## 8. 待你决策（醒后确认即可）

| # | 问题 | 我的建议 |
|---|---|---|
| K1 | 模型下载量其实是 **~38MB**（两个文件），不是 19MB。接受吗？ | 接受。但要**明确告知用户下载大小**，且下载通道要能断点重试 |
| K2 | **DSP 必须自己写**（STFT/iSTFT/软掩码/重采样），工作量远超"接个 SDK"。是否仍要做？ | 仍可做，但要按"独立模块 + 单测"来做（STFT/iSTFT 往返测试可以纯 JVM 验证，不需要设备） |
| K3 | AAC 的 48ms 延迟怎么处理？ | 先用**方案 1**（两条 stem 同参数编码 + 切换时统一补偿），并写一个"往返编解码后对齐误差 < 5ms"的单测钉死 |
| K4 | 低端机（4GB RAM）是否也开放？ | 开放但**自动降级**（22.05kHz + 2 线程），并在按钮上提前显示"这台机器需要久一点" |
| K5 | 16KB 页设备怎么呈现？ | 启动探测，不支持则**隐藏入口**（不崩），并在设置里留一行说明原因 |

---

## 附：证据来源

- Spleeter 源分离模型（含导出脚本，输入 `[2, num_splits, 512, 1024]`）：
  https://github.com/k2-fsa/sherpa-onnx/releases/tag/source-separation-models ·
  https://huggingface.co/csukuangfj/sherpa-onnx-spleeter-2stems ·
  https://github.com/k2-fsa/sherpa-onnx/blob/master/scripts/spleeter/export_onnx.py
- sherpa-onnx Spleeter C++ 实现（确认频谱域 + 软掩码 + 保相位）：
  https://github.com/k2-fsa/sherpa-onnx/blob/master/sherpa-onnx/csrc/offline-source-separation-spleeter-impl.h
- sherpa-onnx 源分离文档（明确只支持 2-stem）：
  https://k2-fsa.github.io/sherpa/onnx/source-separation/models.html
- FP16 在 ARM CPU 不加速：https://github.com/microsoft/onnxruntime/issues/22242
- AAC 编码延迟 2112 采样：Apple QuickTime 文件格式文档（implicit encoder delay）
- Media3 从 iTunSMPB 读 encoderDelay：
  https://github.com/androidx/media/blob/release/libraries/extractor/src/main/java/androidx/media3/extractor/GaplessInfoHolder.java
- ONNX Runtime AAR（体积与对齐为本次实测）：Maven Central
  `com.microsoft.onnxruntime:onnxruntime-mobile:1.18.0` / `onnxruntime-android:1.30.0`
