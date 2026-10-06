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
 * - [Verdict.ALLOW_ALWAYS] 记住「工具 + 首词」组合（本会话内同组合不再询问），
 *   [resetSession] 在每个任务开始/结束时清空记忆 —— 权限最小化；
 * - 拒绝绝不抛异常：调用方拿到 DENY 后向模型回一条可读的失败工具结果，
 *   模型据此**改用其它方式**而不是原样重试。
 *
 * 本组件填补了框架的一个明确缺口：`ToolRisk.HIGH` 的 KDoc 声明「HIGH 风险工具默认
 * 需要会话级确认」，但执行器此前只有熔断与 PreToolUse 钩子，没有内建确认门。
 */
class ApprovalGate {

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
    )

    enum class Verdict {
        /** 只允许这一次。 */
        ALLOW_ONCE,

        /** 本会话内该组合（工具 + 首词）都允许。 */
        ALLOW_ALWAYS,

        /** 拒绝。 */
        DENY,
    }

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Verdict>>()

    private val alwaysAllow = mutableSetOf<String>()
    private val alwaysLock = Any()

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
        pending[req.id] = deferred
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
        val deferred = pending[requestId] ?: return false
        if (verdict == Verdict.ALLOW_ALWAYS) {
            currentRequest?.takeIf { it.id == requestId }?.let { req ->
                decisionKey(req)?.let { key ->
                    synchronized(alwaysLock) { alwaysAllow.add(key) }
                }
            }
        }
        deferred.complete(verdict)
        return true
    }

    /** 清空「本次都允许」记忆（每个任务开始 / 结束时调用）。 */
    fun resetSession() {
        synchronized(alwaysLock) { alwaysAllow.clear() }
    }

    /** 取消全部等待中的请求（全部判 DENY）。 */
    fun cancelAll() {
        pending.values.forEach { it.complete(Verdict.DENY) }
        pending.clear()
        currentRequest = null
        _requests.value = null
    }

    /**
     * 审批记忆的 key：`工具名:命令首词`。BLOCKED 级别返回 null —— 拒绝级决策
     * 永远不进记忆表，不存在「本次都允许」。
     */
    private fun decisionKey(req: Request): String? {
        if (req.level == CommandPolicy.Level.BLOCKED) return null
        val firstWord = req.detail.trim().split(Regex("\\s+")).firstOrNull()?.substringAfterLast('/') ?: return null
        return "${req.toolName}:$firstWord"
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5 * 60 * 1000L

        fun newId(): String = UUID.randomUUID().toString().take(12)
    }
}
