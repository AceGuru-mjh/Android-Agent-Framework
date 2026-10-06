package com.androidguru.agent.tools

import com.androidguru.agent.tools.hook.HookDecision
import com.androidguru.agent.tools.hook.HookEvent
import com.androidguru.agent.tools.hook.HookRegistry
import com.androidguru.agent.tools.resilience.ToolCircuitBreaker
import com.androidguru.agent.tools.resilience.ToolRunPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 工具执行器。
 *
 * 默认实现 [DefaultToolExecutor] 内置五段管线：
 *
 * ```
 * 查找(带相近 id 建议) → 熔断检查 → PreToolUse 钩子(可阻断/改参)
 *   → Schema 校验 → 执行(超时/退避重试/限流) → PostToolUse 钩子
 * ```
 *
 * 错误一律折叠为 [ToolResult.failure]，不向上抛业务异常；
 * [CancellationException] 永远向上传播（取消纪律）。
 */
interface ToolExecutor {

    suspend fun execute(toolId: String, arguments: String, callId: String = "call_${System.nanoTime()}"): ToolResult

    /** 熔断复位（任务切换时调用）。 */
    suspend fun resetBreakers() {}
}

/**
 * 执行统计（宿主可用于观测面板）。
 */
data class ToolUsageSnapshot(
    val toolId: String,
    val totalCalls: Long = 0,
    val successCalls: Long = 0,
    val failureCalls: Long = 0,
    val totalDurationMs: Long = 0,
) {
    val avgDurationMs: Long get() = if (totalCalls == 0L) 0 else totalDurationMs / totalCalls
}

class DefaultToolExecutor(
    private val registry: ToolRegistry,
    private val hooks: HookRegistry = HookRegistry(),
    /** 覆盖全部工具的默认策略；工具级策略通过 [policies] 按 id 覆盖。 */
    private val defaultPolicy: ToolRunPolicy = ToolRunPolicy(),
    private val policies: Map<String, ToolRunPolicy> = emptyMap(),
    private val breaker: ToolCircuitBreaker = ToolCircuitBreaker(),
    /** 输出钳制长度（防止超长工具输出撑爆上下文）。 */
    private val maxOutputChars: Int = 16_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : ToolExecutor {

    private val usage = ConcurrentHashMap<String, UsageCounter>()

    private class UsageCounter {
        var total = AtomicLong(0)
        var success = AtomicLong(0)
        var failure = AtomicLong(0)
        var duration = AtomicLong(0)
    }

    override suspend fun execute(toolId: String, arguments: String, callId: String): ToolResult {
        val startedAt = clock()

        // 1. 查找
        val tool = registry.getTool(toolId)
        if (tool == null) {
            val suggestion = suggestTool(toolId)
            val message = buildString {
                append("未知工具: $toolId")
                if (suggestion != null) append("，你是否想调用 $suggestion ?")
            }
            return record(toolId, startedAt, ToolResult.failure(message, ToolErrorCode.NOT_FOUND, suggestion = suggestion))
        }

        // 2. 熔断检查
        if (!breaker.tryAcquire(toolId)) {
            return record(
                toolId, startedAt,
                ToolResult.failure(
                    "工具 $toolId 熔断中（近期连续失败），请改用其他方案",
                    ToolErrorCode.UNAVAILABLE,
                    suggestion = "本工具暂时不可用，不要立即重试",
                ),
            )
        }

        // 3. PreToolUse 钩子（可阻断 / 改参）
        var effectiveArgs = arguments
        when (val decision = hooks.dispatch(HookEvent.PreToolUse(toolId, effectiveArgs))) {
            is HookDecision.Block -> {
                return record(
                    toolId, startedAt,
                    ToolResult.failure("工具调用被钩子阻断: ${decision.reason}", ToolErrorCode.PERMISSION),
                )
            }

            is HookDecision.Modify -> effectiveArgs = decision.arguments
            HookDecision.Proceed -> Unit
        }

        // 4. Schema 校验（声明过才校验；无 schema 的工具直通）
        val schemaErrors = tool.parameters.validate(effectiveArgs)
        if (schemaErrors.isNotEmpty()) {
            return record(
                toolId, startedAt,
                ToolResult.failure(
                    "参数校验失败: ${schemaErrors.joinToString("; ")}",
                    ToolErrorCode.VALIDATION,
                    suggestion = "请根据工具 schema 修正参数后重试",
                ),
            )
        }

        // 5. 执行（超时 + 确定性退避重试 + 限流）
        val policy = policies[toolId] ?: defaultPolicy
        val rateLimited = rateLimiter.tryAcquire(toolId, policy.rateLimitPerMinute)
        if (!rateLimited) {
            return record(
                toolId, startedAt,
                ToolResult.failure(
                    "工具 $toolId 触发限流（${policy.rateLimitPerMinute}/分钟），请稍后再试",
                    ToolErrorCode.RATE_LIMITED,
                ),
            )
        }

        val result = executeWithPolicy(tool, effectiveArgs, callId, policy)

        // 6. 熔断记账（业务失败也计入熔断）
        if (result.ok) breaker.recordSuccess(toolId) else breaker.recordFailure(toolId)

        // 7. PostToolUse 钩子（通知性质）
        hooks.dispatch(HookEvent.PostToolUse(toolId, result))

        return record(toolId, startedAt, result)
    }

    private suspend fun executeWithPolicy(
        tool: AgentTool,
        arguments: String,
        callId: String,
        policy: ToolRunPolicy,
    ): ToolResult {
        var attempt = 0
        var lastResult: ToolResult = ToolResult.failure("未执行", ToolErrorCode.UNKNOWN)
        while (true) {
            // 注意 catch 顺序：TimeoutCancellationException 是 CancellationException 的子类，必须先于取消捕获
            lastResult = try {
                if (policy.timeoutMs > 0) {
                    withTimeout(policy.timeoutMs) { tool.execute(ToolRequest(callId, tool.id, arguments)) }
                } else {
                    tool.execute(ToolRequest(callId, tool.id, arguments))
                }
            } catch (te: kotlinx.coroutines.TimeoutCancellationException) {
                ToolResult.failure(
                    "工具 ${tool.id} 执行超时（${policy.timeoutMs}ms, 第 ${attempt + 1} 次）",
                    ToolErrorCode.TIMEOUT,
                    suggestion = "若必须重试，请缩小任务规模",
                )
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                ToolResult.failure("工具 ${tool.id} 执行异常: ${e.message ?: e.javaClass.simpleName}", ToolErrorCode.INTERNAL)
            }
            if (lastResult.ok) return clamp(lastResult)

            val retriable = lastResult.error?.code in RETRIABLE_CODES
            if (attempt >= policy.maxRetries || !retriable) return clamp(lastResult)
            delay(policy.retryDelaysMs[attempt])
            attempt++
        }
    }

    private fun clamp(result: ToolResult): ToolResult {
        if (result.content.length <= maxOutputChars) return result
        val truncated = result.content.take(maxOutputChars)
        val note = "\n…（输出超长，已截断 ${result.content.length - maxOutputChars} 字符）"
        return result.copy(content = truncated + note)
    }

    private fun record(toolId: String, startedAt: Long, result: ToolResult): ToolResult {
        val counter = usage.computeIfAbsent(toolId) { UsageCounter() }
        counter.total.incrementAndGet()
        if (result.ok) counter.success.incrementAndGet() else counter.failure.incrementAndGet()
        counter.duration.addAndGet((clock() - startedAt).coerceAtLeast(0))
        return result
    }

    /** 相近工具 id 建议（编辑距离 + 前缀匹配）。 */
    private fun suggestTool(toolId: String): String? {
        val all = registry.getAllTools().map { it.id }
        if (all.isEmpty()) return null
        // 1) 前缀包含
        all.firstOrNull { it.startsWith(toolId) || toolId.startsWith(it) }?.let { return it }
        // 2) 最小编辑距离
        return all.minByOrNull { editDistance(toolId, it) }
            ?.takeIf { editDistance(toolId, it) <= (toolId.length / 2).coerceAtLeast(2) }
    }

    private fun editDistance(a: String, b: String): Int {
        val dp = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) dp[i][0] = i
        for (j in 0..b.length) dp[0][j] = j
        for (i in 1..a.length) {
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[i][j] = minOf(dp[i - 1][j] + 1, dp[i][j - 1] + 1, dp[i - 1][j - 1] + cost)
            }
        }
        return dp[a.length][b.length]
    }

    fun usageSnapshot(): List<ToolUsageSnapshot> = usage.map { (id, c) ->
        ToolUsageSnapshot(
            toolId = id,
            totalCalls = c.total.get(),
            successCalls = c.success.get(),
            failureCalls = c.failure.get(),
            totalDurationMs = c.duration.get(),
        )
    }

    override suspend fun resetBreakers() = breaker.reset()

    // ------------------------------------------------------------------

    private object rateLimiter {
        private val windows = ConcurrentHashMap<String, ArrayDeque<Long>>()
        private val lock = Any()

        fun tryAcquire(toolId: String, limitPerMinute: Int): Boolean {
            if (limitPerMinute <= 0) return true
            val now = System.currentTimeMillis()
            synchronized(lock) {
                val window = windows.computeIfAbsent(toolId) { ArrayDeque() }
                while (window.isNotEmpty() && now - window.first() > 60_000L) window.removeFirst()
                if (window.size >= limitPerMinute) return false
                window.addLast(now)
                return true
            }
        }
    }

    private companion object {
        val RETRIABLE_CODES = setOf(
            ToolErrorCode.TIMEOUT,
            ToolErrorCode.RATE_LIMITED,
            ToolErrorCode.UNAVAILABLE,
        )
    }
}
