package com.androidguru.agent.chat

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡片模型序列化往返 —— 持久化层正确性的地基：
 * 任何一张存进 chat-history.json 的卡片都必须能无损读回来。
 */
class ChatItemTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    private inline fun <reified T : ChatItem> roundTrip(item: T): ChatItem {
        val encoded = json.encodeToString<ChatItem>(item)
        return json.decodeFromString<ChatItem>(encoded)
    }

    @Test
    fun `五类卡片_序列化往返无损`() {
        val items: List<ChatItem> = listOf(
            ChatItem.UserMessage(id = "i1", ts = 1_700_000_000_000, text = "帮我把服务跑起来"),
            ChatItem.AssistantMessage(id = "i2", ts = 1_700_000_000_001, text = "好的，我先检查端口占用。", streaming = true),
            ChatItem.AssistantMessage(id = "i3", ts = 1_700_000_000_002, text = "已完成。"),
            ChatItem.ActionItem(
                id = "i4",
                ts = 1_700_000_000_003,
                toolName = "terminal_exec",
                summary = "python3 -m http.server 8080",
                state = ChatItem.ActionState.FAILED,
                outputTail = "Address already in use",
                jobId = "job_x1",
            ),
            ChatItem.ApprovalItem(
                id = "i5",
                ts = 1_700_000_000_004,
                requestId = "req-1",
                toolName = "fs_write",
                title = "写入文件",
                detail = "/data/config.txt",
                impact = "覆盖 120 字符",
                resolved = true,
                verdict = "ALLOW_ONCE",
            ),
            ChatItem.SystemNote(id = "i6", ts = 1_700_000_000_005, text = "已取消", level = ChatItem.Level.WARN),
        )

        val decoded = items.map { roundTrip(it) }
        assertEquals(items, decoded)
    }

    @Test
    fun `判别字段用短名_便于人工检查历史文件`() {
        val cases = mapOf(
            ChatItem.UserMessage(text = "hi") to "\"user\"",
            ChatItem.AssistantMessage(text = "ok") to "\"assistant\"",
            ChatItem.ActionItem(toolName = "t") to "\"action\"",
            ChatItem.ApprovalItem(requestId = "r", toolName = "t", title = "t", detail = "d", impact = "i") to "\"approval\"",
            ChatItem.SystemNote(text = "n") to "\"note\"",
        )
        for ((item, tag) in cases) {
            val encoded = json.encodeToString<ChatItem>(item)
            assertTrue("应包含短名判别字段 $tag：$encoded", encoded.contains("\"type\":$tag"))
        }
    }

    @Test
    fun `缺省字段解码_用默认值补齐`() {
        val decoded = json.decodeFromString<ChatItem>(
            """{"type":"user","text":"只有正文"}""",
        )
        val user = decoded as ChatItem.UserMessage
        assertEquals("只有正文", user.text)
        assertTrue("id 应自动生成", user.id.isNotBlank())
        assertTrue("ts 应自动生成", user.ts > 0)
    }

    @Test
    fun `默认值不落盘_文件保持紧凑`() {
        val encoded = json.encodeToString<ChatItem>(
            ChatItem.AssistantMessage(id = "i7", ts = 42, text = "定稿", streaming = false),
        )
        assertTrue("streaming=false 不应写入：$encoded", !encoded.contains("streaming"))
        assertTrue("null 字段不应写入：$encoded", !encoded.contains("null"))
    }

    @Test
    fun `ActionState 与 Level 枚举_按名字往返`() {
        ChatItem.ActionState.entries.forEachIndexed { i, state ->
            val decoded = roundTrip(ChatItem.ActionItem(id = "s$i", ts = i.toLong(), toolName = "t", state = state))
            assertEquals(state, (decoded as ChatItem.ActionItem).state)
        }
        ChatItem.Level.entries.forEachIndexed { i, level ->
            val decoded = roundTrip(ChatItem.SystemNote(id = "n$i", ts = i.toLong(), text = "n", level = level))
            assertEquals(level, (decoded as ChatItem.SystemNote).level)
        }
    }

    @Test
    fun `审批卡未决态_verdict为null可往返`() {
        val pending = ChatItem.ApprovalItem(
            id = "i8",
            ts = 1_700_000_000_009,
            requestId = "req-2",
            toolName = "terminal_exec",
            title = "执行命令",
            detail = "rm -rf build",
            impact = "删除目录",
        )
        assertNull(pending.verdict)
        val decoded = roundTrip(pending) as ChatItem.ApprovalItem
        assertNull("未决卡不应被读成已决", decoded.verdict)
        assertEquals(false, decoded.resolved)
    }

    @Test
    fun `id生成_短且不重复`() {
        val a = ChatItem.newId()
        val b = ChatItem.newId()
        assertNotEquals(a, b)
        assertTrue(a.length <= 10)
    }
}
