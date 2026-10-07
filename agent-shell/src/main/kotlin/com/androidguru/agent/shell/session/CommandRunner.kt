package com.androidguru.agent.shell.session

import com.androidguru.agent.shell.terminal.AnsiStripper
import com.androidguru.agent.shell.terminal.SentinelProtocol
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.ArrayDeque
import kotlin.coroutines.resume

/**
 * 哨兵式命令执行器 —— 把一条命令注入共享 shell 并精确取回「退出码 + 输出」。
 *
 * 与 yl-ai 原实现的差异（全面优化点）：
 * - 使用修复版 [SentinelProtocol]（BEGIN 前置 / `$?` 立即暂存 / 累计缓冲解析），
 *   解决「取不到输出、退出码恒 0、跨 chunk 解析失败」三个缺陷；
 * - `cd` 目标解析内聚在本类（原实现在 AgentLoop 里做），会话 [ShellSession.currentDirectory]
 *   保持与真实 shell 同步；
 * - 每会话缓存一个 runner（原实现由上层 Map 管理）。
 *
 * 并发纪律：[Mutex] 保证同一会话内命令严格串行 —— 共享终端里两条命令交错写入
 * 会把哨兵输出搅在一起。
 */
class CommandRunner(private val session: ShellSession) {

    private val mutex = Mutex()

    private val recentCommands = ArrayDeque<String>()
    private val recentLock = Any()

    /**
     * 执行一条命令。
     *
     * @param command 命令文本（可以是多行脚本）
     * @param timeoutMs 超时；超时后返回 [CommandResult.timedOut] = true，进程不终止
     * @param maxOutputChars 输出预算（头尾保留式截断）
     */
    suspend fun run(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        maxOutputChars: Int = DEFAULT_MAX_OUTPUT,
    ): CommandResult = mutex.withLock {
        val id = SentinelProtocol.newId()
        val payload = SentinelProtocol.buildPayload(command, id)

        // 修复 issue #11：markBusy / 写入 / 收集协程全部纳入 try/finally 保护 ——
        // 旧实现里 session.write 抛异常（shell 已死时管道写必抛）会导致
        // busy 永久卡死 + 收集协程泄漏
        val collector = OutputCollector(id)
        var collectJob: Job? = null
        try {
            session.markBusy(true)
            session.clearTail()

            collectJob = CoroutineScope(Dispatchers.IO).launch {
                session.rawOutput.collect { chunk -> collector.feed(chunk) }
            }

            // 等订阅生效，防止 SharedFlow 订阅延迟吃掉首块输出（yl-ai 踩过的坑）
            val subscribeDeadline = System.currentTimeMillis() + 3_000
            while (session.subscriberCount == 0 && System.currentTimeMillis() < subscribeDeadline) {
                delay(10)
            }

            val started = System.currentTimeMillis()
            session.write(payload)

            var shellDied = false
            var exitCode: Int? = try {
                withTimeout(timeoutMs) { collector.awaitEnd() }
            } catch (te: TimeoutCancellationException) {
                // 哨兵未到。若 shell 本身已被命令杀掉（exit / exec / 崩溃），
                // 退出码从通道上取 —— 这是哨兵协议在「命令杀死 shell」场景的唯一出路
                if (!session.isAlive) {
                    shellDied = true
                    session.waitForExit(2_000)
                } else {
                    null
                }
            } finally {
                collectJob?.cancel()
            }

            val durationMs = System.currentTimeMillis() - started
            val raw = collector.text()
            val timedOut = exitCode == null && !shellDied

            // 修复点：从累计缓冲提取 [BEGIN, END) 区间 + 精确退出码
            val extraction = SentinelProtocol.extract(raw, id)
            val body = extraction?.rawBody ?: raw // 超时等异常场景退化为原始输出
            val deEchoed = SentinelProtocol.stripEchoedCommand(body, command)
            val clean = AnsiStripper.clean(deEchoed)
            val budget = maxOutputChars.coerceAtLeast(1000)
            val truncated = AnsiStripper.truncate(
                clean,
                headChars = budget / 3,
                tailChars = budget - budget / 3,
            )

            trackRecent(command)
            trackCd(command)

            CommandResult(
                command = command,
                exitCode = extraction?.exitCode ?: exitCode ?: -1,
                stdout = truncated.text,
                truncated = truncated.truncated,
                omittedChars = truncated.omittedChars,
                durationMs = durationMs,
                timedOut = timedOut,
            )
        } finally {
            collectJob?.cancel()
            session.markBusy(false)
        }
    }

    /** 近期是否重复执行过同一条命令（防死循环空转）。 */
    fun repeatedCommandCount(command: String): Int = synchronized(recentLock) {
        recentCommands.count { it == command.trim() }
    }

    private fun trackRecent(command: String) = synchronized(recentLock) {
        recentCommands.addLast(command.trim())
        if (recentCommands.size > 20) recentCommands.removeFirst()
    }

    /** `cd` 前缀命令：解析目标并同步会话 cwd（相对路径基于会话当前目录）。 */
    private fun trackCd(command: String) {
        val trimmed = command.trim()
        val firstLine = trimmed.lineSequence().firstOrNull()?.trim() ?: return
        if (!firstLine.startsWith("cd ")) return
        val target = firstLine.removePrefix("cd ").trim().trim('"', '\'')
        if (target.isEmpty()) return
        val expanded = if (target == "~") {
            System.getProperty("user.home")
        } else if (target.startsWith("~/")) {
            System.getProperty("user.home") + target.removePrefix("~")
        } else {
            target
        }
        val dir = File(expanded)
        val resolved = if (dir.isAbsolute) dir else File(session.currentDirectory, expanded)
        if (resolved.isDirectory) session.setCurrentDirectory(resolved.absolutePath)
    }

    /**
     * 输出收集器：累计原始输出并解析哨兵。
     *
     * ## 增量解析（修复 issue #19：每次 feed 全量重扫的 O(n²) 卡顿）
     *
     * 直接在 [StringBuilder] 上用 `indexOf(text, fromIndex)` 增量搜索：
     * - BEGIN 找到后位置稳定（缓冲只追加），缓存 [beginIdx] 不再重搜；
     * - END 搜索从上次游标继续（带跨界回看窗口），每个字节至多被扫一次。
     *
     * ## UTF-8 增量解码（修复 issue #12：多字节字符跨 chunk 损坏）
     *
     * 用有状态 CharsetDecoder 解码，被读边界切开的多字节字符由解码器保存状态补齐。
     */
    private class OutputCollector(private val id: String) {
        private val sb = StringBuilder()
        private val lock = Any()
        private var waiter: CancellableContinuation<Int>? = null

        private val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)

        private var beginIdx = -1
        private var beginScanFrom = 0
        private var endScanFrom = 0

        fun feed(chunk: ByteArray) {
            synchronized(lock) {
                sb.append(decodeChunk(chunk))
                tryParseExit()?.let { code ->
                    waiter?.let { w -> if (w.isActive) w.resume(code) }
                    waiter = null
                }
            }
        }

        private fun decodeChunk(chunk: ByteArray): String {
            val cb = java.nio.CharBuffer.allocate(chunk.size + 1)
            decoder.decode(java.nio.ByteBuffer.wrap(chunk), cb, false)
            cb.flip()
            return cb.toString()
        }

        /** 增量在累计缓冲里找 BEGIN + END 标记 + `:<exitCode>`。 */
        private fun tryParseExit(): Int? {
            if (beginIdx < 0) {
                val beginNeedle = "\n" + SentinelProtocol.beginMarker(id)
                val idx = sb.indexOf(beginNeedle, beginScanFrom)
                if (idx < 0) {
                    beginScanFrom = (sb.length - beginNeedle.length + 1).coerceAtLeast(0)
                    return null
                }
                beginIdx = idx + 1
                endScanFrom = beginIdx
            }

            val endNeedle = "\n" + SentinelProtocol.endMarker(id)
            var from = (endScanFrom - endNeedle.length + 1).coerceAtLeast(beginIdx)
            while (true) {
                val idx = sb.indexOf(endNeedle, from)
                if (idx < 0) {
                    endScanFrom = (sb.length - endNeedle.length + 1).coerceAtLeast(beginIdx)
                    return null
                }
                val code = SentinelProtocol.parseExitCodeTail(sb, idx + endNeedle.length)
                if (code != null) return code
                from = idx + 1
            }
        }

        suspend fun awaitEnd(): Int = suspendCancellableCoroutine { cont ->
            synchronized(lock) {
                val done = tryParseExit()
                if (done != null) {
                    cont.resume(done)
                } else {
                    waiter = cont
                    cont.invokeOnCancellation { synchronized(lock) { waiter = null } }
                }
            }
        }

        fun text(): String = synchronized(lock) { sb.toString() }
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 30_000L
        const val DEFAULT_MAX_OUTPUT = 8_000
    }
}

/** 一次哨兵式命令执行的结果。 */
data class CommandResult(
    val command: String,
    val exitCode: Int,
    val stdout: String,
    val truncated: Boolean,
    val omittedChars: Int,
    val durationMs: Long,
    val timedOut: Boolean,
) {
    val success: Boolean get() = exitCode == 0 && !timedOut

    /** 面向模型的文本渲染（中文、带退出码与截断提示）。 */
    fun toToolText(): String = buildString {
        appendLine("命令: $command")
        appendLine("退出码: " + if (timedOut) "超时未结束（可能进入了交互式程序）" else exitCode.toString())
        appendLine("耗时: ${durationMs}ms")
        if (truncated) appendLine("注意: 输出过长，已省略 $omittedChars 个字符")
        appendLine("输出:")
        append(stdout.ifBlank { "(无输出)" })
    }
}
