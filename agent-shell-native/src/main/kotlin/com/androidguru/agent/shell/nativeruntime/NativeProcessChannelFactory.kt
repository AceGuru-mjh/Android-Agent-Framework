package com.androidguru.agent.shell.nativeruntime

import com.androidguru.agent.shell.process.ProcessChannel
import com.androidguru.agent.shell.process.ProcessChannelFactory
import java.io.File

/**
 * 原生 PTY 通道工厂 —— [ProcessChannelFactory] 的 forkpty 实现。
 *
 * 这是 agent-shell 「宿主注入 PTY」注入点的官方参考实现：
 *
 * ```
 * val factory = NativeProcessChannelFactory.createIfAvailable()
 *     ?: JvmProcessChannelFactory()          // 无原生库时优雅降级（纯 JVM 管道）
 * val runtime = ShellRuntime.create(baseDir, channelFactory = factory)
 * ```
 *
 * Android 宿主接入：把 CI 构建的 `libagsh_native.so`（arm64-v8a）放进
 * `app/src/main/jniLibs/arm64-v8a/`，AGP 会自动打包并解压到 nativeLibraryDir，
 * `System.loadLibrary("agsh_native")` 即可命中 —— 与 yl-ai 打包 libproot.so 的
 * 方式一致（Android 只允许 execve nativeLibraryDir 下的文件）。
 *
 * 桌面宿主接入：`-Djava.library.path=<dir-of-libagsh_native.so>` 或
 * `-Dagsh.native.lib=/abs/path/libagsh_native.so`。
 */
class NativeProcessChannelFactory private constructor() : ProcessChannelFactory {

    override fun open(
        program: String,
        argv: List<String>,
        env: Map<String, String>,
        cwd: File?,
        rows: Int,
        cols: Int,
    ): ProcessChannel {
        val handle = NativeBridge.spawn(program, argv, env, cwd, rows, cols)
        return NativePtyChannel(handle.masterFd, handle.pid)
    }

    companion object {

        /** 原生库是否可用（加载一次，结果缓存）。 */
        @JvmStatic
        fun isAvailable(): Boolean = NativeBridge.isLoaded()

        /**
         * 可用则返回实例，不可用返回 null —— 宿主用它做一行式降级：
         * `NativeProcessChannelFactory.createIfAvailable() ?: JvmProcessChannelFactory()`
         */
        @JvmStatic
        fun createIfAvailable(): NativeProcessChannelFactory? =
            if (isAvailable()) NativeProcessChannelFactory() else null

        /** 强制获取（不可用时抛 IllegalStateException，含修复指引）。 */
        @JvmStatic
        fun create(): NativeProcessChannelFactory = createIfAvailable()
            ?: error(
                "agsh_native 原生库不可用：Android 请将 libagsh_native.so 放入 jniLibs/" +
                    "arm64-v8a；桌面请设置 java.library.path 或 agsh.native.lib",
            )
    }
}
