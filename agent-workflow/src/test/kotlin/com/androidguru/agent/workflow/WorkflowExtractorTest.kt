package com.androidguru.agent.workflow

import com.androidguru.agent.llm.LlmClient
import com.androidguru.agent.llm.LlmMessage
import com.androidguru.agent.llm.LlmResponse
import com.androidguru.agent.tools.AgentTool
import com.androidguru.agent.tools.DefaultToolRegistry
import com.androidguru.agent.tools.ToolRequest
import com.androidguru.agent.tools.ToolResult
import com.androidguru.agent.tools.ToolSchema
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 蒸馏器测试：LLM 路径（成功 / 围栏 / 失败回退）+ 宏录制回退（去重 / 截断 /
 * 非法参数过滤）+ 轨迹构建。
 */
class WorkflowExtractorTest {

    /** 脚本化假 LLM（按序返回预设回复）。 */
    private class ScriptedClient(private val replies: List<String>) : LlmClient {
        var calls = 0
        override suspend fun chat(
            messages: List<LlmMessage>,
            tools: List<com.androidguru.agent.llm.ToolDefinition>,
            temperature: Double?,
            maxTokens: Int?,
            toolChoice: com.androidguru.agent.llm.ToolChoiceSpec?,
        ): LlmResponse {
            val text = replies[calls.coerceAtMost(replies.size - 1)]
            calls++
            return LlmResponse(content = text)
        }

        override fun chatStream(
            messages: List<LlmMessage>,
            tools: List<com.androidguru.agent.llm.ToolDefinition>,
            temperature: Double?,
            maxTokens: Int?,
            toolChoice: com.androidguru.agent.llm.ToolChoiceSpec?,
        ): kotlinx.coroutines.flow.Flow<com.androidguru.agent.llm.LlmStreamChunk> =
            kotlinx.coroutines.flow.flow { }
    }

    private fun traceConversation(): List<LlmMessage> = listOf(
        LlmMessage.User("把日报发给 alice"),
        LlmMessage.Assistant(
            toolCalls = listOf(com.androidguru.agent.llm.ToolCall(id = "c1", name = "collect", arguments = """{"date":"2024-06-01"}""")),
        ),
        LlmMessage.Tool("c1", """{"dau":1}"""),
        LlmMessage.Assistant(
            toolCalls = listOf(com.androidguru.agent.llm.ToolCall(id = "c2", name = "send", arguments = """{"to":"alice"}""")),
        ),
        LlmMessage.Tool("c2", "ok"),
        LlmMessage.Assistant("done"),
    )

    private val recipientTemplate = "\${params.recipient}"

    private val validWorkflowJson = """
        {"id":"send_report","name":"发日报","description":"给指定人发日报",
         "params":[{"name":"recipient","description":"收件人","required":true}],
         "nodes":[{"kind":"tool","id":"n1","toolId":"collect",
                   "arguments":{"date":"2024-06-01"},"description":"收集"},
                  {"kind":"tool","id":"n2","toolId":"send",
                   "arguments":{"to":"$recipientTemplate"},"description":"发送"}],
         "edges":[{"from":"__start__","to":"n1"},{"from":"n1","to":"n2"},{"from":"n2","to":"__end__"}]}
    """.trimIndent()

    @Test
    fun `LLM 蒸馏成功产出参数化定义`() = runBlocking {
        val extractor = WorkflowExtractor(client = ScriptedClient(listOf(validWorkflowJson)))
        val def = extractor.extract(traceConversation(), "发日报")
        assertNotNull(def)
        assertEquals("send_report", def!!.id)
        assertEquals(2, def.nodes.size)
        assertEquals(1, def.params.size)
        val sendNode = def.nodes[1] as WorkflowNode.Tool
        assertTrue(sendNode.arguments["to"].toString().contains("\${params.recipient}"))
        def.validate() // 合法
    }

    @Test
    fun `LLM 输出带代码围栏也能解析`() = runBlocking {
        val fenced = "```json\n$validWorkflowJson\n```"
        val extractor = WorkflowExtractor(client = ScriptedClient(listOf(fenced)))
        assertNotNull(extractor.extract(traceConversation(), "发日报"))
    }

    @Test
    fun `LLM 输出 skip 指令时回退宏录制`() = runBlocking {
        val extractor = WorkflowExtractor(client = ScriptedClient(listOf("""{"skip":true}""")))
        val def = extractor.extract(traceConversation(), "发日报")
        assertNotNull(def) // 回退宏录制（不是 null —— 轨迹可用）
        assertTrue(def!!.nodes.all { it is WorkflowNode.Tool })
    }

    @Test
    fun `LLM 输出非法 JSON 时回退宏录制`() = runBlocking {
        val extractor = WorkflowExtractor(client = ScriptedClient(listOf("这不是 JSON")))
        val def = extractor.extract(traceConversation(), "发日报")
        assertNotNull(def)
        assertEquals(2, def!!.nodes.size)
    }

    @Test
    fun `无 LLM 走宏录制（实参固化）`() = runBlocking {
        val extractor = WorkflowExtractor()
        val def = extractor.extract(traceConversation(), "发日报给 alice")
        assertNotNull(def)
        val nodes = def!!.nodes
        assertEquals(2, nodes.size)
        val n1 = nodes[0] as WorkflowNode.Tool
        assertEquals("collect", n1.toolId)
        assertTrue(n1.arguments.toString().contains("2024-06-01")) // 实参原样固化
        // 宏录制产物必须自身合法（可入库）
        def.validate()
    }

    @Test
    fun `宏录制折叠连续重复调用（循环护栏痕迹）`() = runBlocking {
        val messages = listOf(
            LlmMessage.Assistant(
                toolCalls = (1..3).map {
                    com.androidguru.agent.llm.ToolCall(id = "c$it", name = "probe", arguments = """{"x":1}""")
                },
            ),
            LlmMessage.Tool("c1", "r"), LlmMessage.Tool("c2", "r"), LlmMessage.Tool("c3", "r"),
            LlmMessage.Assistant(
                toolCalls = listOf(com.androidguru.agent.llm.ToolCall(id = "c4", name = "done", arguments = """{"y":2}""")),
            ),
            LlmMessage.Tool("c4", "r"),
        )
        val extractor = WorkflowExtractor()
        val def = extractor.extract(messages, "重复调用")
        assertEquals(2, def!!.nodes.size) // 3 次相同调用折叠为 1 + done
    }

    @Test
    fun `宏录制过滤非法 JSON 参数调用`() = runBlocking {
        val messages = listOf(
            LlmMessage.Assistant(
                toolCalls = listOf(
                    com.androidguru.agent.llm.ToolCall(id = "c1", name = "bad", arguments = "not-json"),
                    com.androidguru.agent.llm.ToolCall(id = "c2", name = "good", arguments = """{"a":1}"""),
                    com.androidguru.agent.llm.ToolCall(id = "c3", name = "good2", arguments = """{"b":2}"""),
                ),
            ),
            LlmMessage.Tool("c1", "r"), LlmMessage.Tool("c2", "r"), LlmMessage.Tool("c3", "r"),
        )
        val extractor = WorkflowExtractor()
        val def = extractor.extract(messages, "混合")
        assertEquals(2, def!!.nodes.size)
        assertTrue(def.nodes.none { (it as WorkflowNode.Tool).toolId == "bad" })
    }

    @Test
    fun `轨迹太薄（少于 2 次调用）不产出`() = runBlocking {
        val extractor = WorkflowExtractor()
        assertNull(extractor.extract(emptyList(), "空"))
        val single = listOf(
            LlmMessage.Assistant(
                toolCalls = listOf(com.androidguru.agent.llm.ToolCall(id = "c1", name = "only", arguments = """{"a":1}""")),
            ),
            LlmMessage.Tool("c1", "r"),
        )
        assertNull(extractor.extract(single, "单步"))
    }

    @Test
    fun `extractAndSave 入库为候选态`() = runBlocking {
        val lib = WorkflowLibrary(InMemoryWorkflowLibraryStore())
        val extractor = WorkflowExtractor()
        val saved = extractor.extractAndSave(traceConversation(), "发日报", lib)
        assertNotNull(saved)
        assertEquals(WorkflowLifecycle.CANDIDATE, saved!!.lifecycle)
        assertEquals(saved.definition.id, lib.get(saved.definition.id)!!.definition.id)
    }

    @Test
    fun `extractJsonBlock 截取首个对象到末个大括号`() {
        val extractor = WorkflowExtractor()
        assertNull(extractor.extractJsonBlock("no braces"))
        assertEquals("""{"a":1}""", extractor.extractJsonBlock("""前置文本 {"a":1} 后缀"""))
    }
}

/**
 * 工具五件套测试：run（成功 / 失败接管包 / 未知 / 禁用 / human 拒绝） /
 * list / read / save / disable。
 */
class WorkflowToolsTest {

    private val noop = object : AgentTool {
        override val id = "noop"
        override val description = "空操作"
        override val parameters: ToolSchema = ToolSchema.build { string("text", "t") }
        override suspend fun execute(request: ToolRequest) = ToolResult.success("noop ok", data = """{"ok":true}""")
    }

    private val failing = object : AgentTool {
        override val id = "boom"
        override val description = "必炸"
        override val parameters: ToolSchema = ToolSchema.build { string("text", "t") }
        override suspend fun execute(request: ToolRequest) =
            ToolResult.failure("炸了", com.androidguru.agent.tools.ToolErrorCode.INTERNAL)
    }

    private fun simpleDef(id: String, toolId: String = "noop"): WorkflowDefinition = workflow(id, id) {
        toolNode("n1", toolId, args = mapOf("text" to "x"))
        toolNode("n2", toolId, args = mapOf("text" to "y"))
        edge(START, "n1")
        edge("n1" to "n2")
        edge("n2", END)
    }

    private fun humanDef(): WorkflowDefinition = workflow("with_human", "带人工") {
        humanNode("gate", "确认？")
        toolNode("n1", "noop", args = mapOf("text" to "x"))
        edge(START, "gate")
        edge("gate" to "n1")
        edge("n1", END)
    }

    @Test
    fun `run_workflow 成功返回摘要与末步输出`() = runBlocking {
        val lib = WorkflowLibrary(InMemoryWorkflowLibraryStore())
        val engine = WorkflowEngine(toolRegistry = DefaultToolRegistry().also { it.register(noop) })
        lib.save(simpleDef("wf_ok"))
        val tool = WorkflowRunTool(lib, engine)
        val result = tool.execute(ToolRequest("c1", "run_workflow", """{"id":"wf_ok"}"""))
        assertTrue(result.ok)
        assertTrue(result.content.contains("成功"))
        assertTrue(result.content.contains("noop ok") || result.content.contains("末步输出"))
    }

    @Test
    fun `run_workflow 失败带接管包（失败节点 + 剩余步骤）`() = runBlocking {
        val lib = WorkflowLibrary(InMemoryWorkflowLibraryStore())
        val engine = WorkflowEngine(toolRegistry = DefaultToolRegistry().also { it.register(failing) })
        lib.save(simpleDef("wf_bad", toolId = "boom"))
        val tool = WorkflowRunTool(lib, engine)
        val result = tool.execute(ToolRequest("c1", "run_workflow", """{"id":"wf_bad"}"""))
        assertTrue(result.isError)
        assertTrue(result.content.contains("n1")) // 失败节点
        assertTrue(result.content.contains("剩余未执行步骤"))
        assertTrue(result.content.contains("n2")) // 剩余步骤可接手
    }

    @Test
    fun `run_workflow 未知 id 报 NOT_FOUND`() = runBlocking {
        val tool = WorkflowRunTool(WorkflowLibrary(InMemoryWorkflowLibraryStore()), WorkflowEngine())
        val result = tool.execute(ToolRequest("c1", "run_workflow", """{"id":"ghost"}"""))
        assertTrue(result.isError)
        assertEquals(com.androidguru.agent.tools.ToolErrorCode.NOT_FOUND, result.error!!.code)
    }

    @Test
    fun `run_workflow 禁用态拒绝执行`() = runBlocking {
        val lib = WorkflowLibrary(InMemoryWorkflowLibraryStore())
        val engine = WorkflowEngine(toolRegistry = DefaultToolRegistry().also { it.register(noop) })
        lib.save(simpleDef("wf_off"))
        lib.disable("wf_off", "人工禁用")
        val result = WorkflowRunTool(lib, engine).execute(ToolRequest("c1", "run_workflow", """{"id":"wf_off"}"""))
        assertTrue(result.isError)
        assertTrue(result.content.contains("禁用"))
    }

    @Test
    fun `run_workflow 遇 human 节点拒绝并说明（工具路径无人通道）`() = runBlocking {
        val lib = WorkflowLibrary(InMemoryWorkflowLibraryStore())
        val engine = WorkflowEngine(toolRegistry = DefaultToolRegistry().also { it.register(noop) })
        lib.save(humanDef())
        val result = WorkflowRunTool(lib, engine).execute(ToolRequest("c1", "run_workflow", """{"id":"with_human"}"""))
        assertTrue(result.isError)
        assertTrue(result.content.contains("人工节点"))
    }

    @Test
    fun `run_workflow 参数校验错误折叠为 VALIDATION 失败`() = runBlocking {
        val lib = WorkflowLibrary(InMemoryWorkflowLibraryStore())
        val engine = WorkflowEngine(toolRegistry = DefaultToolRegistry().also { it.register(noop) })
        // 带必填参数的工作流，不传参
        val def = workflow("wf_p", "p") {
            param("must", required = true)
            toolNode("n1", "noop", args = mapOf("text" to "\${params.must}"))
            edge(START, "n1")
            edge("n1", END)
        }
        lib.save(def)
        val result = WorkflowRunTool(lib, engine).execute(ToolRequest("c1", "run_workflow", """{"id":"wf_p"}"""))
        assertTrue(result.isError)
        assertEquals(com.androidguru.agent.tools.ToolErrorCode.VALIDATION, result.error!!.code)
    }

    @Test
    fun `list_workflows 渲染索引且禁用项默认隐藏`() = runBlocking {
        val lib = WorkflowLibrary(InMemoryWorkflowLibraryStore())
        lib.save(simpleDef("wf_list_a"))
        lib.save(simpleDef("wf_list_b"))
        lib.disable("wf_list_b")
        val result = WorkflowListTool(lib).execute(ToolRequest("c1", "list_workflows", "{}"))
        assertTrue(result.ok)
        assertTrue(result.content.contains("wf_list_a"))
        assertTrue(!result.content.contains("wf_list_b"))
    }

    @Test
    fun `read_workflow 返回定义 JSON`() = runBlocking {
        val lib = WorkflowLibrary(InMemoryWorkflowLibraryStore())
        lib.save(simpleDef("wf_read"))
        val result = WorkflowReadTool(lib).execute(ToolRequest("c1", "read_workflow", """{"id":"wf_read"}"""))
        assertTrue(result.ok)
        assertTrue(result.content.contains("wf_read"))
        assertTrue(result.content.contains("n1"))
    }

    @Test
    fun `save_workflow 合法定义入库为候选态`() = runBlocking {
        val lib = WorkflowLibrary(InMemoryWorkflowLibraryStore())
        val definitionJson = WorkflowCodec.definitionToJson(simpleDef("wf_new"))
        val result = WorkflowSaveTool(lib).execute(
            ToolRequest("c1", "save_workflow", """{"definition":${encodeJsonString(definitionJson)}}"""),
        )
        assertTrue(result.ok)
        assertEquals(WorkflowLifecycle.CANDIDATE, lib.get("wf_new")!!.lifecycle)
    }

    @Test
    fun `save_workflow 非法 JSON 给出可修建议`() = runBlocking {
        val lib = WorkflowLibrary(InMemoryWorkflowLibraryStore())
        val result = WorkflowSaveTool(lib).execute(ToolRequest("c1", "save_workflow", """{"definition":"not json"}"""))
        assertTrue(result.isError)
        assertEquals(com.androidguru.agent.tools.ToolErrorCode.VALIDATION, result.error!!.code)
        assertTrue(result.error!!.suggestion!!.contains("read_workflow"))
    }

    @Test
    fun `disable_workflow 生效并留原因`() = runBlocking {
        val lib = WorkflowLibrary(InMemoryWorkflowLibraryStore())
        lib.save(simpleDef("wf_disable"))
        val result = WorkflowDisableTool(lib).execute(
            ToolRequest("c1", "disable_workflow", """{"id":"wf_disable","reason":"行为错误"}"""),
        )
        assertTrue(result.ok)
        assertEquals(WorkflowLifecycle.DISABLED, lib.get("wf_disable")!!.lifecycle)
        assertTrue(lib.get("wf_disable")!!.lastError!!.contains("行为错误"))
    }

    @Test
    fun `工具集 id 稳定（五件套齐备）`() {
        val lib = WorkflowLibrary(InMemoryWorkflowLibraryStore())
        val tools = WorkflowTools.all(lib, WorkflowEngine())
        assertEquals(
            setOf("run_workflow", "list_workflows", "read_workflow", "save_workflow", "disable_workflow"),
            tools.map { it.id }.toSet(),
        )
    }

    /** 把 JSON 文本再编码为 JSON 字符串字面量（作为 arguments 里的 definition 值）。 */
    private fun encodeJsonString(text: String): String =
        kotlinx.serialization.json.JsonPrimitive(text).toString()
}
