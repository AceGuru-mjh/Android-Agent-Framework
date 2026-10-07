package com.androidguru.agent.core.engine

/**
 * 工具调用循环护栏（长程任务实测高频失败模式：模型对同一工具重复发起**完全相同**的调用）。
 *
 * 机制：在最近 [windowSize] 次调用的滑动窗口内统计「工具名 + 归一化参数」签名，
 * 达到 [threshold] 次时引擎发出 [AgentEvent.LoopDetected]，并在回填给模型的
 * 工具结果上附加建议文本，促使模型改变策略或求助。
 *
 * 设计要点：
 * - **只统计、不阻断** —— 判定「是否真的死循环」交给模型/宿主，护栏只保证信息可见；
 * - 参数做轻量归一化（去首尾空白），避免因空白差异漏判；
 * - 线程安全（引擎主循环内调用，但实现上仍保证并发安全）。
 */
class ToolCallLoopDetector(
    private val windowSize: Int = 12,
    /** 触发阈值（同签名窗口内出现次数）。 */
    val threshold: Int = 3,
) {

    private val window = ArrayDeque<String>()
    private val lock = Any()

    /**
     * 记录一次调用，返回该签名在当前窗口内的累计出现次数。
     * （到达 [threshold] 及其整数倍时调用方应触发告警事件。）
     */
    fun observe(toolName: String, arguments: String): Int {
        val signature = "$toolName\u0000${arguments.trim()}"
        synchronized(lock) {
            window.addLast(signature)
            while (window.size > windowSize) window.removeFirst()
            return window.count { it == signature }
        }
    }

    /** 重置窗口（新任务开始时）。 */
    fun reset() = synchronized(lock) {
        window.clear()
    }

    /** 建议注入文本：仅在触发阈值时使用。 */
    fun advisoryText(repeatedCount: Int): String =
        "\n[loop-guard] 注意：本次调用与窗口内最近的调用完全相同（第 $repeatedCount 次）。" +
            "你似乎在重复同一操作。请重新审视任务状态：换一种方法、调整参数，或用 ask_user 向用户求助。" +
            "继续原样重复大概率仍会得到相同结果。"
}
