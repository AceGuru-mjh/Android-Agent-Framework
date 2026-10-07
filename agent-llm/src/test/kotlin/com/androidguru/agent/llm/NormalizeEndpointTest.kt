package com.androidguru.agent.llm

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [normalizeEndpoint] 规格测试 —— baseUrl 自动补全（yl-ai 对齐，I 组缺口）。
 *
 * 背景：旧实现 `baseUrl.trimEnd('/') + "/chat/completions"` 要求用户把
 * baseUrl 精确填到 /v1；填 `https://api.deepseek.com`（官方推荐写法）会 404。
 */
class NormalizeEndpointTest {

    @Test
    fun `裸域名自动补 v1`() {
        assertEquals("https://api.deepseek.com/v1", normalizeEndpoint("https://api.deepseek.com"))
    }

    @Test
    fun `尾斜杠与首尾空白容忍`() {
        assertEquals("https://api.deepseek.com/v1", normalizeEndpoint("  https://api.deepseek.com/  "))
        assertEquals("https://x.example.com/v1", normalizeEndpoint("https://x.example.com///"))
    }

    @Test
    fun `已含 v1 的场景原样保留`() {
        assertEquals("https://api.openai.com/v1", normalizeEndpoint("https://api.openai.com/v1"))
        assertEquals("https://api.moonshot.cn/v1", normalizeEndpoint("https://api.moonshot.cn/v1/"))
        // 兼容模式路径里以 /v1 结尾
        assertEquals(
            "https://dashscope.aliyuncs.com/compatible-mode/v1",
            normalizeEndpoint("https://dashscope.aliyuncs.com/compatible-mode/v1"),
        )
    }

    @Test
    fun `其它版本段 vN 原样保留不误补 v1`() {
        // 智谱 GLM 的版本段是 /v4 —— 若只认 /v1 会被错误补成 /v4/v1 导致 404
        assertEquals(
            "https://open.bigmodel.cn/api/paas/v4",
            normalizeEndpoint("https://open.bigmodel.cn/api/paas/v4"),
        )
        assertEquals("https://host.example.com/api/v3", normalizeEndpoint("https://host.example.com/api/v3"))
        assertEquals("https://host.example.com/v10", normalizeEndpoint("https://host.example.com/v10"))
    }

    @Test
    fun `旧配置直填全路径 chat_completions 剥掉后再拼回等价`() {
        // 旧版框架要求 baseUrl 精确到补全路径；归一化应剥掉后缀，post 时拼回，最终 URL 不变
        assertEquals("https://api.openai.com/v1", normalizeEndpoint("https://api.openai.com/v1/chat/completions"))
        val base = normalizeEndpoint("https://api.openai.com/v1/chat/completions")
        assertEquals("https://api.openai.com/v1/chat/completions", base + "/chat/completions")
    }

    @Test
    fun `Ollama 本地裸地址补 v1`() {
        assertEquals("http://127.0.0.1:11434/v1", normalizeEndpoint("http://127.0.0.1:11434"))
    }

    @Test
    fun `全部预设的 baseUrl 经归一化不产生双重版本段`() {
        ProviderPresets.ALL
            .filter { it.baseUrl.isNotBlank() }
            .forEach { preset ->
                val normalized = normalizeEndpoint(preset.baseUrl)
                assertEquals(
                    "${preset.label} 的 baseUrl 归一化后不应出现 /vN/vN",
                    1,
                    Regex("/v\\d+").findAll(normalized).count(),
                )
                assertEquals(
                    "${preset.label} 归一化后应以版本段结尾",
                    true,
                    Regex("/v\\d+$").containsMatchIn(normalized),
                )
            }
    }
}
