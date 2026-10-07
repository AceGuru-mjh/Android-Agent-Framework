package com.androidguru.agent.eval

import com.androidguru.agent.core.engine.AgentEvent

/**
 * Agent 执行轨迹：评估的原始材料。
 *
 * 由 [AgentTraceRecorder] 从引擎事件流直接录制 —— 评估不侵入引擎，
 * 同一份轨迹可被多个校验器反复重放（改判据不重跑任务）。
 */
data class TraceRecord(
    /** 一次运行的全部工具调用事件（按发生顺序）。 */
    val toolCalls: List<ToolCallEvent>,
    /** 最终回复文本（Complete 时非空）。 */
    val finalText: String?,
    /** 是否正常收尾（Complete 事件）。 */
    val completed: Boolean,
    /** 总迭代轮数。 */
    val iterations: Int,
    /** 期间是否触发过循环护栏。 */
    val loopDetected: Boolean,
    /** 期间触发反思的次数。 */
    val reflections: Int,
    /** 期间触发跑偏告警的次数。 */
    val driftWarnings: Int,
    /** 总工具调用次数。 */
    val totalToolCalls: Int,
    /** 运行时长（ms）。 */
    val durationMs: Long,
) {
    /** 按工具名过滤的调用序列（快捷访问）。 */
    fun callsOf(tool: String): List<ToolCallEvent> = toolCalls.filter { it.name == tool }
}

/** 轨迹中的单次工具调用。 */
data class ToolCallEvent(
    /** 发生在第几轮迭代（1 起）。 */
    val iteration: Int,
    val callId: String,
    val name: String,
    /** 原始参数 JSON 文本。 */
    val arguments: String,
    val ok: Boolean,
    /** 输出摘要（截断，供失败原因展示）。 */
    val outputExcerpt: String,
    val durationMs: Long,
)

/**
 * 轨迹录制器：collect 引擎事件流，收尾产出 [TraceRecord]。
 *
 * ```kotlin
 * val recorder = AgentTraceRecorder()
 * engine.execute(UserInput(task.instruction)).collect { recorder.onEvent(it) }
 * val trace = recorder.build()
 * ```
 */
class AgentTraceRecorder {

    private val calls = mutableListOf<ToolCallEvent>()
    private var finalText: String? = null
    private var completed = false
    private var currentIteration = 0
    private var totalIterations = 0
    private var loopDetected = false
    private var reflections = 0
    private var driftWarnings = 0
    private var runStartedAt = System.currentTimeMillis()

    /** 调用参数缓存：ToolCallStart 带参数、ToolCallComplete 不带 —— 以 start 为准。 */
    private val argumentsById = HashMap<String, String>()

    fun reset() {
        calls.clear()
        finalText = null
        completed = false
        currentIteration = 0
        totalIterations = 0
        loopDetected = false
        reflections = 0
        driftWarnings = 0
        runStartedAt = System.currentTimeMillis()
        argumentsById.clear()
    }

    fun onEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.IterationStart -> currentIteration = event.iteration

            is AgentEvent.ToolCallStart -> argumentsById[event.callId] = event.arguments

            is AgentEvent.ToolCallComplete -> {
                calls += ToolCallEvent(
                    iteration = currentIteration,
                    callId = event.callId,
                    name = event.toolName,
                    arguments = argumentsById.remove(event.callId) ?: "",
                    ok = event.result.ok,
                    outputExcerpt = event.result.content.take(200),
                    durationMs = event.durationMs,
                )
            }

            is AgentEvent.Complete -> {
                completed = true
                finalText = event.summary
                totalIterations = event.totalIterations
            }

            is AgentEvent.LoopDetected -> loopDetected = true
            is AgentEvent.ReflectionTriggered -> reflections++
            is AgentEvent.DriftSuspected -> driftWarnings++
            else -> Unit
        }
    }

    fun build(): TraceRecord = TraceRecord(
        toolCalls = calls.toList(),
        finalText = finalText,
        completed = completed,
        iterations = if (totalIterations > 0) totalIterations else currentIteration,
        loopDetected = loopDetected,
        reflections = reflections,
        driftWarnings = driftWarnings,
        totalToolCalls = calls.size,
        durationMs = System.currentTimeMillis() - runStartedAt,
    )
}
