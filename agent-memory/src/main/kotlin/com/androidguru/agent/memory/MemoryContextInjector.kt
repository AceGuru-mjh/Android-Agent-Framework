package com.androidguru.agent.memory

import com.androidguru.agent.core.engine.SystemContextProvider
import com.androidguru.agent.core.session.ConversationMemory
import com.androidguru.agent.llm.LlmMessage

/**
 * 长期记忆注入器 —— 把召回的记忆注入引擎的每轮动态上下文。
 *
 * 接线方式与 agent-tasks 的 PlanContextInjector 完全一致（同一道
 * [SystemContextProvider] 缝）：引擎每轮 LLM 调用前求值一次，
 * 上下文压缩可能裁掉早期对话，但**记忆永远在**。
 *
 * 召回信号：[conversation] 提供时取最近一条用户消息（当前意图），
 * 记忆按「与之相关性」排序；否则退化为重要度 / 新近度排名。
 *
 * 设计边界：
 * - 无相关记忆时返回空串（引擎不注入空段落）；
 * - 本注入器**只读记忆**；写入走 memory_save 工具（模型主动）与
 *   [MemorySystem.onSessionComplete] / [MemoryCapturingCompressor]（系统自动）；
 * - 引擎侧已有异常隔离（单点故障不打断任务），此处不再重复兜底。
 */
class MemoryContextInjector(
    private val recall: MemoryRecall,
    /** 召回信号源（可选）：最近一条用户消息。 */
    private val conversation: ConversationMemory? = null,
    private val queryLimit: Int = 6,
    private val queryMaxTokens: Int = 500,
) : SystemContextProvider {

    override suspend fun provideContext(sessionId: String): String {
        val query = MemoryQuery(
            text = lastUserSignal(),
            limit = queryLimit,
            maxTokens = queryMaxTokens,
        )
        val records = recall.recall(query)
        if (records.isEmpty()) return ""
        return buildString {
            append("# 长期记忆（按相关性自动召回，供参考）\n")
            append(renderMemories(records))
            append("\n（可用 memory_search 查询更多；memory_save 保存新记忆；内容过时请用 memory_forget 纠正）")
        }
    }

    /** 最近一条用户消息文本（图片 / 空消息不算信号）。 */
    private fun lastUserSignal(): String? = conversation
        ?.snapshot()
        ?.lastOrNull { it is LlmMessage.User && it.content.isNotBlank() }
        ?.let { (it as LlmMessage.User).content }
}
