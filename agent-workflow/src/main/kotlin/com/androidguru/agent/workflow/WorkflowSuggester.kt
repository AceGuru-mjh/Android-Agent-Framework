package com.androidguru.agent.workflow

import com.androidguru.agent.core.engine.SystemContextProvider

/**
 * 工作流建议注入器 —— 两阶段披露的第一层（OS-Copilot 分层注入模式）。
 *
 * 每轮只注入 **ACTIVE** 工作流的索引（id / 名称 / 描述 / 统计，上限
 * [maxEntries] 条），不注入完整步骤（全文经 read_workflow 按需拉取）——
 * 防 prompt 膨胀。候选 / 熔断工作流不进索引（复用验证门槛 + 健康度过滤）。
 *
 * 接线：`LongTaskAgent(extraContextProviders = listOf(workflowSystem.suggester()))`
 * —— 与计划注入、记忆召回经 CompositeContextProvider 组合，互不感知。
 */
class WorkflowSuggester(
    private val library: WorkflowLibrary,
    /** 索引条数上限（防 prompt 膨胀）。 */
    private val maxEntries: Int = 8,
) : SystemContextProvider {

    override suspend fun provideContext(sessionId: String): String {
        val active = library.list().filter { it.lifecycle == WorkflowLifecycle.ACTIVE }
            .sortedWith(compareByDescending<SavedWorkflow> { it.successCount }.thenBy { it.definition.id })
            .take(maxEntries.coerceAtLeast(1))
        if (active.isEmpty()) return ""

        val entries = active.joinToString("\n") { saved ->
            val stats = if (saved.totalRuns > 0) "（成功 ${saved.successCount}/${saved.totalRuns}）" else ""
            "- ${saved.definition.id}$stats: ${saved.definition.description}"
        }
        return buildString {
            appendLine("# 可复用工作流（run_workflow 工具）")
            appendLine("以下多步骤流程已验证可用，接到匹配任务时**优先用 run_workflow 复用**，")
            appendLine("不要逐步重新推理；参数需求先用 read_workflow 查看：")
            append(entries)
        }
    }
}
