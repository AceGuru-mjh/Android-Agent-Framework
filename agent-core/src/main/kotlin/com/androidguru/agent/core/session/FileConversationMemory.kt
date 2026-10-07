package com.androidguru.agent.core.session

import com.androidguru.agent.llm.ImageContent
import com.androidguru.agent.llm.LlmMessage
import com.androidguru.agent.llm.ToolCall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * 崩溃安全的文件会话记忆（JSONL 追加式持久化）。
 *
 * 长程任务跨进程生命周期的恢复能力：
 * - **追加即持久**：每条消息一行 JSON，写后立即 flush —— 进程崩溃（JVM 死亡）后已写入消息不丢；
 * - **原子替换**：压缩触发的 [replaceAll] 走「临时文件 + 原子 move」；
 * - **容错加载**：损坏行（半写 / 手工编辑错误）跳过并计数，绝不因单行损坏报废整个会话；
 * - 零第三方依赖（手工 JSON 编解码，[LlmMessage] 保持非 Serializable 的纯模型身份）。
 *
 * 用法（崩溃恢复三步）：
 *
 * ```kotlin
 * val memory = FileConversationMemory(Path.of("data/sessions/${sessionId}.jsonl"))
 * // 进程重启后：memory.load() 自动在构造时执行，历史完整恢复
 * val engine = DefaultAgentEngine(llm, registry, executor, memory = memory, ...)
 * // 若上一程因预算耗尽 / 崩溃中断，直接：
 * engine.continueExecution(50).collect { ... }
 * ```
 *
 * > fsync 级掉电保护不在此层承诺（flush 覆盖进程崩溃场景；掉电安全由宿主按需加 fsync）。
 */
class FileConversationMemory(
    private val path: Path,
    /** 构造时是否立即加载既有历史。 */
    loadNow: Boolean = true,
) : ConversationMemory {

    // 状态字段必须在 init（load）之前初始化 —— Kotlin 属性按声明顺序初始化
    private val lock = Any()

    private val messages = mutableListOf<LlmMessage>()

    /** 最近一次 [load] 跳过的损坏行数（观测用）。 */
    var skippedCorruptLines: Int = 0
        private set

    init {
        if (loadNow) load()
    }

    // ------------------------------------------------------------------
    // 追加式写入（每行一个消息，flush 即进程崩溃安全）
    // ------------------------------------------------------------------

    override fun appendSystem(content: String) = append(LlmMessage.System(content))

    override fun appendUser(text: String, images: List<ImageContent>) =
        append(LlmMessage.User(text, images))

    override fun appendAssistant(content: String?, toolCalls: List<ToolCall>) =
        append(LlmMessage.Assistant(content = content, toolCalls = toolCalls))

    override fun appendToolResult(toolCallId: String, content: String) =
        append(LlmMessage.Tool(toolCallId, content))

    override fun snapshot(): List<LlmMessage> = synchronized(lock) { messages.toList() }

    // ------------------------------------------------------------------
    // 整体替换（压缩回写）：临时文件 + 原子 move
    // ------------------------------------------------------------------

    override fun replaceAll(messages: List<LlmMessage>) {
        synchronized(lock) {
            val dir = path.toAbsolutePath().parent
            if (dir != null) Files.createDirectories(dir)
            val tmp = dir?.resolve("${path.fileName}.tmp") ?: Path.of("${path.fileName}.tmp")
            Files.newBufferedWriter(tmp, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { w ->
                for (m in messages) {
                    w.write(encode(m))
                    w.newLine()
                }
                w.flush()
            }
            Files.move(tmp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            this@FileConversationMemory.messages.clear()
            this@FileConversationMemory.messages.addAll(messages)
            Unit
        }
    }

    override fun clear() {
        synchronized(lock) {
            messages.clear()
            val dir = path.toAbsolutePath().parent
            if (dir != null) Files.createDirectories(dir)
            Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { it.flush() }
            Unit
        }
    }

    // ------------------------------------------------------------------
    // 加载 / 重建
    // ------------------------------------------------------------------

    /** 从文件重建内存态；返回加载成功的消息条数。损坏行跳过并计入 [skippedCorruptLines]。 */
    fun load(): Int {
        val loaded = mutableListOf<LlmMessage>()
        var corrupt = 0
        if (Files.exists(path)) {
            Files.newBufferedReader(path, StandardCharsets.UTF_8).use { r ->
                var line = r.readLine()
                while (line != null) {
                    if (line.isNotBlank()) {
                        try {
                            decode(line)?.let(loaded::add)
                        } catch (e: Exception) {
                            corrupt++
                        }
                    }
                    line = r.readLine()
                }
            }
        }
        synchronized(lock) {
            messages.clear()
            messages.addAll(loaded)
        }
        skippedCorruptLines = corrupt
        return loaded.size
    }

    // ------------------------------------------------------------------
    // 内部：JSONL 编解码
    // ------------------------------------------------------------------

    private fun append(message: LlmMessage) {
        synchronized(lock) {
            val dir = path.toAbsolutePath().parent
            if (dir != null) Files.createDirectories(dir)
            Files.newBufferedWriter(path, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND).use { w ->
                w.write(encode(message))
                w.newLine()
                w.flush()
            }
            messages.add(message)
            Unit
        }
    }

    private fun encode(m: LlmMessage): String = when (m) {
        is LlmMessage.System -> JsonObject(
            mapOf("kind" to JsonPrimitive("system"), "content" to JsonPrimitive(m.content)),
        ).toString()

        is LlmMessage.User -> JsonObject(
            buildMap {
                put("kind", JsonPrimitive("user"))
                put("content", JsonPrimitive(m.content))
                if (m.images.isNotEmpty()) {
                    put(
                        "images",
                        JsonArray(
                            m.images.map {
                                JsonObject(
                                    mapOf(
                                        "base64" to JsonPrimitive(it.base64),
                                        "mediaType" to JsonPrimitive(it.mediaType),
                                    ),
                                )
                            },
                        ),
                    )
                }
            },
        ).toString()

        is LlmMessage.Assistant -> JsonObject(
            buildMap {
                put("kind", JsonPrimitive("assistant"))
                m.content?.let { put("content", JsonPrimitive(it)) }
                if (m.toolCalls.isNotEmpty()) {
                    put(
                        "toolCalls",
                        JsonArray(
                            m.toolCalls.map {
                                JsonObject(
                                    mapOf(
                                        "id" to JsonPrimitive(it.id),
                                        "name" to JsonPrimitive(it.name),
                                        "arguments" to JsonPrimitive(it.arguments),
                                        "index" to JsonPrimitive(it.index),
                                    ),
                                )
                            },
                        ),
                    )
                }
                m.reasoning?.let { put("reasoning", JsonPrimitive(it)) }
            },
        ).toString()

        is LlmMessage.Tool -> JsonObject(
            mapOf(
                "kind" to JsonPrimitive("tool"),
                "toolCallId" to JsonPrimitive(m.toolCallId),
                "content" to JsonPrimitive(m.content),
            ),
        ).toString()
    }

    private fun decode(line: String): LlmMessage? {
        val obj = Json.parseToJsonElement(line).jsonObject
        return when (obj["kind"]?.jsonPrimitive?.contentOrNull) {
            "system" -> LlmMessage.System(obj["content"]?.jsonPrimitive?.contentOrNull ?: return null)
            "user" -> {
                val content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return null
                val images = obj["images"]?.jsonArray?.mapNotNull { el ->
                    val io = el.jsonObject
                    val base64 = io["base64"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                    ImageContent(
                        base64 = base64,
                        mediaType = io["mediaType"]?.jsonPrimitive?.contentOrNull ?: "image/png",
                    )
                } ?: emptyList()
                LlmMessage.User(content, images)
            }

            "assistant" -> {
                val toolCalls = obj["toolCalls"]?.jsonArray?.mapNotNull { el ->
                    val co = el.jsonObject
                    ToolCall(
                        id = co["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        name = co["name"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        arguments = co["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}",
                        index = co["index"]?.jsonPrimitive?.longOrNull?.toInt() ?: -1,
                    )
                } ?: emptyList()
                LlmMessage.Assistant(
                    content = obj["content"]?.jsonPrimitive?.contentOrNull,
                    toolCalls = toolCalls,
                    reasoning = obj["reasoning"]?.jsonPrimitive?.contentOrNull,
                )
            }

            "tool" -> LlmMessage.Tool(
                toolCallId = obj["toolCallId"]?.jsonPrimitive?.contentOrNull ?: return null,
                content = obj["content"]?.jsonPrimitive?.contentOrNull ?: return null,
            )

            else -> null
        }
    }
}
