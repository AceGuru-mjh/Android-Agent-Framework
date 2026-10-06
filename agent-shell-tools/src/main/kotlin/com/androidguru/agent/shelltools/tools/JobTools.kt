package com.androidguru.agent.shelltools.tools

import com.androidguru.agent.shell.ShellRuntime
import com.androidguru.agent.tools.AgentTool
import com.androidguru.agent.tools.ToolCategory
import com.androidguru.agent.tools.ToolErrorCode
import com.androidguru.agent.tools.ToolMetadata
import com.androidguru.agent.tools.ToolRequest
import com.androidguru.agent.tools.ToolResult
import com.androidguru.agent.tools.ToolAnnotations
import com.androidguru.agent.tools.ToolSchema
import com.androidguru.agent.shelltools.JsonArgs
import com.androidguru.agent.shelltools.ShellToolSet
import kotlinx.coroutines.delay

/**
 * 后台作业四件套：job_start / job_list / job_get / job_stop。
 *
 * 作业的生命周期模型是「**启动-记账-随时查看**」：job_start 立即返回 id，
 * 模型用 job_get 轮询 / 等待 / 查日志。job_start 的命令同样走 CommandPolicy
 * 审批（审批钩子统一处理）—— **作业不是绕过策略的后门**。
 */
object JobTools {

    fun start(runtime: ShellRuntime): AgentTool = object : AgentTool {
        override val id = ShellToolSet.ToolIds.JOB_START
        override val description =
            "启动一个后台作业（长任务：起开发服务器、下载大文件、长编译）。" +
                "立即返回作业 id，不等待完成；输出实时落盘。任务需要在「本次工具调用」内" +
                "完成时才用 terminal_exec —— **terminal_exec 超时几乎总是该用 job_start 的信号**。"
        override val parameters = ToolSchema.build {
            string("command", "要后台执行的命令", required = true)
            string("workdir", "工作目录（默认主目录）")
            string("purpose", "这个作业的目的（一句话，展示给用户）")
        }
        override val metadata = ToolMetadata(
            id = id, category = ToolCategory.SYSTEM, risk = ToolRiskType,
            annotations = ToolAnnotations(openWorldHint = true),
            tags = setOf("job", "background"),
        )

        override suspend fun execute(request: ToolRequest): ToolResult {
            val args = JsonArgs.parse(request.arguments)
            val command = args.str("command")?.trim().orEmpty()
            if (command.isEmpty()) return ToolResult.invalid("command", "command 不能为空")
            val job = runtime.startJob(command, args.str("workdir"))
            runtime.audit.recordSetting("job.start", "id=${job.id} cmd=${command.take(500)}")
            return ToolResult.success(
                "已启动后台作业。\n${job.toToolText(includeOutput = false)}\n\n" +
                    "它不占用用户的终端；用 job_get 查看 / 等待 / 读取输出。",
                data = """{"jobId":"${job.id}"}""",
            )
        }
    }

    fun list(runtime: ShellRuntime): AgentTool = object : AgentTool {
        override val id = ShellToolSet.ToolIds.JOB_LIST
        override val description = "列出全部后台作业（含历史记录；App 重启后作业如实标注为已中断）。"
        override val parameters = ToolSchema.empty()
        override val metadata = ToolMetadata(
            id = id, category = ToolCategory.SYSTEM,
            annotations = ToolAnnotations(readOnlyHint = true),
        )

        override suspend fun execute(request: ToolRequest): ToolResult {
            val jobs = runtime.jobs.list()
            if (jobs.isEmpty()) return ToolResult.success("没有后台作业记录。")
            val text = jobs.take(50).joinToString("\n") { j ->
                "· ${j.id} [${j.state}] ${j.durationMs / 1000}s ${j.command.take(80)}${j.url?.let { " → $it" } ?: ""}"
            }
            return ToolResult.success("共 ${jobs.size} 个作业（新的在前）：\n$text")
        }
    }

    fun get(runtime: ShellRuntime): AgentTool = object : AgentTool {
        override val id = ShellToolSet.ToolIds.JOB_GET
        override val description =
            "查看后台作业状态；可等待至多 60 秒；可读取输出尾部。"
        override val parameters = ToolSchema.build {
            string("id", "作业 id（job_start 返回）", required = true)
            boolean("include_output", "是否读取输出尾部（默认 false）")
            integer("wait_ms", "最多等待多少毫秒（0–60000，默认 0）",
                minimum = 0.0, maximum = 60000.0)
            integer("max_output_chars", "输出上限（默认 4000，范围 500–20000）",
                minimum = 500.0, maximum = 20000.0)
        }
        override val metadata = ToolMetadata(
            id = id, category = ToolCategory.SYSTEM,
            annotations = ToolAnnotations(readOnlyHint = true),
        )

        override suspend fun execute(request: ToolRequest): ToolResult {
            val args = JsonArgs.parse(request.arguments)
            val id = args.str("id") ?: return ToolResult.invalid("id", "缺少 id")
            val waitMs = (args.long("wait_ms") ?: 0L).coerceIn(0, 60_000)
            val includeOutput = args.bool("include_output") ?: false
            val maxChars = (args.int("max_output_chars") ?: 4000).coerceIn(500, 20_000)

            val job = runtime.jobs.get(id)
                ?: return runtime.jobs.getRecord(id)?.let {
                    // 历史记录（进程已不在）：如实告知只能读日志
                    ToolResult.success(
                        "${it.id} [${it.state}]（作业进程已随上一次运行结束，只能读日志）\n" +
                            "命令: ${it.command.take(200)}\n日志: ${it.log}",
                    )
                } ?: ToolResult.failure("作业不存在：$id", ToolErrorCode.NOT_FOUND)

            val deadline = System.currentTimeMillis() + waitMs
            while (job.state == com.androidguru.agent.shell.job.ShellJob.State.RUNNING &&
                System.currentTimeMillis() < deadline
            ) {
                delay(250)
            }
            return ToolResult.success(job.toToolText(includeOutput, maxChars))
        }
    }

    fun stop(runtime: ShellRuntime): AgentTool = object : AgentTool {
        override val id = ShellToolSet.ToolIds.JOB_STOP
        override val description = "停止一个后台作业（Ctrl-C → SIGTERM → 强杀的阶梯）。"
        override val parameters = ToolSchema.build {
            string("id", "作业 id", required = true)
        }
        override val metadata = ToolMetadata(id = id, category = ToolCategory.SYSTEM)

        override suspend fun execute(request: ToolRequest): ToolResult {
            val args = JsonArgs.parse(request.arguments)
            val id = args.str("id") ?: return ToolResult.invalid("id", "缺少 id")
            val job = runtime.jobs.get(id)
                ?: return ToolResult.failure(
                    "作业不存在或已随进程结束（跨进程的旧作业不可操作，只能读日志）：$id",
                    ToolErrorCode.NOT_FOUND,
                )
            job.stop()
            runtime.audit.recordSetting("job.stop", id)
            return ToolResult.success("已停止作业 $id。\n${job.toToolText()}")
        }
    }

    private val ToolRiskType = com.androidguru.agent.tools.ToolRisk.MEDIUM
}
