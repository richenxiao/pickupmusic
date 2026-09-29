# 拾光长期 Memory —— 设计方案（待拍板，未动代码）

> 2026-09-28 拟。按用户定调：Memory 不并入早前那批修复，单独规划、单独评估是否启动。
> 本文只给**方案与决策点**，不含实现。代码一行未改。

---

## 0. 出发点：一个来自实践的硬约束

本轮排查 clarify "从未触发" 时确认了两件关于当前模型（用户实际在用 `sensenova-6.8-flash-lite`）的事实：

1. **不会稳定地做第二次工具调用**（日志实证：`plan: skill=library_query` 之后就拿检索结果当回答，从不接着调播放）；
2. **会把工具返回的文本原样念给用户**（用户实测看到 `track_id` 与方法名）。

这两点直接决定 Memory 的架构取向：

> **只要 Memory 依赖"模型主动调用 save_memory / search_memories"，它就会像 clarify 一样静默失效。**

因此本方案把 Memory 做成 **runtime 驱动**为主、工具为**可选补充**；模型只负责"理解哪句话值得记"这一件它擅长的事，其余（何时存、存什么格式、何时取、取几条）全交给确定性代码。

---

## 1. 边界（Persona vs Memory 分离）

| | 内容 | 存放 |
|---|---|---|
| **Persona** | 拾光是谁（人格、语气、边界） | `AgentEngine.persona`（单一真源，已有） |
| **Memory** | 拾光认识这个用户什么（偏好/经历/音乐关系） | 独立数据层，**运行时注入**，绝不写进 persona |

注入形式与现有 `contextBlock`（当前专辑/歌手/曲目）同构——即"本轮提示词里的一段动态块"，不是人格的一部分。

---

## 2. 数据模型

新增 Room 表 `agent_memory`（与 `artist_image_cache` 同级的独立表，扫描/更新不丢）：

| 字段 | 类型 | 说明 |
|---|---|---|
| `id` | TEXT PK | UUID（稳定标识，供删除/更新引用） |
| `type` | TEXT | `preference` / `personal` / `episodic` / `music` / `relationship` |
| `content` | TEXT | 一句话陈述（人话，可直接展示给用户） |
| `createdAt` / `updatedAt` | INTEGER | 毫秒时间戳 |
| `importance` | INTEGER | 1-3（决定注入优先级与淘汰顺序） |
| `source` | TEXT | `explicit`（用户明说记住）/ `inferred`（运行时推断）/ `manual`（用户手加） |
| `tags` | TEXT | 逗号分隔关键词（检索用，从 content 抽） |
| `supersededBy` | TEXT | 冲突时旧条目指向新条目（保留历史，不硬删） |

**检索**：第一版用 Room 的 **FTS4** 虚表（`content` + `tags`）做关键词检索即可；`embedding` 列**预留但不填**，将来接语义检索时不必改表结构。
**短期上下文**（`session_state`）**不进长期表**——沿用现有的对话历史/上下文块机制。

迁移：`AppDatabase` 版本号 +1，按现有风格写 `CREATE TABLE / CREATE VIRTUAL TABLE`（与既有 migration 块并列）。

---

## 3. MemoryManager（唯一入口，Agent 不碰数据库）

```kotlin
interface MemoryManager {
    suspend fun recall(query: String, limit: Int = 3): List<Memory>      // 检索(带相关度门槛)
    suspend fun remember(candidate: MemoryCandidate): RememberResult      // 写入(去重/冲突/校验)
    suspend fun update(id: String, content: String): Boolean
    suspend fun forget(id: String): Boolean                              // 用户要求忘记
    suspend fun list(type: String? = null, limit: Int = 50): List<Memory> // 给管理界面
}
```

**写入流程**（用户你的四条要求逐条落地）：

```
【回合结束后,异步】用户消息 + 助手回复
   ↓  ① 独立的小 LLM 调用(单用途、严格 JSON 出参) —— 只做"这句有没有长期记忆价值"
   ↓     输出: [{type, content, importance}]  (0-3 条, 允许空)
   ↓  ② 确定性校验(runtime,不信模型)
   ↓     - type 白名单 / content 长度上限 / 空或纯语气词丢弃
   ↓     - 一次性情绪词表("今天有点累/有点烦/刚吃了饭")直接拒
   ↓     - 用户明说"记住/别忘了/以后"→ importance 抬到 3 且绕过拒表(source=explicit)
   ↓  ③ 去重与冲突
   ↓     - 与既有条目高相似(关键词重合 + 长度比) → update 旧条目(或标记 supersededBy),不新增
   ↓  ④ 落库 → 发出真实 runtime event
```

**为什么提取用"独立调用"而不是主对话里调工具**：单用途调用出参可控（严格 JSON）、失败不影响主回复、且不依赖模型记得调工具。代价是每轮多一次轻量调用——用**廉价预筛**挡掉大部分轮次：用户消息太短、纯指令（"下一首""暂停"）、纯闲聊（无实义名词）时**不调用**提取。

---

## 4. 检索与注入（回合开始）

```
用户消息到达
   ↓ ① 关键词/FTS 检索 top-k(k=3, importance 加权)
   ↓ ② 相关度门槛:低于阈值就**不注入**(避免"每次聊日本音乐都强行说我记得你喜欢aiko")
   ↓ ③ 注入为提示词里的独立块:
        [关于这位用户,你记得的事]
        · 他偏好完整听完一张专辑,不喜欢随机推荐
        · 他喜欢 aiko,常在情绪低落时听
   ↓ ④ 发出 recalled event(见下),UI 显示"↳ 回想了 N 条"
```

**明确不做的**：不把全部记忆倒进提示词；不在检索为空时让模型"装作记得"（提示词里写明"没检索到就说没有印象，不要编")。

---

## 5. 工具（可选路径，不是主路径）

仍提供 `search_memories` / `save_memory` / `delete_memory`，但**只作为显式补充**（例如用户说"把我说的这件事记下来"，而确定性快路径没命中时）。工具设计遵守本轮教训：

- **返回值只写人话**（"已记住：你偏好完整听专辑"），**不出现 memory_id、工具名、内部指示**——否则弱模型会原样念给用户；
- 删除/更新需要的 id 由 runtime 在内部关联（工具返回自然语言，实际 id 由 runtime 记账），避免把 id 暴露到消息流。

---

## 6. Activity 事件（必须由真实 runtime 驱动）

| 时机 | event | UI |
|---|---|---|
| 提取出候选并开始落库 | `memory.saving` | 「✦ 正在整理记忆」 |
| 落库成功 | `memory.saved` | 「✓ 已记住：<短摘要>」 |
| 落库失败 | `memory.failed` | 「✗ 没记住（<原因>）」 |
| 检索命中并注入 | `memory.recalled` | 「↳ 回想了 N 条相关记忆」 |

- 复用现有 Agent 的 activity/step 渲染组件（不另造消息系统）；
- **禁止**靠提示词让模型输出"我正在记忆/已经记住"——那会产生假状态（本轮已确立的原则）；
- 敏感内容只显示**短摘要**，不在聊天正文展开。

---

## 7. 用户控制（分期，不一次做全）

- P3 提供「设置 → 拾光记忆」：列表（按类型分组：我的偏好 / 音乐记忆 / 重要的事 / 共同回忆）、编辑、删除、**忘记指定条目**；
- 数据层从 P1 起就支持 view/edit/delete/forget，UI 只是它的入口——不会为了 UI 返工数据层。

---

## 8. 分期与验收

| 期 | 内容 | 可独立验收 |
|---|---|---|
| **P1** | 数据层（表 + FTS + migration）+ MemoryManager（去重/冲突/校验）+ 纯逻辑单测 | 单测：重复偏好只留一条；一次性情绪不入库；冲突时更新而非新增；忘记按 id 生效 |
| **P2** | 接线：回合后提取（独立调用 + 廉价预筛）、回合前检索注入、"记住 X"确定性快路径、activity 事件 | 你列的验收 1-10 条（除第 27 条 UI 管理） |
| **P3** | 「拾光记忆」设置入口（列表/编辑/删除/忘记） | 验收第 27 条 + 删除后下一轮不再被检索到 |

**建议先只做 P1**：它零 UI、零提示词改动、纯本地逻辑 + 单测，风险最低，且把最难的部分（去重/冲突/校验这些"数据质量"问题）先钉死。P2 才涉及提示词与事件，P3 才动设置页。

---

## 9. 需要你拍板的点

1. **是否启动**、以及**是否按"只做 P1"起**；
2. **提取调用的成本**：每轮多一次轻量 LLM 调用（有预筛挡掉多数轮次）可接受吗？还是要更省（例如只在你说"记住"时提取）？
3. **episodic（共同回忆）要不要自动存**——按你的规格"只有长期有意义的故事才存"，但"有没有长期意义"由谁判？我倾向：**自动提取时只收 preference/music/personal，episodic 一律需要你明说**（更保守，避免把琐事记成回忆）；
4. **淘汰策略**：重要性低的条目是否随时间衰减/自动清理（如 90 天未被检索且 importance=1 则归档）？还是永久保留、只靠你去删？
