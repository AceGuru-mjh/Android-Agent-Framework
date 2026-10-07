package com.androidguru.agent.workflow

import com.androidguru.agent.tools.AgentTool
import com.androidguru.agent.tools.DefaultToolRegistry
import com.androidguru.agent.tools.ToolErrorCode
import com.androidguru.agent.tools.ToolRequest
import com.androidguru.agent.tools.ToolResult
import com.androidguru.agent.tools.ToolSchema
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/**
 * 引擎调度测试：线性 / 并行扇出 / 分支路由 / 重试 / 失败处置三态 /
 * human-in-loop / 崩溃恢复 / 熔断 / 参数校验。
 */
class WorkflowEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    /** 计数假工具（可注入失败脚本）。 */
    private class CountingTool(
        override val id: String,
        private val failTimes: Int = 0,
        private val failCode: ToolErrorCode = ToolErrorCode.TIMEOUT,
        private val delayMs: Long = 0,
        val counter: Counter = Counter(),
    ) : AgentTool {
        override val description = "测试工具 $id"
        override val parameters: ToolSchema = ToolSchema.build {
            string("text", "文本", required = false)
        }

        override suspend fun execute(request: ToolRequest): ToolResult {
            counter.calls++
            if (delayMs > 0) Thread.sleep(delayMs)
            if (counter.calls <= failTimes) {
                return ToolResult.failure("第 ${counter.calls} 次失败", failCode)
            }
            return ToolResult.success("$id ok #${counter.calls}", data = """{"tool":"$id","n":${counter.calls}}""")
        }
    }

    private class Counter {
        @Volatile
        var calls: Int = 0
    }

    private fun registryOf(vararg tools: AgentTool): DefaultToolRegistry =
        DefaultToolRegistry().also { tools.forEach { t -> it.register(t) } }

    private fun linearDef(toolId: String, nodes: Int, retry: RetryPolicy? = null): WorkflowDefinition {
        val nodeList = (1..nodes).map {
            WorkflowNode.Tool(id = "n$it", toolId = toolId, arguments = emptyJson(), retry = retry)
        }
        val edges = buildList {
            add(WorkflowEdge(WorkflowEdge.START, "n1"))
            for (i in 2..nodes) add(WorkflowEdge("n${i - 1}", "n$i"))
            add(WorkflowEdge("n$nodes", WorkflowEdge.END))
        }
        return WorkflowDefinition(id = "wf_linear", name = "线性", nodes = nodeList, edges = edges)
    }

    private fun emptyJson() = kotlinx.serialization.json.JsonObject(emptyMap())

    // ------------------------------------------------------------------
    // 线性执行
    // ------------------------------------------------------------------

    @Test
    fun `线性链顺序执行且事件齐备`() = runBlocking {
        val tool = CountingTool("t1")
        val engine = WorkflowEngine(toolRegistry = registryOf(tool))
        val events = engine.start(linearDef("t1", 3)).toList()

        assertTrue(events.first() is WorkflowEvent.RunStarted)
        assertEquals(3, events.count { it is WorkflowEvent.NodeStarted })
        assertEquals(3, events.count { it is WorkflowEvent.NodeCompleted })
        val done = events.filterIsInstance<WorkflowEvent.RunSucceeded>().single()
        assertEquals(3, tool.counter.calls)
        assertTrue(done.summary.contains("3/3"))
        assertEquals(RunStatus.SUCCEEDED, engine.run(done.runId)!!.status)
    }

    @Test
    fun `节点输出流入下游参数（模板传递）`() = runBlocking {
        val seen = mutableListOf<String>()
        val probe = object : AgentTool {
            override val id = "probe"
            override val description = "探针"
            override val parameters = ToolSchema.build { string("text", "t") }
            override suspend fun execute(request: ToolRequest): ToolResult {
                seen += request.arguments
                return ToolResult.success("ok", data = """{"tool":"probe","n":1}""")
            }
        }
        val def = WorkflowDefinition(
            id = "wf_chain", name = "链",
            nodes = listOf(
                WorkflowNode.Tool(
                    id = "a",
                    toolId = "probe",
                    arguments = kotlinx.serialization.json.JsonObject(
                        mapOf("text" to kotlinx.serialization.json.JsonPrimitive("\${params.seed}")),
                    ),
                ),
                WorkflowNode.Tool(
                    id = "b",
                    toolId = "probe",
                    arguments = kotlinx.serialization.json.JsonObject(
                        mapOf("text" to kotlinx.serialization.json.JsonPrimitive("got:\${nodes.a.output.tool}:\${nodes.a.output.n}")),
                    ),
                ),
            ),
            edges = listOf(
                WorkflowEdge(WorkflowEdge.START, "a"),
                WorkflowEdge("a", "b"),
                WorkflowEdge("b", WorkflowEdge.END),
            ),
            params = listOf(ParamSpec("seed", required = true)),
        )
        val engine = WorkflowEngine(toolRegistry = registryOf(probe))
        val events = engine.start(def, mapOf("seed" to "hello")).toList()
        assertTrue(events.last() is WorkflowEvent.RunSucceeded)
        assertTrue(seen[0].contains("hello"))
        assertTrue(seen[1].contains("got:probe:1"))
    }

    // ------------------------------------------------------------------
    // 并行扇出
    // ------------------------------------------------------------------

    @Test
    fun `无依赖节点并行扇出且汇聚`() = runBlocking {
        val t1 = CountingTool("p1", delayMs = 60)
        val t2 = CountingTool("p2", delayMs = 60)
        val join = CountingTool("join")
        val def = WorkflowDefinition(
            id = "wf_fan", name = "扇出",
            nodes = listOf(
                WorkflowNode.Tool(id = "a", toolId = "p1"),
                WorkflowNode.Tool(id = "b", toolId = "p2"),
                WorkflowNode.Tool(id = "c", toolId = "join"),
            ),
            edges = listOf(
                WorkflowEdge(WorkflowEdge.START, "a"),
                WorkflowEdge(WorkflowEdge.START, "b"),
                WorkflowEdge("a", "c"),
                WorkflowEdge("b", "c"), // AND-join：a、b 都完成才跑 c
                WorkflowEdge("c", WorkflowEdge.END),
            ),
        )
        val engine = WorkflowEngine(toolRegistry = registryOf(t1, t2, join))
        val events = engine.start(def).toList()
        assertTrue(events.last() is WorkflowEvent.RunSucceeded)
        assertEquals(1, t1.counter.calls)
        assertEquals(1, t2.counter.calls)
        assertEquals(1, join.counter.calls) // 恰好一次（不重复触发）
        // 两个并行节点同超步启动
        val started = events.filterIsInstance<WorkflowEvent.NodeStarted>()
        assertEquals(setOf("a", "b"), started.take(2).map { it.nodeId }.toSet())
    }

    // ------------------------------------------------------------------
    // 分支路由
    // ------------------------------------------------------------------

    @Test
    fun `switch 命中分支且路由决策持久化`() = runBlocking {
        val slack = CountingTool("slack")
        val email = CountingTool("email")
        val def = WorkflowDefinition(
            id = "wf_route", name = "路由",
            params = listOf(ParamSpec("channel", required = true)),
            nodes = listOf(
                WorkflowNode.Switch(
                    id = "route",
                    expression = "\${params.channel}",
                    cases = listOf(WorkflowNode.Switch.Case("slack", "via_slack")),
                    defaultBranch = "via_email",
                ),
                WorkflowNode.Tool(id = "to_slack", toolId = "slack"),
                WorkflowNode.Tool(id = "to_email", toolId = "email"),
            ),
            edges = listOf(
                WorkflowEdge(WorkflowEdge.START, "route"),
                WorkflowEdge("route", "to_slack", branch = "via_slack"),
                WorkflowEdge("route", "to_email", branch = "via_email"),
                WorkflowEdge("to_slack", WorkflowEdge.END),
                WorkflowEdge("to_email", WorkflowEdge.END),
            ),
        )
        val engine = WorkflowEngine(toolRegistry = registryOf(slack, email))
        val events = engine.start(def, mapOf("channel" to "slack")).toList()
        assertTrue(events.last() is WorkflowEvent.RunSucceeded)
        assertEquals(1, slack.counter.calls)
        assertEquals(0, email.counter.calls)
        assertEquals("via_slack", engine.run(events.filterIsInstance<WorkflowEvent.RunSucceeded>().first().runId)!!.nodeStates["route"]!!.branch)
    }

    @Test
    fun `switch 无匹配且无 default 时下游不执行但运行成功收尾`() = runBlocking {
        val a = CountingTool("ta")
        val def = WorkflowDefinition(
            id = "wf_nodefault", name = "无默认分支",
            params = listOf(ParamSpec("channel", required = true)),
            nodes = listOf(
                WorkflowNode.Switch(
                    id = "route",
                    expression = "\${params.channel}",
                    cases = listOf(WorkflowNode.Switch.Case("slack", "via_slack")),
                ),
                WorkflowNode.Tool(id = "to_slack", toolId = "ta"),
            ),
            edges = listOf(
                WorkflowEdge(WorkflowEdge.START, "route"),
                WorkflowEdge("route", "to_slack", branch = "via_slack"),
                WorkflowEdge("to_slack", WorkflowEdge.END),
            ),
        )
        val engine = WorkflowEngine(toolRegistry = registryOf(a))
        val events = engine.start(def, mapOf("channel" to "email")).toList()
        assertTrue(events.last() is WorkflowEvent.RunSucceeded) // 路由到无处 = 早终，不是失败
        assertEquals(0, a.counter.calls)
    }

    // ------------------------------------------------------------------
    // 重试
    // ------------------------------------------------------------------

    @Test
    fun `瞬时失败按策略重试后成功`() = runBlocking {
        val flaky = CountingTool("flaky", failTimes = 2)
        val def = WorkflowDefinition(
            id = "wf_retry", name = "重试",
            nodes = listOf(
                WorkflowNode.Tool(
                    id = "n1",
                    toolId = "flaky",
                    retry = RetryPolicy(maxAttempts = 3, initialDelayMs = 5, jitter = false),
                ),
            ),
            edges = listOf(WorkflowEdge(WorkflowEdge.START, "n1"), WorkflowEdge("n1", WorkflowEdge.END)),
        )
        val engine = WorkflowEngine(toolRegistry = registryOf(flaky))
        val events = engine.start(def).toList()
        assertTrue(events.last() is WorkflowEvent.RunSucceeded)
        assertEquals(3, flaky.counter.calls)
        val failures = events.filterIsInstance<WorkflowEvent.NodeFailed>()
        assertEquals(2, failures.size)
        assertTrue(failures.all { it.willRetry })
    }

    @Test
    fun `非可重试错误码立即失败不重试`() = runBlocking {
        val broken = CountingTool("broken", failTimes = 99, failCode = ToolErrorCode.PERMISSION)
        val def = WorkflowDefinition(
            id = "wf_noretry", name = "不重试",
            nodes = listOf(
                WorkflowNode.Tool(
                    id = "n1",
                    toolId = "broken",
                    retry = RetryPolicy(maxAttempts = 5, initialDelayMs = 5),
                ),
            ),
            edges = listOf(WorkflowEdge(WorkflowEdge.START, "n1"), WorkflowEdge("n1", WorkflowEdge.END)),
        )
        val engine = WorkflowEngine(toolRegistry = registryOf(broken))
        val events = engine.start(def).toList()
        assertTrue(events.last() is WorkflowEvent.RunFailed)
        assertEquals(1, broken.counter.calls)
    }

    @Test
    fun `FAIL_RUN 失败运行并收拢并行兄弟为 SKIPPED`() = runBlocking {
        val flaky = CountingTool("always_fail", failTimes = 99)
        val slow = CountingTool("slow", delayMs = 300)
        val def = WorkflowDefinition(
            id = "wf_failrun", name = "失败运行",
            nodes = listOf(
                WorkflowNode.Tool(id = "x", toolId = "always_fail"),
                WorkflowNode.Tool(id = "y", toolId = "slow"),
            ),
            edges = listOf(
                WorkflowEdge(WorkflowEdge.START, "x"),
                WorkflowEdge(WorkflowEdge.START, "y"),
                WorkflowEdge("x", WorkflowEdge.END),
                WorkflowEdge("y", WorkflowEdge.END),
            ),
            maxSteps = 10,
        )
        val engine = WorkflowEngine(toolRegistry = registryOf(flaky, slow))
        val events = engine.start(def).toList()
        val failed = events.filterIsInstance<WorkflowEvent.RunFailed>().single()
        assertEquals("x", failed.failedNodeId)
        // FAIL_RUN 时并行兄弟被收拢为 SKIPPED，不留 RUNNING 残影
        val run = engine.run(failed.runId)!!
        // y 从未启动（单线程调度下 x 先失败即取消兄弟）→ PENDING，不是 RUNNING 残影
        assertEquals(NodeStatus.PENDING, run.nodeStates["y"]!!.status)
    }

    // ------------------------------------------------------------------
    // 失败处置三态
    // ------------------------------------------------------------------

    @Test
    fun `CONTINUE 失败标记 SKIPPED 且下游继续`() = runBlocking {
        val failing = CountingTool("failing", failTimes = 99)
        val after = CountingTool("after")
        val def = WorkflowDefinition(
            id = "wf_continue", name = "跳过继续",
            nodes = listOf(
                WorkflowNode.Tool(id = "a", toolId = "failing", onError = NodeOnError.CONTINUE),
                WorkflowNode.Tool(id = "b", toolId = "after"),
            ),
            edges = listOf(
                WorkflowEdge(WorkflowEdge.START, "a"),
                WorkflowEdge("a", "b"),
                WorkflowEdge("b", WorkflowEdge.END),
            ),
        )
        val engine = WorkflowEngine(toolRegistry = registryOf(failing, after))
        val events = engine.start(def).toList()
        assertTrue(events.last() is WorkflowEvent.RunSucceeded)
        assertEquals(1, after.counter.calls)
        val run = engine.run(events.filterIsInstance<WorkflowEvent.RunSucceeded>().first().runId)!!
        assertEquals(NodeStatus.SKIPPED, run.nodeStates["a"]!!.status)
    }

    @Test
    fun `ERROR_BRANCH 失败路由进错误分支`() = runBlocking {
        val failing = CountingTool("failing2", failTimes = 99)
        val recovery = CountingTool("recovery")
        val normal = CountingTool("normal")
        val def = WorkflowDefinition(
            id = "wf_errbranch", name = "错误分支",
            nodes = listOf(
                WorkflowNode.Tool(id = "a", toolId = "failing2", onError = NodeOnError.ERROR_BRANCH),
                WorkflowNode.Tool(id = "normal_path", toolId = "normal"),
                WorkflowNode.Tool(id = "recovery_path", toolId = "recovery"),
            ),
            edges = listOf(
                WorkflowEdge(WorkflowEdge.START, "a"),
                WorkflowEdge("a", "normal_path"),
                WorkflowEdge("a", "recovery_path", branch = WorkflowEdge.ERROR_BRANCH),
                WorkflowEdge("normal_path", WorkflowEdge.END),
                WorkflowEdge("recovery_path", WorkflowEdge.END),
            ),
        )
        val engine = WorkflowEngine(toolRegistry = registryOf(failing, recovery, normal))
        val events = engine.start(def).toList()
        assertTrue(events.last() is WorkflowEvent.RunSucceeded) // 失败被错误分支接管 → 运行仍成功
        assertEquals(1, recovery.counter.calls)
        assertEquals(0, normal.counter.calls)
    }

    @Test
    fun `模板解析错误不重试（参数性错误换参才有救）`() = runBlocking {
        val probe = CountingTool("probe2")
        val def = WorkflowDefinition(
            id = "wf_badtpl", name = "坏模板",
            nodes = listOf(
                WorkflowNode.Tool(
                    id = "n1",
                    toolId = "probe2",
                    arguments = kotlinx.serialization.json.JsonObject(
                        mapOf("text" to kotlinx.serialization.json.JsonPrimitive("\${vars.nonexistent}")),
                    ),
                    retry = RetryPolicy(maxAttempts = 5, initialDelayMs = 5),
                ),
            ),
            edges = listOf(WorkflowEdge(WorkflowEdge.START, "n1"), WorkflowEdge("n1", WorkflowEdge.END)),
        )
        val engine = WorkflowEngine(toolRegistry = registryOf(probe))
        val events = engine.start(def).toList()
        assertTrue(events.last() is WorkflowEvent.RunFailed)
        assertEquals(0, probe.counter.calls) // 模板都没解析出来，工具一次都不该跑
    }

    // ------------------------------------------------------------------
    // human-in-the-loop
    // ------------------------------------------------------------------

    @Test
    fun `human 节点暂停运行并携答案恢复`() = runBlocking {
        val before = CountingTool("before")
        val after = CountingTool("after")
        val def = WorkflowDefinition(
            id = "wf_human", name = "人工",
            nodes = listOf(
                WorkflowNode.Tool(id = "a", toolId = "before"),
                WorkflowNode.Human(id = "gate", prompt = "继续吗？"),
                WorkflowNode.Tool(id = "b", toolId = "after"),
            ),
            edges = listOf(
                WorkflowEdge(WorkflowEdge.START, "a"),
                WorkflowEdge("a", "gate"),
                WorkflowEdge("gate", "b"),
                WorkflowEdge("b", WorkflowEdge.END),
            ),
        )
        val engine = WorkflowEngine(toolRegistry = registryOf(before, after))
        val events1 = engine.start(def).toList()
        val waiting = events1.filterIsInstance<WorkflowEvent.RunWaiting>().single()
        assertEquals("gate", waiting.nodeId)

        val run = engine.run(waiting.runId)!!
        assertEquals(RunStatus.WAITING_HUMAN, run.status)
        assertEquals("gate", run.waitingNodeId)

        // WAITING_HUMAN 恢复必须带答案
        try {
            engine.resume(waiting.runId)
            throw AssertionError("应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("humanAnswer"))
        }

        val events2 = engine.resume(waiting.runId, humanAnswer = "同意").toList()
        assertTrue(events2.first() is WorkflowEvent.RunResumed)
        assertTrue(events2.last() is WorkflowEvent.RunSucceeded)
        assertEquals(1, after.counter.calls)
        // 答案写进节点输出
        val final = engine.run(waiting.runId)!!
        assertEquals("同意", (final.nodeStates["gate"]!!.output as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `human 等待提示中的模板被解析`() = runBlocking<Unit> {
        val def = WorkflowDefinition(
            id = "wf_human_tpl", name = "人工模板",
            params = listOf(ParamSpec("who", required = true)),
            nodes = listOf(WorkflowNode.Human(id = "gate", prompt = "确认要发给 \${params.who} 吗？")),
            edges = listOf(WorkflowEdge(WorkflowEdge.START, "gate"), WorkflowEdge("gate", WorkflowEdge.END)),
        )
        val engine = WorkflowEngine()
        val events = engine.start(def, mapOf("who" to "alice")).toList()
        val waiting = events.filterIsInstance<WorkflowEvent.RunWaiting>().single()
        assertTrue(waiting.prompt.contains("alice"))
        engine.resume(waiting.runId, "yes").toList()
    }

    // ------------------------------------------------------------------
    // 崩溃恢复
    // ------------------------------------------------------------------

    @Test
    fun `恢复时 SUCCEEDED 不重跑 RUNNING 回退重跑`() = runBlocking {
        val store = FileWorkflowRunStore(tmp.root.toPath().resolve("runs"))
        val t = CountingTool("t_resume")
        val engine1 = WorkflowEngine(toolRegistry = registryOf(t), runStore = store)

        val def = WorkflowDefinition(
            id = "wf_crash", name = "崩溃",
            nodes = listOf(
                WorkflowNode.Tool(id = "a", toolId = "t_resume"),
                WorkflowNode.Tool(id = "b", toolId = "t_resume"),
                WorkflowNode.Tool(id = "c", toolId = "t_resume"),
            ),
            edges = listOf(
                WorkflowEdge(WorkflowEdge.START, "a"),
                WorkflowEdge("a", "b"),
                WorkflowEdge("b", "c"),
                WorkflowEdge("c", WorkflowEdge.END),
            ),
        )
        // 跑到 b 成功后人为构造崩溃现场：c 置 RUNNING、状态 RUNNING
        var runId: String? = null
        engine1.start(def).toList().forEach { if (it is WorkflowEvent.RunStarted) runId = it.runId }
        // 先完整跑完一次（成功）
        val done = runId!!
        assertEquals(3, t.counter.calls)

        // 构造崩溃现场：从终态改回（真实场景是节点执行中 kill 进程）
        val crashed = engine1.run(done)!!.let { state ->
            state.copy(
                status = RunStatus.RUNNING,
                nodeStates = state.nodeStates.toMutableMap().apply {
                    put("a", this["a"]!!.copy(status = NodeStatus.SUCCEEDED))
                    put("b", this["b"]!!.copy(status = NodeStatus.RUNNING)) // 崩溃窗口
                    put("c", this["c"]!!.copy(status = NodeStatus.PENDING))
                },
            )
        }
        store.save(crashed)

        // 新引擎（模拟进程重启）：扫描重分类 → resume
        val engine2 = WorkflowEngine(toolRegistry = registryOf(t), runStore = store)
        assertEquals(listOf(done), engine2.recoverInterrupted())

        val callsBefore = t.counter.calls
        val events = engine2.resume(done).toList()
        assertTrue(events.last() is WorkflowEvent.RunSucceeded)
        // a 不重跑（SUCCEEDED），b 重跑（RUNNING 回退），c 首跑
        assertEquals(callsBefore + 2, t.counter.calls)
    }

    @Test
    fun `RUNNING 残留先 recover 再 resume（直接 resume 被拒）`() = runBlocking {
        val store = InMemoryWorkflowRunStore()
        val t = CountingTool("t_guard")
        val engine = WorkflowEngine(toolRegistry = registryOf(t), runStore = store)
        val def = linearDef("t_guard", 1)
        engine.start(def).toList()

        // 造 RUNNING 残留
        val run = store.list().first()
        store.save(run.copy(status = RunStatus.RUNNING))
        try {
            engine.resume(run.runId)
            throw AssertionError("应拒绝直接 resume RUNNING")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("recoverInterrupted"))
        }
    }

    @Test
    fun `终态运行不可恢复`() = runBlocking {
        val t = CountingTool("t_terminal")
        val engine = WorkflowEngine(toolRegistry = registryOf(t))
        val events = engine.start(linearDef("t_terminal", 1)).toList()
        val runId = events.filterIsInstance<WorkflowEvent.RunSucceeded>().first().runId
        try {
            engine.resume(runId)
            throw AssertionError("应拒绝")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("终态"))
        }
    }

    // ------------------------------------------------------------------
    // 熔断与参数校验
    // ------------------------------------------------------------------

    @Test
    fun `超步数熔断（不收敛护栏）`() = runBlocking {
        // 循环定义：a → b → a …（回边合法）
        val t = CountingTool("t_loop")
        val def = WorkflowDefinition(
            id = "wf_loop", name = "循环",
            nodes = listOf(
                WorkflowNode.Tool(id = "a", toolId = "t_loop"),
                WorkflowNode.Tool(id = "b", toolId = "t_loop"),
            ),
            edges = listOf(
                WorkflowEdge(WorkflowEdge.START, "a"),
                WorkflowEdge("a", "b"),
                WorkflowEdge("b", "a"), // 回边 → 无限循环
            ),
            maxSteps = 5,
        )
        val engine = WorkflowEngine(toolRegistry = registryOf(t))
        val events = engine.start(def).toList()
        val failed = events.filterIsInstance<WorkflowEvent.RunFailed>().single()
        assertTrue(failed.error.contains("熔断"))
    }

    @Test
    fun `缺少必填参数启动即拒`() {
        val t = CountingTool("t_param")
        val def = WorkflowDefinition(
            id = "wf_params", name = "参数",
            params = listOf(ParamSpec("must", required = true)),
            nodes = listOf(WorkflowNode.Tool(id = "n1", toolId = "t_param")),
            edges = listOf(WorkflowEdge(WorkflowEdge.START, "n1"), WorkflowEdge("n1", WorkflowEdge.END)),
        )
        val engine = WorkflowEngine(toolRegistry = registryOf(t))
        try {
            engine.start(def, emptyMap())
            throw AssertionError("应抛 IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("must"))
        }
    }

    @Test
    fun `未声明参数被拒且默认值生效`() = runBlocking {
        val t = CountingTool("t_defaults")
        val def = WorkflowDefinition(
            id = "wf_defaults", name = "默认值",
            params = listOf(ParamSpec("date", defaultValue = "2024-01-01")),
            nodes = listOf(
                WorkflowNode.Tool(
                    id = "n1",
                    toolId = "t_defaults",
                    arguments = kotlinx.serialization.json.JsonObject(
                        mapOf("text" to kotlinx.serialization.json.JsonPrimitive("\${params.date}")),
                    ),
                ),
            ),
            edges = listOf(WorkflowEdge(WorkflowEdge.START, "n1"), WorkflowEdge("n1", WorkflowEdge.END)),
        )
        val engine = WorkflowEngine(toolRegistry = registryOf(t))
        try {
            engine.start(def, mapOf("unknown_param" to "x"))
            throw AssertionError("未知参数应被拒")
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("未知参数"))
        }
        val events = engine.start(def).toList()
        assertTrue(events.last() is WorkflowEvent.RunSucceeded)
    }

    @Test
    fun `工具未注册时报可读错误`() = runBlocking {
        val engine = WorkflowEngine(toolRegistry = DefaultToolRegistry())
        val events = engine.start(linearDef("ghost_tool", 1)).toList()
        val failed = events.filterIsInstance<WorkflowEvent.RunFailed>().single()
        assertTrue(failed.error.contains("ghost_tool"))
    }

    // ------------------------------------------------------------------
    // 取消 / 终态回调 / runStore 恢复
    // ------------------------------------------------------------------

    @Test
    fun `协作取消落 CANCELLED 状态`() = runBlocking {
        val slow = CountingTool("t_cancel", delayMs = 120)
        val engine = WorkflowEngine(toolRegistry = registryOf(slow))
        val events = mutableListOf<WorkflowEvent>()
        val job = launch {
            engine.start(linearDef("t_cancel", 5)).collect { events += it }
        }
        // 等第一个节点开跑
        while (events.none { it is WorkflowEvent.NodeStarted }) delay(5)
        assertTrue(engine.cancel(events.filterIsInstance<WorkflowEvent.RunStarted>().first().runId))
        job.join()
        val runId = events.filterIsInstance<WorkflowEvent.RunStarted>().first().runId
        assertEquals(RunStatus.CANCELLED, engine.run(runId)!!.status)
        assertTrue(events.any { it is WorkflowEvent.RunCancelled }) // 超步边界发出取消事件
    }

    @Test
    fun `onRunFinished 终态回调（成功）`() = runBlocking {
        val t = CountingTool("t_cb")
        val finished = mutableListOf<Pair<String, RunStatus>>()
        val engine = WorkflowEngine(
            toolRegistry = registryOf(t),
            onRunFinished = { id, run -> finished += id to run.status },
        )
        engine.start(linearDef("t_cb", 2)).toList()
        assertEquals(1, finished.size)
        assertEquals("wf_linear" to RunStatus.SUCCEEDED, finished[0])
    }

    @Test
    fun `运行态经文件存储可跨引擎读取`() = runBlocking {
        val runsDir = tmp.root.toPath().resolve("runs")
        val t = CountingTool("t_file")
        val store = FileWorkflowRunStore(runsDir)
        val engine = WorkflowEngine(toolRegistry = registryOf(t), runStore = store)
        val events = engine.start(linearDef("t_file", 2)).toList()
        val runId = events.filterIsInstance<WorkflowEvent.RunSucceeded>().first().runId

        val engine2 = WorkflowEngine(toolRegistry = registryOf(t), runStore = FileWorkflowRunStore(runsDir))
        assertEquals(RunStatus.SUCCEEDED, engine2.run(runId)?.status)
        // 检查点文件真实存在
        assertTrue(Files.exists(runsDir.resolve("$runId.run.json")))
    }
}
