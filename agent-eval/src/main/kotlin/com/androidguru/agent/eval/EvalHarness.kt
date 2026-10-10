package com.androidguru.agent.eval

import com.androidguru.agent.core.engine.AgentEngine
import com.androidguru.agent.core.engine.UserInput

/**
 * 评估 harness：把「跑任务 → 录轨迹 → 判分 → 出报告」串成一条流水线。
 *
 * 设计要点：
 * - **每任务新引擎**：[engineFactory] 按任务组装全新引擎（干净状态，无跨任务污染；
 *   同一工厂可复用同一 LLM 客户端，省连接）；
 * - **串行执行**：评估逐任务跑，互不干扰，失败隔离（单任务异常不报废整场评估）；
 * - **报告即调优入口**：[EvalReport] 提供成功率、失败分类统计、逐任务明细，
 *   换提示词 / 换模型 / 调护栏参数后重跑，两份报告直接对比。
 *
 * ```kotlin
 * val harness = EvalHarness(
 *     engineFactory = { task -> buildAgentFor(task) },
 *     tasks = MobileTaskSuites.defaultSuite(),
 * )
 * val report = harness.run()
 * println(report.renderText())
 * ```
 */
class EvalHarness(
    /** 每任务组装一个全新引擎（返回 [AgentEngine]；异常被隔离）。 */
    private val engineFactory: (EvalTask) -> AgentEngine,
    private val tasks: List<EvalTask>,
) {

    /** 单任务评估结果。 */
    data class TaskResult(
        val task: EvalTask,
        val result: TraceValidator.ValidationResult,
        val trace: TraceRecord,
        /** 引擎/组装失败时的错误信息（此时 result 为占位失败）。 */
        val error: String? = null,
    ) {
        val passed: Boolean get() = result.passed && error == null
    }

    /** 逐任务评估（串行；单任务异常折叠为该任务失败，不中断整场）。 */
    suspend fun run(): EvalReport {
        require(tasks.isNotEmpty()) { "评估任务列表不能为空" }
        val results = tasks.map { task ->
            runOne(task)
        }
        return EvalReport(results = results)
    }

    /** 评估指定类别（如只跑「通讯」类做专项调优）。 */
    suspend fun runCategory(category: String): EvalReport {
        val subset = tasks.filter { it.category == category }
        require(subset.isNotEmpty()) { "没有类别为 $category 的任务" }
        val results = subset.map { runOne(it) }
        return EvalReport(results = results)
    }

    private suspend fun runOne(task: EvalTask): TaskResult = try {
        val engine = engineFactory(task)
        val recorder = AgentTraceRecorder()
        engine.execute(UserInput(task.instruction)).collect { recorder.onEvent(it) }
        val trace = recorder.build()
        TaskResult(
            task = task,
            result = TraceValidator.validate(task, trace),
            trace = trace,
        )
    } catch (e: Exception) {
        TaskResult(
            task = task,
            result = TraceValidator.ValidationResult(
                passed = false,
                failures = listOf("执行异常: ${e.message ?: e.javaClass.simpleName}"),
                passedChecks = 0,
                totalChecks = 0,
            ),
            trace = emptyTrace(),
            error = e.message ?: e.javaClass.simpleName,
        )
    }

    private fun emptyTrace() = TraceRecord(
        toolCalls = emptyList(),
        finalText = null,
        completed = false,
        iterations = 0,
        loopDetected = false,
        reflections = 0,
        driftWarnings = 0,
        totalToolCalls = 0,
        durationMs = 0,
    )
}

/**
 * 评估报告：成功率 + 失败分类 + 逐任务明细 —— 调优的对比基线。
 */
data class EvalReport(
    val results: List<EvalHarness.TaskResult>,
) {
    val total: Int get() = results.size
    val passedCount: Int get() = results.count { it.passed }
    val failedCount: Int get() = total - passedCount

    /** 成功率（0..100）。 */
    val successRate: Int get() = if (total == 0) 0 else passedCount * 100 / total

    /** 按类别统计（类别 → (通过, 总数)）。 */
    val byCategory: Map<String, Pair<Int, Int>>
        get() = results.groupBy { it.task.category }.mapValues { (_, group) ->
            group.count { it.passed } to group.size
        }

    /** 按难度统计（难度 → (通过, 总数)）。 */
    val byDifficulty: Map<Int, Pair<Int, Int>>
        get() = results.groupBy { it.task.difficulty }.mapValues { (_, group) ->
            group.count { it.passed } to group.size
        }

    /** 失败原因分类（描述 → 次数，降序）—— 调优的定位入口。 */
    val failureTaxonomy: Map<String, Int>
        get() = results
            .filter { !it.passed }
            .flatMap { r -> r.result.failures }
            .groupingBy { failure ->
                if (failure.startsWith("缺少必做调用")) "缺少必做调用"
                else if (failure.startsWith("出现禁止调用")) "出现禁止调用"
                else if (failure.startsWith("迭代轮数超限")) "迭代轮数超限"
                else if (failure.startsWith("期间触发循环护栏")) "触发循环护栏（疑似死循环）"
                else if (failure.startsWith("最终回复未命中")) "收尾文本未达标"
                else if (failure.startsWith("成功调用次数不足")) "成功调用不足"
                else if (failure.startsWith("任务未正常收尾")) "未正常收尾"
                else "其他"
            }
            .eachCount()
            .entries.sortedByDescending { it.value }
            .associate { it.key to it.value }

    /** 控制台友好的文本报告。 */
    fun renderText(): String = buildString {
        appendLine("════════════ Agent 任务评估报告 ════════════")
        appendLine("总任务: $total    通过: $passedCount    失败: $failedCount    成功率: $successRate%")
        appendLine()
        appendLine("── 按类别 ──")
        for ((category, stat) in byCategory.entries.sortedBy { it.key }) {
            appendLine("  $category: ${stat.first}/${stat.second}（${stat.first * 100 / stat.second}%）")
        }
        appendLine()
        appendLine("── 按难度 ──")
        for ((difficulty, stat) in byDifficulty.entries.sortedBy { it.key }) {
            appendLine("  难度$difficulty: ${stat.first}/${stat.second}")
        }
        appendLine()
        val taxonomy = failureTaxonomy
        if (taxonomy.isNotEmpty()) {
            appendLine("── 失败分类（调优定位） ──")
            for ((reason, count) in taxonomy) {
                appendLine("  $reason: $count 次")
            }
            appendLine()
        }
        appendLine("── 逐任务明细 ──")
        for (r in results) {
            val mark = if (r.passed) "✓" else "✗"
            appendLine("  $mark [${r.task.category}] ${r.task.name}（${r.task.id}）")
            if (!r.passed) {
                r.error?.let { appendLine("      执行异常: $it") }
                for (failure in r.result.failures) {
                    appendLine("      - $failure")
                }
                appendLine(
                    "      轨迹: ${r.trace.totalToolCalls} 次调用 / ${r.trace.iterations} 轮" +
                        if (r.trace.loopDetected) " / ⚠触发循环护栏" else "",
                )
            }
        }
        appendLine("══════════════════════════════════════════")
    }
}
