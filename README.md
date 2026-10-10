# Android Agent Framework

[![CI](https://github.com/AceGuru-mjh/Android-Agent-Framework/actions/workflows/ci.yml/badge.svg)](https://github.com/AceGuru-mjh/Android-Agent-Framework/actions/workflows/ci.yml)
[![PR Check](https://github.com/AceGuru-mjh/Android-Agent-Framework/actions/workflows/pr-check.yml/badge.svg)](https://github.com/AceGuru-mjh/Android-Agent-Framework/actions/workflows/pr-check.yml)
[![Release](https://github.com/AceGuru-mjh/Android-Agent-Framework/actions/workflows/release.yml/badge.svg)](https://github.com/AceGuru-mjh/Android-Agent-Framework/actions/workflows/release.yml)

> 从 [Android-Guru-Agent](https://github.com/AceGuru-mjh/Android-Guru-Agent) 中提取的通用 Agent 框架。
> 任何 Android / JVM 软件接入本框架，即可快速获得完整的 Agent 能力：**接入你的 LLM Key，几分钟内造出一个 Agent**。

## 定位

- **纯 JVM Kotlin**：全部模块零 Android 依赖，Android 工程与任何 JVM 应用（服务端、桌面）均可直接依赖。
- **基础能力完善**：聚焦框架级底座 —— Agent 引擎、工具系统、LLM 适配、MCP 协议、插件 SDK。
- **终端执行能力**（PR #4，自 [yl-ai](https://github.com/iill392/yl-ai) 移植重写）：共享终端会话、哨兵执行、风险分级与审批闸门、后台作业、Alpine 容器（PRoot）、本地控制 API、审计。
- **C++17 原生增强层**（PR #5）：forkpty 真 PTY 通道、ANSI 批量清洗、ELF64 补丁 —— 不可用时优雅回退纯 JVM，框架主体仍零平台依赖。
- **长程任务能力**（PR #22）：任务计划（todo 状态机 + 每轮注入）、迭代预算续跑、相同调用循环护栏、崩溃恢复（计划与会话双持久化）。
- **长期记忆能力**（PR #24）：跨会话三层记忆（工作/情景/语义）—— 每轮相关性召回注入、记忆三件套工具、会话结束抽取（LLM + 启发式回退）、压缩时捕获被裁对话、审批授权跨会话存续（opt-in + TTL）。
- **Agent 智能深化**（PR #26）：反思纠错（失败驱动的 LLM 根因分析 + 启发式回退）、语义循环检测（JSON 键序等价签名 + A→B→A→B 交替周期）、跑偏检测（进展停滞 + 周期性目标对齐抽查）、框架主动的子目标拆解（阶段→步骤+验收标准，父任务状态自动收拢）、**移动端任务评估**（agent-eval：轨迹校验 + 18 任务标准套件 + 调优报告）、**本地模型深度适配**（文本协议工具调用模拟 + 档位库）。
- **克制**：不含原项目中的设备工具集等业务高级功能；连接器体系不复用，插件与 MCP 全新重做。

## 模块总览

| 模块 | 说明 | 文档 |
|---|---|---|
| `agent-core` | Agent 引擎（单一 ReAct 主循环）、事件流、会话管理、生命周期钩子、上下文压缩 | — |
| `agent-tools` | 工具系统：注册表、五段执行管线（门控/钩子/校验/熔断/限流）、Schema DSL、结构化结果 | — |
| `agent-llm` | LLM 适配层：OpenAI 兼容流式客户端（SSE + 工具调用累积）、消息模型、端点差异适配 | — |
| `agent-mcp` | **全新重做**的 MCP 协议：JSON-RPC 2.0、stdio / Streamable HTTP 双传输、客户端 + 服务端 + 工具桥 | [MCP_GUIDE.md](docs/MCP_GUIDE.md) |
| `agent-chat` | **聊天层**（自 yl-ai 融入）：五类卡片模型、历史持久化（原子写 + 上限 + 作业对账）、事件→卡片转译器（流式权威对齐 / 审批内联卡 / 取消回填） | [HOST_UI.md](docs/HOST_UI.md) |
| `agent-plugin` | **全新重做**的插件 SDK：ServiceLoader 发现、三道信任门、宿主能力桥 | [PLUGIN_SDK.md](docs/PLUGIN_SDK.md) |
| `agent-shell` | **全新移植**的终端引擎：共享终端会话、哨兵式命令执行、命令风险分级、挂起式审批闸门、后台作业、Alpine 容器（PRoot）、**Termux 环境**（proot 自映射）、本地控制 API、审计与崩溃恢复 | [SHELL_GUIDE.md](docs/SHELL_GUIDE.md) |
| `agent-shell-tools` | **全新移植**的终端工具集：20 个正交 AgentTool、审批/审计钩子、系统提示词构建器、平台能力 SPI | [SHELL_GUIDE.md](docs/SHELL_GUIDE.md) |
| `agent-shell-native` | **C++17 原生增强层**（PR #5）：forkpty 真 PTY 通道（NativeProcessChannelFactory）、ANSI 批量清洗、ELF64 补丁（RUNPATH/SONAME），不可用时优雅回退纯 JVM | [NATIVE_GUIDE.md](docs/NATIVE_GUIDE.md) |
| `agent-tasks` | **长程任务系统**（PR #22）：TaskPlan 计划状态机（rewrite/patch 双模式）、每轮计划状态注入、进度观察、文件持久化 + `LongTaskAgent` 装配门面 | [LONG_TASK_GUIDE.md](docs/LONG_TASK_GUIDE.md) |
| `agent-memory` | **长期记忆系统**（PR #24）：三层记忆架构（工作/情景/语义）、四因子相关性召回每轮注入、memory_save/search/forget 三件套工具、会话结束抽取（LLM + 启发式回退 + 去重）、压缩时捕获被裁对话、`MemorySystem` 装配门面 | [MEMORY_GUIDE.md](docs/MEMORY_GUIDE.md) |
| `agent-eval` | **任务评估系统**（PR #26）：轨迹校验器（必做/禁止调用 + 参数级匹配 + 循环/轮数/收尾文本判据）、移动端任务套件（8 类 18 任务标准集）、评估 harness（失败隔离 + 逐任务报告）、调优报告（成功率 / 分类 / 难度 / 失败分类定位）、`EvalHarness` 一行接入 | [AGENT_INTELLIGENCE.md](docs/AGENT_INTELLIGENCE.md) |
| `agent-workflow` | **工作流系统**（PR #25）：参数化声明式工作流（DAG + 条件分支 + 回边循环）、超步调度引擎（节点级重试/超时/失败三态处置/human-in-loop/崩溃恢复/熔断护栏）、工作流库（候选→激活→熔断生命周期 + 执行统计）、会话蒸馏学习（LLM 泛化 + 宏录制回退）、工具五件套 + 建议注入、`WorkflowSystem` 装配门面 | [WORKFLOW_GUIDE.md](docs/WORKFLOW_GUIDE.md) |
| `examples/simple-agent` | 最小可运行示例：控制台对话 Agent（含插件演示） | [GETTING_STARTED.md](docs/GETTING_STARTED.md) |
| `examples/terminal-agent` | 终端 Agent 示例：ShellToolSet 全套接入 + 原生 PTY 自动探测 + 控制台审批 | [SHELL_GUIDE.md](docs/SHELL_GUIDE.md) |
| `examples/long-task-agent` | 长程任务示例：计划进度条 + 预算续跑确认 + `--resume` 崩溃恢复 | [LONG_TASK_GUIDE.md](docs/LONG_TASK_GUIDE.md) |
| `examples/memory-agent` | 长期记忆示例：跨会话召回闭环演示（`--demo` 双脚本会话，无需 API Key） | [MEMORY_GUIDE.md](docs/MEMORY_GUIDE.md) |

## 快速上手

```kotlin
val llm = OpenAiCompatibleClient(
    LlmConfig(baseUrl = "https://api.deepseek.com/v1", apiKey = "sk-...", model = "deepseek-chat")
)

val registry = DefaultToolRegistry()
registry.register(CalculatorTool())          // 你的业务工具
val executor = DefaultToolExecutor(registry)

val engine = DefaultAgentEngine(
    llmClient = llm,
    toolRegistry = registry,
    toolExecutor = executor,
    config = AgentConfig.DEFAULT,
)

engine.execute(UserInput("帮我算一下 237 * 19")).collect { event ->
    when (event) {
        is AgentEvent.ResponseChunk -> print(event.text)
        is AgentEvent.ToolCallStart -> println("调用: ${event.toolName}")
        else -> Unit
    }
}
```

运行示例（本地跑通全流程）：

```bash
export AGENT_BASE_URL="https://api.deepseek.com/v1"
export AGENT_API_KEY="sk-..."
export AGENT_MODEL="deepseek-chat"
./gradlew :examples:simple-agent:run
```

完整指南见 **[docs/GETTING_STARTED.md](docs/GETTING_STARTED.md)**。

## 设计原则（继承自 Android-Guru-Agent）

1. **纯 JVM 内核** — 全部模块零平台依赖，Android 特性只在宿主接入层出现。
2. **确定性骨架 × 学习型血肉** — 工作流是可验证的确定性骨架（模板形参 / 路由持久化 / 崩溃恢复），蒸馏与记忆是学习型血肉；骨架可审计、血肉可淘汰（执行统计驱动的生命周期）。
2. **事件流驱动** — 引擎对外只暴露 `Flow<AgentEvent>`，UI / 日志 / 遥测按需订阅。
3. **注入式扩展** — 模式差异走 system prompt 注入，能力差异走工具注册，引擎主循环只有一份。
4. **生产级韧性** — 熔断器、确定性退避、限流、schema 校验、悬空 tool-call 修补、空响应重试、相同调用循环护栏，全部内建。
5. **长程任务一等公民** — 任务计划每轮注入、迭代预算续跑、崩溃恢复（对话 + 计划双持久化）；任务能跨预算边界、跨进程生命周期继续。
6. **记忆分层** — 工作记忆（会话内消息流）、情景记忆（会话摘要 / 压缩捕获）、语义记忆（事实 / 偏好 / 教训）各司其职；自动召回与模型主动读写双通道；过时记忆可纠正（forget 是一等能力）。
7. **协议严谨** — MCP 严格遵循 JSON-RPC 2.0 与 MCP 规范（2024-11-05），握手 / 分页 / 会话头 / 错误码完整实现，且有服务端 ↔ 客户端端到端测试背书。

## 从原项目提取了什么、剔除了什么

**提取并保留（生产机制层）**
- ReAct 主循环 + 事件流模型 + 人机交互挂起（CompletableDeferred）
- 五段工具执行管线 + 熔断/退避/限流 + 8 类生命周期钩子
- 流式工具调用 index 复合键累积、流尾 usage 帧、取消即断连
- 悬空 tool-call 修补、上下文压缩门、MCP 分页游标/会话头/stderr 泄放

**全新重做**
- MCP：三层清晰协议分层（protocol / transport / client+server+bridge），纯 JVM 端到端测试
- 插件：AIDL 跨进程 → ServiceLoader + 信任门（版本门/描述符门/风险钳制语义不降级）

**剔除（app 业务层，不进框架）**
- 821 行中文系统提示词、SmallTalk/PromiseDetector、终端顾问、技能市场
- 设备工具集（111 个内置工具）、终端仿真、认知记忆 cs-mem（app 业务实现；框架级记忆能力由 agent-memory 重新设计，见 [MEMORY_GUIDE.md](docs/MEMORY_GUIDE.md)）、三级权限链
- 连接器体系（不复用）

## 构建

```bash
./gradlew build        # 编译 + 全部测试（JDK 17+，无需 Android SDK）
./gradlew test         # 仅测试
./gradlew :examples:simple-agent:run   # 运行示例

# C++ 原生层（可选，需要 cmake + C++17 编译器）
cmake -S agent-shell-native/src/main/cpp -B build-native && cmake --build build-native
ctest --test-dir build-native    # C++ 单元测试
./gradlew :agent-shell-native:test -Pagsh.native.lib=$PWD/build-native/libagsh_native.so
```

Android arm64-v8a 交叉编译（NDK）：见 [NATIVE_GUIDE.md](docs/NATIVE_GUIDE.md)；
CI 产出的成品库在 Actions → Artifacts 下载。

## 路线图

- [x] 仓库基建 + CI 工作流
- [x] **PR #1** — 核心框架（agent-core / agent-tools / agent-llm）
- [x] **PR #2** — MCP 上下文协议重做（agent-mcp）
- [x] **PR #3** — 插件系统重做 + 示例 + 发布工作流（agent-plugin / examples / release）
- [x] **PR #4** — 终端能力整体移植：agent-shell / agent-shell-tools（自 yl-ai 移植重写，修复哨兵协议缺陷）
- [x] **PR #5** — C++17 原生增强层（agent-shell-native）+ PRoot RUNPATH 自动清空 + CI 全面编译验证（JVM 矩阵 / C++ host / NDK 交叉编译）+ PR 门禁（pr-check.yml）
- [x] **PR #23** — yl-ai 全量融合收尾 + 全线加固：Termux 环境（agent-shell/termux + termux_exec 工具）、聊天层（agent-chat）、控制 API 自检器、设置存储（SecretVault 首个消费者）、LLM 细节（cachedTokens / normalizeEndpoint / 8 家预设 / 自带工具注入）；**23 项 bug 修复**（issues #8–#21：PTY 回显哨兵锚定、写入通道门控、熔断探测槽泄漏、UTF-8 跨 chunk、审计取旧、策略绕过、RFC 6455 合规等）
- [x] **PR #24** — 长期记忆系统 + 基础能力补强：新模块 agent-memory（三层记忆：召回注入 / 记忆三件套工具 / 会话抽取 / 压缩捕获 / MemorySystem 门面）、agent-core CompositeContextProvider 组合缝、LongTaskAgent extraContextProviders、审批授权跨会话存续（FileApprovalDecisionStore + TTL，opt-in）

- [x] **PR #25** — 工作流系统：新模块 agent-workflow（参数化工作流定义 + ${} 模板形参 / 超步 DAG 调度 / Tarjan 回边循环重入 / 节点级退避重试 / 失败三态处置 / human-in-loop / 持久化运行态崩溃恢复 / maxSteps 熔断）、工作流库（CANDIDATE→ACTIVE 晋升 + 连败自动熔断 + 执行统计）、WorkflowExtractor 蒸馏学习（LLM 泛化纪律 + 宏录制零依赖回退）、run/list/read/save/disable 工具五件套 + WorkflowSuggester 两阶段披露注入、examples/workflow-agent 确定性演示
- [x] **PR #26** — Agent 智能深化：agent-core 反思引擎（失败驱动根因分析 + 启发式回退 + 冷却纪律）、循环护栏升级（语义等价签名 + 交替周期检测）、跑偏检测器（停滞 + LLM 目标对齐抽查）、结构化默认提示；agent-llm ToolCallEmulatingClient（无 function-calling 本地模型的文本协议模拟 + fence 边界流式回持）；agent-core LocalModelProfiles（6 档本地模型预设 + custom 档位生成）；agent-tasks TaskDecomposer（阶段→步骤+验收标准拆解 + 解析容错兜底）+ 父任务状态自动收拢；新模块 agent-eval（轨迹校验 / 移动端 18 任务套件 / 评估 harness / 调优报告）

## License

[MIT](./LICENSE)
