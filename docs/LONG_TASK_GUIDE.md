# 长程任务指南（Long-Horizon Tasks）

> 适用版本：PR #22（`agent-tasks` 模块 + `agent-core` 长程增强）。
>
> 长程任务 = 需要**几十轮迭代、跨越预算边界、甚至跨越进程生命周期**才能完成的任务
> （典型：多源调研并产出报告、大型代码库重构、批量数据处理）。
> 本框架对长程任务的完整答案由四个正交能力拼成：

| 能力 | 模块 | 解决的失败模式 |
|---|---|---|
| **任务计划（TaskPlan）** | `agent-tasks` | 多轮之后模型"迷航"：忘记目标、跳步、重复已完成工作 |
| **预算续跑（continueExecution）** | `agent-core` | `maxIterations` 耗尽即任务报废，前面几十轮全部白费 |
| **循环护栏（LoopGuard）** | `agent-core` | 模型对同一工具反复发起**完全相同**的调用，烧 token 到死 |
| **崩溃恢复（文件持久化）** | `agent-core` + `agent-tasks` | 进程重启后对话与计划全丢，无法从断点继续 |

---

## 30 秒上手：LongTaskAgent 门面

一个构造器调用电齐全部长程能力：

```kotlin
import com.androidguru.agent.tasks.engine.LongTaskAgent

val agent = LongTaskAgent(
    llmClient = OpenAiCompatibleClient(
        LlmConfig(baseUrl = "...", apiKey = "...", model = "...")
    ),
    sessionId = "quarterly-report",
    workspace = Path.of("data/agent"),   // 计划 + 会话记忆自动落盘 → 崩溃可恢复
    extraTools = listOf(/* 你的业务工具 */),
)

agent.execute("把这份季度数据整理成对比报告").collect { event ->
    when (event) {
        is AgentEvent.BudgetExhausted ->
            agent.continueExecution(50)          // 预算耗尽 → 追加 50 轮从断点续跑
        is AgentEvent.LoopDetected ->
            println("护栏触发: ${event.toolName} × ${event.repeatedCount}")
        is AgentEvent.Complete ->
            println("完成：${event.summary}")
        else -> Unit
    }
}
```

`LongTaskAgent` 装配了什么：

1. `DefaultAgentEngine`（ReAct 主循环 + 全部既有生产韧性）；
2. `task_plan` 工具注册进注册表（模型可维护计划，见下节）；
3. `PlanContextInjector` 接入引擎**动态上下文缝**：计划状态每轮注入系统提示 ——
   即便上下文压缩裁掉了早期对话，模型永远看得到最新计划；
4. 长程纪律系统提示（先计划再执行、完成即更新、失败先调整再重试）；
5. workspace 持久化（`FileTaskPlanStore` + `FileConversationMemory`）。

---

## 一、任务计划（TaskPlan）

### 模型侧：task_plan 工具

对齐 Claude Code 的 TodoWrite 模式，并增强为**双模式**：

**rewrite —— 整表替换**（计划初建 / 结构性大改）：

```json
{
  "action": "rewrite",
  "goal": "产出三个 JSON 库的对比报告",
  "tasks": [
    {"title": "调研 kotlinx.serialization"},
    {"title": "调研 Jackson"},
    {"title": "调研 Gson"},
    {"title": "写对比报告"}
  ]
}
```

**patch —— 按 id 增量操作**（长计划下比全量写省 token，不误伤未提及条目）：

```json
{
  "action": "patch",
  "ops": [
    {"op": "update_status", "id": "t1", "status": "done", "notes": "特性矩阵已收集"},
    {"op": "update_status", "id": "t2", "status": "in_progress"},
    {"op": "insert", "after_id": "t2", "title": "跑基准测试"}
  ]
}
```

操作类型：`add` / `update_status` / `update` / `insert` / `remove` / `goal`。

状态机五态：`pending → in_progress → done / failed / skipped`。

**框架保证**：

- **原子性**：patch 中任一操作非法（如未知 id）→ 整批不生效，当前计划原样保留；
- **单一活跃纪律**（默认开启）：同一时刻只允许一个任务 `in_progress`，
  新的进行中任务自动把上一个回退 `pending`；
- **id 稳定**：框架分配（`t1..tN`），patch 按 id 精确寻址，不做模糊标题匹配；
- 工具返回值即渲染后的最新计划（模型调用后立刻看到确认视图）。

### 每轮自动注入（关键设计）

计划状态由 `PlanContextInjector` 经引擎的 `SystemContextProvider` 缝
**每轮求值**注入系统提示，模型在每一步决策前都看到：

```
# 长程任务计划（task_plan 工具维护，每轮自动刷新）
目标: 产出三个 JSON 库的对比报告
进度: 2/4 完成（50%），进行中 1，失败 0，跳过 0

- [x] t1 调研 kotlinx.serialization  # 特性矩阵已收集
- [x] t2 调研 Jackson
- [~] t3 调研 Gson
- [ ] t4 写对比报告
...
```

### 宿主侧：观察进度

```kotlin
agent.onPlanChanged { plan ->          // 每次变更回调（UI 刷新 / 遥测上报）
    val p = agent.progress()!!
    progressBar.value = p.percent      // done / (total - skipped)
}

val p = agent.progress()               // 任意时刻读取快照
// p.total / p.done / p.failed / p.inProgress / p.pending / p.percent
// p.currentTask / p.nextTask / p.isAllDone
```

---

## 二、预算续跑（continueExecution）

旧行为：`maxIterations` 耗尽 → 一条 `Error`，任务报废。

新行为：耗尽时发出 **`AgentEvent.BudgetExhausted`**（含 `iterationsUsed` /
`maxIterations` / `totalToolCalls` / `durationMs`），宿主决定是否续跑：

```kotlin
agent.continueExecution(extraIterations = 50).collect { ... }
```

续跑语义：

- **记忆完整保留**：不重新注入用户输入，从上次中断处继续；
- **迭代编号衔接**：同一引擎实例续跑，迭代号从断点继续（Complete 事件中的
  `totalIterations` / `totalToolCalls` 跨续跑段累计）；
- **预算累加**：`maxIterations += extraIterations`（配置热替换，下一轮生效）；
- 自动注入一条系统说明「预算已追加，从断点继续，不要重复已完成工作」；
- 之后仍附带一条 recoverable 的 `Error` 事件（兼容只监听 Error 的旧宿主）。

> 典型宿主策略：BudgetExhausted → 询问用户「继续吗？」→ y 则 continueExecution，
> 否则持久化暂停（见下节），下次启动 `--resume`。

---

## 三、循环护栏（LoopGuard）

实测高频失败模式：模型对同一工具发起**完全相同**的调用（同名 + 同参数），
反复烧 token 直到预算耗尽。

护栏机制（`AgentConfig` 控制，默认开启）：

```kotlin
AgentConfig(
    loopDetectionEnabled = true,   // 总开关
    loopDetectionThreshold = 3,    // 同签名出现 N 次触发
    loopDetectionWindow = 12,      // 只统计最近 N 次调用
)
```

触发时：

1. 发出 `AgentEvent.LoopDetected(toolName, repeatedCount, arguments)`（宿主可见）；
2. 在回填给模型的工具结果上附加建议文本：「你似乎在重复同一操作，
   请换一种方法、调整参数，或用 ask_user 向用户求助」。

**只统计、不阻断** —— 判定是否真的死循环交给模型/宿主，护栏保证信息可见。
（`ask_user` 挂起等待用户输入，不构成循环风险，不计入。）

---

## 四、崩溃恢复（文件持久化）

两份持久化拼起「跨进程恢复」：

| 状态 | 实现 | 文件 | 写入策略 |
|---|---|---|---|
| 会话记忆 | `FileConversationMemory`（agent-core） | `{workspace}/session-{id}.jsonl` | JSONL 逐条追加 + flush（进程崩溃安全）；压缩回写走临时文件 + 原子 move |
| 任务计划 | `FileTaskPlanStore`（agent-tasks） | `{workspace}/plans/{id}.plan.json` | 每次变更临时文件 + 原子 move |

崩溃恢复三步（`LongTaskAgent` + workspace 已自动接线）：

```kotlin
// ① 之前：agent.execute(...).collect { ... } 进程中途被杀
// ② 重启后：相同 sessionId + workspace 重建（构造即恢复）
val agent = LongTaskAgent(llm, sessionId = "quarterly-report", workspace = workspace)
agent.plan()                  // 计划恢复（含每项状态与 notes）
agent.engineMemoryMessages()  // > 0 表示对话历史恢复成功
// ③ 从断点续跑
agent.continueExecution(50).collect { ... }
```

细节保证：

- **悬空 tool-call 修补**：崩溃可能留下「Assistant 发起工具调用但没有结果」的残缺历史
  （OpenAI 端点会 400 报废整个会话）。引擎构建消息时自动合成
  「结果未知，先验证」的修补消息 —— 恢复天然可用；
- **损坏行容错**：JSONL 半写行（崩溃瞬间）加载时跳过并计入
  `skippedCorruptLines`，绝不因单行损坏报废整个会话；
- **文件名消毒**：会话 id 只保留 `\w-` 字符，防目录穿越。

> fsync 级掉电保护不在此层承诺（flush 覆盖进程崩溃；掉电安全由宿主按需追加）。

---

## 五、不用门面，手动接线

`LongTaskAgent` 只是装配便利；各件可独立使用：

```kotlin
// ① 计划管理器 + 工具 + 注入器
val manager = TaskPlanManager(FileTaskPlanStore(Path.of("plans")), sessionId = "s1")
val registry = DefaultToolRegistry().also { it.register(TaskPlanTool(manager)) }

// ② 引擎：接入动态上下文缝（计划每轮注入）
val engine = DefaultAgentEngine(
    llmClient = llm,
    toolRegistry = registry,
    toolExecutor = DefaultToolExecutor(registry),
    memory = FileConversationMemory(Path.of("s1.jsonl")),  // 崩溃恢复
    systemContextProvider = PlanContextInjector(manager),
)

// ③ 预算耗尽 → 续跑（任意宿主逻辑）
engine.execute("...").collect { event ->
    if (event is AgentEvent.BudgetExhausted) engine.continueExecution(50)
}
```

`SystemContextProvider` 缝本身与计划无关 —— 宿主可注入任何实时状态
（当前时间、工作目录、外部事件）：

```kotlin
val engine = DefaultAgentEngine(
    ...,
    systemContextProvider = SystemContextProvider {
        "当前时间: ${java.time.LocalDateTime.now()}"
    },
)
```

提供者异常被引擎隔离（记为空上下文），单点故障不打断任务。

### 组合多个注入器（如：计划 + 长期记忆）

`extraContextProviders` 把宿主注入器（如 agent-memory 的记忆召回）与计划注入组合 ——
内部经 `CompositeContextProvider`（agent-core）拼接，单点异常隔离、顺序稳定：

```kotlin
val memory = MemorySystem.create(workspace, llmClient)
val conversation = FileConversationMemory(workspace.resolve("sessions/report.jsonl"))

val agent = LongTaskAgent(
    llmClient = llm,
    sessionId = "report-2024",
    memory = conversation,
    extraTools = memory.tools,                                                // 记忆工具与 task_plan 共存
    extraContextProviders = listOf(memory.contextInjectorFor(conversation)),  // 每轮记忆召回
    compressor = memory.capturingCompressor(SlidingWindowCompressor()),       // 压缩时捕获被裁对话
)
```

完整记忆系统见 **[MEMORY_GUIDE.md](MEMORY_GUIDE.md)**。

---

## 六、配置速查

| 配置 | 默认 | 说明 |
|---|---|---|
| `maxIterations` | 25 | 单段迭代预算；耗尽发 `BudgetExhausted`，可 `continueExecution` 追加 |
| `loopDetectionEnabled` | true | 循环护栏总开关 |
| `loopDetectionThreshold` | 3 | 同签名窗口内出现次数阈值 |
| `loopDetectionWindow` | 12 | 签名统计滑动窗口 |
| `enforceSingleActive`（LongTaskAgent） | true | 单一 in_progress 纪律 |

## 七、事件速查（新增）

| 事件 | 时机 | 关键字段 |
|---|---|---|
| `BudgetExhausted` | 迭代预算耗尽（可续跑暂停点） | `iterationsUsed` / `maxIterations` / `totalToolCalls` / `durationMs` |
| `LoopDetected` | 循环护栏触发 | `toolName` / `repeatedCount` / `arguments` |

## 八、运行示例

```bash
export AGENT_BASE_URL="https://api.deepseek.com/v1"
export AGENT_API_KEY="sk-..."
export AGENT_MODEL="deepseek-chat"

./gradlew :examples:long-task-agent:run            # 正常长程任务（预算耗尽时可交互续跑）
./gradlew :examples:long-task-agent:run --args=--resume   # 崩溃 / 暂停后从断点续跑
```

示例演示：进度条实时刷新（`onPlanChanged`）、预算续跑确认、`--resume` 恢复、护栏告警打印。

## 九、设计取舍记录

- **为什么计划状态注入系统提示而不是工具结果回读？**
  上下文压缩可能裁掉早期对话（含工具结果）；系统上下文每轮重建，计划永不丢失。
  压缩器（`LlmSummarizingCompressor`）的摘要提示词也保留「未完成任务」要点，双保险。
- **为什么 rewrite + patch 双模式而不是只留一个？**
  rewrite 适合初建（模型一次产出完整结构，出错率低）；patch 适合长计划增量维护
  （省 token、不误伤）。实测单模式各有明显短板。
- **为什么循环护栏只统计不阻断？**
  「相同调用」不必然是错误（如等待外部状态收敛的轮询）。阻断交给审批闸门 /
  钩子（PreToolUse Block），护栏负责让模型**意识到**自己在转圈。
- **为什么 continueExecution 而不是自动无限续跑？**
  预算是宿主的钱。追不追加、追加多少，必须是宿主（或用户）的显式决策。
