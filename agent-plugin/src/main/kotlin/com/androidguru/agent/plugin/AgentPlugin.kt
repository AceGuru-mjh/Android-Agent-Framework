package com.androidguru.agent.plugin

/**
 * 插件契约常量（单一事实源）。
 */
object PluginContract {
    /**
     * 宿主 API 版本 —— 只增不减。
     * 插件声明 [AgentPlugin.minHostApi]，宿主版本低于它时拒绝加载（向前兼容门）。
     */
    const val HOST_API_VERSION: Int = 1
}

/** 插件描述符。 */
data class PluginDescriptor(
    /** 插件 id（[a-z0-9_]，同时作为工具命名空间前缀）。 */
    val id: String,
    val name: String,
    val version: String,
    val vendor: String = "",
    val description: String = "",
) {
    val idValid: Boolean
        get() = id.isNotBlank() && id.all { it in 'a'..'z' || it in '0'..'9' || it == '_' }
}

/**
 * 插件接口 —— **全新重做**的插件体系。
 *
 * 与原仓库 Android-Guru-Agent 的取舍：
 *
 * | | 原仓库（AIDL 跨进程） | 本框架（ServiceLoader） |
 * |---|---|---|
 * | 隔离 | 进程级隔离 | 类加载器 / 宿主层隔离 |
 * | 依赖 | Android Binder | 零平台依赖，JVM 通用 |
 * | 安全 | 签名门 + 版本门 | **保留版本门 + 风险钳制**（信任门语义不降级） |
 * | 接入 | 独立 APK Service | `META-INF/services` 或编程式注册 |
 *
 * Android 宿主如需跨进程隔离，可在宿主层包装远程插件协议，本契约不变。
 */
interface AgentPlugin {

    val descriptor: PluginDescriptor

    /** 声明兼容的最低宿主 API 版本。 */
    val minHostApi: Int get() = PluginContract.HOST_API_VERSION

    /**
     * 创建本插件提供的工具。
     *
     * 命名空间纪律：工具 id 无需手动加前缀，宿主会自动映射为
     * `{pluginId}_{toolId}`，避免跨插件冲突。
     *
     * [hostBridge] 可反向调用宿主能力（见 [HostBridge]）。
     */
    fun createTools(hostBridge: HostBridge): List<com.androidguru.agent.tools.AgentTool>

    /** 激活回调（信任门全部通过后调用）。 */
    fun onActivate() {}

    /** 停用回调。 */
    fun onDeactivate() {}
}
