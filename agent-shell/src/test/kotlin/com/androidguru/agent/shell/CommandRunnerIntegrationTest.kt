package com.androidguru.agent.shell

import com.androidguru.agent.shell.process.JvmProcessChannelFactory
import com.androidguru.agent.shell.runtime.ShellEnvironment
import com.androidguru.agent.shell.session.CommandRunner
import com.androidguru.agent.shell.session.ShellSession
import com.androidguru.agent.shell.session.ShellSessionManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 命令执行器端到端测试（真实 shell：/bin/sh）—— 验证修复后的哨兵协议
 * 在真实进程通道上取回「退出码 + 输出」。
 *
 * 这是 yl-ai 移植中最关键的一组测试：原实现的哨兵协议在真机自检中长期
 * 未能通过（退出码恒 0 / 输出缺失），本组测试保证移植后的实现是通的。
 */
class CommandRunnerIntegrationTest {

    private fun newSession(base: File): Pair<ShellSessionManager, ShellSession> {
        val env = ShellEnvironment(
            prefix = File(base, "usr"),
            home = File(base, "home"),
            tmp = File(base, "tmp"),
        )
        val manager = ShellSessionManager(env, JvmProcessChannelFactory())
        // 非 PTY 管道：用 sh -s 从 stdin 读命令（等价交互 shell 的读循环语义）
        val session = ShellSession("t1", "测试", env, JvmProcessChannelFactory())
        session.start("/bin/sh", listOf("-s"), cwd = env.home.absolutePath)
        return manager to session
    }

    @Test
    fun `端到端_取回输出与退出码`() = runBlocking {
        val base = java.nio.file.Files.createTempDirectory("agsh-runner").toFile()
        try {
            val (_, session) = newSession(base)
            val runner = CommandRunner(session)
            val result = withTimeout(30_000) { runner.run("echo HELLO_SENTINEL", timeoutMs = 15_000) }
            assertEquals(0, result.exitCode)
            assertTrue("输出应包含命令结果：${result.stdout}", result.stdout.contains("HELLO_SENTINEL"))
            assertTrue(result.success)
            session.close()
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `端到端_非零退出码被精确捕获`() = runBlocking {
        val base = java.nio.file.Files.createTempDirectory("agsh-runner").toFile()
        try {
            val (_, session) = newSession(base)
            val runner = CommandRunner(session)
            val result = withTimeout(30_000) { runner.run("exit 42", timeoutMs = 15_000) }
            assertEquals("退出码必须是 42（修复点：\$? 立即暂存）", 42, result.exitCode)
            session.close()
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `端到端_管道命令退出码`() = runBlocking {
        val base = java.nio.file.Files.createTempDirectory("agsh-runner").toFile()
        try {
            val (_, session) = newSession(base)
            val runner = CommandRunner(session)
            val result = withTimeout(30_000) {
                runner.run("printf 'a\\nb\\nc\\n' | grep -c b", timeoutMs = 15_000)
            }
            assertEquals(0, result.exitCode)
            assertTrue(result.stdout.contains("1"))
            session.close()
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `端到端_命令未找到返回127`() = runBlocking {
        val base = java.nio.file.Files.createTempDirectory("agsh-runner").toFile()
        try {
            val (_, session) = newSession(base)
            val runner = CommandRunner(session)
            val result = withTimeout(30_000) {
                runner.run("definitely_not_a_command_xyz", timeoutMs = 15_000)
            }
            assertEquals(127, result.exitCode)
            session.close()
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `端到端_超时改判`() = runBlocking {
        val base = java.nio.file.Files.createTempDirectory("agsh-runner").toFile()
        try {
            val (_, session) = newSession(base)
            val runner = CommandRunner(session)
            val result = withTimeout(30_000) { runner.run("sleep 10", timeoutMs = 2_000) }
            assertTrue("超时应标记 timedOut", result.timedOut)
            assertEquals(-1, result.exitCode)
            session.close()
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `端到端_串行执行不串流`() = runBlocking {
        val base = java.nio.file.Files.createTempDirectory("agsh-runner").toFile()
        try {
            val (_, session) = newSession(base)
            val runner = CommandRunner(session)
            val r1 = withTimeout(30_000) { runner.run("echo FIRST_ONE", timeoutMs = 15_000) }
            val r2 = withTimeout(30_000) { runner.run("echo SECOND_TWO", timeoutMs = 15_000) }
            assertTrue(r1.stdout.contains("FIRST_ONE"))
            assertTrue(!r1.stdout.contains("SECOND_TWO"))
            assertTrue(r2.stdout.contains("SECOND_TWO"))
            assertTrue(!r2.stdout.contains("FIRST_ONE"))
            session.close()
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `cd 命令同步会话工作目录`() = runBlocking {
        val base = java.nio.file.Files.createTempDirectory("agsh-runner").toFile()
        try {
            val dirA = File(base, "dirA").apply { mkdirs() }
            val (_, session) = newSession(base)
            val runner = CommandRunner(session)
            withTimeout(30_000) { runner.run("cd $dirA", timeoutMs = 15_000) }
            assertEquals(dirA.absolutePath, session.currentDirectory)
            session.close()
        } finally {
            base.deleteRecursively()
        }
    }
}
