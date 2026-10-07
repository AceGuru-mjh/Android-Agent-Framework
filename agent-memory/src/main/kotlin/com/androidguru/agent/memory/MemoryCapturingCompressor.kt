package com.androidguru.agent.memory

import com.androidguru.agent.core.context.ContextCompressor
import com.androidguru.agent.llm.LlmClient
import com.androidguru.agent.llm.LlmMessage
import kotlinx.coroutines.CancellationException

/**
 * 压缩捕获器 —— 上下文压缩时把「被裁掉的对话」留存为情景记忆。
 *
 * 背景：长程任务跑几小时后触发压缩，早期对话从工作记忆中消失。
 * 若不在消失前抢救，关键事实（用户在开头说过什么）从此失忆。
 * 本装饰器包在宿主的实际压缩器外：压缩真正发生（返回非 null 且有裁剪）时，
 * 被裁剪的对话渲染成一条 [MemoryKind.SESSION_SUMMARY] 记忆入库 ——
 * 之后由 [MemoryRecall] 按相关性重新召回，**压缩丢的历史变成可召回的记忆**。
 *
 * 韧性纪律：
 * - 捕获失败绝不影响压缩本身（压缩必须成功，否则上下文爆窗）；
 * - [CancellationException] 向上传播；
 * - 与 LlmSummarizingCompressor 组合时天然只捕获「摘要后被丢弃」的增量
 *   （结构上不在压缩结果里的消息），不与压缩器内部的摘要重复。
 *
 * ```kotlin
 * val engine = DefaultAgentEngine(
 *     ...,
 *     compressor = MemoryCapturingCompressor(
 *         delegate = LlmSummarizingCompressor(client),
 *         store = memorySystem.store,
 *         client = client,  // 可选：LLM 摘要捕获；缺省确定性截取
 *     ),
 * )
 * ```
 */
class MemoryCapturingCompressor(
    private val delegate: ContextCompressor,
    private val store: MemoryStore,
    /** 捕获摘要用的 LLM（null = 确定性截取，无额外成本）。 */
    private val client: LlmClient? = null,
    private val sessionId: String? = null,
    /** 单条捕获记忆的内容上限。 */
    private val maxCaptureChars: Int = 800,
    private val clock: () -> Long = System::currentTimeMillis,
) : ContextCompressor {

    override suspend fun compress(
        messages: List<LlmMessage>,
        maxTokens: Int,
        preserveRecentTurns: Int,
    ): List<LlmMessage>? {
        val compressed = delegate.compress(messages, maxTokens, preserveRecentTurns) ?: return null

        // 只在真正发生裁剪时捕获（结构上不在压缩结果里的消息 = 被丢弃的部分）
        if (compressed.size < messages.size) {
            val dropped = messages.filterNot { it in compressed }
            val signal = dropped
                .mapNotNull { m ->
                    when (m) {
                        is LlmMessage.User -> m.content.takeIf { it.isNotBlank() }?.let { "User: $it" }
                        is LlmMessage.Assistant -> m.content?.let { "Assistant: $it" }
                        is LlmMessage.Tool -> m.content.take(120).takeIf { it.isNotBlank() }?.let { "Tool: $it" }
                        is LlmMessage.System -> null // 动态上下文每轮重建，无需留存
                    }
                }
                .joinToString("\n")

            if (signal.isNotBlank()) {
                capture(signal, dropped.size)
            }
        }
        return compressed
    }

    /** 捕获入库：LLM 摘要优先，失败 / 无 client 时确定性截取。任何异常都不外溢。 */
    private suspend fun capture(rendered: String, droppedCount: Int) {
        try {
            val content = summarize(rendered, droppedCount) ?: return
            store.append(
                MemoryRecord(
                    id = "",
                    kind = MemoryKind.SESSION_SUMMARY,
                    content = content,
                    importance = 45,
                    sessionId = sessionId,
                    tags = listOf("compaction"),
                    createdAtMs = clock(),
                    lastAccessedAtMs = clock(),
                ),
            )
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            // 捕获失败不影响压缩主流程
        }
    }

    private suspend fun summarize(rendered: String, droppedCount: Int): String? {
        if (client != null) {
            try {
                val response = client.chat(
                    messages = listOf(
                        LlmMessage.System(
                            "把以下被压缩裁剪的对话片段总结为不超过 300 字的要点（保留用户提出的事实、" +
                                "偏好与未完成事项），直接输出总结正文。",
                        ),
                        LlmMessage.User(rendered.take(12_000)),
                    ),
                    maxTokens = 500,
                )
                val text = response.content?.trim()
                if (!text.isNullOrBlank()) return text.take(maxCaptureChars)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                // 回退确定性截取
            }
        }
        // 确定性截取：头部（早期事实最重要）+ 提示
        val head = rendered.take(maxCaptureChars / 2)
        return "（会话早期 ${droppedCount} 条消息被压缩，要点留存：$head…）".take(maxCaptureChars)
    }
}
