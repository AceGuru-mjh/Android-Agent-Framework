package com.androidguru.agent.shell.control

import com.androidguru.agent.shell.audit.InMemoryAuditLog
import com.androidguru.agent.shell.policy.ApprovalGate
import com.androidguru.agent.shell.policy.CommandPolicy
import com.androidguru.agent.shell.process.JvmProcessChannelFactory
import com.androidguru.agent.shell.runtime.ShellEnvironment
import com.androidguru.agent.shell.session.ShellSessionManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [ControlApiSelfTest] 用真实 [LocalControlServer]（进程内 dispatch，不起端口）
 * + 内存审计日志跑通全部检查项 —— 对应 yl-ai ApiSelfTest 的「协议方法逐个验证 +
 * 审计落盘往返」。
 */
class ControlApiSelfTestTest {

    private fun newSelfTest(base: File): Pair<ControlApiSelfTest, InMemoryAuditLog> {
        val env = ShellEnvironment(
            prefix = File(base, "usr"),
            home = File(base, "home"),
            tmp = File(base, "tmp"),
        )
        val sessions = ShellSessionManager(env, JvmProcessChannelFactory())
        sessions.create()
        val audit = InMemoryAuditLog()
        val server = com.androidguru.agent.shell.control.LocalControlServer(
            sessions = sessions,
            policy = CommandPolicy,
            approval = ApprovalGate(),
            audit = audit,
            environment = env,
            tokenStore = File(base, "token"),
        )
        return ControlApiSelfTest(server, audit) to audit
    }

    @Test
    fun `自检全绿_协议方法逐项通过`() = runBlocking {
        val base = java.nio.file.Files.createTempDirectory("agsh-selftest").toFile()
        try {
            val (selfTest, _) = newSelfTest(base)
            val report = selfTest.run()

            fun resultOf(prefix: String): CheckResult =
                report.results.first { it.method.startsWith(prefix) }

            // 基础方法
            assertTrue("server.info 应通过：${resultOf("server.info").note}", resultOf("server.info").passed)
            assertTrue("session.list 应通过", resultOf("session.list").passed)
            assertTrue("approval.pending 应通过", resultOf("approval.pending").passed)

            // 负路径
            assertTrue("未知方法必须报错", resultOf("no.such.method").passed)
            assertTrue(resultOf("no.such.method").note.contains("未知方法"))
            assertTrue("非法 JSON 必须报错", resultOf("非法 JSON").passed)

            // 审计闭环
            assertTrue("审计往返应通过", resultOf("audit.roundtrip").passed)

            // 报告结论
            assertTrue("全项通过时 allPassed 应为 true", report.allPassed)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `执行与写文件标注 SKIPPED_NEEDS_APPROVAL`() = runBlocking {
        val base = java.nio.file.Files.createTempDirectory("agsh-selftest").toFile()
        try {
            val (selfTest, _) = newSelfTest(base)
            val report = selfTest.run()

            val skipped = report.results.filter { it.method == "session.exec" || it.method == "fs.write" }
            assertEquals("session.exec 与 fs.write 都应在列", 2, skipped.size)
            skipped.forEach {
                assertTrue("应标注 SKIPPED_NEEDS_APPROVAL：${it.note}", it.note.startsWith(ControlApiSelfTest.SKIP_MARKER))
                assertTrue("跳过项不算失败", it.passed)
            }
            // 跳过不应污染结论
            assertTrue(report.allPassed)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `审计往返确实写入了 test 事件`() = runBlocking {
        val base = java.nio.file.Files.createTempDirectory("agsh-selftest").toFile()
        try {
            val (selfTest, audit) = newSelfTest(base)
            val before = audit.count()
            val report = selfTest.run()

            val roundtrip = report.results.first { it.method == "audit.roundtrip" }
            assertTrue(roundtrip.note, roundtrip.passed)
            assertTrue("自检后审计条数应增加", audit.count() > before)
            assertNotNull(audit.recent(20).firstOrNull { it.payload["what"] == "api.selftest" })
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `render 输出可读报告`() = runBlocking {
        val base = java.nio.file.Files.createTempDirectory("agsh-selftest").toFile()
        try {
            val (selfTest, _) = newSelfTest(base)
            val text = selfTest.run().render()
            assertTrue(text.contains("本地控制 API 自检"))
            assertTrue(text.contains("server.info"))
            assertTrue(text.contains("全部通过"))
            // 失败场景的渲染：不通过的项带 ✗ 且结论变化
            val failing = ControlSelfTestReport(
                results = listOf(CheckResult("server.info", false, "响应异常")),
                allPassed = false,
            )
            val failText = failing.render()
            assertFalse(failText.contains("全部通过"))
            assertTrue(failText.contains("存在失败项"))
        } finally {
            base.deleteRecursively()
        }
    }
}
