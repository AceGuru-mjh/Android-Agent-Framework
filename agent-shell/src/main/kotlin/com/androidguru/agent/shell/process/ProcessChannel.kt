package com.androidguru.agent.shell.process

import java.io.Closeable
import java.io.File

/**
 * 进程通道抽象 —— 框架与子进程之间的全部 I/O 面。
 *
 * 这是把 yl-ai 的 JNI PTY 层（`forkpty` + `login_tty`）抽象为纯 JVM SPI 的关键接口：
 *
 * - **框架默认实现** [JvmProcessChannelFactory]：基于 `ProcessBuilder` 的管道通道，
 *   在任何 JVM（服务端 / 桌面 / CI / Android）上零依赖可用，覆盖哨兵执行、作业、容器
 *   的全部非交互场景；
 * - **宿主 PTY 实现**：Android 宿主可注入基于 `forkpty` 的真 PTY 通道（保持 yl-ai 的
 *   交互语义：ssh / vim / top 可用、Ctrl-C 送达前台进程组、窗口尺寸可调）。
 *
 * [read] 返回值语义与 POSIX 非阻塞读对齐：`>0` 数据、`0` 暂无数据（EAGAIN）、
 * `-1` 流已关闭。引擎的泵循环用 `0` 触发短暂轮询等待。
 */
interface ProcessChannel : Closeable {

    /** 读取输出；`>0` 数据、`0` 暂无数据、`-1` 已关闭。 */
    fun read(buffer: ByteArray): Int

    /** 写入输入。 */
    fun write(bytes: ByteArray, offset: Int = 0, length: Int = bytes.size)

    /** 调整终端窗口尺寸（PTY 实现生效；管道实现为空操作）。 */
    fun resize(rows: Int, cols: Int) {}

    /**
     * 等待子进程结束。返回退出码；`-1` 错误（如进程未知）；`-2` 超时。
     */
    fun waitFor(timeoutMs: Long): Int

    /** 发送 POSIX 信号编号（2=SIGINT、15=SIGTERM、9=SIGKILL）。 */
    fun signal(signalNumber: Int)

    /** 进程是否仍存活。 */
    fun isAlive(): Boolean

    companion object {
        const val SIGINT = 2
        const val SIGTERM = 15
        const val SIGKILL = 9
    }
}

/**
 * 通道工厂。宿主可注入自定义实现（真 PTY / 沙箱启动器 / 容器 runtime 等）。
 */
fun interface ProcessChannelFactory {

    fun open(
        program: String,
        argv: List<String>,
        env: Map<String, String>,
        cwd: File?,
        rows: Int,
        cols: Int,
    ): ProcessChannel
}

/**
 * 基于 [ProcessBuilder] 的默认通道实现（纯 JVM，无平台依赖）。
 *
 * 语义说明：
 * - stderr 合并进 stdout（PTY 天然合并两侧输出，管道通道保持一致）；
 * - [signal]：SIGINT / SIGTERM → `destroy()`，SIGKILL → `destroyForcibly()`
 *   （无真实 TTY 时无法送达 Ctrl-C 字节，这是管道通道的固有限制，文档已注明）；
 * - [resize] 为空操作（没有 TTY 尺寸概念）。
 */
class JvmProcessChannel(private val process: Process) : ProcessChannel {

    private val input = process.inputStream
    private val output = process.outputStream

    override fun read(buffer: ByteArray): Int {
        // 严格非阻塞：available()>0 才读，否则返回 0（EAGAIN 语义）。
        // 「进程已死」的 EOF 判定交给泵循环用 [isAlive] / [waitFor] 完成 ——
        // 若在管道仍被孙进程持有时尝试阻塞读，会永久卡死（yl-ai 原生层用 EAGAIN 规避了同类问题）。
        val available = runCatching { input.available() }.getOrDefault(0)
        if (available <= 0) return 0
        return input.read(buffer, 0, minOf(buffer.size, available))
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        output.write(bytes, offset, length)
        output.flush()
    }

    override fun waitFor(timeoutMs: Long): Int = try {
        if (process.waitFor(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)) process.exitValue() else -2
    } catch (e: Exception) {
        -1
    }

    override fun signal(signalNumber: Int) {
        when (signalNumber) {
            ProcessChannel.SIGKILL -> process.destroyForcibly()
            else -> process.destroy()
        }
    }

    override fun isAlive(): Boolean = process.isAlive

    override fun close() {
        runCatching { output.flush() }
        runCatching { output.close() }
        if (process.isAlive) {
            process.destroy()
            if (!process.waitFor(300, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                process.destroyForcibly()
            }
        }
        runCatching { input.close() }
    }
}

/**
 * 默认工厂：[ProcessBuilder] 拉起子进程。
 */
class JvmProcessChannelFactory : ProcessChannelFactory {

    override fun open(
        program: String,
        argv: List<String>,
        env: Map<String, String>,
        cwd: File?,
        rows: Int,
        cols: Int,
    ): ProcessChannel {
        val command = ArrayList<String>(1 + argv.size)
        command.add(program)
        command.addAll(argv)

        val builder = ProcessBuilder(command)
        builder.redirectErrorStream(true) // PTY 天然合并 stdout/stderr，管道通道保持一致
        if (cwd != null) builder.directory(cwd)
        env.forEach { (k, v) -> builder.environment()[k] = v }

        val process = builder.start()
        return JvmProcessChannel(process)
    }
}
