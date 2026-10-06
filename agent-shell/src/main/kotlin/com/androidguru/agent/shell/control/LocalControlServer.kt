package com.androidguru.agent.shell.control

import com.androidguru.agent.shell.audit.AuditLog
import com.androidguru.agent.shell.policy.ApprovalGate
import com.androidguru.agent.shell.policy.CommandPolicy
import com.androidguru.agent.shell.session.CommandRunner
import com.androidguru.agent.shell.session.ShellSession
import com.androidguru.agent.shell.session.ShellSessionManager
import com.androidguru.agent.shell.terminal.AnsiStripper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/**
 * 本地控制 API —— 供 PC 端或外部编排器复用同一会话的 WebSocket 服务。
 *
 * 安全模型（保留 yl-ai 的全部决策，一个都不放松）：
 * - **仅监听 127.0.0.1 + 随机端口**：局域网内其它设备无法访问；
 * - **令牌鉴权**：32 字节 SecureRandom → base64url，`?token=` 或 `Authorization: Bearer`，
 *   **定长 + 恒时比较**（防时序侧信道）；
 * - **默认关闭**（[enabled] 偏好），开启需宿主显式操作；
 * - **命令分级照走 [CommandPolicy]**：BLOCKED 直接拒，CONFIRM 弹审批 ——
 *   外部客户端不能绕过用户与审计；
 * - 每条执行命令都进 [audit]。
 *
 * 协议：请求 `{"id", "method", "params"}` → 响应 `{"id", "ok", "result"|"error"}`。
 */
class LocalControlServer(
    private val sessions: ShellSessionManager,
    private val policy: CommandPolicy,
    private val approval: ApprovalGate,
    private val audit: AuditLog,
    private val environment: com.androidguru.agent.shell.runtime.ShellEnvironment,
    private val tokenStore: File,
    private val version: String = "1.0",
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val clients = ConcurrentHashMap<String, WsConnection>()

    /** 每会话一个 CommandRunner（串行执行语义随会话走）。 */
    private val runners = ConcurrentHashMap<String, CommandRunner>()

    @Volatile
    var enabled: Boolean = false
        private set

    @Volatile
    var port: Int = 0
        private set

    // ---------------- 令牌管理 ----------------

    /** 读取或首次生成 API 令牌。 */
    fun token(): String {
        if (tokenStore.isFile) {
            val saved = runCatching { tokenStore.readText().trim() }.getOrNull()
            if (!saved.isNullOrBlank()) return saved
        }
        val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val t = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        runCatching {
            tokenStore.parentFile?.mkdirs()
            tokenStore.writeText(t)
        }
        return t
    }

    /** 重新生成令牌（旧令牌立即失效）。 */
    fun regenerateToken(): String {
        runCatching { tokenStore.delete() }
        return token()
    }

    // ---------------- 生命周期 ----------------

    /** 启动服务（仅回环 + 随机端口）。 */
    fun start(): Boolean {
        if (enabled) return true
        return runCatching {
            val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
            serverSocket = socket
            port = socket.localPort
            enabled = true
            audit.recordSetting("api.start", "port=$port")

            acceptThread = Thread({
                while (enabled) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    scope.launch { handleClient(client) }
                }
            }, "agsh-control-accept").apply {
                isDaemon = true
                start()
            }
            true
        }.getOrDefault(false)
    }

    fun stop() {
        if (!enabled) return
        enabled = false
        runCatching { serverSocket?.close() }
        clients.values.forEach { runCatching { it.close() } }
        clients.clear()
        serverSocket = null
        audit.recordSetting("api.stop", "port=$port")
        scope.cancel()
    }

    private suspend fun handleClient(socket: Socket) {
        socket.use { s ->
            s.soTimeout = 0
            val head = readHead(s.getInputStream()) ?: return
            val rawHead = String(head, Charsets.US_ASCII)
            val firstLine = rawHead.lineSequence().firstOrNull() ?: return
            val tokenGiven = extractToken(firstLine, rawHead)

            if (!constantTimeEquals(tokenGiven, token())) {
                s.getOutputStream().write(
                    "HTTP/1.1 401 Unauthorized\r\nContent-Type: text/plain; charset=utf-8\r\n\r\n".toByteArray(),
                )
                audit.recordSetting("api.auth_failed", firstLine.take(120))
                return
            }

            val ws = WsConnection(s.getInputStream(), s.getOutputStream())
            // 握手前已 peek 过部分字节（head 里可能含握手之后的数据？不会 —— 升级前无数据）
            if (!ws.handshakeWith(rawHead)) return
            val clientId = "c${System.nanoTime()}"
            clients[clientId] = ws
            audit.recordSetting("api.client_connected", clientId)

            try {
                while (true) {
                    val text = ws.readText() ?: break
                    val response = dispatch(text)
                    ws.sendText(response)
                }
            } finally {
                clients.remove(clientId)
                audit.recordSetting("api.client_disconnected", clientId)
            }
        }
    }

    private fun readHead(input: java.io.InputStream): ByteArray? {
        val buf = java.io.ByteArrayOutputStream()
        var last4 = ""
        while (true) {
            val c = input.read()
            if (c < 0) return null
            buf.write(c)
            last4 = (last4 + c.toChar()).takeLast(4)
            if (last4 == "\r\n\r\n") break
            if (buf.size() > 16 * 1024) return null
        }
        return buf.toByteArray()
    }

    private fun extractToken(firstLine: String, rawHead: String): String {
        // ?token= 查询参数
        val path = firstLine.split(" ").getOrNull(1) ?: ""
        val query = path.substringAfter('?', "")
        Regex("""(?:^|&)token=([^&]+)""").find(query)?.let { return it.groupValues[1] }
        // Authorization: Bearer
        rawHead.lineSequence()
            .firstOrNull { it.startsWith("Authorization:", ignoreCase = true) }
            ?.let { return it.substringAfter(':').trim().removePrefix("Bearer ").trim() }
        return ""
    }

    /** 恒时比较（先对齐长度再逐字符 XOR，防时序侧信道）。 */
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
        return diff == 0
    }

    // ---------------- 协议分发 ----------------

    /** 处理一条 JSON 请求文本（挂起语义；ApiSelfTest 用 runBlocking 直调）。 */
    suspend fun dispatch(requestJson: String): String {
        val id: String
        val method: String
        val params: JsonObject
        try {
            val obj = json.parseToJsonElement(requestJson).jsonObject
            id = obj["id"]?.jsonPrimitive?.content ?: ""
            method = obj["method"]?.jsonPrimitive?.content ?: ""
            params = obj["params"]?.jsonObject ?: JsonObject(emptyMap())
        } catch (e: Exception) {
            return errorResponse("", "请求不是合法的 JSON 对象: ${e.message}")
        }
        return try {
            val result = handle(method, params)
            buildJsonObject {
                put("id", id)
                put("ok", true)
                put("result", result)
            }.toString()
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            buildJsonObject {
                put("id", id)
                put("ok", false)
                put("error", e.message ?: e.javaClass.simpleName)
            }.toString()
        }
    }

    private fun errorResponse(id: String, message: String): String = buildJsonObject {
        put("id", id)
        put("ok", false)
        put("error", message)
    }.toString()

    private suspend fun handle(method: String, p: JsonObject): JsonObject = when (method) {
        "server.info" -> buildJsonObject {
            put("port", port)
            put("version", version)
            put("sessionCount", sessions.count)
        }

        "session.list" -> buildJsonObject {
            putJsonArray("sessions") {
                sessions.sessionList.forEach { s ->
                    add(
                        buildJsonObject {
                            put("id", s.id)
                            put("title", s.title)
                            put("state", s.state.name)
                            put("busy", s.busy)
                            put("cwd", s.cwd)
                        },
                    )
                }
            }
        }

        "session.open" -> {
            val title = p.str("title")
            val session = sessions.create(title = title)
            buildJsonObject {
                put("sessionId", session.id)
                put("cwd", session.currentDirectory)
            }
        }

        "session.exec" -> {
            val session = p.str("sessionId")?.let { sessions.get(it) } ?: sessions.active
                ?: throw IllegalStateException("没有可用会话")
            val command = p.str("command") ?: throw IllegalArgumentException("缺少 command 参数")
            val timeoutMs = (p.long("timeoutMs") ?: 30_000L).coerceIn(500, 300_000)

            val decision = policy.decide(command)
            if (decision.level == CommandPolicy.Level.BLOCKED) {
                audit.recordBlocked(AuditLog.Source.API, command, decision.reason)
                throw IllegalStateException("命令被安全策略硬拦截：${decision.reason}")
            }
            var approvalOutcome = AuditLog.ApprovalOutcome.NOT_REQUIRED
            if (decision.level == CommandPolicy.Level.CONFIRM) {
                val verdict = approval.request(
                    ApprovalGate.Request(
                        id = ApprovalGate.newId(),
                        toolName = "api.session.exec",
                        title = "外部客户端请求执行命令",
                        detail = command,
                        impact = decision.targets.takeIf { it.isNotEmpty() }
                            ?.let { "涉及路径：${it.joinToString("、")}" } ?: "该命令可能修改系统状态",
                        level = decision.level,
                        reason = decision.reason,
                    ),
                )
                approvalOutcome = when (verdict) {
                    ApprovalGate.Verdict.ALLOW_ONCE -> AuditLog.ApprovalOutcome.ALLOWED_ONCE
                    ApprovalGate.Verdict.ALLOW_ALWAYS -> AuditLog.ApprovalOutcome.ALLOWED_ALWAYS
                    ApprovalGate.Verdict.DENY -> AuditLog.ApprovalOutcome.DENIED
                }
                if (verdict == ApprovalGate.Verdict.DENY) {
                    audit.recordCommand(AuditLog.Source.API, command, level = decision.level.name, approval = "DENIED", sessionId = session.id)
                    throw IllegalStateException("用户拒绝执行这条命令")
                }
            }

            val runner = runners.computeIfAbsent(session.id) { CommandRunner(session) }
            val result = runner.run(command, timeoutMs = timeoutMs)
            audit.recordCommand(
                AuditLog.Source.API, command,
                exitCode = result.exitCode,
                durationMs = result.durationMs,
                level = decision.level.name,
                approval = approvalOutcome.name,
                sessionId = session.id,
            )
            broadcastCommandFinished(session.id, command, result.exitCode, result.durationMs, result.timedOut)
            buildJsonObject {
                put("exitCode", result.exitCode)
                put("durationMs", result.durationMs)
                put("timedOut", result.timedOut)
                put("truncated", result.truncated)
                put("stdout", result.stdout)
                put("cwd", session.currentDirectory)
            }
        }

        "session.write" -> {
            val session = p.str("sessionId")?.let { sessions.get(it) } ?: sessions.active
                ?: throw IllegalStateException("没有可用会话")
            session.write(p.str("input") ?: "")
            buildJsonObject { put("ok", true) }
        }

        "session.interrupt" -> {
            val session = p.str("sessionId")?.let { sessions.get(it) } ?: sessions.active
                ?: throw IllegalStateException("没有可用会话")
            session.interrupt()
            buildJsonObject { put("ok", true) }
        }

        "session.resize" -> {
            val session = p.str("sessionId")?.let { sessions.get(it) } ?: sessions.active
                ?: throw IllegalStateException("没有可用会话")
            session.resize((p.long("rows") ?: 24L).toInt(), (p.long("cols") ?: 80L).toInt())
            buildJsonObject { put("ok", true) }
        }

        "session.tail" -> {
            val session = p.str("sessionId")?.let { sessions.get(it) } ?: sessions.active
                ?: throw IllegalStateException("没有可用会话")
            val tail = AnsiStripper.truncate(session.tailText(64 * 1024), headChars = 2000, tailChars = 6000).text
            buildJsonObject {
                put("sessionId", session.id)
                put("tail", tail)
            }
        }

        "fs.read" -> {
            val path = resolvePath(p.str("path") ?: throw IllegalArgumentException("缺少 path 参数"))
            val maxBytes = (p.long("maxBytes") ?: 65536L).coerceIn(256, 1024L * 1024)
            val f = File(path)
            if (!f.isFile) throw IllegalStateException("文件不存在：$path")
            val bytes = f.length().coerceAtMost(maxBytes)
            val content = f.inputStream().use { input ->
                val buf = ByteArray(bytes.toInt())
                var read = 0
                while (read < buf.size) {
                    val n = input.read(buf, read, buf.size - read)
                    if (n < 0) break
                    read += n
                }
                String(buf, 0, read, Charsets.UTF_8)
            }
            buildJsonObject {
                put("path", f.absolutePath)
                put("size", f.length())
                put("content", content)
            }
        }

        "fs.write" -> {
            val path = resolvePath(p.str("path") ?: throw IllegalArgumentException("缺少 path 参数"))
            val content = p.str("content") ?: ""
            val append = p.bool("append") ?: false
            val verdict = approval.request(
                ApprovalGate.Request(
                    id = ApprovalGate.newId(),
                    toolName = "api.fs.write",
                    title = "外部客户端请求写入文件",
                    detail = path,
                    impact = "${content.length} 字符，${if (append) "追加" else "覆盖"}",
                    level = CommandPolicy.Level.CONFIRM,
                    canRollback = false,
                ),
            )
            if (verdict == ApprovalGate.Verdict.DENY) {
                audit.recordCommand(AuditLog.Source.API, "fs.write $path", approval = "DENIED")
                throw IllegalStateException("用户拒绝写入文件")
            }
            val f = File(path)
            f.parentFile?.mkdirs()
            if (append) f.appendText(content) else f.writeText(content)
            audit.recordFileWrite(f.absolutePath, content.toByteArray().size.toLong(), approved = true)
            buildJsonObject {
                put("path", f.absolutePath)
                put("bytes", content.toByteArray().size)
            }
        }

        "fs.list" -> {
            val path = resolvePath(p.str("path") ?: sessions.active?.currentDirectory ?: environment.home.absolutePath)
            val dir = File(path)
            if (!dir.isDirectory) throw IllegalStateException("目录不存在：$path")
            val entries = dir.listFiles()?.take(500) ?: emptyList()
            buildJsonObject {
                putJsonArray("entries") {
                    entries.forEach { f ->
                        add(
                            buildJsonObject {
                                put("name", f.name)
                                put("dir", f.isDirectory)
                                put("size", if (f.isFile) f.length() else 0L)
                            },
                        )
                    }
                }
            }
        }

        "approval.pending" -> buildJsonObject {
            val pending = approval.currentRequest
            put("pending", pending != null)
            pending?.let {
                put("id", it.id)
                put("tool", it.toolName)
                put("title", it.title)
                put("detail", it.detail)
                put("level", it.level.name)
            }
        }

        "approval.respond" -> {
            val id = p.str("id") ?: throw IllegalArgumentException("缺少 id 参数")
            val verdictStr = p.str("verdict") ?: "deny"
            val verdict = when (verdictStr) {
                "allow_once" -> ApprovalGate.Verdict.ALLOW_ONCE
                "allow_always" -> ApprovalGate.Verdict.ALLOW_ALWAYS
                else -> ApprovalGate.Verdict.DENY
            }
            buildJsonObject {
                put("accepted", approval.respond(id, verdict))
            }
        }

        else -> throw IllegalArgumentException("未知方法：$method")
    }

    private fun resolvePath(path: String): String {
        val expanded = when {
            path == "~" -> environment.home.absolutePath
            path.startsWith("~/") -> File(environment.home, path.removePrefix("~/")).path
            else -> path
        }
        val f = File(expanded)
        return if (f.isAbsolute) f.path else File(environment.home, expanded).path
    }

    private fun broadcastCommandFinished(
        sessionId: String,
        command: String,
        exitCode: Int,
        durationMs: Long,
        timedOut: Boolean,
    ) {
        val message = buildJsonObject {
            put("event", "command.finished")
            put("sessionId", sessionId)
            put("command", command.take(500))
            put("exitCode", exitCode)
            put("durationMs", durationMs)
            put("timedOut", timedOut)
        }.toString()
        clients.values.forEach { c -> c.sendText(message) }
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.let { runCatching { it.jsonPrimitive.content }.getOrNull() }

    private fun JsonObject.long(key: String): Long? =
        this[key]?.let { runCatching { it.jsonPrimitive.content.toLong() }.getOrNull() }

    private fun JsonObject.bool(key: String): Boolean? =
        this[key]?.let { runCatching { it.jsonPrimitive.content.toBooleanStrict() }.getOrNull() }
}
