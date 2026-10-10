package com.androidguru.agent.tasks.plan

import java.util.concurrent.CopyOnWriteArrayList

/**
 * 任务计划管理器：单一事实源（Single Source of Truth）的状态机。
 *
 * 职责：
 * - **rewrite**：整表替换（对应 Claude Code TodoWrite 的全量写语义，适合计划初建 / 大改）；
 * - **patch（ops）**：按 id 的增量操作（add / update_status / update / insert / remove），
 *   长计划下比全量写省 token，且不会误伤未提及条目；
 * - **纪律执行**：[enforceSingleActive] 开启时（默认），置 IN_PROGRESS 自动把上一个
 *   进行中任务回退 PENDING —— 保证「同一时刻只有一个任务在进行」的长程任务纪律；
 * - **进度与渲染**：[progress] 供宿主 UI；[renderForModel] 注入引擎系统上下文
 *   （经 PlanContextInjector，每轮刷新，模型永远看到最新计划状态）；
 * - **可观察**：[addListener] 订阅变更（宿主 UI 实时刷新 / 遥测上报）；
 * - **持久化**：每次变更原子落盘（[TaskPlanStore]），崩溃后可恢复。
 *
 * 所有变更方法失败时**抛 [PlanOpException]** 由工具层折叠为 ToolResult.failure，
 * 管理器自身永不半写（先验证后提交，验证失败原状态不动）。
 */
class TaskPlanManager(
    private val store: TaskPlanStore,
    private val sessionId: String,
    /** 同一时刻只允许一个任务 IN_PROGRESS（新进行中任务自动把旧的回退 PENDING）。 */
    val enforceSingleActive: Boolean = true,
    /**
     * 父任务状态自动收拢（PR #26）：子任务全部 DONE/SKIPPED → 父任务 DONE；
     * 全部终态且 ≥1 FAILED → 父任务 FAILED；子任务开始推进 → 父任务 PENDING →
     * IN_PROGRESS。永不把父任务从人工置位的终态回退。
     */
    val autoAggregateParent: Boolean = true,
) {

    /** 计划变更操作失败（未知 id / 空标题等）。 */
    class PlanOpException(message: String) : Exception(message)

    private val lock = Any()

    @Volatile
    private var current: TaskPlan? = store.load(sessionId)

    private val listeners = CopyOnWriteArrayList<(TaskPlan) -> Unit>()

    /** 订阅计划变更（返回取消订阅函数）。 */
    fun addListener(listener: (TaskPlan) -> Unit): () -> Unit {
        listeners.add(listener)
        return { listeners.remove(listener) }
    }

    /** 当前计划快照（null = 尚未创建）。 */
    fun plan(): TaskPlan? = current

    /** 删除计划（清空状态）。 */
    fun clear() {
        synchronized(lock) {
            current = null
            store.delete(sessionId)
        }
    }

    // ------------------------------------------------------------------
    // rewrite：整表替换
    // ------------------------------------------------------------------

    /**
     * 用新目标 + 新任务列表整体替换计划。
     *
     * @param rawTasks 任务描述；id 缺省时自动分配（t1..tN），status 宽容解析。
     * @throws PlanOpException 标题为空 / 重复 id / parent 悬空时。
     */
    fun rewrite(goal: String, rawTasks: List<RawTask>): TaskPlan {
        if (goal.isBlank()) throw PlanOpException("goal 不能为空")
        if (rawTasks.isEmpty()) throw PlanOpException("任务列表不能为空（至少拆一条）")

        val now = System.currentTimeMillis()
        val allocated = LinkedHashMap<String, TaskEntry>()
        var seq = 0
        for (raw in rawTasks) {
            val title = raw.title.trim()
            if (title.isEmpty()) throw PlanOpException("任务标题不能为空")
            val id = raw.id?.trim()?.takeIf { it.isNotEmpty() } ?: nextId(++seq, allocated)
            if (allocated.containsKey(id)) throw PlanOpException("重复任务 id: $id")
            allocated[id] = TaskEntry(
                id = id,
                title = title,
                status = raw.status,
                notes = raw.notes ?: "",
                parent = raw.parent,
                createdAtMs = now,
                updatedAtMs = now,
            )
        }
        // parent 必须指向已存在的 id（防悬空引用）
        for (t in allocated.values) {
            if (t.parent != null && !allocated.containsKey(t.parent)) {
                throw PlanOpException("任务 ${t.id} 的 parent=${t.parent} 不存在")
            }
        }

        val plan = TaskPlan(
            goal = goal.trim(),
            tasks = normalize(allocated.values.toList()),
            createdAtMs = current?.createdAtMs ?: now,
            updatedAtMs = now,
        )
        commit(plan)
        return plan
    }

    // ------------------------------------------------------------------
    // patch：增量操作
    // ------------------------------------------------------------------

    /** 计划增量操作（工具参数直通模型产物，宽容解析）。 */
    sealed interface PlanOp {
        /** 追加任务（可选 id / parent）。 */
        data class Add(val id: String?, val title: String, val notes: String?, val parent: String?) : PlanOp

        /** 更新状态（add 时 status 缺省 PENDING）。 */
        data class UpdateStatus(val id: String, val status: TaskStatus, val notes: String?) : PlanOp

        /** 更新标题 / 备注。 */
        data class Update(val id: String, val title: String?, val notes: String?) : PlanOp

        /** 在指定任务后插入新任务（阶段中途补充步骤）。 */
        data class Insert(val afterId: String, val id: String?, val title: String, val notes: String?) : PlanOp

        /** 移除任务（连同其子任务）。 */
        data class Remove(val id: String) : PlanOp

        /** 更新目标描述。 */
        data class Goal(val goal: String) : PlanOp
    }

    /**
     * 顺序应用一批操作（全部校验通过才提交 —— 原子性）。
     *
     * @throws PlanOpException 任何一步失败：未知 id / 空标题 / 重复 id 等。
     */
    fun applyOps(ops: List<PlanOp>): TaskPlan {
        if (ops.isEmpty()) throw PlanOpException("ops 不能为空")
        val base = current ?: throw PlanOpException("尚无计划，请先 rewrite 创建")

        // 在副本上演练，成功才提交
        var goal = base.goal
        val tasks = mutableListOf<TimeoutlessEntry>()
        for (t in base.tasks) tasks.add(TimeoutlessEntry(t.id, t.title, t.status, t.notes, t.parent))
        var nextSeq = maxSeq(tasks)

        for (op in ops) {
            when (op) {
                is PlanOp.Goal -> {
                    if (op.goal.isBlank()) throw PlanOpException("goal 不能为空")
                    goal = op.goal.trim()
                }

                is PlanOp.Add -> {
                    val title = op.title.trim()
                    if (title.isEmpty()) throw PlanOpException("任务标题不能为空")
                    val id = op.id?.trim()?.takeIf { it.isNotEmpty() } ?: "t${++nextSeq}"
                    requireUniqueId(id, tasks)
                    if (op.parent != null && tasks.none { it.id == op.parent }) {
                        throw PlanOpException("parent=${op.parent} 不存在")
                    }
                    tasks.add(TimeoutlessEntry(id, title, TaskStatus.PENDING, op.notes ?: "", op.parent))
                }

                is PlanOp.UpdateStatus -> {
                    val idx = indexOf(op.id, tasks) ?: throw PlanOpException("未知任务 id: ${op.id}")
                    val entry = tasks[idx]
                    tasks[idx] = entry.copy(status = op.status, notes = op.notes ?: entry.notes)
                }

                is PlanOp.Update -> {
                    val idx = indexOf(op.id, tasks) ?: throw PlanOpException("未知任务 id: ${op.id}")
                    var entry = tasks[idx]
                    op.title?.trim()?.takeIf { it.isNotEmpty() }?.let { entry = entry.copy(title = it) }
                    op.notes?.let { entry = entry.copy(notes = it) }
                    tasks[idx] = entry
                }

                is PlanOp.Insert -> {
                    val afterIdx = indexOf(op.afterId, tasks) ?: throw PlanOpException("未知任务 id: ${op.afterId}")
                    val title = op.title.trim()
                    if (title.isEmpty()) throw PlanOpException("任务标题不能为空")
                    val id = op.id?.trim()?.takeIf { it.isNotEmpty() } ?: "t${++nextSeq}"
                    requireUniqueId(id, tasks)
                    tasks.add(afterIdx + 1, TimeoutlessEntry(id, title, TaskStatus.PENDING, op.notes ?: "", null))
                }

                is PlanOp.Remove -> {
                    val idx = indexOf(op.id, tasks) ?: throw PlanOpException("未知任务 id: ${op.id}")
                    val removed = tasks.removeAt(idx)
                    // 连同子任务一起移除
                    tasks.removeAll { it.parent == removed.id }
                }
            }
        }

        val now = System.currentTimeMillis()
        val plan = TaskPlan(
            goal = goal,
            tasks = normalize(tasks.map { it.toEntry(now) }),
            createdAtMs = base.createdAtMs,
            updatedAtMs = now,
        )
        commit(plan)
        return plan
    }

    // ------------------------------------------------------------------
    // 进度与渲染
    // ------------------------------------------------------------------

    /** 当前进度（无计划时 null）。 */
    fun progress(): TaskPlanProgress? {
        val plan = current ?: return null
        val tasks = plan.tasks
        if (tasks.isEmpty()) return null
        val done = tasks.count { it.status == TaskStatus.DONE }
        val failed = tasks.count { it.status == TaskStatus.FAILED }
        val skipped = tasks.count { it.status == TaskStatus.SKIPPED }
        val inProgress = tasks.count { it.status == TaskStatus.IN_PROGRESS }
        val pending = tasks.count { it.status == TaskStatus.PENDING }
        val denominator = (tasks.size - skipped).coerceAtLeast(1)
        return TaskPlanProgress(
            total = tasks.size,
            done = done,
            failed = failed,
            skipped = skipped,
            inProgress = inProgress,
            pending = pending,
            percent = done * 100 / denominator,
            currentTask = tasks.firstOrNull { it.status == TaskStatus.IN_PROGRESS },
            nextTask = tasks.firstOrNull { it.status == TaskStatus.PENDING },
        )
    }

    /**
     * 渲染为注入系统上下文的计划状态块（模型每轮看到的最新视图）。
     * 无计划时返回空串（不注入）。
     */
    fun renderForModel(): String {
        val plan = current ?: return ""
        val progress = progress() ?: return ""
        if (plan.tasks.isEmpty()) return ""

        return buildString {
            appendLine("# 长程任务计划（task_plan 工具维护，每轮自动刷新）")
            appendLine("目标: ${plan.goal}")
            appendLine(
                "进度: ${progress.done}/${progress.total} 完成（${progress.percent}%），" +
                    "进行中 ${progress.inProgress}，失败 ${progress.failed}，跳过 ${progress.skipped}",
            )
            appendLine()
            for (t in plan.tasks) {
                appendLine(renderTask(t, plan.tasks))
            }
            appendLine()
            appendLine("计划纪律：")
            appendLine("- 开始做一项之前先把它标为 in_progress；做完立刻标为 done（notes 写一句验收结论）；")
            appendLine("- 失败的任务标 failed 并写明原因，然后调整计划（insert 补充步骤）而不是原地重试；")
            appendLine("- 计划变化（新增 / 移除 / 重排）时立即通过 task_plan 的 patch 模式更新，保持计划与实际一致。")
        }
    }

    private fun renderTask(t: TaskEntry, all: List<TaskEntry>): String {
        val box = when (t.status) {
            TaskStatus.DONE -> "[x]"
            TaskStatus.IN_PROGRESS -> "[~]"
            TaskStatus.FAILED -> "[!]"
            TaskStatus.SKIPPED -> "[-]"
            TaskStatus.PENDING -> "[ ]"
        }
        val suffix = when {
            t.notes.isNotBlank() && t.status != TaskStatus.PENDING -> "  # ${t.notes}"
            else -> ""
        }
        val prefix = if (t.parent == null) "- " else "    - "
        return "$prefix$box ${t.id} ${t.title}$suffix"
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    /** 中间态条目（演练用，避开时间戳噪声）。 */
    private data class TimeoutlessEntry(
        val id: String,
        val title: String,
        val status: TaskStatus,
        val notes: String,
        val parent: String?,
    ) {
        fun toEntry(now: Long) = TaskEntry(
            id = id,
            title = title,
            status = status,
            notes = notes,
            parent = parent,
            updatedAtMs = now,
            createdAtMs = now,
        )
    }

    private fun commit(plan: TaskPlan) {
        synchronized(lock) {
            current = plan
            store.save(sessionId, plan)
        }
        listeners.forEach { it(plan) }
    }

    /**
     * 单一活跃纪律 + 父任务状态收拢（子任务全 DONE/SKIPPED 时父任务自动推进）。
     *
     * 注：两级结构下父任务的 IN_PROGRESS 是**派生态**（表示「该阶段正在推进」），
     * 与当前叶子任务并存不算违反单一活跃纪律 —— 单一活跃约束的是实际执行的叶子。
     */
    private fun normalize(tasks: List<TaskEntry>): List<TaskEntry> {
        val normalized = tasks.toMutableList()
        if (enforceSingleActive) {
            val active = normalized.filter { it.status == TaskStatus.IN_PROGRESS }
            if (active.size > 1) {
                // 保留最后置为进行中的（最新意图），其余回退 PENDING
                val keep = active.last()
                for (i in normalized.indices) {
                    val t = normalized[i]
                    if (t.status == TaskStatus.IN_PROGRESS && t.id != keep.id) {
                        normalized[i] = t.copy(status = TaskStatus.PENDING)
                    }
                }
            }
        }
        if (autoAggregateParent) {
            aggregateParents(normalized)
        }
        return normalized
    }

    /**
     * 父任务状态收拢：只在「向前推进」方向上改写父任务，永不回退人工置位的终态。
     *
     * - 子任务全 DONE/SKIPPED → 父任务 DONE（含部分 SKIPPED：跳过不算失败）；
     * - 子任务全终态且 ≥1 FAILED → 父任务 FAILED；
     * - 父任务 PENDING 且有子任务开始推进（IN_PROGRESS/DONE）→ 父任务 IN_PROGRESS。
     */
    private fun aggregateParents(tasks: MutableList<TaskEntry>) {
        val childrenByParent = tasks.filter { it.parent != null }.groupBy { it.parent!! }
        for (i in tasks.indices) {
            val task = tasks[i]
            if (task.parent != null) continue // 只收拢父层级（有子任务的非叶子）
            val children = childrenByParent[task.id] ?: continue

            val aggregated = when {
                task.status == TaskStatus.DONE || task.status == TaskStatus.SKIPPED -> null // 不回退人工终态

                children.all { it.status == TaskStatus.DONE || it.status == TaskStatus.SKIPPED } ->
                    TaskStatus.DONE

                children.none { it.status == TaskStatus.PENDING || it.status == TaskStatus.IN_PROGRESS } &&
                    children.any { it.status == TaskStatus.FAILED } ->
                    TaskStatus.FAILED

                task.status == TaskStatus.PENDING &&
                    children.any { it.status == TaskStatus.IN_PROGRESS || it.status == TaskStatus.DONE || it.status == TaskStatus.FAILED } ->
                    TaskStatus.IN_PROGRESS

                else -> null
            }
            if (aggregated != null && aggregated != task.status) {
                tasks[i] = task.copy(status = aggregated)
            }
        }
    }

    private fun nextId(seq: Int, allocated: LinkedHashMap<String, TaskEntry>): String {
        var candidate = "t$seq"
        var n = seq
        while (allocated.containsKey(candidate)) {
            n++
            candidate = "t$n"
        }
        return candidate
    }

    private fun requireUniqueId(id: String, tasks: List<TimeoutlessEntry>) {
        if (tasks.any { it.id == id }) throw PlanOpException("重复任务 id: $id")
    }

    private fun indexOf(id: String, tasks: List<TimeoutlessEntry>): Int? =
        tasks.indexOfFirst { it.id == id }.takeIf { it >= 0 }

    private fun maxSeq(tasks: List<TimeoutlessEntry>): Int =
        tasks.mapNotNull { it.id.removePrefix("t").toIntOrNull() }.maxOrNull() ?: 0
}

/** rewrite 的原始任务描述（宽容：id / status / notes / parent 均可缺省）。 */
data class RawTask(
    val id: String? = null,
    val title: String,
    val status: TaskStatus = TaskStatus.PENDING,
    val notes: String? = null,
    val parent: String? = null,
)
