# 工作流系统（agent-workflow）

> 可复用、可恢复、可学习的多步骤流程 —— Agent 的「确定性骨架」。
> 参数化声明式工作流 + 超步 DAG 调度 + 健康度生命周期 + 从成功会话蒸馏。

## 为什么需要

前四个能力层各管一段，但都缺一块「**流程资产**」：

| 层 | 已覆盖 | 缺口 |
|---|---|---|
| agent-core（引擎） | 单轮 ReAct 循环、韧性 | 跨调用的流程结构无处安放 |
| agent-tasks（计划） | 任务内自适应计划（模型边跑边改） | 计划是一次性的，成功经验无法复用 |
| agent-memory（记忆） | 跨会话事实/偏好/教训 | 记忆是陈述性知识，「怎么做」的过程性知识没有载体 |
| agent-shell（终端） | 工具与审批 | 无编排 |

工作流补上这块：**把验证过的流程沉淀为参数化资产**，下次同类任务走快速确定性路径
（模型一个 `run_workflow` 调用替代几十轮推理），失败时模型可无缝接手（接管包）。

业界对照（完整考古见 PR 描述）：
- Mobile-Agent-E 的 Shortcut（参数化子程序）+ 成功才沉淀的纪律；
- OS-Copilot 的两阶段注入（索引进 prompt、全文按需拉）；
- mobilerun 的宏录制与失败 handoff 回 ReAct；
- LangGraph 的超步调度 / 检查点 / interrupt；Temporal 的确定性纪律；n8n 的错误三态与崩溃重分类。
两个项目的共同盲区 —— **执行统计与显式淘汰** —— 是本模块的差异化重点。

## 架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                         宿主 / ReAct 引擎                            │
│   SystemContextProvider                ToolRegistry                  │
│        ▲ (每轮注入 ACTIVE 索引)         ▲ (run/list/read/save/disable)│
│        │                               │                             │
│  WorkflowSuggester              WorkflowTools 五件套                  │
│        │                               │ start() ────────┐           │
│        │                               │                 ▼           │
│  ┌─────┴──────────── WorkflowEngine（超步调度器）────────────────┐   │
│  │  就绪集(AND-join+回边豁免) → 并行执行 → 重试/三态处置 → 重入   │   │
│  │  human-in-loop 暂停 → resume(答案)   崩溃恢复(SUCCEEDED不重跑) │   │
│  └──────────────┬─────────────────────────────────────────────────┘   │
│                 │ onRunFinished（终态统计接线）                        │
│                 ▼                                                   │
│  WorkflowLibrary（生命周期）                                          │
│   CANDIDATE ──2次成功──▶ ACTIVE ──连续3次失败──▶ DISABLED(熔断)      │
│        ▲ save(v1/v2…)      ▲ recordRunOutcome      enable(一票复权)   │
│        │                                                        │   │
│  WorkflowExtractor（蒸馏：LLM 泛化 → 宏录制回退）                    │   │
└─────────────────────────────────────────────────────────────────────┘
     持久化：workflows/{id}.workflow.json + runs/{runId}.run.json（原子写）
```

## 五分钟上手

```kotlin
// 1. DSL 声明一个参数化工作流
val dailyReport = workflow("daily_report", "渠道日报", "生成日报并按渠道分发") {
    param("channel", "分发渠道：slack 或 email", required = true)
    param("date", default = "today")

    setVarNode("init", mapOf("date" to "\${params.date}"))
    toolNode("gather", "collect_metrics", args = mapOf("date" to "\${vars.date}"),
             retry = retry(maxAttempts = 3))
    toolNode("render", "render_report",
             args = mapOf("data" to "\${nodes.gather.output}", "date" to "\${vars.date}"))
    humanNode("approve", "发送到 \${params.channel}：\${nodes.render.output}，批准？")
    switchNode("route", "\${params.channel}", cases = mapOf("slack" to "via_slack"),
               defaultBranch = "via_email")
    toolNode("to_slack", "post_slack", args = mapOf("text" to "\${nodes.render.output}"))
    toolNode("to_email", "send_email", onError = NodeOnError.ERROR_BRANCH)

    edge(START, "init"); edge("init" to "gather"); edge("gather" to "render")
    edge("render" to "approve"); edge("approve" to "route")
    edge("route", "to_slack", branch = "via_slack")
    edge("route", "to_email", branch = "via_email")
    edge("to_slack", END); edge("to_email", END)
}

// 2. 组装系统（库 + 引擎 + 工具 + 统计自动接线）
val system = WorkflowSystem(toolRegistry = registry, llmClient = client,
                            workspace = Path.of("data/agent"))
system.library.save(dailyReport)
system.tools.forEach { registry.register(it) }          // run_workflow 等五件套

// 3. 执行：事件流消费 + human-in-loop
val events = system.engine.start(dailyReport, mapOf("channel" to "slack")).toList()
val waiting = events.filterIsInstance<WorkflowEvent.RunWaiting>().single()
system.engine.resume(waiting.runId, humanAnswer = "批准").collect { println(it) }

// 4. 接入长程 Agent（建议注入 + 工具复用）
val agent = LongTaskAgent(
    llmClient = client, extraTools = registry.getAllTools(),
    extraContextProviders = listOf(system.suggester()),
)
```

## 核心概念

### 1. 参数化模板（`${...}` 形参绑定）

步骤存「意图 + 绑定」，不存快照值（AppAgent 教训：坐标硬编码即过期）：

| 模板根 | 含义 |
|---|---|
| `${params.x}` | 形参透传（Mobile-Agent-E arguments_map 的 Formal） |
| `${vars.y}` | 运行变量（SetVar 写入 / 常量字面量 = Const） |
| `${nodes.n1.output}` | 上游节点输出（对象可继续下钻 `.field`） |

解析失败 = 节点级 VALIDATION 错误，**立即失败不重试**（重试换不来更好的参数）。

### 2. 超步调度（superstep）

每轮：算**就绪集**（PENDING 且全部非回边入边激活）→ 并行执行（`maxConcurrency`，
默认 4）→ 汇聚 → 回边重入检查 → 下一轮。入边激活规则：

- 普通边：来源 SUCCEEDED / SKIPPED（CONTINUE）；
- `branch` 边：来源 SUCCEEDED 且**路由决策持久化值**等于该 branch（switch 出口）；
- `error` 边：来源 FAILED（ERROR_BRANCH 的降级路径 —— 失败也有一条边）。

**回边循环**（Tarjan DFS 识别，环上恰一条）：回边不参与就绪判定（否则环入口永不
满足），只触发重入 —— 重入判据是「目标完成于来源完成之前」（`finishedStep`
严格更小）。线性 / 扇出图天然不触发；显式回边循环直到 `maxSteps` 熔断。
自环 = 每次完成后重入。

### 3. 节点级韧性

- **重试**（LangGraph RetryPolicy）：指数退避 + ±25% 抖动，**仅瞬时错误**
  （工具 TIMEOUT / RATE_LIMITED / UNAVAILABLE、节点超时、LLM 网络异常）；
  权限 / 校验类业务失败立即终止（Temporal NonRetryable 纪律）；
- **超时**：`timeoutMs` per 节点（`withTimeoutOrNull`，与用户取消严格区分）；
- **失败三态**（n8n onError）：
  - `FAIL_RUN`（缺省）：失败即失败运行，并行兄弟取消，终态收拢为 SKIPPED；
  - `CONTINUE`：标 SKIPPED 继续推进（下游模板引用其输出会报错 —— 适合旁路步骤）；
  - `ERROR_BRANCH`：路由进 `branch="error"` 的边 —— 降级路径 / 人工接管；
- **熔断**：超步数超 `maxSteps`（默认 100）终止 —— agent 自主循环不收敛的护栏。

### 4. human-in-the-loop（LangGraph interrupt 模式）

`humanNode` 就绪时：运行落盘 `WAITING_HUMAN`（**暂停不是错误**），事件流以
`RunWaiting` 结束；宿主收集人工答复后 `engine.resume(runId, humanAnswer)` ——
答案写进节点输出，从中断处继续。等待提示支持模板（`${params.who}` 等）。
工具路径（run_workflow）遇 human 节点会拒绝执行并说明 —— 模型自治工作流
不应包含人工节点，宿主编排的工作流才用它。

### 5. 持久化与崩溃恢复

- **两级落盘**（LangGraph pending_writes 的单进程简化）：节点状态变更**即时**
  原子落盘（tmp + ATOMIC_MOVE），超步计数与状态同事务 —— 恢复后熔断上限不失效；
- **恢复语义**：SUCCEEDED 且有输出 → 不重跑，直接供下游消费（`engine.resume`）；
  RUNNING → 崩溃窗口残留 → 回退 PENDING 按已计 attempt 续跑（**at-least-once**，
  工具应尽量幂等）；WAITING_HUMAN → resume 携答案；PENDING → 直接续跑；
- **崩溃重分类**（n8n 纪律）：进程启动时 `recoverInterrupted()` 只把残留
  `RUNNING` 重分类为 `CRASHED`（WAITING_HUMAN 是合法暂停，不误杀）；
- **路由确定性**（Temporal 纪律）：switch 的 branch 决策持久化在节点运行态，
  恢复后不重判 —— 决策只依赖已持久化状态。

### 6. 工作流库生命周期（执行统计驱动）

```
   save()      2×成功       连续3次失败
CANDIDATE ─────────▶ ACTIVE ─────────▶ DISABLED
    ▲ save(新版v+1)     enable(一票复权)   │
    └─────────────────────────────────────┘ disable(一票否决)
```

- **CANDIDATE → ACTIVE**：累计 2 次成功运行晋升（「被复用验证过」比
  「自评高分」可信 —— OS-Copilot 评分门槛的统计化替代）；
- **ACTIVE → DISABLED**：连续 3 次失败自动熔断（间歇失败不误杀：成功重置连败）；
- 同 id 保存 = version+1，历史成功统计保留，连败清零重新观察；
- DISABLED 从建议索引与 run 列表摘除，文件保留，`enable` 复活直达 ACTIVE。
- `run_workflow` / 引擎终态 → `onRunFinished` → `recordRunOutcome` 自动接线。

### 7. 蒸馏学习（WorkflowExtractor）

**只从成功轨迹蒸馏**（Mobile-Agent-E 纪律：失败轨迹交给 agent-memory 的
LESSON，不产出工作流 —— 坏流程复用是净损失）：

- **LLM 路径**：渲染工具调用轨迹 → 泛化纪律提示词（具体值→形参、剔除一次性
  探索、失败重试只留成功路径）→ 宽容解析（剥围栏 / 截取首尾大括号）→ 静态校验；
- **宏录制回退**（零依赖保底，与 MemoryExtractor 启发式回退同哲学）：轨迹顺序
  转 ToolNode 链，实参原样固化，连续相同调用折叠（循环护栏痕迹），>20 步截断；
- 入库即 CANDIDATE —— 由复用统计决定晋升，蒸馏质量由生命周期兜底。

### 8. 模型侧接口（五件套 + 建议注入）

两阶段披露（OS-Copilot 分层注入，防 prompt 膨胀）：

1. **索引层**：`WorkflowSuggester`（SystemContextProvider）每轮注入 ACTIVE
   工作流的 id/描述/统计（上限 8 条）—— 接入 `LongTaskAgent.extraContextProviders`；
2. **全文层**：`read_workflow(id)` 按需拉完整定义。

工具语义：
- `run_workflow(id, params)`：执行到终态；**失败带接管包**（失败节点 + 剩余
  步骤 + 错误明细）—— 模型可据此自行完成剩余步骤（mobilerun handoff 模式）；
- `save_workflow(definition)`：模型手写工作流入库（CANDIDATE）；
- `disable_workflow(id, reason)`：熔断坏工作流（与 memory_forget 同族的自愈能力）；
- 递归保护：定义校验拒绝引用 `run_workflow` 的工具节点。

## 文件格式

```
workspace/
├─ workflows/{id}.workflow.json     # SavedWorkflow：定义 + 生命周期 + 统计
└─ runs/{runId}.run.json            # WorkflowRunState：定义快照 + 节点态 + 变量
```

- 定义格式版本 `schemaVersion`（高于当前版本拒绝加载，防静默错乱）；
- 节点多态 `kind`：`tool` | `agent` | `human` | `switch` | `setvar` | `delay`；
- 单文件损坏 → 跳过该条不阻断（对齐 FileTaskPlanStore 容错纪律）；
- id 进文件名前消毒（`[^\w-]` → `_`，截 120 字符，防目录穿越）。

## 安全边界

- 工作流执行的是**注册表内既有工具**（同引擎工具门控 / 钩子 / 审批链路照常生效）；
- 工具节点参数经 `ToolSchema.validate`（模型看到的与执行器校验的同一 schema）；
- 人工节点不落答案以外的任何状态；敏感值建议经宿主凭据存储解析后以形参传入
  （工作流文件本身不要存明文密码 —— 模板里只写 `${params.xxx}`）；
- at-least-once 语义下**非幂等工具**（发邮件 / 下单）在崩溃恢复窗口可能重复
  执行 —— 关键写操作建议放幂等包装或人工节点之后。

## 与其他模块的组合

| 组合 | 效果 |
|---|---|
| `LongTaskAgent(extraContextProviders = listOf(system.suggester()))` | 计划 + 记忆 + 工作流索引三重注入（CompositeContextProvider 组合缝） |
| `extraTools = registry.getAllTools()` | 工作流五件套与业务工具共存，模型自主选择 |
| 会话成功后 `system.distillFromConversation(agent.conversationMemory.snapshot(), goal)` | 成功经验沉淀为候选工作流（学习闭环） |
| `agent-memory` 的 TASK_OUTCOME / LESSON | 工作流运行结论可另存记忆（陈述性）与工作流（过程性）互补 |

## 设计决策记录

1. **模板字符串而非 sealed ParamBinding**：JSON 可序列化、DSL 友好；语义等价于
   Shortcut 的 arguments_map（`${params.x}` = Formal 透传，字面量 = Const）。
   代价：无编译期检查 —— 用 fail-fast 解析异常 + 静态校验弥补。
2. **「从哪继续」用状态标记而非位置指针**（LangGraph versions_seen / n8n runData
   共同结论）：状态标记天然幂等；就绪集计算是纯函数，只读持久化状态。
3. **回边不参与就绪判定**：AND-join 若把回边当依赖，环入口永不被满足（实测踩坑）；
   Tarjan DFS 保证每环恰一条回边，重入判据用 finishedStep 严格小于。
4. **取消是协作式的**：channelFlow 冷流无法在取消后发事件；超步 / 重试边界检查
   取消信号，在跑节点受其 timeoutMs 约束 —— 长节点请设超时。
5. **蒸馏只走成功 + 回退宏录制**：与 OS-Copilot 的评分门槛相比，「复用统计」
   是更硬的验证（2 次成功才晋升）；宏录制保证无 LLM 也有产出（质量降级可用性保底）。
6. **不做完整事件溯源重放**（Temporal 模式）：单进程无多 worker 竞争，
   at-least-once + 幂等建议足够；只取其「调度决策只依赖持久化状态」纪律。

## 验证

- 99 个单元用例：定义校验 14 + 模板 8 + 编解码 / 存储 12 + 引擎 24（并行 /
  分支 / 重试 / 三态 / human / 崩溃恢复 / 熔断 / 循环 / 取消）+ 库 12 + 建议器 3 +
  DSL 2 + 蒸馏 10 + 工具 12 + 门面 3；
- `examples/workflow-agent --demo`：确定性端到端演示（无需 API Key）—— 参数化
  模板 / switch 分支 / 重试退避 / 错误分支降级 / human 审批恢复 / 双引擎崩溃接力 /
  生命周期迁移 / 宏录制蒸馏 / 建议注入。
