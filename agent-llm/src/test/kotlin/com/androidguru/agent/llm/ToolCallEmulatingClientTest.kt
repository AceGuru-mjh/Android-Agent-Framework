package com.androidguru.agent.llm

import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 工具调用模拟客户端测试（本地模型深度适配）：
 * - 消息适配：协议注入 / Tool→User 降级 / 历史调用回渲染；
 * - 响应解析：单块 / 多块 / 裸 JSON 兜底 / 非 JSON 文本透传；
 * - 流式回持：tool_call 块内容永不作为正文流出，普通文本不丢。
 */
class ToolCallEmulatingClientTest {

    /** 直通假端点：记录收到的请求，返回预置响应。 */
    private class FakeEndpoint(
        vararg val scripted: LlmResponse,
    ) : LlmClient {
        val chatRequests = mutableListOf<Pair<List<LlmMessage>, List<ToolDefinition>>>()
        val streamRequests = mutableListOf<Pair<List<LlmMessage>, List<ToolDefinition>>>()

        private var chatCursor = 0

        override suspend fun chat(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Double?,
            maxTokens: Int?,
            toolChoice: ToolChoiceSpec?,
        ): LlmResponse {
            chatRequests += messages to tools
            return scripted[chatCursor++ % scripted.size]
        }

        override fun chatStream(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Double?,
            maxTokens: Int?,
            toolChoice: ToolChoiceSpec?,
        ): kotlinx.coroutines.flow.Flow<LlmStreamChunk> {
            streamRequests += messages to tools
            val response = scripted[0]
            val chunks = buildList {
                response.content?.let { text ->
                    // 模拟 token 级流式：逐 8 字符切片
                    text.chunked(8).forEach { add(LlmStreamChunk(content = it)) }
                }
                add(LlmStreamChunk(finish = true))
            }
            return flowOf(*chunks.toTypedArray())
        }
    }

    private val tools = listOf(
        ToolDefinition(
            name = "open_app",
            description = "打开应用",
            parametersJsonSchema = """{"type":"object","properties":{"app":{"type":"string","description":"应用名"}},"required":["app"]}""",
        ),
        ToolDefinition(
            name = "set_alarm",
            description = "设置闹钟",
            parametersJsonSchema = """{"type":"object","properties":{"time":{"type":"string"}}}""",
        ),
    )

    private fun emulated(delegate: LlmClient) = ToolCallEmulatingClient(delegate)

    // ------------------------------------------------------------------
    // 消息适配
    // ------------------------------------------------------------------

    @Test
    fun `协议与工具清单注入系统提示`() = runTest {
        val endpoint = FakeEndpoint(LlmResponse(content = "好的"))
        val client = emulated(endpoint)

        client.chat(
            listOf(LlmMessage.System("你是助手"), LlmMessage.User("打开时钟")),
            tools,
        )

        val (messages, sentTools) = endpoint.chatRequests.single()
        assertTrue(sentTools.isEmpty()) // 工具不再走原生 tools 参数
        val system = messages.first() as LlmMessage.System
        assertTrue(system.content.contains("你是助手"))
        assertTrue(system.content.contains("工具调用协议"))
        assertTrue(system.content.contains("open_app"))
        assertTrue(system.content.contains("set_alarm"))
    }

    @Test
    fun `工具结果降级为 User 消息且截断`() = runTest {
        val endpoint = FakeEndpoint(LlmResponse(content = "ok"))
        val client = emulated(endpoint)

        val longResult = "x".repeat(ToolCallEmulatingClient.TOOL_RESULT_MAX_CHARS + 100)
        client.chat(
            listOf(
                LlmMessage.System("s"),
                LlmMessage.User("go"),
                LlmMessage.Assistant(
                    content = "调用工具",
                    toolCalls = listOf(ToolCall(id = "c1", name = "open_app", arguments = """{"app":"时钟"}""")),
                ),
                LlmMessage.Tool("c1", longResult),
            ),
            tools,
        )

        val (messages, _) = endpoint.chatRequests.single()
        // Tool → User
        val toolResult = messages.last() as LlmMessage.User
        assertTrue(toolResult.content.startsWith("TOOL_RESULT c1:"))
        assertTrue(toolResult.content.contains("（截断）"))
        // Assistant 历史调用回渲染为协议块文本
        val assistant = messages.filterIsInstance<LlmMessage.Assistant>().single()
        assertTrue(assistant.content.orEmpty().contains("```tool_call"))
        assertTrue(assistant.content.orEmpty().contains("open_app"))
    }

    @Test
    fun `无工具时消息原样透传`() = runTest {
        val endpoint = FakeEndpoint(LlmResponse(content = "plain"))
        val client = emulated(endpoint)
        val messages = listOf(LlmMessage.System("s"), LlmMessage.User("hi"))

        client.chat(messages, tools = emptyList())

        assertEquals(messages, endpoint.chatRequests.single().first)
    }

    // ------------------------------------------------------------------
    // 响应解析（非流式）
    // ------------------------------------------------------------------

    @Test
    fun `单个 tool_call 块解析为调用`() = runTest {
        val endpoint = FakeEndpoint(
            LlmResponse(content = "我来打开时钟。\n```tool_call\n{\"name\": \"open_app\", \"arguments\": {\"app\": \"时钟\"}}\n```"),
        )
        val response = emulated(endpoint).chat(listOf(LlmMessage.System("s")), tools)

        assertEquals(1, response.toolCalls.size)
        assertEquals("open_app", response.toolCalls[0].name)
        assertEquals("""{"app":"时钟"}""", response.toolCalls[0].arguments)
        assertEquals("我来打开时钟。", response.content)
    }

    @Test
    fun `多个块与 arguments 字符串形式`() = runTest {
        val endpoint = FakeEndpoint(
            LlmResponse(
                content = "```tool_call\n{\"name\": \"open_app\", \"arguments\": \"{\\\"app\\\": \\\"a\\\"}\"}\n```\n" +
                    "```tool_call\n{\"name\": \"set_alarm\", \"arguments\": {\"time\": \"7:30\"}}\n```",
            ),
        )
        val response = emulated(endpoint).chat(listOf(LlmMessage.System("s")), tools)

        assertEquals(2, response.toolCalls.size)
        assertEquals("open_app", response.toolCalls[0].name)
        assertEquals("""{"app":"a"}""", response.toolCalls[0].arguments) // 字符串形式也规范化
        assertEquals("set_alarm", response.toolCalls[1].name)
        assertEquals("", response.content) // 纯调用无正文
    }

    @Test
    fun `裸 JSON 整体调用兜底`() = runTest {
        val endpoint = FakeEndpoint(LlmResponse(content = """{"name": "set_alarm", "arguments": {"time": "6:00"}}"""))
        val response = emulated(endpoint).chat(listOf(LlmMessage.System("s")), tools)

        assertEquals(1, response.toolCalls.size)
        assertEquals("set_alarm", response.toolCalls[0].name)
    }

    @Test
    fun `普通文本不受影响`() = runTest {
        val endpoint = FakeEndpoint(LlmResponse(content = "这是普通回答，无需调用工具。"))
        val response = emulated(endpoint).chat(listOf(LlmMessage.System("s")), tools)

        assertTrue(response.toolCalls.isEmpty())
        assertEquals("这是普通回答，无需调用工具。", response.content)
    }

    @Test
    fun `maxCallsPerTurn 截断失控刷块`() = runTest {
        val blocks = (1..6).joinToString("\n") { i ->
            "```tool_call\n{\"name\": \"open_app\", \"arguments\": {\"app\": \"$i\"}}\n```"
        }
        val endpoint = FakeEndpoint(LlmResponse(content = blocks))
        val response = ToolCallEmulatingClient(endpoint, maxCallsPerTurn = 3)
            .chat(listOf(LlmMessage.System("s")), tools)

        assertEquals(3, response.toolCalls.size)
    }

    // ------------------------------------------------------------------
    // 流式
    // ------------------------------------------------------------------

    @Test
    fun `流式 tool_call 内容不外泄且调用正确解析`() = runTest {
        val full = "先说明一下。\n```tool_call\n{\"name\": \"open_app\", \"arguments\": {\"app\": \"时钟\"}}\n```"
        val endpoint = FakeEndpoint(LlmResponse(content = full))
        val chunks = emulated(endpoint)
            .chatStream(listOf(LlmMessage.System("s")), tools)
            .toList()

        val streamedText = chunks.mapNotNull { it.content }.joinToString("")
        assertTrue(streamedText.contains("先说明一下。"))
        assertTrue(!streamedText.contains("tool_call"))
        assertTrue(!streamedText.contains("open_app"))

        val complete = chunks.last()
        assertTrue(complete.finish)
        assertEquals(1, complete.completeToolCalls.size)
        assertEquals("open_app", complete.completeToolCalls[0].name)
    }

    @Test
    fun `流式普通文本完整下发`() = runTest {
        val endpoint = FakeEndpoint(LlmResponse(content = "一步一步回答用户的问题，内容不含任何围栏。"))
        val chunks = emulated(endpoint)
            .chatStream(listOf(LlmMessage.System("s")), tools)
            .toList()

        val text = chunks.mapNotNull { it.content }.joinToString("")
        assertEquals("一步一步回答用户的问题，内容不含任何围栏。", text)
        val last = chunks.last()
        assertTrue(last.finish)
        assertTrue(last.completeToolCalls.isEmpty())
    }

    @Test
    fun `流式含普通代码块不误吞正文`() = runTest {
        val full = "示例代码：\n```python\nprint('hi')\n```\n以上。"
        val endpoint = FakeEndpoint(LlmResponse(content = full))
        val chunks = emulated(endpoint)
            .chatStream(listOf(LlmMessage.System("s")), tools)
            .toList()

        val text = chunks.mapNotNull { it.content }.joinToString("")
        assertEquals(full, text)
    }

    @Test
    fun `safePrefixLength 边界行为`() {
        // 悬尾反引号回持
        assertEquals(4, ToolCallEmulatingClient.safePrefixLength("正文如下``"))
        // tool_call 起始全回持（"看我的。" = 4 字符）
        assertEquals(4, ToolCallEmulatingClient.safePrefixLength("看我的。```tool_call"))
        // 普通代码块闭合后放行
        val closed = "a\n```python\nx\n```\nb"
        assertEquals(closed.length, ToolCallEmulatingClient.safePrefixLength(closed))
        // 未闭合普通围栏回持
        assertEquals(2, ToolCallEmulatingClient.safePrefixLength("ab```py\ncode"))
    }
}
