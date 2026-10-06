package com.androidguru.agent.plugin

import com.androidguru.agent.tools.AgentTool
import com.androidguru.agent.tools.DefaultToolExecutor
import com.androidguru.agent.tools.DefaultToolRegistry
import com.androidguru.agent.tools.DuplicateToolIdPolicy
import com.androidguru.agent.tools.ToolMetadata
import com.androidguru.agent.tools.ToolRequest
import com.androidguru.agent.tools.ToolResult
import com.androidguru.agent.tools.ToolRisk
import com.androidguru.agent.tools.ToolSchema
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class SimplePlugin(
    private val toolIds: List<String> = listOf("hello"),
    private val minApi: Int = PluginContract.HOST_API_VERSION,
    private val declaredRisk: ToolRisk = ToolRisk.LOW,
) : AgentPlugin {
    override val descriptor = PluginDescriptor(id = "simple", name = "Simple", version = "1.0")
    override val minHostApi: Int get() = minApi

    override fun createTools(hostBridge: HostBridge): List<AgentTool> = toolIds.map { id ->
        object : AgentTool {
            override val id = id
            override val description = "工具 $id"
            override val metadata = ToolMetadata(id = id, risk = declaredRisk)
            override suspend fun execute(request: ToolRequest): ToolResult = ToolResult.success("hi from $id")
        }
    }
}

class PluginHostTest {

    @Test
    fun `注册自动命名空间映射并执行`() = runTest {
        val registry = DefaultToolRegistry()
        val host = PluginHost(registry)
        val loaded = host.registerPlugin(SimplePlugin(listOf("hello")))!!

        assertEquals(listOf("simple_hello"), loaded.registeredToolIds)
        val tool = registry.getTool("simple_hello")!!
        assertEquals("simple_hello", tool.name)

        val executor = DefaultToolExecutor(registry)
        val result = executor.execute("simple_hello", "{}")
        assertTrue(result.ok)
        assertEquals("hi from hello", result.content)
    }

    @Test
    fun `风险钳制 - LOW 提升为 MEDIUM 且 readOnlyHint 强制 false`() = runTest {
        val registry = DefaultToolRegistry()
        val host = PluginHost(registry)
        host.registerPlugin(SimplePlugin(listOf("read_stuff"), declaredRisk = ToolRisk.LOW))

        val meta = registry.getTool("simple_read_stuff")!!.metadata
        assertTrue(meta.risk >= ToolRisk.MEDIUM)
        assertTrue(!meta.annotations.readOnlyHint)
    }

    @Test
    fun `信任门 - minHostApi 过新被拒绝`() {
        val registry = DefaultToolRegistry()
        val host = PluginHost(registry)
        val loaded = host.registerPlugin(SimplePlugin(minApi = PluginContract.HOST_API_VERSION + 1))
        assertNull(loaded)
        assertEquals(0, registry.size)
    }

    @Test
    fun `信任门 - 非法描述符被拒绝`() {
        val registry = DefaultToolRegistry()
        val host = PluginHost(registry)
        val badPlugin = object : AgentPlugin {
            override val descriptor = PluginDescriptor(id = "Bad-Id!", name = "x", version = "1")
            override fun createTools(hostBridge: HostBridge): List<AgentTool> = emptyList()
        }
        assertNull(host.registerPlugin(badPlugin))
    }

    @Test
    fun `重复注册同 id 插件被拒绝`() {
        val registry = DefaultToolRegistry()
        val host = PluginHost(registry)
        host.registerPlugin(SimplePlugin())
        assertNull(host.registerPlugin(SimplePlugin()))
    }

    @Test
    fun `卸载注销全部工具且不误伤他人`() = runTest {
        val registry = DefaultToolRegistry()
        val host = PluginHost(registry)
        host.registerPlugin(SimplePlugin(listOf("a", "b")))
        registry.register(object : AgentTool {
            override val id = "standalone"
            override val description = "非插件工具"
            override suspend fun execute(request: ToolRequest): ToolResult = ToolResult.success("ok")
        }, DuplicateToolIdPolicy.REPLACE)

        assertTrue(host.unregisterPlugin("simple"))
        assertEquals(1, registry.size)
        assertEquals("standalone", registry.getAllTools()[0].id)
        assertTrue(!host.unregisterPlugin("simple")) // 二次卸载返回 false
    }

    @Test
    fun `ServiceLoader 发现 - loadFromClasspath`() = runTest {
        val registry = DefaultToolRegistry()
        val host = PluginHost(registry)
        // classpath 上有 examples 模块声明的 DemoPlugin？—— 测试模块自己声明一个服务文件
        val loaded = host.loadFromClasspath(classLoader = this.javaClass.classLoader)
        // 本测试模块 META-INF/services 中声明了 ServiceLoaderTestPlugin
        assertTrue(loaded.isNotEmpty())
        assertTrue(registry.getTool("svcplugin_count") != null)
    }
}

/** 供 ServiceLoader 发现的测试插件。 */
class ServiceLoaderTestPlugin : AgentPlugin {
    override val descriptor = PluginDescriptor(id = "svcplugin", name = "Svc", version = "1.0")
    override fun createTools(hostBridge: HostBridge): List<AgentTool> = listOf(object : AgentTool {
        override val id = "count"
        override val description = "计数"
        override val parameters = ToolSchema.empty()
        override suspend fun execute(request: ToolRequest): ToolResult = ToolResult.success("1")
    })
}

class HostBridgeTest {

    @Test
    fun `能力注册与调用`() {
        val bridge = DefaultHostBridge()
        bridge.register("storage_get") { key -> """{"value":"v_$key"}""" }
        assertTrue(bridge.hasCapability("storage_get"))
        assertEquals("""{"value":"v_k1"}""", bridge.invokeCapability("storage_get", "k1"))
        assertTrue(!bridge.hasCapability("nope"))
        try {
            bridge.invokeCapability("nope", "")
            throw AssertionError("应当抛异常")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("nope"))
        }
    }
}
