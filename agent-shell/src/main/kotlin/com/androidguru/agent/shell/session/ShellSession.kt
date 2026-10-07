package com.androidguru.agent.shell.session

import com.androidguru.agent.shell.process.ProcessChannel
import com.androidguru.agent.shell.process.ProcessChannelFactory
import com.androidguru.agent.shell.runtime.ShellEnvironment
import com.androidguru.agent.shell.terminal.AnsiStripper
import com.androidguru.agent.shell.terminal.SentinelStripper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * 终端会话 —— 用户与 AI 共用的同一条通道。
 *
 * 双流输出模型（移植自 yl-ai 并保留其关键工程决策）：
 * - **原始流** [rawOutput]：未经哨兵清洗，[CommandRunner] 在这里提取退出码；
 * - **清洗流** [output]：经 [SentinelStripper] 去除哨兵标记，供渲染者消费。
 *
 * 渲染缓冲：渲染器未就绪时，清洗后的 chunk 进 [pending] 缓冲（上限 512KB，超限丢最旧），
 * [onRendererReady] 时一次性回放 —— 「渲染器未就绪时的输出不会丢」。
 *
 * 尾部环：[cleanTail] 保留最近 64KB 清洗文本，供 `terminal_write` 后的上下文读取与
 * 会话检查点快照。
 */
class ShellSession(
    val id: String,
    val title: String,
    private val environment: ShellEnvironment,
    private val channelFactory: ProcessChannelFactory,
    private val rows: Int = 30,
    private val cols: Int = 100,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var channel: ProcessChannel? = null
    private var pumpJob: Job? = null

    private val _rawOutput = MutableSharedFlow<ByteArray>(
        replay = RAW_REPLAY_CHUNKS,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val rawOutput: SharedFlow<ByteArray> = _rawOutput.asSharedFlow()

    private val stripper = SentinelStripper()

    private val _output = MutableSharedFlow<ByteArray>(
        replay = REPLAY_CHUNKS,
        extraBufferCapacity = 512,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val output: SharedFlow<ByteArray> = _output.asSharedFlow()

    /** 原始流订阅者数量（[CommandRunner] 启动收集前用它确认订阅已生效）。 */
    val subscriberCount: Int get() = _rawOutput.subscriptionCount.value

    private val pendingLock = Any()
    private val pending = ArrayDeque<ByteArray>()
    private var pendingBytes = 0
    private var rendererAttached = false

    /** 渲染器就绪：返回缓冲快照并清空 replay 缓存（避免重复渲染）。 */
    fun onRendererReady(): List<ByteArray> = synchronized(pendingLock) {
        rendererAttached = true
        val snapshot = pending.toList()
        pending.clear()
        pendingBytes = 0
        _output.resetReplayCache()
        snapshot
    }

    fun onRendererDetached() = synchronized(pendingLock) {
        rendererAttached = false
    }

    /** 推送回放文本（会话恢复横幅等）。 */
    fun pushReplay(text: String) {
        if (text.isEmpty()) return
        emitOrBuffer(text.toByteArray(Charsets.UTF_8))
    }

    private val _state = MutableStateFlow(SessionState.STARTING)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val cleanTail = ArrayDeque<Char>()
    private val rawTail = ArrayDeque<Char>()
    private val tailLock = Any()

    val bytesIn = AtomicLong()
    val bytesOut = AtomicLong()

    @Volatile
    var currentDirectory: String = environment.home.absolutePath
        private set

    /**
     * 启动默认登录 shell。不传 program 时使用 [ShellEnvironment.defaultShell]。
     */
    fun start(
        program: String? = null,
        argv: List<String> = emptyList(),
        cwd: String? = environment.home.absolutePath,
        extraEnv: Map<String, String> = emptyMap(),
    ) {
        val exe = program ?: environment.defaultShell()
        val args = if (program == null && argv.isEmpty()) emptyList() else argv
        cwd?.let { currentDirectory = it }
        val proc = channelFactory.open(
            program = exe,
            argv = args,
            env = environment.buildEnv(extraEnv),
            cwd = cwd?.let(::File),
            rows = rows,
            cols = cols,
        )
        attach(proc)
    }

    /**
     * 挂接已打开的通道（容器 / Termux / 宿主自定义启动器场景）。
     */
    fun attachChannel(proc: ProcessChannel) {
        attach(proc)
    }

    private fun attach(proc: ProcessChannel) {
        check(channel == null) { "会话已启动，不可重复 attach" }
        channel = proc
        proc.resize(rows, cols)
        _state.value = SessionState.RUNNING

        pumpJob = scope.launch {
            val buf = ByteArray(8192)
            var total = 0L
            var idlePolls = 0
            var deadPolls = 0
            while (isActive) {
                val n = try {
                    proc.read(buf)
                } catch (t: Throwable) {
                    // 修复 issue #21 L-3：close() 已置 CLOSED 时不覆盖为 ERROR
                    if (_state.value != SessionState.CLOSED) _state.value = SessionState.ERROR
                    break
                }
                if (n > 0) {
                    val chunk = buf.copyOf(n)
                    total += n
                    bytesOut.addAndGet(n.toLong())
                    appendRawTail(chunk)
                    emitOrBuffer(chunk)
                    idlePolls = 0
                    deadPolls = 0
                } else if (n < 0) {
                    // 通道明确关闭（PTY EOF）
                    break
                } else {
                    // 无数据：管道实现需要靠进程存活状态判定结束
                    idlePolls++
                    if (!proc.isAlive()) {
                        deadPolls++
                        // 连续多轮确认死亡且无新数据 → 结束（容忍内核缓冲的尾部数据）
                        if (deadPolls > 8) break
                    } else {
                        deadPolls = 0
                    }
                    delay(12)
                }
            }
            val code = runCatching { proc.waitFor(500) }.getOrDefault(-1)
            // 修复 issue #21 L-3：close() 先置 CLOSED 时，pump 收尾不得覆盖终态、
            // 也不得再触发 onExit（旧实现会把 CLOSED 改写回 EXITED）
            if (_state.value != SessionState.CLOSED) {
                _state.value = SessionState.EXITED
                onExit?.invoke(code)
            }
            scope.cancel()
        }
    }

    private var onExit: ((Int) -> Unit)? = null

    fun setOnExit(listener: (Int) -> Unit) {
        onExit = listener
    }

    private fun emitOrBuffer(chunk: ByteArray) {
        // 先发原始流（解析者），再清洗进渲染流（渲染者）
        _rawOutput.tryEmit(chunk)
        appendRawTail(chunk)

        // 修复 issue #21 L-12：cleanTail 改存哨兵剥离后的文本 ——
        // 旧实现只做 ANSI 清洗，__AGSH_* 内部标记会泄漏进 tailText /
        // 控制 API session.tail / 会话检查点快照
        val cleaned = stripper.strip(chunk)
        appendCleanTail(cleaned)
        if (cleaned.isEmpty()) return

        synchronized(pendingLock) {
            if (!rendererAttached) {
                pending.addLast(cleaned)
                pendingBytes += cleaned.size
                while (pendingBytes > MAX_PENDING_BYTES && pending.isNotEmpty()) {
                    pendingBytes -= pending.removeFirst().size
                }
            }
        }

        _output.tryEmit(cleaned)
    }

    // 修复 issue #12：尾部环用有状态解码器增量解码，
    // 避免多字节字符被读边界切开时产生 U+FFFD 污染检查点与控制 API
    private val rawTailDecoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(java.nio.charset.CodingErrorAction.REPLACE)
        .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPLACE)

    private fun appendRawTail(chunk: ByteArray) {
        val cb = java.nio.CharBuffer.allocate(chunk.size + 1)
        rawTailDecoder.decode(java.nio.ByteBuffer.wrap(chunk), cb, false)
        cb.flip()
        val text = cb.toString()
        if (text.isEmpty()) return
        synchronized(tailLock) {
            for (ch in text) {
                rawTail.addLast(ch)
                if (rawTail.size > MAX_CLEAN_TAIL) rawTail.removeFirst()
            }
        }
    }

    private fun appendCleanTail(cleaned: ByteArray) {
        // cleaned 已由 SentinelStripper 从完整解码文本重新编码，不会出现半个多字节字符
        val text = AnsiStripper.clean(String(cleaned, Charsets.UTF_8))
        if (text.isEmpty()) return
        synchronized(tailLock) {
            for (ch in text) {
                cleanTail.addLast(ch)
                if (cleanTail.size > MAX_CLEAN_TAIL) cleanTail.removeFirst()
            }
        }
    }

    fun write(text: String) {
        val bytes = text.toByteArray(Charsets.UTF_8)
        channel?.write(bytes)
        bytesIn.addAndGet(bytes.size.toLong())
    }

    fun write(bytes: ByteArray) {
        channel?.write(bytes)
        bytesIn.addAndGet(bytes.size.toLong())
    }

    /** 发送 Ctrl-C（0x03）。真实 PTY 上送达前台进程组；管道通道退化为无操作。 */
    fun interrupt() {
        write(byteArrayOf(0x03))
    }

    fun resize(rows: Int, cols: Int) {
        channel?.resize(rows, cols)
    }

    fun markBusy(busy: Boolean) {
        _busy.value = busy
    }

    /** 清洗后的尾部文本（最多 [maxChars] 字符）。 */
    fun tailText(maxChars: Int = MAX_CLEAN_TAIL): String = synchronized(tailLock) {
        val joined = StringBuilder().append(cleanTail.toCharArray()).toString()
        if (joined.length <= maxChars) joined else joined.takeLast(maxChars)
    }

    /** 原始尾部文本（调试用）。 */
    fun rawTailText(maxChars: Int = 4000): String = synchronized(tailLock) {
        val joined = StringBuilder().append(rawTail.toCharArray()).toString()
        if (joined.length <= maxChars) joined else joined.takeLast(maxChars)
    }

    fun clearTail() = synchronized(tailLock) {
        cleanTail.clear()
        rawTail.clear()
    }

    fun setCurrentDirectory(dir: String) {
        currentDirectory = dir
    }

    val isAlive: Boolean get() = channel?.isAlive() == true

    /**
     * 等待会话进程结束并取真实退出码。
     *
     * 用途：命令含 `exit` / `exec` 时会杀掉共享 shell 本身，哨兵永远不会到 ——
     * 此时退出码只能从通道（shell 进程）上取。返回 null = 未在超时内结束或已丢失。
     */
    fun waitForExit(timeoutMs: Long = 2_000): Int? {
        val ch = channel ?: return null
        val code = runCatching { ch.waitFor(timeoutMs) }.getOrDefault(-1)
        return code.takeIf { it >= 0 }
    }

    fun close() {
        pumpJob?.cancel()
        runCatching { channel?.close() }
        channel = null
        _state.value = SessionState.CLOSED
        scope.cancel()
    }

    companion object {
        /** 清洗尾部环上限（64KB）。 */
        const val MAX_CLEAN_TAIL = 64 * 1024

        /** 渲染缓冲上限（512KB）。 */
        const val MAX_PENDING_BYTES = 512 * 1024

        const val REPLAY_CHUNKS = 256
        const val RAW_REPLAY_CHUNKS = 32
    }
}

/** 会话状态。 */
enum class SessionState { STARTING, RUNNING, EXITED, ERROR, CLOSED }
