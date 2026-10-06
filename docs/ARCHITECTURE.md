# 架构总览

```
┌────────────────────────────────────────────────────────────────┐
│                        宿主应用 (Android / JVM)                  │
│   UI · 业务逻辑 · 平台能力                                      │
└──────┬──────────────────┬──────────────────┬───────────────────┘
       │                  │                  │
       │           ┌──────▼──────┐    ┌──────▼──────┐
       │           │ agent-plugin│    │  agent-mcp  │
       │           │ ServiceLoader│   │ MCP 客户端/  │
       │           │ 插件发现+信任门│   │ 服务端+桥    │
       │           └──────┬──────┘    └──────┬──────┘
       │                  │                  │
       │         ┌────────▼──────────────────▼────────┐
       │         │           agent-tools              │
       │         │  ToolRegistry · ToolExecutor       │
       │         │  Schema DSL · 熔断/重试/限流         │
       │         └────────────────┬───────────────────┘
       │                          │
       │  ┌───────────────────────▼────────────────────┐
       │  │               agent-core                   │
       │  │  AgentEngine(ReAct 主循环) · AgentEvent     │
       │  │  SessionManager · HookRegistry · 压缩       │
       │  └───────────────────────┬────────────────────┘
       │                          │
       │  ┌───────────────────────▼────────────────────┐
       └─►│               agent-llm                    │
          │  LlmClient · OpenAiCompatibleClient(SSE)    │
          │  LlmMessage · 重试退避                       │
          └─────────────────────────────────────────────┘
```

## 依赖方向

```
agent-core ──► agent-tools
agent-core ──► agent-llm
agent-mcp ──► agent-tools          （MCP 工具注册进 ToolRegistry）
agent-plugin ──► agent-tools       （插件工具注册进 ToolRegistry）
examples ──► 全部
```

- 依赖单向、无环；框架不感知宿主业务。
- 引擎对工具/LLM 只依赖最小接口，任何环节都可替换。

## 关键机制

### ReAct 主循环
`while (iteration < maxIterations)`：构建消息 → 流式请求 LLM（thinking/content/tool-call 分流）→
有工具调用则经执行管线逐个执行并把结果回填，无工具调用且文本完整则结束。
每轮之间经过压缩门与钩子点。

### 事件流
引擎对外只发 `Flow<AgentEvent>`（约 15 类事件，覆盖思考/工具/回复/用量/交互/韧性/终态）。
宿主 UI 只需 collect，无需理解引擎内部。

### 工具执行管线
`查找 → 门控(风险确认) → PreToolUse 钩子(可阻断/改参) → Schema 校验 → 执行(超时/重试/熔断) → 统计 → PostToolUse 钩子`

### MCP 重做
- 传输层抽象：`StdioMcpTransport`（NDJSON 帧 + 写锁 + stderr 泄放）与
  `HttpMcpTransport`（Streamable HTTP + `Mcp-Session-Id` 会话头）。
- 客户端：initialize 握手 → notifications/initialized → tools/list 分页 → tools/call。
- 服务端：把本框架 `ToolRegistry` 经白名单暴露为 MCP Server（Streamable HTTP + Bearer Token）。
- 桥：`McpToolBridge` 自动把远端 MCP 工具注册为一等 `AgentTool`（`mcp__{server}__{tool}` 命名）。

### 插件重做（对比原仓库 AIDL 方案的取舍）
原仓库用 Android AIDL 跨进程隔离，安全但重；框架版改用 **ServiceLoader** 声明式发现 +
**信任门**（API 版本校验 / 工具风险钳制 / 描述符校验），宿主同进程加载，保留
`HostBridge` 反向能力注入。Android 端如需跨进程隔离，可在宿主层包装，框架契约不变。
