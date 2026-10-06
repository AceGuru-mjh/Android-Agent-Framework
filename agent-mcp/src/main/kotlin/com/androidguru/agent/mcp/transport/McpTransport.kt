package com.androidguru.agent.mcp.transport

import kotlinx.serialization.json.JsonObject
import java.io.InputStream
import java.io.OutputStream

/**
 * MCP 传输抽象 —— 协议核心与物理承载解耦。
 *
 * 实现：
 * - [StdioMcpTransport]：子进程 stdin/stdout，NDJSON 帧；
 * - [HttpMcpTransport]：Streamable HTTP（POST + 会话头）。
 *
 * 宿主可自行实现新传输（WebSocket / 蓝牙 / 任意 RPC 桥）接入同一套客户端逻辑。
 */
interface McpTransport {

    /** 传输名（诊断 / 事件用）。 */
    val name: String

    /** 建立传输。 */
    suspend fun start()

    /**
     * 发送一条 JSON-RPC 消息。
     *
     * - [id] != null：挂起等待对应响应，返回**完整响应帧**（含 result 或 error 字段，
     *   由调用方经 [com.androidguru.agent.mcp.protocol.JsonRpc.resultOf] 解析）；
     *   超时或连接关闭时抛异常。
     * - [id] == null：通知，尽力发送，返回 null。
     */
    suspend fun send(id: Long?, payload: JsonObject): JsonObject?

    /** 传输是否健康。 */
    fun isHealthy(): Boolean

    /** 关闭传输并释放资源。幂等。 */
    suspend fun close()
}

/**
 * MCP 子进程抽象 —— 使 stdio 传输可脱离真实进程测试（沙箱 / 宿主自定义运行时）。
 */
interface McpProcess {
    val stdin: OutputStream
    val stdout: InputStream
    val stderr: InputStream
    val isAlive: Boolean
    fun destroy()
}

/**
 * 进程启动器 —— 宿主可替换为沙箱启动器 / 自定义运行时。
 */
fun interface McpProcessLauncher {
    fun launch(command: List<String>, environment: Map<String, String>, workingDir: String?): McpProcess
}

/** 默认启动器：JVM ProcessBuilder。 */
class DefaultProcessLauncher : McpProcessLauncher {

    override fun launch(command: List<String>, environment: Map<String, String>, workingDir: String?): McpProcess {
        val pb = ProcessBuilder(command)
        if (environment.isNotEmpty()) pb.environment().putAll(environment)
        workingDir?.let { pb.directory(java.io.File(it)) }
        pb.redirectErrorStream(false)
        val proc = pb.start()
        return object : McpProcess {
            override val stdin: OutputStream get() = proc.outputStream
            override val stdout: InputStream get() = proc.inputStream
            override val stderr: InputStream get() = proc.errorStream
            override val isAlive: Boolean get() = proc.isAlive
            override fun destroy() = proc.destroy()
        }
    }
}
