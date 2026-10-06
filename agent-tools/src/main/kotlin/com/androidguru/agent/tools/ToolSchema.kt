package com.androidguru.agent.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * 声明式工具参数 Schema（DSL 构建）。
 *
 * ```kotlin
 * parameters = ToolSchema.build {
 *     string("path", "要读取的文件路径", required = true)
 *     integer("limit", "最多读取行数", required = false, minimum = 1.0, maximum = 1000.0)
 * }
 * ```
 *
 * 设计要点：[render] 生成的 JSON Schema 发给模型，[validate] 在执行前校验 ——
 * 模型看到的与执行器校验的是同一对象，永不漂移。
 *
 * 校验策略为「宽容」：只强制声明过的要求（required / type / enum / 边界），
 * 未声明的多余字段放行，避免与模型死循环重试。
 */
class ToolSchema private constructor(
    private val properties: LinkedHashMap<String, PropertySpec>,
    private val requiredKeys: Set<String>,
) {

    // ------------------------------------------------------------------
    // DSL
    // ------------------------------------------------------------------

    class ToolSchemaBuilder internal constructor() {
        internal val props = LinkedHashMap<String, PropertySpec>()
        internal val required = LinkedHashSet<String>()

        fun string(
            name: String,
            description: String = "",
            required: Boolean = false,
            enumValues: List<String> = emptyList(),
        ) = add(name, PropertySpec("string", description, enumValues = enumValues), required)

        fun integer(
            name: String,
            description: String = "",
            required: Boolean = false,
            minimum: Double? = null,
            maximum: Double? = null,
        ) = add(name, PropertySpec("integer", description, minimum, maximum), required)

        fun number(
            name: String,
            description: String = "",
            required: Boolean = false,
            minimum: Double? = null,
            maximum: Double? = null,
        ) = add(name, PropertySpec("number", description, minimum, maximum), required)

        fun boolean(name: String, description: String = "", required: Boolean = false) =
            add(name, PropertySpec("boolean", description), required)

        fun array(
            name: String,
            description: String = "",
            required: Boolean = false,
            itemType: String = "string",
        ) = add(name, PropertySpec("array", description, itemType = itemType), required)

        fun jsonObject(name: String, description: String = "", required: Boolean = false) =
            add(name, PropertySpec("object", description), required)

        private fun add(name: String, spec: PropertySpec, isRequired: Boolean) {
            require(name.isNotBlank()) { "schema property name must not be blank" }
            props[name] = spec
            if (isRequired) required += name
        }
    }

    internal data class PropertySpec(
        val type: String,
        val description: String,
        val minimum: Double? = null,
        val maximum: Double? = null,
        val enumValues: List<String> = emptyList(),
        val itemType: String? = null,
    )

    // ------------------------------------------------------------------
    // API
    // ------------------------------------------------------------------

    val propertyNames: Set<String> get() = properties.keys

    /** 校验参数 JSON 字符串，返回错误列表（空列表 = 通过）。 */
    fun validate(arguments: String): List<String> {
        val element = try {
            Json.parseToJsonElement(arguments.ifBlank { "{}" })
        } catch (e: Exception) {
            return listOf("arguments 不是合法 JSON: ${e.message}")
        }
        if (element !is JsonObject) return listOf("arguments 必须是 JSON 对象")

        val errors = mutableListOf<String>()
        for (key in requiredKeys) {
            if (element[key] == null || element[key] is JsonNull) errors += "缺少必填参数: $key"
        }
        for ((key, spec) in properties) {
            val value = element[key] ?: continue
            if (value is JsonNull) continue
            validateProperty(key, spec, value, errors)
        }
        return errors
    }

    private fun validateProperty(key: String, spec: PropertySpec, value: JsonElement, errors: MutableList<String>) {
        val prim = value as? JsonPrimitive
        when (spec.type) {
            "string" -> {
                if (prim == null) {
                    errors += "参数 $key 应为 string"
                    return
                }
                if (spec.enumValues.isNotEmpty()) {
                    val str = prim.content
                    if (str !in spec.enumValues) {
                        errors += "参数 $key 取值 \"$str\" 不在枚举 [${spec.enumValues.joinToString(", ")}] 内"
                    }
                }
            }

            "integer" -> {
                val num = prim?.longOrNull
                if (num == null) {
                    errors += "参数 $key 应为 integer"
                    return
                }
                checkRange(key, spec, num.toDouble(), errors)
            }

            "number" -> {
                val num = prim?.doubleOrNull
                if (num == null) {
                    errors += "参数 $key 应为 number"
                    return
                }
                checkRange(key, spec, num, errors)
            }

            "boolean" -> if (prim == null || prim.booleanOrNull == null) errors += "参数 $key 应为 boolean"

            "array" -> if (value !is JsonArray) errors += "参数 $key 应为 array"

            "object" -> if (value !is JsonObject) errors += "参数 $key 应为 object"
        }
    }

    private fun checkRange(key: String, spec: PropertySpec, value: Double, errors: MutableList<String>) {
        spec.minimum?.let { if (value < it) errors += "参数 $key 不得小于 ${it.toBigDecimal().stripTrailingZeros().toPlainString()}" }
        spec.maximum?.let { if (value > it) errors += "参数 $key 不得大于 ${it.toBigDecimal().stripTrailingZeros().toPlainString()}" }
    }

    /** 渲染为 JSON Schema（发给模型）。 */
    fun render(): JsonObject {
        val propsJson = LinkedHashMap<String, JsonElement>()
        for ((key, spec) in properties) {
            val propJson = LinkedHashMap<String, JsonElement>()
            propJson["type"] = JsonPrimitive(spec.type)
            if (spec.description.isNotBlank()) propJson["description"] = JsonPrimitive(spec.description)
            if (spec.minimum != null) propJson["minimum"] = JsonPrimitive(spec.minimum)
            if (spec.maximum != null) propJson["maximum"] = JsonPrimitive(spec.maximum)
            if (spec.enumValues.isNotEmpty()) {
                propJson["enum"] = JsonArray(spec.enumValues.map { JsonPrimitive(it) })
            }
            if (spec.itemType != null) {
                propJson["items"] = JsonObject(mapOf("type" to JsonPrimitive(spec.itemType)))
            }
            propsJson[key] = JsonObject(propJson)
        }
        val root = LinkedHashMap<String, JsonElement>()
        root["type"] = JsonPrimitive("object")
        root["properties"] = JsonObject(propsJson)
        if (requiredKeys.isNotEmpty()) {
            root["required"] = JsonArray(requiredKeys.map { JsonPrimitive(it) })
        }
        return JsonObject(root)
    }

    override fun toString(): String = "ToolSchema(properties=${properties.keys})"

    companion object {

        fun empty(): ToolSchema = ToolSchema(LinkedHashMap(), emptySet())

        /** DSL 构建。 */
        fun build(block: ToolSchemaBuilder.() -> Unit): ToolSchema {
            val builder = ToolSchemaBuilder()
            builder.block()
            return ToolSchema(LinkedHashMap(builder.props), LinkedHashSet(builder.required))
        }

        /**
         * 从已渲染的 JSON Schema 字符串宽松导入（供宿主导入第三方工具定义）。
         * 解析失败一律返回 [empty] —— 导入失败绝不误伤执行。
         */
        fun fromRendered(jsonSchema: String?): ToolSchema {
            if (jsonSchema.isNullOrBlank()) return empty()
            return try {
                val obj = Json.parseToJsonElement(jsonSchema) as? JsonObject ?: return empty()
                val props = LinkedHashMap<String, PropertySpec>()
                val required = LinkedHashSet<String>()
                val propsEl = obj["properties"] as? JsonObject
                propsEl?.forEach { (name, el) ->
                    val po = el as? JsonObject ?: return@forEach
                    val type = (po["type"] as? JsonPrimitive)?.content ?: "string"
                    val desc = (po["description"] as? JsonPrimitive)?.content ?: ""
                    val enumValues = (po["enum"] as? JsonArray)
                        ?.mapNotNull { (it as? JsonPrimitive)?.content }
                        ?: emptyList()
                    props[name] = PropertySpec(type = type, description = desc, enumValues = enumValues)
                }
                (obj["required"] as? JsonArray)?.forEach { el ->
                    (el as? JsonPrimitive)?.content?.let(required::add)
                }
                ToolSchema(props, required)
            } catch (e: Exception) {
                empty()
            }
        }
    }
}
