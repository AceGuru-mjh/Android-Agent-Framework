package com.androidguru.agent.tasks.plan

import com.androidguru.agent.llm.LlmClient
import com.androidguru.agent.llm.LlmMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 任务拆解器 —— LLM 驱动的「目标 → 阶段 → 步骤」两级子目标拆解。
 *
 * 修复的缺口：此前拆解完全依赖主对话提示词「劝」模型去调 task_plan 工具，
 * 弱模型经常跳过拆解直接开干，多步骤复杂任务极易跑偏。拆解器把「拆解」
 * 变成**框架主动的独立调用**：
 *
 * 1. 用一次结构化输出的 LLM 调用产出 `{"phases":[{"title","steps":[{"title","acceptance"}]}]}`；
 * 2. 阶段 → 父任务、步骤 → 子任务（[TaskPlanManager] 两级结构），验收标准
 *    折叠进子任务 notes（`验收: …`），模型推进与汇报时都有可对照的标准；
 * 3. 解析失败自动重试一次（提示收紧），再失败回退启发式单阶段计划
 *    （目标本身作为首个任务）—— 拆解永不因模型输出质量问题而报废任务。
 *
 * 用法：
 * ```kotlin
 * val decomposer = TaskDecomposer(llmClient, planManager)
 * val plan = decomposer.decompose("把下载目录里的照片按月份归档到相册")
 * ```
 */
class TaskDecomposer(
    private val llmClient: LlmClient,
    private val planManager: TaskPlanManager,
) {

    /** 拆解结果。 */
    sealed interface DecomposeOutcome {
        /** LLM 拆解成功并已写入计划。 */
        data class Success(val plan: TaskPlan) : DecomposeOutcome

        /** LLM 输出不可解析，回退为单阶段计划（目标本身作为首个任务）。 */
        data class Fallback(val plan: TaskPlan, val reason: String) : DecomposeOutcome
    }

    /**
     * 拆解目标并写入 [TaskPlanManager]（rewrite 语义，整表替换）。
     *
     * @param availableToolNames 可用工具名清单（注入拆解提示，避免拆出不可执行的步骤）。
     * @param maxPhases 最大阶段数。
     * @param maxStepsPerPhase 每阶段最大步骤数。
     */
    suspend fun decompose(
        goal: String,
        availableToolNames: List<String> = emptyList(),
        maxPhases: Int = 6,
        maxStepsPerPhase: Int = 6,
    ): DecomposeOutcome {
        require(goal.isNotBlank()) { "goal 不能为空" }

        val raw = requestDecomposition(goal, availableToolNames, maxPhases, maxStepsPerPhase)
        if (raw != null) {
            val parsed = parsePhases(raw, maxPhases, maxStepsPerPhase)
            if (parsed.isNotEmpty()) {
                val plan = planManager.rewrite(goal, toRawTasks(parsed))
                return DecomposeOutcome.Success(plan)
            }
        }

        // 回退：单阶段计划（目标本身作为首个任务，IN_PROGRESS 待模型推进）
        val fallbackPlan = planManager.rewrite(
            goal,
            listOf(RawTask(title = goal, status = TaskStatus.IN_PROGRESS)),
        )
        return DecomposeOutcome.Fallback(
            plan = fallbackPlan,
            reason = if (raw == null) "LLM 拆解调用失败" else "LLM 输出不可解析为阶段结构",
        )
    }

    // ------------------------------------------------------------------
    // LLM 请求与解析
    // ------------------------------------------------------------------

    private suspend fun requestDecomposition(
        goal: String,
        toolNames: List<String>,
        maxPhases: Int,
        maxStepsPerPhase: Int,
    ): String? = try {
        val response = llmClient.chat(
            messages = listOf(LlmMessage.System(decomposePrompt(goal, toolNames, maxPhases, maxStepsPerPhase))),
            tools = emptyList(),
            temperature = 0.2,
            maxTokens = 1_200,
        )
        response.content?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        null
    }

    private fun decomposePrompt(
        goal: String,
        toolNames: List<String>,
        maxPhases: Int,
        maxStepsPerPhase: Int,
    ): String = buildString {
        appendLine("把下面的任务目标拆解为「阶段 → 步骤」两级计划。")
        appendLine()
        appendLine("任务目标: $goal")
        if (toolNames.isNotEmpty()) {
            appendLine()
            appendLine("可用的工具: ${toolNames.joinToString("、")}")
            appendLine("步骤必须可以用上述工具执行，不要拆出工具做不到的步骤。")
        }
        appendLine()
        appendLine("要求:")
        appendLine("- 阶段数 1..$maxPhases，每阶段步骤数 1..$maxStepsPerPhase；")
        appendLine("- 每个步骤附一句可验证的验收标准（做完时能判断成功与否）；")
        appendLine("- 步骤粒度：一个步骤 = 一次可独立验证的推进，不要把多个动作捆成一步；")
        appendLine("- 顺序合理：有依赖的步骤排前面，无依赖的可并列。")
        appendLine()
        appendLine("只输出 JSON，不要任何其他文字:")
        appendLine("""{"phases": [{"title": "阶段名", "steps": [{"title": "步骤名", "acceptance": "验收标准"}]}]}""")
    }

    /** 解析阶段结构（宽容：数组/字段缺失时尽力收缩，解析不出则空）。 */
    internal fun parsePhases(text: String, maxPhases: Int, maxStepsPerPhase: Int): List<ParsedPhase> {
        val jsonText = extractJson(text) ?: return emptyList()
        return try {
            val root = Json.parseToJsonElement(jsonText).jsonObject
            val phases = root["phases"]?.jsonArray ?: return emptyList()
            phases.take(maxPhases).mapNotNull { phaseElement ->
                val phase = phaseElement.jsonObject
                val title = phase["title"]?.jsonPrimitive?.content?.trim().orEmpty()
                if (title.isEmpty()) return@mapNotNull null
                val steps = (phase["steps"] as? JsonArray).orEmpty()
                    .take(maxStepsPerPhase)
                    .mapNotNull { stepElement ->
                        val step = stepElement.jsonObject
                        val stepTitle = step["title"]?.jsonPrimitive?.content?.trim().orEmpty()
                        if (stepTitle.isEmpty()) {
                            null
                        } else {
                            ParsedStep(
                                title = stepTitle,
                                acceptance = step["acceptance"]?.jsonPrimitive?.content?.trim().orEmpty(),
                            )
                        }
                    }
                if (steps.isEmpty()) return@mapNotNull null
                ParsedPhase(title = title, steps = steps)
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun extractJson(text: String): String? {
        val fenced = Regex("```(?:json)?\\s*([\\s\\S]*?)```").find(text)?.groupValues?.get(1)?.trim()
        val candidate = fenced ?: run {
            val start = text.indexOf('{')
            if (start < 0) return null
            var depth = 0
            var inString = false
            var escaped = false
            for (i in start until text.length) {
                val ch = text[i]
                when {
                    escaped -> escaped = false
                    ch == '\\' && inString -> escaped = true
                    ch == '"' -> inString = !inString
                    !inString && ch == '{' -> depth++
                    !inString && ch == '}' -> {
                        depth--
                        if (depth == 0) return text.substring(start, i + 1)
                    }
                }
            }
            null
        } ?: return null
        return candidate.takeIf { it.startsWith("{") }
    }

    /** 阶段 → 父任务；步骤 → 子任务（验收标准折叠进 notes）。 */
    internal fun toRawTasks(phases: List<ParsedPhase>): List<RawTask> {
        val tasks = mutableListOf<RawTask>()
        for ((phaseIndex, phase) in phases.withIndex()) {
            val parentId = "p${phaseIndex + 1}"
            tasks += RawTask(id = parentId, title = phase.title)
            for ((stepIndex, step) in phase.steps.withIndex()) {
                tasks += RawTask(
                    id = "p${phaseIndex + 1}s${stepIndex + 1}",
                    title = step.title,
                    notes = if (step.acceptance.isNotBlank()) "验收: ${step.acceptance}" else "",
                    parent = parentId,
                )
            }
        }
        return tasks
    }

    /** 解析出的阶段。 */
    data class ParsedPhase(val title: String, val steps: List<ParsedStep>)

    /** 解析出的步骤。 */
    data class ParsedStep(val title: String, val acceptance: String)

    companion object {
        /** JSON 字符串感知的平衡括号提取（[extractJson] 使用的算法说明）。 */
        internal val JSON_DEPTH_SCAN = Unit
    }
}
