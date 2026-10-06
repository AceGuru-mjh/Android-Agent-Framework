package com.androidguru.agent.mcp.bridge

import com.androidguru.agent.mcp.client.McpClient
import com.androidguru.agent.mcp.protocol.McpCallToolResult
import com.androidguru.agent.tools.AgentTool
import com.androidguru.agent.tools.DuplicateToolIdPolicy
import com.androidguru.agent.tools.ToolCategory
import com.androidguru.agent.tools.ToolMetadata
import com.androidguru.agent.tools.ToolRequest
import com.androidguru.agent.mcp.protocol.McpToolDescriptor
import com.androidguru.agent.tools.ToolResult
import com.androidguru.agent.tools.ToolRisk
import com.androidguru.agent.tools.ToolAnnotations
import com.androidguru.agent.tools.ToolErrorCode
import com.androidguru.agent.tools.ToolSchema
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * 工具命名：注册表 id = `mcp__{server_slug}__{tool_slug}`。
 * slug 归一化到 [a-z0-9_]，防跨服务器 / 特殊字符 id 碰撞。
 */
object McpToolNaming {

    fun slug(raw: String): String {
        val s = buildString {
            for (ch in raw.lowercase()) {
                when {
                    ch in 'a'..'z' || ch in '0'..'9' || ch == '_' -> append(ch)
                    else -> append('_')
                }
            }
        }
        return s.ifBlank { "tool" }
    }

    fun toolId(serverName: String, remoteToolName: String): String =
        "mcp__${slug(serverName)}__${slug(remoteToolName)}"
}

/**
 * MCP → 一等工具桥：把远端 MCP 服务器的工具批量注册进 [com.androidguru.agent.tools.ToolRegistry]，
 * 引擎与其他工具一视同仁地调用。
 */
class McpToolBridge(
    private val registry: com.androidguru.agent.tools.ToolRegistry,
    private val maxOutputChars: Int = 16_000,
) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 发现并注册一个 MCP 服务器的全部工具。
     * 返回注册的工具 id 列表（注销时回传给 [unregisterServer]）。
     */
    suspend fun registerServer(serverName: String, client: McpClient): List<String> {
        val tools = client.listAllTools()
        val registered = mutableListOf<String>()
        for (descriptor in tools) {
            val id = McpToolNaming.toolId(serverName, descriptor.name)
            registry.register(createAgentTool(id, serverName, descriptor, client), DuplicateToolIdPolicy.REPLACE)
            registered += id
        }
        return registered
    }

    /** 精确注销（避免注销时误伤其他服务器的工具）。 */
    fun unregisterServer(registeredIds: List<String>) {
        registeredIds.forEach { registry.unregister(it) }
    }

    internal fun createAgentTool(
        id: String,
        serverName: String,
        descriptor: McpToolDescriptor,
        client: McpClient,
    ): AgentTool = McpAgentTool(
        id = id,
        serverName = serverName,
        remoteName = descriptor.name,
        description = descriptor.description.ifBlank { "MCP tool ${descriptor.name} @ $serverName" },
        parameters = ToolSchema.fromRendered(descriptor.inputSchema.toString()),
        client = client,
        maxOutputChars = maxOutputChars,
    )
}

/**
 * MCP 远端工具的一等包装。
 *
 * 风险定级：MEDIUM + openWorld（跨进程外部调用，保守处理）。
 * 输出钳制：超长结果截断并附说明，防撑爆上下文。
 */
class McpAgentTool(
    override val id: String,
    private val serverName: String,
    private val remoteName: String,
    override val description: String,
    override val parameters: ToolSchema,
    private val client: McpClient,
    private val maxOutputChars: Int,
) : AgentTool {

    override val name: String = id

    override val metadata: ToolMetadata = ToolMetadata(
        id = id,
        category = ToolCategory.NETWORK,
        risk = ToolRisk.MEDIUM,
        annotations = ToolAnnotations(openWorldHint = true),
        tags = setOf("mcp", "server:${McpToolNaming.slug(serverName)}"),
    )

    override suspend fun execute(request: ToolRequest): ToolResult {
        val args = try {
            Json.parseToJsonElement(request.arguments.ifBlank { "{}" }) as? JsonObject
                ?: return ToolResult.invalid("arguments", "参数必须是 JSON 对象")
        } catch (e: Exception) {
            return ToolResult.invalid("arguments", "参数不是合法 JSON: ${e.message}")
        }

        val callResult: McpCallToolResult = try {
            client.callTool(remoteName, args)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: com.androidguru.agent.mcp.protocol.McpRemoteException) {
            return ToolResult.failure(
                "MCP 远端错误: ${e.message}",
                ToolErrorCode.UNAVAILABLE,
                suggestion = "远端服务器返回错误，可稍后重试或改用其他工具",
            )
        } catch (e: Exception) {
            return ToolResult.failure(
                "MCP 调用失败: ${e.message ?: e.javaClass.simpleName}",
                ToolErrorCode.UNAVAILABLE,
                suggestion = "检查服务器连接状态",
            )
        }

        val text = callResult.renderText()
        return if (callResult.isError) {
            ToolResult.failure(text.take(maxOutputChars), ToolErrorCode.INTERNAL)
        } else {
            clampSuccess(text)
        }
    }

    private fun clampSuccess(text: String): ToolResult {
        if (text.length <= maxOutputChars) return ToolResult.success(text)
        return ToolResult.success(
            text.take(maxOutputChars) + "\n…（MCP 输出超长，已截断 ${text.length - maxOutputChars} 字符）",
        )
    }
}
