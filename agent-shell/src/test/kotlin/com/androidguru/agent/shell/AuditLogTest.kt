package com.androidguru.agent.shell

import com.androidguru.agent.shell.audit.AuditLog
import com.androidguru.agent.shell.audit.FileAuditLog
import com.androidguru.agent.shell.audit.InMemoryAuditLog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

class AuditLogTest {

    @Test
    fun `内存日志记录与检索`() {
        val log = InMemoryAuditLog()
        log.recordCommand(AuditLog.Source.AI, "ls -la", exitCode = 0, durationMs = 5)
        log.recordBlocked(AuditLog.Source.AI, "rm -rf /", "递归删除")
        log.recordSetting("container.install", "alpine 3.20")

        assertEquals(3, log.count())
        val recent = log.recent(10)
        assertEquals(3, recent.size)
        // 新的在前
        assertEquals(AuditLog.TYPE_SETTING, recent.first().type)
        val commands = log.recent(10, type = AuditLog.TYPE_COMMAND)
        assertEquals(1, commands.size)
        assertEquals("ls -la", commands.first().payload["command"])
    }

    @Test
    fun `文件日志落盘并可跨实例读取`() {
        val dir = Files.createTempDirectory("agsh-audit").toFile()
        try {
            val log = FileAuditLog(dir)
            log.recordCommand(AuditLog.Source.USER, "df -h", exitCode = 0)
            log.recordAiTask("看看磁盘", "OK", 3, 120)
            // 异步落盘：等待 executor 完成
            Thread.sleep(300)

            val log2 = FileAuditLog(dir)
            val events = log2.recent(10)
            assertTrue(events.size >= 2)
            assertTrue(events.any { it.type == AuditLog.TYPE_COMMAND && it.payload["command"] == "df -h" })
            assertTrue(events.any { it.type == AuditLog.TYPE_AI_TASK })

            val exported = log2.exportJson(10)
            assertTrue(exported.contains("\"count\""))
            assertTrue(exported.contains("df -h"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `清理过期文件`() {
        val dir = Files.createTempDirectory("agsh-audit").toFile()
        try {
            val log = FileAuditLog(dir)
            log.recordSetting("x", "y")
            Thread.sleep(200)
            // 30 天保留期内 → 不清理今天的文件
            assertEquals(0, log.purgeOlderThan(30))
            // 预置一个回溯日期文件 → 应被清理
            File(dir, "audit-2020-01-01.jsonl").writeText("x\n")
            assertEquals(1, log.purgeOlderThan(30))
            assertTrue("今天的文件应保留", dir.listFiles()?.any { it.name != "audit-2020-01-01.jsonl" && it.name.startsWith("audit-") } == true)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `recordFileWrite 记录审批结论`() {
        val log = InMemoryAuditLog()
        log.recordFileWrite("/tmp/a.txt", 42, approved = true)
        val e = log.recent(1).first()
        assertEquals(AuditLog.TYPE_FS_WRITE, e.type)
        assertEquals("42", e.payload["bytes"])
        assertEquals("true", e.payload["approved"])
    }
}
