package com.androidguru.agent.workflow

import kotlinx.serialization.json.JsonObject

/**
 * 工作流模块的核心模型：参数化的声明式工作流定义。
 *
 * 设计来源（同类项目考古结论）：
 * - **参数化子程序范式**（Mobile-Agent-E Shortcut）：步骤存「意图 + 参数绑定」
 *   而非快照值 —— 具体值在执行时经 `${...}` 模板解析（形参透传 / 常量字面量），
 *   避免 AppAgent 式「坐标硬编码即过期」问题；
 * - **边表 + 条件路由**（LangGraph）：`edges` 显式连线，`branch` 标签表达
 *   switch 出口与错误分支，比纯 DAG 更能表达循环 / 审批 / 失败降级；
 * - **路由决策持久化**（LangGraph path_map 静态化）：[WorkflowNode.Switch]
 *   选中的 branch 存进节点运行态，崩溃恢复后路由不漂移（Temporal 确定性纪律）。
 *
 * 序列化走 [WorkflowCodec]（手工 JSON 编解码，模型类型保持纯 data class）。
 */

// ------------------------------------------------------------------
// 参数与模板
// ------------------------------------------------------------------

/**
 * 工作流形参声明。运行时传入实参，节点模板经 `${params.<name>}` 引用。
 */
data class ParamSpec(
    val name: String,
    val description: String = "",
    val required: Boolean = false,
    /** 缺省实参（未传且非必填时使用）。 */
    val defaultValue: String? = null,
)

// ------------------------------------------------------------------
// 重试与错误策略
// ------------------------------------------------------------------

/**
 * 节点级重试策略（LangGraph RetryPolicy 模式：指数退避 + 抖动 + 错误分类）。
 *
 * 只对**瞬时错误**重试（工具超时 / 限流 / 下游不可用）；参数校验 / 权限等
 * 业务性失败立即终止 —— 重试换不来更好的结果（Temporal NonRetryable 思想）。
 */
data class RetryPolicy(
    /** 最大尝试次数（含首次）。 */
    val maxAttempts: Int = 3,
    /** 首次重试前延迟。 */
    val initialDelayMs: Long = 500,
    /** 退避倍率。 */
    val backoffFactor: Double = 2.0,
    /** 单次延迟上限。 */
    val maxDelayMs: Long = 60_000,
    /** 抖动（±25%），防并行节点同步轰击。 */
    val jitter: Boolean = true,
) {
    /** 第 attempt 次失败后的重试延迟（attempt 从 1 起计）。 */
    fun delayFor(attempt: Int, random: Double = Math.random()): Long {
        if (maxAttempts <= 1 || attempt < 1) return 0
        val exp = initialDelayMs * Math.pow(backoffFactor, (attempt - 1).coerceAtLeast(0).toDouble())
        val capped = exp.coerceAtMost(maxDelayMs.toDouble()).toLong()
        if (!jitter) return capped
        val factor = 0.75 + random / 2.0
        return (capped * factor).toLong().coerceAtLeast(0)
    }
}

/**
 * 节点失败（重试耗尽后）的处置策略（n8n onError 三态模式）。
 */
enum class NodeOnError {
    /** 失败即失败整个运行（并行兄弟节点立即取消）。缺省。 */
    FAIL_RUN,

    /** 标记 SKIPPED 继续推进（下游把该节点视为已完成，无输出）。 */
    CONTINUE,

    /** 路由进 `branch = "error"` 的边 —— 失败降级 / 人工接管路径。 */
    ERROR_BRANCH,
}

// ------------------------------------------------------------------
// 节点
// ------------------------------------------------------------------

/**
 * 工作流节点。六种一等节点类型：
 *
 * - [Tool]：调用注册表里的 AgentTool（快速确定性路径）；
 * - [Agent]：一次单轮 LLM 调用（无工具，产出文本 —— 组合词句 / 判断 / 摘要）；
 * - [Human]：暂停等人（审批 / 补充信息），恢复后输出即人给的答案；
 * - [Switch]：对变量求值选分支（路由决策持久化）；
 * - [SetVar]：写变量（把上游输出裁剪 / 固化为变量供下游引用）；
 * - [Delay]：延迟等待。
 */
sealed interface WorkflowNode {
    /** 节点 id（全局唯一；DSL 与模板引用 `${nodes.<id>.output}` 用它寻址）。 */
    val id: String

    /** 展示描述（事件 / UI / 蒸馏产物可读性）。 */
    val description: String

    /** 本节点重试策略（null = 不重试）。 */
    val retry: RetryPolicy?

    /** 单次尝试超时。 */
    val timeoutMs: Long?

    /** 重试耗尽后的失败处置。 */
    val onError: NodeOnError

    /** 工具调用节点：arguments 的字符串叶子可含 `${...}` 模板。 */
    data class Tool(
        override val id: String,
        val toolId: String,
        val arguments: JsonObject = JsonObject(emptyMap()),
        override val description: String = "",
        override val retry: RetryPolicy? = null,
        override val timeoutMs: Long? = null,
        override val onError: NodeOnError = NodeOnError.FAIL_RUN,
    ) : WorkflowNode

    /** 单轮 LLM 节点（无工具；system + prompt 模板）。 */
    data class Agent(
        override val id: String,
        val prompt: String,
        val systemPrompt: String? = null,
        val temperature: Double? = null,
        val maxTokens: Int? = null,
        override val description: String = "",
        override val retry: RetryPolicy? = null,
        override val timeoutMs: Long? = null,
        override val onError: NodeOnError = NodeOnError.FAIL_RUN,
    ) : WorkflowNode

    /** 人工节点：运行进入 WAITING_HUMAN，[WorkflowEngine.resume] 携答案恢复。 */
    data class Human(
        override val id: String,
        val prompt: String,
        override val description: String = "",
        override val retry: RetryPolicy? = null,
        override val timeoutMs: Long? = null,
        override val onError: NodeOnError = NodeOnError.FAIL_RUN,
    ) : WorkflowNode

    /**
     * 分支节点：[expression] 模板解析为一个值，命中 [cases] 中 value 相等的
     * 分支；都不中走 [defaultBranch]。选中分支持久化在运行态（恢复不漂移）。
     */
    data class Switch(
        override val id: String,
        val expression: String,
        val cases: List<Case> = emptyList(),
        val defaultBranch: String? = null,
        override val description: String = "",
        override val retry: RetryPolicy? = null,
        override val timeoutMs: Long? = null,
        override val onError: NodeOnError = NodeOnError.FAIL_RUN,
    ) : WorkflowNode {
        /** value（模板解析结果精确匹配）→ branch 标签。 */
        data class Case(val value: String, val branch: String)
    }

    /** 写变量节点：assignments 的值为模板，解析结果存入运行变量。 */
    data class SetVar(
        override val id: String,
        val assignments: Map<String, String> = emptyMap(),
        override val description: String = "",
        override val retry: RetryPolicy? = null,
        override val timeoutMs: Long? = null,
        override val onError: NodeOnError = NodeOnError.FAIL_RUN,
    ) : WorkflowNode

    /** 延迟节点（等待 / 退避节奏控制）。 */
    data class Delay(
        override val id: String,
        val delayMs: Long,
        override val description: String = "",
        override val retry: RetryPolicy? = null,
        override val timeoutMs: Long? = null,
        override val onError: NodeOnError = NodeOnError.FAIL_RUN,
    ) : WorkflowNode
}

// ------------------------------------------------------------------
// 边与定义
// ------------------------------------------------------------------

/**
 * 边。`from`/`to` 为节点 id 或哨兵 [START] / [END]。
 * [branch] 非空时：仅当来源节点路由到该分支才激活（switch 出口 / 错误分支）。
 */
data class WorkflowEdge(
    val from: String,
    val to: String,
    val branch: String? = null,
) {
    companion object {
        const val START = "__start__"
        const val END = "__end__"
        /** 错误分支标签（[NodeOnError.ERROR_BRANCH] 路由目标）。 */
        const val ERROR_BRANCH = "error"
    }
}

/**
 * 工作流定义 —— 可持久化 / 可版本化 / 可复用的完整蓝图。
 */
data class WorkflowDefinition(
    /** 语义化 snake_case id（「望名生义」：OS-Copilot 泛化命名纪律）。 */
    val id: String,
    val name: String,
    /** 何时适用（建议注入与检索的依据，一句话）。 */
    val description: String = "",
    /** 定义格式版本（解码层据此迁移 / 拒绝）。 */
    val schemaVersion: Int = SCHEMA_VERSION,
    val params: List<ParamSpec> = emptyList(),
    val nodes: List<WorkflowNode> = emptyList(),
    val edges: List<WorkflowEdge> = emptyList(),
    /** 超步数熔断上限（agent 自主循环可能不收敛）。 */
    val maxSteps: Int = 100,
) {
    companion object {
        const val SCHEMA_VERSION = 1

        /** 节点 / 参数 / 变量名的合法形态（模板路径寻址要求无点号；允许 CJK）。 */
        val ID_PATTERN = Regex("^[A-Za-z0-9_\\-\\u4e00-\\u9fa5]{1,64}$")
    }

    /**
     * 静态校验（定义是宿主 / 模型产出的持久化物，入库前必须过检）：
     * - id / 节点 id / 参数名形态合法且唯一；
     * - 边端点存在（节点 id 或哨兵）；
     * - 至少一条 start 边；有节点但没有 end 边时允许可达性收尾；
     * - 所有节点从 START 可达（防「死分支」悄悄积灰）；
     * - ERROR_BRANCH 边的 from 必须是真实节点且带 `error` 分支语义；
     * - 工具节点不得引用 `run_workflow`（递归执行保护）。
     *
     * @throws WorkflowValidationException 校验失败（信息可直接展示）。
     */
    fun validate() {
        val problems = validateOrNull()
        if (problems != null) throw WorkflowValidationException(problems)
    }

    /** 校验通过返回 null，否则返回问题清单（不抛异常的变体）。 */
    fun validateOrNull(): String? {
        val problems = mutableListOf<String>()

        if (!ID_PATTERN.matches(id)) problems += "工作流 id「$id」不合法（snake_case，1~64 字符）"
        if (name.isBlank()) problems += "name 不能为空"
        if (nodes.isEmpty()) problems += "至少需要一个节点"

        val nodeIds = HashSet<String>(nodes.size)
        for (n in nodes) {
            if (!ID_PATTERN.matches(n.id)) {
                problems += "节点 id「${n.id}」不合法（仅字母数字下划线连字符，1~64）"
                continue
            }
            if (!nodeIds.add(n.id)) problems += "节点 id 重复: ${n.id}"
            if (n is WorkflowNode.Tool && n.toolId == WorkflowTools.RUN_TOOL_ID) {
                problems += "节点 ${n.id} 引用 ${n.toolId}（工作流内禁止递归执行工作流）"
            }
            if (n is WorkflowNode.Delay && n.delayMs < 0) {
                problems += "节点 ${n.id} 的 delayMs 不能为负"
            }
            if (n is WorkflowNode.Switch && n.cases.isEmpty() && n.defaultBranch == null) {
                problems += "switch 节点 ${n.id} 没有 cases 也没有 defaultBranch（永远路由不出）"
            }
        }

        val paramNames = HashSet<String>(params.size)
        for (p in params) {
            if (!ID_PATTERN.matches(p.name)) problems += "参数名「${p.name}」不合法"
            if (!paramNames.add(p.name)) problems += "参数名重复: ${p.name}"
        }

        val startEdges = edges.filter { it.from == WorkflowEdge.START }
        if (startEdges.isEmpty()) problems += "至少需要一条 ${WorkflowEdge.START} 起始边"
        for (e in edges) {
            val fromOk = e.from == WorkflowEdge.START || e.from == WorkflowEdge.END || nodeIds.contains(e.from)
            val toOk = e.to == WorkflowEdge.START || e.to == WorkflowEdge.END || nodeIds.contains(e.to)
            if (!fromOk) problems += "边 from=${e.from} 指向不存在的节点"
            if (!toOk) problems += "边 to=${e.to} 指向不存在的节点"
            if (e.from == WorkflowEdge.END) problems += "不允许从 ${WorkflowEdge.END} 出发"
            if (e.to == WorkflowEdge.START) problems += "不允许指回 ${WorkflowEdge.START}"
            if (e.branch != null && e.from == WorkflowEdge.START) {
                problems += "起始边不能带 branch（起点没有路由决策）"
            }
            if (e.branch != null && e.branch.isBlank()) problems += "边 ${e.from}→${e.to} 的 branch 不能为空白"
        }

        // 可达性（BFS 自 start 出发，沿「可能激活」的边）
        val reachable = HashSet<String>()
        val queue = ArrayDeque<String>()
        startEdges.forEach { if (it.to != WorkflowEdge.END) { queue.add(it.to); reachable.add(it.to) } }
        val adjacency = edges.filter { it.from != WorkflowEdge.START && it.from != WorkflowEdge.END }
            .groupBy { it.from }
        while (queue.isNotEmpty()) {
            val cur = queue.removeFirst()
            adjacency[cur]?.forEach { next ->
                val target = next.to
                if (target != WorkflowEdge.END && reachable.add(target)) queue.add(target)
            }
        }
        val unreachable = nodes.map { it.id }.filter { it !in reachable }
        if (unreachable.isNotEmpty()) {
            problems += "节点从起点不可达（死分支）: ${unreachable.joinToString(", ")}"
        }

        return if (problems.isEmpty()) null else problems.joinToString("; ")
    }
}

/** 定义校验失败。 */
class WorkflowValidationException(message: String) : Exception(message)
