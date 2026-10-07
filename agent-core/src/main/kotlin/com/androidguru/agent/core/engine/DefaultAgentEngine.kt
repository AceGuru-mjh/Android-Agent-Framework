package com.androidguru.agent.core.engine

import com.androidguru.agent.core.context.ContextCompressor
import com.androidguru.agent.core.context.SlidingWindowCompressor
import com.androidguru.agent.core.context.TokenEstimator
import com.androidguru.agent.core.session.ConversationMemory
import com.androidguru.agent.core.session.InMemoryConversationMemory
import com.androidguru.agent.llm.LlmClient
import com.androidguru.agent.llm.LlmException
import com.androidguru.agent.llm.LlmMessage
import com.androidguru.agent.llm.ToolCall
import com.androidguru.agent.llm.ToolDefinition
import com.androidguru.agent.llm.Usage
import com.androidguru.agent.tools.ToolErrorCode
import com.androidguru.agent.tools.ToolExecutor
import com.androidguru.agent.tools.ToolRegistry
import com.androidguru.agent.tools.ToolResult
import com.androidguru.agent.tools.hook.HookDecision
import com.androidguru.agent.tools.hook.HookEvent
import com.androidguru.agent.tools.hook.HookRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * 默认 Agent 引擎 —— 单一 ReAct 主循环。
 *
 * 设计承诺：
 * - **主循环只有一份**：模式 / 人设差异全部走 system prompt 注入与钩子，不 fork 循环；
 * - **事件流可见**：思考 / 工具 / 回复 / 用量 / 韧性全部以 [AgentEvent] 暴露；
 * - **生产韧性**：LLM 瞬时错误指数退避重试（不消耗迭代配额）、空响应重试、
 *   悬空 tool-call 修补、上下文压缩门、相同调用循环护栏；
 * - **长程任务**：[continueExecution] 预算续跑（记忆 / 迭代编号跨运行连续）、
 *   [SystemContextProvider] 每轮动态上下文注入缝；
 * - **取消纪律**：[kotlinx.coroutines.CancellationException] 永远向上传播，绝不吞并。
 *
 * 配置热替换：[patchConfig] 读-改-写，下一轮迭代生效。
 */
class DefaultAgentEngine(
    private val llmClient: LlmClient,
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    config: AgentConfig = AgentConfig.DEFAULT,
    private val memory: ConversationMemory = InMemoryConversationMemory(),
    private val compressor: ContextCompressor = SlidingWindowCompressor(),
    private val hooks: HookRegistry = HookRegistry(),
    private val sessionId: String = "session-${System.currentTimeMillis()}",
    /** 每轮求值的动态上下文提供者（时间 / 计划进度等实时状态）；异常被隔离，永不打断任务。 */
    private val systemContextProvider: SystemContextProvider? = null,
) : AgentEngine {

    @Volatile
    private var configSnapshot: AgentConfig = config

    fun currentConfig(): AgentConfig = configSnapshot

    /** 全量更新配置，下一轮迭代生效。 */
    fun updateConfig(newConfig: AgentConfig) {
        configSnapshot = newConfig
    }

    /** 读-改-写局部更新（保留未触及字段）。 */
    fun patchConfig(block: (AgentConfig) -> AgentConfig) {
        configSnapshot = block(configSnapshot)
    }

    // ------------------------------------------------------------------
    // 运行状态
    // ------------------------------------------------------------------

    private val running = AtomicBoolean(false)
    private val callSeq = AtomicLong(0)

    @Volatile
    private var aborted = false

    @Volatile
    private var currentJob: Job? = null

    @Volatile
    private var inputDeferred: CompletableDeferred<String>? = null

    /** 上一次运行的尾状态（迭代数 / 工具调用数），供 [continueExecution] 续跑衔接。 */
    @Volatile
    private var lastRunIterations: Int = 0

    @Volatile
    private var lastRunToolCalls: Int = 0

    /** 本次运行起始时间（续跑段独立计时）。 */
    @Volatile
    private var runStartedAtMs: Long = 0L

    /** 循环护栏（任务开始时按配置重建）。 */
    @Volatile
    private var loopDetector: ToolCallLoopDetector? = null

    override val isRunning: Boolean get() = running.get()

    // ------------------------------------------------------------------
    // 主循环
    // ------------------------------------------------------------------

    /** 单次运行的累计器（续跑时由 [continueExecution] 接入上次数值继续累计）。 */
    private class RunStats {
        var iterations: Int = 0
        var totalToolCalls: Int = 0
        var completed: Boolean = false
        var errorEmitted: Boolean = false
    }

    override fun execute(input: UserInput): Flow<AgentEvent> = flow {
        // 单活跃任务互斥
        if (!running.compareAndSet(false, true)) {
            emit(AgentEvent.Error("已有任务在运行，请等待完成或先 abort()", recoverable = false))
            return@flow
        }
        beginRun()
        val stats = RunStats()

        try {
            hooks.dispatch(HookEvent.SessionStart(sessionId))

            val promptDecision = hooks.dispatch(HookEvent.UserPromptSubmit(input.text))
            if (promptDecision is HookDecision.Block) {
                emit(AgentEvent.Error("输入被钩子拒绝: ${promptDecision.reason}", recoverable = false))
                stats.errorEmitted = true
                return@flow
            }
            memory.appendUser(input.text, input.images)

            runAgentLoop(stats, startIteration = 0)
            emitTerminalEvents(stats)

        } catch (ce: CancellationException) {
            withContext(NonCancellable) { emit(AgentEvent.Aborted) }
            throw ce
        } catch (e: Exception) {
            withContext(NonCancellable) {
                emit(AgentEvent.Error("引擎异常: ${e.message ?: e.javaClass.simpleName}", recoverable = false))
            }
        } finally {
            withContext(NonCancellable) {
                hooks.dispatch(HookEvent.SessionEnd(sessionId))
            }
            endRun(stats)
        }
    }

    override fun continueExecution(extraIterations: Int): Flow<AgentEvent> = flow {
        if (!running.compareAndSet(false, true)) {
            emit(AgentEvent.Error("已有任务在运行，无法续跑", recoverable = false))
            return@flow
        }
        // 判据是记忆而非引擎内状态：文件记忆恢复的新实例（崩溃重启场景）同样可续跑
        val hasHistory = memory.snapshot().any { it is LlmMessage.User }
        if (!hasHistory) {
            emit(AgentEvent.Error("没有可续跑的任务：请先 execute() 至少一轮", recoverable = false))
            running.set(false)
            return@flow
        }
        beginRun()
        // 迭代编号衔接：同实例续跑从上次断点继续；新实例（记忆恢复）从 1 重新计
        val startFrom = lastRunIterations.takeIf { it > 0 } ?: 0
        val stats = RunStats().apply {
            iterations = startFrom
            totalToolCalls = lastRunToolCalls
        }

        try {
            hooks.dispatch(HookEvent.SessionStart(sessionId))

            // 预算扩充（下一轮生效）：主循环条件 iteration < maxIterations 天然衔接断点
            patchConfig { it.copy(maxIterations = it.maxIterations + extraIterations) }
            memory.appendSystem(
                "（系统：迭代预算已追加 $extraIterations 轮。请从上次中断处继续当前任务，" +
                    "不要重复已完成的工作，保持既有进度与结论。）",
            )

            runAgentLoop(stats, startIteration = startFrom)
            emitTerminalEvents(stats)
        } catch (ce: CancellationException) {
            withContext(NonCancellable) { emit(AgentEvent.Aborted) }
            throw ce
        } catch (e: Exception) {
            withContext(NonCancellable) {
                emit(AgentEvent.Error("引擎异常: ${e.message ?: e.javaClass.simpleName}", recoverable = false))
            }
        } finally {
            withContext(NonCancellable) {
                hooks.dispatch(HookEvent.SessionEnd(sessionId))
            }
            endRun(stats)
        }
    }

    /** 运行前公共初始化（互斥闸门通过后调用）。 */
    private fun beginRun() {
        aborted = false
        runStartedAtMs = System.currentTimeMillis()
        currentJob = null
        val cfg = configSnapshot
        loopDetector = if (cfg.loopDetectionEnabled) {
            ToolCallLoopDetector(cfg.loopDetectionWindow, cfg.loopDetectionThreshold)
        } else {
            null
        }
    }

    /** 运行收尾：释放闸门 + 记录尾状态供续跑。 */
    private fun endRun(stats: RunStats) {
        lastRunIterations = stats.iterations
        lastRunToolCalls = stats.totalToolCalls
        running.set(false)
        currentJob = null
    }

    /**
     * 主循环体：[execute] 与 [continueExecution] 共享同一份循环，绝不 fork。
     * iteration 从 [startIteration] 继续计数（续跑时 = 上次断点）。
     */
    private suspend fun FlowCollector<AgentEvent>.runAgentLoop(
        stats: RunStats,
        startIteration: Int,
    ) {
        currentJob = currentCoroutineContext()[Job]
        var iteration = startIteration
        while (iteration < configSnapshot.maxIterations && !aborted && currentCoroutineContext().isActive) {
            iteration++
            stats.iterations = iteration
            emit(AgentEvent.IterationStart(iteration))

            maybeCompressContext()

            val messages = buildMessages(currentDynamicContext())
            val tools = toolRegistry.getAllTools().map {
                ToolDefinition(name = it.name, description = it.description, parametersJsonSchema = it.parameters.render().toString())
            }

            // ---- LLM 流式一轮 ----
            val turn = try {
                runLlmTurn(messages, tools)
            } catch (e: LlmException) {
                emit(AgentEvent.Error("LLM 错误: ${e.message}", recoverable = LlmException.isTransient(e)))
                stats.errorEmitted = true
                return
            }
            if (aborted || !currentCoroutineContext().isActive) return

            // ---- 无工具调用：文本收尾 ----
            if (turn.toolCalls.isEmpty()) {
                if (turn.text.isBlank()) {
                    // 空响应重试已耗尽
                    emit(AgentEvent.Error("LLM 返回空响应", recoverable = true))
                    stats.errorEmitted = true
                    return
                }
                memory.appendAssistant(turn.text)
                stats.completed = true
                val duration = System.currentTimeMillis() - runStartedAtMs
                emit(AgentEvent.Complete(turn.text, iteration, stats.totalToolCalls, duration))
                hooks.dispatch(HookEvent.Stop(sessionId, turn.text))
                return
            }

            // ---- 有工具调用：执行并回填（含循环护栏） ----
            // 修复 issue #21 L-11：伴随工具调用的正文已 emit 给用户，也必须入记忆 ——
            // content = null 会让下一轮上下文丢失该段（模型看起来"失忆"）
            memory.appendAssistant(content = turn.text.ifBlank { null }, toolCalls = turn.toolCalls)
            for (call in turn.toolCalls) {
                if (aborted || !currentCoroutineContext().isActive) break
                val result = executeOneCall(call)
                stats.totalToolCalls++
                memory.appendToolResult(call.id, renderForModel(guardLoop(call, result)))
            }
        }
    }

    /** 终态事件：预算耗尽（可续跑信号）/ 中止。 */
    private suspend fun FlowCollector<AgentEvent>.emitTerminalEvents(stats: RunStats) {
        if (!stats.completed && !aborted && !stats.errorEmitted) {
            val duration = System.currentTimeMillis() - runStartedAtMs
            emit(
                AgentEvent.BudgetExhausted(
                    iterationsUsed = stats.iterations,
                    maxIterations = configSnapshot.maxIterations,
                    totalToolCalls = stats.totalToolCalls,
                    durationMs = duration,
                ),
            )
            emit(
                AgentEvent.Error(
                    "达到最大迭代轮数（${configSnapshot.maxIterations}）仍未完成；" +
                        "长程任务可调用 continueExecution() 追加预算续跑",
                    recoverable = true,
                ),
            )
        }
        if (aborted && !stats.completed) {
            emit(AgentEvent.Aborted)
        }
    }

    /**
     * 循环护栏：同一工具 + 完全相同参数在窗口内重复达到阈值时，
     * 发出 [AgentEvent.LoopDetected] 并把建议文本附加到回填给模型的结果上。
     */
    private suspend fun FlowCollector<AgentEvent>.guardLoop(call: ToolCall, result: ToolResult): ToolResult {
        val detector = loopDetector ?: return result
        if (call.name == ASK_USER_TOOL_NAME) return result // 交互挂起不构成循环风险
        val repeats = detector.observe(call.name, call.arguments)
        if (repeats >= detector.threshold && repeats % detector.threshold == 0) {
            emit(AgentEvent.LoopDetected(call.name, repeats, call.arguments))
            return result.copy(content = result.content + detector.advisoryText(repeats))
        }
        return result
    }

    /** 求值动态上下文；提供者异常被隔离（单点故障不允许打断任务）。 */
    private suspend fun currentDynamicContext(): String {
        val provider = systemContextProvider ?: return ""
        return try {
            provider.provideContext(sessionId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            ""
        }
    }

    // ------------------------------------------------------------------
    // LLM 一轮（含韧性）
    // ------------------------------------------------------------------

    private class TurnResult {
        var text: String = ""
        var reasoning: String = ""
        var toolCalls: List<ToolCall> = emptyList()
        var usage: Usage? = null
    }

    private class ToolCallAcc {
        var id: String = ""
        var name: String = ""
        var args: StringBuilder = StringBuilder()

        fun build(index: Int): ToolCall = ToolCall(
            id = id.ifBlank { "call_${index}_${System.nanoTime()}" },
            name = name,
            arguments = args.toString().ifBlank { "{}" },
            index = index,
        )
    }

    /**
     * 流式收集一轮：思考 / 文本 / 工具调用增量分流；
     * 瞬时错误退避重试（本轮有部分输出则不重试，避免重复 emit）；
     * 空响应有限重试，耗尽后抛 [LlmException.EmptyResponse]。
     */
    private suspend fun FlowCollector<AgentEvent>.runLlmTurn(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
    ): TurnResult {
        var attempt = 0
        var emptyRetries = 0
        val cfg = configSnapshot

        while (true) {
            val textBuf = StringBuilder()
            val reasoningBuf = StringBuilder()
            val toolAcc = LinkedHashMap<Int, ToolCallAcc>()
            var authoritativeComplete: List<ToolCall>? = null
            var usage: Usage? = null

            try {
                llmClient
                    .chatStream(messages, tools, cfg.temperature, cfg.maxResponseTokens)
                    .collect { chunk ->
                        chunk.reasoning?.let {
                            reasoningBuf.append(it)
                            emit(AgentEvent.ThinkingChunk(it))
                        }
                        chunk.content?.let {
                            textBuf.append(it)
                            emit(AgentEvent.ResponseChunk(it))
                        }
                        for (delta in chunk.toolCallDeltas) {
                            val acc = toolAcc.getOrPut(delta.index) { ToolCallAcc() }
                            if (delta.id.isNotBlank()) acc.id = delta.id
                            if (delta.name.isNotBlank()) acc.name = delta.name
                            // 客户端下发的 delta.arguments 为“累积快照”语义：整体替换（非追加）
                            if (delta.arguments.isNotEmpty()) acc.args = StringBuilder(delta.arguments)
                        }
                        chunk.usage?.let { usage = it }
                        if (chunk.finish && chunk.completeToolCalls.isNotEmpty()) {
                            authoritativeComplete = chunk.completeToolCalls
                        }
                    }

                usage?.let {
                    emit(AgentEvent.UsageUpdated(it))
                }

                val result = TurnResult()
                result.text = textBuf.toString()
                result.reasoning = reasoningBuf.toString()
                result.toolCalls = authoritativeComplete
                    ?: toolAcc.entries.sortedBy { it.key }.map { it.value.build(it.key) }
                result.usage = usage

                if (result.text.isBlank() && result.toolCalls.isEmpty()) {
                    if (emptyRetries < cfg.maxEmptyResponseRetries) {
                        emptyRetries++
                        val delayMs = backoffMs(emptyRetries)
                        emit(AgentEvent.LlmRetryScheduled(emptyRetries, delayMs, "空响应"))
                        delay(delayMs)
                        continue
                    }
                    throw LlmException.EmptyResponse
                }
                return result
            } catch (e: LlmException) {
                val hadPartial = textBuf.isNotEmpty() || reasoningBuf.isNotEmpty() || toolAcc.isNotEmpty()
                if (LlmException.isTransient(e) && !hadPartial && attempt < cfg.llmMaxRetries) {
                    attempt++
                    val delayMs = backoffMs(attempt)
                    emit(AgentEvent.LlmRetryScheduled(attempt, delayMs, e.message ?: "LLM 瞬时错误"))
                    delay(delayMs)
                    continue
                }
                throw e
            }
        }
    }

    private fun backoffMs(attempt: Int): Long {
        val base = configSnapshot.llmRetryBaseDelayMs
        return (base shl (attempt - 1).coerceIn(0, 5)).coerceAtMost(30_000L)
    }

    // ------------------------------------------------------------------
    // 工具执行
    // ------------------------------------------------------------------

    private suspend fun FlowCollector<AgentEvent>.executeOneCall(call: ToolCall): ToolResult {
        emit(AgentEvent.ToolCallStart(call.id, call.name, call.arguments))
        val startedAt = System.currentTimeMillis()

        // 内置 ask_user：挂起等待宿主 submitUserInput
        if (call.name == ASK_USER_TOOL_NAME) {
            val prompt = parseAskUserPrompt(call.arguments)
            val deferred = CompletableDeferred<String>()
            inputDeferred = deferred
            emit(AgentEvent.UserInputRequired(prompt, configSnapshot.askUserTimeoutMs))
            val answer = withTimeoutOrNull(configSnapshot.askUserTimeoutMs) { deferred.await() }
                ?: "（用户未及时回复，本次交互已超时）"
            inputDeferred = null
            val result = ToolResult.success(answer)
            emit(AgentEvent.ToolCallComplete(call.id, call.name, result, System.currentTimeMillis() - startedAt))
            return result
        }

        val result = try {
            toolExecutor.execute(toolId = call.name, arguments = call.arguments, callId = call.id)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            ToolResult.failure(
                "工具执行框架异常: ${e.message ?: e.javaClass.simpleName}",
                ToolErrorCode.INTERNAL,
            )
        }
        emit(AgentEvent.ToolCallComplete(call.id, call.name, result, System.currentTimeMillis() - startedAt))
        return result
    }

    /** 模型可见的工具结果渲染：错误走 "Error: " 协议并附建议。 */
    private fun renderForModel(result: ToolResult): String {
        val content = result.content.take(configSnapshot.maxToolOutputChars)
        return if (result.ok) {
            content
        } else {
            buildString {
                append("Error: ").append(content)
                result.error?.code?.let { append(" [code=").append(it.name).append("]") }
                result.error?.suggestion?.let { append("\n建议: ").append(it) }
            }
        }
    }

    // ------------------------------------------------------------------
    // 消息构建与修补
    // ------------------------------------------------------------------

    private fun buildMessages(dynamicContext: String): List<LlmMessage> {
        val system = buildString {
            append(configSnapshot.systemPrompt ?: DEFAULT_SYSTEM_PROMPT)
            val dynamic = listOf(configSnapshot.additionalSystemContext, dynamicContext)
                .filter { it.isNotBlank() }
                .joinToString("\n\n")
            if (dynamic.isNotBlank()) {
                append("\n\n# 动态上下文\n")
                append(dynamic)
            }
        }
        return listOf(LlmMessage.System(system)) + repairDanglingToolCalls(memory.snapshot())
    }

    /**
     * 悬空 tool-call 修补：Assistant 带工具调用但没有配对的 Tool 结果
     * （崩溃 / 中断后残留）会让 OpenAI 端点 400 报废整个会话 ——
     * 合成「结果未知、先验证」消息修复历史。
     */
    internal fun repairDanglingToolCalls(messages: List<LlmMessage>): List<LlmMessage> {
        val result = mutableListOf<LlmMessage>()
        var pendingIds = LinkedHashSet<String>()

        fun flushPending() {
            if (pendingIds.isNotEmpty()) {
                for (id in pendingIds) {
                    result += LlmMessage.Tool(
                        id,
                        "（该工具调用在上一轮被中断，结果未知。请先向用户确认实际状态，不要臆测执行结果。）",
                    )
                }
                pendingIds = LinkedHashSet()
            }
        }

        for (m in messages) {
            when (m) {
                is LlmMessage.Assistant -> {
                    flushPending()
                    if (m.toolCalls.isNotEmpty()) pendingIds.addAll(m.toolCalls.map { it.id })
                    result += m
                }

                is LlmMessage.Tool -> {
                    pendingIds.remove(m.toolCallId)
                    result += m
                }

                is LlmMessage.User -> {
                    flushPending()
                    result += m
                }

                is LlmMessage.System -> result += m
            }
        }
        flushPending()
        return result
    }

    // ------------------------------------------------------------------
    // 上下文压缩
    // ------------------------------------------------------------------

    private suspend fun FlowCollector<AgentEvent>.maybeCompressContext() {
        val cfg = configSnapshot
        val snapshot = memory.snapshot()
        val tokens = TokenEstimator.estimate(snapshot)
        if (tokens < cfg.maxContextTokens * cfg.compressionThreshold) return

        hooks.dispatch(HookEvent.PreCompact(sessionId, tokens))
        val compressed = compressor.compress(snapshot, cfg.maxContextTokens, cfg.preserveRecentTurns)
        if (compressed != null) {
            memory.replaceAll(compressed)
        }
    }

    // ------------------------------------------------------------------
    // 交互与中止
    // ------------------------------------------------------------------

    override suspend fun abort() {
        aborted = true
        inputDeferred?.cancel()
        inputDeferred = null
        currentJob?.cancel(CancellationException("任务被用户中止"))
    }

    override suspend fun submitUserInput(answer: String): Boolean {
        val deferred = inputDeferred ?: return false
        val accepted = deferred.complete(answer)
        if (accepted) inputDeferred = null
        return accepted
    }

    private fun parseAskUserPrompt(arguments: String): String = try {
        val obj = Json.parseToJsonElement(arguments) as? JsonObject
        obj?.get("prompt")?.jsonPrimitive?.content ?: arguments.ifBlank { "请提供更多信息" }
    } catch (e: Exception) {
        arguments.ifBlank { "请提供更多信息" }
    }

    companion object {
        /** 内置交互工具名：模型调用后引擎发出 [AgentEvent.UserInputRequired]。 */
        const val ASK_USER_TOOL_NAME = "ask_user"

        /**
         * 内置 ask_user 工具定义（宿主如需展示可注册同描述的自定义工具）。
         */
        val ASK_USER_TOOL_DESCRIPTION = "当缺少必要信息、需要用户确认或选择时调用。" +
            "参数: {\"prompt\": \"要问用户的问题\"}。引擎会挂起等待用户回答。"

        const val DEFAULT_SYSTEM_PROMPT =
            "You are a capable agent running on Android Agent Framework. " +
                "Work step by step. Use the available tools when they help complete the task. " +
                "If required information is missing, use the ask_user tool. " +
                "Always respond in the user's language."
    }
}
