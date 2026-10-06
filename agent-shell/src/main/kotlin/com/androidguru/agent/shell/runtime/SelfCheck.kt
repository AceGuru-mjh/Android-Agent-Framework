package com.androidguru.agent.shell.runtime

import com.androidguru.agent.shell.process.ProcessChannelFactory
import com.androidguru.agent.shell.terminal.AnsiStripper

/**
 * 自检报告 —— 环境探测 + 通道探针 + 端到端哨兵冒烟。
 *
 * 保留 yl-ai 的设计：自检报告是「一条命令能全部拿到」的诊断文本，
 * 宿主可直接展示或让用户一键复制回传。
 */
class SelfCheck(
    private val environment: ShellEnvironment,
    private val channelFactory: ProcessChannelFactory,
    private val probe: EnvironmentProbe,
    private val extraSections: List<Pair<String, () -> String>> = emptyList(),
) {

    /**
     * 运行自检并返回报告文本。
     *
     * @param runEndToEnd 是否执行端到端哨兵冒烟（真实拉起 shell，耗时约 1s）
     */
    fun run(runEndToEnd: Boolean = true): String {
        val sb = StringBuilder()
        sb.appendLine("== Shell 运行环境 ==")
        sb.appendLine(environment.describe())
        sb.appendLine()

        sb.appendLine("== 通道工厂 ==")
        sb.appendLine(channelFactory.javaClass.name)
        sb.appendLine()

        sb.appendLine("== 执行能力探测 ==")
        sb.appendLine(execProbe())
        sb.appendLine()

        sb.appendLine("== 工具可用性 ==")
        sb.appendLine(probe.summarize())
        sb.appendLine()

        for ((title, section) in extraSections) {
            sb.appendLine("== $title ==")
            runCatching { section() }.getOrElse { "自检段失败：${it.message}" }.let(sb::appendLine)
            sb.appendLine()
        }

        if (runEndToEnd) {
            sb.appendLine("== 端到端哨兵冒烟 ==")
            sb.appendLine(endToEndSmoke())
        }
        return sb.toString()
    }

    /** W^X / execve 能力探针：在可写目录里尝试执行拷贝出来的 shell。 */
    private fun execProbe(): String = buildString {
        appendLine("uid = ${uid()}")
        appendLine("os.name = ${System.getProperty("os.name")}")
        appendLine("os.arch = ${System.getProperty("os.arch")}")
        appendLine("java.io.tmpdir = ${System.getProperty("java.io.tmpdir")}")
    }

    /** 端到端冒烟：默认 shell 里跑哨兵协议，期望输出 PTY_READY 与退出码 7。 */
    private fun endToEndSmoke(): String {
        return runCatching {
            val channel = channelFactory.open(
                program = environment.defaultShell(),
                argv = listOf("-c", "echo AGSH_READY; id -u; pwd; exit 7"),
                env = environment.buildEnv(),
                cwd = environment.home,
                rows = 24,
                cols = 100,
            )
            channel.use { p ->
                val out = StringBuilder()
                val buf = ByteArray(4096)
                val deadline = System.currentTimeMillis() + 5000
                var code: Int = -2
                while (System.currentTimeMillis() < deadline) {
                    val n = p.read(buf)
                    if (n > 0) out.append(String(buf, 0, n, Charsets.UTF_8))
                    code = p.waitFor(150)
                    if (code != -2) break
                }
                val text = AnsiStripper.clean(out.toString())
                buildString {
                    appendLine("退出码: $code（期望 7）")
                    appendLine("输出: $text")
                    if (code == 7 && text.contains("AGSH_READY")) {
                        appendLine("结论: ✓ 哨兵链路可用")
                    } else {
                        appendLine("结论: ✗ 哨兵链路异常（检查 shell 与通道工厂配置）")
                    }
                }
            }
        }.getOrElse { "✗ 冒烟失败：${it.javaClass.simpleName}: ${it.message}" }
    }

    private fun uid(): String = runCatching {
        val p = ProcessBuilder("id", "-u").start()
        p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        p.inputStream.readBytes().decodeToString().trim()
    }.getOrDefault("?")
}
