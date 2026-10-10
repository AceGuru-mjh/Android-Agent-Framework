package com.androidguru.agent.tasks.plan

import com.androidguru.agent.llm.LlmClient
import com.androidguru.agent.llm.LlmMessage
import com.androidguru.agent.llm.LlmResponse
import com.androidguru.agent.llm.LlmStreamChunk
import com.androidguru.agent.llm.ToolChoiceSpec
import com.androidguru.agent.llm.ToolDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务拆解器测试（PR #26）：
 * - LLM 输出解析（fenced / 带前后废话 / 宽容字段）；
 * - 两级计划结构 + 验收标准折叠；
 * - 解析失败回退单阶段计划。
 */
class TaskDecomposerTest {

    private class ScriptedLlm(private val responses: ArrayDeque<String>) : LlmClient {
        val requests = mutableListOf<List<LlmMessage>>()

        override suspend fun chat(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Double?,
            maxTokens: Int?,
            toolChoice: ToolChoiceSpec?,
        ): LlmResponse {
            requests += messages
            return LlmResponse(content = responses.removeFirstOrNull())
        }

        override fun chatStream(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Double?,
            maxTokens: Int?,
            toolChoice: ToolChoiceSpec?,
        ): Flow<LlmStreamChunk> = flowOf()
    }

    private fun manager() = TaskPlanManager(InMemoryTaskPlanStore(), "s1")

    @Test
    fun `LLM 拆解产出两级计划并折叠验收标准`() = runTest {
        val llm = ScriptedLlm(
            ArrayDeque(
                listOf(
                    """
                    好的，拆解如下：
                    ```json
                    {"phases": [
                      {"title": "定位素材", "steps": [
                        {"title": "列出下载目录文件", "acceptance": "得到文件清单"},
                        {"title": "筛选照片文件", "acceptance": "照片列表就绪"}
                      ]},
                      {"title": "归档", "steps": [
                        {"title": "按月份建立目录", "acceptance": "目录创建完成"},
                        {"title": "移动照片", "acceptance": "全部照片就位"}
                      ]}
                    ]}
                    ```
                    """.trimIndent(),
                ),
            ),
        )
        val decomposer = TaskDecomposer(llm, manager())

        val outcome = decomposer.decompose("把下载目录里的照片按月份归档", availableToolNames = listOf("list_files", "move_file"))

        assertTrue(outcome is TaskDecomposer.DecomposeOutcome.Success)
        val plan = (outcome as TaskDecomposer.DecomposeOutcome.Success).plan

        assertEquals("把下载目录里的照片按月份归档", plan.goal)
        // 2 父 + 4 子
        val parents = plan.tasks.filter { it.parent == null }
        val children = plan.tasks.filter { it.parent != null }
        assertEquals(2, parents.size)
        assertEquals(4, children.size)
        // 子任务验收标准折叠
        assertTrue(children.all { it.notes.startsWith("验收: ") })
        assertTrue(children.any { it.notes.contains("文件清单") })
        // 拆解提示包含工具名
        val prompt = (llm.requests.first().first() as LlmMessage.System).content
        assertTrue(prompt.contains("list_files"))
        assertTrue(prompt.contains("move_file"))
    }

    @Test
    fun `LLM 输出垃圾时回退单阶段计划`() = runTest {
        val llm = ScriptedLlm(ArrayDeque(listOf("我不会拆解，抱歉。")))
        val decomposer = TaskDecomposer(llm, manager())

        val outcome = decomposer.decompose("整理季度报告")

        assertTrue(outcome is TaskDecomposer.DecomposeOutcome.Fallback)
        val plan = (outcome as TaskDecomposer.DecomposeOutcome.Fallback).plan
        assertEquals(1, plan.tasks.size)
        assertEquals("整理季度报告", plan.tasks[0].title)
        assertEquals(TaskStatus.IN_PROGRESS, plan.tasks[0].status)
    }

    @Test
    fun `LLM 异常时回退`() = runTest {
        val llm = object : LlmClient {
            override suspend fun chat(
                messages: List<LlmMessage>,
                tools: List<ToolDefinition>,
                temperature: Double?,
                maxTokens: Int?,
                toolChoice: ToolChoiceSpec?,
            ): LlmResponse = throw IllegalStateException("endpoint down")

            override fun chatStream(
                messages: List<LlmMessage>,
                tools: List<ToolDefinition>,
                temperature: Double?,
                maxTokens: Int?,
                toolChoice: ToolChoiceSpec?,
            ): Flow<LlmStreamChunk> = flowOf()
        }
        val outcome = TaskDecomposer(llm, manager()).decompose("任何目标")
        assertTrue(outcome is TaskDecomposer.DecomposeOutcome.Fallback)
    }

    @Test
    fun `阶段数超限被截断`() = runTest {
        val phases = (1..10).joinToString(",") { i ->
            """{"title": "阶段$i", "steps": [{"title": "步骤$i", "acceptance": "ok"}]}"""
        }
        val llm = ScriptedLlm(ArrayDeque(listOf("""{"phases": [$phases]}""")))
        val outcome = TaskDecomposer(llm, manager()).decompose("大目标")

        val plan = (outcome as TaskDecomposer.DecomposeOutcome.Success).plan
        assertEquals(6, plan.tasks.count { it.parent == null }) // maxPhases 默认 6
    }
}
