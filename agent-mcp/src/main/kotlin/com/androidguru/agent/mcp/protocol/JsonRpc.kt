package com.androidguru.agent.mcp.protocol

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * JSON-RPC 2.0 消息构造与宽容解析。
 *
 * 宽容策略（对齐 MCP 生态现实）：
 * - `jsonrpc` 字段缺失不拒收；
 * - `id` 接受 number / string / null；
 * - `params` 缺失视为空对象。
 */
object JsonRpc {

    const val VERSION = "2.0"

    // 标准错误码
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    private val json = Json { ignoreUnknownKeys = true }

    // ------------------------------------------------------------------
    // 构造
    // ------------------------------------------------------------------

    fun request(id: Long, method: String, params: JsonObject? = null): JsonObject = buildJsonObject {
        put("jsonrpc", VERSION)
        put("id", id)
        put("method", method)
        if (params != null) put("params", params)
    }

    fun request(id: String, method: String, params: JsonObject? = null): JsonObject = buildJsonObject {
        put("jsonrpc", VERSION)
        put("id", id)
        put("method", method)
        if (params != null) put("params", params)
    }

    fun notification(method: String, params: JsonObject? = null): JsonObject = buildJsonObject {
        put("jsonrpc", VERSION)
        put("method", method)
        if (params != null) put("params", params)
    }

    fun successResponse(id: JsonElement, result: JsonElement): JsonObject = buildJsonObject {
        put("jsonrpc", VERSION)
        put("id", id)
        put("result", result)
    }

    fun errorResponse(id: JsonElement, code: Int, message: String): JsonObject = buildJsonObject {
        put("jsonrpc", VERSION)
        put("id", id)
        putJsonObject("error") {
            put("code", code)
            put("message", message)
        }
    }

    // ------------------------------------------------------------------
    // 解析
    // ------------------------------------------------------------------

    /** 入站消息类型。 */
    sealed class Incoming {
        /** 带 id 的请求（需要响应）。 */
        data class Call(val id: JsonElement, val method: String, val params: JsonObject) : Incoming()

        /** 通知（无需响应）。 */
        data class Notice(val method: String, val params: JsonObject) : Incoming()
    }

    /** 解析入站消息；不合法返回 null（宽容策略：日志级处理而非报错）。 */
    fun parseIncoming(text: String): Incoming? {
        val obj = try {
            json.parseToJsonElement(text) as? JsonObject ?: return null
        } catch (e: Exception) {
            return null
        }
        val method = (obj["method"] as? JsonPrimitive)?.content ?: return null
        val params = obj["params"] as? JsonObject ?: JsonObject(emptyMap())
        val idEl = obj["id"]
        return when (idEl) {
            null -> Incoming.Notice(method, params)
            is JsonPrimitive -> if (idEl.isString && idEl.content == "null") {
                Incoming.Notice(method, params)
            } else {
                Incoming.Call(idEl, method, params)
            }

            is JsonObject, is JsonArray -> Incoming.Call(idEl, method, params)
        }
    }

    /** 解析响应帧的 result 字段；error 存在时抛 [McpRemoteException]。 */
    fun resultOf(response: JsonObject): JsonElement {
        val error = response["error"] as? JsonObject
        if (error != null) {
            val code = (error["code"] as? JsonPrimitive)?.content?.toIntOrNull() ?: INTERNAL_ERROR
            val message = (error["message"] as? JsonPrimitive)?.content ?: "unknown error"
            throw McpRemoteException(code, message)
        }
        return response["result"] ?: JsonObject(emptyMap())
    }

    fun idOf(response: JsonObject): JsonElement? = response["id"]
}

/** 远端返回 JSON-RPC error 的异常。 */
class McpRemoteException(val code: Int, message: String) : RuntimeException("MCP error $code: $message")
