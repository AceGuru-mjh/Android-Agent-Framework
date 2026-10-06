package com.androidguru.agent.llm

/**
 * LLM 端点配置。
 *
 * 兼容一切 OpenAI 协议端点（OpenAI / DeepSeek / Qwen / Moonshot / vLLM / Ollama / LM Studio…）。
 * 端点差异通过 [ProviderHints] 声明，而不是 if-else 硬编码。
 */
data class LlmConfig(
    /** OpenAI 兼容 base URL（不带尾斜杠，如 `https://api.deepseek.com/v1`）。 */
    val baseUrl: String = "https://api.openai.com/v1",
    /** API Key；本地端点（Ollama 等）可为空。 */
    val apiKey: String = "",
    val model: String,

    // ---- 采样参数（null = 端点默认）----
    val temperature: Double? = null,
    val maxTokens: Int? = null,

    // ---- 网络与韧性 ----
    val connectTimeoutMs: Long = 15_000L,
    val requestTimeoutMs: Long = 120_000L,
    val maxRetries: Int = 2,
    val retryBaseDelayMs: Long = 1_000L,

    // ---- 端点差异 ----
    val hints: ProviderHints = ProviderHints(),

    /** 额外请求头（鉴权代理 / 审计追踪等）。 */
    val extraHeaders: Map<String, String> = emptyMap(),
) {
    /** 判定是否本地端点（豁免 apiKey 校验；localhost / RFC1918 / mDNS .local）。 */
    val isLocalEndpoint: Boolean by lazy {
        val url = baseUrl.lowercase()
        val localhostLike = url.contains("localhost") || url.contains("127.0.0.1") ||
            url.contains("[::1]") || url.endsWith(".local")
        val privateRange = Regex("""https?://(172\.(1[6-9]|2\d|3[01])|192\.168|10)\.\d+\.\d+""")
            .containsMatchIn(url)
        localhostLike || privateRange
    }

    init {
        require(model.isNotBlank()) { "model 不能为空" }
        require(baseUrl.isNotBlank()) { "baseUrl 不能为空" }
        if (!isLocalEndpoint && apiKey.isBlank()) {
            throw IllegalArgumentException("远程端点必须配置 apiKey（本地端点可豁免）: $baseUrl")
        }
    }
}

/**
 * 端点差异提示（Provider 差异化请求体矩阵的声明式替代）。
 */
data class ProviderHints(
    /** 严格推理模型（o 系列 / gpt-5 等）：不发送 temperature，max_tokens → max_completion_tokens。 */
    val strictReasoningModel: Boolean = false,

    /** DashScope/Qwen: enable_thinking 开关。null = 不发送。 */
    val enableThinking: Boolean? = null,

    /** 声明 reasoning 能力时发送 reasoning_effort（如 "medium"）。 */
    val reasoningEffort: String? = null,

    /** Anthropic 兼容端点的 thinking budget。 */
    val anthropicThinkingBudgetTokens: Int? = null,
)
