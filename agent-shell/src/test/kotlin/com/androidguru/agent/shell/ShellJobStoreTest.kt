package com.androidguru.agent.shell

import com.androidguru.agent.shell.job.ShellJob
import com.androidguru.agent.shell.process.JvmProcessChannelFactory
import com.androidguru.agent.shell.runtime.ShellEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShellJobStoreTest {

    private fun env(base: File) = ShellEnvironment(
        prefix = File(base, "usr"),
        home = File(base, "home"),
        tmp = File(base, "tmp"),
    )

    private fun store(base: File): ShellJob.ShellJobStore = ShellJob.ShellJobStore(File(base, "jobs"))

    @Test
    fun `启动作业_执行完成_退出码与输出正确`() {
        val base = java.nio.file.Files.createTempDirectory("agsh-job").toFile()
        try {
            val jobs = store(base).also { it.init() }
            val job = ShellJob.start(
                id = jobs.newId(),
                command = "echo JOB_OK; exit 5",
                workdir = null,
                environment = env(base),
                channelFactory = JvmProcessChannelFactory(),
                store = jobs,
            )
            val deadline = System.currentTimeMillis() + 15_000
            while (job.state == ShellJob.State.RUNNING && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
            }
            assertEquals(ShellJob.State.EXITED, job.state)
            assertEquals(5, job.exitCode)
            assertTrue("输出应落盘", job.logFile.isFile)
            assertTrue("日志应含输出", job.readOutput().contains("JOB_OK"))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `作业记录写入index并可列出`() {
        val base = java.nio.file.Files.createTempDirectory("agsh-job").toFile()
        try {
            val jobs = store(base).also { it.init() }
            val job = ShellJob.start(
                id = jobs.newId(),
                command = "echo quick",
                workdir = null,
                environment = env(base),
                channelFactory = JvmProcessChannelFactory(),
                store = jobs,
            )
            val deadline = System.currentTimeMillis() + 15_000
            while (job.state == ShellJob.State.RUNNING && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
            }
            val list = jobs.list()
            assertTrue(list.any { it.id == job.id })
            assertTrue("index.json 应存在", File(File(base, "jobs"), "index.json").isFile)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `重启后_running改判为interrupted`() {
        val base = java.nio.file.Files.createTempDirectory("agsh-job").toFile()
        try {
            // 预置带 RUNNING 状态的 index.json（模拟上次进程死亡）
            val jobsDir = File(base, "jobs").apply { mkdirs() }
            File(jobsDir, "index.json").writeText(
                """{"jobs":[{"id":"job_old1","command":"echo old","workdir":"/tmp",""" +
                    """"state":"RUNNING","log":"/tmp/old.log","startedAt":1,"durationMs":100}]}""",
            )

            val jobs = store(base).also { it.init() }
            val rec = jobs.getRecord("job_old1")
            assertNotNull(rec)
            assertEquals("残留 RUNNING 必须改判", "INTERRUPTED", rec!!.state)
            assertNull("旧作业不可操作（只能读日志）", jobs.get("job_old1"))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `服务地址自动探测`() {
        val base = java.nio.file.Files.createTempDirectory("agsh-job").toFile()
        try {
            val jobs = store(base).also { it.init() }
            val job = ShellJob.start(
                id = jobs.newId(),
                command = "echo serving on 0.0.0.0:8080; sleep 0.3; exit 0",
                workdir = null,
                environment = env(base),
                channelFactory = JvmProcessChannelFactory(),
                store = jobs,
            )
            val deadline = System.currentTimeMillis() + 15_000
            while (job.state == ShellJob.State.RUNNING && System.currentTimeMillis() < deadline) {
                Thread.sleep(100)
            }
            assertEquals("http://127.0.0.1:8080", job.serviceUrl)
        } finally {
            base.deleteRecursively()
        }
    }
}
