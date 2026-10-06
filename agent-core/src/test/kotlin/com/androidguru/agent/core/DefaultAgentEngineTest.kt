package com.androidguru.agent.core

import com.androidguru.agent.core.engine.AgentConfig
import com.androidguru.agent.core.engine.AgentEvent
import com.androidguru.agent.core.engine.DefaultAgentEngine
import com.androidguru.agent.core.engine.UserInput
import com.androidguru.agent.llm.LlmClient
import com.androidguru.agent.llm.LlmException
import com.androidguru.agent.llm.LlmMessage
import com.androidguru.agent.llm.LlmStreamChunk
import com.androidguru.agent.llm.ToolCall
import com.androidguru.agent.llm.ToolChoiceSpec
import com.androidguru.agent.llm.ToolDefinition
import com.androidguru.agent.tools.AgentTool
import com.androidguru.agent.tools.DefaultToolExecutor
import com.androidguru.agent.tools.DefaultToolRegistry
import com.androidguru.agent.tools.ToolRequest
import com.androidguru.agent.tools.ToolResult
import com.androidguru.agent.tools.ToolSchema
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 脚本化 Fake：按脚本依次返回响应；记录收到的消息与工具清单。
 */
private class ScriptedLlmClient(
    vararg scripts: List<LlmStreamChunk>,
) : LlmClient {
    private val queue = ArrayDeque(scripts.toList())
    val requests = mutableListOf<Pair<List<LlmMessage>, List<ToolDefinition>>>()

    override suspend fun chat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Double?,
        maxTokens: Int?,
        toolChoice: ToolChoiceSpec?,
    ) = throw UnsupportedOperationException()

    override fun chatStream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Double?,
        maxTokens: Int?,
        toolChoice: ToolChoiceSpec?,
    ): Flow<LlmStreamChunk> {
        requests += messages to tools
        val script = queue.removeFirstOrNull() ?: throw LlmException.EmptyResponse
        return flowOf(*script.toTypedArray())
    }
}

private class AddTool : AgentTool {
    override val id = "add"
    override val name = "add"
    override val description = "两数相加"
    override val parameters = ToolSchema.build {
        integer("a", required = true)
        integer("b", required = true)
    }
    var lastArgs = ""
    override suspend fun execute(request: ToolRequest): ToolResult {
        lastArgs = request.arguments
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(request.arguments) as kotlinx.serialization.json.JsonObject
        val a = (obj["a"] as kotlinx.serialization.json.JsonPrimitive).content.toInt()
        val b = (obj["b"] as kotlinx.serialization.json.JsonPrimitive).content.toInt()
        return ToolResult.success((a + b).toString())
    }
}

private suspend fun collectEvents(flow: Flow<AgentEvent>): List<AgentEvent> {
    val events = mutableListOf<AgentEvent>()
    flow.collect { events += it }
    return events
}

class DefaultAgentEngineTest {

    @Test
    fun `纯文本回复走一轮并 Complete`() = runTest {
        val llm = ScriptedLlmClient(
            listOf(
                LlmStreamChunk(content = "你好"),
                LlmStreamChunk(content = "，世界"),
                LlmStreamChunk(finish = true),
            ),
        )
        val registry = DefaultToolRegistry()
        val engine = DefaultAgentEngine(
            llmClient = llm,
            toolRegistry = registry,
            toolExecutor = DefaultToolExecutor(registry),
        )

        val events = collectEvents(engine.execute("打个招呼"))

        val chunks = events.filterIsInstance<AgentEvent.ResponseChunk>()
        assertEquals(2, chunks.size)
        assertEquals("你好，世界", chunks.joinToString("") { it.text })
        val complete = events.filterIsInstance<AgentEvent.Complete>().single()
        assertEquals(1, complete.totalIterations)
        assertEquals(0, complete.totalToolCalls)
    }

    @Test
    fun `工具调用循环 - 调用后回填结果并产出最终答案`() = runTest {
        val llm = ScriptedLlmClient(
            // 第一轮：要求调用 add(2, 3)
            listOf(
                LlmStreamChunk(finish = true, completeToolCalls = listOf(
                    ToolCall(id = "c1", name = "add", arguments = """{"a":2,"b":3}"""),
                )),
            ),
            // 第二轮：基于工具结果回复
            listOf(
                LlmStreamChunk(content = "答案是 5"),
                LlmStreamChunk(finish = true),
            ),
        )
        val registry = DefaultToolRegistry()
        val add = AddTool()
        registry.register(add)
        val engine = DefaultAgentEngine(
            llmClient = llm,
            toolRegistry = registry,
            toolExecutor = DefaultToolExecutor(registry),
        )

        val events = collectEvents(engine.execute("2+3=?"))

        assertEquals(1, events.count { it is AgentEvent.ToolCallStart })
        val toolComplete = events.filterIsInstance<AgentEvent.ToolCallComplete>().single()
        assertTrue(toolComplete.result.ok)
        assertEquals("5", toolComplete.result.content)
        assertTrue(events.filterIsInstance<AgentEvent.Complete>().single().summary.contains("5"))
        // 第二轮请求应包含工具结果消息
        val secondMessages = llm.requests[1].first
        assertTrue(secondMessages.any { it is LlmMessage.Tool && it.content == "5" })
    }

    @Test
    fun `瞬时 LLM 错误退避重试后恢复`() = runTest {
        // 第 1 次：网络错误；第 2 次：正常回复
        val llm = object : LlmClient by ScriptedLlmClient() {
            private var failed = false
            override fun chatStream(
                messages: List<LlmMessage>,
                tools: List<ToolDefinition>,
                temperature: Double?,
                maxTokens: Int?,
                toolChoice: ToolChoiceSpec?,
            ): Flow<LlmStreamChunk> {
                return if (!failed) {
                    failed = true
                    throw LlmException.Network(java.io.IOException("conn reset"))
                } else {
                    flowOf(LlmStreamChunk(content = "ok"), LlmStreamChunk(finish = true))
                }
            }
        }
        val registry = DefaultToolRegistry()
        val engine = DefaultAgentEngine(
            llmClient = llm,
            toolRegistry = registry,
            toolExecutor = DefaultToolExecutor(registry),
            config = AgentConfig(llmRetryBaseDelayMs = 1),
        )

        val events = collectEvents(engine.execute("hi"))

        assertTrue(events.any { it is AgentEvent.LlmRetryScheduled })
        assertEquals("ok", events.filterIsInstance<AgentEvent.Complete>().single().summary)
    }

    @Test
    fun `ask_user 工具触发交互并回填用户答复`() = runTest {
        val llm = ScriptedLlmClient(
            listOf(
                LlmStreamChunk(finish = true, completeToolCalls = listOf(
                    ToolCall(id = "c1", name = "ask_user", arguments = """{"prompt":"叫什么名字？"}"""),
                )),
            ),
            listOf(
                LlmStreamChunk(content = "你好，小明"),
                LlmStreamChunk(finish = true),
            ),
        )
        val registry = DefaultToolRegistry()
        val engine = DefaultAgentEngine(
            llmClient = llm,
            toolRegistry = registry,
            toolExecutor = DefaultToolExecutor(registry),
            config = AgentConfig(askUserTimeoutMs = 5000),
        )

        var askedPrompt: String? = null
        var submitted = false
        val events = mutableListOf<AgentEvent>()
        engine.execute("hi").collect { event ->
            events += event
            if (event is AgentEvent.UserInputRequired) {
                askedPrompt = event.prompt
                submitted = engine.submitUserInput("小明")
            }
        }

        assertTrue(submitted)
        assertEquals("叫什么名字？", askedPrompt)
        // 用户回答应作为工具结果回填
        assertTrue(llm.requests[1].first.any { it is LlmMessage.Tool && it.content == "小明" })
        assertEquals("你好，小明", events.filterIsInstance<AgentEvent.Complete>().single().summary)
    }

    @Test
    fun `并发 execute 被互斥拒绝`() = runTest {
        // 首个任务被闸门挡住，保持运行中状态
        val gate = CompletableDeferred<Unit>()
        val llm = object : LlmClient {
            override suspend fun chat(
                messages: List<LlmMessage>,
                tools: List<ToolDefinition>,
                temperature: Double?,
                maxTokens: Int?,
                toolChoice: ToolChoiceSpec?,
            ) = throw UnsupportedOperationException()

            override fun chatStream(
                messages: List<LlmMessage>,
                tools: List<ToolDefinition>,
                temperature: Double?,
                maxTokens: Int?,
                toolChoice: ToolChoiceSpec?,
            ): Flow<LlmStreamChunk> = kotlinx.coroutines.flow.flow {
                gate.await()
                emit(LlmStreamChunk(content = "done"))
                emit(LlmStreamChunk(finish = true))
            }
        }
        val registry = DefaultToolRegistry()
        val engine = DefaultAgentEngine(llm, registry, DefaultToolExecutor(registry))

        var firstEvents: List<AgentEvent>? = null
        val job = launch {
            firstEvents = collectEvents(engine.execute("first"))
        }
        runCurrent()

        // 第二次执行应被拒绝（首个任务仍在运行）
        val secondEvents = collectEvents(engine.execute("second"))
        assertTrue(secondEvents.any { it is AgentEvent.Error && !it.recoverable })
        assertTrue(engine.isRunning)

        gate.complete(Unit)
        job.join()
        assertEquals(false, engine.isRunning)
        assertTrue(firstEvents!!.any { it is AgentEvent.Complete })
    }

    @Test
    fun `悬空 tool-call 被修补`() {
        val registry = DefaultToolRegistry()
        val engine = DefaultAgentEngine(
            llmClient = ScriptedLlmClient(),
            toolRegistry = registry,
            toolExecutor = DefaultToolExecutor(registry),
        )
        val repaired = engine.repairDanglingToolCalls(
            listOf(
                LlmMessage.User("hi"),
                LlmMessage.Assistant(toolCalls = listOf(ToolCall(id = "c1", name = "x", arguments = "{}"))),
                LlmMessage.User("继续"),
            ),
        )
        val toolMsgs = repaired.filterIsInstance<LlmMessage.Tool>()
        assertEquals(1, toolMsgs.size)
        assertEquals("c1", toolMsgs[0].toolCallId)
    }
}
