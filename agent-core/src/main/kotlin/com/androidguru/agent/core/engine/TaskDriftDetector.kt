package com.androidguru.agent.core.engine

import com.androidguru.agent.llm.LlmClient
import com.androidguru.agent.llm.LlmMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 任务跑偏检测器 —— 回答「多步骤复杂任务执行到一半，还在做用户要的事吗？」
 *
 * 两层机制（均为**建议性**注入，不阻断执行 —— 与循环护栏同一纪律）：
 *
 * 1. **进展停滞检测（启发式，零成本）**：连续 [AgentConfig.driftStagnationIterations] 轮
 *    迭代中，工具调用虽有发生但**没有任何进展信号**（默认进展信号 = 至少一次成功调用；
 *    宿主可注入更精确的判定，如「计划状态变化」「文件落盘」）→ 疑似原地打转。
 *
 * 2. **目标对齐检查（LLM 周期抽查）**：每 [AgentConfig.driftCheckInterval] 轮迭代，
 *    用一次轻量 LLM 调用对照「原始目标 + 最近动作摘要」输出对齐判定
 *    （`{"aligned": false, "reason": "..."}`）→ 未对齐时向模型注入拉回提示。
 *    解析失败 / LLM 不可用时静默跳过（检测器永不打断主任务）。
 *
 * 配合使用：循环护栏管「重复」，跑偏检测管「发散」—— 两者正交。
 */
class TaskDriftDetector(
    private val llmClient: LlmClient,
    private val config: AgentConfig,
) {

    /** 目标对齐判定结果。 */
    data class DriftVerdict(
        val aligned: Boolean,
        val reason: String,
    )

    /** 单轮迭代的动作记录。 */
    private class IterationActivity {
        var toolCallCount = 0
        var hasProgress = false
        var lastCalls = mutableListOf<String>()
    }

    /** 最近动作摘要上限（LLM 检查用的动作条数）。 */
    private val maxActionSummary = 12

    private var currentIteration = 0
    private var currentActivity = IterationActivity()
    private var stagnationCount = 0
    private val actionHistory = ArrayDeque<String>()

    /** 是否已因停滞发出告警（同一次停滞只告警一次，直到出现新进展重置）。 */
    private var stagnationAlerted = false

    // ------------------------------------------------------------------
    // 信号接入（引擎每轮调用）
    // ------------------------------------------------------------------

    /** 一轮迭代开始。 */
    fun onIterationStart(iteration: Int) {
        currentIteration = iteration
        currentActivity = IterationActivity()
    }

    /** 记录一次工具调用（引擎在每次工具执行后调用）。 */
    fun onToolCall(toolName: String, arguments: String, progressed: Boolean) {
        currentActivity.toolCallCount++
        if (progressed) currentActivity.hasProgress = true
        currentActivity.lastCalls += summarizeCall(toolName, arguments)
    }

    /**
     * 一轮迭代结束：更新停滞计数与动作历史。
     *
     * @return 停滞告警（连续停滞达阈值且本轮仍未有进展，且尚未告警过）。
     */
    fun onIterationEnd(): String? {
        actionHistory.addAll(currentActivity.lastCalls)
        while (actionHistory.size > maxActionSummary) actionHistory.removeFirst()

        if (currentActivity.toolCallCount > 0 && !currentActivity.hasProgress) {
            stagnationCount++
        } else {
            // 有进展（或本轮无调用 = LLM 在思考 / 收尾）：重置停滞与告警
            stagnationCount = 0
            stagnationAlerted = false
        }

        if (stagnationCount >= config.driftStagnationIterations && !stagnationAlerted) {
            stagnationAlerted = true
            return "已连续 $stagnationCount 轮没有任何成功进展，" +
                "但仍在持续调用工具。请停下来对照任务目标重新评估：当前步骤是否真的在推进目标？" +
                "若已偏离，回到计划中下一个未完成项；若被卡住，说明原因并用 ask_user 求助。"
        }
        return null
    }

    /** 本轮迭代是否应做 LLM 目标对齐抽查（每 [AgentConfig.driftCheckInterval] 轮一次）。 */
    fun shouldCheckAlignment(): Boolean {
        if (config.driftCheckInterval <= 0) return false
        if (currentActivity.toolCallCount == 0 && actionHistory.isEmpty()) return false
        return currentIteration > 0 && currentIteration % config.driftCheckInterval == 0
    }

    /**
     * LLM 目标对齐抽查：原始目标 + 最近动作 → aligned/reason。
     * 解析失败 / LLM 异常返回 null（静默跳过，永不打断主任务）。
     */
    suspend fun checkAlignment(goal: String): DriftVerdict? = try {
        val response = llmClient.chat(
            messages = listOf(LlmMessage.System(alignmentPrompt(goal))),
            tools = emptyList(),
            temperature = 0.0,
            maxTokens = 200,
        )
        val text = response.content?.trim().orEmpty()
        parseVerdict(text)
    } catch (e: Exception) {
        null
    }

    /** 最近动作摘要（引擎注入对齐提示时附带）。 */
    fun recentActions(): List<String> = actionHistory.toList()

    /** 重置（新任务开始）。 */
    fun reset() {
        currentIteration = 0
        currentActivity = IterationActivity()
        stagnationCount = 0
        actionHistory.clear()
        stagnationAlerted = false
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun alignmentPrompt(goal: String): String = buildString {
        appendLine("你是一个 Android Agent 的目标对齐审查器。判断 Agent 最近执行的动作是否仍在推进原始目标。")
        appendLine()
        appendLine("原始目标: $goal")
        appendLine()
        appendLine("最近执行的动作（按时间序）:")
        if (actionHistory.isEmpty()) {
            appendLine("（尚无工具调用）")
        } else {
            for (action in actionHistory) appendLine("- $action")
        }
        appendLine()
        appendLine("只输出一个 JSON 对象，不要其他文字:")
        appendLine("""{"aligned": true/false, "reason": "一句话理由"}""")
        appendLine()
        appendLine("判定标准：动作序列的目标指向与原始目标一致（中间步骤合理）= aligned；")
        appendLine("动作在做与目标无关的事、绕开关键步骤空转、或在无意义地探索 = not aligned。")
    }

    private fun parseVerdict(text: String): DriftVerdict? = try {
        val jsonText = extractJson(text) ?: return null
        val obj = Json.parseToJsonElement(jsonText).jsonObject
        val aligned = obj["aligned"]?.jsonPrimitive?.booleanOrNull ?: return null
        val reason = obj["reason"]?.jsonPrimitive?.content ?: ""
        DriftVerdict(aligned = aligned, reason = reason)
    } catch (e: Exception) {
        null
    }

    private fun extractJson(text: String): String? {
        // 优先取 fenced ```json ...```；否则取首个 {...} 平衡段
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(text)?.groupValues?.get(1)?.trim()
        val candidate = fenced ?: run {
            val start = text.indexOf('{')
            if (start < 0) return null
            var depth = 0
            var end = -1
            for (i in start until text.length) {
                when (text[i]) {
                    '{' -> depth++
                    '}' -> {
                        depth--
                        if (depth == 0) {
                            end = i
                            break
                        }
                    }
                }
            }
            if (end > start) text.substring(start, end + 1) else null
        } ?: return null
        return candidate.takeIf { it.startsWith("{") }
    }

    private fun summarizeCall(toolName: String, arguments: String): String {
        val gist = arguments.replace(Regex("\\s+"), " ").trim().removePrefix("{").removeSuffix("}")
        return "$toolName(${gist.take(48)})"
    }
}
