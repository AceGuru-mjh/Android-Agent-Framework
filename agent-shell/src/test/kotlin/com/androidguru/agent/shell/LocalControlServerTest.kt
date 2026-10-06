package com.androidguru.agent.shell

import com.androidguru.agent.shell.audit.AuditLog
import com.androidguru.agent.shell.audit.InMemoryAuditLog
import com.androidguru.agent.shell.control.LocalControlServer
import com.androidguru.agent.shell.policy.ApprovalGate
import com.androidguru.agent.shell.policy.CommandPolicy
import com.androidguru.agent.shell.process.JvmProcessChannelFactory
import com.androidguru.agent.shell.runtime.ShellEnvironment
import com.androidguru.agent.shell.session.ShellSessionManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalControlServerTest {

    private fun newServer(base: File): Triple<LocalControlServer, ShellSessionManager, InMemoryAuditLog> {
        val env = ShellEnvironment(
            prefix = File(base, "usr"),
            home = File(base, "home"),
            tmp = File(base, "tmp"),
        )
        val sessions = ShellSessionManager(env, JvmProcessChannelFactory())
        sessions.create()
        val audit = InMemoryAuditLog()
        val approval = ApprovalGate()
        val server = LocalControlServer(
            sessions = sessions,
            policy = CommandPolicy,
            approval = approval,
            audit = audit,
            environment = env,
            tokenStore = File(base, "token"),
        )
        return Triple(server, sessions, audit)
    }

    @Test
    fun `server_info 返回端口与版本`() = runBlocking {
        val base = java.nio.file.Files.createTempDirectory("agsh-api").toFile()
        try {
            val (server, _, _) = newServer(base)
            val resp = server.dispatch("""{"id":"1","method":"server.info","params":{}}""")
            assertTrue(resp.contains("\"ok\":true"))
            assertTrue(resp.contains("version"))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `session_list 返回会话数组`() = runBlocking {
        val base = java.nio.file.Files.createTempDirectory("agsh-api").toFile()
        try {
            val (server, _, _) = newServer(base)
            val resp = server.dispatch("""{"id":"1","method":"session.list","params":{}}""")
            assertTrue(resp.contains("\"ok\":true"))
            assertTrue(resp.contains("sessions"))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `session_exec_执行只读命令并记账`() = runBlocking {
        val base = java.nio.file.Files.createTempDirectory("agsh-api").toFile()
        try {
            val (server, sessions, audit) = newServer(base)
            val sessionId = sessions.sessionList.first().id
            val resp = server.dispatch(
                """{"id":"2","method":"session.exec","params":{"sessionId":"$sessionId","command":"echo API_SELFTEST_OK"}}""",
            )
            assertTrue("响应应为 ok：$resp", resp.contains("\"ok\":true"))
            assertTrue(resp.contains("API_SELFTEST_OK"))
            assertTrue(resp.contains("exitCode\":0"))
            // 审计闭环
            assertTrue(
                "API 来源的执行必须记账",
                audit.recent(50, type = AuditLog.TYPE_COMMAND)
                    .any { it.source == "API" && it.payload["command"]?.contains("API_SELFTEST_OK") == true },
            )
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `session_exec_危险命令硬拦不弹审批`() = runBlocking {
        val base = java.nio.file.Files.createTempDirectory("agsh-api").toFile()
        try {
            val (server, sessions, audit) = newServer(base)
            val sessionId = sessions.sessionList.first().id
            val resp = server.dispatch(
                """{"id":"3","method":"session.exec","params":{"sessionId":"$sessionId","command":"rm -rf /"}}""",
            )
            assertTrue("BLOCKED 必须拒绝：$resp", resp.contains("\"ok\":false"))
            assertTrue(resp.contains("硬拦截") || resp.contains("拒绝"))
            assertTrue(audit.recent(50, type = AuditLog.TYPE_BLOCKED).isNotEmpty())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `未知方法返回错误`() = runBlocking {
        val base = java.nio.file.Files.createTempDirectory("agsh-api").toFile()
        try {
            val (server, _, _) = newServer(base)
            val resp = server.dispatch("""{"id":"4","method":"no.such.method","params":{}}""")
            assertTrue(resp.contains("\"ok\":false"))
            assertTrue(resp.contains("未知方法"))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `非法JSON返回错误`() = runBlocking {
        val base = java.nio.file.Files.createTempDirectory("agsh-api").toFile()
        try {
            val (server, _, _) = newServer(base)
            val resp = server.dispatch("not-json")
            assertTrue(resp.contains("\"ok\":false"))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `approval_respond 通路`() = runBlocking {
        val base = java.nio.file.Files.createTempDirectory("agsh-api").toFile()
        try {
            val (server, _, _) = newServer(base)
            val resp = server.dispatch(
                """{"id":"5","method":"approval.respond","params":{"id":"x","verdict":"deny"}}""",
            )
            assertTrue(resp.contains("accepted\":false")) // 无等待中的请求
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `令牌生成与恒时比较`() {
        val base = java.nio.file.Files.createTempDirectory("agsh-api").toFile()
        try {
            val (server, _, _) = newServer(base)
            val t1 = server.token()
            assertTrue("令牌应为 base64url 32字节", t1.length >= 40)
            assertEquals("同库读回应一致", t1, server.token())
            val t2 = server.regenerateToken()
            assertTrue(t2 != t1)
        } finally {
            base.deleteRecursively()
        }
    }
}
