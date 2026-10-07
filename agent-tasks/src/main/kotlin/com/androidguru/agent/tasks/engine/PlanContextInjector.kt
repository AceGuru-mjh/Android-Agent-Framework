package com.androidguru.agent.tasks.engine

import com.androidguru.agent.core.engine.SystemContextProvider
import com.androidguru.agent.tasks.plan.TaskPlanManager

/**
 * 把 [TaskPlanManager] 的最新计划状态注入引擎系统上下文。
 *
 * 经 DefaultAgentEngine 的 SystemContextProvider 缝**每轮求值**：
 * 模型在任何一轮决策前都能看到最新计划与进度（这是长程任务不迷航的关键 ——
 * 上下文压缩可能裁掉早期对话，但计划状态永远在）。
 */
class PlanContextInjector(
    private val manager: TaskPlanManager,
) : SystemContextProvider {

    override suspend fun provideContext(sessionId: String): String = manager.renderForModel()
}
