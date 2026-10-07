package com.androidguru.agent.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * OpenAI 兼容流式客户端。
 *
 * 生产级细节（继承自 Android-Guru-Agent 踩坑经验）：
 * - SSE 解析兼容 `data:` / `data: ` 两种前缀，`[DONE]` 终止；
 * - Base URL 自动归一化（[normalizeEndpoint]）：填域名也会补 /v1，DeepSeek 等官方
 *   base 不带 /v1 的端点不再 404；
 * - HTTP 错误附中文诊断（[describeHttpError]），LlmException 结构不变；
 * - 取消立即中断阻塞读：注册 invokeOnCompletion 关闭 response，socket 关闭使 readLine 抛 IOException；
 * - 流式工具调用以 **index 为复合键** 累积（OpenAI 并行工具调用的后续片段只带 index 不带 id）；
 * - 流尾 usage 统计帧（choices 为空但 usage 非空）先提取再判空；
 * - 严格推理模型不发 temperature，max_tokens → max_completion_tokens；
 * - 单个工具 schema 非法不拖死全部工具（降级为空对象骨架）。
 */
class OpenAiCompatibleClient(private val config: LlmConfig) : LlmClient {

    private val json = Json { ignoreUnknownKeys = true }

    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.connectTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(config.requestTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(config.requestTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    // ------------------------------------------------------------------
    // 公共 API
    // ------------------------------------------------------------------

    override suspend fun chat(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Double?,
        maxTokens: Int?,
        toolChoice: ToolChoiceSpec?,
    ): LlmResponse {
        val body = buildRequestBody(messages, tools, temperature, maxTokens, toolChoice, stream = false)
        val response = post(body)
        response.use { resp ->
            val text = resp.body?.string()
            if (!resp.isSuccessful) throw LlmException.Http(resp.code, describeHttpError(resp.code, text))
            if (text.isNullOrBlank()) throw LlmException.EmptyResponse
            val root = try {
                json.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                throw LlmException.Parse("非 JSON 响应", e)
            }
            return parseNonStreamResponse(root)
        }
    }

    override fun chatStream(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Double?,
        maxTokens: Int?,
        toolChoice: ToolChoiceSpec?,
    ): Flow<LlmStreamChunk> = flow {
        val body = buildRequestBody(messages, tools, temperature, maxTokens, toolChoice, stream = true)
        val response = post(body)
        // 取消即关闭连接，解除阻塞读
        val closeHandler = currentCoroutineContext()[Job]?.invokeOnCompletion { response.close() }
        try {
            if (!response.isSuccessful) {
                val errBody = response.body?.string()
                throw LlmException.Http(response.code, describeHttpError(response.code, errBody))
            }
            val source = response.body?.source() ?: throw LlmException.EmptyResponse

            val textBuf = StringBuilder()
            val reasoningBuf = StringBuilder()
            val toolAccumulator = LinkedHashMap<Int, ToolCallAccumulator>()
            var lastUsage: Usage? = null

            while (true) {
                currentCoroutineContext().ensureActive()
                val line = source.readUtf8Line() ?: break
                if (line.isBlank()) continue
                if (!line.startsWith("data:")) continue
                val payload = line.removePrefix("data:").trim()
                if (payload == "[DONE]") break
                if (!payload.startsWith("{")) continue

                val chunk = parseStreamFrame(payload, toolAccumulator) ?: continue
                chunk.usage?.let { lastUsage = it }
                chunk.reasoning?.let { reasoningBuf.append(it) }
                chunk.content?.let { textBuf.append(it) }
                emit(chunk)
            }

            // 流尾兜底：部分端点不发 [DONE]
            val complete = toolAccumulator.entries.sortedBy { it.key }.map { it.value.toToolCall(it.key) }
            emit(
                LlmStreamChunk(
                    finish = true,
                    completeToolCalls = complete,
                    usage = lastUsage,
                    content = null,
                ),
            )
        } finally {
            closeHandler?.dispose()
            response.close()
        }
    }.flowOn(Dispatchers.IO)

    // ------------------------------------------------------------------
    // 请求体构建
    // ------------------------------------------------------------------

    internal fun buildRequestBody(
        messages: List<LlmMessage>,
        tools: List<ToolDefinition>,
        temperature: Double?,
        maxTokens: Int?,
        toolChoice: ToolChoiceSpec?,
        stream: Boolean,
    ): JsonObject = buildJsonObject {
        put("model", config.model)
        put("messages", buildMessagesJson(messages))
        put("stream", stream)
        if (stream) putJsonObject("stream_options") { put("include_usage", true) }

        val strict = config.hints.strictReasoningModel
        val effectiveTemp = temperature ?: config.temperature
        if (effectiveTemp != null && !strict) put("temperature", effectiveTemp)
        if (strict && config.hints.reasoningEffort != null) put("reasoning_effort", config.hints.reasoningEffort)

        val effectiveMax = maxTokens ?: config.maxTokens
        if (effectiveMax != null) {
            put(if (strict) "max_completion_tokens" else "max_tokens", effectiveMax)
        }

        config.hints.enableThinking?.let { put("enable_thinking", it) }
        config.hints.anthropicThinkingBudgetTokens?.let {
            putJsonObject("thinking") {
                put("type", "enabled")
                put("budget_tokens", it)
            }
        }

        if (tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                for (t in tools) add(buildJsonObject {
                    put("type", "function")
                    putJsonObject("function") {
                        put("name", t.name)
                        put("description", t.description.take(1024))
                        put("parameters", parseSchemaOrSkeleton(t.parametersJsonSchema))
                    }
                })
            })
        }

        when (toolChoice) {
            is ToolChoiceSpec.Required -> put("tool_choice", "required")
            is ToolChoiceSpec.None -> put("tool_choice", "none")
            is ToolChoiceSpec.Function -> putJsonObject("tool_choice") {
                put("type", "function")
                putJsonObject("function") { put("name", toolChoice.name) }
            }

            ToolChoiceSpec.Auto, null -> if (tools.isNotEmpty()) put("tool_choice", "auto")
        }
    }

    private fun buildMessagesJson(messages: List<LlmMessage>): JsonArray = buildJsonArray {
        for (m in messages) {
            when (m) {
                is LlmMessage.System -> add(buildJsonObject {
                    put("role", "system")
                    put("content", m.content)
                })

                is LlmMessage.User -> if (m.images.isEmpty()) {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", m.content)
                    })
                } else {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "text")
                                put("text", m.content)
                            })
                            for (img in m.images) add(buildJsonObject {
                                put("type", "image_url")
                                putJsonObject("image_url") {
                                    put("url", "data:${img.mediaType};base64,${img.base64}")
                                }
                            })
                        })
                    })
                }

                is LlmMessage.Assistant -> add(buildJsonObject {
                    put("role", "assistant")
                    m.content?.let { put("content", it) }
                    m.reasoning?.let { put("reasoning_content", it) }
                    if (m.content == null) put("content", JsonPrimitive(null as String?))
                    if (m.toolCalls.isNotEmpty()) {
                        put("tool_calls", buildJsonArray {
                            for (tc in m.toolCalls) add(buildJsonObject {
                                put("id", tc.id)
                                put("type", "function")
                                putJsonObject("function") {
                                    put("name", tc.name)
                                    put("arguments", tc.arguments)
                                }
                            })
                        })
                    }
                })

                is LlmMessage.Tool -> add(buildJsonObject {
                    put("role", "tool")
                    put("tool_call_id", m.toolCallId)
                    put("content", m.content)
                })
            }
        }
    }

    /** 单个坏 schema 降级为空对象骨架，不拖死全部工具。 */
    private fun parseSchemaOrSkeleton(schema: String): JsonElement = try {
        if (schema.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(schema)
    } catch (e: Exception) {
        buildJsonObject { put("type", "object") }
    }

    // ------------------------------------------------------------------
    // 响应解析
    // ------------------------------------------------------------------

    private class ToolCallAccumulator {
        var id: String = ""
        var name: String = ""
        val args = StringBuilder()

        fun toToolCall(index: Int): ToolCall = ToolCall(
            id = id.ifBlank { "call_idx_$index" },
            name = name,
            arguments = args.toString(),
            index = index,
        )
    }

    /** 解析一帧 SSE 数据。tools 累积写入 [toolAccumulator]。 */
    private fun parseStreamFrame(payload: String, toolAccumulator: LinkedHashMap<Int, ToolCallAccumulator>): LlmStreamChunk? {
        val root = try {
            json.parseToJsonElement(payload).jsonObject
        } catch (e: Exception) {
            return null // 非常规帧跳过（部分端点混发心跳/日志）
        }

        val usage = root["usage"]?.let { parseUsage(it) }
        val choices = root["choices"] as? JsonArray
        if (choices == null || choices.isEmpty()) {
            // 统计帧：choices 为空但 usage 非空
            return if (usage != null) LlmStreamChunk(usage = usage) else null
        }

        val choice = choices[0].jsonObject
        val delta = choice["delta"]?.jsonObject ?: choice["message"]?.jsonObject
        var content: String? = null
        var reasoning: String? = null
        val deltas = mutableListOf<ToolCall>()

        if (delta != null) {
            content = delta["content"]?.jsonPrimitive?.contentOrNull
            reasoning = (delta["reasoning_content"] ?: delta["reasoning"])?.jsonPrimitive?.contentOrNull
            (delta["tool_calls"] as? JsonArray)?.forEach { el ->
                val tc = el.jsonObject
                val index = (tc["index"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
                val acc = toolAccumulator.getOrPut(index) { ToolCallAccumulator() }
                tc["id"]?.jsonPrimitive?.contentOrNull?.let { if (it.isNotBlank()) acc.id = it }
                val fn = tc["function"]?.jsonObject
                fn?.get("name")?.jsonPrimitive?.contentOrNull?.let { if (it.isNotBlank()) acc.name = it }
                fn?.get("arguments")?.jsonPrimitive?.contentOrNull?.let { acc.args.append(it) }
                deltas += ToolCall(id = acc.id, name = acc.name, arguments = acc.args.toString(), index = index)
            }
        }

        val finishReason = choice["finish_reason"]?.jsonPrimitive?.contentOrNull
        val isFinish = finishReason != null && finishReason != "null"

        return LlmStreamChunk(
            content = content?.takeIf { it.isNotEmpty() },
            reasoning = reasoning?.takeIf { it.isNotEmpty() },
            toolCallDeltas = deltas,
            usage = usage,
            finish = isFinish,
            completeToolCalls = if (isFinish) {
                toolAccumulator.toSortedMap().values.mapIndexed { i, acc -> acc.toToolCall(i) }
            } else {
                emptyList()
            },
        )
    }

    private fun parseNonStreamResponse(root: JsonObject): LlmResponse {
        val usage = root["usage"]?.let { parseUsage(it) }
        val choices = root["choices"] as? JsonArray
        if (choices.isNullOrEmpty()) {
            return LlmResponse(content = null, usage = usage)
        }
        val message = choices[0].jsonObject["message"]?.jsonObject
            ?: throw LlmException.Parse("choices[0].message 缺失")
        val content = message["content"]?.jsonPrimitive?.contentOrNull
        val reasoning = (message["reasoning_content"] ?: message["reasoning"])?.jsonPrimitive?.contentOrNull
        val toolCalls = (message["tool_calls"] as? JsonArray)?.mapIndexed { i, el ->
            val tc = el.jsonObject
            val fn = tc["function"]?.jsonObject
            ToolCall(
                id = tc["id"]?.jsonPrimitive?.contentOrNull ?: "call_$i",
                name = fn?.get("name")?.jsonPrimitive?.contentOrNull ?: "",
                arguments = fn?.get("arguments")?.jsonPrimitive?.contentOrNull ?: "{}",
                index = i,
            )
        } ?: emptyList()
        return LlmResponse(content = content, reasoning = reasoning, toolCalls = toolCalls, usage = usage)
    }

    internal fun parseUsage(el: JsonElement): Usage? {
        val obj = el as? JsonObject ?: return null
        val prompt = (obj["prompt_tokens"] as? JsonPrimitive)?.longOrNull ?: 0L
        val completion = (obj["completion_tokens"] as? JsonPrimitive)?.longOrNull ?: 0L
        val total = (obj["total_tokens"] as? JsonPrimitive)?.longOrNull ?: (prompt + completion)
        // yl-ai 对齐（I 组缺口）：DeepSeek / OpenAI 等在 prompt_tokens_details 里
        // 上报命中上下文缓存的 prompt tokens；未上报时保持 null（区别于 0）
        val cached = (obj["prompt_tokens_details"] as? JsonObject)
            ?.let { (it["cached_tokens"] as? JsonPrimitive)?.longOrNull }
        return Usage(prompt, completion, total, cached)
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private suspend fun post(body: JsonObject): Response {
        val url = normalizeEndpoint(config.baseUrl) + "/chat/completions"
        val requestBuilder = Request.Builder()
            .url(url)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
        if (config.apiKey.isNotBlank()) requestBuilder.header("Authorization", "Bearer ${config.apiKey}")
        config.extraHeaders.forEach { (k, v) -> requestBuilder.header(k, v) }

        val call = http.newCall(requestBuilder.build())
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (cont.isActive) cont.resumeWithException(LlmException.Network(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    // 修复 issue #21 L-9：取消恰好发生在恢复前时关闭 Response，
                    // 避免连接泄漏
                    if (cont.isActive) cont.resume(response) else response.close()
                }
            })
        }
    }

}

/** OpenAI 兼容路径后缀（用户可能把整个补全路径填进 baseUrl，需先剥掉再拼）。 */
private const val COMPLETIONS_SUFFIX = "/chat/completions"

/** 版本化 base 段：/v1、/v2、/v4…（智谱 GLM 是 /api/paas/v4，不能误补 /v1）。 */
private val VERSIONED_BASE = Regex("/v\\d+$")

/**
 * Base URL 归一化（yl-ai `normalizeEndpoint` 的移植，I 组缺口）。
 *
 * 规则（KDoc 即规格）：
 * 1. 以 `/chat/completions` 结尾 → 剥掉后缀（旧版框架要求 baseUrl 精确到补全路径，
 *    老配置直填全路径必须继续可用；post 时会再拼回）；
 * 2. 以版本段 `/v1`（或其它 `/vN`）结尾 → 原样保留（yl-ai 只认 /v1；这里泛化为
 *    /vN 以兼容智谱 GLM 的 /api/paas/v4 预设，否则会被错误补成 /v4/v1）；
 * 3. 其余（如 `https://api.deepseek.com`）→ 补 `/v1`。
 *
 * post 的最终 URL 恒为 `normalizeEndpoint(baseUrl) + "/chat/completions"`：
 * 用户填 `https://api.deepseek.com` 不再 404，填到 /v1 或全路径的老配置不受影响。
 * 首尾空白与尾斜杠一律容忍。
 */
internal fun normalizeEndpoint(raw: String): String {
    val trimmed = raw.trim().trimEnd('/')
    return when {
        trimmed.endsWith(COMPLETIONS_SUFFIX) -> trimmed.removeSuffix(COMPLETIONS_SUFFIX)
        VERSIONED_BASE.containsMatchIn(trimmed) -> trimmed
        else -> "$trimmed/v1"
    }
}

/**
 * HTTP 错误的中文诊断文案（yl-ai `describeHttpError` 的等价移植，I 组缺口）。
 *
 * 保持 [LlmException] 结构不变：仅把「中文提示 + 服务端原始错误摘要」拼进
 * [LlmException.Http.body]（message 随之可读），statusCode 原样保留 ——
 * [LlmException.isTransient] 的重试判定不受影响。
 */
internal fun describeHttpError(status: Int, body: String?): String {
    val brief = body?.take(400)?.replace("\n", " ").orEmpty()
    return when (status) {
        401, 403 -> "鉴权失败（$status）：请检查 API Key 是否正确、是否有该模型的权限。$brief"
        404 -> "接口地址不存在（404）：请检查 Base URL 是否填写正确（通常以 /v1 结尾）。$brief"
        429 -> "触发限流（429）：请求过于频繁或额度用尽。$brief"
        in 500..599 -> "服务端错误（$status）：模型服务暂时不可用，可稍后重试。$brief"
        else -> "请求失败（$status）：$brief"
    }
}
