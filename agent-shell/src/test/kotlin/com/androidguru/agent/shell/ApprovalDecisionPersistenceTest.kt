package com.androidguru.agent.shell

import com.androidguru.agent.shell.policy.ApprovalDecisionStore
import com.androidguru.agent.shell.policy.ApprovalGate
import com.androidguru.agent.shell.policy.CommandPolicy
import com.androidguru.agent.shell.policy.FileApprovalDecisionStore
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 审批决定持久化测试：TTL / 跨实例恢复 / opt-in 语义 / 全清。
 */
class ApprovalDecisionPersistenceTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun request(memoryKey: String? = null, detail: String = "mkdir build") =
        ApprovalGate.Request(
            id = ApprovalGate.newId(),
            toolName = "terminal_exec",
            title = "执行命令",
            detail = detail,
            impact = "测试",
            level = CommandPolicy.Level.CONFIRM,
            memoryKey = memoryKey,
        )

    private fun waitingId(gate: ApprovalGate): String? = gate.currentRequest?.id

    @Test
    fun `allow always 落盘后新实例免询问`() = runTest {
        val file = tmp.newFolder().toPath().resolve("approvals.jsonl")
        val store = FileApprovalDecisionStore(file)

        // 实例 1：用户点「本次都允许」
        val gate1 = ApprovalGate(store)
        val waiter = async { gate1.request(request(memoryKey = "fs_write:/data/app")) }
        runCurrent()
        gate1.respond(waitingId(gate1)!!, ApprovalGate.Verdict.ALLOW_ALWAYS)
        assertEquals(ApprovalGate.Verdict.ALLOW_ALWAYS, withTimeout(1000) { waiter.await() })

        // 实例 2（进程重启模拟）：同键请求直接放行
        val gate2 = ApprovalGate(FileApprovalDecisionStore(file))
        val verdict = gate2.request(request(memoryKey = "fs_write:/data/app"))
        assertEquals(ApprovalGate.Verdict.ALLOW_ALWAYS, verdict)
    }

    @Test
    fun `TTL 过期的授权不再生效`() = runTest {
        val file = tmp.newFolder().toPath().resolve("approvals.jsonl")
        var now = 1_000_000L
        val store = object : ApprovalDecisionStore {
            private val delegate = FileApprovalDecisionStore(file, ttlMs = 1000L)
            override fun loadAllowedKeys(nowMs: Long) = delegate.loadAllowedKeys(now)
            override fun persistAllowedKey(key: String, nowMs: Long) = delegate.persistAllowedKey(key, now)
            override fun removeAllowedKey(key: String) = delegate.removeAllowedKey(key)
            override fun clear() = delegate.clear()
        }

        val gate = ApprovalGate(store)
        val waiter = async { gate.request(request(memoryKey = "term:deploy")) }
        runCurrent()
        gate.respond(waitingId(gate)!!, ApprovalGate.Verdict.ALLOW_ALWAYS)
        waiter.await()

        now += 2000L // 越过 TTL
        val reloaded = FileApprovalDecisionStore(file, ttlMs = 1000L).loadAllowedKeys(now)
        assertTrue("过期授权应当被过滤", reloaded.isEmpty())
    }

    @Test
    fun `未接 store 时行为不变（会话内记忆）`() = runTest {
        val gate = ApprovalGate() // 无持久化
        val waiter = async { gate.request(request(memoryKey = "fs_write:/tmp")) }
        runCurrent()
        gate.respond(waitingId(gate)!!, ApprovalGate.Verdict.ALLOW_ALWAYS)
        waiter.await()

        // resetSession 后清空（旧行为）
        gate.resetSession()
        val again = async { gate.request(request(memoryKey = "fs_write:/tmp")) }
        runCurrent()
        assertTrue(gate.currentRequest != null) // 重新询问
        gate.respond(waitingId(gate)!!, ApprovalGate.Verdict.DENY)
        assertEquals(ApprovalGate.Verdict.DENY, withTimeout(1000) { again.await() })
    }

    @Test
    fun `接 store 时 resetSession 保留持久授权`() = runTest {
        val file = tmp.newFolder().toPath().resolve("approvals.jsonl")
        val store = FileApprovalDecisionStore(file)
        val gate = ApprovalGate(store)

        val waiter = async { gate.request(request(memoryKey = "term:build")) }
        runCurrent()
        gate.respond(waitingId(gate)!!, ApprovalGate.Verdict.ALLOW_ALWAYS)
        waiter.await()

        gate.resetSession() // 会话级清空，但持久授权保留
        val verdict = gate.request(request(memoryKey = "term:build"))
        assertEquals(ApprovalGate.Verdict.ALLOW_ALWAYS, verdict)
    }

    @Test
    fun `clearAllMemory 一键撤销含落盘授权`() = runTest {
        val file = tmp.newFolder().toPath().resolve("approvals.jsonl")
        val store = FileApprovalDecisionStore(file)
        val gate = ApprovalGate(store)

        val waiter = async { gate.request(request(memoryKey = "term:rm")) }
        runCurrent()
        gate.respond(waitingId(gate)!!, ApprovalGate.Verdict.ALLOW_ALWAYS)
        waiter.await()

        gate.clearAllMemory()
        assertTrue(store.loadAllowedKeys().isEmpty())

        val after = async { gate.request(request(memoryKey = "term:rm")) }
        runCurrent()
        assertFalse(after.isCompleted) // 需要重新询问
        gate.respond(waitingId(gate)!!, ApprovalGate.Verdict.DENY)
    }

    @Test
    fun `损坏行与空文件容错`() {
        val file = tmp.newFolder().toPath().resolve("approvals.jsonl")
        val store = FileApprovalDecisionStore(file)
        java.nio.file.Files.write(
            file,
            listOf("corrupt line {", """{"key":"term:ok","savedAtMs":1}"""),
            java.nio.charset.StandardCharsets.UTF_8,
        )
        val keys = store.loadAllowedKeys(nowMs = 2)
        assertEquals(setOf("term:ok"), keys)
    }
}
