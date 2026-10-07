package com.androidguru.agent.workflow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * 工作流库：沉淀可复用工作流 + 健康度生命周期。
 *
 * 生命周期（同类项目的共同盲区，本框架补上执行统计）：
 * - **CANDIDATE → ACTIVE**：新蒸馏 / 新保存的工作流先进候选态，累计
 *   [promoteThreshold] 次（默认 2）成功运行后晋升 —— OS-Copilot 评分门槛的
 *   统计化替代（「被复用验证过」比「自评高分」可信）；
 * - **ACTIVE → DISABLED**：连续 [degradeAfterFailures] 次（默认 3）失败自动
 *   熔断下架（从建议注入摘除、run_workflow 拒绝执行，文件保留可人工复活）；
 * - 人工 [disable] / [enable] 一票否决 / 一票复权（enable 直达 ACTIVE —— 显式
 *   信任高于统计）。
 *
 * 统计字段（successCount / failCount / consecutiveFailures / lastError）由
 * [recordRunOutcome] 维护 —— 引擎 onRunFinished 回调接线（见 WorkflowSystem）。
 */
enum class WorkflowLifecycle {
    /** 新入库（蒸馏 / 保存 / 模型手写）：尚未被复用验证。 */
    CANDIDATE,

    /** 已验证：进入建议注入索引。 */
    ACTIVE,

    /** 熔断 / 人工禁用：不可执行、不进索引；文件保留。 */
    DISABLED,
}

/** 库中一条工作流（定义 + 生命周期 + 执行统计）。 */
data class SavedWorkflow(
    val definition: WorkflowDefinition,
    val lifecycle: WorkflowLifecycle = WorkflowLifecycle.CANDIDATE,
    /** 同 id 语义版本（内容更新 +1，保留可回滚）。 */
    val version: Int = 1,
    val successCount: Int = 0,
    val failCount: Int = 0,
    val consecutiveFailures: Int = 0,
    val promotedAtMs: Long? = null,
    val lastRunAtMs: Long? = null,
    val lastError: String? = null,
    /** 蒸馏自哪次运行（溯源）。 */
    val sourceRunId: String? = null,
    val createdAtMs: Long,
    val updatedAtMs: Long,
) {
    /** 总运行次数。 */
    val totalRuns: Int get() = successCount + failCount

    /** 成功率（无运行时 0）。 */
    val successRate: Double get() = if (totalRuns == 0) 0.0 else successCount.toDouble() / totalRuns

    fun isExecutable(): Boolean = lifecycle != WorkflowLifecycle.DISABLED
}

/** 工作流库存储。 */
interface WorkflowLibraryStore {

    fun save(saved: SavedWorkflow)

    fun load(id: String): SavedWorkflow?

    fun list(): List<SavedWorkflow>

    fun delete(id: String): Boolean
}

/** 内存实现（默认；测试 / 一次性使用）。 */
class InMemoryWorkflowLibraryStore : WorkflowLibraryStore {

    private val store = ConcurrentHashMap<String, SavedWorkflow>()

    override fun save(saved: SavedWorkflow) {
        store[saved.definition.id] = saved
    }

    override fun load(id: String): SavedWorkflow? = store[id]

    override fun list(): List<SavedWorkflow> = store.values.toList()

    override fun delete(id: String): Boolean = store.remove(id) != null
}

/**
 * 文件实现：`{dir}/{id}.workflow.json`，临时文件 + 原子 move；单文件损坏跳过
 * （对齐 FileTaskPlanStore 容错纪律）。
 */
class FileWorkflowLibraryStore(
    private val dir: Path,
) : WorkflowLibraryStore {

    init {
        Files.createDirectories(dir.toAbsolutePath())
    }

    private fun fileOf(id: String): Path {
        val safe = id.replace(Regex("[^\\w-]"), "_").take(120)
        return dir.resolve("$safe.workflow.json")
    }

    override fun save(saved: SavedWorkflow) {
        val file = fileOf(saved.definition.id)
        val tmp = dir.resolve("${file.fileName}.tmp")
        Files.writeString(tmp, encode(saved).toString(), StandardCharsets.UTF_8)
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun load(id: String): SavedWorkflow? {
        val file = fileOf(id)
        if (!Files.exists(file)) return null
        return try {
            decode(Json.parseToJsonElement(Files.readString(file, StandardCharsets.UTF_8)).jsonObject)
        } catch (e: Exception) {
            null
        }
    }

    override fun list(): List<SavedWorkflow> {
        if (!Files.isDirectory(dir)) return emptyList()
        val result = mutableListOf<SavedWorkflow>()
        Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".workflow.json") }.forEach { path ->
                try {
                    decode(Json.parseToJsonElement(Files.readString(path, StandardCharsets.UTF_8)).jsonObject)
                        ?.let { result += it }
                } catch (e: Exception) {
                    // 单文件损坏跳过
                }
            }
        }
        return result
    }

    override fun delete(id: String): Boolean {
        val file = fileOf(id)
        return Files.exists(file) && Files.deleteIfExists(file)
    }

    // ------------------------------------------------------------------
    // JSON 编解码（复用 WorkflowCodec 的定义编解码）
    // ------------------------------------------------------------------

    internal fun encode(saved: SavedWorkflow): JsonObject = JsonObject(
        buildMap {
            put("definition", WorkflowCodec.encodeDefinition(saved.definition))
            put("lifecycle", JsonPrimitive(saved.lifecycle.name))
            put("version", JsonPrimitive(saved.version))
            put("successCount", JsonPrimitive(saved.successCount))
            put("failCount", JsonPrimitive(saved.failCount))
            put("consecutiveFailures", JsonPrimitive(saved.consecutiveFailures))
            saved.promotedAtMs?.let { put("promotedAtMs", JsonPrimitive(it)) }
            saved.lastRunAtMs?.let { put("lastRunAtMs", JsonPrimitive(it)) }
            saved.lastError?.let { put("lastError", JsonPrimitive(it)) }
            saved.sourceRunId?.let { put("sourceRunId", JsonPrimitive(it)) }
            put("createdAtMs", JsonPrimitive(saved.createdAtMs))
            put("updatedAtMs", JsonPrimitive(saved.updatedAtMs))
        },
    )

    internal fun decode(obj: JsonObject): SavedWorkflow? {
        val defObj = obj["definition"] as? JsonObject ?: return null
        val definition = try {
            WorkflowCodec.decodeDefinition(defObj)
        } catch (e: Exception) {
            return null
        }
        val lifecycle = runCatching {
            WorkflowLifecycle.valueOf(obj.stringField("lifecycle") ?: "CANDIDATE")
        }.getOrDefault(WorkflowLifecycle.CANDIDATE)
        return SavedWorkflow(
            definition = definition,
            lifecycle = lifecycle,
            version = obj.intField("version") ?: 1,
            successCount = obj.intField("successCount") ?: 0,
            failCount = obj.intField("failCount") ?: 0,
            consecutiveFailures = obj.intField("consecutiveFailures") ?: 0,
            promotedAtMs = obj.longField("promotedAtMs"),
            lastRunAtMs = obj.longField("lastRunAtMs"),
            lastError = obj.stringField("lastError"),
            sourceRunId = obj.stringField("sourceRunId"),
            createdAtMs = obj.longField("createdAtMs") ?: 0L,
            updatedAtMs = obj.longField("updatedAtMs") ?: 0L,
        )
    }

    private fun JsonObject.stringField(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.intField(key: String): Int? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()

    private fun JsonObject.longField(key: String): Long? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
}

/**
 * 工作流库管理器（单一事实源；变更原子提交 + 监听器通知，对齐 TaskPlanManager）。
 */
class WorkflowLibrary(
    private val store: WorkflowLibraryStore,
    private val clock: () -> Long = System::currentTimeMillis,
    /** CANDIDATE → ACTIVE 的成功运行门槛。 */
    private val promoteThreshold: Int = 2,
    /** ACTIVE 自动熔断的连续失败门槛。 */
    private val degradeAfterFailures: Int = 3,
) {

    private val lock = Any()

    /** 保存 / 更新（同 id 内容更新 = version+1；统计与生命周期保留）。 */
    fun save(definition: WorkflowDefinition, sourceRunId: String? = null): SavedWorkflow {
        definition.validate()
        synchronized(lock) {
            val now = clock()
            val existing = store.load(definition.id)
            val saved = if (existing == null) {
                SavedWorkflow(
                    definition = definition,
                    lifecycle = WorkflowLifecycle.CANDIDATE,
                    version = 1,
                    sourceRunId = sourceRunId,
                    createdAtMs = now,
                    updatedAtMs = now,
                )
            } else {
                existing.copy(
                    definition = definition,
                    version = existing.version + 1,
                    // 新内容上线：连败清零重新观察；成功统计保留（历史信誉）
                    consecutiveFailures = 0,
                    lastError = null,
                    updatedAtMs = now,
                )
            }
            store.save(saved)
            return saved
        }
    }

    fun get(id: String): SavedWorkflow? = synchronized(lock) { store.load(id) }

    /** 列举（默认只列可执行项；includeDisabled = false 排除熔断项）。 */
    fun list(includeDisabled: Boolean = false): List<SavedWorkflow> = synchronized(lock) {
        store.list().filter { includeDisabled || it.lifecycle != WorkflowLifecycle.DISABLED }
    }

    /** 记录一次运行结果（引擎 onRunFinished 接线点）：统计 + 生命周期迁移。 */
    fun recordRunOutcome(definitionId: String, success: Boolean, error: String? = null): SavedWorkflow? {
        synchronized(lock) {
            val existing = store.load(definitionId) ?: return null
            val now = clock()
            var next = if (success) {
                existing.copy(
                    successCount = existing.successCount + 1,
                    consecutiveFailures = 0,
                    lastRunAtMs = now,
                    lastError = null,
                    updatedAtMs = now,
                )
            } else {
                existing.copy(
                    failCount = existing.failCount + 1,
                    consecutiveFailures = existing.consecutiveFailures + 1,
                    lastRunAtMs = now,
                    lastError = error?.take(200),
                    updatedAtMs = now,
                )
            }
            // 生命周期迁移
            if (success && next.lifecycle == WorkflowLifecycle.CANDIDATE && next.successCount >= promoteThreshold) {
                next = next.copy(lifecycle = WorkflowLifecycle.ACTIVE, promotedAtMs = now)
            } else if (!success && next.lifecycle == WorkflowLifecycle.ACTIVE &&
                next.consecutiveFailures >= degradeAfterFailures
            ) {
                next = next.copy(lifecycle = WorkflowLifecycle.DISABLED, lastError = "连续失败 ${next.consecutiveFailures} 次，自动熔断")
            }
            store.save(next)
            return next
        }
    }

    /** 人工禁用（一票否决）。 */
    fun disable(id: String, reason: String? = null): SavedWorkflow? {
        synchronized(lock) {
            val existing = store.load(id) ?: return null
            val next = existing.copy(
                lifecycle = WorkflowLifecycle.DISABLED,
                lastError = reason ?: existing.lastError,
                updatedAtMs = clock(),
            )
            store.save(next)
            return next
        }
    }

    /** 人工复活（显式信任 → 直达 ACTIVE）。 */
    fun enable(id: String): SavedWorkflow? {
        synchronized(lock) {
            val existing = store.load(id) ?: return null
            val next = existing.copy(
                lifecycle = WorkflowLifecycle.ACTIVE,
                consecutiveFailures = 0,
                lastError = null,
                updatedAtMs = clock(),
            )
            store.save(next)
            return next
        }
    }

    fun delete(id: String): Boolean = synchronized(lock) { store.delete(id) }
}
