package com.androidguru.agent.shelltools.tools

import com.androidguru.agent.shell.ShellRuntime
import com.androidguru.agent.shell.session.CommandResult
import com.androidguru.agent.shell.terminal.AnsiStripper
import com.androidguru.agent.tools.AgentTool
import com.androidguru.agent.tools.ToolCategory
import com.androidguru.agent.tools.ToolErrorCode
import com.androidguru.agent.tools.ToolMetadata
import com.androidguru.agent.tools.ToolRequest
import com.androidguru.agent.tools.ToolResult
import com.androidguru.agent.tools.ToolRisk
import com.androidguru.agent.tools.ToolSchema
import com.androidguru.agent.shelltools.JsonArgs
import com.androidguru.agent.shelltools.ShellToolSet
import kotlinx.coroutines.delay

/**
 * 终端三件套：terminal_exec / terminal_write / container_exec。
 *
 * terminal_exec 与 container_exec 的**安全决策不在这里做** —— 审批钩子在
 * PreToolUse 段统一处理（见 [ShellToolSet.approvalHook]），工具自身保持纯粹。
 */
object TerminalTools {

    /**
     * terminal_exec：在共享终端里执行一条命令（用户与 AI 同一条通道）。
     */
    fun terminalExec(runtime: ShellRuntime, defaultTimeoutMs: Long): AgentTool =
        object : AgentTool {
            override val id = ShellToolSet.ToolIds.TERMINAL_EXEC
            override val description =
                "在当前共享终端会话里执行一条 shell 命令，返回退出码与输出。" +
                    "用户与 AI 共用同一终端，命令与输出用户都看得到。" +
                    "长任务（起服务器、长编译）请改用 job_start。"
            override val parameters = ToolSchema.build {
                string("command", "要执行的命令（可以是多行脚本）", required = true)
                integer(
                    "timeout_ms",
                    "超时毫秒数（默认 $defaultTimeoutMs，范围 1000–300000）",
                    minimum = 1000.0,
                    maximum = 300000.0,
                )
                string("purpose", "这条命令的目的（一句话，展示给用户）")
            }
            override val metadata = ToolMetadata(
                id = id,
                category = ToolCategory.SHELL,
                risk = ToolRisk.MEDIUM,
                annotations = com.androidguru.agent.tools.ToolAnnotations(openWorldHint = true),
                tags = setOf("terminal", "shared-session"),
            )

            override suspend fun execute(request: ToolRequest): ToolResult {
                val args = JsonArgs.parse(request.arguments)
                val command = args.str("command")?.trim().orEmpty()
                if (command.isEmpty()) {
                    return ToolResult.invalid("command", "command 不能为空")
                }

                // 防死循环空转：同一条命令连续 ≥3 次直接失败（yl-ai 的保护语义）
                val session = runtime.sessions.active
                    ?: return ToolResult.failure("没有可用终端会话", ToolErrorCode.UNAVAILABLE)
                val runner = runtime.runnerOf(session)
                if (runner.repeatedCommandCount(command) >= 2) {
                    return ToolResult.failure(
                        "检测到同一条命令已连续重复执行 3 次。命令没有产生预期变化，" +
                            "请换一种方式或先诊断环境（例如 ls 查看当前目录、cat 查看文件内容）。",
                        ToolErrorCode.UNKNOWN,
                        suggestion = "停止重复，先收集环境信息再决定策略",
                    )
                }

                val timeoutMs = (args.long("timeout_ms") ?: defaultTimeoutMs).coerceIn(1000, 300_000)
                val result = runner.run(command, timeoutMs = timeoutMs)

                return if (result.success) {
                    ToolResult.success(result.toToolText())
                } else {
                    // 失败也作为可读结果回灌：命令失败是正常信息，让模型读退出码后调整
                    ToolResult.failure(
                        result.toToolText(),
                        ToolErrorCode.UNKNOWN,
                        suggestion = "读退出码与错误输出，调整命令后重试",
                    )
                }
            }
        }

    /**
     * terminal_write：向终端写入原始输入（交互式程序的应答：y/密码/回车等）。
     */
    fun terminalWrite(runtime: ShellRuntime): AgentTool = object : AgentTool {
        override val id = ShellToolSet.ToolIds.TERMINAL_WRITE
        override val description =
            "向当前终端写入原始输入（不执行哨兵协议）。用于应答交互式程序：" +
                "确认提示、输入密码、回车翻页。写完后读取终端尾部输出。"
        override val parameters = ToolSchema.build {
            string("input", "要写入的原始文本（\"\\n\" 表示回车）", required = true)
            integer("then_read_ms", "写入后等待多少毫秒再读输出（默认 800，范围 0–10000）",
                minimum = 0.0, maximum = 10000.0)
        }
        override val metadata = ToolMetadata(
            id = id,
            category = ToolCategory.SHELL,
            risk = ToolRisk.MEDIUM,
            tags = setOf("terminal", "interactive"),
        )

        override suspend fun execute(request: ToolRequest): ToolResult {
            val args = JsonArgs.parse(request.arguments)
            val input = args.str("input")
                ?: return ToolResult.invalid("input", "input 不能为空")
            val waitMs = (args.long("then_read_ms") ?: 800L).coerceIn(0, 10_000)

            val session = runtime.sessions.active
                ?: return ToolResult.failure("没有可用终端会话", ToolErrorCode.UNAVAILABLE)
            session.write(input)
            if (waitMs > 0) delay(waitMs)
            val tail = AnsiStripper.clean(session.tailText(4000))
            return ToolResult.success("已写入输入。随后的终端输出（尾部）：\n$tail")
        }
    }

    /**
     * container_exec：在 Alpine 容器里执行命令（uid=0、apk 可用）。
     *
     * 实现说明：一次性容器执行 —— 每次拉起 proot + `/bin/sh -lc <cmd>`，
     * 与 yl-ai 的 AndroidTools.containerExec 语义一致（输出上限 120KB）。
     */
    fun containerExec(runtime: ShellRuntime): AgentTool = object : AgentTool {
        override val id = ShellToolSet.ToolIds.CONTAINER_EXEC
        override val description =
            "在 Alpine Linux 容器里执行一条命令（root 权限，apk 包管理器可用）。" +
                "适合：安装软件包、使用 Android 上缺失的工具链。容器文件系统独立，" +
                "/root 与宿主可写目录互通。未安装容器时返回安装指引。"
        override val parameters = ToolSchema.build {
            string("command", "要在容器内执行的命令", required = true)
            integer("timeout_ms", "超时毫秒数（默认 120000，范围 5000–600000）",
                minimum = 5000.0, maximum = 600000.0)
            string("purpose", "这条命令的目的（一句话，展示给用户）")
        }
        override val metadata = ToolMetadata(
            id = id,
            category = ToolCategory.SHELL,
            risk = ToolRisk.MEDIUM,
            annotations = com.androidguru.agent.tools.ToolAnnotations(openWorldHint = true),
            tags = setOf("container", "alpine", "proot"),
        )

        override suspend fun execute(request: ToolRequest): ToolResult {
            val args = JsonArgs.parse(request.arguments)
            val command = args.str("command")?.trim().orEmpty()
            if (command.isEmpty()) return ToolResult.invalid("command", "command 不能为空")
            val timeoutMs = (args.long("timeout_ms") ?: 120_000L).coerceIn(5_000, 600_000)

            if (!runtime.container.isReady) {
                return ToolResult.failure(
                    "Alpine 容器尚未安装。请告知用户：到设置界面执行一次安装（约 4 MB，" +
                        "多镜像回退 + 官方 SHA256 校验），安装完成后本工具即可用。",
                    ToolErrorCode.UNAVAILABLE,
                    suggestion = "不要反复尝试；先向用户说明需要安装容器",
                )
            }

            return try {
                val channel = runtime.proot.launch(
                    rootfs = runtime.container.rootfsDir,
                    command = listOf("/bin/sh", "-lc", command),
                    home = runtime.environment.home,
                    rows = 40,
                    cols = 200,
                )
                channel.use { p ->
                    val out = StringBuilder()
                    val buf = ByteArray(8192)
                    var total = 0L
                    val deadline = System.currentTimeMillis() + timeoutMs
                    while (System.currentTimeMillis() < deadline) {
                        val n = p.read(buf)
                        if (n > 0) {
                            out.append(String(buf, 0, n, Charsets.UTF_8))
                            total += n
                            if (total > 120_000L * 4) break // 硬上限
                        }
                        val code = p.waitFor(150)
                        if (code != -2) {
                            val cleaned = AnsiStripper.truncate(
                                AnsiStripper.clean(out.toString()),
                                headChars = 40_000,
                                tailChars = 80_000,
                            )
                            val body = buildString {
                                appendLine("容器内执行：$command")
                                appendLine("退出码: $code")
                                if (cleaned.truncated) appendLine("（输出过长，已省略 ${cleaned.omittedChars} 字符）")
                                appendLine()
                                append(cleaned.text.ifBlank { "(无输出)" })
                            }
                            return@use if (code == 0) ToolResult.success(body) else ToolResult.failure(
                                body,
                                ToolErrorCode.UNKNOWN,
                                suggestion = "读容器内的错误输出后调整命令",
                            )
                        }
                    }
                    ToolResult.failure(
                        "容器命令超时（${timeoutMs}ms）未结束，已放弃等待。进程可能仍在后台运行。",
                        ToolErrorCode.TIMEOUT,
                        suggestion = "缩小任务规模，或改用 job_start 放到后台",
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolResult.failure("容器执行失败：${e.message ?: e.javaClass.simpleName}", ToolErrorCode.UNAVAILABLE)
            }
        }
    }

    /**
     * termux_exec：在 Termux 环境里一次性执行命令（apt / bash 工具链）。
     *
     * 与 container_exec 的分工（yl-ai 里程碑 2 规划的 `termux_exec`）：
     * 容器文件系统独立、适合装软件包实验；Termux 环境与官方 Termux 布局一致、
     * 启动开销更低，日常命令行任务优先用它。与共享终端会话互不占用 ——
     * 一次性命令经独立通道执行，不会打断用户正在看的终端。
     *
     * 安全决策不在这里做 —— 审批钩子在 PreToolUse 段统一门控。
     */
    fun termuxExec(runtime: ShellRuntime): AgentTool = object : AgentTool {
        override val id = ShellToolSet.ToolIds.TERMUX_EXEC
        override val description =
            "在 Termux 环境里执行一条命令（apt / bash / python 等工具链可用）。" +
                "适合日常命令行任务，比 Alpine 容器启动更快。环境未安装时返回安装指引。"
        override val parameters = ToolSchema.build {
            string("command", "要在 Termux 环境内执行的命令", required = true)
            integer("timeout_ms", "超时毫秒数（默认 120000，范围 5000–600000）",
                minimum = 5000.0, maximum = 600000.0)
            string("purpose", "这条命令的目的（一句话，展示给用户）")
        }
        override val metadata = ToolMetadata(
            id = id,
            category = ToolCategory.SHELL,
            risk = ToolRisk.MEDIUM,
            annotations = com.androidguru.agent.tools.ToolAnnotations(openWorldHint = true),
            tags = setOf("terminal", "termux", "proot"),
        )

        override suspend fun execute(request: ToolRequest): ToolResult {
            val args = JsonArgs.parse(request.arguments)
            val command = args.str("command")?.trim().orEmpty()
            if (command.isEmpty()) return ToolResult.invalid("command", "command 不能为空")
            val timeoutMs = (args.long("timeout_ms") ?: 120_000L).coerceIn(5_000, 600_000)

            if (!runtime.termux.isInstalled) {
                return ToolResult.failure(
                    "Termux 环境尚未安装。请告知用户：先执行 Termux 环境安装" +
                        "（约 30 MB，多镜像回退 + 官方 SHA256 校验），安装完成后本工具即可用。",
                    ToolErrorCode.UNAVAILABLE,
                    suggestion = "不要反复尝试；先向用户说明需要安装 Termux 环境",
                )
            }
            val ready = runtime.termuxLauncher.prepare()
            if (!ready.available) {
                return ToolResult.failure(
                    "Termux 环境未就绪：${ready.reason}",
                    ToolErrorCode.UNAVAILABLE,
                    suggestion = "向用户转述原因，不要反复重试",
                )
            }

            return try {
                val text = runtime.termuxLauncher.runShellCommand(command, timeoutMs = timeoutMs)
                if (text.contains("退出码: 0")) {
                    ToolResult.success(text)
                } else {
                    ToolResult.failure(
                        text,
                        ToolErrorCode.UNKNOWN,
                        suggestion = "读 Termux 环境内的错误输出后调整命令",
                    )
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                ToolResult.failure("Termux 执行失败：${e.message ?: e.javaClass.simpleName}", ToolErrorCode.UNAVAILABLE)
            }
        }
    }
}
