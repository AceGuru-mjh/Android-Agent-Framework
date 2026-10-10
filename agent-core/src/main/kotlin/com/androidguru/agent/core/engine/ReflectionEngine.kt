package com.androidguru.agent.core.engine

import com.androidguru.agent.llm.LlmClient
import com.androidguru.agent.llm.LlmMessage
import com.androidguru.agent.tools.ToolErrorCode
import com.androidguru.agent.tools.ToolResult

/**
 * 反思引擎（Reflexion）—— 把「失败盲重试」升级为「失败驱动的策略修正」。
 *
 * 触发条件（满足其一）：
 * - 连续失败工具调用达到 [AgentConfig.reflectionFailureThreshold]；
 * - 引擎护栏检出循环（[ToolCallLoopDetector.detectCycle] 命中 / 单签名重复达阈值）。
 *
 * 反思动作：
 * 1. 汇总最近失败的调用（工具名 / 参数摘要 / 错误码与文本）与循环信号；
 * 2. 用一次**独立的轻量 LLM 调用**（无工具、低 token 上限）分析失败原因并产出
 *    下一步的修正策略（≤ 120 字）；
 * 3. LLM 不可用 / 调用失败时回退**确定性启发式**（按 [ToolErrorCode] 分诊），
 *    反思永不因自身故障打断主任务；
 * 4. 产出物（lesson）由引擎注入为系统消息，模型下一轮可见。
 *
 * 反思节流：触发后 [cooldownTurns] 轮内不重复触发（同一故障群不反复打扰）。
 */
class ReflectionEngine(
    private val llmClient: LlmClient,
    private val config: AgentConfig,
) {

    /** 一次失败调用的最小记录（参数截断，避免反思自身吃掉上下文）。 */
    data class FailedCall(
        val toolName: String,
        val argumentsExcerpt: String,
        val errorCode: ToolErrorCode?,
        val errorExcerpt: String,
    )

    /** 反思产出。 */
    data class ReflectionOutcome(
        /** 注入模型可见上下文的修正策略（≤ 120 字）。 */
        val lesson: String,
        /** true = LLM 分析产出；false = 启发式回退。 */
        val byLlm: Boolean,
        val triggerReason: String,
    )

    private val recentFailures = ArrayDeque<FailedCall>()
    private var consecutiveFailures = 0
    private var cooldownRemaining = 0

    /** 主循环每轮结束调用（冷却递减）。 */
    fun onIterationEnd() {
        if (cooldownRemaining > 0) cooldownRemaining--
    }

    /** 记录一次工具调用结果，维护连续失败计数与失败尾迹。 */
    fun recordResult(toolName: String, arguments: String, result: ToolResult) {
        if (result.ok) {
            consecutiveFailures = 0
            return
        }
        consecutiveFailures++
        recentFailures.addLast(
            FailedCall(
                toolName = toolName,
                argumentsExcerpt = arguments.take(120),
                errorCode = result.error?.code,
                errorExcerpt = result.content.take(200),
            ),
        )
        while (recentFailures.size > MAX_FAILURE_HISTORY) recentFailures.removeFirst()
    }

    /** 连续失败次数（供引擎判定阈值）。 */
    fun currentConsecutiveFailures(): Int = consecutiveFailures

    /** 是否应触发反思（连续失败达阈值 且 不在冷却期）。 */
    fun shouldReflect(): Boolean =
        consecutiveFailures >= config.reflectionFailureThreshold && cooldownRemaining == 0

    /** 是否处于反思冷却期外（循环护栏触发的反思复用同一冷却纪律）。 */
    fun canReflectNow(): Boolean = cooldownRemaining == 0

    /**
     * 执行反思：LLM 分析 → 启发式回退。永不抛异常（反思自身故障不打断主任务）。
     *
     * @param goal 当前任务目标（可为空，引擎尽力提取首条用户消息）。
     * @param loopSignal 若因循环护栏触发，携带循环描述。
     */
    suspend fun reflect(goal: String?, loopSignal: ToolCallLoopDetector.LoopSignal?): ReflectionOutcome {
        val reason = if (loopSignal != null) {
            "循环护栏：${loopSignal.description}（重复 ${loopSignal.repetitions} 遍）"
        } else {
            "连续 $consecutiveFailures 次工具调用失败"
        }
        // 进入冷却 + 失败尾迹清零（同一故障群只反思一次）
        cooldownRemaining = cooldownTurns
        consecutiveFailures = 0

        val llmLesson = try {
            reflectWithLlm(goal, reason)
        } catch (e: Exception) {
            null
        }
        val lesson = llmLesson ?: heuristicLesson(reason)

        recentFailures.clear()
        return ReflectionOutcome(
            lesson = lesson,
            byLlm = llmLesson != null,
            triggerReason = reason,
        )
    }

    // ------------------------------------------------------------------
    // LLM 反思（失败返回 null → 启发式）
    // ------------------------------------------------------------------

    private suspend fun reflectWithLlm(goal: String?, reason: String): String? = try {
        val response = llmClient.chat(
            messages = listOf(LlmMessage.System(reflectionPrompt(goal, reason))),
            tools = emptyList(),
            temperature = 0.2,
            maxTokens = config.reflectionMaxTokens,
        )
        val text = response.content?.trim().orEmpty()
        if (text.isBlank()) null else text.take(REFLECTION_LESSON_MAX_CHARS)
    } catch (e: Exception) {
        null
    }

    private fun reflectionPrompt(goal: String?, reason: String): String = buildString {
        appendLine("你是一个 Android Agent 的策略反思器。Agent 在执行任务时遇到持续失败，请分析原因并给出下一步修正策略。")
        appendLine()
        if (!goal.isNullOrBlank()) appendLine("任务目标: $goal")
        appendLine("触发原因: $reason")
        appendLine()
        appendLine("最近失败的调用:")
        if (recentFailures.isEmpty()) {
            appendLine("（无记录 —— 由循环护栏触发，说明调用虽成功但无进展）")
        } else {
            for (f in recentFailures.takeLast(5)) {
                appendLine("- ${f.toolName}(${f.argumentsExcerpt}) → ${f.errorCode ?: "UNKNOWN"}: ${f.errorExcerpt}")
            }
        }
        appendLine()
        appendLine("请输出（中文，不超过 120 字，直接给结论，不要客套）:")
        appendLine("1. 一句话诊断最可能的失败根因；")
        appendLine("2. 一句话给出下一步应该改用什么方法 / 参数 / 工具。")
    }

    // ------------------------------------------------------------------
    // 启发式回退（确定性分诊）
    // ------------------------------------------------------------------

    private fun heuristicLesson(reason: String): String {
        val diagnosis = recentFailures.lastOrNull()?.let { diagnose(it) } ?: "反复执行等价调用而未取得新进展"
        return "$reason。诊断: $diagnosis。修正建议: $HEURISTIC_FIX"
    }

    private fun diagnose(failure: FailedCall): String = when (failure.errorCode) {
        ToolErrorCode.NOT_FOUND ->
            "工具 ${failure.toolName} 不存在或目标对象（文件 / 应用 / 联系人等）不存在，" +
                "先用列表 / 搜索类工具确认实际名称再操作"

        ToolErrorCode.VALIDATION ->
            "工具 ${failure.toolName} 的参数校验失败，检查必填参数、类型与格式（如路径、JSON 结构）"

        ToolErrorCode.PERMISSION ->
            "操作被权限或审批闸门拒绝，先向用户说明并获得授权，或改用无风险等价工具"

        ToolErrorCode.TIMEOUT ->
            "工具 ${failure.toolName} 执行超时，命令可能挂起 —— 缩小操作范围或改用后台作业方式执行"

        ToolErrorCode.RATE_LIMITED ->
            "触发限流，放慢调用频率或合并批量操作"

        ToolErrorCode.UNAVAILABLE ->
            "工具 ${failure.toolName} 暂不可用（环境 / 依赖缺失），改用替代工具或先修复环境"

        ToolErrorCode.INTERNAL ->
            "工具 ${failure.toolName} 内部异常，重试一次仍失败则改用其他途径"

        ToolErrorCode.CANCELLED ->
            "调用被取消（可能是审批拒绝或超时），确认是否真的要继续该操作"

        ToolErrorCode.UNKNOWN ->
            "调用失败原因未知: ${failure.errorExcerpt.take(60)}，换一个途径验证环境状态"

        null -> "调用持续失败: ${failure.errorExcerpt.take(60)}"
    }

    companion object {
        private const val MAX_FAILURE_HISTORY = 8
        private const val REFLECTION_LESSON_MAX_CHARS = 400

        /** 触发后的反思冷却轮数。 */
        const val cooldownTurns = 3

        private const val HEURISTIC_FIX =
            "不要原样重试；先验证前置条件，再换工具或调参数；仍无进展则用 ask_user 求助。"
    }
}
