package com.androidguru.agent.core.engine

import com.androidguru.agent.llm.LlmClient
import com.androidguru.agent.llm.LlmMessage
import com.androidguru.agent.llm.LlmResponse
import com.androidguru.agent.llm.LlmStreamChunk
import com.androidguru.agent.llm.ToolChoiceSpec
import com.androidguru.agent.llm.ToolDefinition
import com.androidguru.agent.tools.ToolErrorCode
import com.androidguru.agent.tools.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 反思引擎测试：连续失败触发 / LLM 分析与启发式回退 / 冷却纪律 / 信号接入。
 */
class ReflectionEngineTest {

    /** 脚本化 LLM：chat 返回固定文本（可切换为抛异常模拟不可用）。 */
    private class ScriptedLlm(private val response: String?, private val systemMessages: MutableList<String> = mutableListOf()) : LlmClient {
        override suspend fun chat(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Double?,
            maxTokens: Int?,
            toolChoice: ToolChoiceSpec?,
        ): LlmResponse {
            systemMessages += messages.filterIsInstance<LlmMessage.System>().joinToString("\n") { it.content }
            return response?.let { LlmResponse(content = it) } ?: throw IllegalStateException("llm down")
        }

        override fun chatStream(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Double?,
            maxTokens: Int?,
            toolChoice: ToolChoiceSpec?,
        ): Flow<LlmStreamChunk> = flowOf()
    }

    private fun failure(code: ToolErrorCode = ToolErrorCode.NOT_FOUND, message: String = "找不到目标") =
        ToolResult.failure(message, code)

    private val config = AgentConfig(reflectionFailureThreshold = 3)

    @Test
    fun `连续失败达到阈值触发反思`() = runTest {
        val engine = ReflectionEngine(ScriptedLlm("根因：路径错误。改用 find_file 先定位。"), config)

        engine.recordResult("t1", "{}", ToolResult.success("ok"))
        assertFalse(engine.shouldReflect()) // 成功清零

        engine.recordResult("t1", "{}", failure())
        engine.recordResult("t2", "{}", failure())
        assertFalse(engine.shouldReflect()) // 2 次未达阈值

        engine.recordResult("t3", "{}", failure())
        assertTrue(engine.shouldReflect()) // 3 次达阈值
    }

    @Test
    fun `LLM 反思产出策略修正`() = runTest {
        val engine = ReflectionEngine(ScriptedLlm("根因：应用名不匹配。改用 list_apps 确认实际名称。"), config)
        repeat(3) { engine.recordResult("open_app", """{"app":"抖音"}""", failure()) }

        val outcome = engine.reflect(goal = "卸载抖音", loopSignal = null)
        assertTrue(outcome.byLlm)
        assertTrue(outcome.lesson.contains("list_apps"))
        assertTrue(outcome.triggerReason.contains("连续 3 次"))

        // 反思后进入冷却
        assertFalse(engine.canReflectNow())
        // 失败计数被清零
        assertEquals(0, engine.currentConsecutiveFailures())
    }

    @Test
    fun `LLM 不可用回退启发式分诊`() = runTest {
        val engine = ReflectionEngine(ScriptedLlm(null), config)
        repeat(3) {
            engine.recordResult("open_app", """{"app":"抖音"}""", failure(ToolErrorCode.NOT_FOUND, "应用不存在"))
        }

        val outcome = engine.reflect(goal = null, loopSignal = null)
        assertFalse(outcome.byLlm)
        assertTrue(outcome.lesson.contains("NOT_FOUND 相关诊断".let { "不存在" })) // 启发式诊断包含针对性文案
        assertTrue(outcome.lesson.contains("不要原样重试"))
    }

    @Test
    fun `冷却期内不重复触发`() = runTest {
        val engine = ReflectionEngine(ScriptedLlm("ok"), config)
        repeat(3) { engine.recordResult("t", "{}", failure()) }
        engine.reflect(null, null)

        // 冷却期 3 轮内即使再失败也不触发
        repeat(5) { engine.recordResult("t", "{}", failure()) }
        // shouldReflect 看 consecutiveFailures ≥ 阈值 && 冷却结束
        // 冷却 3 轮未过完 → false
        assertFalse(engine.shouldReflect())

        engine.onIterationEnd()
        engine.onIterationEnd()
        engine.onIterationEnd()
        // 冷却结束，且连续失败仍在累计 → 可再触发
        assertTrue(engine.shouldReflect())
    }

    @Test
    fun `循环信号触发反思`() = runTest {
        val engine = ReflectionEngine(ScriptedLlm("你在读屏和点击间来回。等待界面加载完成再操作。"), config)
        val signal = ToolCallLoopDetector.LoopSignal("read_screen → tap", 2, 2)

        val outcome = engine.reflect(goal = "打开设置", loopSignal = signal)
        assertTrue(outcome.byLlm)
        assertTrue(outcome.triggerReason.contains("循环护栏"))
        assertTrue(outcome.triggerReason.contains("read_screen → tap"))
    }
}
