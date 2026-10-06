package com.androidguru.example

import com.androidguru.agent.core.engine.AgentConfig
import com.androidguru.agent.core.engine.AgentEvent
import com.androidguru.agent.core.engine.DefaultAgentEngine
import com.androidguru.agent.core.engine.UserInput
import com.androidguru.agent.llm.LlmConfig
import com.androidguru.agent.llm.OpenAiCompatibleClient
import com.androidguru.agent.plugin.DefaultHostBridge
import com.androidguru.agent.plugin.PluginHost
import com.androidguru.agent.tools.AgentTool
import com.androidguru.agent.tools.DefaultToolExecutor
import com.androidguru.agent.tools.DefaultToolRegistry
import com.androidguru.agent.tools.ToolRequest
import com.androidguru.agent.tools.ToolResult
import com.androidguru.agent.tools.ToolSchema
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 演示工具 1：两数四则运算（展示 ToolSchema DSL + 结构化结果）。
 */
class CalculatorTool : AgentTool {
    override val id = "calculator"
    override val description = "两数四则运算。参数: {\"a\": number, \"b\": number, \"op\": \"add\"|\"sub\"|\"mul\"|\"div\"}"
    override val parameters = ToolSchema.build {
        number("a", "第一个操作数", required = true)
        number("b", "第二个操作数", required = true)
        string("op", "运算符", required = true, enumValues = listOf("add", "sub", "mul", "div"))
    }

    override suspend fun execute(request: ToolRequest): ToolResult {
        val obj = Json.parseToJsonElement(request.arguments) as JsonObject
        val a = (obj["a"] as JsonPrimitive).content.toDouble()
        val b = (obj["b"] as JsonPrimitive).content.toDouble()
        return when ((obj["op"] as JsonPrimitive).content) {
            "add" -> ToolResult.success((a + b).toString())
            "sub" -> ToolResult.success((a - b).toString())
            "mul" -> ToolResult.success((a * b).toString())
            "div" -> if (b == 0.0) {
                ToolResult.invalid("b", "除数不能为零", suggestion = "请换一个非零除数")
            } else {
                ToolResult.success((a / b).toString())
            }

            else -> ToolResult.invalid("op", "不支持的运算符")
        }
    }
}

/**
 * 演示工具 2：当前时间。
 */
class ClockTool : AgentTool {
    override val id = "clock_now"
    override val description = "获取当前系统时间"
    override val parameters = ToolSchema.empty()

    override suspend fun execute(request: ToolRequest): ToolResult {
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        return ToolResult.success(now)
    }
}

/**
 * 演示插件：经 PluginHost 信任门加载（工具自动获得 demo_ 命名空间 + MEDIUM 风险钳制）。
 */
class DemoPlugin : com.androidguru.agent.plugin.AgentPlugin {
    override val descriptor = com.androidguru.agent.plugin.PluginDescriptor(
        id = "demo",
        name = "Demo Plugin",
        version = "1.0.0",
        vendor = "Android Agent Framework",
        description = "演示 ServiceLoader 插件：提供 word_count 工具",
    )

    override fun createTools(hostBridge: com.androidguru.agent.plugin.HostBridge) = listOf(object : AgentTool {
        override val id = "word_count"
        override val description = "统计文本行数与字符数"
        override val parameters = ToolSchema.build {
            string("text", required = true)
        }

        override suspend fun execute(request: ToolRequest): ToolResult {
            val obj = Json.parseToJsonElement(request.arguments) as JsonObject
            val text = (obj["text"] as JsonPrimitive).content
            return ToolResult.success("lines=${text.lines().size}, chars=${text.length}")
        }
    })
}

/**
 * 最小可运行示例 —— **5 步造出一个 Agent**。
 *
 * 运行前设置环境变量：
 * ```
 * export AGENT_BASE_URL="https://api.deepseek.com/v1"   # 任意 OpenAI 兼容端点
 * export AGENT_API_KEY="sk-..."
 * export AGENT_MODEL="deepseek-chat"
 * ./gradlew :examples:simple-agent:run
 * ```
 */
fun main() = runBlocking {
    // ① 配置 LLM（任意 OpenAI 兼容端点）
    val llmConfig = LlmConfig(
        baseUrl = System.getenv("AGENT_BASE_URL") ?: "https://api.openai.com/v1",
        apiKey = System.getenv("AGENT_API_KEY") ?: error("请设置 AGENT_API_KEY 环境变量"),
        model = System.getenv("AGENT_MODEL") ?: "gpt-4o-mini",
    )

    // ② 注册业务工具
    val registry = DefaultToolRegistry()
    registry.register(CalculatorTool())
    registry.register(ClockTool())

    // ③ 加载插件（classpath 发现 META-INF/services 声明的插件）
    val pluginHost = PluginHost(registry, DefaultHostBridge())
    val loaded = pluginHost.loadFromClasspath()
    loaded.forEach { println("[插件] ${it.plugin.descriptor.id} → 工具 ${it.registeredToolIds}") }

    // ④ 组装引擎
    val engine = DefaultAgentEngine(
        llmClient = OpenAiCompatibleClient(llmConfig),
        toolRegistry = registry,
        toolExecutor = DefaultToolExecutor(registry),
        config = AgentConfig(
            systemPrompt = "你是运行在 Android Agent Framework 上的助手。" +
                "需要计算时用 calculator，需要时间时用 clock_now。" +
                "注册表里还有插件提供的工具，按需调用。用中文回答。",
        ),
    )

    // ⑤ 对话 REPL：collect 事件流
    println("=== Android Agent Framework Demo（输入 exit 退出）===")
    while (true) {
        print("\n你> ")
        val line = readLine() ?: break
        if (line.isBlank()) continue
        if (line == "exit") break

        engine.execute(UserInput(line)).collect { event ->
            when (event) {
                is AgentEvent.ThinkingChunk -> print("💭 ${event.text}")
                is AgentEvent.ResponseChunk -> print(event.text)
                is AgentEvent.ToolCallStart -> print("\n🔧 ${event.toolName}(${event.arguments.take(80)})")
                is AgentEvent.ToolCallComplete -> println(if (event.result.ok) " ✓" else " ✗ ${event.result.content.take(100)}")
                is AgentEvent.UserInputRequired -> {
                    print("\n${event.prompt}\n你> ")
                    engine.submitUserInput(readLine().orEmpty())
                }

                is AgentEvent.UsageUpdated -> Unit
                is AgentEvent.Error -> println("\n[错误] ${event.message}")
                is AgentEvent.Complete -> print("\n")
                else -> Unit
            }
        }
    }
    println("再见！")
}
