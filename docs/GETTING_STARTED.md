# 快速上手

本指南带你用 Android Agent Framework 在几分钟内造出一个带工具调用能力的 Agent。

## 1. 引入依赖

### Gradle (JVM / Android)

```kotlin
repositories {
    mavenCentral()
    maven {
        url = uri("https://maven.pkg.github.com/AceGuru-mjh/Android-Agent-Framework")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("USERNAME")
            password = project.findProperty("gpr.key") as String? ?: System.getenv("TOKEN")
        }
    }
}

dependencies {
    implementation("com.androidguru.agent:agent-core:0.1.0")   // 引擎（传递依赖 agent-tools / agent-llm）
    implementation("com.androidguru.agent:agent-mcp:0.1.0")    // 可选：MCP 协议
    implementation("com.androidguru.agent:agent-plugin:0.1.0") // 可选：插件 SDK
}
```

## 2. 五步最小 Agent

```kotlin
// ① 配置 LLM（任意 OpenAI 兼容端点）
val llm = OpenAiCompatibleClient(
    LlmConfig(baseUrl = "https://api.deepseek.com/v1", apiKey = "sk-...", model = "deepseek-chat"),
)

// ② 注册业务工具
val registry = DefaultToolRegistry()
registry.register(MyTools.weather())   // 你的工具
val executor = DefaultToolExecutor(registry)

// ③ 组装引擎
val engine = DefaultAgentEngine(
    llmClient = llm,
    toolRegistry = registry,
    toolExecutor = executor,
    config = AgentConfig(
        systemPrompt = "你是天气助手，需要时调用工具查询。",
        maxIterations = 15,
    ),
)

// ④ collect 事件流（思考 / 工具 / 回复全程可见）
engine.execute(UserInput("上海今天适合跑步吗？")).collect { event ->
    when (event) {
        is AgentEvent.ResponseChunk -> print(event.text)
        is AgentEvent.ToolCallStart -> println("调用工具: ${event.toolName}")
        is AgentEvent.ToolCallComplete -> println(" → ${event.result.content}")
        is AgentEvent.Error -> println("错误: ${event.message}")
        else -> Unit
    }
}
```

## 3. 写一个工具

```kotlin
class WeatherTool : AgentTool {
    override val id = "weather_query"
    override val description = "查询城市实时气温"
    override val parameters = ToolSchema.build {
        string("city", "城市名", required = true)
        string("unit", "温度单位", enumValues = listOf("c", "f"))
    }

    override suspend fun execute(request: ToolRequest): ToolResult {
        val args = Json.parseToJsonElement(request.arguments).jsonObject
        val city = args["city"]!!.jsonPrimitive.content
        return try {
            ToolResult.success("北京 23°C 晴")   // 你的实现
        } catch (e: Exception) {
            ToolResult.failure("查询失败: ${e.message}", ToolErrorCode.UNAVAILABLE)
        }
    }
}
```

要点：
- **失败返回 `ToolResult.failure`**（带错误码与重试建议），不要抛异常——模型读到建议后会自动换策略；
- schema 与校验同源，参数问题会被管线在执行前拦截。

## 4. 加一层韧性（可选）

```kotlin
val executor = DefaultToolExecutor(
    registry = registry,
    policies = mapOf(
        "weather_query" to ToolRunPolicy.NETWORK,          // 网络级超时 + 退避重试
        "batch_export" to ToolRunPolicy.LONG,              // 长任务级
    ),
    breaker = ToolCircuitBreaker(failureThreshold = 5),
)
```

## 5. 多轮会话（SessionManager）

```kotlin
val sessions = SessionManager()
val session = sessions.getOrCreate("chat-1001")

val engine = DefaultAgentEngine(
    llmClient = llm,
    toolRegistry = registry,
    toolExecutor = executor,
    memory = session.memory,      // 绑定会话记忆
)

engine.execute(UserInput("我叫小明")).collect { ... }
// 下一轮引擎自动带上历史
```

## 6. 人机交互（ask_user）

框架内置 `ask_user` 工具：模型缺信息时调用，引擎发出 `UserInputRequired` 事件并挂起：

```kotlin
engine.execute(input).collect { event ->
    if (event is AgentEvent.UserInputRequired) {
        val answer = showUiDialog(event.prompt)     // 你的 UI
        engine.submitUserInput(answer)
    }
}
```

## 7. 接入 MCP

```kotlin
// 作为客户端：接入任意 MCP 服务器
val manager = McpManager(registry)
manager.addServer(McpServerConfig(name = "fs", command = listOf("mcp-server-filesystem", "/data")))
manager.addServer(McpServerConfig(name = "web", url = "https://mcp.example.com/mcp"))
// 远端工具自动以 mcp__fs__read_file 等命名进入注册表，引擎直接可用

// 作为服务端：把你的工具暴露给 Claude Desktop / Cline
val server = McpToolServer(registry, executor, McpServerConfig(port = 8765, bearerToken = "your-token"))
server.start()
```

## 8. 加载插件

```kotlin
val host = PluginHost(registry)
host.loadFromClasspath()   // ServiceLoader 发现 META-INF/services 声明的插件
```

详见 [PLUGIN_SDK.md](./PLUGIN_SDK.md)。
