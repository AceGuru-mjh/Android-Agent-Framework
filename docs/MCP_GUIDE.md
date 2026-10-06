# MCP 协议指南

本框架实现了完整的 MCP（Model Context Protocol，版本 `2024-11-05`）：
**客户端 + 服务端 + 工具桥**，传输层抽象可扩展。

## 架构

```
                  ┌─────────────────────────────────────┐
                  │           ToolRegistry              │
                  │  (内置工具 ∪ 插件工具 ∪ MCP远端工具)    │
                  └───────┬───────────────────▲─────────┘
                          │                   │ 注册为一等 AgentTool
                  ┌───────▼───────┐   ┌───────┴────────┐
   外部 AI ──────►│  McpToolServer │   │  McpToolBridge │◄───── 远端 MCP 工具
  (Claude/Cline)  │  (本框架作为    │   │  (远端工具本地   │       (其他 MCP 服务器)
                  │   MCP 服务端)   │   │   一等公民化)    │
                  └───────▲───────┘   └───────┬────────┘
                          │                   │
                          │           ┌───────▼────────┐
                          │           │ McpClient      │
                          │           │  ├ Stdio 传输   │
                          │           │  └ HTTP 传输    │
                          │           └────────────────┘
```

## 作为客户端：接入 MCP 服务器

### stdio（本地进程）

```kotlin
val manager = McpManager(toolRegistry)
manager.addServer(
    McpServerConfig(
        name = "filesystem",
        command = listOf("npx", "-y", "@modelcontextprotocol/server-filesystem", "/data"),
        env = mapOf("GITHUB_TOKEN" to System.getenv("GITHUB_TOKEN")),  // 环境变量桥
    ),
)
```

### Streamable HTTP（远端）

```kotlin
manager.addServer(
    McpServerConfig(
        name = "remote",
        url = "https://mcp.example.com/mcp",
        headers = mapOf("Authorization" to "Bearer ${System.getenv("MCP_TOKEN")}"),
    ),
)
```

连接成功后，远端工具自动注册为 `mcp__{server}__{tool}`（如 `mcp__filesystem__read_file`），
与本地工具一视同仁地出现在模型工具清单里。会话监听：

```kotlin
manager.addSessionListener(object : McpSessionListener {
    override fun onServerConnected(connection: McpConnection) { /* 更新 UI */ }
    override fun onServerDisconnected(serverName: String, cause: Throwable?) { /* 提示重连 */ }
})
```

## 作为服务端：暴露你的工具

```kotlin
val server = McpToolServer(
    toolRegistry = registry,
    toolExecutor = executor,
    config = McpServerConfig(
        port = 8765,
        bearerToken = "strong-random-token",       // 必填；空 = 拒绝全部（fail-closed）
        serverName = "my-agent",
        allowedToolIds = setOf("weather_query", "clock_now"),  // null = 全部
        blockedToolPrefixes = setOf("admin_"),
    ),
)
server.start()   // 端点: http://<host>:8765/mcp
```

接入 Claude Desktop（`claude_desktop_config.json`）：

```json
{
  "mcpServers": {
    "my-agent": {
      "type": "http",
      "url": "http://192.168.1.20:8765/mcp",
      "headers": { "Authorization": "Bearer strong-random-token" }
    }
  }
}
```

### 安全模型

| 机制 | 行为 |
|---|---|
| Bearer Token | 常时比较（`MessageDigest.isEqual`），防时序攻击 |
| 空 Token | **拒绝全部请求**（fail-closed），必须显式配置 |
| 工具白名单 | `allowedToolIds`（null = 全部） |
| 工具黑名单 | `blockedToolPrefixes` + `vault_` 前缀**硬拦截**（不可配置） |
| 限速 | 每远端地址分钟窗口（默认 120/min），超出 429 |
| 会话 | initialize 发放 `Mcp-Session-Id`；空闲 30 分钟回收；容量 256 |
| 请求体 | 1MB 上限 |
| 执行路径 | **tools/call 与内部 Agent 走同一 ToolExecutor**，不旁路管线（熔断/限流/schema 校验全生效） |

## 自定义传输

实现 `McpTransport` 三件套（`start` / `send` / `close`）即可接入任意承载
（WebSocket、蓝牙、PRoot 沙箱……），协议逻辑复用：

```kotlin
class MyTransport : McpTransport {
    override val name = "my-channel"
    override suspend fun start() { /* 建立信道 */ }
    override suspend fun send(id: Long?, payload: JsonObject): JsonObject? {
        // id != null: 发送并等待响应帧（完整 JSON-RPC 响应对象）
        // id == null: 通知，尽力发送，返回 null
    }
    override fun isHealthy(): Boolean = ...
    override suspend fun close() { /* 幂等释放 */ }
}

val client = McpClient(MyTransport())
client.initialize()
```

## 与原仓库实现的对照

| 维度 | 原仓库（Android-Guru-Agent） | 本框架重做 |
|---|---|---|
| 响应等待 | 单读线程 + 50ms 轮询 pending map | 单读线程 + `CompletableDeferred` 按 id 直达 |
| 服务端 | 独立模块裸 ServerSocket | JDK 内置 HttpServer，与客户端共享同一 JSON-RPC 内核 |
| 分页防护 | 20 页上限 | 保留，且抛 `McpException` 可观测 |
| 错误语义 | 字符串折叠 | `McpRemoteException`（远端）与 `McpException`（本地）分离 |
| 工具注册 | McpToolRegistrar + 代数治理 | McpToolBridge + McpManager 重连语义（更简） |
| 测试 | 设备/沙箱依赖 | **纯 JVM 端到端**（真实 HTTP 服务端 ↔ 客户端回环） |
