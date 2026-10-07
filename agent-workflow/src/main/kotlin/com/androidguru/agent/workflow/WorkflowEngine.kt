package com.androidguru.agent.workflow

import com.androidguru.agent.llm.LlmClient
import com.androidguru.agent.llm.LlmMessage
import com.androidguru.agent.tools.ToolErrorCode
import com.androidguru.agent.tools.ToolRegistry
import com.androidguru.agent.tools.ToolRequest
import com.androidguru.agent.tools.ToolResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * 工作流引擎：超步（superstep）调度器。
 *
 * 每个超步：计算**就绪集**（PENDING 且全部入边满足）→ 并行执行（默认并发 4）
 * → 超步结束。入边满足（AND-join，LangGraph 边表模型）：
 * - 普通边：来源 SUCCEEDED（switch 来源需已路由到该边）或 SKIPPED（CONTINUE）；
 * - `branch` 边：来源 SUCCEEDED 且路由决策等于该 branch；
 * - `error` 边：来源 FAILED（[NodeOnError.ERROR_BRANCH] 的降级路径）。
 *
 * 韧性（多源综合，详见 WORKFLOW_GUIDE）：
 * - **节点级重试**：指数退避 + 抖动，仅瞬时错误（工具 TIMEOUT/RATE_LIMIT/
 *   UNAVAILABLE / 节点超时 / LLM 网络错误）；模板解析等参数性错误立即失败；
 * - **失败处置三态**（n8n onError）：FAIL_RUN（取消并行兄弟）/ CONTINUE
 *   （SKIPPED 继续推进）/ ERROR_BRANCH（失败也有一条边）；
 * - **human-in-the-loop**（LangGraph interrupt）：HUMAN 节点暂停整条运行
 *   （WAITING_HUMAN 落盘），[resume] 携答案恢复 —— 暂停不是错误；
 * - **崩溃恢复**：节点状态变更即时落盘（LangGraph 两级持久化的单进程简化）；
 *   SUCCEEDED 节点恢复后不重跑；RUNNING 节点回退 PENDING 按已计 attempt 续跑
 *   （at-least-once —— 工具应尽量幂等）；
 * - **熔断**：超步数超过 definition.maxSteps 终止（agent 循环不收敛护栏）。
 *
 * 取消语义：宿主取消 collect（scope 取消）→ 运行落盘 CANCELLED（协程已取消
 * 无法再发事件，状态经 [run] 可查）；[cancel] 为协作式（超步与重试边界生效；
 * 在跑节点受其 timeoutMs 约束，长节点请设超时）。
 *
 * 并行节点事件经 channelFlow 汇聚（冷 flow 构建器禁止跨协程发射，channelFlow
 * 是唯一合法通道）。
 */
class WorkflowEngine(
    /** Tool 节点的工具来源；不提供则 Tool 节点不可执行（可先跑纯编排节点）。 */
    private val toolRegistry: ToolRegistry? = null,
    /** Agent 节点的 LLM 来源；不提供则 Agent 节点不可执行。 */
    private val llmClient: LlmClient? = null,
    private val runStore: WorkflowRunStore = InMemoryWorkflowRunStore(),
    /** 超步内并行执行节点数上限。 */
    private val maxConcurrency: Int = 4,
    private val clock: () -> Long = System::currentTimeMillis,
    private val runIdGenerator: () -> String =
        { "run-" + UUID.randomUUID().toString().substring(0, 8) },
    /** 终态回调（工作流库健康度统计接线用；同步调用，勿做重活）。 */
    private val onRunFinished: ((definitionId: String, run: WorkflowRunState) -> Unit)? = null,
) {

    /** 跨就绪集并行共享的运行态句柄（互斥更新 + 即时落盘）。 */
    private class RunHandle(@Volatile var state: WorkflowRunState)

    /** FAIL_RUN 信号（普通异常 —— 与用户取消的 CancellationException 严格分离）。 */
    private class NodeFailRunException(val nodeId: String, message: String) : Exception(message)

    /** 工具失败携带错误码（重试分类依据）。 */
    private class ToolFailureException(val result: ToolResult) : Exception(result.content)

    /** 节点超时。 */
    private class NodeTimeoutException(val nodeId: String) : Exception("节点 $nodeId 执行超时")

    /** 活跃运行的协作取消信号（cancel 写入，循环边界读取）。 */
    private val cancelSignals = ConcurrentHashMap<String, Boolean>()

    private val stateLock = Any()

    // ------------------------------------------------------------------
    // 生命周期 API
    // ------------------------------------------------------------------

    /**
     * 启动一次工作流执行。
     *
     * @throws WorkflowValidationException 定义静态校验失败。
     * @throws IllegalArgumentException 缺少必填参数（fail-fast：启动前即拒绝，
     *   调用方（工具层）折叠为失败结果）。
     */
    fun start(
        definition: WorkflowDefinition,
        params: Map<String, String> = emptyMap(),
    ): Flow<WorkflowEvent> {
        definition.validate()
        validateParams(definition, params)

        // 缺省实参合并：未传 / 传空的参数回落定义 default（一次到位，模板永见完整 params）
        val effectiveParams = definition.params.fold(params) { acc, spec ->
            val provided = acc[spec.name]
            if ((provided == null || provided.isBlank()) && spec.defaultValue != null) {
                acc + (spec.name to spec.defaultValue)
            } else {
                acc
            }
        }

        val run = WorkflowRunState(
            runId = runIdGenerator(),
            definitionId = definition.id,
            definition = definition,
            status = RunStatus.PENDING,
            params = JsonObject(effectiveParams.mapValues { JsonPrimitive(it.value) }),
            vars = JsonObject(emptyMap()),
            nodeStates = definition.nodes.associate { it.id to NodeRunState(NodeStatus.PENDING) },
            createdAtMs = clock(),
        )
        runStore.save(run)
        return executionFlow(run, humanAnswer = null)
    }

    /**
     * 恢复一次运行：
     * - WAITING_HUMAN：必须给 [humanAnswer]（等的就是它）；
     * - CRASHED / PENDING：从检查点续跑（SUCCEEDED 节点不重跑，RUNNING 节点
     *   回退 PENDING 续跑）。
     *
     * @throws IllegalArgumentException 运行不存在 / 终态 / 缺答案 / 残留 RUNNING
     *   （先 [recoverInterrupted]）等不可恢复态。
     */
    fun resume(runId: String, humanAnswer: String? = null): Flow<WorkflowEvent> {
        val run = runStore.load(runId)
            ?: throw IllegalArgumentException("运行 $runId 不存在")
        if (run.isTerminal()) {
            throw IllegalArgumentException("运行 $runId 已是终态 ${run.status}，不可恢复")
        }
        val waitingId = run.waitingNodeId
        if (run.status == RunStatus.WAITING_HUMAN) {
            if (humanAnswer == null) {
                throw IllegalArgumentException("运行 $runId 正在等待人工答复，resume 必须携带 humanAnswer")
            }
            if (run.definition.nodes.none { it.id == waitingId }) {
                throw IllegalArgumentException("等待节点 $waitingId 不在定义中（定义快照损坏）")
            }
        } else if (run.status == RunStatus.RUNNING) {
            throw IllegalArgumentException(
                "运行 $runId 状态为 RUNNING —— 本进程内它应由原 Flow 承载；" +
                    "若是进程重启残留请先 recoverInterrupted() 再 resume",
            )
        }
        return executionFlow(run, humanAnswer = humanAnswer)
    }

    /** 协作式取消（超步 / 重试边界生效；返回 false = 无活跃执行）。 */
    fun cancel(runId: String): Boolean {
        val active = cancelSignals.containsKey(runId)
        if (active) cancelSignals[runId] = true
        return active
    }

    /** 查询运行态（store 是事实源，终态后仍可读）。 */
    fun run(runId: String): WorkflowRunState? = runStore.load(runId)

    /**
     * 崩溃扫描（宿主进程启动时调用）：残留 RUNNING → 重分类 CRASHED
     * （n8n 纪律：只有 RUNNING 是崩溃嫌疑，WAITING_HUMAN 是合法暂停）。
     * 返回受影响 runId（随后可 [resume] 续跑）。
     */
    fun recoverInterrupted(): List<String> {
        val crashed = mutableListOf<String>()
        for (run in runStore.list()) {
            if (run.status == RunStatus.RUNNING) {
                runStore.save(run.copy(status = RunStatus.CRASHED, message = "进程中断（引擎启动扫描重分类）"))
                crashed += run.runId
            }
        }
        return crashed
    }

    // ------------------------------------------------------------------
    // 执行循环
    // ------------------------------------------------------------------

    private fun executionFlow(initial: WorkflowRunState, humanAnswer: String?): Flow<WorkflowEvent> = channelFlow {
        val handle = RunHandle(initial)
        val runId = initial.runId
        cancelSignals[runId] = false
        val events: suspend (WorkflowEvent) -> Unit = { send(it) }
        val startedAt = clock()

        try {
            // ---- 人工节点恢复：答案写进等待节点 ----
            val waitingId = initial.waitingNodeId
            if (humanAnswer != null && waitingId != null) {
                val now = clock()
                updateNode(handle, waitingId) { old ->
                    old.copy(
                        status = NodeStatus.SUCCEEDED,
                        output = JsonPrimitive(humanAnswer),
                        finishedStep = handle.state.stepCounter,
                        finishedAtMs = now,
                    )
                }
                events(WorkflowEvent.RunResumed(runId, waitingId))
            }

            // ---- 崩溃回滚：RUNNING 节点 → PENDING（attempt 保留续跑） ----
            if (initial.nodeStates.values.any { it.status == NodeStatus.RUNNING }) {
                synchronized(stateLock) {
                    handle.state = handle.state.copy(
                        nodeStates = handle.state.nodeStates.mapValues { (_, ns) ->
                            if (ns.status == NodeStatus.RUNNING) ns.copy(status = NodeStatus.PENDING) else ns
                        },
                    )
                    runStore.save(handle.state)
                }
            }

            synchronized(stateLock) {
                handle.state = handle.state.copy(
                    status = RunStatus.RUNNING,
                    startedAtMs = handle.state.startedAtMs ?: startedAt,
                    waitingNodeId = null,
                    message = null,
                )
                runStore.save(handle.state)
            }

            events(
                WorkflowEvent.RunStarted(
                    runId = runId,
                    definitionId = handle.state.definitionId,
                    stepCounterOffset = handle.state.stepCounter,
                ),
            )

            // ------------------- 超步主循环 -------------------
            var failedNodeId: String? = null
            var failureMessage: String? = null

            loop@ while (true) {
                if (cancelSignals[runId] == true) {
                    finishCancelled(handle, "协作取消")
                    events(WorkflowEvent.RunCancelled(runId, "协作取消"))
                    return@channelFlow
                }

                val ready = computeReady(handle.state)
                if (ready.isEmpty()) break@loop

                if (handle.state.stepCounter >= handle.state.definition.maxSteps) {
                    failureMessage = "超步数熔断：已达 ${handle.state.definition.maxSteps} 上限（疑似不收敛循环）"
                    break@loop
                }

                // 人工节点：暂停整条运行（不进并行池 —— 暂停是整条运行的事）
                val humanNode = ready.filterIsInstance<WorkflowNode.Human>().firstOrNull()
                if (humanNode != null) {
                    parkForHuman(handle, humanNode)
                    events(
                        WorkflowEvent.RunWaiting(
                            runId = runId,
                            nodeId = humanNode.id,
                            prompt = resolveText(humanNode.prompt, handle.state),
                        ),
                    )
                    return@channelFlow
                }

                synchronized(stateLock) {
                    handle.state = handle.state.copy(stepCounter = handle.state.stepCounter + 1)
                    runStore.save(handle.state)
                }

                try {
                    coroutineScope {
                        ready.take(maxConcurrency.coerceAtLeast(1)).forEach { node ->
                            launch { executeNode(node, handle, events) }
                        }
                        // 超出并发上限的节点留在 PENDING，下一超步由就绪集再次拾起
                    }
                } catch (e: NodeFailRunException) {
                    failedNodeId = e.nodeId
                    failureMessage = e.message
                    break@loop
                }

                // 回边重入：后完成者可重新激活先完成的下游（LangGraph Pregel 式循环）
                synchronized(stateLock) {
                    val rearmed = rearmLoopNodes(handle.state)
                    if (rearmed != handle.state) {
                        handle.state = rearmed
                        runStore.save(handle.state)
                    }
                }
            }

            // ------------------- 收尾 -------------------
            val duration = clock() - (handle.state.startedAtMs ?: startedAt)
            val unhandledFailure = firstUnhandledFailure(handle.state)
            if (failureMessage != null) {
                finishRun(handle, RunStatus.FAILED, failureMessage)
                events(
                    WorkflowEvent.RunFailed(
                        runId = runId,
                        definitionId = handle.state.definitionId,
                        failedNodeId = failedNodeId,
                        error = failureMessage!!,
                        durationMs = duration,
                    ),
                )
            } else if (unhandledFailure != null) {
                val message = unhandledFailure.second?.errorText ?: "节点 ${unhandledFailure.first} 失败且无错误分支出路"
                finishRun(handle, RunStatus.FAILED, message)
                events(
                    WorkflowEvent.RunFailed(
                        runId = runId,
                        definitionId = handle.state.definitionId,
                        failedNodeId = unhandledFailure.first,
                        error = message,
                        durationMs = duration,
                    ),
                )
            } else {
                val summary = buildSummary(handle.state)
                finishRun(handle, RunStatus.SUCCEEDED, null)
                events(
                    WorkflowEvent.RunSucceeded(
                        runId = runId,
                        definitionId = handle.state.definitionId,
                        totalSteps = handle.state.stepCounter,
                        durationMs = duration,
                        summary = summary,
                    ),
                )
            }
        } catch (e: CancellationException) {
            // 宿主取消 collect：协程已取消无法再发事件，只落终态
            finishRun(handle, RunStatus.CANCELLED, "宿主取消收集: ${e.message ?: ""}")
            throw e
        } catch (e: Exception) {
            finishRun(handle, RunStatus.FAILED, "引擎内部错误: ${e.message}")
            events(
                WorkflowEvent.RunFailed(
                    runId = runId,
                    definitionId = handle.state.definitionId,
                    failedNodeId = null,
                    error = "引擎内部错误: ${e.message}",
                    durationMs = clock() - startedAt,
                ),
            )
        } finally {
            cancelSignals.remove(runId)
        }
    }

    /** 单节点执行：重试循环 + 失败处置（事件实时上报）。 */
    private suspend fun executeNode(
        node: WorkflowNode,
        handle: RunHandle,
        events: suspend (WorkflowEvent) -> Unit,
    ) {
        val policy = node.retry
        var attempt = handle.state.nodeStates[node.id]?.attempt ?: 0

        while (true) {
            attempt += 1
            val nodeStart = clock()
            updateNode(handle, node.id) { old ->
                old.copy(status = NodeStatus.RUNNING, attempt = attempt, startedAtMs = nodeStart, errorText = null)
            }
            events(
                WorkflowEvent.NodeStarted(
                    runId = handle.state.runId,
                    nodeId = node.id,
                    attempt = attempt,
                    description = node.description,
                ),
            )

            try {
                val output = withTimeoutOrNull(node.timeoutMs ?: INFINITE) {
                    executeKind(node, handle)
                } ?: throw NodeTimeoutException(node.id)

                val now = clock()
                updateNode(handle, node.id) { old ->
                    old.copy(
                        status = NodeStatus.SUCCEEDED,
                        output = output,
                        finishedStep = handle.state.stepCounter,
                        finishedAtMs = now,
                        errorText = null,
                    )
                }
                events(
                    WorkflowEvent.NodeCompleted(
                        runId = handle.state.runId,
                        nodeId = node.id,
                        attempt = attempt,
                        durationMs = now - nodeStart,
                        outputPreview = preview(output),
                    ),
                )
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                val message = e.message ?: e.javaClass.simpleName
                val willRetry = isRetryable(e) && policy != null && attempt < policy.maxAttempts
                events(
                    WorkflowEvent.NodeFailed(
                        runId = handle.state.runId,
                        nodeId = node.id,
                        attempt = attempt,
                        error = message,
                        willRetry = willRetry,
                        onError = node.onError,
                    ),
                )
                if (willRetry) {
                    updateNode(handle, node.id) { old ->
                        old.copy(status = NodeStatus.PENDING, attempt = attempt, errorText = message)
                    }
                    delay(policy!!.delayFor(attempt))
                    continue
                }

                when (node.onError) {
                    NodeOnError.FAIL_RUN -> throw NodeFailRunException(node.id, "节点 ${node.id} 失败: $message")
                    NodeOnError.CONTINUE -> updateNode(handle, node.id) { old ->
                        old.copy(
                            status = NodeStatus.SKIPPED,
                            attempt = attempt,
                            errorText = message,
                            finishedStep = handle.state.stepCounter,
                            finishedAtMs = clock(),
                        )
                    }
                    NodeOnError.ERROR_BRANCH -> updateNode(handle, node.id) { old ->
                        old.copy(
                            status = NodeStatus.FAILED,
                            attempt = attempt,
                            errorText = message,
                            finishedStep = handle.state.stepCounter,
                            finishedAtMs = clock(),
                        )
                    }
                }
                return
            }
        }
    }

    // ------------------------------------------------------------------
    // 各节点类型的执行体
    // ------------------------------------------------------------------

    /** 模板解析只读持久化状态（Temporal 确定性纪律：决策不依赖易失内存）。 */
    private suspend fun executeKind(node: WorkflowNode, handle: RunHandle): JsonElement {
        val ctx = buildContext(handle.state)
        return when (node) {
            is WorkflowNode.Tool -> executeToolNode(node, ctx)
            is WorkflowNode.Agent -> executeAgentNode(node, ctx)
            is WorkflowNode.Human -> throw IllegalStateException(
                "human 节点 ${node.id} 必须经 parkForHuman 暂停，不应进入执行体",
            )
            is WorkflowNode.Switch -> executeSwitchNode(node, handle, ctx)
            is WorkflowNode.SetVar -> executeSetVarNode(node, handle, ctx)
            is WorkflowNode.Delay -> {
                delay(node.delayMs)
                JsonPrimitive("delayed:${node.delayMs}")
            }
        }
    }

    private suspend fun executeToolNode(node: WorkflowNode.Tool, ctx: ResolveContext): JsonElement {
        val registry = toolRegistry
            ?: throw TemplateResolver.TemplateResolutionException(
                "引擎未配置 toolRegistry，无法执行工具节点 ${node.id}",
            )
        val tool = registry.getTool(node.toolId)
            ?: throw TemplateResolver.TemplateResolutionException(
                "工具 ${node.toolId} 未注册（节点 ${node.id}）",
            )

        val resolved = TemplateResolver.resolveJsonArgs(node.arguments, ctx)
        val argsText = resolved.toString()

        // 模型产物的最后一道防线：与引擎执行器共用同一 schema 校验，永不漂移
        val schemaErrors = tool.parameters.validate(argsText)
        if (schemaErrors.isNotEmpty()) {
            throw TemplateResolver.TemplateResolutionException(
                "节点 ${node.id} 参数校验失败: ${schemaErrors.joinToString("; ")}",
            )
        }

        val callId = "wf-${node.id}-${UUID.randomUUID().toString().substring(0, 8)}"
        val result = try {
            tool.execute(ToolRequest(callId = callId, toolId = node.toolId, arguments = argsText))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ToolFailureException(
                ToolResult.failure("工具执行异常: ${e.message}", ToolErrorCode.UNAVAILABLE),
            )
        }
        if (!result.ok) throw ToolFailureException(result)

        // 输出：data 是 JSON 则原样（对象可下钻），否则 content 文本
        val dataText = result.data
        return if (dataText != null) {
            try {
                Json.parseToJsonElement(dataText)
            } catch (e: Exception) {
                JsonPrimitive(result.content)
            }
        } else {
            JsonPrimitive(result.content)
        }
    }

    private suspend fun executeAgentNode(node: WorkflowNode.Agent, ctx: ResolveContext): JsonElement {
        val client = llmClient
            ?: throw TemplateResolver.TemplateResolutionException(
                "引擎未配置 llmClient，无法执行 Agent 节点 ${node.id}",
            )
        val prompt = TemplateResolver.resolve(node.prompt, ctx)
        val system = node.systemPrompt?.let { TemplateResolver.resolve(it, ctx) }

        val messages = buildList<LlmMessage> {
            if (system != null) add(LlmMessage.System(system))
            add(LlmMessage.User(prompt))
        }
        val response = try {
            client.chat(messages = messages, temperature = node.temperature, maxTokens = node.maxTokens)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw ToolFailureException(
                ToolResult.failure("LLM 调用失败: ${e.message}", ToolErrorCode.UNAVAILABLE),
            )
        }
        val content = response.content?.trim()
        if (content.isNullOrBlank()) {
            throw ToolFailureException(
                ToolResult.failure("Agent 节点 ${node.id} 得到空回复", ToolErrorCode.INTERNAL),
            )
        }
        return JsonPrimitive(content)
    }

    private fun executeSwitchNode(node: WorkflowNode.Switch, handle: RunHandle, ctx: ResolveContext): JsonElement {
        val value = TemplateResolver.resolve(node.expression, ctx)
        val branch = node.cases.firstOrNull { it.value == value }?.branch ?: node.defaultBranch
        // 路由决策持久化（恢复后不重判 —— 路由即状态）
        updateNode(handle, node.id) { old -> old.copy(branch = branch) }
        return JsonPrimitive(value)
    }

    private fun executeSetVarNode(node: WorkflowNode.SetVar, handle: RunHandle, ctx: ResolveContext): JsonElement {
        val resolved = node.assignments.mapValues { (_, template) ->
            JsonPrimitive(TemplateResolver.resolve(template, ctx))
        }
        synchronized(stateLock) {
            handle.state = handle.state.copy(
                vars = JsonObject(handle.state.vars.toMap() + resolved),
            )
            runStore.save(handle.state)
        }
        return JsonPrimitive(resolved.keys.joinToString(","))
    }

    // ------------------------------------------------------------------
    // 就绪集计算（纯函数，只读持久化状态）
    // ------------------------------------------------------------------

    /**
     * 就绪 = PENDING 且所有入边激活：
     * - start 边恒激活；
     * - 普通边：来源 SUCCEEDED（switch 来源需尚未产出路由决策——见下）或 SKIPPED；
     *   switch 来源一旦路由完成，普通出边视为未选中（只有 branch 匹配边激活）；
     * - branch 边（非 error）：来源 SUCCEEDED 且其路由决策 == branch；
     * - error 边：来源 FAILED。
     */
    internal fun computeReady(state: WorkflowRunState): List<WorkflowNode> {
        val nodesById = state.definition.nodes.associateBy { it.id }
        val backEdges = backEdgeIndices(state.definition)
        val incoming = state.definition.edges.withIndex()
            .filter { it.value.to != WorkflowEdge.END }
            .groupBy { it.value.to }

        fun satisfied(nodeId: String): Boolean {
            val edges = incoming[nodeId] ?: return false
            // 回边不参与就绪判定（否则环入口永不被满足）—— 只参与重入触发
            return edges.filter { it.index !in backEdges }.all { edgeActive(it.value, state, nodesById) }
        }

        return state.definition.nodes.filter { node ->
            (state.nodeStates[node.id]?.status ?: NodeStatus.PENDING) == NodeStatus.PENDING &&
                satisfied(node.id)
        }
    }

    /**
     * 静态回边识别（Tarjan DFS 染色）：从 START 深度优先，指向**栈上灰节点**的边
     * 是回边（每个环恰好一条）；自环天然命中。回边语义 = 循环重入信号，不是依赖屏障。
     */
    internal fun backEdgeIndices(def: WorkflowDefinition): Set<Int> {
        val adjacency = def.edges.withIndex().groupBy({ it.value.from }, { it })
        val WHITE = 0
        val GRAY = 1
        val BLACK = 2
        val color = mutableMapOf<String, Int>()
        val back = mutableSetOf<Int>()

        fun dfs(u: String) {
            color[u] = GRAY
            for (indexed in adjacency[u].orEmpty()) {
                val v = indexed.value.to
                if (v == WorkflowEdge.END) continue
                when (color[v]) {
                    null, WHITE -> dfs(v)
                    GRAY -> back += indexed.index // 指向栈上节点 → 闭环
                    else -> Unit // BLACK：树 / 前向 / 交叉边
                }
            }
            color[u] = BLACK
        }

        dfs(WorkflowEdge.START)
        // 不可达节点（validate 已拒，防御性补扫保持确定性）
        for (n in def.nodes) {
            if (color[n.id] == null || color[n.id] == WHITE) dfs(n.id)
        }
        return back
    }

    /** 单条入边是否激活（computeReady 与 rearmLoopNodes 共用同一判定 —— 语义不漂移）。 */
    private fun edgeActive(edge: WorkflowEdge, state: WorkflowRunState, nodesById: Map<String, WorkflowNode>): Boolean {
        if (edge.from == WorkflowEdge.START) return true
        val fromState = state.nodeStates[edge.from] ?: return false
        val fromNode = nodesById[edge.from] ?: return false
        return when {
            edge.branch == WorkflowEdge.ERROR_BRANCH -> fromState.status == NodeStatus.FAILED

            edge.branch != null -> fromState.status == NodeStatus.SUCCEEDED &&
                fromState.branch == edge.branch

            else -> when (fromState.status) {
                NodeStatus.SUCCEEDED -> fromNode !is WorkflowNode.Switch || fromState.branch == null
                NodeStatus.SKIPPED -> true
                else -> false
            }
        }
    }

    /** 失败且无 error 出路的节点（收尾时判运行失败的依据）。 */
    private fun firstUnhandledFailure(state: WorkflowRunState): Pair<String, NodeRunState>? {
        return state.nodeStates.entries.firstOrNull { (id, ns) ->
            ns.status == NodeStatus.FAILED &&
                state.definition.edges.none { it.from == id && it.branch == WorkflowEdge.ERROR_BRANCH }
        }?.toPair()
    }

    /**
     * 回边重入（Pregel 式循环）：某边激活且其目标已终态、但目标完成于来源完成**之前**
     * （finishedStep 严格更小）→ 把目标重置 PENDING 重新执行。maxSteps 是唯一护栏 ——
     * agent 自主循环可能不收敛，熔断即终止。
     *
     * 线性 / 分支图天然不触发（下游总比上游后完成）；只有显式回边才会循环。
     */
    internal fun rearmLoopNodes(state: WorkflowRunState): WorkflowRunState {
        val nodesById = state.definition.nodes.associateBy { it.id }
        var next: WorkflowRunState? = null
        for (edge in state.definition.edges) {
            if (edge.from == WorkflowEdge.START || edge.from == WorkflowEdge.END) continue
            if (edge.to == WorkflowEdge.END || edge.to == WorkflowEdge.START) continue
            if (!edgeActive(edge, state, nodesById)) continue
            val from = state.nodeStates[edge.from] ?: continue
            val to = state.nodeStates[edge.to] ?: continue
            if (to.status == NodeStatus.PENDING || to.status == NodeStatus.RUNNING) continue
            val fromStep = from.finishedStep ?: continue
            val toStep = to.finishedStep ?: continue
            // 通用重入判据：目标完成于来源完成之前且边仍激活 → 目标重新执行。
            // 线性/扇出图天然不触发（下游总比上游后完成）；显式回边（或其前向半边）触发循环。
            val shouldRearm = edge.to == edge.from || toStep < fromStep // 自环：完成即重入
            if (shouldRearm) {
                val base = next ?: state
                next = base.copy(
                    nodeStates = base.nodeStates + (edge.to to NodeRunState(NodeStatus.PENDING)),
                )
            }
        }
        return next ?: state
    }

    // ------------------------------------------------------------------
    // 状态更新（互斥 + 即时落盘）
    // ------------------------------------------------------------------

    private fun updateNode(
        handle: RunHandle,
        nodeId: String,
        transform: (NodeRunState) -> NodeRunState,
    ) {
        synchronized(stateLock) {
            val current = handle.state.nodeStates[nodeId] ?: NodeRunState(NodeStatus.PENDING)
            handle.state = handle.state.copy(
                nodeStates = handle.state.nodeStates + (nodeId to transform(current)),
            )
            runStore.save(handle.state)
        }
    }

    private fun finishRun(handle: RunHandle, status: RunStatus, message: String?) {
        synchronized(stateLock) {
            handle.state = handle.state.copy(
                status = status,
                message = message,
                finishedAtMs = clock(),
                // FAIL_RUN 取消并行兄弟时把它们收拢为 SKIPPED（终态不留 RUNNING 残影）
                nodeStates = if (status == RunStatus.FAILED) {
                    handle.state.nodeStates.mapValues { (_, ns) ->
                        if (ns.status == NodeStatus.RUNNING) {
                            ns.copy(status = NodeStatus.SKIPPED, errorText = ns.errorText ?: "并行节点失败连带取消")
                        } else {
                            ns
                        }
                    }
                } else {
                    handle.state.nodeStates
                },
            )
            runStore.save(handle.state)
        }
        onRunFinished?.invoke(handle.state.definitionId, handle.state)
    }

    private fun parkForHuman(handle: RunHandle, node: WorkflowNode.Human) {
        synchronized(stateLock) {
            handle.state = handle.state.copy(status = RunStatus.WAITING_HUMAN, waitingNodeId = node.id)
            runStore.save(handle.state)
        }
    }

    private fun finishCancelled(handle: RunHandle, reason: String) {
        synchronized(stateLock) {
            handle.state = handle.state.copy(
                status = RunStatus.CANCELLED,
                message = reason,
                finishedAtMs = clock(),
                nodeStates = handle.state.nodeStates.mapValues { (_, ns) ->
                    if (ns.status == NodeStatus.RUNNING) ns.copy(status = NodeStatus.PENDING) else ns
                },
            )
            runStore.save(handle.state)
            onRunFinished?.invoke(handle.state.definitionId, handle.state)
        }
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private fun buildContext(state: WorkflowRunState): ResolveContext = ResolveContext(
        params = state.params,
        vars = state.vars,
        nodeOutputs = state.nodeStates.mapValues { it.value.output },
    )

    private fun resolveText(template: String, state: WorkflowRunState): String = try {
        TemplateResolver.resolve(template, buildContext(state))
    } catch (e: Exception) {
        template // 等待提示解析失败给原文（人总能看懂原文）
    }

    private fun isRetryable(e: Exception): Boolean = when (e) {
        is TemplateResolver.TemplateResolutionException -> false
        is NodeTimeoutException -> true
        is ToolFailureException -> e.result.error?.code in RETRYABLE_CODES
        else -> true // LLM 网络异常等未知 → 视为瞬时
    }

    private fun buildSummary(state: WorkflowRunState): String =
        "完成 ${state.succeededCount}/${state.definition.nodes.size} 个节点" +
            (if (state.skippedCount > 0) "，跳过 ${state.skippedCount}" else "") +
            "，${state.stepCounter} 个超步"

    private fun preview(output: JsonElement): String {
        val text = when (output) {
            is JsonPrimitive -> output.content
            else -> output.toString()
        }
        return if (text.length > 80) text.take(77) + "..." else text
    }

    private fun validateParams(definition: WorkflowDefinition, params: Map<String, String>) {
        val problems = mutableListOf<String>()
        for (spec in definition.params) {
            val provided = params[spec.name]
            if (provided == null || provided.isBlank()) {
                if (spec.required && spec.defaultValue == null) {
                    problems += "缺少必填参数: ${spec.name}（${spec.description}）"
                }
            }
        }
        val unknown = params.keys.filter { name -> definition.params.none { it.name == name } }
        if (unknown.isNotEmpty()) {
            problems += "未知参数（未在定义声明）: ${unknown.joinToString(", ")}"
        }
        if (problems.isNotEmpty()) throw IllegalArgumentException(problems.joinToString("; "))
    }

    companion object {
        private val RETRYABLE_CODES = setOf(ToolErrorCode.TIMEOUT, ToolErrorCode.RATE_LIMITED, ToolErrorCode.UNAVAILABLE)
        private const val INFINITE = Long.MAX_VALUE
    }
}
