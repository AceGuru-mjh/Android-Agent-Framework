package com.androidguru.agent.tasks.plan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * 任务计划存储：按 sessionId 读写整份计划。
 *
 * 长程任务的「跨进程恢复」拼图之一：计划存文件、会话记忆存文件（agent-core 的
 * FileConversationMemory），进程重启后两者都可重建，任务从断点继续。
 */
interface TaskPlanStore {

    fun load(sessionId: String): TaskPlan?

    fun save(sessionId: String, plan: TaskPlan)

    fun delete(sessionId: String): Boolean
}

/** 内存实现（默认；测试 / 一次性任务使用）。 */
class InMemoryTaskPlanStore : TaskPlanStore {

    private val store = ConcurrentHashMap<String, TaskPlan>()

    override fun load(sessionId: String): TaskPlan? = store[sessionId]

    override fun save(sessionId: String, plan: TaskPlan) {
        store[sessionId] = plan
    }

    override fun delete(sessionId: String): Boolean = store.remove(sessionId) != null
}

/**
 * 文件实现：`{dir}/{sessionId}.plan.json`，写入走「临时文件 + 原子 move」，
 * 进程崩溃 / 掉电都不会留下半写计划。
 */
class FileTaskPlanStore(
    private val dir: Path,
) : TaskPlanStore {

    init {
        Files.createDirectories(dir.toAbsolutePath())
    }

    private fun fileOf(sessionId: String): Path {
        // 会话 id 进入文件名：只保留词字符与连字符，防目录穿越 / 特殊文件名
        val safe = sessionId.replace(Regex("[^\\w-]"), "_").take(120)
        return dir.resolve("$safe.plan.json")
    }

    override fun load(sessionId: String): TaskPlan? {
        val file = fileOf(sessionId)
        if (!Files.exists(file)) return null
        return try {
            decodePlan(Json.parseToJsonElement(Files.readString(file, StandardCharsets.UTF_8)).jsonObject)
        } catch (e: Exception) {
            null // 单文件损坏回落为「无计划」，绝不阻断任务
        }
    }

    override fun save(sessionId: String, plan: TaskPlan) {
        val file = fileOf(sessionId)
        val tmp = dir.resolve("${file.fileName}.tmp")
        Files.writeString(tmp, encodePlan(plan).toString(), StandardCharsets.UTF_8)
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun delete(sessionId: String): Boolean {
        val file = fileOf(sessionId)
        return Files.exists(file) && Files.deleteIfExists(file)
    }

    // ------------------------------------------------------------------
    // JSON 编解码（手工实现，模型类型保持纯 data class）
    // ------------------------------------------------------------------

    internal fun encodePlan(plan: TaskPlan): JsonObject = JsonObject(
        mapOf(
            "goal" to JsonPrimitive(plan.goal),
            "tasks" to JsonArray(
                plan.tasks.map { t ->
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive(t.id),
                            "title" to JsonPrimitive(t.title),
                            "status" to JsonPrimitive(t.status.name),
                            "notes" to JsonPrimitive(t.notes),
                            "parent" to (t.parent?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull),
                            "createdAtMs" to JsonPrimitive(t.createdAtMs),
                            "updatedAtMs" to JsonPrimitive(t.updatedAtMs),
                        ),
                    )
                },
            ),
            "createdAtMs" to JsonPrimitive(plan.createdAtMs),
            "updatedAtMs" to JsonPrimitive(plan.updatedAtMs),
        ),
    )

    internal fun decodePlan(obj: JsonObject): TaskPlan? {
        val goal = obj["goal"]?.jsonPrimitive?.contentOrNull ?: return null
        val tasks = (obj["tasks"] as? JsonArray)?.mapNotNull { el ->
            val t = el as? JsonObject ?: return@mapNotNull null
            val id = t["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val title = t["title"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            TaskEntry(
                id = id,
                title = title,
                status = TaskStatus.parse(t["status"]?.jsonPrimitive?.contentOrNull),
                notes = t["notes"]?.jsonPrimitive?.contentOrNull ?: "",
                parent = (t["parent"] as? JsonPrimitive)?.contentOrNull,
                createdAtMs = t["createdAtMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                updatedAtMs = t["updatedAtMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            )
        } ?: emptyList()
        return TaskPlan(
            goal = goal,
            tasks = tasks,
            createdAtMs = obj["createdAtMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            updatedAtMs = obj["updatedAtMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
        )
    }
}
