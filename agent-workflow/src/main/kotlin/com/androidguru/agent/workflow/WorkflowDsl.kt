package com.androidguru.agent.workflow

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * 工作流定义 DSL —— 宿主用代码声明工作流（编译期友好，模板字符串写形参引用）。
 *
 * ```kotlin
 * val dailyReport = workflow("daily_report", "日报生成与分发", "生成指标日报并分发给指定渠道") {
 *     param("channel", "分发渠道（slack/email）", required = true)
 *     param("date", "报告日期，默认今天", default = "today")
 *
 *     setVarNode("init", mapOf("date" to "\${params.date}"))
 *     toolNode("gather", "collect_metrics", args = mapOf("date" to "\${vars.date}"), retry = retry())
 *     toolNode("render", "render_report", args = mapOf("data" to "\${nodes.gather.output}"))
 *     switchNode("route", "\${params.channel}", cases = mapOf("slack" to "slack", "email" to "email"))
 *     toolNode("to_slack", "post_slack", args = mapOf("text" to "\${nodes.render.output}"))
 *     toolNode("to_email", "send_email", args = mapOf("body" to "\${nodes.render.output}"))
 *
 *     edge(START, "init")
 *     edge("init" to "gather"); edge("gather" to "render"); edge("render" to "route")
 *     edge("route", "to_slack", branch = "slack")
 *     edge("route", "to_email", branch = "email")
 *     edge("to_slack", END); edge("to_email", END)
 * }
 * ```
 */
fun workflow(
    id: String,
    name: String = id,
    description: String = "",
    block: WorkflowBuilder.() -> Unit,
): WorkflowDefinition {
    val builder = WorkflowBuilder().apply(block)
    val definition = builder.build(id = id, name = name, description = description)
    definition.validate() // DSL 产物也要过静态校验（fail-fast，写错当场报）
    return definition
}

/** 快捷构造：默认重试策略（3 次 / 500ms 起 / 2 倍退避）。 */
fun retry(
    maxAttempts: Int = 3,
    initialDelayMs: Long = 500,
    backoffFactor: Double = 2.0,
    maxDelayMs: Long = 60_000,
    jitter: Boolean = true,
): RetryPolicy = RetryPolicy(maxAttempts, initialDelayMs, backoffFactor, maxDelayMs, jitter)

class WorkflowBuilder internal constructor() {

    private val params = mutableListOf<ParamSpec>()
    private val nodes = mutableListOf<WorkflowNode>()
    private val edges = mutableListOf<WorkflowEdge>()
    private var maxSteps: Int = 100

    // ---------------- 参数 ----------------

    fun param(name: String, description: String = "", required: Boolean = false, default: String? = null) {
        params += ParamSpec(name, description, required, default)
    }

    // ---------------- 节点 ----------------

    fun toolNode(
        id: String,
        toolId: String,
        args: Map<String, String> = emptyMap(),
        description: String = "",
        retry: RetryPolicy? = null,
        timeoutMs: Long? = null,
        onError: NodeOnError = NodeOnError.FAIL_RUN,
    ) {
        nodes += WorkflowNode.Tool(
            id = id,
            toolId = toolId,
            arguments = JsonObject(args.mapValues { JsonPrimitive(it.value) }),
            description = description,
            retry = retry,
            timeoutMs = timeoutMs,
            onError = onError,
        )
    }

    /** args 原始 JSON 版（需要数字 / 布尔 / 嵌套结构时用）。 */
    fun toolNode(
        id: String,
        toolId: String,
        args: JsonObject,
        description: String = "",
        retry: RetryPolicy? = null,
        timeoutMs: Long? = null,
        onError: NodeOnError = NodeOnError.FAIL_RUN,
    ) {
        nodes += WorkflowNode.Tool(id, toolId, args, description, retry, timeoutMs, onError)
    }

    fun agentNode(
        id: String,
        prompt: String,
        systemPrompt: String? = null,
        description: String = "",
        temperature: Double? = null,
        maxTokens: Int? = null,
        retry: RetryPolicy? = null,
        timeoutMs: Long? = null,
        onError: NodeOnError = NodeOnError.FAIL_RUN,
    ) {
        nodes += WorkflowNode.Agent(
            id = id,
            prompt = prompt,
            systemPrompt = systemPrompt,
            description = description,
            temperature = temperature,
            maxTokens = maxTokens,
            retry = retry,
            timeoutMs = timeoutMs,
            onError = onError,
        )
    }

    fun humanNode(id: String, prompt: String, description: String = "") {
        nodes += WorkflowNode.Human(id, prompt, description)
    }

    fun switchNode(
        id: String,
        expression: String,
        cases: Map<String, String> = emptyMap(),
        defaultBranch: String? = null,
        description: String = "",
    ) {
        nodes += WorkflowNode.Switch(
            id = id,
            expression = expression,
            cases = cases.entries.map { WorkflowNode.Switch.Case(it.key, it.value) },
            defaultBranch = defaultBranch,
            description = description,
        )
    }

    fun setVarNode(id: String, assignments: Map<String, String>, description: String = "") {
        nodes += WorkflowNode.SetVar(id, assignments, description)
    }

    fun delayNode(id: String, delayMs: Long, description: String = "") {
        nodes += WorkflowNode.Delay(id, delayMs, description)
    }

    // ---------------- 边 ----------------

    fun edge(from: String, to: String, branch: String? = null) {
        edges += WorkflowEdge(from, to, branch)
    }

    fun edge(pair: Pair<String, String>, branch: String? = null) {
        edges += WorkflowEdge(pair.first, pair.second, branch)
    }

    fun maxSteps(n: Int) {
        maxSteps = n
    }

    // ---------------- 哨兵 ----------------

    val START: String get() = WorkflowEdge.START
    val END: String get() = WorkflowEdge.END

    internal fun build(id: String, name: String, description: String): WorkflowDefinition = WorkflowDefinition(
        id = id,
        name = name,
        description = description,
        params = params.toList(),
        nodes = nodes.toList(),
        edges = edges.toList(),
        maxSteps = maxSteps,
    )
}
