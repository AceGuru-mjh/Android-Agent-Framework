package com.androidguru.agent.core.engine

/**
 * 每轮迭代的动态系统上下文提供者。
 *
 * 与 [AgentConfig.additionalSystemContext]（静态、整个任务期间不变）互补：
 * 本缝在**每轮 LLM 请求前**求值一次，宿主可注入实时变化的状态 ——
 * 当前时间、工作目录、任务计划（todo）进度、外部世界事件等。
 *
 * 典型接入（长程任务计划注入）：
 *
 * ```kotlin
 * val engine = DefaultAgentEngine(
 *     ...,
 *     systemContextProvider = SystemContextProvider { sessionId ->
 *         "当前时间: ${Instant.now()}"
 *     },
 * )
 * ```
 *
 * 契约：
 * - 返回空字符串 = 不注入任何内容；
 * - 提供者实现应**快速且永不失败** —— 引擎会隔离非取消异常（记为空上下文），
 *   单个提供者故障不允许打断任务；
 * - 内容会被拼接到系统提示的「# 动态上下文」段（与 additionalSystemContext 同段）。
 */
fun interface SystemContextProvider {

    /** 返回本轮要注入的动态上下文（ sessionId 供多会话场景区分）。空串 = 无。 */
    suspend fun provideContext(sessionId: String): String
}
