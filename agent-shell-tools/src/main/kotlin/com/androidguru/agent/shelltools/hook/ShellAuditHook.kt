package com.androidguru.agent.shelltools.hook

import com.androidguru.agent.shell.ShellRuntime
import com.androidguru.agent.shell.audit.AuditLog
import com.androidguru.agent.tools.hook.HookDecision
import com.androidguru.agent.tools.hook.HookEvent
import com.androidguru.agent.tools.hook.ToolHook

/**
 * 审计钩子 —— 工具执行与会话轨迹的全量记账。
 *
 * 记账点（复用框架钩子的全部插桩位）：
 * - PostToolUse：每次工具调用（含结果 ok / 失败码 / 耗时）；
 * - Stop：任务正常收束（summary 进 ai_task 事件）；
 * - SessionStart / SessionEnd：会话边界。
 *
 * 契约：审计绝不抛异常、绝不阻断执行（PostToolUse 等通知性事件本来就是只读的；
 * 异常由 HookRegistry 隔离）。
 */
class ShellAuditHook(
    private val runtime: ShellRuntime,
) : ToolHook {

    override val name = "agsh-audit"

    /** 审计在审批之后（order 更大）。 */
    override val order = 100

    override suspend fun onEvent(event: HookEvent): HookDecision {
        when (event) {
            is HookEvent.PostToolUse -> {
                val errCode = event.result.error?.code?.name
                runtime.audit.record(
                    AuditLog.TYPE_TOOL,
                    AuditLog.Source.AI,
                    linkedMapOf(
                        "toolId" to event.toolId,
                        "ok" to event.result.ok.toString(),
                        "errorCode" to errCode,
                    ).filterValues { it != null }.mapValues { it.value!! },
                )
            }

            is HookEvent.Stop -> {
                runtime.audit.recordAiTask(event.summary, "STOP", 0, 0)
            }

            is HookEvent.SessionStart -> runtime.audit.recordSetting("session.start", event.sessionId)
            is HookEvent.SessionEnd -> runtime.audit.recordSetting("session.end", event.sessionId)
            else -> Unit
        }
        return HookDecision.Proceed
    }
}
