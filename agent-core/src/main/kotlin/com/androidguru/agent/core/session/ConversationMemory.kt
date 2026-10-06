package com.androidguru.agent.core.session

import com.androidguru.agent.llm.LlmMessage
import com.androidguru.agent.llm.ToolCall

/**
 * 会话记忆 —— 引擎构建消息列表的唯一来源。
 *
 * 实现可以是内存、文件、数据库；宿主也可在此基础上做历史恢复。
 */
interface ConversationMemory {

    /** 当前完整消息列表（只读视图）。 */
    fun snapshot(): List<LlmMessage>

    fun appendSystem(content: String)

    fun appendUser(text: String, images: List<com.androidguru.agent.llm.ImageContent> = emptyList())

    fun appendAssistant(content: String?, toolCalls: List<ToolCall> = emptyList())

    fun appendToolResult(toolCallId: String, content: String)

    /** 压缩后整体替换消息列表。 */
    fun replaceAll(messages: List<LlmMessage>)

    fun clear()
}

/** 线程安全的内存实现（框架默认）。 */
class InMemoryConversationMemory : ConversationMemory {

    private val lock = Any()
    private val messages = mutableListOf<LlmMessage>()

    override fun snapshot(): List<LlmMessage> = synchronized(lock) { messages.toList() }

    override fun appendSystem(content: String) = synchronized(lock) {
        messages.add(LlmMessage.System(content)); Unit
    }

    override fun appendUser(text: String, images: List<com.androidguru.agent.llm.ImageContent>) = synchronized(lock) {
        messages.add(LlmMessage.User(text, images)); Unit
    }

    override fun appendAssistant(content: String?, toolCalls: List<ToolCall>) = synchronized(lock) {
        messages.add(LlmMessage.Assistant(content = content, toolCalls = toolCalls)); Unit
    }

    override fun appendToolResult(toolCallId: String, content: String) = synchronized(lock) {
        messages.add(LlmMessage.Tool(toolCallId, content)); Unit
    }

    override fun replaceAll(newMessages: List<LlmMessage>) = synchronized(lock) {
        messages.clear()
        messages.addAll(newMessages); Unit
    }

    override fun clear() = synchronized(lock) { messages.clear(); Unit }
}
