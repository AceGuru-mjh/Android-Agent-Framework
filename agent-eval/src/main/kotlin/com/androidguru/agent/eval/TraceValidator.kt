package com.androidguru.agent.eval

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 轨迹校验器：[TraceRecord] × [EvalTask.criteria] → 结构化的通过/失败判定。
 *
 * 每条判据独立报告（失败时能定位到「缺了什么 / 多了什么 / 哪条约束没过」），
 * 失败原因直接可用于调优：缺必做调用 → 提示词没引导到位；触禁止调用 →
 * 安全边界太松；循环 → 护栏参数要收紧。
 */
object TraceValidator {

    /** 校验结果。 */
    data class ValidationResult(
        val passed: Boolean,
        /** 未满足的判据说明（人话，可直接进报告）。 */
        val failures: List<String>,
        /** 通过的独立判据数 / 总判据数。 */
        val passedChecks: Int,
        val totalChecks: Int,
    )

    /** 校验一条轨迹（合取语义：任一判据不满足即失败）。 */
    fun validate(task: EvalTask, trace: TraceRecord): ValidationResult {
        val failures = mutableListOf<String>()
        var total = 0
        var passed = 0

        fun check(ok: Boolean, failMessage: () -> String) {
            total++
            if (ok) passed++ else failures += failMessage()
        }

        val criteria = task.criteria

        // 1) 必做调用
        for (required in criteria.requiredCalls) {
            check(matchCount(trace, required) >= required.minCount) {
                val argsNote = required.argChecks.joinToString("; ") { "${it.key} ${it.op} ${it.expected}" }
                "缺少必做调用 ${required.tool}" + if (argsNote.isEmpty()) "" else "（$argsNote）" +
                    "：实际 ${matchCount(trace, required)} 次 / 要求 ${required.minCount} 次"
            }
        }

        // 2) 禁止调用
        for (forbidden in criteria.forbiddenCalls) {
            check(matchCount(trace, forbidden) == 0) {
                val hit = firstMatch(trace, forbidden)
                "出现禁止调用 ${hit?.name ?: forbidden.tool}" +
                    "（第 ${hit?.iteration} 轮）"
            }
        }

        // 3) 轮数上限
        criteria.maxIterations?.let { max ->
            check(trace.iterations <= max) {
                "迭代轮数超限：${trace.iterations} > $max"
            }
        }

        // 4) 循环护栏
        if (criteria.requireNoLoop) {
            check(!trace.loopDetected) { "期间触发循环护栏（疑似死循环）" }
        }

        // 5) 收尾文本
        for (pattern in criteria.finalTextPatterns) {
            val text = trace.finalText.orEmpty()
            check(pattern.containsMatchIn(text)) {
                "最终回复未命中要求的文本模式 /${pattern.pattern}/（回复：${text.take(80)}…）"
            }
        }

        // 6) 最少成功调用
        criteria.minSuccessfulCalls?.let { min ->
            check(trace.toolCalls.count { it.ok } >= min) {
                "成功调用次数不足：${trace.toolCalls.count { it.ok }} < $min"
            }
        }

        // 7) 必须正常收尾（未 Complete = 预算耗尽 / 出错，均判失败）
        check(trace.completed) {
            "任务未正常收尾（无 Complete：预算耗尽或引擎错误）"
        }

        return ValidationResult(
            passed = failures.isEmpty(),
            failures = failures,
            passedChecks = passed,
            totalChecks = total,
        )
    }

    // ------------------------------------------------------------------
    // 匹配
    // ------------------------------------------------------------------

    /** 轨迹中满足 [matcher]（工具名 + 参数约束）的调用次数。 */
    fun matchCount(trace: TraceRecord, matcher: CallMatcher): Int =
        trace.toolCalls.count { matches(it, matcher) }

    private fun firstMatch(trace: TraceRecord, matcher: CallMatcher): ToolCallEvent? =
        trace.toolCalls.firstOrNull { matches(it, matcher) }

    private fun matches(call: ToolCallEvent, matcher: CallMatcher): Boolean {
        // 工具名正则按「部分匹配」语义：出现即命中（如 delete 命中 delete_file）
        val toolOk = if (matcher.toolRegex) {
            Regex(matcher.tool).containsMatchIn(call.name)
        } else {
            call.name == matcher.tool
        }
        if (!toolOk) return false
        if (matcher.argChecks.isEmpty()) return true

        // 参数约束：全部满足（解析失败视为不满足 —— 宁可判负也不放过错误调用）
        return matcher.argChecks.all { check -> argMatches(call.arguments, check) }
    }

    /** 从调用参数 JSON 取顶层键的字符串值，按算子比较。 */
    private fun argMatches(arguments: String, check: ArgCheck): Boolean {
        val value = try {
            val obj = Json.parseToJsonElement(arguments).jsonObject
            val primitive = obj[check.key] as? JsonPrimitive ?: return false
            primitive.content
        } catch (e: Exception) {
            return false
        }
        return when (check.op) {
            MatchOp.CONTAINS -> value.contains(check.expected, ignoreCase = true)
            MatchOp.EQUALS -> value.equals(check.expected, ignoreCase = true)
            MatchOp.REGEX -> Regex(check.expected).containsMatchIn(value)
        }
    }
}
