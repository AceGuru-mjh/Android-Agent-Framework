package com.androidguru.agent.shell.audit

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 审计日志 —— 命令 / 审批 / 任务 / 文件写入 / 设置的全量记账。
 *
 * 设计（保留 yl-ai 的口径）：
 * - 记录**发生了什么**（命令、退出码、来源、审批结果），不记录终端原始输入
 *   与截图 —— 审计是安全记账，不是监控；
 * - 外部 API（控制服务）触发的执行同样记账 —— **外部客户端不能绕过用户与审计**；
 * - 存储可插拔：[AuditLog] 接口 + 默认 [FileAuditLog]（JSONL，追加写）+
 *   [InMemoryAuditLog]（测试用）。宿主可换 SQLite / 远端。
 */
interface AuditLog {

    /** 记录一条事件。实现必须非阻塞（异步落盘）且绝不抛异常。 */
    fun record(type: String, source: Source, payload: Map<String, String?>)

    /** 按时间倒序取最近事件。 */
    fun recent(limit: Int = 200, type: String? = null): List<Event>

    /** 导出 JSON（pretty）。 */
    fun exportJson(limit: Int = 1000): String

    fun count(): Long

    fun clear()

    fun purgeOlderThan(days: Int = DEFAULT_RETENTION_DAYS): Int

    /** 事件来源。 */
    enum class Source { USER, AI, API, SYSTEM }

    /** 审批结论（payload 里的 approval 字段）。 */
    enum class ApprovalOutcome { NOT_REQUIRED, ALLOWED_ONCE, ALLOWED_ALWAYS, DENIED, BLOCKED }

    @Serializable
    data class Event(
        val ts: Long,
        val seq: Long,
        val type: String,
        val source: String,
        val payload: Map<String, String>,
    )

    companion object {
        const val DEFAULT_RETENTION_DAYS = 30

        // 事件类型常量
        const val TYPE_COMMAND = "command"
        const val TYPE_BLOCKED = "blocked"
        const val TYPE_AI_TASK = "ai_task"
        const val TYPE_FS_WRITE = "fs_write"
        const val TYPE_SETTING = "setting"
        const val TYPE_TOOL = "tool"

        // 便捷方法（实现侧可 override 优化，这里用扩展保持接口窄面）
    }

    /** 记录命令执行。 */
    fun recordCommand(
        source: Source,
        command: String,
        exitCode: Int? = null,
        durationMs: Long? = null,
        level: String? = null,
        approval: String? = null,
        sessionId: String? = null,
    ) = record(
        TYPE_COMMAND, source,
        linkedMapOf(
            "command" to command.take(4000),
            "exitCode" to exitCode?.toString(),
            "durationMs" to durationMs?.toString(),
            "level" to level,
            "approval" to approval,
            "sessionId" to sessionId,
        ).filterValues { it != null }.mapValues { it.value!! },
    )

    /** 记录硬拦截。 */
    fun recordBlocked(source: Source, command: String, reason: String) = record(
        TYPE_BLOCKED, source,
        linkedMapOf("command" to command.take(4000), "reason" to reason)
            .filterValues { it != null }.mapValues { it.value!! },
    )

    /** 记录 AI 任务结论。 */
    fun recordAiTask(instruction: String, outcome: String, steps: Int, tokens: Long) = record(
        TYPE_AI_TASK, Source.AI,
        linkedMapOf(
            "instruction" to instruction.take(2000),
            "outcome" to outcome.take(2000),
            "steps" to steps.toString(),
            "tokens" to tokens.toString(),
        ),
    )

    /** 记录文件写入。 */
    fun recordFileWrite(path: String, bytes: Long, approved: Boolean) = record(
        TYPE_FS_WRITE, Source.AI,
        linkedMapOf("path" to path, "bytes" to bytes.toString(), "approved" to approved.toString()),
    )

    /** 记录设置 / 生命周期事件（容器安装、Termux 启动等）。 */
    fun recordSetting(what: String, detail: String) = record(
        TYPE_SETTING, Source.SYSTEM,
        linkedMapOf("what" to what, "detail" to detail.take(2000)),
    )
}

/**
 * JSONL 文件审计日志（默认实现）。
 *
 * 追加写 + 按天滚动（audit-YYYYMMDD.jsonl）；读取时倒序扫最近文件。
 * 写入通过单线程 executor 异步化，绝不阻塞调用方、绝不抛异常。
 */
class FileAuditLog(private val dir: File) : AuditLog {

    private val json = Json { ignoreUnknownKeys = true }
    private val seq = AtomicLong(0)
    private val executor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "agsh-audit").apply { isDaemon = true }
    }
    private val memory = ArrayDeque<AuditLog.Event>()

    init {
        dir.mkdirs()
    }

    override fun record(type: String, source: AuditLog.Source, payload: Map<String, String?>) {
        val event = AuditLog.Event(
            ts = System.currentTimeMillis(),
            seq = seq.incrementAndGet(),
            type = type,
            source = source.name,
            payload = payload.filterValues { it != null }.mapValues { it.value!! },
        )
        synchronized(memory) {
            memory.addLast(event)
            if (memory.size > MAX_MEMORY) memory.removeFirst()
        }
        executor.execute {
            runCatching {
                dir.mkdirs()
                val day = java.time.Instant.ofEpochMilli(event.ts)
                    .atZone(java.time.ZoneId.systemDefault())
                    .toLocalDate()
                val file = File(dir, "audit-$day.jsonl")
                file.appendText(json.encodeToString(event) + "\n")
            }
        }
    }

    override fun recent(limit: Int, type: String?): List<AuditLog.Event> {
        // 内存 ring 优先（进程内刚写入的），不足时从文件补
        val memSnapshot = synchronized(memory) { memory.toList() }
        val filtered = if (type == null) memSnapshot else memSnapshot.filter { it.type == type }
        val result = filtered.sortedByDescending { it.seq }.take(limit)
        if (result.size >= limit) return result
        return result + recentFromFiles(limit - result.size, type, excludeSeqs = memSnapshot.map { it.seq }.toSet())
    }

    private fun recentFromFiles(limit: Int, type: String?, excludeSeqs: Set<Long>): List<AuditLog.Event> {
        if (limit <= 0) return emptyList()
        val files = dir.listFiles { f -> f.name.startsWith("audit-") && f.name.endsWith(".jsonl") }
            ?.sortedByDescending { it.name } ?: return emptyList()
        // 修复 issue #13：旧实现从文件头顺序读、凑满 limit 即 break ——
        // 取到的是「最新一天里最老的事件」，当日事件超过 limit 时最新命令永远查不到。
        // 现在用有界环形缓冲保留每个文件的**最后** limit 条合格事件，再跨文件归并
        val events = mutableListOf<AuditLog.Event>()
        val candidates = ArrayDeque<AuditLog.Event>()
        for (file in files) {
            if (events.size >= limit) break
            candidates.clear()
            runCatching {
                file.useLines { lines ->
                    for (line in lines) {
                        runCatching { json.decodeFromString<AuditLog.Event>(line) }
                            .getOrNull()
                            ?.takeIf { (type == null || it.type == type) && it.seq !in excludeSeqs }
                            ?.let {
                                candidates.addLast(it)
                                if (candidates.size > limit) candidates.removeFirst()
                            }
                    }
                }
            }
            // 该文件最新的在后面（文件按时间追加），倒序并入归并集
            for (e in candidates.asReversed()) {
                events.add(e)
                if (events.size >= limit) break
            }
        }
        return events.sortedByDescending { it.seq }
    }

    override fun exportJson(limit: Int): String {
        val events = recent(limit)
        return buildString {
            append("{\n")
            append("  \"app\": \"android-agent-framework\",\n")
            append("  \"exportedAt\": ${System.currentTimeMillis()},\n")
            append("  \"count\": ${events.size},\n")
            append("  \"events\": [\n")
            events.forEachIndexed { i, e ->
                append("    ")
                append(json.encodeToString(e))
                if (i < events.size - 1) append(",")
                append("\n")
            }
            append("  ]\n")
            append("}")
        }
    }

    override fun count(): Long {
        val files = dir.listFiles { f -> f.name.startsWith("audit-") } ?: return synchronized(memory) { memory.size.toLong() }
        return synchronized(memory) { memory.size.toLong() } +
            files.filter { it.isFile }.sumOf { f -> f.useLines { lines -> lines.count().toLong() } }
    }

    override fun clear() {
        synchronized(memory) { memory.clear() }
        dir.listFiles { f -> f.name.startsWith("audit-") }?.forEach { runCatching { it.delete() } }
    }

    override fun purgeOlderThan(days: Int): Int {
        val cutoff = System.currentTimeMillis() - days.toLong() * 24 * 60 * 60 * 1000
        var purged = 0
        dir.listFiles { f -> f.name.startsWith("audit-") }?.forEach { file ->
            val dayStr = file.name.removePrefix("audit-").removeSuffix(".jsonl")
            val day = runCatching { java.time.LocalDate.parse(dayStr) }.getOrNull() ?: return@forEach
            val dayMs = day.atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
            if (dayMs < cutoff - 24 * 60 * 60 * 1000) {
                if (runCatching { file.delete() }.getOrDefault(false)) purged++
            }
        }
        return purged
    }

    companion object {
        private const val MAX_MEMORY = 500
    }
}

/** 内存实现（测试 / 短生命周期宿主）。 */
class InMemoryAuditLog : AuditLog {

    private val events = ArrayDeque<AuditLog.Event>()
    private val seq = AtomicLong(0)

    override fun record(type: String, source: AuditLog.Source, payload: Map<String, String?>) {
        synchronized(events) {
            events.addLast(
                AuditLog.Event(
                    ts = System.currentTimeMillis(),
                    seq = seq.incrementAndGet(),
                    type = type,
                    source = source.name,
                    payload = payload.filterValues { it != null }.mapValues { it.value!! },
                ),
            )
            if (events.size > 1000) events.removeFirst()
        }
    }

    override fun recent(limit: Int, type: String?): List<AuditLog.Event> = synchronized(events) {
        events.filter { type == null || it.type == type }.sortedByDescending { it.seq }.take(limit)
    }

    override fun exportJson(limit: Int): String {
        val events = recent(limit)
        return """{"count":${events.size},"events":[${events.joinToString(",") {
            """{"ts":${it.ts},"seq":${it.seq},"type":"${it.type}","source":"${it.source}"}"""
        }}]}"""
    }

    override fun count(): Long = synchronized(events) { events.size.toLong() }

    override fun clear() = synchronized(events) { events.clear() }

    override fun purgeOlderThan(days: Int): Int {
        val cutoff = System.currentTimeMillis() - days.toLong() * 24 * 60 * 60 * 1000
        return synchronized(events) {
            val it0 = events.iterator()
            var purged = 0
            while (it0.hasNext()) {
                if (it0.next().ts < cutoff) { it0.remove(); purged++ }
            }
            purged
        }
    }
}
