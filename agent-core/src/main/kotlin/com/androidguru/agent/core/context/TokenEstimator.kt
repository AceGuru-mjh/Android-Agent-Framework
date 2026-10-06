package com.androidguru.agent.core.context

import com.androidguru.agent.llm.LlmMessage

/**
 * Token 估算器（零依赖、毫秒级）。
 *
 * 启发式：CJK 字符约 1 token/字，其他按 4 字符 ≈ 1 token（GPT 系经验值）。
 * 仅用于压缩触发判断，不用于计费。
 */
object TokenEstimator {

    fun estimate(text: String): Int {
        var cjk = 0
        var other = 0
        for (ch in text) {
            if (isCjk(ch)) cjk++ else other++
        }
        return cjk + (other + 3) / 4
    }

    /** 估算消息列表总 token（含每条消息的结构开销）。 */
    fun estimate(messages: List<LlmMessage>): Int {
        var total = 0
        for (m in messages) {
            total += when (m) {
                is LlmMessage.System -> estimate(m.content)
                is LlmMessage.User -> estimate(m.content) + m.images.size * 800 // 图片按固定开销估算
                is LlmMessage.Assistant -> (m.content?.let(::estimate) ?: 0) +
                    m.toolCalls.sumOf { estimate(it.arguments) + 8 } +
                    (m.reasoning?.let(::estimate) ?: 0)
                is LlmMessage.Tool -> estimate(m.content)
            } + 4 // 消息结构开销
        }
        return total
    }

    private val CJK_RANGES = listOf(
        0x4E00..0x9FFF,   // CJK 基本区
        0x3400..0x4DBF,   // 扩展 A
        0x3000..0x303F,   // CJK 标点
        0xFF00..0xFFEF,   // 全角
        0x3040..0x30FF,   // 假名
    )

    /** 字符是否落在 CJK 范围。 */
    fun isCjk(ch: Char): Boolean = CJK_RANGES.any { ch.code in it }
}
