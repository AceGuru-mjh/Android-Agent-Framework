package com.androidguru.agent.tasks

import com.androidguru.agent.core.engine.AgentConfig
import com.androidguru.agent.core.engine.AgentEvent
import com.androidguru.agent.llm.LlmClient
import com.androidguru.agent.llm.LlmMessage
import com.androidguru.agent.llm.LlmResponse
import com.androidguru.agent.llm.LlmStreamChunk
import com.androidguru.agent.llm.ToolCall
import com.androidguru.agent.llm.ToolChoiceSpec
import com.androidguru.agent.llm.ToolDefinition
import com.androidguru.agent.tasks.engine.LongTaskAgent
import com.androidguru.agent.tasks.plan.InMemoryTaskPlanStore
import com.androidguru.agent.tasks.plan.TaskStatus
import com.androidguru.agent.tasks.tools.TaskPlanTool
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
import java.nio.file.Path

/**
 * task_plan 工具 + LongTaskAgent 装配门面的端到端测试。
 */
class LongTaskAgentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 脚本化 LLM（记录请求供断言）。 */
    private class ScriptedLlm(private val responses: MutableList<List<LlmStreamChunk>>) : LlmClient {
        val requests = mutableListOf<Pair<List<LlmMessage>, List<ToolDefinition>>>()

        override suspend fun chat(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Double?,
            maxTokens: Int?,
            toolChoice: ToolChoiceSpec?,
        ): LlmResponse = throw UnsupportedOperationException()

        override fun chatStream(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Double?,
            maxTokens: Int?,
            toolChoice: ToolChoiceSpec?,
        ): Flow<LlmStreamChunk> {
            requests += messages to tools
            val next = responses.removeFirstOrNull() ?: emptyList()
            return flowOf(*next.toTypedArray())
        }
    }

    private fun toolRound(vararg calls: ToolCall) = listOf(
        LlmStreamChunk(finish = true, completeToolCalls = calls.toList()),
    )

    private fun textRound(text: String) = listOf(
        LlmStreamChunk(content = text),
        LlmStreamChunk(finish = true),
    )

    // ------------------------------------------------------------------
    // TaskPlanTool（经执行管线）
    // ------------------------------------------------------------------

    @Test
    fun `工具经管线 rewrite 与 patch 闭环`() = runTest {
        val manager = com.androidguru.agent.tasks.plan.TaskPlanManager(InMemoryTaskPlanStore(), "s1")
        val tool = TaskPlanTool(manager)
        val registry = DefaultToolRegistry().also { it.register(tool) }
        val executor = DefaultToolExecutor(registry)

        // rewrite
        val rewrite = executor.execute(
            tool.id,
            """
            {"action":"rewrite","goal":"发布 v2","tasks":[
              {"title":"写变更日志"},
              {"title":"跑回归测试"}
            ]}
            """.trimIndent(),
        )
        assertTrue(rewrite.ok)
        assertEquals(2, manager.plan()!!.tasks.size)

        // patch：完成第一项 + 插入新步骤
        val patch = executor.execute(
            tool.id,
            """
            {"action":"patch","ops":[
              {"op":"update_status","id":"t1","status":"done","notes":"changelog 已写"},
              {"op":"insert","after_id":"t1","title":"代码评审"}
            ]}
            """.trimIndent(),
        )
        assertTrue(patch.ok)
        val plan = manager.plan()!!
        assertEquals(3, plan.tasks.size)
        assertEquals(TaskStatus.DONE, plan.tasks[0].status)
        assertEquals(1, manager.progress()!!.done)

        // 工具结果应携带渲染计划与机器可读进度
        assertTrue(patch.content.contains("[x] t1"))
        assertTrue(patch.data!!.contains("\"done\":1"))
    }

    @Test
    fun `未知 action 与非法计划操作折叠为失败结果`() = runTest {
        val manager = com.androidguru.agent.tasks.plan.TaskPlanManager(InMemoryTaskPlanStore(), "s1")
        val registry = DefaultToolRegistry().also { it.register(TaskPlanTool(manager)) }
        val executor = DefaultToolExecutor(registry)

        val badAction = executor.execute(TaskPlanTool.ID, """{"action":"fly"}""")
        assertFalse(badAction.ok)

        val badOp = executor.execute(
            TaskPlanTool.ID,
            """{"action":"patch","ops":[{"op":"update_status","id":"t999","status":"done"}]}""",
        )
        // 尚无计划 → PlanOpException → VALIDATION 失败
        assertFalse(badOp.ok)
        assertTrue(badOp.content.contains("尚无计划"))
    }

    // ------------------------------------------------------------------
    // LongTaskAgent 端到端
    // ------------------------------------------------------------------

    @Test
    fun `模型经 task_plan 建计划且计划状态每轮注入系统上下文`() = runTest {
        val llm = ScriptedLlm(
            mutableListOf(
                // 第 1 轮：模型建计划
                toolRound(
                    ToolCall(
                        id = "c1",
                        name = "task_plan",
                        arguments = """{"action":"rewrite","goal":"整理季度数据","tasks":[{"title":"抽取数据"},{"title":"清洗"},{"title":"出图"}]}""",
                    ),
                ),
                // 第 2 轮：基于工具结果收尾
                textRound("计划已建立，开始执行。"),
            ),
        )
        val agent = LongTaskAgent(
            llmClient = llm,
            sessionId = "e2e-1",
            config = AgentConfig(maxIterations = 5),
        )

        val events = agent.execute("整理季度数据并出报告").toList()

        assertTrue(events.filterIsInstance<AgentEvent.Complete>().isNotEmpty())
        // 注册表内 task_plan 对模型可见
        assertTrue(llm.requests[0].second.any { it.name == "task_plan" })
        // 计划已建立
        assertEquals(3, agent.plan()!!.tasks.size)
        // 第 2 轮请求的系统提示包含计划状态块（每轮注入；buildMessages 生成的系统提示固定在首位）
        val system2 = llm.requests[1].first.filterIsInstance<LlmMessage.System>().first()
        assertTrue(system2.content.contains("长程任务计划"))
        assertTrue(system2.content.contains("整理季度数据"))
        // 工具结果回填为渲染后的计划
        assertTrue(llm.requests[1].first.filterIsInstance<LlmMessage.Tool>().single().content.contains("[ ] t1"))
    }

    @Test
    fun `预算耗尽后 continueExecution 续跑并保留计划`() = runTest {
        val llm = ScriptedLlm(
            mutableListOf(
                // 第 1 轮：建计划（预算 1 轮立即耗尽）
                toolRound(
                    ToolCall(
                        id = "c1",
                        name = "task_plan",
                        arguments = """{"action":"rewrite","goal":"多步任务","tasks":[{"title":"步骤一"},{"title":"步骤二"}]}""",
                    ),
                ),
                // 续跑第 2 轮：完成步骤一并收尾
                textRound("步骤一已完成（演示收尾）。"),
            ),
        )
        val agent = LongTaskAgent(
            llmClient = llm,
            sessionId = "e2e-2",
            config = AgentConfig(maxIterations = 1),
        )

        val first = agent.execute("多步任务").toList()
        assertTrue(first.filterIsInstance<AgentEvent.BudgetExhausted>().isNotEmpty())
        assertEquals(2, agent.plan()!!.tasks.size)

        val second = agent.continueExecution(1).toList()
        assertTrue(second.filterIsInstance<AgentEvent.Complete>().isNotEmpty())
        // 续跑轮的系统提示仍含计划状态（首位为 buildMessages 生成的系统提示）
        val system = llm.requests[1].first.filterIsInstance<LlmMessage.System>().first()
        assertTrue(system.content.contains("多步任务"))
    }

    @Test
    fun `workspace 持久化跨实例恢复计划与会话`() = runTest {
        val dir: Path = tmp.newFolder().toPath()
        val llmA = ScriptedLlm(
            mutableListOf(
                toolRound(
                    ToolCall(
                        id = "c1",
                        name = "task_plan",
                        arguments = """{"action":"rewrite","goal":"可恢复任务","tasks":[{"title":"a"},{"title":"b"}]}""",
                    ),
                ),
                textRound("第一程结束。"),
            ),
        )
        val agentA = LongTaskAgent(llmClient = llmA, sessionId = "resume-1", workspace = dir)
        agentA.execute("可恢复任务").toList()
        assertEquals(2, agentA.plan()!!.tasks.size)

        // 模拟进程重启：相同 sessionId + workspace 重建
        val llmB = ScriptedLlm(mutableListOf(textRound("续跑完成。")))
        val agentB = LongTaskAgent(llmClient = llmB, sessionId = "resume-1", workspace = dir)
        assertEquals(2, agentB.plan()!!.tasks.size)          // 计划恢复
        assertTrue(agentB.engineMemoryMessages() > 0)         // 会话记忆恢复

        val events = agentB.continueExecution(5).toList()
        assertTrue(events.filterIsInstance<AgentEvent.Complete>().isNotEmpty())
    }

    @Test
    fun `宿主业务工具与 task_plan 共存`() = runTest {
        val llm = ScriptedLlm(
            mutableListOf(
                toolRound(
                    ToolCall(id = "c1", name = "calc", arguments = """{"a":2,"b":40}"""),
                ),
                textRound("42"),
            ),
        )
        val agent = LongTaskAgent(
            llmClient = llm,
            sessionId = "e2e-3",
            extraTools = listOf(CalcTool()),
        )
        val events = agent.execute("2+40=?").toList()
        assertEquals("42", events.filterIsInstance<AgentEvent.Complete>().single().summary)
    }

    /** 简单计算工具（宿主业务工具替身）。 */
    private class CalcTool : AgentTool {
        override val id = "calc"
        override val name = "calc"
        override val description = "两数相加"
        override val parameters = ToolSchema.build {
            integer("a", required = true)
            integer("b", required = true)
        }

        override suspend fun execute(request: ToolRequest): ToolResult {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(request.arguments) as kotlinx.serialization.json.JsonObject
            val a = (obj["a"] as kotlinx.serialization.json.JsonPrimitive).content.toInt()
            val b = (obj["b"] as kotlinx.serialization.json.JsonPrimitive).content.toInt()
            return ToolResult.success((a + b).toString())
        }
    }
}
