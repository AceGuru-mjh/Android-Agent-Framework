package com.androidguru.agent.tools

/**
 * 框架统一的工具抽象。
 *
 * 实现者只需声明 [id] / [name] / [description] / [parameters] 并实现 [execute]；
 * 执行管线（门控、钩子、Schema 校验、超时、重试、熔断、限流、统计）由 [ToolExecutor] 统一提供，
 * 工具实现零心智负担。
 *
 * 命名约定：id 使用 snake_case；远程 MCP 工具由框架自动命名为 `mcp__{server}__{tool}`。
 */
interface AgentTool {

    /** 注册表内唯一 id。 */
    val id: String

    /** 展示给模型的名字，缺省与 [id] 相同。 */
    val name: String get() = id

    /** 能力描述，直接进入模型工具清单。建议写清「做什么 / 什么时候用 / 有什么限制」。 */
    val description: String

    /** 参数 JSON Schema。模型看到的 schema 与执行器校验的 schema 是同一对象，不可漂移。 */
    val parameters: ToolSchema get() = ToolSchema.empty()

    /** 元数据（分类 / 风险 / 能力注解），驱动执行门控与审计。 */
    val metadata: ToolMetadata get() = ToolMetadata.forId(id)

    /**
     * 执行工具。
     *
     * 契约：
     * - 失败请返回 [ToolResult.failure] 而不是抛异常（异常会被管线兜底折叠为失败）；
     * - 不可吞并 [kotlinx.coroutines.CancellationException]，取消必须向上传播；
     * - 输出建议控制在合理长度，管线会按策略钳制。
     */
    suspend fun execute(request: ToolRequest): ToolResult
}

/** 一次工具调用请求。 */
data class ToolRequest(
    /** 本次调用的唯一 id（来自 LLM tool_call id，框架内部调用时自动生成）。 */
    val callId: String,
    /** 工具 id。 */
    val toolId: String,
    /** 原始参数 JSON 字符串（可能不合法，管线校验后传入工具）。 */
    val arguments: String,
)
