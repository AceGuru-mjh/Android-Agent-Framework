package com.androidguru.agent.workflow

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * 工作流运行态：一次执行的全部持久化状态。
 *
 * 「从哪继续」不靠位置指针，靠**状态标记**（LangGraph versions_seen / n8n runData
 * 的共同结论）：SUCCEEDED 且有输出的节点恢复时直接供下游消费，永不重跑；
 * RUNNING 的节点是崩溃窗口残留 → 恢复时回退 PENDING 按既有 attempt 续跑
 * （at-least-once 语义，工具应尽量幂等，见 WORKFLOW_GUIDE）。
 */
data class WorkflowRunState(
    val runId: String,
    /** 执行所用的定义快照 id。 */
    val definitionId: String,
    /** 定义内容快照（恢复时不依赖库中定义是否被改版）。 */
    val definition: WorkflowDefinition,
    /** 运行状态。 */
    val status: RunStatus,
    /** 形参实参（模板根 `params`）。 */
    val params: JsonObject,
    /** 运行变量（SetVar 写入；模板根 `vars`）。 */
    val vars: JsonObject,
    /** 各节点运行态（模板根 `nodes`）。 */
    val nodeStates: Map<String, NodeRunState> = emptyMap(),
    /** 超步计数（单调递增，与检查点同事务，防恢复后熔断上限失效）。 */
    val stepCounter: Int = 0,
    /** WAITING_HUMAN 时等待答复的节点 id。 */
    val waitingNodeId: String? = null,
    val createdAtMs: Long,
    val startedAtMs: Long? = null,
    val finishedAtMs: Long? = null,
    /** 终态为 FAILED 时的原因；CANCELLED 时为取消说明。 */
    val message: String? = null,
) {
    /** 快速判定可续跑态。 */
    fun isResumable(): Boolean = status == RunStatus.WAITING_HUMAN || status == RunStatus.CRASHED ||
        status == RunStatus.PENDING

    fun isTerminal(): Boolean = status == RunStatus.SUCCEEDED || status == RunStatus.FAILED ||
        status == RunStatus.CANCELLED

    /** 已成功节点数（事件摘要 / 工具结果渲染）。 */
    val succeededCount: Int get() = nodeStates.values.count { it.status == NodeStatus.SUCCEEDED }

    /** 失败 + 跳过统计。 */
    val failedCount: Int get() = nodeStates.values.count { it.status == NodeStatus.FAILED }

    val skippedCount: Int get() = nodeStates.values.count { it.status == NodeStatus.SKIPPED }
}

/** 运行状态机。 */
enum class RunStatus {
    /** 已创建未启动（崩溃发生在「落盘后 / 循环前」的窗口）。 */
    PENDING,

    /** 执行中（进程重启后残留的 RUNNING 即崩溃判定依据，n8n 纪律）。 */
    RUNNING,

    /** 等待人工答复（不是故障 —— 优雅暂停）。 */
    WAITING_HUMAN,

    SUCCEEDED,
    FAILED,
    CANCELLED,

    /** 引擎启动扫描把残留 RUNNING 重分类后的状态（可 resume）。 */
    CRASHED,
}

/** 节点运行态。 */
data class NodeRunState(
    val status: NodeStatus,
    /** 已尝试次数（重试累计，崩溃回退 PENDING 时保留）。 */
    val attempt: Int = 0,
    /** 成功输出（恢复不重跑的判据；模板 `${nodes.<id>.output}` 寻址）。 */
    val output: JsonElement? = null,
    /** Switch 节点选中的分支（路由决策持久化 —— 恢复后不重判）。 */
    val branch: String? = null,
    val errorText: String? = null,
    /** 终态落在第几个超步（回边循环的重入判据：后完成者可重新激活先完成者）。 */
    val finishedStep: Int? = null,
    val startedAtMs: Long? = null,
    val finishedAtMs: Long? = null,
)

enum class NodeStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED,
}
