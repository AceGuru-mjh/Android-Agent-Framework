package com.androidguru.agent.llm

import kotlinx.coroutines.flow.Flow

/** 工具选择策略。 */
sealed interface ToolChoiceSpec {
    data object Auto : ToolChoiceSpec
    data object Required : ToolChoiceSpec
    data object None : ToolChoiceSpec
    data class Function(val name: String) : ToolChoiceSpec
}

/** 流式片段。字段均可空 —— 端点按需下发。 */
data class LlmStreamChunk(
    /** 文本增量。 */
    val content: String? = null,
    /** 推理内容增量（deepseek-reasoner / qwen-think 等）。 */
    val reasoning: String? = null,
    /** 工具调用增量片段（以 index 为累积键）。 */
    val toolCallDeltas: List<ToolCall> = emptyList(),
    /** 用量（通常出现在流尾统计帧）。 */
    val usage: Usage? = null,
    /** 流结束标记；结束时 [completeToolCalls] 携带完整累积结果。 */
    val finish: Boolean = false,
    /** 流结束时完整的工具调用列表（按 index 排序）。 */
    val completeToolCalls: List<ToolCall> = emptyList(),
)

/** 非流式响应。 */
data class LlmResponse(
    val content: String?,
    val reasoning: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val usage: Usage? = null,
)

/**
 * LLM 客户端抽象 —— 引擎只依赖此接口。
 *
 * 采样参数为可空哨兵：null = 未指定，实现层回退 [LlmConfig] 配置值，
 * 引擎不再用全局配置覆盖端点级参数。
 */
interface LlmClient {

    /** 非流式对话。 */
    suspend fun chat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition> = emptyList(),
        temperature: Double? = null,
        maxTokens: Int? = null,
        toolChoice: ToolChoiceSpec? = null,
    ): LlmResponse

    /** 流式对话。 */
    fun chatStream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition> = emptyList(),
        temperature: Double? = null,
        maxTokens: Int? = null,
        toolChoice: ToolChoiceSpec? = null,
    ): Flow<LlmStreamChunk>
}
