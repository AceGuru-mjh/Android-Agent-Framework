package com.androidguru.agent.shelltools.chat

import com.androidguru.agent.chat.ChatItem
import com.androidguru.agent.chat.ChatPresenter
import com.androidguru.agent.shell.job.ShellJob
import com.androidguru.agent.shell.policy.ApprovalGate
import com.androidguru.agent.shell.policy.CommandPolicy
import com.androidguru.agent.shell.process.JvmProcessChannelFactory
import com.androidguru.agent.shell.runtime.ShellEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Shell 宿主 ↔ 聊天层桥：
 * - 审批通道：闸门请求 → 卡片上屏（字段拷贝）、respond 转发 + 回填、cancel 全量释放；
 * - 作业通道：live 作业保持 / 改判、跨进程记录改判、消失置 FAILED。
 *
 * 审批用 runTest（闸门挂起是纯协程）；作业用 runBlocking（真实子进程，
 * 虚拟时钟与真实进程不兼容 —— 与 ShellJobStoreTest 同款取舍）。
 */
class ShellChatBridgeTest {

    private fun request(id: String = "req-1") = ApprovalGate.Request(
        id = id,
        toolName = "terminal_exec",
        title = "执行命令",
        detail = "mkdir build",
        impact = "新建目录",
        level = CommandPolicy.Level.CONFIRM,
    )

    // ---- 审批通道 ------------------------------------------------------

    @Test
    fun `审批请求经闸门上屏_respond转发并回填卡片`() = runTest {
        val gate = ApprovalGate()
        val presenter = ChatPresenter(scope = backgroundScope, clock = { 1L })
        val bridge = ShellChatBridge(presenter, gate, backgroundScope)
        bridge.start()
        runCurrent() // watcher 完成订阅

        var verdict: ApprovalGate.Verdict? = null
        val waiter = launch { verdict = gate.request(request()) }
        runCurrent()

        // 闸门 Request → 审批卡（字段拷贝，无壳类型穿层）
        val card = presenter.items.value.filterIsInstance<ChatItem.ApprovalItem>().single()
        assertEquals("req-1", card.requestId)
        assertEquals("terminal_exec", card.toolName)
        assertEquals("mkdir build", card.detail)
        assertEquals("新建目录", card.impact)
        assertFalse(card.resolved)

        assertTrue("转发应被闸门接收", bridge.respond("req-1", ApprovalGate.Verdict.ALLOW_ONCE))
        runCurrent()
        waiter.join()
        assertEquals(ApprovalGate.Verdict.ALLOW_ONCE, verdict)

        val resolved = presenter.items.value.filterIsInstance<ChatItem.ApprovalItem>().single()
        assertTrue(resolved.resolved)
        assertEquals("ALLOW_ONCE", resolved.verdict)
        bridge.stop()
    }

    @Test
    fun `DENY裁决同样转发并回填`() = runTest {
        val gate = ApprovalGate()
        val presenter = ChatPresenter(scope = backgroundScope, clock = { 1L })
        val bridge = ShellChatBridge(presenter, gate, backgroundScope)
        bridge.start()
        runCurrent()

        val waiter = launch { gate.request(request("req-deny")) }
        runCurrent()
        bridge.respond("req-deny", ApprovalGate.Verdict.DENY)
        waiter.join()

        val card = presenter.items.value.filterIsInstance<ChatItem.ApprovalItem>().single()
        assertTrue(card.resolved)
        assertEquals("DENY", card.verdict)
    }

    @Test
    fun `respond未知请求_返回false且不误标卡片`() = runTest {
        val gate = ApprovalGate()
        val presenter = ChatPresenter(scope = backgroundScope, clock = { 1L })
        val bridge = ShellChatBridge(presenter, gate, backgroundScope)

        // 屏上已有一张卡（此处直接经 presenter 上卡，模拟宿主自绘路径）
        presenter.onApprovalRequest(
            com.androidguru.agent.chat.ApprovalUi(
                id = "card-1", toolName = "terminal_exec",
                title = "执行命令", detail = "mkdir build", impact = "新建目录",
            ),
        )
        // 闸门已不认识（幽灵 id 模拟超时回收后的迟来裁决）
        assertFalse(bridge.respond("ghost", ApprovalGate.Verdict.ALLOW_ONCE))
        val card = presenter.items.value.filterIsInstance<ChatItem.ApprovalItem>().single()
        assertFalse("闸门没收到的裁决不能上卡", card.resolved)
    }

    @Test
    fun `cancel_先释放闸门等待者再回填卡片`() = runTest {
        val gate = ApprovalGate()
        val presenter = ChatPresenter(scope = backgroundScope, clock = { 1L })
        val bridge = ShellChatBridge(presenter, gate, backgroundScope)
        bridge.start()
        runCurrent()

        var verdict: ApprovalGate.Verdict? = null
        val waiter = launch { verdict = gate.request(request("req-1")) }
        runCurrent()
        presenter.addUserMessage("跑个东西")

        bridge.cancel()
        waiter.join()
        assertEquals("闸门等待者应被 DENY 释放", ApprovalGate.Verdict.DENY, verdict)

        val card = presenter.items.value.filterIsInstance<ChatItem.ApprovalItem>().single()
        assertTrue(card.resolved)
        assertEquals("DENY", card.verdict)
        val note = presenter.items.value.last() as ChatItem.SystemNote
        assertEquals("已取消", note.text)
    }

    // ---- 作业通道 ------------------------------------------------------

    private fun env(base: File) = ShellEnvironment(
        prefix = File(base, "usr"),
        home = File(base, "home"),
        tmp = File(base, "tmp"),
    )

    private fun awaitExit(job: ShellJob, timeoutMs: Long = 15_000) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (job.state == ShellJob.State.RUNNING && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
    }

    @Test
    fun `作业对账_live结束按退出码改判_消失置FAILED`() = runBlocking {
        val base = java.nio.file.Files.createTempDirectory("bridge-job").toFile()
        try {
            val store = ShellJob.ShellJobStore(File(base, "jobs")).also { it.init() }
            val failed = ShellJob.start(
                id = store.newId(), command = "echo JOB_EXIT; exit 3",
                workdir = null, environment = env(base),
                channelFactory = JvmProcessChannelFactory(), store = store,
            )
            awaitExit(failed)
            assertEquals(ShellJob.State.EXITED, failed.state)

            val presenter = ChatPresenter(scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), clock = { 1L })
            val bridge = ShellChatBridge(presenter, ApprovalGate(), CoroutineScope(SupervisorJob()))
            // 注入三张历史重放后的作业卡 + 一张无 jobId 的普通卡
            presenter.patch { list ->
                list + listOf(
                    ChatItem.ActionItem(id = "k1", ts = 1, toolName = "job_start", summary = "echo …", jobId = failed.id),
                    ChatItem.ActionItem(id = "k2", ts = 2, toolName = "job_start", summary = "sleep 30", jobId = "job_missing"),
                    ChatItem.ActionItem(id = "k3", ts = 3, toolName = "terminal_exec", summary = "ls"),
                )
            }

            bridge.observeJobStore(store)
            val cards = presenter.items.value.filterIsInstance<ChatItem.ActionItem>().associateBy { it.id }

            // live + EXITED(3) → FAILED + 退出码 + 日志尾部
            val c1 = cards.getValue("k1")
            assertEquals(ChatItem.ActionState.FAILED, c1.state)
            assertEquals("退出码 3", c1.summary)
            assertTrue("应从作业日志补输出尾部", c1.outputTail?.contains("JOB_EXIT") == true)

            // 彻底消失 → FAILED + yl-ai 原句
            val c2 = cards.getValue("k2")
            assertEquals(ChatItem.ActionState.FAILED, c2.state)
            assertEquals(ShellChatBridge.GONE_NOTE, c2.summary)

            // 无 jobId 的普通 RUNNING 卡不受对账影响
            assertEquals(ChatItem.ActionState.RUNNING, cards.getValue("k3").state)
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `作业对账_live运行中保持_停止后置已被停止`() = runBlocking {
        val base = java.nio.file.Files.createTempDirectory("bridge-job-live").toFile()
        try {
            val store = ShellJob.ShellJobStore(File(base, "jobs")).also { it.init() }
            val live = ShellJob.start(
                id = store.newId(), command = "sleep 30",
                workdir = null, environment = env(base),
                channelFactory = JvmProcessChannelFactory(), store = store,
            )
            val presenter = ChatPresenter(scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), clock = { 1L })
            val bridge = ShellChatBridge(presenter, ApprovalGate(), CoroutineScope(SupervisorJob()))
            presenter.patch { list ->
                list + ChatItem.ActionItem(id = "k1", ts = 1, toolName = "job_start", summary = "sleep 30", jobId = live.id)
            }

            bridge.observeJobStore(store)
            assertEquals(
                ChatItem.ActionState.RUNNING,
                (presenter.items.value.filterIsInstance<ChatItem.ActionItem>().single()).state,
            )

            live.stop()
            bridge.observeJobStore(store)
            val stopped = presenter.items.value.filterIsInstance<ChatItem.ActionItem>().single()
            assertEquals(ChatItem.ActionState.FAILED, stopped.state)
            assertEquals("已被停止", stopped.summary)
            live.close()
        } finally {
            base.deleteRecursively()
        }
    }

    @Test
    fun `作业对账_跨进程仅存记录_按改判后的记录回填`() = runBlocking {
        val base = java.nio.file.Files.createTempDirectory("bridge-job-record").toFile()
        try {
            // 预置 index.json：一条 EXITED(3) 记录 + 一条 RUNNING 残留（init 后改判 INTERRUPTED）
            val jobsDir = File(base, "jobs").apply { mkdirs() }
            File(jobsDir, "index.json").writeText(
                """{"jobs": [
                    {"id":"job_old1","command":"echo RECORD","workdir":"/tmp","state":"EXITED",
                     "log":"no-such-1.log","startedAt":1,"durationMs":10,"exitCode":3},
                    {"id":"job_old2","command":"echo GHOST","workdir":"/tmp","state":"RUNNING",
                     "log":"no-such-2.log","startedAt":2,"durationMs":10}
                ]}""",
            )
            val presenter = ChatPresenter(scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), clock = { 1L })
            val bridge = ShellChatBridge(presenter, ApprovalGate(), CoroutineScope(SupervisorJob()))
            presenter.patch { list ->
                list + listOf(
                    ChatItem.ActionItem(id = "k1", ts = 1, toolName = "job_start", summary = "echo RECORD", jobId = "job_old1"),
                    ChatItem.ActionItem(id = "k2", ts = 2, toolName = "job_start", summary = "echo GHOST", jobId = "job_old2"),
                )
            }

            // 重启后的新 store：只有磁盘记录，没有 live 作业
            val store = ShellJob.ShellJobStore(jobsDir).also { it.init() }
            bridge.observeJobStore(store)
            val cards = presenter.items.value.filterIsInstance<ChatItem.ActionItem>().associateBy { it.id }

            val c1 = cards.getValue("k1")
            assertEquals(ChatItem.ActionState.FAILED, c1.state)
            assertEquals("退出码 3", c1.summary)

            val c2 = cards.getValue("k2")
            assertEquals(ChatItem.ActionState.FAILED, c2.state)
            assertEquals(ShellChatBridge.GONE_NOTE, c2.summary)
        } finally {
            base.deleteRecursively()
        }
    }
}
