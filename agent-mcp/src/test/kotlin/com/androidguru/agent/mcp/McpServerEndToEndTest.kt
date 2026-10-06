package com.androidguru.agent.mcp

import com.androidguru.agent.mcp.bridge.McpToolBridge
import com.androidguru.agent.mcp.bridge.McpToolNaming
import com.androidguru.agent.mcp.client.McpClient
import com.androidguru.agent.mcp.server.McpServerConfig
import com.androidguru.agent.mcp.server.McpToolServer
import com.androidguru.agent.mcp.transport.HttpMcpTransport
import com.androidguru.agent.tools.AgentTool
import com.androidguru.agent.tools.DefaultToolExecutor
import com.androidguru.agent.tools.DefaultToolRegistry
import com.androidguru.agent.tools.ToolRequest
import com.androidguru.agent.tools.ToolResult
import com.androidguru.agent.tools.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

private class EchoTool : AgentTool {
    override val id = "echo"
    override val description = "回显工具（端到端测试用）"
    override val parameters = ToolSchema.build {
        string("text", required = true)
    }
    override suspend fun execute(request: ToolRequest): ToolResult {
        val obj = Json.parseToJsonElement(request.arguments) as JsonObject
        val text = (obj["text"] as kotlinx.serialization.json.JsonPrimitive).content
        return ToolResult.success("echo: $text")
    }
}

private class VaultTool : AgentTool {
    override val id = "vault_read"
    override val description = "机密读取（必须被硬拦截）"
    override suspend fun execute(request: ToolRequest): ToolResult = ToolResult.success("secret")
}

/**
 * 端到端：McpToolServer（真实 HTTP）↔ HttpMcpTransport + McpClient ↔ ToolBridge。
 * 双向验证客户端协议与服务端协议的兼容性。
 */
class McpServerEndToEndTest {

    private lateinit var registry: DefaultToolRegistry
    private lateinit var executor: DefaultToolExecutor
    private lateinit var server: McpToolServer

    @Before
    fun setUp() {
        registry = DefaultToolRegistry()
        registry.register(EchoTool())
        registry.register(VaultTool())
        executor = DefaultToolExecutor(registry)
        server = McpToolServer(
            toolRegistry = registry,
            toolExecutor = executor,
            config = McpServerConfig(
                port = 0, // 随机端口
                bearerToken = "test-token-123",
                serverName = "test-server",
            ),
        )
        server.start()
    }

    @After
    fun tearDown() {
        server.stop()
    }

    private fun newClient(token: String = "test-token-123"): McpClient =
        McpClient(
            HttpMcpTransport(
                url = "http://127.0.0.1:${server.boundPort}/mcp",
                headers = mapOf("Authorization" to "Bearer $token"),
                requestTimeoutMs = 5000,
            ),
        )

    @Test
    fun `完整链路 - 握手 发现 调用`() = runBlocking {
        val client = newClient()
        val init = client.initialize()
        assertEquals("test-server", init.serverInfo.name)
        assertTrue(init.supportsTools)

        // tools/list —— vault_read 已被硬拦截，只剩 echo
        val tools = client.listAllTools()
        assertEquals(1, tools.size)
        assertEquals("echo", tools[0].name)

        // tools/call（通过服务端真实执行注册表里的工具）
        val result = client.callTool("echo", buildJsonObject { put("text", "hello mcp") })
        assertTrue(!result.isError)
        assertEquals("echo: hello mcp", result.renderText())

        client.close()
    }

    @Test
    fun `vault 前缀工具被硬拦截`() = runBlocking {
        val client = newClient()
        client.initialize()

        // 清单不包含
        val tools = client.listAllTools().map { it.name }
        assertTrue(!tools.contains("vault_read"))

        // 直接调用也拒绝（服务端返回 -32602 error 帧）
        try {
            client.callTool("vault_read", buildJsonObject { })
            throw AssertionError("应当抛出 McpRemoteException")
        } catch (e: com.androidguru.agent.mcp.protocol.McpRemoteException) {
            assertTrue(e.message?.contains("未知工具") == true)
        }
        client.close()
    }

    @Test
    fun `错误令牌被拒绝`() = runBlocking {
        val client = newClient(token = "wrong-token")
        try {
            client.initialize()
            throw AssertionError("应当抛出异常")
        } catch (e: IOException) {
            // HTTP 401 → IOException("MCP 请求失败: HTTP 401 ...")
            assertTrue(e.message?.contains("401") == true)
        } finally {
            client.close()
        }
    }

    @Test
    fun `缺少会话头的后续请求返回 404`() = runBlocking {
        val raw = HttpMcpTransport(
            url = "http://127.0.0.1:${server.boundPort}/mcp",
            headers = mapOf("Authorization" to "Bearer test-token-123"),
            requestTimeoutMs = 5000,
            sessionHeader = false, // 不缓存/回传会话头
        )
        raw.start()
        try {
            raw.send(99, com.androidguru.agent.mcp.protocol.JsonRpc.request(99, "tools/list"))
            throw AssertionError("应当抛出 IOException（服务端 404 session_not_found）")
        } catch (e: IOException) {
            assertTrue(e.message?.contains("404") == true)
        } finally {
            raw.close()
        }
    }

    @Test
    fun `工具调用走同一执行管线（含schema校验）`() = runBlocking {
        val client = newClient()
        client.initialize()
        // echo 需要 required text —— 空参数应触发 schema 校验失败而不是工具崩溃
        val result = client.callTool("echo", buildJsonObject { })
        assertTrue(result.isError)
        assertTrue(result.renderText().contains("text"))
        client.close()
    }
}

/** 桥接层测试。 */
class McpToolBridgeTest {

    @Test
    fun `命名归一化防碰撞`() {
        assertEquals("mcp__files__read_file", McpToolNaming.toolId("Files", "read-file"))
        assertEquals("mcp__files__read_file", McpToolNaming.toolId("FILES", "READ_FILE"))
        assertEquals("mcp__web__fetch", McpToolNaming.toolId("web", "fetch"))
        assertTrue(McpToolNaming.toolId("a b", "x!") != McpToolNaming.toolId("a", "b_x!"))
    }

    @Test
    fun `注册 - 注销对称`() = runBlocking {
        val registry = DefaultToolRegistry()
        val bridge = McpToolBridge(registry)

        // 构造一个带工具的假客户端：直接用手写 AgentTool 模拟注册效果
        val fakeTool = object : AgentTool {
            override val id = "mcp__fake__hello"
            override val name = "mcp__fake__hello"
            override val description = "假 MCP 工具"
            override val parameters = ToolSchema.empty()
            override suspend fun execute(request: ToolRequest): ToolResult = ToolResult.success("hi")
        }
        registry.register(fakeTool, com.androidguru.agent.tools.DuplicateToolIdPolicy.REPLACE)
        assertEquals(1, registry.size)

        bridge.unregisterServer(listOf("mcp__fake__hello"))
        assertEquals(0, registry.size)
    }

    @Test
    fun `McpAgentTool 输出钳制`() = runBlocking {
        val registry = DefaultToolRegistry()
        val executor = DefaultToolExecutor(registry)
        val bridge = McpToolBridge(registry, maxOutputChars = 100)

        // 通过服务端回环测试钳制
        val bigRegistry = DefaultToolRegistry()
        val bigTool = object : AgentTool {
            override val id = "big"
            override val description = "大输出"
            override suspend fun execute(request: ToolRequest): ToolResult = ToolResult.success("y".repeat(10_000))
        }
        bigRegistry.register(bigTool)
        val bigServer = McpToolServer(
            toolRegistry = bigRegistry,
            toolExecutor = DefaultToolExecutor(bigRegistry),
            config = McpServerConfig(port = 0, bearerToken = "t"),
        )
        bigServer.start()
        val client = McpClient(
            HttpMcpTransport(
                url = "http://127.0.0.1:${bigServer.boundPort}/mcp",
                headers = mapOf("Authorization" to "Bearer t"),
                requestTimeoutMs = 5000,
            ),
        )
        client.initialize()
        val tool = bridge.createAgentTool("mcp__t__big", "t", com.androidguru.agent.mcp.protocol.McpToolDescriptor("big", "", kotlinx.serialization.json.JsonObject(emptyMap())), client)
        registry.register(tool, com.androidguru.agent.tools.DuplicateToolIdPolicy.REPLACE)

        val result = executor.execute("mcp__t__big", "{}")
        assertTrue(result.ok)
        assertTrue(result.content.length < 300)
        assertTrue(result.content.contains("截断"))

        client.close()
        bigServer.stop()
    }
}
