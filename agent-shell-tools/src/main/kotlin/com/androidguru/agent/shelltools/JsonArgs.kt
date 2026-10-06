package com.androidguru.agent.shelltools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/**
 * 参数 JSON 解析辅助 —— 工具与钩子共用的宽容解析器。
 *
 * 宽容策略与框架一致：缺字段给 null，调用方决定缺省值；解析失败一律 null 不抛
 * （执行管线的 schema 校验段已给出可读错误，这里只做静默兜底）。
 */
class JsonArgs(private val obj: JsonObject?) {

    fun str(key: String): String? = obj?.get(key)?.let {
        runCatching { (it as? JsonPrimitive)?.contentOrNull }.getOrNull()
    }

    fun strOrEmpty(key: String): String = str(key) ?: ""

    fun int(key: String): Int? = obj?.get(key)?.let {
        runCatching { (it as? JsonPrimitive)?.let { p -> p.intOrNull ?: p.contentOrNull?.toIntOrNull() } }.getOrNull()
    }

    fun long(key: String): Long? = obj?.get(key)?.let {
        runCatching { (it as? JsonPrimitive)?.let { p -> p.longOrNull ?: p.contentOrNull?.toLongOrNull() } }.getOrNull()
    }

    fun bool(key: String): Boolean? = obj?.get(key)?.let {
        runCatching { (it as? JsonPrimitive)?.let { p -> p.booleanOrNull ?: p.contentOrNull?.toBooleanStrictOrNull() } }.getOrNull()
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** 解析参数 JSON（非法输入返回空参数集）。 */
        fun parse(arguments: String): JsonArgs = JsonArgs(
            runCatching { json.parseToJsonElement(arguments.ifBlank { "{}" }).jsonObject }.getOrNull(),
        )
    }
}
