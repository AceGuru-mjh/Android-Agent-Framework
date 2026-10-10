package com.androidguru.agent.eval

/**
 * 评估任务定义 —— 一个可判分（成功 / 失败 / 失败原因）的 Agent 任务。
 *
 * 评估维度全部**基于执行轨迹**（工具调用序列 + 终态），不依赖设备真机 ——
 * 纯 JVM 可跑，适合 CI 与提示词 / 模型 / 护栏参数的回归调优：
 * 换一个提示词或换一个模型档位，跑同一套套件，成功率与失败分布直接可比。
 *
 * @param instruction 给 Agent 的用户指令（评估即执行这条指令）。
 * @param criteria 成功判据（必做调用 / 禁止调用 / 轮数上限 / 无循环 / 收尾文本模式）。
 */
data class EvalTask(
    val id: String,
    val name: String,
    /** 任务类别（通讯 / 系统设置 / 媒体 / 文件 / 应用 / 日历 / 信息查询…）。 */
    val category: String,
    val instruction: String,
    val criteria: SuccessCriteria,
    /** 难度 1..5（人工标注，报告分组统计用）。 */
    val difficulty: Int = 2,
    val description: String = "",
) {
    init {
        require(id.isNotBlank()) { "id 不能为空" }
        require(instruction.isNotBlank()) { "instruction 不能为空（$id）" }
        require(difficulty in 1..5) { "difficulty 须在 1..5（$id）" }
    }
}

/**
 * 成功判据：全部满足才算通过（合取语义）。
 *
 * 设计原则：**判得动、不冤枉** ——
 * - 必做调用支持参数级匹配（如 `set_alarm` 且 `time` 含 "7:30"）；
 * - 禁止调用抓危险操作（评估「不该做的事」）；
 * - 轮数 / 循环 / 收尾文本约束保证任务「干净完成」而非「碰巧做对一步」。
 */
data class SuccessCriteria(
    /** 必须出现的调用（各匹配器独立满足；minCount 控制最少次数）。 */
    val requiredCalls: List<CallMatcher> = emptyList(),
    /** 绝不允许出现的调用（一旦出现即失败，如误删 / 误发）。 */
    val forbiddenCalls: List<CallMatcher> = emptyList(),
    /** 最大迭代轮数（超即失败；null = 不限）。 */
    val maxIterations: Int? = null,
    /** 期间不允许触发循环护栏（死循环即失败）。 */
    val requireNoLoop: Boolean = true,
    /** 最终回复须全部命中的正则（如必须包含结论性文本）。 */
    val finalTextPatterns: List<Regex> = emptyList(),
    /** 最少成功调用次数（防止「一步没做就说完成」）。 */
    val minSuccessfulCalls: Int? = null,
)

/** 参数匹配算子。 */
enum class MatchOp {
    /** 参数字符串包含期望值（最常用，容错序）。 */
    CONTAINS,

    /** 参数字符串等于期望值。 */
    EQUALS,

    /** 参数字符串匹配正则。 */
    REGEX,
}

/**
 * 工具调用匹配器：工具名 + 可选的参数约束。
 *
 * @param tool 工具名（默认精确匹配；[toolRegex] = true 时为正则，**部分匹配**
 *   语义 —— 出现即命中，如 `delete` 命中 `delete_file`）。
 * @param argChecks 参数约束（合取；解析不到对应 JSON 键则该约束不满足）。
 * @param minCount 轨迹中至少出现次数（默认 1）。
 */
data class CallMatcher(
    val tool: String,
    val toolRegex: Boolean = false,
    val argChecks: List<ArgCheck> = emptyList(),
    val minCount: Int = 1,
) {
    init {
        require(minCount >= 1) { "minCount >= 1" }
    }
}

/**
 * 参数约束：从调用参数 JSON 的顶层键取值（字符串化）后按算子比较。
 *
 * @param key JSON 顶层键（如 "app"、"time"、"text"）。
 */
data class ArgCheck(
    val key: String,
    val op: MatchOp,
    val expected: String,
) {
    init {
        if (op == MatchOp.REGEX) {
            // 构造期校验，坏正则快速失败
            Regex(expected)
        }
    }
}
