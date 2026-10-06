package com.androidguru.agent.mcp.client

import com.androidguru.agent.mcp.McpException
import com.androidguru.agent.mcp.bridge.McpToolBridge
import com.androidguru.agent.mcp.protocol.McpInitializeResult
import com.androidguru.agent.mcp.transport.DefaultProcessLauncher
import com.androidguru.agent.mcp.transport.HttpMcpTransport
import com.androidguru.agent.mcp.transport.McpProcessLauncher
import com.androidguru.agent.mcp.transport.McpTransport
import com.androidguru.agent.mcp.transport.StdioMcpTransport
import com.androidguru.agent.tools.ToolRegistry

/** 服务器配置（宿主持久化此结构即可恢复连接）。 */
data class McpServerConfig(
    /** 服务器名（工具命名空间）。 */
    val name: String,
    /** stdio 命令行（STDIO 模式必填）。 */
    val command: List<String> = emptyList(),
    /** Streamable HTTP 端点（HTTP 模式必填）。 */
    val url: String = "",
    val env: Map<String, String> = emptyMap(),
    val headers: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
    /** 连接超时（stdio 模式含冷启动）。 */
    val requestTimeoutMs: Long = 180_000L,
) {
    val isStdio: Boolean get() = command.isNotEmpty()
    val isHttp: Boolean get() = command.isEmpty() && url.isNotBlank()

    fun validate() {
        require(name.isNotBlank()) { "服务器名不能为空" }
        require(isStdio || isHttp) { "必须指定 command (stdio) 或 url (http)" }
        require(!(isStdio && isHttp)) { "command 与 url 互斥" }
    }
}

/** 连接状态。 */
data class McpConnection(
    val config: McpServerConfig,
    val transport: McpTransport,
    val client: McpClient,
    val initializeResult: McpInitializeResult,
    /** 经桥接注册进注册表的工具 id。 */
    val registeredToolIds: List<String>,
)

/** 连接事件监听。 */
interface McpSessionListener {
    fun onServerConnected(connection: McpConnection)
    fun onServerDisconnected(serverName: String, cause: Throwable?)
}

/**
 * MCP 连接管理器 —— 多服务器生命周期 + 桥接一体化。
 *
 * ```kotlin
 * val manager = McpManager(toolRegistry)
 * manager.addSessionListener(ui)
 * manager.addServer(McpServerConfig(name = "files", command = listOf("mcp-server-filesystem", "/data")))
 * manager.addServer(McpServerConfig(name = "remote", url = "https://mcp.example.com/mcp"))
 * ```
 */
class McpManager(
    private val toolRegistry: ToolRegistry,
    private val processLauncher: McpProcessLauncher = DefaultProcessLauncher(),
    private val bridge: McpToolBridge = McpToolBridge(toolRegistry),
) {

    private val lock = Any()
    private val connections = LinkedHashMap<String, McpConnection>()
    private val listeners = mutableListOf<McpSessionListener>()

    fun addSessionListener(listener: McpSessionListener) {
        synchronized(lock) { listeners += listener }
    }

    fun removeSessionListener(listener: McpSessionListener) {
        synchronized(lock) { listeners -= listener }
    }

    /** 当前全部连接（快照）。 */
    fun snapshot(): List<McpConnection> = synchronized(lock) { connections.values.toList() }

    fun getConnection(serverName: String): McpConnection? = synchronized(lock) { connections[serverName] }

    /**
     * 连接一个 MCP 服务器：建立传输 → 握手 → 桥接注册工具。
     * 服务器名已存在时先断开旧连接（重连语义）。
     */
    suspend fun addServer(config: McpServerConfig): McpConnection {
        config.validate()
        getConnection(config.name)?.let { disconnect(config.name) }

        val transport: McpTransport = if (config.isStdio) {
            StdioMcpTransport(
                command = config.command,
                environment = config.env,
                processLauncher = processLauncher,
                requestTimeoutMs = config.requestTimeoutMs,
            )
        } else {
            HttpMcpTransport(
                url = config.url,
                headers = config.headers,
                requestTimeoutMs = config.requestTimeoutMs,
            )
        }

        val client = McpClient(transport)
        try {
            transport.start()
            val init = client.initialize()
            val toolIds = bridge.registerServer(config.name, client)
            val connection = McpConnection(config, transport, client, init, toolIds)
            synchronized(lock) { connections[config.name] = connection }
            listenersSnapshot().forEach { runCatching { it.onServerConnected(connection) } }
            return connection
        } catch (e: kotlinx.coroutines.CancellationException) {
            runCatching { transport.close() }
            throw e
        } catch (e: Exception) {
            runCatching { transport.close() }
            throw McpException("连接 MCP 服务器「${config.name}」失败: ${e.message}", e)
        }
    }

    /** 断开并注销一个服务器的全部工具。 */
    suspend fun disconnect(serverName: String, cause: Throwable? = null): Boolean {
        val connection = synchronized(lock) { connections.remove(serverName) } ?: return false
        runCatching { connection.transport.close() }
        bridge.unregisterServer(connection.registeredToolIds)
        listenersSnapshot().forEach { runCatching { it.onServerDisconnected(serverName, cause) } }
        return true
    }

    /** 断开全部（应用退出时调用）。 */
    suspend fun disconnectAll() {
        val names = synchronized(lock) { connections.keys.toList() }
        names.forEach { disconnect(it) }
    }

    private fun listenersSnapshot(): List<McpSessionListener> = synchronized(lock) { listeners.toList() }
}
