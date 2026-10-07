package com.androidguru.agent.chat

import com.androidguru.agent.core.engine.AgentEvent
import com.androidguru.agent.tools.ToolResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 事件→卡片转译：流式分段、权威对齐（"正文只和正文比"）、工具卡生命周期、
 * 审批卡与 cancel 回填、debounce 持久化。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatPresenterTest {

    /** 绑定到当前 TestScope 的 backgroundScope：与虚拟时钟同步，debounce 断言可控。 */
    private fun TestScope.newPresenter(dir: File? = null): ChatPresenter = ChatPresenter(
        scope = backgroundScope,
        store = dir?.let { ChatHistoryStore(it) },
        clock = { 1_700_000_000_000L },
    )

    private fun newDir(): File = java.nio.file.Files.createTempDirectory("agent-chat-presenter").toFile()

    private fun approval(id: String = "req-1") = ApprovalUi(
        id = id,
        toolName = "terminal_exec",
        title = "执行命令",
        detail = "mkdir build",
        impact = "新建目录",
    )

    // ---- 流式与分段 ----------------------------------------------------

    @Test
    fun `ResponseChunk_粘到同一张流式气泡`() = runTest {
        val p = newPresenter()
        p.onAgentEvent(AgentEvent.ResponseChunk("你好"))
        p.onAgentEvent(AgentEvent.ResponseChunk("，世界"))
        val msgs = p.items.value.filterIsInstance<ChatItem.AssistantMessage>()
        assertEquals(1, msgs.size)
        assertEquals("你好，世界", msgs.single().text)
        assertTrue(msgs.single().streaming)
    }

    @Test
    fun `工具卡截断正文段_之后的增量开新气泡`() = runTest {
        val p = newPresenter()
        p.onAgentEvent(AgentEvent.ResponseChunk("先看目录。"))
        p.onAgentEvent(AgentEvent.ToolCallStart("c1", "terminal_exec", """{"command":"ls -la"}"""))
        p.onAgentEvent(AgentEvent.ResponseChunk("看到了。"))

        val msgs = p.items.value.filterIsInstance<ChatItem.AssistantMessage>()
        assertEquals(2, msgs.size)
        assertEquals("先看目录。", msgs[0].text)
        assertFalse("前一段应已被工具卡收口", msgs[0].streaming)
        assertEquals("看到了。", msgs[1].text)
        assertTrue(msgs[1].streaming)
    }

    // ---- 权威对齐 ------------------------------------------------------

    @Test
    fun `Complete_权威文本是已显示的前缀时_补齐尾部`() = runTest {
        val p = newPresenter()
        p.onAgentEvent(AgentEvent.ResponseChunk("你好，世"))
        p.onAgentEvent(AgentEvent.Complete(summary = "你好，世界！", totalIterations = 1, totalToolCalls = 0, durationMs = 10))
        val bubble = p.items.value.filterIsInstance<ChatItem.AssistantMessage>().single()
        assertEquals("你好，世界！", bubble.text)
        assertFalse(bubble.streaming)
    }

    @Test
    fun `Complete_正文走样时_整体覆盖`() = runTest {
        val p = newPresenter()
        p.onAgentEvent(AgentEvent.ResponseChunk("流式期间走样的内容"))
        p.onAgentEvent(AgentEvent.Complete(summary = "权威定稿", totalIterations = 2, totalToolCalls = 1, durationMs = 10))
        val bubble = p.items.value.filterIsInstance<ChatItem.AssistantMessage>().single()
        assertEquals("权威定稿", bubble.text)
        assertFalse(bubble.streaming)
    }

    @Test
    fun `Complete_权威为空_保留已显示内容`() = runTest {
        val p = newPresenter()
        p.onAgentEvent(AgentEvent.ResponseChunk("已显示"))
        p.onAgentEvent(AgentEvent.Complete(summary = "", totalIterations = 1, totalToolCalls = 0, durationMs = 1))
        val bubble = p.items.value.filterIsInstance<ChatItem.AssistantMessage>().single()
        assertEquals("已显示", bubble.text)
        assertFalse(bubble.streaming)
    }

    @Test
    fun `Complete_无流式气泡时_追加定稿气泡_覆盖非流式宿主`() = runTest {
        val p = newPresenter()
        p.onAgentEvent(AgentEvent.Complete(summary = "一次性给全文", totalIterations = 1, totalToolCalls = 0, durationMs = 1))
        val bubble = p.items.value.filterIsInstance<ChatItem.AssistantMessage>().single()
        assertEquals("一次性给全文", bubble.text)
        assertFalse(bubble.streaming)
    }

    // ---- 工具卡 --------------------------------------------------------

    @Test
    fun `工具卡生命周期_运行到成功与失败`() = runTest {
        val p = newPresenter()
        p.onAgentEvent(AgentEvent.ToolCallStart("c1", "terminal_exec", """{"command":"ls -la"}"""))
        p.onAgentEvent(AgentEvent.ToolCallStart("c2", "fs_read", """{"path":"/etc/hosts"}"""))

        val running = p.items.value.filterIsInstance<ChatItem.ActionItem>()
        assertEquals(2, running.size)
        assertEquals("ls -la", running[0].summary)
        assertEquals(ChatItem.ActionState.RUNNING, running[0].state)

        p.onAgentEvent(AgentEvent.ToolCallComplete("c1", "terminal_exec", ToolResult.success("total 0"), 5))
        p.onAgentEvent(AgentEvent.ToolCallComplete("c2", "fs_read", ToolResult.failure("no such file"), 3))

        val done = p.items.value.filterIsInstance<ChatItem.ActionItem>()
        assertEquals(ChatItem.ActionState.OK, done[0].state)
        assertEquals("total 0", done[0].outputTail)
        assertEquals(ChatItem.ActionState.FAILED, done[1].state)
        assertEquals("no such file", done[1].outputTail)
    }

    @Test
    fun `job工具声明jobId_动作卡转作业卡保持RUNNING`() = runTest {
        val p = newPresenter()
        p.onAgentEvent(AgentEvent.ToolCallStart("c1", "job_start", """{"command":"python3 -m http.server"}"""))
        p.onAgentEvent(
            AgentEvent.ToolCallComplete(
                "c1", "job_start",
                ToolResult.success("作业已启动", data = """{"jobId":"job_9"}"""),
                12,
            ),
        )
        val card = p.items.value.filterIsInstance<ChatItem.ActionItem>().single()
        assertEquals(ChatItem.ActionState.RUNNING, card.state)
        assertEquals("job_9", card.jobId)
    }

    @Test
    fun `未知callId的完成事件_兜底补结果卡`() = runTest {
        val p = newPresenter()
        p.onAgentEvent(AgentEvent.ToolCallComplete("ghost", "http_get", ToolResult.success("ok"), 1))
        val card = p.items.value.filterIsInstance<ChatItem.ActionItem>().single()
        assertEquals(ChatItem.ActionState.OK, card.state)
        assertEquals("http_get", card.toolName)
    }

    // ---- 参数摘要 ------------------------------------------------------

    @Test
    fun `参数摘要_取command等惯例字段`() = runTest {
        val p = newPresenter()
        p.onAgentEvent(AgentEvent.ToolCallStart("id1", "terminal_exec", """{"command":"ls -la","timeout":3}"""))
        val card = p.items.value.filterIsInstance<ChatItem.ActionItem>().single()
        assertEquals("ls -la", card.summary)
    }

    @Test
    fun `参数摘要_超长截断到上限`() = runTest {
        val p = newPresenter()
        val long = "x".repeat(500)
        p.onAgentEvent(AgentEvent.ToolCallStart("id2", "fs_write", """{"path":"$long"}"""))
        val card = p.items.value.filterIsInstance<ChatItem.ActionItem>().single()
        assertEquals(ChatPresenter.MAX_SUMMARY_CHARS, card.summary.length)
    }

    @Test
    fun `extractJobId_合法与非法输入`() {
        assertEquals("job_9", ChatPresenter.extractJobId("""{"jobId":"job_9"}"""))
        assertNull(ChatPresenter.extractJobId(null))
        assertNull(ChatPresenter.extractJobId(""))
        assertNull(ChatPresenter.extractJobId("not-json"))
        assertNull(ChatPresenter.extractJobId("""{"other":1}"""))
    }

    // ---- 审批卡 --------------------------------------------------------

    @Test
    fun `审批上卡与裁决回填`() = runTest {
        val p = newPresenter()
        p.onApprovalRequest(approval())
        val card = p.items.value.filterIsInstance<ChatItem.ApprovalItem>().single()
        assertEquals("req-1", card.requestId)
        assertFalse(card.resolved)
        assertNull(card.verdict)

        p.onApprovalResolved("req-1", "ALLOW_ONCE")
        val resolved = p.items.value.filterIsInstance<ChatItem.ApprovalItem>().single()
        assertTrue(resolved.resolved)
        assertEquals("ALLOW_ONCE", resolved.verdict)
    }

    @Test
    fun `同一requestId重复上卡_去重`() = runTest {
        val p = newPresenter()
        p.onApprovalRequest(approval())
        p.onApprovalRequest(approval()) // StateFlow 重放 / 双订阅
        assertEquals(1, p.items.value.filterIsInstance<ChatItem.ApprovalItem>().size)
    }

    @Test
    fun `裁决回填未知requestId_无副作用`() = runTest {
        val p = newPresenter()
        p.onApprovalRequest(approval())
        p.onApprovalResolved("no-such-id", "DENY")
        val card = p.items.value.filterIsInstance<ChatItem.ApprovalItem>().single()
        assertFalse("不存在的裁决不应误伤未决卡", card.resolved)
    }

    // ---- cancel 回填 ---------------------------------------------------

    @Test
    fun `cancel_未决审批全量DENY并追加已取消`() = runTest {
        val p = newPresenter()
        p.onAgentEvent(AgentEvent.ResponseChunk("处理中…"))
        p.onApprovalRequest(approval("r1"))
        p.onApprovalRequest(approval("r2"))
        p.onApprovalResolved("r1", "ALLOW_ONCE") // r1 已决，不应被改写

        p.cancel()

        val approvals = p.items.value.filterIsInstance<ChatItem.ApprovalItem>()
        assertEquals("ALLOW_ONCE", approvals.first { it.requestId == "r1" }.verdict)
        val r2 = approvals.first { it.requestId == "r2" }
        assertTrue(r2.resolved)
        assertEquals("DENY", r2.verdict)

        val bubble = p.items.value.filterIsInstance<ChatItem.AssistantMessage>().single()
        assertFalse("流式气泡应收口", bubble.streaming)

        val note = p.items.value.last() as ChatItem.SystemNote
        assertEquals("已取消", note.text)
        assertEquals(ChatItem.Level.WARN, note.level)
    }

    @Test
    fun `Aborted事件与cancel双路径_尾注不重复`() = runTest {
        val p = newPresenter()
        p.cancel()
        p.onAgentEvent(AgentEvent.Aborted)
        val notes = p.items.value.filterIsInstance<ChatItem.SystemNote>()
        assertEquals(1, notes.size)
        assertEquals("已取消", notes.single().text)
    }

    @Test
    fun `Error事件_收口并追加ERROR注`() = runTest {
        val p = newPresenter()
        p.onAgentEvent(AgentEvent.ResponseChunk("部分内容"))
        p.onAgentEvent(AgentEvent.Error(message = "连接中断", recoverable = false))
        val bubble = p.items.value.filterIsInstance<ChatItem.AssistantMessage>().single()
        assertFalse(bubble.streaming)
        val note = p.items.value.last() as ChatItem.SystemNote
        assertEquals("出错了：连接中断", note.text)
        assertEquals(ChatItem.Level.ERROR, note.level)
    }

    // ---- 持久化 --------------------------------------------------------

    @Test
    fun `dirty经500ms去抖后自动落盘`() = runTest {
        val dir = newDir()
        try {
            val p = ChatPresenter(
                scope = backgroundScope,
                store = ChatHistoryStore(dir),
                clock = { 1L },
            )
            runCurrent() // 让持久化收集器完成订阅（初值 0 被跳过，不会空写历史）
            p.addUserMessage("持久化我")
            advanceTimeBy(ChatPresenter.DEBOUNCE_MS - 1)
            runCurrent()
            assertFalse("去抖窗口内不应落盘", ChatHistoryStore(dir).file.isFile)

            advanceTimeBy(1)
            runCurrent()
            val loaded = ChatHistoryStore(dir).load()
            assertEquals("持久化我", (loaded.single() as ChatItem.UserMessage).text)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `flush_立即落盘`() = runTest {
        val dir = newDir()
        try {
            val p = ChatPresenter(scope = backgroundScope, store = ChatHistoryStore(dir), clock = { 1L })
            p.addUserMessage("立刻落盘")
            p.flush()
            assertTrue(ChatHistoryStore(dir).file.isFile)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `loadHistory_仅空会话时生效`() = runTest {
        val dir = newDir()
        try {
            val store = ChatHistoryStore(dir)
            store.save(listOf(ChatItem.UserMessage(id = "old", ts = 1, text = "历史消息")))
            val p = ChatPresenter(scope = backgroundScope, store = store, clock = { 1L })

            assertTrue(p.loadHistory())
            assertEquals("历史消息", (p.items.value.single() as ChatItem.UserMessage).text)

            // 已有内容时不覆盖（yl-ai attach 的一次性装载语义）
            store.save(listOf(ChatItem.UserMessage(id = "new", ts = 2, text = "新历史")))
            assertFalse(p.loadHistory())
            assertEquals("历史消息", (p.items.value.single() as ChatItem.UserMessage).text)
        } finally {
            dir.deleteRecursively()
        }
    }
}
