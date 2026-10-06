package com.androidguru.agent.shelltools

import com.androidguru.agent.shelltools.tools.DeviceFeatureTools
import com.androidguru.agent.shelltools.tools.FsTools
import com.androidguru.agent.shelltools.tools.HttpGetTool
import com.androidguru.agent.shelltools.tools.JobTools
import com.androidguru.agent.shelltools.tools.TaskFinishTool
import com.androidguru.agent.shelltools.tools.TerminalTools
import com.androidguru.agent.shell.ShellRuntime
import com.androidguru.agent.shell.policy.ApprovalGate
import com.androidguru.agent.shell.policy.CommandPolicy
import com.androidguru.agent.tools.resilience.ToolRunPolicy
import com.androidguru.agent.shelltools.hook.ShellApprovalHook
import com.androidguru.agent.shelltools.hook.ShellAuditHook
import com.androidguru.agent.tools.AgentTool
import com.androidguru.agent.tools.ToolRegistry

/**
 * 终端工具集 —— yl-ai 的 19 个正交工具在本框架工具系统上的完整落地。
 *
 * 用法（三行接入）：
 *
 * ```kotlin
 * val runtime = ShellRuntime.create(File("data/shell"))
 * val shell = ShellToolSet(runtime)
 * shell.installInto(registry)
 * val executor = DefaultToolExecutor(registry, hooks, policies = shell.recommendedPolicies())
 * ```
 *
 * 安全语义：
 * - **审批闸门**（[ShellApprovalHook]）在 PreToolUse 段工作：BLOCKED 硬拒、
 *   CONFIRM 挂起等人裁决、拒绝回灌给模型让它改道 —— 框架此前缺失的
 *   `ToolRisk.HIGH` 会话级确认门由此补齐；
 * - **审计钩子**（[ShellAuditHook]）在 PostToolUse / Stop 段记账 ——
 *   「外部模型不能绕过用户与审计」。
 *
 * 工具自身保持纯粹（只做能力，不做安全决策），安全全部收敛在钩子层，
 * 与本地控制 API 共用同一套 [CommandPolicy] + [ApprovalGate]。
 */
class ShellToolSet(
    private val runtime: ShellRuntime,
    /** 平台设备能力（Android 宿主注入真实现；桌面/服务端用 [DeviceTools.NONE]）。 */
    val device: DeviceTools = DeviceTools.NONE,
    /** 只读命令是否免确认直接放行。 */
    val autoApproveSafe: Boolean = true,
    /** terminal_exec 默认超时。 */
    val defaultTimeoutMs: Long = 30_000L,
) {

    /** 工具 id 常量（与 yl-ai 保持同名，模型迁移零成本）。 */
    object ToolIds {
        const val TERMINAL_EXEC = "terminal_exec"
        const val TERMINAL_WRITE = "terminal_write"
        const val FS_READ = "fs_read"
        const val FS_WRITE = "fs_write"
        const val FS_LIST = "fs_list"
        const val FILE_DELETE = "file_delete"
        const val HTTP_GET = "http_get"
        const val CONTAINER_EXEC = "container_exec"
        const val APP_LIST = "app_list"
        const val APP_LAUNCH = "app_launch"
        const val OPEN_URL = "open_url"
        const val DEVICE_INFO = "device_info"
        const val CLIPBOARD_READ = "clipboard_read"
        const val CLIPBOARD_WRITE = "clipboard_write"
        const val JOB_START = "job_start"
        const val JOB_LIST = "job_list"
        const val JOB_GET = "job_get"
        const val JOB_STOP = "job_stop"
        const val TASK_FINISH = "task_finish"

        val ALL = listOf(
            TERMINAL_EXEC, TERMINAL_WRITE, FS_READ, FS_WRITE, FS_LIST, FILE_DELETE,
            HTTP_GET, CONTAINER_EXEC, APP_LIST, APP_LAUNCH, OPEN_URL, DEVICE_INFO,
            CLIPBOARD_READ, CLIPBOARD_WRITE, JOB_START, JOB_LIST, JOB_GET, JOB_STOP,
            TASK_FINISH,
        )
    }

    /** 构造全部工具（顺序即模型工具清单顺序）。 */
    fun tools(): List<AgentTool> = listOf(
        TerminalTools.terminalExec(runtime, defaultTimeoutMs),
        TerminalTools.terminalWrite(runtime),
        TerminalTools.containerExec(runtime),
        FsTools.read(runtime),
        FsTools.write(runtime),
        FsTools.list(runtime),
        FsTools.delete(runtime),
        HttpGetTool(runtime),
        DeviceFeatureTools.appList(device),
        DeviceFeatureTools.appLaunch(device),
        DeviceFeatureTools.openUrl(device),
        DeviceFeatureTools.deviceInfo(device),
        DeviceFeatureTools.clipboardRead(device),
        DeviceFeatureTools.clipboardWrite(device),
        JobTools.start(runtime),
        JobTools.list(runtime),
        JobTools.get(runtime),
        JobTools.stop(runtime),
        TaskFinishTool(),
    )

    /** 注册进 [ToolRegistry]（重复 id 拒绝策略：启动期冲突尽早暴露）。 */
    fun installInto(registry: ToolRegistry) {
        tools().forEach { registry.register(it) }
        runtime.audit.recordSetting("shell_tools.install", "tools=${ToolIds.ALL.size}")
    }

    /** 与 [ToolIds] 对应的执行策略建议（宿主构造 DefaultToolExecutor 时传入）。 */
    fun recommendedPolicies(): Map<String, ToolRunPolicy> = mapOf(
        // 模型可传 timeout_ms 到 300s，执行器超时必须放得更宽（内部还有一层细粒度超时）
        ToolIds.TERMINAL_EXEC to ToolRunPolicy.LONG.copy(timeoutMs = 320_000L),
        ToolIds.TERMINAL_WRITE to ToolRunPolicy(timeoutMs = 15_000L),
        ToolIds.CONTAINER_EXEC to ToolRunPolicy(timeoutMs = 620_000L, retryDelaysMs = listOf(2_000L)),
        ToolIds.FS_READ to ToolRunPolicy.QUICK,
        ToolIds.FS_WRITE to ToolRunPolicy.QUICK,
        ToolIds.FS_LIST to ToolRunPolicy.QUICK,
        ToolIds.FILE_DELETE to ToolRunPolicy.QUICK,
        ToolIds.HTTP_GET to ToolRunPolicy(timeoutMs = 90_000L, retryDelaysMs = listOf(1_000L, 2_000L)),
        ToolIds.JOB_START to ToolRunPolicy.QUICK,
        ToolIds.JOB_LIST to ToolRunPolicy.QUICK,
        ToolIds.JOB_GET to ToolRunPolicy(timeoutMs = 90_000L),
        ToolIds.JOB_STOP to ToolRunPolicy(timeoutMs = 15_000L),
    )

    /** 审批钩子（挂到 DefaultToolExecutor 的 hooks）。 */
    fun approvalHook(): ShellApprovalHook = ShellApprovalHook(
        runtime = runtime,
        autoApproveSafe = autoApproveSafe,
    )

    /** 审计钩子。 */
    fun auditHook(): ShellAuditHook = ShellAuditHook(runtime = runtime)
}
