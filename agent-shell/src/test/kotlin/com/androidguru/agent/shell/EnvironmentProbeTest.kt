package com.androidguru.agent.shell

import com.androidguru.agent.shell.runtime.EnvironmentProbe
import com.androidguru.agent.shell.runtime.ShellEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EnvironmentProbeTest {

    /** 隔离环境：systemPathDirs 置空，只看注入的目录（消除宿主机差异）。 */
    private fun isolatedEnv(base: File, extra: List<File>) = ShellEnvironment(
        prefix = File(base, "usr"),
        home = File(base, "home"),
        tmp = File(base, "tmp"),
        extraPathDirs = extra,
        systemPathDirs = emptyList(),
    )

    @Test
    fun `探测_命中的目录里的可执行文件`() {
        val base = java.nio.file.Files.createTempDirectory("agsh-env").toFile()
        try {
            val bin = File(base, "tools").apply { mkdirs() }
            listOf("curl", "git").forEach { name ->
                File(bin, name).writeText("#!/bin/sh\n")
                File(bin, name).setExecutable(true)
            }
            val probe2 = isolatedEnv(base, listOf(bin))
            val report = EnvironmentProbe(probe2).probe()
            assertTrue("curl 应可用", "curl" in report.available)
            assertTrue("git 应可用", "git" in report.available)
            val probe = EnvironmentProbe(isolatedEnv(base, listOf(bin)))
            assertEquals("可用 ∪ 缺失 = 全集", report.available.size + report.missing.size, (probe.essential + probe.developer).distinct().size)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `探测_内建命令视为存在`() {
        val base = java.nio.file.Files.createTempDirectory("agsh-env").toFile()
        try {
            val report = EnvironmentProbe(isolatedEnv(base, emptyList())).probe()
            assertTrue("echo 是内建且在必备清单", "echo" in report.available)
            assertTrue("printf 是内建且在必备清单", "printf" in report.available)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `探测_无执行位的文件不算可用`() {
        val base = java.nio.file.Files.createTempDirectory("agsh-env").toFile()
        try {
            val bin = File(base, "tools").apply { mkdirs() }
            File(bin, "git").writeText("data") // 无执行位
            val report = EnvironmentProbe(isolatedEnv(base, listOf(bin))).probe()
            assertTrue("无执行位的文件不应算可用", "git" !in report.available)
            assertTrue("git 应进缺失", "git" in report.missing)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `forPrompt 生成三段式清单`() {
        val base = java.nio.file.Files.createTempDirectory("agsh-env").toFile()
        try {
            val prompt = EnvironmentProbe(isolatedEnv(base, emptyList())).probe().forPrompt()
            assertTrue(prompt.contains("可用命令"))
            assertTrue(prompt.contains("确认不存在"))
            assertTrue(prompt.contains("不要尝试"))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `默认shell解析`() {
        val base = java.nio.file.Files.createTempDirectory("agsh-env").toFile()
        try {
            val env = isolatedEnv(base, emptyList())
            assertEquals("/bin/sh", env.defaultShell())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `buildEnv 注入关键变量`() {
        val base = java.nio.file.Files.createTempDirectory("agsh-env").toFile()
        try {
            val env = isolatedEnv(base, emptyList())
            val e = env.buildEnv(mapOf("MY_VAR" to "x"))
            assertEquals(env.buildPath(), e["PATH"])
            assertEquals(env.home.absolutePath, e["HOME"])
            assertEquals(env.tmp.absolutePath, e["TMPDIR"])
            assertEquals("x", e["MY_VAR"])
            assertEquals("xterm-256color", e["TERM"])
        } finally {
            base.deleteRecursively()
        }
    }
}
