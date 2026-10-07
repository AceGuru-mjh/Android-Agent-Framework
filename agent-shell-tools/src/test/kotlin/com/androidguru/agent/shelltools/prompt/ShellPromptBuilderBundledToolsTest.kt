package com.androidguru.agent.shelltools.prompt

import com.androidguru.agent.shell.ShellRuntime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「应用自带工具」注入行测试 —— yl-ai AgentLoop.environmentNotes（L607-617）的对齐：
 * prefix/bin 里链入了宿主自带可执行体时提示词列出，为空时不输出该行。
 */
class ShellPromptBuilderBundledToolsTest {

    @Test
    fun `prefix_bin 为空时不输出自带工具行`() {
        val base = java.nio.file.Files.createTempDirectory("agsh-bundled").toFile()
        try {
            val rt = ShellRuntime.create(base)
            assertEquals("未链入任何工具时应为空列表", emptyList<String>(), ShellPromptBuilder.bundledTools(rt))
            val prompt = ShellPromptBuilder.build(rt)
            assertFalse("空清单不应输出该行", prompt.contains("应用自带工具"))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `prefix_bin 链入工具时提示词列出短名`() {
        val base = java.nio.file.Files.createTempDirectory("agsh-bundled").toFile()
        try {
            val rt = ShellRuntime.create(base)
            val binDir = File(base, "usr/bin")
            // 模拟 installBundledExecutables 链入的短名可执行体（内容无关，只看清单）
            File(binDir, "proot").writeText("#!/bin/sh\n")
            File(binDir, "bash").writeText("#!/bin/sh\n")

            assertEquals(listOf("bash", "proot"), ShellPromptBuilder.bundledTools(rt))

            val prompt = ShellPromptBuilder.build(rt)
            assertTrue("应输出自带工具行", prompt.contains("应用自带工具：bash、proot"))
            assertTrue("应说明可直接调用", prompt.contains("已链接进 PATH"))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `目录型条目与子目录不算自带工具`() {
        val base = java.nio.file.Files.createTempDirectory("agsh-bundled").toFile()
        try {
            val rt = ShellRuntime.create(base)
            val binDir = File(base, "usr/bin")
            File(binDir, "git").writeText("x")
            File(binDir, "somedir").mkdirs() // 目录不算

            assertEquals(listOf("git"), ShellPromptBuilder.bundledTools(rt))
        } finally {
            base.deleteRecursively()
        }
    }
}
