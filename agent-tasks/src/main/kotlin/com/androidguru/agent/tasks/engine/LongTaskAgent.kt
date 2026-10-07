package com.androidguru.agent.tasks.engine

import com.androidguru.agent.core.context.CompositeContextProvider
import com.androidguru.agent.core.context.ContextCompressor
import com.androidguru.agent.core.context.SlidingWindowCompressor
import com.androidguru.agent.core.engine.AgentConfig
import com.androidguru.agent.core.engine.AgentEngine
import com.androidguru.agent.core.engine.AgentEvent
import com.androidguru.agent.core.engine.DefaultAgentEngine
import com.androidguru.agent.core.engine.SystemContextProvider
import com.androidguru.agent.core.engine.UserInput
import com.androidguru.agent.core.session.ConversationMemory
import com.androidguru.agent.core.session.FileConversationMemory
import com.androidguru.agent.core.session.InMemoryConversationMemory
import com.androidguru.agent.llm.LlmClient
import com.androidguru.agent.tasks.plan.FileTaskPlanStore
import com.androidguru.agent.tasks.plan.InMemoryTaskPlanStore
import com.androidguru.agent.tasks.plan.TaskPlan
import com.androidguru.agent.tasks.plan.TaskPlanManager
import com.androidguru.agent.tasks.plan.TaskPlanProgress
import com.androidguru.agent.tasks.plan.TaskPlanStore
import com.androidguru.agent.tools.AgentTool
import com.androidguru.agent.tools.DefaultToolExecutor
import com.androidguru.agent.tools.DefaultToolRegistry
import com.androidguru.agent.tools.ToolRegistry
import com.androidguru.agent.tools.hook.HookRegistry
import kotlinx.coroutines.flow.Flow
import java.nio.file.Path

/**
 * 长程任务 Agent 装配门面 —— 一个构造器调用电齐全部长程能力。
 *
 * 装配清单：
 * 1. [DefaultAgentEngine]（ReAct 主循环 + 全部生产韧性）；
 * 2. [TaskPlanTool] 注册进注册表（模型可维护计划）；
 * 3. [PlanContextInjector] 接入引擎动态上下文缝（计划状态每轮注入）；
 * 4. 计划持久化（[TaskPlanStore]）+ 会话持久化（[ConversationMemory]）——
 *    两者均用文件实现时即获得**跨进程恢复**能力；
 * 5. 宿主业务工具经 [extraTools] 注入，与计划工具共存；
 * 6. 宿主可叠加更多动态上下文注入器（[extraContextProviders]，如
 *    agent-memory 的 MemoryContextInjector）—— 经 CompositeContextProvider
 *    与计划注入组合，单点异常隔离。
 *
 * 最小用法：
 *
 * ```kotlin
 * val agent = LongTaskAgent(
 *     llmClient = OpenAiCompatibleClient(llmConfig),
 *     sessionId = "report-2024",
 *     workspace = Path.of("data/agent"),   // 计划 + 会话都落在这里（可恢复）
 *     extraTools = listOf(MyBusinessTool()),
 * )
 *
 * agent.execute(UserInput("把这份季度数据整理成报告")).collect { event ->
 *     when (event) {
 *         is AgentEvent.BudgetExhausted -> agent.continueExecution(50)  // 预算续跑
 *         is AgentEvent.LoopDetected -> println("护栏: ${event.toolName} 重复 ${event.repeatedCount} 次")
 *         else -> Unit
 *     }
 * }
 * ```
 *
 * 进程崩溃后重启：用相同 [sessionId] + [workspace] 重建实例，
 * `agent.plan()` 恢复计划状态，`agent.engineMemoryMessages()` 可检查恢复的对话历史，
 * 然后直接 [continueExecution] 从断点续跑。
 */
class LongTaskAgent(
    llmClient: LlmClient,
    config: AgentConfig = AgentConfig.DEFAULT,
    sessionId: String = "longtask-${System.currentTimeMillis()}",
    /** 计划持久化；缺省内存（一次性任务）。 */
    planStore: TaskPlanStore? = null,
    /** 会话记忆；缺省内存。传 [FileConversationMemory] 获得跨进程恢复。 */
    memory: ConversationMemory = InMemoryConversationMemory(),
    /** 宿主业务工具（与 task_plan 共存于同一注册表）。 */
    extraTools: List<AgentTool> = emptyList(),
    /** 额外动态上下文注入器（如 agent-memory 的记忆召回）；与计划注入组合且互不感知。 */
    extraContextProviders: List<SystemContextProvider> = emptyList(),
    compressor: ContextCompressor = SlidingWindowCompressor(),
    hooks: HookRegistry = HookRegistry(),
    /** 同一时刻只允许一个任务 in_progress（默认开）。 */
    enforceSingleActive: Boolean = true,
    /** 便捷参数：给了 workspace 就自动用文件存储（计划 + 会话）。 */
    workspace: Path? = null,
) {

    /** 长程任务推荐系统提示（计划纪律内建）。 */
    private val longTaskSystemPrompt: String = (config.systemPrompt
        ?: "You are a capable agent running on Android Agent Framework.")
        .trimEnd() + "\n\n" + LONG_TASK_PROMPT_SUFFIX

    /** 会话记忆：workspace 给定时自动文件化（跨进程恢复；文件名与会话 id 同步消毒）。 */
    val conversationMemory: ConversationMemory = memory.let { m ->
        if (workspace != null && m is InMemoryConversationMemory) {
            val safeId = sessionId.replace(Regex("[^\\w-]"), "_").take(120)
            FileConversationMemory(workspace.resolve("session-$safeId.jsonl"))
        } else {
            m
        }
    }

    /** 计划管理器：单一事实源。 */
    val planManager: TaskPlanManager = TaskPlanManager(
        store = planStore ?: workspace?.let { FileTaskPlanStore(it.resolve("plans")) }
            ?: InMemoryTaskPlanStore(),
        sessionId = sessionId,
        enforceSingleActive = enforceSingleActive,
    )

    private val registry: ToolRegistry = DefaultToolRegistry().also { r ->
        r.register(com.androidguru.agent.tasks.tools.TaskPlanTool(planManager))
        extraTools.forEach { r.register(it) }
    }

    private val executor = DefaultToolExecutor(registry, hooks)

    /** 动态上下文：计划注入 + 宿主额外注入器（组合缝，单点异常隔离）。 */
    private val contextProvider: SystemContextProvider =
        if (extraContextProviders.isEmpty()) {
            PlanContextInjector(planManager)
        } else {
            CompositeContextProvider(listOf(PlanContextInjector(planManager)) + extraContextProviders)
        }

    private val engine: DefaultAgentEngine = DefaultAgentEngine(
        llmClient = llmClient,
        toolRegistry = registry,
        toolExecutor = executor,
        config = config.copy(systemPrompt = longTaskSystemPrompt),
        memory = conversationMemory,
        compressor = compressor,
        hooks = hooks,
        sessionId = sessionId,
        systemContextProvider = contextProvider,
    )

    /** 暴露底层引擎（宿主需要 patchConfig / updateConfig / submitUserInput 时使用）。 */
    val underlyingEngine: AgentEngine get() = engine

    // ------------------------------------------------------------------
    // 代理引擎 API
    // ------------------------------------------------------------------

    fun execute(input: UserInput): Flow<AgentEvent> = engine.execute(input)

    fun execute(text: String): Flow<AgentEvent> = engine.execute(text)

    fun continueExecution(extraIterations: Int = 25): Flow<AgentEvent> = engine.continueExecution(extraIterations)

    suspend fun abort() = engine.abort()

    suspend fun submitUserInput(answer: String): Boolean = engine.submitUserInput(answer)

    val isRunning: Boolean get() = engine.isRunning

    // ------------------------------------------------------------------
    // 计划状态直达
    // ------------------------------------------------------------------

    /** 当前计划（null = 尚未创建）。 */
    fun plan(): TaskPlan? = planManager.plan()

    /** 当前进度（null = 尚未创建）。 */
    fun progress(): TaskPlanProgress? = planManager.progress()

    /** 计划变更订阅（宿主 UI 实时刷新）。 */
    fun onPlanChanged(listener: (TaskPlan) -> Unit): () -> Unit = planManager.addListener(listener)

    /** 恢复的对话历史条数（文件记忆时 >0 表示成功恢复）。 */
    fun engineMemoryMessages(): Int = conversationMemory.snapshot().size

    private companion object {
        val LONG_TASK_PROMPT_SUFFIX = """
            |# 长程任务纪律
            |你面对的可能是需要多步、多轮才能完成的长程任务：
            |- 接到复杂任务先调用 task_plan（rewrite）拆解为有序步骤（3~10 条，过大再拆）；
            |- 每轮开始前先看「长程任务计划」状态块：只做当前 in_progress / next 任务，不跳步、不重复；
            |- 完成一步立刻 update_status 为 done 并写验收结论；失败标 failed 写明原因，再调整计划；
            |- 计划状态每轮自动刷新展示，无需重复 read；
            |- 收到迭代预算追加的系统提示时，从中断处继续，不要从头开始。
        """.trimMargin()
    }
}
