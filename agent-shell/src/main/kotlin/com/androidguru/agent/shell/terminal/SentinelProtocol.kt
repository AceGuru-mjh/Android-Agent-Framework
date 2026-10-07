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
     *
     * ## 行首锚定（修复 issue #8：PTY 回显开启时协议永不匹配）
     *
     * 真 PTY（回显开启）会把写入的 payload 原样回显，标记在回显行里**先于**真实输出
     * 出现（如 `printf '\n__AGSH_<id>_BEGIN__\n'`，标记前面是引号而非换行）。
     * 旧实现取第一个匹配 → 锚定到回显上，且回显 END 后跟字面量 `:%s`，
     * 退出码正则永不匹配 → 每条命令都超时。
     *
     * 真实标记由 printf 输出，**必然行首出现**（前面是换行，或在缓冲开头）；
     * 回显里的标记前面永远是普通字符。因此：
     * - BEGIN 取**最后一个**「行首」出现（真实输出永远在回显之后）；
     * - END 从 body 起点向后找**最后一个**「行首 + 紧跟 :<数字>」的出现。
     */
    fun extract(cumulative: String, id: String): Extraction? {
        val begin = beginMarker(id)
        val end = endMarker(id)

        // BEGIN：最后一个「行首」出现（idx==0 或前面是 \n）
        var b = -1
        var searchFrom = 0
        while (true) {
            val idx = cumulative.indexOf(begin, searchFrom)
            if (idx < 0) break
            if (idx == 0 || cumulative[idx - 1] == '\n') b = idx
            searchFrom = idx + 1
        }
        if (b < 0) return null
        val bodyStart = b + begin.length

        // END：最后一个「行首 + :<exitCode>」出现
        var e = -1
        var exitCode = -1
        searchFrom = bodyStart
        while (true) {
            val idx = cumulative.indexOf(end, searchFrom)
            if (idx < 0) break
            if (idx > b && (idx == 0 || cumulative[idx - 1] == '\n')) {
                parseExitCodeTail(cumulative, idx + end.length)?.let { code ->
                    e = idx
                    exitCode = code
                }
            }
            searchFrom = idx + 1
        }
        if (e < 0) return null

        val rawBody = cumulative.substring(bodyStart, e)

        // 过滤哨兵回显行与 payload 注入行（PTY / set -v 会把写入的命令文本回显）
        // 修复 issue #21 L-2：不再用宽匹配的 "printf '" 过滤（会误杀 grep 结果），
        // 只滤含当前哨兵 id 的行 —— 行首锚定后回显行本已基本落在 body 之外
        val body = rawBody.lineSequence()
            .filterNot { it.contains(begin) || it.contains(end) }
            .filterNot { it.trim() == "__agsh_rc=$?" }
            .joinToString("\n")

        return Extraction(exitCode = exitCode, rawBody = body)
    }

    /**
     * 解析 END 标记后紧邻的 `:<digits>`（不匹配返回 null）。
     *
     * 手工解析而非正则：热路径（每个输出 chunk 一次）上不再重复编译/匹配正则
     * （修复 issue #19 的正则部分）。供 [extract] 与 CommandRunner 的增量解析复用。
     */
    fun parseExitCodeTail(text: CharSequence, start: Int): Int? {
        if (start >= text.length || text[start] != ':') return null
        var j = start + 1
        var negative = false
        if (j < text.length && (text[j] == '-' || text[j] == '+')) {
            negative = text[j] == '-'
            j++
        }
        if (j >= text.length || !text[j].isDigit()) return null
        var value = 0
        while (j < text.length && text[j].isDigit()) {
            value = value * 10 + (text[j] - '0')
            j++
        }
        return if (negative) -value else value
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
