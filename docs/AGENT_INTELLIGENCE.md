# Agent 智能深化（PR #26）

> 反思纠错 / 语义循环与跑偏护栏 / 子目标拆解 / 移动端任务评估 / 本地模型深度适配。
>
> 针对「Agent 智能部分偏基础」的系统性补强：提示词与护栏管**单轮决策质量**，
> 本 PR 补齐**跨轮次的过程智能** —— 失败了会反思、跑偏了会被拉回、
> 交替死循环会被识破、复杂目标会主动拆解、效果可被**量化评估与调优**、
> 弱本地模型也能可靠调用工具。

## 缺口与解法总览

| 缺口（事实核查结论） | 解法 | 代码 |
|---|---|---|
| 反思纠错：失败只会盲重试，无根因分析 | **ReflectionEngine**（LLM 根因分析 + 启发式回退，注入修正策略） | agent-core |
| 死循环：只检测「完全相同调用」，A→B→A→B 漏检 | **语义签名 + 交替周期检测** | agent-core |
| 跑偏：多步任务中途发散无感知 | **TaskDriftDetector**（停滞检测 + LLM 目标对齐抽查） | agent-core |
| 子目标拆解靠提示词「劝」，弱模型直接跳过 | **TaskDecomposer**（框架主动调用，阶段→步骤+验收标准） | agent-tasks |
| 父任务状态不自动收拢 | **aggregateParents**（子任务全完成→父 DONE） | agent-tasks |
| 移动端任务评估：完全没有 | **agent-eval**（轨迹校验 + 移动端套件 + harness + 调优报告） | agent-eval |
| 本地模型：无 function calling 即退化为聊天机器人 | **ToolCallEmulatingClient**（文本协议模拟）+ **LocalModelProfiles**（档位库） | agent-llm / agent-core |
| 默认提示词 5 行硬编码 | 结构化默认提示（工作纪律/错误处理/多步任务） | agent-core |

## 一、反思纠错（ReflectionEngine）

**位置**：`agent-core/.../engine/ReflectionEngine.kt`

把「失败 → 原样重试」升级为「失败 → 根因分析 → 策略修正」：

```
工具连续失败 ≥ reflectionFailureThreshold（默认 3）
        │
        ▼
反思（独立轻量 LLM 调用：无工具、≤300 token、temp 0.2）
  输入：任务目标 + 最近 5 次失败调用（工具/参数/错误码/错误文本）
  输出：一句话根因 + 一句话修正策略
        │
        ├─ LLM 可用 → lesson（≤120 字）
        └─ LLM 异常 → 确定性启发式分诊（按 ToolErrorCode 九类诊断）
        │
        ▼
memory.appendSystem("[reflection] ...")   ← 模型下一轮可见
emit(AgentEvent.ReflectionTriggered(...))  ← 宿主 UI 可见
```

要点：

- **反思冷却 3 轮**：同一故障群只反思一次，不反复打扰；
- **反思永不打断主任务**：自身异常全部内部吸收（回退启发式）；
- **循环护栏也能触发反思**：交替循环（A→B→A→B）检出时，把循环形态作为信号交给反思分析。

## 二、语义循环 + 交替周期检测（ToolCallLoopDetector 升级）

原实现只统计「工具名+参数完全相同」的调用次数，三处升级：

1. **语义等价签名**：参数 JSON 感知归一化（递归键排序 + 紧凑化）——
   `{"b":2,"a":1}` 与 `{"a": 1, "b": 2}` 视为同一调用；非 JSON 参数（shell 命令）空白折叠；
2. **交替循环**：签名序列周期检测（周期 2..6、完整重复 ≥2 遍）——
   `A→B→A→B`、`A→B→C→A→B→C` 等原实现**完全漏检**的形态；
3. **分级建议**：单签名重复与交替循环注入不同的修正指引（后者提示检查前置条件/换工具/求助）。

```kotlin
// 引擎内自动接线，无需宿主操作；事件流新增：
is AgentEvent.LoopDetected -> // toolName = "cycle" 表示交替循环形态
```

## 三、跑偏检测（TaskDriftDetector）

**位置**：`agent-core/.../engine/TaskDriftDetector.kt`

两层机制，全部**建议性注入**（不阻断，与循环护栏同一纪律）：

| 机制 | 成本 | 触发 | 动作 |
|---|---|---|---|
| 进展停滞 | 零（启发式） | 连续 `driftStagnationIterations` 轮（默认 4）工具在调但**零成功** | 注入「停下来重新评估」拉回建议 |
| 目标对齐抽查 | 一次轻量 LLM 调用 | 每 `driftCheckInterval` 轮（默认 8） | 原始目标 + 最近动作 → `{"aligned": false, "reason": "..."}` → 未对齐注入拉回提示 |

判定输出走 `AgentEvent.DriftSuspected`；抽查解析失败/LLM 不可用**静默跳过**。

## 四、子目标拆解（TaskDecomposer）

**位置**：`agent-tasks/.../plan/TaskDecomposer.kt`

此前拆解依赖主对话提示词「劝」模型调 `task_plan` —— 弱模型经常跳过直接开干。
拆解器把「拆解」变成框架主动的独立调用：

```kotlin
val decomposer = TaskDecomposer(llmClient, planManager)
when (val outcome = decomposer.decompose("把下载目录的照片按月份归档", availableToolNames)) {
    is TaskDecomposer.DecomposeOutcome.Success -> // 两级计划已写入
    is TaskDecomposer.DecomposeOutcome.Fallback -> // LLM 输出不可解析 → 单阶段兜底
}
```

- 阶段 → 父任务，步骤 → 子任务；**每个步骤带验收标准**（折叠进 notes，模型推进时对照）；
- 拆解提示注入**可用工具清单**——不拆出工具做不到的步骤；
- 解析容错：fenced / 裸 JSON / 前后废话均能提取；失败回退单阶段计划，**拆解永不报废任务**。

配套修复（`TaskPlanManager`）：**父任务状态自动收拢**（`autoAggregateParent`，默认开）——
子任务全部完成 → 父任务自动 DONE；全部终态且有失败 → FAILED；开始推进 → IN_PROGRESS。
只向前推进，永不回退人工置位的终态。原有行为可 `autoAggregateParent = false` 关闭。

## 五、移动端任务评估（agent-eval）

**新模块**：纯 JVM 可跑的评估 harness —— 不需要真机、不需要 API Key（可脚本化），
专门用于**移动端任务的调优闭环**：换提示词/换模型/调护栏参数 → 跑同一套件 → 报告对比。

```
EvalHarness.run()
  ├─ 每任务新引擎执行（engineFactory 组装，异常隔离）
  ├─ AgentTraceRecorder 录轨迹（工具调用/迭代/循环/反思/跑偏信号）
  ├─ TraceValidator 判分（必做调用+参数级匹配 / 禁止调用 / 轮数 / 循环 / 收尾文本）
  └─ EvalReport（成功率 / 按类别 / 按难度 / 失败分类 / 逐任务明细）
```

```kotlin
val harness = EvalHarness(
    engineFactory = { task -> buildAgentFor(task) },   // 你的引擎组装
    tasks = MobileTaskSuites.defaultSuite(),           // 18 个 Android 标准任务
)
println(harness.run().renderText())
```

**移动端套件**（`MobileTaskSuites`）：8 类 18 任务（系统设置/通讯/媒体/应用/文件/日历/信息查询/导航），
判据基于**设备 Agent 标准工具词表**（open_app / set_alarm / send_sms / …，与原项目设备工具集对齐），
含参数级校验（闹钟时间必须含 `7:30`、短信内容必须含关键词）、禁止误操作（不得重复发送/误删）、
条件执行（先查电量再条件开省电）、收尾结论（天气任务必须给出「适不适合」）。

报告内置**失败分类**——调优定位直接可用：`缺少必做调用`（提示词引导不足）/
`出现禁止调用`（安全边界太松）/`触发循环护栏`（护栏参数要收紧）/`收尾文本未达标`（收尾提示缺失）…

## 六、本地模型深度适配

### 工具调用模拟（agent-llm / ToolCallEmulatingClient）

Qwen2.5 / Gemma / Llama 等**无原生 function-calling** 的本地模型此前接上就退化为聊天机器人。
模拟客户端（装饰器）用文本协议补齐：

```kotlin
val base = OpenAiCompatibleClient(LlmConfig(baseUrl = "http://127.0.0.1:11434/v1", model = "qwen2.5:7b"))
val engine = DefaultAgentEngine(
    llmClient = ToolCallEmulatingClient(base),   // ← 一行接入
    ...
)
```

- **协议注入**：工具清单渲染进系统提示，模型用 ` ```tool_call {"name":…, "arguments":…} ``` ` 块发起调用；
- **消息适配**：历史工具调用回渲染为协议块；Tool 结果降级为 User 消息（截断到 6k 字符）；
- **流式安全**：fence 边界回持 —— 协议块内容**永不作为正文流出**，普通代码块正常流式；
- **解析容错**：多块、`arguments` 字符串形式、裸 JSON 整体调用兜底、`maxCallsPerTurn` 防失控刷块。

### 本地模型档位库（agent-core / LocalModelProfiles）

```kotlin
val profile = LocalModelProfiles.OLLAMA_QWEN25_7B   // 或 14B/32B/Llama3.1/Termux/LM Studio
val engine = DefaultAgentEngine(
    llmClient = profile.llmClient(),     // 自动判断是否包模拟层
    config = profile.agentConfig,        // 上下文/压缩/工具输出/反思/跑偏全部按档位调好
    ...
)
// 自定义端点：
LocalModelProfiles.custom("http://127.0.0.1:8080/v1", "my-model", contextTokens = 4096)
```

小模型档位的调参逻辑：上下文 8k/4k → 压缩阈值降到 0.6/0.55、保留轮数 3/2、工具输出截断 4k/2k、
反思 token 上限 200、**关闭 LLM 对齐抽查**（省调用；零成本护栏保留）、超时放宽到 5–10 分钟（手机推理慢）。

## 七、配置速查（全部默认开启，可逐项关闭）

```kotlin
AgentConfig(
    // 反思纠错
    reflectionEnabled = true,             // 关闭 = 零开销
    reflectionFailureThreshold = 3,       // 连续失败触发阈值
    reflectionMaxTokens = 300,
    // 跑偏检测
    driftDetectionEnabled = true,
    driftStagnationIterations = 4,        // 零进展轮数阈值
    driftCheckInterval = 8,               // LLM 对齐抽查间隔（0 = 关闭抽查）
)
```

新事件（宿主按需消费，`agent-chat` 已内置转译为 INFO/WARN 卡片）：

- `AgentEvent.ReflectionTriggered(triggerReason, lesson, byLlm)`
- `AgentEvent.DriftSuspected(stagnationIterations, reason, advisory)`
- `AgentEvent.LoopDetected(toolName = "cycle", …)` —— 交替循环形态

## 测试

| 测试 | 覆盖 |
|---|---|
| `ToolCallLoopDetectorTest` | 语义等价 / 交替周期 / 不误报 / 窗口滑出 |
| `ReflectionEngineTest` | 触发阈值 / LLM 分析 / 启发式回退 / 冷却 |
| `TaskDriftDetectorTest` | 停滞 / 重置 / 告警去重 / 对齐解析容错 |
| `IntelligenceGuardrailsTest` | 引擎端到端：三护栏真实触发 + 关闭开关零开销 |
| `ToolCallEmulatingClientTest` | 协议注入 / 消息适配 / 解析 / 流式回持 |
| `TaskDecomposerTest` | 解析容错 / 兜底 / 超限截断 |
| `TaskPlanParentAggregationTest` | 父任务收拢全语义矩阵 |
| `TraceValidatorTest` / `EvalHarnessTest` | 判分矩阵 / 端到端评估流水线 |
