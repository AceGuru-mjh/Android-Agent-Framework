package com.androidguru.agent.shelltools.tools

import com.androidguru.agent.tools.AgentTool
import com.androidguru.agent.tools.ToolRequest
import com.androidguru.agent.tools.ToolResult
import com.androidguru.agent.tools.ToolSchema

/**
 * task_finish —— 任务结论记录。
 *
 * 与 yl-ai 的语义对齐：模型用它显式收尾。在框架中，引擎以「助手不再调用工具、
 * 直接输出总结文本」作为任务完成的信号；本工具负责让模型**先声明结论**，
 * 引擎随后一轮自然收束 —— 拒绝抛异常式强停（那会绕过引擎的 Stop 钩子与
 * 熔断记账，破坏框架语义）。
 */
class TaskFinishTool : AgentTool {

    override val id = "task_finish"
    override val description =
        "记录任务结论并结束任务。任务完成（或确认无法完成）时调用：summary 写清" +
            "做了什么、结果如何；失败时 success=false 并说明原因。调用后请直接输出最终总结文本。"
    override val parameters = ToolSchema.build {
        string("summary", "任务结论（做了什么、结果如何）", required = true)
        boolean("success", "任务是否成功（默认 true）")
    }

    override suspend fun execute(request: ToolRequest): ToolResult {
        val args = com.androidguru.agent.shelltools.JsonArgs.parse(request.arguments)
        val summary = args.str("summary") ?: return ToolResult.invalid("summary", "缺少 summary")
        val success = args.bool("success") ?: true
        return ToolResult.success(
            "已记录任务结论（${if (success) "成功" else "失败"}）：$summary\n" +
                "请现在直接输出最终总结文本，不要再调用任何工具。",
            data = """{"finished":true,"success":$success}""",
        )
    }
}
