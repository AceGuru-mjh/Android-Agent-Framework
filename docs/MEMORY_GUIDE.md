# 长期记忆系统（agent-memory）

> 跨会话记忆 —— 让 Agent 记住用户是谁、偏好什么、上一次任务做成了什么。
> 三层记忆架构：工作记忆（引擎已有）+ 情景记忆 + 语义记忆（本模块）。

## 为什么需要

长程任务的两个时间尺度，此前只覆盖了第一个：

| 尺度 | 问题 | 已有能力（PR #22） |
|---|---|---|
| **任务内**（几小时） | 中断后从断点继续 | 计划持久化 + 会话持久化 + 预算续跑 |
| **跨任务/跨会话**（几天～永久） | 换个会话就失忆：用户上次说过的偏好、踩过的坑全部归零 | **缺失 → 本模块补齐** |

上下文压缩让问题更尖锐：长任务跑几小时后早期对话被裁掉，用户在开头说过的事从此「失忆」。
记忆系统的解法是**在信息消失前把它抢救进独立存储**，之后按相关性召回 —— 压缩丢的历史变成可召回的记忆。

## 架构

```
┌────────────────────────────────────────────────────────────┐
│                      引擎（agent-core）                     │
│                                                            │
│   每轮 LLM 调用前                    每轮压缩时            │
│        │                              │                    │
│   SystemContextProvider          ContextCompressor         │
│        ▼                              ▼ (装饰)              │
│  MemoryContextInjector ──召回── MemoryCapturingCompressor   │
│        │                       │（被裁对话 → 情景记忆）      │
│        │                       │                           │
│        ▼                       ▼                           │
│  ┌─────────────────────────────────────┐                   │
│  │        MemoryStore（memory.jsonl）   │ ◄── memory_save   │
│  │  FACT / PREFERENCE / LESSON /       │     （模型主动写） │
│  │  TASK_OUTCOME / SESSION_SUMMARY     │ ◄── onSessionComplete
│  └─────────────────────────────────────┘     （会话结束抽取）│
│        ▲                                    memory_forget  │
│        └── memory_search（模型主动读/改）                   │
└────────────────────────────────────────────────────────────┘
```

三条写入通道（全部独立可用）：

1. **模型主动**：`memory_save` 工具 —— 模型判断某信息值得跨会话记住时显式落库；
2. **会话沉淀**：`MemorySystem.onSessionComplete()` / SessionEnd 钩子 —— 会话结束时 LLM 抽取
   （无 LLM 时启发式回退：任务目标 + 结论合成一条 TASK_OUTCOME）；
3. **压缩捕获**：`MemoryCapturingCompressor` —— 压缩发生时把被裁对话留为 SESSION_SUMMARY。

两条读出通道：

1. **自动召回注入**：`MemoryContextInjector`（SystemContextProvider 缝，每轮求值）——
   以最近用户消息为信号，按相关性排序注入 `# 长期记忆` 段落；
2. **模型检索**：`memory_search` 工具 —— 自动召回没覆盖到时，模型主动查询。

## 快速上手

```kotlin
val memory = MemorySystem.create(
    workspace = Path.of("data/agent"),   // memory.jsonl 落在这里（跨进程存续）
    client = llm,                        // 可选：接入后启用 LLM 抽取/摘要
)
val conversation = FileConversationMemory(Path.of("data/agent/conversation.jsonl"))

val registry = DefaultToolRegistry().also { r ->
    memory.tools.forEach { r.register(it) }   // memory_save / search / forget
}
val engine = DefaultAgentEngine(
    llmClient = llm,
    toolRegistry = registry,
    toolExecutor = DefaultToolExecutor(registry),
    memory = conversation,
    compressor = memory.capturingCompressor(SlidingWindowCompressor()),  // 压缩时捕获
    systemContextProvider = memory.contextInjectorFor(conversation),     // 每轮召回注入
)

engine.execute("帮我配置部署环境").collect { /* ... */ }

memory.onSessionComplete(conversation)   // 会话沉淀（或用 installSessionEndHook 自动触发）
```

**下一个会话**（哪怕换了进程）：

```kotlin
// 新建 engine + 新建 conversation —— 但记忆自动召回
engine.execute("上次配置的部署环境是什么方案？").collect { /* 模型直接引用记忆作答 */ }
```

### 确定性验证（无需 API Key）

```bash
./gradlew :examples:memory-agent:run --args="--demo"
```

演示模式跑两个脚本化会话：会话 1 学偏好（memory_save + 自动沉淀），会话 2 全新引擎凭记忆作答 —— 完整闭环。

## 与 LongTaskAgent 组合

计划注入与记忆注入经 `CompositeContextProvider` 组合（agent-core 提供，单点异常隔离）：

```kotlin
val memory = MemorySystem.create(workspace, llmClient)
val conversation = FileConversationMemory(workspace.resolve("sessions/report.jsonl"))

val agent = LongTaskAgent(
    llmClient = llm,
    sessionId = "report-2024",
    memory = conversation,
    extraTools = memory.tools,                                  // 记忆工具与 task_plan 共存
    extraContextProviders = listOf(memory.contextInjectorFor(conversation)),  // 与计划注入组合
    compressor = memory.capturingCompressor(SlidingWindowCompressor()),
)
```

## 召回评分模型

四因子加权（可解释、零向量依赖、可离线测试）：

```
score = 0.45 × importance/100      # 静态权重（写入时声明，0..100）
      + 0.30 × 2^(-ageDays/14)     # 新近度半衰期（默认 14 天）
      + 0.10 × ln(1+n)/ln(17)      # 使用频率（16 次访问 ≈ 饱和）
      + 0.15 × keywordCoverage     # 关键词覆盖（|query∩record| / |query|）
```

- 分词双语：CJK 切 bigram（「用户名」→ 用户/户名/名是…），拉丁词小写化；
- **访问回写冷却**（默认 1 小时）：注入器每轮求值，若命中即 +1 会把频率/新近度灌成
  「越召回越热」的正反馈 —— 冷却间隔内的命中不计统计；
- **token 预算**（默认 500）：召回结果按内容估算累加，超预算即止 —— 记忆注入永远不挤压工作记忆；
- 语义向量检索留给宿主按需接入（`MemoryStore` 接口可换实现，评分在 `MemoryRecall` 之上可替换）。

## 文件格式

`memory.jsonl`（JSONL，一行一条，与 FileConversationMemory 同款纪律）：

```json
{"id":"m1","kind":"PREFERENCE","content":"用户叫小明，偏好 Kotlin 与深色主题","importance":85,"tags":["user"],"createdAtMs":1791343791980,"lastAccessedAtMs":1791343791998,"accessCount":1}
```

- **追加即持久**：append 一行立即 flush —— 进程崩溃后已写入记忆不丢；
- **原子替换**：update / remove / 容量淘汰走「临时文件 + 原子 move」；
- **容错加载**：损坏行跳过并计数，绝不报废整个记忆库；
- **容量上限**：默认 1000 条，满时淘汰（重要度最低 → 最久未访问）；
- **id 续编**：加载后从既有最大 id 续编，不与历史冲突。

## 记忆类别

| 类别 | 语义 | 典型来源 |
|---|---|---|
| `FACT` | 项目/环境的稳定事实 | 模型保存 / 会话抽取 |
| `PREFERENCE` | 用户偏好与约定 | 模型保存（用户明确表达，importance ≥ 80） |
| `LESSON` | 踩坑教训（下次别再犯） | 会话抽取 |
| `TASK_OUTCOME` | 任务结论（做成了什么/怎么做成的） | 会话沉淀（启发式回退的默认产物） |
| `SESSION_SUMMARY` | 过去会话/被压缩对话的摘要 | 压缩捕获 / 会话抽取 |

## 组件速查

| 组件 | 职责 | 独立可用 |
|---|---|---|
| `MemoryStore` / `FileMemoryStore` | 存储 + JSONL 持久化 + 容量淘汰 | ✅ |
| `MemoryRecall` | 四因子评分 + 预算选择 + 访问回写 | ✅ |
| `MemoryContextInjector` | 每轮召回注入（SystemContextProvider） | ✅ |
| `MemorySaveTool` / `MemorySearchTool` / `MemoryForgetTool` | 模型读写通道 | ✅ |
| `MemoryExtractor` | 会话 → 记忆（LLM 抽取 + 启发式回退 + 去重合并） | ✅ |
| `MemoryCapturingCompressor` | 压缩装饰器：被裁对话 → 情景记忆 | ✅ |
| `MemorySystem` | 装配门面（create / tools / injector / capturingCompressor） | — |

去重合并：新抽取记忆与既有同类记忆做 token 集合 Jaccard 相似度（≥ 0.75 视为重复）——
不落库、改为刷新既有记录（重要度取高 + 访问升温）。重要信息被反复提及时自然升温，
而不是堆出 N 条近义记忆。

## 配置

`MemoryConfig`（全部带默认值）：

```kotlin
val memory = MemorySystem.create(
    workspace = dir,
    client = llm,
    config = MemoryConfig(
        recallLimit = 6,               // 每轮自动召回上限
        recallMaxTokens = 500,         // 召回 token 预算
        maxRecords = 1000,             // 存储容量
        halfLifeDays = 14.0,           // 新近度半衰期
        accessCooldownMs = 3600_000,   // 访问回写冷却
        extractionEnabled = true,      // 会话结束自动抽取
        maxMemoriesPerExtraction = 8,  // 单次抽取上限
    ),
)
```

## 安全与边界

- **自动召回是参考不是圣旨**：提示词明确「与当前对话冲突时以用户最新表述为准」；
- **memory_forget 是一等能力**：过时/错误记忆必须可清除，否则错误记忆被反复召回强化；
- **单点故障隔离**：注入器异常被引擎隔离（任务不打断）；压缩捕获异常不外溢（压缩必须成功）；
  会话抽取失败静默降级（启发式路径保证「没有 LLM 也永远有记忆」）；
- **存储位置**：memory.jsonl 含用户偏好等隐私数据，落盘位置由宿主掌控（workspace 参数）；
  建议与 SecretVault 同级的安全策略对待。

## 与被剔除的 cs-mem 的关系

原项目的「认知记忆 cs-mem」是 app 业务层实现，被刻意排除出框架。本模块是**框架级的重新设计**：
三层记忆架构对标 MemGPT / Letta 的分层思路，但实现形态适配本框架的既有缝
（SystemContextProvider 注入、ContextCompressor 压缩、AgentTool 工具、HookEvent 生命周期），
零向量库依赖、零新增中间件、全部组件可独立取用。

## 设计决策记录

1. **为什么评分用四因子而不是向量检索**：零依赖（纯 JVM、无 embedding 服务）、可解释可调参、
   离线可测试；语义检索是加分项不是门槛 —— `MemoryStore` 是接口，宿主可换 SQLite/向量实现。
2. **为什么记忆工具交给模型三件套**（save/search/forget）：自动抽取负责「无感」，工具负责「精确」；
   对齐 MemGPT archival memory 模式。没有 forget 的记忆系统会积累错误。
3. **为什么压缩捕获做成装饰器**：不 fork 压缩逻辑（引擎主循环只有一份的纪律同样适用于压缩器）；
   宿主任意压缩器（滑动窗口 / LLM 摘要）都能叠加捕获。
4. **为什么访问回写要冷却**：注入器每轮求值，无冷却会把使用频率灌成正反馈，排序失真。
