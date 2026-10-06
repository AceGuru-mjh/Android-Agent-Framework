package com.androidguru.agent.mcp.client

import com.androidguru.agent.mcp.McpException
import com.androidguru.agent.mcp.protocol.JsonRpc
import com.androidguru.agent.mcp.protocol.McpCallToolResult
import com.androidguru.agent.mcp.protocol.McpInitializeResult
import com.androidguru.agent.mcp.protocol.McpProtocol
import com.androidguru.agent.mcp.protocol.McpToolDescriptor
import com.androidguru.agent.mcp.protocol.McpToolsPage
import com.androidguru.agent.mcp.transport.McpTransport
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * MCP 客户端 —— 协议核心（重做版）。
 *
 * 生命周期：
 * ```
 * transport.start() → initialize()（握手 + initialized 通知）
 *   → listAllTools()（跟随游标分页） → callTool(...) → close()
 * ```
 *
 * 防御细节：
 * - 每次请求自增 id；传输层按 id 精准匹配响应；
 * - JSON-RPC error 帧统一抛 [McpException]（而非静默空成功）；
 * - tools/list 游标跟随上限 [MAX_PAGINATION_PAGES]，防恶意游标环；
 * - CancellationException 不包装、直接传播。
 */
class McpClient(
    private val transport: McpTransport,
    private val clientName: String = "android-agent-framework",
    private val clientVersion: String = "1.0.0",
) {

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    var initializeResult: McpInitializeResult? = null
        private set

    /** 执行 initialize 握手，并回发 initialized 通知。 */
    suspend fun initialize(): McpInitializeResult {
        val params = buildJsonObject {
            put("protocolVersion", McpProtocol.PROTOCOL_VERSION)
            putJsonObject("capabilities") {
                putJsonObject("tools") {}
                putJsonObject("resources") {}
            }
            putJsonObject("clientInfo") {
                put("name", clientName)
                put("version", clientVersion)
            }
        }
        val response = sendRequest(McpProtocol.Methods.INITIALIZE, params)
        val result = JsonRpc.resultOf(response)
        val info = McpInitializeResult.fromJson(result as? JsonObject ?: JsonObject(emptyMap()))

        // 回发 initialized 通知（无 id）
        transport.send(
            id = null,
            payload = JsonRpc.notification(McpProtocol.Methods.INITIALIZED),
        )
        initializeResult = info
        return info
    }

    /** 单页工具清单。 */
    suspend fun listTools(cursor: String? = null): McpToolsPage {
        val params = buildJsonObject {
            if (cursor != null) put("cursor", cursor)
        }
        val response = sendRequest(McpProtocol.Methods.TOOLS_LIST, params)
        val result = response["result"] as? JsonObject ?: JsonObject(emptyMap())
        val tools = (result["tools"] as? JsonArray ?: JsonArray(emptyList()))
            .mapNotNull { el -> (el as? JsonObject)?.let(McpToolDescriptor::fromJson) }
        val next = (result["nextCursor"] as? JsonPrimitive)?.content
        return McpToolsPage(tools, next)
    }

    /** 跟随游标拉取全量工具清单。 */
    suspend fun listAllTools(maxPages: Int = MAX_PAGINATION_PAGES): List<McpToolDescriptor> {
        val all = mutableListOf<McpToolDescriptor>()
        var cursor: String? = null
        var pages = 0
        do {
            val page = listTools(cursor)
            all += page.tools
            cursor = page.nextCursor
            pages++
            if (pages > maxPages) {
                throw McpException("工具清单分页超过 $maxPages 页上限，可能存在游标环")
            }
        } while (cursor != null)
        return all
    }

    /** 调用远端工具。 */
    suspend fun callTool(name: String, arguments: JsonObject): McpCallToolResult {
        val params = buildJsonObject {
            put("name", name)
            put("arguments", arguments)
        }
        val response = sendRequest(McpProtocol.Methods.TOOLS_CALL, params)
        val result = JsonRpc.resultOf(response) as? JsonObject
            ?: throw McpException("tools/call 响应缺少 result 对象")
        return McpCallToolResult.fromJson(result)
    }

    /** 健康检查。 */
    suspend fun ping(): Boolean = try {
        val response = sendRequest(McpProtocol.Methods.PING, null)
        response["result"] != null
    } catch (ce: kotlinx.coroutines.CancellationException) {
        throw ce
    } catch (e: Exception) {
        false
    }

    suspend fun close() {
        transport.close()
    }

    private suspend fun sendRequest(method: String, params: JsonObject?): JsonObject {
        if (method != McpProtocol.Methods.INITIALIZE && initializeResult == null) {
            throw McpException("必须先 initialize() 再调用 $method")
        }
        val id = idCounter.incrementAndGet()
        val response = transport.send(id, JsonRpc.request(id, method, params))
            ?: throw McpException("请求 $method 未获得响应")
        // 响应 id 校验（防乱序 / 错配）
        val respId = JsonRpc.idOf(response)
        if (respId != null && respId is JsonPrimitive && respId.content != id.toString()) {
            throw McpException("响应 id 不匹配: 期望 $id, 实际 ${respId.content}")
        }
        return response
    }

    private val idCounter = java.util.concurrent.atomic.AtomicLong(0)

    companion object {
        const val MAX_PAGINATION_PAGES = 20
    }
}
