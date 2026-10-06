# 插件 SDK 指南

## 设计定位（对比原仓库 Android-Guru-Agent 的重做说明）

| | 原仓库方案（AIDL） | 本框架方案（ServiceLoader） |
|---|---|---|
| 进程模型 | 插件为独立 APK + Service，Binder 跨进程 | 同进程类加载，宿主可再包装隔离 |
| 平台依赖 | Android Binder / PackageManager | **零平台依赖**，任意 JVM 可用 |
| 发现方式 | Intent action + PackageManager 查询 | `META-INF/services` ServiceLoader + 编程式注册 |
| 安全门 | 签名指纹门 + 版本门 + 元数据钳制 | **版本门 + 描述符门 + 风险钳制**（语义不降级） |
| 降级策略 | 插件死亡 → HostFallbackTool | 插件卸载 → 精确注销，注册表其余部分不受影响 |

保留的核心思想：**跨边界代码永远保守对待** —— 插件工具一律 MEDIUM 以上风险、
只读提示强制关闭，不给「PLAN 模式只读静默放行」通道留口子。

## 写一个插件

```kotlin
class TranslationPlugin : AgentPlugin {
    override val descriptor = PluginDescriptor(
        id = "translate",            // [a-z0-9_]，同时作为工具命名空间
        name = "Translation Plugin",
        version = "1.0.0",
        vendor = "your-org",
    )

    override fun createTools(hostBridge: HostBridge): List<AgentTool> = listOf(
        object : AgentTool {
            override val id = "translate_text"
            override val description = "翻译文本到目标语言"
            override val parameters = ToolSchema.build {
                string("text", required = true)
                string("target", "目标语言", required = true)
            }
            override suspend fun execute(request: ToolRequest): ToolResult {
                // ...调用翻译能力，或经 hostBridge 调宿主能力
                return ToolResult.success("translated text")
            }
        },
    )
}
```

### 注册到 META-INF/services

`src/main/resources/META-INF/services/com.androidguru.agent.plugin.AgentPlugin`：

```
com.yourorg.TranslationPlugin
```

宿主侧一行加载：

```kotlin
val host = PluginHost(toolRegistry)
host.loadFromClasspath()
```

## 信任门行为

| 检查 | 条件 | 结果 |
|---|---|---|
| API 版本门 | `plugin.minHostApi > PluginContract.HOST_API_VERSION` | 拒绝加载 |
| 描述符门 | id 非 `[a-z0-9_]` 或重复注册 | 拒绝加载 |
| 风险钳制 | 一律生效 | `risk = max(声明, MEDIUM)`，`readOnlyHint = false` |
| 命名空间 | 自动生效 | 工具 id 映射为 `{pluginId}_{toolId}`，防碰撞 |

## 宿主能力桥（HostBridge）

插件可以反向调用宿主注册的能力（持久化、受控网络、平台功能等）：

```kotlin
// 宿主侧
val bridge = DefaultHostBridge()
bridge.register("storage_put") { args -> kvStore.put(args).let { "{\"ok\":true}" } }
val host = PluginHost(registry, bridge)

// 插件侧
val resp = hostBridge.invokeCapability("storage_put", """{"key":"k1","value":"v1"}""")
```

## 生命周期

```kotlin
host.registerPlugin(plugin)            // 编程式注册
host.loadFromClasspath()               // 或 ServiceLoader 发现
host.unregisterPlugin("translate")     // 精确卸载（只注销本插件工具）
host.unloadAll()                       // 全部卸载
host.snapshot()                        // 当前加载状态
```

## Android 宿主接入建议

本 SDK 为纯 JVM；Android 宿主可以：
1. 直接 classpath 加载（单进程方案，最简单）；
2. 参考原仓库 Android-Guru-Agent 的 AIDL 跨进程方案做隔离 —— 在宿主层把远程插件
   适配为本框架 `AgentPlugin` 接口，信任门语义保持一致。
