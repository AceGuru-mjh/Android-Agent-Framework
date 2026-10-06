package com.androidguru.agent.core.context

import com.androidguru.agent.llm.LlmMessage
import kotlinx.coroutines.CancellationException

/**
 * 上下文压缩器。
 *
 * 返回 null 表示无需压缩；返回新列表时引擎将用 [ConversationMemory.replaceAll] 回写。
 * 摘要式压缩（调用 LLM 做总结）由宿主注入 [LlmSummarizingCompressor]，
 * 框架默认提供零成本的滑动窗口实现。
 */
interface ContextCompressor {

    suspend fun compress(
        messages: List<LlmMessage>,
        maxTokens: Int,
        preserveRecentTurns: Int,
    ): List<LlmMessage>?
}

/**
 * 滑动窗口压缩：丢弃最老的对话轮（保留 system 头部 + 最近 N 轮），
 * 在丢弃点插入一条占位说明。零 token 成本。
 *
 * [computeSplit] 同时供 [LlmSummarizingCompressor] 复用切分逻辑。
 */
open class SlidingWindowCompressor(
    private val estimator: (List<LlmMessage>) -> Int = TokenEstimator::estimate,
) : ContextCompressor {

    override suspend fun compress(
        messages: List<LlmMessage>,
        maxTokens: Int,
        preserveRecentTurns: Int,
    ): List<LlmMessage>? {
        val (dropped, kept) = computeSplit(messages, maxTokens, preserveRecentTurns) ?: return null
        val head = dropped.takeWhile { it is LlmMessage.System }
        val note = LlmMessage.System(
            "（更早的 ${dropped.size - head.size} 条对话已被压缩省略。若需要早期细节，请让用户重新提供。）",
        )
        return head + note + kept
    }

    /**
     * 计算切分点。null = 无需压缩。
     * 返回 (被裁剪部分, 保留部分)；被裁剪部分以 system 头部开头。
     */
    fun computeSplit(
        messages: List<LlmMessage>,
        maxTokens: Int,
        preserveRecentTurns: Int,
    ): Pair<List<LlmMessage>, List<LlmMessage>>? {
        if (estimator(messages) <= maxTokens) return null

        // 定位裁剪点：从尾部往前数 preserveRecentTurns 个用户消息
        var turnCount = 0
        var cutoff = messages.size
        for (i in messages.indices.reversed()) {
            if (messages[i] is LlmMessage.User) {
                turnCount++
                if (turnCount > preserveRecentTurns) {
                    cutoff = i
                    break
                }
            }
        }
        if (cutoff == messages.size) return null // 只剩一轮也超限：不裁当前轮，交由上层决定

        return messages.subList(0, cutoff).toList() to messages.subList(cutoff, messages.size).toList()
    }
}

/**
 * LLM 摘要压缩：被裁剪的历史先摘要化再拼接，优于纯丢弃。
 * 摘要失败时自动回退 [SlidingWindowCompressor]。
 */
class LlmSummarizingCompressor(
    private val client: com.androidguru.agent.llm.LlmClient,
    private val summarizationPrompt: String =
        "请把以下对话历史压缩为一段要点摘要（保留事实、决策与未完成任务），控制在 500 字以内。",
    private val maxSummaryTokens: Int = 800,
    private val fallback: SlidingWindowCompressor = SlidingWindowCompressor(),
) : ContextCompressor {

    override suspend fun compress(
        messages: List<LlmMessage>,
        maxTokens: Int,
        preserveRecentTurns: Int,
    ): List<LlmMessage>? {
        val (dropped, kept) = fallback.computeSplit(messages, maxTokens, preserveRecentTurns) ?: return null
        return try {
            val droppedText = dropped
                .dropWhile { it is LlmMessage.System }
                .joinToString("\n") { m ->
                    when (m) {
                        is LlmMessage.User -> "User: ${m.content}"
                        is LlmMessage.Assistant -> "Assistant: ${m.content ?: "(工具调用)"}"
                        is LlmMessage.Tool -> "Tool: ${m.content.take(200)}"
                        is LlmMessage.System -> ""
                    }
                }
            val summary = client.chat(
                messages = listOf(
                    LlmMessage.System(summarizationPrompt),
                    LlmMessage.User(droppedText.take(30_000)),
                ),
                maxTokens = maxSummaryTokens,
            )
            val head = dropped.takeWhile { it is LlmMessage.System }
            val note = LlmMessage.System("（更早对话摘要：${summary.content ?: "（摘要生成失败，已省略）"}）")
            head + note + kept
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // 回退到纯滑动窗口
            fallback.compress(messages, maxTokens, preserveRecentTurns)
        }
    }
}
