package com.androidguru.agent.shelltools.tools

import com.androidguru.agent.shelltools.DeviceTools
import com.androidguru.agent.shelltools.JsonArgs
import com.androidguru.agent.shelltools.ShellToolSet
import com.androidguru.agent.tools.AgentTool
import com.androidguru.agent.tools.ToolCategory
import com.androidguru.agent.tools.ToolErrorCode
import com.androidguru.agent.tools.ToolMetadata
import com.androidguru.agent.tools.ToolRequest
import com.androidguru.agent.tools.ToolResult
import com.androidguru.agent.tools.ToolSchema

/**
 * 平台能力六件套：app_list / app_launch / open_url / device_info /
 * clipboard_read / clipboard_write。
 *
 * 全部通过 [DeviceTools] SPI 转发 —— Android 宿主注入真实现，其它平台用
 * [DeviceTools.NONE]（工具返回结构化「平台不支持」，模型自动跳过）。
 */
object DeviceFeatureTools {

    fun appList(device: DeviceTools): AgentTool = object : AgentTool {
        override val id = ShellToolSet.ToolIds.APP_LIST
        override val description =
            "列出设备上已安装的应用（可按关键词过滤）。需要读取应用列表能力。"
        override val parameters = ToolSchema.build {
            string("keyword", "按名称或包名过滤的关键词")
            boolean("include_system", "是否包含系统应用（默认 false）")
        }
        override val metadata = ToolMetadata(id = id, category = ToolCategory.UI)

        override suspend fun execute(request: ToolRequest): ToolResult {
            val args = JsonArgs.parse(request.arguments)
            val apps = device.listApps(args.str("keyword"), args.bool("include_system") ?: false)
            if (apps.isEmpty()) {
                return ToolResult.failure("宿主平台未提供应用列表能力", ToolErrorCode.UNAVAILABLE)
            }
            val text = apps.take(120).joinToString("\n") { "· ${it.label} (${it.packageName})" }
            return ToolResult.success("共 ${apps.size} 个应用：\n$text")
        }
    }

    fun appLaunch(device: DeviceTools): AgentTool = object : AgentTool {
        override val id = ShellToolSet.ToolIds.APP_LAUNCH
        override val description = "打开一个应用（按名称模糊匹配或包名精确匹配）。"
        override val parameters = ToolSchema.build {
            string("target", "应用名或包名", required = true)
        }
        override val metadata = ToolMetadata(id = id, category = ToolCategory.UI)

        override suspend fun execute(request: ToolRequest): ToolResult {
            val args = JsonArgs.parse(request.arguments)
            val target = args.str("target") ?: return ToolResult.invalid("target", "缺少 target")
            return when (val r = device.launchApp(target)) {
                is DeviceTools.AppLaunchResult.Launched ->
                    ToolResult.success("已打开：${r.label}（${r.packageName}）")
                is DeviceTools.AppLaunchResult.NotFound ->
                    ToolResult.failure("没有找到匹配「${r.target}」的应用", ToolErrorCode.NOT_FOUND)
                is DeviceTools.AppLaunchResult.Failed ->
                    ToolResult.failure(r.reason, ToolErrorCode.UNAVAILABLE)
            }
        }
    }

    fun openUrl(device: DeviceTools): AgentTool = object : AgentTool {
        override val id = ShellToolSet.ToolIds.OPEN_URL
        override val description = "用系统默认应用打开一个 URL。"
        override val parameters = ToolSchema.build {
            string("url", "要打开的 URL", required = true)
        }
        override val metadata = ToolMetadata(id = id, category = ToolCategory.UI)

        override suspend fun execute(request: ToolRequest): ToolResult {
            val args = JsonArgs.parse(request.arguments)
            val url = args.str("url") ?: return ToolResult.invalid("url", "缺少 url")
            return if (device.openUrl(url)) {
                ToolResult.success("已用系统默认应用打开：$url")
            } else {
                ToolResult.failure("宿主平台无法打开 URL", ToolErrorCode.UNAVAILABLE)
            }
        }
    }

    fun deviceInfo(device: DeviceTools): AgentTool = object : AgentTool {
        override val id = ShellToolSet.ToolIds.DEVICE_INFO
        override val description = "读取设备信息（型号 / 系统 / 内存 / 存储 / 电量）。"
        override val parameters = ToolSchema.empty()
        override val metadata = ToolMetadata(
            id = id, category = ToolCategory.SYSTEM,
            annotations = com.androidguru.agent.tools.ToolAnnotations(readOnlyHint = true),
        )

        override suspend fun execute(request: ToolRequest): ToolResult {
            val info = device.deviceInfo()
                ?: return ToolResult.failure("宿主平台未提供设备信息能力", ToolErrorCode.UNAVAILABLE)
            return ToolResult.success(info)
        }
    }

    fun clipboardRead(device: DeviceTools): AgentTool = object : AgentTool {
        override val id = ShellToolSet.ToolIds.CLIPBOARD_READ
        override val description = "读取剪贴板文本（上限 8000 字符）。"
        override val parameters = ToolSchema.empty()
        override val metadata = ToolMetadata(
            id = id, category = ToolCategory.SYSTEM,
            annotations = com.androidguru.agent.tools.ToolAnnotations(readOnlyHint = true),
        )

        override suspend fun execute(request: ToolRequest): ToolResult {
            val text = device.clipboardRead()
                ?: return ToolResult.failure(
                    "剪贴板为空或宿主平台未提供剪贴板能力",
                    ToolErrorCode.UNAVAILABLE,
                )
            return ToolResult.success(text.take(8000))
        }
    }

    fun clipboardWrite(device: DeviceTools): AgentTool = object : AgentTool {
        override val id = ShellToolSet.ToolIds.CLIPBOARD_WRITE
        override val description = "写入剪贴板文本。"
        override val parameters = ToolSchema.build {
            string("text", "要写入的文本", required = true)
        }
        override val metadata = ToolMetadata(id = id, category = ToolCategory.SYSTEM)

        override suspend fun execute(request: ToolRequest): ToolResult {
            val args = JsonArgs.parse(request.arguments)
            val text = args.str("text") ?: return ToolResult.invalid("text", "缺少 text")
            return if (device.clipboardWrite(text)) {
                ToolResult.success("已写入剪贴板（${text.length} 字符）")
            } else {
                ToolResult.failure("宿主平台无法写入剪贴板", ToolErrorCode.UNAVAILABLE)
            }
        }
    }
}
