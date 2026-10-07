package com.androidguru.agent.shell.terminal

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

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
 *
 * ## 严格标记校验（修复 issue #16：巧合吞内容）
 *
 * 旧实现在 MARK 之后**任意距离**找 `__`，输出里出现 `__AGSH_`（如 Python 双下划线
 * 内容）且后续任意位置有 `__` 时，中间内容会被整体删除。现在要求完整标记形态
 * `__AGSH_<id字母数字>_BEGIN__ / _END__(:<exitCode>)?` 在 MARK 处**紧邻**匹配，
 * 不匹配则按普通文本放行，只可能把真正的半截标记扣回缓冲。
 *
 * ## UTF-8 增量解码（修复 issue #12：多字节字符跨 chunk 损坏）
 *
 * 用有状态 [CharsetDecoder] 解码，跨 chunk 被切开的多字节字符由解码器保存状态，
 * 下一个 chunk 到达后补齐，不再产生 U+FFFD。
 */
class SentinelStripper {

    private val pending = StringBuilder()

    private val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPLACE)
        .onUnmappableCharacter(CodingErrorAction.REPLACE)

    @Synchronized
    fun strip(chunk: ByteArray): ByteArray {
        if (chunk.isEmpty()) return chunk

        val text = pending.toString() + decodeChunk(chunk)
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

            val match = MARKER_FULL.matchAt(text, mark)
            if (match == null) {
                // 不是完整标记：若是合法的半截前缀则扣回等待闭合
                if (isMarkerPrefix(text.substring(mark))) {
                    val remainder = text.substring(mark)
                    if (remainder.length <= MAX_PENDING * 4) {
                        pending.append(remainder)
                    } else {
                        // 超长保护：疑似标记段迟迟不闭合，直接放行避免渲染流卡死
                        out.append(remainder)
                    }
                    break
                }
                // 巧合文本（如 `__AGSH_` 后跟非标记内容）：只放行 MARK 本身，不吞后续内容
                out.append(MARK)
                i = mark + MARK.length
                continue
            }

            var end = match.range.last + 1

            // 匹配恰好在 `:`/`-`/数字上被文本边界截断：退出码可能未到齐，扣回等待
            if (end == text.length && isTruncatedExitTail(text, match.range)) {
                pending.append(text, mark, text.length)
                break
            }

            if (end < text.length && text[end] == '\n') end++
            i = end
        }

        val cleaned = out.toString().replace(Regex("\n{3,}"), "\n\n")
        return cleaned.toByteArray(Charsets.UTF_8)
    }

    private fun decodeChunk(chunk: ByteArray): String {
        val cb = CharBuffer.allocate(chunk.size + 1)
        decoder.decode(ByteBuffer.wrap(chunk), cb, false)
        cb.flip()
        return cb.toString()
    }

    /**
     * `s` 是否是某个完整标记的**前缀**（即可能随后续 chunk 闭合成真标记）。
     * 形态：`__AGSH_` 的任意前缀 / `__AGSH_<字母数字>*` / 再加 `_BEGIN__`|`_END__:` 的任意前缀。
     */
    private fun isMarkerPrefix(s: String): Boolean {
        if (s.length <= MARK.length) return MARK.startsWith(s)
        if (!s.startsWith(MARK)) return false
        val rest = s.substring(MARK.length)
        var k = 0
        while (k < rest.length && rest[k].isLetterOrDigit()) k++
        if (k == rest.length) return true // id 未闭合
        val tail = rest.substring(k)
        return MARK_SUFFIXES.any { it.startsWith(tail) }
    }

    /** 匹配是否结束在退出码尾巴中间（`:12|34` 之类），需要等下一个 chunk。 */
    private fun isTruncatedExitTail(text: String, range: IntRange): Boolean {
        val last = text[range.last]
        if (last.isDigit()) return text.substring(range.first).contains("_END__:")
        if (last == ':' || last == '-') return text.substring(range.first).contains("_END__")
        return false
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
        decoder.reset()
    }

    companion object {
        /** 哨兵标记前缀（Android Guru SHell）。 */
        const val MARK = "__AGSH_"

        private const val MAX_PENDING = 64

        /** 完整标记形态：`__AGSH_<id>_BEGIN__` / `__AGSH_<id>_END__` / `__AGSH_<id>_END__:<code>`。 */
        private val MARKER_FULL = Regex("__AGSH_[0-9a-zA-Z]+_(BEGIN|END)__(:-?\\d+)?")

        private val MARK_SUFFIXES = listOf("_BEGIN__", "_END__")
    }
}
