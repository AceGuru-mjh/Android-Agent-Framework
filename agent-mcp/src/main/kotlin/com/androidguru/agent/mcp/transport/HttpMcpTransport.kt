package com.androidguru.agent.mcp.transport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Streamable HTTP 传输（MCP 规范 2025-03 版传输，兼容 2024-11-05 服务器的单端点 POST 语义）。
 *
 * 规范细节：
 * - `Accept: application/json, text/event-stream` 双协商；
 * - **会话头**：捕获 initialize 响应的 `Mcp-Session-Id` 并全程回带；
 * - 每请求携带 `MCP-Protocol-Version` 头（有状态服务器缺此头可能 404）；
 * - 通知（id=null）不解析响应体，仅看 2xx 状态码（规范 202 语义）；
 * - 响应体兼容裸 JSON 与 SSE `data:` 分帧（取最后一条 JSON-RPC 响应）。
 */
class HttpMcpTransport(
    private val url: String,
    private val headers: Map<String, String> = emptyMap(),
    private val requestTimeoutMs: Long = 120_000L,
    private val sessionHeader: Boolean = true,
) : McpTransport {

    override val name: String = "http:$url"

    private val json = Json { ignoreUnknownKeys = true }
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(requestTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    @Volatile
    private var sessionId: String? = null

    private val closed = java.util.concurrent.atomic.AtomicBoolean(false)
    private val idCounter = AtomicLong(0)
    private val inFlight = java.util.concurrent.ConcurrentHashMap<Call, CompletableDeferred<Response>>()

    fun nextId(): Long = idCounter.incrementAndGet()

    override suspend fun start() {
        // HTTP 无连接建立期；会话在 initialize 响应中获取
    }

    override suspend fun send(id: Long?, payload: JsonObject): JsonObject? {
        check(!closed.get()) { "传输已关闭" }

        val builder = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")

        headers.forEach { (k, v) -> builder.header(k, v) }
        if (sessionHeader) {
            sessionId?.let { builder.header(com.androidguru.agent.mcp.protocol.McpProtocol.HEADER_SESSION_ID, it) }
            builder.header(
                com.androidguru.agent.mcp.protocol.McpProtocol.HEADER_PROTOCOL_VERSION,
                com.androidguru.agent.mcp.protocol.McpProtocol.PROTOCOL_VERSION,
            )
        }

        val call = http.newCall(builder.build())

        if (id == null) {
            // 通知：仅看 2xx，不解析体
            val resp = awaitResponse(call)
            resp.use { r ->
                if (!r.isSuccessful) throw IOException("MCP 通知被拒绝: HTTP ${r.code}")
            }
            return null
        }

        val resp = awaitResponse(call)
        resp.use { r ->
            // 捕获会话头（initialize 响应）
            if (sessionHeader) {
                r.header(com.androidguru.agent.mcp.protocol.McpProtocol.HEADER_SESSION_ID)
                    ?.let { if (sessionId == null) sessionId = it }
            }
            if (!r.isSuccessful) {
                val body = runCatching { r.body?.string() }.getOrNull()
                throw IOException("MCP 请求失败: HTTP ${r.code} ${body?.take(200) ?: ""}")
            }
            val contentType = r.header("Content-Type") ?: ""
            val body = r.body?.string().orEmpty()
            val frame = if (contentType.contains("text/event-stream")) {
                parseSseFrame(body)
            } else {
                body
            }
            if (frame.isBlank()) throw IOException("MCP 响应体为空")
            return try {
                json.parseToJsonElement(frame) as? JsonObject
                    ?: throw IOException("MCP 响应不是 JSON 对象")
            } catch (e: Exception) {
                throw IOException("MCP 响应解析失败: ${e.message}", e)
            }
        }
    }

    /** 从 SSE 文本中取最后一条 JSON-RPC 响应帧。 */
    private fun parseSseFrame(body: String): String {
        var last: String = ""
        for (line in body.lineSequence()) {
            val trimmed = line.trim()
            if (!trimmed.startsWith("data:")) continue
            val payload = trimmed.removePrefix("data:").trim()
            if (payload == "[DONE]") continue
            if (payload.contains("\"id\"") || payload.contains("\"result\"") || payload.contains("\"error\"")) {
                last = payload
            }
        }
        return last
    }

    private suspend fun awaitResponse(call: Call): Response {
        val deferred = CompletableDeferred<Response>()
        inFlight[call] = deferred
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                deferred.completeExceptionally(e)
            }

            override fun onResponse(call: Call, response: Response) {
                deferred.complete(response)
            }
        })
        try {
            return deferred.await()
        } finally {
            inFlight.remove(call)
        }
    }

    override fun isHealthy(): Boolean = !closed.get()

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        sessionId = null
    }
}
