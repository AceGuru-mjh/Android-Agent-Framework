package com.androidguru.agent.plugin

import java.util.concurrent.ConcurrentHashMap

/**
 * 宿主能力桥 —— 插件反向调用宿主能力（对应原仓库 IApexPluginHost 的重做版）。
 *
 * 典型能力：持久化存储、受控网络、宿主 UI 通知、平台传感器……
 * 插件只看到 [invokeCapability] 字符串协议，宿主保持对能力的完全控制。
 */
interface HostBridge {

    /** 是否注册了某能力。 */
    fun hasCapability(capabilityId: String): Boolean

    /**
     * 调用宿主能力。
     * @return 宿主返回的 JSON 字符串；能力不存在时抛 [IllegalStateException]。
     */
    fun invokeCapability(capabilityId: String, arguments: String): String
}

/** 默认实现：注册表 + 字符串协议。 */
class DefaultHostBridge : HostBridge {

    private val capabilities = ConcurrentHashMap<String, (String) -> String>()

    /** 注册能力处理器（宿主启动期完成，运行期只读）。 */
    fun register(capabilityId: String, handler: (String) -> String) {
        capabilities[capabilityId] = handler
    }

    fun unregister(capabilityId: String) {
        capabilities.remove(capabilityId)
    }

    fun capabilityIds(): Set<String> = capabilities.keys.toSet()

    override fun hasCapability(capabilityId: String): Boolean = capabilities.containsKey(capabilityId)

    override fun invokeCapability(capabilityId: String, arguments: String): String {
        val handler = capabilities[capabilityId]
            ?: throw IllegalStateException("宿主能力不存在: $capabilityId")
        return handler(arguments)
    }
}
