package com.androidguru.agent.shell

import com.androidguru.agent.shell.terminal.AnsiStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnsiStripperTest {

    private val csi = "\u001B["
    private val esc = "\u001B"
    private val bel = "\u0007"

    @Test
    fun `删除 CSI 控制序列`() {
        assertEquals("hello", AnsiStripper.strip("hel${csi}1;32mlo"))
    }

    @Test
    fun `删除 OSC 序列含 BEL 终止`() {
        assertEquals("hello", AnsiStripper.strip("hel${esc}]0;title${bel}lo"))
    }

    @Test
    fun `删除 OSC 序列含 ST 终止`() {
        assertEquals("hello", AnsiStripper.strip("hel${esc}]0;title${esc}\\lo"))
    }

    @Test
    fun `删除回车退格与响铃`() {
        assertEquals("abc", AnsiStripper.strip("a\rb${bel}c"))
    }

    @Test
    fun `折叠进度条覆盖只保留最后内容`() {
        assertEquals("100%", AnsiStripper.collapseCarriageReturns("10%\r50%\r100%"))
    }

    @Test
    fun `clean 折叠三连换行并去尾部空白`() {
        assertEquals("a\n\nb", AnsiStripper.clean("a\n\n\n\nb\n\n\n"))
    }

    @Test
    fun `truncate 保留头尾并标记省略`() {
        val text = "x".repeat(3000) + "MID" + "y".repeat(5000)
        val t = AnsiStripper.truncate(text, headChars = 1000, tailChars = 1000)
        assertTrue(t.truncated)
        assertEquals(text.length - 2000, t.omittedChars)
        assertTrue(t.text.contains("已省略"))
        assertTrue(t.text.startsWith("x".repeat(100)))
        assertTrue(t.text.endsWith("y".repeat(100)))
    }

    @Test
    fun `truncate 短文本原样返回`() {
        val t = AnsiStripper.truncate("hello", 100, 100)
        assertFalse(t.truncated)
        assertEquals("hello", t.text)
    }
}
