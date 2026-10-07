package com.androidguru.agent.memory

import com.androidguru.agent.core.session.InMemoryConversationMemory
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 抽取器测试：LLM 输出宽容解析 / 回退启发式 / 去重合并 / 相似度。
 */
class MemoryExtractorTest {

    private var now = 1_000_000L
    private val clock = { now }

    /** 可编程 chat 响应的 Fake（抽取器只用 chat，不用 chatStream）。 */
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

    private class ChatThrows : LlmClient {
        override suspend fun chat(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Double?,
            maxTokens: Int?,
            toolChoice: ToolChoiceSpec?,
        ): LlmResponse = throw RuntimeException("LLM 不可用")

        override fun chatStream(
            messages: List<LlmMessage>,
            tools: List<ToolDefinition>,
            temperature: Double?,
            maxTokens: Int?,
            toolChoice: ToolChoiceSpec?,
        ): Flow<LlmStreamChunk> = flowOf()
    }

    private fun conversation(vararg turns: String): InMemoryConversationMemory {
        val memory = InMemoryConversationMemory()
        // turns: user, assistant, user, assistant, ...
        turns.forEachIndexed { i, text ->
            if (i % 2 == 0) memory.appendUser(text) else memory.appendAssistant(text)
        }
        return memory
    }

    // ------------------------------------------------------------------
    // LLM 路径
    // ------------------------------------------------------------------

    @Test
    fun `LLM 严格 JSON 输出被完整解析`() = runTest {
        val extractor = MemoryExtractor(ChatScripted("""[{"kind":"preference","content":"用户偏好 Kotlin","importance":80,"tags":["lang"]}]"""), clock)
        val records = extractor.extract(conversation("记住我喜欢 Kotlin", "好的，已记住").snapshot(), "s1")

        assertEquals(1, records.size)
        assertEquals(MemoryKind.PREFERENCE, records.single().kind)
        assertEquals("用户偏好 Kotlin", records.single().content)
        assertEquals(80, records.single().importance)
        assertEquals("s1", records.single().sessionId)
    }

    @Test
    fun `代码围栏与前后解释文字被容忍`() = runTest {
        val raw = """
            以下是抽取结果：
            ```json
            [{"kind":"fact","content":"项目用 Gradle 构建","importance":50}]
            ```
            以上共 1 条。
        """.trimIndent()
        val extractor = MemoryExtractor(ChatScripted(raw), clock)
        val records = extractor.extract(conversation("a", "b").snapshot(), null)

        assertEquals(1, records.size)
        assertEquals("项目用 Gradle 构建", records.single().content)
    }

    @Test
    fun `空数组与非法输出触发启发式回退`() = runTest {
        // LLM 输出 []（没有值得记的）→ 回退启发式（保证至少留下任务结论）
        val extractor = MemoryExtractor(ChatScripted("[]"), clock)
        val records = extractor.extract(conversation("帮我调研 JSON 库", "结论：kotlinx.serialization 最合适").snapshot(), "s9")

        assertEquals(1, records.size)
        assertEquals(MemoryKind.TASK_OUTCOME, records.single().kind)
        assertTrue(records.single().content.contains("调研 JSON 库"))
        assertTrue(records.single().content.contains("kotlinx.serialization"))
    }

    @Test
    fun `LLM 异常回退启发式`() = runTest {
        val extractor = MemoryExtractor(ChatThrows(), clock)
        val records = extractor.extract(conversation("任务一", "结论一").snapshot(), null)

        assertEquals(1, records.size)
        assertEquals(MemoryKind.TASK_OUTCOME, records.single().kind)
    }

    @Test
    fun `无 client 纯启发式路径`() = runTest {
        val extractor = MemoryExtractor(client = null, clock = clock)
        val records = extractor.extract(conversation("只有目标没有结论", "").snapshot(), null)

        assertEquals(1, records.size)
        assertEquals(MemoryKind.SESSION_SUMMARY, records.single().kind)
        assertTrue(records.single().content.contains("只有目标"))
    }

    @Test
    fun `超出单次上限的记忆被截断`() = runTest {
        val raw = (1..10).joinToString(",", "[", "]") { """{"kind":"fact","content":"事实$it","importance":50}""" }
        val extractor = MemoryExtractor(ChatScripted(raw), clock, maxMemories = 4)
        assertEquals(4, extractor.extract(conversation("a", "b").snapshot(), null).size)
    }

    // ------------------------------------------------------------------
    // 去重合并
    // ------------------------------------------------------------------

    @Test
    fun `重复记忆升温而不落库`() = runTest {
        val store = InMemoryMemoryStore()
        val existing = store.append(
            MemoryRecord(
                id = "", kind = MemoryKind.PREFERENCE,
                content = "用户偏好 Kotlin 语言", importance = 60,
                createdAtMs = now, lastAccessedAtMs = now,
            ),
        )

        val extractor = MemoryExtractor(
            ChatScripted("""[{"kind":"preference","content":"用户偏好 Kotlin 语言","importance":70}]"""),
            clock,
        )
        val added = extractor.extractAndStore(conversation("x", "y").snapshot(), null, store)

        assertEquals(0, added.size) // 近重复：不新增
        assertEquals(1, store.all().size)
        val merged = store.all().single()
        assertEquals(70, merged.importance) // 重要度取高
        assertEquals(existing.id, merged.id)
        assertEquals(1, merged.accessCount) // 升温
    }

    @Test
    fun `不相似记忆正常入库`() = runTest {
        val store = InMemoryMemoryStore()
        store.append(MemoryRecord(id = "", kind = MemoryKind.PREFERENCE, content = "用户偏好 Kotlin 语言"))

        val extractor = MemoryExtractor(
            ChatScripted("""[{"kind":"lesson","content":"PTY 哨兵必须行首锚定","importance":65}]"""),
            clock,
        )
        val added = extractor.extractAndStore(conversation("x", "y").snapshot(), null, store)

        assertEquals(1, added.size)
        assertEquals(2, store.all().size)
    }

    @Test
    fun `相似度判定只对同类记忆生效`() {
        val extractor = MemoryExtractor(null, clock)
        val a = MemoryRecord(id = "m1", kind = MemoryKind.FACT, content = "用户偏好 Kotlin 语言", createdAtMs = now)
        val b = MemoryRecord(id = "m2", kind = MemoryKind.PREFERENCE, content = "用户偏好 Kotlin 语言", createdAtMs = now)
        assertFalse(extractor.isSimilar(a, b)) // 类别不同 → 不算重复

        val c = MemoryRecord(id = "m3", kind = MemoryKind.FACT, content = "用户偏好 Kotlin 语言", createdAtMs = now)
        assertTrue(extractor.isSimilar(a, c))
    }
}
