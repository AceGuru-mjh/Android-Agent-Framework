package com.androidguru.agent.workflow

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap

/**
 * 工作流运行态存储：崩溃恢复拼图之一（与 agent-tasks 的 TaskPlanStore、
 * agent-core 的 FileConversationMemory 同族设计）。
 *
 * 文件实现：`{dir}/{runId}.run.json`，临时文件 + 原子 move；
 * 单文件损坏回落为「无此运行」，绝不阻断引擎（对齐 FileTaskPlanStore 容错纪律）。
 */
interface WorkflowRunStore {

    fun save(run: WorkflowRunState)

    fun load(runId: String): WorkflowRunState?

    /** 全量列举（崩溃扫描 / 宿主 UI 列表用）。 */
    fun list(): List<WorkflowRunState>

    fun delete(runId: String): Boolean
}

/** 内存实现（默认；测试 / 一次性运行）。 */
class InMemoryWorkflowRunStore : WorkflowRunStore {

    private val store = ConcurrentHashMap<String, WorkflowRunState>()

    override fun save(run: WorkflowRunState) {
        store[run.runId] = run
    }

    override fun load(runId: String): WorkflowRunState? = store[runId]

    override fun list(): List<WorkflowRunState> = store.values.toList()

    override fun delete(runId: String): Boolean = store.remove(runId) != null
}

/**
 * 文件实现：每运行一文件，写入走「临时文件 + 原子 move」（半行残留 = 崩溃窗口）。
 *
 * 恢复语义见 [WorkflowRunState]：SUCCEEDED 节点带输出 → 不重跑；
 * RUNNING 节点 → 调用方（引擎恢复逻辑）回退 PENDING 续跑（at-least-once）。
 */
class FileWorkflowRunStore(
    private val dir: Path,
) : WorkflowRunStore {

    init {
        Files.createDirectories(dir.toAbsolutePath())
    }

    private fun fileOf(runId: String): Path {
        val safe = runId.replace(Regex("[^\\w-]"), "_").take(120)
        return dir.resolve("$safe.run.json")
    }

    override fun save(run: WorkflowRunState) {
        val file = fileOf(run.runId)
        val tmp = dir.resolve("${file.fileName}.tmp")
        Files.writeString(tmp, WorkflowCodec.encodeRun(run).toString(), StandardCharsets.UTF_8)
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    override fun load(runId: String): WorkflowRunState? {
        val file = fileOf(runId)
        if (!Files.exists(file)) return null
        return try {
            WorkflowCodec.decodeRun(Json.parseToJsonElement(Files.readString(file, StandardCharsets.UTF_8)).jsonObject)
        } catch (e: Exception) {
            null // 单文件损坏 = 无此运行（已终态数据不复活），不阻断
        }
    }

    override fun list(): List<WorkflowRunState> {
        if (!Files.isDirectory(dir)) return emptyList()
        val result = mutableListOf<WorkflowRunState>()
        Files.list(dir).use { stream ->
            stream.filter { it.fileName.toString().endsWith(".run.json") }.forEach { path ->
                try {
                    result += WorkflowCodec.decodeRun(
                        Json.parseToJsonElement(Files.readString(path, StandardCharsets.UTF_8)).jsonObject,
                    )
                } catch (e: Exception) {
                    // 单文件损坏跳过
                }
            }
        }
        return result
    }

    override fun delete(runId: String): Boolean {
        val file = fileOf(runId)
        return Files.exists(file) && Files.deleteIfExists(file)
    }
}
