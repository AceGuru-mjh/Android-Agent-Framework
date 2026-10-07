package com.androidguru.agent.tasks.tools

import com.androidguru.agent.tasks.plan.RawTask
import com.androidguru.agent.tasks.plan.TaskPlanManager
import com.androidguru.agent.tasks.plan.TaskPlanProgress
import com.androidguru.agent.tasks.plan.TaskStatus
import com.androidguru.agent.tools.AgentTool
import com.androidguru.agent.tools.ToolRequest
import com.androidguru.agent.tools.ToolResult
import com.androidguru.agent.tools.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 内置任务计划工具（对齐 Claude Code 的 TodoWrite 模式，并增强为 rewrite / patch 双模式）。
 *
 * - **rewrite**：整表替换 —— 计划初建、结构性大改（任务 3~10 条时推荐）；
 * - **patch**：按 id 增量操作（add / update_status / update / insert / remove / goal）——
 *   长计划下增量更新，不误伤未提及条目；
 * - **read**：返回当前计划渲染（配合关闭自动注入调试用）。
 *
 * 计划状态由 [TaskPlanManager] 持有并每轮注入系统上下文（PlanContextInjector），
 * 工具返回值同时携带渲染后的最新计划，保证模型调用后立刻看到确认视图。
 */
class TaskPlanTool(
    private val manager: TaskPlanManager,
) : AgentTool {

    override val id: String = ID
    override val name: String = ID
    override val description: String =
        "维护长程任务计划（todo）。开始复杂任务时先用 rewrite 模式把任务拆解为 3~10 条有序步骤；" +
            "每完成 / 失败一步立即用 patch 模式更新状态（done 请附一句验收结论，failed 写明原因）。" +
            "计划状态每轮自动展示给你。参数 action=rewrite 时需 goal + tasks；" +
            "action=patch 时需 ops（数组，按顺序执行，任一失败则全部不生效）。"

    override val parameters: ToolSchema = ToolSchema.build {
        string(
            "action",
            "操作模式：rewrite=整表替换计划；patch=增量修改；read=查看当前计划",
            required = true,
            enumValues = listOf("rewrite", "patch", "read"),
        )
        string("goal", "任务总目标（rewrite 必填；patch 中通过 ops 的 goal 操作修改）")
        array("tasks", "rewrite 模式的任务列表，元素结构 {id?, title, status?, notes?, parent?}", itemType = "object")
        array("ops", "patch 模式的操作列表，按序执行，元素结构见描述", itemType = "object")
    }

    override suspend fun execute(request: ToolRequest): ToolResult {
        val obj = try {
            Json.parseToJsonElement(request.arguments.ifBlank { "{}" }).jsonObject
        } catch (e: Exception) {
            return ToolResult.failure("arguments 不是合法 JSON 对象: ${e.message}", com.androidguru.agent.tools.ToolErrorCode.VALIDATION)
        }

        val action = obj["action"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.failure("缺少 action 参数（rewrite / patch / read）", com.androidguru.agent.tools.ToolErrorCode.VALIDATION)

        return try {
            when (action) {
                "rewrite" -> doRewrite(obj)
                "patch" -> doPatch(obj)
                "read" -> doRead()
                else -> ToolResult.failure(
                    "未知 action: $action（支持 rewrite / patch / read）",
                    com.androidguru.agent.tools.ToolErrorCode.VALIDATION,
                )
            }
        } catch (e: TaskPlanManager.PlanOpException) {
            ToolResult.failure(
                e.message ?: "计划操作失败",
                com.androidguru.agent.tools.ToolErrorCode.VALIDATION,
                suggestion = "按错误提示修正后重试；当前计划未受影响（操作是原子的）",
            )
        } catch (e: Exception) {
            ToolResult.failure(
                "task_plan 内部错误: ${e.message ?: e.javaClass.simpleName}（参数结构是否符合描述？）",
                com.androidguru.agent.tools.ToolErrorCode.INTERNAL,
            )
        }
    }

    // ------------------------------------------------------------------
    // rewrite
    // ------------------------------------------------------------------

    private fun doRewrite(obj: JsonObject): ToolResult {
        val goal = obj["goal"]?.jsonPrimitive?.contentOrNull
            ?: return ToolResult.failure("rewrite 需要 goal 参数", com.androidguru.agent.tools.ToolErrorCode.VALIDATION)
        val tasksArr = obj["tasks"] as? JsonArray
            ?: return ToolResult.failure("rewrite 需要 tasks 数组参数", com.androidguru.agent.tools.ToolErrorCode.VALIDATION)

        val rawTasks = mutableListOf<RawTask>()
        for ((i, el) in tasksArr.withIndex()) {
            val t = el as? JsonObject
                ?: return ToolResult.failure("tasks[$i] 应为对象 {id?, title, status?, notes?, parent?}", com.androidguru.agent.tools.ToolErrorCode.VALIDATION)
            val title = t["title"]?.jsonPrimitive?.contentOrNull
                ?: return ToolResult.failure("tasks[$i] 缺少 title", com.androidguru.agent.tools.ToolErrorCode.VALIDATION)
            rawTasks += RawTask(
                id = t["id"]?.jsonPrimitive?.contentOrNull,
                title = title,
                status = TaskStatus.parse(t["status"]?.jsonPrimitive?.contentOrNull),
                notes = t["notes"]?.jsonPrimitive?.contentOrNull,
                parent = t["parent"]?.jsonPrimitive?.contentOrNull,
            )
        }

        val plan = manager.rewrite(goal, rawTasks)
        return ToolResult.success(
            "计划已创建（${plan.tasks.size} 项）：\n${manager.renderForModel()}",
            data = progressData(),
        )
    }

    // ------------------------------------------------------------------
    // patch
    // ------------------------------------------------------------------

    private fun doPatch(obj: JsonObject): ToolResult {
        val opsArr = obj["ops"] as? JsonArray
            ?: return ToolResult.failure("patch 需要 ops 数组参数", com.androidguru.agent.tools.ToolErrorCode.VALIDATION)

        val ops = mutableListOf<TaskPlanManager.PlanOp>()
        for ((i, el) in opsArr.withIndex()) {
            val o = el as? JsonObject
                ?: return ToolResult.failure("ops[$i] 应为对象", com.androidguru.agent.tools.ToolErrorCode.VALIDATION)
            val kind = o["op"]?.jsonPrimitive?.contentOrNull
                ?: return ToolResult.failure("ops[$i] 缺少 op 字段（add / update_status / update / insert / remove / goal）", com.androidguru.agent.tools.ToolErrorCode.VALIDATION)
            ops += when (kind) {
                "add" -> TaskPlanManager.PlanOp.Add(
                    id = o["id"]?.jsonPrimitive?.contentOrNull,
                    title = o["title"]?.jsonPrimitive?.contentOrNull
                        ?: return ToolResult.failure("ops[$i] add 缺少 title", com.androidguru.agent.tools.ToolErrorCode.VALIDATION),
                    notes = o["notes"]?.jsonPrimitive?.contentOrNull,
                    parent = o["parent"]?.jsonPrimitive?.contentOrNull,
                )

                "update_status" -> TaskPlanManager.PlanOp.UpdateStatus(
                    id = o["id"]?.jsonPrimitive?.contentOrNull
                        ?: return ToolResult.failure("ops[$i] update_status 缺少 id", com.androidguru.agent.tools.ToolErrorCode.VALIDATION),
                    status = TaskStatus.parse(o["status"]?.jsonPrimitive?.contentOrNull),
                    notes = o["notes"]?.jsonPrimitive?.contentOrNull,
                )

                "update" -> TaskPlanManager.PlanOp.Update(
                    id = o["id"]?.jsonPrimitive?.contentOrNull
                        ?: return ToolResult.failure("ops[$i] update 缺少 id", com.androidguru.agent.tools.ToolErrorCode.VALIDATION),
                    title = o["title"]?.jsonPrimitive?.contentOrNull,
                    notes = o["notes"]?.jsonPrimitive?.contentOrNull,
                )

                "insert" -> TaskPlanManager.PlanOp.Insert(
                    afterId = o["after_id"]?.jsonPrimitive?.contentOrNull
                        ?: return ToolResult.failure("ops[$i] insert 缺少 after_id", com.androidguru.agent.tools.ToolErrorCode.VALIDATION),
                    id = o["id"]?.jsonPrimitive?.contentOrNull,
                    title = o["title"]?.jsonPrimitive?.contentOrNull
                        ?: return ToolResult.failure("ops[$i] insert 缺少 title", com.androidguru.agent.tools.ToolErrorCode.VALIDATION),
                    notes = o["notes"]?.jsonPrimitive?.contentOrNull,
                )

                "remove" -> TaskPlanManager.PlanOp.Remove(
                    id = o["id"]?.jsonPrimitive?.contentOrNull
                        ?: return ToolResult.failure("ops[$i] remove 缺少 id", com.androidguru.agent.tools.ToolErrorCode.VALIDATION),
                )

                "goal" -> TaskPlanManager.PlanOp.Goal(
                    goal = o["goal"]?.jsonPrimitive?.contentOrNull
                        ?: return ToolResult.failure("ops[$i] goal 缺少 goal", com.androidguru.agent.tools.ToolErrorCode.VALIDATION),
                )

                else -> return ToolResult.failure(
                    "ops[$i] 未知操作类型: $kind",
                    com.androidguru.agent.tools.ToolErrorCode.VALIDATION,
                )
            }
        }

        val plan = manager.applyOps(ops)
        return ToolResult.success(
            "计划已更新（${plan.tasks.size} 项）：\n${manager.renderForModel()}",
            data = progressData(),
        )
    }

    private fun doRead(): ToolResult {
        val rendered = manager.renderForModel()
        return if (rendered.isBlank()) {
            ToolResult.success("当前没有计划。用 action=rewrite 创建。")
        } else {
            ToolResult.success(rendered, data = progressData())
        }
    }

    // ------------------------------------------------------------------

    private fun progressData(): String? {
        val p: TaskPlanProgress = manager.progress() ?: return null
        return JsonObject(
            mapOf(
                "total" to JsonPrimitive(p.total),
                "done" to JsonPrimitive(p.done),
                "failed" to JsonPrimitive(p.failed),
                "skipped" to JsonPrimitive(p.skipped),
                "inProgress" to JsonPrimitive(p.inProgress),
                "pending" to JsonPrimitive(p.pending),
                "percent" to JsonPrimitive(p.percent),
                "allDone" to JsonPrimitive(p.isAllDone),
            ),
        ).toString()
    }

    companion object {
        const val ID = "task_plan"
    }
}
