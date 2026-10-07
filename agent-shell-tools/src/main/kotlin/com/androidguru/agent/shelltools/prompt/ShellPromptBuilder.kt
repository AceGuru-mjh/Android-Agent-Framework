package com.androidguru.agent.shelltools.prompt

import com.androidguru.agent.shell.ShellRuntime
import com.androidguru.agent.shell.runtime.EnvironmentProbe
import java.io.File

/**
 * 终端 Agent 的系统提示词构建器 —— yl-ai `ToolRegistry.systemPrompt` 的移植。
 *
 * 结构与设计要点全部保留：
 * - 角色定位：把需求办成的执行者，不是聊天伙伴；
 * - **能力优先级 7 条**（终端命令 > Termux 环境 > 容器 > 后台作业 > 手机能力 > 文件操作 > 原始写入），
 *   其中「terminal_exec 超时几乎总是该用 job_start 的信号」是实战换来的经验句；
 * - **环境注入**：EnvironmentProbe 的「可用 / 确认不存在」三段式清单 ——
 *   防止模型对缺失命令瞎试浪费 token；
 * - **应用自带工具**：yl-ai AgentLoop.environmentNotes 的「应用自带工具」行
 *   （AgentLoop.kt L607-617）同样保留 —— 模型不知道宿主链了哪些可执行文件，
 *   显式列出后才会优先用它们而不是瞎试；
 * - 安全边界与输出要求。
 */
object ShellPromptBuilder {

    /** 工作目录、shell、环境探测清单 → 完整 system prompt。 */
    fun build(
        runtime: ShellRuntime,
        additionalContext: String? = null,
    ): String {
        val cwd = runtime.sessions.active?.currentDirectory ?: runtime.environment.home.absolutePath
        val shell = runtime.environment.defaultShell()
        val report = runtime.probe.probe()
        val bundled = bundledTools(runtime)

        return buildString {
            appendLine("你是一个运行在终端环境里的 AI 助手，通过工具调用替用户把需求办成。")
            appendLine("你不是聊天伙伴：能用工具解决的问题不要只给口头建议。")
            appendLine()
            appendLine("## 能力优先级（按顺序选择）")
            appendLine("1. terminal_exec：日常命令的首选；在用户看得见的共享终端里执行，命令与输出用户都能核对。")
            appendLine("2. termux_exec：Termux 环境里的 apt / bash 工具链（已安装时启动开销比容器低，日常命令行任务优先）。")
            appendLine("3. container_exec：需要 Alpine + root + apk 的场景（容器文件系统独立，适合装包实验）。")
            appendLine("4. job_start：长任务（起服务器 / 大文件下载 / 长编译）。**terminal_exec 超时几乎总是该用 job_start 的信号** —— 超时后进程状态会丢，而作业输出落盘、随时可查。")
            appendLine("5. 手机 / 设备能力：app_list、app_launch、open_url、clipboard、device_info。")
            appendLine("6. 文件操作：fs_read / fs_write / fs_list / file_delete（写与删都要用户确认）。")
            appendLine("7. terminal_write：只用于应答交互式程序（确认提示、密码）；不要用它替代 terminal_exec。")
            appendLine()
            appendLine("## 工作方式")
            appendLine("- 用退出码判断成败；失败时读错误输出，改命令再试，不要原样重复。")
            appendLine("- 命令保持短小、可读；不要拼超长 one-liner。")
            appendLine("- 先探测再动手：不确定环境里有什么时，先 ls / command -v 确认。")
            appendLine("- 需要用户拍板时，用 task_finish 汇总现状与选项，等用户回复。")
            appendLine("- 同一条命令连续失败两次：换思路，不要试第三次。")
            appendLine()
            appendLine("## 当前环境")
            appendLine("- 工作目录：$cwd")
            appendLine("- shell：$shell")
            if (bundled.isNotEmpty()) {
                appendLine("- 应用自带工具：" + bundled.joinToString("、") + "（已链接进 PATH，可直接调用）")
            }
            appendLine(report.forPrompt())
            additionalContext?.takeIf { it.isNotBlank() }?.let {
                appendLine(it)
                appendLine()
            }
            appendLine("## 安全边界")
            appendLine("- 不执行破坏数据的命令（递归删除、格式化、覆写块设备会被硬拦截）。")
            appendLine("- 不尝试提权（su / sudo 会被硬拦截）。")
            appendLine("- 不把用户的文件内容、密钥、隐私信息发送到任何外部地址。")
            appendLine()
            appendLine("## 输出要求")
            appendLine("- 全程使用中文。")
            appendLine("- 任务完成或无法推进时，调用 task_finish 记录结论，然后直接输出最终总结文本。")
        }
    }

    /** 环境变化后（容器 / Termux 环境装好、装了新软件）的增量上下文。 */
    fun environmentNotes(runtime: ShellRuntime): String {
        val report = runtime.probe.probe()
        return buildString {
            appendLine("### 环境探测（刷新）")
            appendLine(report.forPrompt())
            if (runtime.termux.isInstalled) {
                appendLine("- Termux 环境已安装（termux_exec 可用；apt / bash 工具链）。")
            } else {
                appendLine("- Termux 环境未安装：termux_exec 会返回安装指引。")
            }
            if (runtime.container.isReady) {
                appendLine("- Alpine 容器已安装（container_exec 可用；容器内 apk 可装包）。")
            } else {
                appendLine("- Alpine 容器未安装：container_exec 会返回安装指引。")
            }
        }
    }

    /** 供宿主把探测报告嵌入自定义提示词。 */
    fun probeNotes(probe: EnvironmentProbe): String = probe.probe().forPrompt()

    /**
     * 应用自带工具清单（yl-ai `RuntimeLocator.bundledExecutables()` 的框架等价数据源）。
     *
     * 框架里没有同名属性：[com.androidguru.agent.shell.runtime.ShellEnvironment]
     * 的 `installBundledExecutables()` 把宿主链入的 lib*.so 可执行体（bash、proot 等）
     * 以短名符号链接进 `prefix/bin` —— 该目录就是「自带工具」的权威清单。
     * 目录为空（纯 JVM 宿主未链入任何工具）时返回空列表，提示词不输出该行。
     */
    fun bundledTools(runtime: ShellRuntime): List<String> =
        File(runtime.environment.prefix, "bin").listFiles()
            ?.filter { it.isFile }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()
}
