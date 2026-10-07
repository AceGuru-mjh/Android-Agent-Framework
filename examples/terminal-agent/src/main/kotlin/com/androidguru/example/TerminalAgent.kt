package com.androidguru.example

import com.androidguru.agent.core.engine.AgentConfig
import com.androidguru.agent.core.engine.AgentEvent
import com.androidguru.agent.core.engine.DefaultAgentEngine
import com.androidguru.agent.core.engine.UserInput
import com.androidguru.agent.llm.LlmConfig
import com.androidguru.agent.llm.OpenAiCompatibleClient
import com.androidguru.agent.shell.ShellRuntime
import com.androidguru.agent.shell.policy.ApprovalGate
import com.androidguru.agent.shelltools.ShellToolSet
import com.androidguru.agent.shelltools.hook.ShellApprovalHook
import com.androidguru.agent.shelltools.hook.ShellAuditHook
import com.androidguru.agent.shelltools.prompt.ShellPromptBuilder
import com.androidguru.agent.tools.DefaultToolExecutor
import com.androidguru.agent.tools.DefaultToolRegistry
import com.androidguru.agent.tools.hook.HookRegistry
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * 终端 Agent 示例 —— ShellToolSet 的完整接入演示。
 *
 * 与 simple-agent 的差异：工具不再是一个个手写，而是**整套终端能力一次接入**
 * （19 个工具 + 审批闸门 + 审计 + 提示词构建器）。
 *
 * 运行前设置环境变量：
 * ```
 * export AGENT_BASE_URL="https://api.deepseek.com/v1"
 * export AGENT_API_KEY="sk-..."
 * export AGENT_MODEL="deepseek-chat"
 * ./gradlew :examples:terminal-agent:run
 * ```
 *
 * 原生层（agent-shell-native）：启动时探测 agsh_native，可用则把进程通道
 * 升级为真 PTY（交互式程序 / Ctrl-C / 窗口尺寸全语义），不可用则回退
 * JVM 管道 —— 桌面加载方式：
 * ```
 * ./gradlew :examples:terminal-agent:run \
 *   -Djava.library.path=/path/to/dir-with-libagsh_native.so
 * ```
 *
 * 安全行为：写文件 / 删文件 / 非只读命令会在控制台请求确认 ——
 * 输入 y 允许一次，a 允许本会话同组合，其它一律拒绝（拒绝会回灌给模型让它改道）。
 */
fun main() = runBlocking {
    // ① Shell 运行时（状态落盘在本目录下：会话检查点 / 审计 / 作业日志）
    val baseDir = File(System.getProperty("user.dir"), ".terminal-agent-data")

    // 原生 PTY 优先：加载 agsh_native（forkpty + C++17），失败优雅回退 JVM 管道
    val nativeFactory = com.androidguru.agent.shell.nativeruntime.NativeProcessChannelFactory.createIfAvailable()
    if (nativeFactory != null) {
        println("终端通道：原生 PTY（${com.androidguru.agent.shell.nativeruntime.NativeElfTools.info()}）")
    } else {
        println("终端通道：JVM 管道（agsh_native 未加载；交互式程序 / Ctrl-C 语义退化，其余功能不受影响）")
    }
    val runtime = nativeFactory
        ?.let { ShellRuntime.create(baseDir, channelFactory = it) }
        ?: ShellRuntime.create(baseDir)
    runtime.sessions.create()

    // ② 终端工具集：19 个工具一次注册
    val registry = DefaultToolRegistry()
    val shell = ShellToolSet(runtime, device = com.androidguru.agent.shelltools.DeviceTools.NONE)
    shell.installInto(registry)

    // ③ 审批钩子 + 审计钩子挂进执行管线
    val hooks = HookRegistry()
    hooks.register(shell.approvalHook())
    hooks.register(shell.auditHook())

    // 审批请求的协程观察者（等价 yl-ai 的 ChatController 审批卡逻辑）
    val approvalJob = launch {
        runtime.approval.requests.collect { req ->
            if (req != null) {
                println()
                println("┌─ 需要你确认 [$req.level]")
                println("│ ${req.title}: ${req.detail.take(160)}")
                if (req.impact.isNotBlank()) println("│ 影响: ${req.impact.replace("\n", " | ").take(160)}")
                println("└─ [y] 允许一次  [a] 本会话都允许  [其它] 拒绝")
            }
        }
    }

    // ④ 组装引擎（用 ShellPromptBuilder 生成终端 agent 的系统提示词）
    val llmConfig = LlmConfig(
        baseUrl = System.getenv("AGENT_BASE_URL") ?: "https://api.openai.com/v1",
        apiKey = System.getenv("AGENT_API_KEY") ?: error("请设置 AGENT_API_KEY 环境变量"),
        model = System.getenv("AGENT_MODEL") ?: "gpt-4o-mini",
    )
    val engine = DefaultAgentEngine(
        llmClient = OpenAiCompatibleClient(llmConfig),
        toolRegistry = registry,
        toolExecutor = DefaultToolExecutor(
            registry = registry,
            hooks = hooks,
            policies = shell.recommendedPolicies(),
        ),
        config = AgentConfig(
            systemPrompt = ShellPromptBuilder.build(runtime),
            maxIterations = 24,
        ),
    )

    // ⑤ 对话 REPL：每个任务开始前重置「本次都允许」记忆（权限最小化）
    println("=== 终端 Agent（输入 exit 退出；写/删/非只读命令会请求确认）===")
    while (true) {
        print("\n你> ")
        val line = readLine() ?: break
        if (line.isBlank()) continue
        if (line == "exit") break

        runtime.approval.resetSession()

        // 控制台审批：监听 currentRequest，读 stdin 裁决
        val inputJob = launch {
            runtime.approval.requests.collect { req ->
                if (req != null) {
                    val answer = readLine()?.trim()?.lowercase() ?: "n"
                    val verdict = when (answer) {
                        "y" -> ApprovalGate.Verdict.ALLOW_ONCE
                        "a" -> ApprovalGate.Verdict.ALLOW_ALWAYS
                        else -> ApprovalGate.Verdict.DENY
                    }
                    runtime.approval.respond(req.id, verdict)
                }
            }
        }

        engine.execute(UserInput(line)).collect { event ->
            when (event) {
                is AgentEvent.ResponseChunk -> print(event.text)
                is AgentEvent.ToolCallStart -> print("\n🔧 ${event.toolName} ${event.arguments.take(100)}")
                is AgentEvent.ToolCallComplete -> println(if (event.result.ok) " ✓" else " ✗")
                is AgentEvent.UserInputRequired -> {
                    print("\n${event.prompt}\n你> ")
                    engine.submitUserInput(readLine().orEmpty())
                }

                is AgentEvent.Error -> println("\n[错误] ${event.message}")
                is AgentEvent.Complete -> println()
                else -> Unit
            }
        }
        inputJob.cancel()
    }
    approvalJob.cancel()

    // 退出前保存会话检查点（下次启动可恢复）
    runtime.checkpoint.save(runtime.sessions)
    runtime.sessions.closeAll()
    println("会话已存档。再见！")
}
