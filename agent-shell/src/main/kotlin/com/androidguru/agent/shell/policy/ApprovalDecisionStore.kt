package com.androidguru.agent.shell.policy

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 审批决定持久化 —— 让 ALLOW_ALWAYS 的裁决跨会话存续。
 *
 * 背景：[ApprovalGate] 的「本次都允许」原本只活在进程内，
 * 每次重启 / 新会话都要重新点确认 —— 高频操作（写固定目录、跑固定脚本）
 * 在长期使用中摩擦极大。本存储把**显式授权**（ALLOW_ALWAYS）落盘，
 * 重启后直接生效。
 *
 * 安全纪律（缺一不可）：
 * - **opt-in**：不接 store 时行为与旧版完全一致（会话内记忆）；
 * - **BLOCKED 永不持久**：拒绝级决策没有「都允许」，门控侧保证；
 * - **TTL**：[ttlMs] 过期的授权自动失效（默认 7 天），限制暴露窗口；
 * - **可全清**：[clear] 一键抹除（用户「撤销全部授权」入口）；
 * - 密钥 / 密码类交互应答不进本存储（WriteGate 的 CONFIRM 通道本就
 *   不产生 ALLOW_ALWAYS 记忆键）。
 */
interface ApprovalDecisionStore {

    /** 读取仍然有效的 ALLOW_ALWAYS 键（TTL 外的过滤掉）。 */
    fun loadAllowedKeys(nowMs: Long = System.currentTimeMillis()): Set<String>

    /** 持久化一条「都允许」授权。 */
    fun persistAllowedKey(key: String, nowMs: Long = System.currentTimeMillis())

    /** 移除单条授权。 */
    fun removeAllowedKey(key: String)

    /** 清空全部授权。 */
    fun clear()
}

/**
 * 文件实现：JSONL（键 + 落盘时间），追加写 + 原子重写，
 * 损坏行容错跳过（对齐框架存储纪律）。
 */
class FileApprovalDecisionStore(
    private val file: Path,
    /** 授权有效期（毫秒）；默认 7 天。 */
    private val ttlMs: Long = DEFAULT_TTL_MS,
) : ApprovalDecisionStore {

    private val lock = Any()

    init {
        val dir = file.toAbsolutePath().parent
        if (dir != null) Files.createDirectories(dir)
    }

    override fun loadAllowedKeys(nowMs: Long): Set<String> = synchronized(lock) {
        if (!Files.exists(file)) return emptySet()
        val keys = mutableSetOf<String>()
        Files.newBufferedReader(file, StandardCharsets.UTF_8).use { r ->
            var line = r.readLine()
            while (line != null) {
                if (line.isNotBlank()) {
                    try {
                        val obj = Json.parseToJsonElement(line).jsonObject
                        val key = obj["key"]?.jsonPrimitive?.contentOrNull
                        val savedAt = obj["savedAtMs"]?.jsonPrimitive?.longOrNull ?: 0L
                        if (key != null && nowMs - savedAt < ttlMs) keys += key
                    } catch (e: Exception) {
                        // 损坏行跳过
                    }
                }
                line = r.readLine()
            }
        }
        keys
    }

    override fun persistAllowedKey(key: String, nowMs: Long) {
        synchronized(lock) {
            // 去重：已有同键且未过期的记录只刷新时间戳（重写）
            val existing = readAllLocked()
            val updated = existing.filter { it.first != key } + (key to nowMs)
            writeAllLocked(updated)
        }
    }

    override fun removeAllowedKey(key: String) {
        synchronized(lock) {
            val existing = readAllLocked()
            if (existing.any { it.first == key }) {
                writeAllLocked(existing.filter { it.first != key })
            }
        }
    }

    override fun clear() {
        synchronized(lock) {
            Files.newBufferedWriter(file, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { it.flush() }
            Unit
        }
    }

    // ------------------------------------------------------------------

    private fun readAllLocked(): List<Pair<String, Long>> {
        if (!Files.exists(file)) return emptyList()
        val out = mutableListOf<Pair<String, Long>>()
        Files.newBufferedReader(file, StandardCharsets.UTF_8).use { r ->
            var line = r.readLine()
            while (line != null) {
                if (line.isNotBlank()) {
                    try {
                        val obj = Json.parseToJsonElement(line).jsonObject
                        val key = obj["key"]?.jsonPrimitive?.contentOrNull
                        val savedAt = obj["savedAtMs"]?.jsonPrimitive?.longOrNull ?: 0L
                        if (key != null) out += key to savedAt
                    } catch (e: Exception) {
                        // 损坏行跳过
                    }
                }
                line = r.readLine()
            }
        }
        return out
    }

    private fun writeAllLocked(entries: List<Pair<String, Long>>) {
        val dir = file.toAbsolutePath().parent
        val tmp = dir?.resolve("${file.fileName}.tmp") ?: Path.of("${file.fileName}.tmp")
        Files.newBufferedWriter(tmp, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { w ->
            for ((key, savedAt) in entries) {
                w.write(
                    JsonObject(
                        mapOf(
                            "key" to JsonPrimitive(key),
                            "savedAtMs" to JsonPrimitive(savedAt),
                        ),
                    ).toString(),
                )
                w.newLine()
            }
            w.flush()
        }
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val DEFAULT_TTL_MS = 7L * 24 * 3600_000L
    }
}
