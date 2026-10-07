package com.androidguru.agent.shell.policy

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 审批闸门 —— 工具调用与外部 API 的挂起式人机确认通道。
 *
 * 工作方式：
 * - 调用方（执行钩子 / 控制 API）[request] 一个确认，协程**挂起**直到宿主 UI 调用
 *   [respond] 给出裁决，或超时（超时 = DENY，fail-closed）；
 * - 宿主订阅 [requests]（StateFlow）在界面上渲染确认卡；
 * - [Verdict.ALLOW_ALWAYS] 记住「工具 + 首词」组合，未接持久化时只在**本会话内**生效，
 *   [resetSession] 在每个任务开始/结束时清空记忆 —— 权限最小化；
 * - 接入 [decisionStore]（opt-in）后 ALLOW_ALWAYS 跨会话存续：构造时加载
 *   仍有效的授权，授权落盘时受存储侧 TTL 约束；[clearAllMemory] 一键撤销全部
 *   （含落盘部分）；
 * - 拒绝绝不抛异常：调用方拿到 DENY 后向模型回一条可读的失败工具结果，
 *   模型据此**改用其它方式**而不是原样重试。
 *
 * 本组件填补了框架的一个明确缺口：`ToolRisk.HIGH` 的 KDoc 声明「HIGH 风险工具默认
 * 需要会话级确认」，但执行器此前只有熔断与 PreToolUse 钩子，没有内建确认门。
 */
class ApprovalGate(
    /** 审批决定持久化（opt-in）：接入后 ALLOW_ALWAYS 跨会话存续（见 [ApprovalDecisionStore] 安全纪律）。 */
    private val decisionStore: ApprovalDecisionStore? = null,
) {

    data class Request(
        val id: String,
        /** 发起确认的工具 / 能力名（如 terminal_exec、api.fs.write）。 */
        val toolName: String,
        /** 界面标题。 */
        val title: String,
        /** 详细内容（命令文本 / 文件 diff 等）。 */
        val detail: String,
        /** 影响说明（涉及路径、能否撤销）。 */
        val impact: String,
        /** 风险级别。 */
        val level: CommandPolicy.Level,
        val reason: String? = null,
        val canRollback: Boolean = false,
        val rollbackHint: String? = null,
        /**
         * 显式审批记忆键（ALLOW_ALWAYS 的作用域）。
         *
         * 修复 issue #17：旧实现从 [detail] 首词猜测记忆键，fs 类操作（detail 是路径）
         * 会退化为文件名 —— 对 `/home/a/config.txt` 点"本次都允许"会放行任意目录的
         * 同名文件。提供 [memoryKey] 时优先使用（fs 类应传完整规范路径），
         * 未提供时才回退到旧的「工具名:首词」启发式。
         */
        val memoryKey: String? = null,
    )

    enum class Verdict {
        /** 只允许这一次。 */
        ALLOW_ONCE,

        /** 本会话内该组合（工具 + 首词）都允许。 */
        ALLOW_ALWAYS,

        /** 拒绝。 */
        DENY,
    }

    // 修复 issue #21 L-10：ALLOW_ALWAYS 记忆键随请求一起存入 pending 表，
    // respond 时用请求自身的 key，不再读 currentRequest（并发请求互踩时
    // 旧实现会把「本次都允许」记到别的请求头上）
    private val pending = ConcurrentHashMap<String, PendingRequest>()

    private class PendingRequest(
        val deferred: CompletableDeferred<Verdict>,
        val memoryKey: String?,
    )

    private val alwaysAllow = mutableSetOf<String>()
    private val alwaysLock = Any()

    init {
        // 接入持久化时加载仍有效的授权（TTL 外的已被存储侧过滤）
        decisionStore?.loadAllowedKeys()?.let { persisted ->
            synchronized(alwaysLock) { alwaysAllow.addAll(persisted) }
        }
    }

    @Volatile
    var currentRequest: Request? = null
        private set

    private val _requests = MutableStateFlow<Request?>(null)

    /** 当前待确认请求（宿主 UI 的数据源）。 */
    val requests: StateFlow<Request?> = _requests.asStateFlow()

    /** 挂起等待用户裁决；超时返回 [Verdict.DENY]。 */
    suspend fun request(req: Request, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Verdict {
        val key = decisionKey(req)
        synchronized(alwaysLock) {
            if (key != null && alwaysAllow.contains(key)) return Verdict.ALLOW_ALWAYS
        }

        val deferred = CompletableDeferred<Verdict>()
        pending[req.id] = PendingRequest(deferred, key)
        currentRequest = req
        _requests.value = req
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() } ?: Verdict.DENY
        } finally {
            pending.remove(req.id)
            if (currentRequest?.id == req.id) {
                currentRequest = null
                _requests.value = null
            }
        }
    }

    /** 宿主 UI 提交裁决。返回是否有对应等待中的请求。 */
    fun respond(requestId: String, verdict: Verdict): Boolean {
        val entry = pending[requestId] ?: return false
        if (verdict == Verdict.ALLOW_ALWAYS && entry.memoryKey != null) {
            synchronized(alwaysLock) { alwaysAllow.add(entry.memoryKey!!) }
            // 持久化是尽力而为：存储故障不阻断裁决回填（fail-open 到会话内记忆）
            try {
                decisionStore?.persistAllowedKey(entry.memoryKey!!)
            } catch (e: Exception) {
                // 本会话内仍生效，仅不跨会话
            }
        }
        entry.deferred.complete(verdict)
        return true
    }

    /**
     * 清空「本次都允许」的**会话内**记忆（每个任务开始 / 结束时调用）。
     *
     * 接入持久化时落盘授权不受影响（这正是该能力的目的 —— 跨任务免重复确认）；
     * 需要连同落盘授权一并撤销时用 [clearAllMemory]。
     */
    fun resetSession() {
        synchronized(alwaysLock) { alwaysAllow.clear() }
        decisionStore?.loadAllowedKeys()?.let { persisted ->
            synchronized(alwaysLock) { alwaysAllow.addAll(persisted) } // 只剩仍有效的持久授权
        }
    }

    /**
     * 撤销全部授权（含落盘部分）—— 用户「撤销全部允许」入口。
     * 同时清空会话内记忆与持久化存储。
     */
    fun clearAllMemory() {
        synchronized(alwaysLock) { alwaysAllow.clear() }
        try {
            decisionStore?.clear()
        } catch (e: Exception) {
            // 存储故障不影响会话内清空；下次构造时残留授权会随 TTL 自然过期
        }
    }

    /** 取消全部等待中的请求（全部判 DENY）。 */
    fun cancelAll() {
        pending.values.forEach { it.deferred.complete(Verdict.DENY) }
        pending.clear()
        currentRequest = null
        _requests.value = null
    }

    /**
     * 审批记忆的 key：优先用请求显式声明的 [Request.memoryKey]；
     * 否则回退到 `工具名:命令首词` 启发式。BLOCKED 级别返回 null —— 拒绝级决策
     * 永远不进记忆表，不存在「本次都允许」。
     */
    private fun decisionKey(req: Request): String? {
        if (req.level == CommandPolicy.Level.BLOCKED) return null
        req.memoryKey?.let { return it }
        val firstWord = req.detail.trim().split(Regex("\\s+")).firstOrNull()?.substringAfterLast('/') ?: return null
        return "${req.toolName}:$firstWord"
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5 * 60 * 1000L

        fun newId(): String = UUID.randomUUID().toString().take(12)
    }
}
