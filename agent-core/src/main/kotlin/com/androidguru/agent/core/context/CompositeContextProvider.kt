package com.androidguru.agent.core.context

import com.androidguru.agent.core.engine.SystemContextProvider
import kotlinx.coroutines.CancellationException

/**
 * 动态上下文组合器 —— 把多个 [SystemContextProvider] 拼接为一个。
 *
 * 动机：引擎的动态上下文缝是**单个** [SystemContextProvider]，而能力模块各自提供
 * 注入器（agent-tasks 的 PlanContextInjector、agent-memory 的 MemoryContextInjector…）。
 * 宿主同时启用多个能力时用本组合器接线，无需任何模块感知彼此。
 *
 * 设计承诺：
 * - **非空过滤**：返回空串的提供者不参与拼接（无能力的轮次不注入空头衔）；
 * - **异常隔离**：单个提供者异常被丢弃（对齐引擎 `currentDynamicContext` 的隔离纪律），
 *   单点故障不允许打断任务 —— [CancellationException] 例外，必须向上传播；
 * - **顺序稳定**：按声明顺序拼接，先计划后记忆（宿主自行决定优先级排列）。
 *
 * ```kotlin
 * val provider = CompositeContextProvider(
 *     listOf(PlanContextInjector(planManager), memorySystem.contextInjector),
 * )
 * ```
 */
class CompositeContextProvider(
    private val providers: List<SystemContextProvider>,
) : SystemContextProvider {

    override suspend fun provideContext(sessionId: String): String =
        providers.mapNotNull { provider ->
            try {
                provider.provideContext(sessionId).takeIf { it.isNotBlank() }
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                null // 单点异常隔离：丢弃该提供者的输出，不影响其余
            }
        }.joinToString("\n\n")
}
