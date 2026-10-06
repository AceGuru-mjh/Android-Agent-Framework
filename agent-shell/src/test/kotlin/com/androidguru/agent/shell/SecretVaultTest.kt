package com.androidguru.agent.shell

import com.androidguru.agent.shell.crypto.AesGcmSecretVault
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SecretVaultTest {

    @Test
    fun `加解密往返`() {
        val dir = java.nio.file.Files.createTempDirectory("agsh-vault").toFile()
        try {
            val vault = AesGcmSecretVault(File(dir, "keys.p12"), keyStorePassword = "pw123".toCharArray())
            val secret = "sk-abc123-我的密钥"
            val cipher = vault.encrypt(secret)
            assertNotEquals(secret, cipher)
            assertEquals(secret, vault.decrypt(cipher))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `密文格式为iv_body双base64`() {
        val dir = java.nio.file.Files.createTempDirectory("agsh-vault").toFile()
        try {
            val vault = AesGcmSecretVault(File(dir, "keys.p12"))
            val cipher = vault.encrypt("data")
            val parts = cipher.split(":")
            assertEquals(2, parts.size)
            assertTrue(parts[0].isNotBlank())
            assertTrue(parts[1].isNotBlank())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `相同明文两次加密产生不同密文（随机IV）`() {
        val dir = java.nio.file.Files.createTempDirectory("agsh-vault").toFile()
        try {
            val vault = AesGcmSecretVault(File(dir, "keys.p12"))
            val c1 = vault.encrypt("same")
            val c2 = vault.encrypt("same")
            assertNotEquals(c1, c2)
            assertEquals("same", vault.decrypt(c1))
            assertEquals("same", vault.decrypt(c2))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `密钥库跨实例复用`() {
        val dir = java.nio.file.Files.createTempDirectory("agsh-vault").toFile()
        try {
            val file = File(dir, "keys.p12")
            val v1 = AesGcmSecretVault(file, keyStorePassword = "pw".toCharArray())
            val cipher = v1.encrypt("persistent")
            val v2 = AesGcmSecretVault(file, keyStorePassword = "pw".toCharArray())
            assertEquals("persistent", v2.decrypt(cipher))
        } finally {
            dir.deleteRecursively()
        }
    }
}
