package com.androidguru.agent.workflow

import kotlinx.coroutines.flow.toList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/**
 * 工作流库测试：保存 / 版本 / 统计 / 生命周期迁移（候选→激活→熔断→复活） /
 * 文件存储往返。
 */
class WorkflowLibraryTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private var now = 1000L
    private val clock = { now += 10; now }

    private fun def(id: String = "wf_a") = WorkflowDefinition(
        id = id,
        name = "工作流 $id",
        description = "desc",
        nodes = listOf(WorkflowNode.Tool(id = "n1", toolId = "t")),
        edges = listOf(WorkflowEdge(WorkflowEdge.START, "n1"), WorkflowEdge("n1", WorkflowEdge.END)),
    )

    @Test
    fun `新保存进候选态 v1`() {
        val lib = WorkflowLibrary(InMemoryWorkflowLibraryStore(), clock)
        val saved = lib.save(def())
        assertEquals(WorkflowLifecycle.CANDIDATE, saved.lifecycle)
        assertEquals(1, saved.version)
        assertEquals(0, saved.successCount)
    }

    @Test
    fun `同 id 更新版本递增且保留成功统计`() {
        val lib = WorkflowLibrary(InMemoryWorkflowLibraryStore(), clock)
        lib.save(def())
        lib.recordRunOutcome("wf_a", success = true)
        val updated = lib.save(def().copy(name = "工作流 v2"))
        assertEquals(2, updated.version)
        assertEquals(1, updated.successCount) // 历史信誉保留
        assertEquals(0, updated.consecutiveFailures) // 连败清零重新观察
    }

    @Test
    fun `累计两次成功晋升 ACTIVE`() {
        val lib = WorkflowLibrary(InMemoryWorkflowLibraryStore(), clock)
        lib.save(def())
        lib.recordRunOutcome("wf_a", success = true)
        assertEquals(WorkflowLifecycle.CANDIDATE, lib.get("wf_a")!!.lifecycle) // 1 次还不够
        val after = lib.recordRunOutcome("wf_a", success = true)
        assertEquals(WorkflowLifecycle.ACTIVE, after!!.lifecycle)
        assertNotNull(after.promotedAtMs)
    }

    @Test
    fun `激活后连续三次失败自动熔断 DISABLED`() {
        val lib = WorkflowLibrary(InMemoryWorkflowLibraryStore(), clock)
        lib.save(def())
        repeat(2) { lib.recordRunOutcome("wf_a", success = true) }
        lib.recordRunOutcome("wf_a", success = false, error = "err1")
        lib.recordRunOutcome("wf_a", success = false, error = "err2")
        assertEquals(WorkflowLifecycle.ACTIVE, lib.get("wf_a")!!.lifecycle) // 2 次还没到
        val after = lib.recordRunOutcome("wf_a", success = false, error = "err3")
        assertEquals(WorkflowLifecycle.DISABLED, after!!.lifecycle)
        assertTrue(after.lastError!!.contains("熔断"))
    }

    @Test
    fun `成功重置连败计数（间歇失败不熔断）`() {
        val lib = WorkflowLibrary(InMemoryWorkflowLibraryStore(), clock)
        lib.save(def())
        repeat(2) { lib.recordRunOutcome("wf_a", success = true) }
        // 失败-成功-失败-成功…永远到不了连续 3 次
        repeat(5) {
            lib.recordRunOutcome("wf_a", success = false)
            lib.recordRunOutcome("wf_a", success = true)
        }
        assertEquals(WorkflowLifecycle.ACTIVE, lib.get("wf_a")!!.lifecycle)
    }

    @Test
    fun `disable 一票否决 enable 一票复权直达 ACTIVE`() {
        val lib = WorkflowLibrary(InMemoryWorkflowLibraryStore(), clock)
        lib.save(def())
        lib.disable("wf_a", "人工不信任")
        assertEquals(WorkflowLifecycle.DISABLED, lib.get("wf_a")!!.lifecycle)
        assertTrue(lib.get("wf_a")!!.isExecutable().not())

        lib.enable("wf_a")
        assertEquals(WorkflowLifecycle.ACTIVE, lib.get("wf_a")!!.lifecycle)
    }

    @Test
    fun `list 默认排除禁用项`() {
        val lib = WorkflowLibrary(InMemoryWorkflowLibraryStore(), clock)
        lib.save(def("wf_a"))
        lib.save(def("wf_b"))
        lib.disable("wf_b")
        assertEquals(listOf("wf_a"), lib.list().map { it.definition.id })
        assertEquals(2, lib.list(includeDisabled = true).size)
    }

    @Test
    fun `recordRunOutcome 未知 id 返回 null 不新建`() {
        val lib = WorkflowLibrary(InMemoryWorkflowLibraryStore(), clock)
        assertNull(lib.recordRunOutcome("ghost", success = true))
        assertNull(lib.get("ghost"))
    }

    @Test
    fun `保存非法定义被拒（校验前置）`() {
        val lib = WorkflowLibrary(InMemoryWorkflowLibraryStore(), clock)
        val bad = WorkflowDefinition(id = "wf_bad", name = "x", nodes = emptyList(), edges = emptyList())
        try {
            lib.save(bad)
            throw AssertionError("应抛校验异常")
        } catch (e: WorkflowValidationException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun `文件存储往返含统计与生命周期`() {
        val dir = tmp.root.toPath().resolve("library")
        val lib = WorkflowLibrary(FileWorkflowLibraryStore(dir), clock)
        lib.save(def())
        repeat(3) { lib.recordRunOutcome("wf_a", success = true) }
        assertTrue(lib.get("wf_a")!!.lifecycle == WorkflowLifecycle.ACTIVE)

        // 新实例（模拟重启）读同一目录
        val lib2 = WorkflowLibrary(FileWorkflowLibraryStore(dir), clock)
        val loaded = lib2.get("wf_a")
        assertNotNull(loaded)
        assertEquals(3, loaded!!.successCount)
        assertEquals(WorkflowLifecycle.ACTIVE, loaded.lifecycle)
        assertTrue(Files.exists(dir.resolve("wf_a.workflow.json")))
    }

    @Test
    fun `文件损坏行被跳过不阻断`() {
        val dir = tmp.root.toPath().resolve("library")
        val store = FileWorkflowLibraryStore(dir)
        store.save(
            SavedWorkflow(
                definition = def("wf_ok"),
                createdAtMs = 1,
                updatedAtMs = 1,
            ),
        )
        Files.writeString(dir.resolve("broken.workflow.json"), "{ not json")
        assertEquals(1, store.list().size)
        assertNull(store.load("broken"))
    }

    @Test
    fun `成功率与总次数统计`() {
        val lib = WorkflowLibrary(InMemoryWorkflowLibraryStore(), clock)
        lib.save(def())
        lib.recordRunOutcome("wf_a", success = true)
        lib.recordRunOutcome("wf_a", success = true)
        lib.recordRunOutcome("wf_a", success = false)
        val saved = lib.get("wf_a")!!
        assertEquals(3, saved.totalRuns)
        assertEquals(2.0 / 3, saved.successRate, 1e-9)
    }
}

/** 建议注入器测试。 */
class WorkflowSuggesterTest {

    private fun def(id: String, desc: String = "desc-$id") = WorkflowDefinition(
        id = id,
        name = id,
        description = desc,
        nodes = listOf(WorkflowNode.Tool(id = "n1", toolId = "t")),
        edges = listOf(WorkflowEdge(WorkflowEdge.START, "n1"), WorkflowEdge("n1", WorkflowEdge.END)),
    )

    @Test
    fun `空库注入空串`() = kotlinx.coroutines.runBlocking {
        val suggester = WorkflowSuggester(WorkflowLibrary(InMemoryWorkflowLibraryStore()))
        assertEquals("", suggester.provideContext("s"))
    }

    @Test
    fun `只注入 ACTIVE 项且带统计`() = kotlinx.coroutines.runBlocking {
        val lib = WorkflowLibrary(InMemoryWorkflowLibraryStore())
        lib.save(def("active_one"))
        repeat(2) { lib.recordRunOutcome("active_one", success = true) }
        lib.save(def("candidate_one")) // 未激活不进索引
        lib.save(def("disabled_one"))
        lib.disable("disabled_one")

        val text = WorkflowSuggester(lib).provideContext("s")
        assertTrue(text.contains("active_one"))
        assertTrue(text.contains("run_workflow"))
        assertFalse(text.contains("candidate_one"))
        assertFalse(text.contains("disabled_one"))
        assertTrue(text.contains("成功 2/2"))
    }

    @Test
    fun `条数上限生效防膨胀`() = kotlinx.coroutines.runBlocking {
        val lib = WorkflowLibrary(InMemoryWorkflowLibraryStore())
        for (i in 1..10) {
            lib.save(def("wf_$i"))
            repeat(2) { lib.recordRunOutcome("wf_$i", success = true) }
        }
        val text = WorkflowSuggester(lib, maxEntries = 3).provideContext("s")
        assertEquals(3, Regex("- wf_").findAll(text).count())
    }
}

/** DSL 测试。 */
class WorkflowDslTest {

    @Test
    fun `DSL 构建出合法定义`() {
        val def = workflow("daily_report", name = "日报", description = "d") {
            param("channel", required = true)
            param("date", default = "today")
            setVarNode("init", mapOf("date" to "\${params.date}"))
            toolNode("gather", "collect", args = mapOf("date" to "\${vars.date}"), retry = retry(maxAttempts = 2))
            switchNode("route", "\${params.channel}", cases = mapOf("slack" to "via_slack"), defaultBranch = "via_email")
            toolNode("slack_path", "post_slack", args = mapOf("text" to "\${nodes.gather.output}"))
            edge(START, "init")
            edge("init" to "gather")
            edge("gather" to "route")
            edge("route", "slack_path", branch = "via_slack")
            edge("slack_path", END)
            maxSteps(30)
        }
        assertEquals("daily_report", def.id)
        assertEquals(4, def.nodes.size)
        assertEquals(5, def.edges.size)
        assertEquals(2, def.params.size)
        assertEquals(30, def.maxSteps)
        assertTrue(def.nodes[1] is WorkflowNode.Tool)
        assertEquals(2, (def.nodes[1] as WorkflowNode.Tool).retry!!.maxAttempts)
        assertTrue(def.nodes[2] is WorkflowNode.Switch)
        def.validate() // 不抛即通过
    }

    @Test
    fun `DSL 写错当场抛校验异常`() {
        try {
            workflow("bad", "坏") {
                toolNode("n1", "t")
                // 忘了连线 —— 不可达
            }
            throw AssertionError("应抛校验异常")
        } catch (e: WorkflowValidationException) {
            assertTrue(e.message!!.contains("不可达"))
        }
    }
}

/** WorkflowSystem 门面测试。 */
class WorkflowSystemTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `门面装配库引擎工具与建议器接线`() = kotlinx.coroutines.runBlocking {
        val registry = com.androidguru.agent.tools.DefaultToolRegistry()
        val noop = object : com.androidguru.agent.tools.AgentTool {
            override val id = "noop_tool"
            override val description = "空操作"
            override suspend fun execute(request: com.androidguru.agent.tools.ToolRequest) =
                com.androidguru.agent.tools.ToolResult.success("noop")
        }
        registry.register(noop)
        val system = WorkflowSystem(toolRegistry = registry, workspace = tmp.root.toPath())
        assertEquals(5, system.tools.size)
        system.tools.forEach { registry.register(it) }

        // 工具五件套注册成功（run_workflow 在注册表可寻址）
        assertNotNull(registry.getTool("run_workflow"))

        // 引擎终态自动更新库统计
        val def = workflow("hello_wf", "你好") {
            toolNode("n1", "noop_tool")
            edge(START, "n1")
            edge("n1", END)
        }
        system.library.save(def)
        system.engine.start(def).toList()
        system.engine.start(def).toList()
        assertEquals(2, system.library.get("hello_wf")!!.successCount)
        assertEquals(WorkflowLifecycle.ACTIVE, system.library.get("hello_wf")!!.lifecycle) // 2 次成功晋升
    }

    @Test
    fun `distillFromConversation 宏回退可入库`() = kotlinx.coroutines.runBlocking {
        val system = WorkflowSystem(workspace = tmp.root.toPath())
        val messages = listOf(
            com.androidguru.agent.llm.LlmMessage.User("整理日报"),
            com.androidguru.agent.llm.LlmMessage.Assistant(
                toolCalls = listOf(
                    com.androidguru.agent.llm.ToolCall(id = "c1", name = "collect_metrics", arguments = """{"date":"2024-06-01"}"""),
                ),
            ),
            com.androidguru.agent.llm.LlmMessage.Tool("c1", """{"dau":1}"""),
            com.androidguru.agent.llm.LlmMessage.Assistant(
                toolCalls = listOf(
                    com.androidguru.agent.llm.ToolCall(id = "c2", name = "send_email", arguments = """{"to":"ops"}"""),
                ),
            ),
            com.androidguru.agent.llm.LlmMessage.Tool("c2", "ok"),
            com.androidguru.agent.llm.LlmMessage.Assistant("done"),
        )
        val saved = system.distillFromConversation(messages, "整理日报并发送")
        assertNotNull(saved)
        assertEquals(WorkflowLifecycle.CANDIDATE, saved!!.lifecycle)
        assertEquals(2, saved.definition.nodes.size)
        // 入库可读
        assertEquals(saved.definition.id, system.library.get(saved.definition.id)!!.definition.id)
    }

    @Test
    fun `recoverInterruptedRuns 扫描残留`() = kotlinx.coroutines.runBlocking {
        val runsDir = tmp.root.toPath().resolve("runs")
        val store = FileWorkflowRunStore(runsDir)
        val system = WorkflowSystem(workspace = tmp.root.toPath())
        // 纯 setVar 工作流（无工具依赖，稳定成功）
        val def = workflow("crash_wf", "崩溃") {
            setVarNode("n1", mapOf("x" to "1"))
            edge(START, "n1")
            edge("n1", END)
        }
        val events = system.engine.start(def).toList()
        val runId = events.filterIsInstance<WorkflowEvent.RunSucceeded>().first().runId
        // 手造 RUNNING 残留（复用同一 store 的独立引擎写回）
        val state = store.load(runId)!!
        FileWorkflowRunStore(runsDir).save(state.copy(status = RunStatus.RUNNING))

        val crashed = system.recoverInterruptedRuns()
        assertEquals(listOf(runId), crashed)
        assertEquals(RunStatus.CRASHED, system.engine.run(runId)!!.status)
    }
}
