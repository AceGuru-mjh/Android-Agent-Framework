package com.androidguru.agent.tasks.plan

/**
 * 任务状态机（对齐 Claude Code TodoWrite 的五态模型）。
 *
 * 合法流转：
 * - PENDING → IN_PROGRESS / SKIPPED
 * - IN_PROGRESS → DONE / FAILED / PENDING（回退重做）
 * - FAILED → IN_PROGRESS（重试）/ SKIPPED（放弃）
 * - DONE / SKIPPED 为终态，模型显式回退时框架放行但不鼓励。
 */
enum class TaskStatus {
    PENDING,
    IN_PROGRESS,
    DONE,
    FAILED,
    SKIPPED;

    companion object {
        /** 宽容解析：未知取值回落 PENDING（模型输出容错）。 */
        fun parse(value: String?): TaskStatus =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: PENDING
    }
}

/**
 * 任务计划中的一条任务（todo 条目）。
 *
 * [id] 由框架统一分配（`t1`、`t2`…），对模型稳定 —— patch 操作按 id 精确寻址，
 * 避免按标题模糊匹配的漂移问题。[parent] 可选，用于「阶段 → 步骤」两级结构。
 */
data class TaskEntry(
    val id: String,
    val title: String,
    val status: TaskStatus = TaskStatus.PENDING,
    /** 结果备注（DONE 的验收结论 / FAILED 的原因），会展示给模型。 */
    val notes: String = "",
    /** 父任务 id（可选，两级结构）。 */
    val parent: String? = null,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
)

/**
 * 任务计划：目标 + 有序任务列表。
 *
 * 一个长程任务对应一份计划；计划整体可持久化（见 [TaskPlanStore]）、
 * 可跨进程恢复、可随引擎 continueExecution 继续推进。
 */
data class TaskPlan(
    /** 任务总目标（来自用户原始诉求，rewrite 时可细化）。 */
    val goal: String,
    val tasks: List<TaskEntry>,
    val createdAtMs: Long = System.currentTimeMillis(),
    val updatedAtMs: Long = System.currentTimeMillis(),
)

/**
 * 计划进度快照（宿主 UI / 遥测直接消费）。
 */
data class TaskPlanProgress(
    val total: Int,
    val done: Int,
    val failed: Int,
    val skipped: Int,
    val inProgress: Int,
    val pending: Int,
    /** 完成率（分母排除 SKIPPED）：0..100。 */
    val percent: Int,
    /** 当前进行中的任务（enforceSingleActive 下唯一）。 */
    val currentTask: TaskEntry?,
    /** 按 id 顺序的下一个 PENDING 任务（驱动「下一步」提示）。 */
    val nextTask: TaskEntry?,
) {
    val isAllDone: Boolean get() = total > 0 && (done + skipped) == total
}
