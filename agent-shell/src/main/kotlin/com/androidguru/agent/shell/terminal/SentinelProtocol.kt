package com.androidguru.agent.shell.terminal

import java.util.UUID

/**
 * 哨兵协议 —— 在**共享的交互式 shell** 里精确捕获「一条命令的退出码与输出」。
 *
 * ## 与 yl-ai 原始实现的差异（本次移植的关键修复）
 *
 * 原实现（dev.aiterm）把 BEGIN 放在命令**之后**：
 *
 * ```sh
 * <command>
 * printf '\n__AITERM_<id>_BEGIN__\n'
 * printf '__AITERM_<id>_END__:%s\n' "$?"
 * ```
 *
 * 存在四个缺陷：
 * 1. 命令输出发生在 BEGIN **之前**，`[BEGIN, END)` 区间里取不到命令输出；
 * 2. `$?` 紧跟第一条 printf 之后展开，捕获到的是 **printf 自己的状态（恒 0）**；
 * 3. 退出码解析只在「当前 chunk」里找 END 标记，哨兵被 PTY 读边界切开时解析失败；
 * 4. echo 开关关闭时命令根本不会被写入（调用方必须永远传 true，参数形同虚设）。
 *
 * 本实现改为 **BEGIN 前置 + 显式暂存退出码 + 累计缓冲解析**：
 *
 * ```sh
 * printf '\n__AGSH_<id>_BEGIN__\n'
 * <command>
 * __agsh_rc=$?
 * printf '__AGSH_<id>_END__:%s\n' "$__agsh_rc"
 * ```
 *
 * - BEGIN 在命令之前 → `[BEGIN, END)` 区间恰好是「命令回显（若有）+ 命令输出」；
 * - `$?` 由 `__agsh_rc=$?` 在命令结束后**立即**捕获，不受后续语句影响；
 * - 解析基于累计缓冲（调用方拼接全部原始输出）而非单 chunk，天然跨 chunk 安全；
 * - 输出预算由 [AnsiStripper.truncate] 统一头尾保留式截断。
 */
object SentinelProtocol {

    /** 哨兵 id：每条命令随机生成，避免命令输出里的巧合内容被误认为标记。 */
    fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)

    fun beginMarker(id: String): String = "${SentinelStripper.MARK}${id}_BEGIN__"

    fun endMarker(id: String): String = "${SentinelStripper.MARK}${id}_END__"

    /**
     * 构造写入共享 shell 的完整 payload（4 行，一次性写入）。
     *
     * 在真实 PTY 上，shell 会把写入的命令文本回显给用户 —— 这正是「共享终端」想要的
     * 效果：用户能看到 AI 执行了什么。非交互通道（管道）没有回显，输出同样正确。
     */
    fun buildPayload(command: String, id: String): String = buildString {
        append("printf '\\n").append(beginMarker(id)).append("\\n'\n")
        append(command).append('\n')
        append("__agsh_rc=$?\n")
        append("printf '").append(endMarker(id)).append(":%s\\n' \"\$__agsh_rc\"\n")
    }

    /**
     * 从累计原始文本中提取命令体与退出码。
     *
     * 返回 null 表示 END 标记（或其退出码尾巴）尚未到齐 —— 调用方继续收集输出后再试。
     * 跨 chunk 安全：调用方应把写入 payload 之后收到的**全部**原始输出累计进来再调用。
     */
    fun extract(cumulative: String, id: String): Extraction? {
        val begin = beginMarker(id)
        val end = endMarker(id)
        val b = cumulative.indexOf(begin)
        if (b < 0) return null

        val e = cumulative.indexOf(end, b + begin.length)
        if (e < 0) return null

        // END 标记后必须紧跟 :<exitCode>（同一行），否则视为标记未到齐
        val afterEnd = cumulative.substring(e + end.length)
        val codeMatch = Regex("^:(-?\\d+)").find(afterEnd) ?: return null
        val exitCode = codeMatch.groupValues[1].toIntOrNull() ?: -1

        val rawBody = cumulative.substring(b + begin.length, e)

        // 过滤哨兵回显行与 payload 注入行（PTY 会把写入的命令文本原样回显）
        val body = rawBody.lineSequence()
            .filterNot { it.contains(begin) || it.contains(end) || it.contains("printf '") }
            .filterNot { it.trim() == "__agsh_rc=$?" }
            .joinToString("\n")

        return Extraction(exitCode = exitCode, rawBody = body)
    }

    /**
     * 尽力去掉 PTY 回显的命令首行（与命令文本的干净形态比对）。
     * 非交互通道（管道）没有回显，此函数原样返回。
     */
    fun stripEchoedCommand(rawBody: String, command: String): String {
        val firstLine = command.trim().lineSequence().firstOrNull() ?: return rawBody
        if (firstLine.isEmpty()) return rawBody
        val lines = rawBody.split("\n")
        if (lines.isEmpty()) return rawBody
        // 跳过前导空行（BEGIN 标记后的换行残留在 body 开头），再与命令首行比对
        var start = 0
        while (start < lines.size && lines[start].replace("\r", "").trim().isEmpty()) start++
        if (start < lines.size && lines[start].replace("\r", "").trim() == firstLine.replace("\r", "").trim()) {
            return lines.drop(start + 1).joinToString("\n")
        }
        return rawBody
    }

    data class Extraction(
        /** 命令退出码（由 `__agsh_rc=$?` 精确捕获）。 */
        val exitCode: Int,
        /** [BEGIN, END) 区间的原始输出（未清洗 ANSI）。 */
        val rawBody: String,
    )
}
