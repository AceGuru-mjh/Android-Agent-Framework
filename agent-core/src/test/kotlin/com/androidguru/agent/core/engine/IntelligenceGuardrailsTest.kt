package com.androidguru.agent.core.engine

import com.androidguru.agent.llm.LlmClient
import com.androidguru.agent.llm.LlmMessage
import com.androidguru.agent.llm.LlmResponse
import com.androidguru.agent.llm.LlmStreamChunk
import com.androidguru.agent.llm.ToolCall
import com.androidguru.agent.llm.ToolChoiceSpec
import com.androidguru.agent.llm.ToolDefinition
import com.androidguru.agent.tools.AgentTool
import com.androidguru.agent.tools.DefaultToolExecutor
import com.androidguru.agent.tools.DefaultToolRegistry
import com.androidguru.agent.tools.ToolErrorCode
import com.androidguru.agent.tools.ToolRequest
import com.androidguru.agent.tools.ToolResult
import com.androidguru.agent.tools.ToolSchema
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Agent 智能护栏端到端测试（PR #26）：
 * - 连续失败 → [AgentEvent.ReflectionTriggered] + 模型可见修正注入；
 * - 交替循环（A→B→A→B，每次调用本身都成功）→ LoopDetected(cycle) + 反思；
 * - 全失败轮次 → [AgentEvent.DriftSuspected] 停滞告警。
 */
class IntelligenceGuardrailsTest {

    /** 脚本化 LLM：chatStream 弹出脚本；chat（反思/抽查用）返回固定分析。 */
    private class ScriptedLlm(
        private val rounds: MutableList<List<LlmStreamChunk>>,
        private val analysis: String = "诊断：参数错误。建议：换用 list 工具确认名称后重试。",
    ) : LlmClient {
        val requests = mutableListOf<List<LlmMessage>>()
        val analyses = mutableListOf<List<LlmMessage>>()

        override suspend fun chat(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Double?,
            maxTokens: Int?,
            toolChoice: ToolChoiceSpec?,
        ): LlmResponse {
            analyses += messages
            return LlmResponse(content = analysis)
        }

        override fun chatStream(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Double?,
            maxTokens: Int?,
            toolChoice: ToolChoiceSpec?,
        ): Flow<LlmStreamChunk> {
            requests += messages
            return flowOf(*(rounds.removeFirstOrNull() ?: emptyList()).toTypedArray())
        }
    }

    /** 永远失败的工具（连续失败源）。 */
    private class FailingTool : AgentTool {
        override val id = "broken"
        override val name = "broken"
        override val description = "永远失败"
        override val parameters = ToolSchema.build { string("p", required = true) }

        override suspend fun execute(request: ToolRequest): ToolResult =
            ToolResult.failure("目标不存在", ToolErrorCode.NOT_FOUND)
    }

    /** 成功的恒等工具（制造交替循环：两个不同名工具轮换）。 */
    private class OkTool(private val toolName: String, private val output: String = "ok") : AgentTool {
        override val id = toolName
        override val name = toolName
        override val description = "成功工具"
        override val parameters = ToolSchema.build { string("p", required = true) }

        override suspend fun execute(request: ToolRequest): ToolResult = ToolResult.success(output)
    }

    private fun toolCallRound(id: String, name: String, args: String) = listOf(
        LlmStreamChunk(finish = true, completeToolCalls = listOf(ToolCall(id = id, name = name, arguments = args))),
    )

    private fun textRound(text: String) = listOf(
        LlmStreamChunk(content = text),
        LlmStreamChunk(finish = true),
    )

    @Test
    fun `连续失败触发反思并注入修正策略`() = runTest {
        val llm = ScriptedLlm(
            mutableListOf(
                toolCallRound("c1", "broken", """{"p":"a"}"""),
                toolCallRound("c2", "broken", """{"p":"b"}"""),
                toolCallRound("c3", "broken", """{"p":"c"}"""),
                textRound("按修正策略换方法了"),
            ),
        )
        val registry = DefaultToolRegistry().also { it.register(FailingTool()) }
        val engine = DefaultAgentEngine(
            llmClient = llm,
            toolRegistry = registry,
            toolExecutor = DefaultToolExecutor(registry),
            config = AgentConfig(reflectionFailureThreshold = 3, driftCheckInterval = 0, driftDetectionEnabled = false),
        )

        val events = engine.execute("执行任务").toList()

        // 反思事件触发，且 lesson 来自 LLM 分析
        val reflection = events.filterIsInstance<AgentEvent.ReflectionTriggered>()
        assertTrue(reflection.isNotEmpty())
        assertTrue(reflection.first().byLlm)
        assertTrue(reflection.first().triggerReason.contains("连续"))
        assertTrue(reflection.first().lesson.contains("list"))

        // 修正策略注入为系统消息（第 4 轮请求可见）
        assertTrue(llm.requests[3].any { it is LlmMessage.System && it.content.contains("[reflection]") })
    }

    @Test
    fun `交替循环触发 cycle 告警与反思`() = runTest {
        val llm = ScriptedLlm(
            mutableListOf(
                toolCallRound("c1", "alpha", """{"p":"1"}"""),
                toolCallRound("c2", "beta", """{"p":"2"}"""),
                toolCallRound("c3", "alpha", """{"p":"1"}"""),
                toolCallRound("c4", "beta", """{"p":"2"}"""),
                textRound("跳出循环了"),
            ),
            analysis = "你在两个操作间来回切换。建议：等待界面就绪再操作。",
        )
        val registry = DefaultToolRegistry().also {
            it.register(OkTool("alpha"))
            it.register(OkTool("beta"))
        }
        val engine = DefaultAgentEngine(
            llmClient = llm,
            toolRegistry = registry,
            toolExecutor = DefaultToolExecutor(registry),
            config = AgentConfig(driftDetectionEnabled = false),
        )

        val events = engine.execute("交替任务").toList()

        // cycle 告警（区别于单签名重复：toolName = cycle）
        val loopEvents = events.filterIsInstance<AgentEvent.LoopDetected>()
        assertTrue(loopEvents.any { it.toolName == "cycle" && it.arguments.contains("alpha") })

        // 循环触发的反思
        val reflection = events.filterIsInstance<AgentEvent.ReflectionTriggered>()
        assertTrue(reflection.isNotEmpty())
        assertTrue(reflection.first().triggerReason.contains("循环护栏"))

        // 交替循环建议注入模型
        assertTrue(llm.requests[4].any { it is LlmMessage.System && it.content.contains("交替循环") })
    }

    @Test
    fun `全失败轮次触发跑偏停滞告警`() = runTest {
        val llm = ScriptedLlm(
            mutableListOf(
                toolCallRound("c1", "broken", """{"p":"1"}"""),
                toolCallRound("c2", "broken", """{"p":"2"}"""),
                toolCallRound("c3", "broken", """{"p":"3"}"""),
                textRound("重新评估后收尾"),
            ),
        )
        val registry = DefaultToolRegistry().also { it.register(FailingTool()) }
        val engine = DefaultAgentEngine(
            llmClient = llm,
            toolRegistry = registry,
            toolExecutor = DefaultToolExecutor(registry),
            config = AgentConfig(
                reflectionEnabled = false, // 隔离：只验证跑偏
                driftStagnationIterations = 2,
                driftCheckInterval = 0,
            ),
        )

        val events = engine.execute("停滞任务").toList()

        val drift = events.filterIsInstance<AgentEvent.DriftSuspected>()
        assertTrue(drift.isNotEmpty())
        assertTrue(drift.first().advisory.contains("没有"))
        // 拉回建议注入模型上下文
        assertTrue(llm.requests[3].any { it is LlmMessage.System && it.content.contains("[drift-guard]") })
    }

    @Test
    fun `关闭全部护栏保持零开销`() = runTest {
        val llm = ScriptedLlm(
            mutableListOf(
                toolCallRound("c1", "broken", """{"p":"1"}"""),
                toolCallRound("c2", "broken", """{"p":"2"}"""),
                toolCallRound("c3", "broken", """{"p":"3"}"""),
                textRound("静默完成"),
            ),
        )
        val registry = DefaultToolRegistry().also { it.register(FailingTool()) }
        val engine = DefaultAgentEngine(
            llmClient = llm,
            toolRegistry = registry,
            toolExecutor = DefaultToolExecutor(registry),
            config = AgentConfig(
                reflectionEnabled = false,
                driftDetectionEnabled = false,
                loopDetectionEnabled = false,
            ),
        )

        val events = engine.execute("无护栏任务").toList()

        assertTrue(events.filterIsInstance<AgentEvent.ReflectionTriggered>().isEmpty())
        assertTrue(events.filterIsInstance<AgentEvent.DriftSuspected>().isEmpty())
        assertTrue(events.filterIsInstance<AgentEvent.LoopDetected>().isEmpty())
        assertTrue(events.filterIsInstance<AgentEvent.Complete>().isNotEmpty())
    }
}
