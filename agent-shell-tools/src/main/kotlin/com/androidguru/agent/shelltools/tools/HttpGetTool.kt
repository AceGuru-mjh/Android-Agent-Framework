package com.androidguru.agent.shelltools.tools

import com.androidguru.agent.shell.ShellRuntime
import com.androidguru.agent.tools.AgentTool
import com.androidguru.agent.tools.ToolCategory
import com.androidguru.agent.tools.ToolErrorCode
import com.androidguru.agent.tools.ToolMetadata
import com.androidguru.agent.tools.ToolRequest
import com.androidguru.agent.tools.ToolResult
import com.androidguru.agent.tools.ToolAnnotations
import com.androidguru.agent.tools.ToolSchema
import com.androidguru.agent.shelltools.HtmlStripper
import com.androidguru.agent.shelltools.JsonArgs
import com.androidguru.agent.shelltools.ShellToolSet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * http_get —— 抓取网页 / API 响应（HTML 自动转纯文本，与 yl-ai 的 stripHtml 对齐）。
 */
class HttpGetTool(private val runtime: ShellRuntime) : AgentTool {

    override val id = ShellToolSet.ToolIds.HTTP_GET
    override val description =
        "抓取一个 http/https URL 的内容（HTML 自动转纯文本，API JSON 原样返回）。" +
            "仅 GET；大响应会被截断。"
    override val parameters = ToolSchema.build {
        string("url", "目标 URL（仅 http/https）", required = true)
        integer("max_bytes", "最多读取字节（默认 200000，范围 1024–2097152）",
            minimum = 1024.0, maximum = 2097152.0)
    }
    override val metadata = ToolMetadata(
        id = id, category = ToolCategory.NETWORK,
        annotations = ToolAnnotations(readOnlyHint = true, openWorldHint = true),
    )

    override suspend fun execute(request: ToolRequest): ToolResult = withContext(Dispatchers.IO) {
        val args = JsonArgs.parse(request.arguments)
        val url = args.str("url")?.trim().orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext ToolResult.invalid("url", "仅支持 http/https URL")
        }
        val maxBytes = (args.long("max_bytes") ?: 200_000L).coerceIn(1024, 2L * 1024 * 1024)

        var conn: HttpURLConnection? = null
        try {
            conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", USER_AGENT)

            val code = conn.responseCode
            if (code !in 200..299) {
                return@withContext ToolResult.failure(
                    "请求失败：HTTP $code",
                    ToolErrorCode.UNAVAILABLE,
                    suggestion = "检查 URL 是否正确；资源可能需要认证或暂时不可用",
                )
            }
            val contentType = conn.contentType ?: ""
            val stream = conn.inputStream
            val buf = ByteArray(16 * 1024)
            var received = 0L
            val out = StringBuilder()
            while (received < maxBytes) {
                val n = stream.read(buf, 0, minOf(buf.size, (maxBytes - received).toInt()))
                if (n < 0) break
                out.append(String(buf, 0, n, Charsets.UTF_8))
                received += n
            }
            val text = if (contentType.contains("html")) HtmlStripper.strip(out.toString()) else out.toString()
            ToolResult.success("HTTP $code（$contentType，${text.length} 字符）\n\n$text")
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            ToolResult.failure(
                "请求失败：${e.message ?: e.javaClass.simpleName}",
                ToolErrorCode.UNAVAILABLE,
            )
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        const val USER_AGENT = "Mozilla/5.0 (Linux; Android) AndroidAgentFramework/1.0"
    }
}
