package com.androidguru.agent.memory

import com.androidguru.agent.tools.AgentTool
import com.androidguru.agent.tools.ToolErrorCode
import com.androidguru.agent.tools.ToolRequest
import com.androidguru.agent.tools.ToolResult
import com.androidguru.agent.tools.ToolSchema
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 记忆三件套工具 —— 模型对长期记忆的**主动**读写通道。
 *
 * 自动通道（召回注入 / 会话抽取 / 压缩捕获）负责「无感」，
 * 本组工具负责「精确」：模型判断某信息值得跨会话记住时显式落库，
 * 判断过时 / 错误时显式纠正。对齐 MemGPT 的 archival memory 工具模式。
 *
 * - **memory_save**：保存一条记忆（类别 / 重要度 / 标签）；
 * - **memory_search**：关键词查询，返回带 id 的渲染结果；
 * - **memory_forget**：按 id 删除（纠正能力 —— 记忆系统必须允许改错）。
 */
class MemorySaveTool(
    private val store: MemoryStore,
) : AgentTool {

    override val id: String = ID
    override val name: String = ID
    override val description: String =
        "把一条值得跨会话记住的信息保存进长期记忆（用户偏好、项目事实、经验教训、" +
            "任务结论等）。自动召回按相关性注入，但重要信息请显式保存以确保存续。" +
            "content 一句话写清完整信息（不含上下文也能看懂）；importance 0~100，" +
            "用户明确表达的重要偏好用 80+。"

    override val parameters: ToolSchema = ToolSchema.build {
        string("content", "记忆内容（自包含的一句话，脱离对话也能理解）", required = true)
        string(
            "kind",
            "记忆类别",
            required = false,
            enumValues = listOf("fact", "preference", "lesson", "task_outcome", "session_summary"),
        )
        integer("importance", "重要度 0~100（默认 50）", required = false, minimum = 0.0, maximum = 100.0)
        array("tags", "检索标签（可选）", required = false, itemType = "string")
    }

    override suspend fun execute(request: ToolRequest): ToolResult {
        val obj = try {
            Json.parseToJsonElement(request.arguments.ifBlank { "{}" }).jsonObject
        } catch (e: Exception) {
            return failure("arguments 不是合法 JSON 对象: ${e.message}")
        }

        val content = obj["content"]?.jsonPrimitive?.contentOrNull?.trim()
        if (content.isNullOrBlank()) return failure("content 不能为空")

        val kind = MemoryKind.parse(obj["kind"]?.jsonPrimitive?.contentOrNull)
        val importance = (obj["importance"]?.jsonPrimitive?.longOrNull ?: 50L).toInt().coerceIn(0, 100)
        val tags = (obj["tags"] as? JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim() }
            ?.filter { it.isNotEmpty() }
            ?.take(8)
            ?: emptyList()

        val record = store.append(
            MemoryRecord(
                id = "",
                kind = kind,
                content = content.take(MAX_CONTENT_CHARS),
                importance = importance,
                tags = tags,
            ),
        )
        return ToolResult.success(
            "已保存记忆 [${record.id}] ${kind.label} / 重要度 ${record.importance}" +
                (if (record.tags.isNotEmpty()) " / 标签 ${record.tags.joinToString(",")}" else "") +
                "：${record.content}",
            data = JsonObject(mapOf("id" to JsonPrimitive(record.id), "kind" to JsonPrimitive(record.kind.name))).toString(),
        )
    }

    private fun failure(message: String) = ToolResult.failure(message, ToolErrorCode.VALIDATION)

    companion object {
        const val ID = "memory_save"
        const val MAX_CONTENT_CHARS = 500
    }
}

/**
 * 记忆查询工具：关键词 + 类别 / 标签过滤，返回带 id 的渲染结果
 * （id 供 memory_forget 精确寻址）。
 */
class MemorySearchTool(
    private val recall: MemoryRecall,
) : AgentTool {

    override val id: String = ID
    override val name: String = ID
    override val description: String =
        "按关键词检索长期记忆，返回最相关的条目（含 id、类别、重要度）。" +
            "当自动召回没有覆盖到需要的历史信息时使用。"

    override val parameters: ToolSchema = ToolSchema.build {
        string("query", "检索关键词", required = true)
        string(
            "kind",
            "限定记忆类别（可选）",
            required = false,
            enumValues = listOf("fact", "preference", "lesson", "task_outcome", "session_summary"),
        )
        integer("limit", "最多返回条数（1~20，默认 5）", required = false, minimum = 1.0, maximum = 20.0)
    }

    override suspend fun execute(request: ToolRequest): ToolResult {
        val obj = try {
            Json.parseToJsonElement(request.arguments.ifBlank { "{}" }).jsonObject
        } catch (e: Exception) {
            return ToolResult.failure("arguments 不是合法 JSON 对象: ${e.message}", ToolErrorCode.VALIDATION)
        }

        val query = obj["query"]?.jsonPrimitive?.contentOrNull?.trim()
        if (query.isNullOrBlank()) {
            return ToolResult.failure("query 不能为空", ToolErrorCode.VALIDATION)
        }

        val kindFilter = obj["kind"]?.jsonPrimitive?.contentOrNull
            ?.let { MemoryKind.parse(it) }
            ?.let { setOf(it) }
        val limit = (obj["limit"]?.jsonPrimitive?.longOrNull ?: 5L).toInt().coerceIn(1, 20)

        val records = recall.recall(
            MemoryQuery(
                text = query,
                kinds = kindFilter,
                limit = limit,
                maxTokens = 1200,
                minScore = 0.0,
                minKeywordCoverage = 0.1, // 检索模式：过滤掉关键词零命中的记录
            ),
        )
        return if (records.isEmpty()) {
            ToolResult.success("（没有匹配的长期记忆）")
        } else {
            ToolResult.success(renderMemories(records))
        }
    }

    companion object {
        const val ID = "memory_search"
    }
}

/**
 * 记忆删除工具：按 id 删除（记忆系统的「改错」能力 —— 过时信息必须可清除，
 * 否则错误记忆会被反复召回强化）。
 */
class MemoryForgetTool(
    private val store: MemoryStore,
) : AgentTool {

    override val id: String = ID
    override val name: String = ID
    override val description: String =
        "按 id 删除一条过时 / 错误的长期记忆（id 来自 memory_search 结果或保存时的回执）。" +
            "仅删除明确过时的条目；不确定时先 memory_search 确认。"

    override val parameters: ToolSchema = ToolSchema.build {
        string("id", "要删除的记忆 id（如 m3）", required = true)
    }

    override suspend fun execute(request: ToolRequest): ToolResult {
        val obj = try {
            Json.parseToJsonElement(request.arguments.ifBlank { "{}" }).jsonObject
        } catch (e: Exception) {
            return ToolResult.failure("arguments 不是合法 JSON 对象: ${e.message}", ToolErrorCode.VALIDATION)
        }

        val id = obj["id"]?.jsonPrimitive?.contentOrNull?.trim()
        if (id.isNullOrBlank()) {
            return ToolResult.failure("id 不能为空", ToolErrorCode.VALIDATION)
        }

        return if (store.remove(id)) {
            ToolResult.success("已删除记忆 [$id]")
        } else {
            ToolResult.failure(
                "记忆 [$id] 不存在（可能已被删除）。可用 memory_search 查询现存记忆。",
                ToolErrorCode.NOT_FOUND,
            )
        }
    }

    companion object {
        const val ID = "memory_forget"
    }
}
