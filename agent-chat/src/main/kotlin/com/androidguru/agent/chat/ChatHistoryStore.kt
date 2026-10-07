package com.androidguru.agent.chat

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonObject
import java.io.File

/**
 * 聊天历史持久化 —— yl-ai `ChatStore` 的纯 JVM 重写（org.json → kotlinx-serialization）。
 *
 * 沿用 yl-ai 的四个既定行为，并说明理由：
 * - **tmp + rename 原子写**：先写 `chat-history.json.tmp` 再改名。聊天历史是
 *   全量覆写的小文件（≤200 条），rename 在 POSIX 上是原子操作 —— 进程被杀时
 *   不会留下半截 JSON。改名失败（Windows 目标占用等）回退为直接覆写；
 * - **MAX_ITEMS = 200**：聊天流水只保留最近 200 条。它服务的是"回看最近发生
 *   了什么"，不是全量审计（审计在 agent-shell 的 AuditLog，JSONL 追加制）；
 * - **outputTail 截 4000 字符**：只存尾部（`takeLast`）。尾部是最有信息量的
 *   部分（最终报错 / 最终结果），头部通常是启动噪音；
 * - **load 容错**：文件损坏 / 版本不识别 → 返回空列表而不是崩溃；数组里某一条
   解码失败（未知 type、字段损坏）→ 跳过该条保留其余（逐条解码）。
 *
 * 并发：所有文件操作经 [Mutex] 串行化 —— debounce 收集器与宿主 flush / clear
 * 可能从不同协程同时到达。
 */
class ChatHistoryStore(private val dir: File) {

    private val mutex = Mutex()

    private val json = Json {
        // 前向兼容：新版加字段，旧版照读不炸
        ignoreUnknownKeys = true
        // 紧凑：默认值不落盘（streaming=false、resolved=false、null 字段省略）
        encodeDefaults = false
    }

    /** 历史文件（测试与宿主诊断用）。 */
    val file: File get() = File(dir, FILE_NAME)

    /**
     * 全量保存。超出 [MAX_ITEMS] 只保留最近 200 条；ActionItem.outputTail 超
     * [MAX_OUTPUT_CHARS] 截尾。返回是否成功写入（失败不抛 —— 存储不应打断聊天，
     * yl-ai 的取舍：只记日志）。
     */
    suspend fun save(items: List<ChatItem>): Boolean = mutex.withLock {
        runCatching {
            dir.mkdirs()
            val capped = items.takeLast(MAX_ITEMS).map { item ->
                val tail = (item as? ChatItem.ActionItem)?.outputTail
                if (tail != null && tail.length > MAX_OUTPUT_CHARS) {
                    item.copy(outputTail = tail.takeLast(MAX_OUTPUT_CHARS))
                } else {
                    item
                }
            }
            val root = HistoryFile(savedAt = System.currentTimeMillis(), items = capped)
            val text = json.encodeToString(HistoryFile.serializer(), root)

            val target = file
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.writeText(text)
            if (target.exists() && !target.delete()) {
                // 删旧失败（Windows 占用等）：回退直接覆写
                target.writeText(text)
                tmp.delete()
            } else if (!tmp.renameTo(target)) {
                target.writeText(text)
                tmp.delete()
            }
            true
        }.getOrDefault(false)
    }

    /**
     * 装载历史。容错语义：
     * - 文件不存在 / 根对象不合法 / items 缺失 → 空列表；
     * - 单条解码失败（未知 type、损坏字段）→ 跳过该条，其余照常返回；
     * - 重放时 AssistantMessage.streaming 一律归位 false（重启后不存在
     *   "仍在流式"的气泡，yl-ai ChatStore.fromJson 的既定语义）。
     */
    suspend fun load(): List<ChatItem> = mutex.withLock {
        val f = file
        if (!f.isFile) return@withLock emptyList()
        runCatching {
            val root = json.parseToJsonElement(f.readText()).jsonObject
            val arr = root["items"] as? JsonArray ?: return@withLock emptyList()
            arr.mapNotNull { element ->
                runCatching { json.decodeFromJsonElement(ChatItem.serializer(), element) }.getOrNull()
            }.map { item ->
                if (item is ChatItem.AssistantMessage && item.streaming) {
                    item.copy(streaming = false)
                } else {
                    item
                }
            }
        }.getOrDefault(emptyList())
    }

    /** 清空历史（删除文件；文件不存在亦视为成功）。 */
    suspend fun clear() {
        mutex.withLock { runCatching { file.delete() } }
    }

    /** 盘上文件结构（带版本号，未来格式迁移的锚点）。 */
    @Serializable
    private data class HistoryFile(
        val version: Int = 1,
        val savedAt: Long,
        @SerialName("items") val items: List<ChatItem>,
    )

    companion object {
        const val FILE_NAME = "chat-history.json"
        const val MAX_ITEMS = 200
        const val MAX_OUTPUT_CHARS = 4000

        /** 供宿主做批量导入等高级场景（一般走 [save]）。 */
        val itemListSerializer = ListSerializer(ChatItem.serializer())
    }
}
