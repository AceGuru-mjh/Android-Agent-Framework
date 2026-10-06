package com.androidguru.agent.core.engine

import com.androidguru.agent.llm.Usage
import com.androidguru.agent.tools.ToolResult

/**
 * Agent 事件流 —— 引擎对外的唯一通道。
 *
 * 宿主 UI / 日志 / 遥测按需 collect，无需理解引擎内部。
 * 事件按阶段分组：思考 / 执行 / 回复 / 交互 / 韧性 / 终态。
 */
sealed interface AgentEvent {

    // ---- 循环状态 ----

    /** 一轮 ReAct 迭代开始。 */
    data class IterationStart(val iteration: Int) : AgentEvent

    // ---- 思考 ----

    /** 推理内容增量（原生 reasoning 模型才有）。 */
    data class ThinkingChunk(val text: String) : AgentEvent

    // ---- 回复 ----

    /** 回复文本增量。 */
    data class ResponseChunk(val text: String) : AgentEvent

    // ---- 工具执行 ----

    data class ToolCallStart(
        val callId: String,
        val toolName: String,
        val arguments: String,
    ) : AgentEvent

    data class ToolCallComplete(
        val callId: String,
        val toolName: String,
        val result: ToolResult,
        val durationMs: Long,
    ) : AgentEvent

    // ---- 用量与韧性 ----

    data class UsageUpdated(val usage: Usage) : AgentEvent

    /** LLM 瞬时错误退避重试（过程可见性，区别于终态 Error）。 */
    data class LlmRetryScheduled(val attempt: Int, val delayMs: Long, val reason: String) : AgentEvent

    // ---- 人机交互 ----

    /**
     * 引擎请求用户输入（由内置 ask_user 工具触发）。
     * 宿主通过 [AgentEngine.submitUserInput] 回复。
     */
    data class UserInputRequired(val prompt: String, val timeoutMs: Long) : AgentEvent

    // ---- 终态 ----

    data class Error(val message: String, val recoverable: Boolean) : AgentEvent

    data class Complete(
        val summary: String,
        val totalIterations: Int,
        val totalToolCalls: Int,
        val durationMs: Long,
    ) : AgentEvent

    /** 用户主动中止。 */
    data object Aborted : AgentEvent
}
