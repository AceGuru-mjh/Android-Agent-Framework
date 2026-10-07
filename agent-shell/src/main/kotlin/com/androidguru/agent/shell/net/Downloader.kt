package com.androidguru.agent.shell.net

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 下载器 —— 断点续传 + SHA256 校验 + 指数退避重试。
 *
 * 保留 yl-ai 的全部工程细节：
 * - `.part` 中间文件 + `Range: bytes=<已有>-` 断点续传（仅 HTTP 206 才续传，
 *   否则从头下载）；
 * - 校验失败抛 [ChecksumMismatch] 且**不重试**（网络问题重试有意义，内容被篡改没有）；
 * - 网络异常指数退避 `1s << attempt`，上限 15s；
 * - 成功后 `.part` → 目标名 rename（原子落位）。
 *
 * 去 Android 化：日志通过 [onLog] 回调交付，不依赖任何日志框架。
 */
class Downloader(
    private val userAgent: String = DEFAULT_USER_AGENT,
    private val onLog: (String) -> Unit = {},
) {

    class ChecksumMismatch(expected: String, actual: String) :
        IllegalStateException("SHA256 校验失败。期望: $expected，实际: $actual")

    /**
     * 下载文件到 [dest]。
     *
     * @param expectedSha256 期望的 SHA256（64 位十六进制，忽略大小写）；null 跳过校验
     * @param maxAttempts 最大尝试次数（含首次）
     */
    suspend fun download(
        url: String,
        dest: File,
        expectedSha256: String? = null,
        maxAttempts: Int = 8,
        onProgress: (received: Long, total: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")

        var result: File? = null
        var attempt = 0
        while (result == null) {
            try {
                val already = if (part.isFile) part.length() else 0L
                val conn = open(url)
                if (already > 0) {
                    conn.setRequestProperty("Range", "bytes=$already-")
                }
                val code = conn.responseCode
                val resuming = code == HttpURLConnection.HTTP_PARTIAL && already > 0

                if (code !in 200..299) {
                    conn.disconnect()
                    throw java.io.IOException("HTTP $code")
                }

                val total = if (resuming) {
                    already + (conn.contentLengthLong.takeIf { it > 0 } ?: 0L)
                } else {
                    conn.contentLengthLong.takeIf { it > 0 } ?: -1L
                }

                conn.inputStream.use { input ->
                    FileOutputStream(part, resuming).use { out ->
                        val buf = ByteArray(16 * 1024)
                        var received = if (resuming) already else 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            received += n
                            onProgress(received, total)
                        }
                        out.flush()
                    }
                }
                conn.disconnect()

                // 完整性校验
                if (expectedSha256 != null) {
                    val actual = sha256(part)
                    if (!actual.equals(expectedSha256, ignoreCase = true)) {
                        part.delete()
                        throw ChecksumMismatch(expectedSha256.lowercase(), actual)
                    }
                }

                // 原子落位
                if (dest.exists()) dest.delete()
                if (!part.renameTo(dest)) {
                    part.copyTo(dest, overwrite = true)
                    part.delete()
                }
                onLog("下载完成：${dest.name}（${dest.length()} 字节）")
                result = dest
            } catch (e: CancellationException) {
                throw e
            } catch (e: ChecksumMismatch) {
                throw e // 内容被篡改：重试没有意义
            } catch (e: Exception) {
                attempt++
                if (attempt >= maxAttempts) {
                    throw java.io.IOException("下载失败（已尝试 $attempt 次）：${e.message}", e)
                }
                val backoff = (1000L shl (attempt - 1)).coerceAtMost(15_000L)
                onLog("下载中断（${e.message ?: e.javaClass.simpleName}），${backoff / 1000}s 后第 ${attempt + 1} 次重试")
                kotlinx.coroutines.delay(backoff)
            }
        }
        result
    }

    /** 下载文本（上限 [limitBytes]）。 */
    suspend fun getText(url: String, limitBytes: Int = 256 * 1024): String = withContext(Dispatchers.IO) {
        val conn = open(url)
        try {
            val code = conn.responseCode
            if (code !in 200..299) throw java.io.IOException("HTTP $code")
            val stream = conn.inputStream
            val buf = ByteArray(8192)
            var received = 0
            val bytes = java.io.ByteArrayOutputStream()
            while (received < limitBytes) {
                val n = stream.read(buf, 0, minOf(buf.size, limitBytes - received))
                if (n < 0) break
                bytes.write(buf, 0, n)
                received += n
            }
            // 修复 issue #12：整体解码而非逐 chunk 解码，避免多字节字符跨 chunk 损坏
            String(bytes.toByteArray(), Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }

    /** HEAD 请求取内容长度。 */
    suspend fun head(url: String): Long = withContext(Dispatchers.IO) {
        val conn = open(url)
        try {
            conn.requestMethod = "HEAD"
            if (conn.responseCode !in 200..299) throw java.io.IOException("HTTP ${conn.responseCode}")
            conn.contentLengthLong
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", userAgent)
        conn.instanceFollowRedirects = true
        return conn
    }

    companion object {
        const val DEFAULT_USER_AGENT = "AndroidAgentFramework/1.0 (+jvm)"

        /** 计算文件 SHA256（64 位小写十六进制）。 */
        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }
    }
}
