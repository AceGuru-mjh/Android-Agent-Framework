package com.androidguru.agent.shell.policy

import com.androidguru.agent.shell.audit.AuditLog

/**
 * 终端写入闸门 —— 修复 issue #9（安全边界）：`terminal_write` / `session.write`
 * 旧实现完全绕过策略、审批与审计，模型在被拒 `rm -rf x` 后可以改用
 * `terminal_write("rm -rf x\n")` 在共享 shell 里直接执行。
 *
 * ## 门控语义（兼顾「应答交互式程序」的核心用途）
 *
 * 只有**完整行**（以 `\n` 结尾）才可能是命令；交互应答（`y\n`、密码、`q\n`）
 * 通常不是已知命令名，保持放行：
 * - 对每条完整行跑 [CommandPolicy.decide]；
 * - **BLOCKED 恒拦截**（无论行内容像不像命令 —— 硬拦截宁可错杀）；
 * - CONFIRM 仅在行**看起来是 shell 命令**时强制审批：
 *   首词是已知命令（白名单/修改类），或含 `;` `&&` `|` `>` `` ` `` `$(` 等元字符；
 * - 未以换行结尾的尾行视为"正在输入的半行"，本轮放行；
 * - 全部门控决策（放行/审批/拦截）都记入审计。
 */
object WriteGate {

    data class Result(
        val allowed: Boolean,
        /** 拒绝原因（allowed=false 时非空）。 */
        val reason: String? = null,
    )

    private val shellMetachars = Regex("""[;|>]|&&|\$\(|`""")

    fun gate(input: String, source: AuditLog.Source, policy: CommandPolicy, audit: AuditLog): Result {
        if (input.isEmpty()) return Result(allowed = true)

        val completeLines = input.split('\n').dropLast(1) // 只取以 \n 结尾的完整行
        for (rawLine in completeLines) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue

            val decision = policy.decide(line)

            if (decision.level == CommandPolicy.Level.BLOCKED) {
                audit.recordBlocked(source, line, decision.reason)
                return Result(
                    allowed = false,
                    reason = "写入内容被安全策略硬拦截：${decision.reason}\n" +
                        "这条命令不会因为换一种写入方式而变化，请改用安全的方式完成同样的事。",
                )
            }

            if (decision.level == CommandPolicy.Level.CONFIRM && looksLikeCommand(line)) {
                audit.recordCommand(source, line, level = "WRITE_CONFIRM_REQUIRED")
                return Result(
                    allowed = false,
                    reason = "写入内容包含需要确认的命令（${decision.reason}）。\n" +
                        "请改用 terminal_exec（会走用户审批），或先向用户说明并征得同意。",
                )
            }
        }
        return Result(allowed = true)
    }

    /** 行首词是已知命令，或含 shell 元字符 → 视为真实命令而非交互应答。 */
    private fun looksLikeCommand(line: String): Boolean {
        if (shellMetachars.containsMatchIn(line)) return true
        val firstWord = line.split(Regex("\\s+")).firstOrNull()
            ?.substringAfterLast('/')?.lowercase() ?: return false
        return firstWord in knownCommandWords
    }

    private val knownCommandWords: Set<String> = buildList {
        addAll(listOf("ls", "cat", "rm", "mv", "cp", "mkdir", "touch", "echo", "printf", "cd", "pwd"))
        addAll(listOf("sh", "bash", "zsh", "su", "sudo", "kill", "pkill", "reboot", "shutdown"))
        addAll(listOf("apt", "apt-get", "apk", "pkg", "pip", "pip3", "npm", "node", "python", "python3", "git", "curl", "wget"))
        addAll(listOf("chmod", "chown", "dd", "mkfs", "fdisk", "shutdown", "halt", "format", "wipe"))
        addAll(listOf("tar", "gzip", "unzip", "sed", "awk", "grep", "find", "xargs", "nc", "ssh", "make", "gcc"))
    }.toSet()
}
