package com.androidguru.example

import com.androidguru.agent.tools.AgentTool
import com.androidguru.agent.tools.DefaultToolRegistry
import com.androidguru.agent.tools.ToolRequest
import com.androidguru.agent.tools.ToolResult
import com.androidguru.agent.tools.ToolSchema
import com.androidguru.agent.workflow.FileWorkflowRunStore
import com.androidguru.agent.workflow.InMemoryWorkflowLibraryStore
import com.androidguru.agent.workflow.NodeOnError
import com.androidguru.agent.workflow.RunStatus
import com.androidguru.agent.workflow.WorkflowEvent
import com.androidguru.agent.workflow.WorkflowExtractor
import com.androidguru.agent.workflow.WorkflowLibrary
import com.androidguru.agent.workflow.WorkflowSuggester
import com.androidguru.agent.workflow.WorkflowSystem
import com.androidguru.agent.workflow.retry
import com.androidguru.agent.workflow.workflow
import com.androidguru.agent.llm.LlmMessage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path

/**
 * 工作流能力确定性演示（**无需 API Key**）。
 *
 * 场景：一个「渠道日报」工作流 —— 收集指标 → 生成报告 → 按渠道分发，
 * 含：参数化模板、switch 分支路由、节点级重试（指数退避）、失败错误分支、
 * 人工审批节点（暂停-恢复）、崩溃恢复（双引擎接力）、工作流库生命周期
 * （候选 → 激活 → 熔断）、会话蒸馏（宏录制回退）。
 */
fun main(args: Array<String>) = runBlocking {
    val demoOnly = args.isNotEmpty() && args[0] == "--demo"
    if (!demoOnly) {
        println("用法：--demo（确定性演示，无需 API Key）")
        println("本示例为纯演示模式 —— 交互式执行见 examples/long-task-agent 与 memory-agent。\n")
    }

    val workspace = Files.createTempDirectory("workflow-demo")

    // ------------------------------------------------------------------
    // 1. 假工具（确定性；reportflare 工具前 2 次失败演示重试）
    // ------------------------------------------------------------------
    var flareCount = 0
    var gatherCalls = 0
    var renderCalls = 0
    val collectMetrics = object : AgentTool {
        override val id = "collect_metrics"
        override val description = "收集指定日期的运营指标（演示假工具）"
        override val parameters = ToolSchema.build {
            string("date", "报告日期", required = true)
        }
        override suspend fun execute(request: ToolRequest): ToolResult {
            gatherCalls++
            val date = request.arguments.substringAfter("\"date\":\"").substringBefore('"')
            return ToolResult.success(
                "指标已收集（$date）： dau=12000, orders=350, revenue=9800.5",
                data = """{"date":"$date","dau":12000,"orders":350,"revenue":9800.5}""",
            )
        }
    }
    val renderReport = object : AgentTool {
        override val id = "render_report"
        override val description = "把指标 JSON 渲染为报告文本（演示假工具）"
        override val parameters = ToolSchema.build {
            string("data", "指标 JSON", required = true)
            string("date", "日期", required = true)
        }
        override suspend fun execute(request: ToolRequest): ToolResult {
            renderCalls++
            return ToolResult.success("日报（${request.arguments.take(60)}...）：核心指标平稳，营收环比 +3%")
        }
    }
    val postSlack = object : AgentTool {
        override val id = "post_slack"
        override val description = "发 Slack 消息（演示假工具）"
        override val parameters = ToolSchema.build {
            string("text", "消息内容", required = true)
            string("channel", "频道", required = false)
        }
        override suspend fun execute(request: ToolRequest): ToolResult =
            ToolResult.success("已发送到 #ops（演示）")
    }
    val sendEmail = object : AgentTool {
        override val id = "send_email"
        override val description = "发送邮件（演示假工具）"
        override val parameters = ToolSchema.build {
            string("body", "邮件正文", required = true)
            string("to", "收件人", required = true)
        }
        override suspend fun execute(request: ToolRequest): ToolResult =
            ToolResult.success("邮件已发送（演示）")
    }
    val flakyTool = object : AgentTool {
        override val id = "flaky_export"
        override val description = "不稳定导出（前 2 次超时，演示节点级重试）"
        override val parameters = ToolSchema.build { string("target", "导出目标", required = true) }
        override suspend fun execute(request: ToolRequest): ToolResult {
            flareCount++
            return if (flareCount % 3 != 0) {
                ToolResult.failure("导出超时（第 $flareCount 次尝试）", com.androidguru.agent.tools.ToolErrorCode.TIMEOUT)
            } else {
                ToolResult.success("导出成功（第 $flareCount 次尝试）")
            }
        }
    }

    val registry = DefaultToolRegistry().also {
        listOf(collectMetrics, renderReport, postSlack, sendEmail, flakyTool).forEach { t -> it.register(t) }
    }

    // ------------------------------------------------------------------
    // 2. DSL 定义工作流（参数化 + 分支 + 重试 + 人工审批）
    // ------------------------------------------------------------------
    val dailyReport = workflow(
        "daily_report",
        name = "渠道日报",
        description = "收集运营指标生成日报，按渠道分发；可选人工审批",
    ) {
        param("channel", "分发渠道：slack 或 email", required = true)
        param("date", "报告日期", default = "2024-01-01")
        param("recipient", "邮件收件人（channel=email 时必填）", default = "ops@team")

        setVarNode("init", mapOf("date" to "\${params.date}"), "初始化变量")
        toolNode(
            "gather",
            "collect_metrics",
            args = mapOf("date" to "\${vars.date}"),
            description = "收集指标",
            retry = retry(maxAttempts = 3, initialDelayMs = 50),
        )
        toolNode(
            "render",
            "render_report",
            args = mapOf("data" to "\${nodes.gather.output}", "date" to "\${vars.date}"),
            description = "渲染报告",
        )
        humanNode("approve", "报告将发送到 \${params.channel}：\${nodes.render.output} —— 批准发送吗？")
        switchNode("route", "\${params.channel}", cases = mapOf("slack" to "slack", "email" to "email"))

        toolNode("to_slack", "post_slack", args = mapOf("text" to "\${nodes.render.output}"), description = "发 Slack")
        toolNode(
            "to_email",
            "send_email",
            args = mapOf("body" to "\${nodes.render.output}", "to" to "\${params.recipient}"),
            description = "发邮件",
            // 演示失败错误分支：邮件失败 → 降级走 Slack
            onError = NodeOnError.ERROR_BRANCH,
        )
        toolNode(
            "fallback_slack",
            "post_slack",
            args = mapOf("text" to "[降级] 邮件发送失败，日报改走 Slack：\${nodes.render.output}"),
            description = "邮件失败时的降级路径",
        )

        edge(START, "init")
        edge("init" to "gather")
        edge("gather" to "render")
        edge("render" to "approve")
        edge("approve" to "route")
        edge("route", "to_slack", branch = "slack")
        edge("route", "to_email", branch = "email")
        edge("to_email", "fallback_slack", branch = "error")
        edge("to_slack", END)
        edge("fallback_slack", END)
    }

    // ------------------------------------------------------------------
    // 3. 组装 WorkflowSystem（库 + 引擎 + 统计自动接线）
    // ------------------------------------------------------------------
    val system = WorkflowSystem(
        toolRegistry = registry,
        workspace = workspace,
    )
    println("== 3. 工作流入库（候选态） ==")
    system.library.save(dailyReport)
    println("已入库：${dailyReport.id}（${dailyReport.nodes.size} 节点）\n")

    // ------------------------------------------------------------------
    // 4. 执行：human-in-loop（暂停 → 答复 → 恢复）
    // ------------------------------------------------------------------
    println("== 4. 执行（slack 渠道，人工审批节点） ==")
    var runId: String? = null
    system.engine.start(dailyReport, mapOf("channel" to "slack")).collect { event ->
        when (event) {
            is WorkflowEvent.RunStarted -> runId = event.runId
            is WorkflowEvent.NodeStarted -> println("  ▶ ${event.nodeId}（第 ${event.attempt} 次）")
            is WorkflowEvent.NodeCompleted -> println("  ✔ ${event.nodeId} 完成：${event.outputPreview.take(50)}")
            is WorkflowEvent.NodeFailed -> println("  ✘ ${event.nodeId} 失败：${event.error.take(60)}（willRetry=${event.willRetry}）")
            is WorkflowEvent.RunWaiting -> println("  ⏸ 暂停等待人工：${event.prompt.take(70)}")
            is WorkflowEvent.RunSucceeded -> println("  ✔ 运行成功：${event.summary}")
            else -> Unit
        }
    }

    val waitingRun = system.engine.run(runId!!)
    check(waitingRun?.status == RunStatus.WAITING_HUMAN) { "应停在 WAITING_HUMAN，实际 ${waitingRun?.status}" }
    println("\n== 5. 人工批准 → resume ==")
    system.engine.resume(runId!!, humanAnswer = "批准").collect { event ->
        when (event) {
            is WorkflowEvent.NodeCompleted -> println("  ✔ ${event.nodeId} 完成")
            is WorkflowEvent.RunSucceeded -> println("  ✔ 运行成功：${event.summary}（slack 分支）")
            else -> Unit
        }
    }

    // ------------------------------------------------------------------
    // 6. 节点级重试演示（flaky 工具 2 次超时后成功）
    // ------------------------------------------------------------------
    println("\n== 6. 节点级重试（flaky_export 两次超时后成功） ==")
    val withFlaky = workflow("flaky_demo", "重试演示", "不稳定工具的重试") {
        toolNode(
            "export",
            "flaky_export",
            args = mapOf("target" to "monthly.csv"),
            retry = retry(maxAttempts = 3, initialDelayMs = 20),
        )
        edge(START, "export")
        edge("export", END)
    }
    system.engine.start(withFlaky).toList().forEach { event ->
        if (event is WorkflowEvent.NodeFailed) println("  ✘ 第 ${event.attempt} 次失败（将重试=${event.willRetry}）")
        if (event is WorkflowEvent.RunSucceeded) println("  ✔ 重试后成功：${event.summary}")
    }

    // ------------------------------------------------------------------
    // 7. 崩溃恢复（手造 RUNNING 残留现场 → 新引擎 recover + resume）
    // ------------------------------------------------------------------
    println("\n== 7. 崩溃恢复（模拟进程重启） ==")
    val runsDir = workspace.resolve("runs")
    val runStore = FileWorkflowRunStore(runsDir)

    // 起一条新运行，停在人工审批（gather/render 已成功）
    val waitingEvents = system.engine.start(dailyReport, mapOf("channel" to "slack")).toList()
    val waitingRunId = waitingEvents.filterIsInstance<WorkflowEvent.RunWaiting>().first().runId
    val before = gatherCalls
    val renderBefore = renderCalls

    // 手造崩溃现场：render 置 RUNNING（进程在 render 落盘前被 kill 的窗口）
    val parked = runStore.load(waitingRunId)!!
    val crashedState = parked.copy(
        status = RunStatus.RUNNING,
        waitingNodeId = null,
        nodeStates = parked.nodeStates + (
            "render" to parked.nodeStates["render"]!!.copy(
                status = com.androidguru.agent.workflow.NodeStatus.RUNNING,
            )
            ),
    )
    runStore.save(crashedState)

    val engine2 = com.androidguru.agent.workflow.WorkflowEngine(
        toolRegistry = registry,
        runStore = runStore,
    )
    val reclassified = engine2.recoverInterrupted()
    println("  重启扫描：残留 RUNNING → $reclassified")

    // resume：gather 不重跑（SUCCEEDED），render 重跑（RUNNING 回退 PENDING）
    val resumeEvents = engine2.resume(waitingRunId).toList()
    val reWaiting = resumeEvents.filterIsInstance<WorkflowEvent.RunWaiting>().firstOrNull()
    check(reWaiting != null) { "崩溃恢复后应重新停到人工节点" }
    println("  gather 重跑次数：${gatherCalls - before}（SUCCEEDED 节点不重跑）")
    println("  render 重跑次数：${renderCalls - renderBefore}（RUNNING 回退后重执行），重新停在 ${reWaiting.nodeId}")
    engine2.resume(waitingRunId, humanAnswer = "批准（重启后补批）").toList()
        .filterIsInstance<WorkflowEvent.RunSucceeded>()
        .forEach { println("  ✔ 新引擎接力成功：${it.summary}") }

    // ------------------------------------------------------------------
    // 8. 库生命周期：候选 → 激活（2 次成功）
    // ------------------------------------------------------------------
    println("\n== 8. 库生命周期 ==")
    println("  当前统计：${system.library.get("daily_report")?.let { "${it.lifecycle} 成功${it.successCount}/共${it.totalRuns}" }}")
    val lib2 = WorkflowLibrary(InMemoryWorkflowLibraryStore())
    lib2.save(dailyReport)
    lib2.recordRunOutcome("daily_report", success = true)
    lib2.recordRunOutcome("daily_report", success = true)
    println("  模拟 2 次成功后：${lib2.get("daily_report")?.lifecycle}（晋升）")
    repeat(3) { lib2.recordRunOutcome("daily_report", success = false, error = "渠道不可达") }
    println("  模拟连续 3 次失败后：${lib2.get("daily_report")?.lifecycle}（熔断）")
    lib2.enable("daily_report")
    println("  人工复活后：${lib2.get("daily_report")?.lifecycle}\n")

    // ------------------------------------------------------------------
    // 9. 蒸馏（宏录制回退，无 LLM）
    // ------------------------------------------------------------------
    println("== 9. 会话蒸馏（宏录制回退，无需 LLM） ==")
    val conversation = listOf(
        LlmMessage.User("把 2024-06-01 的指标整理成日报发给 ops@team"),
        LlmMessage.Assistant(
            toolCalls = listOf(
                com.androidguru.agent.llm.ToolCall(id = "c1", name = "collect_metrics", arguments = """{"date":"2024-06-01"}"""),
            ),
        ),
        LlmMessage.Tool("c1", """{"date":"2024-06-01","dau":15000,"orders":400,"revenue":12000}"""),
        LlmMessage.Assistant(
            toolCalls = listOf(
                com.androidguru.agent.llm.ToolCall(id = "c2", name = "render_report", arguments = """{"data":"...","date":"2024-06-01"}"""),
            ),
        ),
        LlmMessage.Tool("c2", "日报：指标平稳"),
        LlmMessage.Assistant(
            toolCalls = listOf(
                com.androidguru.agent.llm.ToolCall(id = "c3", name = "send_email", arguments = """{"body":"日报...","to":"ops@team"}"""),
            ),
        ),
        LlmMessage.Tool("c3", "邮件已发送"),
        LlmMessage.Assistant("已完成：日报已发送给 ops@team。"),
    )
    val extractor = WorkflowExtractor(client = null)
    val distilled = extractor.extract(conversation, "整理日报并发送")
    if (distilled != null) {
        println("  蒸馏产物：${distilled.id}（${distilled.nodes.size} 步宏录制）")
        println("  描述：${distilled.description.take(80)}")
        println("  步骤：${distilled.nodes.joinToString(" → ") { "${it.id}:${(it as com.androidguru.agent.workflow.WorkflowNode.Tool).toolId}" }}")
    }

    // ------------------------------------------------------------------
    // 10. 建议注入（两阶段披露第一层）
    // ------------------------------------------------------------------
    println("\n== 10. 建议注入（ACTIVE 索引） ==")
    val suggester = WorkflowSuggester(lib2)
    println(suggester.provideContext("demo-session").prependIndent("  ").take(400))

    println("\n演示完成。工作目录：$workspace（可检查 runs/*.run.json 与 workflows/*.workflow.json）")
}
