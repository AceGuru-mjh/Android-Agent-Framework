package com.androidguru.agent.shell.nativeruntime

import com.androidguru.agent.shell.process.ProcessChannel
import java.io.IOException

/**
 * 原生 PTY 通道 —— [ProcessChannel] 的 forkpty 实现。
 *
 * 语义与框架的 JvmProcessChannel 完全兼容（read 的 0/-1 约定、waitFor 的
 * -1/-2 约定），差别在于：
 * - stdout/stderr 天然合并（PTY 行规程），交互式程序（vim/top/ssh）可用；
 * - [signal] 通过 kill(2) 送达子进程（Ctrl-C = SIGINT 送达整个前台进程组）；
 * - [resize] 通过 TIOCSWINSZ 真实生效。
 *
 * 对齐 yl-ai PtyProcess 的生命周期约定：close 时 SIGTERM → 300ms → SIGKILL，
 * 最后关 master fd 并尽力 reap（不留僵尸）。
 */
class NativePtyChannel internal constructor(
    private val masterFd: Int,
    private val pid: Int,
) : ProcessChannel {

    override fun read(buffer: ByteArray): Int = NativeBridge.nativeRead(masterFd, buffer)

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        var written = 0
        while (written < length) {
            val n = NativeBridge.nativeWrite(masterFd, bytes, offset + written, length - written)
            if (n < 0) throw IOException("PTY 写入失败（errno=${-n}）")
            written += n
        }
    }

    override fun resize(rows: Int, cols: Int) {
        NativeBridge.nativeResize(masterFd, rows, cols)
    }

    override fun waitFor(timeoutMs: Long): Int =
        NativeBridge.nativeWaitFor(pid, timeoutMs.coerceIn(0, Int.MAX_VALUE.toLong()).toInt())

    override fun signal(signalNumber: Int) {
        NativeBridge.nativeSignal(pid, signalNumber)
    }

    override fun isAlive(): Boolean = NativeBridge.nativeIsAlive(pid)

    override fun close() {
        // 优雅终止 → 300ms 宽限 → SIGKILL（对齐 yl-ai PtyProcess.close）
        if (isAlive()) {
            signal(ProcessChannel.SIGTERM)
            val deadline = System.currentTimeMillis() + 300
            while (System.currentTimeMillis() < deadline && isAlive()) {
                Thread.sleep(20)
            }
            if (isAlive()) signal(ProcessChannel.SIGKILL)
        }
        NativeBridge.nativeCloseChild(masterFd, pid) // 关 fd + 尽力 reap（防僵尸）
    }

    /** 诊断信息。 */
    override fun toString(): String = "NativePtyChannel(masterFd=$masterFd, pid=$pid)"
}
