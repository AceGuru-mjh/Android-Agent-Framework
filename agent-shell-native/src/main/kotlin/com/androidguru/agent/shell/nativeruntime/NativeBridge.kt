package com.androidguru.agent.shell.nativeruntime

import java.io.File
import java.io.IOException

/**
 * agsh_native（C++17）原生桥。
 *
 * 这是 [agent-shell 的 ProcessChannel SPI][com.androidguru.agent.shell.process.ProcessChannel]
 * 的原生实现入口，把 yl-ai 的 JNI 层（pty_jni.c）升级为 C++ 并并入框架：
 *
 * - **PTY**：forkpty + login_tty（交互语义：ssh / vim / top 可用、Ctrl-C 送达前台
 *   进程组、窗口尺寸可调）；
 * - **ANSI 清洗**：字节级状态机（UTF-8 安全），批量剥离转义序列；
 * - **ELF64 补丁**：RUNPATH 清空 / SONAME 改写（PRoot 无 root 适配的核心）。
 *
 * 加载策略（按序尝试，全部失败则 [isLoaded] = false，宿主回退
 * JvmProcessChannelFactory —— 框架功能不受影响）：
 * 1. 系统属性 `agsh.native.lib` 指定的绝对路径（CI 测试 / 调试用）；
 * 2. 常规 `System.loadLibrary("agsh_native")`（Android 打进 APK 的 jniLibs、
 *    Linux 上 `-Djava.library.path=` 均可命中）。
 */
internal object NativeBridge {

    private const val LIB_NAME = "agsh_native"

    private const val STATE_NOT_TRIED = 0
    private const val STATE_LOADED = 1
    private const val STATE_FAILED = 2

    @Volatile
    private var state = STATE_NOT_TRIED

    /** 原生库是否已成功加载（失败会缓存结果，避免重复 UnsatisfiedLinkError 开销）。 */
    @JvmStatic
    fun isLoaded(): Boolean {
        if (state == STATE_LOADED) return true
        if (state == STATE_FAILED) return false
        synchronized(this) {
            if (state != STATE_NOT_TRIED) return state == STATE_LOADED

            // ① 显式路径（优先级最高 —— 测试与宿主指定）
            val explicit = System.getProperty("agsh.native.lib")
            if (!explicit.isNullOrBlank() && runCatching { System.load(explicit) }.isSuccess) {
                state = STATE_LOADED
                return true
            }
            // ② 常规搜索
            if (runCatching { System.loadLibrary(LIB_NAME) }.isSuccess) {
                state = STATE_LOADED
                return true
            }
            state = STATE_FAILED
            return false
        }
    }

    /** [isLoaded] 不为真时直接调用 external 函数会抛 UnsatisfiedLinkError —— 前置守卫。 */
    private fun requireLoaded() {
        check(isLoaded()) { "agsh_native 未加载：请确认 jniLibs / java.library.path，或回退 JvmProcessChannelFactory" }
    }

    // ------------------------------------------------------------------
    // PTY（语义对齐 ProcessChannel：read 0=EAGAIN；waitFor -1 未知 / -2 超时）
    // ------------------------------------------------------------------

    /**
     * forkpty 拉起子进程。
     *
     * @param argv 不含 argv[0] 的参数列表
     * @param env `KEY=VALUE` 形式；为空时继承宿主环境（execve 语义）
     * @return 复合句柄：高 32 位 master fd，低 32 位子进程 pid
     * @throws IOException 原生层失败（错误码见 [SpawnResult] 伴生的错误说明）
     */
    fun spawn(program: String, argv: List<String>, env: Map<String, String>, cwd: File?, rows: Int, cols: Int): SpawnHandle {
        requireLoaded()
        val envArray = env.entries.map { "${it.key}=${it.value}" }.toTypedArray()
        val handle = nativeSpawn(
            program, argv.toTypedArray(), envArray, cwd?.absolutePath, rows, cols,
        )
        if (handle < 0) {
            val err = (-handle).toInt()
            throw IOException(
                when (err) {
                    1 -> "PTY 打开失败（forkpty/openpt）"
                    2 -> "fork 失败（进程数上限？）"
                    3 -> "原生层内存不足"
                    4 -> "参数非法"
                    else -> "原生层错误 $err"
                },
            )
        }
        val fd = (handle ushr 32).toInt()
        val pid = (handle and 0xFFFFFFFFL).toInt()
        return SpawnHandle(fd, pid)
    }

    /** PTY 句柄：master fd + 子进程 pid。 */
    data class SpawnHandle(val masterFd: Int, val pid: Int)

    // 底层 external 入口（供 NativePtyChannel / 测试直接使用）
    external fun nativeSpawn(program: String, argv: Array<String>, env: Array<String>, cwd: String?, rows: Int, cols: Int): Long
    external fun nativeRead(fd: Int, buffer: ByteArray): Int
    external fun nativeWrite(fd: Int, buffer: ByteArray, offset: Int, length: Int): Int
    external fun nativeResize(fd: Int, rows: Int, cols: Int)
    external fun nativeWaitFor(pid: Int, timeoutMs: Int): Int
    external fun nativeSignal(pid: Int, signalNumber: Int)
    external fun nativeIsAlive(pid: Int): Boolean
    external fun nativeClose(fd: Int)
    external fun nativeCloseChild(fd: Int, pid: Int)

    // ------------------------------------------------------------------
    // ANSI 清洗（对应 JVM 版 AnsiStripper；UTF-8 安全）
    // ------------------------------------------------------------------

    external fun nativeStripAnsi(input: ByteArray): ByteArray

    // ------------------------------------------------------------------
    // ELF64 补丁（对应 JVM 版 ElfRunpathPatcher）
    // ------------------------------------------------------------------

    external fun nativeElfReadRunpath(path: String): String?
    external fun nativeElfClearRunpath(path: String): Boolean
    external fun nativeElfReadSoname(path: String): String?
    external fun nativeElfRenameSoname(path: String, newName: String): Boolean

    // ------------------------------------------------------------------
    // 诊断
    // ------------------------------------------------------------------

    /** 后端描述（版本 / uid），供 SelfCheck 输出。 */
    external fun nativeInfo(): String

    init {
        // 类加载时即尝试一次（Android 上 Application 启动线程安全）；
        // 失败不抛 —— isLoaded()/createIfAvailable() 走降级路径。
        isLoaded()
    }
}
