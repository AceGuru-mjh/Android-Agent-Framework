# Android Agent Framework

[![CI](https://github.com/AceGuru-mjh/Android-Agent-Framework/actions/workflows/ci.yml/badge.svg)](https://github.com/AceGuru-mjh/Android-Agent-Framework/actions/workflows/ci.yml)
[![Release](https://github.com/AceGuru-mjh/Android-Agent-Framework/actions/workflows/release.yml/badge.svg)](https://github.com/AceGuru-mjh/Android-Agent-Framework/actions/workflows/release.yml)

> 从 [Android-Guru-Agent](https://github.com/AceGuru-mjh/Android-Guru-Agent) 中提取的通用 Agent 框架。
> 任何 Android / JVM 软件接入本框架，即可快速获得完整的 Agent 能力：**接入你的 LLM Key，几分钟内造出一个 Agent**。

## 定位

- **纯 JVM Kotlin**：全部模块零 Android 依赖，Android 工程与任何 JVM 应用（服务端、桌面）均可直接依赖。
- **基础能力完善**：聚焦框架级底座 —— Agent 引擎、工具系统、LLM 适配、MCP 协议、插件 SDK。
- **克制**：不含原项目中的终端仿真、设备工具集、认知记忆等业务高级功能；连接器体系不复用，插件与 MCP 全新重做。

## 模块总览

| 模块 | 说明 | 文档 |
|---|---|---|
| `agent-core` | Agent 引擎（单一 ReAct 主循环）、事件流、会话管理、生命周期钩子、上下文压缩 | — |
| `agent-tools` | 工具系统：注册表、五段执行管线（门控/钩子/校验/熔断/限流）、Schema DSL、结构化结果 | — |
| `agent-llm` | LLM 适配层：OpenAI 兼容流式客户端（SSE + 工具调用累积）、消息模型、端点差异适配 | — |
| `agent-mcp` | **全新重做**的 MCP 协议：JSON-RPC 2.0、stdio / Streamable HTTP 双传输、客户端 + 服务端 + 工具桥 | [MCP_GUIDE.md](docs/MCP_GUIDE.md) |
| `agent-plugin` | **全新重做**的插件 SDK：ServiceLoader 发现、三道信任门、宿主能力桥 | [PLUGIN_SDK.md](docs/PLUGIN_SDK.md) |
| `examples/simple-agent` | 最小可运行示例：控制台对话 Agent（含插件演示） | [GETTING_STARTED.md](docs/GETTING_STARTED.md) |

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
2. **事件流驱动** — 引擎对外只暴露 `Flow<AgentEvent>`，UI / 日志 / 遥测按需订阅。
3. **注入式扩展** — 模式差异走 system prompt 注入，能力差异走工具注册，引擎主循环只有一份。
4. **生产级韧性** — 熔断器、确定性退避、限流、schema 校验、悬空 tool-call 修补、空响应重试，全部内建。
5. **协议严谨** — MCP 严格遵循 JSON-RPC 2.0 与 MCP 规范（2024-11-05），握手 / 分页 / 会话头 / 错误码完整实现，且有服务端 ↔ 客户端端到端测试背书。

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
- 设备工具集（111 个内置工具）、终端仿真、认知记忆 cs-mem、三级权限链
- 连接器体系（不复用）

## 构建

```bash
./gradlew build        # 编译 + 全部测试（JDK 17+，无需 Android SDK）
./gradlew test         # 仅测试
./gradlew :examples:simple-agent:run   # 运行示例
```

## 路线图

- [x] 仓库基建 + CI 工作流
- [x] **PR #1** — 核心框架（agent-core / agent-tools / agent-llm）
- [x] **PR #2** — MCP 上下文协议重做（agent-mcp）
- [x] **PR #3** — 插件系统重做 + 示例 + 发布工作流（agent-plugin / examples / release）

## License

[MIT](./LICENSE)
