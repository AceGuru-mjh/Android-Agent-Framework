package com.androidguru.agent.shell.settings

import com.androidguru.agent.shell.crypto.AesGcmSecretVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [AgentSettingsStore] 测试：vault 加密往返 / 明文兜底 / 掩码 / coerce / 原子写。
 * 对应 yl-ai AiSettings 的全部语义（SharedPreferences + AndroidKeyStore → JSON + SecretVault）。
 */
class AgentSettingsStoreTest {

    private fun newVault(base: File): AesGcmSecretVault =
        AesGcmSecretVault(keyStoreFile = File(base, "keystore.p12"))

    private val sample = AgentSettings(
        providerLabel = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        model = "deepseek-chat",
    )

    // ---------------- 加密往返 ----------------

    @Test
    fun `vault 加密往返_文件不含明文 Key`() {
        val base = java.nio.file.Files.createTempDirectory("agsh-settings").toFile()
        try {
            val vault = newVault(base)
            val file = File(base, "settings.json")
            val store = AgentSettingsStore(file, vault)
            val key = "sk-test1234567890abcd"
            store.save(sample, key)
            assertEquals("保存后立刻可读回", key, store.apiKey())

            // 新实例（模拟进程重启）读回
            val reopened = AgentSettingsStore(file, vault)
            assertEquals("重启后经 vault 解密读回", key, reopened.apiKey())

            // 密文检查：encrypted 字段为 iv:body 双 base64，文件里没有明文 Key
            val raw = file.readText()
            assertFalse("文件绝不能含明文 Key", raw.contains(key))
            assertTrue("应存 encrypted 字段", raw.contains("\"encrypted\""))
            val encLine = Regex("\"encrypted\"\\s*:\\s*\"([^\"]+)\"").find(raw)!!.groupValues[1]
            assertTrue("密文格式应为 iv:body", encLine.contains(':'))
            assertEquals("iv 与 body 都应是 base64", 2, encLine.split(':').size)
            assertFalse("isPlainText 不应为 true", Regex("\"isPlainText\"\\s*:\\s*true").containsMatchIn(raw))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `无 vault 时明文存储并带 isPlainText 标记`() {
        val base = java.nio.file.Files.createTempDirectory("agsh-settings").toFile()
        try {
            val file = File(base, "settings.json")
            val key = "sk-plain-key-000111"
            val store = AgentSettingsStore(file, vault = null)
            store.save(sample, key)
            assertEquals("无 vault 也应能读回", key, store.apiKey())

            val raw = file.readText()
            assertTrue("明文兜底必须知情标记", raw.contains("\"isPlainText\""))
            assertTrue(raw.contains(key))

            val reopened = AgentSettingsStore(file, vault = null)
            assertEquals(key, reopened.apiKey())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `空 Key 保存即清除已存 Key`() {
        val base = java.nio.file.Files.createTempDirectory("agsh-settings").toFile()
        try {
            val store = AgentSettingsStore(File(base, "s.json"), newVault(base))
            store.save(sample, "sk-1234567890abcd")
            assertEquals("sk-1234567890abcd", store.apiKey())
            store.save(sample, "")
            assertEquals("空 Key = 清除", "", store.apiKey())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `密文损坏时解密回退空串而不抛异常`() {
        val base = java.nio.file.Files.createTempDirectory("agsh-settings").toFile()
        try {
            val file = File(base, "s.json")
            file.writeText(
                """{"settings":{"providerLabel":"X","baseUrl":"https://a.com/v1","model":"m"},""" +
                    """"apiKey":{"encrypted":"!!!not-base64:???","isPlainText":false}}""",
            )
            val store = AgentSettingsStore(file, newVault(base))
            assertEquals("解密失败应回退空串", "", store.apiKey())
            assertEquals("设置本身仍可读", "X", store.load().providerLabel)
        } finally {
            base.deleteRecursively()
        }
    }

    // ---------------- 掩码 ----------------

    @Test
    fun `maskKey 按 sk-xxx…xxxx 形态掩码`() {
        assertEquals("sk-123…abcd", maskKey("sk-1234567890abcd"))
        assertEquals("(未配置)", maskKey(""))
        assertEquals("****", maskKey("short"))
        assertEquals("短 Key 不泄露任何字符", "****", maskKey("sk-12345678"))
        // 掩码不含完整 Key
        val masked = maskKey("sk-1234567890abcd")
        assertFalse(masked.contains("sk-1234567890abcd"))

        val base = java.nio.file.Files.createTempDirectory("agsh-settings").toFile()
        try {
            val store = AgentSettingsStore(File(base, "s.json"), null)
            assertEquals("未配置时 store.maskKey 走同一路径", "(未配置)", store.maskKey())
            store.save(sample, "sk-1234567890abcd")
            assertEquals("sk-123…abcd", store.maskKey())
        } finally {
            base.deleteRecursively()
        }
    }

    // ---------------- coerce ----------------

    @Test
    fun `load 时越界值收拢回合法区间`() {
        val base = java.nio.file.Files.createTempDirectory("agsh-settings").toFile()
        try {
            val file = File(base, "s.json")
            file.writeText(
                """{"settings":{"maxStepsPerTask":500,"commandTimeoutMs":1}}""",
            )
            val s = AgentSettingsStore(file).load()
            assertEquals("maxSteps 应收拢到 100", 100, s.maxStepsPerTask)
            assertEquals("timeout 应收拢到 2000", 2000, s.commandTimeoutMs)
            assertEquals("未提供的字段回默认", 24, AgentSettings().maxStepsPerTask)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `save 时越界值收拢后落盘`() {
        val base = java.nio.file.Files.createTempDirectory("agsh-settings").toFile()
        try {
            val file = File(base, "s.json")
            val store = AgentSettingsStore(file)
            store.save(sample.copy(maxStepsPerTask = 0, commandTimeoutMs = 999_999), "")
            assertEquals(1, store.load().maxStepsPerTask)
            assertEquals(300_000, store.load().commandTimeoutMs)
            // 落盘的就是收拢后的值（下次进程读取不会再越界）；冒号两侧空白容忍（kotlinx prettyPrint）
            val raw = file.readText()
            assertTrue(Regex("\"maxStepsPerTask\"\\s*:\\s*1").containsMatchIn(raw))
            assertTrue(Regex("\"commandTimeoutMs\"\\s*:\\s*300000").containsMatchIn(raw))
        } finally {
            base.deleteRecursively()
        }
    }

    // ---------------- 原子写 ----------------

    @Test
    fun `原子写不留残留 tmp 文件且可连续保存`() {
        val base = java.nio.file.Files.createTempDirectory("agsh-settings").toFile()
        try {
            val file = File(base, "s.json")
            val store = AgentSettingsStore(file)
            repeat(5) { i ->
                store.save(sample.copy(model = "m$i"), "sk-key-$i-abcdefgh")
            }
            assertEquals("最后一次保存生效", "m4", store.load().model)
            val leftovers = base.listFiles { f -> f.name.contains(".tmp-") }.orEmpty()
            assertTrue("不应残留 tmp 文件：${leftovers.map { it.name }}", leftovers.isEmpty())
            assertTrue("目标文件存在且可解析", file.isFile)
        } finally {
            base.deleteRecursively()
        }
    }

    // ---------------- isConfigured / describe ----------------

    @Test
    fun `isConfigured 与 describe 语义与 yl-ai 一致`() {
        val base = java.nio.file.Files.createTempDirectory("agsh-settings").toFile()
        try {
            val file = File(base, "s.json")
            val store = AgentSettingsStore(file, newVault(base))
            assertFalse("空设置未配置", AgentSettingsStore(file).isConfigured)
            assertFalse("只有端点不算配置完成", store.load().copy(baseUrl = "https://a.com").isConfigured)
            assertFalse("只有模型不算配置完成", store.load().copy(model = "m").isConfigured)

            store.save(sample, "sk-1234567890abcd")
            assertTrue("端点+模型齐备即配置完成", store.isConfigured)

            val d = store.describe()
            assertTrue(d.contains("服务商: DeepSeek"))
            assertTrue(d.contains("端点: https://api.deepseek.com"))
            assertTrue(d.contains("模型: deepseek-chat"))
            assertTrue("describe 掩码 Key", d.contains("sk-123…abcd"))
            assertTrue(d.contains("单任务最大步数: 24"))
            assertTrue(d.contains("只读命令自动放行: 是"))
            assertTrue(d.contains("命令超时: 30000ms"))
            assertTrue(d.contains("密钥存储: 加密"))
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `损坏的设置文件回退默认值`() {
        val base = java.nio.file.Files.createTempDirectory("agsh-settings").toFile()
        try {
            val file = File(base, "s.json")
            file.writeText("{{{not json")
            val store = AgentSettingsStore(file)
            assertEquals(AgentSettings(), store.load())
            assertNotEquals(true, store.isConfigured)
        } finally {
            base.deleteRecursively()
        }
    }
}
