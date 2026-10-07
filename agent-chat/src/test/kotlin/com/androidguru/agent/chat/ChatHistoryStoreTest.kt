package com.androidguru.agent.chat

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 聊天历史持久化 —— 原子写 / 200 上限 / 4000 截尾 / 容错装载。
 * 全部走真实文件（runBlocking，不用虚拟时钟 —— 文件 IO 与虚拟时间无益且易踩坑）。
 */
class ChatHistoryStoreTest {

    private fun newStore(): Pair<ChatHistoryStore, File> {
        val dir = java.nio.file.Files.createTempDirectory("agent-chat-store").toFile()
        return ChatHistoryStore(dir) to dir
    }

    private fun fixture(): List<ChatItem> = listOf(
        ChatItem.UserMessage(id = "u1", ts = 1, text = "起个服务"),
        ChatItem.AssistantMessage(id = "a1", ts = 2, text = "正在检查端口。", streaming = true),
        ChatItem.ActionItem(
            id = "ac1",
            ts = 3,
            toolName = "job_start",
            summary = "python3 -m http.server",
            state = ChatItem.ActionState.RUNNING,
            jobId = "job_1",
        ),
        ChatItem.ApprovalItem(
            id = "ap1",
            ts = 4,
            requestId = "req_1",
            toolName = "fs_write",
            title = "写入文件",
            detail = "/tmp/a.txt",
            impact = "覆盖",
        ),
        ChatItem.SystemNote(id = "n1", ts = 5, text = "已取消", level = ChatItem.Level.WARN),
    )

    @Test
    fun `保存后装载_五类卡片往返一致`() = runBlocking {
        val (store, dir) = newStore()
        try {
            val items = fixture()
            assertTrue("保存应成功", store.save(items))
            val loaded = store.load()
            // 唯一有意差异：流式气泡重放时归位为非流式（重启后不存在"仍在流式"）
            assertEquals(
                items.map { if (it is ChatItem.AssistantMessage) it.copy(streaming = false) else it },
                loaded,
            )
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `超过200条_只保留最近200条`() = runBlocking {
        val (store, dir) = newStore()
        try {
            val items = (1..210).map {
                ChatItem.UserMessage(id = "u$it", ts = it.toLong(), text = "msg-$it")
            }
            store.save(items)
            val loaded = store.load()
            assertEquals(ChatHistoryStore.MAX_ITEMS, loaded.size)
            assertEquals("msg-11", (loaded.first() as ChatItem.UserMessage).text)
            assertEquals("msg-210", (loaded.last() as ChatItem.UserMessage).text)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `outputTail超4000字符_持久化时截尾保尾部`() = runBlocking {
        val (store, dir) = newStore()
        try {
            val head = "HEAD_NOISE".repeat(300) // 3000 字符头部噪音
            val tail = "TAIL_MARK".repeat(200) // 1800 字符尾部
            val long = head + tail
            assertEquals(4800, long.length)

            store.save(
                listOf(
                    ChatItem.ActionItem(
                        id = "ac-long",
                        ts = 1,
                        toolName = "terminal_exec",
                        summary = "cat big.log",
                        state = ChatItem.ActionState.OK,
                        outputTail = long,
                    ),
                ),
            )
            // 内存里的原值不被改动（截断只发生在持久化投影）
            val loaded = store.load().single() as ChatItem.ActionItem
            assertEquals(ChatHistoryStore.MAX_OUTPUT_CHARS, loaded.outputTail!!.length)
            // 精确断言：截掉头部、保留最后 4000 字符
            assertEquals(long.takeLast(ChatHistoryStore.MAX_OUTPUT_CHARS), loaded.outputTail)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `损坏文件_装载返回空而不是崩溃`() = runBlocking {
        val (store, dir) = newStore()
        try {
            store.file.writeText("""{"version":1,"items":[{"type":"use""") // 半截 JSON
            assertEquals(emptyList<ChatItem>(), store.load())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `单条损坏_跳过该条保留其余`() = runBlocking {
        val (store, dir) = newStore()
        try {
            val good = """{"type":"user","id":"ok1","ts":1,"text":"好卡"}"""
            val bad = """{"type":"mystery","id":"x"}""" // 未知卡片类型
            store.file.parentFile.mkdirs()
            store.file.writeText("""{"version":1,"savedAt":0,"items":[$bad,$good]}""")
            val loaded = store.load()
            assertEquals(1, loaded.size)
            assertEquals("ok1", loaded.single().id)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `原子写_成功后不留tmp残留`() = runBlocking {
        val (store, dir) = newStore()
        try {
            store.save(fixture())
            assertTrue(store.file.isFile)
            assertFalse("tmp 文件不应残留", File(dir, ChatHistoryStore.FILE_NAME + ".tmp").exists())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `并发保存_最终文件仍是合法完整JSON`() = runBlocking {
        val (store, dir) = newStore()
        try {
            (1..16).map { n ->
                async {
                    store.save(listOf(ChatItem.UserMessage(id = "c$n", ts = n.toLong(), text = "并发$n")))
                }
            }.awaitAll()
            val loaded = store.load()
            assertEquals(1, loaded.size)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `clear_删除文件`() = runBlocking {
        val (store, dir) = newStore()
        try {
            store.save(fixture())
            assertTrue(store.file.isFile)
            store.clear()
            assertEquals(emptyList<ChatItem>(), store.load())
        } finally {
            dir.deleteRecursively()
        }
    }
}
