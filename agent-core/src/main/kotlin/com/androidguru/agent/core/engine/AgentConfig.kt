package com.androidguru.agent.core.engine

/**
 * 引擎配置（不可变快照）。
 *
 * 人设 / 模式等业务差异一律走 [systemPrompt] 与 [additionalSystemContext] 注入，
 * 引擎不感知具体业务模型 —— 主循环只有一份。
 */
data class AgentConfig(
    /** 基础系统提示（人设 / 角色）。null = 框架默认极简提示。 */
    val systemPrompt: String? = null,

    /** 每轮请求前拼接的动态上下文（时间、工作目录、选中内容等），由宿主组装。 */
    val additionalSystemContext: String = "",

    // ---- 循环控制 ----

    /** 单次任务最大 ReAct 迭代轮数。 */
    val maxIterations: Int = 25,

    /** 上下文 token 上限（估算），超过触发压缩。 */
    val maxContextTokens: Int = 128_000,

    /** 压缩阈值：估算 token / maxContextTokens 超过该比例时压缩。 */
    val compressionThreshold: Double = 0.8,

    /** 压缩时保留的最近对话轮数（一轮 = 一条用户消息起止）。 */
    val preserveRecentTurns: Int = 5,

    // ---- 生成 ----

    /** 覆盖 LLM 配置的温度；null = 使用 LlmConfig 值。 */
    val temperature: Double? = null,

    /** 单次回复最大 token；null = 使用 LlmConfig 值。 */
    val maxResponseTokens: Int? = null,

    // ---- 工具 ----

    /** 单条工具输出进入上下文的最大字符数。 */
    val maxToolOutputChars: Int = 16_000,

    // ---- 韧性 ----

    /** LLM 瞬时错误最大重试次数。 */
    val llmMaxRetries: Int = 2,

    /** 重试基础退避（指数：base * 2^attempt）。 */
    val llmRetryBaseDelayMs: Long = 1_000L,

    /** 空响应（无文本无工具调用）重试次数。 */
    val maxEmptyResponseRetries: Int = 1,

    // ---- 交互 ----

    /** ask_user 等待用户输入的超时。 */
    val askUserTimeoutMs: Long = 5 * 60_000L,

    // ---- 长程任务：循环护栏 ----

    /** 是否启用“相同工具 + 相同参数重复调用”检测（默认开）。 */
    val loopDetectionEnabled: Boolean = true,

    /** 同一签名在窗口内出现达到该次数时触发 [com.androidguru.agent.core.engine.AgentEvent.LoopDetected] 并向模型注入建议。 */
    val loopDetectionThreshold: Int = 3,

    /** 签名统计窗口大小（只统计最近 N 次调用）。 */
    val loopDetectionWindow: Int = 12,

    // ---- 反思纠错（Reflexion） ----

    /** 是否启用失败驱动的反思（连续失败 → LLM 根因分析 → 策略修正注入）。 */
    val reflectionEnabled: Boolean = true,

    /** 触发反思的连续失败次数阈值。 */
    val reflectionFailureThreshold: Int = 3,

    /** 反思调用（独立轻量 LLM 调用）的最大输出 token。 */
    val reflectionMaxTokens: Int = 300,

    // ---- 跑偏检测 ----

    /** 是否启用进展停滞 + 目标对齐检测（多步骤复杂任务防发散）。 */
    val driftDetectionEnabled: Boolean = true,

    /** 连续 N 轮无任何进展信号时判定疑似停滞，注入拉回建议。 */
    val driftStagnationIterations: Int = 4,

    /** 每隔 N 轮迭代做一次 LLM 目标对齐抽查（0 = 关闭）。 */
    val driftCheckInterval: Int = 8,
) {
    /** 读-改-写：保留未触及字段的局部更新。 */
    fun patch(block: (AgentConfig) -> AgentConfig): AgentConfig = block(this)

    companion object {
        val DEFAULT = AgentConfig()
    }
}
