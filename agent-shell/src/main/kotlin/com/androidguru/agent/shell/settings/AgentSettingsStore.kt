package com.androidguru.agent.shell.settings

import com.androidguru.agent.shell.crypto.SecretVault
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Agent 设置（yl-ai `AiSettings` 的框架移植，G 组缺口）。
 *
 * 字段与 yl-ai 一一对应：providerLabel / baseUrl / model / maxStepsPerTask(24) /
 * autoApproveSafe(true) / commandTimeoutMs(30s)。数值字段在 [coerced] 强制收拢
 * 到合法区间 —— 与 yl-ai 在 setter 里 `coerceIn` 的语义一致，只是改为纯函数式
 * （不可变数据类 + 显式收拢），防止 `@Serializable` 反序列化绕过 setter。
 */
@Serializable
data class AgentSettings(
    /** 服务商展示名（OpenAI / DeepSeek / 阿里百炼…，见 agent-llm 的 ProviderPresets）。 */
    val providerLabel: String = "",
    /** OpenAI 兼容端点 base URL。 */
    val baseUrl: String = "",
    /** 模型名。 */
    val model: String = "",
    /** 单任务最大步数（1..100）。 */
    val maxStepsPerTask: Int = DEFAULT_MAX_STEPS,
    /** 只读安全命令自动放行（不弹审批）。 */
    val autoApproveSafe: Boolean = true,
    /** 单条命令超时（2_000..300_000 ms）。 */
    val commandTimeoutMs: Int = DEFAULT_COMMAND_TIMEOUT_MS,
) {

    /** 把越界数值收拢回合法区间（load / save 双侧都会调用）。 */
    fun coerced(): AgentSettings = copy(
        maxStepsPerTask = maxStepsPerTask.coerceIn(MIN_MAX_STEPS, MAX_MAX_STEPS),
        commandTimeoutMs = commandTimeoutMs.coerceIn(MIN_TIMEOUT_MS, MAX_TIMEOUT_MS),
    )

    /** 是否已具备调用模型的最低配置（yl-ai 语义：端点 + 模型，不含 apiKey）。 */
    val isConfigured: Boolean
        get() = baseUrl.isNotBlank() && model.isNotBlank()

    companion object {
        const val DEFAULT_MAX_STEPS = 24
        const val DEFAULT_COMMAND_TIMEOUT_MS = 30_000
        const val MIN_MAX_STEPS = 1
        const val MAX_MAX_STEPS = 100
        const val MIN_TIMEOUT_MS = 2_000
        const val MAX_TIMEOUT_MS = 300_000
    }
}

/**
 * Agent 设置存储 —— JSON 文件持久化 + API Key 经 [SecretVault] 静态加密。
 *
 * 设计要点（对应 yl-ai `AiSettings` 的 SharedPreferences + AndroidKeyStore）：
 * - **API Key 加密**：vault != null 时密文存 `encrypted` 字段，格式为
 *   `iv:body` 双 base64（[com.androidguru.agent.shell.crypto.AesGcmSecretVault]
 *   的原生格式，AES-256-GCM）—— 至此 SecretVault 加密原语有了第一个真实消费者；
 * - **无 vault 兜底**：vault == null（宿主未提供安全存储）时明文存 `plain` 并打
 *   `isPlainText: true` 标记 —— 宁可明文也要可用，但必须让宿主/用户知情，
 *   设置页可据此提示「建议接入加密保管库」；
 * - **加密失败兜底**：encrypt 抛异常时同样落明文 + isPlainText 标记（不静默丢 Key）；
 * - **原子写**：先写 `*.tmp-<nanoTime>` 再 rename，避免半截 JSON；
 * - 读取容错：文件不存在 / 损坏时回退默认值，解密失败回退空串（yl-ai getOrDefault("")）。
 *
 * @param file 设置 JSON 文件路径
 * @param vault 密钥保管库（null = 明文兜底）
 */
class AgentSettingsStore(
    private val file: File,
    private val vault: SecretVault? = null,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }
    private val lock = Any()

    @Volatile
    private var cachedKeyRecord: ApiKeyRecord? = null

    @Serializable
    internal data class ApiKeyRecord(
        /** vault 加密密文，`iv:body` 双 base64（AES-256-GCM）。 */
        val encrypted: String? = null,
        /** 明文兜底（仅 isPlainText=true 时有值）。 */
        val plain: String? = null,
        /** 明文标记：vault 缺失或加密失败时为 true，宿主应提示用户。 */
        val isPlainText: Boolean = false,
    )

    @Serializable
    internal data class SettingsFileDto(
        val settings: AgentSettings = AgentSettings(),
        val apiKey: ApiKeyRecord? = null,
        val savedAtMs: Long = 0L,
    )

    // ---------------- 读取 ----------------

    /**
     * 读取设置（越界值自动收拢）。
     *
     * 文件不存在 / JSON 损坏时返回默认值 —— 首次启动与升级路径都无需特殊处理。
     */
    fun load(): AgentSettings = synchronized(lock) {
        val dto = readDto()
        val s = dto.settings.coerced()
        cachedKeyRecord = dto.apiKey
        s
    }

    /**
     * 当前保存的 API Key（解密后）。
     *
     * 解密失败（密钥库被清 / 密文损坏）时返回空串而不抛异常 —— 与 yl-ai
     * `getOrDefault("")` 一致，调用方按「未配置」处理并提示重新填写。
     */
    fun apiKey(): String = synchronized(lock) {
        val rec = cachedKeyRecord ?: readDto().apiKey?.also { cachedKeyRecord = it } ?: return ""
        val v = vault // 局部捕获：成员 val 的智能转换不能跨 lambda
        when {
            rec.encrypted != null && v != null ->
                runCatching { v.decrypt(rec.encrypted) }.getOrDefault("")

            rec.isPlainText -> rec.plain.orEmpty()

            else -> "" // 有密文但无 vault：无法解密，等价于未配置
        }
    }

    /** 是否已具备调用模型的最低配置。 */
    val isConfigured: Boolean
        get() = load().isConfigured

    /** 密钥掩码（`sk-xxx…xxxx` 形态；过短/未配置不泄露任何信息）。 */
    fun maskKey(): String = maskKey(apiKey())

    /** 人类可读描述（设置页 / 自检展示）。 */
    fun describe(): String {
        val s = load()
        val key = apiKey()
        return buildString {
            appendLine("服务商: ${s.providerLabel.ifBlank { "(未设置)" }}")
            appendLine("端点: ${s.baseUrl.ifBlank { "(未设置)" }}")
            appendLine("模型: ${s.model.ifBlank { "(未设置)" }}")
            appendLine("API Key: " + if (key.isBlank()) "(未设置)" else maskKey(key))
            appendLine("密钥存储: " + keyStorageLabel())
            appendLine("单任务最大步数: ${s.maxStepsPerTask}")
            appendLine("只读命令自动放行: ${if (s.autoApproveSafe) "是" else "否"}")
            appendLine("命令超时: ${s.commandTimeoutMs}ms")
        }
    }

    // ---------------- 写入 ----------------

    /**
     * 保存设置与 API Key（越界值收拢后落盘；Key 为空则清除已存 Key）。
     *
     * @param settings 待保存设置（内部自动 [AgentSettings.coerced]）
     * @param apiKey 明文 API Key；空串 = 清除
     */
    fun save(settings: AgentSettings, apiKey: String) = synchronized(lock) {
        val s = settings.coerced()
        val v = vault
        val rec: ApiKeyRecord? = when {
            apiKey.isBlank() -> null
            v != null -> runCatching { ApiKeyRecord(encrypted = v.encrypt(apiKey)) }
                .getOrElse {
                    // 加密失败不静默丢 Key：明文兜底 + 知情标记
                    ApiKeyRecord(plain = apiKey, isPlainText = true)
                }

            else -> ApiKeyRecord(plain = apiKey, isPlainText = true)
        }
        atomicWrite(json.encodeToString(SettingsFileDto(settings = s, apiKey = rec, savedAtMs = System.currentTimeMillis())))
        cachedKeyRecord = rec
    }

    // ---------------- 内部 ----------------

    private fun readDto(): SettingsFileDto = runCatching {
        if (!file.isFile) SettingsFileDto() else json.decodeFromString<SettingsFileDto>(file.readText())
    }.getOrDefault(SettingsFileDto())

    /**
     * 原子写：先写同目录 `*.tmp-<nanoTime>` 唯一临时文件，再 rename 覆盖目标。
     *
     * nanoTime 后缀避免并发写 / 残留 tmp 冲突；rename 失败（跨文件系统等）
     * 退化为直接覆写，保证数据不丢。
     */
    private fun atomicWrite(text: String) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp-${System.nanoTime()}")
        try {
            tmp.writeText(text, Charsets.UTF_8)
            if (!tmp.renameTo(file)) {
                file.writeText(text, Charsets.UTF_8)
            }
        } finally {
            if (tmp.exists()) runCatching { tmp.delete() }
        }
    }

    private fun keyStorageLabel(): String {
        val rec = cachedKeyRecord ?: readDto().apiKey
        return when {
            apiKey().isBlank() -> "未设置"
            rec?.encrypted != null && vault != null -> "加密（AES-256-GCM 保管库）"
            rec?.isPlainText == true -> "明文（未接入加密保管库，建议宿主接入）"
            else -> "未知"
        }
    }
}

/** 密钥掩码（store 与测试共用）：`sk-xxx…xxxx`；短 Key 与空 Key 不泄露任何字符。 */
internal fun maskKey(key: String): String = when {
    key.isBlank() -> "(未配置)"
    key.length <= SHORT_KEY_THRESHOLD -> "****"
    else -> key.take(PREFIX_CHARS) + "…" + key.takeLast(SUFFIX_CHARS)
}

private const val SHORT_KEY_THRESHOLD = 12
private const val PREFIX_CHARS = 6
private const val SUFFIX_CHARS = 4
