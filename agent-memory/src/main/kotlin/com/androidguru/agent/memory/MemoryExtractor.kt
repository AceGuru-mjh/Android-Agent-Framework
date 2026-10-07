package com.androidguru.agent.memory

import com.androidguru.agent.llm.LlmClient
import com.androidguru.agent.llm.LlmMessage
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 会话记忆抽取器 —— 把一次会话的对话提炼为长期记忆。
 *
 * 两条路径：
 * 1. **LLM 抽取**（[client] 提供时）：渲染对话 → 提示词要求输出严格 JSON 数组
 *    `[{kind, content, importance, tags}]` → 宽容解析（剥代码围栏 / 截取首个 `[` 到末个 `]`）
 *    → 逐条校验钳制；
 * 2. **启发式回退**（无 client / 调用失败 / 输出不合法）：确定性提炼 ——
 *    首条用户消息（任务目标）+ 末条助手正文（结论）合成一条 [MemoryKind.TASK_OUTCOME]。
 *    回退保证「没有 LLM 也永远有记忆」，只是质量降级。
 *
 * 去重合并（[extractAndStore]）：与既有同类记忆做 token 集合 Jaccard 相似度比对，
 * ≥ [duplicateThreshold] 视为重复 —— 不落库、改为刷新既有记录的访问统计
 * （重要信息被反复提及时自然升温，而不是堆出 N 条近义记忆）。
 */
class MemoryExtractor(
    private val client: LlmClient? = null,
    private val clock: () -> Long = System::currentTimeMillis,
    /** 单次抽取上限（防 LLM 输出轰炸）。 */
    private val maxMemories: Int = 8,
    /** 对话渲染的最大字符数（超长截断，保头部 + 尾部）。 */
    private val maxInputChars: Int = 24_000,
    /** 重复判定阈值（token 集合 Jaccard）。 */
    private val duplicateThreshold: Double = 0.75,
) {

    /** 抽取（不入库）。失败 / 无 LLM 时走启发式回退。 */
    suspend fun extract(messages: List<LlmMessage>, sessionId: String?): List<MemoryRecord> {
        if (messages.isEmpty()) return emptyList()

        val viaLlm = client?.let { tryExtractViaLlm(it, messages, sessionId) } ?: emptyList()
        return if (viaLlm.isNotEmpty()) viaLlm else heuristicExtract(messages, sessionId)
    }

    /** 抽取 + 去重入库；返回实际新增的记录（重复项只升温不落库）。 */
    suspend fun extractAndStore(
        messages: List<LlmMessage>,
        sessionId: String?,
        store: MemoryStore,
    ): List<MemoryRecord> {
        val extracted = extract(messages, sessionId)
        val now = clock()
        val existing = store.all()
        val added = mutableListOf<MemoryRecord>()

        for (record in extracted) {
            val duplicate = existing.firstOrNull { isSimilar(record, it) }
            if (duplicate != null) {
                // 重复：刷新既有记录（新近度 + 访问次数 + 重要度取高）
                try {
                    store.update(
                        duplicate.copy(
                            importance = maxOf(duplicate.importance, record.importance),
                            lastAccessedAtMs = now,
                            accessCount = duplicate.accessCount + 1,
                        ),
                    )
                } catch (e: Exception) {
                    // 升温失败不影响其余记忆入库
                }
                continue
            }
            try {
                added += store.append(record)
            } catch (e: Exception) {
                // 单条落库失败（如内容非法）跳过
            }
        }
        return added
    }

    // ------------------------------------------------------------------
    // LLM 路径
    // ------------------------------------------------------------------

    /** 抽取提示词（实例属性：引用 [maxMemories] 实际值）。 */
    private val extractionPrompt = """
        你是记忆抽取器。从以下对话中提炼值得跨会话记住的信息，输出严格 JSON 数组（不要任何其他文字）：
        [{"kind": "fact|preference|lesson|task_outcome|session_summary", "content": "自包含的一句话", "importance": 0-100, "tags": ["可选"]}]
        抽取纪律：
        - 只保留脱离对话上下文仍有价值的稳定信息（用户偏好、项目事实、踩坑教训、任务结论）；
        - 忽略寒暄、过程性细节、工具调用的原始输出；
        - 用户明确要求记住的信息 importance ≥ 80；
        - 最多 $maxMemories 条，宁缺毋滥；没有值得记的就输出 []。
    """.trimIndent()

    private suspend fun tryExtractViaLlm(
        client: LlmClient,
        messages: List<LlmMessage>,
        sessionId: String?,
    ): List<MemoryRecord> {
        return try {
            val rendered = renderConversation(messages)
            if (rendered.isBlank()) return emptyList()

            val response = client.chat(
                messages = listOf(
                    LlmMessage.System(extractionPrompt),
                    LlmMessage.User(rendered.take(maxInputChars)),
                ),
                maxTokens = 1000,
            )
            parseMemories(response.content, sessionId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            emptyList() // LLM 失败 → 启发式回退（调用方负责）
        }
    }

    /** 宽容解析模型输出：剥围栏 / 截 `[..]` / 逐条校验。 */
    internal fun parseMemories(raw: String?, sessionId: String?): List<MemoryRecord> {
        if (raw.isNullOrBlank()) return emptyList()

        val body = extractJsonArray(raw) ?: return emptyList()
        val array = try {
            Json.parseToJsonElement(body).jsonArray
        } catch (e: Exception) {
            return emptyList()
        }

        val now = clock()
        val parsed = mutableListOf<MemoryRecord>()
        for (el in array) {
            if (parsed.size >= maxMemories) break
            val obj = el as? JsonObject ?: continue
            val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim() ?: continue
            if (content.isBlank()) continue
            parsed += MemoryRecord(
                id = "",
                kind = MemoryKind.parse(obj["kind"]?.jsonPrimitive?.contentOrNull),
                content = content.take(MemorySaveTool.MAX_CONTENT_CHARS),
                importance = (obj["importance"]?.jsonPrimitive?.longOrNull ?: 50L).toInt().coerceIn(0, 100),
                sessionId = sessionId,
                tags = (obj["tags"] as? JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                    ?.filter { it.isNotBlank() }
                    ?.take(8)
                    ?: emptyList(),
                createdAtMs = now,
                lastAccessedAtMs = now,
            )
        }
        return parsed
    }

    /** 截取首个 `[` 到末个 `]` 的片段（容忍围栏 / 前后解释文字）。 */
    private fun extractJsonArray(raw: String): String? {
        val cleaned = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```")
            .trim()
        val start = cleaned.indexOf('[')
        val end = cleaned.lastIndexOf(']')
        if (start < 0 || end <= start) return null
        return cleaned.substring(start, end + 1)
    }

    // ------------------------------------------------------------------
    // 启发式回退
    // ------------------------------------------------------------------

    /**
     * 无 LLM 的确定性提炼：
     * - 首条用户消息 → 任务目标；
     * - 末条带正文的助手消息 → 结论。
     * 两者齐备时合成一条 TASK_OUTCOME；只有目标时存为 SESSION_SUMMARY（待续）。
     */
    internal fun heuristicExtract(messages: List<LlmMessage>, sessionId: String?): List<MemoryRecord> {
        val firstUser = messages.firstOrNull { it is LlmMessage.User && it.content.isNotBlank() }
            ?.let { (it as LlmMessage.User).content }
        if (firstUser == null) return emptyList()

        val lastAssistant = messages.lastOrNull { it is LlmMessage.Assistant && !it.content.isNullOrBlank() }
            ?.let { (it as LlmMessage.Assistant).content }

        val now = clock()
        return if (lastAssistant != null) {
            listOf(
                MemoryRecord(
                    id = "",
                    kind = MemoryKind.TASK_OUTCOME,
                    content = "任务「${firstUser.take(80)}」结论：${lastAssistant.take(300)}",
                    importance = 55,
                    sessionId = sessionId,
                    createdAtMs = now,
                    lastAccessedAtMs = now,
                ),
            )
        } else {
            listOf(
                MemoryRecord(
                    id = "",
                    kind = MemoryKind.SESSION_SUMMARY,
                    content = "会话中用户提出：${firstUser.take(160)}（未记录到结论）",
                    importance = 40,
                    sessionId = sessionId,
                    createdAtMs = now,
                    lastAccessedAtMs = now,
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // 渲染与相似度
    // ------------------------------------------------------------------

    /** 对话渲染：正文优先（工具结果截断），memory_save 调用回执也作为信号保留。 */
    private fun renderConversation(messages: List<LlmMessage>): String = messages
        .mapNotNull { m ->
            when (m) {
                is LlmMessage.User -> "User: ${m.content}".takeIf { m.content.isNotBlank() }
                is LlmMessage.Assistant -> m.content?.let { "Assistant: $it" }
                is LlmMessage.Tool -> "Tool: ${m.content.take(200)}".takeIf { m.content.isNotBlank() }
                is LlmMessage.System -> null // 系统注入（动态上下文等）不构成记忆信号
            }
        }
        .joinToString("\n")
        .take(maxInputChars)

    /** token 集合 Jaccard 相似度（类别相同才比较）。 */
    internal fun isSimilar(a: MemoryRecord, b: MemoryRecord): Boolean {
        if (a.kind != b.kind) return false
        val ta = MemoryRecall.tokenize(a.content).toHashSet()
        val tb = MemoryRecall.tokenize(b.content).toHashSet()
        if (ta.isEmpty() || tb.isEmpty()) return false
        val intersection = ta.count { it in tb }
        val union = ta.size + tb.size - intersection
        return union > 0 && intersection.toDouble() / union >= duplicateThreshold
    }
}
