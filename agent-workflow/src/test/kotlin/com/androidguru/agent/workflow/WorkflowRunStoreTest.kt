package com.androidguru.agent.workflow

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/**
 * 编解码往返 + 文件运行态存储（原子写 / 损坏容错 / list）。
 */
class WorkflowCodecTest {

    private fun fullDefinition(): WorkflowDefinition = WorkflowDefinition(
        id = "daily_report",
        name = "渠道日报",
        description = "生成并发送日报",
        params = listOf(
            ParamSpec("channel", "渠道", required = true),
            ParamSpec("date", "日期", defaultValue = "today"),
        ),
        nodes = listOf(
            WorkflowNode.Tool(
                id = "n1",
                toolId = "collect",
                arguments = JsonObject(mapOf("date" to JsonPrimitive("\${params.date}"))),
                description = "收集",
                retry = RetryPolicy(maxAttempts = 5, initialDelayMs = 10, maxDelayMs = 100, jitter = false),
                timeoutMs = 5_000,
                onError = NodeOnError.ERROR_BRANCH,
            ),
            WorkflowNode.Agent(
                id = "n2",
                prompt = "总结 \${nodes.n1.output}",
                systemPrompt = "你是记者",
                temperature = 0.3,
                maxTokens = 512,
            ),
            WorkflowNode.Human(id = "n3", prompt = "确认？"),
            WorkflowNode.Switch(
                id = "n4",
                expression = "\${vars.kind}",
                cases = listOf(
                    WorkflowNode.Switch.Case("a", "branch_a"),
                    WorkflowNode.Switch.Case("b", "branch_b"),
                ),
                defaultBranch = "branch_default",
            ),
            WorkflowNode.SetVar(id = "n5", assignments = mapOf("x" to "\${params.date}")),
            WorkflowNode.Delay(id = "n6", delayMs = 2_000),
        ),
        edges = listOf(
            WorkflowEdge(WorkflowEdge.START, "n1"),
            WorkflowEdge("n1", "n2"),
            WorkflowEdge("n2", "n4"),
            WorkflowEdge("n4", "n5", branch = "branch_a"),
            WorkflowEdge("n4", "n6", branch = "branch_default"),
            WorkflowEdge("n5", WorkflowEdge.END),
            WorkflowEdge("n6", WorkflowEdge.END),
        ),
        maxSteps = 42,
    )

    @Test
    fun `定义往返无损`() {
        val original = fullDefinition()
        val decoded = WorkflowCodec.definitionFromJson(WorkflowCodec.definitionToJson(original))
        assertEquals(original, decoded)
    }

    @Test
    fun `运行态往返无损（含节点输出与路由决策）`() {
        val run = WorkflowRunState(
            runId = "run-abc",
            definitionId = "daily_report",
            definition = fullDefinition(),
            status = RunStatus.WAITING_HUMAN,
            params = JsonObject(mapOf("channel" to JsonPrimitive("slack"))),
            vars = JsonObject(mapOf("x" to JsonPrimitive("1"))),
            nodeStates = mapOf(
                "n1" to NodeRunState(
                    status = NodeStatus.SUCCEEDED,
                    attempt = 2,
                    output = JsonObject(mapOf("ok" to JsonPrimitive(true))),
                ),
                "n4" to NodeRunState(status = NodeStatus.SUCCEEDED, branch = "branch_a"),
                "n2" to NodeRunState(status = NodeStatus.FAILED, errorText = "boom"),
            ),
            stepCounter = 7,
            waitingNodeId = "n3",
            createdAtMs = 100,
            startedAtMs = 101,
            message = "等待",
        )
        val encoded = WorkflowCodec.encodeRun(run).toString()
        val decoded = WorkflowCodec.decodeRun(
            kotlinx.serialization.json.Json.parseToJsonElement(encoded).jsonObjectSafe(),
        )
        assertEquals(run, decoded)
    }

    @Test
    fun `未知节点 kind 抛异常（不静默）`() {
        val json = """{"id":"x","name":"x","nodes":[{"kind":"quantum","id":"n1"}],"edges":[]}"""
        try {
            WorkflowCodec.definitionFromJson(json)
            throw AssertionError("应抛异常")
        } catch (e: WorkflowCodec.WorkflowCodecException) {
            assertTrue(e.message!!.contains("kind"))
        }
    }

    @Test
    fun `高于当前支持的 schemaVersion 被拒`() {
        val json = """{"schemaVersion":99,"id":"x","name":"x","nodes":[],"edges":[]}"""
        try {
            WorkflowCodec.definitionFromJson(json)
            throw AssertionError("应抛异常")
        } catch (e: WorkflowCodec.WorkflowCodecException) {
            assertTrue(e.message!!.contains("schemaVersion"))
        }
    }

    @Test
    fun `非合法 JSON 抛异常`() {
        try {
            WorkflowCodec.definitionFromJson("not json at all {")
            throw AssertionError("应抛异常")
        } catch (e: WorkflowCodec.WorkflowCodecException) {
            assertTrue(e.message!!.contains("不是合法 JSON"))
        }
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectSafe(): JsonObject =
        this as? JsonObject ?: throw IllegalStateException("not an object")
}

/** 文件运行态存储。 */
class WorkflowRunStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun sampleRun(runId: String = "run-1"): WorkflowRunState = WorkflowRunState(
        runId = runId,
        definitionId = "wf",
        definition = WorkflowDefinition(
            id = "wf", name = "n",
            nodes = listOf(WorkflowNode.Tool(id = "n1", toolId = "t")),
            edges = listOf(WorkflowEdge(WorkflowEdge.START, "n1"), WorkflowEdge("n1", WorkflowEdge.END)),
        ),
        status = RunStatus.PENDING,
        params = JsonObject(emptyMap()),
        vars = JsonObject(emptyMap()),
        createdAtMs = 1,
    )

    @Test
    fun `保存后可加载往返`() {
        val store = FileWorkflowRunStore(tmp.root.toPath())
        store.save(sampleRun("run-a"))
        assertEquals("run-a", store.load("run-a")?.runId)
    }

    @Test
    fun `原子覆盖写（同 id 更新生效）`() {
        val store = FileWorkflowRunStore(tmp.root.toPath())
        store.save(sampleRun("run-a").copy(status = RunStatus.PENDING))
        store.save(sampleRun("run-a").copy(status = RunStatus.SUCCEEDED))
        assertEquals(RunStatus.SUCCEEDED, store.load("run-a")?.status)
    }

    @Test
    fun `runId 消毒防目录穿越`() {
        val store = FileWorkflowRunStore(tmp.root.toPath())
        store.save(sampleRun("../../evil"))
        // 只应存在一个文件且落在目录内
        val files = Files.walk(tmp.root.toPath()).filter { Files.isRegularFile(it) }.toList()
        assertEquals(1, files.size)
        assertTrue(files[0].parent == tmp.root.toPath())
    }

    @Test
    fun `损坏文件回落为无此运行`() {
        val dir = tmp.root.toPath()
        val store = FileWorkflowRunStore(dir)
        store.save(sampleRun("run-a"))
        val file = dir.resolve("run-a.run.json")
        Files.writeString(file, "{ broken json")
        assertNull(store.load("run-a"))
    }

    @Test
    fun `list 举全部且忽略损坏行`() {
        val dir = tmp.root.toPath()
        val store = FileWorkflowRunStore(dir)
        store.save(sampleRun("run-a"))
        store.save(sampleRun("run-b"))
        Files.writeString(dir.resolve("garbage.run.json"), "not-json")
        assertEquals(2, store.list().size)
    }

    @Test
    fun `delete 删除并返回真`() {
        val store = FileWorkflowRunStore(tmp.root.toPath())
        store.save(sampleRun("run-a"))
        assertTrue(store.delete("run-a"))
        assertNull(store.load("run-a"))
        assertEquals(false, store.delete("run-a"))
    }

    @Test
    fun `内存实现基本契约`() {
        val store = InMemoryWorkflowRunStore()
        store.save(sampleRun("r"))
        assertNotNull(store.load("r"))
        assertEquals(1, store.list().size)
        assertTrue(store.delete("r"))
        assertNull(store.load("r"))
    }
}
