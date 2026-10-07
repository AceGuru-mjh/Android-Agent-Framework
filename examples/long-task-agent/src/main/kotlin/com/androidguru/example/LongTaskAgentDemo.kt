package com.androidguru.example

import com.androidguru.agent.core.engine.AgentEvent
import com.androidguru.agent.llm.LlmConfig
import com.androidguru.agent.llm.OpenAiCompatibleClient
import com.androidguru.agent.tasks.engine.LongTaskAgent
import kotlinx.coroutines.runBlocking
import java.nio.file.Path

/**
 * 长程任务 Agent 示例 —— 一个构造器调用电齐：
 * 计划工具（task_plan）+ 每轮计划状态注入 + 循环护栏 + 预算续跑 + 崩溃恢复。
 *
 * 运行前设置环境变量：
 * ```
 * export AGENT_BASE_URL="https://api.deepseek.com/v1"
 * export AGENT_API_KEY="sk-..."
 * export AGENT_MODEL="deepseek-chat"
 * ./gradlew :examples:long-task-agent:run
 * ```
 *
 * 演示要点：
 * 1. **计划可见**：每轮工具调用后打印计划进度条（宿主 UI 接入点）；
 * 2. **预算续跑**：迭代预算耗尽时询问用户是否追加，选 y 则 `continueExecution` 从断点继续；
 * 3. **崩溃恢复**：workspace 目录持久化（计划 + 会话记忆），进程重启后
 *    `--resume` 参数直接续跑（对话与计划完整恢复）；
 * 4. **循环护栏**：模型对同一工具重复相同调用时自动告警并注入策略建议。
 */
fun main(args: Array<String>) = runBlocking {
    val workspace = Path.of(System.getProperty("user.dir"), ".long-task-agent-data")
    val sessionId = System.getenv("AGENT_SESSION_ID") ?: "demo-longtask"

    val llmConfig = LlmConfig(
        baseUrl = System.getenv("AGENT_BASE_URL") ?: "https://api.openai.com/v1",
        apiKey = System.getenv("AGENT_API_KEY") ?: error("请设置 AGENT_API_KEY 环境变量"),
        model = System.getenv("AGENT_MODEL") ?: "gpt-4o-mini",
    )

    val agent = LongTaskAgent(
        llmClient = OpenAiCompatibleClient(llmConfig),
        sessionId = sessionId,
        workspace = workspace, // 计划 + 会话记忆都落盘 → 跨进程恢复
    )

    // 计划变更实时观察（宿主 UI 的接入点）
    agent.onPlanChanged { plan ->
        val p = agent.progress()
        if (p != null) {
            val bar = "█".repeat(p.percent / 10) + "░".repeat(10 - p.percent / 10)
            println("📋 [${bar}] ${p.done}/${p.total} done, ${p.failed} failed, ${p.inProgress} in-progress")
        }
    }

    println("=== 长程任务 Agent（workspace: $workspace）===")

    if (args.contains("--resume")) {
        val restored = agent.engineMemoryMessages()
        println("恢复会话 $sessionId：对话 $restored 条，计划 ${agent.plan()?.tasks?.size ?: 0} 项")
        if (restored == 0) {
            println("没有可恢复的历史，请去掉 --resume 正常启动")
            return@runBlocking
        }
        println("从断点续跑 50 轮…")
        agent.continueExecution(50).collect { render(it) }
        return@runBlocking
    }

    println("输入一个复杂目标（例如：调研 3 个主流 JSON 库并产出对比报告），exit 退出\n")
    while (true) {
        print("\n你> ")
        val line = readLine() ?: break
        if (line.isBlank()) continue
        if (line == "exit") break

        agent.execute(line).collect { event ->
            when (event) {
                is AgentEvent.UserInputRequired -> {
                    print("\n${event.prompt}\n你> ")
                    agent.submitUserInput(readLine().orEmpty())
                }

                else -> render(event)
            }
            // 长程任务核心：预算耗尽 → 询问 → 续跑（记忆与计划完整保留）
            if (event is AgentEvent.BudgetExhausted) {
                print("\n预算耗尽（${event.iterationsUsed} 轮 / ${event.totalToolCalls} 次工具调用）。追加 50 轮继续？[y/N] ")
                if (readLine()?.trim()?.equals("y", ignoreCase = true) == true) {
                    agent.continueExecution(50).collect { render(it) }
                } else {
                    println("已暂停。重启后可用 --resume 从断点续跑。")
                }
            }
        }
    }
    println("再见！重启后可用 --resume 续跑（workspace 已持久化）。")
}

private fun render(event: AgentEvent) {
    when (event) {
        is AgentEvent.ResponseChunk -> print(event.text)
        is AgentEvent.ThinkingChunk -> Unit // 推理流一般不展示
        is AgentEvent.ToolCallStart -> print("\n🔧 ${event.toolName} ${event.arguments.take(120)}")
        is AgentEvent.ToolCallComplete -> println(if (event.result.ok) " ✓" else " ✗ ${event.result.content.take(120)}")
        is AgentEvent.IterationStart -> if (event.iteration % 5 == 0) println("\n--- 第 ${event.iteration} 轮 ---")
        is AgentEvent.LoopDetected -> println("\n⚠ 循环护栏：${event.toolName} 相同调用已 ${event.repeatedCount} 次，已注入策略建议")
        is AgentEvent.BudgetExhausted -> Unit // 外层处理
        is AgentEvent.UserInputRequired -> Unit // 外层处理
        is AgentEvent.Error -> println("\n[错误] ${event.message}")
        is AgentEvent.Complete -> println("\n✅ 完成（${event.totalIterations} 轮 / ${event.totalToolCalls} 次工具调用）")
        is AgentEvent.Aborted -> println("\n[中止]")
        is AgentEvent.LlmRetryScheduled -> println("\n… 重试 ${event.attempt}（${event.reason}）")
        is AgentEvent.UsageUpdated -> Unit
    }
}
