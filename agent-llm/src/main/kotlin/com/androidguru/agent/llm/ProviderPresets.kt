package com.androidguru.agent.llm

/**
 * LLM 服务商端点预设（yl-ai `OpenAiCompatibleProvider.PRESETS` 的移植，I 组缺口）。
 *
 * 为什么要内置预设（yl-ai 的洞察）：让用户手填 baseUrl + model 的出错率极高 ——
 * 端点少个 /v1、模型名拼错一行都直接报错。预设给出「可直接选」的默认组合，
 * 结合 [normalizeEndpoint]（baseUrl 自动补全）后，即使预设/base 填写不完整也能工作。
 *
 * @param label 设置页展示名
 * @param baseUrl OpenAI 兼容 base URL（空串 = 自定义，用户手填）
 * @param defaultModel 开箱可用的默认模型
 * @param notes 设置页显示的注意事项（本地端点免 Key、版本段说明等）
 */
data class ProviderPreset(
    val label: String,
    val baseUrl: String,
    val defaultModel: String,
    val notes: String = "",
)

object ProviderPresets {

    /** 8 家预设，顺序即设置页展示顺序（与 yl-ai PRESETS 一致）。 */
    val ALL: List<ProviderPreset> = listOf(
        ProviderPreset(
            label = "OpenAI",
            baseUrl = "https://api.openai.com/v1",
            defaultModel = "gpt-4o-mini",
            notes = "官方端点；Key 在 platform.openai.com 申请。",
        ),
        ProviderPreset(
            label = "DeepSeek",
            baseUrl = "https://api.deepseek.com",
            defaultModel = "deepseek-chat",
            notes = "官方 base 不带 /v1，客户端会自动补全；填 /v1 也可以。",
        ),
        ProviderPreset(
            label = "阿里百炼",
            baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
            defaultModel = "qwen-plus",
            notes = "OpenAI 兼容模式（compatible-mode），不要用 dashscope 原生 SDK 路径。",
        ),
        ProviderPreset(
            label = "智谱 GLM",
            baseUrl = "https://open.bigmodel.cn/api/paas/v4",
            defaultModel = "glm-4-flash",
            notes = "版本段是 /v4（不是 /v1），归一化会保留 /vN 不再追加。",
        ),
        ProviderPreset(
            label = "Moonshot Kimi",
            baseUrl = "https://api.moonshot.cn/v1",
            defaultModel = "moonshot-v1-8k",
            notes = "按上下文长度选 moonshot-v1-8k / 32k / 128k。",
        ),
        ProviderPreset(
            label = "硅基流动",
            baseUrl = "https://api.siliconflow.cn/v1",
            defaultModel = "Qwen/Qwen2.5-7B-Instruct",
            notes = "模型名带命名空间（如 Qwen/ 前缀），照官方模型列表填。",
        ),
        ProviderPreset(
            label = "Ollama 本地",
            baseUrl = "http://127.0.0.1:11434/v1",
            defaultModel = "llama3.1",
            notes = "本地端点免 API Key；先 `ollama serve` 再 `ollama pull` 模型。",
        ),
        ProviderPreset(
            label = "自定义",
            baseUrl = "",
            defaultModel = "",
            notes = "手填 OpenAI 兼容端点；漏 /v1 会自动补全（已含 /vN 或 /chat/completions 除外）。",
        ),
    )

    /** 按展示名查找（找不到返回 null，宿主可回退自定义）。 */
    fun byLabel(label: String): ProviderPreset? = ALL.firstOrNull { it.label == label }
}
