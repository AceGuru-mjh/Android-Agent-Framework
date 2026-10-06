package com.androidguru.agent.mcp

import com.androidguru.agent.mcp.client.McpClient
import com.androidguru.agent.mcp.protocol.JsonRpc
import com.androidguru.agent.mcp.protocol.McpRemoteException
import com.androidguru.agent.mcp.transport.McpTransport
import com.androidguru.agent.mcp.transport.StdioMcpTransport
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.atomic.AtomicLong

/**
 * 脚本化传输：按 method 规则返回响应；记录收到的请求。
 */
private class ScriptedTransport : McpTransport {
    override val name = "scripted"
    val sentRequests = mutableListOf<JsonObject>()

    /** 每个请求先经此函数产生响应（返回 null = 无响应）。 */
    var responder: (JsonObject) -> JsonObject? = { null }
    val idCounter = AtomicLong(0)

    fun nextRequestId(): Long = idCounter.incrementAndGet()

    override suspend fun start() {}

    override suspend fun send(id: Long?, payload: JsonObject): JsonObject? {
        sentRequests += payload
        if (id == null) return null
        val response = responder(payload) ?: throw IllegalStateException("scripted no response for ${payload["method"]}")
        return response
    }

    override fun isHealthy(): Boolean = true
    override suspend fun close() {}
}

class McpClientTest {

    private fun okResponse(id: Any?, result: String): JsonObject =
        Json.parseToJsonElement("""{"jsonrpc":"2.0","id":$id,"result":$result}""").let { it as JsonObject }

    @Test
    fun `initialize 握手发送规范参数并回发 initialized 通知`() = runTest {
        val transport = ScriptedTransport()
        transport.responder = { req ->
            val method = (req["method"] as JsonPrimitive).content
            when (method) {
                "initialize" -> okResponse(1, """{"protocolVersion":"2024-11-05","capabilities":{"tools":{}},"serverInfo":{"name":"fake","version":"0.1"}}""")
                else -> okResponse(2, "{}")
            }
        }
        val client = McpClient(transport)
        val result = client.initialize()

        assertEquals("fake", result.serverInfo.name)
        assertTrue(result.supportsTools)

        val initReq = transport.sentRequests[0]
        assertEquals("initialize", (initReq["method"] as JsonPrimitive).content)
        val params = initReq["params"] as JsonObject
        assertEquals("2024-11-05", params["protocolVersion"]!!.jsonPrimitive.content)

        // 通知（无 id）
        val notice = transport.sentRequests[1]
        assertEquals("notifications/initialized", (notice["method"] as JsonPrimitive).content)
        assertTrue(!notice.containsKey("id"))
    }

    @Test
    fun `未握手调用工具抛 McpException`() = runTest {
        val transport = ScriptedTransport()
        val client = McpClient(transport)
        assertThrows(McpException::class.java) {
            kotlinx.coroutines.runBlocking { client.callTool("x", buildJsonObject { }) }
        }
    }

    @Test
    fun `listAllTools 跟随游标分页`() = runTest {
        val transport = ScriptedTransport()
        var page = 0
        transport.responder = { req ->
            val method = (req["method"] as JsonPrimitive).content
            if (method == "initialize") {
                fakeInitializeResponse(JsonRpc.idOf(req)!!)
            } else {
                page++
                val toolName = if (page == 1) "tool_a" else "tool_b"
                val result = kotlinx.serialization.json.buildJsonObject {
                    put("tools", kotlinx.serialization.json.buildJsonArray {
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("name", toolName)
                            put("description", toolName.uppercase())
                            put("inputSchema", kotlinx.serialization.json.buildJsonObject { put("type", "object") })
                        })
                    })
                    if (page == 1) put("nextCursor", "cursor_2")
                }
                JsonRpc.successResponse(JsonRpc.idOf(req)!!, result)
            }
        }
        val client = McpClient(transport)
        client.initialize()

        val tools = client.listAllTools()
        assertEquals(2, tools.size)
        assertEquals("tool_a", tools[0].name)
        assertEquals("tool_b", tools[1].name)
    }

    private fun fakeInitializeResponse(id: kotlinx.serialization.json.JsonElement): JsonObject =
        JsonRpc.successResponse(
            id,
            kotlinx.serialization.json.buildJsonObject {
                put("protocolVersion", "2024-11-05")
                put("capabilities", kotlinx.serialization.json.buildJsonObject {
                    put("tools", kotlinx.serialization.json.buildJsonObject { })
                })
                put("serverInfo", kotlinx.serialization.json.buildJsonObject {
                    put("name", "fake")
                    put("version", "0.1")
                })
            },
        )

    @Test
    fun `游标环超过上限抛异常`() = runTest {
        val transport = ScriptedTransport()
        transport.responder = { req ->
            okResponse(JsonRpc.idOf(req), """{"tools":[],"nextCursor":"cursor_forever"}""")
        }
        val client = McpClient(transport)
        client.initialize()
        assertThrows(McpException::class.java) {
            kotlinx.coroutines.runBlocking { client.listAllTools(maxPages = 3) }
        }
    }

    @Test
    fun `callTool 解析 content 与 isError`() = runTest {
        val transport = ScriptedTransport()
        transport.responder = { req ->
            okResponse(JsonRpc.idOf(req), """{"content":[{"type":"text","text":"42"}],"isError":false}""")
        }
        val client = McpClient(transport)
        client.initialize()
        val result = client.callTool("calc", buildJsonObject { })
        assertFalse(result.isError)
        assertEquals("42", result.renderText())
    }

    @Test
    fun `远端 error 帧抛 McpRemoteException`() = runTest {
        val transport = ScriptedTransport()
        transport.responder = { req ->
            val method = (req["method"] as JsonPrimitive).content
            if (method == "initialize") {
                okResponse(JsonRpc.idOf(req), """{"protocolVersion":"2024-11-05","capabilities":{"tools":{}},"serverInfo":{"name":"f","version":"0"}}""")
            } else {
                Json.parseToJsonElement("""{"jsonrpc":"2.0","id":2,"error":{"code":-32602,"message":"bad params"}}""").let { it as JsonObject }
            }
        }
        val client = McpClient(transport)
        client.initialize()
        assertThrows(McpRemoteException::class.java) {
            kotlinx.coroutines.runBlocking { client.callTool("x", buildJsonObject { }) }
        }
    }
}

/**
 * stdio 传输单元测试：注入管道流 + 应答线程的 Fake 进程，不依赖真实子进程。
 * 使用 runBlocking（真实时间）—— 传输层依赖真实 IO 线程，与 runTest 虚拟时钟不兼容。
 */
class StdioTransportTest {

    @Test
    fun `stdio 帧收发与响应匹配`() = kotlinx.coroutines.runBlocking {
        // 客户端 → 进程
        val clientOut = PipedOutputStream()
        val serverIn = PipedInputStream(clientOut, 65536)
        // 进程 → 客户端
        val serverOut = PipedOutputStream()
        val clientIn = PipedInputStream(serverOut, 65536)

        val process = object : com.androidguru.agent.mcp.transport.McpProcess {
            override val stdin: java.io.OutputStream = clientOut
            override val stdout: java.io.InputStream = clientIn
            override val stderr: java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))
            override val isAlive: Boolean get() = true
            override fun destroy() {}
        }

        val transport = StdioMcpTransport(
            command = listOf("fake-mcp-server"),
            processLauncher = { _, _, _ -> process },
            requestTimeoutMs = 5000,
        )
        transport.start()

        // 启动一个应答线程：读请求行 → 回写响应
        val responder = Thread {
            val reader = java.io.BufferedReader(java.io.InputStreamReader(serverIn))
            try {
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val req = Json.parseToJsonElement(line!!).let { it as JsonObject }
                    val id = req["id"]?.jsonPrimitive?.content ?: continue
                    val method = req["method"]?.jsonPrimitive?.content ?: continue
                    if (method == "ping") {
                        val resp = """{"jsonrpc":"2.0","id":$id,"result":{}}"""
                        synchronized(serverOut) {
                            serverOut.write(resp.toByteArray())
                            serverOut.write('\n'.code)
                            serverOut.flush()
                        }
                    }
                }
            } catch (e: Exception) {
            }
        }
        responder.isDaemon = true
        responder.start()

        val response = transport.send(1, JsonRpc.request(1, "ping"))
        assertNotNull(response)
        assertEquals("1", JsonRpc.idOf(response!!)?.jsonPrimitive?.content)

        transport.close()
    }

    @Test
    fun `超时抛 TimeoutException`() = kotlinx.coroutines.runBlocking {
        // stdin：已连接但永远无人读的管道（写入不阻塞、也无响应）
        val silentStdin = PipedOutputStream(java.io.PipedInputStream(65536))
        val process = object : com.androidguru.agent.mcp.transport.McpProcess {
            override val stdin: java.io.OutputStream = silentStdin
            override val stdout: java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))
            override val stderr: java.io.InputStream = java.io.ByteArrayInputStream(ByteArray(0))
            override val isAlive: Boolean get() = true
            override fun destroy() {}
        }
        val transport = StdioMcpTransport(
            command = listOf("silent-server"),
            processLauncher = { _, _, _ -> process },
            requestTimeoutMs = 100,
        )
        transport.start()
        assertThrows(java.util.concurrent.TimeoutException::class.java) {
            kotlinx.coroutines.runBlocking { transport.send(1, JsonRpc.request(1, "ping")) }
        }
        transport.close()
    }
}
