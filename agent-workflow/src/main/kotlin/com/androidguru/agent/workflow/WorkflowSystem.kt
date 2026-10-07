package com.androidguru.agent.workflow

import com.androidguru.agent.llm.LlmClient
import com.androidguru.agent.tools.ToolRegistry
import java.nio.file.Path

/**
 * 工作流系统装配门面 —— 一个构造器调齐全部工作流能力（对齐 LongTaskAgent /
 * MemorySystem 的门面家族）。
 *
 * 装配清单：
 * 1. [WorkflowLibrary]（沉淀 + 健康度生命周期）—— workspace 给定时文件持久化；
 * 2. [WorkflowEngine]（DAG 调度 + 持久化运行 + human-in-loop + 崩溃恢复）——
 *    onRunFinished 回调自动接线库统计（成功 / 失败 / 晋升 / 熔断）；
 * 3. [tools] 工具五件套（run / list / read / save / disable）—— 注册进宿主的
 *    ToolRegistry 后模型即可复用工作流；
 * 4. [suggester] 建议注入器 —— 接入 LongTaskAgent.extraContextProviders，
 *    与计划 / 记忆注入组合；
 * 5. [WorkflowExtractor] 蒸馏器 —— 成功会话 → 候选工作流。
 *
 * 最小用法：
 *
 * ```kotlin
 * val registry = DefaultToolRegistry()   // 宿主工具注册表
 * val workflowSystem = WorkflowSystem(
 *     toolRegistry = registry,
 *     llmClient = client,
 *     workspace = Path.of("data/agent"),   // 库 + 运行态文件持久化
 * )
 * workflowSystem.tools.forEach { registry.register(it) }
 *
 * val agent = LongTaskAgent(
 *     llmClient = client,
 *     extraTools = registry.getAllTools(),
 *     extraContextProviders = listOf(workflowSystem.suggester()),
 * )
 *
 * // 进程重启后：
 * workflowSystem.recoverInterruptedRuns()   // 残留 RUNNING → CRASHED → 可 resume
 * ```
 */
class WorkflowSystem(
    /** Tool 节点与工具五件套共用的注册表（可与宿主 / LongTaskAgent 共用）。 */
    val toolRegistry: ToolRegistry? = null,
    private val llmClient: LlmClient? = null,
    /** 工作流库目录（null = 内存库）。 */
    workflowDir: Path? = null,
    /** 运行态目录（null = 内存运行态）。 */
    runsDir: Path? = null,
    /** 便捷参数：给了 workspace 就自动落 `workflows/` 与 `runs/`。 */
    workspace: Path? = null,
    engineMaxConcurrency: Int = 4,
    /** 蒸馏器（null = 不启用蒸馏学习）。 */
    private val extractor: WorkflowExtractor? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    /** 工作流库（建议注入 / 工具读写 / 健康度管理的单一事实源）。 */
    val library: WorkflowLibrary = WorkflowLibrary(
        store = workflowDir?.let { FileWorkflowLibraryStore(it) }
            ?: workspace?.let { FileWorkflowLibraryStore(it.resolve("workflows")) }
            ?: InMemoryWorkflowLibraryStore(),
        clock = clock,
    )

    /** 引擎（库统计自动接线：终态回调 → recordRunOutcome）。 */
    val engine: WorkflowEngine = WorkflowEngine(
        toolRegistry = toolRegistry,
        llmClient = llmClient,
        runStore = runsDir?.let { FileWorkflowRunStore(it) }
            ?: workspace?.let { FileWorkflowRunStore(it.resolve("runs")) }
            ?: InMemoryWorkflowRunStore(),
        maxConcurrency = engineMaxConcurrency,
        clock = clock,
        onRunFinished = { definitionId, run ->
            when (run.status) {
                RunStatus.SUCCEEDED -> library.recordRunOutcome(definitionId, true)
                RunStatus.FAILED, RunStatus.CANCELLED -> library.recordRunOutcome(
                    definitionId,
                    false,
                    run.message,
                )
                else -> Unit // WAITING_HUMAN 等非终态不计数
            }
        },
    )

    /** 工具五件套（注册进宿主 ToolRegistry；工具内部已防递归执行 run_workflow）。 */
    val tools: List<com.androidguru.agent.tools.AgentTool> = WorkflowTools.all(library, engine)

    /** 建议注入器（两阶段披露第一层：ACTIVE 索引）。 */
    fun suggester(): WorkflowSuggester = WorkflowSuggester(library)

    /**
     * 崩溃恢复扫描（进程启动时调用）：残留 RUNNING → CRASHED。
     * 返回受影响 runId（宿主可选择性 engine.resume 续跑）。
     */
    fun recoverInterruptedRuns(): List<String> = engine.recoverInterrupted()

    /**
     * 从成功会话蒸馏工作流（学习闭环入口）。
     * 消息来源：LongTaskAgent.conversationMemory.snapshot()（成功的会话）。
     * 返回入库的候选工作流（null = 轨迹太薄 / 无工具调用 / 定义不合格）。
     */
    suspend fun distillFromConversation(
        messages: List<com.androidguru.agent.llm.LlmMessage>,
        goal: String,
        save: Boolean = true,
    ): SavedWorkflow? {
        val distiller = extractor ?: WorkflowExtractor(client = llmClient, clock = clock)
        return if (save) {
            distiller.extractAndSave(messages, goal, library)
        } else {
            distiller.extract(messages, goal)?.let { definition ->
                try {
                    SavedWorkflow(
                        definition = definition,
                        createdAtMs = clock(),
                        updatedAtMs = clock(),
                    )
                } catch (e: Exception) {
                    null
                }
            }
        }
    }
}
