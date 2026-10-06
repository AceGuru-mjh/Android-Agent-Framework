package com.androidguru.agent.tools.hook

import com.androidguru.agent.tools.ToolResult

/**
 * 生命周期钩子事件（对齐 Claude Code 语义的 8 类事件）。
 *
 * 引擎与执行管线在固定插桩点派发事件；只有 [PreToolUse] 可以阻断 / 改写参数，
 * 其余事件仅作通知。
 */
sealed interface HookEvent {

    /** 工具执行前（唯一可介入执行流向的事件）。 */
    data class PreToolUse(val toolId: String, val arguments: String) : HookEvent

    /** 工具执行后。 */
    data class PostToolUse(val toolId: String, val result: ToolResult) : HookEvent

    /** 用户提交输入。 */
    data class UserPromptSubmit(val prompt: String) : HookEvent

    /** 会话开始。 */
    data class SessionStart(val sessionId: String) : HookEvent

    /** 会话结束。 */
    data class SessionEnd(val sessionId: String) : HookEvent

    /** 一轮正常完成。 */
    data class Stop(val sessionId: String, val summary: String) : HookEvent

    /** 上下文压缩前。 */
    data class PreCompact(val sessionId: String, val tokensBefore: Int) : HookEvent
}

/** PreToolUse 的介入决策。 */
sealed interface HookDecision {
    /** 继续。 */
    data object Proceed : HookDecision

    /** 阻断执行，reason 会作为工具结果回给模型。 */
    data class Block(val reason: String) : HookDecision

    /** 改写参数后继续（链式：改写结果传给下一个钩子）。 */
    data class Modify(val arguments: String) : HookDecision
}

/**
 * 编程式钩子。异常被注册表隔离，单个钩子故障不会打断链路。
 */
interface ToolHook {
    /** 钩子名（注销 / 审计用）。 */
    val name: String

    /** 排序权重，小者先执行；同序按注册先后。 */
    val order: Int get() = 0

    suspend fun onEvent(event: HookEvent): HookDecision
}
