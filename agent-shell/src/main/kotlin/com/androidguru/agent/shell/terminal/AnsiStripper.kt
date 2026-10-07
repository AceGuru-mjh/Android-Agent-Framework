package com.androidguru.agent.shell.terminal

/**
 * ANSI 转义序列清理（纯函数，零依赖）。
 *
 * 终端原始输出包含大量 CSI / OSC 控制序列、`\r` 覆盖与 `\b`，直接喂给 LLM 会浪费
 * token 并干扰解析；本工具把原始流清洗为适合模型阅读的纯文本。
 *
 * 能力移植自 yl-ai（AI Terminal）的同名组件，行为与其设备端测试用例对齐。
 */
object AnsiStripper {

    private const val ESC = '\u001B'

    /** 删除全部 ANSI 转义序列、`\r`、`\b` 与 BEL。 */
    fun strip(input: String): String {
        val sb = StringBuilder(input.length)
        var i = 0
        val n = input.length
        while (i < n) {
            val c = input[i]
            when {
                c == ESC -> {
                    i++
                    if (i >= n) break
                    when (input[i]) {
                        '[' -> {
                            // CSI：ESC [ + 0x20..0x3F 参数字节 + 终止字节
                            i++
                            while (i < n && input[i] in ' '..'@') i++
                            if (i < n) i++
                        }
                        ']' -> {
                            // OSC：到 BEL 或 ESC \ 结束
                            i++
                            while (i < n) {
                                if (input[i] == '\u0007') { i++; break }
                                if (input[i] == ESC && i + 1 < n && input[i + 1] == '\\') { i += 2; break }
                                i++
                            }
                        }
                        else -> i++
                    }
                }
                c == '\r' -> i++
                c == '\b' -> i++
                c == '\u0007' -> i++
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    /**
     * 进度条覆盖语义：同一行内多次 `\r` 覆盖时只保留最后一次的内容。
     */
    fun collapseCarriageReturns(input: String): String =
        input.split('\n').joinToString("\n") { line ->
            val idx = line.lastIndexOf('\r')
            if (idx >= 0) line.substring(idx + 1) else line
        }

    /**
     * 完整清洗：折叠覆盖 → 去转义 → 折叠 3+ 连续换行 → 去尾部空白。
     *
     * 顺序说明（PR5 修正）：必须**先**折叠 `\r` 覆盖再剥离 —— [strip] 会删除
     * 全部 `\r`，若顺序颠倒（yl-ai / 早期移植版的 `collapse(strip(x))`），
     * 折叠步骤将永远空转，进度条碎片（`10%50%100%`）会全部混进正文。
     */
    fun clean(input: String): String =
        strip(collapseCarriageReturns(input))
            .replace(Regex("\n{3,}"), "\n\n")
            .trimEnd()

    /**
     * 头尾保留式截断：超长输出保留开头（命令开头信息）与结尾（最终结果），
     * 中间以可读省略标记衔接。
     */
    fun truncate(input: String, headChars: Int = 2000, tailChars: Int = 4000): Truncated {
        if (input.length <= headChars + tailChars) return Truncated(input, false, 0)
        val omitted = input.length - headChars - tailChars
        val head = input.substring(0, headChars)
        val tail = input.substring(input.length - tailChars)
        return Truncated(
            "$head\n\n…[已省略 $omitted 个字符]…\n\n$tail",
            true,
            omitted,
        )
    }

    data class Truncated(val text: String, val truncated: Boolean, val omittedChars: Int)
}
