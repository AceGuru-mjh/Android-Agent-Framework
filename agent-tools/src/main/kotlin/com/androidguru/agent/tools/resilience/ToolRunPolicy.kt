package com.androidguru.agent.tools.resilience

/**
 * 工具运行策略：单次执行的确定性护栏。
 *
 * - [timeoutMs]：单次执行超时（0 = 不限时）；
 * - [retryDelaysMs]：确定性退避阶梯，如 [1000, 2000, 4000] 表示最多重试 3 次；
 * - [rateLimitPerMinute]：每分钟最大执行次数（0 = 不限）。
 */
data class ToolRunPolicy(
    val timeoutMs: Long = 30_000L,
    val retryDelaysMs: List<Long> = emptyList(),
    val rateLimitPerMinute: Int = 0,
) {
    val maxRetries: Int get() = retryDelaysMs.size

    companion object {
        /** 快速只读操作。 */
        val QUICK = ToolRunPolicy(timeoutMs = 10_000L)

        /** 网络操作。 */
        val NETWORK = ToolRunPolicy(timeoutMs = 60_000L, retryDelaysMs = listOf(1_000L, 2_000L))

        /** 长任务。 */
        val LONG = ToolRunPolicy(timeoutMs = 300_000L, retryDelaysMs = listOf(2_000L, 4_000L, 8_000L))

        /** 不限时（高风险工具请勿使用）。 */
        val UNBOUNDED = ToolRunPolicy(timeoutMs = 0L)
    }
}
