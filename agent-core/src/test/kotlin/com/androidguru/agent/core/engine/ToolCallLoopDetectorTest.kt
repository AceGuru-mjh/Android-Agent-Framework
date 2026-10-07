package com.androidguru.agent.core.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 循环护栏升级（PR #26）测试：
 * - 语义等价签名（JSON 键序 / 空白差异被吸收）；
 * - 交替循环检测（A→B→A→B —— 原实现漏检形态）；
 * - 单签名重复维持既有阈值语义；
 * - 无循环序列不误报。
 */
class ToolCallLoopDetectorTest {

    @Test
    fun `JSON 键序与空白差异视为同一调用`() {
        val detector = ToolCallLoopDetector(windowSize = 12, threshold = 3)

        val c1 = detector.observe("open_app", """{"app": "时钟", "wait": true}""")
        val c2 = detector.observe("open_app", """{ "wait" : true, "app" : "时钟" }""")
        val c3 = detector.observe("open_app", """{"app":"时钟","wait":true}""")

        assertEquals(1, c1)
        assertEquals(2, c2)
        assertEquals(3, c3) // 语义等价：第 3 次达到阈值
    }

    @Test
    fun `参数不同不累计`() {
        val detector = ToolCallLoopDetector(windowSize = 12, threshold = 3)
        detector.observe("echo", """{"payload":"a"}""")
        detector.observe("echo", """{"payload":"b"}""")
        val c3 = detector.observe("echo", """{"payload":"c"}""")
        assertEquals(1, c3)
    }

    @Test
    fun `非 JSON 参数空白折叠后等价`() {
        val detector = ToolCallLoopDetector(windowSize = 12, threshold = 2)
        detector.observe("run_cmd", "ls  -la   /sdcard")
        val c2 = detector.observe("run_cmd", "ls -la /sdcard")
        assertEquals(2, c2)
    }

    @Test
    fun `交替循环 A 到 B 到 A 到 B 被检出`() {
        val detector = ToolCallLoopDetector(windowSize = 12, threshold = 3)

        // A→B→A：1.5 个周期，不报
        detector.observe("read_screen", "{}")
        detector.observe("tap", """{"x":1}""")
        detector.observe("read_screen", "{}")
        assertNull(detector.detectCycle())

        // A→B→A→B：2 个完整周期 → 报
        detector.observe("tap", """{"x":1}""")
        val signal = detector.detectCycle()
        assertNotNull(signal)
        assertEquals(2, signal!!.cycleLength)
        assertEquals(2, signal.repetitions)
        assertTrue(signal.description.contains("read_screen"))
        assertTrue(signal.description.contains("tap"))
    }

    @Test
    fun `三元素循环 A B C A B C 被检出`() {
        val detector = ToolCallLoopDetector(windowSize = 12, threshold = 3)
        repeat(2) {
            detector.observe("open_app", """{"app":"a"}""")
            detector.observe("read_screen", "{}")
            detector.observe("swipe", """{"dir":"up"}""")
        }
        val signal = detector.detectCycle()
        assertNotNull(signal)
        assertEquals(3, signal!!.cycleLength)
        assertEquals(2, signal.repetitions)
    }

    @Test
    fun `无规律序列不误报`() {
        val detector = ToolCallLoopDetector(windowSize = 12, threshold = 3)
        detector.observe("a", """{"n":1}""")
        detector.observe("b", """{"n":2}""")
        detector.observe("c", """{"n":3}""")
        detector.observe("a", """{"n":4}""")
        detector.observe("d", """{"n":5}""")
        assertNull(detector.detectCycle())
    }

    @Test
    fun `窗口滑出后旧签名不再累计`() {
        val detector = ToolCallLoopDetector(windowSize = 3, threshold = 3)
        detector.observe("echo", """{"p":"1"}""")
        detector.observe("echo", """{"p":"2"}""")
        detector.observe("echo", """{"p":"3"}""")
        val c4 = detector.observe("echo", """{"p":"1"}""") // 第一个 p=1 已滑出窗口
        assertEquals(1, c4)
    }

    @Test
    fun `reset 清空窗口`() {
        val detector = ToolCallLoopDetector(windowSize = 12, threshold = 2)
        detector.observe("echo", """{"p":"1"}""")
        detector.reset()
        val c = detector.observe("echo", """{"p":"1"}""")
        assertEquals(1, c)
    }

    @Test
    fun `建议文本包含修正指引`() {
        val detector = ToolCallLoopDetector()
        assertTrue(detector.advisoryText(3).contains("[loop-guard]"))
        val cycle = ToolCallLoopDetector.LoopSignal("a → b", 2, 2)
        assertTrue(detector.cycleAdvisoryText(cycle).contains("交替循环"))
    }
}
