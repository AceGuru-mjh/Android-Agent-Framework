package com.androidguru.agent.shell

import com.androidguru.agent.shell.terminal.SentinelStripper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentinelStripperTest {

    private fun strip(vararg chunks: String): String {
        val s = SentinelStripper()
        return String(s.strip(chunks.joinToString("").toByteArray()), Charsets.UTF_8)
    }

    @Test
    fun `删除完整哨兵标记`() {
        assertEquals("beforeafter", strip("before__AGSH_ab12_END__:0\nafter"))
    }

    @Test
    fun `删除带退出码尾巴与换行的哨兵`() {
        assertEquals("x\ny", strip("x\n__AGSH_id1_BEGIN__\n__AGSH_id1_END__:0\ny"))
    }

    @Test
    fun `跨chunk半截标记被缓冲`() {
        val s = SentinelStripper()
        val out1 = String(s.strip("abc__AGSH".toByteArray()), Charsets.UTF_8)
        assertEquals("abc", out1)
        val out2 = String(s.strip("_id_END__:0\ndef".toByteArray()), Charsets.UTF_8)
        assertEquals("def", out2)
    }

    @Test
    fun `普通文本不含标记时原样通过`() {
        assertEquals("hello world", strip("hello world"))
    }

    @Test
    fun `三连换行折叠为两连`() {
        assertEquals("a\n\nb", strip("a\n\n\n\nb"))
    }

    @Test
    fun `reset 清空半截标记缓冲`() {
        val s = SentinelStripper()
        s.strip("abc__AGSH".toByteArray())
        // 不 reset：下一个 chunk 会与 pending 拼接（等待标记闭合）→ 无输出
        // reset：pending 丢弃，普通文本原样通过
        s.reset()
        assertEquals("def", String(s.strip("def".toByteArray()), Charsets.UTF_8))
    }

    @Test
    fun `MARK前缀常量与协议一致`() {
        assertTrue(SentinelStripper.MARK.startsWith("__"))
        assertTrue(SentinelStripper.MARK.endsWith("_"))
    }
}
