package com.androidguru.agent.core.session

import java.util.concurrent.ConcurrentHashMap

/**
 * 会话。
 *
 * 一个会话 = 一份独立记忆；引擎由宿主按需创建（多会话 = 多引擎实例共享注册表）。
 */
class AgentSession(
    val id: String,
    val memory: ConversationMemory,
) {
    @Volatile
    var lastActiveAtMs: Long = System.currentTimeMillis()
        internal set

    fun touch() {
        lastActiveAtMs = System.currentTimeMillis()
    }
}

/**
 * 会话管理器：创建 / 复用 / 淘汰会话。
 *
 * 宿主接入多轮对话的推荐姿势：
 *
 * ```kotlin
 * val session = sessions.getOrCreate(chatId)
 * val engine = engineFactory(session)   // 宿主实现：绑定 session.memory
 * engine.execute(UserInput(text)).collect { ... }
 * ```
 */
class SessionManager(
    private val memoryFactory: () -> ConversationMemory = { InMemoryConversationMemory() },
    private val maxSessions: Int = 64,
    /** 会话空闲回收时间。 */
    private val ttlMs: Long = 30 * 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val sessions = ConcurrentHashMap<String, AgentSession>()

    fun getOrCreate(sessionId: String): AgentSession {
        val session = sessions.computeIfAbsent(sessionId) { AgentSession(it, memoryFactory()) }
        session.touch()
        evictExpired()
        return session
    }

    operator fun get(sessionId: String): AgentSession? = sessions[sessionId]

    fun remove(sessionId: String): Boolean = sessions.remove(sessionId) != null

    fun activeCount(): Int = sessions.size

    fun allSessionIds(): Set<String> = sessions.keys.toSet()

    /** 淘汰过期会话；超容量时淘汰最久未活跃。 */
    fun evictExpired() {
        val now = clock()
        sessions.entries.removeIf { now - it.value.lastActiveAtMs > ttlMs }
        if (sessions.size > maxSessions) {
            sessions.entries
                .sortedBy { it.value.lastActiveAtMs }
                .take(sessions.size - maxSessions)
                .forEach { sessions.remove(it.key) }
        }
    }
}
