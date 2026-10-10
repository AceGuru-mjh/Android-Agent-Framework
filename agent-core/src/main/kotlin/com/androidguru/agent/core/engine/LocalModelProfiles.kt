package com.androidguru.agent.core.engine

import com.androidguru.agent.llm.LlmClient
import com.androidguru.agent.llm.LlmConfig
import com.androidguru.agent.llm.OpenAiCompatibleClient
import com.androidguru.agent.llm.ToolCallEmulatingClient

/**
 * 本地模型档位库 —— 常见本地推理后端（Ollama / llama.cpp / LM Studio）×
 * 常见开源模型的**开箱即用配置**，补齐「本地模型深度适配」的最后一公里。
 *
 * 每个档位组合了三件事：
 * 1. [llmConfig]：端点 + 模型 + 超时（本地端点豁免 apiKey）；
 * 2. [agentConfig]：上下文 / 压缩 / 工具输出 / 反思与跑偏护栏的**小模型调参**
 *    （小上下文 → 更早压缩、更少保留轮数；弱推理 → 更低反思 token 上限、
 *    关闭 LLM 对齐抽查省调用）；
 * 3. [toolCallEmulation]：模型是否需要文本协议模拟工具调用（无原生
 *    function-calling 的模型必须开，否则 Agent 退化为聊天机器人）。
 *
 * 用法：
 * ```kotlin
 * val profile = LocalModelProfiles.OLLAMA_QWEN25_7B
 * val engine = DefaultAgentEngine(
 *     llmClient = profile.llmClient(),
 *     toolRegistry = registry,
 *     toolExecutor = executor,
 *     config = profile.agentConfig,
 * )
 * ```
 */
data class LocalModelProfile(
    val displayName: String,
    /** 端点与模型配置（本地端点免 apiKey）。 */
    val llmConfig: LlmConfig,
    /** 引擎配置（已按模型档位调参）。 */
    val agentConfig: AgentConfig,
    /** 是否需要 [ToolCallEmulatingClient] 文本协议模拟。 */
    val toolCallEmulation: Boolean,
    val notes: String,
) {
    /** 组装最终客户端：需要模拟时自动包一层。 */
    fun llmClient(): LlmClient {
        val base: LlmClient = OpenAiCompatibleClient(llmConfig)
        return if (toolCallEmulation) ToolCallEmulatingClient(base) else base
    }
}

object LocalModelProfiles {

    // ------------------------------------------------------------------
    // Ollama（http://127.0.0.1:11434/v1，OpenAI 兼容端点）
    // ------------------------------------------------------------------

    /** Qwen2.5 7B Instruct —— 手机/低端设备可跑；无原生 function calling，必须模拟。 */
    val OLLAMA_QWEN25_7B = LocalModelProfile(
        displayName = "Ollama · Qwen2.5 7B Instruct",
        llmConfig = LlmConfig(
            baseUrl = "http://127.0.0.1:11434/v1",
            model = "qwen2.5:7b-instruct",
            temperature = 0.3,
            requestTimeoutMs = 300_000L, // 手机 CPU 推理慢，放宽请求超时
        ),
        agentConfig = AgentConfig(
            maxContextTokens = 8_192,
            compressionThreshold = 0.6, // 小上下文更早压缩
            preserveRecentTurns = 3,
            maxToolOutputChars = 4_000,
            maxIterations = 15,
            reflectionMaxTokens = 200,
            reflectionFailureThreshold = 4,
            driftCheckInterval = 0, // 关闭 LLM 对齐抽查（省 token），保留零成本护栏
        ),
        toolCallEmulation = true,
        notes = "7B 档：上下文与输出全面收缩；工具走文本协议模拟。",
    )

    /** Qwen2.5 14B Instruct —— 中端档；指令跟随更好，仍建议模拟（Ollama 模板不稳）。 */
    val OLLAMA_QWEN25_14B = LocalModelProfile(
        displayName = "Ollama · Qwen2.5 14B Instruct",
        llmConfig = LlmConfig(
            baseUrl = "http://127.0.0.1:11434/v1",
            model = "qwen2.5:14b-instruct",
            temperature = 0.3,
            requestTimeoutMs = 240_000L,
        ),
        agentConfig = AgentConfig(
            maxContextTokens = 16_384,
            compressionThreshold = 0.65,
            preserveRecentTurns = 4,
            maxToolOutputChars = 6_000,
            maxIterations = 20,
            reflectionMaxTokens = 250,
        ),
        toolCallEmulation = true,
        notes = "14B 档：对齐抽查保持默认开启。",
    )

    /** Qwen2.5 32B —— 高端本地档；多数 Ollama 版本支持原生工具调用，走原生路径。 */
    val OLLAMA_QWEN25_32B = LocalModelProfile(
        displayName = "Ollama · Qwen2.5 32B Instruct",
        llmConfig = LlmConfig(
            baseUrl = "http://127.0.0.1:11434/v1",
            model = "qwen2.5:32b-instruct",
            temperature = 0.3,
        ),
        agentConfig = AgentConfig(
            maxContextTokens = 32_768,
            compressionThreshold = 0.7,
            preserveRecentTurns = 5,
            maxToolOutputChars = 8_000,
        ),
        toolCallEmulation = false,
        notes = "32B 档：走原生 function-calling；若端点不支持可手动换 [OLLAMA_QWEN25_14B] 同款模拟。",
    )

    /** Llama 3.1 8B —— 无稳定 function calling，必须模拟。 */
    val OLLAMA_LLAMA31_8B = LocalModelProfile(
        displayName = "Ollama · Llama 3.1 8B",
        llmConfig = LlmConfig(
            baseUrl = "http://127.0.0.1:11434/v1",
            model = "llama3.1:8b",
            temperature = 0.3,
            requestTimeoutMs = 300_000L,
        ),
        agentConfig = AgentConfig(
            maxContextTokens = 8_192,
            compressionThreshold = 0.6,
            preserveRecentTurns = 3,
            maxToolOutputChars = 4_000,
            maxIterations = 15,
            reflectionMaxTokens = 200,
            reflectionFailureThreshold = 4,
            driftCheckInterval = 0,
        ),
        toolCallEmulation = true,
        notes = "Llama 系对 fenced 协议遵守度弱于 Qwen，建议优先选 Qwen 档位。",
    )

    // ------------------------------------------------------------------
    // llama.cpp / Termux 本地推理（llama-server，http://127.0.0.1:8080/v1）
    // ------------------------------------------------------------------

    /** Termux on-device —— llama.cpp llama-server；极限小上下文，全参数收缩。 */
    val TERMUX_LLAMA_CPP = LocalModelProfile(
        displayName = "Termux · llama.cpp (llama-server)",
        llmConfig = LlmConfig(
            baseUrl = "http://127.0.0.1:8080/v1",
            model = "local-model", // llama-server 任意模型名均可
            temperature = 0.3,
            requestTimeoutMs = 600_000L, // 手机 GPU/CPU 极慢
        ),
        agentConfig = AgentConfig(
            maxContextTokens = 4_096,
            compressionThreshold = 0.55, // 极小上下文：过半即压缩
            preserveRecentTurns = 2,
            maxToolOutputChars = 2_000,
            maxIterations = 12,
            reflectionMaxTokens = 150,
            reflectionFailureThreshold = 4,
            driftCheckInterval = 0,
            loopDetectionWindow = 8,
        ),
        toolCallEmulation = true,
        notes = "端侧极限档：工具结果截断到 2k 字符，护栏窗口同步收缩。",
    )

    // ------------------------------------------------------------------
    // LM Studio（http://127.0.0.1:1234/v1）
    // ------------------------------------------------------------------

    /** LM Studio 桌面档 —— 端点对 tools 支持取决于模型模板，默认开模拟保稳。 */
    val LM_STUDIO = LocalModelProfile(
        displayName = "LM Studio (本地)",
        llmConfig = LlmConfig(
            baseUrl = "http://127.0.0.1:1234/v1",
            model = "local-model",
            temperature = 0.3,
            requestTimeoutMs = 300_000L,
        ),
        agentConfig = AgentConfig(
            maxContextTokens = 16_384,
            compressionThreshold = 0.65,
            preserveRecentTurns = 4,
            maxToolOutputChars = 6_000,
        ),
        toolCallEmulation = true,
        notes = "LM Studio 的 function calling 支持因模型模板而异，默认走模拟协议。",
    )

    /** 全部档位（遍历展示 / 自动探测用）。 */
    val all: List<LocalModelProfile> = listOf(
        OLLAMA_QWEN25_7B,
        OLLAMA_QWEN25_14B,
        OLLAMA_QWEN25_32B,
        OLLAMA_LLAMA31_8B,
        TERMUX_LLAMA_CPP,
        LM_STUDIO,
    )

    /**
     * 自定义本地档位：按端点 + 模型名生成一档调好参的配置。
     *
     * @param toolCallEmulation 模型无原生 function calling 时必须 true。
     * @param contextTokens 模型实际上下文窗口（决定压缩阈值与保留轮数）。
     */
    fun custom(
        baseUrl: String,
        model: String,
        contextTokens: Int = 8_192,
        toolCallEmulation: Boolean = true,
        displayName: String = "Local · $model",
    ): LocalModelProfile {
        val (threshold, preserve) = when {
            contextTokens <= 4_096 -> 0.55 to 2
            contextTokens <= 8_192 -> 0.6 to 3
            contextTokens <= 16_384 -> 0.65 to 4
            else -> 0.7 to 5
        }
        return LocalModelProfile(
            displayName = displayName,
            llmConfig = LlmConfig(
                baseUrl = baseUrl,
                model = model,
                temperature = 0.3,
                requestTimeoutMs = 300_000L,
            ),
            agentConfig = AgentConfig(
                maxContextTokens = contextTokens,
                compressionThreshold = threshold,
                preserveRecentTurns = preserve,
                maxToolOutputChars = (contextTokens / 2).coerceAtMost(8_000),
                reflectionMaxTokens = 200,
                reflectionFailureThreshold = 4,
                driftCheckInterval = if (contextTokens >= 16_384) 8 else 0,
            ),
            toolCallEmulation = toolCallEmulation,
            notes = "custom 档位（context=$contextTokens）。",
        )
    }
}
