package com.androidguru.agent.shelltools.hook

import com.androidguru.agent.shell.ShellRuntime
import com.androidguru.agent.shell.audit.AuditLog
import com.androidguru.agent.shell.policy.ApprovalGate
import com.androidguru.agent.shell.policy.CommandPolicy
import com.androidguru.agent.shell.policy.WriteGate
import com.androidguru.agent.shelltools.JsonArgs
import com.androidguru.agent.tools.hook.HookDecision
import com.androidguru.agent.tools.hook.HookEvent
import com.androidguru.agent.tools.hook.ToolHook

/**
 * 审批钩子 —— 工具执行前的统一安全门（PreToolUse 段）。
 *
 * 这是 yl-ai「挂起式审批闸门」在框架语义上的落地：
 *
 * | 工具 | 策略来源 | BLOCKED | CONFIRM | SAFE |
 * |---|---|---|---|---|
 * | terminal_exec / container_exec / job_start | [CommandPolicy] 分级 | 直接拒 | 挂起等人 | 放行 |
 * | terminal_write | [WriteGate] 行级扫描（修复 issue #9） | 直接拒 | 引导改用 terminal_exec | 放行 |
 * | fs_write | 恒 CONFIRM（带内容摘要，记忆键 = 完整路径） | — | 挂起等人 | 放行 |
 * | file_delete | 恒 CONFIRM（可回收，记忆键 = 完整路径） | — | 挂起等人 | 放行 |
 * | 其余 | 不介入 | — | — | 放行 |
 *
 * **拒绝回灌**：DENY 不是异常，而是 [HookDecision.Block]，其 reason 作为工具结果
 * 进入对话 —— 模型读到「用户拒绝…请改用其它方式」后会换道而不是原样重试。
 * 这正是 yl-ai 的设计：「拒绝会回灌给模型让它换做法」。
 *
 * BLOCKED 级别**永不弹审批窗**（fail-closed），同时记入审计。
 */
class ShellApprovalHook(
    private val runtime: ShellRuntime,
    private val autoApproveSafe: Boolean = true,
) : ToolHook {

    override val name = "agsh-approval"

    /** 审批先于审计钩子执行。 */
    override val order = -100

    override suspend fun onEvent(event: HookEvent): HookDecision {
        if (event !is HookEvent.PreToolUse) return HookDecision.Proceed

        when (event.toolId) {
            "terminal_exec", "container_exec", "job_start" -> {
                val command = JsonArgs.parse(event.arguments).str("command")?.trim()
                    ?: return HookDecision.Proceed
                return gateCommand(event.toolId, command)
            }

            "fs_write" -> {
                val args = JsonArgs.parse(event.arguments)
                val path = args.str("path") ?: return HookDecision.Proceed
                val content = args.str("content").orEmpty()
                val append = args.bool("append") ?: false
                val verdict = runtime.approval.request(
                    ApprovalGate.Request(
                        id = ApprovalGate.newId(),
                        toolName = "fs_write",
                        title = "写入文件",
                        detail = path,
                        impact = buildString {
                            append("${content.length} 字符，${if (append) "追加" else "覆盖"}")
                            val preview = content.lines().take(8).joinToString("\n")
                            if (preview.isNotBlank()) append("\n内容预览：\n$preview")
                        },
                        level = CommandPolicy.Level.CONFIRM,
                        canRollback = false,
                        // 修复 issue #17：记忆键用完整路径，避免「本次都允许」退化为文件名
                        memoryKey = "fs_write:${java.io.File(path).absolutePath}",
                    ),
                )
                return verdictToDecision("fs_write", path, verdict)
            }

            "file_delete" -> {
                val path = JsonArgs.parse(event.arguments).str("path") ?: return HookDecision.Proceed
                val verdict = runtime.approval.request(
                    ApprovalGate.Request(
                        id = ApprovalGate.newId(),
                        toolName = "file_delete",
                        title = "删除文件",
                        detail = path,
                        impact = "文件会移入回收目录（可恢复）",
                        level = CommandPolicy.Level.CONFIRM,
                        canRollback = true,
                        rollbackHint = "删除后可从 trash 目录找回",
                        // 修复 issue #17：记忆键用完整路径
                        memoryKey = "file_delete:${java.io.File(path).absolutePath}",
                    ),
                )
                return verdictToDecision("file_delete", path, verdict)
            }

            // 修复 issue #9：terminal_write 旧实现完全绕过策略/审批/审计，
            // 被拒命令可以换通道执行。现在行级扫描：BLOCKED 拦截、
            // 命令形态的 CONFIRM 引导走 terminal_exec，纯交互应答保持畅通
            "terminal_write" -> {
                val input = JsonArgs.parse(event.arguments).str("input") ?: return HookDecision.Proceed
                val result = WriteGate.gate(input, AuditLog.Source.AI, runtime.policy, runtime.audit)
                return if (result.allowed) {
                    HookDecision.Proceed
                } else {
                    HookDecision.Block(result.reason!!)
                }
            }

            else -> return HookDecision.Proceed
        }
    }

    private suspend fun gateCommand(toolId: String, command: String): HookDecision {
        val decision = runtime.policy.decide(command)
        return when (decision.level) {
            CommandPolicy.Level.BLOCKED -> {
                runtime.audit.recordBlocked(AuditLog.Source.AI, command, decision.reason)
                HookDecision.Block("已拒绝执行（安全策略硬拦截）：${decision.reason}\n" +
                    "这条命令不会因为重试而变化，请改用安全的方式完成同样的事。")
            }

            CommandPolicy.Level.CONFIRM -> {
                val verdict = runtime.approval.request(
                    ApprovalGate.Request(
                        id = ApprovalGate.newId(),
                        toolName = toolId,
                        title = if (toolId == "job_start") "启动后台作业" else "执行命令",
                        detail = command,
                        impact = decision.targets.takeIf { it.isNotEmpty() }
                            ?.let { "涉及路径：${it.joinToString("、")}" }
                            ?: "该命令可能修改系统状态",
                        level = decision.level,
                        reason = decision.reason,
                    ),
                )
                verdictToDecision(toolId, command, verdict)
            }

            CommandPolicy.Level.SAFE -> {
                if (autoApproveSafe) {
                    HookDecision.Proceed
                } else {
                    val verdict = runtime.approval.request(
                        ApprovalGate.Request(
                            id = ApprovalGate.newId(),
                            toolName = toolId,
                            title = "执行命令",
                            detail = command,
                            impact = "只读命令",
                            level = CommandPolicy.Level.SAFE,
                            reason = decision.reason,
                        ),
                    )
                    verdictToDecision(toolId, command, verdict)
                }
            }
        }
    }

    private fun verdictToDecision(toolId: String, detail: String, verdict: ApprovalGate.Verdict): HookDecision =
        when (verdict) {
            ApprovalGate.Verdict.ALLOW_ONCE, ApprovalGate.Verdict.ALLOW_ALWAYS -> {
                runtime.audit.recordCommand(
                    AuditLog.Source.AI, detail,
                    level = "APPROVED_${verdict.name}",
                    approval = verdict.name,
                )
                HookDecision.Proceed
            }
            ApprovalGate.Verdict.DENY -> {
                runtime.audit.recordCommand(
                    AuditLog.Source.AI, detail,
                    level = "DENIED",
                    approval = "DENIED",
                )
                HookDecision.Block(
                    "用户拒绝执行这条操作。请不要重复提交它；改用其它方式，" +
                        "或先向用户解释为什么需要它、得到同意后再试。",
                )
            }
        }
}
