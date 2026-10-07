package com.androidguru.agent.core.engine

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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 跑偏检测器测试：进展停滞 / 进展重置 / LLM 目标对齐抽查（含解析容错）。
 */
class TaskDriftDetectorTest {

    /** 脚本化 LLM：chat 返回固定文本。 */
    private class ScriptedLlm(private val response: String?) : LlmClient {
        override suspend fun chat(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Double?,
            maxTokens: Int?,
            toolChoice: ToolChoiceSpec?,
        ): LlmResponse =
            response?.let { LlmResponse(content = it) } ?: throw IllegalStateException("down")

        override fun chatStream(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Double?,
            maxTokens: Int?,
            toolChoice: ToolChoiceSpec?,
        ): Flow<LlmStreamChunk> = flowOf()
    }

    private val config = AgentConfig(driftStagnationIterations = 3, driftCheckInterval = 4)

    @Test
    fun `连续无进展轮次触发停滞告警`() {
        val detector = TaskDriftDetector(ScriptedLlm(null), config)

        var advisory: String? = null
        for (iteration in 1..3) {
            detector.onIterationStart(iteration)
            detector.onToolCall("run_cmd", "ls", progressed = false) // 全部失败
            advisory = detector.onIterationEnd()
        }
        assertNotNull(advisory)
        assertTrue(advisory!!.contains("3"))
        assertTrue(advisory.contains("没有"))

        // 出现进展 → 停滞清零
        detector.onIterationStart(4)
        detector.onToolCall("run_cmd", "ls", progressed = true)
        assertNull(detector.onIterationEnd())
    }

    @Test
    fun `有成功调用的轮次不计入停滞`() {
        val detector = TaskDriftDetector(ScriptedLlm(null), config)
        for (iteration in 1..5) {
            detector.onIterationStart(iteration)
            detector.onToolCall("read_screen", "{}", progressed = true)
            assertNull(detector.onIterationEnd())
        }
    }

    @Test
    fun `同一轮既有失败又有成功不 stagnation`() {
        val detector = TaskDriftDetector(ScriptedLlm(null), config)
        for (iteration in 1..5) {
            detector.onIterationStart(iteration)
            detector.onToolCall("a", "{}", progressed = false)
            detector.onToolCall("b", "{}", progressed = true)
            assertNull(detector.onIterationEnd())
        }
    }

    @Test
    fun `停滞告警只发一次直到新进展`() {
        val detector = TaskDriftDetector(ScriptedLlm(null), config)
        var alerts = 0
        for (iteration in 1..8) {
            detector.onIterationStart(iteration)
            detector.onToolCall("x", "{}", progressed = false)
            if (detector.onIterationEnd() != null) alerts++
        }
        assertEquals(1, alerts)
    }

    @Test
    fun `对齐抽查按间隔触发且解析判定`() = runTest {
        val detector = TaskDriftDetector(ScriptedLlm("""{"aligned": false, "reason": "在做无关探索"}"""), config)

        // 填充动作历史
        detector.onIterationStart(1)
        detector.onToolCall("open_app", """{"app":"settings"}""", progressed = true)
        detector.onIterationEnd()

        assertFalse(detector.shouldCheckAlignment()) // 第 1 轮不是间隔点

        detector.onIterationStart(4)
        detector.onToolCall("read_screen", "{}", progressed = true)
        detector.onIterationEnd()
        assertTrue(detector.shouldCheckAlignment()) // 第 4 轮 = 间隔点

        val verdict = detector.checkAlignment("打开设置并关闭 WiFi")
        assertNotNull(verdict)
        assertEquals(false, verdict!!.aligned)
        assertTrue(verdict.reason.contains("无关"))
    }

    @Test
    fun `对齐判定 fenced json 也能解析`() = runTest {
        val detector = TaskDriftDetector(ScriptedLlm("""```json
{"aligned": true, "reason": "在推进"}
```"""), config)
        val verdict = detector.checkAlignment("任何目标")
        assertNotNull(verdict)
        assertTrue(verdict!!.aligned)
    }

    @Test
    fun `LLM 输出非法时静默跳过`() = runTest {
        val detector = TaskDriftDetector(ScriptedLlm("我觉得还行吧"), config)
        assertNull(detector.checkAlignment("目标"))
    }

    @Test
    fun `间隔关闭时不抽查`() = runTest {
        val detector = TaskDriftDetector(ScriptedLlm(null), AgentConfig(driftCheckInterval = 0))
        detector.onIterationStart(100)
        detector.onToolCall("x", "{}", progressed = true)
        detector.onIterationEnd()
        assertFalse(detector.shouldCheckAlignment())
    }
}
