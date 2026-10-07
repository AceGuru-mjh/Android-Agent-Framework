package com.androidguru.agent.eval

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 轨迹校验器测试：必做 / 禁止 / 参数匹配 / 轮数 / 收尾文本 / 未完成判定。
 */
class TraceValidatorTest {

    private fun trace(
        calls: List<ToolCallEvent>,
        finalText: String? = "完成了",
        completed: Boolean = true,
        iterations: Int = 3,
        loopDetected: Boolean = false,
    ) = TraceRecord(
        toolCalls = calls,
        finalText = finalText,
        completed = completed,
        iterations = iterations,
        loopDetected = loopDetected,
        reflections = 0,
        driftWarnings = 0,
        totalToolCalls = calls.size,
        durationMs = 100,
    )

    private fun call(
        name: String,
        args: String = "{}",
        ok: Boolean = true,
        iteration: Int = 1,
    ) = ToolCallEvent(
        iteration = iteration,
        callId = "c-$name-$iteration",
        name = name,
        arguments = args,
        ok = ok,
        outputExcerpt = "",
        durationMs = 10,
    )

    private val alarmTask = EvalTask(
        id = "t1",
        name = "设置闹钟",
        category = "系统设置",
        instruction = "设一个 7:30 的闹钟",
        criteria = SuccessCriteria(
            requiredCalls = listOf(
                CallMatcher(
                    tool = "set_alarm",
                    argChecks = listOf(ArgCheck("time", MatchOp.REGEX, """7[:：]30""")),
                ),
            ),
            maxIterations = 8,
        ),
    )

    @Test
    fun `必做调用与参数全部命中则通过`() {
        val trace = trace(
            listOf(
                call("open_app", """{"app":"时钟"}"""),
                call("set_alarm", """{"time":"明天 7:30","label":"晨跑"}"""),
            ),
        )
        val result = TraceValidator.validate(alarmTask, trace)
        assertTrue(result.passed)
        assertEquals(0, result.failures.size)
        assertTrue(result.totalChecks >= 3)
    }

    @Test
    fun `参数不匹配判失败并给出原因`() {
        val trace = trace(
            listOf(call("set_alarm", """{"time":"8:00"}""")), // 时间错了
        )
        val result = TraceValidator.validate(alarmTask, trace)
        assertFalse(result.passed)
        assertTrue(result.failures.any { it.contains("set_alarm") })
    }

    @Test
    fun `缺少必做调用判失败`() {
        val trace = trace(listOf(call("open_app", """{"app":"时钟"}""")))
        val result = TraceValidator.validate(alarmTask, trace)
        assertFalse(result.passed)
        assertTrue(result.failures.any { it.contains("缺少必做调用") })
    }

    @Test
    fun `出现禁止调用判失败`() {
        val task = EvalTask(
            id = "t2",
            name = "查电量",
            category = "系统设置",
            instruction = "看电量",
            criteria = SuccessCriteria(
                requiredCalls = listOf(CallMatcher(tool = "battery_status")),
                forbiddenCalls = listOf(CallMatcher(tool = "set_wifi")),
            ),
        )
        val trace = trace(
            listOf(
                call("battery_status"),
                call("set_wifi", """{"enabled":false}""", iteration = 2), // 误操作
            ),
        )
        val result = TraceValidator.validate(task, trace)
        assertFalse(result.passed)
        assertTrue(result.failures.any { it.contains("禁止调用") && it.contains("第 2 轮") })
    }

    @Test
    fun `正则工具名匹配删除类工具`() {
        val task = EvalTask(
            id = "t3",
            name = "只读查询",
            category = "文件",
            instruction = "列文件",
            criteria = SuccessCriteria(
                requiredCalls = listOf(CallMatcher(tool = "list_files")),
                forbiddenCalls = listOf(CallMatcher(tool = """delete|remove|trash""", toolRegex = true)),
            ),
        )
        val trace = trace(listOf(call("list_files"), call("delete_file", """{"f":"x"}""")))
        val result = TraceValidator.validate(task, trace)
        assertFalse(result.passed)
        assertTrue(result.failures.any { it.contains("delete_file") })
    }

    @Test
    fun `轮数超限判失败`() {
        val trace = trace(listOf(call("set_alarm", """{"time":"7:30"}""")), iterations = 12)
        val result = TraceValidator.validate(alarmTask, trace)
        assertFalse(result.passed)
        assertTrue(result.failures.any { it.contains("迭代轮数超限") })
    }

    @Test
    fun `触发循环护栏判失败`() {
        val task = EvalTask(
            id = "t4",
            name = "无循环要求",
            category = "文件",
            instruction = "x",
            criteria = SuccessCriteria(requiredCalls = listOf(CallMatcher(tool = "x"))),
        )
        val trace = trace(listOf(call("x")), loopDetected = true)
        val result = TraceValidator.validate(task, trace)
        assertFalse(result.passed)
        assertTrue(result.failures.any { it.contains("循环护栏") })
    }

    @Test
    fun `收尾文本模式校验`() {
        val task = EvalTask(
            id = "t5",
            name = "天气",
            category = "信息查询",
            instruction = "查天气",
            criteria = SuccessCriteria(
                requiredCalls = listOf(CallMatcher(tool = "weather")),
                finalTextPatterns = listOf(Regex("""适合|不适合""")),
            ),
        )
        val bad = TraceValidator.validate(task, trace(listOf(call("weather")), finalText = "明天晴。"))
        assertFalse(bad.passed)

        val good = TraceValidator.validate(task, trace(listOf(call("weather")), finalText = "适合晨跑。"))
        assertTrue(good.passed)
    }

    @Test
    fun `未正常收尾判失败`() {
        val trace = trace(listOf(call("set_alarm", """{"time":"7:30"}""")), completed = false, finalText = null)
        val result = TraceValidator.validate(alarmTask, trace)
        assertFalse(result.passed)
        assertTrue(result.failures.any { it.contains("未正常收尾") })
    }

    @Test
    fun `最少成功调用校验`() {
        val task = EvalTask(
            id = "t6",
            name = "多步",
            category = "文件",
            instruction = "x",
            criteria = SuccessCriteria(minSuccessfulCalls = 2),
        )
        val one = trace(listOf(call("a"), call("b", ok = false)))
        assertFalse(TraceValidator.validate(task, one).passed)
        val two = trace(listOf(call("a"), call("b", ok = true)))
        assertTrue(TraceValidator.validate(task, two).passed)
    }

    @Test
    fun `坏正则构造期快速失败`() {
        try {
            ArgCheck("k", MatchOp.REGEX, "([")
            throw AssertionError("应当抛异常")
        } catch (e: Exception) {
            // 预期
        }
    }
}
