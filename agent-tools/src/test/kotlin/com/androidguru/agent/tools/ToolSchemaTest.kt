package com.androidguru.agent.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolSchemaTest {

    @Test
    fun `render 输出合法 JSON Schema`() {
        val schema = ToolSchema.build {
            string("path", "文件路径", required = true)
            integer("limit", "行数上限", minimum = 1.0, maximum = 100.0)
            string("mode", "模式", enumValues = listOf("fast", "slow"))
        }
        val json = schema.render()
        assertEquals("object", (json["type"] as kotlinx.serialization.json.JsonPrimitive).content)
        val required = json["required"] as kotlinx.serialization.json.JsonArray
        assertEquals(1, required.size)
        assertEquals("path", (required[0] as kotlinx.serialization.json.JsonPrimitive).content)
    }

    @Test
    fun `校验必填缺失`() {
        val schema = ToolSchema.build {
            string("path", required = true)
        }
        val errors = schema.validate("{}")
        assertEquals(1, errors.size)
        assertTrue(errors[0].contains("path"))
    }

    @Test
    fun `校验类型错误`() {
        val schema = ToolSchema.build {
            integer("count", required = true)
        }
        assertFalse(schema.validate("""{"count": 3}""").isNotEmpty())
        assertTrue(schema.validate("""{"count": "abc"}""").isNotEmpty())
        assertTrue(schema.validate("""{"count": true}""").isNotEmpty())
    }

    @Test
    fun `校验数值边界`() {
        val schema = ToolSchema.build {
            integer("limit", minimum = 1.0, maximum = 10.0)
        }
        assertTrue(schema.validate("""{"limit": 0}""").isNotEmpty())
        assertTrue(schema.validate("""{"limit": 11}""").isNotEmpty())
        assertTrue(schema.validate("""{"limit": 5}""").isEmpty())
    }

    @Test
    fun `校验枚举取值`() {
        val schema = ToolSchema.build {
            string("size", enumValues = listOf("S", "M", "L"))
        }
        assertTrue(schema.validate("""{"size": "XL"}""").isNotEmpty())
        assertTrue(schema.validate("""{"size": "M"}""").isEmpty())
    }

    @Test
    fun `宽容策略 - 未声明的多余字段放行`() {
        val schema = ToolSchema.build {
            string("path", required = true)
        }
        assertTrue(schema.validate("""{"path": "a.txt", "extra": 123}""").isEmpty())
    }

    @Test
    fun `非法 JSON 返回错误而非抛异常`() {
        val schema = ToolSchema.build { string("a") }
        assertTrue(schema.validate("not-json").isNotEmpty())
    }

    @Test
    fun `fromRendered 宽松导入并保持校验能力`() {
        val schema = ToolSchema.build {
            string("path", required = true)
            integer("limit")
        }
        val imported = ToolSchema.fromRendered(schema.render().toString())
        assertTrue(imported.validate("""{"path": "x"}""").isEmpty())
        assertTrue(imported.validate("""{"limit": 1}""").isNotEmpty())
    }

    @Test
    fun `fromRendered 解析失败返回 empty 不误伤`() {
        val imported = ToolSchema.fromRendered("broken json")
        assertTrue(imported.propertyNames.isEmpty())
        assertTrue(imported.validate("{}").isEmpty())
    }
}
