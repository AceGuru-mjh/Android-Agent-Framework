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

    // ---- 长程任务 ----

    /**
     * 迭代预算耗尽（长程任务可续跑信号）。
     *
     * 与普通 [Error] 区分：这是**可续跑的暂停点** —— 宿主可调用
     * [AgentEngine.continueExecution] 追加预算从断点继续，记忆与进度完整保留。
     * 之后仍会附带一条 recoverable 的 [Error] 事件（兼容只监听 Error 的旧宿主）。
     */
    data class BudgetExhausted(
        val iterationsUsed: Int,
        val maxIterations: Int,
        val totalToolCalls: Int,
        val durationMs: Long,
    ) : AgentEvent

    /**
     * 循环护栏触发：同一工具 + 完全相同参数的调用在窗口内第 N 次出现。
     *
     * 护栏会在回填给模型的工具结果上附加“改变策略”的建议文本，
     * 帮助模型跳出重复调用死循环（业界长程任务实测的高频失败模式）。
     */
    data class LoopDetected(
        val toolName: String,
        val repeatedCount: Int,
        val arguments: String,
    ) : AgentEvent

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
