package com.androidguru.agent.core

import com.androidguru.agent.core.context.SlidingWindowCompressor
import com.androidguru.agent.core.context.TokenEstimator
import com.androidguru.agent.llm.LlmMessage
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextCompressorTest {

    private fun buildTurns(count: Int): List<LlmMessage> {
        val messages = mutableListOf<LlmMessage>(LlmMessage.System("You are a helper."))
        for (i in 1..count) {
            messages += LlmMessage.User("用户第 $i 轮的问题")
            messages += LlmMessage.Assistant("第 $i 轮的回答")
        }
        return messages
    }

    @Test
    fun `未超限返回 null`() = runTest {
        val compressor = SlidingWindowCompressor()
        assertNull(compressor.compress(buildTurns(3), maxTokens = 10_000, preserveRecentTurns = 2))
    }

    @Test
    fun `超限裁剪最老轮次并保留 system 与最近轮`() = runTest {
        val compressor = SlidingWindowCompressor()
        val messages = buildTurns(6)
        val compressed = compressor.compress(
            messages,
            maxTokens = 10, // 强制触发
            preserveRecentTurns = 2,
        )!!
        // system 头保留
        val first = compressed.first()
        assertTrue(first is LlmMessage.System && first.content.contains("helper"))
        // 压缩说明存在
        assertTrue(compressed.any { it is LlmMessage.System && it.content.contains("压缩") })
        // 最近 2 轮保留
        assertTrue(compressed.contains(messages[messages.size - 4])) // 第 5 轮 User
        assertTrue(compressed.contains(messages.last()))             // 第 6 轮 Assistant
        // 第 1 轮被裁剪
        assertTrue(!compressed.contains(messages[1]))
    }

    @Test
    fun `只剩一轮超限时放弃裁剪`() = runTest {
        val compressor = SlidingWindowCompressor()
        val messages = listOf(
            LlmMessage.System("sys"),
            LlmMessage.User("唯一一轮"),
        )
        assertNull(compressor.compress(messages, maxTokens = 1, preserveRecentTurns = 5))
    }

    @Test
    fun `TokenEstimator CJK 与英文区分`() {
        val chinese = TokenEstimator.estimate("一二三四五六七八九十") // 10 CJK ≈ 10 tokens
        val english = TokenEstimator.estimate("abcdefghij")          // 10 ascii ≈ 3 tokens
        assertEquals(10, chinese)
        assertEquals(3, english)
    }
}
