package com.androidguru.agent.workflow

import com.androidguru.agent.llm.LlmClient
import com.androidguru.agent.llm.LlmMessage
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 工作流蒸馏器 —— 从**成功**的会话轨迹提炼可复用工作流（学习闭环）。
 *
 * 两条路径（对齐 OS-Copilot「生成时泛化」与 mobilerun「宏录制」）：
 * 1. **LLM 蒸馏**（[client] 提供时）：渲染工具调用轨迹 → 提示词要求输出严格
 *    JSON 工作流（泛化纪律：具体值 → 形参、步骤抽象化）→ [WorkflowCodec]
 *    宽容解析 + 静态校验 → 候选工作流；
 * 2. **宏录制回退**（无 client / 调用失败 / 输出不合法）：确定性提炼 ——
 *    轨迹顺序转为 ToolNode 链，实参原样固化（连续重复调用去重，防循环护栏
 *    痕迹），产出「参数为零的宏」。质量降级但**零依赖可用**（与
 *    MemoryExtractor 的启发式回退同一哲学：没有 LLM 也永远有产出）。
 *
 * 沉淀纪律（Mobile-Agent-E）：**只从成功任务蒸馏** —— 失败轨迹交给
 * agent-memory 的 LESSON 记忆，不产出工作流（坏流程复用是净损失）。
 */
class WorkflowExtractor(
    private val client: LlmClient? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 轨迹渲染上限（超长截断保首尾）。 */
    private val maxInputChars: Int = 24_000,
    /** 宏回退的最大节点数（超长轨迹截断，防巨石工作流）。 */
    private val maxMacroNodes: Int = 20,
) {

    /** 蒸馏（不入库）。无可蒸馏轨迹（无工具调用）返回 null。 */
    suspend fun extract(messages: List<LlmMessage>, goal: String): WorkflowDefinition? {
        val trace = buildTrace(messages)
        if (trace.size < MIN_TRACE_CALLS) return null

        val viaLlm = client?.let { tryExtractViaLlm(it, trace, goal) }
        return viaLlm ?: macroFallback(trace, goal)
    }

    /** 蒸馏 + 入库（CANDIDATE 态）。返回 null = 无可蒸馏 / 全部失败。 */
    suspend fun extractAndSave(
        messages: List<LlmMessage>,
        goal: String,
        library: WorkflowLibrary,
    ): SavedWorkflow? {
        val definition = extract(messages, goal) ?: return null
        return try {
            library.save(definition, sourceRunId = null)
        } catch (e: Exception) {
            null // 定义校验失败（LLM 产物不合格）不入库
        }
    }

    // ------------------------------------------------------------------
    // 轨迹构建
    // ------------------------------------------------------------------

    /**
     * 会话消息 → 工具调用轨迹：Assistant.toolCalls 与其后的 Tool 结果配对。
     * 结果取前 200 字符（蒸馏只需要「成没成 / 大致产出」，不需要全文）。
     */
    internal fun buildTrace(messages: List<LlmMessage>): List<TraceCall> {
        val trace = mutableListOf<TraceCall>()
        val pendingCalls = LinkedHashMap<String, String>()

        for (message in messages) {
            when (message) {
                is LlmMessage.Assistant -> {
                    for (call in message.toolCalls) {
                        pendingCalls[call.id] = call.name
                        trace.add(TraceCall(toolId = call.name, arguments = call.arguments, result = null))
                    }
                }
                is LlmMessage.Tool -> {
                    if (pendingCalls.containsKey(message.toolCallId)) {
                        val idx = trace.indexOfLast { it.toolId == pendingCalls[message.toolCallId] && it.result == null }
                        if (idx >= 0) {
                            trace[idx] = trace[idx].copy(result = message.content.take(RESULT_SNIPPET_CHARS))
                        }
                    }
                }
                else -> Unit
            }
        }
        return trace
    }

    /** 一次工具调用（含结果摘要）。 */
    data class TraceCall(val toolId: String, val arguments: String, val result: String?)

    // ------------------------------------------------------------------
    // LLM 蒸馏路径
    // ------------------------------------------------------------------

    private suspend fun tryExtractViaLlm(
        client: LlmClient,
        trace: List<TraceCall>,
        goal: String,
    ): WorkflowDefinition? {
        return try {
            val rendered = renderTrace(trace)
            if (rendered.isBlank()) return null

            val response = client.chat(
                messages = listOf(
                    LlmMessage.System(DISTILL_SYSTEM_PROMPT),
                    LlmMessage.User(
                        "任务目标：$goal\n\n工具调用轨迹（按时间序）：\n$rendered\n\n" +
                            "请输出蒸馏后的工作流 JSON（只要 JSON，不要任何其他文字）。",
                    ),
                ),
                temperature = 0.1,
            )
            val text = response.content?.trim() ?: return null
            val jsonText = extractJsonBlock(text) ?: return null
            val definition = WorkflowCodec.decodeDefinition(
                Json.parseToJsonElement(jsonText).jsonObject,
            )
            definition.validate() // 不合法 → 抛 → 回退宏录制
            definition
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null // 宽容：LLM 路径任何失败回退宏录制（质量降级，可用性保底）
        }
    }

    /** 剥代码围栏 / 截取首个 { 到末个 }（与 MemoryExtractor 同一容错家族）。 */
    internal fun extractJsonBlock(text: String): String? {
        val stripped = text.removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
        val start = stripped.indexOf('{')
        val end = stripped.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return stripped.substring(start, end + 1)
    }

    private fun renderTrace(trace: List<TraceCall>): String {
        val sb = StringBuilder()
        trace.forEachIndexed { idx, call ->
            sb.appendLine("[$idx] tool=${call.toolId} args=${call.arguments.take(300)}")
            call.result?.let { sb.appendLine("    => ${it.take(RESULT_SNIPPET_CHARS)}") }
        }
        val rendered = sb.toString()
        if (rendered.length <= maxInputChars) return rendered
        // 保头尾（开头的感知 + 结尾的结果最有信息量）
        val half = maxInputChars / 2
        return rendered.take(half) + "\n...（中段截断）...\n" + rendered.takeLast(half)
    }

    // ------------------------------------------------------------------
    // 宏录制回退
    // ------------------------------------------------------------------

    /**
     * 确定性回退：轨迹 → 顺序 ToolNode 链。
     * - 连续相同（toolId + arguments）调用折叠（循环护栏 / 重试痕迹）；
     * - arguments 不是合法 JSON 对象 → 整体作为字符串参数 `{"input": ...}`？
     *   不 —— 非法参数直接跳过该调用（无法结构化复现）；
     * - 超过 [maxMacroNodes] 截断并在描述中注明。
     */
    internal fun macroFallback(trace: List<TraceCall>, goal: String): WorkflowDefinition? {
        val deduped = mutableListOf<TraceCall>()
        for (call in trace) {
            val last = deduped.lastOrNull()
            if (last != null && last.toolId == call.toolId && last.arguments == call.arguments) continue
            deduped.add(call)
        }

        val toolCalls = deduped.filter { call ->
            try {
                Json.parseToJsonElement(call.arguments.ifBlank { "{}" }) is JsonObject
            } catch (e: Exception) {
                false
            }
        }.take(maxMacroNodes)

        if (toolCalls.size < MIN_TRACE_CALLS) return null

        val nodes = toolCalls.mapIndexed { idx, call ->
            WorkflowNode.Tool(
                id = "n${idx + 1}",
                toolId = call.toolId,
                arguments = Json.parseToJsonElement(call.arguments.ifBlank { "{}" }).jsonObject,
                description = "宏录制步骤 ${idx + 1}（实参原样固化）",
                retry = DEFAULT_MACRO_RETRY,
            )
        }
        val edges = buildList {
            add(WorkflowEdge(WorkflowEdge.START, "n1"))
            for (i in 1 until nodes.size) {
                add(WorkflowEdge(nodes[i - 1].id, nodes[i].id))
            }
            add(WorkflowEdge(nodes.last().id, WorkflowEdge.END))
        }
        return WorkflowDefinition(
            id = sanitizeId(goal),
            name = goal.take(NAME_MAX_CHARS).ifBlank { "录制工作流" },
            description = "从成功轨迹宏录制（无 LLM 蒸馏回退，实参固化，复用前建议人工参数化）" +
                (if (deduped.size > toolCalls.size) "；原始轨迹 ${deduped.size} 步，截断为 ${toolCalls.size} 步" else ""),
            params = emptyList(),
            nodes = nodes,
            edges = edges,
            maxSteps = nodes.size * 2 + 10,
        )
    }

    // ------------------------------------------------------------------
    // 辅助
    // ------------------------------------------------------------------

    private fun sanitizeId(goal: String): String {
        val slug = goal.lowercase()
            .replace(Regex("[^a-z0-9_\\-\\u4e00-\\u9fa5]+"), "_")
            .trim('_')
            .take(48)
        return if (slug.matches(WorkflowDefinition.ID_PATTERN)) slug else "recorded_workflow"
    }

    private companion object {
        const val MIN_TRACE_CALLS = 2
        const val RESULT_SNIPPET_CHARS = 200
        const val NAME_MAX_CHARS = 60
        val DEFAULT_MACRO_RETRY = RetryPolicy(maxAttempts = 2, initialDelayMs = 200, maxDelayMs = 2_000)

        val DISTILL_SYSTEM_PROMPT = """
            你是工作流蒸馏器。把一次成功的任务执行轨迹提炼为**可复用、参数化**的工作流 JSON。

            输出格式（严格 JSON，无其他文字）：
            {
              "id": "snake_case 语义化英文 id（望名生义，如 send_report_to_channel）",
              "name": "简短名称",
              "description": "一句话：什么场景下应该复用这个工作流",
              "params": [{"name": "参数名", "description": "参数含义", "required": true, "default": null}],
              "nodes": [{"kind": "tool", "id": "n1", "toolId": "工具id", "arguments": {...}, "description": "..."}],
              "edges": [{"from": "__start__", "to": "n1"}, {"from": "n1", "to": "__end__"}]
            }

            蒸馏纪律（按优先级）：
            1. **泛化**：轨迹里的具体值（人名/文件名/日期/数字）抽象为 params 形参，
               节点 arguments 里用 "${'$'}{params.形参名}" 引用；常量保留字面量；
            2. **可复用**：只保留可重复的流程骨架；一次性探索（查看列表、试错调用）
               剔除或合并；失败后重试的调用只保留成功路径；
            3. **节点**：kind=tool；id 用 n1..nN 顺序编号；每个节点 description 写清意图；
            4. **连线**：edges 显式写出（含 __start__ 起点与 __end__ 终点）；
               有分支时可用 kind=switch 节点（expression 引用 "${'$'}{vars.变量}"，cases 给出分支）；
            5. 轨迹不值得复用（纯问答、单步调用）时输出 {"skip": true}；
            6. 模板语法：${'$'}{params.x}、${'$'}{vars.y}、${'$'}{nodes.n1.output}（上游输出）。
        """.trimIndent()
    }
}
