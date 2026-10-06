package com.androidguru.agent.shell

import com.androidguru.agent.shell.session.SessionCheckpoint
import com.androidguru.agent.shell.session.ShellSessionManager
import com.androidguru.agent.shell.process.JvmProcessChannelFactory
import com.androidguru.agent.shell.runtime.ShellEnvironment
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SessionCheckpointTest {

    private fun tempEnv(base: File) = ShellEnvironment(
        prefix = File(base, "usr"),
        home = File(base, "home"),
        tmp = File(base, "tmp"),
    )

    @Test
    fun `保存与读取快照`() = runBlocking<Unit> {
        val base = java.nio.file.Files.createTempDirectory("agsh-ckpt").toFile()
        try {
            val env = tempEnv(base)
            val manager = ShellSessionManager(env, JvmProcessChannelFactory())
            val session = manager.create(title = "终端 1")
            Thread.sleep(300) // 等泵与状态就绪

            val ckpt = SessionCheckpoint(File(base, "ckpt"))
            assertTrue("保存应成功（会话存在）", ckpt.save(manager))
            val loaded = ckpt.load()
            assertNotNull(loaded)
            assertEquals(1, loaded!!.sessions.size)
            assertEquals("终端 1", loaded.sessions.first().title)
            manager.close(session.id) // 保存之后再关闭真实进程
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `过期快照返回null`() = runBlocking<Unit> {
        val base = java.nio.file.Files.createTempDirectory("agsh-ckpt").toFile()
        try {
            val env = tempEnv(base)
            val manager = ShellSessionManager(env, JvmProcessChannelFactory())
            val s = manager.create()
            manager.close(s.id)
            val ckpt = SessionCheckpoint(File(base, "ckpt"))
            ckpt.save(manager)
            assertNull("7 天前应视为过期", ckpt.load(maxAgeMs = -1))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `无快照时restore返回0`() = runBlocking<Unit> {
        val base = java.nio.file.Files.createTempDirectory("agsh-ckpt").toFile()
        try {
            val env = tempEnv(base)
            val manager = ShellSessionManager(env, JvmProcessChannelFactory())
            assertEquals(0, SessionCheckpoint(File(base, "ckpt")).restore(manager))
            assertEquals(0, manager.count)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `快照包含清洗后的尾部输出`() = runBlocking<Unit> {
        val base = java.nio.file.Files.createTempDirectory("agsh-ckpt").toFile()
        try {
            val env = tempEnv(base)
            val manager = ShellSessionManager(env, JvmProcessChannelFactory())
            val s = manager.create()
            s.pushReplay("标记输出 abc123")
            Thread.sleep(200) // 等泵与尾部环更新
            val ckpt = SessionCheckpoint(File(base, "ckpt"))
            assertTrue(ckpt.save(manager))
            val loaded = ckpt.load()
            assertNotNull(loaded)
            manager.close(s.id)
        } finally {
            base.deleteRecursively()
        }
    }
}
