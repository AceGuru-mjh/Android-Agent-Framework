package com.androidguru.agent.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files

/**
 * 记忆存储测试：append 分配 id / update / remove / clear / 文件持久化 /
 * 损坏行容错 / 容量淘汰 / id 续编。
 */
class MemoryStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun record(
        content: String = "用户偏好使用 Kotlin",
        kind: MemoryKind = MemoryKind.PREFERENCE,
        importance: Int = 60,
    ) = MemoryRecord(id = "", kind = kind, content = content, importance = importance)

    // ------------------------------------------------------------------
    // InMemory
    // ------------------------------------------------------------------

    @Test
    fun `append 分配稳定 id 且可寻址`() {
        val store = InMemoryMemoryStore()
        val r1 = store.append(record("事实一"))
        val r2 = store.append(record("事实二"))

        assertTrue(r1.id.isNotBlank())
        assertTrue(r2.id.isNotBlank())
        assertEquals(r1, store.all().first { it.id == r1.id })
    }

    @Test
    fun `update 与 remove 语义正确`() {
        val store = InMemoryMemoryStore()
        val r = store.append(record())
        assertTrue(store.update(r.copy(importance = 90)))
        assertEquals(90, store.all().single().importance)

        assertFalse(store.update(record("x").copy(id = "m999")))
        assertTrue(store.remove(r.id))
        assertFalse(store.remove(r.id))
        assertTrue(store.all().isEmpty())
    }

    @Test
    fun `空内容与越界重要度被拒绝`() {
        try {
            record("  ")
            throw AssertionError("空白内容应当被拒绝")
        } catch (e: IllegalArgumentException) {
            // 预期
        }
        try {
            record(importance = 101)
            throw AssertionError("重要度 101 应当被拒绝")
        } catch (e: IllegalArgumentException) {
            // 预期
        }
    }

    @Test
    fun `clear 清空全部`() {
        val store = InMemoryMemoryStore()
        store.append(record("a"))
        store.append(record("b"))
        store.clear()
        assertTrue(store.all().isEmpty())
    }

    // ------------------------------------------------------------------
    // File
    // ------------------------------------------------------------------

    @Test
    fun `文件存储追加即持久（新实例可见）`() {
        val file = tmp.newFolder().toPath().resolve("memory.jsonl")
        val store = FileMemoryStore(file)
        store.append(record("用户叫小明"))
        store.append(record("喜欢深色主题", kind = MemoryKind.PREFERENCE))

        // 新实例（模拟进程重启）完整恢复
        val reloaded = FileMemoryStore(file)
        assertEquals(2, reloaded.all().size)
        assertTrue(reloaded.all().any { it.content == "用户叫小明" })
        assertTrue(reloaded.all().all { it.id.isNotBlank() })
    }

    @Test
    fun `损坏行容错跳过且不致命`() {
        val file = tmp.newFolder().toPath().resolve("memory.jsonl")
        val store = FileMemoryStore(file)
        store.append(record("完好记忆"))

        Files.write(file, listOf("not a json line", "{\"id\":\"m9\"}"), java.nio.charset.StandardCharsets.UTF_8)

        val reloaded = FileMemoryStore(file)
        assertEquals(0, reloaded.all().size) // 损坏 / 缺字段行全部跳过
        assertEquals(2, reloaded.skippedCorruptLines)
    }

    @Test
    fun `update 与 remove 原子重写落盘`() {
        val file = tmp.newFolder().toPath().resolve("memory.jsonl")
        val store = FileMemoryStore(file)
        val a = store.append(record("a"))
        store.append(record("b"))

        assertTrue(store.update(a.copy(importance = 95)))
        assertTrue(store.remove(a.id))

        val reloaded = FileMemoryStore(file)
        assertEquals(1, reloaded.all().size)
        assertEquals("b", reloaded.all().single().content)
    }

    @Test
    fun `容量满时淘汰低重要度记忆`() {
        val file = tmp.newFolder().toPath().resolve("memory.jsonl")
        val store = FileMemoryStore(file, maxRecords = 2)
        store.append(record("低价值", importance = 10))
        store.append(record("高价值", importance = 90))
        store.append(record("中价值", importance = 50)) // 触发淘汰

        val all = store.all()
        assertEquals(2, all.size)
        assertTrue(all.none { it.content == "低价值" }) // 最低重要度被牺牲
        val reloaded = FileMemoryStore(file)
        assertEquals(2, reloaded.all().size)
    }

    @Test
    fun `id 续编不与既有 id 冲突`() {
        val file = tmp.newFolder().toPath().resolve("memory.jsonl")
        val store = FileMemoryStore(file)
        store.append(record("first")) // m1

        val reloaded = FileMemoryStore(file) // 加载 m1，水位线 = 1
        val next = reloaded.append(record("second"))
        val ids = reloaded.all().map { it.id }
        assertEquals(2, ids.size)
        assertEquals(2, ids.toSet().size) // 无重复
        assertTrue(next.id != "m1")
    }

    @Test
    fun `codec 往返保真`() {
        val r = MemoryRecord(
            id = "m1",
            kind = MemoryKind.LESSON,
            content = "PTY 下哨兵要行首锚定",
            importance = 75,
            sessionId = "s-1",
            tags = listOf("shell", "pty"),
            createdAtMs = 100L,
            lastAccessedAtMs = 200L,
            accessCount = 3,
        )
        val decoded = MemoryCodec.decode(MemoryCodec.encode(r))
        assertNotNull(decoded)
        assertEquals(r, decoded)
    }

    @Test
    fun `宽容解码钳制越界重要度`() {
        val obj = MemoryCodec.encode(record("x", importance = 60)).let {
            kotlinx.serialization.json.Json.parseToJsonElement(it.toString()).let { el ->
                kotlinx.serialization.json.JsonObject((el as kotlinx.serialization.json.JsonObject).toMutableMap().apply { put("importance", kotlinx.serialization.json.JsonPrimitive(999)) })
            }
        }
        val decoded = MemoryCodec.decode(obj)!!
        assertEquals(100, decoded.importance)
    }
}
