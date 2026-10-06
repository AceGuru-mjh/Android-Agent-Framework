package com.androidguru.agent.llm

/**
 * LLM 消息模型（OpenAI 兼容语义，多端点适配的最小公共集）。
 */
sealed interface LlmMessage {

    /** 系统提示。 */
    data class System(val content: String) : LlmMessage

    /** 用户消息，可携带图片（多模态端点）。 */
    data class User(
        val content: String,
        val images: List<ImageContent> = emptyList(),
    ) : LlmMessage

    /** 助手消息：纯文本回复或工具调用决策。 */
    data class Assistant(
        val content: String? = null,
        val toolCalls: List<ToolCall> = emptyList(),
        val reasoning: String? = null,
    ) : LlmMessage

    /** 工具结果回填（与 Assistant.toolCalls 一一配对）。 */
    data class Tool(
        val toolCallId: String,
        val content: String,
    ) : LlmMessage
}

/** 图片内容（base64 内联）。 */
data class ImageContent(
    val base64: String,
    val mediaType: String = "image/png",
)

/**
 * 工具调用。
 *
 * [index] 专为流式并行工具调用设计：OpenAI 流式增量片段可能只带 index 不带 id，
 * 累积器必须以 index 为复合键，以 id 为键会撕裂并行调用的参数流。
 */
data class ToolCall(
    val id: String,
    val name: String,
    val arguments: String,
    val index: Int = -1,
)

/** Token 用量。 */
data class Usage(
    val promptTokens: Long = 0,
    val completionTokens: Long = 0,
    val totalTokens: Long = 0,
)

/** 发给模型的工具定义。 */
data class ToolDefinition(
    val name: String,
    val description: String,
    /** JSON Schema 字符串（`{"type":"object",...}`）。 */
    val parametersJsonSchema: String,
)
