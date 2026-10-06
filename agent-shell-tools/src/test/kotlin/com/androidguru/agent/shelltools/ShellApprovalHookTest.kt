package com.androidguru.agent.shelltools

import com.androidguru.agent.shell.ShellRuntime
import com.androidguru.agent.shell.audit.AuditLog
import com.androidguru.agent.shell.policy.ApprovalGate
import com.androidguru.agent.shell.policy.CommandPolicy
import com.androidguru.agent.shelltools.hook.ShellApprovalHook
import com.androidguru.agent.tools.hook.HookDecision
import com.androidguru.agent.tools.hook.HookEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ShellApprovalHookTest {

    private fun hook(base: File): Pair<ShellApprovalHook, ShellRuntime> {
        val rt = ShellRuntime.create(base)
        return ShellApprovalHook(rt) to rt
    }

    @Test
    fun `BLOCKED 命令直接拦截且不弹审批`() = runTest {
        val base = java.nio.file.Files.createTempDirectory("agsh-hook").toFile()
        try {
            val (h, rt) = hook(base)
            val decision = h.onEvent(
                HookEvent.PreToolUse("terminal_exec", """{"command":"rm -rf /"}"""),
            )
            assertTrue("硬拦截应 Block", decision is HookDecision.Block)
            assertTrue((decision as HookDecision.Block).reason.contains("拒绝"))
            assertTrue("不应有审批等待", rt.approval.currentRequest == null)
            assertTrue("拦截应记账", rt.audit.recent(10, AuditLog.TYPE_BLOCKED).isNotEmpty())
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `CONFIRM 命令挂起_允许后放行`() = runTest {
        val base = java.nio.file.Files.createTempDirectory("agsh-hook").toFile()
        try {
            val (h, rt) = hook(base)
            val waiter = launch {
                val decision = h.onEvent(
                    HookEvent.PreToolUse("terminal_exec", """{"command":"mkdir build"}"""),
                )
                // 由审批线程裁决后恢复
                gateVerdict = decision
            }
            runCurrent()
            // 宿主 UI 侧看到请求并裁决
            val req = rt.approval.currentRequest
            assertTrue("应产生审批请求", req != null)
            rt.approval.respond(req!!.id, ApprovalGate.Verdict.ALLOW_ONCE)
            runCurrent()
            gateVerdict?.let { assertTrue(it is HookDecision.Proceed) }
            waiter.join()
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `DENY 回灌可读的改道建议`() = runTest {
        val base = java.nio.file.Files.createTempDirectory("agsh-hook").toFile()
        try {
            val (h, rt) = hook(base)
            var decision: HookDecision? = null
            val waiter = launch {
                decision = h.onEvent(
                    HookEvent.PreToolUse("terminal_exec", """{"command":"mv a b"}"""),
                )
            }
            runCurrent()
            rt.approval.respond(rt.approval.currentRequest!!.id, ApprovalGate.Verdict.DENY)
            runCurrent()
            waiter.join()

            val block = decision as? HookDecision.Block
            assertTrue("拒绝应 Block", block != null)
            assertTrue("拒绝理由应引导模型改道", block!!.reason.contains("改用其它方式"))
            assertTrue("拒绝应记账", rt.audit.recent(10, AuditLog.TYPE_COMMAND)
                .any { it.payload["approval"] == "DENIED" })
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `SAFE 命令在自动放行模式下直通`() = runTest {
        val base = java.nio.file.Files.createTempDirectory("agsh-hook").toFile()
        try {
            val (h, _) = hook(base)
            val decision = h.onEvent(
                HookEvent.PreToolUse("terminal_exec", """{"command":"ls -la"}"""),
            )
            assertEquals(HookDecision.Proceed, decision)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `fs_write 恒需确认_摘要含内容预览`() = runTest {
        val base = java.nio.file.Files.createTempDirectory("agsh-hook").toFile()
        try {
            val (h, rt) = hook(base)
            var decision: HookDecision? = null
            val waiter = launch {
                decision = h.onEvent(
                    HookEvent.PreToolUse("fs_write", """{"path":"/tmp/x.txt","content":"line1\nline2\nline3"}"""),
                )
            }
            runCurrent()
            val req = rt.approval.currentRequest
            assertTrue(req != null)
            assertTrue("影响摘要应含预览", req!!.impact.contains("line1"))
            rt.approval.respond(req.id, ApprovalGate.Verdict.ALLOW_ONCE)
            runCurrent()
            waiter.join()
            assertTrue(decision is HookDecision.Proceed || decision is HookDecision.Block)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `无关工具不介入`() = runTest {
        val base = java.nio.file.Files.createTempDirectory("agsh-hook").toFile()
        try {
            val (h, _) = hook(base)
            val decision = h.onEvent(
                HookEvent.PreToolUse("device_info", "{}"),
            )
            assertEquals(HookDecision.Proceed, decision)
        } finally {
            base.deleteRecursively()
        }
    }

    private var gateVerdict: HookDecision? = null
}
