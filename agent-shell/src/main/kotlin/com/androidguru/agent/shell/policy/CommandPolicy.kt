package com.androidguru.agent.shell.policy

/**
 * 命令风险分级策略 —— 只读放行 / 写操作确认 / 危险命令硬拦截。
 *
 * 设计要点（保留 yl-ai 的成熟规则并补强）：
 * - **硬拦截优先**：14 条 BLOCKED 正则对**整条命令**匹配，任何一段命中即拒 ——
 *   `ls -la; rm -rf /`、`true && rm -rf /`、`echo hi | rm -rf /` 都拦得住；
 * - **分段分析**：按 `&& || ; | 换行` 切段，逐段看首词与「确认增强因子」；
 * - **白名单收敛**：首词不在任何白名单里 → 需要确认（无法识别 = 不放行）；
 * - BLOCKED 级别的决策**永不进入**审批记忆表（[ApprovalGate.decisionKey] 返回 null），
 *   拒绝就是拒绝，不存在「本次都允许」。
 */
object CommandPolicy {

    enum class Level {
        /** 只读，可直接执行。 */
        SAFE,

        /** 有副作用，需要用户确认。 */
        CONFIRM,

        /** 危险，硬拦截（不允许用户「本次都允许」）。 */
        BLOCKED,
    }

    data class Decision(
        val level: Level,
        val reason: String,
        val matchedRule: String? = null,
        /** 命令涉及的绝对路径（审批界面展示「影响范围」）。 */
        val targets: List<String> = emptyList(),
    )

    private val readOnlyCommands = setOf(
        "ls", "ll", "cat", "head", "tail", "less", "more", "wc", "file", "stat",
        "pwd", "whoami", "id", "uname", "date", "uptime", "df", "du", "free",
        "ps", "top", "env", "printenv", "echo", "printf", "which", "type", "command",
        "grep", "egrep", "fgrep", "rg", "find", "fd", "sort", "uniq", "cut", "tr",
        "sed", "awk", "jq", "xargs", "diff", "cmp", "md5sum", "sha256sum",
        "history", "alias", "git", "tree", "readlink", "realpath", "basename", "dirname",
        "test", "[", "true", "false", "sleep", "seq", "bc", "expr", "toybox",
    )

    private val mutatingCommands = setOf(
        "rm", "rmdir", "mv", "cp", "mkdir", "touch", "ln", "chmod", "chown", "chgrp",
        "truncate", "dd", "tee", "install", "rsync", "scp", "sftp", "tar", "zip", "unzip",
        "gzip", "gunzip", "bzip2", "xz", "zstd", "pkg", "apt", "apt-get", "apk", "pip",
        "pip3", "npm", "yarn", "pnpm", "gem", "cargo", "go", "make", "cmake", "gcc", "cc",
        "clang", "python", "python3", "node", "deno", "curl", "wget", "nc", "ncat", "socat",
        "ssh", "ssh-keygen", "systemctl", "service", "crontab", "at", "nohup", "setsid",
        "kill", "pkill", "killall", "reboot", "shutdown", "pm", "am", "cmd", "settings",
        "svc", "input", "screencap", "screenrecord", "logcat", "su",
    )

    private val blockedPatterns: List<Triple<Regex, String, String>> = listOf(
        Triple(
            Regex("""\brm\s+(-[a-zA-Z]*[rR][a-zA-Z]*\s+)+.*"""),
            "递归删除",
            "递归删除会连同子目录一起抹掉，通常无法恢复",
        ),
        Triple(
            Regex("""\brm\s+(-[a-zA-Z]*[rR][a-zA-Z]*\s+)*/(\s|$)"""),
            "删除根目录",
            "删除根目录会破坏整个文件系统",
        ),
        Triple(
            Regex("""\brm\s+(-[a-zA-Z]*[rR][a-zA-Z]*\s+)*~(\s|/|$)"""),
            "删除家目录",
            "删除家目录会丢失全部个人文件",
        ),
        Triple(
            Regex("""\brm\s+(-[a-zA-Z]*[rR][a-zA-Z]*\s+)*\*(\s|$)"""),
            "通配符递归删除",
            "对所有文件执行递归删除，风险不可控",
        ),
        Triple(
            Regex("""\bdd\b[^|;]*\bof\s*=\s*/dev/(block|sd|mmcblk|nvme)"""),
            "覆写块设备",
            "直接写块设备会不可逆地破坏分区数据",
        ),
        Triple(
            Regex("""\bmkfs(\.[a-z0-9]+)?\b"""),
            "格式化文件系统",
            "格式化会清空目标分区",
        ),
        Triple(
            Regex("""\b(fdisk|parted|sgdisk)\b"""),
            "修改分区表",
            "改动分区表可能导致设备无法启动",
        ),
        Triple(
            Regex("""\bchmod\s+(-[a-zA-Z]+\s+)*(777|a\+rwx)\s+/\s*$"""),
            "放开根目录权限",
            "把根目录设为全局可写会破坏系统安全模型",
        ),
        Triple(
            Regex("""\b(curl|wget)\b[^|]*\|\s*(sudo\s+)?(ba|z|k|da)?sh\b"""),
            "下载即执行",
            "把网络内容直接交给 shell 执行，无法审查内容",
        ),
        Triple(
            Regex("""\b(shutdown|reboot|halt|poweroff)\b"""),
            "关机/重启设备",
            "会中断所有正在运行的任务",
        ),
        Triple(
            Regex("""\b(format|wipe)\b"""),
            "擦除数据",
            "擦除操作不可逆",
        ),
        Triple(
            Regex(""":\(\)\s*\{.*\}\s*;\s*:"""),
            "fork 炸弹",
            "会导致系统资源耗尽、设备卡死",
        ),
        Triple(
            Regex("""\bmv\b[^|;]*\s/dev/null\s*$"""),
            "覆盖到空设备",
            "会把文件内容丢弃",
        ),
        Triple(
            Regex("""\bsu\b(\s|$)|\bsudo\b(\s|$)"""),
            "提权执行",
            "提权命令不会被执行",
        ),
    )

    private val confirmBoosters: List<Pair<Regex, String>> = listOf(
        Regex("""\brm\b""") to "删除文件",
        Regex("""\bmv\b""") to "移动/重命名文件",
        Regex("""\b(chmod|chown|chgrp)\b""") to "修改权限或属主",
        Regex("""\b(pip|pip3|npm|yarn|pnpm|apt|apt-get|apk|pkg)\b""") to "安装或卸载软件包",
        Regex("""\b(kill|pkill|killall)\b""") to "结束进程",
        Regex("""\b(pm|am|cmd|settings|svc|input|screencap|screenrecord)\b""") to "调用系统管理命令",
        Regex("""\b(reboot|shutdown)\b""") to "设备电源操作",
        Regex(""">\s*[^|&\s]""") to "重定向写入文件",
        Regex("""\bgit\s+(push|reset|clean|checkout\s+--)\b""") to "可能丢失改动的 git 操作",
    )

    /** 按连接符切段。 */
    fun splitSegments(command: String): List<String> =
        command.split(Regex("""(?:&&|\|\||;|\||\n)"""))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    private fun firstWord(segment: String): String {
        // 跳过 VAR=value 前缀，取真正的命令首词（去路径、小写）
        val tokens = segment.trim().split(Regex("""\s+"""))
        var idx = 0
        while (idx < tokens.size && Regex("""^[A-Za-z_][A-Za-z0-9_]*=""").containsMatchIn(tokens[idx])) idx++
        return tokens.getOrNull(idx)?.substringAfterLast('/')?.lowercase() ?: ""
    }

    private fun extractTargets(command: String): List<String> =
        Regex("""(?:^|\s)(/[\w./\-~*]+)""").findAll(command)
            .map { it.groupValues[1] }
            .distinct()
            .take(8)
            .toList()

    /** 决策入口。 */
    fun decide(command: String): Decision {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) return Decision(Level.SAFE, "空命令")

        for ((regex, name, why) in blockedPatterns) {
            if (regex.containsMatchIn(trimmed)) {
                return Decision(
                    level = Level.BLOCKED,
                    reason = "$name：$why。已拒绝执行。",
                    matchedRule = name,
                    targets = extractTargets(trimmed),
                )
            }
        }

        val segments = splitSegments(trimmed)
        var needConfirm = false
        var confirmReason: String? = null
        val targets = mutableListOf<String>()

        for (seg in segments) {
            val head = firstWord(seg)
            if (head.isEmpty()) continue

            if (head in mutatingCommands && head !in readOnlyCommands) {
                needConfirm = true
                confirmReason = confirmReason ?: "包含会修改系统的命令（$head）"
            }
            for ((regex, why) in confirmBoosters) {
                if (regex.containsMatchIn(seg)) {
                    needConfirm = true
                    confirmReason = confirmReason ?: why
                }
            }
            targets += extractTargets(seg)

            if (head !in readOnlyCommands && head !in mutatingCommands) {
                needConfirm = true
                confirmReason = confirmReason ?: "无法识别的命令（$head），为安全起见需要确认"
            }
        }

        return if (needConfirm) {
            Decision(
                level = Level.CONFIRM,
                reason = confirmReason ?: "该命令可能修改系统状态",
                targets = targets.distinct(),
            )
        } else {
            Decision(Level.SAFE, "只读命令，可直接执行", targets = targets.distinct())
        }
    }

    /** 中文分级说明。 */
    fun describe(decision: Decision): String = when (decision.level) {
        Level.SAFE -> "安全（无需确认）"
        Level.CONFIRM -> "需要确认：${decision.reason}"
        Level.BLOCKED -> "已拒绝：${decision.reason}"
    }
}
