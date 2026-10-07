package com.androidguru.example

import com.androidguru.agent.core.engine.AgentEvent
import com.androidguru.agent.core.engine.DefaultAgentEngine
import com.androidguru.agent.core.session.InMemoryConversationMemory
import com.androidguru.agent.core.context.SlidingWindowCompressor
import com.androidguru.agent.llm.LlmClient
import com.androidguru.agent.llm.LlmConfig
import com.androidguru.agent.llm.LlmMessage
import com.androidguru.agent.llm.LlmResponse
import com.androidguru.agent.llm.LlmStreamChunk
import com.androidguru.agent.llm.OpenAiCompatibleClient
import com.androidguru.agent.llm.ToolChoiceSpec
import com.androidguru.agent.llm.ToolDefinition
import com.androidguru.agent.memory.MemorySystem
import com.androidguru.agent.tools.DefaultToolExecutor
import com.androidguru.agent.tools.DefaultToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/**
 * 长期记忆 Agent 示例 —— 演示跨会话记忆闭环：
 *
 * 1. **会话 1**：用户告诉 Agent 一条偏好 → 模型调用 memory_save 落库
 *    → 会话结束时 [MemorySystem.onSessionComplete] 沉淀任务结论；
 * 2. **会话 2**：**全新引擎 + 全新工作记忆**（对上一个会话一无所知）→
 *    用户提问相关话题 → MemoryContextInjector 自动召回上一会话的记忆
 *    → 模型直接引用记忆作答（无需用户重复说明）。
 *
 * 运行方式：
 * ```
 * # 演示模式（脚本化 LLM，无需 API Key，确定性验证跨会话召回）：
 * ./gradlew :examples:memory-agent:run --args="--demo"
 *
 * # 真实模式（交互式，需要环境变量）：
 * export AGENT_BASE_URL="https://api.deepseek.com/v1"
 * export AGENT_API_KEY="sk-..."
 * export AGENT_MODEL="deepseek-chat"
 * ./gradlew :examples:memory-agent:run
 * ```
 */
fun main(args: Array<String>) = runBlocking {
    val workspace = Path.of(System.getProperty("user.dir"), ".memory-agent-data")

    if (args.contains("--demo")) {
        runDemo(workspace)
        return@runBlocking
    }

    runInteractive(workspace)
}

// ------------------------------------------------------------------
// 演示模式：两个脚本化会话，确定性验证跨会话召回
// ------------------------------------------------------------------

private suspend fun runDemo(workspace: Path) {
    println("=== 跨会话记忆演示（workspace: $workspace）===\n")

    // ---------- 会话 1：学习 ----------
    println("──── 会话 1（学习）────")
    val llm1 = ScriptedLlm(
        // 第 1 轮：模型决定调用 memory_save 记住用户偏好
        """
        用户明确要求记住偏好 —— 调用 memory_save 保存。
        """.trimIndent() to listOf(
            ToolCallScript(
                name = "memory_save",
                arguments = """{"content":"用户叫小明，偏好 Kotlin 与深色主题","kind":"preference","importance":85,"tags":["user"]}""",
            ),
        ),
        // 第 2 轮：收到保存回执后确认
        "好的，我已把你的偏好（Kotlin 与深色主题）存入长期记忆，之后任何会话我都会记得。" to emptyList(),
    )

    val memory1 = MemorySystem.create(workspace)
    val conversation1 = InMemoryConversationMemory()
    val engine1 = buildEngine(llm1, conversation1, memory1, "demo-session-1")

    println("你> 我叫小明，喜欢用 Kotlin，请记住我的偏好")
    engine1.execute("我叫小明，喜欢用 Kotlin，请记住我的偏好").collect { renderDemo(it) }

    val learned = memory1.onSessionComplete(conversation1, "demo-session-1")
    println("\n📚 会话 1 沉淀记忆 ${learned.size} 条（任务结论自动抽取）")

    println("\n当前记忆库：")
    memory1.store.all().forEach { println("  ${it.renderForModel()}") }

    // ---------- 会话 2：全新实例，凭记忆作答 ----------
    println("\n──── 会话 2（全新引擎 + 全新工作记忆，只凭长期记忆）────")
    // 会话 2 的模型会看到注入的「长期记忆」段落并在回答中引用
    val llm2 = AnsweringLlm()

    val memory2 = MemorySystem.create(workspace) // 从文件恢复全部记忆
    val conversation2 = InMemoryConversationMemory()
    val engine2 = buildEngine(llm2, conversation2, memory2, "demo-session-2")

    println("你> 我是谁？我喜欢什么？")
    engine2.execute("我是谁？我喜欢什么？").collect { renderDemo(it) }

    memory2.onSessionComplete(conversation2, "demo-session-2")

    println("\n✅ 演示完成：会话 2 在零工作记忆的前提下，靠自动召回答出了会话 1 学到的信息。")
    println("   记忆文件：$workspace/memory.jsonl（删除后重新演示可回到「失忆」状态）")
}

/** 演示模式的引擎装配（裸引擎 + 记忆全套接线）。 */
private fun buildEngine(
    llm: LlmClient,
    conversation: com.androidguru.agent.core.session.ConversationMemory,
    memory: MemorySystem,
    sessionId: String,
): DefaultAgentEngine {
    val registry = DefaultToolRegistry().also { r ->
        memory.tools.forEach { r.register(it) }
    }
    return DefaultAgentEngine(
        llmClient = llm,
        toolRegistry = registry,
        toolExecutor = DefaultToolExecutor(registry),
        memory = conversation,
        compressor = memory.capturingCompressor(SlidingWindowCompressor()),
        systemContextProvider = memory.contextInjectorFor(conversation),
        sessionId = sessionId,
    )
}

private fun renderDemo(event: AgentEvent) {
    when (event) {
        is AgentEvent.ToolCallStart -> println("🔧 ${event.toolName} ${event.arguments.take(100)}")
        is AgentEvent.ToolCallComplete -> println(if (event.result.ok) "   ✓ ${event.result.content.take(140)}" else "   ✗ ${event.result.content.take(140)}")
        is AgentEvent.Complete -> println("🤖 ${event.summary.take(200)}")
        is AgentEvent.Error -> println("[错误] ${event.message.take(160)}")
        else -> Unit
    }
}

/** 会话 1 的脚本化模型：先调用 memory_save，再文本确认。 */
private class ScriptedLlm(
    vararg rounds: Pair<String, List<ToolCallScript>>,
) : LlmClient {
    private val queue = ArrayDeque(rounds.toList())

    override suspend fun chat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Double?,
        maxTokens: Int?,
        toolChoice: ToolChoiceSpec?,
    ) = LlmResponse(content = "")

    override fun chatStream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Double?,
        maxTokens: Int?,
        toolChoice: ToolChoiceSpec?,
    ): Flow<LlmStreamChunk> {
        val (text, calls) = queue.removeFirstOrNull() ?: ("（脚本耗尽）" to emptyList())
        return flowOf(
            LlmStreamChunk(content = text),
            LlmStreamChunk(
                toolCallDeltas = calls.map { com.androidguru.agent.llm.ToolCall(id = "call_${it.name}", name = it.name, arguments = it.arguments, index = 0) },
                finish = true,
                completeToolCalls = calls.map { com.androidguru.agent.llm.ToolCall(id = "call_${it.name}", name = it.name, arguments = it.arguments, index = 0) },
            ),
        )
    }
}

private data class ToolCallScript(val name: String, val arguments: String)

/** 会话 2 的应答模型：检查「长期记忆」段落是否被注入（确定性验证召回）。 */
private class AnsweringLlm : LlmClient {
    var sawMemoryContext = false

    override suspend fun chat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Double?,
        maxTokens: Int?,
        toolChoice: ToolChoiceSpec?,
    ) = LlmResponse(content = "")

    override fun chatStream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Double?,
        maxTokens: Int?,
        toolChoice: ToolChoiceSpec?,
    ): Flow<LlmStreamChunk> {
        val system = (messages.firstOrNull { it is LlmMessage.System } as? LlmMessage.System)?.content.orEmpty()
        if (system.contains("长期记忆") && system.contains("小明")) {
            sawMemoryContext = true
        }
        val answer = if (sawMemoryContext) {
            "根据长期记忆：你是小明，喜欢 Kotlin 和深色主题（记忆注入验证 ✓）"
        } else {
            "抱歉，我不认识你（记忆注入验证 ✗ —— 未召回）"
        }
        return flowOf(
            LlmStreamChunk(content = answer, finish = true),
        )
    }
}

// ------------------------------------------------------------------
// 交互模式：真实 LLM + 记忆全套
// ------------------------------------------------------------------

private suspend fun runInteractive(workspace: Path) {
    val llmConfig = LlmConfig(
        baseUrl = System.getenv("AGENT_BASE_URL") ?: "https://api.openai.com/v1",
        apiKey = System.getenv("AGENT_API_KEY") ?: error("请设置 AGENT_API_KEY 环境变量（或用 --demo 演示模式）"),
        model = System.getenv("AGENT_MODEL") ?: "gpt-4o-mini",
    )
    val client = OpenAiCompatibleClient(llmConfig)

    val memory = MemorySystem.create(workspace, client)
    val conversation = com.androidguru.agent.core.session.FileConversationMemory(
        workspace.resolve("conversation.jsonl"),
    )
    val registry = DefaultToolRegistry().also { r -> memory.tools.forEach { r.register(it) } }

    // 自动沉淀：钩子注册表必须与引擎共用同一个实例
    val hooks = com.androidguru.agent.tools.hook.HookRegistry()
    memory.installSessionEndHook(
        hooks,
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
        conversation,
        "memory-demo",
    )

    val engine = DefaultAgentEngine(
        llmClient = client,
        toolRegistry = registry,
        toolExecutor = DefaultToolExecutor(registry),
        memory = conversation,
        compressor = memory.capturingCompressor(SlidingWindowCompressor()),
        systemContextProvider = memory.contextInjectorFor(conversation),
        hooks = hooks,
        sessionId = "memory-demo",
    )

    val existing = memory.store.all()
    println("=== 长期记忆 Agent（workspace: $workspace，既有记忆 ${existing.size} 条）===")
    if (existing.isNotEmpty()) {
        existing.take(5).forEach { println("  ${it.renderForModel()}") }
        if (existing.size > 5) println("  …等共 ${existing.size} 条")
    }
    println("输入任何话题（exit 退出；记忆将跨会话存续）\n")

    while (true) {
        print("\n你> ")
        val line = readLine() ?: break
        if (line.isBlank()) continue
        if (line == "exit") break

        engine.execute(line).collect { event ->
            when (event) {
                is AgentEvent.UserInputRequired -> {
                    print("\n${event.prompt}\n你> ")
                    engine.submitUserInput(readLine().orEmpty())
                }

                is AgentEvent.ResponseChunk -> print(event.text)
                is AgentEvent.ToolCallStart -> print("\n🔧 ${event.toolName} ${event.arguments.take(120)}")
                is AgentEvent.ToolCallComplete -> println(if (event.result.ok) " ✓" else " ✗ ${event.result.content.take(120)}")
                is AgentEvent.Error -> println("\n[错误] ${event.message}")
                is AgentEvent.Complete -> println("\n✅ 完成（${event.totalIterations} 轮 / ${event.totalToolCalls} 次工具调用）")
                else -> Unit
            }
        }
    }

    memory.onSessionComplete(conversation, "memory-demo")
    println("再见！本次会话的记忆已沉淀（${memory.store.all().size} 条长期记忆）。")
}
