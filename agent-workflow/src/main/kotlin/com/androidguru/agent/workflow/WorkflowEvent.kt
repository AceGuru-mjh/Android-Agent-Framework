package com.androidguru.agent.workflow

/**
 * 工作流事件流 —— 引擎对外唯一通道（宿主 UI / 日志 / 遥测按需 collect）。
 *
 * 最小集对齐业界模式（LangGraph StreamMode.tasks / XState inspection）：
 * 运行起止 + 节点起止 + 等待 / 恢复。终态事件（Succeeded / Failed / Cancelled）
 * 结束 Flow；WAITING_HUMAN 以 [RunWaiting] 结束 Flow（待 resume）。
 */
sealed interface WorkflowEvent {
    val runId: String

    data class RunStarted(
        override val runId: String,
        val definitionId: String,
        val stepCounterOffset: Int = 0,
    ) : WorkflowEvent

    data class NodeStarted(
        override val runId: String,
        val nodeId: String,
        val attempt: Int,
        val description: String,
    ) : WorkflowEvent

    data class NodeCompleted(
        override val runId: String,
        val nodeId: String,
        val attempt: Int,
        val durationMs: Long,
        val outputPreview: String,
    ) : WorkflowEvent

    /** 节点一次尝试失败。[willRetry] = true 时宿主可渲染重试倒计时。 */
    data class NodeFailed(
        override val runId: String,
        val nodeId: String,
        val attempt: Int,
        val error: String,
        val willRetry: Boolean,
        val onError: NodeOnError,
    ) : WorkflowEvent

    /** 运行暂停等待人工（Flow 就此结束，[WorkflowEngine.resume] 恢复）。 */
    data class RunWaiting(
        override val runId: String,
        val nodeId: String,
        val prompt: String,
    ) : WorkflowEvent

    data class RunResumed(
        override val runId: String,
        val nodeId: String,
    ) : WorkflowEvent

    data class RunSucceeded(
        override val runId: String,
        val definitionId: String,
        val totalSteps: Int,
        val durationMs: Long,
        val summary: String,
    ) : WorkflowEvent

    data class RunFailed(
        override val runId: String,
        val definitionId: String,
        val failedNodeId: String?,
        val error: String,
        val durationMs: Long,
    ) : WorkflowEvent

    data class RunCancelled(
        override val runId: String,
        val reason: String,
    ) : WorkflowEvent
}
