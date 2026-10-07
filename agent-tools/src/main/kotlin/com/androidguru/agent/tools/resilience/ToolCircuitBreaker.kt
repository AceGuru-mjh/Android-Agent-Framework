package com.androidguru.agent.tools.resilience

import java.util.concurrent.ConcurrentHashMap

/**
 * 工具熔断器（每工具独立状态机）。
 *
 * - CLOSED：正常放行；连续失败达 [failureThreshold] → OPEN；
 * - OPEN：快速失败；冷却 [openCooldownMs] 后进入 HALF_OPEN；
 * - HALF_OPEN：放行一次探测，成功 → CLOSED，失败 → OPEN（冷却时间指数延长至上限）。
 */
class ToolCircuitBreaker(
    private val failureThreshold: Int = 5,
    private val openCooldownMs: Long = 15_000L,
    private val maxCooldownMs: Long = 120_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    enum class State { CLOSED, OPEN, HALF_OPEN }

    private data class Entry(
        var state: State = State.CLOSED,
        var consecutiveFailures: Int = 0,
        var openedAt: Long = 0L,
        var cooldownMs: Long = 0L,
        var probing: Boolean = false,
    )

    private val entries = ConcurrentHashMap<String, Entry>()

    /** 执行前调用。返回 false 表示熔断中，应快速失败。 */
    fun tryAcquire(toolId: String): Boolean {
        val entry = entry(toolId)
        synchronized(entry) {
            val now = clock()
            when (entry.state) {
                State.CLOSED -> return true
                State.OPEN -> {
                    if (now - entry.openedAt >= entry.cooldownMs) {
                        entry.state = State.HALF_OPEN
                        entry.probing = true
                        return true
                    }
                    return false
                }

                State.HALF_OPEN -> return !entry.probing
            }
        }
    }

    /** 执行成功。 */
    fun recordSuccess(toolId: String) {
        val entry = entry(toolId)
        synchronized(entry) {
            entry.state = State.CLOSED
            entry.consecutiveFailures = 0
            entry.cooldownMs = openCooldownMs
            entry.probing = false
        }
    }

    /** 执行失败。返回 true 表示本次失败触发了熔断打开。 */
    fun recordFailure(toolId: String): Boolean {
        val entry = entry(toolId)
        var opened = false
        synchronized(entry) {
            when (entry.state) {
                State.HALF_OPEN -> {
                    // 探测失败：冷却指数延长后重新打开
                    entry.cooldownMs = (entry.cooldownMs * 2).coerceAtMost(maxCooldownMs)
                    open(entry)
                    opened = true
                }

                else -> {
                    entry.consecutiveFailures++
                    if (entry.consecutiveFailures >= failureThreshold) {
                        open(entry)
                        opened = true
                    }
                }
            }
        }
        return opened
    }

    /**
     * 归还探测槽（中性结果，不计成败）。
     *
     * 修复 issue #10：工具调用的早退路径（钩子阻断 / schema 校验失败 / 限流 /
     * 协程取消）既不算成功也不算失败，但必须释放 HALF_OPEN 探测槽，
     * 否则该工具会永久停留在「探测中」—— 之后所有调用都被误判为熔断。
     */
    fun recordNeutral(toolId: String) {
        val entry = entry(toolId)
        synchronized(entry) {
            if (entry.state == State.HALF_OPEN) entry.probing = false
        }
    }

    fun stateOf(toolId: String): State = synchronized(entry(toolId)) { entry(toolId).state }

    fun reset() = entries.clear()

    private fun open(entry: Entry) {
        entry.state = State.OPEN
        entry.openedAt = clock()
        entry.consecutiveFailures = 0
        entry.probing = false
    }

    private fun entry(toolId: String): Entry = entries.computeIfAbsent(toolId) {
        Entry(cooldownMs = openCooldownMs)
    }
}
