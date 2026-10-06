package com.androidguru.agent.mcp.transport

import com.androidguru.agent.mcp.protocol.JsonRpc
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * stdio 传输：以子进程 stdin/stdout 承载 NDJSON（换行分隔 JSON-RPC）帧。
 *
 * 生产细节（继承自 Android-Guru-Agent 踩坑经验，线程模型重做为 Deferred 直达）：
 * - **写侧锁**：write + flush 原子化，防并发写交错撕裂帧；
 * - **报文内换行替换为空格**：防内容换行导致的帧撕裂；
 * - **单读线程 pump**：按 `id` 精准投放 [CompletableDeferred]，替代旧版 50ms 轮询；
 * - **stderr 专职泄放线程**：防 64KB 管道写满导致子进程自锁死；
 * - **非 JSON 行跳过**：MCP 服务器常在 stdout 混发日志，宽容处理不抛异常。
 */
class StdioMcpTransport(
    private val command: List<String>,
    private val environment: Map<String, String> = emptyMap(),
    private val workingDir: String? = null,
    private val processLauncher: McpProcessLauncher = DefaultProcessLauncher(),
    private val requestTimeoutMs: Long = 180_000L, // npx 冷启动可能很慢
    private val onEvent: (StdioEvent) -> Unit = {},
    private val onNotification: (String, JsonObject) -> Unit = { _, _ -> },
) : McpTransport {

    sealed class StdioEvent {
        data object ProcessSpawned : StdioEvent()
        data object InitializeSent : StdioEvent()
        data class Failed(val cause: Throwable) : StdioEvent()
        data class StderrLine(val line: String) : StdioEvent()
    }

    override val name: String = "stdio:${command.firstOrNull() ?: "?"}"

    private val json = Json { ignoreUnknownKeys = true }
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonObject?>>()
    private val idCounter = java.util.concurrent.atomic.AtomicLong(0)
    private val closed = AtomicBoolean(false)

    private var process: McpProcess? = null
    private var writer: OutputStream? = null
    private var readerThread: Thread? = null
    private var stderrThread: Thread? = null
    private val writeLock = Any()

    override suspend fun start() {
        if (command.isEmpty()) throw IllegalArgumentException("stdio 传输需要非空 command")
        val proc = try {
            processLauncher.launch(command, environment, workingDir)
        } catch (e: Exception) {
            onEvent(StdioEvent.Failed(e))
            throw e
        }
        process = proc
        writer = proc.stdin
        onEvent(StdioEvent.ProcessSpawned)

        // 读线程：按 id 投放响应
        val reader = Thread({
            val br = BufferedReader(InputStreamReader(proc.stdout, StandardCharsets.UTF_8))
            try {
                while (!closed.get()) {
                    val line = br.readLine() ?: break
                    if (line.isBlank()) continue
                    dispatchLine(line.trim())
                }
            } catch (e: Exception) {
                if (!closed.get()) onEvent(StdioEvent.Failed(e))
            } finally {
                // 传输关闭：所有等待方以异常完成
                pending.forEach { (_, d) -> d.completeExceptionally(java.io.IOException("stdio closed")) }
                pending.clear()
            }
        }, "mcp-stdio-reader-${command.firstOrNull()}")
        reader.isDaemon = true
        reader.start()
        readerThread = reader

        // stderr 泄放线程：防管道写满死锁
        val errThread = Thread({
            val br = BufferedReader(InputStreamReader(proc.stderr, StandardCharsets.UTF_8))
            try {
                var reported = 0
                while (br.readLine()?.also { line ->
                    if (reported < 30) {
                        onEvent(StdioEvent.StderrLine(line))
                        reported++
                    }
                } != null) {
                    // drain
                }
            } catch (e: Exception) {
                // 进程退出时 readLine 抛异常属正常泄放结束
            }
        }, "mcp-stdio-stderr-${command.firstOrNull()}")
        errThread.isDaemon = true
        errThread.start()
        stderrThread = errThread
    }

    private fun dispatchLine(line: String) {
        // 非起始 `{` 的行视为服务器日志，跳过
        if (!line.startsWith("{")) return
        val obj = try {
            json.parseToJsonElement(line) as? JsonObject ?: return
        } catch (e: Exception) {
            return // 非 JSON 行（服务器日志）宽容跳过
        }
        val id = (obj["id"] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull()
        if (id != null) {
            val deferred = pending.remove(id)
            if (deferred != null) {
                deferred.complete(obj)
            }
            // 未匹配 id 的响应（超时后迟到）：静默丢弃
        } else {
            val method = (obj["method"] as? kotlinx.serialization.json.JsonPrimitive)?.content
            if (method != null) {
                onNotification(method, obj["params"] as? JsonObject ?: JsonObject(emptyMap()))
            }
        }
    }

    override suspend fun send(id: Long?, payload: JsonObject): JsonObject? {
        check(!closed.get()) { "传输已关闭" }
        val w = writer ?: throw IllegalStateException("传输尚未 start()")

        if (id == null) {
            // 通知：尽力发送
            withContext(Dispatchers.IO) {
                synchronized(writeLock) {
                    w.write(payload.toString().replace("\n", " ").toByteArray(StandardCharsets.UTF_8))
                    w.write('\n'.code)
                    w.flush()
                }
            }
            return null
        }

        val deferred = CompletableDeferred<JsonObject?>()
        pending[id] = deferred
        try {
            withContext(Dispatchers.IO) {
                synchronized(writeLock) {
                    w.write(payload.toString().replace("\n", " ").toByteArray(StandardCharsets.UTF_8))
                    w.write('\n'.code)
                    w.flush()
                }
            }
            return withTimeout(requestTimeoutMs) { deferred.await() }
                ?: throw java.io.IOException("连接关闭，响应未到达 (id=$id)")
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw java.util.concurrent.TimeoutException("stdio 请求超时 (${requestTimeoutMs}ms, id=$id)")
        } finally {
            pending.remove(id)
        }
    }

    fun nextId(): Long = idCounter.incrementAndGet()

    override fun isHealthy(): Boolean = !closed.get() && (process?.isAlive ?: false)

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            writer?.flush()
        } catch (e: Exception) {
        }
        try {
            writer?.close()
        } catch (e: Exception) {
        }
        process?.destroy()
        readerThread?.interrupt()
        stderrThread?.interrupt()
        pending.forEach { (_, d) -> d.completeExceptionally(java.io.IOException("transport closed")) }
        pending.clear()
    }
}
