package com.androidguru.agent.shelltools

import com.androidguru.agent.shell.terminal.AnsiStripper

/**
 * 平台设备能力 SPI —— yl-ai「手机能力工具」的去 Android 化抽象。
 *
 * yl-ai 里 app_list / app_launch / open_url / device_info / clipboard_* 直接调
 * PackageManager / ClipboardManager / Intent；框架是纯 JVM，这些能力通过本 SPI
 * 注入：
 * - **Android 宿主**：用 PackageManager / ClipboardManager / Intent / Build /
 *   BatteryManager 实现全部方法（约 100 行胶水）；
 * - **桌面 / 服务端宿主**：使用 [NONE]，对应工具返回「平台不支持」的结构化错误，
 *   模型会自动跳过（不会瞎试）。
 */
interface DeviceTools {

    data class AppEntry(val label: String, val packageName: String)

    sealed interface AppLaunchResult {
        data class Launched(val label: String, val packageName: String) : AppLaunchResult
        data class NotFound(val target: String) : AppLaunchResult
        data class Failed(val reason: String) : AppLaunchResult
    }

    fun clipboardRead(): String?

    fun clipboardWrite(text: String): Boolean

    fun listApps(keyword: String?, includeSystem: Boolean): List<AppEntry>

    fun launchApp(target: String): AppLaunchResult

    fun openUrl(url: String): Boolean

    /** 多行设备信息文本（型号 / 系统 / 内存 / 存储等）。 */
    fun deviceInfo(): String?

    companion object {

        /** 空实现：全部能力不可用（返回结构化失败，模型据此跳过）。 */
        val NONE: DeviceTools = object : DeviceTools {
            override fun clipboardRead(): String? = null
            override fun clipboardWrite(text: String): Boolean = false
            override fun listApps(keyword: String?, includeSystem: Boolean): List<AppEntry> = emptyList()
            override fun launchApp(target: String): AppLaunchResult =
                AppLaunchResult.Failed("宿主平台未提供应用列表能力")
            override fun openUrl(url: String): Boolean = false
            override fun deviceInfo(): String? = null
        }
    }
}

/** [DeviceTools] 附带的小工具：HTML 转纯文本（http_get 用，保留 yl-ai 的 stripHtml 语义）。 */
object HtmlStripper {

    fun strip(html: String): String = html
        .replace(Regex("(?is)<script[^>]*>.*?</script>"), "")
        .replace(Regex("(?is)<style[^>]*>.*?</style>"), "")
        .replace(Regex("(?is)<br\\s*/?>"), "\n")
        .replace(Regex("(?is)</(p|div|li|h[1-6]|tr)>"), "\n")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .let { AnsiStripper.clean(it) }
}
