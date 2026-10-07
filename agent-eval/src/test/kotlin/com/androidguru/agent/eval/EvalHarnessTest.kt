package com.androidguru.agent.eval

import com.androidguru.agent.core.engine.AgentConfig
import com.androidguru.agent.core.engine.AgentEngine
import com.androidguru.agent.core.engine.DefaultAgentEngine
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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 评估 harness 端到端测试：真引擎 + 脚本化 LLM + 假移动工具 ——
 * 跑通「执行 → 录轨迹 → 判分 → 报告」全链路（无需 API Key、无需真机）。
 */
class EvalHarnessTest {

    /** 脚本化 LLM：按预设轮次返回。 */
    private class ScriptedLlm(private val rounds: List<List<LlmStreamChunk>>) : LlmClient {
        private var cursor = 0

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
            val round = rounds.getOrNull(cursor++) ?: emptyList()
            return flowOf(*round.toTypedArray())
        }
    }

    /** 假移动工具：永远成功（按词表实现）。 */
    private class FakeMobileTool(override val name: String) : AgentTool {
        override val id = name
        override val description = "假工具 $name"
        override val parameters = ToolSchema.build { string("x", required = false) }

        override suspend fun execute(request: ToolRequest): ToolResult =
            ToolResult.success("done: $name")
    }

    private fun toolCallRound(id: String, name: String, args: String) = listOf(
        LlmStreamChunk(finish = true, completeToolCalls = listOf(ToolCall(id = id, name = name, arguments = args))),
    )

    private fun textRound(text: String) = listOf(
        LlmStreamChunk(content = text),
        LlmStreamChunk(finish = true),
    )

    /** 按任务脚本组装引擎（词表工具全注册，剧本随任务）。 */
    private fun engineFor(script: List<List<LlmStreamChunk>>): AgentEngine {
        val registry = DefaultToolRegistry().also {
            listOf("set_alarm", "send_sms", "weather", "battery_status", "list_files", "set_wifi").forEach { name ->
                it.register(FakeMobileTool(name))
            }
        }
        return DefaultAgentEngine(
            llmClient = ScriptedLlm(script),
            toolRegistry = registry,
            toolExecutor = DefaultToolExecutor(registry),
            config = AgentConfig(driftDetectionEnabled = false), // 评估确定性隔离
        )
    }

    @Test
    fun `成功路径全链路`() = runTest {
        val task = EvalTask(
            id = "ok-task",
            name = "设闹钟",
            category = "系统设置",
            instruction = "设 7:30 闹钟",
            criteria = SuccessCriteria(
                requiredCalls = listOf(
                    CallMatcher(tool = "set_alarm", argChecks = listOf(ArgCheck("time", MatchOp.CONTAINS, "7:30"))),
                ),
                finalTextPatterns = listOf(Regex("完成|好了")),
            ),
        )
        val harness = EvalHarness(
            engineFactory = { engineFor(listOf(toolCallRound("c1", "set_alarm", """{"time":"07:30"}"""), textRound("闹钟设好了"))) },
            tasks = listOf(task),
        )

        val report = harness.run()

        assertEquals(1, report.total)
        assertEquals(1, report.passedCount)
        assertEquals(100, report.successRate)
        val result = report.results.single()
        assertEquals(1, result.trace.totalToolCalls)
        assertTrue(result.trace.completed)
        assertTrue(report.renderText().contains("成功率: 100%"))
    }

    @Test
    fun `失败路径含失败分类与明细`() = runTest {
        val task = EvalTask(
            id = "bad-task",
            name = "设闹钟（模型犯错）",
            category = "系统设置",
            instruction = "设 7:30 闹钟",
            criteria = SuccessCriteria(
                requiredCalls = listOf(
                    CallMatcher(tool = "set_alarm", argChecks = listOf(ArgCheck("time", MatchOp.CONTAINS, "7:30"))),
                ),
                maxIterations = 8,
                finalTextPatterns = listOf(Regex("完成")),
            ),
        )
        // 模型把时间设错了，且收尾没说「完成」
        val harness = EvalHarness(
            engineFactory = { engineFor(listOf(toolCallRound("c1", "set_alarm", """{"time":"8:00"}"""), textRound("弄好了"))) },
            tasks = listOf(task),
        )

        val report = harness.run()

        assertEquals(0, report.passedCount)
        val failures = report.results.single().result.failures
        assertTrue(failures.any { it.contains("缺少必做调用") })
        assertTrue(failures.any { it.contains("未命中要求的文本模式") })
        // 失败分类可定位
        assertTrue(report.failureTaxonomy.containsKey("缺少必做调用"))
        assertTrue(report.renderText().contains("失败分类"))
    }

    @Test
    fun `引擎异常被隔离不报废整场`() = runTest {
        val good = EvalTask(
            id = "good",
            name = "正常任务",
            category = "系统设置",
            instruction = "ok",
            criteria = SuccessCriteria(finalTextPatterns = listOf(Regex("."))),
        )
        val bad = EvalTask(
            id = "bad",
            name = "会炸的任务",
            category = "系统设置",
            instruction = "boom",
            criteria = SuccessCriteria(),
        )
        val harness = EvalHarness(
            engineFactory = { t ->
                if (t.id == "bad") error("引擎组装失败")
                else engineFor(listOf(textRound("done")))
            },
            tasks = listOf(good, bad),
        )

        val report = harness.run()

        assertEquals(2, report.total)
        assertEquals(1, report.passedCount)
        assertEquals(50, report.successRate)
        val badResult = report.results.first { it.task.id == "bad" }
        assertTrue(badResult.error!!.contains("引擎组装失败"))
    }

    @Test
    fun `按类别专项评估`() = runTest {
        val taskA = EvalTask(
            id = "a", name = "a", category = "通讯", instruction = "a",
            criteria = SuccessCriteria(finalTextPatterns = listOf(Regex("."))),
        )
        val taskB = EvalTask(
            id = "b", name = "b", category = "系统设置", instruction = "b",
            criteria = SuccessCriteria(),
        )
        val harness = EvalHarness(
            engineFactory = { engineFor(listOf(textRound("ok"))) },
            tasks = listOf(taskA, taskB),
        )

        val report = harness.runCategory("通讯")
        assertEquals(listOf("a"), report.results.map { it.task.id })
    }
}
