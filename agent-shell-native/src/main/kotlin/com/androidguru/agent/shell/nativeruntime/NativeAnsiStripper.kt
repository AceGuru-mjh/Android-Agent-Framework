package com.androidguru.agent.shell.nativeruntime

/**
 * 原生 ANSI 清洗 —— 对应 agent-shell 的 AnsiStripper（JVM 版），
 * 大块终端输出（LLM 输入预处理热路径）走 C++ 状态机批量处理。
 *
 * 两个实现逐字节等价（CI 中有对照测试），选择建议：
 * - 单条消息 < 几 KB：JVM 版即可（无 JNI 往返开销）；
 * - 连续输出流 / 高频清洗：本实现（单次 JNI 调用处理整个缓冲区）。
 */
object NativeAnsiStripper {

    /** 原生库是否可用。 */
    fun available(): Boolean = NativeBridge.isLoaded()

    /** 删除全部 ANSI 转义序列、`\r`、`\b` 与 BEL（UTF-8 安全）。 */
    fun strip(input: String): String {
        if (!available() || input.isEmpty()) return input
        val out = NativeBridge.nativeStripAnsi(input.toByteArray(Charsets.UTF_8))
        return String(out, Charsets.UTF_8)
    }

    /** 字节流入口（连续流式清洗）。 */
    fun strip(input: ByteArray): ByteArray {
        if (!available() || input.isEmpty()) return input
        return NativeBridge.nativeStripAnsi(input)
    }
}
