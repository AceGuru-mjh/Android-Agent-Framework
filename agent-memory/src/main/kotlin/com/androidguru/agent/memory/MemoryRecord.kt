package com.androidguru.agent.memory

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.util.concurrent.atomic.AtomicLong

/**
 * 记忆类别 —— 决定渲染标签与抽取提示词的语义分区。
 *
 * 对标认知科学的三层分类（适配智能体场景）：
 * - 语义记忆：[FACT]（事实）/ [PREFERENCE]（用户偏好）/ [LESSON]（经验教训）；
 * - 情景记忆：[SESSION_SUMMARY]（过去会话的摘要，含压缩捕获的早期对话）；
 * - 任务记忆：[TASK_OUTCOME]（任务结论 —— 做成了什么 / 怎么做成的）。
 */
enum class MemoryKind(val label: String) {
    FACT("事实"),
    PREFERENCE("偏好"),
    LESSON("经验教训"),
    TASK_OUTCOME("任务结论"),
    SESSION_SUMMARY("会话摘要"),
    ;

    companion object {
        /** 宽容解析：未知取值回落 [FACT]（模型输出容错）。 */
        fun parse(value: String?): MemoryKind =
            entries.firstOrNull { it.name.equals(value, ignoreCase = true) } ?: FACT
    }
}

/**
 * 长期记忆单元 —— 跨会话存续的最小知识粒度。
 *
 * 评分四因子（见 [MemoryRecall]）全部由本结构承载：
 * - [importance]：重要度 0..100（静态权重，写入时由模型或宿主指定）；
 * - [createdAtMs] / [lastAccessedAtMs]：新近度衰减的基准；
 * - [accessCount]：使用频率（召回命中并真正注入上下文时 +1）。
 *
 * [id] 由 [MemoryStore] 统一分配（`m1`、`m2`…对宿主稳定），
 * memory_forget 工具按 id 精确寻址。模型类型保持纯 data class，
 * JSON 编解码由 [MemoryCodec] 手工实现（对齐 FileConversationMemory 纪律）。
 */
data class MemoryRecord(
    val id: String,
    val kind: MemoryKind,
    val content: String,
    val importance: Int = 50,
    /** 来源会话（可追溯「这条记忆是哪次会话学到的」）。 */
    val sessionId: String? = null,
    val tags: List<String> = emptyList(),
    val createdAtMs: Long = System.currentTimeMillis(),
    val lastAccessedAtMs: Long = createdAtMs,
    val accessCount: Int = 0,
) {
    init {
        require(content.isNotBlank()) { "记忆内容不能为空" }
        require(importance in 0..100) { "重要度必须在 0..100，实际 $importance" }
    }

    /** 记录一次有效访问（受冷却间隔控制时由 [MemoryRecall] 判定后再调用）。 */
    fun accessed(nowMs: Long, newAccess: Boolean = true): MemoryRecord = copy(
        lastAccessedAtMs = nowMs,
        accessCount = if (newAccess) accessCount + 1 else accessCount,
    )

    /** 渲染给模型的单行形式。 */
    fun renderForModel(): String = buildString {
        append("- [").append(kind.label).append("] ")
        append(content)
        append("  (重要度 ").append(importance).append(")")
    }
}

/**
 * 记忆 JSON 编解码（手工实现，对齐 agent-core / agent-tasks 的无注解纪律）。
 *
 * 损坏 / 缺关键字段的行由解码方容错跳过（见 [FileMemoryStore]），
 * 编码方保证字段齐全。
 */
internal object MemoryCodec {

    fun encode(r: MemoryRecord): JsonObject = JsonObject(
        buildMap {
            put("id", JsonPrimitive(r.id))
            put("kind", JsonPrimitive(r.kind.name))
            put("content", JsonPrimitive(r.content))
            put("importance", JsonPrimitive(r.importance))
            if (r.sessionId != null) put("sessionId", JsonPrimitive(r.sessionId))
            if (r.tags.isNotEmpty()) put("tags", JsonArray(r.tags.map(::JsonPrimitive)))
            put("createdAtMs", JsonPrimitive(r.createdAtMs))
            put("lastAccessedAtMs", JsonPrimitive(r.lastAccessedAtMs))
            put("accessCount", JsonPrimitive(r.accessCount))
        },
    )

    fun decode(obj: JsonObject): MemoryRecord? {
        val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return null
        val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return null
        if (content.isBlank()) return null
        val importance = obj["importance"]?.jsonPrimitive?.longOrNull?.toInt() ?: 50
        return MemoryRecord(
            id = id,
            kind = MemoryKind.parse(obj["kind"]?.jsonPrimitive?.contentOrNull),
            content = content,
            importance = importance.coerceIn(0, 100),
            sessionId = obj["sessionId"]?.jsonPrimitive?.contentOrNull,
            tags = obj["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList(),
            createdAtMs = obj["createdAtMs"]?.jsonPrimitive?.longOrNull ?: 0L,
            lastAccessedAtMs = obj["lastAccessedAtMs"]?.jsonPrimitive?.longOrNull
                ?: obj["createdAtMs"]?.jsonPrimitive?.longOrNull ?: 0L,
            accessCount = obj["accessCount"]?.jsonPrimitive?.longOrNull?.toInt() ?: 0,
        )
    }

    fun decodeLine(line: String): MemoryRecord? = try {
        decode(Json.parseToJsonElement(line).jsonObject)
    } catch (e: Exception) {
        null
    }
}

/** id 分配器（进程内单调递增；[FileMemoryStore] 加载后从既有最大号续编）。 */
internal object MemoryIds {
    private val seq = AtomicLong(0)

    fun next(): String = "m" + seq.incrementAndGet()

    fun maxKnown(records: List<MemoryRecord>): Long = records
        .mapNotNull { r -> r.id.removePrefix("m").toLongOrNull() }
        .maxOrNull() ?: 0L
}
