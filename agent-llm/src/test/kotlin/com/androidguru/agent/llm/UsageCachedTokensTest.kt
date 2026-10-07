package com.androidguru.agent.llm

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [Usage.cachedTokens] 解析测试 —— yl-ai AgentLoop 对齐（I 组缺口）：
 * OpenAI 兼容端点在 `usage.prompt_tokens_details.cached_tokens` 上报缓存命中。
 */
class UsageCachedTokensTest {

    private val client = OpenAiCompatibleClient(
        LlmConfig(baseUrl = "https://api.openai.com/v1", apiKey = "sk-test", model = "gpt-4o"),
    )

    @Test
    fun `解析 prompt_tokens_details 里的 cached_tokens`() {
        val usage = client.parseUsage(
            buildJsonObject {
                put("prompt_tokens", 100)
                put("completion_tokens", 20)
                put("total_tokens", 120)
                putJsonObject("prompt_tokens_details") {
                    put("cached_tokens", 64)
                    put("text_tokens", 0)
                }
            },
        )!!
        assertEquals(100L, usage.promptTokens)
        assertEquals(20L, usage.completionTokens)
        assertEquals(120L, usage.totalTokens)
        assertEquals(64L, usage.cachedTokens)
    }

    @Test
    fun `端点未上报 details 时 cachedTokens 为 null 而非 0`() {
        val usage = client.parseUsage(
            buildJsonObject {
                put("prompt_tokens", 100)
                put("completion_tokens", 20)
            },
        )!!
        assertNull("null = 未上报（区别于 0 命中）", usage.cachedTokens)
        assertEquals(120L, usage.totalTokens) // 缺 total 时回退 prompt+completion
    }

    @Test
    fun `cached_tokens 为 0 时如实报 0`() {
        val usage = client.parseUsage(
            buildJsonObject {
                put("prompt_tokens", 50)
                put("completion_tokens", 5)
                putJsonObject("prompt_tokens_details") {
                    put("cached_tokens", 0)
                }
            },
        )!!
        assertEquals(0L, usage.cachedTokens)
    }

    @Test
    fun `details 里缺 cached_tokens 键时为 null`() {
        val usage = client.parseUsage(
            buildJsonObject {
                put("prompt_tokens", 50)
                put("completion_tokens", 5)
                putJsonObject("prompt_tokens_details") {
                    put("text_tokens", 3)
                }
            },
        )!!
        assertNull(usage.cachedTokens)
    }

    @Test
    fun `旧构造点只传三参仍兼容`() {
        val usage = Usage(promptTokens = 1, completionTokens = 2, totalTokens = 3)
        assertNull(usage.cachedTokens)

        // 位置参数的老调用形态
        val positional = Usage(1, 2, 3)
        assertNull(positional.cachedTokens)
    }

    @Test
    fun `usage 非 JSON 对象时返回 null`() {
        assertNull(client.parseUsage(kotlinx.serialization.json.JsonPrimitive("oops")))
    }
}
