package com.androidguru.agent.shell.runtime

import java.io.File

/**
 * 环境探测 —— 探测真实可用的命令并生成注入 system prompt 的清单。
 *
 * 为什么要探测（yl-ai 的核心洞察）：模型对「手机/容器里有什么命令」毫无概念，
 * 会反复 curl / git / python 瞎试浪费 token。把「**确认不存在**的命令」显式写进
 * 提示词，模型立即改道 —— 这是环境自检最省钱的一个设计。
 *
 * 注意：探测是启动时的静态快照；容器安装 / apt install 之后的命令不在快照里
 * （宿主可在装完容器后重新 [probe] 并刷新注入）。
 */
class EnvironmentProbe(private val environment: ShellEnvironment) {

    /** 必备命令（45 个）：日常 shell 工作的基线。 */
    val essential: List<String> = listOf(
        "sh", "ls", "cat", "cp", "mv", "rm", "mkdir", "rmdir", "ln", "chmod", "touch",
        "echo", "printf", "sed", "awk", "grep", "find", "xargs", "sort", "uniq", "cut", "tr",
        "head", "tail", "wc", "diff", "tar", "gzip", "unzip", "zip", "date", "df", "du",
        "ps", "top", "kill", "env", "id", "uname", "pwd", "sleep", "seq", "base64",
    )

    /** 开发命令（26 个）：有则锦上添花。 */
    val developer: List<String> = listOf(
        "curl", "wget", "ssh", "scp", "sftp", "git", "python3", "python", "node", "npm",
        "pip3", "vim", "nano", "tmux", "screen", "jq", "rg", "fd", "make", "gcc", "clang",
        "busybox", "nc", "socat", "openssl", "sqlite3", "ffmpeg",
    )

    /** shell 内建命令（视为存在）。 */
    val builtins: Set<String> = setOf(
        "echo", "printf", "cd", "pwd", "export", "unset", "alias", "unalias", "set",
        "shift", "test", "true", "false", "read", "exit", "return", "eval", "exec",
        "trap", "wait", "type", "command", "ulimit", "umask", "jobs", "fg", "bg",
    )

    data class Report(
        val available: List<String>,
        val missing: List<String>,
        val shellPath: String,
    ) {
        /** 注入 system prompt 的三段式清单。 */
        fun forPrompt(): String = buildString {
            appendLine("- 可用命令（$shellPath 的 PATH 中已确认存在）：")
            appendLine("  " + available.joinToString(" "))
            appendLine("- **确认不存在**的命令（不要尝试，会失败）：")
            appendLine("  " + missing.joinToString(" "))
            appendLine("- 若需要上述缺失能力，请用已有命令实现，或明确告知用户需要安装。")
        }
    }

    /** 按 [ShellEnvironment] 的 PATH 目录逐个探测。 */
    fun probe(): Report {
        val pathDirs = environment.pathDirs()

        fun exists(name: String): Boolean {
            if (name in builtins) return true
            for (dir in pathDirs) {
                val f = File(dir, name)
                if (f.isFile && f.canExecute()) return true
            }
            return false
        }

        val all = (essential + developer).distinct()
        val available = all.filter { exists(it) }
        val missing = all.filterNot { exists(it) }
        return Report(available, missing, environment.defaultShell())
    }

    /** 简短摘要（自检报告用）。 */
    fun summarize(): String {
        val r = probe()
        return buildString {
            appendLine("可用 (${r.available.size}): " + r.available.joinToString(" "))
            appendLine()
            appendLine("缺失 (${r.missing.size}): " + r.missing.joinToString(" "))
        }
    }
}
