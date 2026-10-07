package com.androidguru.agent.workflow

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * `${...}` 模板解析器 —— 参数化子程序的「形参绑定」机制。
 *
 * 模板根（对齐 Mobile-Agent-E Shortcut 的 arguments_map「透传 or 常量」语义）：
 * - `${params.<name>}`：形参透传；
 * - `${vars.<name>}`：运行变量（SetVar 写入 / 常量字面量）；
 * - `${nodes.<id>.output[.path...]}`：上游节点输出（可继续下钻 JSON 路径）。
 *
 * 规则：
 * - 字符串里可混排多个模板与常量文本（`"报告-${params.name}-${vars.date}"`）；
 * - 解析为 JsonPrimitive → 内联其文本；对象 / 数组 → JSON 序列化内联
 *   （整串单一模板且解析值为对象时即「原样传递」）；
 * - 解析不到 / 语法非法 → [TemplateResolutionException]（节点级 VALIDATION
 *   失败，**不重试** —— 换参数才有救，重试没有意义）。
 */
object TemplateResolver {

    class TemplateResolutionException(message: String) : Exception(message)

    private val PATTERN = Regex("\\$\\{([^}]*)}")

    /** 解析模板串（叶子级）。 */
    fun resolve(template: String, context: ResolveContext): String {
        if (!template.contains("\${")) return template
        val result = PATTERN.replace(template) { match ->
            val path = match.groupValues[1].trim()
            if (path.isEmpty()) throw TemplateResolutionException("空模板路径")
            renderElement(context.lookup(path), path)
        }
        return result
    }

    /** 递归解析参数对象里全部字符串叶子（Tool 节点 arguments）。 */
    fun resolveJsonArgs(args: JsonObject, context: ResolveContext): JsonObject =
        JsonObject(args.mapValues { (key, value) ->
            when (value) {
                is JsonPrimitive -> if (value.isStringLeaf()) {
                    JsonPrimitive(resolve(value.content, context))
                } else {
                    value
                }
                is JsonObject -> resolveJsonArgs(value, context)
                is JsonArray -> JsonArray(value.map { el ->
                    when (el) {
                        is JsonPrimitive -> if (el.isStringLeaf()) JsonPrimitive(resolve(el.content, context)) else el
                        is JsonObject -> resolveJsonArgs(el, context)
                        else -> el
                    }
                })
                else -> value
            }
        })

    private fun JsonPrimitive.isStringLeaf(): Boolean = content is String

    private fun renderElement(element: JsonElement?, path: String): String {
        if (element == null || element is JsonNull) {
            throw TemplateResolutionException("模板路径「$path」解析不到值（节点未执行或无输出）")
        }
        return when (element) {
            is JsonPrimitive -> element.content
            else -> element.toString()
        }
    }
}

/**
 * 模板寻址上下文（一次超步共享只读快照 —— 决策只依赖持久化状态的 Temporal 纪律）。
 */
class ResolveContext(
    val params: JsonObject,
    val vars: JsonObject,
    val nodeOutputs: Map<String, JsonElement?>,
) {
    private val rootNode = JsonObject(
        buildMap {
            put("params", params)
            put("vars", vars)
            // nodes.<id>.output —— 每节点一个 {output: JsonElement} 壳，output 为对象时可继续下钻
            put(
                "nodes",
                JsonObject(
                    nodeOutputs.mapValues { (_, output) ->
                        JsonObject(mapOf("output" to (output ?: kotlinx.serialization.json.JsonNull)))
                    },
                ),
            )
        },
    )

    /** 按点分路径寻址（params.x / vars.y / nodes.n1.output / nodes.n1.output.a.b）。 */
    fun lookup(path: String): JsonElement? {
        var current: JsonElement = rootNode
        for (segment in path.split('.')) {
            if (segment.isEmpty()) throw TemplateResolver.TemplateResolutionException("模板路径「$path」含空段")
            val obj = current as? JsonObject
                ?: throw TemplateResolver.TemplateResolutionException(
                    "模板路径「$path」在段「$segment」处遇到非对象（值: $current）",
                )
            current = obj[segment] ?: throw TemplateResolver.TemplateResolutionException("模板路径「$path」不存在（段 $segment 缺失）")
        }
        return current
    }
}
