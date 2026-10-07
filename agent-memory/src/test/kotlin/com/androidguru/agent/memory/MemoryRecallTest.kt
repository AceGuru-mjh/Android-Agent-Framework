package com.androidguru.agent.memory

import com.androidguru.agent.core.session.InMemoryConversationMemory
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 召回测试：四因子评分 / 关键词覆盖 / 预算截断 / 过滤 / 访问回写冷却 / 渲染。
 */
class MemoryRecallTest {

    private var now = 1_000_000_000L
    private val clock = { now }

    private fun recall(store: MemoryStore = InMemoryMemoryStore()) =
        MemoryRecall(store, clock = clock, halfLifeDays = 14.0, accessCooldownMs = 1000L)

    private fun storeOf(vararg contents: String): MemoryStore {
        val store = InMemoryMemoryStore()
        contents.forEach { store.append(MemoryRecord(id = "", kind = MemoryKind.FACT, content = it)) }
        return store
    }

    @Test
    fun `重要度主导排序`() {
        val store = InMemoryMemoryStore()
        store.append(MemoryRecord(id = "", kind = MemoryKind.FACT, content = "低", importance = 10, createdAtMs = now))
        store.append(MemoryRecord(id = "", kind = MemoryKind.FACT, content = "高", importance = 95, createdAtMs = now))

        val result = recall(store).recall(MemoryQuery(limit = 2, minScore = 0.0))
        assertEquals(listOf("高", "低"), result.map { it.content })
    }

    @Test
    fun `新近度衰减生效`() {
        val r = MemoryRecall(storeOf(), clock = clock, halfLifeDays = 14.0)
        val fresh = MemoryRecord(id = "m1", kind = MemoryKind.FACT, content = "x", createdAtMs = now)
        val old = MemoryRecord(id = "m2", kind = MemoryKind.FACT, content = "x", createdAtMs = now - 28L * MemoryRecall.DAY_MS)

        // 28 天 = 两个半衰期 → 时间因子 1/4
        val freshScore = r.score(fresh, MemoryQuery(), now)
        val oldScore = r.score(old, MemoryQuery(), now)
        assertTrue("新记录应当得分更高: $freshScore vs $oldScore", freshScore > oldScore)
    }

    @Test
    fun `关键词命中显著提升得分`() {
        val r = recall()
        val hit = MemoryRecord(id = "m1", kind = MemoryKind.FACT, content = "用户叫小明，喜欢 Kotlin", createdAtMs = now)
        val miss = MemoryRecord(id = "m2", kind = MemoryKind.FACT, content = "项目使用 Gradle 构建", createdAtMs = now)

        val query = MemoryQuery(text = "用户叫什么名字？喜欢什么语言？")
        assertTrue(r.score(hit, query, now) > r.score(miss, query, now))
    }

    @Test
    fun `token 预算截断召回条数`() {
        val store = storeOf("很长的记忆内容".repeat(30), "另一条很长的记忆".repeat(30))
        val result = recall(store).recall(MemoryQuery(limit = 5, maxTokens = 30, minScore = 0.0))
        assertTrue(result.size < 2)
    }

    @Test
    fun `类别过滤`() {
        val s = InMemoryMemoryStore()
        s.append(MemoryRecord(id = "", kind = MemoryKind.PREFERENCE, content = "偏好深色主题"))
        s.append(MemoryRecord(id = "", kind = MemoryKind.LESSON, content = "PTY 哨兵要行首锚定"))

        val result = recall(s).recall(MemoryQuery(kinds = setOf(MemoryKind.LESSON), limit = 5, minScore = 0.0))
        assertEquals(1, result.size)
        assertEquals(MemoryKind.LESSON, result.single().kind)
    }

    @Test
    fun `访问回写受冷却约束`() {
        val store = storeOf("记忆 A")
        val r = recall(store)
        val before = store.all().single()

        r.recall(MemoryQuery(limit = 3, minScore = 0.0)) // 命中，回写
        val afterFirst = store.all().single()
        assertEquals(1, afterFirst.accessCount)
        assertEquals(now, afterFirst.lastAccessedAtMs)

        r.recall(MemoryQuery(limit = 3, minScore = 0.0)) // 冷却内（<1000ms）→ 不回写
        val afterSecond = store.all().single()
        assertEquals(1, afterSecond.accessCount)

        now += 2000L // 越过冷却
        r.recall(MemoryQuery(limit = 3, minScore = 0.0))
        val afterThird = store.all().single()
        assertEquals(2, afterThird.accessCount)
    }

    @Test
    fun `双语分词覆盖 CJK 与拉丁`() {
        val tokens = MemoryRecall.tokenize("用户 prefer Kotlin 语言")
        assertTrue(tokens.contains("kotlin"))
        assertTrue(tokens.contains("prefer"))
        assertTrue(tokens.contains("用户")) // CJK bigram
        assertTrue(tokens.contains("语言"))
    }

    @Test
    fun `渲染器输出标签与空态`() {
        assertEquals("", renderMemories(emptyList()))
        val rendered = renderMemories(
            listOf(
                MemoryRecord(id = "m1", kind = MemoryKind.PREFERENCE, content = "深色主题", importance = 80, tags = listOf("ui")),
            ),
        )
        assertTrue(rendered.contains("[偏好]"))
        assertTrue(rendered.contains("深色主题"))
        assertTrue(rendered.contains("标签: ui"))
    }
}

/**
 * 注入器测试：召回信号（最近用户消息）/ 空记忆返回空串 / 有记忆注入完整段落。
 */
class MemoryContextInjectorTest {

    @Test
    fun `空记忆返回空串`() = runTest {
        val injector = MemoryContextInjector(MemoryRecall(InMemoryMemoryStore()))
        assertEquals("", injector.provideContext("s1"))
    }

    @Test
    fun `用最近用户消息作召回信号`() = runTest {
        val store = InMemoryMemoryStore()
        store.append(MemoryRecord(id = "", kind = MemoryKind.PREFERENCE, content = "用户叫小明", importance = 90))
        val conversation = InMemoryConversationMemory()
        conversation.appendUser("我是谁？")

        val injector = MemoryContextInjector(MemoryRecall(store), conversation)
        val context = injector.provideContext("s1")

        assertTrue(context.contains("# 长期记忆"))
        assertTrue(context.contains("用户叫小明"))
        assertTrue(context.contains("memory_save"))
    }

    @Test
    fun `无会话时退化为重要度排名也注入`() = runTest {
        val store = InMemoryMemoryStore()
        store.append(MemoryRecord(id = "", kind = MemoryKind.FACT, content = "部署环境是 Termux", importance = 70))

        val injector = MemoryContextInjector(MemoryRecall(store))
        assertTrue(injector.provideContext("s1").contains("Termux"))
    }

    @Test
    fun `低分记忆被 minScore 过滤不注入`() = runTest {
        val store = InMemoryMemoryStore()
        store.append(
            MemoryRecord(
                id = "", kind = MemoryKind.SESSION_SUMMARY, content = "远古会话",
                importance = 1, createdAtMs = 1L, // 极老 + 极低重要度
            ),
        )
        val injector = MemoryContextInjector(MemoryRecall(store))
        assertEquals("", injector.provideContext("s1"))
    }
}
