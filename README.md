# Android Agent Framework

[![CI](https://github.com/AceGuru-mjh/Android-Agent-Framework/actions/workflows/ci.yml/badge.svg)](https://github.com/AceGuru-mjh/Android-Agent-Framework/actions/workflows/ci.yml)

> 从 [Android-Guru-Agent](https://github.com/AceGuru-mjh/Android-Guru-Agent) 中提取的通用 Agent 框架。
> 任何 Android / JVM 软件接入本框架，即可快速获得完整的 Agent 能力：**接入你的 LLM Key，几分钟内造出一个 Agent**。

## 定位

- **纯 JVM Kotlin**：全部模块零 Android 依赖，Android 工程与任何 JVM 应用（服务端、桌面）均可直接依赖。
- **基础能力完善**：聚焦框架级底座 —— Agent 引擎、工具系统、LLM 适配、MCP 协议、插件 SDK。
- **克制**：不含原项目中的终端仿真、设备工具集、认知记忆等业务高级功能；连接器体系不复用，插件体系全新重做。

## 模块总览

| 模块 | 说明 | 状态 |
|---|---|---|
| `agent-core` | Agent 引擎（ReAct 主循环）、事件流、会话管理、生命周期钩子、上下文压缩 | PR #1 |
| `agent-tools` | 工具系统：注册表、执行管线（门控/钩子/校验/熔断）、Schema DSL、结构化结果 | PR #1 |
| `agent-llm` | LLM 适配层：OpenAI 兼容流式客户端（SSE + 工具调用累积）、消息模型、重试退避 | PR #1 |
| `agent-mcp` | **全新重做**的 MCP（Model Context Protocol）：JSON-RPC 2.0、stdio / Streamable HTTP 双传输、客户端 + 服务端 + 工具桥 | PR #2 |
| `agent-plugin` | **全新重做**的插件 SDK：ServiceLoader 发现、信任门校验、宿主能力桥 | PR #3 |
| `examples/simple-agent` | 最小可运行示例：一个控制台 Agent | PR #3 |

## 快速上手（预览）

```kotlin
val llm = OpenAiCompatibleClient(
    LlmConfig(baseUrl = "https://api.openai.com/v1", apiKey = "sk-...", model = "gpt-4o")
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
        is AgentEvent.ToolCallComplete -> println("[${event.toolName}] ${if (event.success) "ok" else "fail"}")
        else -> Unit
    }
}
```

## 设计原则（继承自 Android-Guru-Agent）

1. **纯 JVM 内核** — `core` 层零平台依赖，Android 特性只在接入层出现。
2. **事件流驱动** — 引擎对外只暴露 `Flow<AgentEvent>`，UI / 日志 / 遥测按需订阅。
3. **注入式扩展** — 模式差异走 system prompt 注入，能力差异走工具注册，引擎主循环只有一份。
4. **生产级韧性** — 熔断器、确定性退避、限流、schema 校验、悬空 tool-call 修补，全部内建。
5. **协议严谨** — MCP 严格遵循 JSON-RPC 2.0 与 MCP 规范（2024-11-05），握手 / 分页 / 会话头 / 错误码完整实现。

## 路线图

- [x] 仓库基建 + CI 工作流
- [ ] **PR #1** — 核心框架（agent-core / agent-tools / agent-llm）
- [ ] **PR #2** — MCP 上下文协议重做（agent-mcp）
- [ ] **PR #3** — 插件系统重做 + 示例 + 发布工作流（agent-plugin / examples）

## License

[MIT](./LICENSE)
