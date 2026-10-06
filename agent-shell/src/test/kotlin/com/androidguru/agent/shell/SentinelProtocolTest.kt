package com.androidguru.agent.shell

import com.androidguru.agent.shell.terminal.SentinelProtocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 哨兵协议测试 —— 重点验证对 yl-ai 原实现四个缺陷的修复：
 * 1. BEGIN 前置 → 命令输出完整进入 body；
 * 2. `__agsh_rc=$?` 立即暂存 → 退出码不再是 printf 的 0；
 * 3. 累计缓冲解析 → 跨 chunk 切开也能解析；
 * 4. payload 恒定 4 行 → 无 echo 开关边界问题。
 */
class SentinelProtocolTest {

    @Test
    fun `payload 结构为 BEGIN在前_退出码立即暂存`() {
        val id = SentinelProtocol.newId()
        val payload = SentinelProtocol.buildPayload("ls -la", id)
        val lines = payload.split("\n").map { it.trimEnd('\r') }
        // 4 行 payload（最后 println 产生一个空尾串）
        assertEquals(
            listOf(
                "printf '\\n${SentinelProtocol.beginMarker(id)}\\n'",
                "ls -la",
                "__agsh_rc=$?",
                "printf '${SentinelProtocol.endMarker(id)}:%s\\n' \"\$__agsh_rc\"",
                "",
            ),
            lines,
        )
    }

    @Test
    fun `提取_完整输出与真实退出码`() {
        val id = "abc123"
        val raw = "\n${SentinelProtocol.beginMarker(id)}\n" +
            "file-a\nfile-b\n" +
            "${SentinelProtocol.endMarker(id)}:42\n"
        val ex = SentinelProtocol.extract(raw, id)
        assertNotNull(ex)
        assertEquals(42, ex!!.exitCode)
        assertTrue(ex.rawBody.contains("file-a"))
        assertTrue(ex.rawBody.contains("file-b"))
    }

    @Test
    fun `提取_哨兵行被过滤_回显行由stripEchoedCommand处理`() {
        val id = "abc123"
        val raw = "\n${SentinelProtocol.beginMarker(id)}\n" +
            "echo hello\nhello\n" +
            "${SentinelProtocol.endMarker(id)}:0\n"
        val ex = SentinelProtocol.extract(raw, id)
        assertNotNull(ex)
        assertEquals(0, ex!!.exitCode)
        // extract 只滤哨兵行；PTY 回显的命令行由 stripEchoedCommand 尽力去掉
        assertTrue(ex.rawBody.contains("hello"))
        val deEchoed = SentinelProtocol.stripEchoedCommand(ex.rawBody, "echo hello")
        assertEquals("hello", deEchoed.trim())
    }

    @Test
    fun `提取_跨chunk切开哨兵仍能解析`() {
        // 模拟 PTY 读边界把 END 标记与退出码切到不同 chunk
        val id = "xyz789"
        val chunk1 = "\n${SentinelProtocol.beginMarker(id)}\noutput-line\n${SentinelProtocol.endMarker(id).take(9)}"
        val chunk2 = "${SentinelProtocol.endMarker(id).drop(9)}:3\n"
        val cumulative = chunk1 + chunk2

        assertNull("chunk1 未凑齐 → 应返回 null", SentinelProtocol.extract(chunk1, id))
        val ex = SentinelProtocol.extract(cumulative, id)
        assertNotNull(ex)
        assertEquals(3, ex!!.exitCode)
        assertTrue(ex.rawBody.contains("output-line"))
    }

    @Test
    fun `提取_未出现END返回null`() {
        val id = "abc123"
        val raw = "\n${SentinelProtocol.beginMarker(id)}\npartial output...\n"
        assertNull(SentinelProtocol.extract(raw, id))
    }

    @Test
    fun `提取_负数退出码`() {
        val id = "abc123"
        val raw = "${SentinelProtocol.beginMarker(id)}\n${SentinelProtocol.endMarker(id)}:-15\n"
        val ex = SentinelProtocol.extract(raw, id)
        assertNotNull(ex)
        assertEquals(-15, ex!!.exitCode)
    }

    @Test
    fun `stripEchoedCommand 去掉回显首行`() {
        val body = "ls -la\ntotal 0\ndrwxr-xr-x .\n"
        val cleaned = SentinelProtocol.stripEchoedCommand(body, "ls -la")
        assertEquals("total 0\ndrwxr-xr-x .", cleaned.trim())
    }

    @Test
    fun `stripEchoedCommand 无回显时原样返回`() {
        val body = "total 0\n"
        assertEquals(body, SentinelProtocol.stripEchoedCommand(body, "ls -la"))
    }

    @Test
    fun `每个id生成唯一哨兵`() {
        val a = SentinelProtocol.newId()
        val b = SentinelProtocol.newId()
        assertTrue(a != b)
        assertEquals(12, a.length)
    }
}
