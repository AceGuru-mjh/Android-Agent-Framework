package com.androidguru.agent.mcp.server

import com.androidguru.agent.mcp.protocol.JsonRpc
import com.androidguru.agent.mcp.protocol.McpProtocol
import com.androidguru.agent.tools.ToolExecutor
import com.androidguru.agent.tools.ToolRegistry
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.IOException
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/**
 * 内置 MCP 服务端 —— 把本框架的 [ToolRegistry] 经 Streamable HTTP 暴露为标准 MCP Server，
 * 任何外部 MCP 客户端（Claude Desktop / Cline / 其他 Agent）均可接入。
 *
 * 安全设计（fail-closed）：
 * - Bearer Token 常时比较（[MessageDigest.isEqual] 防时序攻击）；空 token = 拒绝全部；
 * - 工具白名单 + 黑名单（前缀）；`vault_` 前缀硬拦截（不可配置）；
 * - 每远端地址分钟窗口限速；会话空闲回收 + 容量上限。
 */
class McpToolServer(
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private val config: McpServerConfig,
) {

    private val json = Json { ignoreUnknownKeys = true }
    private var httpServer: HttpServer? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val rateCounter = AtomicLong(0)

    /** session → 最后活跃时间。 */
    private val sessions = ConcurrentHashMap<String, Long>()

    @Volatile
    var isRunning: Boolean = false
        private set

    fun start() {
        check(!isRunning) { "服务端已启动" }
        if (config.bearerToken.isBlank()) {
            throw IllegalStateException("bearerToken 为空 = 拒绝全部请求（fail-closed）。请显式配置令牌。")
        }
        val server = HttpServer.create(InetSocketAddress(config.port), 0)
        server.createContext(MCP_ENDPOINT) { exchange -> scope.launch { handle(exchange) } }
        server.executor = Executors.newFixedThreadPool(config.maxThreads)
        server.start()
        httpServer = server
        isRunning = true
        scheduleSessionSweep()
    }

    fun stop() {
        if (!isRunning) return
        isRunning = false
        httpServer?.stop(0)
        httpServer = null
        sessions.clear()
    }

    /** 实际监听端口（config.port = 0 时由系统分配）。 */
    val boundPort: Int get() = httpServer?.address?.port ?: config.port

    // ------------------------------------------------------------------
    // 请求处理
    // ------------------------------------------------------------------

    private suspend fun handle(exchange: HttpExchange) {
        val remote = exchange.remoteAddress.address?.hostAddress ?: "unknown"
        try {
            // 1. 鉴权（常时比较）
            if (!checkAuth(exchange)) {
                respond(exchange, 401, JsonRpc.errorResponse(JsonPrimitive(null as String?), JsonRpc.INVALID_REQUEST, "unauthorized"))
                return
            }
            // 2. 限速（每远端地址分钟窗口）
            if (!rateLimit(remote)) {
                respond(exchange, 429, JsonRpc.errorResponse(JsonPrimitive(null as String?), JsonRpc.INVALID_REQUEST, "rate limited"))
                return
            }
            when (exchange.requestMethod.uppercase()) {
                "GET" -> {
                    // 无服务器推送流
                    exchange.sendResponseHeaders(405, -1)
                    exchange.close()
                }

                "DELETE" -> {
                    val sid = exchange.requestHeaders.getFirst(McpProtocol.HEADER_SESSION_ID)
                    if (sid != null) sessions.remove(sid)
                    exchange.sendResponseHeaders(204, -1)
                    exchange.close()
                }

                "POST" -> handlePost(exchange)
                else -> {
                    exchange.sendResponseHeaders(405, -1)
                    exchange.close()
                }
            }
        } catch (ce: CancellationException) {
            exchange.close()
            throw ce
        } catch (e: Exception) {
            runCatching {
                respond(exchange, 500, JsonRpc.errorResponse(JsonPrimitive(null as String?), JsonRpc.INTERNAL_ERROR, "internal error"))
            }
        }
    }

    private suspend fun handlePost(exchange: HttpExchange) {
        val bodyText = exchange.requestBody?.use { it.readBytes() }?.toString(Charsets.UTF_8).orEmpty()
        if (bodyText.length > config.maxBodyBytes) {
            respond(exchange, 413, JsonRpc.errorResponse(JsonPrimitive(null as String?), JsonRpc.INVALID_REQUEST, "payload too large"))
            return
        }
        val incoming = JsonRpc.parseIncoming(bodyText)
        if (incoming == null) {
            respond(exchange, 400, JsonRpc.errorResponse(JsonPrimitive(null as String?), JsonRpc.PARSE_ERROR, "invalid json"))
            return
        }

        when (incoming) {
            is JsonRpc.Incoming.Notice -> {
                // 通知：202 无体
                exchange.sendResponseHeaders(202, -1)
                exchange.close()
            }

            is JsonRpc.Incoming.Call -> handleCall(exchange, incoming)
        }
    }

    private suspend fun handleCall(exchange: HttpExchange, call: JsonRpc.Incoming.Call) {
        // initialize 免会话；其余方法要求已知会话
        val sessionHeader = exchange.requestHeaders.getFirst(McpProtocol.HEADER_SESSION_ID)
        if (call.method == McpProtocol.Methods.INITIALIZE) {
            val sessionId = UUID.randomUUID().toString()
            sessions[sessionId] = System.currentTimeMillis()
            val result = buildJsonObject {
                put("protocolVersion", McpProtocol.PROTOCOL_VERSION)
                putJsonObject("capabilities") {
                    putJsonObject("tools") {
                        put("listChanged", false)
                    }
                }
                putJsonObject("serverInfo") {
                    put("name", config.serverName)
                    put("version", SERVER_VERSION)
                }
                put("instructions", "本服务器暴露 Android Agent Framework 的注册工具清单。")
            }
            val body = JsonRpc.successResponse(call.id, result)
            val headers = exchange.responseHeaders
            headers.set(McpProtocol.HEADER_SESSION_ID, sessionId)
            respond(exchange, 200, body)
            return
        }

        if (sessionHeader == null || !sessions.containsKey(sessionHeader)) {
            respond(exchange, 404, JsonRpc.errorResponse(call.id, JsonRpc.INVALID_REQUEST, "session_not_found"))
            return
        }
        sessions[sessionHeader] = System.currentTimeMillis()

        val response: JsonObject = try {
            when (call.method) {
                McpProtocol.Methods.TOOLS_LIST -> buildToolsList(call)
                McpProtocol.Methods.TOOLS_CALL -> executeToolCall(call)
                McpProtocol.Methods.PING -> JsonRpc.successResponse(call.id, buildJsonObject { })

                else -> JsonRpc.errorResponse(call.id, JsonRpc.METHOD_NOT_FOUND, "method not found: ${call.method}")
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            JsonRpc.errorResponse(call.id, JsonRpc.INTERNAL_ERROR, e.message ?: "internal error")
        }
        respond(exchange, 200, response)
    }

    /** tools/list：白名单 / 黑名单过滤后的工具清单（MCP 格式）。 */
    private fun buildToolsList(call: JsonRpc.Incoming.Call): JsonObject {
        val tools = toolRegistry.getAllTools()
            .filter { isExposed(it.id) }
            .map { t ->
                buildJsonObject {
                    put("name", t.id)
                    put("description", t.description)
                    put("inputSchema", runCatching {
                        json.parseToJsonElement(t.parameters.render().toString())
                    }.getOrElse { buildJsonObject { put("type", "object") } })
                }
            }
        return JsonRpc.successResponse(
            call.id,
            buildJsonObject {
                putJsonArray("tools") { tools.forEach { add(it) } }
            },
        )
    }

    /** tools/call：**不旁路执行管线** —— 与内部 Agent 走同一 ToolExecutor。 */
    private suspend fun executeToolCall(call: JsonRpc.Incoming.Call): JsonObject {
        val name = (call.params["name"] as? JsonPrimitive)?.content
            ?: return JsonRpc.errorResponse(call.id, JsonRpc.INVALID_PARAMS, "缺少 name 参数")
        if (!isExposed(name)) {
            return JsonRpc.errorResponse(call.id, JsonRpc.INVALID_PARAMS, "未知工具: $name")
        }
        val arguments = call.params["arguments"] as? JsonObject ?: JsonObject(emptyMap())

        val result = try {
            toolExecutor.execute(toolId = name, arguments = arguments.toString(), callId = "mcp-host-${UUID.randomUUID()}")
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            com.androidguru.agent.tools.ToolResult.failure("工具执行异常: ${e.message}")
        }

        val content = buildJsonArrayCompat {
            add(buildJsonObject {
                put("type", "text")
                put("text", result.content.take(config.maxOutputChars))
            })
        }
        return JsonRpc.successResponse(
            call.id,
            buildJsonObject {
                putJsonArray("content") { content.forEach { add(it) } }
                put("isError", !result.ok)
            },
        )
    }

    private fun isExposed(toolId: String): Boolean {
        if (toolId.startsWith(HARD_BLOCKED_PREFIX)) return false // vault 硬拦截，不可配置
        if (config.blockedToolPrefixes.any { toolId.startsWith(it) }) return false
        val allowed = config.allowedToolIds ?: return true
        return toolId in allowed
    }

    // ------------------------------------------------------------------
    // 安全设施
    // ------------------------------------------------------------------

    private fun checkAuth(exchange: HttpExchange): Boolean {
        val provided = exchange.requestHeaders.getFirst("Authorization")?.removePrefix("Bearer ")?.trim()
        if (provided.isNullOrBlank()) return false
        return MessageDigest.isEqual(
            provided.toByteArray(Charsets.UTF_8),
            config.bearerToken.toByteArray(Charsets.UTF_8),
        )
    }

    private fun rateLimit(remote: String): Boolean {
        val windowStart = System.currentTimeMillis() / 60_000L
        val key = "$remote:$windowStart"
        val count = rateMap.computeIfAbsent(key) { AtomicLong(0) }.incrementAndGet()
        // 清理过期窗口
        if (rateMap.size > 1024) {
            rateMap.entries.removeIf { it.key.substringAfterLast(':').toLongOrNull() != windowStart }
        }
        return count <= config.rateLimitPerMinute
    }

    private val rateMap = java.util.concurrent.ConcurrentHashMap<String, AtomicLong>()

    private fun scheduleSessionSweep() {
        scope.launch {
            while (isRunning) {
                kotlinx.coroutines.delay(60_000)
                val now = System.currentTimeMillis()
                sessions.entries.removeIf { now - it.value > config.sessionIdleMs }
                if (sessions.size > config.maxSessions) {
                    sessions.entries.sortedBy { it.value }.take(sessions.size - config.maxSessions)
                        .forEach { sessions.remove(it.key) }
                }
            }
        }
    }

    private fun respond(exchange: HttpExchange, status: Int, body: JsonObject) {
        val bytes = body.toString().toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.set("Content-Type", "application/json")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
        exchange.close()
    }

    private inline fun buildJsonArrayCompat(block: kotlinx.serialization.json.JsonArrayBuilder.() -> Unit) =
        kotlinx.serialization.json.buildJsonArray(block)

    companion object {
        const val MCP_ENDPOINT = "/mcp"
        const val SERVER_VERSION = "1.0.0"
        const val HARD_BLOCKED_PREFIX = "vault_"
    }
}

/**
 * 服务端配置。
 */
data class McpServerConfig(
    /** 监听端口；0 = 随机（测试友好）。 */
    val port: Int = 8765,
    /** Bearer Token；空 = 拒绝全部（fail-closed），必须显式配置。 */
    val bearerToken: String,
    val serverName: String = "android-agent-framework",
    /** 工具白名单；null = 全部暴露。 */
    val allowedToolIds: Set<String>? = null,
    /** 工具黑名单前缀。 */
    val blockedToolPrefixes: Set<String> = emptySet(),
    val maxSessions: Int = 256,
    val sessionIdleMs: Long = 30 * 60_000L,
    val rateLimitPerMinute: Int = 120,
    val maxBodyBytes: Int = 1 shl 20, // 1MB
    val maxThreads: Int = 16,
    val maxOutputChars: Int = 16_000,
)
