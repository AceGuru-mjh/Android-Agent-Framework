package com.androidguru.agent.shelltools.tools

import com.androidguru.agent.shell.ShellRuntime
import com.androidguru.agent.tools.AgentTool
import com.androidguru.agent.tools.ToolCategory
import com.androidguru.agent.tools.ToolErrorCode
import com.androidguru.agent.tools.ToolMetadata
import com.androidguru.agent.tools.ToolRequest
import com.androidguru.agent.tools.ToolResult
import com.androidguru.agent.tools.ToolRisk
import com.androidguru.agent.tools.ToolAnnotations
import com.androidguru.agent.tools.ToolSchema
import com.androidguru.agent.shelltools.JsonArgs
import com.androidguru.agent.shelltools.ShellToolSet
import java.io.File

/**
 * 文件四件套：fs_read / fs_write / fs_list / file_delete。
 *
 * file_delete 的安全语义（保留 yl-ai 的设计）：**删除 = 移入回收目录** ——
 * rename 到 `trash/<时间戳>_<名字>`，rename 失败才硬删。可恢复，审批卡上
 * 明示「可撤销」。
 */
object FsTools {

    /** `~` 展开 + 相对路径基于会话 cwd 解析。 */
    internal fun resolve(runtime: ShellRuntime, raw: String): File {
        val expanded = when {
            raw == "~" -> System.getProperty("user.home")
            raw.startsWith("~/") -> File(System.getProperty("user.home"), raw.removePrefix("~/")).path
            else -> raw
        }
        val f = File(expanded)
        return if (f.isAbsolute) f else File(runtime.sessions.active?.currentDirectory ?: runtime.environment.home.absolutePath, expanded)
    }

    fun read(runtime: ShellRuntime): AgentTool = object : AgentTool {
        override val id = ShellToolSet.ToolIds.FS_READ
        override val description =
            "读取文件内容（UTF-8 文本）。支持 ~ 与相对路径（基于当前工作目录）。" +
                "超过 max_bytes 只返回开头并说明总大小。"
        override val parameters = ToolSchema.build {
            string("path", "文件路径", required = true)
            integer("max_bytes", "最多读取字节（默认 65536，范围 256–1048576）",
                minimum = 256.0, maximum = 1048576.0)
        }
        override val metadata = ToolMetadata(
            id = id, category = ToolCategory.FILE,
            annotations = ToolAnnotations(readOnlyHint = true, idempotentHint = true),
        )

        override suspend fun execute(request: ToolRequest): ToolResult {
            val args = JsonArgs.parse(request.arguments)
            val path = args.str("path") ?: return ToolResult.invalid("path", "缺少 path")
            val maxBytes = (args.long("max_bytes") ?: 65536L).coerceIn(256, 1024L * 1024)
            val f = resolve(runtime, path)
            if (!f.isFile) return ToolResult.failure("文件不存在：${f.absolutePath}", ToolErrorCode.NOT_FOUND)
            if (f.length() > maxBytes) {
                val content = f.inputStream().use { input ->
                    val buf = ByteArray(maxBytes.toInt())
                    var read = 0
                    while (read < buf.size) {
                        val n = input.read(buf, read, buf.size - read)
                        if (n < 0) break
                        read += n
                    }
                    String(buf, 0, read, Charsets.UTF_8)
                }
                return ToolResult.success(
                    "文件较大（${f.length()} 字节），仅返回前 $maxBytes 字节：\n$content",
                )
            }
            return ToolResult.success(f.readText())
        }
    }

    fun write(runtime: ShellRuntime): AgentTool = object : AgentTool {
        override val id = ShellToolSet.ToolIds.FS_WRITE
        override val description =
            "写入文件（覆盖或追加）。需要用户审批（审批卡会显示影响摘要）。" +
                "目标目录不存在时自动创建。"
        override val parameters = ToolSchema.build {
            string("path", "目标文件路径", required = true)
            string("content", "要写入的内容", required = true)
            boolean("append", "是否追加（默认 false = 覆盖）")
        }
        override val metadata = ToolMetadata(
            id = id, category = ToolCategory.FILE, risk = ToolRisk.MEDIUM,
            annotations = ToolAnnotations(destructiveHint = true),
        )

        override suspend fun execute(request: ToolRequest): ToolResult {
            val args = JsonArgs.parse(request.arguments)
            val path = args.str("path") ?: return ToolResult.invalid("path", "缺少 path")
            val content = args.str("content")
                ?: return ToolResult.invalid("content", "缺少 content")
            val append = args.bool("append") ?: false
            val f = resolve(runtime, path)
            f.parentFile?.mkdirs()
            return try {
                if (append) f.appendText(content) else f.writeText(content)
                val bytes = content.toByteArray(Charsets.UTF_8).size.toLong()
                runtime.audit.recordFileWrite(f.absolutePath, bytes, approved = true)
                ToolResult.success(
                    "已写入 ${f.absolutePath}（$bytes 字节，${if (append) "追加" else "覆盖"}）",
                    data = """{"path":"${f.absolutePath}","bytes":$bytes}""",
                )
            } catch (e: Exception) {
                ToolResult.failure("写入失败：${e.message}", ToolErrorCode.INTERNAL)
            }
        }
    }

    fun list(runtime: ShellRuntime): AgentTool = object : AgentTool {
        override val id = ShellToolSet.ToolIds.FS_LIST
        override val description =
            "列目录（递归树，depth 1–3）。默认列当前工作目录。"
        override val parameters = ToolSchema.build {
            string("path", "目录路径（默认当前工作目录）")
            integer("depth", "递归深度（默认 1，最大 3）", minimum = 1.0, maximum = 3.0)
        }
        override val metadata = ToolMetadata(
            id = id, category = ToolCategory.FILE,
            annotations = ToolAnnotations(readOnlyHint = true),
        )

        override suspend fun execute(request: ToolRequest): ToolResult {
            val args = JsonArgs.parse(request.arguments)
            val path = args.str("path") ?: runtime.sessions.active?.currentDirectory
                ?: runtime.environment.home.absolutePath
            val depth = (args.int("depth") ?: 1).coerceIn(1, 3)
            val root = resolve(runtime, path)
            if (!root.isDirectory) return ToolResult.failure("目录不存在：${root.absolutePath}", ToolErrorCode.NOT_FOUND)

            val sb = StringBuilder()
            var budget = LIMIT_ENTRIES
            var budgetExhausted = false

            fun walk(dir: File, level: Int, prefix: String) {
                if (budgetExhausted || level > depth) return
                val children = dir.listFiles()?.sortedWith(compareByDescending<File> { it.isDirectory }.thenBy { it.name }) ?: return
                for (c in children) {
                    if (budget <= 0) { budgetExhausted = true; return }
                    budget--
                    val mark = if (c.isDirectory) "d" else "-"
                    val size = if (c.isFile) c.length().toString() else ""
                    sb.appendLine("$prefix$mark ${c.name} $size")
                    if (c.isDirectory) walk(c, level + 1, "$prefix  ")
                }
            }
            walk(root, 1, "")
            if (budgetExhausted) sb.appendLine("…（条目过多，已截断）")
            return ToolResult.success("目录：${root.absolutePath}\n${sb.toString().trimEnd()}")
        }
    }

    fun delete(runtime: ShellRuntime): AgentTool = object : AgentTool {
        override val id = ShellToolSet.ToolIds.FILE_DELETE
        override val description =
            "删除文件或目录。实际是移入回收目录（可恢复），回收目录定期清理。" +
                "需要用户审批。"
        override val parameters = ToolSchema.build {
            string("path", "要删除的路径", required = true)
        }
        override val metadata = ToolMetadata(
            id = id, category = ToolCategory.FILE, risk = ToolRisk.MEDIUM,
            annotations = ToolAnnotations(destructiveHint = true),
            tags = setOf("trash", "recoverable"),
        )

        override suspend fun execute(request: ToolRequest): ToolResult {
            val args = JsonArgs.parse(request.arguments)
            val path = args.str("path") ?: return ToolResult.invalid("path", "缺少 path")
            val f = resolve(runtime, path)
            if (!f.exists() && !isSymlink(f)) {
                return ToolResult.failure("路径不存在：${f.absolutePath}", ToolErrorCode.NOT_FOUND)
            }

            // 安全边界：只允许删 baseDir 与共享存储内的内容（yl-ai 的边界语义）
            val allowedRoots = listOf(runtime.baseDir.absoluteFile, File("/sdcard"), File("/storage"))
            val canonical = runCatching { f.canonicalFile }.getOrDefault(f.absoluteFile)
            val inside = allowedRoots.any { root ->
                canonical.absolutePath == root.absolutePath ||
                    canonical.absolutePath.startsWith(root.absolutePath + File.separator)
            }
            if (!inside) {
                return ToolResult.failure(
                    "拒绝删除：只允许删除运行时目录（${runtime.baseDir}）与共享存储内的路径。" +
                        "其它路径请通过终端命令并走用户审批。",
                    ToolErrorCode.PERMISSION,
                )
            }

            val trash = File(runtime.baseDir, "trash").apply { mkdirs() }
            val target = File(trash, "${System.currentTimeMillis()}_${f.name}")
            val moved = runCatching { f.renameTo(target) }.getOrDefault(false)
            return if (moved) {
                runtime.audit.recordSetting("file.trash", "${f.absolutePath} -> ${target.absolutePath}")
                ToolResult.success(
                    "已删除（实际移入回收目录，可恢复）：${f.absolutePath}\n回收位置：${target.absolutePath}",
                )
            } else {
                val deleted = runCatching { f.deleteRecursively() }.getOrDefault(false)
                if (deleted) {
                    runtime.audit.recordSetting("file.delete_hard", f.absolutePath)
                    ToolResult.success("已删除（无法移入回收目录，已直接删除）：${f.absolutePath}")
                } else {
                    ToolResult.failure("删除失败：${f.absolutePath}", ToolErrorCode.INTERNAL)
                }
            }
        }

        private fun isSymlink(f: File): Boolean =
            runCatching { java.nio.file.Files.isSymbolicLink(f.toPath()) }.getOrDefault(false)
    }

    private const val LIMIT_ENTRIES = 200
}
