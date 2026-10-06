package com.androidguru.agent.mcp.protocol

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * MCP 协议常量与数据模型（重做版，遵循 MCP 规范 2024-11-05）。
 */
object McpProtocol {

    const val PROTOCOL_VERSION = "2024-11-05"

    /** HTTP 会话头 / 协议版本头。 */
    const val HEADER_SESSION_ID = "Mcp-Session-Id"
    const val HEADER_PROTOCOL_VERSION = "MCP-Protocol-Version"

    object Methods {
        const val INITIALIZE = "initialize"
        const val INITIALIZED = "notifications/initialized"
        const val TOOLS_LIST = "tools/list"
        const val TOOLS_CALL = "tools/call"
        const val PING = "ping"
        const val TOOLS_LIST_CHANGED = "notifications/tools/list_changed"
    }

    object Capabilities {
        const val TOOLS = "tools"
        const val RESOURCES = "resources"
        const val PROMPTS = "prompts"
    }
}

/** 服务器描述。 */
data class McpServerInfo(
    val name: String,
    val version: String,
) {
    companion object {
        fun fromJson(obj: JsonObject): McpServerInfo = McpServerInfo(
            name = (obj["name"] as? JsonPrimitive)?.content ?: "unknown",
            version = (obj["version"] as? JsonPrimitive)?.content ?: "0",
        )
    }
}

/** initialize 握手结果。 */
data class McpInitializeResult(
    val protocolVersion: String,
    val supportsTools: Boolean,
    val supportsResources: Boolean,
    val supportsPrompts: Boolean,
    val serverInfo: McpServerInfo,
    val instructions: String?,
) {
    companion object {
        fun fromJson(obj: JsonObject): McpInitializeResult {
            val caps = obj["capabilities"] as? JsonObject
            return McpInitializeResult(
                protocolVersion = (obj["protocolVersion"] as? JsonPrimitive)?.content ?: McpProtocol.PROTOCOL_VERSION,
                supportsTools = caps?.get("tools") != null,
                supportsResources = caps?.get("resources") != null,
                supportsPrompts = caps?.get("prompts") != null,
                serverInfo = (obj["serverInfo"] as? JsonObject)?.let(McpServerInfo::fromJson) ?: McpServerInfo("unknown", "0"),
                instructions = (obj["instructions"] as? JsonPrimitive)?.contentOrNull,
            )
        }
    }
}

/** 远端工具描述。 */
data class McpToolDescriptor(
    val name: String,
    val description: String,
    /** 输入 JSON Schema（原样保存，由桥接层转换为 ToolSchema）。 */
    val inputSchema: JsonElement,
) {
    companion object {
        fun fromJson(obj: JsonObject): McpToolDescriptor = McpToolDescriptor(
            name = (obj["name"] as? JsonPrimitive)?.content ?: "",
            description = (obj["description"] as? JsonPrimitive)?.content ?: "",
            inputSchema = obj["inputSchema"] ?: JsonObject(emptyMap()),
        )
    }
}

/** tools/list 分页。 */
data class McpToolsPage(
    val tools: List<McpToolDescriptor>,
    val nextCursor: String?,
)

/** 工具调用内容块。 */
data class McpContent(
    val type: String,
    val text: String?,
)

/** tools/call 结果。 */
data class McpCallToolResult(
    val content: List<McpContent>,
    val isError: Boolean,
) {
    /** 渲染为模型可读文本（text 类型取文本，其他类型序列化占位）。 */
    fun renderText(): String = content.joinToString("\n") { c ->
        c.text ?: "[${c.type}]"
    }

    companion object {
        fun fromJson(obj: JsonObject): McpCallToolResult {
            val contentArr = obj["content"] as? JsonArray ?: JsonArray(emptyList())
            val contents = contentArr.mapNotNull { el ->
                val c = el as? JsonObject ?: return@mapNotNull null
                McpContent(
                    type = (c["type"] as? JsonPrimitive)?.content ?: "text",
                    text = (c["text"] as? JsonPrimitive)?.contentOrNull,
                )
            }
            return McpCallToolResult(
                content = contents,
                isError = (obj["isError"] as? JsonPrimitive)?.content == "true",
            )
        }
    }
}
