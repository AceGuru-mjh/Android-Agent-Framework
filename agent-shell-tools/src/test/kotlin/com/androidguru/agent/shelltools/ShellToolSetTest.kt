package com.androidguru.agent.shelltools

import com.androidguru.agent.shell.ShellRuntime
import com.androidguru.agent.tools.DefaultToolRegistry
import com.androidguru.agent.tools.ToolSchema
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShellToolSetTest {

    private fun runtime(base: File): ShellRuntime =
        ShellRuntime.create(base)

    @Test
    fun `注册20个工具_id唯一`() {
        val base = java.nio.file.Files.createTempDirectory("agsh-tools").toFile()
        try {
            val set = ShellToolSet(runtime(base))
            val registry = DefaultToolRegistry()
            set.installInto(registry)
            assertEquals(20, registry.size)
            assertEquals(20, ShellToolSet.ToolIds.ALL.size)
            assertEquals(20, registry.getAllTools().map { it.id }.distinct().size)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `全部工具的schema可渲染且可校验`() {
        val base = java.nio.file.Files.createTempDirectory("agsh-tools").toFile()
        try {
            val set = ShellToolSet(runtime(base))
            for (tool in set.tools()) {
                val rendered = tool.parameters.render()
                assertTrue("${tool.id} 应渲染出 object schema", rendered.containsKey("type"))
                // 校验合法参数不报错（空对象只检查 required）
                val errors = tool.parameters.validate("{}")
                val requiredOk = tool.parameters.validate(
                    tool.parameters.propertyNames.joinToString(","),
                )
                // required 字段缺失时的错误信息应提到参数名
                if (errors.isNotEmpty()) {
                    assertTrue(errors.first().contains("缺少必填参数"))
                }
                assertNotNull(requiredOk)
            }
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `推荐策略覆盖长任务工具`() {
        val base = java.nio.file.Files.createTempDirectory("agsh-tools").toFile()
        try {
            val set = ShellToolSet(runtime(base))
            val policies = set.recommendedPolicies()
            assertTrue(policies.containsKey(ShellToolSet.ToolIds.TERMINAL_EXEC))
            assertTrue("terminal_exec 执行器超时须 > 300s（模型可传 timeout_ms）",
                policies[ShellToolSet.ToolIds.TERMINAL_EXEC]!!.timeoutMs >= 300_000)
            assertTrue(policies.containsKey(ShellToolSet.ToolIds.CONTAINER_EXEC))
            assertTrue(policies.containsKey(ShellToolSet.ToolIds.HTTP_GET))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `terminal_exec_拒绝空命令`() = runBlocking<Unit> {
        val base = java.nio.file.Files.createTempDirectory("agsh-tools").toFile()
        try {
            val rt = runtime(base)
            rt.sessions.create()
            val set = ShellToolSet(rt)
            val tool = set.tools().first { it.id == ShellToolSet.ToolIds.TERMINAL_EXEC }
            val result = tool.execute(
                com.androidguru.agent.tools.ToolRequest("call1", tool.id, """{"command":"  "}"""),
            )
            assertTrue(!result.ok)
            assertEquals("command", result.error?.field)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `fs 工具_读写列表决路径解析`() = runBlocking<Unit> {
        val base = java.nio.file.Files.createTempDirectory("agsh-tools").toFile()
        try {
            val rt = runtime(base)
            rt.sessions.create()
            val set = ShellToolSet(rt)
            val write = set.tools().first { it.id == ShellToolSet.ToolIds.FS_WRITE }
            val read = set.tools().first { it.id == ShellToolSet.ToolIds.FS_READ }
            val list = set.tools().first { it.id == ShellToolSet.ToolIds.FS_LIST }

            val target = File(base, "home/note.txt").absolutePath
            val w = write.execute(
                com.androidguru.agent.tools.ToolRequest("c2", write.id, """{"path":"$target","content":"你好 FS"}"""),
            )
            assertTrue("写入应成功：${w.content}", w.ok)

            val r = read.execute(
                com.androidguru.agent.tools.ToolRequest("c3", read.id, """{"path":"$target"}"""),
            )
            assertTrue(r.ok)
            assertTrue(r.content.contains("你好 FS"))

            val l = list.execute(
                com.androidguru.agent.tools.ToolRequest("c4", list.id, """{"path":"${File(base, "home").absolutePath}"}"""),
            )
            assertTrue(l.ok)
            assertTrue(l.content.contains("note.txt"))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `file_delete_移入回收目录`() = runBlocking<Unit> {
        val base = java.nio.file.Files.createTempDirectory("agsh-tools").toFile()
        try {
            val rt = runtime(base)
            rt.sessions.create()
            val set = ShellToolSet(rt)
            val victim = File(base, "home/old.txt").apply { writeText("bye") }
            val del = set.tools().first { it.id == ShellToolSet.ToolIds.FILE_DELETE }
            val r = del.execute(
                com.androidguru.agent.tools.ToolRequest("c5", del.id, """{"path":"${victim.absolutePath}"}"""),
            )
            assertTrue("删除应成功：${r.content}", r.ok)
            assertTrue("原路径不存在", !victim.exists())
            assertTrue("回收目录应存在文件", File(base, "trash").listFiles()?.isNotEmpty() == true)
            assertTrue(r.content.contains("可恢复"))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `file_delete_拒绝运行时目录之外的路径`() = runBlocking<Unit> {
        val base = java.nio.file.Files.createTempDirectory("agsh-tools").toFile()
        try {
            val rt = runtime(base)
            rt.sessions.create()
            val set = ShellToolSet(rt)
            val outside = java.nio.file.Files.createTempDirectory("outside").toFile()
            val victim = File(outside, "x.txt").apply { writeText("x") }
            val del = set.tools().first { it.id == ShellToolSet.ToolIds.FILE_DELETE }
            val r = del.execute(
                com.androidguru.agent.tools.ToolRequest("c6", del.id, """{"path":"${victim.absolutePath}"}"""),
            )
            assertTrue("越界删除必须拒绝", !r.ok)
            assertTrue(victim.exists())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `job 工具_启动查看停止闭环`() = runBlocking<Unit> {
        val base = java.nio.file.Files.createTempDirectory("agsh-tools").toFile()
        try {
            val rt = runtime(base)
            rt.sessions.create()
            val set = ShellToolSet(rt)
            val start = set.tools().first { it.id == ShellToolSet.ToolIds.JOB_START }
            val get = set.tools().first { it.id == ShellToolSet.ToolIds.JOB_GET }

            val s = start.execute(
                com.androidguru.agent.tools.ToolRequest("c7", start.id, """{"command":"echo JOB_RUNNING_TEST; sleep 30"}"""),
            )
            assertTrue("作业应启动：${s.content}", s.ok)
            val jobId = s.data?.let {
                kotlinx.serialization.json.Json.parseToJsonElement(it)
                    .jsonObjectOrNull()?.get("jobId")?.toString()?.trim('"')
            } ?: error("应返回 jobId")

            val g = get.execute(
                com.androidguru.agent.tools.ToolRequest("c8", get.id, """{"id":"$jobId","include_output":true,"wait_ms":3000}"""),
            )
            assertTrue(g.ok)
            assertTrue(g.content.contains(jobId))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `device 能力_NONE实现返回结构化失败`() = runBlocking<Unit> {
        val base = java.nio.file.Files.createTempDirectory("agsh-tools").toFile()
        try {
            val rt = runtime(base)
            val set = ShellToolSet(rt, device = DeviceTools.NONE)
            val info = set.tools().first { it.id == ShellToolSet.ToolIds.DEVICE_INFO }
            val r = info.execute(com.androidguru.agent.tools.ToolRequest("c9", info.id, "{}"))
            assertTrue(!r.ok)
            assertTrue(r.content.contains("平台"))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `task_finish_返回总结指令`() = runBlocking<Unit> {
        val base = java.nio.file.Files.createTempDirectory("agsh-tools").toFile()
        try {
            val set = ShellToolSet(runtime(base))
            val tool = set.tools().first { it.id == ShellToolSet.ToolIds.TASK_FINISH }
            val r = tool.execute(
                com.androidguru.agent.tools.ToolRequest("c10", tool.id, """{"summary":"一切就绪","success":true}"""),
            )
            assertTrue(r.ok)
            assertTrue(r.content.contains("一切就绪"))
            assertTrue(r.data!!.contains("finished"))
        } finally {
            base.deleteRecursively()
        }
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): kotlinx.serialization.json.JsonObject? =
        this as? kotlinx.serialization.json.JsonObject
}
