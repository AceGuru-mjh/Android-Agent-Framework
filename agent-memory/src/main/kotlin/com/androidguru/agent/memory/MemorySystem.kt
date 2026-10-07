package com.androidguru.agent.memory

import com.androidguru.agent.core.context.ContextCompressor
import com.androidguru.agent.core.engine.SystemContextProvider
import com.androidguru.agent.core.session.ConversationMemory
import com.androidguru.agent.llm.LlmClient
import com.androidguru.agent.tools.AgentTool
import com.androidguru.agent.tools.hook.HookDecision
import com.androidguru.agent.tools.hook.HookEvent
import com.androidguru.agent.tools.hook.HookRegistry
import com.androidguru.agent.tools.hook.ToolHook
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.nio.file.Path

/**
 * 记忆系统配置（全部带默认值 —— 不配置也能跑）。
 */
data class MemoryConfig(
    /** 每轮自动召回的最大条数。 */
    val recallLimit: Int = 6,
    /** 召回注入的 token 预算。 */
    val recallMaxTokens: Int = 500,
    /** 存储容量上限（FileMemoryStore 淘汰用）。 */
    val maxRecords: Int = 1000,
    /** 新近度半衰期（天）。 */
    val halfLifeDays: Double = 14.0,
    /** 访问回写冷却（毫秒）。 */
    val accessCooldownMs: Long = MemoryRecall.DEFAULT_ACCESS_COOLDOWN_MS,
    /** 会话结束自动抽取（onSessionComplete / SessionEnd 钩子）。 */
    val extractionEnabled: Boolean = true,
    /** 单次抽取上限。 */
    val maxMemoriesPerExtraction: Int = 8,
    /** 压缩捕获（capturingCompressor）内容上限。 */
    val maxCaptureChars: Int = 800,
)

/**
 * 记忆系统装配门面 —— 一次 [create] 电齐跨会话记忆的全部接线件。
 *
 * 三层记忆架构（对标 MemGPT / Letta，适配本框架的工作方式）：
 * 1. **工作记忆** = [ConversationMemory]（引擎消息流，已有能力，不动）；
 * 2. **情景记忆** = 会话摘要（[MemoryCapturingCompressor] 压缩时捕获 +
 *    [onSessionComplete] 会话结束时抽取）；
 * 3. **语义记忆** = 事实 / 偏好 / 教训 / 任务结论（memory_save 工具 + LLM 抽取）。
 *
 * 最小用法（裸引擎）：
 * ```kotlin
 * val memory = MemorySystem.create(workspace, llmClient)
 * val registry = DefaultToolRegistry().also { memory.tools.forEach(it::register) }
 * val conversation = FileConversationMemory(workspace.resolve("session.jsonl"))
 * val engine = DefaultAgentEngine(
 *     llmClient, registry, executor,
 *     memory = conversation,
 *     compressor = memory.capturingCompressor(SlidingWindowCompressor()),
 *     systemContextProvider = memory.contextInjectorFor(conversation),
 * )
 * engine.execute("...").collect { ... }
 * memory.onSessionComplete(conversation)   // 会话记忆沉淀
 * ```
 *
 * 与 LongTaskAgent 组合：把 [contextInjectorFor] 的结果塞进
 * `extraContextProviders`（agent-tasks 提供 CompositeContextProvider 组合）。
 *
 * 装配件全部独立可用（门面只是糖）：宿主可以只用工具、只用注入器、
 * 或只用压缩捕获 —— 对齐框架「门面不绑架」的装配纪律。
 */
class MemorySystem private constructor(
    val store: MemoryStore,
    val recall: MemoryRecall,
    val config: MemoryConfig,
    private val client: LlmClient?,
    private val extractor: MemoryExtractor,
) {

    /** 记忆三件套工具（memory_save / memory_search / memory_forget）。 */
    val tools: List<AgentTool> = listOf(
        MemorySaveTool(store),
        MemorySearchTool(recall),
        MemoryForgetTool(store),
    )

    /**
     * 每轮注入器：不带会话信号（纯重要度 / 新近度排名）。
     * 推荐用 [contextInjectorFor]（带当前用户消息作召回信号，更准）。
     */
    val contextInjector: SystemContextProvider = MemoryContextInjector(recall)

    /** 带召回信号的注入器（[conversation] 即引擎的工作记忆）。 */
    fun contextInjectorFor(conversation: ConversationMemory): SystemContextProvider =
        MemoryContextInjector(
            recall = recall,
            conversation = conversation,
            queryLimit = config.recallLimit,
            queryMaxTokens = config.recallMaxTokens,
        )

    /**
     * 压缩捕获器：包住宿主实际压缩器，压缩发生时把被裁对话留为情景记忆。
     * LLM 摘要（[client] 存在时）失败自动回退确定性截取。
     */
    fun capturingCompressor(delegate: ContextCompressor): ContextCompressor =
        MemoryCapturingCompressor(
            delegate = delegate,
            store = store,
            client = client,
            maxCaptureChars = config.maxCaptureChars,
        )

    /**
     * 会话结束记忆沉淀：从工作记忆抽取值得长期记住的信息（去重入库）。
     *
     * 建议在 collect 完事件流后调用；或用 [installSessionEndHook] 自动触发。
     * [extractionEnabled] = false 时为空操作。
     */
    suspend fun onSessionComplete(conversation: ConversationMemory, sessionId: String? = null): List<MemoryRecord> {
        if (!config.extractionEnabled) return emptyList()
        return extractor.extractAndStore(conversation.snapshot(), sessionId, store)
    }

    /**
     * 自动沉淀：向引擎钩子注册表挂 SessionEnd 监听，会话结束异步抽取
     * （[scope] 内执行 —— 不阻塞引擎 finally 段；异常被钩子注册表隔离）。
     *
     * 注意：宿主必须把**同一个** [hooks] 传给引擎构造器。
     */
    fun installSessionEndHook(
        hooks: HookRegistry,
        scope: CoroutineScope,
        conversation: ConversationMemory,
        sessionId: String? = null,
    ) {
        hooks.register(
            object : ToolHook {
                override val name: String = "memory-session-end-capture"
                override val order: Int = 100

                override suspend fun onEvent(event: HookEvent): HookDecision {
                    if (event is HookEvent.SessionEnd && config.extractionEnabled) {
                        scope.launch {
                            onSessionComplete(conversation, sessionId)
                        }
                    }
                    return HookDecision.Proceed
                }
            },
        )
    }

    /** 记忆纪律提示（追加到系统提示，让模型知道记忆工具的存在与用法）。 */
    fun promptSuffix(): String = PROMPT_SUFFIX

    companion object {
        /**
         * 创建记忆系统。
         *
         * - [workspace] 给定时用 [FileMemoryStore]（`{workspace}/memory.jsonl`，跨进程存续）；
         * - [store] 显式指定时优先于 workspace；
         * - 两者都缺省时退化为内存实现（进程内跨会话、不跨进程）；
         * - [client] 提供时启用 LLM 抽取 / 摘要，缺省全程确定性零成本。
         */
        fun create(
            workspace: Path? = null,
            client: LlmClient? = null,
            config: MemoryConfig = MemoryConfig(),
            store: MemoryStore? = null,
            clock: () -> Long = System::currentTimeMillis,
        ): MemorySystem {
            val actualStore = store
                ?: workspace?.let { FileMemoryStore(it.resolve("memory.jsonl"), maxRecords = config.maxRecords) }
                ?: InMemoryMemoryStore()
            val recall = MemoryRecall(
                store = actualStore,
                clock = clock,
                halfLifeDays = config.halfLifeDays,
                accessCooldownMs = config.accessCooldownMs,
            )
            return MemorySystem(
                store = actualStore,
                recall = recall,
                config = config,
                client = client,
                extractor = MemoryExtractor(
                    client = client,
                    clock = clock,
                    maxMemories = config.maxMemoriesPerExtraction,
                ),
            )
        }

        private val PROMPT_SUFFIX = """
            |# 记忆纪律
            |你有跨会话的长期记忆能力：
            |- 系统每轮自动召回相关记忆（「长期记忆」段落），无需主动查询；
            |- 用户提到值得长期记住的信息（偏好、事实、约定）时，主动调用 memory_save 保存；
            |- 需要更多历史信息时用 memory_search 检索；
            |- 发现某条记忆过时或错误时用 memory_forget 纠正；
            |- 记忆是参考不是圣旨：与当前对话冲突时以用户最新表述为准。
        """.trimMargin()
    }
}
