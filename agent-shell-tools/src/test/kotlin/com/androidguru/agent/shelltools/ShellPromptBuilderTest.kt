package com.androidguru.agent.shelltools

import com.androidguru.agent.shell.ShellRuntime
import com.androidguru.agent.shelltools.prompt.ShellPromptBuilder
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShellPromptBuilderTest {

    @Test
    fun `提示词包含能力优先级与环境清单`() {
        val base = java.nio.file.Files.createTempDirectory("agsh-prompt").toFile()
        try {
            val rt = ShellRuntime.create(base)
            rt.sessions.create()
            val prompt = ShellPromptBuilder.build(rt)
            assertTrue(prompt.contains("能力优先级"))
            assertTrue(prompt.contains("job_start"))
            assertTrue(prompt.contains("terminal_exec 超时几乎总是该用 job_start 的信号"))
            assertTrue(prompt.contains("工作目录"))
            assertTrue(prompt.contains("确认不存在"))
            assertTrue(prompt.contains("安全边界"))
            assertTrue(prompt.contains("task_finish"))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `环境提示区分容器是否就绪`() {
        val base = java.nio.file.Files.createTempDirectory("agsh-prompt").toFile()
        try {
            val rt = ShellRuntime.create(base)
            val notes = ShellPromptBuilder.environmentNotes(rt)
            assertTrue("未装容器时应说明", notes.contains("未安装") || notes.contains("已安装"))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `additionalContext 被追加`() {
        val base = java.nio.file.Files.createTempDirectory("agsh-prompt").toFile()
        try {
            val rt = ShellRuntime.create(base)
            val prompt = ShellPromptBuilder.build(rt, additionalContext = "自定义上下文 XYZ")
            assertTrue(prompt.contains("XYZ"))
        } finally {
            base.deleteRecursively()
        }
    }
}
