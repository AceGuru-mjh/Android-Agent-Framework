package com.androidguru.agent.plugin

import com.androidguru.agent.tools.AgentTool
import com.androidguru.agent.tools.DuplicateToolIdPolicy
import com.androidguru.agent.tools.ToolMetadata
import com.androidguru.agent.tools.ToolRegistry
import com.androidguru.agent.tools.ToolRequest
import com.androidguru.agent.tools.ToolRisk
import java.util.ServiceLoader

/** 插件加载结果。 */
data class LoadedPlugin(
    val plugin: AgentPlugin,
    /** 经命名空间映射后注册进注册表的工具 id。 */
    val registeredToolIds: List<String>,
    /** 被信任门拒绝的工具 id（原始 id）。 */
    val rejectedToolIds: List<String>,
    val rejectionReasons: List<String> = emptyList(),
)

/**
 * 插件宿主：发现 + 信任门 + 工具注册。
 *
 * 三道信任门（全部 fail-closed，继承原仓库跨进程插件的安全范式）：
 * 1. **API 版本门**：`plugin.minHostApi > PluginContract.HOST_API_VERSION` → 拒绝加载；
 * 2. **描述符门**：插件 id 必须为 [a-z0-9_] 且全局唯一；
 * 3. **风险钳制**：插件工具风险 = max(声明, MEDIUM)，`readOnlyHint` 强制 false
 *    —— 跨边界代码永远保守对待，不给只读静默放行通道留口子。
 *
 * 工具命名空间：注册进注册表时自动映射为 `{pluginId}_{toolId}`，防跨插件碰撞。
 */
class PluginHost(
    private val toolRegistry: ToolRegistry,
    private val hostBridge: HostBridge = DefaultHostBridge(),
) {

    private val lock = Any()
    private val active = LinkedHashMap<String, LoadedPlugin>()

    /** 当前已加载插件（快照）。 */
    fun snapshot(): List<LoadedPlugin> = synchronized(lock) { active.values.toList() }

    /**
     * 注册一个插件实例（编程式接入）。
     * 返回 null 表示被信任门拒绝（描述符 / API 版本问题）。
     */
    fun registerPlugin(plugin: AgentPlugin, duplicatePolicy: DuplicateToolIdPolicy = DuplicateToolIdPolicy.REPLACE): LoadedPlugin? {
        val descriptor = plugin.descriptor

        // 信任门 1：API 版本
        if (plugin.minHostApi > PluginContract.HOST_API_VERSION) {
            return null
        }
        // 信任门 2：描述符
        if (!descriptor.idValid) {
            return null
        }
        synchronized(lock) {
            if (active.containsKey(descriptor.id)) return null
        }

        // 创建工具 → 命名空间映射 + 风险钳制
        val tools = plugin.createTools(hostBridge)
        val registered = mutableListOf<String>()
        val rejected = mutableListOf<String>()
        for (tool in tools) {
            val effectiveId = namespacedId(descriptor.id, tool.id)
            if (!tool.id.isNotBlank()) {
                rejected += tool.id
                continue
            }
            val clamped = tool.metadata.copy(
                id = effectiveId,
                risk = maxOf(tool.metadata.risk, ToolRisk.MEDIUM),
                annotations = tool.metadata.annotations.copy(readOnlyHint = false),
            )
            val namespacedTool = NamespacedTool(tool, effectiveId, clamped)
            val previous = toolRegistry.register(namespacedTool, duplicatePolicy)
            if (previous != null || duplicatePolicy == DuplicateToolIdPolicy.REPLACE) {
                registered += effectiveId
            } else {
                rejected += tool.id // REJECT 策略下 id 冲突
            }
        }

        plugin.onActivate()
        val loaded = LoadedPlugin(plugin, registered, rejected)
        synchronized(lock) { active[descriptor.id] = loaded }
        return loaded
    }

    /** 卸载插件并注销其全部工具。 */
    fun unregisterPlugin(pluginId: String): Boolean {
        val loaded = synchronized(lock) { active.remove(pluginId) } ?: return false
        loaded.registeredToolIds.forEach { toolRegistry.unregister(it) }
        runCatching { loaded.plugin.onDeactivate() }
        return true
    }

    /** 卸载全部插件。 */
    fun unloadAll() {
        val ids = synchronized(lock) { active.keys.toList() }
        ids.forEach { unregisterPlugin(it) }
    }

    /**
     * ServiceLoader 发现：从 classpath 读取 `META-INF/services/com.androidguru.agent.plugin.AgentPlugin`
     * 并注册全部合法插件。
     */
    fun loadFromClasspath(classLoader: ClassLoader = Thread.currentThread().contextClassLoader): List<LoadedPlugin> {
        val results = mutableListOf<LoadedPlugin>()
        val loader = ServiceLoader.load(AgentPlugin::class.java, classLoader)
        for (plugin in loader) {
            val result = registerPlugin(plugin)
            if (result != null) results += result
        }
        return results
    }

    private fun namespacedId(pluginId: String, toolId: String): String =
        if (toolId.startsWith("${pluginId}_")) toolId else "${pluginId}_$toolId"

    /** 命名空间包装：id / name / metadata 换为宿主视角，执行委托给原工具。 */
    private class NamespacedTool(
        private val delegate: AgentTool,
        override val id: String,
        override val metadata: ToolMetadata,
    ) : AgentTool {
        override val name: String get() = id
        override val description: String get() = delegate.description
        override val parameters get() = delegate.parameters

        override suspend fun execute(request: ToolRequest): com.androidguru.agent.tools.ToolResult =
            delegate.execute(request)
    }
}
