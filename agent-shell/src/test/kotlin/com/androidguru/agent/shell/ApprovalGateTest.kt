package com.androidguru.agent.shell

import com.androidguru.agent.shell.policy.ApprovalGate
import com.androidguru.agent.shell.policy.CommandPolicy
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalGateTest {

    private fun request(detail: String, level: CommandPolicy.Level = CommandPolicy.Level.CONFIRM) =
        ApprovalGate.Request(
            id = ApprovalGate.newId(),
            toolName = "terminal_exec",
            title = "执行命令",
            detail = detail,
            impact = "测试",
            level = level,
        )

    @Test
    fun `请求后挂起_respond允许后恢复`() = runTest {
        val gate = ApprovalGate()
        val waiter = async { gate.request(request("ls file.txt")) }
        runCurrent()
        assertTrue(gate.respond(waitingId(gate)!!, ApprovalGate.Verdict.ALLOW_ONCE))
        assertEquals(ApprovalGate.Verdict.ALLOW_ONCE, withTimeout(1000) { waiter.await() })
    }

    @Test
    fun `allow_always 记住同组合后续免询问`() = runTest {
        val gate = ApprovalGate()
        // 第一次：挂起 → 允许并记住
        val first = async { gate.request(request("mkdir build")) }
        runCurrent()
        gate.respond(waitingId(gate)!!, ApprovalGate.Verdict.ALLOW_ALWAYS)
        first.await()

        // 第二次同组合（工具+首词）：直接放行，不再进入 pending
        val before = gate.currentRequest
        val second = gate.request(request("mkdir dist"))
        assertEquals(ApprovalGate.Verdict.ALLOW_ALWAYS, second)
        assertEquals("不应产生新请求", before, gate.currentRequest)
    }

    @Test
    fun `不同组合不共享记忆`() = runTest {
        val gate = ApprovalGate()
        val first = async { gate.request(request("mkdir build")) }
        runCurrent()
        gate.respond(waitingId(gate)!!, ApprovalGate.Verdict.ALLOW_ALWAYS)
        first.await()

        // 不同首词 → 需要再次询问
        val waiter = async { gate.request(request("rm draft.txt")) }
        runCurrent()
        assertTrue(waitingId(gate) != null)
        gate.respond(waitingId(gate)!!, ApprovalGate.Verdict.DENY)
        waiter.await()
    }

    @Test
    fun `respond 返回false当无等待请求`() = runTest {
        val gate = ApprovalGate()
        assertFalse(gate.respond("nonexistent", ApprovalGate.Verdict.ALLOW_ONCE))
    }

    @Test
    fun `resetSession 清空记忆`() = runTest {
        val gate = ApprovalGate()
        val waiter = async { gate.request(request("mkdir build")) }
        runCurrent()
        gate.respond(waitingId(gate)!!, ApprovalGate.Verdict.ALLOW_ALWAYS)
        waiter.await()

        gate.resetSession()
        val waiter2 = async { gate.request(request("mkdir dist")) }
        runCurrent()
        assertTrue("清空后需要重新询问", waitingId(gate) != null)
        gate.respond(waitingId(gate)!!, ApprovalGate.Verdict.DENY)
        waiter2.await()
    }

    @Test
    fun `cancelAll 全部判拒`() = runTest {
        val gate = ApprovalGate()
        val w1 = async { gate.request(request("cmd-a")) }
        runCurrent()
        // 同一时刻只有 currentRequest 一个展示位，但 pending 表支持多个
        gate.cancelAll()
        assertEquals(ApprovalGate.Verdict.DENY, w1.await())
    }

    @Test
    fun `BLOCKED 级别不产生审批记忆key`() = runTest {
        val gate = ApprovalGate()
        val req = request("rm -rf /", level = CommandPolicy.Level.BLOCKED)
        // 直接测 decisionKey 行为：allow_always 对 BLOCKED 无效（respond 后再次询问）
        val waiter = async { gate.request(req) }
        runCurrent()
        gate.respond(waitingId(gate)!!, ApprovalGate.Verdict.ALLOW_ALWAYS)
        waiter.await()
        // BLOCKED 的 key 为 null → 记忆表为空 → 下次仍询问
        val waiter2 = async { gate.request(req) }
        runCurrent()
        assertTrue(waitingId(gate) != null)
        gate.respond(waitingId(gate)!!, ApprovalGate.Verdict.DENY)
        waiter2.await()
    }

    @Test
    fun `超时未响应判拒`() = runTest {
        val gate = ApprovalGate()
        val verdict = gate.request(request("cmd"), timeoutMs = 1)
        assertEquals(ApprovalGate.Verdict.DENY, verdict)
    }

    private fun waitingId(gate: ApprovalGate): String? = gate.currentRequest?.id
}
