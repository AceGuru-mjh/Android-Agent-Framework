package com.androidguru.agent.memory

import com.androidguru.agent.tools.AgentTool
import com.androidguru.agent.tools.ToolErrorCode
import com.androidguru.agent.tools.ToolRequest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆三件套工具测试：schema 校验 / 参数容错 / 语义正确。
 */
class MemoryToolsTest {

    private fun call(tool: AgentTool, args: String): com.androidguru.agent.tools.ToolResult = runBlocking {
        tool.execute(ToolRequest(callId = "c1", toolId = tool.id, arguments = args))
    }

    // ------------------------------------------------------------------
    // memory_save
    // ------------------------------------------------------------------

    @Test
    fun `保存记忆并返回回执`() {
        val store = InMemoryMemoryStore()
        val result = call(
            MemorySaveTool(store),
            """{"content":"用户叫小明","kind":"preference","importance":85,"tags":["user"]}""",
        )

        assertTrue(result.ok)
        assertTrue(result.content.contains(store.all().single().id)) // 回执带 id（供 memory_forget 寻址）
        assertTrue(result.content.contains("偏好"))
        assertTrue(result.content.contains("85"))
        assertEquals(1, store.all().size)
        assertEquals(MemoryKind.PREFERENCE, store.all().single().kind)
        assertEquals(listOf("user"), store.all().single().tags)
    }

    @Test
    fun `未知类别回落 fact 与重要度钳制`() {
        val store = InMemoryMemoryStore()
        val result = call(MemorySaveTool(store), """{"content":"某事实","kind":"nonsense","importance":999}""")

        assertTrue(result.ok)
        val saved = store.all().single()
        assertEquals(MemoryKind.FACT, saved.kind)
        assertEquals(100, saved.importance)
    }

    @Test
    fun `缺 content 校验失败`() {
        val result = call(MemorySaveTool(InMemoryMemoryStore()), """{"kind":"fact"}""")
        assertFalse(result.ok)
        assertTrue(result.content.contains("content"))
    }

    @Test
    fun `非法 JSON 报校验错误`() {
        val result = call(MemorySaveTool(InMemoryMemoryStore()), "not-json")
        assertFalse(result.ok)
    }

    @Test
    fun `超长内容被截断`() {
        val store = InMemoryMemoryStore()
        call(MemorySaveTool(store), """{"content":"${"长".repeat(800)}"}""")
        assertEquals(MemorySaveTool.MAX_CONTENT_CHARS, store.all().single().content.length)
    }

    // ------------------------------------------------------------------
    // memory_search
    // ------------------------------------------------------------------

    @Test
    fun `按关键词检索并渲染`() {
        val store = InMemoryMemoryStore()
        store.append(MemoryRecord(id = "", kind = MemoryKind.FACT, content = "用户名是小明", importance = 90))
        store.append(MemoryRecord(id = "", kind = MemoryKind.LESSON, content = "PTY 哨兵要行首锚定", importance = 70))

        val result = call(MemorySearchTool(MemoryRecall(store)), """{"query":"用户 名字"}""")
        assertTrue(result.ok)
        assertTrue(result.content.contains("小明"))
        assertFalse(result.content.contains("PTY"))
    }

    @Test
    fun `无命中返回空提示`() {
        val result = call(MemorySearchTool(MemoryRecall(InMemoryMemoryStore())), """{"query":"不存在的东西"}""")
        assertTrue(result.ok)
        assertTrue(result.content.contains("没有匹配"))
    }

    // ------------------------------------------------------------------
    // memory_forget
    // ------------------------------------------------------------------

    @Test
    fun `按 id 删除记忆`() {
        val store = InMemoryMemoryStore()
        val saved = store.append(MemoryRecord(id = "", kind = MemoryKind.FACT, content = "待删除"))

        val result = call(MemoryForgetTool(store), """{"id":"${saved.id}"}""")
        assertTrue(result.ok)
        assertEquals(0, store.all().size)
    }

    @Test
    fun `删除不存在的 id 报 NOT_FOUND`() {
        val result = call(MemoryForgetTool(InMemoryMemoryStore()), """{"id":"m404"}""")
        assertFalse(result.ok)
        assertEquals(ToolErrorCode.NOT_FOUND, result.error?.code)
    }

    @Test
    fun `三件套 id 与 schema 契约稳定`() {
        val store = InMemoryMemoryStore()
        val save = MemorySaveTool(store)
        val search = MemorySearchTool(MemoryRecall(store))
        val forget = MemoryForgetTool(store)

        assertEquals("memory_save", save.id)
        assertEquals("memory_search", search.id)
        assertEquals("memory_forget", forget.id)

        assertTrue(save.parameters.validate("""{"content":"x"}""").isEmpty())
        assertTrue(save.parameters.validate("{}").isNotEmpty()) // content 必填
        assertTrue(search.parameters.validate("{}").isNotEmpty())
        assertTrue(forget.parameters.validate("""{"id":"m1"}""").isEmpty())
    }
}
