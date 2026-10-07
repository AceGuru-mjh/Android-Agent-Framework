package com.androidguru.agent.memory

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.concurrent.ConcurrentHashMap

/**
 * 长期记忆存储 —— 跨会话读写记忆单元的唯一入口。
 *
 * 与 [com.androidguru.agent.core.session.ConversationMemory]（工作记忆，会话内消息流）
 * 的分工：本存储面向**跨会话存续的知识**，按 id 寻址、按评分召回，不是对话回放。
 */
interface MemoryStore {

    /** 追加一条记忆（[MemoryRecord.id] 为空时由存储分配）。返回带 id 的最终记录。 */
    fun append(record: MemoryRecord): MemoryRecord

    /** 更新既有记忆（访问回写 / 内容修正）；id 不存在返回 false。 */
    fun update(record: MemoryRecord): Boolean

    /** 按 id 删除。 */
    fun remove(id: String): Boolean

    /** 全量快照（召回评分的输入）。 */
    fun all(): List<MemoryRecord>

    fun clear()
}

/** 内存实现（默认；测试 / 一次性任务使用）。线程安全。 */
class InMemoryMemoryStore : MemoryStore {

    private val store = ConcurrentHashMap<String, MemoryRecord>()

    override fun append(record: MemoryRecord): MemoryRecord {
        val final = if (record.id.isBlank()) record.copy(id = MemoryIds.next()) else record
        store[final.id] = final
        return final
    }

    override fun update(record: MemoryRecord): Boolean {
        // 未知 id 不落库（update 不是 upsert —— 防止误插脏记录）
        if (!store.containsKey(record.id)) return false
        store[record.id] = record
        return true
    }

    override fun remove(id: String): Boolean = store.remove(id) != null

    override fun all(): List<MemoryRecord> = store.values.sortedBy { it.createdAtMs }

    override fun clear() = store.clear()
}

/**
 * 文件实现：JSONL 追加式持久化（对齐 [com.androidguru.agent.core.session.FileConversationMemory] 的纪律）。
 *
 * - **追加即持久**：append 一行 JSON 立即 flush —— 进程崩溃后已写入记忆不丢；
 * - **原子替换**：update / remove / 容量淘汰走「临时文件 + 原子 move」，不留半写状态；
 * - **容错加载**：损坏行（半写 / 手工编辑错误）跳过并计入 [skippedCorruptLines]，绝不报废整个记忆库；
 * - **容量上限**：[maxRecords] 满时按（重要度升序 → 最久未访问优先）淘汰，存储有界；
 * - **id 续编**：加载后从既有最大 id 续编，新记忆不与旧 id 冲突。
 *
 * 典型路径：`{workspace}/memory.jsonl`（由 [MemorySystem.create] 自动接线）。
 */
class FileMemoryStore(
    private val path: Path,
    /** 记忆条数上限（防无限膨胀；淘汰纪律见类注释）。 */
    private val maxRecords: Int = 1000,
) : MemoryStore {

    private val lock = Any()

    private val records = mutableListOf<MemoryRecord>()

    /** 最近一次 [load] 跳过的损坏行数（观测用）。 */
    var skippedCorruptLines: Int = 0
        private set

    init {
        load()
    }

    // ------------------------------------------------------------------
    // API
    // ------------------------------------------------------------------

    override fun append(record: MemoryRecord): MemoryRecord = synchronized(lock) {
        val dir = path.toAbsolutePath().parent
        if (dir != null) Files.createDirectories(dir)
        val final = if (record.id.isBlank()) record.copy(id = nextId()) else record
        Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND).use { w ->
            w.write(MemoryCodec.encode(final).toString())
            w.newLine()
            w.flush()
        }
        records.add(final)
        evictOverflowLocked()
        final
    }

    override fun update(record: MemoryRecord): Boolean = synchronized(lock) {
        if (records.none { it.id == record.id }) return false
        val idx = records.indexOfFirst { it.id == record.id }
        records[idx] = record
        rewriteLocked(records)
        true
    }

    override fun remove(id: String): Boolean = synchronized(lock) {
        val idx = records.indexOfFirst { it.id == id }
        if (idx < 0) return false
        records.removeAt(idx)
        rewriteLocked(records)
        true
    }

    override fun all(): List<MemoryRecord> = synchronized(lock) { records.toList() }

    override fun clear() = synchronized(lock) {
        records.clear()
        val dir = path.toAbsolutePath().parent
        if (dir != null) Files.createDirectories(dir)
        Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { it.flush() }
        Unit
    }

    /** 从文件重建内存态；返回加载条数。损坏行跳过并计数。 */
    fun load(): Int {
        val loaded = mutableListOf<MemoryRecord>()
        var corrupt = 0
        if (Files.exists(path)) {
            Files.newBufferedReader(path, StandardCharsets.UTF_8).use { r ->
                var line = r.readLine()
                while (line != null) {
                    if (line.isNotBlank()) {
                        val decoded = MemoryCodec.decodeLine(line)
                        if (decoded != null) {
                            loaded += decoded
                        } else {
                            corrupt++
                        }
                    }
                    line = r.readLine()
                }
            }
        }
        synchronized(lock) {
            records.clear()
            records.addAll(loaded)
        }
        skippedCorruptLines = corrupt
        return loaded.size
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 已分配的最大 id 序号（加载时锚定既有最大号，防新旧 id 冲突）。 */
    @Volatile
    private var lastIdSeq: Long = 0

    init {
        lastIdSeq = MemoryIds.maxKnown(records)
    }

    /** id 续编：既有最大号 +1（显式 id 写入抬高水位线）。 */
    private fun nextId(): String {
        val next = maxOf(lastIdSeq, MemoryIds.maxKnown(records)) + 1
        lastIdSeq = next
        return "m$next"
    }

    /** 容量淘汰：超过 [maxRecords] 时牺牲（重要度最低 → 最久未访问）的记录并原子重写。 */
    private fun evictOverflowLocked() {
        if (records.size <= maxRecords) return
        val victims = records
            .sortedWith(compareBy({ it.importance }, { it.lastAccessedAtMs }))
            .take(records.size - maxRecords)
            .map { it.id }
            .toSet()
        records.removeAll { it.id in victims }
        rewriteLocked(records)
    }

    /** 全量原子重写（临时文件 + 原子 move）。 */
    private fun rewriteLocked(current: List<MemoryRecord>) {
        val dir = path.toAbsolutePath().parent
        if (dir != null) Files.createDirectories(dir)
        val tmp = dir?.resolve("${path.fileName}.tmp") ?: Path.of("${path.fileName}.tmp")
        Files.newBufferedWriter(tmp, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { w ->
            for (r in current) {
                w.write(MemoryCodec.encode(r).toString())
                w.newLine()
            }
            w.flush()
        }
        Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun toString(): String = "FileMemoryStore($path, ${records.size} records)"
}
