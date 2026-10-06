package com.androidguru.agent.shell.terminal

/**
 * 哨兵标记清洗器（有状态，按会话持有）。
 *
 * 渲染流会混入本框架注入的 `__AGSH_` 哨兵标记；渲染器（终端 UI / 日志）不应看到它们。
 * 本类从字节流中删除完整哨兵标记（含 `:<exitCode>` 尾巴与尾随换行），并处理跨 chunk
 * 被读边界切开的半截标记。
 *
 * 数据流分工（双流模型）：
 * - 解析者订阅 **原始流**（哨兵完整保留，供 [CommandRunner] 提取退出码）；
 * - 渲染者订阅 **清洗流**（本类处理后的输出）。
 */
class SentinelStripper {

    private val pending = StringBuilder()

    @Synchronized
    fun strip(chunk: ByteArray): ByteArray {
        if (chunk.isEmpty()) return chunk

        val text = pending.toString() + String(chunk, Charsets.UTF_8)
        pending.setLength(0)

        val out = StringBuilder(text.length)
        var i = 0
        while (i < text.length) {

            val mark = text.indexOf(MARK, i)
            if (mark < 0) {
                // 结尾可能是被切开的标记前缀：扣回 pending 等下一个 chunk 拼接
                val tailLen = longestSuffixThatIsMarkerPrefix(text, i)
                if (tailLen > 0) {
                    out.append(text, i, text.length - tailLen)
                    pending.append(text, text.length - tailLen, text.length)
                } else {
                    out.append(text, i, text.length)
                }
                break
            }

            out.append(text, i, mark)

            // MARK 之后必须紧跟 "__" 才是完整哨兵；否则视为普通文本里的巧合前缀
            val afterMark = text.indexOf("__", mark + MARK.length)
            if (afterMark < 0) {
                val remainder = text.substring(mark)
                if (remainder.length <= MAX_PENDING * 4) {
                    pending.append(remainder)
                } else {
                    // 超长保护：疑似标记段过短却迟迟不闭合，直接放行避免渲染流卡死
                    out.append(remainder)
                }
                break
            }

            var end = afterMark + 2

            // 消费可选的 ":<digits>" 退出码尾巴
            if (end < text.length && text[end] == ':') {
                var j = end + 1
                while (j < text.length && (text[j].isDigit() || text[j] == '-')) j++
                if (j > end + 1) end = j
            }

            if (end < text.length && text[end] == '\n') end++
            i = end
        }

        val cleaned = out.toString().replace(Regex("\n{3,}"), "\n\n")
        return cleaned.toByteArray(Charsets.UTF_8)
    }

    private fun longestSuffixThatIsMarkerPrefix(text: String, from: Int): Int {
        val maxLen = minOf(MARK.length - 1, text.length - from)
        for (len in maxLen downTo 1) {
            val suffix = text.substring(text.length - len)
            if (MARK.startsWith(suffix)) return len
        }
        return 0
    }

    @Synchronized
    fun reset() {
        pending.setLength(0)
    }

    companion object {
        /** 哨兵标记前缀（Android Guru SHell）。 */
        const val MARK = "__AGSH_"

        private const val MAX_PENDING = 64
    }
}
