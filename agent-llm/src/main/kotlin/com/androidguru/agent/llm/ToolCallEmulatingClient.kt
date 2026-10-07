package com.androidguru.agent.llm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 工具调用模拟客户端（本地模型深度适配）—— 装饰 [LlmClient]。
 *
 * 解决的问题：Qwen2.5 / Gemma / Llama 等小参数本地模型**没有原生 function-calling**，
 * 直接接 OpenAI `tools` 参数会导致端点 400 或静默忽略 —— Agent 退化为纯聊天机器人。
 *
 * 方案（业界 Qwen-Agent / ReAct 文本协议路线）：
 * 1. **协议注入**：把工具 schema 渲染进系统提示，约定模型用 fenced 代码块发起调用：
 *
 *    ```tool_call
 *    {"name": "open_app", "arguments": {"app": "时钟"}}
 *    ```
 *
 * 2. **消息适配**（引擎 → 端点）：历史中 Assistant 的工具调用回渲染为协议块文本；
 *    Tool 结果消息降级为 User 消息（弱模型对话语料里没有 tool role）；
 *    历史超长时从头截断（保留系统提示 + 最近上下文，小上下文模型保护）；
 * 3. **响应解析**（端点 → 引擎）：从回复文本提取全部 `tool_call` 块（兼容裸 JSON
 *    整体即调用对象的形式），剥离协议文本后作为正文，调用块转为 [ToolCall]。
 *
 * 流式语义：增量正文 **fence 边界回持** —— 从「可能是 tool_call 块起点」的位置起
 * 暂缓下发，流结束时统一裁决（协议块永不作为正文流出，正文不丢字；普通代码块
 * 的围栏配对完整时不回持，流式体验不受影响）。
 *
 * 典型接线：
 * ```kotlin
 * val base = OpenAiCompatibleClient(LlmConfig(baseUrl = "http://127.0.0.1:11434/v1", model = "qwen2.5:7b"))
 * val engine = DefaultAgentEngine(llmClient = ToolCallEmulatingClient(base), ...)
 * ```
 */
class ToolCallEmulatingClient(
    private val delegate: LlmClient,
    /** 单轮最多解析的调用块数（防弱模型失控刷块）。 */
    private val maxCallsPerTurn: Int = 5,
    /** 协议块围栏标记（```tool_call）。 */
    private val fenceTag: String = DEFAULT_FENCE_TAG,
) : LlmClient {

    override suspend fun chat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Double?,
        maxTokens: Int?,
        toolChoice: ToolChoiceSpec?,
    ): LlmResponse {
        val adapted = adaptMessages(messages, tools)
        val response = delegate.chat(adapted, tools = emptyList(), temperature = temperature, maxTokens = maxTokens)
        val parsed = parseToolCalls(response.content ?: "", toolsAreKnown = tools.isNotEmpty())
        return response.copy(
            content = parsed.remainingText,
            toolCalls = parsed.calls.take(maxCallsPerTurn),
        )
    }

    override fun chatStream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Double?,
        maxTokens: Int?,
        toolChoice: ToolChoiceSpec?,
    ): Flow<LlmStreamChunk> = flow {
        val adapted = adaptMessages(messages, tools)
        val buffer = StringBuilder()
        var emittedUpTo = 0 // 已安全下发的正文长度

        delegate.chatStream(adapted, tools = emptyList(), temperature = temperature, maxTokens = maxTokens)
            .collect { chunk ->
                chunk.reasoning?.let { emit(LlmStreamChunk(reasoning = it)) }
                chunk.content?.let { buffer.append(it) }

                // fence 边界回持：正文只下发到安全前缀
                if (chunk.content != null) {
                    val safeLen = safePrefixLength(buffer.toString())
                    if (safeLen > emittedUpTo) {
                        val release = buffer.substring(emittedUpTo, safeLen)
                        emittedUpTo = safeLen
                        emit(LlmStreamChunk(content = release))
                    }
                }

                chunk.usage?.let { emit(LlmStreamChunk(usage = it)) }
            }

        // 流结束：统一裁决 —— 剩余正文 + 提取的调用块
        val parsed = parseToolCalls(buffer.toString(), toolsAreKnown = tools.isNotEmpty())
        if (parsed.remainingText.length > emittedUpTo) {
            emit(LlmStreamChunk(content = parsed.remainingText.substring(emittedUpTo)))
        }
        emit(
            LlmStreamChunk(
                finish = true,
                completeToolCalls = parsed.calls.take(maxCallsPerTurn),
            ),
        )
    }

    // ------------------------------------------------------------------
    // 消息适配（引擎 → 端点）
    // ------------------------------------------------------------------

    internal fun adaptMessages(messages: List<LlmMessage>, tools: List<ToolDefinition>): List<LlmMessage> {
        if (tools.isEmpty()) return messages

        // 小上下文保护：历史过长时从头截断（保系统提示 + 最近上下文）
        val bounded = boundHistory(messages)
        val result = mutableListOf<LlmMessage>()

        for (message in bounded) {
            when (message) {
                is LlmMessage.System -> {
                    val isLeading = result.none { it is LlmMessage.System }
                    val suffix = if (isLeading) "\n\n$PROTOCOL_HEADER\n${renderTools(tools)}" else ""
                    result += LlmMessage.System(message.content + suffix)
                }

                is LlmMessage.User -> result += message

                is LlmMessage.Assistant -> {
                    if (message.toolCalls.isEmpty()) {
                        result += message
                    } else {
                        // 历史调用回渲染为协议块（模型看得懂自己「说过」什么）
                        val blocks = message.toolCalls.joinToString("\n") { renderCallBlock(it) }
                        val text = (message.content?.takeIf { it.isNotBlank() } ?: "") + "\n" + blocks
                        result += LlmMessage.Assistant(content = text.trim(), reasoning = message.reasoning)
                    }
                }

                is LlmMessage.Tool -> {
                    // 工具结果降级为 User 消息（弱模型没有 tool role 概念）
                    val body = if (message.content.length > TOOL_RESULT_MAX_CHARS) {
                        message.content.take(TOOL_RESULT_MAX_CHARS) + "…（截断）"
                    } else {
                        message.content
                    }
                    result += LlmMessage.User("TOOL_RESULT ${message.toolCallId}:\n$body")
                }
            }
        }
        return result
    }

    private fun boundHistory(messages: List<LlmMessage>): List<LlmMessage> {
        if (messages.size <= MAX_HISTORY_MESSAGES) return messages
        val kept = mutableListOf<LlmMessage>()
        messages.firstOrNull()?.let { if (it is LlmMessage.System) kept.add(it) }
        kept.addAll(messages.takeLast((MAX_HISTORY_MESSAGES - kept.size).coerceAtLeast(1)))
        return kept
    }

    // ------------------------------------------------------------------
    // 响应解析（端点 → 引擎）
    // ------------------------------------------------------------------

    /** 解析结果：剥离调用块后的正文 + 提取的调用。 */
    data class ParsedResponse(
        val remainingText: String,
        val calls: List<ToolCall>,
    )

    internal fun parseToolCalls(text: String, toolsAreKnown: Boolean): ParsedResponse {
        val calls = mutableListOf<ToolCall>()
        val regex = fencedBlockRegex(fenceTag)
        val remaining = StringBuilder()
        var cursor = 0

        for (match in regex.findAll(text)) {
            val blockStart = match.range.first
            val blockEnd = match.range.last + 1
            remaining.append(text, cursor, blockStart)
            cursor = blockEnd

            val payload = match.groupValues[1].trim()
            parseCallObject(payload, calls.size)?.let { calls.add(it) }
        }
        remaining.append(text, cursor, text.length)

        // 兜底：没有 fence 块时，整段文本若就是一个调用对象也接受（弱模型常见）
        if (calls.isEmpty() && toolsAreKnown) {
            val trimmed = text.trim()
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                parseCallObject(trimmed, 0)?.let { calls.add(it) }
                return ParsedResponse(remainingText = "", calls = calls)
            }
        }

        return ParsedResponse(
            remainingText = remaining.toString().trim(),
            calls = calls,
        )
    }

    private fun parseCallObject(payload: String, index: Int): ToolCall? {
        return try {
            val obj = Json.parseToJsonElement(payload).jsonObject
            val name = obj["name"]?.jsonPrimitive?.content?.trim().orEmpty()
            if (name.isEmpty()) {
                null
            } else {
                val arguments = when (val args = obj["arguments"] ?: obj["parameters"]) {
                    null -> "{}"
                    is JsonObject -> args.toString()
                    else -> {
                        // 字符串形式：内容是 JSON 则紧凑化，否则原样
                        val raw = args.jsonPrimitive.content
                        try {
                            Json.parseToJsonElement(raw).toString()
                        } catch (e: Exception) {
                            raw
                        }
                    }
                }
                ToolCall(
                    id = "emulated_${index}_${System.nanoTime()}",
                    name = name,
                    arguments = arguments,
                    index = index,
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private fun renderCallBlock(call: ToolCall): String =
        "```$fenceTag\n{\"name\": \"${call.name}\", \"arguments\": ${compactJson(call.arguments)}}\n```"

    private fun compactJson(arguments: String): String = try {
        Json.parseToJsonElement(arguments).toString()
    } catch (e: Exception) {
        arguments
    }

    companion object {
        internal const val DEFAULT_FENCE_TAG = "tool_call"

        /** 工具结果回填的最大字符数（弱模型上下文保护）。 */
        internal const val TOOL_RESULT_MAX_CHARS = 6_000

        /** 适配后的历史消息上限（小上下文模型保护）。 */
        internal const val MAX_HISTORY_MESSAGES = 40

        internal const val PROTOCOL_HEADER = """# 工具调用协议
你可以调用工具完成任务。需要调用时，输出如下格式的代码块（一次可输出多个）：

```tool_call
{"name": "工具名", "arguments": {"参数名": "值"}}
```

规则：
- 参数必须是合法 JSON 对象，与工具清单中的定义一致；
- 输出调用块后停止生成，等待 TOOL_RESULT 消息返回结果；
- 不需要调用工具时，直接用自然语言回答，不要输出调用块。

可用工具清单："""

        /** fence 块正则（标签后任意空白，惰性匹配至闭合围栏）。 */
        internal fun fencedBlockRegex(tag: String): Regex =
            Regex("```$tag\\s*([\\s\\S]*?)```")

        /** 渲染工具清单（schema 瘦身：剥离 description 省 token）。 */
        internal fun renderTools(tools: List<ToolDefinition>): String = buildString {
            appendLine()
            for (tool in tools) {
                appendLine("- ${tool.name}: ${tool.description} 参数: ${compactSchema(tool.parametersJsonSchema)}")
            }
            append("如需调用，请严格遵守上述 tool_call 块格式。")
        }

        private fun compactSchema(schema: String): String = try {
            val element = Json.parseToJsonElement(schema)
            normalizeSchema(element).toString().take(600)
        } catch (e: Exception) {
            schema.take(600)
        }

        /** schema 瘦身：递归剥离 description（协议提示里保留参数结构即可，省 token）。 */
        private fun normalizeSchema(element: JsonElement): JsonElement = when (element) {
            is JsonObject -> {
                val cleaned = LinkedHashMap<String, JsonElement>()
                for ((key, value) in element) {
                    if (key == "description") continue
                    cleaned[key] = normalizeSchema(value)
                }
                JsonObject(cleaned)
            }

            is JsonArray -> JsonArray(element.map { normalizeSchema(it) })

            else -> element
        }

        /**
         * 计算可安全下发的正文长度（fence 边界回持）。
         *
         * 状态机扫描：普通代码块（围栏配对完整）不回持；出现
         * ` ```tool_call ` 起始、未闭合围栏、或缓冲区尾部悬着
         * 反引号前缀（``` 进行中）时，从该位置起全部回持 ——
         * 保证协议块内容永不作为正文流出，且不误伤普通代码块的流式体验。
         */
        internal fun safePrefixLength(text: String): Int {
            val fence = "```"
            var i = 0
            while (i < text.length) {
                val idx = text.indexOf(fence, i)
                if (idx < 0) break
                // 围栏后的标签行（至换行或缓冲区尾）
                var j = idx + fence.length
                while (j < text.length && text[j] != '\n' && text[j] != '`') j++
                val tag = text.substring(idx + fence.length, j).trim()

                if (tag.isEmpty() || fenceTagPrefixOf(tag)) {
                    // 可能是 tool_call 块（或围栏刚出现还看不清）→ 全部回持
                    return idx
                }
                // 普通代码块：找闭合围栏；未闭合 → 回持
                val close = text.indexOf(fence, j)
                if (close < 0) return idx
                i = close + fence.length
            }
            // 尾部悬着反引号（``` 进行中）→ 从最后一个反引号 run 起回持
            var k = text.length
            while (k > 0 && text[k - 1] == '`') k--
            return k
        }

        /** [tag] 是否是 tool_call 标签的（完整或部分）前缀。 */
        private fun fenceTagPrefixOf(tag: String): Boolean {
            if (tag.length > DEFAULT_FENCE_TAG.length) return false
            return DEFAULT_FENCE_TAG.startsWith(tag)
        }
    }
}
