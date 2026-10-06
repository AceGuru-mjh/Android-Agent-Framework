package com.androidguru.agent.tools

/**
 * 工具注册表。
 *
 * 注册表是框架的「能力总线」：内置工具、MCP 远端工具、插件工具全部注册于此，
 * 引擎每轮从 [getAllTools] 生成模型工具清单。
 *
 * [registryVersion] 在结构变化时自增，调用方可据此做工具清单快照失效。
 */
interface ToolRegistry {

    /** 注册工具；同 id 已存在时按 [DuplicateToolIdPolicy.REJECT] 拒绝并返回 null。 */
    fun register(tool: AgentTool): AgentTool?

    /** 注册工具；返回被覆盖/拒绝的旧工具（REPLACE 返回旧工具，REJECT 冲突返回 null）。 */
    fun register(tool: AgentTool, duplicatePolicy: DuplicateToolIdPolicy): AgentTool?

    /** 注销工具，返回是否存在。 */
    fun unregister(toolId: String): Boolean

    fun getTool(toolId: String): AgentTool?

    /** 全部已注册工具（稳定排序：按 id）。 */
    fun getAllTools(): List<AgentTool>

    /** 注册表结构版本号：注册/注销都会自增。 */
    val registryVersion: Long

    /** 当前工具数量。 */
    val size: Int
}

enum class DuplicateToolIdPolicy {
    /** 覆盖旧工具（热注册语义，MCP 重连 / 插件重载使用）。 */
    REPLACE,

    /** 冲突即拒绝（启动期注册使用，便于尽早暴露 id 冲突）。 */
    REJECT,
}

/** 默认实现：synchronized map + 版本号自增。 */
class DefaultToolRegistry : ToolRegistry {

    private val lock = Any()
    private val tools = LinkedHashMap<String, AgentTool>()
    private var version: Long = 0L

    override val registryVersion: Long
        get() = synchronized(lock) { version }

    override val size: Int
        get() = synchronized(lock) { tools.size }

    override fun register(tool: AgentTool): AgentTool? = register(tool, DuplicateToolIdPolicy.REJECT)

    override fun register(tool: AgentTool, duplicatePolicy: DuplicateToolIdPolicy): AgentTool? = synchronized(lock) {
        val previous = tools[tool.id]
        if (previous != null && duplicatePolicy == DuplicateToolIdPolicy.REJECT) return null
        tools[tool.id] = tool
        version++
        previous
    }

    override fun unregister(toolId: String): Boolean = synchronized(lock) {
        val removed = tools.remove(toolId) != null
        if (removed) version++
        removed
    }

    override fun getTool(toolId: String): AgentTool? = synchronized(lock) { tools[toolId] }

    override fun getAllTools(): List<AgentTool> = synchronized(lock) {
        tools.values.sortedBy { it.id }
    }
}
