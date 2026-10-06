package com.androidguru.agent.tools

import com.androidguru.agent.tools.hook.HookDecision
import com.androidguru.agent.tools.hook.HookEvent
import com.androidguru.agent.tools.hook.HookRegistry
import com.androidguru.agent.tools.hook.ToolHook
import com.androidguru.agent.tools.resilience.ToolCircuitBreaker
import com.androidguru.agent.tools.resilience.ToolRunPolicy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

private class EchoTool : AgentTool {
    override val id = "echo"
    override val description = "回显输入"
    override val parameters = ToolSchema.build {
        string("text", required = true)
    }
    override suspend fun execute(request: ToolRequest): ToolResult {
        val text = kotlinx.serialization.json.Json.parseToJsonElement(request.arguments)
            .let { (it as kotlinx.serialization.json.JsonObject)["text"]!!.let { p -> (p as kotlinx.serialization.json.JsonPrimitive).content } }
        return ToolResult.success(text)
    }
}

private class BoomTool : AgentTool {
    override val id = "boom"
    override val description = "总是抛异常"
    override suspend fun execute(request: ToolRequest): ToolResult =
        throw IllegalStateException("boom")
}

class ToolExecutorTest {

    @Test
    fun `正常执行走完整管线`() = runTest {
        val registry = DefaultToolRegistry().apply { register(EchoTool()) }
        val executor = DefaultToolExecutor(registry)
        val result = executor.execute("echo", """{"text": "hello"}""")
        assertTrue(result.ok)
        assertEquals("hello", result.content)
    }

    @Test
    fun `未知工具返回 NOT_FOUND 并带相近建议`() = runTest {
        val registry = DefaultToolRegistry().apply { register(EchoTool()) }
        val executor = DefaultToolExecutor(registry)
        val result = executor.execute("ech", "{}")
        assertFalse(result.ok)
        assertEquals(ToolErrorCode.NOT_FOUND, result.error?.code)
        assertEquals("echo", result.error?.suggestion)
    }

    @Test
    fun `schema 校验失败返回 VALIDATION`() = runTest {
        val registry = DefaultToolRegistry().apply { register(EchoTool()) }
        val executor = DefaultToolExecutor(registry)
        val result = executor.execute("echo", "{}")
        assertFalse(result.ok)
        assertEquals(ToolErrorCode.VALIDATION, result.error?.code)
    }

    @Test
    fun `PreToolUse 钩子可阻断`() = runTest {
        val registry = DefaultToolRegistry().apply { register(EchoTool()) }
        val hooks = HookRegistry().apply {
            register(object : ToolHook {
                override val name = "blocker"
                override suspend fun onEvent(event: HookEvent) =
                    if (event is HookEvent.PreToolUse) HookDecision.Block("敏感词") else HookDecision.Proceed
            })
        }
        val executor = DefaultToolExecutor(registry, hooks)
        val result = executor.execute("echo", """{"text": "x"}""")
        assertFalse(result.ok)
        assertEquals(ToolErrorCode.PERMISSION, result.error?.code)
        assertTrue(result.content.contains("敏感词"))
    }

    @Test
    fun `PreToolUse 钩子可改写参数`() = runTest {
        val registry = DefaultToolRegistry().apply { register(EchoTool()) }
        val hooks = HookRegistry().apply {
            register(object : ToolHook {
                override val name = "replacer"
                override suspend fun onEvent(event: HookEvent) =
                    if (event is HookEvent.PreToolUse) {
                        HookDecision.Modify("""{"text": "改写后"}""")
                    } else {
                        HookDecision.Proceed
                    }
            })
        }
        val executor = DefaultToolExecutor(registry, hooks)
        val result = executor.execute("echo", """{"text": "原始"}""")
        assertEquals("改写后", result.content)
    }

    @Test
    fun `钩子内部异常被隔离不打断执行`() = runTest {
        val registry = DefaultToolRegistry().apply { register(EchoTool()) }
        val hooks = HookRegistry().apply {
            register(object : ToolHook {
                override val name = "bad"
                override suspend fun onEvent(event: HookEvent): HookDecision =
                    throw RuntimeException("hook crashed")
            })
        }
        val executor = DefaultToolExecutor(registry, hooks)
        val result = executor.execute("echo", """{"text": "ok"}""")
        assertTrue(result.ok)
    }

    @Test
    fun `工具异常被折叠为 INTERNAL 失败`() = runTest {
        val registry = DefaultToolRegistry().apply { register(BoomTool()) }
        val executor = DefaultToolExecutor(registry)
        val result = executor.execute("boom", "{}")
        assertFalse(result.ok)
        assertEquals(ToolErrorCode.INTERNAL, result.error?.code)
    }

    @Test
    fun `超时折叠为 TIMEOUT`() = runTest {
        val slow = object : AgentTool {
            override val id = "slow"
            override val description = "慢工具"
            override suspend fun execute(request: ToolRequest): ToolResult {
                kotlinx.coroutines.delay(5000)
                return ToolResult.success("done")
            }
        }
        val registry = DefaultToolRegistry().apply { register(slow) }
        val executor = DefaultToolExecutor(
            registry,
            defaultPolicy = ToolRunPolicy(timeoutMs = 50),
        )
        val result = executor.execute("slow", "{}")
        assertFalse(result.ok)
        assertEquals(ToolErrorCode.TIMEOUT, result.error?.code)
    }

    @Test
    fun `RETRYABLE 错误按退避阶梯重试`() = runTest {
        val attempts = AtomicInteger(0)
        val flaky = object : AgentTool {
            override val id = "flaky"
            override val description = "前两次失败"
            override suspend fun execute(request: ToolRequest): ToolResult =
                if (attempts.incrementAndGet() < 3) {
                    ToolResult.failure("暂时不可用", ToolErrorCode.UNAVAILABLE)
                } else {
                    ToolResult.success("recovered")
                }
        }
        val registry = DefaultToolRegistry().apply { register(flaky) }
        val executor = DefaultToolExecutor(
            registry,
            defaultPolicy = ToolRunPolicy(timeoutMs = 1000, retryDelaysMs = listOf(0, 0, 0)),
        )
        val result = executor.execute("flaky", "{}")
        assertTrue(result.ok)
        assertEquals(3, attempts.get())
    }

    @Test
    fun `连续失败触发熔断`() = runTest {
        val registry = DefaultToolRegistry().apply { register(BoomTool()) }
        val breaker = ToolCircuitBreaker(failureThreshold = 2, openCooldownMs = 60_000)
        val executor = DefaultToolExecutor(registry, breaker = breaker)

        executor.execute("boom", "{}")
        executor.execute("boom", "{}")
        assertEquals(ToolCircuitBreaker.State.OPEN, breaker.stateOf("boom"))

        val result = executor.execute("boom", "{}")
        assertEquals(ToolErrorCode.UNAVAILABLE, result.error?.code)
        assertNotNull(result.error?.suggestion)
    }

    @Test
    fun `超长输出被钳制`() = runTest {
        val big = object : AgentTool {
            override val id = "big"
            override val description = "大输出"
            override suspend fun execute(request: ToolRequest): ToolResult =
                ToolResult.success("x".repeat(100_000))
        }
        val registry = DefaultToolRegistry().apply { register(big) }
        val executor = DefaultToolExecutor(registry, maxOutputChars = 1000)
        val result = executor.execute("big", "{}")
        assertTrue(result.content.length < 2000)
        assertTrue(result.content.contains("截断"))
    }

    @Test
    fun `用量统计正确累计`() = runTest {
        val registry = DefaultToolRegistry().apply { register(EchoTool()) }
        val executor = DefaultToolExecutor(registry)
        executor.execute("echo", """{"text": "a"}""")
        executor.execute("echo", """{"text": "b"}""")
        executor.execute("unknown", "{}")
        val usage = executor.usageSnapshot()
        assertEquals(2, usage.first { it.toolId == "echo" }.totalCalls)
        assertEquals(1, usage.first { it.toolId == "unknown" }.failureCalls)
    }
}
