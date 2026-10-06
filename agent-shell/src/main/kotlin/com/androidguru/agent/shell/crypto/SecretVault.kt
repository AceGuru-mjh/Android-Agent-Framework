package com.androidguru.agent.shell.crypto

import java.io.File
import java.security.KeyStore
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 密钥保管库 —— API Key 等敏感凭据的静态加密。
 *
 * yl-ai 用 AndroidKeyStore（AES-256/GCM，密钥不出安全存储）；本框架是纯 JVM，
 * 提供 JCE KeyStore（PKCS12 文件）的等价实现，Android 宿主可将 [KeyStore] 换成
 * AndroidKeyStore 实例（接口不变）。
 *
 * 密文格式：`iv:body` 双 base64（无填充），与 yl-ai 的 SharedPreferences 存储格式一致。
 */
interface SecretVault {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertext: String): String
}

/**
 * AES-256-GCM + JCE KeyStore 实现。
 *
 * @param keyStoreFile PKCS12 密钥库文件（纯 JVM）；Android 宿主可注入
 *   AndroidKeyStore 的加载器替代
 * @param keyStorePassword 密钥库口令（null = 无口令打开）
 * @param keyAlias 密钥别名
 */
class AesGcmSecretVault(
    private val keyStoreFile: File,
    private val keyStorePassword: CharArray? = null,
    private val keyAlias: String = "agsh_secret_key",
) : SecretVault {

    private val lock = Any()

    private fun obtainKey(): SecretKey {
        val ks = KeyStore.getInstance("PKCS12")
        if (keyStoreFile.exists()) {
            keyStoreFile.inputStream().use { input ->
                ks.load(input, keyStorePassword)
            }
        } else {
            ks.load(null, keyStorePassword)
        }
        (ks.getKey(keyAlias, null) as? SecretKey)?.let { return it }

        // 首次：生成并落库
        val generator = KeyGenerator.getInstance("AES")
        generator.init(256)
        val key = generator.generateKey()
        ks.setKeyEntry(keyAlias, key, null, null)
        keyStoreFile.parentFile?.mkdirs()
        keyStoreFile.outputStream().use { output ->
            ks.store(output, keyStorePassword)
        }
        return key
    }

    override fun encrypt(plaintext: String): String = synchronized(lock) {
        val key = obtainKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val iv = ByteArray(12).also { SecureRandom().nextBytes(it) }
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, iv))
        val body = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        val enc = Base64.getEncoder().withoutPadding()
        enc.encodeToString(iv) + ":" + enc.encodeToString(body)
    }

    override fun decrypt(ciphertext: String): String = synchronized(lock) {
        val key = obtainKey()
        val dec = Base64.getDecoder()
        val parts = ciphertext.split(":")
        require(parts.size == 2) { "密文格式异常（期望 iv:body）" }
        val iv = dec.decode(parts[0])
        val body = dec.decode(parts[1])
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        String(cipher.doFinal(body), Charsets.UTF_8)
    }

    /** 掩码显示（日志 / 界面）。 */
    fun maskKey(apiKey: String): String = when {
        apiKey.isBlank() -> "(未配置)"
        apiKey.length <= 8 -> "***"
        else -> apiKey.take(4) + "****" + apiKey.takeLast(4)
    }
}
