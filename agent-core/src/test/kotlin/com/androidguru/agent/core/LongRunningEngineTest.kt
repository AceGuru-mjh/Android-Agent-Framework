package com.androidguru.agent.core

import com.androidguru.agent.core.engine.AgentConfig
import com.androidguru.agent.core.engine.AgentEvent
import com.androidguru.agent.core.engine.DefaultAgentEngine
import com.androidguru.agent.core.engine.SystemContextProvider
import com.androidguru.agent.llm.LlmClient
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 长程任务引擎增强的端到端测试：
 * - SystemContextProvider 每轮求值注入；
 * - 循环护栏（LoopDetected + 模型可见建议文本）；
 * - continueExecution 预算续跑（迭代编号衔接 / 记忆保留 / Complete 统计累计）；
 * - FileConversationMemory 崩溃恢复（含压缩 replaceAll 后重载）。
 */
class LongRunningEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ------------------------------------------------------------------
    // 测试脚手架
    // ------------------------------------------------------------------

    /** 脚本化 LLM：每次请求弹出一个预设响应。 */
    private class ScriptedLlm(private val responses: MutableList<List<LlmStreamChunk>>) : LlmClient {
        val requests = mutableListOf<List<LlmMessage>>()

        override suspend fun chat(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Double?,
            maxTokens: Int?,
            toolChoice: ToolChoiceSpec?,
        ): com.androidguru.agent.llm.LlmResponse = throw UnsupportedOperationException()

        override fun chatStream(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Double?,
            maxTokens: Int?,
            toolChoice: ToolChoiceSpec?,
        ): Flow<LlmStreamChunk> {
            requests += messages
            val next = responses.removeFirstOrNull() ?: emptyList()
            return flowOf(*next.toTypedArray())
        }
    }

    /** 恒等工具：永远返回固定文本（用于制造循环）。 */
    private class EchoTool(private val output: String = "pong") : AgentTool {
        override val id = "echo"
        override val name = "echo"
        override val description = "原样返回输入"
        override val parameters = ToolSchema.build {
            string("payload", required = true)
        }

        override suspend fun execute(request: ToolRequest): ToolResult =
            ToolResult.success(output)
    }

    private fun registryWith(vararg tools: AgentTool): DefaultToolRegistry =
        DefaultToolRegistry().also { r -> tools.forEach { r.register(it) } }

    private fun engineWith(
        llm: LlmClient,
        registry: DefaultToolRegistry,
        config: AgentConfig = AgentConfig.DEFAULT,
        contextProvider: SystemContextProvider? = null,
    ) = DefaultAgentEngine(
        llmClient = llm,
        toolRegistry = registry,
        toolExecutor = DefaultToolExecutor(registry),
        config = config,
        systemContextProvider = contextProvider,
    )

    private fun toolCallRound(id: String, name: String, args: String) = listOf(
        LlmStreamChunk(finish = true, completeToolCalls = listOf(ToolCall(id = id, name = name, arguments = args))),
    )

    private fun textRound(text: String) = listOf(
        LlmStreamChunk(content = text),
        LlmStreamChunk(finish = true),
    )

    // ------------------------------------------------------------------
    // SystemContextProvider
    // ------------------------------------------------------------------

    @Test
    fun `动态上下文每轮注入系统提示`() = runTest {
        val llm = ScriptedLlm(mutableListOf(toolCallRound("c1", "echo", """{"payload":"a"}"""), textRound("done")))
        var counter = 0
        val provider = SystemContextProvider {
            counter++
            "当前轮次标记: round-$counter"
        }
        val engine = engineWith(llm, registryWith(EchoTool()), contextProvider = provider)

        engine.execute("go").toList()

        // 两轮请求都应包含注入内容，且第二轮看到 round-2（每轮求值）
        assertTrue(llm.requests[0].any { it is LlmMessage.System && it.content.contains("round-1") })
        assertTrue(llm.requests[1].any { it is LlmMessage.System && it.content.contains("round-2") })
    }

    @Test
    fun `动态上下文提供者异常被隔离不打断任务`() = runTest {
        val llm = ScriptedLlm(mutableListOf(textRound("survived")))
        val engine = engineWith(
            llm,
            registryWith(),
            contextProvider = SystemContextProvider { throw IllegalStateException("provider boom") },
        )

        val events = engine.execute("go").toList()
        assertTrue(events.filterIsInstance<AgentEvent.Complete>().isNotEmpty())
        // 系统提示不应包含异常痕迹
        assertTrue(llm.requests[0].filterIsInstance<LlmMessage.System>().single().content.contains("capable agent"))
    }

    // ------------------------------------------------------------------
    // 循环护栏
    // ------------------------------------------------------------------

    @Test
    fun `相同工具相同参数重复触发 LoopDetected 并注入建议`() = runTest {
        // 3 轮完全相同的调用 → 第 3 次触发护栏
        val llm = ScriptedLlm(
            mutableListOf(
                toolCallRound("c1", "echo", """{"payload":"same"}"""),
                toolCallRound("c2", "echo", """{"payload":"same"}"""),
                toolCallRound("c3", "echo", """{"payload":"same"}"""),
                textRound("ok"),
            ),
        )
        val engine = engineWith(llm, registryWith(EchoTool()))

        val events = engine.execute("loop test").toList()

        val loopEvent = events.filterIsInstance<AgentEvent.LoopDetected>().single()
        assertEquals("echo", loopEvent.toolName)
        assertEquals(3, loopEvent.repeatedCount)

        // 模型可见的工具结果应附带建议文本（第 3 次调用之后的那条 Tool 消息）
        val toolMessages = llm.requests[3].filterIsInstance<LlmMessage.Tool>()
        assertTrue(toolMessages.any { it.content.contains("[loop-guard]") })
    }

    @Test
    fun `参数不同不触发护栏`() = runTest {
        val llm = ScriptedLlm(
            mutableListOf(
                toolCallRound("c1", "echo", """{"payload":"a"}"""),
                toolCallRound("c2", "echo", """{"payload":"b"}"""),
                textRound("ok"),
            ),
        )
        val engine = engineWith(llm, registryWith(EchoTool()))

        val events = engine.execute("no loop").toList()
        assertTrue(events.filterIsInstance<AgentEvent.LoopDetected>().isEmpty())
    }

    @Test
    fun `关闭护栏后不检测`() = runTest {
        val llm = ScriptedLlm(
            mutableListOf(
                toolCallRound("c1", "echo", """{"payload":"same"}"""),
                toolCallRound("c2", "echo", """{"payload":"same"}"""),
                textRound("ok"),
            ),
        )
        val engine = engineWith(
            llm,
            registryWith(EchoTool()),
            config = AgentConfig(loopDetectionEnabled = false),
        )

        val events = engine.execute("disabled").toList()
        assertTrue(events.filterIsInstance<AgentEvent.LoopDetected>().isEmpty())
    }

    // ------------------------------------------------------------------
    // continueExecution 预算续跑
    // ------------------------------------------------------------------

    @Test
    fun `预算耗尽发出 BudgetExhausted 且可续跑衔接迭代编号`() = runTest {
        // 第 1 轮：工具调用（耗尽 maxIterations=1）→ 预算耗尽
        // 续跑：第 2 轮 → 文本收尾
        val llm = ScriptedLlm(
            mutableListOf(
                toolCallRound("c1", "echo", """{"payload":"x"}"""),
                textRound("finally done"),
            ),
        )
        val engine = engineWith(llm, registryWith(EchoTool()), config = AgentConfig(maxIterations = 1))

        val first = engine.execute("long task").toList()

        // 断言：BudgetExhausted + recoverable Error
        val budget = first.filterIsInstance<AgentEvent.BudgetExhausted>().single()
        assertEquals(1, budget.iterationsUsed)
        assertEquals(1, budget.maxIterations)
        assertTrue(first.any { it is AgentEvent.Error && it.recoverable })
        assertFalse(first.any { it is AgentEvent.Complete })

        // 续跑 1 轮：应完成，迭代编号从 2 开始（衔接），Complete 统计累计工具调用
        val second = engine.continueExecution(1).toList()
        val complete = second.filterIsInstance<AgentEvent.Complete>().single()
        assertEquals("finally done", complete.summary)
        assertEquals(2, complete.totalIterations) // 1 + 1 衔接
        assertEquals(1, complete.totalToolCalls)  // 上一段的调用累计进统计

        // 续跑注入了系统续跑说明
        assertTrue(llm.requests[1].any { it is LlmMessage.System && it.content.contains("迭代预算已追加") })
        // 记忆保留：第二轮请求仍含用户原始输入与第一轮工具结果
        assertTrue(llm.requests[1].any { it is LlmMessage.User && it.content == "long task" })
        assertTrue(llm.requests[1].any { it is LlmMessage.Tool && it.toolCallId == "c1" })
    }

    @Test
    fun `没有历史时续跑被拒绝`() = runTest {
        val llm = ScriptedLlm(mutableListOf(textRound("ok")))
        val engine = engineWith(llm, registryWith())

        val events = engine.continueExecution(10).toList()
        assertTrue(events.any { it is AgentEvent.Error && !it.recoverable && it.message.contains("续跑") })
        // 闸门已释放：后续 execute 正常完成
        val ok = engine.execute("hi").toList()
        assertFalse(ok.any { it is AgentEvent.Error })
        assertTrue(ok.filterIsInstance<AgentEvent.Complete>().isNotEmpty())
    }

    // ------------------------------------------------------------------
    // FileConversationMemory
    // ------------------------------------------------------------------

    @Test
    fun `文件记忆写入后重建实例恢复历史`() {
        val file = tmp.newFolder().toPath().resolve("session.jsonl")
        val memory = com.androidguru.agent.core.session.FileConversationMemory(file)

        memory.appendUser("任务：写报告")
        memory.appendAssistant(content = null, toolCalls = listOf(ToolCall(id = "c1", name = "echo", arguments = """{"payload":"a"}""")))
        memory.appendToolResult("c1", "pong")
        memory.appendAssistant("第一步完成")

        // 模拟进程重启：新建实例自动加载
        val reloaded = com.androidguru.agent.core.session.FileConversationMemory(file)
        assertEquals(4, reloaded.snapshot().size)
        assertEquals("任务：写报告", reloaded.snapshot().filterIsInstance<LlmMessage.User>().single().content)
        val assistantToolCall = reloaded.snapshot().filterIsInstance<LlmMessage.Assistant>()
            .first { it.toolCalls.isNotEmpty() }
        assertEquals("c1", assistantToolCall.toolCalls.single().id)
        assertEquals("pong", reloaded.snapshot().filterIsInstance<LlmMessage.Tool>().single().content)
    }

    @Test
    fun `压缩 replaceAll 原子回写且可重载`() {
        val file = tmp.newFolder().toPath().resolve("session.jsonl")
        val memory = com.androidguru.agent.core.session.FileConversationMemory(file)

        repeat(20) { memory.appendUser("msg-$it") }
        memory.replaceAll(
            listOf(
                LlmMessage.System("head"),
                LlmMessage.User("压缩后最近一轮"),
            ),
        )

        val reloaded = com.androidguru.agent.core.session.FileConversationMemory(file)
        assertEquals(2, reloaded.snapshot().size)
        assertTrue(reloaded.snapshot().any { it is LlmMessage.System && it.content == "head" })
    }

    @Test
    fun `损坏行被跳过不报废会话`() {
        val dir = tmp.newFolder().toPath()
        val file = dir.resolve("session.jsonl")
        // 手工写入：1 条好 + 1 条坏 + 1 条好
        java.nio.file.Files.writeString(
            file,
            """
            {"kind":"user","content":"good-1"}
            {this is not valid json
            {"kind":"user","content":"good-2"}
            """.trimIndent(),
        )

        val memory = com.androidguru.agent.core.session.FileConversationMemory(file)
        assertEquals(2, memory.snapshot().size)
        assertEquals(1, memory.skippedCorruptLines)
    }
}
