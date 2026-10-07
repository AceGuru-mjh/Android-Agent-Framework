package com.androidguru.agent.memory

import com.androidguru.agent.core.context.ContextCompressor
import com.androidguru.agent.core.context.SlidingWindowCompressor
import com.androidguru.agent.llm.LlmClient
import com.androidguru.agent.llm.LlmMessage
import com.androidguru.agent.llm.LlmResponse
import com.androidguru.agent.llm.LlmStreamChunk
import com.androidguru.agent.llm.ToolChoiceSpec
import com.androidguru.agent.llm.ToolDefinition
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 压缩捕获器测试：压缩发生时捕获被裁内容 / 无压缩不捕获 / 捕获异常不外溢。
 */
class MemoryCapturingCompressorTest {

    private class ChatScripted(private val reply: String?) : LlmClient {
        override suspend fun chat(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Double?,
            maxTokens: Int?,
            toolChoice: ToolChoiceSpec?,
        ) = LlmResponse(content = reply)

        override fun chatStream(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Double?,
            maxTokens: Int?,
            toolChoice: ToolChoiceSpec?,
        ): Flow<LlmStreamChunk> = flowOf()
    }

    private class ThrowingCompressor : ContextCompressor {
        override suspend fun compress(
            messages: List<LlmMessage>,
            maxTokens: Int,
            preserveRecentTurns: Int,
        ): List<LlmMessage> = throw RuntimeException("压缩器坏了")
    }

    private fun history(turns: Int): List<LlmMessage> {
        val messages = mutableListOf<LlmMessage>()
        repeat(turns) { i ->
            messages += LlmMessage.User("用户消息 $i：讨论主题 ${if (i == 0) "部署架构" else "细节"}")
            messages += LlmMessage.Assistant("回复 $i")
        }
        return messages
    }

    @Test
    fun `压缩发生时被裁内容留为情景记忆`() = runTest {
        val store = InMemoryMemoryStore()
        val compressor = MemoryCapturingCompressor(
            delegate = SlidingWindowCompressor(),
            store = store,
            client = null, // 确定性截取路径
        )

        val messages = history(8)
        val compressed = compressor.compress(messages, maxTokens = 1, preserveRecentTurns = 2)

        assertTrue(compressed != null && compressed.size < messages.size)
        assertEquals(1, store.all().size)
        val captured = store.all().single()
        assertEquals(MemoryKind.SESSION_SUMMARY, captured.kind)
        assertTrue(captured.tags.contains("compaction"))
        assertTrue(captured.content.contains("用户消息"))
    }

    @Test
    fun `无需压缩时不捕获`() = runTest {
        val store = InMemoryMemoryStore()
        val compressor = MemoryCapturingCompressor(SlidingWindowCompressor(), store)

        val messages = history(2)
        compressor.compress(messages, maxTokens = 100_000, preserveRecentTurns = 5)

        assertEquals(0, store.all().size)
    }

    @Test
    fun `LLM 摘要捕获成功时存摘要正文`() = runTest {
        val store = InMemoryMemoryStore()
        val compressor = MemoryCapturingCompressor(
            delegate = SlidingWindowCompressor(),
            store = store,
            client = ChatScripted("早期讨论了部署架构：选定 Termux + proot。"),
        )

        val messages = history(6)
        compressor.compress(messages, maxTokens = 1, preserveRecentTurns = 1)

        val captured = store.all().single()
        assertTrue(captured.content.contains("Termux"))
        assertTrue(!captured.content.contains("会话早期")) // 走的是 LLM 摘要而非确定性截取
    }

    @Test
    fun `捕获异常不影响压缩主流程`() = runTest {
        // store 抛异常 → 捕获吞掉，压缩结果照常返回
        val brokenStore = object : MemoryStore {
            override fun append(record: MemoryRecord): MemoryRecord = throw RuntimeException("存储不可写")
            override fun update(record: MemoryRecord): Boolean = throw RuntimeException("存储不可写")
            override fun remove(id: String): Boolean = false
            override fun all(): List<MemoryRecord> = emptyList()
            override fun clear() = throw RuntimeException("存储不可写")
        }
        val compressor = MemoryCapturingCompressor(SlidingWindowCompressor(), brokenStore)

        val messages = history(6)
        val compressed = compressor.compress(messages, maxTokens = 1, preserveRecentTurns = 2)

        assertTrue(compressed != null) // 压缩不受影响
    }

    @Test
    fun `委托压缩器异常正常上抛`() = runTest {
        val compressor = MemoryCapturingCompressor(ThrowingCompressor(), InMemoryMemoryStore())
        try {
            compressor.compress(history(4), maxTokens = 1, preserveRecentTurns = 2)
            throw AssertionError("委托异常应当上抛")
        } catch (e: RuntimeException) {
            assertEquals("压缩器坏了", e.message)
        }
    }
}

/**
 * MemorySystem 门面测试：装配 / workspace 文件化 / 会话沉淀 / 提示词。
 */
class MemorySystemTest {

    @Test
    fun `create 装配三件套工具与注入器`() = runTest {
        val system = MemorySystem.create(client = null)

        assertEquals(3, system.tools.size)
        assertEquals(listOf("memory_save", "memory_search", "memory_forget"), system.tools.map { it.id })
        assertTrue(system.promptSuffix().contains("记忆纪律"))
    }

    @get:org.junit.Rule
    val tmp = org.junit.rules.TemporaryFolder()

    @Test
    fun `workspace 文件化存储跨实例存续`() {
        val workspace = tmp.newFolder().toPath()
        val first = MemorySystem.create(workspace = workspace)
        first.store.append(MemoryRecord(id = "", kind = MemoryKind.FACT, content = "用户叫小明"))

        // 新实例（进程重启模拟）恢复记忆
        val second = MemorySystem.create(workspace = workspace)
        assertEquals(1, second.store.all().size)
        assertTrue(second.store.all().single().content.contains("小明"))
    }

    @Test
    fun `onSessionComplete 启发式沉淀任务结论`() = runTest {
        val system = MemorySystem.create(client = null) // 无 LLM → 启发式
        val conversation = com.androidguru.agent.core.session.InMemoryConversationMemory()
        conversation.appendUser("帮我把数据导出为 CSV")
        conversation.appendAssistant("已完成导出，共 42 行")

        val added = system.onSessionComplete(conversation, "s1")

        assertEquals(1, added.size)
        assertEquals(MemoryKind.TASK_OUTCOME, added.single().kind)
        assertTrue(added.single().content.contains("CSV"))
        assertEquals(1, system.store.all().size)
    }

    @Test
    fun `extractionEnabled=false 时不抽取`() = runTest {
        val system = MemorySystem.create(config = MemoryConfig(extractionEnabled = false))
        val conversation = com.androidguru.agent.core.session.InMemoryConversationMemory()
        conversation.appendUser("任务")
        conversation.appendAssistant("结论")

        assertTrue(system.onSessionComplete(conversation).isEmpty())
    }

    @Test
    fun `contextInjectorFor 带会话信号召回`() = runTest {
        val system = MemorySystem.create()
        system.store.append(MemoryRecord(id = "", kind = MemoryKind.PREFERENCE, content = "用户偏好深色主题", importance = 90))
        val conversation = com.androidguru.agent.core.session.InMemoryConversationMemory()
        conversation.appendUser("我需要主题设置")

        val context = system.contextInjectorFor(conversation).provideContext("s1")
        assertTrue(context.contains("深色主题"))
    }

    @Test
    fun `installSessionEndHook 触发自动沉淀`() = runTest {
        val system = MemorySystem.create()
        val conversation = com.androidguru.agent.core.session.InMemoryConversationMemory()
        conversation.appendUser("目标 X")
        conversation.appendAssistant("结论 Y")

        val hooks = com.androidguru.agent.tools.hook.HookRegistry()
        system.installSessionEndHook(hooks, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), conversation, "s-hook")

        hooks.dispatch(com.androidguru.agent.tools.hook.HookEvent.SessionEnd("s-hook"))
        // Unconfined 立即执行；存储应已沉淀
        assertEquals(1, system.store.all().size)
        assertTrue(system.store.all().single().content.contains("结论 Y"))
    }
}
