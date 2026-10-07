package com.androidguru.agent.workflow

import com.androidguru.agent.tools.AgentTool
import com.androidguru.agent.tools.ToolErrorCode
import com.androidguru.agent.tools.ToolRequest
import com.androidguru.agent.tools.ToolResult
import com.androidguru.agent.tools.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * 工作流工具五件套 —— 模型对工作流库的主动读写通道（工作流是**一等工具**，
 * droidrun ToolRegistry 模式：ReAct 引擎观察到的就是普通工具调用，
 * 模型自主决定走快速确定性路径还是逐步推理）。
 *
 * - **run_workflow**：执行库中工作流（参数对象）—— 失败时返回**接管包**
 *   （失败节点 + 剩余步骤 + 错误），模型可据此无缝接手（mobilerun
 *   on-mismatch=agent 的 handoff 设计）；
 * - **list_workflows**：索引（两阶段披露第一层：只给 id/名称/描述/统计）；
 * - **read_workflow**：按需拉全文（第二层，防 prompt 膨胀）；
 * - **save_workflow**：模型手写工作流入库（CANDIDATE）；
 * - **disable_workflow**：熔断坏工作流（自愈能力 —— 与 memory_forget 同族）。
 */
object WorkflowTools {

    const val RUN_TOOL_ID = "run_workflow"
    const val LIST_TOOL_ID = "list_workflows"
    const val READ_TOOL_ID = "read_workflow"
    const val SAVE_TOOL_ID = "save_workflow"
    const val DISABLE_TOOL_ID = "disable_workflow"

    /** 全部工具实例（接线引擎与库）。 */
    fun all(library: WorkflowLibrary, engine: WorkflowEngine): List<AgentTool> = listOf(
        WorkflowRunTool(library, engine),
        WorkflowListTool(library),
        WorkflowReadTool(library),
        WorkflowSaveTool(library),
        WorkflowDisableTool(library),
    )
}

/** 执行工作流（同步到终态；失败带接管包）。 */
class WorkflowRunTool(
    private val library: WorkflowLibrary,
    private val engine: WorkflowEngine,
) : AgentTool {

    override val id: String = WorkflowTools.RUN_TOOL_ID
    override val name: String = id
    override val description: String =
        "执行一个已保存的工作流（可复用的多步骤流程）。优先用 list_workflows 查看可用工作流，" +
            "把任务参数（如收件人、文件名、数量）作为 params 传入。工作流失败时返回失败节点、" +
            "剩余步骤与错误信息 —— 可据此决定重试、换参或自行接手剩余步骤。"

    override val parameters: ToolSchema = ToolSchema.build {
        string("id", "工作流 id（来自 list_workflows）", required = true)
        string("params", "参数 JSON 对象，如 {\"recipient\": \"alice\", \"count\": 3}", required = false)
    }

    override suspend fun execute(request: ToolRequest): ToolResult {
        val obj = parseArgs(request) ?: return invalid("arguments 不是合法 JSON 对象")
        val id = obj.string("id") ?: return invalid("id", "id 不能为空")

        val saved = library.get(id) ?: return notFound("工作流 $id 不存在（用 list_workflows 查看）")
        if (!saved.isExecutable()) {
            return ToolResult.failure(
                "工作流 $id 已被禁用（${saved.lastError ?: "人工/自动熔断"}）。" +
                    "如需复活请让宿主调用 WorkflowLibrary.enable。",
                ToolErrorCode.PERMISSION,
            )
        }

        val params = parseParams(obj.string("params") ?: "{}")

        val events = try {
            engine.start(saved.definition, params)
        } catch (e: WorkflowValidationException) {
            return ToolResult.failure("工作流定义校验失败: ${e.message}", ToolErrorCode.INTERNAL)
        } catch (e: IllegalArgumentException) {
            return ToolResult.failure(e.message ?: "参数校验失败", ToolErrorCode.VALIDATION)
        }

        // 收集事件直至终态（WAITING_HUMAN 不该出现在工具路径 —— 定义里 human
        // 节点面向宿主编排，模型工作流应可自治完成）
        var terminal: WorkflowEvent? = null
        val nodeFailures = mutableListOf<String>()
        try {
            events.collect { event ->
                when (event) {
                    is WorkflowEvent.NodeFailed -> nodeFailures += "${event.nodeId}: ${event.error}"
                    is WorkflowEvent.RunSucceeded, is WorkflowEvent.RunFailed,
                    is WorkflowEvent.RunCancelled, is WorkflowEvent.RunWaiting,
                    -> terminal = event
                    else -> Unit
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        }

        // WAITING_HUMAN 落在工具路径 = 定义包含 human 节点 —— 无人工通道时标记取消并说明
        val waiting = terminal as? WorkflowEvent.RunWaiting
        if (waiting != null) {
            engine.cancel(waiting.runId)
            return ToolResult.failure(
                "工作流 $id 含人工节点（${waiting.nodeId}），无法在工具路径内等待人工答复 —— " +
                    "请自行完成该步骤或让宿主直接编排此工作流",
                ToolErrorCode.PERMISSION,
            )
        }

        val terminalEvent = terminal
            ?: return ToolResult.failure("运行未产生终态事件（引擎异常）", ToolErrorCode.INTERNAL)
        val run = engine.run(terminalEvent.runId)
            ?: return ToolResult.failure("运行态丢失（存储异常）", ToolErrorCode.INTERNAL)

        return if (run.status == RunStatus.SUCCEEDED) {
            val success = terminal as WorkflowEvent.RunSucceeded
            ToolResult.success(
                "工作流 ${saved.definition.name} 执行成功：${success.summary}，耗时 ${success.durationMs}ms" +
                    renderOutputs(run),
                data = JsonObject(
                    mapOf(
                        "runId" to JsonPrimitive(run.runId),
                        "status" to JsonPrimitive(run.status.name),
                        "workflowId" to JsonPrimitive(id),
                    ),
                ).toString(),
            )
        } else {
            val failed = terminal as? WorkflowEvent.RunFailed
            val remaining = run.definition.nodes
                .filter { (run.nodeStates[it.id]?.status ?: NodeStatus.PENDING) == NodeStatus.PENDING }
                .joinToString(", ") { it.id }
            ToolResult(
                ok = false,
                content = buildString {
                    append("工作流执行失败")
                    failed?.failedNodeId?.let { append("（节点 $it）") }
                    append("：${failed?.error ?: run.message ?: "未知原因"}。")
                    if (remaining.isNotBlank()) {
                        append(" 剩余未执行步骤: $remaining —— 可用工具自行完成这些步骤，或换参数重试。")
                    }
                    if (nodeFailures.isNotEmpty()) {
                        append(" 失败明细: ${nodeFailures.joinToString("; ").take(300)}")
                    }
                },
                error = com.androidguru.agent.tools.ToolError(
                    code = ToolErrorCode.INTERNAL,
                    message = failed?.error ?: run.message ?: "workflow failed",
                ),
                data = JsonObject(
                    mapOf(
                        "runId" to JsonPrimitive(run.runId),
                        "status" to JsonPrimitive(run.status.name),
                        "workflowId" to JsonPrimitive(id),
                        "failedNodeId" to (failed?.failedNodeId?.let { JsonPrimitive(it) }
                            ?: kotlinx.serialization.json.JsonNull),
                        "remainingNodes" to JsonPrimitive(remaining),
                    ),
                ).toString(),
            )
        }
    }

    private fun renderOutputs(run: WorkflowRunState): String {
        // 给模型的最后一步输出（可直接接续使用）
        val endNodes = run.definition.edges.filter { it.to == WorkflowEdge.END }.mapNotNull { e ->
            val nodeId = e.from
            val output = run.nodeStates[nodeId]?.output ?: return@mapNotNull null
            nodeId to output
        }
        if (endNodes.isEmpty()) return ""
        val last = endNodes.joinToString(" | ") { (_, output) ->
            when (output) {
                is JsonPrimitive -> output.content.take(200)
                else -> output.toString().take(200)
            }
        }
        return "。末步输出: $last"
    }
}

/** 工作流索引（两阶段披露第一层）。 */
class WorkflowListTool(private val library: WorkflowLibrary) : AgentTool {

    override val id: String = WorkflowTools.LIST_TOOL_ID
    override val name: String = id
    override val description: String =
        "列出已保存的可复用工作流（id、名称、适用场景、执行统计）。" +
            "接到多步骤任务时先查这里 —— 已验证的流程直接用 run_workflow 复用，" +
            "比逐步推理更快更稳。需要完整步骤时再用 read_workflow。"

    override val parameters: ToolSchema = ToolSchema.build {
        boolean("include_disabled", "是否包含已禁用的工作流（默认否）", required = false)
    }

    override suspend fun execute(request: ToolRequest): ToolResult {
        val includeDisabled = try {
            parseArgs(request)?.string("include_disabled")?.toBooleanStrict() ?: false
        } catch (e: Exception) {
            return invalid("include_disabled", "应为 true/false")
        }
        val all = library.list(includeDisabled = includeDisabled)
        if (all.isEmpty()) return ToolResult.success("暂无已保存工作流。")
        val rendered = all.sortedWith(
            compareByDescending<SavedWorkflow> { it.lifecycle == WorkflowLifecycle.ACTIVE }
                .thenByDescending { it.successRate },
        ).joinToString("\n") { saved ->
            val stats = "成功 ${saved.successCount}/${saved.totalRuns}" +
                (saved.lastError?.let { "，最近错误: ${it.take(60)}" } ?: "")
            "- ${saved.definition.id} [${saved.lifecycle}] ${saved.definition.name}: " +
                "${saved.definition.description}（$stats）"
        }
        return ToolResult.success("可用工作流（${all.size} 个，ACTIVE 优先）：\n$rendered")
    }
}

/** 读取工作流完整定义（两阶段披露第二层）。 */
class WorkflowReadTool(private val library: WorkflowLibrary) : AgentTool {

    override val id: String = WorkflowTools.READ_TOOL_ID
    override val name: String = id
    override val description: String =
        "读取一个工作流的完整定义（参数、节点、步骤顺序）——" +
            "评估它是否适用于当前任务 / 需要传什么参数 / 失败后从哪一步接手。"

    override val parameters: ToolSchema = ToolSchema.build {
        string("id", "工作流 id", required = true)
    }

    override suspend fun execute(request: ToolRequest): ToolResult {
        val id = parseArgs(request)?.string("id") ?: return invalid("id", "id 不能为空")
        val saved = library.get(id) ?: return notFound("工作流 $id 不存在")
        val json = WorkflowCodec.definitionToJson(saved.definition)
        val lifecycle = "状态 ${saved.lifecycle} v${saved.version}，成功 ${saved.successCount}/${saved.totalRuns}"
        return ToolResult.success(
            "工作流 $id（$lifecycle）定义：\n${json.take(4000)}" +
                (if (json.length > 4000) "\n...（超长截断）" else ""),
        )
    }
}

/** 模型手写工作流入库。 */
class WorkflowSaveTool(private val library: WorkflowLibrary) : AgentTool {

    override val id: String = WorkflowTools.SAVE_TOOL_ID
    override val name: String = id
    override val description: String =
        "把一个可复用的多步骤流程保存为工作流（JSON 定义）。当你刚用一系列工具调用完成了" +
            "一个可重复的任务模式时，把它沉淀下来供以后 run_workflow 快速复用。" +
            "具体值（人名/文件名等）抽象为 params 形参，arguments 里用 \${params.形参名} 引用。" +
            "新工作流为候选态，被复用验证 2 次后自动激活。"

    override val parameters: ToolSchema = ToolSchema.build {
        string("definition", "工作流 JSON 定义（见 read_workflow 输出格式）", required = true)
    }

    override suspend fun execute(request: ToolRequest): ToolResult {
        val text = parseArgs(request)?.string("definition")
            ?: return invalid("definition", "definition 不能为空")
        val definition = try {
            WorkflowCodec.definitionFromJson(text)
        } catch (e: Exception) {
            return ToolResult.failure(
                "工作流定义不合法: ${e.message}",
                ToolErrorCode.VALIDATION,
                suggestion = "参考 read_workflow 的输出格式；节点 kind 取 tool/agent/human/switch/setvar/delay",
            )
        }
        return try {
            val saved = library.save(definition)
            ToolResult.success(
                "已保存工作流 ${saved.definition.id}（v${saved.version}，候选态）。" +
                    "被成功复用 2 次后自动激活进建议索引。",
                data = JsonObject(
                    mapOf("id" to JsonPrimitive(saved.definition.id), "version" to JsonPrimitive(saved.version)),
                ).toString(),
            )
        } catch (e: WorkflowValidationException) {
            ToolResult.failure("定义校验失败: ${e.message}", ToolErrorCode.VALIDATION)
        }
    }
}

/** 熔断坏工作流（自愈）。 */
class WorkflowDisableTool(private val library: WorkflowLibrary) : AgentTool {

    override val id: String = WorkflowTools.DISABLE_TOOL_ID
    override val name: String = id
    override val description: String =
        "禁用一个反复失败或行为错误的工作流（从复用索引移除，文件保留）。" +
            "当某工作流连续失败且无法换参挽救时使用；防止坏流程被反复复用。"

    override val parameters: ToolSchema = ToolSchema.build {
        string("id", "工作流 id", required = true)
        string("reason", "禁用原因（供人工排查）", required = false)
    }

    override suspend fun execute(request: ToolRequest): ToolResult {
        val obj = parseArgs(request) ?: return invalid("arguments 不是合法 JSON 对象")
        val id = obj.string("id") ?: return invalid("id", "id 不能为空")
        val disabled = library.disable(id, obj.string("reason"))
            ?: return notFound("工作流 $id 不存在")
        return ToolResult.success(
            "已禁用工作流 $id（${disabled.lastError ?: "无原因记录"}）。" +
                "此流程不再出现在建议与 run 列表；宿主可 WorkflowLibrary.enable 复活。",
        )
    }
}

// ------------------------------------------------------------------
// 内部小工具
// ------------------------------------------------------------------

private fun parseArgs(request: ToolRequest): JsonObject? = try {
    Json.parseToJsonElement(request.arguments.ifBlank { "{}" }).jsonObject
} catch (e: Exception) {
    null
}

private fun parseParams(text: String): Map<String, String> = try {
    val obj = Json.parseToJsonElement(text.ifBlank { "{}" }).jsonObject
    obj.entries.mapNotNull { (k, v) ->
        val content = (v as? JsonPrimitive)?.content ?: return@mapNotNull null
        k to content
    }.toMap()
} catch (e: Exception) {
    emptyMap()
}

private fun invalid(field: String, message: String): ToolResult =
    ToolResult.failure(message, ToolErrorCode.VALIDATION, field)

private fun invalid(message: String): ToolResult =
    ToolResult.failure(message, ToolErrorCode.VALIDATION)

private fun notFound(message: String): ToolResult =
    ToolResult.failure(message, ToolErrorCode.NOT_FOUND)

private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }
