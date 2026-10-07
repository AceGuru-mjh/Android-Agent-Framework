package com.androidguru.agent.workflow

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 定义静态校验测试：形态 / 唯一性 / 边端点 / 起始边 / 可达性 / 递归保护。
 */
class WorkflowDefinitionTest {

    private fun node(id: String): WorkflowNode.Tool =
        WorkflowNode.Tool(id = id, toolId = "t_$id")

    private fun linear(nodesCount: Int): WorkflowDefinition {
        val nodes = (1..nodesCount).map { node("n$it") }
        val edges = buildList {
            add(WorkflowEdge(WorkflowEdge.START, "n1"))
            for (i in 1 until nodesCount) add(WorkflowEdge(nodes[i - 1].id, nodes[i].id))
            add(WorkflowEdge(nodes.last().id, WorkflowEdge.END))
        }
        return WorkflowDefinition(id = "wf_test", name = "测试", nodes = nodes, edges = edges)
    }

    @Test
    fun `线性定义校验通过`() {
        linear(3).validateOrNull().let { assertEquals(null, it) }
    }

    @Test
    fun `空节点列表被拒`() {
        val def = WorkflowDefinition(id = "wf_empty", name = "空", nodes = emptyList(), edges = emptyList())
        assertTrue(def.validateOrNull()!!.contains("至少需要一个节点"))
    }

    @Test
    fun `节点 id 重复被拒`() {
        val nodes = listOf(node("n1"), node("n1"))
        val def = WorkflowDefinition(
            id = "wf_dup", name = "重复",
            nodes = nodes,
            edges = listOf(WorkflowEdge(WorkflowEdge.START, "n1"), WorkflowEdge("n1", WorkflowEdge.END)),
        )
        assertTrue(def.validateOrNull()!!.contains("重复"))
    }

    @Test
    fun `节点 id 含点号被拒（模板路径寻址要求）`() {
        val def = WorkflowDefinition(
            id = "wf_dot", name = "点号",
            nodes = listOf(node("n.1")),
            edges = listOf(WorkflowEdge(WorkflowEdge.START, "n.1"), WorkflowEdge("n.1", WorkflowEdge.END)),
        )
        assertTrue(def.validateOrNull()!!.contains("不合法"))
    }

    @Test
    fun `悬空边端点被拒`() {
        val def = WorkflowDefinition(
            id = "wf_dangling", name = "悬空",
            nodes = listOf(node("n1")),
            edges = listOf(WorkflowEdge(WorkflowEdge.START, "n1"), WorkflowEdge("n1", "ghost")),
        )
        assertTrue(def.validateOrNull()!!.contains("不存在"))
    }

    @Test
    fun `缺起始边被拒`() {
        val def = WorkflowDefinition(
            id = "wf_nostart", name = "无起点",
            nodes = listOf(node("n1")),
            edges = listOf(WorkflowEdge("n1", WorkflowEdge.END)),
        )
        assertTrue(def.validateOrNull()!!.contains("起始边"))
    }

    @Test
    fun `不可达死分支被拒`() {
        val def = WorkflowDefinition(
            id = "wf_dead", name = "死分支",
            nodes = listOf(node("n1"), node("island")),
            edges = listOf(
                WorkflowEdge(WorkflowEdge.START, "n1"),
                WorkflowEdge("n1", WorkflowEdge.END),
            ),
        )
        assertTrue(def.validateOrNull()!!.contains("不可达"))
    }

    @Test
    fun `工作流内引用 run_workflow 被拒（递归保护）`() {
        val nodes = listOf(WorkflowNode.Tool(id = "n1", toolId = WorkflowTools.RUN_TOOL_ID))
        val def = WorkflowDefinition(
            id = "wf_recursive", name = "递归",
            nodes = nodes,
            edges = listOf(WorkflowEdge(WorkflowEdge.START, "n1"), WorkflowEdge("n1", WorkflowEdge.END)),
        )
        assertTrue(def.validateOrNull()!!.contains("递归"))
    }

    @Test
    fun `从 END 出发的边被拒`() {
        val def = WorkflowDefinition(
            id = "wf_from_end", name = "反向",
            nodes = listOf(node("n1")),
            edges = listOf(
                WorkflowEdge(WorkflowEdge.START, "n1"),
                WorkflowEdge(WorkflowEdge.END, "n1"),
            ),
        )
        assertTrue(def.validateOrNull()!!.contains("不允许从"))
    }

    @Test
    fun `起始边带 branch 被拒`() {
        val def = WorkflowDefinition(
            id = "wf_start_branch", name = "带分支起点",
            nodes = listOf(node("n1")),
            edges = listOf(
                WorkflowEdge(WorkflowEdge.START, "n1", branch = "x"),
                WorkflowEdge("n1", WorkflowEdge.END),
            ),
        )
        assertTrue(def.validateOrNull()!!.contains("起始边不能带 branch"))
    }

    @Test
    fun `switch 无 cases 且无 default 被拒`() {
        val sw = WorkflowNode.Switch(id = "n1", expression = "\${vars.x}")
        val def = WorkflowDefinition(
            id = "wf_switch", name = "空 switch",
            nodes = listOf(sw),
            edges = listOf(WorkflowEdge(WorkflowEdge.START, "n1"), WorkflowEdge("n1", WorkflowEdge.END)),
        )
        assertTrue(def.validateOrNull()!!.contains("switch"))
    }

    @Test
    fun `参数名重复被拒`() {
        val def = linear(1).copy(params = listOf(ParamSpec("a"), ParamSpec("a")))
        assertTrue(def.validateOrNull()!!.contains("参数名重复"))
    }

    @Test
    fun `校验异常携带问题清单`() {
        val bad = WorkflowDefinition(id = "wf_bad id!", name = "", nodes = emptyList(), edges = emptyList())
        try {
            bad.validate()
            throw AssertionError("应抛 WorkflowValidationException")
        } catch (e: WorkflowValidationException) {
            assertTrue(e.message!!.isNotBlank())
        }
    }

    @Test
    fun `重试退避单调递增且封顶`() {
        val policy = RetryPolicy(maxAttempts = 5, initialDelayMs = 100, backoffFactor = 2.0, maxDelayMs = 800)
        val delays = (1..4).map { policy.delayFor(it, random = 0.5) }
        // 无抖动中点（random=0.5 → factor=1.0）：100 / 200 / 400 / 800
        assertEquals(listOf(100L, 200L, 400L, 800L), delays)
        assertEquals(800L, policy.delayFor(10, random = 0.5))
    }
}

/** 模板解析器测试（独立小套件：叶子 / 混排 / 下钻 / 缺失）。 */
class TemplateResolverTest {

    private fun ctx(
        params: Map<String, String> = emptyMap(),
        vars: Map<String, String> = emptyMap(),
        outputs: Map<String, kotlinx.serialization.json.JsonElement?> = emptyMap(),
    ) = ResolveContext(
        params = kotlinx.serialization.json.JsonObject(params.mapValues { JsonPrimitive(it.value) }),
        vars = kotlinx.serialization.json.JsonObject(vars.mapValues { JsonPrimitive(it.value) }),
        nodeOutputs = outputs,
    )

    @Test
    fun `无模板串原样返回`() {
        assertEquals("plain text", TemplateResolver.resolve("plain text", ctx()))
    }

    @Test
    fun `params 与 vars 透传`() {
        val c = ctx(params = mapOf("name" to "alice"), vars = mapOf("date" to "2024-01-01"))
        assertEquals("alice", TemplateResolver.resolve("\${params.name}", c))
        assertEquals("2024-01-01", TemplateResolver.resolve("\${vars.date}", c))
    }

    @Test
    fun `多模板混排常量文本`() {
        val c = ctx(params = mapOf("a" to "1", "b" to "2"))
        assertEquals("a=1,b=2!", TemplateResolver.resolve("a=\${params.a},b=\${params.b}!", c))
    }

    @Test
    fun `节点输出对象整体内联为 JSON 文本`() {
        val output = kotlinx.serialization.json.JsonObject(
            mapOf("dau" to JsonPrimitive(12000), "orders" to JsonPrimitive(350)),
        )
        val c = ctx(outputs = mapOf("gather" to output))
        assertEquals("""{"dau":12000,"orders":350}""", TemplateResolver.resolve("\${nodes.gather.output}", c))
    }

    @Test
    fun `节点输出对象可下钻字段`() {
        val output = kotlinx.serialization.json.JsonObject(mapOf("dau" to JsonPrimitive(12000)))
        val c = ctx(outputs = mapOf("gather" to output))
        assertEquals("12000", TemplateResolver.resolve("\${nodes.gather.output.dau}", c))
    }

    @Test
    fun `缺失路径抛异常（fail-fast 不静默）`() {
        try {
            TemplateResolver.resolve("\${params.missing}", ctx())
            throw AssertionError("应抛异常")
        } catch (e: TemplateResolver.TemplateResolutionException) {
            assertTrue(e.message!!.contains("params.missing"))
        }
    }

    @Test
    fun `未执行节点输出解析到 null 也报错`() {
        val c = ctx(outputs = mapOf("n1" to null))
        try {
            TemplateResolver.resolve("\${nodes.n1.output}", c)
            throw AssertionError("应抛异常")
        } catch (e: TemplateResolver.TemplateResolutionException) {
            assertTrue(e.message!!.contains("未执行或无输出"))
        }
    }

    @Test
    fun `参数对象叶子递归解析且非字符串叶子透传`() {
        val args = kotlinx.serialization.json.JsonObject(
            mapOf(
                "name" to JsonPrimitive("\${params.name}"),
                "count" to JsonPrimitive(3),
                "nested" to kotlinx.serialization.json.JsonObject(
                    mapOf("date" to JsonPrimitive("\${vars.date}")),
                ),
            ),
        )
        val c = ctx(params = mapOf("name" to "bob"), vars = mapOf("date" to "2024-06-01"))
        val resolved = TemplateResolver.resolveJsonArgs(args, c)
        assertEquals("bob", (resolved["name"] as JsonPrimitive).content)
        assertEquals(3, (resolved["count"] as JsonPrimitive).content.toLong())
        assertEquals(
            "2024-06-01",
            ((resolved["nested"] as kotlinx.serialization.json.JsonObject)["date"] as JsonPrimitive).content,
        )
    }
}
